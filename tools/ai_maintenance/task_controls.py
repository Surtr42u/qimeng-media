"""Trusted executor grants and session budgets; never infer authorization from confirm.

持久任务预算按 (taskId, apiBase, 凭据摘要) 隔离：同一 taskId 在不同实例或不同令牌下
是不同账本，避免跨凭据复用/核销未决写入。状态文件只保存最小操作定位信息（白名单
字段、有界），不保存 token、完整请求、路径或大计划。查询/回读核销/续期都只在可信
执行端使用；普通模型工具不暴露通用 budget.reset 或无限续期。
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import threading
import time
import tempfile
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from . import errors, pathguard, local_state, plans

STATE_FILE_MAX_BYTES = 8 * 1024 * 1024
IDENTITY_MAX_BYTES = 4096
EVIDENCE_MAX_BYTES = 8192
RECONCILE_HISTORY_LIMIT = 50
RENEW_HISTORY_LIMIT = 50
ABSOLUTE_MAX_CALLS = 10000
ABSOLUTE_MAX_SECONDS = 86400
NO_CREDENTIAL = "no-credential"

# 可作为“原操作身份”回读的稳定定位字段；证据至少需要全部覆盖这些字段。
LOCATOR_KEYS = ("uploadId", "assetId", "authorId", "jobId",
                "sourceAuthorId", "targetAuthorId")
PROTECTED_IDENTITY_KEYS = frozenset(LOCATOR_KEYS + ("libraryId",))
FORBIDDEN_EVIDENCE_KEYS = frozenset({"path", "fileref", "mediapath", "token",
                                     "leasetoken", "authorization", "credential"})
RECONCILE_VERDICTS = ("confirmed_applied", "confirmed_not_applied")


def _failure(code, message, *, details=None, exit_code=2):
    return errors.ToolError(code, message, category="permission" if code.startswith("AUTH") else "budget",
                            details=details, recovery="由可信执行端核对授权/任务预算；保留已有结果，不循环重试。",
                            exit_code=exit_code)


def _number(env, name, default, maximum):
    try:
        value = int(env.get(name, str(default)))
    except (TypeError, ValueError):
        raise errors.configuration_error(name + " 必须是整数") from None
    if not 1 <= value <= maximum:
        raise errors.configuration_error(name + f" 必须在 1..{maximum}")
    return value


def _iso_time(epoch) -> str:
    if not isinstance(epoch, (int, float)) or not math.isfinite(epoch):
        return ""
    return datetime.fromtimestamp(epoch, tz=timezone.utc).isoformat().replace("+00:00", "Z")


def _canonical(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _credential_digest(env) -> str:
    """凭据分量：只存摘要，绝不读写/记录 token 原文。"""
    token = env.get("QIMENG_API_TOKEN")
    if not token:
        return NO_CREDENTIAL
    return hashlib.sha256(str(token).encode("utf-8")).hexdigest()


def _sanitize_text(value, limit=512) -> str:
    if not isinstance(value, str):
        return ""
    # 控制字符不进入状态文件/管理输出；超长按字段上限截断。
    return "".join(ch for ch in value if ord(ch) >= 32)[:limit]


def _identity_scalar(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, str):
        text = _sanitize_text(value)
        return text if text else None
    return None


def _identity_values(value, *, limit=512, max_items=50):
    """只接受标量/字符串数组/由标量组成的浅对象；拒绝路径与嵌套大载荷。"""
    if isinstance(value, (bool, int)):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, str):
        return _sanitize_text(value, limit)
    if isinstance(value, list):
        items = []
        for item in value[:max_items]:
            if isinstance(item, str):
                items.append(_sanitize_text(item, limit))
            elif isinstance(item, int) and not isinstance(item, bool):
                items.append(item)
            elif isinstance(item, dict):
                entry = {}
                for key, child in list(item.items())[:10]:
                    key = _sanitize_text(key, 64)
                    if not key:
                        continue
                    scalar = _identity_scalar(child)
                    if scalar is not None:
                        entry[key] = scalar
                if entry:
                    items.append(entry)
        return items
    return None


def _identity_size(identity) -> int:
    return len(_canonical(identity).encode("utf-8"))


def _bounded_identity(identity) -> dict:
    """有界化 identity：超限时先丢可选字段，再缩短长值，并标记 truncated。"""
    if not isinstance(identity, dict):
        return {}
    clean = {}
    for key, value in identity.items():
        if not isinstance(key, str) or not key or len(key) > 64:
            continue
        bounded = _identity_values(value)
        if bounded is None:
            continue
        if bounded == "" and key not in ("directory",):
            continue
        if bounded == [] and key not in ("authorIds", "tagIds", "objectIds",
                                        "previousAuthorIds", "previousTagIds"):
            continue
        clean[key] = bounded
    truncated = False
    drop_order = ("changes", "fields", "newAuthorNames", "objectIds",
                  "previousAuthorIds", "previousTagIds", "authorIds",
                  "tagIds", "directory", "fileName", "step", "kind", "stepIndex",
                  "expectedRevision", "bytes", "sourceMtime", "artifactSha256",
                  "changesDigest", "planRefDigest")
    for key in drop_order:
        if key in clean and _identity_size(clean) > IDENTITY_MAX_BYTES:
            clean.pop(key, None)
            truncated = True
    if _identity_size(clean) > IDENTITY_MAX_BYTES:
        for key, value in list(clean.items()):
            if key in PROTECTED_IDENTITY_KEYS:
                continue
            if isinstance(value, str) and len(value) > 64:
                clean[key] = value[:64]
                truncated = True
    if _identity_size(clean) > IDENTITY_MAX_BYTES:
        # 最后只保留稳定定位字段；仍超限则视为损坏，绝不落盘超大 identity。
        clean = {key: value for key, value in clean.items()
                 if key in PROTECTED_IDENTITY_KEYS and isinstance(value, (str, int))}
        truncated = True
    if _identity_size(clean) > IDENTITY_MAX_BYTES:
        return {"truncated": True}
    if truncated:
        clean["truncated"] = True
    return clean


def _author_ids_from_plan(plan: dict):
    changes = plan.get("changes") if isinstance(plan.get("changes"), list) else []
    preconditions = plan.get("preconditions") if isinstance(plan.get("preconditions"), dict) else {}
    base = preconditions.get("currentAuthorIds")
    if not isinstance(base, list):
        return None
    final = {item for item in base if isinstance(item, str)}
    for change in changes:
        if not isinstance(change, dict):
            continue
        if change.get("field") == "authors.add":
            final.update(item for item in change.get("newValue") or [] if isinstance(item, str))
        elif change.get("field") == "authors.remove":
            final.difference_update(item for item in change.get("newValue") or [] if isinstance(item, str))
    return sorted(final)


def _identity_from_plan(tool: str, plan: dict) -> dict:
    identity = {}
    fingerprint = plan.get("planFingerprint")
    if isinstance(fingerprint, str):
        identity["planFingerprint"] = fingerprint
    target = plan.get("target") if isinstance(plan.get("target"), dict) else {}
    library = target.get("library") if isinstance(target.get("library"), dict) else {}
    if isinstance(library.get("id"), str):
        identity["libraryId"] = library["id"]
    objects = target.get("objects") if isinstance(target.get("objects"), list) else []
    object_ids = []
    for item in objects[:50]:
        if not isinstance(item, dict):
            continue
        object_id = item.get("id")
        if isinstance(item.get("assetId"), str):
            identity.setdefault("assetId", item["assetId"])
        if isinstance(object_id, str):
            object_ids.append(object_id)
            role = item.get("role")
            if item.get("type") == "author" and role in ("source", "target"):
                identity.setdefault("sourceAuthorId" if role == "source" else "targetAuthorId",
                                    object_id)
            elif item.get("type") == "author":
                identity.setdefault("authorId", object_id)
            elif item.get("type") == "asset":
                identity.setdefault("assetId", object_id)
    if object_ids:
        identity["objectIds"] = object_ids
    if isinstance(plan.get("operationId"), str):
        identity["uploadId"] = plan["operationId"]
    if isinstance(plan.get("sourceMtime"), int) and not isinstance(plan.get("sourceMtime"), bool):
        identity["sourceMtime"] = plan["sourceMtime"]
    preconditions = plan.get("preconditions") if isinstance(plan.get("preconditions"), dict) else {}
    if isinstance(preconditions.get("revision"), int) and not isinstance(preconditions.get("revision"), bool):
        identity["expectedRevision"] = preconditions["revision"]
    previous_authors = preconditions.get("currentAuthorIds")
    if isinstance(previous_authors, list):
        identity["previousAuthorIds"] = [item for item in previous_authors if isinstance(item, str)]
    previous_tags = preconditions.get("currentTagIds")
    if isinstance(previous_tags, list):
        identity["previousTagIds"] = [item for item in previous_tags if isinstance(item, str)]
    changes = plan.get("changes") if isinstance(plan.get("changes"), list) else []
    profiled = [change for change in changes
                if isinstance(change, dict) and isinstance(change.get("field"), str)]
    if profiled and all("field" in change for change in changes if isinstance(change, dict)):
        fields = sorted({_sanitize_text(change["field"], 64) for change in profiled})
        identity["fields"] = fields[:40]
        identity["changes"] = [
            {"field": _sanitize_text(change["field"], 64),
             "digest": plans.digest(change.get("newValue"))}
            for change in profiled[:40]
        ]
        for change in profiled:
            if change.get("field") == "fileName":
                if isinstance(change.get("oldValue"), str):
                    identity["oldName"] = change["oldValue"]
                if isinstance(change.get("newValue"), str):
                    identity["newName"] = change["newValue"]
    else:
        for change in changes[:10]:
            if not isinstance(change, dict):
                continue
            if isinstance(change.get("fileName"), str) and "fileName" not in identity:
                identity["fileName"] = change["fileName"]
            if isinstance(change.get("directory"), str):
                identity["directory"] = change["directory"]
            if isinstance(change.get("bytes"), int) and not isinstance(change.get("bytes"), bool):
                identity["bytes"] = change["bytes"]
    author_ids = _author_ids_from_plan(plan)
    if author_ids is not None:
        identity["authorIds"] = author_ids
    new_names = []
    for change in profiled:
        if change.get("field") == "authors.newByNames":
            new_names.extend(item for item in change.get("newValue") or [] if isinstance(item, str))
    if new_names:
        identity["newAuthorNames"] = new_names[:20]
    before = plan.get("before") if isinstance(plan.get("before"), dict) else {}
    if isinstance(before.get("id"), str):
        identity.setdefault("assetId", before["id"])
    if isinstance(before.get("fileName"), str):
        identity.setdefault("oldName", before["fileName"])
    return identity


def _identity_from_payload(tool: str, payload: dict) -> dict:
    identity = {}
    for key in ("assetId", "authorId", "sourceAuthorId", "targetAuthorId", "jobId",
                "libraryId", "operationId", "displayName", "newName", "planFingerprint"):
        if isinstance(payload.get(key), str):
            identity[key] = payload[key]
    if isinstance(payload.get("operationId"), str):
        identity.setdefault("uploadId", payload["operationId"])
    for key in ("tagIds", "authorIds"):
        values = payload.get(key)
        if isinstance(values, list):
            identity[key] = [item for item in values if isinstance(item, str)]
    if tool == "asset.upload.prepare":
        for key in ("fileName", "directory"):
            if isinstance(payload.get(key), str):
                identity[key] = payload[key]
        if isinstance(payload.get("sourceMtime"), int) and not isinstance(payload.get("sourceMtime"), bool):
            identity["sourceMtime"] = payload["sourceMtime"]
    if tool == "author.profile.prepare":
        changes = payload.get("changes")
        if isinstance(changes, dict):
            identity["fields"] = sorted(str(key) for key in changes)[:40]
            identity["changesDigest"] = plans.digest(changes)
    if tool == "maintenance.step.record":
        if isinstance(payload.get("step"), str):
            identity["step"] = payload["step"]
        if isinstance(payload.get("stepIndex"), int) and not isinstance(payload.get("stepIndex"), bool):
            identity["stepIndex"] = payload["stepIndex"]
        if isinstance(payload.get("status"), str):
            identity["stepStatus"] = payload["status"]
    if tool == "maintenance.artifact.record":
        if isinstance(payload.get("kind"), str):
            identity["kind"] = payload["kind"]
        if isinstance(payload.get("sha256"), str):
            identity["artifactSha256"] = payload["sha256"]
    if tool == "maintenance.complete" and isinstance(payload.get("status"), str):
        identity["terminalStatus"] = payload["status"]
    plan_ref = payload.get("planRef")
    if isinstance(plan_ref, str):
        # planRef 在 reserve 时尚未解析；只保存摘要，绝不保存引用原文。
        identity["planRefDigest"] = hashlib.sha256(plan_ref.encode("utf-8")).hexdigest()
    return identity


def _extract_identity(tool, payload) -> dict:
    """从 plan/payload 白名单提取最小定位信息；不含 path/fileRef/source/token。"""
    if not isinstance(tool, str) or not isinstance(payload, dict):
        return {}
    try:
        plan = payload.get("plan") if isinstance(payload.get("plan"), dict) else None
        identity = (_identity_from_plan(tool, plan) if plan is not None
                    else _identity_from_payload(tool, payload))
    except Exception:
        # reserve 早于 Schema 校验；异常载荷不能阻断预算入口，也不能落盘原载荷。
        return {}
    return _bounded_identity(identity)


def _forbidden_evidence_keys(value) -> list[str]:
    found = []
    if isinstance(value, dict):
        for key, child in value.items():
            lowered = str(key).lower()
            if lowered in FORBIDDEN_EVIDENCE_KEYS:
                found.append(lowered)
            found.extend(_forbidden_evidence_keys(child))
    elif isinstance(value, list):
        for child in value:
            found.extend(_forbidden_evidence_keys(child))
    return sorted(set(found))


def evidence_problems(identity, evidence, *, require_locators=True) -> list[dict]:
    """校验 evidence 只补足/核对 identity，不能覆盖、不能携带路径/凭据。

    reconcile 必须提供 identity 中全部稳定定位字段（require_locators=True）；
    只读回读可直接信任状态里的 identity，允许不重复传定位字段。
    """
    if not isinstance(evidence, dict):
        return [{"field": "evidence", "reason": "not_object"}]
    problems = []
    try:
        size = len(_canonical(evidence).encode("utf-8"))
    except (TypeError, ValueError):
        return [{"field": "evidence", "reason": "not_json"}]
    if size > EVIDENCE_MAX_BYTES:
        problems.append({"field": "evidence", "reason": "too_large"})
    forbidden = _forbidden_evidence_keys(evidence)
    if forbidden:
        problems.append({"field": "evidence", "reason": "forbidden_keys", "keys": forbidden})
    if isinstance(identity, dict):
        for key in sorted(set(identity) & set(evidence)):
            if key in ("truncated", "changes", "fields"):
                continue
            if _canonical(identity[key]) != _canonical(evidence[key]):
                problems.append({"field": key, "reason": "mismatch"})
        locators = [key for key in LOCATOR_KEYS
                    if isinstance(identity.get(key), str) and identity[key]]
        if require_locators and locators:
            missing = [key for key in locators if key not in evidence]
            if missing:
                problems.append({"field": "locator", "reason": "missing", "keys": missing})
    return problems


def _read_grant_file(path) -> dict:
    try:
        grant_path = Path(path)
        if grant_path.stat().st_size > 64 * 1024:
            raise ValueError()
        grant = json.loads(grant_path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        raise errors.configuration_error("无法读取有效的 AI 任务授权文件") from None
    if (not isinstance(grant, dict) or grant.get("version") != 1
            or set(grant) - {"version", "apiBase", "expiresAt", "allow"}):
        raise errors.configuration_error("AI 任务授权文件版本无效")
    return grant


def _grant_expiry(grant: dict) -> float:
    expiry = grant.get("expiresAt")
    if (isinstance(expiry, bool) or not isinstance(expiry, (int, float))
            or not math.isfinite(expiry) or expiry <= time.time()):
        raise _failure("AUTHORIZATION_EXPIRED", "任务授权已过期或缺少有效期")
    return float(expiry)


def _grant_instance_matches(grant: dict, env) -> None:
    if str(grant.get("apiBase", "")).rstrip("/") != str(env.get("QIMENG_API_BASE", "")).rstrip("/"):
        raise _failure("AUTHORIZATION_MISMATCH", "任务授权不属于当前服务实例")


def authorize(spec, payload, env):
    """An explicit permission is a standing delegation; an optional grant narrows it per task.

    The file is configured by the executor, never supplied through tool arguments. Existing
    Go scope, resource versions, local roots and plan validation remain mandatory.
    """
    path = env.get("QIMENG_AI_AUTHORIZATION_FILE")
    if not spec.side_effects or not path:
        return
    grant = _read_grant_file(path)
    _grant_expiry(grant)
    _grant_instance_matches(grant, env)
    entries = grant.get("allow")
    if not isinstance(entries, list):
        raise errors.configuration_error("AI 任务授权 allow 必须是数组")
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) - {"tool", "arguments"}:
            raise errors.configuration_error("AI 任务授权条目字段无效")
        if entry.get("tool") != spec.id:
            continue
        arguments = entry.get("arguments", {})
        if not isinstance(arguments, dict):
            raise errors.configuration_error("AI 任务授权 arguments 必须是对象")
        matches = True
        for path, expected in arguments.items():
            value = payload
            for part in path.split("."):
                if not isinstance(value, dict) or part not in value:
                    matches = False
                    break
                value = value[part]
            if not matches or value != expected:
                matches = False
                break
        if matches:
            return
    raise _failure("AUTHORIZATION_MISMATCH", "当前工具或参数超出任务授权范围",
                   details={"tool": spec.id})


def _validate_state(state):
    """校验持久状态；旧版 pending（只有 count/sideEffects）按 legacy 接受。"""
    if (not isinstance(state, dict) or type(state.get("calls")) is not int
            or state["calls"] < 0
            or not isinstance(state.get("observations"), dict)
            or type(state.get("startedAt")) not in (int, float)
            or not math.isfinite(state["startedAt"])):
        raise ValueError("invalid budget")
    for observation in state["observations"].values():
        if (not isinstance(observation, list) or len(observation) != 2
                or not isinstance(observation[0], str)
                or type(observation[1]) is not int or observation[1] < 0):
            raise ValueError("invalid observation")
    pending = state.get("pending", {})
    if not isinstance(pending, dict):
        raise ValueError("invalid pending state")
    for request_hash, entry in pending.items():
        if (not isinstance(entry, dict) or type(entry.get("count")) is not int
                or not 1 <= entry["count"] <= 10000
                or type(entry.get("sideEffects")) is not bool):
            raise ValueError("invalid pending state")
        if "requestHash" in entry and entry["requestHash"] != request_hash:
            raise ValueError("invalid pending requestHash")
        if "tool" in entry and not isinstance(entry["tool"], str):
            raise ValueError("invalid pending tool")
        if "createdAt" in entry and (type(entry["createdAt"]) not in (int, float)
                                     or not math.isfinite(entry["createdAt"])):
            raise ValueError("invalid pending createdAt")
        if "identity" in entry:
            identity = entry["identity"]
            if not isinstance(identity, dict) or _identity_size(identity) > IDENTITY_MAX_BYTES:
                raise ValueError("invalid pending identity")
            forbidden = {str(key).lower() for key in identity} & FORBIDDEN_EVIDENCE_KEYS
            if forbidden:
                raise ValueError("invalid pending identity keys")
    limits = state.get("limits", {})
    if not isinstance(limits, dict) or set(limits) - {"maxCalls", "maxSeconds"}:
        raise ValueError("invalid limits")
    for key, maximum in (("maxCalls", ABSOLUTE_MAX_CALLS), ("maxSeconds", ABSOLUTE_MAX_SECONDS)):
        if key in limits and (type(limits[key]) is not int or not 1 <= limits[key] <= maximum):
            raise ValueError("invalid limits")
    reconciled = state.get("reconciled", [])
    if not isinstance(reconciled, list) or len(reconciled) > RECONCILE_HISTORY_LIMIT:
        raise ValueError("invalid reconciled history")
    for entry in reconciled:
        if (not isinstance(entry, dict)
                or not isinstance(entry.get("requestHash"), str)
                or entry.get("verdict") not in RECONCILE_VERDICTS
                or not isinstance(entry.get("tool", ""), str)
                or type(entry.get("at")) not in (int, float)
                or not math.isfinite(entry["at"])
                or not isinstance(entry.get("evidenceDigest", ""), str)):
            raise ValueError("invalid reconciled entry")
    renewals = state.get("renewals", [])
    if not isinstance(renewals, list) or len(renewals) > RENEW_HISTORY_LIMIT:
        raise ValueError("invalid renewal history")
    for entry in renewals:
        if (not isinstance(entry, dict)
                or type(entry.get("at")) not in (int, float)
                or not math.isfinite(entry["at"])):
            raise ValueError("invalid renewal entry")


def _read_state_file(path: Path):
    if pathguard.is_linkish(path) or pathguard.is_linkish(path.parent):
        raise ValueError("state file is a link")
    if path.stat().st_size > STATE_FILE_MAX_BYTES:
        raise ValueError("budget too large")
    state = json.loads(path.read_text(encoding="utf-8"))
    _validate_state(state)
    return state


def _state_report(state, *, persisted, max_calls, max_seconds, elapsed=None,
                 task_scope=None) -> dict:
    limits = state.get("limits") if isinstance(state.get("limits"), dict) else {}
    calls_candidates = [int(v) for v in (max_calls, limits.get("maxCalls")) if v is not None]
    seconds_candidates = [int(v) for v in (max_seconds, limits.get("maxSeconds")) if v is not None]
    effective_calls = max(calls_candidates) if calls_candidates else None
    effective_seconds = max(seconds_candidates) if seconds_candidates else None
    started_at = state["startedAt"]
    pending = []
    for request_hash, entry in state.get("pending", {}).items():
        if not isinstance(entry, dict):
            continue
        identity = entry.get("identity") if isinstance(entry.get("identity"), dict) else None
        pending.append({
            "requestHash": request_hash,
            "tool": entry.get("tool") or "legacy",
            "count": entry["count"],
            "sideEffects": entry["sideEffects"],
            "createdAt": entry.get("createdAt"),
            "identity": identity,
        })
    pending.sort(key=lambda item: (item["createdAt"] is None, -(item["createdAt"] or 0)))
    now = time.time()
    deadline = started_at + effective_seconds if effective_seconds else None
    elapsed = max(0.0, now - started_at) if elapsed is None else elapsed
    scope_is_task = bool(persisted) if task_scope is None else bool(task_scope)
    return {
        "calls": state["calls"],
        "maxCalls": effective_calls,
        "maxSeconds": effective_seconds,
        "maxUnchanged": None,
        "startedAt": started_at,
        "startedAtIso": _iso_time(started_at),
        "deadline": deadline,
        "deadlineIso": _iso_time(deadline),
        "expiresAt": _iso_time(deadline),
        "elapsedSeconds": round(elapsed, 3),
        "pendingWrites": sum(entry["count"] for entry in state.get("pending", {}).values()
                             if isinstance(entry, dict) and entry.get("sideEffects")),
        "pending": pending,
        "scope": "task" if scope_is_task else "executor-session",
        "persisted": bool(persisted),
        "modelUsage": "host-not-reported",
    }


def inspect_budget_file(path) -> dict:
    """可信端列表用：只读校验单个预算文件并返回安全摘要，不回显文件名以外路径。"""
    path = Path(path)
    try:
        state = _read_state_file(path)
    except (OSError, ValueError, TypeError):
        raise _failure("TASK_BUDGET_UNAVAILABLE", "任务预算记录不可用，须先核对已执行结果") from None
    return _state_report(state, persisted=True, max_calls=None, max_seconds=None)


class SessionBudget:
    """Bound calls/time and repeated unchanged requests in one executor session.

    No model usage is guessed: actual model tokens/cost belong to the host. CLI recipe
    calls share RuntimeConfig; separate CLI processes are separate sessions, explicitly
    not a durable account-wide or Go worker budget. Persistent ledgers bind task,
    instance and credential digest; pending writes carry bounded original-operation
    locators for trusted readback/reconciliation.
    """
    def __init__(self, env):
        self.max_calls = _number(env, "QIMENG_AI_MAX_CALLS", 100, ABSOLUTE_MAX_CALLS)
        self.max_seconds = _number(env, "QIMENG_AI_MAX_SECONDS", 900, ABSOLUTE_MAX_SECONDS)
        self.max_repeats = _number(env, "QIMENG_AI_MAX_UNCHANGED", 3, 100)
        self.started = time.monotonic()
        self.state = {"startedAt": time.time(), "calls": 0, "observations": {}, "pending": {}}
        self.lock = threading.Lock()
        self.path = None
        self.api_base = str(env.get("QIMENG_API_BASE", ""))
        task_id = env.get("QIMENG_AI_TASK_ID")
        if task_id:
            if not env.get("QIMENG_AI_STATE_DIR"):
                raise errors.configuration_error("持久任务预算需要 QIMENG_AI_STATE_DIR")
            identity = hashlib.sha256(json.dumps(
                [task_id, self.api_base, _credential_digest(env)],
                sort_keys=True).encode()).hexdigest()
            self.path = Path(env["QIMENG_AI_STATE_DIR"]) / "budgets" / (identity + ".json")
            if self.path.parent.exists() and pathguard.is_linkish(self.path.parent):
                raise errors.configuration_error("任务预算目录不能是链接")

    def _read_state(self):
        """只读加载；不创建/重写状态，不重置任何计数。"""
        if self.path is None:
            return self.state, False
        if pathguard.is_linkish(self.path):
            raise _failure("TASK_BUDGET_UNAVAILABLE", "任务预算文件不能是链接，未执行操作")
        if not self.path.exists():
            return self.state, False
        try:
            return _read_state_file(self.path), True
        except (OSError, ValueError, TypeError):
            raise _failure("TASK_BUDGET_UNAVAILABLE", "任务预算记录不可用，须先核对已执行结果") from None

    @contextmanager
    def _state(self):
        with self.lock:
            if self.path is None:
                yield self.state
                return
            lock_path = self.path.with_suffix(".lock")
            if pathguard.is_linkish(self.path):
                raise _failure("TASK_BUDGET_UNAVAILABLE", "任务预算文件不能是链接，未执行操作")
            try:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                with local_state.lock_file(lock_path):
                    state, _persisted = self._read_state()
                    yield state
                    temporary_path = None
                    try:
                        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=self.path.parent,
                                                         prefix="budget-", delete=False) as temporary:
                            temporary_path = Path(temporary.name)
                            json.dump(state, temporary, separators=(",", ":"))
                            temporary.flush()
                            os.fsync(temporary.fileno())
                        os.replace(temporary_path, self.path)
                    finally:
                        if temporary_path is not None:
                            temporary_path.unlink(missing_ok=True)
            except (OSError, ValueError, TypeError, local_state.StateLockError):
                raise _failure("TASK_BUDGET_UNAVAILABLE", "任务预算记录不可用，须先核对已执行结果") from None

    def _effective_limits(self, state) -> tuple[int, int]:
        limits = state.get("limits") if isinstance(state.get("limits"), dict) else {}
        calls = max(self.max_calls, int(limits.get("maxCalls") or 0))
        seconds = max(self.max_seconds, int(limits.get("maxSeconds") or 0))
        return calls, seconds

    def reserve(self, tool, payload, *, side_effects=False):
        request = hashlib.sha256(json.dumps([tool, payload], ensure_ascii=False,
                                            sort_keys=True, default=lambda _: "<non-json>").encode()).hexdigest()
        identity = _extract_identity(tool, payload)
        with self._state() as state:
            elapsed = (time.time() - state["startedAt"]) if self.path else (time.monotonic() - self.started)
            max_calls, max_seconds = self._effective_limits(state)
            if state["calls"] >= max_calls or elapsed >= max_seconds:
                raise _failure("TASK_BUDGET_EXHAUSTED", "任务调用数或时间预算已耗尽",
                               details={"calls": state["calls"], "maxCalls": max_calls})
            _, unchanged = state["observations"].get(request, (None, 0))
            pending = state.setdefault("pending", {})
            if side_effects and any(entry["sideEffects"] for entry in pending.values()):
                raise _failure("TASK_OPERATION_PENDING", "任务存在未决写入；未执行新的写操作",
                               details={"requiresReadback": True})
            current = pending.get(request, {"count": 0, "sideEffects": side_effects})
            if unchanged + current["count"] >= self.max_repeats:
                raise _failure("TASK_NO_PROGRESS", "相同请求连续未产生新结果，已停止重复调用")
            state["calls"] += 1
            pending[request] = {
                "requestHash": request,
                "count": current["count"] + 1,
                "sideEffects": side_effects,
                "tool": current.get("tool") or (tool if isinstance(tool, str) else ""),
                "createdAt": current.get("createdAt") or time.time(),
                "identity": current.get("identity") or identity,
            }
        return request

    def complete(self, request, result):
        digest = hashlib.sha256(json.dumps(result, ensure_ascii=False, sort_keys=True,
                                           default=lambda _: "<non-json>").encode()).hexdigest()
        with self._state() as state:
            pending = state.setdefault("pending", {})
            entry = pending.get(request)
            if entry is None:
                raise _failure("TASK_BUDGET_UNAVAILABLE", "任务调用预留记录缺失，须回读已有操作")
            if entry["count"] == 1:
                del pending[request]
            else:
                entry["count"] -= 1
            previous, count = state["observations"].get(request, (None, 0))
            state["observations"][request] = (digest, count + 1 if digest == previous else 1)

    def fail(self, request, error, *, uncertain=False):
        if not uncertain:
            self.complete(request, error)
        # Reservation was persisted before execution. An interrupted/uncertain write
        # stays pending until the trusted executor reconciles its original operation.

    def report(self):
        state, persisted = self._read_state()
        elapsed = max(0.0, time.monotonic() - self.started) if not persisted else None
        report = _state_report(state, persisted=persisted, max_calls=self.max_calls,
                               max_seconds=self.max_seconds, elapsed=elapsed,
                               task_scope=self.path is not None)
        report["maxUnchanged"] = self.max_repeats
        return report

    def pending_entry(self, request_hash: str):
        """只读取待核销项（含最小 identity）；不存在返回 None。"""
        state, _persisted = self._read_state()
        entry = state.get("pending", {}).get(request_hash)
        if not isinstance(entry, dict):
            return None
        return {
            "requestHash": request_hash,
            "tool": entry.get("tool") or "",
            "count": entry["count"],
            "sideEffects": entry["sideEffects"],
            "createdAt": entry.get("createdAt"),
            "identity": entry.get("identity") if isinstance(entry.get("identity"), dict) else {},
        }

    def prior_reconciliation(self, request_hash: str):
        """只读查询已核销历史（有界、无原载荷）。"""
        state, _persisted = self._read_state()
        for entry in reversed(state.get("reconciled") or []):
            if entry.get("requestHash") == request_hash:
                return dict(entry)
        return None

    def reconcile(self, request_hash, verdict, evidence=None):
        """按 requestHash 精确核销一个未决预留；幂等、并发安全、不跨账本。"""
        if (not isinstance(request_hash, str) or len(request_hash) != 64
                or any(ch not in "0123456789abcdef" for ch in request_hash)):
            raise errors.invalid_input("requestHash 必须是 64 位小写 SHA-256")
        if verdict not in RECONCILE_VERDICTS:
            raise errors.invalid_input("verdict 仅接受 confirmed_applied / confirmed_not_applied")
        if evidence is None:
            evidence = {}
        problems = evidence_problems(None, evidence)
        if problems:
            raise errors.invalid_input("evidence 非法", details=problems)
        with self._state() as state:
            pending = state.setdefault("pending", {})
            entry = pending.get(request_hash)
            history = state.get("reconciled") if isinstance(state.get("reconciled"), list) else []
            if entry is None:
                for prior in reversed(history):
                    if prior.get("requestHash") == request_hash:
                        return {"status": "already_reconciled", "requestHash": request_hash,
                                "tool": prior.get("tool", ""), "verdict": prior.get("verdict"),
                                "pendingWrites": sum(item["count"] for item in pending.values()
                                                     if item.get("sideEffects"))}
                return {"status": "not_found", "requestHash": request_hash,
                        "pendingWrites": sum(item["count"] for item in pending.values()
                                             if item.get("sideEffects"))}
            identity = entry.get("identity") if isinstance(entry.get("identity"), dict) else {}
            problems = evidence_problems(identity, evidence)
            if problems:
                return {"status": "rejected", "reason": "evidence_mismatch",
                        "requestHash": request_hash, "problems": problems,
                        "pendingWrites": sum(item["count"] for item in pending.values()
                                             if item.get("sideEffects"))}
            if entry["count"] <= 1:
                del pending[request_hash]
            else:
                entry["count"] -= 1
            history.append({
                "requestHash": request_hash,
                "tool": entry.get("tool") or "",
                "verdict": verdict,
                "evidenceDigest": plans.digest(evidence),
                "at": time.time(),
            })
            if len(history) > RECONCILE_HISTORY_LIMIT:
                del history[:-RECONCILE_HISTORY_LIMIT]
            state["reconciled"] = history
            return {"status": "reconciled", "requestHash": request_hash,
                    "tool": entry.get("tool") or "", "verdict": verdict,
                    "pendingWrites": sum(item["count"] for item in pending.values()
                                         if item.get("sideEffects"))}

    def _renew_authorization_digest(self, authorization_file) -> str:
        grant = _read_grant_file(authorization_file)
        _grant_expiry(grant)
        _grant_instance_matches(grant, {"QIMENG_API_BASE": self.api_base})
        entries = grant.get("allow")
        if not isinstance(entries, list):
            raise errors.configuration_error("AI 任务授权 allow 必须是数组")
        allowed = False
        for entry in entries:
            if not isinstance(entry, dict) or set(entry) - {"tool", "arguments"}:
                raise errors.configuration_error("AI 任务授权条目字段无效")
            if entry.get("tool") != "budget.renew":
                continue
            if entry.get("arguments") not in (None, {}):
                raise errors.configuration_error("budget.renew 授权条目不能限定参数")
            allowed = True
        if not allowed:
            raise _failure("AUTHORIZATION_MISMATCH", "任务授权未包含 budget.renew", details={
                "tool": "budget.renew"})
        return hashlib.sha256(_canonical(grant).encode("utf-8")).hexdigest()

    def renew(self, max_calls=None, max_seconds=None, authorization_file=None, **unknown):
        """可信端续期：只允许提高上限，保留 calls/observations/pending/startedAt。"""
        if unknown:
            raise errors.invalid_input("续期不接受未知字段：" + ", ".join(sorted(unknown)))
        if max_calls is None and max_seconds is None:
            raise errors.invalid_input("续期必须至少提高 max_calls 或 max_seconds 之一")
        for name, value, maximum in (("max_calls", max_calls, ABSOLUTE_MAX_CALLS),
                                     ("max_seconds", max_seconds, ABSOLUTE_MAX_SECONDS)):
            if value is None:
                continue
            if (isinstance(value, bool) or not isinstance(value, int)
                    or not 1 <= value <= maximum):
                raise errors.invalid_input(f"{name} 必须是 1..{maximum} 的整数")
        authorization_digest = ""
        if authorization_file is not None:
            authorization_digest = self._renew_authorization_digest(authorization_file)
        with self._state() as state:
            limits = state.setdefault("limits", {})
            if not isinstance(limits, dict):
                raise _failure("TASK_BUDGET_UNAVAILABLE", "任务预算记录不可用，须先核对已执行结果")
            current_calls, current_seconds = self._effective_limits(state)
            if max_calls is not None and max_calls <= current_calls:
                raise errors.invalid_input(f"max_calls 只能提高（当前上限 {current_calls}）")
            if max_seconds is not None and max_seconds <= current_seconds:
                raise errors.invalid_input(f"max_seconds 只能提高（当前上限 {current_seconds}）")
            if max_calls is not None:
                limits["maxCalls"] = max_calls
            if max_seconds is not None:
                limits["maxSeconds"] = max_seconds
            renewals = state.setdefault("renewals", [])
            if not isinstance(renewals, list):
                raise _failure("TASK_BUDGET_UNAVAILABLE", "任务预算记录不可用，须先核对已执行结果")
            renewals.append({
                "at": time.time(),
                "maxCalls": max_calls,
                "maxSeconds": max_seconds,
                "authorizationDigest": authorization_digest,
            })
            if len(renewals) > RENEW_HISTORY_LIMIT:
                del renewals[:-RENEW_HISTORY_LIMIT]
            state["renewals"] = renewals
            return {"status": "renewed", "maxCalls": max(max_calls or 0, current_calls),
                    "maxSeconds": max(max_seconds or 0, current_seconds),
                    "calls": state["calls"],
                    "pendingWrites": sum(entry["count"] for entry in state.get("pending", {}).values()
                                         if isinstance(entry, dict) and entry.get("sideEffects")),
                    "startedAt": state["startedAt"]}
