"""设备结果包工具：device.apply.prepare / device.apply.submit / device.apply.status。

服务端契约见 OpenAPI POST/GET /api/v1/device/apply-tasks（ADR-0055）：
- prepare 只读：读取本地作者主表 TXT / v2 便携备份，计算 bytes + SHA-256，
  按 Go 上限（masterTxt ≤1MiB、v2Backup ≤64MiB）校验，生成 manifest 与带
  指纹/幂等键的计划；不发起任何写请求。
- submit：confirm=true 且计划指纹/工具契约/规则版本/目标实例一致；重新读取
  源文件并复核 bytes + SHA-256（变化即拒绝），再以 multipart（manifest 为 JSON
  字符串 + 与 kind 同名的文件段）登记结果包，随后回读任务详情核对段清单。
- status：只读列表/详情（targetDevice 过滤 + keyset 分页）。
- 手机 Worker/配对仍未实现：工具只负责 PC 侧登记与查询，不假装已应用到手机。
"""
from __future__ import annotations

import os
import re
from urllib import parse

from . import client as http_client
from . import errors, pathguard, plans
from .runtime import ToolContext

APPLY_TASKS_PATH = "/api/v1/device/apply-tasks"
PROTOCOL_VERSION = 1
MAX_MASTER_TXT_BYTES = 1 << 20
MAX_V2_BACKUP_BYTES = 64 << 20
MAX_MANIFEST_BYTES = 256 << 10
MAX_READBACK_JSON_BYTES = 16 << 10
MIN_TTL_SECONDS = 300
MAX_TTL_SECONDS = 604800
DEFAULT_TTL_SECONDS = 86400
KIND_LIMITS = {"masterTxt": MAX_MASTER_TXT_BYTES, "v2Backup": MAX_V2_BACKUP_BYTES}
KIND_PATHS = {"masterTxt": "masterTxtPath", "v2Backup": "v2BackupPath"}
EXPECTED_STATE_KEYS = ("assets", "authors", "assetAuthorRefs", "tags",
                       "assetTagRefs", "viewEvents")
_DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,100}$")
_RULES_FINGERPRINT_RE = re.compile(r"^[0-9a-f]{64}$")


def _device_id(value) -> str:
    if not isinstance(value, str) or not _DEVICE_ID_RE.match(value):
        raise errors.invalid_input(
            "targetDeviceId 只允许 1..100 个字母/数字/._:- 字符")
    return value


def _ttl_seconds(payload: dict) -> int:
    value = payload.get("ttlSeconds")
    if value is None:
        return DEFAULT_TTL_SECONDS
    if not isinstance(value, int) or isinstance(value, bool):
        raise errors.invalid_input("ttlSeconds 必须是整数")
    if not MIN_TTL_SECONDS <= value <= MAX_TTL_SECONDS:
        raise errors.invalid_input(
            f"ttlSeconds 必须在 {MIN_TTL_SECONDS}..{MAX_TTL_SECONDS}（5 分钟–7 天）")
    return value


def _rules_fingerprint(ctx: ToolContext, payload: dict) -> str:
    value = payload.get("rulesFingerprint")
    if value is not None:
        if not isinstance(value, str) or not _RULES_FINGERPRINT_RE.match(value):
            raise errors.invalid_input("rulesFingerprint 必须是 64 位小写十六进制")
        return value
    rules = getattr(ctx, "rules", None)
    if not isinstance(rules, dict) or not rules:
        raise errors.invalid_input("缺少 rulesFingerprint，且当前上下文没有规则源可计算默认值")
    return plans.digest(rules)


def _bounded_json(value, limit: int, label: str):
    if not isinstance(value, dict):
        raise errors.invalid_input(label + " 必须是 JSON 对象")
    try:
        size = len(plans.canonical_json(value).encode("utf-8"))
    except (TypeError, ValueError):
        raise errors.invalid_input(label + " 无法序列化为 JSON") from None
    if size > limit:
        raise errors.invalid_input(f"{label} 超过 {limit} 字节上限")
    return value


def _expected_readback(payload: dict) -> dict:
    value = payload.get("expectedReadbackByKind")
    if value is None:
        return {}
    body = _bounded_json(value, MAX_READBACK_JSON_BYTES, "expectedReadbackByKind")
    unknown = [kind for kind in body if kind not in KIND_LIMITS]
    if unknown:
        raise errors.invalid_input("expectedReadbackByKind 含未知 kind：" + ", ".join(unknown),
                                   details={"allowedKinds": sorted(KIND_LIMITS)})
    for kind, item in body.items():
        _bounded_json(item, MAX_READBACK_JSON_BYTES, "expectedReadbackByKind." + kind)
    return body


def _expected_state(payload: dict) -> dict:
    value = payload.get("expectedState")
    if value is None:
        return {}
    body = _bounded_json(value, MAX_READBACK_JSON_BYTES, "expectedState")
    unknown = [key for key in body if key not in EXPECTED_STATE_KEYS]
    if unknown:
        raise errors.invalid_input("expectedState 含不支持的键：" + ", ".join(unknown),
                                   details={"allowedKeys": list(EXPECTED_STATE_KEYS)})
    for key, count in body.items():
        if not isinstance(count, int) or isinstance(count, bool) or count < 0:
            raise errors.invalid_input(f"expectedState.{key} 必须是非负整数")
    return body


def _segment(ctx: ToolContext, kind: str, path_value, expected_readback) -> tuple:
    limit = KIND_LIMITS[kind]
    path = pathguard.resolve_local_path(path_value, env=ctx.env, must_exist=True,
                                       kind=KIND_PATHS[kind], mode="read")
    root = pathguard.root_for_path(path, mode="read", env=ctx.env)
    sha256, size = pathguard.sha256_file(path, root=root)
    if size == 0:
        raise errors.invalid_input(f"{KIND_PATHS[kind]} 是空文件")
    if size > limit:
        raise errors.invalid_input(
            f"{KIND_PATHS[kind]} 超过 {kind} 上限（{limit} 字节）",
            details={"kind": kind, "bytes": size, "limitBytes": limit},
        )
    segment = {"kind": kind, "bytes": size, "sha256": sha256}
    if expected_readback is not None:
        segment["expectedReadback"] = expected_readback
    return path, segment


def _build_manifest(ctx: ToolContext, payload: dict):
    target_device_id = _device_id(payload.get("targetDeviceId"))
    ttl_seconds = _ttl_seconds(payload)
    rules_fingerprint = _rules_fingerprint(ctx, payload)
    expected_readback = _expected_readback(payload)
    expected_state = _expected_state(payload)
    files = []
    segments = []
    for kind in ("masterTxt", "v2Backup"):
        path_value = payload.get(KIND_PATHS[kind])
        if path_value is None:
            continue
        path, segment = _segment(ctx, kind, path_value,
                                 expected_readback.get(kind))
        files.append({"kind": kind, "path": str(path),
                      "bytes": segment["bytes"], "sha256": segment["sha256"]})
        segments.append(segment)
    if not segments:
        raise errors.invalid_input("masterTxtPath 与 v2BackupPath 至少提供一个")
    manifest = {
        "targetDeviceId": target_device_id,
        "protocolVersion": PROTOCOL_VERSION,
        "rulesFingerprint": rules_fingerprint,
        "ttlSeconds": ttl_seconds,
        "segments": segments,
    }
    if expected_state:
        manifest["expectedState"] = expected_state
    encoded = plans.canonical_json(manifest).encode("utf-8")
    if len(encoded) > MAX_MANIFEST_BYTES:
        raise errors.invalid_input(f"manifest 超过 {MAX_MANIFEST_BYTES} 字节上限")
    return manifest, files


def _expected_readback_checks(manifest: dict) -> list[str]:
    checks = [
        "POST /device/apply-tasks 返回任务的 segments 与 manifest 的 kind/bytes/sha256 完全一致",
        "任务初始 status=pending，expiresAt = 创建时刻 + ttlSeconds",
        "段字节只在 pending/claimed 且未过期时可下载，服务端复算 SHA-256",
    ]
    for segment in manifest.get("segments") or []:
        if segment.get("expectedReadback"):
            checks.append("手机回传 readback 的 " + segment["kind"] + " 段与 expectedReadback 对照")
    if manifest.get("expectedState"):
        checks.append("手机回传结果时按 expectedState 与库内真实计数比较（expectedStateDiff 应为空）")
    return checks


def device_apply_prepare(ctx: ToolContext, payload: dict) -> dict:
    manifest, files = _build_manifest(ctx, payload)
    total_bytes = sum(segment["bytes"] for segment in manifest["segments"])
    plan = plans.make_plan(
        tool="device.apply.submit",
        tool_version="1.0.0",
        kind="device.apply",
        target={"instance": ctx.target, "device": manifest["targetDeviceId"]},
        changes=[{"field": "segments.register", "oldValue": [],
                  "newValue": [{"kind": segment["kind"], "bytes": segment["bytes"],
                                "sha256": segment["sha256"]}
                               for segment in manifest["segments"]]}],
        impact={"segments": len(manifest["segments"]), "totalBytes": total_bytes,
                "devicePackagesCreated": 1, "mediaFilesTouched": 0},
        evidence=[
            {"source": "local-file", "kind": item["kind"], "path": item["path"],
             "bytes": item["bytes"], "sha256": item["sha256"]}
            for item in files
        ],
        versions_info=plans.version_snapshot(ctx.rulepack_version, ctx.openapi_sha256),
        confirmation={
            "required": True,
            "risk": "medium",
            "reason": "登记设备结果包会在服务端保存段文件并由手机领取；段正文不进入 SQLite。",
        },
        recoverable_steps=[
            "prepare 不写任何数据，可重复执行。",
            "submit 前重新读取源文件并复核 bytes/SHA-256，变化即拒绝（重新 prepare）。",
            "结果包可取消（POST /device/apply-tasks/{id}/cancel）；段文件不删除账本行。",
        ],
        expected_readback=_expected_readback_checks(manifest),
        extra={
            "manifest": manifest,
            "files": files,
            "preconditions": {
                "manifestDigest": plans.digest(manifest),
                "sourceFiles": [{"kind": item["kind"], "bytes": item["bytes"],
                                 "sha256": item["sha256"]} for item in files],
            },
            "notices": [
                "targetDeviceId 只是不透明标签，本轮不校验设备身份、未实现配对。",
                "rulesFingerprint 默认取当前规则包摘要；服务端只校验格式并记录，不重算规则包。",
            ],
        },
    )
    return {"plan": plan, "diagnostics": {
        "targetDeviceId": manifest["targetDeviceId"],
        "segments": manifest["segments"],
        "manifestDigest": plans.digest(manifest),
        "totalBytes": total_bytes,
        "ttlSeconds": manifest["ttlSeconds"],
        "rulesFingerprint": manifest["rulesFingerprint"],
    }}


def _safe_name(name: str) -> str:
    cleaned = "".join(ch if ch.isascii() and (ch.isalnum() or ch in "._-") else "_" for ch in name)
    cleaned = cleaned.strip("._") or "segment"
    return cleaned[:120]


def _multipart(fields) -> tuple[bytes, str]:
    """fields: [(name, bytes, filename|None, content_type|None)]。"""
    boundary = "qimeng" + os.urandom(16).hex()
    chunks: list[bytes] = []
    for name, value, filename, content_type in fields:
        chunks.append(("--" + boundary + "\r\n").encode("utf-8"))
        if filename:
            chunks.append((
                f'Content-Disposition: form-data; name="{name}"; filename="{_safe_name(filename)}"\r\n'
                f"Content-Type: {content_type or 'application/octet-stream'}\r\n\r\n"
            ).encode("utf-8"))
            chunks.append(value)
            chunks.append(b"\r\n")
        else:
            chunks.append(
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode("utf-8"))
            chunks.append(value)
            chunks.append(b"\r\n")
    chunks.append(("--" + boundary + "--\r\n").encode("utf-8"))
    return b"".join(chunks), "multipart/form-data; boundary=" + boundary


def _verified_manifest_and_files(ctx: ToolContext, plan: dict) -> tuple[dict, list[dict]]:
    manifest = plan.get("manifest")
    files = plan.get("files")
    if not isinstance(manifest, dict) or not isinstance(files, list):
        raise errors.plan_invalid("设备结果包计划缺少 manifest/files")
    if manifest.get("protocolVersion") != PROTOCOL_VERSION:
        raise errors.plan_invalid("计划 protocolVersion 必须是 1")
    if not isinstance(manifest.get("segments"), list) or not manifest["segments"]:
        raise errors.plan_invalid("计划 manifest.segments 不能为空")
    expected = plan.get("preconditions") or {}
    if expected.get("manifestDigest") != plans.digest(manifest):
        raise errors.plan_fingerprint_mismatch(plans.digest(manifest),
                                               expected.get("manifestDigest"))
    verified = []
    for entry in files:
        if not isinstance(entry, dict):
            raise errors.plan_invalid("计划 files 条目必须是对象")
        path = pathguard.resolve_local_path(entry.get("path"), env=ctx.env,
                                            must_exist=True, kind="源文件", mode="read")
        root = pathguard.root_for_path(path, mode="read", env=ctx.env)
        sha256, size = pathguard.sha256_file(path, root=root)
        if sha256 != entry.get("sha256") or size != entry.get("bytes"):
            raise errors.precondition_failed(
                "结果包源文件在 prepare 后发生变化，拒绝登记",
                {"kind": entry.get("kind"), "expectedBytes": entry.get("bytes"),
                 "currentBytes": size, "expectedSha256": entry.get("sha256"),
                 "currentSha256": sha256},
            )
        verified.append({"kind": entry["kind"], "path": str(path),
                         "bytes": size, "sha256": sha256})
    if sorted(item["kind"] for item in verified) != sorted(
            segment["kind"] for segment in manifest["segments"]):
        raise errors.plan_invalid("计划 files 与 manifest.segments 的 kind 不一致")
    return manifest, verified


def device_apply_submit(ctx: ToolContext, payload: dict) -> dict:
    plan = plans.verify_plan(payload.get("plan"), tool_id="device.apply.submit",
                             rulepack_version=ctx.rulepack_version, instance=ctx.target)
    if payload.get("confirm") is not True:
        raise errors.not_confirmed("device.apply.submit")
    if plan.get("kind") != "device.apply":
        raise errors.plan_invalid("计划类型不是 device.apply")
    manifest, files = _verified_manifest_and_files(ctx, plan)
    fields = [("manifest", plans.canonical_json(manifest).encode("utf-8"), None, None)]
    for entry in files:
        try:
            with open(entry["path"], "rb") as handle:
                content = handle.read(entry["bytes"] + 1)
        except OSError:
            raise errors.invalid_input("源文件无法读取，拒绝登记",
                                       details={"kind": entry["kind"]}) from None
        if len(content) != entry["bytes"]:
            raise errors.precondition_failed("源文件字节数在复核后变化，拒绝登记",
                                             {"kind": entry["kind"]})
        fields.append((entry["kind"], content, os.path.basename(entry["path"]), None))
    body, content_type = _multipart(fields)
    try:
        created = ctx.client.post(APPLY_TASKS_PATH, data=body, content_type=content_type)
    except http_client.HTTPStatusError as exc:
        if exc.status in (400, 413):
            raise errors.invalid_input(
                "服务端拒绝设备结果包（manifest/段字节/上限不符），未登记",
                details={"status": exc.status, "body": exc.body},
            ) from None
        if exc.status == 403 and isinstance(exc.body, dict) \
                and exc.body.get("code") == "FORBIDDEN_SCOPE":
            raise errors.forbidden_scope("device.apply.submit", str(exc.body)[:500],
                                         {"status": exc.status}) from None
        raise
    if not isinstance(created, dict) or not created.get("id"):
        raise errors.readback_mismatch("设备结果包创建响应缺少任务 id")
    task_id = str(created["id"])
    try:
        readback = ctx.client.get(APPLY_TASKS_PATH + "/" + parse.quote(task_id, safe=""))
    except http_client.HTTPStatusError as exc:
        if exc.status == 404:
            raise errors.readback_mismatch("登记后回读任务 404", {"taskId": task_id}) from None
        raise
    if not isinstance(readback, dict):
        raise errors.readback_mismatch("设备结果包详情响应不是对象")
    expected_segments = {(item["kind"], item["bytes"], item["sha256"])
                         for item in manifest["segments"]}
    actual_segments = {(item.get("kind"), item.get("bytes"), item.get("sha256"))
                       for item in readback.get("segments") or [] if isinstance(item, dict)}
    if expected_segments != actual_segments:
        raise errors.readback_mismatch("结果包段清单回读与 manifest 不一致", {
            "expected": sorted(expected_segments), "actual": sorted(actual_segments),
        })
    if readback.get("status") != "pending":
        raise errors.readback_mismatch("新登记结果包状态不是 pending",
                                       {"status": readback.get("status")})
    return {
        "status": "applied",
        "tool": "device.apply.submit",
        "taskId": task_id,
        "planFingerprint": plan["planFingerprint"],
        "idempotencyKey": plan["idempotencyKey"],
        "task": readback,
        "steps": [
            {"step": 1, "action": "verify-plan-and-rehash-sources", "status": "completed"},
            {"step": 2, "action": "POST /api/v1/device/apply-tasks", "status": "completed"},
            {"step": 3, "action": "readback", "status": "completed"},
        ],
        "readback": {
            "status": readback.get("status"),
            "segments": sorted(expected_segments),
            "expiresAt": readback.get("expiresAt"),
        },
        "summary": "设备结果包已登记并回读；需手机领取/应用后另行核对结果。",
    }


def device_apply_status(ctx: ToolContext, payload: dict) -> dict:
    task_id = payload.get("taskId")
    if task_id is not None:
        if not isinstance(task_id, str) or not task_id or any(ord(ch) < 32 for ch in task_id):
            raise errors.invalid_input("taskId 非法")
        return {"task": ctx.client.get(APPLY_TASKS_PATH + "/" + parse.quote(task_id, safe=""))}
    target_device_id = _device_id(payload.get("targetDevice"))
    status = payload.get("status")
    limit = payload.get("limit")
    if limit is not None and (not isinstance(limit, int) or isinstance(limit, bool)
                              or not 1 <= limit <= 100):
        raise errors.invalid_input("limit 必须在 1..100")
    query = {"targetDevice": target_device_id, "status": status,
             "limit": limit, "cursor": payload.get("cursor")}
    data = ctx.client.get(APPLY_TASKS_PATH, query)
    if not isinstance(data, dict) or not isinstance(data.get("items"), list):
        raise errors.internal_error("设备结果包列表响应不是对象")
    return {"tasks": data.get("items"), "nextCursor": data.get("nextCursor"),
            "targetDeviceId": target_device_id,
            "note": "列表只含序号/状态/段数摘要；段字节需手机侧按 kind 下载并核对 SHA-256。"}
