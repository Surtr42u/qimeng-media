"""本机直接入库工具：`asset.ingest.local.prepare` / `asset.ingest.local`。

媒体库与源文件**同机/同分区**时，入库本质是「移动文件 + 建索引」：服务端直接把读根
内的源文件移入库根并复用上传的入库管线，3GB 级文件从 HTTP 流式上传的「约 2 分钟」
降到同盘 rename 的毫秒级。手机/外网/跨设备仍走 `asset.upload`，两者索引结果一致；
**本机已有文件不要上传**——多绕一圈收流与边算摘要，慢且额外占用暂存空间。

- prepare 只读：校验库、源在 `QIMENG_AI_STORAGES` 根内、源不在任何库根内、单次
  数量/总字节上限，给出目标相对路径/字节数/源时间/预期状态（move|already_present|
  conflict|unknown）；不做全文件哈希（摘要由服务端在复制/幂等比对路径计算）。
- apply：重新核对库根与每个源，逐文件 POST ingest-local，再按 assetId
  `GET /assets/{id}` 回读；不一致即 READBACK_MISMATCH，不静默成功。
- 幂等：同内容目标 → `already_present`（源不动，是成功）；内容不同 → TARGET_EXISTS
  且绝不覆盖。拒绝相对路径、`..`、符号链接/junction 逃逸；服务端读根
  `QIMENG_INGEST_LOCAL_ROOTS` 是另一套配置，AI 侧授权不能替代。
"""
from __future__ import annotations

import os
import re
import stat
import uuid
from pathlib import Path
from urllib import parse

from . import client as http_client
from . import errors, fileref, pathguard, plans

# 单次调用硬上限：数量与总字节都必须有界（批量入库仍可分多次，already_present 可安全重跑）。
MAX_PATHS_PER_CALL = 50
MAX_TOTAL_BYTES = 64 << 30  # 64 GiB
MAX_PATH_CHARS = 4096
INGEST_PATH = "/api/v1/libraries/{libraryId}/ingest-local"
ASSET_PATH = "/api/v1/assets/{assetId}"

# 请求超时预算（review 必修 1）：跨分区复制 3GB 级文件远超默认 20s，客户端不能先超时
# 而服务端还在复制。按单个文件的字节数放大：60s 基础 + 每 GiB 60s（不足 1 GiB 按
# 1 GiB 计），封顶 600s。注册表声明的 timeout_seconds 取封顶值，MCP/CLI 外壳据此
# 放宽整次调用上限（外壳按 max(默认120, 声明+25) 计算，声明 300 会让更大文件被外壳先取消）。
GIB_BYTES = 1 << 30
BASE_TIMEOUT_SECONDS = 60
SECONDS_PER_GIB = 60
MAX_TIMEOUT_SECONDS = 600

# 目标相对路径的预期状态（prepare 只做本地可判定的推断；服务端是最终裁决）。
STATUS_MOVE = "move"
STATUS_PRESENT = "already_present"
STATUS_CONFLICT = "conflict"
STATUS_UNKNOWN = "unknown"
_PLANNED_STATUSES = frozenset({STATUS_MOVE, STATUS_PRESENT, STATUS_CONFLICT, STATUS_UNKNOWN})
_SERVER_STATUSES = frozenset({"ingested", "already_present"})
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def request_timeout(bytes_count: int) -> int:
    """单个请求的超时秒数：60s 基础 + 60s/每 GiB 起（不足 1 GiB 按 1 GiB 计），封顶 600s。"""
    gib = -(-max(0, int(bytes_count)) // GIB_BYTES)  # 向上取整；0 字节只给基础预算
    return min(MAX_TIMEOUT_SECONDS, BASE_TIMEOUT_SECONDS + SECONDS_PER_GIB * gib)


def _library_path(library_id: str) -> str:
    return INGEST_PATH.format(libraryId=parse.quote(library_id, safe=""))


def _asset_path(asset_id: str) -> str:
    return ASSET_PATH.format(assetId=parse.quote(asset_id, safe=""))


def _libraries(ctx) -> dict[str, dict]:
    """读取库列表（库存在性、库根与跨库根拒绝都以此为准）。"""
    try:
        data = ctx.client.get("/api/v1/libraries")
    except http_client.HTTPStatusError as exc:
        raise errors.api_unavailable(
            "GET /api/v1/libraries", f"读取库列表失败（HTTP {exc.status}）：未执行任何入库请求",
            {"status": exc.status}, mutation_rejected=True) from None
    except (http_client.NetworkError, http_client.ResponseError) as exc:
        raise errors.api_unavailable(
            "GET /api/v1/libraries", "读取库列表失败/超时：未执行任何入库请求",
            {"reason": getattr(exc, "kind", type(exc).__name__)},
            mutation_rejected=True) from None
    if not isinstance(data, list):
        raise errors.internal_error("库列表响应不是数组")
    return {item["id"]: item for item in data
            if isinstance(item, dict) and isinstance(item.get("id"), str)}


def _library_id(value) -> str:
    if not isinstance(value, str) or not value.strip():
        raise errors.invalid_input("libraryId 必须是非空字符串")
    text = value.strip()
    if len(text) > 512 or any(ord(ch) < 32 for ch in text):
        raise errors.invalid_input("libraryId 非法")
    return text


def _operation_id(value) -> str | None:
    """可选幂等操作 ID：与 asset.upload 同口径的持久化 UUID。"""
    if value is None or value == "":
        return None
    if not isinstance(value, str):
        raise errors.invalid_input("operationId 必须是 UUID 字符串")
    try:
        return str(uuid.UUID(value))
    except ValueError:
        raise errors.invalid_input("operationId 必须是持久化 UUID；同一操作重试保持不变") from None


def _directory(value) -> str:
    """库内相对目录：与命名/上传适配器共享同一校验，不悄悄修正非法路径。"""
    from .tools_media import _directory as normalize_directory
    return normalize_directory({"directory": "" if value is None else value})


def _within(path: Path, root: Path) -> bool:
    try:
        Path(path).relative_to(Path(root))
        return True
    except ValueError:
        return False


def _storage_roots(ctx) -> dict[str, Path]:
    roots = fileref.storages(dict(getattr(ctx, "env", None) or {}))
    if not roots:
        raise errors.not_in_storage_root(
            "未配置任何本地存储根，无法证明源文件已授权（本机入库只移动已授权根内文件）",
            {"reason": "no_storage_configured"})
    return roots


def _source_entry(raw, roots: dict[str, Path], position: int) -> dict:
    """校验单个源：绝对路径、在存储根内、无 `..`/链接段、普通文件；返回预检信息。"""
    if not isinstance(raw, str) or not raw.strip():
        raise errors.invalid_input(f"paths[{position}] 必须是非空字符串")
    text = raw.strip()
    if len(text) > MAX_PATH_CHARS:
        raise errors.invalid_input(f"paths[{position}] 超过 {MAX_PATH_CHARS} 字符")
    if any(ord(ch) < 32 for ch in text):
        raise errors.path_unsafe(f"paths[{position}] 含控制字符")
    candidate = Path(text)
    if not candidate.is_absolute():
        raise errors.invalid_input(f"paths[{position}] 必须是绝对路径")
    if ".." in candidate.parts:
        raise errors.path_unsafe(f"paths[{position}] 不能包含 .. 路径段",
                                 {"position": position})
    resolved = candidate.resolve(strict=False)
    matches = [(len(root.parts), storage_id, root)
               for storage_id, root in roots.items()
               if _within(resolved, root) or _within(candidate, root)]
    if not matches:
        raise errors.not_in_storage_root(
            f"paths[{position}] 不在任何已配置的存储根内",
            {"position": position, "reason": "outside_roots"})
    _depth, storage_id, root = max(matches, key=lambda item: (item[0], item[1]))
    # 根以下任何链接/junction/reparse 段一律拒绝（根自身允许是可信配置里的链接）。
    target_for_links = candidate if _within(candidate, root) else resolved
    pathguard.assert_no_link_segments(target_for_links, root, kind=f"paths[{position}]")
    try:
        info = os.lstat(candidate)
    except FileNotFoundError:
        raise errors.invalid_input(f"paths[{position}] 指向的源文件不存在",
                                   {"position": position}) from None
    except OSError:
        raise errors.invalid_input(f"paths[{position}] 指向的源文件不可访问",
                                   {"position": position}) from None
    if stat.S_ISLNK(info.st_mode) or pathguard.is_linkish(candidate):
        raise errors.path_unsafe(f"paths[{position}] 是符号链接/junction，本机入库只接受普通文件",
                                 {"position": position})
    if not stat.S_ISREG(info.st_mode):
        raise errors.invalid_input(f"paths[{position}] 必须是普通文件（不接受目录/设备）",
                                   {"position": position})
    return {
        "path": candidate,
        "resolved": resolved,
        "storageId": storage_id,
        "root": root,
        "bytes": int(info.st_size),
        "mtimeNs": int(info.st_mtime_ns),
    }


def _reject_library_root_overlap(entry: dict, libraries: dict[str, dict], position: int) -> None:
    """源不得位于任何媒体库根内（库内文件已建索引，只能走媒体/回收站通道）。"""
    for library in libraries.values():
        root = library.get("rootPath")
        if not isinstance(root, str) or not root.strip():
            continue
        library_root = Path(root).resolve(strict=False)
        if _within(entry["resolved"], library_root) or _within(entry["path"], library_root):
            raise errors.source_in_library_root(
                f"paths[{position}] 位于媒体库 {library.get('id')} 的库根内",
                {"position": position, "libraryId": library.get("id")})


def _target_status(library: dict, rel_path: str, size: int) -> str:
    """本地可判定的预期状态：move / already_present / conflict / unknown。

    只比较大小与存在性（不读内容）：already_present 仍需服务端做 size+SHA256 比对，
    内容不同会返回 TARGET_EXISTS。库根不可访问时如实返回 unknown。
    """
    root = library.get("rootPath")
    if not isinstance(root, str) or not root.strip():
        return STATUS_UNKNOWN
    target = Path(root, *rel_path.split("/"))
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return STATUS_MOVE
    except OSError:
        return STATUS_UNKNOWN
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        return STATUS_CONFLICT
    return STATUS_PRESENT if info.st_size == size else STATUS_CONFLICT


def _files_digest(entries: list[dict]) -> str:
    return plans.digest([{"path": str(entry["path"]), "bytes": entry["bytes"],
                          "mtimeNs": entry["mtimeNs"]} for entry in entries])


def asset_ingest_local_prepare(ctx, payload: dict) -> dict:
    """只读：校验源与目标并生成本机直接入库计划；不移动任何文件。"""
    library_id = _library_id(payload.get("libraryId"))
    directory = _directory(payload.get("directory"))
    operation_id = _operation_id(payload.get("operationId"))
    paths = payload.get("paths")
    if not isinstance(paths, list) or not paths:
        raise errors.invalid_input("paths 必须是非空绝对路径数组")
    if len(paths) > MAX_PATHS_PER_CALL:
        raise errors.invalid_input(f"单次最多 {MAX_PATHS_PER_CALL} 个源文件；请分批执行")
    libraries = _libraries(ctx)
    library = libraries.get(library_id)
    if library is None:
        raise errors.library_not_found(library_id)
    roots = _storage_roots(ctx)
    entries: list[dict] = []
    seen_paths: set[str] = set()
    seen_targets: set[str] = set()
    total_bytes = 0
    for position, raw in enumerate(paths):
        entry = _source_entry(raw, roots, position)
        key = os.path.normcase(str(entry["path"]))
        if key in seen_paths:
            raise errors.invalid_input(f"paths[{position}] 与前面的路径重复")
        seen_paths.add(key)
        _reject_library_root_overlap(entry, libraries, position)
        entry["fileName"] = entry["path"].name
        entry["relPath"] = entry["fileName"] if not directory \
            else directory + "/" + entry["fileName"]
        target_key = os.path.normcase(entry["relPath"])
        if target_key in seen_targets:
            raise errors.invalid_input(
                f"paths[{position}] 的目标相对路径重复：{entry['relPath']}",
                {"relPath": entry["relPath"]})
        seen_targets.add(target_key)
        entry["expectedStatus"] = _target_status(library, entry["relPath"], entry["bytes"])
        total_bytes += entry["bytes"]
        if total_bytes > MAX_TOTAL_BYTES:
            raise errors.invalid_input(
                f"单次调用总字节超过 {MAX_TOTAL_BYTES}；请分批执行",
                {"maxTotalBytes": MAX_TOTAL_BYTES})
        entries.append(entry)

    changes = [{
        "path": str(entry["path"]),
        "storageId": entry["storageId"],
        "fileName": entry["fileName"],
        "directory": directory,
        "relPath": entry["relPath"],
        "bytes": entry["bytes"],
        "sha256": None,
        "sourceMtimeNs": entry["mtimeNs"],
        "expectedStatus": entry["expectedStatus"],
    } for entry in entries]
    counts = {status: sum(1 for entry in entries if entry["expectedStatus"] == status)
              for status in (STATUS_MOVE, STATUS_PRESENT, STATUS_CONFLICT, STATUS_UNKNOWN)}
    extra: dict = {
        "preconditions": {
            "libraryRootDigest": plans.digest(library.get("rootPath")),
            "filesDigest": _files_digest(entries),
        },
        "notices": [
            "prepare 只读：不移动文件、不建索引。",
            "already_present 是幂等结果（目标同内容，源不动），不是失败；内容不同返回 TARGET_EXISTS 且不覆盖。",
            "摘要由服务端在复制/幂等比对路径计算；同分区纯 rename 快路径不计算 SHA-256（计划里为 null）。",
            "单请求超时预算：60s 基础 + 每 GiB 60s，封顶 600s（跨分区复制大文件不会被默认 20s 打断）。",
            "服务端另有独立读根 QIMENG_INGEST_LOCAL_ROOTS；未配置时端点一律拒绝。",
        ],
    }
    if operation_id:
        extra["operationId"] = operation_id
    plan = plans.make_plan(
        tool="asset.ingest.local",
        tool_version="1.0.1",
        kind="asset.ingest.local",
        target={
            "instance": ctx.target,
            "library": {"id": library_id, "name": library.get("name"),
                        "kind": library.get("kind")},
            "objects": [],
        },
        changes=changes,
        impact={
            "files": len(entries),
            "totalBytes": total_bytes,
            "expectedMoves": counts[STATUS_MOVE],
            "expectedAlreadyPresent": counts[STATUS_PRESENT],
            "expectedConflicts": counts[STATUS_CONFLICT],
            "expectedUnknown": counts[STATUS_UNKNOWN],
            "businessWrites": True,
            "libraryAssetsDeleted": False,
        },
        evidence=[
            {"source": "api", "endpoint": "GET /api/v1/libraries",
             "libraryId": library_id, "libraryName": library.get("name")},
            {"source": "local", "storageIds": sorted({entry["storageId"] for entry in entries})},
        ],
        versions_info=plans.version_snapshot(ctx.rulepack_version, ctx.openapi_sha256),
        confirmation={
            "required": True,
            "risk": "medium",
            "reason": ("把本机源文件移动进媒体库并建索引；成功入库后源文件从原位消失。"
                       "已存在同内容目标只返回 already_present，绝不覆盖。"),
        },
        recoverable_steps=[
            "prepare 不写入数据。",
            "apply 逐文件执行：失败即停止，已完成的文件按 details.completed 列出并保持入库状态。",
            "同分区 rename 后才建索引；跨分区复制经长度+SHA256 复核并建索引成功后才删源，任何失败都不丢源。",
            "status=ingested 但源仍存在（删源失败）报 SOURCE_NOT_REMOVED：资产已入库，不要重复入库。",
            "重复执行同一计划：目标已存在且内容一致 → already_present（源不动，本地再比对 sha256）。",
        ],
        expected_readback=[
            "GET /assets/{assetId} 存在，libraryId/relPath/sizeBytes 与计划一致",
            "already_present 时源文件仍在原位（sourceKept=true）",
            "库内不出现第二份同名资产（不覆盖、不自动改名）",
        ],
        extra=extra,
    )
    return {"plan": plan, "diagnostics": {
        "library": {"id": library_id, "name": library.get("name")},
        "directory": directory,
        "operationId": operation_id,
        "totalBytes": total_bytes,
        "files": [{"relPath": entry["relPath"], "bytes": entry["bytes"],
                   "expectedStatus": entry["expectedStatus"],
                   "storageId": entry["storageId"]} for entry in entries],
        "limits": {"maxPaths": MAX_PATHS_PER_CALL, "maxTotalBytes": MAX_TOTAL_BYTES},
        "notices": ["确认后传 planRef（或完整 plan）与 confirm=true；apply 会重新核对源与库根。"],
    }}


def _map_http_error(exc: http_client.HTTPStatusError, *, library_id: str,
                    position: int, entry: dict) -> errors.ToolError:
    """把 ingest-local 的服务端响应映射成稳定错误码（不回显服务端内部路径）。"""
    body = exc.body if isinstance(exc.body, dict) else {}
    code = body.get("code") if isinstance(body, dict) else None
    details = {"status": exc.status, "code": code, "position": position,
               "fileName": entry["fileName"], "relPath": entry["relPath"]}
    if exc.status == 403:
        if code == "FORBIDDEN_SCOPE":
            denied = errors.forbidden_scope("asset.ingest.local", str(body)[:500],
                                            {"status": exc.status})
            # scope 在服务端处理请求前拒绝：确定未写入。
            denied.mutation_rejected = True
            return denied
        return errors.not_in_storage_root(
            "服务端拒绝：未配置本机入库读根，或源路径不在服务端读根内"
            "（服务端 QIMENG_INGEST_LOCAL_ROOTS 与 AI 侧 QIMENG_AI_STORAGES 是两套配置）",
            details)
    if exc.status == 404:
        if code == "LIBRARY_NOT_FOUND":
            return errors.library_not_found(library_id, details)
        return errors.api_unavailable("POST " + INGEST_PATH,
                                      "目标实例没有 ingest-local 端点（404）；请确认服务端版本",
                                      details, mutation_rejected=True)
    if exc.status == 405:
        return errors.api_unavailable("POST " + INGEST_PATH,
                                      "目标实例不支持 POST ingest-local（405）", details,
                                      mutation_rejected=True)
    if exc.status == 409:
        return errors.target_exists(details=details)
    if exc.status == 507:
        # 服务端在复制/落位前检查余量：507 时源与目标都未变化。
        return errors.storage_unavailable(details=details, mutation_rejected=True)
    if exc.status == 413:
        return errors.invalid_input("服务端拒绝：文件超过上限（413 UPLOAD_TOO_LARGE）", details)
    if exc.status == 400:
        return errors.invalid_input("服务端拒绝本机入库输入（400 " + str(code) + "）", details)
    if exc.status == 401:
        # 401 在服务端鉴权层直接拒绝，一个字节都没写：标 mutation_rejected，
        # 否则会被当成「可能已写入」进未决账本，用户端表现为卡住不动。
        return errors.configuration_error(
            "服务端鉴权失败（401）；检查 QIMENG_API_TOKEN 是否为该实例的有效凭据",
            mutation_rejected=True)
    return errors.ToolError(
        "REMOTE_ERROR",
        f"本机入库请求失败：HTTP {exc.status}",
        category="remote",
        exit_code=1,
        recovery="按 details.status/code 核对服务端日志与存储状态；不要循环重试同一文件。",
        details=details,
    )


def _server_result(response, *, library_id: str, entry: dict) -> dict:
    if not isinstance(response, dict):
        raise errors.readback_mismatch("ingest-local 响应不是对象",
                                       {"position": entry["position"]})
    status = response.get("status")
    if status not in _SERVER_STATUSES:
        raise errors.readback_mismatch("ingest-local 响应状态未知",
                                       {"status": status, "position": entry["position"]})
    asset_id = response.get("assetId")
    if not isinstance(asset_id, str) or not asset_id:
        raise errors.readback_mismatch("ingest-local 响应缺少 assetId",
                                       {"position": entry["position"]})
    if response.get("libraryId") not in (None, library_id):
        raise errors.readback_mismatch(
            "ingest-local 返回的库与计划不一致",
            {"position": entry["position"], "libraryId": response.get("libraryId")})
    size = response.get("sizeBytes")
    if not isinstance(size, int) or isinstance(size, bool) or size < 0:
        raise errors.readback_mismatch("ingest-local 响应缺少有效 sizeBytes",
                                       {"position": entry["position"]})
    if size != entry["bytes"]:
        raise errors.readback_mismatch(
            "落盘字节数与计划不一致（源可能在 prepare 后变化）",
            {"position": entry["position"], "plannedBytes": entry["bytes"],
             "serverBytes": size})
    rel_path = response.get("relPath")
    if not isinstance(rel_path, str) or not rel_path:
        raise errors.readback_mismatch("ingest-local 响应缺少 relPath",
                                       {"position": entry["position"]})
    if rel_path != entry["relPath"]:
        raise errors.readback_mismatch(
            "落盘相对路径与计划不一致（服务端可能清洗了文件名）；请改名源文件后重新 prepare",
            {"position": entry["position"], "plannedRelPath": entry["relPath"],
             "serverRelPath": rel_path})
    return {
        "status": status,
        "assetId": asset_id,
        "relPath": rel_path,
        "fileName": response.get("fileName") or entry["fileName"],
        "sizeBytes": size,
        "sha256": response.get("sha256") if isinstance(response.get("sha256"), str) else None,
        "sourceKept": response.get("sourceKept") is True,
    }


def _readback(ctx, served: dict, *, library_id: str, entry: dict) -> dict:
    """GET /assets/{assetId}：确认存在、库、相对路径与字节数一致。"""
    try:
        asset = ctx.client.get(_asset_path(served["assetId"]))
    except http_client.HTTPStatusError as exc:
        raise errors.readback_mismatch(
            "入库后回读资产失败（服务端返回错误）",
            {"assetId": served["assetId"], "position": entry["position"],
             "status": exc.status}) from None
    except (http_client.NetworkError, http_client.ResponseError) as exc:
        raise errors.api_unavailable(
            "GET " + _asset_path(served["assetId"]),
            "入库已返回成功，但资产回读失败/超时：结果未核对，先重试只读回读，勿重传",
            {"reason": getattr(exc, "kind", type(exc).__name__),
             "assetId": served["assetId"], "position": entry["position"]}) from None
    if not isinstance(asset, dict):
        raise errors.readback_mismatch("资产详情响应不是对象",
                                       {"assetId": served["assetId"]})
    mismatches = {}
    if asset.get("id") not in (None, served["assetId"]):
        mismatches["assetId"] = asset.get("id")
    if asset.get("libraryId") not in (None, library_id):
        mismatches["libraryId"] = asset.get("libraryId")
    if asset.get("relPath") != served["relPath"]:
        mismatches["relPath"] = asset.get("relPath")
    if asset.get("sizeBytes") != served["sizeBytes"]:
        mismatches["sizeBytes"] = asset.get("sizeBytes")
    if mismatches:
        raise errors.readback_mismatch(
            "入库后资产回读与 ingest-local 响应/计划不一致",
            {"assetId": served["assetId"], "position": entry["position"],
             "plannedRelPath": entry["relPath"], "serverRelPath": served["relPath"],
             "mismatches": mismatches})
    return {"assetId": served["assetId"], "libraryId": asset.get("libraryId"),
            "relPath": asset.get("relPath"), "sizeBytes": asset.get("sizeBytes"),
            "fileName": asset.get("fileName"), "mediaType": asset.get("mediaType")}


def _planned_entries(plan: dict, libraries: dict[str, dict], library_id: str,
                     ctx) -> tuple[list[dict], str]:
    """apply 侧重新核对计划里的每个源（授权/大小/时间/摘要），返回当前条目。"""
    changes = plan.get("changes")
    if not isinstance(changes, list) or not changes:
        raise errors.plan_invalid("计划缺少 changes")
    if len(changes) > MAX_PATHS_PER_CALL:
        raise errors.plan_invalid(f"计划超过单次上限 {MAX_PATHS_PER_CALL} 个文件")
    roots = _storage_roots(ctx)
    entries: list[dict] = []
    directory = ""
    for position, change in enumerate(changes):
        if not isinstance(change, dict):
            raise errors.plan_invalid("计划 changes 条目必须是对象")
        planned_path = change.get("path")
        planned_bytes = change.get("bytes")
        planned_mtime = change.get("sourceMtimeNs")
        if not isinstance(planned_path, str) or not isinstance(planned_bytes, int) \
                or isinstance(planned_bytes, bool) or not isinstance(planned_mtime, int):
            raise errors.plan_invalid("计划 changes 条目缺少 path/bytes/sourceMtimeNs")
        planned_directory = change.get("directory") or ""
        if position == 0:
            directory = planned_directory
        elif planned_directory != directory:
            raise errors.plan_invalid("计划内 directory 不一致")
        if change.get("expectedStatus") not in _PLANNED_STATUSES:
            raise errors.plan_invalid("计划 changes 条目 expectedStatus 非法")
        entry = _source_entry(planned_path, roots, position)
        _reject_library_root_overlap(entry, libraries, position)
        entry["fileName"] = change.get("fileName") or entry["path"].name
        entry["relPath"] = change.get("relPath") or (
            entry["fileName"] if not directory else directory + "/" + entry["fileName"])
        entry["expectedStatus"] = change.get("expectedStatus")
        entry["position"] = position
        if entry["bytes"] != planned_bytes:
            raise errors.precondition_failed(
                "源文件大小在 prepare 后变化，请重新 prepare",
                {"position": position, "plannedBytes": planned_bytes,
                 "currentBytes": entry["bytes"]}, mutation_rejected=True)
        if entry["mtimeNs"] != planned_mtime:
            raise errors.precondition_failed(
                "源文件修改时间在 prepare 后变化，请重新 prepare",
                {"position": position}, mutation_rejected=True)
        entries.append(entry)
    preconditions = plan.get("preconditions") or {}
    if preconditions.get("filesDigest") != _files_digest(entries):
        raise errors.precondition_failed("源文件集合在 prepare 后变化，请重新 prepare",
                                         mutation_rejected=True)
    return entries, directory


def _apply_preflight(ctx, payload: dict) -> tuple[dict, list[dict], str, str]:
    """apply 的全部预检（计划/确认/库根/源复核），在第一个 POST 之前完成。"""
    plan = plans.verify_plan(payload.get("plan"), tool_id="asset.ingest.local",
                             rulepack_version=ctx.rulepack_version, instance=ctx.target)
    if payload.get("confirm") is not True:
        raise errors.not_confirmed("asset.ingest.local")
    if plan.get("kind") != "asset.ingest.local":
        raise errors.plan_invalid("计划类型不是 asset.ingest.local")
    target = plan.get("target") if isinstance(plan.get("target"), dict) else {}
    library_meta = target.get("library") if isinstance(target.get("library"), dict) else {}
    library_id = _library_id(library_meta.get("id"))
    libraries = _libraries(ctx)
    library = libraries.get(library_id)
    if library is None:
        raise errors.library_not_found(library_id)
    preconditions = plan.get("preconditions") or {}
    if preconditions.get("libraryRootDigest") != plans.digest(library.get("rootPath")):
        raise errors.precondition_failed("目标库根在 prepare 后已变化，请重新 prepare",
                                         mutation_rejected=True)
    entries, directory = _planned_entries(plan, libraries, library_id, ctx)
    # 计划里文件名必须与当前源文件名一致：服务端按 fileName 落盘，改名属于新计划。
    for entry in entries:
        if entry["fileName"] != entry["path"].name:
            raise errors.precondition_failed(
                "计划中的文件名与源文件当前名称不一致，请重新 prepare",
                {"position": entry["position"]}, mutation_rejected=True)
    return plan, entries, directory, library_id


def _post_ingest(ctx, library_id: str, directory: str, entry: dict) -> dict:
    """发一个 ingest-local 请求（按文件字节数放大超时），返回解析后的结果。"""
    body = {"sourcePath": str(entry["path"]), "fileName": entry["fileName"]}
    if directory:
        body["directory"] = directory
    try:
        response = ctx.client.post(_library_path(library_id), body,
                                   timeout=request_timeout(entry["bytes"]))
    except http_client.HTTPStatusError as exc:
        raise _map_http_error(exc, library_id=library_id,
                              position=entry["position"], entry=entry) from None
    except (http_client.NetworkError, http_client.ResponseError) as exc:
        raise errors.api_unavailable(
            "POST " + _library_path(library_id),
            "本机入库请求失败/超时：结果可能未知，先回读目标再决定是否重试",
            {"reason": getattr(exc, "kind", type(exc).__name__),
             "position": entry["position"], "relPath": entry["relPath"]}) from None
    return _server_result(response, library_id=library_id, entry=entry)


def _require_source_removed(served: dict, entry: dict) -> None:
    """move 语义：status=ingested 时源必须已被移除。

    服务端跨分区复制成功但删源失败也会返回 ingested+sourceKept=true（资产已入库、
    源保留）。这里不能报成功：move 没有真正完成，源还在原位会被误认为已移动。
    already_present 的 sourceKept=true 是契约内语义（源本来就不动），不在此列。
    """
    if served["status"] != "ingested" or not served["sourceKept"]:
        return
    raise errors.source_not_removed(details={
        "assetId": served["assetId"], "relPath": served["relPath"],
        "position": entry["position"], "bytes": served["sizeBytes"],
        "reason": "server_reported_source_kept",
    })


def _verify_present_content(served: dict, entry: dict) -> str | None:
    """already_present 的幂等结果必须与本地源同内容。

    服务端回传的 sha256 是目标（已存在文件）摘要，且服务端此前已用它与源比对过；
    这里再用本地源重新哈希做闭环（只在这条幂等路径计算，纯 rename 快路径不受影响）。
    """
    if served["status"] != "already_present":
        return served["sha256"]
    digest = served["sha256"]
    if not isinstance(digest, str) or not _SHA256_RE.match(digest):
        raise errors.readback_mismatch(
            "already_present 未返回有效 sha256，无法核对源内容是否一致",
            {"assetId": served["assetId"], "relPath": served["relPath"],
             "position": entry["position"]})
    try:
        local_sha, local_bytes = pathguard.sha256_file(entry["path"], root=entry["root"])
    except OSError as exc:
        raise errors.readback_mismatch(
            "already_present 后无法读取本地源做摘要复核",
            {"assetId": served["assetId"], "position": entry["position"],
             "reason": type(exc).__name__}) from None
    if local_bytes != entry["bytes"] or local_sha != digest:
        raise errors.content_mismatch(
            details={"assetId": served["assetId"], "relPath": served["relPath"],
                     "position": entry["position"],
                     "serverSha256": digest, "localSha256": local_sha,
                     "plannedBytes": entry["bytes"], "localBytes": local_bytes})
    return local_sha


def _completed_refs(completed: list[dict]) -> list[dict]:
    """失败时回报已完成的文件：只给定位与结果，供按 assetId 回读、禁止整批重传。"""
    return [{"assetId": item["assetId"], "status": item["status"],
             "relPath": item["relPath"]} for item in completed]


def asset_ingest_local(ctx, payload: dict) -> dict:
    """按计划逐文件移动入库并回读；重复执行同一计划是幂等的（already_present 是成功）。"""
    try:
        plan, entries, directory, library_id = _apply_preflight(ctx, payload)
    except errors.ToolError as exc:
        # 预检失败时还没有发出任何 ingest-local 请求：源与库均未变化，不进入未决写入。
        exc.mutation_rejected = True
        raise

    completed: list[dict] = []
    for entry in entries:
        try:
            served = _post_ingest(ctx, library_id, directory, entry)
            _require_source_removed(served, entry)
            _verify_present_content(served, entry)
            readback = _readback(ctx, served, library_id=library_id, entry=entry)
        except errors.ToolError as exc:
            # 所有失败出口（请求/响应校验/源未删/内容不符/回读）统一附已完成清单。
            exc.details = {**(exc.details or {}), "completed": _completed_refs(completed)}
            # 批内前面已有文件落位并入库：整批不再是「完全未写入」，交给未决写入核对。
            if completed and exc.mutation_rejected:
                exc.mutation_rejected = False
            raise
        completed.append({
            "position": entry["position"],
            "relPath": served["relPath"],
            "fileName": served["fileName"],
            "assetId": served["assetId"],
            "status": served["status"],
            "bytes": served["sizeBytes"],
            "sha256": served["sha256"],
            "sourceKept": served["sourceKept"],
            "expectedStatus": entry["expectedStatus"],
            "readback": readback,
        })
    already = [item for item in completed if item["status"] == "already_present"]
    status = "already_applied" if len(already) == len(completed) else "applied"
    return {
        "status": status,
        "tool": "asset.ingest.local",
        "libraryId": library_id,
        "planFingerprint": plan["planFingerprint"],
        "idempotencyKey": plan["idempotencyKey"],
        "operationId": plan.get("operationId"),
        "files": completed,
        "counts": {"ingested": len(completed) - len(already),
                   "alreadyPresent": len(already)},
        "steps": [
            {"step": 1, "action": "query-current", "status": "completed"},
            {"step": 2, "action": "verify-plan-and-preconditions", "status": "completed"},
            {"step": 3, "action": "POST /api/v1/libraries/{libraryId}/ingest-local",
             "status": "completed", "files": len(completed)},
            {"step": 4, "action": "readback GET /api/v1/assets/{assetId}",
             "status": "completed"},
        ],
        "summary": (f"{len(completed)} 个文件已入库并回读"
                    f"（already_present {len(already)} 个）；"
                    "服务端未覆盖任何已存在文件。"),
    }
