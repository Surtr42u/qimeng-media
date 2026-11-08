"""资产工具：asset.authors.prepare/apply、asset.trash、asset.tags.replace。

复用 PUT /assets/{assetId}/authors（整体替换 authorIds ∪ newAuthorNames）：
- prepare 只读：读取资产与作者索引、校验库 capabilities.authorAttach、计算差异；
- apply 重新读取资产并把当前作者集合摘要与计划前置比对，变化即冲突；
- 只解除业务关联（作品行会随移除删除），媒体文件保留；COS 作者不参与。

asset.trash = DELETE /assets/{assetId}：删除一律进回收站，需确认 + 回读 404。
asset.tags.replace = PUT /assets/{assetId}/tags：先读详情生成标签计划，确认后
整体替换并回读标签集合（整体替换会刷新全部关联时间，逐条解除另有端点）。
"""
from __future__ import annotations

import re
from urllib import parse

from . import client as http_client
from . import errors, plans, validation
from .runtime import ToolContext

MAX_NEW_AUTHOR_NAMES = 20

# 服务端规范身份按「两个以上空白」或「 / 」拆分别名，取首个别名决定身份；
# 回读匹配用同一保守口径（只做大小写与首别名归一），不把空格差异当同一人。
_ALIAS_SEPARATOR = re.compile(r"(?:\s{2,}|\s/\s)")


def _display_key(value) -> str:
    if not isinstance(value, str):
        return ""
    text = value.strip()
    if not text:
        return ""
    first_alias = _ALIAS_SEPARATOR.split(text)[0]
    return first_alias.strip().casefold()


def _verify_author_readback(*, explicit_ids, new_names, readback_authors, pre_index) -> dict:
    """按作者名解析后的回读核对。

    显式 authorIds 返回的 id 必须齐全；因 newAuthorNames 由服务端解析成新 id
    （新建）或复用既有作者（显示名可能与输入不同），额外 id 只有在「显示名按
    保守口径命中某个 newAuthorNames」或「PUT 前已存在该常规作者（按名复用）」
    时才视为预期；其余任意额外 id 一律判失败，避免静默放行同名/错名关联。
    """
    readback_ids = sorted({item["id"] for item in readback_authors})
    explicit = set(explicit_ids)
    missing = sorted(explicit - set(readback_ids))
    extras = [author_id for author_id in readback_ids if author_id not in explicit]
    name_keys = {_display_key(name) for name in new_names if _display_key(name)}
    by_id = {item["id"]: item for item in readback_authors}
    display_matched = [author_id for author_id in extras
                       if _display_key(by_id.get(author_id, {}).get("displayName")) in name_keys]
    pre_regular_ids = {author_id for author_id, item in pre_index.items()
                       if isinstance(item, dict) and item.get("type") == "regular"}
    reused = [author_id for author_id in extras if author_id in pre_regular_ids]
    unexpected = sorted(set(extras) - set(display_matched) - set(reused))
    # 请求了按名新建/复用却一个额外作者都没出现：服务端未解析任何名字，判失败。
    unresolved = list(new_names) if new_names and not extras else []
    return {
        "readbackIds": readback_ids,
        "missing": missing,
        "unexpected": unexpected,
        "unresolved": unresolved,
    }


def _asset_path(asset_id: str) -> str:
    return "/api/v1/assets/" + parse.quote(asset_id, safe="")


def _asset_id(payload: dict) -> str:
    value = payload.get("assetId")
    if not isinstance(value, str) or not value:
        raise errors.invalid_input("缺少 assetId")
    if len(value) > 512 or any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input("assetId 非法")
    return value


def _read_asset(ctx: ToolContext, asset_id: str) -> dict:
    asset = ctx.client.get(_asset_path(asset_id))
    if not isinstance(asset, dict):
        raise errors.internal_error("资产详情响应不是对象")
    return asset


def _authors_index(ctx: ToolContext) -> dict[str, dict]:
    data = ctx.client.get("/api/v1/authors")
    if not isinstance(data, list):
        raise errors.internal_error("作者列表响应不是数组")
    return {item["id"]: item for item in data
            if isinstance(item, dict) and isinstance(item.get("id"), str)}


def _library_map(ctx: ToolContext) -> dict[str, dict]:
    data = ctx.client.get("/api/v1/libraries")
    if not isinstance(data, list):
        raise errors.internal_error("库列表响应不是数组")
    return {item["id"]: item for item in data
            if isinstance(item, dict) and isinstance(item.get("id"), str)}


def _current_author_ids(asset: dict) -> list[str]:
    authors = asset.get("authors")
    if not isinstance(authors, list):
        return []
    ids = [item["id"] for item in authors
           if isinstance(item, dict) and isinstance(item.get("id"), str)]
    return sorted(set(ids))


def _asset_digest(asset: dict) -> str:
    return plans.digest({
        "id": asset.get("id"),
        "libraryId": asset.get("libraryId"),
        "fileName": asset.get("fileName"),
        "authors": _current_author_ids(asset),
    })


def _normalized_request(payload: dict, index: dict[str, dict]) -> tuple[list[str], list[str]]:
    author_ids = payload.get("authorIds")
    if not isinstance(author_ids, list):
        raise errors.invalid_input("authorIds 必须是数组（可为空数组=解除全部关联）")
    ids: list[str] = []
    for position, author_id in enumerate(author_ids):
        if not isinstance(author_id, str) or not author_id:
            raise errors.invalid_input(f"authorIds[{position}] 必须是非空字符串")
        if author_id not in index:
            raise errors.precondition_failed("作者不存在", {"authorId": author_id})
        if index[author_id].get("type") != "regular":
            raise errors.invalid_input("COS 作者不参与资产作者关联端点",
                                       {"authorId": author_id})
        if author_id in ids:
            raise errors.invalid_input("authorIds 不可重复")
        ids.append(author_id)
    names = payload.get("newAuthorNames")
    clean_names: list[str] = []
    if names is not None:
        if not isinstance(names, list):
            raise errors.invalid_input("newAuthorNames 必须是数组")
        if len(names) > MAX_NEW_AUTHOR_NAMES:
            raise errors.invalid_input(f"newAuthorNames 最多 {MAX_NEW_AUTHOR_NAMES} 项")
        for position, name in enumerate(names):
            problems = validation.validate_author_name(name)
            if problems:
                raise errors.invalid_input(f"newAuthorNames[{position}] 非法", details=problems)
            clean_names.append(name)
    return ids, clean_names


def asset_authors_prepare(ctx: ToolContext, payload: dict) -> dict:
    asset_id = _asset_id(payload)
    asset = _read_asset(ctx, asset_id)
    index = _authors_index(ctx)
    author_ids, new_names = _normalized_request(payload, index)
    library_id = asset.get("libraryId")
    library = _library_map(ctx).get(library_id)
    if library is None:
        raise errors.precondition_failed("资产所属库不存在", {"libraryId": library_id})
    capabilities = library.get("capabilities") if isinstance(library.get("capabilities"), dict) else {}
    if capabilities.get("authorAttach") is not True:
        raise errors.capability_unavailable("asset.authors.prepare", [{
            "path": "GET /api/v1/libraries", "method": "GET", "status": "incompatible",
            "code": "business_capability_author_attach",
            "detail": "该库 capabilities.authorAttach=false，服务端拒绝作者挂靠",
        }])
    current_ids = _current_author_ids(asset)
    target_ids = sorted(set(author_ids))
    added = [author_id for author_id in target_ids if author_id not in current_ids]
    removed = [author_id for author_id in current_ids if author_id not in target_ids]
    changes = []
    if added:
        changes.append({"field": "authors.add", "oldValue": current_ids, "newValue": added})
    if removed:
        changes.append({"field": "authors.remove", "oldValue": current_ids, "newValue": removed})
    if new_names:
        changes.append({"field": "authors.newByNames", "oldValue": [], "newValue": new_names})
    plan = plans.make_plan(
        tool="asset.authors.apply",
        tool_version="1.0.0",
        kind="asset.authors",
        target={
            "instance": ctx.target,
            "library": {"id": library_id, "name": library.get("name")},
            "objects": [{"type": "asset", "id": asset_id,
                         "fileName": asset.get("fileName"),
                         "relPath": asset.get("relPath")}],
        },
        changes=changes,
        impact={
            "associationsAdded": len(added),
            "associationsRemoved": len(removed),
            "newAuthorNames": len(new_names),
            "worksRowsRemoved": len(removed),
            "mediaFilesPreserved": True,
        },
        evidence=[
            {"source": "api", "endpoint": "GET " + _asset_path(asset_id),
             "fileName": asset.get("fileName"), "authors": current_ids},
            {"source": "api", "endpoint": "GET /api/v1/authors"},
            {"source": "api", "endpoint": "GET /api/v1/libraries",
             "capabilities": {"authorAttach": capabilities.get("authorAttach")}},
        ],
        versions_info=plans.version_snapshot(ctx.rulepack_version, ctx.openapi_sha256),
        confirmation={
            "required": True,
            "risk": "medium",
            "reason": "整体替换资产作者关联；移除关联会删除作品行，媒体文件保留。",
        },
        recoverable_steps=[
            "prepare 不写入数据。",
            "apply 前重新读取资产作者集合，与计划前置摘要不一致即拒绝。",
            "误改可再次 PUT 原作者集合恢复关联（作品行由服务端按当前文件名重建）。",
        ],
        expected_readback=[
            "GET /assets/{assetId}.authors 的 id 集合等于 authorIds + 按名解析结果",
            "移除关联对应的作品行已删除",
            "媒体文件与资产本体未变化",
        ],
        extra={
            "preconditions": {
                "assetAuthorsDigest": _asset_digest(asset),
                "currentAuthorIds": current_ids,
                "libraryAuthorAttach": True,
            },
            "current": {"authors": asset.get("authors") or [],
                        "fileName": asset.get("fileName"),
                        "relPath": asset.get("relPath")},
            "notices": [
                "newAuthorNames 由服务端按规范身份解析：命中已有常规作者则复用，未命中才新建。",
                "移除关联会删除该资产在作者块中的作品行；仅解除业务关联，不删除媒体。",
            ],
        },
    )
    return {"plan": plan, "diagnostics": {
        "asset": {"id": asset_id, "fileName": asset.get("fileName"),
                  "libraryId": library_id, "libraryName": library.get("name")},
        "currentAuthorIds": current_ids,
        "targetAuthorIds": target_ids,
        "added": added,
        "removed": removed,
        "newAuthorNames": new_names,
    }}


def asset_authors_apply(ctx: ToolContext, payload: dict) -> dict:
    plan = plans.verify_plan(payload.get("plan"), tool_id="asset.authors.apply",
                             rulepack_version=ctx.rulepack_version, instance=ctx.target)
    if payload.get("confirm") is not True:
        raise errors.not_confirmed("asset.authors.apply")
    if plan.get("kind") != "asset.authors":
        raise errors.plan_invalid("计划类型不是 asset.authors")
    objects = (plan.get("target") or {}).get("objects") or []
    asset_id = _asset_id({"assetId": objects[0].get("id") if objects else None})
    preconditions = plan.get("preconditions") or {}
    asset = _read_asset(ctx, asset_id)
    if preconditions.get("assetAuthorsDigest") != _asset_digest(asset):
        raise errors.precondition_failed("资产作者关联在 prepare 后发生变化，拒绝覆盖", {
            "expectedDigest": preconditions.get("assetAuthorsDigest"),
            "currentAuthorIds": _current_author_ids(asset),
        })
    author_ids: list[str] = []
    new_names: list[str] = []
    for change in plan.get("changes") or []:
        if change.get("field") == "authors.add":
            author_ids.extend(change.get("newValue") or [])
        elif change.get("field") == "authors.remove":
            pass
        elif change.get("field") == "authors.newByNames":
            new_names.extend(change.get("newValue") or [])
    base_ids = list(preconditions.get("currentAuthorIds") or [])
    final_ids = sorted(set(base_ids) | set(author_ids))
    for change in plan.get("changes") or []:
        if change.get("field") == "authors.remove":
            final_ids = [author_id for author_id in final_ids
                         if author_id not in set(change.get("newValue") or [])]
    body = {"authorIds": final_ids}
    if new_names:
        body["newAuthorNames"] = new_names
    # PUT 前记录作者索引，用于区分「按名复用既有作者」与「意外多出的关联」。
    pre_index = _authors_index(ctx)
    response = ctx.client.put(_asset_path(asset_id) + "/authors", body)
    if not isinstance(response, dict):
        raise errors.readback_mismatch("PUT 资产作者响应不是对象")
    readback = _read_asset(ctx, asset_id)
    readback_authors = [item for item in readback.get("authors") or []
                        if isinstance(item, dict) and isinstance(item.get("id"), str)]
    checked = _verify_author_readback(
        explicit_ids=final_ids,
        new_names=new_names,
        readback_authors=readback_authors,
        pre_index=pre_index,
    )
    if checked["missing"] or checked["unexpected"] or checked["unresolved"]:
        raise errors.readback_mismatch("资产作者关联回读与计划不一致", {
            "missingAuthorIds": checked["missing"],
            "unexpectedAuthorIds": checked["unexpected"],
            "unresolvedNewAuthorNames": checked["unresolved"],
        })
    readback_ids = checked["readbackIds"]
    return {
        "status": "applied",
        "tool": "asset.authors.apply",
        "assetId": asset_id,
        "planFingerprint": plan["planFingerprint"],
        "idempotencyKey": plan["idempotencyKey"],
        "authorIds": readback_ids,
        "steps": [
            {"step": 1, "action": "query-current", "status": "completed"},
            {"step": 2, "action": "verify-plan-and-preconditions", "status": "completed"},
            {"step": 3, "action": "PUT /api/v1/assets/{assetId}/authors", "status": "completed"},
            {"step": 4, "action": "readback", "status": "completed"},
        ],
        "readback": {"authorIds": readback_ids,
                     "authors": readback.get("authors") or [],
                     "fileName": readback.get("fileName")},
        "summary": "资产作者关联已替换并回读；媒体文件未改动。",
    }


# --------------------------------------------------------------------------- #
# 回收站（删除一律进回收站）与标签整体替换
# --------------------------------------------------------------------------- #

MAX_TAG_IDS = 200


def _asset_tags_digest(asset: dict) -> str:
    tags = asset.get("tags") if isinstance(asset.get("tags"), list) else []
    ids = sorted({item["id"] for item in tags
                  if isinstance(item, dict) and isinstance(item.get("id"), str)})
    return plans.digest({"id": asset.get("id"), "fileName": asset.get("fileName"),
                         "tagIds": ids})


def _current_tag_ids(asset: dict) -> list[str]:
    tags = asset.get("tags") if isinstance(asset.get("tags"), list) else []
    ids = [item["id"] for item in tags
           if isinstance(item, dict) and isinstance(item.get("id"), str)]
    return sorted(set(ids))


def _tag_ids(payload: dict) -> list[str]:
    values = payload.get("tagIds")
    if not isinstance(values, list):
        raise errors.invalid_input("tagIds 必须是数组（空数组=清空标签）")
    if len(values) > MAX_TAG_IDS:
        raise errors.invalid_input(f"tagIds 最多 {MAX_TAG_IDS} 项")
    ids: list[str] = []
    for position, tag_id in enumerate(values):
        if not isinstance(tag_id, str) or not tag_id:
            raise errors.invalid_input(f"tagIds[{position}] 必须是非空字符串")
        if len(tag_id) > 512 or any(ord(ch) < 32 for ch in tag_id):
            raise errors.invalid_input(f"tagIds[{position}] 非法")
        if tag_id in ids:
            raise errors.invalid_input("tagIds 不可重复")
        ids.append(tag_id)
    return ids


def asset_trash(ctx: ToolContext, payload: dict) -> dict:
    """DELETE /assets/{assetId}：移入回收站（非物理删除），回读 404。"""
    asset_id = _asset_id(payload)
    if payload.get("confirm") is not True:
        raise errors.not_confirmed("asset.trash")
    try:
        asset = _read_asset(ctx, asset_id)
    except http_client.HTTPStatusError as exc:
        if exc.status == 404:
            raise errors.precondition_failed("资产不存在或已删除，未执行",
                                             {"assetId": asset_id}) from None
        raise
    try:
        ctx.client.delete(_asset_path(asset_id))
    except http_client.HTTPStatusError as exc:
        if exc.status == 404:
            raise errors.precondition_failed("删除时资产已不存在，未确认写入结果",
                                             {"assetId": asset_id}) from None
        if exc.status == 409:
            raise errors.conflict("删除被服务端拒绝（409），未确认写入结果",
                                  {"assetId": asset_id, "body": exc.body}) from None
        if exc.status == 403 and isinstance(exc.body, dict) \
                and exc.body.get("code") == "FORBIDDEN_SCOPE":
            raise errors.forbidden_scope("asset.trash", str(exc.body)[:500],
                                         {"status": exc.status}) from None
        raise
    exists = True
    try:
        _read_asset(ctx, asset_id)
    except http_client.HTTPStatusError as exc:
        if exc.status != 404:
            raise
        exists = False
    if exists:
        raise errors.readback_mismatch("删除后资产仍可读取（可能未实际移入回收站）",
                                       {"assetId": asset_id})
    return {
        "status": "applied",
        "tool": "asset.trash",
        "assetId": asset_id,
        "steps": [
            {"step": 1, "action": "query-current", "status": "completed"},
            {"step": 2, "action": "DELETE /api/v1/assets/{assetId}", "status": "completed"},
            {"step": 3, "action": "readback", "status": "completed"},
        ],
        "readback": {"assetGone": True},
        "removed": {"fileName": asset.get("fileName"),
                    "libraryId": asset.get("libraryId"),
                    "relPath": asset.get("relPath")},
        "summary": "资产已移入回收站（非物理删除）；永久删除需独立管理操作。",
    }


def _tags_plan(ctx: ToolContext, asset: dict, asset_id: str, tag_ids: list[str]) -> dict:
    current_ids = _current_tag_ids(asset)
    target_ids = sorted(set(tag_ids))
    added = [tag_id for tag_id in target_ids if tag_id not in current_ids]
    removed = [tag_id for tag_id in current_ids if tag_id not in target_ids]
    changes = []
    if added:
        changes.append({"field": "tags.add", "oldValue": current_ids, "newValue": added})
    if removed:
        changes.append({"field": "tags.remove", "oldValue": current_ids, "newValue": removed})
    return plans.make_plan(
        tool="asset.tags.replace",
        tool_version="1.0.0",
        kind="asset.tags",
        target={
            "instance": ctx.target,
            "objects": [{"type": "asset", "id": asset_id,
                         "fileName": asset.get("fileName"),
                         "relPath": asset.get("relPath")}],
        },
        changes=changes,
        impact={"tagsAdded": len(added), "tagsRemoved": len(removed),
                "mediaFilesTouched": 0, "associationTimesRefreshed": len(target_ids)},
        evidence=[{"source": "api", "endpoint": "GET " + _asset_path(asset_id),
                   "fileName": asset.get("fileName"), "tagIds": current_ids}],
        versions_info=plans.version_snapshot(ctx.rulepack_version, ctx.openapi_sha256),
        confirmation={
            "required": True,
            "risk": "low",
            "reason": "整体替换资产标签集合；会刷新全部标签关联时间，不修改媒体文件。",
        },
        recoverable_steps=[
            "prepare 不写入数据。",
            "replace 前重新读取资产标签，与计划前置摘要不一致即拒绝。",
            "误改可再次 PUT 原标签集合恢复；关联时间会再次刷新。",
        ],
        expected_readback=[
            "GET /assets/{assetId}.tags 的 id 集合等于 tagIds",
            "媒体文件与资产本体未变化",
        ],
        extra={
            "preconditions": {"assetTagsDigest": _asset_tags_digest(asset),
                              "currentTagIds": current_ids},
            "current": {"tags": asset.get("tags") or []},
            "notices": [
                "整体替换会刷新全部标签关联时间；只解除单条标签可用 DELETE /assets/{assetId}/tags/{tag}。",
                "标签池维度只从 GET /tags 读取；本工具不新建或删除标签。",
            ],
        },
    )


def asset_tags_replace(ctx: ToolContext, payload: dict) -> dict:
    """confirm=false 返回标签替换计划；confirm=true 校验指纹后 PUT 并回读。"""
    asset_id = _asset_id(payload)
    tag_ids = _tag_ids(payload)
    confirm = payload.get("confirm") is True
    asset = _read_asset(ctx, asset_id)
    plan = _tags_plan(ctx, asset, asset_id, tag_ids)
    if not confirm:
        return {"plan": plan, "diagnostics": {
            "mode": "dry-run",
            "currentTagIds": _current_tag_ids(asset),
            "targetTagIds": sorted(set(tag_ids)),
            "notices": [
                "确认时携带 confirm=true 与 planFingerprint 才会写入。",
                "整体替换会刷新全部标签关联时间。",
            ],
        }}
    if payload.get("planFingerprint") != plan["planFingerprint"]:
        raise errors.precondition_failed(
            "标签计划指纹不匹配：现状已变化，需要重新读取并确认", {
                "expectedPlanFingerprint": plan["planFingerprint"],
                "providedPlanFingerprint": payload.get("planFingerprint"),
            })
    fresh = _read_asset(ctx, asset_id)
    preconditions = plan.get("preconditions") or {}
    if preconditions.get("assetTagsDigest") != _asset_tags_digest(fresh):
        raise errors.precondition_failed("资产标签在计划生成后发生变化，拒绝覆盖", {
            "currentTagIds": _current_tag_ids(fresh),
        })
    try:
        response = ctx.client.put(_asset_path(asset_id) + "/tags",
                                  {"tagIds": sorted(set(tag_ids))})
    except http_client.HTTPStatusError as exc:
        if exc.status == 400:
            raise errors.precondition_failed(
                "标签替换被服务端拒绝（400 INVALID_PARAM），未确认写入结果",
                {"status": exc.status, "body": exc.body}) from None
        if exc.status == 403 and isinstance(exc.body, dict) \
                and exc.body.get("code") == "FORBIDDEN_SCOPE":
            raise errors.forbidden_scope("asset.tags.replace", str(exc.body)[:500],
                                         {"status": exc.status}) from None
        raise
    readback = _read_asset(ctx, asset_id)
    readback_ids = _current_tag_ids(readback)
    expected_ids = sorted(set(tag_ids))
    if readback_ids != expected_ids:
        raise errors.readback_mismatch("资产标签回读与计划不一致", {
            "expectedTagIds": expected_ids, "readbackTagIds": readback_ids,
        })
    return {
        "status": "applied",
        "tool": "asset.tags.replace",
        "assetId": asset_id,
        "planFingerprint": plan["planFingerprint"],
        "idempotencyKey": plan["idempotencyKey"],
        "result": response,
        "steps": [
            {"step": 1, "action": "query-current", "status": "completed"},
            {"step": 2, "action": "verify-plan-and-preconditions", "status": "completed"},
            {"step": 3, "action": "PUT /api/v1/assets/{assetId}/tags", "status": "completed"},
            {"step": 4, "action": "readback", "status": "completed"},
        ],
        "readback": {"tagIds": readback_ids, "tags": readback.get("tags") or [],
                     "fileName": readback.get("fileName")},
        "summary": "资产标签集合已整体替换并回读；媒体文件未改动。",
    }
