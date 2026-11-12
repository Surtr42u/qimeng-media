"""统一 HTTP 客户端与配置读取（标准库）。

安全约束（rules.json permissions / SECURITY）：
- token 只从 QIMENG_API_TOKEN 环境变量读取，不打印、不落盘、不进日志。
- 禁用环境代理与重定向，避免把 Bearer 带到其他主机。
- 错误只保留 host:port 目标标签；上游 body 输出前按 token 兜底脱敏。
- 响应有界（默认 2 MiB），超限明确报 response_too_large。

本模块同时供新工具 dispatch 与旧脚本 import_creator_profiles 的兼容入口使用，
保证只有一份请求/脱敏/错误分类实现。
"""
from __future__ import annotations

import http.client
import ipaddress
import json
import os
import socket
from urllib import error, parse, request

REQUEST_TIMEOUT_SECONDS = 20.0
LENS_TIMEOUT_SECONDS = 150.0
MAX_RESPONSE_BYTES = 2 << 20
TOKEN_MAX_LENGTH = 8192
# 流式落盘（backup.export）分块大小：不把大响应整块读进内存，也不使用
# 上面的 JSON 2MiB 响应上限；上限由调用方给出的预算单独强制。
STREAM_CHUNK_BYTES = 256 << 10

LENS_CONTENT_TYPES = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
}


class ConfigError(Exception):
    """环境配置缺失或非法；消息只含变量名，不含值。"""


class OriginError(ValueError):
    """origin 解析失败的固定类别，供各入口映射为自身错误码。"""

    def __init__(self, kind: str):
        super().__init__(kind)
        self.kind = kind


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


def parse_origin(value: str) -> tuple[str, str, int | None]:
    """把字符串解析为 (scheme, host, port)；只接受 origin 形态。

    抛 OriginError(kind)，kind 属于固定词表：empty / invalid_url / userinfo /
    path / query / fragment / scheme / host / port。
    """
    if not isinstance(value, str) or not value.strip():
        raise OriginError("empty")
    raw = value.strip()
    try:
        parts = parse.urlsplit(raw)
    except ValueError:
        raise OriginError("invalid_url") from None
    if parts.scheme not in ("http", "https"):
        raise OriginError("scheme")
    if not parts.hostname:
        raise OriginError("host")
    if parts.username is not None or parts.password is not None:
        raise OriginError("userinfo")
    if parts.query:
        raise OriginError("query")
    if parts.fragment:
        raise OriginError("fragment")
    if parts.path not in ("", "/"):
        raise OriginError("path")
    try:
        port = parts.port
    except ValueError:
        raise OriginError("port") from None
    return parts.scheme, parts.hostname, port


def validate_base_url(value: str) -> str:
    """只接受 http(s) origin；http 仅限回环/私有地址。"""
    try:
        scheme, host, _port = parse_origin(value)
    except OriginError as exc:
        messages = {
            "empty": "QIMENG_API_BASE 未设置",
            "invalid_url": "QIMENG_API_BASE 不是合法 URL",
            "scheme": "QIMENG_API_BASE 必须是 http(s) origin，如 http://127.0.0.1:8420",
            "host": "QIMENG_API_BASE 必须是 http(s) origin，如 http://127.0.0.1:8420",
            "userinfo": "QIMENG_API_BASE 不能包含用户名或口令",
            "query": "QIMENG_API_BASE 只能是 origin，不能带子路径、查询或片段",
            "fragment": "QIMENG_API_BASE 只能是 origin，不能带子路径、查询或片段",
            "path": "QIMENG_API_BASE 只能是 origin，不能带子路径、查询或片段",
            "port": "QIMENG_API_BASE 端口非法",
        }
        raise ConfigError(messages.get(exc.kind, "QIMENG_API_BASE 非法")) from None

    host = host.lower()
    if scheme == "http":
        try:
            address = ipaddress.ip_address(host)
            trusted = address.is_loopback or address.is_private
        except ValueError:
            trusted = host == "localhost" or host.endswith(".localhost")
        if not trusted:
            raise ConfigError("http 仅允许回环或私有地址；远程请用 https 或既有隧道")
    parts = parse.urlsplit(value.strip())
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
    """读取并校验目标实例配置；错误只列变量名。"""
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


def redact(value, secret: str):
    """兜底脱敏：即使上游错误体意外回显 token，也不输出到终端。"""
    if not secret:
        return value
    if isinstance(value, str):
        return value.replace(secret, "<redacted>")
    if isinstance(value, list):
        return [redact(item, secret) for item in value]
    if isinstance(value, dict):
        return {key: redact(item, secret) for key, item in value.items()}
    return value


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


def decode_body(raw: bytes):
    if not raw:
        return ""
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeError, ValueError):
        return raw.decode("utf-8", "replace")


def send(opener, req, *, token: str, timeout: float, max_bytes: int = MAX_RESPONSE_BYTES):
    """执行请求并返回解码后的 JSON；错误分类统一且已脱敏。"""
    try:
        with opener.open(req, timeout=timeout) as response:
            raw = response.read(max_bytes + 1)
    except error.HTTPError as exc:
        body = redact(decode_body(_read_http_error(exc)), token)
        raise HTTPStatusError(exc.code, body) from None
    except (socket.timeout, TimeoutError):
        raise NetworkError("timeout") from None
    except error.URLError as exc:
        if isinstance(exc.reason, (socket.timeout, TimeoutError)):
            raise NetworkError("timeout") from None
        raise NetworkError("network_error") from None
    except (OSError, http.client.HTTPException):
        raise NetworkError("network_error") from None

    if len(raw) > max_bytes:
        raise ResponseError("response_too_large")
    if not raw:
        return None
    try:
        return redact(json.loads(raw.decode("utf-8")), token)
    except (UnicodeError, ValueError):
        raise ResponseError("invalid_json_response") from None


class ApiClient:
    """正式 API 客户端：所有业务写入只能经这里走 /api/v1。"""

    def __init__(self, base: str, token: str, timeout: float = REQUEST_TIMEOUT_SECONDS, opener=None):
        self.base = validate_base_url(base)
        self.token = token
        self.timeout = timeout
        self.target = target_label(self.base)
        self.opener = opener or _build_opener(parse.urlsplit(self.base).scheme)

    def _headers(self) -> dict:
        return {"Authorization": "Bearer " + self.token, "Accept": "application/json"}

    def _url(self, path: str, query: dict | None = None) -> str:
        if not path.startswith("/api/v1/"):
            raise ValueError("path must start with /api/v1/")
        url = self.base + path
        if query:
            clean = {key: value for key, value in query.items() if value is not None}
            if clean:
                url += "?" + parse.urlencode(clean, doseq=True)
        return url

    def get(self, path: str, query: dict | None = None):
        req = request.Request(self._url(path, query), method="GET", headers=self._headers())
        return send(self.opener, req, token=self.token, timeout=self.timeout)

    def post(self, path: str, json_body=None, data: bytes | None = None,
             content_type: str | None = None, timeout: float | None = None):
        """POST JSON 或已编码的请求体；timeout 只用于需要长等待的命令（Lens）。"""
        headers = self._headers()
        if json_body is not None:
            body = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif data is not None:
            body = data
            headers["Content-Type"] = content_type or "application/octet-stream"
        else:
            body = b""
        req = request.Request(self._url(path), data=body, method="POST", headers=headers)
        return send(self.opener, req, token=self.token,
                    timeout=self.timeout if timeout is None else timeout)

    def put(self, path: str, json_body, timeout: float | None = None):
        body = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
        headers = dict(self._headers(), **{"Content-Type": "application/json"})
        req = request.Request(self._url(path), data=body, method="PUT", headers=headers)
        return send(self.opener, req, token=self.token,
                    timeout=self.timeout if timeout is None else timeout)

    def upload(self, path: str, stream, *, length: int, query: dict):
        """有声明长度的流式上传；沿用统一 origin/代理/重定向/错误脱敏边界。"""
        headers = dict(self._headers(), **{"Content-Type": "application/octet-stream",
                                         "Content-Length": str(length)})
        req = request.Request(self._url(path, query), data=stream, method="POST", headers=headers)
        return send(self.opener, req, token=self.token, timeout=self.timeout)

    def delete(self, path: str, timeout: float | None = None):
        req = request.Request(self._url(path), method="DELETE", headers=self._headers())
        return send(self.opener, req, token=self.token,
                    timeout=self.timeout if timeout is None else timeout)

    def download(self, path: str, target, *, max_bytes: int,
                 timeout: float | None = None) -> tuple[int, str]:
        """流式 GET 写入 target，返回 (字节数, SHA-256 十六进制)。

        与 send() 不同：不把正文读进内存、不受 2MiB JSON 上限约束，改用调用方
        给定的字节预算（超限抛 ResponseError("response_too_large") 并删除半成品）。
        target 由调用方保证是同目录临时文件；本方法不做改名/覆盖判断，原子
        rename 由调用方在成功返回后执行。错误分类与 send() 一致且已脱敏。
        """
        import hashlib

        req = request.Request(self._url(path), method="GET", headers=self._headers())
        hasher = hashlib.sha256()
        total = 0
        truncated = False
        try:
            with self.opener.open(req, timeout=self.timeout if timeout is None else timeout) as response:
                with open(target, "wb") as handle:
                    while True:
                        chunk = response.read(STREAM_CHUNK_BYTES)
                        if not chunk:
                            break
                        total += len(chunk)
                        if total > max_bytes:
                            truncated = True
                            break
                        hasher.update(chunk)
                        handle.write(chunk)
        except error.HTTPError as exc:
            body = redact(decode_body(_read_http_error(exc)), self.token)
            raise HTTPStatusError(exc.code, body) from None
        except (socket.timeout, TimeoutError):
            raise NetworkError("timeout") from None
        except error.URLError as exc:
            if isinstance(exc.reason, (socket.timeout, TimeoutError)):
                raise NetworkError("timeout") from None
            raise NetworkError("network_error") from None
        except (OSError, http.client.HTTPException):
            raise NetworkError("network_error") from None
        if truncated:
            try:
                os.unlink(target)
            except OSError:
                pass
            raise ResponseError("response_too_large")
        return total, hasher.hexdigest()
