"""绮梦影库 AI 维护只读查询 CLI（标准库实现）。

只读命令：context/health/libraries/assets/authors/author-profile/author-sources，
以及研究核验 research context/provider/lens；不实现任何写入。
QIMENG_API_TOKEN 只从环境变量读取，不打印、不落盘。
"""
from __future__ import annotations

import argparse
import http.client
import ipaddress
import json
import os
import socket
import sys
from urllib import error, parse, request

try:
    from . import rulepack
except ImportError:  # 直接运行 tools/ai_maintenance/cli.py
    import rulepack

REQUEST_TIMEOUT_SECONDS = 20.0
MAX_RESPONSE_BYTES = 2 << 20
DEFAULT_AUTHOR_PAGE_SIZE = 50
MAX_AUTHOR_PAGE_SIZE = 1000
ASSET_LIMIT_MAX = 200
ASSET_DEFAULT_LIMIT = 60
MAX_ASSET_PAGE = 50
TOKEN_MAX_LENGTH = 8192
MAX_LENS_BYTES = 20 << 20
LENS_TIMEOUT_SECONDS = 150.0
LENS_CONTENT_TYPES = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
}


class ConfigError(Exception):
    """环境配置缺失或非法；消息只含变量名，不含值。"""


class UsageError(Exception):
    """命令行参数之间的约束错误。"""


class HTTPStatusError(Exception):
    def __init__(self, status: int, body):
        super().__init__(f"HTTP {status}")
        self.status = status
        self.body = body


class NetworkError(Exception):
    def __init__(self, kind: str):
        super().__init__(kind)
        self.kind = kind


class ResponseError(Exception):
    def __init__(self, kind: str):
        super().__init__(kind)
        self.kind = kind


class NoRedirect(request.HTTPRedirectHandler):
    """拒绝重定向，避免把 Bearer token 带到其他主机。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _build_opener(scheme: str):
    """HTTP 目标不创建 HTTPS 上下文；避免无关的证书/SSL 环境影响只读查询。"""
    if scheme == "https":
        return request.build_opener(request.ProxyHandler({}), NoRedirect())
    opener = request.OpenerDirector()
    for handler in (
        request.ProxyHandler({}),
        request.HTTPHandler(),
        NoRedirect(),
        request.HTTPErrorProcessor(),
        request.HTTPDefaultErrorHandler(),
    ):
        opener.add_handler(handler)
    return opener


def validate_base_url(value: str) -> str:
    """只接受 http(s) origin；http 仅限回环/私有地址。"""
    if not isinstance(value, str) or not value.strip():
        raise ConfigError("QIMENG_API_BASE 未设置")
    raw = value.strip()
    try:
        parts = parse.urlsplit(raw)
    except ValueError:
        raise ConfigError("QIMENG_API_BASE 不是合法 URL") from None
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise ConfigError("QIMENG_API_BASE 必须是 http(s) origin，如 http://127.0.0.1:8420")
    if parts.username is not None or parts.password is not None:
        raise ConfigError("QIMENG_API_BASE 不能包含用户名或口令")
    if parts.query or parts.fragment or parts.path not in ("", "/"):
        raise ConfigError("QIMENG_API_BASE 只能是 origin，不能带子路径、查询或片段")
    try:
        _ = parts.port
    except ValueError:
        raise ConfigError("QIMENG_API_BASE 端口非法") from None

    host = (parts.hostname or "").lower()
    if parts.scheme == "http":
        try:
            address = ipaddress.ip_address(host)
            trusted = address.is_loopback or address.is_private
        except ValueError:
            trusted = host == "localhost" or host.endswith(".localhost")
        if not trusted:
            raise ConfigError("http 仅允许回环或私有地址；远程请用 https 或既有隧道")
    return parse.urlunsplit((parts.scheme, parts.netloc, "", "", "")).rstrip("/")


def target_label(base: str) -> str:
    """返回 host:port；默认端口显式补全，绝不包含凭据。"""
    parts = parse.urlsplit(base)
    host = parts.hostname or ""
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    port = parts.port
    if port is None:
        port = 443 if parts.scheme == "https" else 80
    return f"{host}:{port}"


def read_config() -> tuple[str, str]:
    base = os.environ.get("QIMENG_API_BASE", "")
    token = os.environ.get("QIMENG_API_TOKEN", "")
    missing = [name for name, value in (("QIMENG_API_BASE", base), ("QIMENG_API_TOKEN", token)) if not value]
    if missing:
        raise ConfigError("缺少环境变量：" + ", ".join(missing))
    base = validate_base_url(base)
    if len(token) > TOKEN_MAX_LENGTH or any(ord(ch) < 32 for ch in token):
        raise ConfigError("QIMENG_API_TOKEN 非法：含控制字符或过长")
    if token != token.strip():
        raise ConfigError("QIMENG_API_TOKEN 首尾不能有空白")
    return base, token


def _read_http_error(exc: error.HTTPError) -> bytes:
    try:
        raw = exc.read(MAX_RESPONSE_BYTES + 1)
    except OSError:
        raw = b""
    finally:
        try:
            exc.close()
        except OSError:
            pass
    return raw[:MAX_RESPONSE_BYTES]


def _decode_body(raw: bytes):
    if not raw:
        return ""
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeError, ValueError):
        return raw.decode("utf-8", "replace")


def _redact(value, secret: str):
    """兜底脱敏：即使上游错误体意外回显 token，也不输出到终端。"""
    if not secret:
        return value
    if isinstance(value, str):
        return value.replace(secret, "<redacted>")
    if isinstance(value, list):
        return [_redact(item, secret) for item in value]
    if isinstance(value, dict):
        return {key: _redact(item, secret) for key, item in value.items()}
    return value


class ApiClient:
    def __init__(self, base: str, token: str, timeout: float = REQUEST_TIMEOUT_SECONDS, opener=None):
        self.base = validate_base_url(base)
        self.token = token
        self.timeout = timeout
        self.target = target_label(self.base)
        self.opener = opener or _build_opener(parse.urlsplit(self.base).scheme)

    def _headers(self) -> dict:
        return {"Authorization": "Bearer " + self.token, "Accept": "application/json"}

    def _send(self, req, timeout: float | None = None):
        try:
            with self.opener.open(req, timeout=self.timeout if timeout is None else timeout) as response:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
        except error.HTTPError as exc:
            body = _redact(_decode_body(_read_http_error(exc)), self.token)
            raise HTTPStatusError(exc.code, body) from None
        except (socket.timeout, TimeoutError):
            raise NetworkError("timeout") from None
        except error.URLError as exc:
            if isinstance(exc.reason, (socket.timeout, TimeoutError)):
                raise NetworkError("timeout") from None
            raise NetworkError("network_error") from None
        except (OSError, http.client.HTTPException):
            raise NetworkError("network_error") from None

        if len(raw) > MAX_RESPONSE_BYTES:
            raise ResponseError("response_too_large")
        if not raw:
            return None
        try:
            return _redact(json.loads(raw.decode("utf-8")), self.token)
        except (UnicodeError, ValueError):
            raise ResponseError("invalid_json_response") from None

    def get(self, path: str, query: dict | None = None):
        if not path.startswith("/api/v1/"):
            raise ValueError("path must start with /api/v1/")
        url = self.base + path
        if query:
            clean = {key: value for key, value in query.items() if value is not None}
            if clean:
                url += "?" + parse.urlencode(clean, doseq=True)
        req = request.Request(url, method="GET", headers=self._headers())
        return self._send(req)

    def post(self, path: str, json_body=None, data: bytes | None = None,
             content_type: str | None = None, timeout: float | None = None):
        """POST JSON 或已编码的请求体；timeout 只用于需要长等待的命令（Lens）。"""
        if not path.startswith("/api/v1/"):
            raise ValueError("path must start with /api/v1/")
        headers = self._headers()
        if json_body is not None:
            body = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif data is not None:
            body = data
            headers["Content-Type"] = content_type or "application/octet-stream"
        else:
            body = b""
        req = request.Request(self.base + path, data=body, method="POST", headers=headers)
        return self._send(req, timeout)


def build_parser() -> argparse.ArgumentParser:
    json_parent = argparse.ArgumentParser(add_help=False)
    json_parent.add_argument(
        "--json",
        action="store_true",
        default=argparse.SUPPRESS,
        help="输出原始 JSON（研究命令默认人读摘要；只读查询命令本就输出 JSON）",
    )
    parser = argparse.ArgumentParser(
        prog="qimeng-ai-maintenance",
        description="绮梦影库外部 AI 维护只读查询 CLI；写入能力未实现。",
    )
    parser.add_argument("--json", action="store_true", help="输出原始 JSON（研究命令默认人读摘要）")
    sub = parser.add_subparsers(dest="command", required=True)

    context = sub.add_parser("context", help="输出规则包 JSON 或中文指南（纯本地）", parents=[json_parent])
    context.add_argument("--markdown", action="store_true", help="输出中文指南摘要")
    context.add_argument("--out", metavar="PATH", help="同时把规则包 JSON 写入文件")
    context.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    context.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")

    health = sub.add_parser("health", help="GET /api/v1/healthz 或 /api/v1/readyz", parents=[json_parent])
    health.add_argument("--ready", action="store_true", help="使用 /api/v1/readyz")

    sub.add_parser("libraries", help="GET /api/v1/libraries", parents=[json_parent])

    assets = sub.add_parser("assets", help="GET /api/v1/assets（cursor + limit；--page 便利取页）", parents=[json_parent])
    assets.add_argument("--limit", "--size", dest="limit", type=int, metavar="N", help="每页数量 1..200（OpenAPI limit）")
    assets.add_argument("--page", type=int, metavar="N", help="页码，从 1 开始；顺序跟随 nextCursor，不发送 page 参数")
    assets.add_argument("--cursor", metavar="CURSOR", help="分页游标 nextCursor")
    assets.add_argument("--q", metavar="TEXT", help="全文搜索")
    assets.add_argument("--library-id", metavar="ID", help="库 ID")
    assets.add_argument("--media-type", choices=("image", "animated_image", "video"), help="媒体类型")
    assets.add_argument(
        "--sort",
        choices=("default", "fileDate", "addedDate", "viewCount", "playCount", "sizeBytes", "name", "favoriteAt"),
        help="排序键",
    )
    assets.add_argument("--order", choices=("asc", "desc"), help="排序方向")

    authors = sub.add_parser("authors", help="GET /api/v1/authors（--page/--size 为客户端切片）", parents=[json_parent])
    authors.add_argument("--page", type=int, metavar="N", help="页码，从 1 开始")
    authors.add_argument("--size", type=int, metavar="N", help="每页数量 1..1000")

    for command, help_text in (
        ("author-profile", "GET /api/v1/authors/{authorId}/profile"),
        ("author-sources", "GET /api/v1/authors/{authorId}/sources"),
    ):
        item = sub.add_parser(command, help=help_text, parents=[json_parent])
        item.add_argument("author_id", nargs="?", metavar="AUTHOR_ID", help="作者 ID（位置参数）")
        item.add_argument("--author-id", dest="author_id_option", metavar="AUTHOR_ID", help="作者 ID（选项）")

    research = sub.add_parser(
        "research", help="研究核验：context/provider/lens（服务端默认关闭）", parents=[json_parent]
    )
    research_sub = research.add_subparsers(dest="research_command", required=True)
    research_sub.add_parser("context", help="GET /api/v1/research/context", parents=[json_parent])
    provider = research_sub.add_parser(
        "provider", help="POST /api/v1/research/providers/{provider}/query", parents=[json_parent]
    )
    provider.add_argument(
        "--provider",
        required=True,
        metavar="NAME",
        help="provider 名：fxtwitter/pixiv/yandere/e621/wayback/steam/bing（矩阵以服务端为准）",
    )
    provider.add_argument(
        "--operation",
        required=True,
        metavar="OP",
        help="author|post|work|artist|tags|url|workshop|search（矩阵外由服务端 400）",
    )
    provider.add_argument("--target", required=True, metavar="VALUE", help="账号/ID/标签/URL/搜索词（1–500 字符）")
    provider.add_argument("--limit", type=int, metavar="N", help="期望条数 1..50（缺省 10；响应最多 20 条）")
    lens = research_sub.add_parser(
        "lens", help="POST /api/v1/research/lens（multipart file）", parents=[json_parent]
    )
    lens.add_argument("--file", required=True, metavar="PATH", help="本地图片：JPEG/PNG/WebP，≤20MiB")
    return parser


def _author_id(args) -> str:
    positional = getattr(args, "author_id", None)
    option = getattr(args, "author_id_option", None)
    values = [value for value in (positional, option) if value]
    if len(set(values)) > 1:
        raise UsageError("作者 ID 只能提供一次")
    value = values[0] if values else ""
    if not value:
        raise UsageError("缺少作者 ID：传位置参数或 --author-id")
    if len(value) > 512 or any(ord(ch) < 32 for ch in value):
        raise UsageError("作者 ID 非法")
    return value


def _slice_authors(data, page, size):
    if page is None and size is None:
        return data
    page = 1 if page is None else page
    size = DEFAULT_AUTHOR_PAGE_SIZE if size is None else size
    if page < 1:
        raise UsageError("--page 必须 >= 1")
    if size < 1 or size > MAX_AUTHOR_PAGE_SIZE:
        raise UsageError(f"--size 必须在 1..{MAX_AUTHOR_PAGE_SIZE}")
    if not isinstance(data, list):
        raise ResponseError("invalid_json_response")
    start = (page - 1) * size
    return data[start:start + size]


def _research_provider_name(args) -> str:
    value = args.provider.strip()
    if not value:
        raise UsageError("--provider 不能为空")
    if len(value) > 64 or any(ord(ch) < 33 or ord(ch) > 126 for ch in value):
        raise UsageError("--provider 只能使用 1..64 个可打印 ASCII 字符")
    return value


def _safe_filename(name: str) -> str:
    """multipart 头部的文件名只保留 ASCII 安全字符，避免 CR/LF 注入。"""
    cleaned = "".join(ch if ch.isascii() and (ch.isalnum() or ch in "._-") else "_" for ch in name)
    cleaned = cleaned.strip("._") or "image"
    return cleaned[:120]


def _lens_multipart(file_path: str) -> tuple[bytes, str]:
    """本地预检扩展名与 20MiB 上限后编码 multipart；不把图片内容写进错误或摘要。"""
    if not file_path:
        raise UsageError("缺少 --file")
    name = os.path.basename(file_path) or "image"
    content_type = LENS_CONTENT_TYPES.get(os.path.splitext(name)[1].lower())
    if content_type is None:
        raise UsageError("--file 仅支持 JPEG/PNG/WebP：扩展名须为 .jpg/.jpeg/.png/.webp")
    try:
        size = os.path.getsize(file_path)
    except OSError:
        raise UsageError("--file 无法读取：文件不存在或不可访问") from None
    if size == 0:
        raise UsageError("--file 是空文件")
    if size > MAX_LENS_BYTES:
        raise UsageError("--file 超过 20MiB 上限")
    try:
        with open(file_path, "rb") as handle:
            payload = handle.read(MAX_LENS_BYTES + 1)
    except OSError:
        raise UsageError("--file 无法读取：文件不存在或不可访问") from None
    if len(payload) > MAX_LENS_BYTES:
        raise UsageError("--file 超过 20MiB 上限")
    boundary = "qimeng" + os.urandom(16).hex()
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{_safe_filename(name)}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    tail = f"\r\n--{boundary}--\r\n".encode("utf-8")
    return head + payload + tail, "multipart/form-data; boundary=" + boundary


def _execute_research(args, client: ApiClient):
    if args.research_command == "context":
        return client.get("/api/v1/research/context")
    if args.research_command == "provider":
        query = {"operation": args.operation, "target": args.target}
        if args.limit is not None:
            query["options"] = {"limit": args.limit}
        provider = _research_provider_name(args)
        return client.post(
            "/api/v1/research/providers/" + parse.quote(provider, safe="") + "/query",
            json_body=query,
        )
    if args.research_command == "lens":
        body, content_type = _lens_multipart(args.file)
        return client.post(
            "/api/v1/research/lens",
            data=body,
            content_type=content_type,
            timeout=LENS_TIMEOUT_SECONDS,
        )
    raise UsageError("未知研究命令")


def _execute(args, client: ApiClient):
    if args.command == "health":
        return client.get("/api/v1/readyz" if args.ready else "/api/v1/healthz")
    if args.command == "libraries":
        return client.get("/api/v1/libraries")
    if args.command == "assets":
        if args.limit is not None and not 1 <= args.limit <= ASSET_LIMIT_MAX:
            raise UsageError(f"--limit/--size 必须在 1..{ASSET_LIMIT_MAX}")
        filters = {
            "q": args.q,
            "libraryId": args.library_id,
            "mediaType": args.media_type,
            "sort": args.sort,
            "order": args.order,
        }
        if args.page is None:
            query = dict(filters, limit=args.limit, cursor=args.cursor)
            return client.get("/api/v1/assets", query)
        if args.cursor:
            raise UsageError("--page 与 --cursor 不能同时使用")
        if not 1 <= args.page <= MAX_ASSET_PAGE:
            raise UsageError(f"--page 必须在 1..{MAX_ASSET_PAGE}")
        limit = args.limit if args.limit is not None else ASSET_DEFAULT_LIMIT
        cursor = None
        for _ in range(args.page - 1):
            current = client.get("/api/v1/assets", dict(filters, limit=limit, cursor=cursor))
            if not isinstance(current, dict):
                raise ResponseError("invalid_json_response")
            next_cursor = current.get("nextCursor")
            if not next_cursor:
                empty = {"items": [], "nextCursor": None}
                if "totalMatched" in current:
                    empty["totalMatched"] = current["totalMatched"]
                return empty
            if not isinstance(next_cursor, str):
                raise ResponseError("invalid_json_response")
            cursor = next_cursor
        return client.get("/api/v1/assets", dict(filters, limit=limit, cursor=cursor))
    if args.command == "authors":
        return _slice_authors(client.get("/api/v1/authors"), args.page, args.size)
    if args.command in ("author-profile", "author-sources"):
        suffix = "profile" if args.command == "author-profile" else "sources"
        return client.get("/api/v1/authors/" + parse.quote(_author_id(args), safe="") + "/" + suffix)
    if args.command == "research":
        return _execute_research(args, client)
    raise UsageError("未知命令")


def _render_research_context(data) -> str:
    if not isinstance(data, dict):
        raise ResponseError("invalid_json_response")
    enabled = data.get("enabled")
    lines = ["研究：" + ("已启用" if enabled is True else "未启用（research.enabled=false）" if enabled is False else "未知")]
    sidecar = data.get("sidecar") if isinstance(data.get("sidecar"), dict) else {}
    parts = [
        "已配置" if sidecar.get("configured") is True else "未配置",
        "可用" if sidecar.get("available") is True else "不可用",
    ]
    pending = sidecar.get("pending")
    if isinstance(pending, int) and not isinstance(pending, bool):
        parts.append(f"在途 {pending}")
    lines.append("sidecar：" + "，".join(parts))
    if sidecar.get("configured") is not True:
        lines.append("提示：sidecar 未配置时 Lens 返回 503 RESEARCH_SIDECAR_DISABLED；需另行部署本机 sidecar。")
    providers = data.get("providers")
    if isinstance(providers, list):
        lines.append("provider 矩阵：")
        for entry in providers:
            if not isinstance(entry, dict):
                continue
            name = entry.get("name") or "?"
            operations = [str(operation) for operation in entry.get("operations") or []]
            details = "、".join(operations) if operations else "（无 operation）"
            limits = entry.get("limits") if isinstance(entry.get("limits"), dict) else {}
            budget = []
            per_minute = limits.get("perMinute")
            if isinstance(per_minute, int) and not isinstance(per_minute, bool) and per_minute > 0:
                budget.append(f"每分钟 {per_minute} 次")
            per_day = limits.get("perDay")
            if isinstance(per_day, int) and not isinstance(per_day, bool) and per_day > 0:
                budget.append(f"每日 {per_day} 次")
            suffix = "；" + "，".join(budget) if budget else ""
            flag = "" if entry.get("enabled", True) else "（已禁用）"
            lines.append(f"- {name}{flag}：{details}{suffix}")
    limits = data.get("limits") if isinstance(data.get("limits"), dict) else {}
    if limits:
        lines.append(
            "Lens 限额：每小时 {} 次，每日 {} 次".format(
                limits.get("lensPerHour", "?"), limits.get("lensPerDay", "?")
            )
        )
    fetched_at = data.get("fetchedAt")
    if isinstance(fetched_at, str) and fetched_at:
        lines.append("读取时刻：" + fetched_at)
    if enabled is not True:
        lines.append("研究关闭时 provider 查询返回 503 RESEARCH_DISABLED；启用需服务端 research.enabled=true 与已部署 sidecar。")
    return "\n".join(lines) + "\n"


def _render_research_provider(data) -> str:
    if not isinstance(data, dict):
        raise ResponseError("invalid_json_response")
    target = data.get("target")
    header = "{}／{}".format(data.get("provider") or "?", data.get("operation") or "?")
    lines = [f"查询：{header}，target={target if isinstance(target, str) else '?'}"]
    lines.append("status：" + str(data.get("status")))
    items = data.get("items") if isinstance(data.get("items"), list) else []
    lines.append(f"items：{len(items)} 条")
    error = data.get("error")
    if isinstance(error, dict) and error:
        lines.append("error：{}：{}".format(error.get("code"), error.get("message")))
    latency = data.get("latencyMs")
    if isinstance(latency, int) and not isinstance(latency, bool):
        lines.append(f"耗时：{latency} ms")
    for item in items[:5]:
        if not isinstance(item, dict):
            continue
        title = item.get("title") or item.get("url") or "（无标题）"
        lines.append("  - " + " ".join(str(title).split()))
    if len(items) > 5:
        lines.append(f"  …另有 {len(items) - 5} 条，--json 查看全部")
    return "\n".join(lines) + "\n"


def _render_research_lens(data) -> str:
    if not isinstance(data, dict):
        raise ResponseError("invalid_json_response")
    lines = ["status：" + str(data.get("status"))]
    frame = data.get("frameSha256")
    if isinstance(frame, str) and frame:
        lines.append("frameSha256：" + frame)
    top_titles = data.get("topTitles") if isinstance(data.get("topTitles"), list) else []
    links = data.get("links") if isinstance(data.get("links"), list) else []
    lines.append(f"topTitles：{len(top_titles)} 条")
    lines.append(f"links：{len(links)} 条（best-effort，空数组不代表失败）")
    elapsed = data.get("elapsedMs")
    if isinstance(elapsed, int) and not isinstance(elapsed, bool):
        lines.append(f"耗时：{elapsed} ms")
    error = data.get("error")
    if isinstance(error, dict) and error:
        lines.append("error：{}：{}".format(error.get("code"), error.get("message")))
    return "\n".join(lines) + "\n"


def _render_research_summary(command: str, data) -> str:
    if command == "context":
        return _render_research_context(data)
    if command == "provider":
        return _render_research_provider(data)
    if command == "lens":
        return _render_research_lens(data)
    raise ResponseError("invalid_json_response")


def _run_context(args) -> int:
    pack = rulepack.build_rulepack(openapi_path=args.openapi, rules_path=args.rules)
    if args.out:
        rulepack.write_pack(pack, args.out)
    if args.markdown:
        sys.stdout.write(rulepack.render_markdown(pack))
    else:
        sys.stdout.write(rulepack.dumps_pack(pack))
    return 0


def _print_json(value, stream) -> None:
    stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    client = None
    base = ""
    try:
        if args.command == "context":
            return _run_context(args)
        base, token = read_config()
        client = ApiClient(base, token)
        result = _execute(args, client)
        if args.command == "research" and not args.json:
            sys.stdout.write(_render_research_summary(args.research_command, result))
        else:
            _print_json(result, sys.stdout)
        return 0
    except UsageError as exc:
        _print_json({"error": "usage_error", "message": str(exc)}, sys.stderr)
        return 2
    except ConfigError as exc:
        _print_json({"error": "configuration_error", "message": str(exc)}, sys.stderr)
        return 2
    except rulepack.RulepackError as exc:
        _print_json({"error": "rulepack_error", "message": str(exc)}, sys.stderr)
        return 2
    except HTTPStatusError as exc:
        target = client.target if client else (target_label(base) if base else "unknown")
        _print_json({"status": exc.status, "error": "http_error", "body": exc.body, "target": target}, sys.stderr)
        return 1
    except NetworkError as exc:
        target = client.target if client else (target_label(base) if base else "unknown")
        _print_json({"error": exc.kind, "target": target}, sys.stderr)
        return 1
    except ResponseError as exc:
        target = client.target if client else (target_label(base) if base else "unknown")
        _print_json({"error": exc.kind, "target": target}, sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
