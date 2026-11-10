"""本地路径安全边界与文件摘要（读写原语共用）。

本地执行域默认拒绝：路径必须落在可信执行端**显式配置**的读写根内，模型传参
不能扩大授权。授权来源：

- 读：`QIMENG_AI_MEDIA_ROOTS`（媒体扫描根）∪ `QIMENG_AI_READ_ROOTS`
  ∪ 仓库内忽略工作根（`QIMENG_AI_RESEARCH_DIR`、`.test-temp/`、`build/`）；
- 写：`QIMENG_AI_WRITE_ROOTS` ∪ 仓库内忽略工作根。

约束（2026-11-10 本地执行域定向优化）：

- 绝对路径；控制字符、仓库源码树、`..` 越界一律拒绝；
- 授权根由可信配置给出，允许根本身是符号链接/junction（配置解析时 resolve 为
  真实路径），但根以下的写目标/父段不得是符号链接/junction/reparse；
- 读路径最终 `resolve()` 结果必须仍在授权根内；
- 仓库内仍只允许忽略工作根，与既有行为一致。

本模块只做校验/摘要/建目录，不读取 token、不联网；错误消息只回显仓库相对路径、
变量名或授权根数量，不把用户绝对路径写进仓库文档/日志。
"""
from __future__ import annotations

import hashlib
import os
import stat
from pathlib import Path

from . import errors

REPO_ROOT = Path(__file__).resolve().parents[2]
PACKAGE_ROOT = Path(__file__).resolve().parent
WORK_ROOT_ENV = "QIMENG_AI_RESEARCH_DIR"
MEDIA_ROOTS_ENV = "QIMENG_AI_MEDIA_ROOTS"
READ_ROOTS_ENV = "QIMENG_AI_READ_ROOTS"
WRITE_ROOTS_ENV = "QIMENG_AI_WRITE_ROOTS"
DEFAULT_WORK_ROOT = PACKAGE_ROOT / ".work" / "research"
TEST_TEMP_ROOT = PACKAGE_ROOT / ".test-temp"
BUILD_ROOT = REPO_ROOT / "build"

SHA256_CHUNK_BYTES = 1 << 20
MD5_CHUNK_BYTES = 1 << 20

_VALID_MODES = ("read", "write")
_REPARSE_ATTRIBUTE = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)


def _env_source(env):
    return os.environ if env is None else env


def work_root(env=None) -> Path:
    """研究产物根目录：QIMENG_AI_RESEARCH_DIR 必须是绝对路径，否则用默认值。"""
    raw = _env_source(env).get(WORK_ROOT_ENV, "")
    if isinstance(raw, str) and raw.strip():
        candidate = Path(raw.strip())
        if not candidate.is_absolute():
            raise errors.invalid_input(WORK_ROOT_ENV + " 必须是绝对路径")
        return candidate.resolve(strict=False)
    return DEFAULT_WORK_ROOT


def allowed_roots(env=None) -> tuple[Path, ...]:
    """仓库内允许写盘/读取的忽略工作根（其余仓库路径一律拒绝）。"""
    return (work_root(env), TEST_TEMP_ROOT.resolve(), BUILD_ROOT.resolve())


def display_path(path: Path) -> str:
    """仓库内路径显示为相对路径；仓库外只显示本机绝对路径的通用描述。"""
    try:
        return Path(path).resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return "<outside-repo>"


def _within(path: Path, root: Path) -> bool:
    try:
        Path(path).relative_to(Path(root))
        return True
    except ValueError:
        return False


def _within_any(path: Path, roots) -> bool:
    return any(_within(path, root) for root in roots)


def _is_linkish(path: Path) -> bool:
    """符号链接、junction 或任意 Windows reparse point。"""
    try:
        info = os.lstat(path)
    except OSError:
        return False
    if stat.S_ISLNK(info.st_mode):
        return True
    attributes = getattr(info, "st_file_attributes", 0)
    return bool(_REPARSE_ATTRIBUTE and attributes & _REPARSE_ATTRIBUTE)


def _parse_root_entries(env, env_name: str) -> list[tuple[Path, Path]]:
    """解析显式根：返回 (原始写法, 真实路径)；原始写法保留用于拒绝根以下的链接。

    授权根本身允许是符号链接/junction：可信配置把它 resolve 成真实路径后参与
    包含判断；原始写法只用于检查根以下路径段。
    """
    source = _env_source(env)
    raw = source.get(env_name, "")
    if not isinstance(raw, str) or not raw.strip():
        return []
    entries: list[tuple[Path, Path]] = []
    for item in raw.replace(";", ",").split(","):
        text = item.strip()
        if not text:
            continue
        if any(ord(ch) < 32 for ch in text):
            raise errors.configuration_error(env_name + " 含控制字符")
        candidate = Path(text)
        if not candidate.is_absolute():
            raise errors.configuration_error(env_name + " 只能包含绝对路径")
        resolved = candidate.resolve(strict=False)
        if not resolved.is_dir():
            raise errors.configuration_error(env_name + " 中存在不可访问的目录")
        entries.append((candidate, resolved))
    return entries


def _dedupe_entries(entries: list[tuple[Path, Path]]) -> list[tuple[Path, Path]]:
    seen: set[tuple[str, str]] = set()
    unique: list[tuple[Path, Path]] = []
    # 每个根同时登记原始写法和真实路径：用户可以直接传真实路径。
    expanded: list[tuple[Path, Path]] = []
    for raw_root, resolved_root in entries:
        expanded.append((raw_root, resolved_root))
        if raw_root != resolved_root:
            expanded.append((resolved_root, resolved_root))
    for raw_root, resolved_root in expanded:
        key = (os.path.normcase(str(raw_root)), os.path.normcase(str(resolved_root)))
        if key in seen:
            continue
        seen.add(key)
        unique.append((raw_root, resolved_root))
    return unique


def _implicit_entries(env=None) -> list[tuple[Path, Path]]:
    work = work_root(env)
    entries = []
    for root in (work, TEST_TEMP_ROOT.resolve(), BUILD_ROOT.resolve()):
        resolved = root.resolve(strict=False)
        entries.append((root, resolved))
    return _dedupe_entries(entries)


def media_root_entries(env=None) -> list[tuple[Path, Path]]:
    return _parse_root_entries(env, MEDIA_ROOTS_ENV)


def media_roots(env=None) -> tuple[Path, ...]:
    """显式授权的媒体/维护根（QIMENG_AI_MEDIA_ROOTS，逗号/分号分隔绝对路径）。

    未配置时返回空元组：调用方必须拒绝本地扫描，而不是默认放行任意路径。
    """
    return tuple(resolved for _raw, resolved in media_root_entries(env))


def read_roots(env=None) -> tuple[Path, ...]:
    """只读授权根：媒体根 ∪ READ_ROOTS ∪ 工作根/测试根/build。"""
    entries = (media_root_entries(env)
               + _parse_root_entries(env, READ_ROOTS_ENV)
               + _implicit_entries(env))
    return tuple(resolved for _raw, resolved in _dedupe_entries(entries))


def write_roots(env=None) -> tuple[Path, ...]:
    """写入授权根：WRITE_ROOTS ∪ 工作根/测试根/build。未配置即默认拒绝。"""
    entries = _parse_root_entries(env, WRITE_ROOTS_ENV) + _implicit_entries(env)
    return tuple(resolved for _raw, resolved in _dedupe_entries(entries))


def _root_entries(env, mode: str) -> list[tuple[Path, Path]]:
    if mode == "read":
        return _dedupe_entries(media_root_entries(env)
                                + _parse_root_entries(env, READ_ROOTS_ENV)
                                + _implicit_entries(env))
    return _dedupe_entries(_parse_root_entries(env, WRITE_ROOTS_ENV)
                           + _implicit_entries(env))


def _find_root(raw: Path, resolved: Path, entries) -> tuple[Path, Path] | None:
    best = None
    best_score = None
    for raw_root, resolved_root in entries:
        if not _within(resolved, resolved_root):
            continue
        score = (len(resolved_root.parts),
                 1 if _within(raw, raw_root) else 0,
                 len(raw_root.parts))
        if best_score is None or score > best_score:
            best_score = score
            best = (raw_root, resolved_root)
    return best


def _assert_no_link_segments(raw: Path, root: Path, kind: str) -> None:
    """检查授权根以下路径段；根自身允许是链接/junction。"""
    try:
        relative = raw.relative_to(root)
    except ValueError:
        return
    current = Path(root)
    for part in relative.parts:
        if part in ("", "."):
            continue
        if part == "..":
            current = Path(os.path.normpath(str(current / part)))
            continue
        current = current / part
        if _is_linkish(current):
            raise errors.path_unsafe(
                f"{kind} 的写入路径包含符号链接/junction/reparse 段",
                details={"segment": part},
            )


def _authorize(value, *, env=None, mode: str, kind: str,
               must_exist: bool = False) -> Path:
    if mode not in _VALID_MODES:
        raise errors.invalid_input("mode 只能是 read/write")
    if not isinstance(value, str) or not value.strip():
        raise errors.invalid_input(f"{kind} 必须是非空字符串")
    if any(ord(ch) < 32 for ch in value):
        raise errors.path_unsafe(f"{kind} 含控制字符")
    candidate = Path(value.strip())
    if not candidate.is_absolute():
        raise errors.invalid_input(f"{kind} 必须是绝对路径", details={"value": value})
    resolved = candidate.resolve(strict=False)
    implicit = tuple(root for _raw, root in _implicit_entries(env))
    if (_within(candidate, REPO_ROOT) or _within(resolved, REPO_ROOT)) \
            and not _within_any(resolved, implicit):
        raise errors.invalid_input(
            f"{kind} 位于仓库内但不在允许的忽略工作根中；请使用显式授权的仓库外路径或工作根",
            details={"allowedRoots": [display_path(root) for root in implicit]},
        )
    entries = _root_entries(env, mode)
    match = _find_root(candidate, resolved, entries)
    if match is None:
        env_names = ((MEDIA_ROOTS_ENV, READ_ROOTS_ENV) if mode == "read"
                     else (WRITE_ROOTS_ENV,))
        raise errors.path_not_authorized(kind, env_names=env_names,
                                         details={"authorizedRootCount": len(entries)})
    raw_root, resolved_root = match
    if mode == "write":
        if _within(candidate, raw_root):
            _assert_no_link_segments(candidate, raw_root, kind)
        else:
            # 用户直接使用真实路径（或经 .. 归一化）：resolved 已无链接段。
            _assert_no_link_segments(resolved, resolved_root, kind)
    if must_exist and not resolved.is_file():
        raise errors.invalid_input(f"{kind} 不是可读的普通文件",
                                   details={"path": display_path(resolved)})
    return resolved


def resolve_local_path(value, *, env=None, must_exist: bool = False,
                       kind: str = "路径", mode: str = "read") -> Path:
    """校验并规范化本地路径；返回绝对 Path（不保证存在，除非 must_exist）。

    `mode="read"` 校验读授权根；`mode="write"` 校验写授权根并拒绝根以下的
    符号链接/junction/reparse 路径段。未配置授权根默认拒绝。
    """
    return _authorize(value, env=env, mode=mode, kind=kind, must_exist=must_exist)


def authorize_existing(path, *, mode: str = "read", env=None,
                       kind: str = "路径") -> Path:
    """复用授权逻辑校验一个已存在/已解析路径（fileRef、复制后目标等）。"""
    resolved = _authorize(str(path), env=env, mode=mode, kind=kind, must_exist=False)
    if not resolved.exists():
        raise errors.invalid_input(f"{kind} 不存在或不可访问",
                                   details={"path": display_path(resolved)})
    return resolved


def root_for_path(path, *, mode: str = "read", env=None) -> Path | None:
    """返回包含该路径的最具体授权根（真实路径）；没有授权根时返回 None。

    用于读取期间的 TOCTOU 复核（打开/哈希前后重新 resolve）。
    """
    resolved = Path(path).resolve(strict=False)
    entries = _root_entries(env, mode if mode in _VALID_MODES else "read")
    match = _find_root(Path(path), resolved, entries)
    return match[1] if match else None


def _verify_within(path: Path, root: Path, kind: str) -> Path:
    resolved = Path(path).resolve(strict=False)
    root_resolved = Path(root).resolve(strict=False)
    if not _within(resolved, root_resolved):
        raise errors.path_unsafe(f"{kind} 在读取期间解析到授权根之外",
                                 details={"reason": "toctou"})
    return resolved


def ensure_directory(path: Path) -> Path:
    """创建目录（含父级）；失败按配置/输入错误返回，不回显完整本机路径。"""
    try:
        Path(path).mkdir(parents=True, exist_ok=True)
    except OSError:
        raise errors.invalid_input("输出目录不可创建或不可写",
                                   details={"path": display_path(Path(path))}) from None
    return Path(path)


def sha256_file(path: Path, *, root: Path | None = None) -> tuple[str, int]:
    """流式计算文件 SHA-256；返回 (hex, 字节数)。

    给出 `root` 时在打开前后各 resolve 一次，确保读取期间没有逃逸授权根。
    """
    target = Path(path)
    if root is not None:
        target = _verify_within(target, root, "文件")
    hasher = hashlib.sha256()
    total = 0
    try:
        with open(target, "rb") as handle:
            while True:
                chunk = handle.read(SHA256_CHUNK_BYTES)
                if not chunk:
                    break
                total += len(chunk)
                hasher.update(chunk)
    except OSError:
        raise errors.invalid_input("文件无法读取（不存在或不可访问）",
                                   details={"path": display_path(Path(path))}) from None
    if root is not None:
        _verify_within(target, root, "文件")
    return hasher.hexdigest(), total


def md5_file(path: Path, *, max_bytes: int | None = None,
             root: Path | None = None) -> tuple[str | None, int]:
    """流式计算文件 MD5；超过 max_bytes 时返回 (None, 已读字节数)。

    给出 `root` 时在打开前后各 resolve 一次，确保读取期间没有逃逸授权根。
    """
    target = Path(path)
    if root is not None:
        target = _verify_within(target, root, "文件")
    hasher = hashlib.md5()
    total = 0
    try:
        with open(target, "rb") as handle:
            while True:
                chunk = handle.read(MD5_CHUNK_BYTES)
                if not chunk:
                    break
                total += len(chunk)
                if max_bytes is not None and total > max_bytes:
                    if root is not None:
                        _verify_within(target, root, "文件")
                    return None, total
                hasher.update(chunk)
    except OSError:
        raise errors.invalid_input("文件无法读取（不存在或不可访问）",
                                   details={"path": display_path(Path(path))}) from None
    if root is not None:
        _verify_within(target, root, "文件")
    return hasher.hexdigest(), total


def resolve_media_path(value, *, env=None, kind: str = "扫描根目录",
                       mode: str = "read") -> Path:
    """校验本地媒体扫描根：必须显式授权、绝对、目录且不得越界。

    保持只读语义（默认 mode=read），复用与 resolve_local_path 相同的授权逻辑；
    不跟随最终路径之外的符号链接：调用方在遍历时还会跳过链接条目。
    """
    resolved = _authorize(value, env=env, mode=mode, kind=kind, must_exist=False)
    if not resolved.is_dir():
        raise errors.invalid_input(f"{kind} 不存在或不是目录",
                                   details={"path": display_path(resolved)})
    return resolved
