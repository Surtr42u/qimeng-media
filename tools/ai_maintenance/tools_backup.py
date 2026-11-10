"""backup.export：GET /api/v1/export/qimeng-backup 流式落盘并计算摘要。

设计（ADR-0056 计划/受控写入模型的本地导出分支）：
- 备份导出是**大响应**：不使用 client.send 的 2MiB JSON 上限，改为按 256KiB
  分块写同目录临时文件，边写边算 SHA-256 与字节数；成功后 os.replace 原子
  改名，失败删除半成品，绝不留下半个备份冒充完整文件。
- 只写用户显式给出的绝对路径或本包被忽略的工作根（pathguard）；默认拒绝
  覆盖现有文件，overwrite=true 才替换。
- 预算默认 128MiB、硬上限 256MiB；超限明确报错（不截断、不静默）。
- 结果只含 path/bytes/sha256 等元数据，不把备份正文放进工具结果，也不打印 token。
"""
from __future__ import annotations

import os
import time

from . import client as http_client
from . import errors, pathguard
from .runtime import ToolContext

EXPORT_ENDPOINT = "/api/v1/export/qimeng-backup"
DEFAULT_MAX_BYTES = 128 << 20
HARD_MAX_BYTES = 256 << 20
MAX_DESTINATION_SUFFIX = ".qimeng-backup.zip"


def _max_bytes(payload: dict) -> int:
    value = payload.get("maxBytes")
    if value is None:
        return DEFAULT_MAX_BYTES
    if not isinstance(value, int) or isinstance(value, bool):
        raise errors.invalid_input("maxBytes 必须是整数")
    if not 1 <= value <= HARD_MAX_BYTES:
        raise errors.invalid_input(
            f"maxBytes 必须在 1..{HARD_MAX_BYTES}（硬上限 256MiB）",
            details={"defaultBytes": DEFAULT_MAX_BYTES, "hardMaxBytes": HARD_MAX_BYTES},
        )
    return value


def _cleanup(path) -> None:
    try:
        os.unlink(path)
    except OSError:
        pass


def _server_code(exc: http_client.HTTPStatusError) -> str:
    body = exc.body
    return str(body.get("code") or "") if isinstance(body, dict) else ""


def backup_export(ctx: ToolContext, payload: dict) -> dict:
    out_path = payload.get("outPath")
    if not isinstance(out_path, str) or not out_path.strip():
        raise errors.invalid_input("缺少 outPath（必须是绝对路径）")
    destination = pathguard.resolve_local_path(out_path, env=ctx.env, kind="outPath",
                                               mode="write")
    max_bytes = _max_bytes(payload)
    overwrite = payload.get("overwrite") is True
    if destination.exists() and not overwrite:
        raise errors.precondition_failed(
            "目标文件已存在；确认替换时显式 overwrite=true",
            {"reason": "output_exists", "path": pathguard.display_path(destination)},
        )
    pathguard.ensure_directory(destination.parent)
    temporary = destination.with_name(
        destination.name + ".part-" + os.urandom(6).hex())
    started = time.monotonic()
    try:
        total, sha256 = ctx.client.download(EXPORT_ENDPOINT, temporary, max_bytes=max_bytes)
    except http_client.HTTPStatusError as exc:
        _cleanup(temporary)
        if exc.status == 403 and _server_code(exc) == "FORBIDDEN_SCOPE":
            raise errors.forbidden_scope("backup.export", str(exc.body)[:500],
                                         {"status": exc.status}) from None
        raise
    except BaseException:
        _cleanup(temporary)
        raise
    # 下载期间目标可能被其他进程创建：不改写别人刚写好的文件。
    if destination.exists() and not overwrite:
        _cleanup(temporary)
        raise errors.precondition_failed(
            "下载期间目标文件已被创建；未覆盖，请检查后重试",
            {"reason": "output_exists", "path": pathguard.display_path(destination)},
        )
    try:
        os.replace(temporary, destination)
    except OSError:
        _cleanup(temporary)
        raise errors.internal_error("备份临时文件无法原子改名",
                                    details={"path": pathguard.display_path(destination)}) from None
    elapsed_ms = int((time.monotonic() - started) * 1000)
    return {
        "path": str(destination),
        "bytes": total,
        "sha256": sha256,
        "endpoint": EXPORT_ENDPOINT,
        "format": "v2",
        "overwrite": overwrite,
        "maxBytes": max_bytes,
        "elapsedMs": elapsed_ms,
        "note": "备份正文只落盘；用 sha256/bytes 与维护目录摘要核对，不要把备份内容转发到外部模型上下文。",
    }
