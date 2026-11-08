"""回收站工具：trash.list（只读）与 trash.restore（需确认 + 回读）。

- 删除媒体只移入回收站；永久删除只走独立管理操作，本模块不提供。
- restore 先读取回收站确认条目仍存在（不存在即前置条件失败），恢复后重新
  读取回收站核对条目已消失；冲突自动改名由服务端处理，工具不猜测新文件名。
"""
from __future__ import annotations

from urllib import parse

from . import client as http_client
from . import errors
from .runtime import ToolContext

TRASH_PATH = "/api/v1/trash"


def _trash_id(payload: dict) -> str:
    value = payload.get("trashId")
    if not isinstance(value, str) or not value or len(value) > 512 \
            or any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input("trashId 非法")
    return value


def _trash_items(ctx: ToolContext) -> list:
    data = ctx.client.get(TRASH_PATH)
    if not isinstance(data, list):
        raise errors.internal_error("回收站列表响应不是数组")
    return data


def _find(items: list, trash_id: str):
    for item in items:
        if isinstance(item, dict) and item.get("id") == trash_id:
            return item
    return None


def trash_list(ctx: ToolContext, payload: dict) -> list:
    return _trash_items(ctx)


def trash_restore(ctx: ToolContext, payload: dict) -> dict:
    trash_id = _trash_id(payload)
    if payload.get("confirm") is not True:
        raise errors.not_confirmed("trash.restore")
    before = _trash_items(ctx)
    entry = _find(before, trash_id)
    if entry is None:
        raise errors.precondition_failed(
            "回收站条目不存在或已恢复，未执行恢复",
            {"reason": "trash_item_not_found", "trashId": trash_id},
        )
    path = TRASH_PATH + "/" + parse.quote(trash_id, safe="") + "/restore"
    try:
        response = ctx.client.post(path)
    except http_client.HTTPStatusError as exc:
        if exc.status == 404:
            raise errors.precondition_failed("恢复时回收站条目已不存在，未写入",
                                             {"trashId": trash_id}) from None
        if exc.status == 409:
            raise errors.conflict("恢复被服务端拒绝（409），未确认写入结果",
                                  {"trashId": trash_id, "body": exc.body}) from None
        if exc.status == 403 and isinstance(exc.body, dict) \
                and exc.body.get("code") == "FORBIDDEN_SCOPE":
            raise errors.forbidden_scope("trash.restore", str(exc.body)[:500],
                                         {"status": exc.status}) from None
        raise
    after = _trash_items(ctx)
    if _find(after, trash_id) is not None:
        raise errors.readback_mismatch("恢复后条目仍在回收站", {"trashId": trash_id})
    return {
        "status": "applied",
        "tool": "trash.restore",
        "trashId": trash_id,
        "restored": {
            "fileName": entry.get("fileName"),
            "sizeBytes": entry.get("sizeBytes"),
            "deletedAt": entry.get("deletedAt"),
        },
        "result": response,
        "steps": [
            {"step": 1, "action": "query-trash", "status": "completed"},
            {"step": 2, "action": "POST /api/v1/trash/{trashId}/restore", "status": "completed"},
            {"step": 3, "action": "readback", "status": "completed"},
        ],
        "readback": {"stillInTrash": False, "remaining": len(after)},
        "summary": "已从回收站恢复；同名冲突由服务端自动改名，实际文件名以媒体列表为准。",
    }
