"""受控研究产物引用（artifactRef）：模型无需本机绝对路径即可接续研究。

`artifactRef = a1_<32hex>` 与现有引用语义分开：

- fileRef：可信执行端配置的本地输入文件；
- resultRef：被省略的完整工具结果，供 `platform.result.read` 续读；
- planRef：完整计划，仅供可信执行端原样执行；
- artifactRef：某个具体研究产物（抽帧、研究 JSON/JSONL、候选摘要、截图）的
  只读引用，不是文件写入授权，也不是计划执行授权。

存储复用 `model_output` 的 `STATE_DIR/<model-output>/<实例+凭据>/` 根、同一
`.model-output-integrity-key`、`local_state.lock_file` 与原子发布思路；清单放在
该根的 `artifacts/` 子目录，`publish_bytes`/`publish_json` 的私有副本放在
`artifacts/blobs/`。清单用 HMAC 校验，不保存绝对路径、token 或原始大载荷。

发布时绑定 API origin/凭据摘要、平台/工具契约/规则版本、来源工具、授权根
（规范化路径的 SHA-256 前缀）、相对路径、大小、SHA-256、MIME、标签、来源步骤
与有效期；读取时重新核对实例/凭据/版本、HMAC、TTL、根是否仍被授权、路径是否
仍在根内，以及文件大小与 SHA-256 是否仍与清单一致。文件被替换、篡改、过期、
跨实例/凭据或根移除都会返回稳定错误码。
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import posixpath
import re
import secrets
import stat
import tempfile
import time
from pathlib import Path

from . import errors, local_state, model_output, pathguard

ARTIFACT_REF_PREFIX = "a1_"
MAX_REF_LENGTH = 64
MANIFEST_FORMAT_VERSION = 1
MANIFEST_SUFFIX = ".json"
BLOB_SUFFIX = ".bin"
MAX_MANIFEST_BYTES = 32 * 1024
MAX_ARTIFACT_BYTES = 32 * 1024 * 1024
MAX_BLOB_TOTAL_BYTES = 64 * 1024 * 1024
MAX_ARTIFACTS = 256
DEFAULT_TTL_SECONDS = 24 * 60 * 60
MIN_TTL_SECONDS = 1
MAX_TTL_SECONDS = 7 * 24 * 60 * 60
MAX_CLEAN_PER_PUBLISH = 64
MAX_PAGE_BYTES = 12 * 1024
TEXT_PAGE_JSON_BYTES = 9 * 1024
MAX_IMAGE_BASE64_CHARS = 2 * 1024 * 1024
MAX_IMAGE_BYTES = (MAX_IMAGE_BASE64_CHARS // 4) * 3
MAX_LABEL_LENGTH = 200
MAX_SOURCE_STEP_LENGTH = 200
MAX_RELATIVE_PATH_LENGTH = 4096

_REF_RE = re.compile(r"^a1_([0-9a-f]{32})$")
_SAFE_KIND = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")
_SAFE_TOOL = re.compile(r"^[A-Za-z0-9_.:-]{1,160}$")
_SAFE_MIME = re.compile(r"^[a-z0-9][a-z0-9.+-]{0,63}/[a-z0-9][a-z0-9.+-]{0,63}$")
_SAFE_STORAGE = frozenset({"root", "state"})
_FORBIDDEN_SEGMENT_CHARS = set('<>:"|?*')
_MIME_BY_EXTENSION = {
    ".json": "application/json",
    ".jsonl": "application/x-ndjson",
    ".ndjson": "application/x-ndjson",
    ".txt": "text/plain",
    ".md": "text/markdown",
    ".csv": "text/csv",
    ".log": "text/plain",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".gif": "image/gif",
    ".avif": "image/avif",
    ".bmp": "image/bmp",
    ".mp4": "video/mp4",
    ".webm": "video/webm",
    ".mov": "video/quicktime",
    ".mkv": "video/x-matroska",
    ".wav": "audio/wav",
    ".mp3": "audio/mpeg",
}
_TEXT_MIMES = frozenset({
    "application/json", "application/x-ndjson", "application/jsonl",
    "application/xml", "application/yaml", "application/x-yaml",
})


def _env_source(env) -> dict:
    return dict(os.environ if env is None else env)


def _within(path: Path, root: Path) -> bool:
    try:
        Path(path).relative_to(Path(root))
        return True
    except ValueError:
        return False


def _normcase(value) -> str:
    return os.path.normcase(str(value))


def _mapped_store_error(exc: model_output.ModelOutputError, action: str):
    code = str(getattr(exc, "code", "") or "")
    if code in ("RESULT_CONTEXT_INVALID",):
        return errors.artifact_context_invalid(
            "缺少有效 API 实例/凭据配置，无法" + action + "产物引用")
    if code in ("RESULT_STATE_DIR_INVALID", "RESULT_STORE_INVALID"):
        return errors.artifact_store_invalid("产物存储目录不可用或校验失败，无法" + action)
    return errors.artifact_store_unavailable("产物存储不可用，无法" + action)


def _base_binding(env, rulepack_version: str, tool_id: str) -> dict:
    if not isinstance(rulepack_version, str) or not rulepack_version:
        raise errors.artifact_context_invalid("当前上下文缺少规则版本，无法处理产物引用")
    try:
        return model_output._binding(env, rulepack_version, tool_id)
    except model_output.ModelOutputError as exc:
        raise _mapped_store_error(exc, "处理") from None


def _state(env, binding: dict):
    try:
        root = model_output._state_root(env, binding)
    except model_output.ModelOutputError as exc:
        raise _mapped_store_error(exc, "访问") from None
    artifacts = root / "artifacts"
    blobs = artifacts / "blobs"
    try:
        for directory in (artifacts, blobs):
            if directory.exists() and pathguard.is_linkish(directory):
                raise errors.artifact_store_invalid("产物目录包含链接或重解析点")
            if directory.exists() and not directory.is_dir():
                raise errors.artifact_store_invalid("产物目录不可用")
            directory.mkdir(exist_ok=True)
            resolved = directory.resolve(strict=True)
            resolved.relative_to(root)
    except errors.ToolError:
        raise
    except (OSError, ValueError):
        raise errors.artifact_store_invalid("产物目录不可用或越过状态根") from None
    return root, artifacts, blobs


def _trusted_root_entries(env) -> list[tuple[Path, Path]]:
    """读写可信根的 (原始写法, 真实路径)；只保留仍存在的真实根。"""
    entries: list[tuple[Path, Path]] = []
    seen: set[tuple[str, str]] = set()
    try:
        configured = pathguard.trusted_root_entries(env)
    except errors.ToolError:
        raise errors.artifact_root_unavailable("可信读写根配置无效，无法核对产物位置") from None
    for raw, resolved in configured:
        resolved = Path(resolved).resolve(strict=False)
        key = (_normcase(raw), _normcase(resolved))
        if key in seen or not resolved.is_dir():
            continue
        seen.add(key)
        entries.append((Path(raw), resolved))
    return entries


def _trusted_roots(env) -> list[Path]:
    roots: list[Path] = []
    seen: set[str] = set()
    for _raw, resolved in _trusted_root_entries(env):
        key = _normcase(resolved)
        if key in seen:
            continue
        seen.add(key)
        roots.append(resolved)
    return roots


def _root_id(root: Path) -> str:
    normalized = _normcase(Path(root).resolve(strict=False))
    return hashlib.sha256(normalized.encode("utf-8", "surrogatepass")).hexdigest()[:32]


def _validate_relative_path(relative_path) -> str:
    if not isinstance(relative_path, str) or not relative_path:
        raise errors.artifact_path_unsafe("产物相对路径为空")
    if len(relative_path) > MAX_RELATIVE_PATH_LENGTH:
        raise errors.artifact_path_unsafe("产物相对路径超过长度上限")
    if any(ord(ch) < 32 for ch in relative_path) or "\x00" in relative_path:
        raise errors.artifact_path_unsafe("产物相对路径含控制字符")
    if "\\" in relative_path:
        raise errors.artifact_path_unsafe("产物相对路径必须使用 POSIX 分隔符")
    if relative_path.startswith("/") or re.match(r"^[A-Za-z]:", relative_path):
        raise errors.artifact_path_unsafe("产物相对路径不能是绝对路径")
    parts = relative_path.split("/")
    if any(not part for part in parts):
        raise errors.artifact_path_unsafe("产物相对路径含空路径段")
    for part in parts:
        if part in (".", ".."):
            raise errors.artifact_path_unsafe("产物相对路径不能包含 . 或 ..")
        if part != part.strip() or part.endswith("."):
            raise errors.artifact_path_unsafe("产物相对路径含不安全的路径段")
        if any(ch in _FORBIDDEN_SEGMENT_CHARS for ch in part):
            raise errors.artifact_path_unsafe("产物相对路径含不安全字符")
    if posixpath.normpath(relative_path) != relative_path:
        raise errors.artifact_path_unsafe("产物相对路径不是规范形式")
    return relative_path


def _assert_no_link_segments(root: Path, target: Path) -> None:
    """拒绝授权根以下的符号链接/junction/reparse 段；根本身允许是链接。"""
    try:
        relative = Path(target).relative_to(root)
    except ValueError:
        raise errors.artifact_path_unsafe("产物解析后越出授权根") from None
    current = Path(root)
    for part in relative.parts:
        if part in ("", "."):
            continue
        current = current / part
        if pathguard.is_linkish(current):
            raise errors.artifact_path_unsafe("产物路径包含符号链接/junction/reparse 段")


def _infer_mime(path: Path) -> str:
    return _MIME_BY_EXTENSION.get(Path(path).suffix.lower(), "application/octet-stream")


def _validate_mime(mime, path: Path | None = None) -> str:
    if mime in (None, ""):
        mime = _infer_mime(path) if path is not None else "application/octet-stream"
    if not isinstance(mime, str):
        raise errors.invalid_input("mime 必须是字符串")
    mime = mime.strip().lower()
    if not _SAFE_MIME.fullmatch(mime):
        raise errors.invalid_input("mime 格式非法")
    return mime


def _validate_kind(kind) -> str:
    if not isinstance(kind, str) or not _SAFE_KIND.fullmatch(kind):
        raise errors.invalid_input("kind 必须匹配 ^[A-Za-z0-9_.:-]{1,64}$")
    return kind


def _safe_text(value, *, limit: int, fallback: str = "") -> str:
    if value is None:
        return fallback
    if not isinstance(value, str):
        raise errors.invalid_input("label/sourceStep 必须是字符串")
    cleaned = re.sub(r"\s+", " ", value).strip()
    if any(ord(ch) < 32 for ch in cleaned):
        cleaned = "".join(ch for ch in cleaned if ord(ch) >= 32)
    return cleaned[:limit] or fallback


def _safe_manifest_text(value, *, limit: int, fallback: str = "", token: str = "") -> str:
    """清单中的标签/步骤不得保存本机绝对路径或凭据。"""
    text = _safe_text(value, limit=limit, fallback=fallback)
    safe, _flags = model_output._model_safe(text, token=token)
    return str(safe)[:limit] or fallback


def _validate_ttl(ttl_seconds) -> int:
    if type(ttl_seconds) is not int or not MIN_TTL_SECONDS <= ttl_seconds <= MAX_TTL_SECONDS:
        raise errors.invalid_input(
            f"ttlSeconds 必须在 {MIN_TTL_SECONDS}..{MAX_TTL_SECONDS}")
    return ttl_seconds


def _validate_ref(reference) -> str:
    if not isinstance(reference, str) or len(reference) > MAX_REF_LENGTH \
            or not _REF_RE.fullmatch(reference):
        raise errors.artifact_ref_invalid("artifactRef 格式非法")
    return reference


def _manifest_path(artifacts: Path, reference: str) -> Path:
    return artifacts / (reference + MANIFEST_SUFFIX)


def _blob_path(blobs: Path, reference: str) -> Path:
    return blobs / (reference + BLOB_SUFFIX)


def _mac_payload(manifest: dict) -> bytes:
    body = {key: value for key, value in manifest.items() if key != "integrityMac"}
    return model_output._json_bytes(body)


def _is_text_mime(mime: str) -> bool:
    return mime.startswith("text/") or mime in _TEXT_MIMES or mime.endswith("+json")


def _is_image_mime(mime: str) -> bool:
    return mime.startswith("image/")


def _published_metadata(reference: str, binding: dict) -> dict:
    return {
        "artifactRef": reference,
        "kind": binding.get("kind"),
        "label": binding.get("label"),
        "mime": binding.get("mime"),
        "bytes": binding.get("size"),
        "sha256": binding.get("sha256"),
        "sourceStep": binding.get("sourceStep"),
        "createdAt": binding.get("createdAt"),
        "expiresAt": binding.get("expiresAt"),
    }


def _cleanup_temps(artifacts: Path, blobs: Path) -> None:
    for directory, pattern in ((artifacts, ".artifact-*.tmp"), (blobs, ".blob-*.tmp")):
        for temporary in directory.glob(pattern):
            try:
                temporary.unlink()
            except OSError:
                raise errors.artifact_store_unavailable("产物临时文件无法清理") from None


def _expired_manifest(path: Path, now: float) -> bool:
    if pathguard.is_linkish(path):
        return False
    try:
        info = path.stat()
    except OSError:
        return False
    if not stat.S_ISREG(info.st_mode):
        return False
    expires_at = None
    try:
        if info.st_size <= MAX_MANIFEST_BYTES:
            raw = path.read_bytes()
            manifest = json.loads(raw.decode("utf-8"))
            binding = manifest.get("binding") if isinstance(manifest, dict) else None
            if isinstance(binding, dict):
                expires_at = int(binding.get("expiresAt") or 0)
    except (OSError, UnicodeError, ValueError, TypeError):
        expires_at = None
    if expires_at is not None:
        return now > expires_at
    # 清单损坏时按文件年龄兜底，避免永久占用缓存名额。
    return now - info.st_mtime > MAX_TTL_SECONDS


def _clean_expired(artifacts: Path, blobs: Path, now: float) -> None:
    removed = 0
    for path in sorted(artifacts.glob(ARTIFACT_REF_PREFIX + "*" + MANIFEST_SUFFIX)):
        if removed >= MAX_CLEAN_PER_PUBLISH:
            break
        if not _expired_manifest(path, now):
            continue
        reference = path.name[:-len(MANIFEST_SUFFIX)]
        try:
            path.unlink()
        except OSError:
            continue
        blob = _blob_path(blobs, reference)
        if blob.is_file() and not pathguard.is_linkish(blob):
            try:
                blob.unlink()
            except OSError:
                pass
        removed += 1


def _manifest_files(artifacts: Path) -> list[Path]:
    return [path for path in artifacts.glob(ARTIFACT_REF_PREFIX + "*" + MANIFEST_SUFFIX)
            if not pathguard.is_linkish(path) and path.is_file()]


def _blob_bytes(blobs: Path) -> int:
    total = 0
    for path in blobs.glob(ARTIFACT_REF_PREFIX + "*" + BLOB_SUFFIX):
        if pathguard.is_linkish(path):
            raise errors.artifact_store_invalid("产物私有副本包含链接或重解析点")
        try:
            info = path.stat()
        except OSError:
            raise errors.artifact_store_unavailable("产物私有副本容量无法核对") from None
        if stat.S_ISREG(info.st_mode):
            total += int(info.st_size)
    return total


def _publish_manifest(base_binding: dict, binding_fields: dict, env, *,
                      blob_bytes: bytes | None = None) -> dict:
    root, artifacts, blobs = _state(env, base_binding)
    try:
        with local_state.lock_file(root / ".artifacts.lock"):
            _cleanup_temps(artifacts, blobs)
            now = int(time.time())
            _clean_expired(artifacts, blobs, now)
            if len(_manifest_files(artifacts)) >= MAX_ARTIFACTS:
                raise errors.artifact_store_limit(
                    "产物引用缓存达到数量限制，请先等待旧引用过期或减少发布数量")
            if blob_bytes is not None:
                if len(blob_bytes) > MAX_ARTIFACT_BYTES:
                    raise errors.artifact_too_large("产物超过单个 artifact 的 32 MiB 上限")
                if _blob_bytes(blobs) + len(blob_bytes) > MAX_BLOB_TOTAL_BYTES:
                    raise errors.artifact_store_limit(
                        "产物私有副本达到容量限制，请先等待旧引用过期")
            reference = ARTIFACT_REF_PREFIX + secrets.token_hex(16)
            binding = dict(binding_fields)
            binding["createdAt"] = now
            binding["expiresAt"] = now + int(binding.pop("_ttlSeconds", DEFAULT_TTL_SECONDS))
            blob_written = False
            try:
                if blob_bytes is not None:
                    binding["storage"] = "state"
                    binding["rootId"] = ""
                    binding["relativePath"] = "blobs/" + reference + BLOB_SUFFIX
                    _write_blob(blobs, reference, blob_bytes)
                    blob_written = True
                else:
                    _validate_relative_path(binding.get("relativePath"))
                manifest = {
                    "formatVersion": MANIFEST_FORMAT_VERSION,
                    "artifactRef": reference,
                    "binding": binding,
                }
                key = _key_for(root)
                manifest["integrityMac"] = hmac.new(
                    key, _mac_payload(manifest), hashlib.sha256).hexdigest()
                _write_manifest(artifacts, reference, manifest)
            except Exception:
                if blob_written:
                    try:
                        _blob_path(blobs, reference).unlink()
                    except OSError:
                        pass
                raise
            return _published_metadata(reference, binding)
    except model_output.ModelOutputError as exc:
        raise _mapped_store_error(exc, "访问") from None
    except errors.ToolError:
        raise
    except local_state.StateLockError:
        raise errors.artifact_store_unavailable("产物缓存被占用，未发布新引用") from None


def _key_for(root: Path) -> bytes:
    try:
        return model_output._key_file(root)
    except model_output.ModelOutputError as exc:
        raise _mapped_store_error(exc, "访问") from None


def _write_blob(blobs: Path, reference: str, payload: bytes) -> None:
    target = _blob_path(blobs, reference)
    descriptor = None
    temporary = None
    try:
        descriptor, name = tempfile.mkstemp(prefix=".blob-", suffix=".tmp", dir=str(blobs))
        temporary = Path(name)
        with os.fdopen(descriptor, "wb") as handle:
            descriptor = None
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        if target.exists() or pathguard.is_linkish(target):
            raise errors.artifact_store_invalid("产物标识冲突，未覆盖已有数据")
        os.link(temporary, target)
        temporary.unlink()
        temporary = None
    except errors.ToolError:
        raise
    except OSError:
        raise errors.artifact_store_unavailable("产物私有副本无法安全保存") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary is not None:
            try:
                temporary.unlink()
            except OSError:
                pass


def _write_manifest(artifacts: Path, reference: str, manifest: dict) -> None:
    target = _manifest_path(artifacts, reference)
    payload = model_output._json_bytes(manifest)
    if len(payload) > MAX_MANIFEST_BYTES:
        raise errors.artifact_store_invalid("产物清单超过大小上限")
    descriptor = None
    temporary = None
    try:
        descriptor, name = tempfile.mkstemp(prefix=".artifact-", suffix=".tmp",
                                            dir=str(artifacts))
        temporary = Path(name)
        with os.fdopen(descriptor, "wb") as handle:
            descriptor = None
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        if target.exists() or pathguard.is_linkish(target):
            raise errors.artifact_store_invalid("产物标识冲突，未覆盖已有数据")
        os.link(temporary, target)
        temporary.unlink()
        temporary = None
    except errors.ToolError:
        raise
    except OSError:
        raise errors.artifact_store_unavailable("产物清单无法安全保存") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary is not None:
            try:
                temporary.unlink()
            except OSError:
                pass


def publish_file(path, *, env=None, tool_id: str, rulepack_version: str,
                 kind: str = "artifact", label: str = "", mime: str = "",
                 source_step: str = "",
                 ttl_seconds: int = DEFAULT_TTL_SECONDS) -> dict:
    """发布受信根内的普通文件为 artifactRef；不复制文件，只保存身份绑定。"""
    source_env = _env_source(env)
    kind = _validate_kind(kind)
    ttl_seconds = _validate_ttl(ttl_seconds)
    label = _safe_manifest_text(label, limit=MAX_LABEL_LENGTH,
                                fallback=Path(str(path)).name,
                                token=str(source_env.get("QIMENG_API_TOKEN") or ""))
    source_step = _safe_manifest_text(
        source_step, limit=MAX_SOURCE_STEP_LENGTH,
        token=str(source_env.get("QIMENG_API_TOKEN") or ""))
    if not isinstance(tool_id, str) or not _SAFE_TOOL.fullmatch(tool_id):
        raise errors.invalid_input("toolId 非法")
    if not isinstance(path, (str, os.PathLike)):
        raise errors.invalid_input("path 非法")
    text_path = os.fspath(path)
    if not isinstance(text_path, str) or not text_path.strip():
        raise errors.invalid_input("path 不能为空")
    if any(ord(ch) < 32 for ch in text_path):
        raise errors.artifact_path_unsafe("产物路径含控制字符")
    candidate = Path(text_path)
    if not candidate.is_absolute():
        raise errors.invalid_input("产物路径必须是绝对路径")
    if any(part in (".", "..") for part in candidate.parts):
        raise errors.artifact_path_unsafe("产物路径不能包含 . 或 ..")
    try:
        resolved = candidate.resolve(strict=True)
    except OSError:
        raise errors.artifact_content_changed("产物不存在或不可访问") from None
    if not resolved.is_file():
        raise errors.artifact_path_unsafe("artifactRef 只接受普通文件")
    roots = [item for item in _trusted_root_entries(source_env)
             if _within(resolved, item[1])]
    if not roots:
        raise errors.artifact_path_unsafe("产物不在任何受信读写根内")
    root = max((item[1] for item in roots), key=lambda item: len(item.parts))
    checked = False
    for raw_root, resolved_root in roots:
        if _normcase(resolved_root) != _normcase(root):
            continue
        if _within(candidate, raw_root):
            _assert_no_link_segments(raw_root, candidate)
            checked = True
            break
    if not checked:
        if _normcase(candidate) == _normcase(resolved):
            _assert_no_link_segments(root, resolved)
        else:
            # 输入路径本身经链接别名指向根内：拒绝，避免把链接身份带进引用。
            raise errors.artifact_path_unsafe("产物路径包含链接别名")
    if pathguard.is_linkish(resolved):
        raise errors.artifact_path_unsafe("artifactRef 不接受符号链接/junction/reparse 文件")
    try:
        digest, size = pathguard.sha256_file(resolved, root=root)
    except errors.ToolError:
        raise errors.artifact_content_changed("产物无法读取或已被替换") from None
    if pathguard.is_linkish(resolved):
        raise errors.artifact_path_unsafe("artifactRef 不接受符号链接/junction/reparse 文件")
    if size > MAX_ARTIFACT_BYTES:
        raise errors.artifact_too_large("产物超过单个 artifact 的 32 MiB 上限")
    mime = _validate_mime(mime, resolved)
    relative = _validate_relative_path(resolved.relative_to(root).as_posix())
    base = _base_binding(source_env, rulepack_version, tool_id)
    binding = {
        **base,
        "sourceTool": tool_id,
        "storage": "root",
        "rootId": _root_id(root),
        "relativePath": relative,
        "size": int(size),
        "sha256": digest,
        "kind": kind,
        "label": label,
        "mime": mime,
        "sourceStep": source_step,
        "_ttlSeconds": ttl_seconds,
    }
    return _publish_manifest(base, binding, source_env)


def publish_bytes(data, *, env=None, tool_id: str, rulepack_version: str,
                  kind: str = "artifact", label: str = "", mime: str = "",
                  source_step: str = "",
                  ttl_seconds: int = DEFAULT_TTL_SECONDS) -> dict:
    """把私有字节副本发布为 artifactRef；不写入任何受信文件的读取授权。"""
    source_env = _env_source(env)
    kind = _validate_kind(kind)
    ttl_seconds = _validate_ttl(ttl_seconds)
    if not isinstance(data, (bytes, bytearray, memoryview)):
        raise errors.invalid_input("data 必须是 bytes")
    payload = bytes(data)
    if len(payload) > MAX_ARTIFACT_BYTES:
        raise errors.artifact_too_large("产物超过单个 artifact 的 32 MiB 上限")
    label = _safe_manifest_text(label, limit=MAX_LABEL_LENGTH, fallback=kind,
                                token=str(source_env.get("QIMENG_API_TOKEN") or ""))
    source_step = _safe_manifest_text(
        source_step, limit=MAX_SOURCE_STEP_LENGTH,
        token=str(source_env.get("QIMENG_API_TOKEN") or ""))
    if not isinstance(tool_id, str) or not _SAFE_TOOL.fullmatch(tool_id):
        raise errors.invalid_input("toolId 非法")
    mime = _validate_mime(mime)
    base = _base_binding(source_env, rulepack_version, tool_id)
    binding = {
        **base,
        "sourceTool": tool_id,
        "storage": "state",
        "rootId": "",
        "relativePath": "",
        "size": len(payload),
        "sha256": hashlib.sha256(payload).hexdigest(),
        "kind": kind,
        "label": label,
        "mime": mime,
        "sourceStep": source_step,
        "_ttlSeconds": ttl_seconds,
    }
    return _publish_manifest(base, binding, source_env, blob_bytes=payload)


def publish_json(value, *, env=None, tool_id: str, rulepack_version: str,
                 kind: str = "json", label: str = "", mime: str = "application/json",
                 source_step: str = "",
                 ttl_seconds: int = DEFAULT_TTL_SECONDS) -> dict:
    """把可序列化 JSON 值作为私有 artifact 发布（不含绝对路径元数据）。"""
    try:
        payload = model_output._json_bytes(value)
    except model_output.ModelOutputError:
        raise errors.invalid_input("value 不是可保存的 JSON 数据") from None
    return publish_bytes(payload, env=env, tool_id=tool_id,
                         rulepack_version=rulepack_version, kind=kind, label=label,
                         mime=mime, source_step=source_step, ttl_seconds=ttl_seconds)


def _reference_in_other_context(root: Path, reference: str) -> bool:
    """检查同一状态根下其他实例/凭据是否已有同名清单；只用于稳定错误码，不读取内容。"""
    try:
        entries = list(root.parent.iterdir())
    except OSError:
        return False
    for entry in entries[:128]:
        if entry == root or pathguard.is_linkish(entry) or not entry.is_dir():
            continue
        candidate = entry / "artifacts" / (reference + MANIFEST_SUFFIX)
        if not pathguard.is_linkish(candidate) and candidate.is_file():
            return True
    return False


def _load_manifest(reference: str, env, rulepack_version: str):
    reference = _validate_ref(reference)
    base = _base_binding(env, rulepack_version, "platform.artifact.read")
    root, artifacts, _blobs = _state(env, base)
    path = _manifest_path(artifacts, reference)
    if pathguard.is_linkish(path):
        raise errors.artifact_tampered("产物清单完整性校验失败")
    try:
        info = path.stat()
    except OSError:
        if _reference_in_other_context(root, reference):
            raise errors.artifact_binding_mismatch(
                "artifactRef 属于其他实例/凭据，不能跨上下文使用") from None
        raise errors.artifact_not_found("artifactRef 不存在或已过期") from None
    if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_MANIFEST_BYTES:
        raise errors.artifact_tampered("产物清单完整性校验失败")
    try:
        raw = path.read_bytes()
        manifest = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeError, ValueError):
        raise errors.artifact_tampered("产物清单完整性校验失败") from None
    if not isinstance(manifest, dict) \
            or manifest.get("formatVersion") != MANIFEST_FORMAT_VERSION \
            or manifest.get("artifactRef") != reference:
        raise errors.artifact_tampered("产物清单完整性校验失败")
    binding = manifest.get("binding")
    if not isinstance(binding, dict):
        raise errors.artifact_tampered("产物清单完整性校验失败")
    source_tool = binding.get("sourceTool")
    if not isinstance(source_tool, str) or not _SAFE_TOOL.fullmatch(source_tool):
        raise errors.artifact_tampered("产物清单完整性校验失败")
    expected = _base_binding(env, rulepack_version, source_tool)
    for key, value in expected.items():
        if binding.get(key) != value:
            raise errors.artifact_binding_mismatch(
                "artifactRef 与当前实例、凭据、工具契约或规则版本不匹配")
    key = _key_for(root)
    expected_mac = hmac.new(key, _mac_payload(manifest), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(str(manifest.get("integrityMac") or ""), expected_mac):
        raise errors.artifact_tampered("产物清单完整性校验失败；请重新生成 artifactRef")
    if binding.get("storage") not in _SAFE_STORAGE:
        raise errors.artifact_tampered("产物清单完整性校验失败")
    for field, value_type in (
            ("kind", str), ("label", str), ("mime", str), ("sourceStep", str),
            ("size", int), ("sha256", str), ("createdAt", int), ("expiresAt", int),
            ("rootId", str), ("relativePath", str)):
        if not isinstance(binding.get(field), value_type) \
                or isinstance(binding.get(field), bool):
            raise errors.artifact_tampered("产物清单完整性校验失败")
    if binding["size"] < 0 or binding["size"] > MAX_ARTIFACT_BYTES \
            or not re.fullmatch(r"[0-9a-f]{64}", binding["sha256"]):
        raise errors.artifact_tampered("产物清单完整性校验失败")
    if int(time.time()) > binding["expiresAt"]:
        raise errors.artifact_expired("artifactRef 已过期，请重新执行研究步骤")
    return manifest, root, artifacts


def _bound_path(manifest: dict, env):
    binding = manifest["binding"]
    reference = manifest["artifactRef"]
    storage = binding["storage"]
    if storage == "state":
        _root, _artifacts, blobs = _state(env, _base_binding(
            env, binding["rulepackVersion"], binding["sourceTool"]))
        target = _blob_path(blobs, reference)
        if pathguard.is_linkish(target):
            raise errors.artifact_path_unsafe("产物私有副本包含链接或重解析点")
        try:
            resolved = target.resolve(strict=True)
            resolved.relative_to(blobs.resolve(strict=True))
        except (OSError, ValueError):
            raise errors.artifact_content_changed("产物私有副本不存在或已被替换") from None
        if not resolved.is_file():
            raise errors.artifact_content_changed("产物私有副本不存在或已被替换")
        return resolved, blobs
    if storage != "root":
        raise errors.artifact_tampered("产物清单完整性校验失败")
    _validate_relative_path(binding["relativePath"])
    roots = _trusted_roots(env)
    matching = [root for root in roots if _root_id(root) == binding["rootId"]]
    if not matching:
        raise errors.artifact_root_unavailable(
            "artifactRef 绑定的授权根已移除或不再可信，请重新执行研究步骤")
    unsafe = None
    for root in matching:
        candidate = root.joinpath(*binding["relativePath"].split("/"))
        try:
            resolved = candidate.resolve(strict=False)
        except OSError:
            continue
        if not _within(resolved, root):
            unsafe = errors.artifact_path_unsafe("产物解析后越出授权根")
            continue
        try:
            _assert_no_link_segments(root, candidate)
        except errors.ToolError as exc:
            unsafe = exc
            continue
        if resolved.is_file():
            return resolved, root
    if unsafe is not None:
        raise unsafe
    raise errors.artifact_content_changed("产物文件不存在或已被替换")


def _verify_identity(path: Path, root: Path, binding: dict) -> None:
    if pathguard.is_linkish(path):
        raise errors.artifact_path_unsafe("产物文件包含链接或重解析点")
    try:
        info = path.stat()
    except OSError:
        raise errors.artifact_content_changed("产物文件不存在或不可访问") from None
    if not stat.S_ISREG(info.st_mode) or int(info.st_size) != binding["size"]:
        raise errors.artifact_content_changed("产物文件大小或类型已变化，引用已失效")
    try:
        digest, size = pathguard.sha256_file(path, root=root)
    except errors.ToolError:
        raise errors.artifact_content_changed("产物文件无法读取或已被替换") from None
    if size != binding["size"] or digest != binding["sha256"]:
        raise errors.artifact_content_changed("产物文件内容已变化，引用已失效")


def resolve_path(reference, *, env=None, rulepack_version: str) -> Path:
    """可信侧内部接续：校验绑定并把 artifactRef 还原为授权路径（不暴露给模型）。"""
    source_env = _env_source(env)
    manifest, _root, _artifacts = _load_manifest(reference, source_env, rulepack_version)
    path, root = _bound_path(manifest, source_env)
    _verify_identity(path, root, manifest["binding"])
    return path


def _decode_prefix(raw: bytes) -> tuple[str, int]:
    try:
        return raw.decode("utf-8"), len(raw)
    except UnicodeDecodeError as exc:
        if exc.end == len(raw):
            for cut in range(len(raw) - 1, max(0, len(raw) - 4), -1):
                try:
                    return raw[:cut].decode("utf-8"), cut
                except UnicodeDecodeError:
                    continue
            return "", 0
        return raw.decode("utf-8", "replace"), len(raw)


def _text_page(path: Path, offset: int, size: int, limit: int, token: str) -> dict:
    if offset == size:
        return {"text": "", "pageBytes": 0, "nextOffset": None, "truncated": False}
    try:
        with open(path, "rb") as handle:
            handle.seek(offset)
            # 多读最多 3 字节，避免把 UTF-8 多字节字符截断在页边界。
            raw = handle.read(min(limit + 3, size - offset))
    except OSError:
        raise errors.artifact_content_changed("产物文件无法读取或已被替换") from None
    if not raw:
        raise errors.artifact_content_changed("产物文件在读取期间发生变化")
    while raw:
        text, used = _decode_prefix(raw)
        if used == 0:
            # 文件本身是截断的 UTF-8：用替换字符消费这些字节并继续，避免死循环。
            text, used = raw.decode("utf-8", "replace"), len(raw)
        if used > limit:
            limited, cut = _decode_prefix(raw[:limit])
            if cut > 0:
                text, used = limited, cut
        safe, flags = model_output._model_safe(text, token=token)
        if model_output._json_size(safe) <= TEXT_PAGE_JSON_BYTES:
            next_offset = offset + used
            response = {
                "text": safe,
                "pageBytes": used,
                "nextOffset": next_offset if next_offset < size else None,
                "truncated": next_offset < size,
            }
            if flags.get("paths"):
                response["contentPathsRedacted"] = True
            if flags.get("credentials"):
                response["contentCredentialsRedacted"] = True
            return response
        if used <= 1:
            break
        raw = raw[:max(1, int(used * 0.8))]
    raise errors.artifact_content_too_large("产物文本片段无法放入 12 KiB 续读页")


def read(reference, *, env=None, rulepack_version: str, offset: int = 0,
         limit: int = MAX_PAGE_BYTES, include_content: bool = False) -> dict:
    """读取 artifactRef：文本分页、图片可有界内联、其它 MIME 只给元数据。"""
    if type(offset) is not int or offset < 0:
        raise errors.invalid_input("offset 必须是非负整数")
    if type(limit) is not int or not 1 <= limit <= MAX_PAGE_BYTES:
        raise errors.invalid_input(f"limit 必须在 1..{MAX_PAGE_BYTES}")
    if not isinstance(include_content, bool):
        raise errors.invalid_input("includeContent 必须是布尔值")
    source_env = _env_source(env)
    manifest, _root, _artifacts = _load_manifest(reference, source_env, rulepack_version)
    path, root = _bound_path(manifest, source_env)
    binding = manifest["binding"]
    _verify_identity(path, root, binding)
    mime = binding["mime"]
    response = {
        "artifactRef": manifest["artifactRef"],
        "sourceTool": binding["sourceTool"],
        "kind": binding["kind"],
        "label": binding["label"],
        "mime": mime,
        "bytes": binding["size"],
        "sha256": binding["sha256"],
        "sourceStep": binding["sourceStep"],
        "createdAt": binding["createdAt"],
        "expiresAt": binding["expiresAt"],
    }
    if _is_image_mime(mime):
        response["contentType"] = "image"
        response["contentIncluded"] = False
        if include_content:
            if binding["size"] > MAX_IMAGE_BYTES:
                raise errors.artifact_content_too_large(
                    "图片超过 2 MiB base64 内联上限；请缩小图片或只读取元数据")
            try:
                payload = path.read_bytes()
            except OSError:
                raise errors.artifact_content_changed("产物文件无法读取或已被替换") from None
            if len(payload) != binding["size"] \
                    or hashlib.sha256(payload).hexdigest() != binding["sha256"]:
                raise errors.artifact_content_changed("产物文件内容已变化，引用已失效")
            encoded = base64.b64encode(payload).decode("ascii")
            if len(encoded) > MAX_IMAGE_BASE64_CHARS:
                raise errors.artifact_content_too_large(
                    "图片超过 2 MiB base64 内联上限；请缩小图片或只读取元数据")
            response["imageBase64"] = encoded
            response["contentIncluded"] = True
            response["imageContentBytes"] = len(payload)
        else:
            response["includeContentHint"] = "includeContent=true 可获取有界图片内容块"
        return response
    if _is_text_mime(mime):
        response["contentType"] = "text"
        response["offset"] = offset
        if offset > binding["size"]:
            raise errors.invalid_input("offset 超出产物字节范围")
        token = str(source_env.get("QIMENG_API_TOKEN") or "")
        response.update(_text_page(path, offset, binding["size"], limit, token))
        return response
    response["contentType"] = "binary"
    response["includeContentHint"] = "仅文本与图片内容可内联读取；其它类型只返回元数据"
    return response


def read_artifact(ctx, payload: dict) -> dict:
    """`platform.artifact.read` handler：纯本地、只读、无副作用。"""
    if not isinstance(payload, dict):
        raise errors.invalid_input("platform.artifact.read 输入必须是对象")
    reference = payload.get("artifactRef")
    env = getattr(ctx, "env", None)
    rulepack_version = getattr(ctx, "rulepack_version", None)
    if not isinstance(rulepack_version, str) or not rulepack_version:
        raise errors.artifact_context_invalid("当前上下文缺少规则版本，无法读取产物引用")
    return read(
        reference,
        env=env,
        rulepack_version=rulepack_version,
        offset=payload.get("offset", 0),
        limit=payload.get("limit", MAX_PAGE_BYTES),
        include_content=payload.get("includeContent", False),
    )
