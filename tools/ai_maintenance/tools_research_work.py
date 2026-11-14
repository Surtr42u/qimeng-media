"""作品研究入口：只给一张图或一段视频即可开始只读研究（research.work）。

设计（S1，2026-11-14）：
- 输入只要求 path/fileRef/artifactRef 三选一；nameHint、authorId、assetId、
  profileChanges 都不是前提，不能为了凑配方补造作者名。
- 研究阶段零业务写入：只读媒体、复用 media_primitives 的缓存抽帧/dHash，
  抽帧写工作根并发布 artifactRef；完整研究证据用 publish_json 发布。
- 输出区分候选作者、作品来源（平台稳定 ID/URL）、采用的证据与等级、排除候选
  及原因、未解决条件、下一步动作；所有候选一律 unverified/unconfirmed。
- Lens topTitles/topDomains/links、provider 检索摘要、转载页、水印、同名账号
  最高只作 weak/medium 线索，不能写成 confirmed；写入阶段仍走 author.discover
  等现有工具，本模块不新建作者、不改档案、不关联资产。
- 401/403 等鉴权错误向上抛；研究关闭、sidecar 不可用、provider 受限、超时、
  网络失败等记录为 limitations 并降级为 partial/unresolved，不伪造空结果成功。
"""
from __future__ import annotations

import os
import re
import time
from pathlib import Path
from urllib import parse

from . import artifact_ref, client as http_client, errors, fileref
from . import media_primitives, media_scan, pathguard, tools_query
from .runtime import ToolContext

TOOL_ID = "research.work"
DEFAULT_FRAME_PERCENTAGES = media_primitives.DEFAULT_FRAME_PERCENTAGES
DEFAULT_PROVIDER_LIMIT = 10
MAX_PROVIDER_LIMIT = 20
DEFAULT_TIMEOUT_SECONDS = 300
MAX_TIMEOUT_SECONDS = 900
MAX_FRAME_PERCENTAGES = 4
MAX_CANDIDATES = 20
MAX_WORK_SOURCES = 20
MAX_EVIDENCE = 40
MAX_EXCLUDED = 20
MAX_LIMITATIONS = 20
MAX_UNRESOLVED = 20
MAX_NEXT_ACTIONS = 20
PROVIDER_NAME = "bing"
PROVIDER_OPERATION = "search"

_STRENGTH_ORDER = {"weak": 1, "medium": 2}
_LENS_STATUS_CODES = {
    "blocked": "LENS_BLOCKED",
    "timeout": "LENS_TIMEOUT",
    "upload_rejected": "LENS_UPLOAD_REJECTED",
    "sidecar_unavailable": "LENS_UNAVAILABLE",
}
_AGGREGATE_SUFFIXES = (
    "bing.com", "google.com", "google.co.jp", "yandex.com", "yandex.ru",
    "saucenao.com", "tineye.com", "duckduckgo.com", "web.archive.org",
    "archive.org",
)
_IMAGE_MIMES = {
    ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
    ".webp": "image/webp", ".gif": "image/gif", ".avif": "image/avif",
}

_X_STATUS_RE = re.compile(
    r"^https?://(?:www\.|mobile\.)?(?:x|twitter)\.com/"
    r"([A-Za-z0-9_]{1,15})/status/(\d{1,25})(?:[/?].*)?$", re.IGNORECASE)
_X_PROFILE_RE = re.compile(
    r"^https?://(?:www\.|mobile\.)?(?:x|twitter)\.com/"
    r"([A-Za-z0-9_]{1,15})/?(?:\?.*)?$", re.IGNORECASE)
_PIXIV_USER_RE = re.compile(
    r"^https?://(?:www\.)?pixiv\.net/(?:en/)?users/(\d{1,20})(?:[/?].*)?$",
    re.IGNORECASE)
_PIXIV_WORK_RE = re.compile(
    r"^https?://(?:www\.)?pixiv\.net/(?:en/)?artworks/(\d{1,20})(?:[/?].*)?$",
    re.IGNORECASE)
_BSKY_PROFILE_RE = re.compile(
    r"^https?://bsky\.app/profile/([A-Za-z0-9._:-]{1,200})/?(?:\?.*)?$",
    re.IGNORECASE)
_BSKY_POST_RE = re.compile(
    r"^https?://bsky\.app/profile/([A-Za-z0-9._:-]{1,200})/post/"
    r"([A-Za-z0-9]{1,40})(?:[/?].*)?$", re.IGNORECASE)
_DANBOORU_POST_RE = re.compile(
    r"^https?://(?:www\.)?danbooru\.donmai\.us/posts/(\d{1,20})(?:[/?].*)?$",
    re.IGNORECASE)
_E621_POST_RE = re.compile(
    r"^https?://e621\.net/posts/(\d{1,20})(?:[/?].*)?$", re.IGNORECASE)
_IWARA_VIDEO_RE = re.compile(
    r"^https?://(?:www\.)?iwara\.tv/video/([A-Za-z0-9]{1,80})(?:[/?].*)?$",
    re.IGNORECASE)
_IWARA_PROFILE_RE = re.compile(
    r"^https?://(?:www\.)?iwara\.tv/profile/([A-Za-z0-9_.-]{1,100})(?:[/?].*)?$",
    re.IGNORECASE)
_FANBOX_POST_RE = re.compile(
    r"^https?://(?:www\.)?fanbox\.cc/@([A-Za-z0-9_.-]{1,100})/posts/"
    r"(\d{1,20})(?:[/?].*)?$", re.IGNORECASE)
_PATREON_USER_RE = re.compile(
    r"^https?://(?:www\.)?patreon\.com/user\?u=(\d{1,20})(?:&.*)?$",
    re.IGNORECASE)
_PATREON_PROFILE_RE = re.compile(
    r"^https?://(?:www\.)?patreon\.com/([A-Za-z0-9_.-]{1,100})/?(?:\?.*)?$",
    re.IGNORECASE)


# --------------------------------------------------------------------------- #
# 输入解析与通用清洗
# --------------------------------------------------------------------------- #

def _timeout_seconds(payload: dict) -> int:
    value = payload.get("timeoutSeconds")
    if value is None:
        return DEFAULT_TIMEOUT_SECONDS
    if not isinstance(value, int) or isinstance(value, bool) \
            or not 1 <= value <= MAX_TIMEOUT_SECONDS:
        raise errors.invalid_input(
            f"timeoutSeconds 必须在 1..{MAX_TIMEOUT_SECONDS}")
    return value


def _provider_limit(payload: dict) -> int:
    value = payload.get("providerLimit")
    if value is None:
        return DEFAULT_PROVIDER_LIMIT
    if not isinstance(value, int) or isinstance(value, bool) \
            or not 1 <= value <= MAX_PROVIDER_LIMIT:
        raise errors.invalid_input(
            f"providerLimit 必须在 1..{MAX_PROVIDER_LIMIT}")
    return value


def _frame_percentages(payload: dict) -> tuple[int, ...]:
    raw = payload.get("frames")
    if raw is None or raw == "":
        return tuple(DEFAULT_FRAME_PERCENTAGES)
    values: list[int] = []
    if isinstance(raw, str):
        try:
            values = list(media_primitives.parse_percentages(raw))
        except ValueError as exc:
            raise errors.invalid_input("frames 非法：" + str(exc)) from None
    elif isinstance(raw, list):
        if not raw or len(raw) > MAX_FRAME_PERCENTAGES:
            raise errors.invalid_input(
                f"frames 数组必须为 1..{MAX_FRAME_PERCENTAGES} 项")
        for item in raw:
            if not isinstance(item, int) or isinstance(item, bool) \
                    or not 0 <= item <= 100:
                raise errors.invalid_input("frames 每项必须是 0..100 的整数")
            values.append(item)
    else:
        raise errors.invalid_input("frames 必须是逗号分隔字符串或整数数组")
    unique = tuple(dict.fromkeys(values))
    if not unique:
        raise errors.invalid_input("frames 不能为空")
    return unique


def _name_hint(payload: dict) -> str:
    value = payload.get("nameHint")
    if value is None:
        return ""
    if not isinstance(value, str) or len(value) > 200 \
            or any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input("nameHint 必须是最多 200 字符的文本")
    return value.strip()


def _clean_text(value, limit: int = 300) -> str:
    if not isinstance(value, str):
        return ""
    text = "".join(" " if ord(ch) < 32 else ch for ch in value).strip()
    return text[:limit]


_SENSITIVE_QUERY_KEYS = frozenset({
    "token", "access_token", "auth", "authorization", "apikey", "api_key",
    "key", "signature", "sig", "expires", "expiry", "password", "secret",
    "session", "sessionid", "cookie", "credential", "credentials",
})


def _safe_url(value) -> str:
    """只接受 http(s)、无凭据/控制字符的公开 URL；敏感查询参数整段丢弃。"""
    if not isinstance(value, str):
        return ""
    text = value.strip()
    if not text or len(text) > 2000 or any(ord(ch) < 32 for ch in text) \
            or " " in text:
        return ""
    try:
        parts = parse.urlsplit(text)
    except ValueError:
        return ""
    if parts.scheme.lower() not in ("http", "https") or not parts.hostname:
        return ""
    if parts.username or parts.password:
        return ""
    query = parts.query
    if query:
        try:
            pairs = parse.parse_qsl(query, keep_blank_values=True)
        except ValueError:
            return ""
        if any(key.lower() in _SENSITIVE_QUERY_KEYS for key, _value in pairs):
            query = ""
    return parse.urlunsplit((parts.scheme.lower(), parts.netloc,
                             parts.path, query, ""))


def _host(url: str) -> str:
    try:
        return (parse.urlsplit(url).hostname or "").lower().rstrip(".")
    except ValueError:
        return ""


def _platform_from_host(host: str) -> str:
    host = (host or "").lower().rstrip(".")
    if host in ("x.com", "twitter.com") or host.endswith((".x.com", ".twitter.com")):
        return "x"
    if host == "pixiv.net" or host.endswith(".pixiv.net"):
        return "pixiv"
    if host == "bsky.app":
        return "bluesky"
    if host == "danbooru.donmai.us":
        return "danbooru"
    if host == "e621.net":
        return "e621"
    if host == "iwara.tv" or host.endswith(".iwara.tv"):
        return "iwara"
    if host == "fanbox.cc" or host.endswith(".fanbox.cc"):
        return "fanbox"
    if host == "patreon.com" or host.endswith(".patreon.com"):
        return "patreon"
    return host


def _is_aggregate(url: str) -> bool:
    host = _host(url)
    return any(host == suffix or host.endswith("." + suffix)
               for suffix in _AGGREGATE_SUFFIXES)


def _parse_platform_url(url: str) -> dict | None:
    """保守解析平台稳定 ID/URL；无法识别时返回 None，不猜。"""
    match = _X_STATUS_RE.match(url)
    if match:
        handle, post_id = match.group(1), match.group(2)
        return {"platform": "x", "kind": "work", "stableId": post_id,
                "handle": handle, "canonicalUrl":
                "https://x.com/" + handle + "/status/" + post_id}
    match = _X_PROFILE_RE.match(url)
    if match:
        handle = match.group(1)
        return {"platform": "x", "kind": "author", "stableId": None,
                "handle": handle,
                "canonicalUrl": "https://x.com/" + handle}
    match = _PIXIV_USER_RE.match(url)
    if match:
        user_id = match.group(1)
        return {"platform": "pixiv", "kind": "author", "stableId": user_id,
                "handle": "", "canonicalUrl":
                "https://www.pixiv.net/users/" + user_id}
    match = _PIXIV_WORK_RE.match(url)
    if match:
        work_id = match.group(1)
        return {"platform": "pixiv", "kind": "work", "stableId": work_id,
                "handle": "", "canonicalUrl":
                "https://www.pixiv.net/artworks/" + work_id}
    match = _BSKY_POST_RE.match(url)
    if match:
        handle, post_id = match.group(1), match.group(2)
        return {"platform": "bluesky", "kind": "work", "stableId": post_id,
                "handle": handle, "canonicalUrl":
                "https://bsky.app/profile/" + handle + "/post/" + post_id}
    match = _BSKY_PROFILE_RE.match(url)
    if match:
        handle = match.group(1)
        stable = handle if handle.lower().startswith("did:") else None
        return {"platform": "bluesky", "kind": "author", "stableId": stable,
                "handle": handle,
                "canonicalUrl": "https://bsky.app/profile/" + handle}
    match = _DANBOORU_POST_RE.match(url)
    if match:
        post_id = match.group(1)
        return {"platform": "danbooru", "kind": "work", "stableId": post_id,
                "handle": "", "canonicalUrl":
                "https://danbooru.donmai.us/posts/" + post_id}
    match = _E621_POST_RE.match(url)
    if match:
        post_id = match.group(1)
        return {"platform": "e621", "kind": "work", "stableId": post_id,
                "handle": "", "canonicalUrl":
                "https://e621.net/posts/" + post_id}
    match = _IWARA_VIDEO_RE.match(url)
    if match:
        video_id = match.group(1)
        return {"platform": "iwara", "kind": "work", "stableId": video_id,
                "handle": "", "canonicalUrl":
                "https://www.iwara.tv/video/" + video_id}
    match = _IWARA_PROFILE_RE.match(url)
    if match:
        handle = match.group(1)
        return {"platform": "iwara", "kind": "author", "stableId": None,
                "handle": handle,
                "canonicalUrl": "https://www.iwara.tv/profile/" + handle}
    match = _FANBOX_POST_RE.match(url)
    if match:
        handle, post_id = match.group(1), match.group(2)
        return {"platform": "fanbox", "kind": "work", "stableId": post_id,
                "handle": handle, "canonicalUrl":
                "https://www.fanbox.cc/@" + handle + "/posts/" + post_id}
    match = _PATREON_USER_RE.match(url)
    if match:
        user_id = match.group(1)
        return {"platform": "patreon", "kind": "author", "stableId": user_id,
                "handle": "", "canonicalUrl":
                "https://www.patreon.com/user?u=" + user_id}
    match = _PATREON_PROFILE_RE.match(url)
    if match:
        handle = match.group(1)
        if handle.lower() == "user":
            return None
        return {"platform": "patreon", "kind": "author", "stableId": None,
                "handle": handle,
                "canonicalUrl": "https://www.patreon.com/" + handle}
    return None


def _profile_url(platform: str, handle: str) -> str:
    if not handle:
        return ""
    if platform == "x":
        return "https://x.com/" + handle
    if platform == "bluesky":
        return "https://bsky.app/profile/" + handle
    if platform == "iwara":
        return "https://www.iwara.tv/profile/" + handle
    if platform == "fanbox":
        return "https://www.fanbox.cc/@" + handle
    if platform == "patreon":
        return "https://www.patreon.com/" + handle
    return ""


def _media_type(path: Path) -> str:
    extension = media_scan.split_extension(path.name)[1]
    if extension == ".gif":
        return "animated_image"
    kind = media_scan.media_type(path.name)
    if kind in ("image", "video"):
        return kind
    raise errors.invalid_input("仅支持图片/视频/动图（按扩展名识别）")


def _mime_for(path: Path) -> str:
    return _IMAGE_MIMES.get(path.suffix.lower(), "application/octet-stream")


def _resolve_media(ctx: ToolContext, payload: dict) -> tuple[Path, str]:
    """path/fileRef/artifactRef 三选一；返回 (可信路径, 来源类型)。"""
    supplied = [(key, payload.get(key)) for key in ("path", "fileRef", "artifactRef")
                if payload.get(key) not in (None, "")]
    if len(supplied) != 1:
        raise errors.invalid_input("path、fileRef、artifactRef 必须且只能提供一个")
    key, value = supplied[0]
    if key == "path":
        path = pathguard.resolve_local_path(
            value, env=ctx.env, must_exist=True, kind="媒体文件", mode="read")
        source_kind = "path"
    elif key == "fileRef":
        path = fileref.resolve_ref(value, env=ctx.env, mode="read")
        source_kind = "fileRef"
    else:
        if not getattr(ctx, "rulepack_version", ""):
            raise errors.artifact_context_invalid(
                "当前上下文缺少规则版本，无法解析 artifactRef")
        path = artifact_ref.resolve_path(
            value, env=ctx.env, rulepack_version=ctx.rulepack_version)
        source_kind = "artifactRef"
    if not path.is_file():
        raise errors.invalid_input("媒体源必须是普通文件")
    return path, source_kind


def _remaining(deadline: float) -> float:
    return max(0.0, deadline - time.monotonic())


def _add_limitation(limitations: list[dict], code: str, message: str,
                    stage: str) -> None:
    for item in limitations:
        if item["code"] == code and item["stage"] == stage:
            return
    if len(limitations) >= MAX_LIMITATIONS:
        if not any(item["code"] == "LIMITATIONS_TRUNCATED" for item in limitations):
            limitations[-1] = {
                "code": "LIMITATIONS_TRUNCATED", "stage": "output",
                "message": "限制项超过输出上限，只保留前若干条",
            }
        return
    limitations.append({"code": code, "message": message, "stage": stage})


def _error_code(body) -> str:
    if not isinstance(body, dict):
        return ""
    error = body.get("error")
    if isinstance(error, dict):
        code = error.get("code")
    elif isinstance(error, str):
        code = error
    else:
        code = None
    if not isinstance(code, str) or not code:
        code = body.get("code")
    if not isinstance(code, str):
        return ""
    return code[:64]


# --------------------------------------------------------------------------- #
# 研究上下文 / Lens / provider
# --------------------------------------------------------------------------- #

def _context_summary(data: dict) -> dict:
    sidecar = data.get("sidecar") if isinstance(data.get("sidecar"), dict) else {}
    providers = []
    raw_providers = data.get("providers")
    if isinstance(raw_providers, list):
        for item in raw_providers[:20]:
            if not isinstance(item, dict):
                continue
            operations = []
            raw_operations = item.get("operations")
            if isinstance(raw_operations, list):
                operations = [_clean_text(op, 64) for op in raw_operations[:12]
                              if isinstance(op, str)]
            providers.append({
                "name": _clean_text(item.get("name"), 64),
                "enabled": item.get("enabled") is True,
                "operations": operations,
            })
    limits = {}
    raw_limits = data.get("limits")
    if isinstance(raw_limits, dict):
        for key in ("lensPerHour", "lensPerDay"):
            value = raw_limits.get(key)
            if isinstance(value, int) and not isinstance(value, bool):
                limits[key] = value
    return {
        "status": "ok",
        "enabled": data.get("enabled") is True,
        "sidecar": {
            "configured": sidecar.get("configured") is True,
            "available": sidecar.get("available") is True,
        },
        "providers": providers,
        "limits": limits,
    }


def _unavailable_context() -> dict:
    return {
        "status": "unavailable",
        "enabled": None,
        "sidecar": {"configured": False, "available": False},
        "providers": [],
        "limits": {},
    }


def _fetch_context(ctx: ToolContext, limitations: list[dict],
                   deadline: float) -> dict:
    if ctx.client is None:
        _add_limitation(limitations, "RESEARCH_CONTEXT_UNAVAILABLE",
                        "缺少 API 客户端，未读取研究上下文", "context")
        return _unavailable_context()
    if _remaining(deadline) <= 0:
        _add_limitation(limitations, "RESEARCH_TIMEOUT_BUDGET",
                        "研究时间预算已用尽，未读取研究上下文", "context")
        return _unavailable_context()
    try:
        data = ctx.client.get("/api/v1/research/context")
    except http_client.HTTPStatusError as exc:
        if exc.status in (401, 403):
            raise
        code = _error_code(exc.body)
        if exc.status == 503 and code == "RESEARCH_DISABLED":
            _add_limitation(limitations, "RESEARCH_DISABLED",
                            "研究网关已关闭（research.enabled=false），未执行 Lens/provider 查询",
                            "context")
        else:
            _add_limitation(limitations, "RESEARCH_CONTEXT_UNAVAILABLE",
                            "研究上下文读取失败（HTTP " + str(exc.status) + "）",
                            "context")
        return _unavailable_context()
    except http_client.NetworkError:
        _add_limitation(limitations, "RESEARCH_CONTEXT_NETWORK",
                        "研究上下文网络请求失败，未执行 Lens/provider 查询", "context")
        return _unavailable_context()
    except http_client.ResponseError:
        _add_limitation(limitations, "RESEARCH_CONTEXT_INVALID_RESPONSE",
                        "研究上下文响应不可解析，未执行 Lens/provider 查询", "context")
        return _unavailable_context()
    if not isinstance(data, dict) or not isinstance(data.get("enabled"), bool):
        _add_limitation(limitations, "RESEARCH_CONTEXT_INVALID",
                        "研究上下文缺少布尔 enabled 字段，按不可用处理", "context")
        return _unavailable_context()
    summary = _context_summary(data)
    if summary["enabled"] is not True:
        _add_limitation(limitations, "RESEARCH_DISABLED",
                        "研究网关已关闭（research.enabled=false），未执行 Lens/provider 查询",
                        "context")
    return summary


def _provider_entry(research: dict, name: str) -> dict | None:
    for item in research.get("providers") or []:
        if isinstance(item, dict) and item.get("name") == name:
            return item
    return None


def _provider_items(data: dict) -> list[dict]:
    raw = data.get("items")
    if not isinstance(raw, list):
        return []
    return [item for item in raw[:20] if isinstance(item, dict)]


def _query_provider(ctx: ToolContext, name_hint: str, limit: int,
                    research: dict, limitations: list[dict],
                    queries: list[dict], deadline: float) -> list[dict]:
    if not name_hint:
        return []
    if research.get("enabled") is not True:
        return []
    provider = _provider_entry(research, PROVIDER_NAME)
    if provider is None or provider.get("enabled") is not True \
            or PROVIDER_OPERATION not in (provider.get("operations") or []):
        _add_limitation(limitations, "PROVIDER_NOT_AVAILABLE",
                        "文字线索 provider 未启用或不在矩阵内，未执行检索", "provider")
        return []
    remaining = _remaining(deadline)
    if remaining <= 0:
        _add_limitation(limitations, "RESEARCH_TIMEOUT_BUDGET",
                        "研究时间预算已用尽，未执行文字线索检索", "provider")
        return []
    body = {"operation": PROVIDER_OPERATION, "target": name_hint,
            "options": {"limit": limit}}
    try:
        data = ctx.client.post(
            "/api/v1/research/providers/" + PROVIDER_NAME + "/query",
            json_body=body,
            timeout=min(remaining, http_client.REQUEST_TIMEOUT_SECONDS),
        )
    except http_client.HTTPStatusError as exc:
        if exc.status in (401, 403):
            raise
        code = _error_code(exc.body)
        if exc.status == 503 and code == "RESEARCH_DISABLED":
            _add_limitation(limitations, "RESEARCH_DISABLED",
                            "研究网关已关闭，未完成文字线索检索", "provider")
        elif exc.status == 429 or code == "RATE_LIMITED":
            _add_limitation(limitations, "PROVIDER_RATE_LIMITED",
                            "文字线索 provider 达到限速预算，未完成检索", "provider")
        elif exc.status == 504:
            _add_limitation(limitations, "PROVIDER_TIMEOUT",
                            "文字线索 provider 查询超时，未完成检索", "provider")
        elif exc.status == 404:
            _add_limitation(limitations, "PROVIDER_NOT_AVAILABLE",
                            "文字线索 provider 端点不可用（HTTP 404）", "provider")
        else:
            _add_limitation(limitations, "PROVIDER_UNAVAILABLE",
                            "文字线索 provider 查询失败（HTTP " + str(exc.status) + "）",
                            "provider")
        queries.append({"provider": PROVIDER_NAME, "operation": PROVIDER_OPERATION,
                        "status": "http_error", "itemCount": 0,
                        "errorCode": code or ("HTTP_" + str(exc.status))})
        return []
    except http_client.NetworkError:
        _add_limitation(limitations, "PROVIDER_NETWORK",
                        "文字线索 provider 网络请求失败，未完成检索", "provider")
        queries.append({"provider": PROVIDER_NAME, "operation": PROVIDER_OPERATION,
                        "status": "network_error", "itemCount": 0,
                        "errorCode": "NETWORK_ERROR"})
        return []
    except http_client.ResponseError:
        _add_limitation(limitations, "PROVIDER_INVALID_RESPONSE",
                        "文字线索 provider 响应不可解析，未完成检索", "provider")
        queries.append({"provider": PROVIDER_NAME, "operation": PROVIDER_OPERATION,
                        "status": "invalid_response", "itemCount": 0,
                        "errorCode": "INVALID_RESPONSE"})
        return []
    if not isinstance(data, dict) or not isinstance(data.get("status"), str):
        _add_limitation(limitations, "PROVIDER_INVALID_RESPONSE",
                        "文字线索 provider 响应结构不符，未采用任何条目", "provider")
        queries.append({"provider": PROVIDER_NAME, "operation": PROVIDER_OPERATION,
                        "status": "invalid_response", "itemCount": 0,
                        "errorCode": "INVALID_RESPONSE"})
        return []
    status = data["status"]
    items = _provider_items(data) if status == "ok" else []
    queries.append({"provider": PROVIDER_NAME, "operation": PROVIDER_OPERATION,
                    "status": _clean_text(status, 64), "itemCount": len(items),
                    "errorCode": _error_code(data.get("error"))})
    if status == "ok":
        return items
    if status == "not_found":
        return []
    if status == "blocked":
        _add_limitation(limitations, "PROVIDER_BLOCKED",
                        "文字线索 provider 被平台拒绝或需要登录，未取得条目", "provider")
    elif status == "rate_limited":
        _add_limitation(limitations, "PROVIDER_RATE_LIMITED",
                        "文字线索 provider 达到限速预算，未取得条目", "provider")
    elif status == "provider_unavailable":
        _add_limitation(limitations, "PROVIDER_UNAVAILABLE",
                        "文字线索 provider 上游不可用，未取得条目", "provider")
    else:
        _add_limitation(limitations, "PROVIDER_UNEXPECTED_STATUS",
                        "文字线索 provider 返回未知状态：" + _clean_text(status, 64),
                        "provider")
    return []


def _query_lens(ctx: ToolContext, lens_path: Path | None, research: dict,
                limitations: list[dict], deadline: float) -> dict | None:
    if research.get("enabled") is not True:
        return None
    sidecar = research.get("sidecar") or {}
    if sidecar.get("available") is not True:
        _add_limitation(limitations, "LENS_UNAVAILABLE",
                        "Lens sidecar 未配置或不可达，未执行视觉反查", "lens")
        return None
    if lens_path is None:
        _add_limitation(limitations, "LENS_NO_INPUT",
                        "没有可用于 Lens 的图片或视频帧", "lens")
        return None
    remaining = _remaining(deadline)
    if remaining <= 0:
        _add_limitation(limitations, "RESEARCH_TIMEOUT_BUDGET",
                        "研究时间预算已用尽，未执行视觉反查", "lens")
        return None
    try:
        body, content_type = tools_query.lens_multipart(str(lens_path))
    except errors.ToolError as exc:
        _add_limitation(limitations, "LENS_INPUT_REJECTED",
                        "Lens 输入预检未通过（" + exc.code + "），未执行视觉反查",
                        "lens")
        return None
    try:
        data = ctx.client.post(
            "/api/v1/research/lens",
            data=body,
            content_type=content_type,
            timeout=min(remaining, http_client.LENS_TIMEOUT_SECONDS),
        )
    except http_client.HTTPStatusError as exc:
        if exc.status in (401, 403):
            raise
        code = _error_code(exc.body)
        if exc.status == 503 and code == "RESEARCH_SIDECAR_DISABLED":
            _add_limitation(limitations, "LENS_UNAVAILABLE",
                            "研究网关关闭或 Lens sidecar 未配置，未执行视觉反查",
                            "lens")
        elif exc.status == 503:
            _add_limitation(limitations, "LENS_UNAVAILABLE",
                            "Lens sidecar 不可达，未完成视觉反查", "lens")
        elif exc.status == 504:
            _add_limitation(limitations, "LENS_TIMEOUT",
                            "Lens 网关等待超时，未完成视觉反查", "lens")
        elif exc.status == 429 or code == "RATE_LIMITED":
            _add_limitation(limitations, "LENS_RATE_LIMITED",
                            "Lens 预算或 sidecar 并发已满，未完成视觉反查", "lens")
        elif exc.status == 502:
            _add_limitation(limitations, "LENS_UPSTREAM_ERROR",
                            "Lens sidecar 返回异常响应，未完成视觉反查", "lens")
        else:
            _add_limitation(limitations, "LENS_UNAVAILABLE",
                            "Lens 请求失败（HTTP " + str(exc.status) + "）", "lens")
        return None
    except http_client.NetworkError:
        _add_limitation(limitations, "LENS_NETWORK",
                        "Lens 网络请求失败，未完成视觉反查", "lens")
        return None
    except http_client.ResponseError:
        _add_limitation(limitations, "LENS_INVALID_RESPONSE",
                        "Lens 响应不可解析，未完成视觉反查", "lens")
        return None
    if not isinstance(data, dict) or not isinstance(data.get("status"), str):
        _add_limitation(limitations, "LENS_INVALID_RESPONSE",
                        "Lens 响应结构不符，未采用任何条目", "lens")
        return None
    status = data["status"]
    if status != "ok":
        _add_limitation(limitations, _LENS_STATUS_CODES.get(status, "LENS_UNKNOWN_STATUS"),
                        "Lens 未完成识别（status=" + _clean_text(status, 64) + "），不当作成功",
                        "lens")
        return data
    links = data.get("links")
    if not isinstance(links, list) or not links:
        _add_limitation(limitations, "LENS_LINKS_EMPTY",
                        "Lens 返回 ok 但没有稳定外链；标题/域名只作弱线索，不当作识别成功",
                        "lens")
    return data


def _lens_summary(data: dict | None) -> dict:
    if not isinstance(data, dict):
        return {"attempted": False}
    links = data.get("links") if isinstance(data.get("links"), list) else []
    cites = data.get("cites") if isinstance(data.get("cites"), list) else []
    titles = data.get("topTitles") if isinstance(data.get("topTitles"), list) else []
    domains = data.get("topDomains") if isinstance(data.get("topDomains"), list) else []
    result_url = _safe_url(data.get("resultUrl"))
    return {
        "attempted": True,
        "status": _clean_text(data.get("status"), 64),
        "frameSha256": _clean_text(data.get("frameSha256"), 64),
        "topTitleCount": len(titles),
        "topDomainCount": len(domains),
        "citeCount": len(cites),
        "linkCount": len(links),
        "elapsedMs": data.get("elapsedMs") if isinstance(data.get("elapsedMs"), int) else None,
        "resultUrl": result_url,
    }


def _lens_usable(data: dict | None) -> bool:
    """只有 status=ok 且至少一条外链时才把 Lens 正文/链接当线索使用。"""
    if not isinstance(data, dict) or data.get("status") != "ok":
        return False
    links = data.get("links")
    return isinstance(links, list) and bool(links)


# --------------------------------------------------------------------------- #
# 候选/作品来源/证据汇总（不合并不同平台 ID，不把线索写成 confirmed）
# --------------------------------------------------------------------------- #

class _Collector:
    def __init__(self) -> None:
        self.evidence: list[dict] = []
        self.candidates: list[dict] = []
        self.work_sources: list[dict] = []
        self.excluded: list[dict] = []
        self.truncated = False
        self._evidence_seen: dict[tuple, str] = {}
        self._candidate_index: dict[str, dict] = {}
        self._work_index: dict[str, dict] = {}
        self._item_seen: dict[str, bool] = {}

    def _mark_truncated(self) -> None:
        self.truncated = True

    def add_evidence(self, *, source: str, kind: str, strength: str,
                     summary: str, url: str = "", platform: str = "",
                     stable_id: str | None = None, note: str = "") -> str:
        strength = "medium" if strength == "medium" else "weak"
        key = (source, kind, url, summary, platform, stable_id)
        if key in self._evidence_seen:
            return self._evidence_seen[key]
        if len(self.evidence) >= MAX_EVIDENCE:
            self._mark_truncated()
            return ""
        evidence_id = "ev-" + str(len(self.evidence) + 1)
        self.evidence.append({
            "evidenceId": evidence_id,
            "source": source,
            "kind": kind,
            "strength": strength,
            "summary": _clean_text(summary, 500),
            "url": _safe_url(url),
            "platform": _clean_text(platform, 64),
            "stableId": stable_id,
            "adopted": True,
            "note": note or "只作候选线索；不得当作 confirmed 或作者确认",
        })
        self._evidence_seen[key] = evidence_id
        return evidence_id

    def _merge_candidate(self, *, platform: str, author_name: str, handle: str,
                         stable_id: str | None, stable_url: str, source: str,
                         evidence_id: str, strength: str, note: str = "") -> None:
        if not evidence_id:
            return
        name = _clean_text(author_name, 200)
        handle = _clean_text(handle, 100)
        platform = _clean_text(platform, 64)
        if stable_id:
            key = platform + "|author|id:" + stable_id
        elif handle:
            key = platform + "|author|handle:" + handle.lower()
        else:
            # 无稳定账号/主页时，同名线索不能互相合并成“同一作者”。
            key = (platform + "|author|name:" + name.lower() + "|" + source
                   + "|obs:" + str(len(self.candidates) + 1))
        existing = self._candidate_index.get(key)
        if existing is not None:
            if evidence_id not in existing["evidenceIds"]:
                existing["evidenceIds"].append(evidence_id)
            if source not in existing["sources"]:
                existing["sources"].append(source)
            if _STRENGTH_ORDER.get(strength, 0) > _STRENGTH_ORDER.get(
                    existing["evidenceLevel"], 0):
                existing["evidenceLevel"] = strength
            if not existing.get("stableId") and stable_id:
                existing["stableId"] = stable_id
                existing["stableUrl"] = stable_url
            if note and note not in existing["notes"]:
                existing["notes"].append(note)
            return
        if len(self.candidates) >= MAX_CANDIDATES:
            self._mark_truncated()
            return
        notes = [
            "来源为 Lens/检索摘要/转载页/水印或同名线索，不能确认作者"
            if strength == "weak" else
            "存在平台稳定 ID/URL，但仍缺原帖/作品页与作者主页互证",
        ]
        if note and note not in notes:
            notes.append(note)
        candidate = {
            "candidateId": "cand-" + str(len(self.candidates) + 1),
            "kind": "author",
            "platform": platform,
            "authorName": name,
            "handle": handle,
            "stableId": stable_id,
            "stableUrl": _safe_url(stable_url),
            "sources": [source],
            "evidenceIds": [evidence_id],
            "evidenceLevel": strength,
            "verification": "unconfirmed",
            "authorConfirmed": False,
            "notes": notes,
        }
        self.candidates.append(candidate)
        self._candidate_index[key] = candidate

    def _merge_work_source(self, *, platform: str, kind: str,
                           stable_id: str | None, url: str, title: str,
                           source: str, evidence_id: str, strength: str,
                           note: str = "") -> None:
        if not evidence_id:
            return
        platform = _clean_text(platform, 64)
        title = _clean_text(title, 300)
        url = _safe_url(url)
        if stable_id:
            key = platform + "|" + kind + "|id:" + stable_id
        elif url:
            key = platform + "|" + kind + "|url:" + url
        else:
            key = platform + "|" + kind + "|title:" + title.lower() + "|" + source
        existing = self._work_index.get(key)
        if existing is not None:
            if evidence_id not in existing["evidenceIds"]:
                existing["evidenceIds"].append(evidence_id)
            if source not in existing["sources"]:
                existing["sources"].append(source)
            if _STRENGTH_ORDER.get(strength, 0) > _STRENGTH_ORDER.get(
                    existing["evidenceLevel"], 0):
                existing["evidenceLevel"] = strength
            if not existing.get("stableId") and stable_id:
                existing["stableId"] = stable_id
                existing["url"] = url
            if note and note not in existing["notes"]:
                existing["notes"].append(note)
            return
        if len(self.work_sources) >= MAX_WORK_SOURCES:
            self._mark_truncated()
            return
        notes = ["作品/帖子来源线索；未取得作者主页互证"]
        if note and note not in notes:
            notes.append(note)
        work = {
            "workSourceId": "work-" + str(len(self.work_sources) + 1),
            "kind": kind,
            "platform": platform,
            "stableId": stable_id,
            "url": url,
            "title": title,
            "sources": [source],
            "evidenceIds": [evidence_id],
            "evidenceLevel": strength,
            "verification": "unconfirmed",
            "notes": notes,
        }
        self.work_sources.append(work)
        self._work_index[key] = work

    def _exclude(self, *, kind: str, source: str, reason: str, url: str = "",
                 domain: str = "", platform: str = "",
                 stable_id: str | None = None) -> None:
        if len(self.excluded) >= MAX_EXCLUDED:
            self._mark_truncated()
            return
        safe_url = _safe_url(url)
        self.excluded.append({
            "kind": kind,
            "platform": _clean_text(platform, 64),
            "stableId": stable_id,
            "url": safe_url,
            "domain": _clean_text(domain, 200) or _host(safe_url),
            "source": source,
            "reason": reason,
        })

    def add_link(self, *, url, title="", domain="", author_name="",
                 source: str, evidence_kind: str) -> None:
        safe = _safe_url(url)
        title = _clean_text(title, 300)
        domain = _clean_text(domain, 200) or _host(safe)
        if not safe:
            self.add_text(title=title, domain=domain, source=source,
                          evidence_kind=evidence_kind)
            return
        if _is_aggregate(safe):
            self._exclude(
                kind="aggregate", source=source, domain=_host(safe),
                reason="搜索/聚合/存档页，不是作者主页或作品原帖")
            return
        parsed = _parse_platform_url(safe)
        canonical = parsed["canonicalUrl"] if parsed else safe
        if parsed and parsed.get("stableId") and parsed.get("kind") in ("author", "work"):
            key = ("stable|" + parsed["platform"] + "|" + parsed["kind"] + "|"
                   + str(parsed["stableId"]))
        elif parsed and parsed.get("kind") == "author" and parsed.get("handle"):
            key = "handle|" + parsed["platform"] + "|" + parsed["handle"].lower()
        else:
            key = "url|" + canonical
        if key in self._item_seen:
            self._exclude(
                kind="duplicate", source=source, url=canonical,
                platform=(parsed or {}).get("platform", ""),
                stable_id=(parsed or {}).get("stableId"),
                reason="同一 URL/平台稳定 ID 的重复条目，未重复计入候选")
            return
        self._item_seen[key] = True
        strength = "medium" if parsed and parsed.get("kind") in ("author", "work") \
            else "weak"
        summary = title or (parsed or {}).get("handle", "") or domain or canonical
        evidence_id = self.add_evidence(
            source=source, kind=evidence_kind, strength=strength,
            summary=summary, url=canonical,
            platform=(parsed or {}).get("platform", _platform_from_host(domain)),
            stable_id=(parsed or {}).get("stableId"),
            note=("平台稳定 ID/URL 线索；仍需原帖/作品页与作者主页互证"
                  if strength == "medium" else
                  "检索摘要/转载/聚合线索；不能确认作者"),
        )
        if not evidence_id:
            return
        if parsed and parsed.get("kind") == "author":
            self._merge_candidate(
                platform=parsed["platform"], author_name=author_name or title,
                handle=parsed.get("handle", ""), stable_id=parsed.get("stableId"),
                stable_url=parsed["canonicalUrl"], source=source,
                evidence_id=evidence_id, strength=strength)
            return
        if parsed and parsed.get("kind") == "work":
            self._merge_work_source(
                platform=parsed["platform"], kind="work",
                stable_id=parsed.get("stableId"), url=parsed["canonicalUrl"],
                title=title, source=source, evidence_id=evidence_id,
                strength=strength)
            handle = parsed.get("handle", "")
            if handle or author_name:
                self._merge_candidate(
                    platform=parsed["platform"], author_name=author_name,
                    handle=handle, stable_id=None,
                    stable_url=_profile_url(parsed["platform"], handle),
                    source=source, evidence_id=evidence_id, strength=strength,
                    note="账号由作品/帖子 URL 推断，未确认是原作者")
            return
        if author_name:
            self._merge_candidate(
                platform=_platform_from_host(domain), author_name=author_name,
                handle="", stable_id=None, stable_url="", source=source,
                evidence_id=evidence_id, strength="weak",
                note="检索摘要中的作者名；未取得平台主页或稳定 ID")
            return
        self._merge_work_source(
            platform=_platform_from_host(domain), kind="unknown",
            stable_id=None, url=canonical, title=title, source=source,
            evidence_id=evidence_id, strength="weak")

    def add_text(self, *, title: str, domain: str, source: str,
                 evidence_kind: str) -> None:
        title = _clean_text(title, 300)
        domain = _clean_text(domain, 200)
        if not title and not domain:
            return
        key = ("text|" + source + "|" + title.lower() + "|" + domain.lower())
        if key in self._item_seen:
            return
        self._item_seen[key] = True
        summary = title + (" / " + domain if domain else "")
        evidence_id = self.add_evidence(
            source=source, kind=evidence_kind, strength="weak",
            summary=summary, platform=_platform_from_host(domain),
            note="Lens 正文启发式文本；不保证是作品真名或作者名")
        if not evidence_id:
            return
        self._merge_work_source(
            platform=_platform_from_host(domain), kind="title-clue",
            stable_id=None, url="", title=title or domain, source=source,
            evidence_id=evidence_id, strength="weak",
            note="只有标题/域名线索，没有稳定作品 ID/URL")

    def add_domain(self, *, domain, source: str) -> None:
        domain = _clean_text(domain, 200)
        if not domain:
            return
        key = ("domain|" + source + "|" + domain.lower())
        if key in self._item_seen:
            return
        self._item_seen[key] = True
        self.add_evidence(
            source=source, kind="visual_domain", strength="weak",
            summary=domain, platform=_platform_from_host(domain),
            note="Lens 正文启发式域名线索；不确认作者或作品归属")

    def add_lens(self, data: dict) -> None:
        links = data.get("links")
        if isinstance(links, list):
            for item in links[:20]:
                if isinstance(item, dict):
                    self.add_link(
                        url=item.get("url"), title=item.get("title", ""),
                        domain=item.get("domain", ""), source="lens.links",
                        evidence_kind="lens_link")
        cites = data.get("cites")
        if isinstance(cites, list):
            for item in cites[:20]:
                if isinstance(item, dict):
                    self.add_link(
                        url=item.get("url"), title=item.get("title", ""),
                        domain=item.get("domain", ""), source="lens.cites",
                        evidence_kind="lens_cite")
        titles = data.get("topTitles")
        if isinstance(titles, list):
            for item in titles[:20]:
                if isinstance(item, dict):
                    self.add_text(
                        title=item.get("title", ""), domain=item.get("domain", ""),
                        source="lens.topTitles", evidence_kind="visual_title")
        domains = data.get("topDomains")
        if isinstance(domains, list):
            for domain in domains[:20]:
                if isinstance(domain, str):
                    self.add_domain(domain=domain, source="lens.topDomains")

    def add_provider_items(self, items: list[dict], provider: str) -> None:
        source = "provider." + provider
        for item in items[:20]:
            title = item.get("title", "")
            url = item.get("url")
            domain = item.get("domain", "")
            author = item.get("author", "")
            if _safe_url(url):
                self.add_link(
                    url=url, title=title, domain=domain, author_name=author,
                    source=source, evidence_kind="search_result")
                continue
            author = _clean_text(author, 200)
            title = _clean_text(title, 300)
            domain = _clean_text(domain, 200)
            if not author and not title and not domain:
                continue
            key = "provider-text|" + source + "|" + author.lower() + "|" \
                + title.lower() + "|" + domain.lower()
            if key in self._item_seen:
                self._exclude(
                    kind="duplicate", source=source,
                    reason="同一检索摘要的重复条目，未重复计入候选")
                continue
            self._item_seen[key] = True
            summary = author or title or domain
            evidence_id = self.add_evidence(
                source=source, kind="provider_author" if author else "search_result",
                strength="weak", summary=summary,
                platform=_platform_from_host(domain),
                note="检索摘要/作者名同现线索；不能确认同名账号就是作者")
            if not evidence_id:
                continue
            if author:
                self._merge_candidate(
                    platform=_platform_from_host(domain), author_name=author,
                    handle="", stable_id=None, stable_url="", source=source,
                    evidence_id=evidence_id, strength="weak",
                    note="仅有检索摘要中的同名账号，未取得平台主页或稳定 ID")
            else:
                self._merge_work_source(
                    platform=_platform_from_host(domain), kind="title-clue",
                    stable_id=None, url="", title=title or domain, source=source,
                    evidence_id=evidence_id, strength="weak")


# --------------------------------------------------------------------------- #
# 本地媒体与证据发布
# --------------------------------------------------------------------------- #

def _local_frames(ctx: ToolContext, path: Path, media_type: str,
                  percentages: tuple[int, ...], frame_dir: Path,
                  limitations: list[dict], media_failed: bool) -> tuple[list[dict], bool]:
    """返回 (帧元数据, 媒体是否失败)；帧内路径只用于发布，不进入结果。"""
    frames: list[dict] = []
    if media_type not in ("video", "animated_image"):
        return frames, media_failed
    ffmpeg = media_primitives.find_ffmpeg(ctx.env)
    if not ffmpeg:
        _add_limitation(limitations, "FFMPEG_MISSING",
                        "缺少 ffmpeg，无法抽帧或计算 dHash；未返回伪造帧", "frames")
        return frames, media_failed
    try:
        record = media_primitives.extract_frames(
            path, frame_dir, percentages=percentages, kind="video",
            env=ctx.env, cache=True)
    except errors.ToolError:
        raise
    except (OSError, ValueError):
        _add_limitation(limitations, "FRAME_EXTRACTION_FAILED",
                        "抽帧过程失败；未把空帧当作识别结果", "frames")
        return frames, True
    status = record.get("status") if isinstance(record, dict) else ""
    if status == "unavailable":
        _add_limitation(limitations, "FRAME_EXTRACTION_UNAVAILABLE",
                        "抽帧依赖不可用；未返回伪造帧", "frames")
        return frames, media_failed
    raw_frames = record.get("frames") if isinstance(record, dict) else None
    if not isinstance(raw_frames, list) or not raw_frames:
        _add_limitation(limitations, "FRAME_EXTRACTION_EMPTY",
                        "没有取得可用采样帧；未把空结果当作已识别", "frames")
        return frames, media_failed or status == "error"
    root = pathguard.work_root(ctx.env).resolve()
    for item in raw_frames[:MAX_FRAME_PERCENTAGES + 2]:
        if not isinstance(item, dict):
            continue
        raw_path = item.get("path")
        if not isinstance(raw_path, str):
            continue
        try:
            frame_path = Path(raw_path).resolve(strict=True)
            frame_path.relative_to(root)
        except (OSError, ValueError):
            _add_limitation(limitations, "FRAME_PATH_UNSAFE",
                            "抽帧产物不在研究工作根内，已跳过", "frames")
            continue
        if pathguard.is_linkish(frame_path) or not frame_path.is_file():
            _add_limitation(limitations, "FRAME_PATH_UNSAFE",
                            "抽帧产物不是普通文件，已跳过", "frames")
            continue
        percent = item.get("percentage")
        if not isinstance(percent, int) or isinstance(percent, bool):
            percent = None
        index = len(frames) + 1
        frames.append({
            "index": index,
            "percent": percent,
            "seekMs": item.get("seekMs") if isinstance(item.get("seekMs"), int) else None,
            "label": "抽帧 " + (str(percent) + "%" if percent is not None else ""),
            "mime": _mime_for(frame_path),
            "bytes": item.get("bytes") if isinstance(item.get("bytes"), int) else None,
            "sha256": _clean_text(item.get("sha256"), 64),
            "_path": frame_path,
        })
    if not frames:
        _add_limitation(limitations, "FRAME_EXTRACTION_EMPTY",
                        "采样帧均不可用；未把空结果当作已识别", "frames")
        return frames, media_failed or status == "error"
    return frames, media_failed


def _publish_artifacts(ctx: ToolContext, frames: list[dict],
                       evidence_payload: dict) -> tuple[list[dict], list[dict], str, dict]:
    refs: list[dict] = []
    frame_refs: list[dict] = []
    evidence_ref = ""
    status = "available"
    code = ""
    message = ""
    problems: list[dict] = []
    rulepack_version = getattr(ctx, "rulepack_version", "")
    for frame in frames:
        try:
            meta = artifact_ref.publish_file(
                frame["_path"], env=ctx.env, tool_id=TOOL_ID,
                rulepack_version=rulepack_version, kind="frame",
                label=frame["label"], mime=frame["mime"],
                source_step="research.work.frames")
        except errors.ToolError as exc:
            if exc.code == "ARTIFACT_CONTEXT_INVALID":
                status = "unavailable"
                code = code or exc.code
                message = message or exc.message
                break
            status = "partial" if refs else "unavailable"
            code = code or exc.code
            message = message or exc.message
            if len(problems) < 10:
                problems.append({"label": frame["label"], "code": exc.code,
                                 "message": exc.message})
            continue
        refs.append({
            "usage": "frame", "kind": meta["kind"], "label": meta["label"],
            "artifactRef": meta["artifactRef"], "mime": meta["mime"],
            "bytes": meta["bytes"], "sha256": meta["sha256"],
            "sourceStep": meta["sourceStep"],
        })
        frame_refs.append({
            "index": frame["index"], "percent": frame["percent"],
            "crop": "full", "artifactRef": meta["artifactRef"],
            "label": meta["label"], "mime": meta["mime"],
            "bytes": meta["bytes"], "sha256": meta["sha256"],
        })
    if status != "unavailable":
        stored_payload = dict(evidence_payload)
        # 证据 JSON 自包含已发布的帧引用，便于只拿到 evidenceRef 时继续接续。
        stored_payload["frameRefs"] = frame_refs
        stored_payload["artifactRefs"] = refs
        try:
            meta = artifact_ref.publish_json(
                stored_payload, env=ctx.env, tool_id=TOOL_ID,
                rulepack_version=rulepack_version, kind="research-evidence",
                label="作品研究证据", source_step="research.work.evidence")
        except errors.ToolError as exc:
            status = "partial" if refs else "unavailable"
            code = code or exc.code
            message = message or exc.message
            if len(problems) < 10:
                problems.append({"label": "作品研究证据", "code": exc.code,
                                 "message": exc.message})
        else:
            evidence_ref = meta["artifactRef"]
            refs.append({
                "usage": "evidence", "kind": meta["kind"], "label": meta["label"],
                "artifactRef": meta["artifactRef"], "mime": meta["mime"],
                "bytes": meta["bytes"], "sha256": meta["sha256"],
                "sourceStep": meta["sourceStep"],
            })
    if not refs and status == "available":
        status = "unavailable"
        code = code or "ARTIFACT_NOT_FOUND"
        message = message or "本次研究没有可发布的产物"
    result = {
        "status": status, "code": code, "message": message,
        "evidenceRef": evidence_ref,
    }
    if problems:
        result["errors"] = problems[:10]
    return refs, frame_refs, evidence_ref, result


def _public_frames(frames: list[dict]) -> list[dict]:
    return [{key: value for key, value in frame.items() if key != "_path"}
            for frame in frames]


def _unresolved_items(*, research: dict, name_hint: str, lens: dict | None,
                      queries: list[dict], media_type: str,
                      work_sources: list[dict], candidates: list[dict],
                      frames: list[dict], limitations: list[dict]) -> list[str]:
    items = ["未取得原帖/作品页与作者主页互证，所有候选均未确认"]
    if not name_hint:
        items.append("未提供 nameHint，未执行文字反查（nameHint 不是开始研究的前提）")
    if research.get("status") == "unavailable":
        items.append("研究上下文不可用，Lens/provider 状态与预算未知")
    elif research.get("enabled") is not True:
        items.append("研究网关关闭，未执行 Lens/provider 查询")
    else:
        sidecar = research.get("sidecar") or {}
        if sidecar.get("available") is not True:
            items.append("Lens sidecar 不可用，未完成视觉反查")
    if isinstance(lens, dict) and lens.get("status") not in (None, "", "ok"):
        items.append("Lens 未完成识别：status=" + _clean_text(lens.get("status"), 64))
    elif isinstance(lens, dict) and lens.get("status") == "ok" \
            and not lens.get("linkCount"):
        items.append("Lens 未返回稳定原帖/作品链接")
    for query in queries:
        if query.get("status") in ("blocked", "rate_limited", "provider_unavailable",
                                   "http_error", "network_error", "invalid_response"):
            items.append("文字线索 provider 受限或失败：status="
                         + _clean_text(query.get("status"), 64))
            break
    if media_type in ("image", "animated_image") \
            and not any(item.get("stableId") for item in work_sources):
        items.append("图片未解析出平台稳定作品 ID/URL")
    if media_type in ("video", "animated_image") and not frames:
        items.append("视频未取得可用采样帧")
    if work_sources and not candidates:
        items.append("作品来源未关联到可核对的作者主页")
    if candidates and not any(item.get("stableId") or item.get("handle")
                              for item in candidates):
        items.append("候选只有名称/标题线索，缺少稳定账号或主页 URL")
    if limitations:
        codes = [item["code"] for item in limitations]
        if "RESULT_TRUNCATED" in codes or "LIMITATIONS_TRUNCATED" in codes:
            items.append("本次证据/候选超过输出上限，只保留前若干条")
    unique: list[str] = []
    for item in items[:MAX_UNRESOLVED]:
        if item not in unique:
            unique.append(item)
    return unique


def _next_actions(*, research: dict, name_hint: str, candidates: list[dict],
                  work_sources: list[dict], limitations: list[dict],
                  evidence_ref: str) -> list[str]:
    actions = [
        "可用外部 AI 自己的公开搜索核对候选；项目 provider 受限不是唯一入口",
        "核对候选作者主页与原帖/作品页是否互链，排除同名账号、转载页与水印误导",
        "确认作者前保持 candidate/unconfirmed，不把检索摘要或 Lens 页面当已确认作者",
    ]
    if any(item.get("url") for item in work_sources):
        actions.append("打开作品来源 URL 核对原帖/官方作品页、平台稳定 ID 与发布时间")
    elif work_sources:
        actions.append("当前只有标题/域名线索；继续公开检索补作品 ID/原帖 URL 后再核作者")
    if candidates:
        actions.append("逐个核对候选主页的稳定账号 ID、简介与作品列表；不同平台 ID 不合并")
    if not name_hint:
        actions.append("可选补充 nameHint 后重新运行 research.work 获取文字线索")
    if limitations:
        actions.append("按 limitations 区分研究关闭、provider 受限、网络失败与缺依赖，"
                       "不要用空结果冒充识别成功")
    if evidence_ref:
        actions.append("用 platform.artifact.read 按 offset/limit 读取 evidenceRef，"
                       "分页查看完整研究证据")
    else:
        actions.append("本次 evidenceRef 不可用；按 artifactRefStatus/limitations 排查后重试")
    actions.append("确认候选作者后，仍以用户确认的 nameHint/profileChanges 调用 "
                   "author.discover 完成查重、建档与关联；写入不在本工具内")
    return actions[:MAX_NEXT_ACTIONS]


# --------------------------------------------------------------------------- #
# 主入口
# --------------------------------------------------------------------------- #

def research_work(ctx: ToolContext, payload: dict) -> dict:
    """research.work：单图/视频只读研究，输出候选、证据、排除与未解决条件。"""
    if not isinstance(payload, dict):
        raise errors.invalid_input("research.work 输入必须是对象")
    try:
        path, source_kind = _resolve_media(ctx, payload)
        media_type = _media_type(path)
        name_hint = _name_hint(payload)
        percentages = _frame_percentages(payload)
        provider_limit = _provider_limit(payload)
        timeout_seconds = _timeout_seconds(payload)
    except errors.ToolError as exc:
        # 输入解析/类型/参数校验都发生在抽帧和产物发布之前：不得记为可能已写入。
        exc.mutation_rejected = True
        raise
    deadline = time.monotonic() + timeout_seconds

    limitations: list[dict] = []
    queries: list[dict] = []
    research = _fetch_context(ctx, limitations, deadline)
    research_enabled = research.get("enabled") is True

    ffprobe = media_primitives.find_ffprobe(ctx.env)
    probe = media_primitives.probe_media(path, ffprobe or None, env=ctx.env)
    if not isinstance(probe, dict):
        probe = {}
    if not ffprobe:
        _add_limitation(limitations, "FFPROBE_MISSING",
                        "缺少 ffprobe，未取得媒体时长/分辨率", "media")
    elif not probe:
        _add_limitation(limitations, "MEDIA_METADATA_UNAVAILABLE",
                        "媒体元数据不可读（仍继续尝试抽帧/识图，不伪造成已识别）", "media")

    perceptual = media_primitives.perceptual_record(
        path, "video" if media_type in ("video", "animated_image") else "image",
        env=ctx.env)
    if not isinstance(perceptual, dict):
        perceptual = {"status": "error", "frames": []}
    if perceptual.get("status") != "ok":
        _add_limitation(limitations, "PERCEPTUAL_UNAVAILABLE",
                        "dHash 不可用或失败，未返回伪造哈希", "media")

    run_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + os.urandom(4).hex()
    work_root = pathguard.ensure_directory(
        pathguard.work_root(ctx.env) / "research-work" / run_id)
    frame_dir = pathguard.ensure_directory(work_root / "frames")

    media_failed = False
    frames, media_failed = _local_frames(
        ctx, path, media_type, percentages, frame_dir, limitations, media_failed)

    lens_input: Path | None = None
    if media_type == "image":
        if path.suffix.lower() in http_client.LENS_CONTENT_TYPES:
            lens_input = path
        else:
            _add_limitation(limitations, "LENS_INPUT_UNSUPPORTED",
                            "图片扩展名不在 Lens 支持的 JPEG/PNG/WebP 内，未执行视觉反查",
                            "lens")
    elif frames:
        lens_input = frames[0]["_path"]
    elif path.suffix.lower() in http_client.LENS_CONTENT_TYPES:
        lens_input = path

    lens_data = _query_lens(ctx, lens_input, research, limitations, deadline) \
        if research_enabled else None
    lens_summary = _lens_summary(lens_data)
    provider_items = _query_provider(
        ctx, name_hint, provider_limit, research, limitations, queries, deadline)

    collector = _Collector()
    if _lens_usable(lens_data):
        collector.add_lens(lens_data)
    if provider_items:
        collector.add_provider_items(provider_items, PROVIDER_NAME)
    if collector.truncated:
        _add_limitation(limitations, "RESULT_TRUNCATED",
                        "候选/证据超过单次输出上限，只保留前若干条", "output")

    evidence_count = len(collector.evidence)
    if media_failed and evidence_count == 0:
        status = "failed"
    elif limitations:
        status = "partial" if evidence_count else "unresolved"
    else:
        status = "ok" if evidence_count else "unresolved"

    if status == "failed":
        summary = "媒体探测/抽帧失败，作品研究未完成；未返回伪造识别结果"
    elif status == "ok":
        summary = "作品研究流程完成；候选均未确认，确认后才可进入写入阶段"
    elif status == "partial":
        summary = "作品研究部分完成；存在受限步骤或缺失证据，候选均未确认"
    else:
        summary = "作品研究未取得可复核线索（unresolved），候选/证据不足以确认作者"

    public_frames = _public_frames(frames)
    media = {
        "mediaType": media_type,
        "label": "媒体 1",
        "sourceKind": source_kind,
        "bytes": path.stat().st_size,
        "mime": _mime_for(path),
        "dimensions": probe.get("dimensions") if isinstance(probe.get("dimensions"), dict) else None,
        "durationMs": probe.get("durationMs") if isinstance(probe.get("durationMs"), int) else None,
        "perceptual": {
            "status": _clean_text(perceptual.get("status"), 64),
            "hashKind": _clean_text(perceptual.get("hashKind"), 32),
            "frameCount": len(perceptual.get("frames") or [])
            if isinstance(perceptual.get("frames"), list) else 0,
        },
    }

    unresolved = _unresolved_items(
        research=research, name_hint=name_hint, lens=lens_summary,
        queries=queries, media_type=media_type,
        work_sources=collector.work_sources, candidates=collector.candidates,
        frames=public_frames, limitations=limitations)

    evidence_payload = {
        "schemaVersion": 1,
        "tool": TOOL_ID,
        "status": status,
        "summary": summary,
        "media": media,
        "research": research,
        "lens": lens_summary,
        "frames": public_frames,
        "candidates": collector.candidates,
        "workSources": collector.work_sources,
        "evidence": collector.evidence,
        "excluded": collector.excluded,
        "unresolved": unresolved,
        "limitations": limitations,
        "queries": queries,
        "businessWrites": 0,
        "writePhase": "not_started",
        "confirmed": False,
        "truncated": collector.truncated,
    }
    artifact_refs, frame_refs, evidence_ref, artifact_status = _publish_artifacts(
        ctx, frames, evidence_payload)
    next_actions = _next_actions(
        research=research, name_hint=name_hint, candidates=collector.candidates,
        work_sources=collector.work_sources, limitations=limitations,
        evidence_ref=evidence_ref)

    return {
        "status": status,
        "summary": summary,
        "media": media,
        "research": research,
        "lens": lens_summary,
        "frames": public_frames,
        "frameRefs": frame_refs,
        "candidates": collector.candidates,
        "workSources": collector.work_sources,
        "evidence": collector.evidence,
        "excluded": collector.excluded,
        "unresolved": unresolved,
        "nextActions": next_actions,
        "limitations": limitations,
        "queries": queries,
        "artifactRefs": artifact_refs,
        "artifactRefStatus": artifact_status,
        "evidenceRef": evidence_ref,
        "businessWrites": 0,
        "writePhase": "not_started",
        "confirmed": False,
        "truncated": collector.truncated,
    }
