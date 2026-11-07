"""资产作者关联工具：asset.authors.prepare / apply。

复用 PUT /assets/{assetId}/authors（整体替换 authorIds ∪ newAuthorNames）：
- prepare 只读：读取资产与作者索引、校验库 capabilities.authorAttach、计算差异；
- apply 重新读取资产并把当前作者集合摘要与计划前置比对，变化即冲突；
- 只解除业务关联（作品行会随移除删除），媒体文件保留；COS 作者不参与。
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
