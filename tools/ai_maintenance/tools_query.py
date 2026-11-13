"""只读查询/研究工具实现（统一 dispatch 的 handler）。

这些 handler 只调用正式 GET/POST 研究端点，不写业务数据；输出保持上游 JSON
原样（研究命令的人读摘要由 CLI 适配层渲染），便于 CLI 与 MCP 复用同一结果。
"""
from __future__ import annotations

import os
from urllib import parse

from . import client as http_client
from . import errors

MAX_AUTHOR_PAGE_SIZE = 100
DEFAULT_AUTHOR_PAGE_SIZE = 20
MAX_AUTHOR_PAGE = 50
ASSET_LIMIT_MAX = 200
ASSET_DEFAULT_LIMIT = 60
MAX_ASSET_PAGE = 50
MAX_LENS_BYTES = 20 << 20
DEFAULT_INVENTORY_MAX_AUTHORS = 50
MAX_INVENTORY_AUTHORS = 200

_ASSET_SORT_VALUES = ("default", "fileDate", "addedDate", "viewCount", "playCount",
                      "sizeBytes", "name", "favoriteAt")
_ASSET_MEDIA_TYPES = ("image", "animated_image", "video")


def project_context(ctx, payload: dict) -> dict:
    """本地生成规则包；不联网、不需要 token。"""
    from . import rulepack  # 局部导入避免 rulepack -> registry -> 本模块的循环

    openapi_path = payload.get("openapiPath") or None
    rules_path = payload.get("rulesPath") or None
    pack = rulepack.build_rulepack(openapi_path=openapi_path, rules_path=rules_path)
    if payload.get("markdown"):
        return {"format": "markdown", "markdown": rulepack.render_markdown(pack)}
    return {"format": "json", "context": pack}


def health_check(ctx, payload: dict) -> dict:
    path = "/api/v1/readyz" if payload.get("ready") else "/api/v1/healthz"
    return ctx.client.get(path)


def library_query(ctx, payload: dict) -> list:
    return ctx.client.get("/api/v1/libraries")


def _asset_query_args(payload: dict) -> dict:
    limit = payload.get("limit")
    if limit is not None and not 1 <= limit <= ASSET_LIMIT_MAX:
        raise errors.invalid_input(f"limit 必须在 1..{ASSET_LIMIT_MAX}")
    media_type = payload.get("mediaType")
    if media_type is not None and media_type not in _ASSET_MEDIA_TYPES:
        raise errors.invalid_input("mediaType 必须是 image/animated_image/video")
    sort = payload.get("sort")
    if sort is not None and sort not in _ASSET_SORT_VALUES:
        raise errors.invalid_input("sort 取值不在 OpenAPI 枚举内")
    order = payload.get("order")
    if order is not None and order not in ("asc", "desc"):
        raise errors.invalid_input("order 必须是 asc 或 desc")
    return {
        "q": payload.get("q"),
        "libraryId": payload.get("libraryId"),
        "mediaType": media_type,
        "sort": sort,
        "order": order,
    }


def asset_query(ctx, payload: dict) -> dict:
    filters = _asset_query_args(payload)
    limit = payload.get("limit")
    cursor = payload.get("cursor")
    page = payload.get("page")
    if page is None:
        data = ctx.client.get("/api/v1/assets", dict(filters, limit=limit, cursor=cursor))
        if not isinstance(data, dict):
            raise errors.internal_error("资产列表响应不是对象")
        return data
    if cursor:
        raise errors.invalid_input("page 与 cursor 不能同时使用")
    if not 1 <= page <= MAX_ASSET_PAGE:
        raise errors.invalid_input(f"page 必须在 1..{MAX_ASSET_PAGE}")
    page_limit = limit if limit is not None else ASSET_DEFAULT_LIMIT
    next_cursor = None
    for _ in range(page - 1):
        current = ctx.client.get("/api/v1/assets", dict(filters, limit=page_limit, cursor=next_cursor))
        if not isinstance(current, dict):
            raise errors.internal_error("资产列表响应不是对象")
        next_cursor = current.get("nextCursor")
        if not next_cursor:
            empty = {"items": [], "nextCursor": None}
            if "totalMatched" in current:
                empty["totalMatched"] = current["totalMatched"]
            return empty
        if not isinstance(next_cursor, str):
            raise errors.internal_error("nextCursor 不是字符串")
    data = ctx.client.get("/api/v1/assets", dict(filters, limit=page_limit, cursor=next_cursor))
    if not isinstance(data, dict):
        raise errors.internal_error("资产列表响应不是对象")
    return data


def author_query(ctx, payload: dict) -> dict:
    """作者服务端子串查询与游标分页；旧 name/page/size 继续作便利别名。"""
    q = payload.get("q")
    legacy_name = payload.get("name")
    if q is not None and legacy_name is not None:
        raise errors.invalid_input("q 与旧参数 name 不能同时使用")
    q = legacy_name if q is None else q
    if q is not None and (not isinstance(q, str) or len(q) > 200):
        raise errors.invalid_input("q 必须是最多 200 个字符的字符串")
    q = q.strip() if isinstance(q, str) else ""

    limit = payload.get("limit")
    legacy_size = payload.get("size")
    if limit is not None and legacy_size is not None:
        raise errors.invalid_input("limit 与旧参数 size 不能同时使用")
    limit = legacy_size if limit is None else limit
    if limit is None:
        limit = DEFAULT_AUTHOR_PAGE_SIZE
    if not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= MAX_AUTHOR_PAGE_SIZE:
        raise errors.invalid_input(f"limit/size 必须在 1..{MAX_AUTHOR_PAGE_SIZE}")

    page = payload.get("page")
    if page is not None and (not isinstance(page, int) or isinstance(page, bool)
                             or not 1 <= page <= MAX_AUTHOR_PAGE):
        raise errors.invalid_input(f"page 必须在 1..{MAX_AUTHOR_PAGE}")
    cursor = payload.get("cursor")
    if cursor is not None and (not isinstance(cursor, str) or len(cursor) > 4096):
        raise errors.invalid_input("cursor 必须是最多 4096 个字符的字符串")
    if page is not None and cursor:
        raise errors.invalid_input("page 与 cursor 不能同时使用")

    def fetch_page(after: str | None) -> dict:
        params = {"limit": limit}
        if q:
            params["q"] = q
        if after:
            params["cursor"] = after
        data = ctx.client.get("/api/v1/authors/query", params)
        if (not isinstance(data, dict) or not isinstance(data.get("items"), list)
                or "nextCursor" not in data
                or (data["nextCursor"] is not None and not isinstance(data["nextCursor"], str))):
            raise errors.internal_error("作者查询响应不是有效分页对象")
        return data

    next_cursor = cursor or None
    if page is not None:
        next_cursor = None
        for _ in range(page - 1):
            current = fetch_page(next_cursor)
            next_cursor = current["nextCursor"]
            if not next_cursor:
                return {"items": [], "nextCursor": None}
    return fetch_page(next_cursor)


def _author_id(payload: dict) -> str:
    value = payload.get("authorId")
    if not isinstance(value, str) or not value:
        raise errors.invalid_input("缺少作者 ID")
    if len(value) > 512 or any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input("作者 ID 非法")
    return value


def author_profile_read(ctx, payload: dict) -> dict:
    author_id = _author_id(payload)
    return ctx.client.get("/api/v1/authors/" + parse.quote(author_id, safe="") + "/profile")


def author_sources_read(ctx, payload: dict) -> dict:
    author_id = _author_id(payload)
    return ctx.client.get("/api/v1/authors/" + parse.quote(author_id, safe="") + "/sources")


def maintenance_inventory(ctx, payload: dict) -> dict:
    """清点作者/库现状；只读、有界，不推断删除候选。"""
    max_authors = payload.get("maxAuthors", DEFAULT_INVENTORY_MAX_AUTHORS)
    if not 1 <= max_authors <= MAX_INVENTORY_AUTHORS:
        raise errors.invalid_input(f"maxAuthors 必须在 1..{MAX_INVENTORY_AUTHORS}")
    authors = ctx.client.get("/api/v1/authors")
    libraries = ctx.client.get("/api/v1/libraries")
    if not isinstance(authors, list):
        raise errors.internal_error("作者列表响应不是数组")
    if not isinstance(libraries, list):
        raise errors.internal_error("库列表响应不是数组")
    regular = [item for item in authors if isinstance(item, dict) and item.get("type") == "regular"]
    cos = [item for item in authors if isinstance(item, dict) and item.get("type") == "cos"]
    zero_assets = [item for item in regular if item.get("fileCount") == 0]
    selected = zero_assets if payload.get("zeroAssetsOnly") else regular
    selected = selected[:max_authors]
    include_profiles = bool(payload.get("includeProfiles"))
    items = []
    for author in selected:
        entry = {
            "id": author.get("id"),
            "displayName": author.get("displayName"),
            "type": author.get("type"),
            "fileCount": author.get("fileCount"),
            "followed": author.get("followed"),
            "viewCount": author.get("viewCount"),
            "txtOrder": author.get("txtOrder"),
        }
        if include_profiles and isinstance(author.get("id"), str):
            try:
                entry["profile"] = ctx.client.get(
                    "/api/v1/authors/" + parse.quote(author["id"], safe="") + "/profile")
            except http_client.HTTPStatusError as exc:
                if exc.status != 404:
                    raise
                entry["profile"] = None
        items.append(entry)
    return {
        "authors": {
            "total": len(authors),
            "regular": len(regular),
            "cos": len(cos),
            "zeroAssets": len(zero_assets),
            "returned": len(items),
        },
        "libraries": libraries,
        "items": items,
        "limitsApplied": {"maxAuthors": max_authors, "includeProfiles": include_profiles,
                          "zeroAssetsOnly": bool(payload.get("zeroAssetsOnly"))},
        "notes": [
            "本清单只读，不构成删除/合并候选结论；清理候选必须另行 prepare 并人工确认。",
            "零关联数量只是信号之一，不得单条件删除作者。",
        ],
    }


def maintenance_status(ctx, payload: dict) -> dict:
    job_id = payload.get("jobId")
    if job_id is not None:
        if not isinstance(job_id, str) or not job_id or any(ord(ch) < 32 for ch in job_id):
            raise errors.invalid_input("jobId 非法")
        return {"job": ctx.client.get("/api/v1/maintenance/jobs/" + parse.quote(job_id, safe=""))}
    limit = payload.get("limit")
    if limit is not None and not 1 <= limit <= 100:
        raise errors.invalid_input("limit 必须在 1..100")
    query = {"status": payload.get("status"), "limit": limit, "cursor": payload.get("cursor")}
    return {"jobs": ctx.client.get("/api/v1/maintenance/jobs", query)}


def research_context(ctx, payload: dict) -> dict:
    return ctx.client.get("/api/v1/research/context")


def _research_provider_name(value) -> str:
    if not isinstance(value, str):
        raise errors.invalid_input("provider 必须是字符串")
    value = value.strip()
    if not value:
        raise errors.invalid_input("provider 不能为空")
    if len(value) > 64 or any(ord(ch) < 33 or ord(ch) > 126 for ch in value):
        raise errors.invalid_input("provider 只能使用 1..64 个可打印 ASCII 字符")
    return value


def research_provider_query(ctx, payload: dict) -> dict:
    provider = _research_provider_name(payload.get("provider"))
    body = {"operation": payload.get("operation"), "target": payload.get("target")}
    limit = payload.get("limit")
    if limit is not None:
        body["options"] = {"limit": limit}
    return ctx.client.post(
        "/api/v1/research/providers/" + parse.quote(provider, safe="") + "/query",
        json_body=body,
    )


def _safe_filename(name: str) -> str:
    """multipart 头部的文件名只保留 ASCII 安全字符，避免 CR/LF 注入。"""
    cleaned = "".join(ch if ch.isascii() and (ch.isalnum() or ch in "._-") else "_" for ch in name)
    cleaned = cleaned.strip("._") or "image"
    return cleaned[:120]


def lens_multipart(file_path: str) -> tuple[bytes, str]:
    """本地预检扩展名与 20MiB 上限后编码 multipart；不把图片内容写进错误。"""
    if not isinstance(file_path, str) or not file_path:
        raise errors.invalid_input("缺少 file")
    name = os.path.basename(file_path) or "image"
    content_type = http_client.LENS_CONTENT_TYPES.get(os.path.splitext(name)[1].lower())
    if content_type is None:
        raise errors.invalid_input("file 仅支持 JPEG/PNG/WebP：扩展名须为 .jpg/.jpeg/.png/.webp")
    try:
        size = os.path.getsize(file_path)
    except OSError:
        raise errors.invalid_input("file 无法读取：文件不存在或不可访问") from None
    if size == 0:
        raise errors.invalid_input("file 是空文件")
    if size > MAX_LENS_BYTES:
        raise errors.invalid_input("file 超过 20MiB 上限")
    try:
        with open(file_path, "rb") as handle:
            payload = handle.read(MAX_LENS_BYTES + 1)
    except OSError:
        raise errors.invalid_input("file 无法读取：文件不存在或不可访问") from None
    if len(payload) > MAX_LENS_BYTES:
        raise errors.invalid_input("file 超过 20MiB 上限")
    boundary = "qimeng" + os.urandom(16).hex()
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{_safe_filename(name)}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    tail = f"\r\n--{boundary}--\r\n".encode("utf-8")
    return head + payload + tail, "multipart/form-data; boundary=" + boundary


def research_lens(ctx, payload: dict) -> dict:
    body, content_type = lens_multipart(payload.get("file"))
    return ctx.client.post(
        "/api/v1/research/lens",
        data=body,
        content_type=content_type,
        timeout=http_client.LENS_TIMEOUT_SECONDS,
    )
