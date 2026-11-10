"""本地路径安全边界与文件摘要（写盘类工具共用）。

backup.export 与 research.* 会把备份、结果、帧或证据写到本机磁盘。为避免把
个人数据或缓存写进被跟踪的源码树，所有路径必须是**绝对路径**，并且满足：

- 仓库外：用户显式给出的本机路径（如维护目录、外部磁盘）；
- 仓库内：只允许本包被 Git 忽略的工作目录
  （`QIMENG_AI_RESEARCH_DIR`，默认 `tools/ai_maintenance/.work/research`）、
  单测临时目录 `tools/ai_maintenance/.test-temp/` 与 `build/`。

本模块只做校验/摘要/建目录，不读取 token、不联网；错误分类为 INVALID_INPUT，
消息只回显仓库相对路径或允许根，不把用户绝对路径写进仓库文档/日志。
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path

from . import errors

REPO_ROOT = Path(__file__).resolve().parents[2]
PACKAGE_ROOT = Path(__file__).resolve().parent
WORK_ROOT_ENV = "QIMENG_AI_RESEARCH_DIR"
MEDIA_ROOTS_ENV = "QIMENG_AI_MEDIA_ROOTS"
DEFAULT_WORK_ROOT = PACKAGE_ROOT / ".work" / "research"
TEST_TEMP_ROOT = PACKAGE_ROOT / ".test-temp"
BUILD_ROOT = REPO_ROOT / "build"

SHA256_CHUNK_BYTES = 1 << 20
MD5_CHUNK_BYTES = 1 << 20


def work_root(env=None) -> Path:
    """研究产物根目录：QIMENG_AI_RESEARCH_DIR 必须是绝对路径，否则用默认值。"""
    raw = (env or {}).get(WORK_ROOT_ENV, "") if env is not None else os.environ.get(WORK_ROOT_ENV, "")
    if isinstance(raw, str) and raw.strip():
        candidate = Path(raw.strip())
        if not candidate.is_absolute():
            raise errors.invalid_input(WORK_ROOT_ENV + " 必须是绝对路径")
        return candidate.resolve()
    return DEFAULT_WORK_ROOT


def allowed_roots(env=None) -> tuple[Path, ...]:
    """仓库内允许写盘/读取的忽略工作根（其余仓库路径一律拒绝）。"""
    return (work_root(env), TEST_TEMP_ROOT.resolve(), BUILD_ROOT.resolve())


def display_path(path: Path) -> str:
    """仓库内路径显示为相对路径；仓库外只显示本机绝对路径的通用描述。"""
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return "<outside-repo>"


def _within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def resolve_local_path(value, *, env=None, must_exist: bool = False,
                       kind: str = "路径") -> Path:
    """校验并规范化本地路径；返回绝对 Path（不保证存在，除非 must_exist）。"""
    if not isinstance(value, str) or not value.strip():
        raise errors.invalid_input(f"{kind} 必须是非空字符串")
    if any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input(f"{kind} 含控制字符")
    candidate = Path(value.strip())
    if not candidate.is_absolute():
        raise errors.invalid_input(f"{kind} 必须是绝对路径", details={"value": value})
    resolved = candidate.resolve(strict=False)
    if _within(resolved, REPO_ROOT):
        roots = allowed_roots(env)
        if not any(_within(resolved, root) for root in roots):
            raise errors.invalid_input(
                f"{kind} 位于仓库内但不在允许的忽略工作根中；请使用仓库外绝对路径或工作根",
                details={"allowedRoots": [display_path(root) for root in roots]},
            )
    if must_exist and not resolved.is_file():
        raise errors.invalid_input(f"{kind} 不是可读的普通文件", details={"path": display_path(resolved)})
    return resolved


def ensure_directory(path: Path) -> Path:
    """创建目录（含父级）；失败按配置/输入错误返回，不回显完整本机路径。"""
    try:
        Path(path).mkdir(parents=True, exist_ok=True)
    except OSError:
        raise errors.invalid_input("输出目录不可创建或不可写",
                                   details={"path": display_path(Path(path))}) from None
    return Path(path)


def sha256_file(path: Path) -> tuple[str, int]:
    """流式计算文件 SHA-256；返回 (hex, 字节数)。"""
    hasher = hashlib.sha256()
    total = 0
    try:
        with open(path, "rb") as handle:
            while True:
                chunk = handle.read(SHA256_CHUNK_BYTES)
                if not chunk:
                    break
                total += len(chunk)
                hasher.update(chunk)
    except OSError:
        raise errors.invalid_input("文件无法读取（不存在或不可访问）",
                                   details={"path": display_path(Path(path))}) from None
    return hasher.hexdigest(), total


def md5_file(path: Path, *, max_bytes: int | None = None) -> tuple[str | None, int]:
    """流式计算文件 MD5；超过 max_bytes 时返回 (None, 已读字节数)。"""
    hasher = hashlib.md5()
    total = 0
    try:
        with open(path, "rb") as handle:
            while True:
                chunk = handle.read(MD5_CHUNK_BYTES)
                if not chunk:
                    break
                total += len(chunk)
                if max_bytes is not None and total > max_bytes:
                    return None, total
                hasher.update(chunk)
    except OSError:
        raise errors.invalid_input("文件无法读取（不存在或不可访问）",
                                   details={"path": display_path(Path(path))}) from None
    return hasher.hexdigest(), total


def media_roots(env=None) -> tuple[Path, ...]:
    """显式授权的媒体/维护根（QIMENG_AI_MEDIA_ROOTS，逗号/分号分隔绝对路径）。

    未配置时返回空元组：调用方必须拒绝本地扫描，而不是默认放行任意路径。
    """
    source = (env if env is not None else os.environ)
    raw = source.get(MEDIA_ROOTS_ENV, "")
    if not isinstance(raw, str) or not raw.strip():
        return ()
    roots: list[Path] = []
    for item in raw.replace(";", ",").split(","):
        text = item.strip()
        if not text:
            continue
        candidate = Path(text)
        if not candidate.is_absolute():
            raise errors.configuration_error(MEDIA_ROOTS_ENV + " 只能包含绝对路径")
        resolved = candidate.resolve(strict=False)
        if not resolved.is_dir():
            raise errors.configuration_error(MEDIA_ROOTS_ENV + " 中存在不可访问的目录")
        roots.append(resolved)
    return tuple(roots)


def _within_any(path: Path, roots: tuple[Path, ...]) -> bool:
    return any(_within(path, root) for root in roots)


def resolve_media_path(value, *, env=None, kind: str = "扫描根目录") -> Path:
    """校验本地媒体扫描根：必须显式授权、绝对、目录且不得越界。

    授权来源只有 `QIMENG_AI_MEDIA_ROOTS`，以及仓库内被忽略的测试/构建工作根
    （便于本地测试与排障）。不跟随最终路径之外的符号链接：调用方在遍历时还会
    跳过符号链接条目。
    """
    if not isinstance(value, str) or not value.strip():
        raise errors.invalid_input(f"{kind} 必须是非空绝对路径")
    if any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input(f"{kind} 含控制字符")
    candidate = Path(value.strip())
    if not candidate.is_absolute():
        raise errors.invalid_input(f"{kind} 必须是绝对路径")
    resolved = candidate.resolve(strict=False)
    if not resolved.is_dir():
        raise errors.invalid_input(f"{kind} 不存在或不是目录",
                                   details={"path": display_path(resolved)})
    roots = media_roots(env) + (TEST_TEMP_ROOT.resolve(), BUILD_ROOT.resolve(),
                                work_root(env))
    if not _within_any(resolved, roots):
        raise errors.invalid_input(
            f"{kind} 不在显式授权的媒体根中；请设置 {MEDIA_ROOTS_ENV} 或使用仓库忽略工作根",
            details={"authorizedRootCount": len(roots), "env": MEDIA_ROOTS_ENV},
        )
    return resolved
