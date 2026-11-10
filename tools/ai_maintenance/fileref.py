"""逻辑存储与文件引用（fileRef）：轻量、无数据库、无索引服务、无全盘搜索。

设计（2026-11-10 本地执行域定向优化）：

- 可信执行端用 `QIMENG_AI_STORAGES` 声明逻辑存储：
  `downloads=D:\\Downloads;inbox=E:\\Inbox`；ID 必须匹配
  `^[a-z][a-z0-9_-]{1,31}$` 且唯一；路径必须是存在目录的绝对路径。
- 模型只拿到 `{fileRef, storageId, relativePath, name, mediaType, size,
  modifiedAt}`，**绝不返回物理绝对路径**；fileRef 自描述、可确定性重建，
  不需要落盘索引。
- 文件被替换/修改后 ref 立即失效（size + mtime_ns 编码在 ref 内），重新搜索即可。
- storages 本身是可信配置的**读授权**；写入时 storage 根仍必须同时在
  `QIMENG_AI_WRITE_ROOTS`（或仓库忽略工作根）内，且根以下的链接/junction/
  reparse 段一律拒绝，fileRef 不能绕过 pathguard 的写授权。
- 配置错误只回显变量名/ID，不回显物理路径。
"""
from __future__ import annotations

import base64
import binascii
import os
import posixpath
import re
from datetime import datetime, timezone
from pathlib import Path

from . import errors, pathguard
from .media_scan import media_type

STORAGES_ENV = "QIMENG_AI_STORAGES"
MAX_REF_LENGTH = 512
STORAGE_ID_RE = re.compile(r"^[a-z][a-z0-9_-]{1,31}$")
_REF_RE = re.compile(r"^file_([A-Za-z0-9_-]+)_([0-9a-f]+)_([0-9a-f]+)$")
_B64_RE = re.compile(r"^[A-Za-z0-9_-]+$")
_WINDOWS_RESERVED = {
    "con", "prn", "aux", "nul",
    *(f"com{index}" for index in range(1, 10)),
    *(f"lpt{index}" for index in range(1, 10)),
}
_FORBIDDEN_SEGMENT_CHARS = set('<>:"|?*')


def _env_source(env):
    return os.environ if env is None else env


def _fail(message: str, *, storage_id: str | None = None):
    details = {"env": STORAGES_ENV}
    if storage_id:
        details["storageId"] = storage_id
    raise errors.configuration_error(message)


def storages(env=None) -> dict[str, Path]:
    """解析 QIMENG_AI_STORAGES；未配置返回 {}。错误只列变量名/ID。"""
    source = _env_source(env)
    raw = source.get(STORAGES_ENV, "")
    if not isinstance(raw, str) or not raw.strip():
        return {}
    result: dict[str, Path] = {}
    for item in raw.replace(";", ",").split(","):
        text = item.strip()
        if not text:
            continue
        if any(ord(ch) < 32 for ch in text):
            _fail(STORAGES_ENV + " 含控制字符")
        name, separator, value = text.partition("=")
        name = name.strip()
        value = value.strip()
        if not separator or not name or not value:
            _fail(STORAGES_ENV + " 条目必须为 storageId=绝对路径")
        if not STORAGE_ID_RE.match(name):
            _fail(STORAGES_ENV + " 的 storageId 非法（需匹配 ^[a-z][a-z0-9_-]{1,31}$）",
                  storage_id=name[:64])
        if name in result:
            _fail(STORAGES_ENV + " 存在重复 storageId", storage_id=name)
        candidate = Path(value)
        if not candidate.is_absolute():
            _fail(STORAGES_ENV + " 中 storage 的路径必须是绝对路径", storage_id=name)
        resolved = candidate.resolve(strict=False)
        if not resolved.is_dir():
            _fail(STORAGES_ENV + " 中 storage 的路径必须是存在的目录", storage_id=name)
        result[name] = resolved
    return result


def storage_info(env=None) -> list[dict]:
    """逻辑存储公开信息；绝不返回物理绝对路径。"""
    write_roots = tuple(pathguard.write_roots(env))
    rows = []
    for storage_id, root in storages(env).items():
        rows.append({
            "storageId": storage_id,
            "writable": any(root == item or _within(root, item) for item in write_roots),
        })
    return rows


def _within(path: Path, root: Path) -> bool:
    try:
        Path(path).relative_to(Path(root))
        return True
    except ValueError:
        return False


def _b64encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _b64decode(value: str) -> bytes:
    if not _B64_RE.match(value):
        raise errors.file_ref_invalid("fileRef 载荷不是合法 base64url")
    padding = "=" * (-len(value) % 4)
    try:
        return base64.urlsafe_b64decode(value + padding)
    except (binascii.Error, ValueError):
        raise errors.file_ref_invalid("fileRef 载荷无法解码") from None


def _validate_relative_path(relative_path: str) -> None:
    if not isinstance(relative_path, str) or not relative_path:
        raise errors.file_ref_invalid("fileRef 的 relativePath 不能为空")
    if any(ord(ch) < 32 for ch in relative_path):
        raise errors.file_ref_invalid("fileRef 的 relativePath 含控制字符")
    if "\\" in relative_path:
        raise errors.file_ref_invalid("fileRef 的 relativePath 必须使用 POSIX 分隔符")
    if relative_path.startswith("/") or re.match(r"^[A-Za-z]:", relative_path):
        raise errors.file_ref_invalid("fileRef 的 relativePath 不能是绝对路径")
    if "\x00" in relative_path:
        raise errors.file_ref_invalid("fileRef 的 relativePath 含 NUL")
    parts = relative_path.split("/")
    if any(not part for part in parts):
        raise errors.file_ref_invalid("fileRef 的 relativePath 含空路径段")
    for part in parts:
        if part in (".", ".."):
            raise errors.file_ref_invalid("fileRef 的 relativePath 不能包含 . 或 ..")
        if part != part.strip() or part.endswith("."):
            raise errors.file_ref_invalid("fileRef 的 relativePath 含不安全的路径段")
        if any(ch in _FORBIDDEN_SEGMENT_CHARS for ch in part):
            raise errors.file_ref_invalid("fileRef 的 relativePath 含不安全字符")
        stem = part.split(".", 1)[0].casefold()
        if stem in _WINDOWS_RESERVED:
            raise errors.file_ref_invalid("fileRef 的 relativePath 含 Windows 保留名")
    normalized = posixpath.normpath(relative_path)
    if normalized != relative_path:
        raise errors.file_ref_invalid("fileRef 的 relativePath 不是规范形式")


def _encode_ref(storage_id: str, relative_path: str, size: int, mtime_ns: int) -> str:
    payload = _b64encode(storage_id.encode("utf-8") + b"\x00" + relative_path.encode("utf-8"))
    ref = f"file_{payload}_{size:x}_{mtime_ns:x}"
    if len(ref) > MAX_REF_LENGTH:
        raise errors.file_ref_invalid(
            "fileRef 超过长度上限", details={"maxLength": MAX_REF_LENGTH})
    return ref


def _decode_ref(ref) -> tuple[str, str, int, int]:
    if not isinstance(ref, str) or not ref.strip():
        raise errors.file_ref_invalid("fileRef 必须是非空字符串")
    text = ref.strip()
    if len(text) > MAX_REF_LENGTH:
        raise errors.file_ref_invalid("fileRef 超过长度上限",
                                      details={"maxLength": MAX_REF_LENGTH})
    if any(ord(ch) < 32 for ch in text):
        raise errors.file_ref_invalid("fileRef 含控制字符")
    match = _REF_RE.match(text)
    if not match:
        raise errors.file_ref_invalid("fileRef 结构非法")
    payload_text, size_hex, mtime_hex = match.groups()
    payload = _b64decode(payload_text)
    if b"\x00" not in payload:
        raise errors.file_ref_invalid("fileRef 载荷缺少分隔符")
    raw_id, _, raw_path = payload.partition(b"\x00")
    try:
        decoded_id = raw_id.decode("utf-8")
        relative_path = raw_path.decode("utf-8")
    except UnicodeDecodeError:
        raise errors.file_ref_invalid("fileRef 载荷不是 UTF-8") from None
    if not STORAGE_ID_RE.match(decoded_id):
        raise errors.file_ref_invalid("fileRef 的 storageId 非法")
    _validate_relative_path(relative_path)
    try:
        size = int(size_hex, 16)
        mtime_ns = int(mtime_hex, 16)
    except ValueError:
        raise errors.file_ref_invalid("fileRef 的 size/mtime 编码非法") from None
    return decoded_id, relative_path, size, mtime_ns


def _metadata(storage_id: str, relative_path: str, path: Path, stat_result=None) -> dict:
    info = stat_result or path.stat()
    modified = datetime.fromtimestamp(info.st_mtime_ns / 1_000_000_000,
                                      tz=timezone.utc)
    return {
        "fileRef": _encode_ref(storage_id, relative_path,
                               int(info.st_size), int(info.st_mtime_ns)),
        "storageId": storage_id,
        "relativePath": relative_path,
        "name": path.name,
        "mediaType": media_type(path.name),
        "size": int(info.st_size),
        "modifiedAt": modified.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
    }


def make_ref(path, *, env=None) -> dict:
    """为已配置 storage 内的文件生成 fileRef 与精简元数据（无物理路径）。"""
    candidate = Path(path)
    if not candidate.is_absolute():
        raise errors.file_ref_invalid("make_ref 需要绝对路径")
    try:
        resolved = candidate.resolve(strict=True)
    except OSError:
        raise errors.file_ref_stale("文件不存在，无法生成 fileRef") from None
    if not resolved.is_file():
        raise errors.file_ref_invalid("make_ref 只接受普通文件")
    configured = storages(env)
    matches = []
    for storage_id, root in configured.items():
        if _within(resolved, root):
            matches.append((len(root.parts), storage_id, root))
    if not matches:
        raise errors.file_ref_invalid("文件不在任何已配置 storage 内",
                                      details={"env": STORAGES_ENV})
    _length, storage_id, root = max(matches, key=lambda item: (item[0], item[1]))
    relative_path = resolved.relative_to(root).as_posix()
    _validate_relative_path(relative_path)
    # storages 是可信配置的读授权：make_ref 只读取元数据，不再要求额外读根，
    # 也不要求写授权（写会在 resolve_ref(mode="write") 时单独检查 WRITE_ROOTS）。
    return _metadata(storage_id, relative_path, resolved)


def _resolve(ref, *, env=None, mode: str = "read") -> tuple[Path, str, str, int, int]:
    storage_id, relative_path, size, mtime_ns = _decode_ref(ref)
    configured = storages(env)
    if storage_id not in configured:
        raise errors.file_ref_unavailable(
            "fileRef 引用的 storage 未配置或已移除",
            details={"storageId": storage_id, "env": STORAGES_ENV},
        )
    root = configured[storage_id]
    candidate = root.joinpath(*relative_path.split("/"))
    resolved = candidate.resolve(strict=False)
    if not _within(resolved, root):
        raise errors.path_unsafe("fileRef 解析后越出 storage 根",
                                 details={"storageId": storage_id})
    # 读：storages 是可信配置的读授权；写：storage 根必须同时在写授权根内，
    # 且根以下的链接/junction/reparse 段一律拒绝。
    if mode == "write":
        pathguard.authorize_existing(candidate, mode="write", env=env, kind="fileRef")
    if not resolved.is_file():
        raise errors.file_ref_stale("fileRef 指向的文件已不存在",
                                    details={"storageId": storage_id,
                                             "relativePath": relative_path})
    try:
        info = resolved.stat()
    except OSError:
        raise errors.file_ref_stale("fileRef 指向的文件不可访问") from None
    if int(info.st_size) != size or int(info.st_mtime_ns) != mtime_ns:
        raise errors.file_ref_stale(
            "fileRef 已失效：文件大小或修改时间已变化",
            details={"storageId": storage_id, "relativePath": relative_path,
                     "expectedBytes": size, "actualBytes": int(info.st_size)},
        )
    return resolved, storage_id, relative_path, size, mtime_ns


def resolve_ref(ref, *, env=None, mode: str = "read") -> Path:
    """把 fileRef 解析为授权路径；失效/未配置/非法分别抛稳定错误码。"""
    path, _storage_id, _relative_path, _size, _mtime = _resolve(
        ref, env=env, mode=mode)
    return path


def describe_ref(ref, *, env=None) -> dict:
    """校验并返回精简元数据（无物理路径）。"""
    path, storage_id, relative_path, _size, _mtime = _resolve(ref, env=env, mode="read")
    return _metadata(storage_id, relative_path, path)


def resolve_input(path_value=None, file_ref=None, *, env=None, mode: str = "read",
                  must_exist: bool = True, kind: str = "路径") -> Path:
    """path 与 fileRef 二选一；都缺/都给都按 invalid_input 拒绝。"""
    has_path = path_value not in (None, "")
    has_ref = file_ref not in (None, "")
    if has_path and has_ref:
        raise errors.invalid_input(f"{kind} 不能同时提供 path 与 fileRef")
    if not has_path and not has_ref:
        raise errors.invalid_input(f"{kind} 必须提供 path 或 fileRef")
    if has_ref:
        return resolve_ref(file_ref, env=env, mode=mode)
    return pathguard.resolve_local_path(path_value, env=env, must_exist=must_exist,
                                        kind=kind, mode=mode)
