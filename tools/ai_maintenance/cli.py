"""绮梦影库 AI 维护只读查询 CLI（标准库实现）。

只有 context/health/libraries/assets/authors/author-profile/author-sources；
不实现任何写入。QIMENG_API_TOKEN 只从环境变量读取，不打印、不落盘。
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

    def get(self, path: str, query: dict | None = None):
        if not path.startswith("/api/v1/"):
            raise ValueError("path must start with /api/v1/")
        url = self.base + path
        if query:
            clean = {key: value for key, value in query.items() if value is not None}
            if clean:
                url += "?" + parse.urlencode(clean, doseq=True)
        req = request.Request(
            url,
            method="GET",
            headers={"Authorization": "Bearer " + self.token, "Accept": "application/json"},
        )
        try:
            with self.opener.open(req, timeout=self.timeout) as response:
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


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="qimeng-ai-maintenance",
        description="绮梦影库外部 AI 维护只读查询 CLI；写入能力未实现。",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    context = sub.add_parser("context", help="输出规则包 JSON 或中文指南（纯本地）")
    context.add_argument("--markdown", action="store_true", help="输出中文指南摘要")
    context.add_argument("--out", metavar="PATH", help="同时把规则包 JSON 写入文件")
    context.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    context.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")

    health = sub.add_parser("health", help="GET /api/v1/healthz 或 /api/v1/readyz")
    health.add_argument("--ready", action="store_true", help="使用 /api/v1/readyz")

    sub.add_parser("libraries", help="GET /api/v1/libraries")

    assets = sub.add_parser("assets", help="GET /api/v1/assets（cursor + limit；--page 便利取页）")
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

    authors = sub.add_parser("authors", help="GET /api/v1/authors（--page/--size 为客户端切片）")
    authors.add_argument("--page", type=int, metavar="N", help="页码，从 1 开始")
    authors.add_argument("--size", type=int, metavar="N", help="每页数量 1..1000")

    for command, help_text in (
        ("author-profile", "GET /api/v1/authors/{authorId}/profile"),
        ("author-sources", "GET /api/v1/authors/{authorId}/sources"),
    ):
        item = sub.add_parser(command, help=help_text)
        item.add_argument("author_id", nargs="?", metavar="AUTHOR_ID", help="作者 ID（位置参数）")
        item.add_argument("--author-id", dest="author_id_option", metavar="AUTHOR_ID", help="作者 ID（选项）")
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
    raise UsageError("未知命令")


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
