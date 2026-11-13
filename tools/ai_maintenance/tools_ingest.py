"""正式上传与扫描薄适配；不复制入库根、不直接写库、不包含手机后端流程。"""
from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from urllib.parse import quote

from . import errors, fileref, pathguard, plans


def _library(ctx, library_id):
    libraries = ctx.client.get("/api/v1/libraries")
    for library in libraries:
        if library.get("id") == library_id:
            return library
    raise errors.precondition_failed("目标库不存在", mutation_rejected=True)


def _source(ctx, source):
    return fileref.resolve_input(source.get("path"), source.get("fileRef"), env=ctx.env)


def asset_upload_prepare(ctx, payload):
    try:
        operation_id = str(uuid.UUID(payload["operationId"]))
    except ValueError:
        raise errors.invalid_input("operationId 必须是持久化 UUID；同一操作重试保持不变") from None
    library_id = payload["libraryId"]
    library = _library(ctx, library_id)
    source = {key: payload[key] for key in ("path", "fileRef") if payload.get(key)}
    path = _source(ctx, source)
    before = path.stat()
    sha256, size = pathguard.sha256_file(path, root=path.parent)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        raise errors.precondition_failed("准备计划时源文件发生变化")
    name = payload.get("fileName") or path.name
    if name != os.path.basename(name) or any(c in name for c in ("/", "\\", "\ufffd")):
        raise errors.invalid_input("fileName 必须是无路径的有效 UTF-8 文件名")
    directory = payload.get("directory", "")
    # 与已有命名适配器共享库内相对目录校验。
    from .tools_media import _directory
    directory = _directory({"directory": directory})
    mtime = payload.get("sourceMtime", before.st_mtime_ns // 1_000_000)
    if not 0 <= mtime <= 253402300799999:
        raise errors.invalid_input("源时间超出 OpenAPI sourceMtime 范围")
    plan = plans.make_plan(
        tool="asset.upload", tool_version="1.0.0", kind="asset.upload",
        target={"instance": ctx.target, "library": {"id": library_id}, "objects": []},
        changes=[{"fileName": name, "directory": directory, "bytes": size}],
        impact={"affectedAssets": 1, "writes": True}, evidence=[],
        versions_info=plans.version_snapshot(ctx.rulepack_version, ctx.openapi_sha256),
        confirmation={"required": True, "reason": "上传至显式目标库；同名自动改名需回读"},
        recoverable_steps=["同一计划使用稳定 uploadId，七天内重放完成凭据；不自动重试。"],
        expected_readback=["资产 ID、库、最终 relPath、文件大小和源时间一致"],
        extra={"operationId": operation_id, "source": source, "sha256": sha256, "sourceMtime": mtime,
               "libraryRootDigest": plans.digest(library.get("rootPath")),
               "sourceMtimeNs": before.st_mtime_ns},
    )
    return {"plan": plan}


def asset_upload(ctx, payload):
    plan = plans.verify_plan(payload["plan"], tool_id="asset.upload",
                             rulepack_version=ctx.rulepack_version, instance=ctx.target)
    if plan["kind"] != "asset.upload":
        raise errors.plan_invalid("不是上传计划")
    library_id = plan["target"]["library"]["id"]
    library = _library(ctx, library_id)
    if plans.digest(library.get("rootPath")) != plan["libraryRootDigest"]:
        raise errors.precondition_failed("目标库根已变化，请重新 prepare", mutation_rejected=True)
    path = _source(ctx, plan["source"])
    sha256, size = pathguard.sha256_file(path, root=path.parent)
    info = path.stat()
    if sha256 != plan["sha256"] or info.st_mtime_ns != plan["sourceMtimeNs"]:
        raise errors.precondition_failed("源内容/时间已变化，请重新 prepare", mutation_rejected=True)
    change = plan["changes"][0]
    upload_id = plan["operationId"]
    if _source(ctx, plan["source"]) != path:
        raise errors.precondition_failed("源路径在上传前发生变化", mutation_rejected=True)
    with path.open("rb") as stream:
        opened = os.fstat(stream.fileno())
        if (opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns) != (info.st_dev, info.st_ino, size, info.st_mtime_ns):
            raise errors.precondition_failed("源文件在打开前被替换", mutation_rejected=True)
        saved = ctx.client.upload("/api/v1/assets/upload", stream, length=size,
                                  query={"libraryId": library_id, "dir": change["directory"],
                                         "filename": change["fileName"], "uploadId": upload_id,
                                         "sourceMtime": plan["sourceMtime"]})
        if os.fstat(stream.fileno()).st_mtime_ns != opened.st_mtime_ns:
            raise errors.readback_mismatch("上传期间源变化；副作用不确定，先核对该 uploadId")
    if not isinstance(saved, dict) or not saved.get("id"):
        raise errors.readback_mismatch("上传响应缺少资产 ID")
    readback = ctx.client.get("/api/v1/assets/" + quote(saved["id"], safe=""))
    expected_rel = "/".join(part for part in (change["directory"], change["fileName"]) if part)
    if readback.get("sizeBytes") != size or readback.get("relPath") != expected_rel:
        raise errors.readback_mismatch("上传后大小/最终路径与计划不一致（包括自动改名）；勿重复上传",
                                       {"assetId": saved["id"], "uploadId": upload_id})
    modified = readback.get("modifiedAt")
    if not isinstance(modified, str):
        raise errors.readback_mismatch("上传后缺少 modifiedAt")
    actual_millis = round(datetime.fromisoformat(modified.replace("Z", "+00:00")).astimezone(timezone.utc).timestamp() * 1000)
    if actual_millis != plan["sourceMtime"]:
        raise errors.readback_mismatch("上传后源时间与计划不一致", {"assetId": saved["id"]})
    return {"status": "applied", "tool": "asset.upload", "assetId": saved["id"], "uploadId": upload_id,
            "planFingerprint": plan["planFingerprint"], "idempotencyKey": plan["idempotencyKey"],
            "readback": {"relPath": readback["relPath"], "sizeBytes": size,
                         "modifiedAt": readback.get("modifiedAt")},
            "note": "直接上传已入库，无需再扫描。内容摘要为源文件校验，不代表服务端逐字节哈希回读。"}


def library_scan(ctx, payload):
    library_id = payload["libraryId"]
    _library(ctx, library_id)
    ctx.client.post("/api/v1/libraries/" + quote(library_id, safe="") + "/scan")
    library = _library(ctx, library_id)
    return {"status": "accepted", "libraryId": library_id,
            "scanState": library.get("scanState"),
            "note": "202 仅表示已受理；用 library.query 观察状态并按预期资产清单回读，不能据此报告整批完成。"}
