"""作者链工具：档案计划/受控保存、清理计划/执行、原子合并。

安全与领域约束（rules.json authorActions/fields、任务书 6.4）：
- 普通与 COS 作者隔离：删除/改名/改挂/合并只接受常规作者；COS 由目录体系管理。
- 档案写入先读 revision；人工已填字段默认保护，需显式 allowProtectedOverwrite；
  409 不静默覆盖，重新 prepare 后才可重试。
- 删除是「三清」（TXT 块、关联、作者行），媒体文件保留；合并只走专用原子端点
  POST /authors/merge，不用多次删除/改挂拼事务。
- 所有写入经正式 API（ctx.client），计划指纹/版本/前置摘要全部校验后才执行；
  失败保留已执行步骤记录并停止后续动作。
"""
from __future__ import annotations

from urllib import parse

from . import client as http_client
from . import errors, plans, validation
from .runtime import ToolContext

PROFILE_FIELDS = ("nickname", "biography", "avatarUrl", "homepageUrl", "evidenceUrls",
                  "status", "autoRefresh", "feedUrl", "trackUpdates")
X_PROFILE_FIELDS = ("url", "biography", "biographyZh")
CLEANUP_ACTIONS = ("delete", "rename", "merge")
MAX_CLEANUP_AUTHORS = 50
MAX_SCAN_AUTHORS = 500
MAX_EVIDENCE_ITEMS = 20


# --------------------------------------------------------------------------- #
# 通用读取与快照
# --------------------------------------------------------------------------- #

def _profile_path(author_id: str) -> str:
    return "/api/v1/authors/" + parse.quote(author_id, safe="") + "/profile"


def _sources_path(author_id: str) -> str:
    return "/api/v1/authors/" + parse.quote(author_id, safe="") + "/sources"


def _author_path(author_id: str) -> str:
    return "/api/v1/authors/" + parse.quote(author_id, safe="") + "/"


def _read_authors(client) -> list[dict]:
    data = client.get("/api/v1/authors")
    if not isinstance(data, list):
        raise errors.internal_error("作者列表响应不是数组")
    for author in data:
        if not isinstance(author, dict) or not isinstance(author.get("id"), str):
            raise errors.internal_error("作者列表包含非法条目")
    return data


def _authors_index(client) -> dict[str, dict]:
    return {author["id"]: author for author in _read_authors(client)}


def _author_or_fail(index: dict, author_id: str) -> dict:
    author = index.get(author_id)
    if author is None:
        raise errors.precondition_failed("作者不存在或已被删除", {"authorId": author_id})
    return author


def _author_id(payload: dict, key: str = "authorId") -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value:
        raise errors.invalid_input(f"缺少 {key}")
    if len(value) > 512 or any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input(f"{key} 非法")
    return value


def _author_snapshot(author: dict) -> dict:
    return {
        "id": author.get("id"),
        "displayName": author.get("displayName"),
        "type": author.get("type"),
        "fileCount": author.get("fileCount"),
        "followed": author.get("followed"),
    }


def _snapshot_digest(snapshots: list[dict]) -> str:
    return plans.digest(sorted(snapshots, key=lambda item: str(item.get("id"))))


def _normalize_profile(profile) -> dict:
    """把服务端档案归一为可比较/可提交的完整字段集合。"""
    if not isinstance(profile, dict):
        raise errors.internal_error("档案响应不是对象")
    evidence = profile.get("evidenceUrls")
    if not isinstance(evidence, list):
        evidence = []
    normalized = {
        "nickname": profile.get("nickname") or "",
        "biography": profile.get("biography") or "",
        "avatarUrl": profile.get("avatarUrl") or "",
        "homepageUrl": profile.get("homepageUrl") or "",
        "evidenceUrls": [item for item in evidence if isinstance(item, str)],
        "status": profile.get("status") or "empty",
        "autoRefresh": bool(profile.get("autoRefresh")),
        "feedUrl": profile.get("feedUrl") or "",
        "trackUpdates": bool(profile.get("trackUpdates")),
    }
    x_profile = profile.get("xProfile")
    if isinstance(x_profile, dict):
        normalized["xProfile"] = {
            "url": x_profile.get("url") or "",
            "biography": x_profile.get("biography") or "",
            "biographyZh": x_profile.get("biographyZh") if x_profile.get("biographyZh") is not None else None,
        }
    else:
        normalized["xProfile"] = None
    revision = profile.get("revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 0:
        raise errors.internal_error("档案 revision 非法")
    normalized["revision"] = revision
    return normalized


def _profile_digest(normalized: dict) -> str:
    body = {field: normalized.get(field) for field in PROFILE_FIELDS}
    body["xProfile"] = normalized.get("xProfile")
    return plans.digest(body)


def _flat_view(normalized: dict) -> dict:
    """展平为比较用视图；xProfile 缺失时按空对象处理。"""
    view = {field: normalized.get(field) for field in PROFILE_FIELDS}
    x_profile = normalized.get("xProfile") or {"url": "", "biography": "", "biographyZh": None}
    for field in X_PROFILE_FIELDS:
        view["xProfile." + field] = x_profile.get(field)
    return view


def _manual_value(field: str, value) -> bool:
    """判断旧值是否属于「人工已填/已确认」，默认不允许 AI 覆盖。"""
    if field == "status":
        return value == "verified"
    if isinstance(value, bool):
        return value is True
    if isinstance(value, str):
        return bool(value)
    if isinstance(value, list):
        return len(value) > 0
    return False


def _merge_profile(normalized: dict, changes: dict) -> tuple[dict, list[str]]:
    """把本次明确修改合并进当前档案，返回（目标档案，notice 列表）。"""
    target = {field: normalized.get(field) for field in PROFILE_FIELDS}
    target["evidenceUrls"] = list(normalized.get("evidenceUrls") or [])
    target["xProfile"] = normalized.get("xProfile")
    notices: list[str] = []
    for field in PROFILE_FIELDS:
        if field in changes:
            target[field] = changes[field]
    if "xProfile" in changes:
        patch = changes["xProfile"]
        base = normalized.get("xProfile") or {"url": "", "biography": "", "biographyZh": None}
        url = patch.get("url", base.get("url", ""))
        biography = patch.get("biography", base.get("biography", ""))
        if "biographyZh" in patch:
            biography_zh = patch["biographyZh"]
        elif url == base.get("url", "") and biography == base.get("biography", ""):
            biography_zh = base.get("biographyZh")
        else:
            biography_zh = ""
            notices.append("X 账号或原文已变化且未提供译文：显式清空 biographyZh，避免译文与原文错配。")
        target_x: dict = {"url": url, "biography": biography}
        if biography_zh is not None:
            target_x["biographyZh"] = biography_zh
        target["xProfile"] = target_x
    return target, notices


def _diff_profile(current: dict, target: dict) -> list[dict]:
    """逐字段（含 xProfile 子字段）比较，返回变更列表。"""
    old_view = _flat_view(current)
    new_view = _flat_view(target)
    changes = []
    for field in PROFILE_FIELDS + tuple("xProfile." + name for name in X_PROFILE_FIELDS):
        if old_view.get(field) != new_view.get(field):
            changes.append({
                "field": field,
                "oldValue": old_view.get(field),
                "newValue": new_view.get(field),
            })
    return changes


def _submission(normalized: dict) -> dict:
    """从归一档案构造完整 CreatorProfileWrite 载荷（去掉候选/更新等服务端字段）。"""
    body = {
        "revision": normalized["revision"],
        "nickname": normalized["nickname"],
        "biography": normalized["biography"],
        "avatarUrl": normalized["avatarUrl"],
        "homepageUrl": normalized["homepageUrl"],
        "evidenceUrls": list(normalized["evidenceUrls"]),
        "status": normalized["status"],
        "autoRefresh": bool(normalized["autoRefresh"]),
        "feedUrl": normalized["feedUrl"],
        "trackUpdates": bool(normalized["trackUpdates"]),
    }
    if isinstance(normalized.get("xProfile"), dict):
        body["xProfile"] = dict(normalized["xProfile"])
    return body


def _write_changes(normalized: dict, planned_changes: list[dict]) -> dict:
    """把计划中的变更写入归一档案（xProfile 子字段单独合并）。"""
    result = dict(normalized)
    result["evidenceUrls"] = list(normalized.get("evidenceUrls") or [])
    result["xProfile"] = normalized.get("xProfile")
    x_touched = False
    for change in planned_changes:
        field = change["field"]
        if field.startswith("xProfile."):
            x_touched = True
            continue
        result[field] = change["newValue"]
    if x_touched:
        base = normalized.get("xProfile")
        patch = {change["field"].split(".", 1)[1]: change["newValue"]
                 for change in planned_changes if change["field"].startswith("xProfile.")}
        merged, _notices = _merge_profile(normalized, {"xProfile": patch})
        result["xProfile"] = merged["xProfile"]
    return result


def _applied_profile(current: dict, planned_changes: list[dict]) -> dict:
    return _write_changes(current, planned_changes)


def _evidence_entries(payload: dict, entries: list[dict]) -> list:
    evidence = []
    raw = payload.get("evidence")
    if raw is not None:
        if not isinstance(raw, list):
            raise errors.invalid_input("evidence 必须是字符串数组")
        if len(raw) > MAX_EVIDENCE_ITEMS:
            raise errors.invalid_input(f"evidence 最多 {MAX_EVIDENCE_ITEMS} 条")
        for index, item in enumerate(raw):
            if not isinstance(item, str) or len(item) > 2000 or validation.has_control_characters(item):
                raise errors.invalid_input(f"evidence[{index}] 非法")
            evidence.append(item)
    return evidence + entries


def _empty_profile(normalized: dict) -> bool:
    if normalized.get("status") != "empty":
        return False
    if normalized.get("evidenceUrls") or normalized.get("xProfile"):
        return False
    for field in ("nickname", "biography", "avatarUrl", "homepageUrl", "feedUrl"):
        if normalized.get(field):
            return False
    return not normalized.get("autoRefresh") and not normalized.get("trackUpdates")


# --------------------------------------------------------------------------- #
# 档案 prepare / update
# --------------------------------------------------------------------------- #

def author_profile_prepare(ctx: ToolContext, payload: dict) -> dict:
    author_id = _author_id(payload)
    changes = payload.get("changes")
    if not isinstance(changes, dict) or not changes:
        raise errors.invalid_input("changes 必须是非空对象")
    problems = validation.validate_profile_changes(changes)
    if problems:
        raise errors.invalid_input("档案字段校验失败", details=problems)
    allow_protected = bool(payload.get("allowProtectedOverwrite"))
    index = _authors_index(ctx.client)
    author = _author_or_fail(index, author_id)
    profile = ctx.client.get(_profile_path(author_id))
    if isinstance(profile, dict) and profile.get("authorId") not in (None, author_id):
        raise errors.internal_error("档案 authorId 与请求不一致")
    current = _normalize_profile(profile)
    target, notices = _merge_profile(current, changes)
    merged_problems = validation.verify_profile_constraints(target)
    if merged_problems:
        raise errors.invalid_input("合并后档案不满足跨字段约束", details=merged_problems)

    raw_changes = _diff_profile(current, target)
    applied: list[dict] = []
    protected: list[dict] = []
    for change in raw_changes:
        if (not allow_protected
                and _manual_value(change["field"], change["oldValue"])
                and change["oldValue"] != change["newValue"]):
            protected.append(dict(change, reason="manual-field-protected"))
        else:
            applied.append(change)

    touched_fields = {change["field"].split(".", 1)[0] for change in applied}
    unchanged = _flat_view(current)
    for field in tuple(touched_fields):
        if field == "xProfile":
            for name in X_PROFILE_FIELDS:
                unchanged.pop("xProfile." + name, None)
        else:
            unchanged.pop(field, None)
    evidence = _evidence_entries(payload, [
        {"source": "api", "endpoint": "GET /api/v1/authors", "authorId": author_id,
         "displayName": author.get("displayName"), "type": author.get("type")},
        {"source": "api", "endpoint": "GET " + _profile_path(author_id),
         "revision": current["revision"], "status": current["status"]},
    ])
    plan = plans.make_plan(
        tool="author.profile.update",
        tool_version="1.0.0",
        kind="profile.update",
        target={
            "instance": ctx.target,
            "library": None,
            "objects": [{"type": "author", "id": author_id,
                         "displayName": author.get("displayName"),
                         "authorType": author.get("type")}],
        },
        changes=applied,
        impact={
            "changedFieldCount": len(applied),
            "protectedFieldCount": len(protected),
            "affectedAuthors": 1,
            "writes": bool(applied),
        },
        evidence=evidence,
        versions_info=plans.version_snapshot(ctx.rulepack_version, ctx.openapi_sha256),
        confirmation={
            "required": bool(applied),
            "risk": "medium",
            "reason": "完整 CreatorProfileWrite 提交；人工已填字段默认保护，409 不覆盖。",
        },
        recoverable_steps=[
            "prepare 不写入任何数据。",
            "update 前会重新读取 profile，校验 revision 与未计划字段摘要；不一致即拒绝。",
            "失败或 409 时重新 prepare，不盲目重试旧计划。",
            "误写可用保存前的服务端 revision 记录对照恢复；人工字段保护避免默认覆盖。",
        ],
        expected_readback=[
            "profile.revision = 计划 revision + 1",
            "计划 changes 的字段值等于 newValue",
            "计划未包含的字段保持 prepare 时摘要不变",
        ],
        extra={
            "preconditions": {
                "revision": current["revision"],
                "profileDigest": _profile_digest(current),
                "unchangedDigest": plans.digest(unchanged),
                "unchangedFields": sorted(unchanged),
            },
            "protectedFields": protected,
            "author": {"id": author_id, "displayName": author.get("displayName"),
                       "type": author.get("type")},
            "current": {field: _flat_view(current).get(field)
                        for field in PROFILE_FIELDS + tuple("xProfile." + name for name in X_PROFILE_FIELDS)},
            "notices": notices,
        },
    )
    return {"plan": plan, "diagnostics": {
        "author": {"id": author_id, "displayName": author.get("displayName"),
                   "type": author.get("type")},
        "revision": current["revision"],
        "changedFields": [change["field"] for change in applied],
        "protectedFields": [change["field"] for change in protected],
        "notices": notices,
        "limitations": [
            "服务端不区分「人工清空」与「从未填写」的空值；空值补填由调用方按证据判断。",
            "COS 作者档案可读，但字段写入仍受服务端校验与人工审核。",
        ],
    }}


def _replay_profile_applied(current: dict, plan: dict) -> bool:
    preconditions = plan.get("preconditions") if isinstance(plan.get("preconditions"), dict) else {}
    expected_revision = preconditions.get("revision")
    if current["revision"] != (expected_revision + 1 if isinstance(expected_revision, int) else None):
        return False
    current_view = _flat_view(current)
    for change in plan.get("changes") or []:
        if current_view.get(change.get("field")) != change.get("newValue"):
            return False
    unchanged = preconditions.get("unchangedDigest")
    if unchanged is not None:
        unchanged_fields = preconditions.get("unchangedFields") or []
        subset = {field: _flat_view(current).get(field) for field in unchanged_fields}
        if plans.digest(subset) != unchanged:
            return False
    return True


def author_profile_update(ctx: ToolContext, payload: dict) -> dict:
    plan = plans.verify_plan(payload.get("plan"), tool_id="author.profile.update",
                             rulepack_version=ctx.rulepack_version)
    if payload.get("confirm") is not True:
        raise errors.not_confirmed("author.profile.update")
    if plan.get("kind") != "profile.update":
        raise errors.plan_invalid("计划类型不是 profile.update")
    objects = (plan.get("target") or {}).get("objects") or []
    author_id = _author_id({"authorId": objects[0].get("id") if objects else None})
    preconditions = plan.get("preconditions") or {}
    index = _authors_index(ctx.client)
    author = _author_or_fail(index, author_id)
    if author.get("type") != (plan.get("author") or {}).get("type"):
        raise errors.precondition_failed("作者类型与计划不一致（COS/常规身份可能已变化）")
    current = _normalize_profile(ctx.client.get(_profile_path(author_id)))
    if current["revision"] != preconditions.get("revision"):
        if _replay_profile_applied(current, plan):
            return {
                "status": "already_applied",
                "tool": "author.profile.update",
                "authorId": author_id,
                "planFingerprint": plan["planFingerprint"],
                "idempotencyKey": plan["idempotencyKey"],
                "revision": current["revision"],
                "summary": "检测到计划已应用（revision 与字段均匹配），本次未写入。",
                "steps": [{"step": 1, "action": "query-current", "status": "completed"},
                          {"step": 2, "action": "detect-replay", "status": "completed"}],
            }
        raise errors.conflict("档案 revision 已变化，计划已过期", {
            "expectedRevision": preconditions.get("revision"),
            "currentRevision": current["revision"],
        })
    unchanged_fields = preconditions.get("unchangedFields") or []
    unchanged_digest = preconditions.get("unchangedDigest")
    current_view = _flat_view(current)
    if unchanged_digest is not None:
        subset = {field: current_view.get(field) for field in unchanged_fields}
        if plans.digest(subset) != unchanged_digest:
            raise errors.precondition_failed("未计划字段在 prepare 后发生变化，拒绝覆盖", {
                "unchangedFields": unchanged_fields,
            })
    target = _write_changes(current, plan.get("changes") or [])
    problems = validation.verify_profile_constraints(target)
    if problems:
        raise errors.precondition_failed("当前状态下计划不再满足跨字段约束", details=problems)
    submission = _submission(target)
    try:
        saved = ctx.client.put(_profile_path(author_id), submission)
    except http_client.HTTPStatusError as exc:
        if exc.status == 409:
            raise errors.conflict("档案 revision 冲突（409），服务端未写入，计划已过期", {
                "status": exc.status, "body": exc.body}) from None
        raise
    steps = [
        {"step": 1, "action": "query-current", "status": "completed",
         "revision": current["revision"]},
        {"step": 2, "action": "verify-plan", "status": "completed",
         "planFingerprint": plan["planFingerprint"]},
        {"step": 3, "action": "PUT /api/v1/authors/{authorId}/profile", "status": "completed",
         "sentRevision": submission["revision"]},
    ]
    if not isinstance(saved, dict):
        raise errors.readback_mismatch("PUT 档案响应不是对象")
    readback = _normalize_profile(ctx.client.get(_profile_path(author_id)))
    if saved.get("authorId") not in (None, author_id):
        raise errors.readback_mismatch("PUT 响应 authorId 与请求不一致")
    if readback["revision"] != current["revision"] + 1:
        raise errors.readback_mismatch("回读 revision 不等于预期值", {
            "expected": current["revision"] + 1, "actual": readback["revision"]})
    readback_view = _flat_view(readback)
    mismatched = [change["field"] for change in plan.get("changes") or []
                  if readback_view.get(change.get("field")) != change.get("newValue")]
    if mismatched:
        raise errors.readback_mismatch("回读字段与计划不一致：" + ", ".join(mismatched), {
            "fields": mismatched})
    for field in unchanged_fields:
        if readback_view.get(field) != current_view.get(field):
            raise errors.readback_mismatch("未计划字段被意外修改：" + str(field))
    steps.append({"step": 4, "action": "readback", "status": "completed",
                  "revision": readback["revision"]})
    return {
        "status": "applied",
        "tool": "author.profile.update",
        "authorId": author_id,
        "planFingerprint": plan["planFingerprint"],
        "idempotencyKey": plan["idempotencyKey"],
        "revision": readback["revision"],
        "changedFields": [change["field"] for change in plan.get("changes") or []],
        "steps": steps,
        "readback": {"revision": readback["revision"], "status": readback["status"]},
        "summary": "档案已保存并回读一致；未计划字段保持不变。",
    }


# --------------------------------------------------------------------------- #
# 清理 prepare / apply 与原子合并
# --------------------------------------------------------------------------- #

def _sources_list(client, author_id: str, author_type: str):
    """读取作者块来源区；COS 作者没有该端点（400），返回 None。"""
    if author_type != "regular":
        return None
    try:
        data = client.get(_sources_path(author_id))
    except http_client.HTTPStatusError as exc:
        if exc.status in (400, 404):
            return None
        raise
    if not isinstance(data, dict):
        raise errors.internal_error("作者来源区响应不是对象")
    sources = data.get("sources")
    if not isinstance(sources, list):
        return []
    return [item for item in sources if isinstance(item, str)]


def _inventory_entry(client, author: dict) -> dict:
    profile = _normalize_profile(client.get(_profile_path(author["id"])))
    sources = _sources_list(client, author["id"], author.get("type"))
    signals = {
        "zeroAssociations": author.get("fileCount") == 0,
        "emptyProfile": _empty_profile(profile),
        "noSources": sources is not None and len(sources) == 0,
        "notInTxt": author.get("txtOrder") is None,
    }
    signal_count = sum(1 for value in signals.values() if value)
    if author.get("type") != "regular":
        suggested = "not_supported"
    elif all(signals.values()):
        suggested = "delete_candidate"
    elif signal_count >= 2:
        suggested = "inspect"
    else:
        suggested = "keep"
    return {
        "author": _author_snapshot(author),
        "profile": {
            "revision": profile["revision"],
            "status": profile["status"],
            "empty": _empty_profile(profile),
            "hasXProfile": isinstance(profile.get("xProfile"), dict),
        },
        "sources": sources,
        "signals": signals,
        "signalCount": signal_count,
        "suggestedAction": suggested,
        "impact": {
            "associations": author.get("fileCount"),
            "profile": "present" if not _empty_profile(profile) else "empty",
            "sources": "unknown" if sources is None else len(sources),
            "txtBlock": "present" if author.get("txtOrder") is not None else "unknown",
        },
    }


def _rollup_actions(actions: list[dict]) -> dict:
    return {
        "actionCount": len(actions),
        "deleteAuthors": sum(1 for action in actions if action["action"] == "delete"),
        "renameAuthors": sum(1 for action in actions if action["action"] == "rename"),
        "mergePairs": sum(1 for action in actions if action["action"] == "merge"),
        "irreversibleActions": sum(1 for action in actions if action["action"] in ("delete", "merge")),
    }


def _validate_actions(actions, index: dict[str, dict]) -> list[dict]:
    if not isinstance(actions, list) or not actions:
        raise errors.invalid_input("actions 必须是非空数组")
    if len(actions) > MAX_CLEANUP_AUTHORS:
        raise errors.invalid_input(f"actions 最多 {MAX_CLEANUP_AUTHORS} 项")
    normalized: list[dict] = []
    seen_authors: set[str] = set()
    for position, raw in enumerate(actions):
        if not isinstance(raw, dict):
            raise errors.invalid_input(f"actions[{position}] 必须是对象")
        action = raw.get("action")
        if action not in CLEANUP_ACTIONS:
            raise errors.invalid_input(f"actions[{position}].action 必须是 delete/rename/merge")
        if action in ("delete", "rename"):
            author_id = _author_id(raw, "authorId")
            author = index.get(author_id)
            if author is None:
                raise errors.precondition_failed("清理目标作者不存在",
                                                 {"authorId": author_id, "position": position})
            if author.get("type") != "regular":
                raise errors.invalid_input("只有常规作者可由清理工具处理；COS 作者由目录体系管理",
                                           {"authorId": author_id, "type": author.get("type")})
            if author_id in seen_authors:
                raise errors.invalid_input("同一作者在 actions 中重复出现")
            seen_authors.add(author_id)
            entry = {"action": action, "authorId": author_id}
            if action == "rename":
                display_name = raw.get("displayName")
                name_problems = validation.validate_author_name(display_name)
                if name_problems:
                    raise errors.invalid_input("改名目标非法", details=name_problems)
                entry["displayName"] = display_name
            normalized.append(entry)
        else:
            source_id = _author_id(raw, "sourceAuthorId")
            target_id = _author_id(raw, "targetAuthorId")
            if source_id == target_id:
                raise errors.invalid_input("合并的 sourceAuthorId 与 targetAuthorId 不能相同")
            for author_id in (source_id, target_id):
                author = index.get(author_id)
                if author is None:
                    raise errors.precondition_failed("合并目标作者不存在", {"authorId": author_id})
                if author.get("type") != "regular":
                    raise errors.invalid_input("合并只接受常规作者；COS 作者由目录体系管理",
                                               {"authorId": author_id})
            if source_id in seen_authors or target_id in seen_authors:
                raise errors.invalid_input("同一作者在 actions 中重复出现")
            seen_authors.update((source_id, target_id))
            normalized.append({"action": "merge", "sourceAuthorId": source_id,
                               "targetAuthorId": target_id})
    return normalized


def _source_profile_discarded(source: dict, target: dict) -> bool:
    return not source["profile"]["empty"] and not target["profile"]["empty"]


def _merge_profile_disposition(source: dict, target: dict) -> str:
    if target["profile"]["empty"] and not source["profile"]["empty"]:
        return "source-profile-moves-to-target"
    if not source["profile"]["empty"]:
        return "source-profile-discarded"
    return "no-profile-change"


def _cleanup_plan_change(action: dict, inventory: dict) -> dict:
    if action["action"] == "delete":
        item = inventory[action["authorId"]]
        return {
            "action": "delete",
            "authorId": action["authorId"],
            "oldValue": {"author": item["author"], "profile": item["profile"],
                         "sources": item["sources"]},
            "newValue": {"author": "absent", "associations": "removed",
                         "txtBlock": "removed", "mediaFiles": "preserved"},
            "impact": item["impact"],
        }
    if action["action"] == "rename":
        item = inventory[action["authorId"]]
        return {
            "action": "rename",
            "authorId": action["authorId"],
            "oldValue": {"displayName": item["author"]["displayName"]},
            "newValue": {"displayName": action["displayName"]},
            "impact": {"identityMigration": True, "associations": item["author"]["fileCount"]},
        }
    source = inventory[action["sourceAuthorId"]]
    target = inventory[action["targetAuthorId"]]
    return {
        "action": "merge",
        "sourceAuthorId": action["sourceAuthorId"],
        "targetAuthorId": action["targetAuthorId"],
        "oldValue": {"source": source["author"], "target": target["author"]},
        "newValue": {"source": "absent", "target": "target retains identity",
                     "sourceProfileDisposition": _merge_profile_disposition(source, target)},
        "impact": {
            "sourceAssets": source["author"]["fileCount"],
            "targetAssets": target["author"]["fileCount"],
            "sourceProfileDiscarded": _source_profile_discarded(source, target),
        },
    }


def author_cleanup_prepare(ctx: ToolContext, payload: dict) -> dict:
    index = _authors_index(ctx.client)
    actions = payload.get("actions")
    if actions is not None:
        normalized = _validate_actions(actions, index)
        involved = []
        for action in normalized:
            if action["action"] == "merge":
                involved.extend((action["sourceAuthorId"], action["targetAuthorId"]))
            else:
                involved.append(action["authorId"])
        involved_ids = list(dict.fromkeys(involved))
        inventory = {author_id: _inventory_entry(ctx.client, index[author_id])
                     for author_id in involved_ids}
        snapshots = [_author_snapshot(index[author_id]) for author_id in involved_ids]
        changes = [_cleanup_plan_change(action, inventory) for action in normalized]
        impact = _rollup_actions(normalized)
        impact["associationsAffected"] = sum(
            (change.get("impact") or {}).get("associations") or 0 for change in changes)
        evidence = _evidence_entries(payload, [
            {"source": "api", "endpoint": "GET /api/v1/authors"},
            {"source": "api", "endpoint": "GET /api/v1/authors/{authorId}/profile"},
            {"source": "api", "endpoint": "GET /api/v1/authors/{authorId}/sources"},
        ])
        plan = plans.make_plan(
            tool="author.cleanup.apply",
            tool_version="1.0.0",
            kind="author.cleanup",
            target={"instance": ctx.target, "library": None, "objects": [
                {"type": "author", "id": author_id} for author_id in involved_ids]},
            changes=changes,
            impact=impact,
            evidence=evidence,
            versions_info=plans.version_snapshot(ctx.rulepack_version, ctx.openapi_sha256),
            confirmation={
                "required": True,
                "risk": "high",
                "reason": "删除/合并不可撤销；执行前必须核对影响数量与身份。",
            },
            recoverable_steps=[
                "prepare 不写入任何数据。",
                "删除/合并没有撤销端点；执行前导出 v2 备份并记录本计划的快照。",
                "改名撞其他身份返回 409，本工具不做自动合并。",
                "apply 遇到失败立即停止后续动作并保留已执行步骤记录。",
            ],
            expected_readback=[
                "delete：作者不存在、关联数为 0、媒体文件保留",
                "rename：回读 id=迁移后身份且 displayName 正确",
                "merge：源身份不存在、目标身份保留、无孤立关联",
            ],
            extra={
                "requestedActions": normalized,
                "preconditions": {
                    "authorsDigest": _snapshot_digest(snapshots),
                    "involvedAuthorIds": sorted({snapshot["id"] for snapshot in snapshots}),
                    "authors": {snapshot["id"]: snapshot for snapshot in snapshots},
                },
                "inventory": inventory,
                "notices": [
                    "零关联、无来源、无档案都只是候选信号，不能单条件删除；actions 必须由调用方显式给出。",
                    "TXT 块数量没有只读 API 可直接计数；txtOrder 只表示该身份出现在片段中。",
                    "删除只解除业务关联，媒体文件与资产本体保留。",
                ],
            },
        )
        return {
            "plan": plan,
            "candidates": [
                {"author": inventory[author_id]["author"],
                 "signals": inventory[author_id]["signals"],
                 "suggestedAction": inventory[author_id]["suggestedAction"]}
                for author_id in involved_ids
            ],
            "diagnostics": {"mode": "explicit-actions", "inventory": inventory},
        }

    author_ids = payload.get("authorIds")
    scan = payload.get("scan")
    if author_ids is not None:
        if not isinstance(author_ids, list) or not author_ids:
            raise errors.invalid_input("authorIds 必须是非空数组")
        if len(author_ids) > MAX_CLEANUP_AUTHORS:
            raise errors.invalid_input(f"authorIds 最多 {MAX_CLEANUP_AUTHORS} 项")
        candidates = []
        for author_id in dict.fromkeys(author_ids):
            if not isinstance(author_id, str):
                raise errors.invalid_input("authorIds 项必须是字符串")
            author = _author_or_fail(index, _author_id({"authorId": author_id}))
            candidates.append(_inventory_entry(ctx.client, author))
        return {"plan": None, "candidates": candidates, "diagnostics": {
            "mode": "inspect-author-ids",
            "notices": ["本结果只读；要执行清理，请把候选整理为 actions 后重新调用 prepare 生成计划。"],
        }}

    max_authors = 20
    if isinstance(scan, dict):
        max_authors = scan.get("maxAuthors", 20)
    elif scan is not None:
        raise errors.invalid_input("scan 必须是对象")
    if not isinstance(max_authors, int) or isinstance(max_authors, bool) or not 1 <= max_authors <= MAX_SCAN_AUTHORS:
        raise errors.invalid_input(f"scan.maxAuthors 必须在 1..{MAX_SCAN_AUTHORS}")
    regular = [author for author in index.values() if author.get("type") == "regular"]
    regular.sort(key=lambda author: (author.get("fileCount") or 0,
                                     str(author.get("displayName") or "")))
    candidates = [_inventory_entry(ctx.client, author) for author in regular[:max_authors]]
    return {"plan": None, "candidates": candidates, "diagnostics": {
        "mode": "scan",
        "scanned": len(candidates),
        "totalRegularAuthors": len(regular),
        "notices": [
            "扫描按关联数升序取前 N 名常规作者，不构成删除建议。",
            "零关联、无来源、无档案都只是候选信号，不能单条件删除。",
            "没有预设的搁置阈值；候选证据与影响数量必须由用户/任务授权确认。",
            "要执行清理，请把候选整理为 actions 后重新调用 prepare 生成计划。",
        ],
    }}


def _cleanup_apply_one(ctx: ToolContext, action: dict) -> dict:
    """执行单个清理动作并回读；回读不一致抛 READBACK_MISMATCH。"""
    author_id = action["authorId"]
    if action["action"] == "delete":
        ctx.client.delete("/api/v1/authors/" + parse.quote(author_id, safe=""))
        index = _authors_index(ctx.client)
        if author_id in index:
            raise errors.readback_mismatch("删除后作者仍存在", {"authorId": author_id})
        return {"action": "delete", "authorId": author_id, "status": "applied",
                "readback": {"author": "absent", "associations": "removed", "media": "preserved"}}
    if action["action"] == "rename":
        response = ctx.client.put("/api/v1/authors/" + parse.quote(author_id, safe=""),
                                  {"displayName": action["displayName"]})
        if not isinstance(response, dict) or not isinstance(response.get("id"), str):
            raise errors.readback_mismatch("改名响应缺少 id", {"response": response})
        new_id = response["id"]
        index = _authors_index(ctx.client)
        author = index.get(new_id)
        if author is None or author.get("displayName") != action["displayName"]:
            raise errors.readback_mismatch("改名回读不一致", {"expectedId": new_id})
        if new_id != author_id and author_id in index:
            raise errors.readback_mismatch("改名后旧身份仍存在", {"oldId": author_id})
        return {"action": "rename", "authorId": author_id, "newAuthorId": new_id,
                "status": "applied",
                "readback": {"id": new_id, "displayName": author.get("displayName")}}
    source_id = action["sourceAuthorId"]
    target_id = action["targetAuthorId"]
    response = ctx.client.post("/api/v1/authors/merge",
                               json_body={"sourceAuthorId": source_id, "targetAuthorId": target_id})
    if not isinstance(response, dict):
        raise errors.readback_mismatch("合并响应不是对象")
    index = _authors_index(ctx.client)
    if source_id in index:
        raise errors.readback_mismatch("合并后源身份仍存在", {"sourceAuthorId": source_id})
    if target_id not in index:
        raise errors.readback_mismatch("合并后目标身份不存在", {"targetAuthorId": target_id})
    return {"action": "merge", "sourceAuthorId": source_id, "targetAuthorId": target_id,
            "status": "applied", "counts": response,
            "readback": {"source": "absent", "target": "present"}}


def author_cleanup_apply(ctx: ToolContext, payload: dict) -> dict:
    plan = plans.verify_plan(payload.get("plan"), tool_id="author.cleanup.apply",
                             rulepack_version=ctx.rulepack_version)
    if payload.get("confirm") is not True:
        raise errors.not_confirmed("author.cleanup.apply")
    if plan.get("kind") != "author.cleanup":
        raise errors.plan_invalid("计划类型不是 author.cleanup")
    actions = plan.get("requestedActions")
    if not isinstance(actions, list) or not actions:
        raise errors.plan_invalid("计划缺少 requestedActions")
    preconditions = plan.get("preconditions") or {}
    index = _authors_index(ctx.client)
    current_snapshots = []
    for author_id in preconditions.get("involvedAuthorIds") or []:
        author = index.get(author_id)
        if author is None:
            raise errors.precondition_failed("计划涉及的作者已不存在，计划已过期",
                                            {"authorId": author_id})
        current_snapshots.append(_author_snapshot(author))
    current_digest = plans.digest(sorted(current_snapshots, key=lambda item: str(item.get("id"))))
    if current_digest != preconditions.get("authorsDigest"):
        raise errors.precondition_failed("作者现状与计划快照不一致（关联数/显示名/身份已变化）")
    executed: list[dict] = []
    total = len(actions)
    for position, action in enumerate(actions, start=1):
        try:
            step = _cleanup_apply_one(ctx, action)
        except http_client.HTTPStatusError as exc:
            executed.append({"action": action.get("action"), "status": "failed",
                             "httpStatus": exc.status, "error": exc.body,
                             "position": position, "remaining": total - position})
            readback_index = _authors_index(ctx.client)
            return {
                "status": "partial" if position > 1 else "failed",
                "tool": "author.cleanup.apply",
                "executedActions": executed,
                "actionCount": total,
                "readback": {"authors": [
                    {"id": author_id, "present": author_id in readback_index}
                    for author_id in preconditions.get("involvedAuthorIds") or []]},
                "summary": "服务端拒绝（HTTP 错误），已停止后续动作并保留已执行步骤；409 不得静默覆盖。",
            }
        step["position"] = position
        executed.append(step)
    final_index = _authors_index(ctx.client)
    return {
        "status": "applied",
        "tool": "author.cleanup.apply",
        "executedActions": executed,
        "actionCount": total,
        "readback": {"authors": [
            {"id": author_id, "present": author_id in final_index}
            for author_id in preconditions.get("involvedAuthorIds") or []]},
        "summary": "全部动作已执行并回读；删除/合并不可撤销，请用备份与回读记录核对。",
    }


# --------------------------------------------------------------------------- #
# 原子合并 author.merge
# --------------------------------------------------------------------------- #

def _merge_plan(ctx: ToolContext, source: dict, target: dict, source_profile: dict,
                target_profile: dict, source_sources, target_sources) -> dict:
    source_snapshot = _author_snapshot(source)
    target_snapshot = _author_snapshot(target)
    source_state = {
        "author": source_snapshot,
        "profile": {"revision": source_profile["revision"],
                    "status": source_profile["status"],
                    "empty": _empty_profile(source_profile),
                    "digest": _profile_digest(source_profile)},
        "sources": source_sources,
    }
    target_state = {
        "author": target_snapshot,
        "profile": {"revision": target_profile["revision"],
                    "status": target_profile["status"],
                    "empty": _empty_profile(target_profile),
                    "digest": _profile_digest(target_profile)},
        "sources": target_sources,
    }
    source_empty = _empty_profile(source_profile)
    target_empty = _empty_profile(target_profile)
    impact = {
        "sourceAssets": source.get("fileCount"),
        "targetAssets": target.get("fileCount"),
        "sourceProfileWillBeDiscarded": (not source_empty) and (not target_empty),
        "profileWillMove": source_empty is False and target_empty is True,
        "followsWillMerge": bool(source.get("followed")) and not bool(target.get("followed")),
        "serverCounts": "回读 AuthorMergeResult 的实际计数",
        "irreversible": True,
    }
    return plans.make_plan(
        tool="author.merge",
        tool_version="1.0.0",
        kind="author.merge",
        target={"instance": ctx.target, "library": None, "objects": [
            {"type": "author", "id": source["id"], "displayName": source.get("displayName"),
             "role": "source"},
            {"type": "author", "id": target["id"], "displayName": target.get("displayName"),
             "role": "target"},
        ]},
        changes=[{
            "field": "merge",
            "oldValue": {"source": source_snapshot, "target": target_snapshot},
            "newValue": {"source": "absent", "target": "target retains identity",
                         "sourceProfileDisposition":
                             "source-profile-discarded" if (not source_empty and not target_empty)
                             else "source-profile-moves-to-target" if (source_empty is False and target_empty)
                             else "no-profile-change"},
        }],
        impact=impact,
        evidence=[
            {"source": "api", "endpoint": "GET /api/v1/authors"},
            {"source": "api", "endpoint": "GET /api/v1/authors/{authorId}/profile"},
            {"source": "api", "endpoint": "GET /api/v1/authors/{authorId}/sources"},
            {"source": "api", "endpoint": "POST /api/v1/authors/merge", "atomic": True},
        ],
        versions_info=plans.version_snapshot(ctx.rulepack_version, ctx.openapi_sha256),
        confirmation={
            "required": True,
            "risk": "high",
            "reason": "合并即删除源身份、不可撤销、无源身份快照；必须人工确认保留/删除身份与影响。",
        },
        recoverable_steps=[
            "prepare 不写入数据；确认前先导出 v2 备份并保存本计划的 source/target 快照。",
            "POST /authors/merge 单事务执行，任一步失败整体回滚。",
            "没有撤销端点；源身份删除后只能靠备份恢复。",
            "重复调用（源已删）返回 404，不得据此重试创建身份。",
        ],
        expected_readback=[
            "GET /api/v1/authors 中源身份不存在、目标身份存在",
            "无孤立 asset_authors 关联",
            "AuthorMergeResult 计数与影响预览一致（差异如实报告）",
        ],
        extra={
            "preconditions": {
                "sourceDigest": plans.digest(source_state),
                "targetDigest": plans.digest(target_state),
                "sourceState": source_state,
                "targetState": target_state,
            },
            "notices": [
                "合并只走专用原子端点，禁止用多次删除/改挂拼事务。",
                "TXT 块/作品行/来源词的实际合并计数由服务端返回；本计划只做只读清点。",
            ],
        },
    )


def author_merge(ctx: ToolContext, payload: dict) -> dict:
    source_id = _author_id(payload, "sourceAuthorId")
    target_id = _author_id(payload, "targetAuthorId")
    if source_id == target_id:
        raise errors.invalid_input("sourceAuthorId 与 targetAuthorId 不能相同")
    confirm = payload.get("confirm") is True
    index = _authors_index(ctx.client)
    source = _author_or_fail(index, source_id)
    target = _author_or_fail(index, target_id)
    for author in (source, target):
        if author.get("type") != "regular":
            raise errors.invalid_input("合并只接受常规作者；COS 作者由目录体系管理",
                                       {"authorId": author.get("id")})
    source_profile = _normalize_profile(ctx.client.get(_profile_path(source_id)))
    target_profile = _normalize_profile(ctx.client.get(_profile_path(target_id)))
    source_sources = _sources_list(ctx.client, source_id, source.get("type"))
    target_sources = _sources_list(ctx.client, target_id, target.get("type"))
    plan = _merge_plan(ctx, source, target, source_profile, target_profile,
                       source_sources, target_sources)
    if not confirm:
        return {"plan": plan, "diagnostics": {
            "mode": "dry-run",
            "impact": plan["impact"],
            "notices": [
                "确认时携带 confirm=true 与 planFingerprint 才会写入。",
                "合并不可撤销；建议先导出 v2 备份。",
            ],
        }}
    if payload.get("planFingerprint") != plan["planFingerprint"]:
        raise errors.precondition_failed(
            "合并计划指纹不匹配：现状已变化，需要重新读取并确认", {
                "expectedPlanFingerprint": plan["planFingerprint"],
                "providedPlanFingerprint": payload.get("planFingerprint"),
            })
    # 重新读取并核对前置状态，防止 plan 生成后身份/关联变化。
    fresh_index = _authors_index(ctx.client)
    fresh_source = _author_or_fail(fresh_index, source_id)
    fresh_target = _author_or_fail(fresh_index, target_id)
    fresh_source_profile = _normalize_profile(ctx.client.get(_profile_path(source_id)))
    fresh_target_profile = _normalize_profile(ctx.client.get(_profile_path(target_id)))
    fresh_source_state = {
        "author": _author_snapshot(fresh_source),
        "profile": {"revision": fresh_source_profile["revision"],
                    "status": fresh_source_profile["status"],
                    "empty": _empty_profile(fresh_source_profile),
                    "digest": _profile_digest(fresh_source_profile)},
        "sources": _sources_list(ctx.client, source_id, fresh_source.get("type")),
    }
    fresh_target_state = {
        "author": _author_snapshot(fresh_target),
        "profile": {"revision": fresh_target_profile["revision"],
                    "status": fresh_target_profile["status"],
                    "empty": _empty_profile(fresh_target_profile),
                    "digest": _profile_digest(fresh_target_profile)},
        "sources": _sources_list(ctx.client, target_id, fresh_target.get("type")),
    }
    preconditions = plan["preconditions"]
    if (plans.digest(fresh_source_state) != preconditions["sourceDigest"]
            or plans.digest(fresh_target_state) != preconditions["targetDigest"]):
        raise errors.precondition_failed("作者现状与计划快照不一致，需要重新 prepare")
    try:
        response = ctx.client.post("/api/v1/authors/merge",
                                   json_body={"sourceAuthorId": source_id,
                                              "targetAuthorId": target_id})
    except http_client.HTTPStatusError as exc:
        if exc.status == 409:
            raise errors.conflict("合并被服务端拒绝（409），未执行任何写入", {
                "status": exc.status, "body": exc.body}) from None
        raise
    if not isinstance(response, dict):
        raise errors.readback_mismatch("合并响应不是对象")
    index_after = _authors_index(ctx.client)
    if source_id in index_after:
        raise errors.readback_mismatch("合并后源身份仍存在", {"sourceAuthorId": source_id})
    if target_id not in index_after:
        raise errors.readback_mismatch("合并后目标身份不存在", {"targetAuthorId": target_id})
    return {
        "status": "applied",
        "tool": "author.merge",
        "sourceAuthorId": source_id,
        "targetAuthorId": target_id,
        "planFingerprint": plan["planFingerprint"],
        "idempotencyKey": plan["idempotencyKey"],
        "result": response,
        "steps": [
            {"step": 1, "action": "query-current", "status": "completed"},
            {"step": 2, "action": "verify-plan-and-preconditions", "status": "completed"},
            {"step": 3, "action": "POST /api/v1/authors/merge", "status": "completed"},
            {"step": 4, "action": "readback", "status": "completed"},
        ],
        "readback": {"source": "absent", "target": "present"},
        "summary": "合并已执行并回读；不可撤销，请保留备份与本次结果。",
        "warnings": ["sourceProfileDiscarded=true 时源档案随源身份删除，详情见 result。"],
    }
