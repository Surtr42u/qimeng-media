"""Exact single-asset rename through the existing Go move API, without numbering."""
from pathlib import PurePosixPath

from . import errors, plans
from .tools_asset import _asset_id, _asset_path, _read_asset


def _filename(value):
    if not isinstance(value, str) or not value or value != value.strip(" .") \
            or any(ord(c) < 32 or ord(c) == 127 or c in '/\\:*?"<>|\ufffd' for c in value) \
            or len(value.encode("utf-8")) > 255:
        raise errors.invalid_input("newName 必须是完整文件名，不含路径或非法字符；保留首尾空格/点无法精确改名")
    return value


def _snapshot(asset):
    return {key: asset.get(key) for key in ("id", "libraryId", "relPath", "fileName", "sizeBytes")}


def _author_ids(asset):
    return sorted(a["id"] for a in asset.get("authors", []) if a.get("type") != "cos")


def asset_rename_prepare(ctx, payload):
    asset = _read_asset(ctx, _asset_id(payload))
    name = _filename(payload["newName"])
    if any(a.get("type") == "cos" for a in asset.get("authors", [])):
        raise errors.invalid_input("COS 文件名决定派生作者/作品编号，请使用媒体整理工作流并核对归属影响")
    directory = str(PurePosixPath(asset["relPath"]).parent)
    if directory == ".":
        directory = ""
    target_rel = directory + "/" + name if directory else name
    plan = plans.make_plan(
        tool="asset.rename.apply", tool_version="1.0.0", kind="asset.rename",
        target={"instance": ctx.target, "library": {"id": asset["libraryId"]},
                "objects": [{"assetId": asset["id"]}]},
        changes=[{"field": "fileName", "oldValue": asset["fileName"], "newValue": name}],
        impact={"affectedAssets": int(target_rel != asset["relPath"]),
                "preservesAssetId": True, "renumbers": False}, evidence=[],
        versions_info=plans.version_snapshot(ctx.rulepack_version, ctx.openapi_sha256),
        confirmation={"required": True, "reason": "只改此文件名，不编号、不覆盖目标"},
        recoverable_steps=["按原资产ID回读；目标已是指定名称时不再写入。"],
        expected_readback=["资产ID、库、精确文件名、大小和常规作者关联一致"],
        extra={"before": _snapshot(asset), "authorIds": _author_ids(asset),
               "targetDir": directory, "targetRelPath": target_rel},
    )
    return {"plan": plan}


def asset_rename_apply(ctx, payload):
    plan = plans.verify_plan(payload["plan"], tool_id="asset.rename.apply",
                             rulepack_version=ctx.rulepack_version, instance=ctx.target)
    before = plan["before"]
    asset_id = before["id"]
    current = _read_asset(ctx, asset_id)
    name = _filename(plan["changes"][0]["newValue"])
    expected = {**before, "fileName": name, "relPath": plan["targetRelPath"]}
    if _snapshot(current) == expected and _author_ids(current) == plan["authorIds"]:
        return {"status": "already_applied", "assetId": asset_id, "readback": expected}
    if _snapshot(current) != before or _author_ids(current) != plan["authorIds"]:
        raise errors.precondition_failed("资产路径/身份/作者在计划生成后变化，请重新 prepare",
                                         mutation_rejected=True)
    ctx.client.post(_asset_path(asset_id) + "/move",
                    json_body={"targetDir": plan["targetDir"], "newName": name})
    after = _read_asset(ctx, asset_id)
    if _snapshot(after) != expected or _author_ids(after) != plan["authorIds"]:
        raise errors.readback_mismatch("改名后身份/精确文件名/作者未匹配；按原资产ID核对，勿盲目重试",
                                       {"assetId": asset_id})
    return {"status": "applied", "assetId": asset_id,
            "planFingerprint": plan["planFingerprint"], "readback": expected}
