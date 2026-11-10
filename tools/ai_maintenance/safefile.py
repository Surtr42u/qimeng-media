"""本地文件安全发布辅助：同文件身份、同目录临时文件与覆盖/无覆盖发布。

只被本地原语（media.transfer、device.pull）使用，不承载业务规则，也不替代
pathguard 的读写根/链接授权。设计边界（2026-11-10 PR #53 修复）：

- 覆盖已有目标时**绝不**直接截断：先写同目录临时文件，调用方校验 size/SHA-256
  后再发布；
- 无覆盖发布优先用硬链接（同卷原子且不覆盖），文件系统不支持硬链接时退回
  “排他创建占位 + os.replace”，并只清理本次创建的占位；
- 覆盖发布用 `os.replace`（同卷原子）；失败时原目标保持不动，临时文件由调用方
  清理；
- `same_file_identity` 用 `os.path.samefile` 判断同路径/硬链接/同一底层文件；
  目标不存在或文件系统不支持时返回 False，由调用方的路径授权与后续校验兜底。
"""
from __future__ import annotations

import os
import tempfile
from pathlib import Path


def same_file_identity(left, right) -> bool:
    """判断两个路径是否指向同一底层文件；目标不存在/无法判断时返回 False。"""
    left_path = Path(left)
    right_path = Path(right)
    try:
        if not right_path.exists():
            return False
    except OSError:
        return False
    try:
        return os.path.samefile(left_path, right_path)
    except OSError:
        try:
            return left_path.resolve(strict=True) == right_path.resolve(strict=True)
        except OSError:
            return False


def new_temp_path(target: Path) -> Path:
    """在同目录创建唯一临时文件路径（空文件），保证与目标同卷可原子替换。"""
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(
        prefix="." + target.name[:40] + ".", suffix=".part", dir=str(target.parent))
    os.close(descriptor)
    return Path(name)


def cleanup(path) -> None:
    """删除本次创建的临时/占位文件；失败忽略（不掩盖主错误）。"""
    if path is None:
        return
    try:
        os.unlink(path)
    except OSError:
        pass


def publish_no_replace(temp: Path, target: Path) -> None:
    """把已校验的临时文件发布到不存在的目标；目标已存在抛 FileExistsError。"""
    temp = Path(temp)
    target = Path(target)
    try:
        os.link(temp, target)
    except FileExistsError:
        raise
    except OSError:
        # 文件系统不支持硬链接（或跨卷）：先排他创建占位，再原子替换自己的占位。
        # 这一步不是完全无竞态，调用方仍保留目标已存在的检查作为第一道防线。
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
        descriptor = os.open(target, flags, 0o666)
        os.close(descriptor)
        try:
            os.replace(temp, target)
        except BaseException:
            cleanup(target)
            raise
        return
    cleanup(temp)


def publish_replace(temp: Path, target: Path) -> None:
    """原子替换已有目标；失败时原目标保持不变，临时文件由调用方清理。"""
    os.replace(Path(temp), Path(target))
