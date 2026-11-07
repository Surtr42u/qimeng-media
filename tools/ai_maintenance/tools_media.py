"""media.organize.prepare / apply 适配层。

命名算法唯一实现在 media_naming.py（组图作品编号与页序号、分组、校验、锁与
回读报告）；本模块只做三件事，不复制命名规则：
1. 从正式 API 读取目标目录现状（有界翻页 GET /assets）；
2. 调用命名引擎生成/校验计划；
3. apply 按 plan_steps 逐步 POST /api/v1/assets/{assetId}/move，并回读文件名。

引擎缺失时本模块报告「未安装实现模块」，registry 会把工具标为 planned，
绝不假装执行。
"""
from __future__ import annotations

import tempfile
from urllib import parse

from . import client as http_client
from . import errors, plans
from .runtime import ToolContext

MAX_LIST_PAGES = 20
ASSET_PAGE_LIMIT = 200
LOCK_SUBDIR = "qimeng-ai-media-locks"


def engine_module():
    """返回 media_naming 模块；不存在时返回 None（不抛 ImportError）。"""
    try:
        from . import media_naming  # 局部导入：允许引擎晚于工具核心落地
        return media_naming
    except ImportError:
        return None


def engine_available() -> bool:
    return engine_module() is not None


def engine_version() -> str:
    module = engine_module()
    return getattr(module, "ENGINE_VERSION", "") if module is not None else ""


def _require_engine():
    module = engine_module()
    if module is None:
        raise errors.tool_not_implemented(
            "media.organize.prepare/apply",
            "缺少 tools/ai_maintenance/media_naming.py 命名引擎（由发布物安装后可用）")
    return module


def _library_id(payload: dict) -> str:
    value = payload.get("libraryId")
    if not isinstance(value, str) or not value:
        raise errors.invalid_input("缺少 libraryId")
    if len(value) > 512 or any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input("libraryId 非法")
    return value


def _directory(payload: dict) -> str:
    value = payload.get("directory", "")
    if not isinstance(value, str):
        raise errors.invalid_input("directory 必须是字符串")
    if ".." in value.split("/") or "\\" in value:
        raise errors.invalid_input("directory 不能越出库根")
    return value.strip("/")


def _list_library_files(ctx: ToolContext, library_id: str, directory: str) -> dict[str, str]:
    """有界翻页读取库内资产，返回 {fileName: assetId}（限定目标目录）。

    当前 GET /assets 没有 fileName/directory 过滤参数，adapter 只按 libraryId
    翻页并在本地按 relPath 目录前缀过滤；页数有界，超限明确失败而不是静默截断。
    """
    files: dict[str, str] = {}
    cursor = None
    for _page in range(MAX_LIST_PAGES):
        data = ctx.client.get("/api/v1/assets", {"libraryId": library_id,
                                                "limit": ASSET_PAGE_LIMIT,
                                                "cursor": cursor})
        if not isinstance(data, dict):
            raise errors.internal_error("资产列表响应不是对象")
        items = data.get("items")
        if not isinstance(items, list):
            raise errors.internal_error("资产列表 items 不是数组")
        for item in items:
            if not isinstance(item, dict):
                continue
            name = item.get("fileName")
            asset_id = item.get("id")
            if not isinstance(name, str) or not isinstance(asset_id, str):
                continue
            rel_path = item.get("relPath")
            parent = ""
            if isinstance(rel_path, str) and "/" in rel_path:
                parent = rel_path.rsplit("/", 1)[0]
            if directory:
                if parent != directory and not parent.startswith(directory + "/"):
                    continue
            elif parent:
                continue
            if name in files and files[name] != asset_id:
                raise errors.precondition_failed("目录内存在同名文件，无法安全规划命名",
                                                 {"fileName": name})
            files[name] = asset_id
        cursor = data.get("nextCursor")
        if not cursor:
            return files
    raise errors.invalid_input(
        f"目录资产超过 {MAX_LIST_PAGES * ASSET_PAGE_LIMIT} 个，超出命名规划上限；请缩小目录后再试")


def _naming_error(module, exc: Exception):
    """把引擎 NamingError 映射为工具错误码，保留 code 供自动化判断。"""
    code = getattr(exc, "code", "") or "engine_error"
    message = str(exc)
    details = {"code": code}
    if code == "lock_timeout":
        return errors.ToolError(
            "CONFLICT", message, category="conflict", exit_code=1,
            recovery=("另一批次可能正在对该库执行命名；稍后重试。锁目录不可写时可设 "
                      "QIMENG_AI_LOCK_DIR 指向可写目录；stale 锁需人工确认后清理，工具不自动删除。"),
            details=details)
    if code == "lock_stale":
        return errors.ToolError(
            "CONFLICT", message, category="conflict", exit_code=1,
            recovery="锁文件超过失效时限：人工确认没有进程持有后清理锁文件，再重新 prepare/apply。",
            details=details)
    mapping = {
        "invalid_request": errors.invalid_input,
        "duplicate_source": errors.invalid_input,
        "unknown_source": errors.invalid_input,
        "invalid_family": errors.invalid_input,
        "invalid_confidence": errors.invalid_input,
        "needs_confirmation": errors.not_confirmed,
        "stale_precondition": errors.precondition_failed,
        "plan_fingerprint_mismatch": errors.plan_fingerprint_mismatch,
        "target_conflict": errors.precondition_failed,
        "work_number_conflict": errors.precondition_failed,
        "work_number_gap": errors.precondition_failed,
        "readback_mismatch": errors.readback_mismatch,
    }
    factory = mapping.get(code)
    if factory is errors.plan_fingerprint_mismatch:
        return factory("plan", "plan")
    if factory is errors.not_confirmed:
        return factory("media.organize.apply")
    if factory is not None:
        return factory(message, details)
    return errors.internal_error("命名引擎错误：" + message, details)


def _engine_call(module, label: str, call):
    """调用引擎函数并把 NamingError 映射为工具错误；其他异常按内部缺陷处理。"""
    try:
        return call()
    except Exception as exc:  # noqa: BLE001
        if getattr(exc, "code", None):
            raise _naming_error(module, exc) from None
        raise errors.internal_error(label + "失败") from exc


def media_organize_prepare(ctx: ToolContext, payload: dict) -> dict:
    module = _require_engine()
    library_id = _library_id(payload)
    directory = _directory(payload)
    groups = payload.get("groups")
    if not isinstance(groups, list) or not groups:
        raise errors.invalid_input("groups 必须是非空数组")
    current_files = _list_library_files(ctx, library_id, directory)
    library = None
    libraries = ctx.client.get("/api/v1/libraries")
    if isinstance(libraries, list):
        for item in libraries:
            if isinstance(item, dict) and item.get("id") == library_id:
                library = item
                break
    plan = _engine_call(
        module, "命名引擎 prepare_plan",
        lambda: module.prepare_plan(
            groups,
            sorted(current_files),
            instance=ctx.target,
            library={"id": library_id, "name": (library or {}).get("name")},
            directory=directory,
        ))
    if not isinstance(plan, dict):
        raise errors.internal_error("命名引擎返回的计划不是对象")
    return {"plan": plan, "diagnostics": {
        "engineVersion": engine_version(),
        "libraryId": library_id,
        "directory": directory,
        "currentFileCount": len(current_files),
        "planStatus": plan.get("status"),
        "preview": plan.get("preview") or [],
        "notices": [
            "命名计划必须人工确认；needs_confirmation 时不得直接 apply。",
            "apply 会重新读取目录现状并核对前置指纹，变化即拒绝。",
            "服务端同名自动改名视为冲突终止，不得当作命名成功。",
        ],
    }}


def _resolve_lock_dir(ctx: ToolContext) -> str:
    env = ctx.env or {}
    override = env.get("QIMENG_AI_LOCK_DIR")
    base = override or tempfile.gettempdir()
    return base.rstrip("/\\") + "/" + LOCK_SUBDIR


def media_organize_apply(ctx: ToolContext, payload: dict) -> dict:
    module = _require_engine()
    plan = payload.get("plan")
    if not isinstance(plan, dict):
        raise errors.plan_invalid("plan 必须是对象")
    if payload.get("confirm") is not True:
        raise errors.not_confirmed("media.organize.apply")
    target = plan.get("target") if isinstance(plan.get("target"), dict) else {}
    library_value = target.get("library")
    if isinstance(library_value, dict):
        library_id = _library_id({"libraryId": library_value.get("id")})
    else:
        library_id = _library_id({"libraryId": library_value})
    directory = _directory({"directory": target.get("directory", "")})
    if not isinstance(plan.get("planFingerprint"), str) or not plan.get("planFingerprint"):
        raise errors.plan_invalid("plan 缺少 planFingerprint，必须由 prepare 生成")
    # 并发约束（任务书 §6.6 第 5 条）：同一目标实例+库的 apply 串行化，
    # 防止两批并发各自通过前置校验后分配出重复/跳号的作品编号。锁只保护
    # “锁内取现状→校验→逐步移动→回读”，不跨请求持锁；锁目录可由
    # QIMENG_AI_LOCK_DIR 覆盖（默认系统临时目录下的 qimeng-ai-media-locks）。
    lock_dir = _resolve_lock_dir(ctx)
    library_payload = {"id": library_id}
    if isinstance(library_value, dict) and isinstance(library_value.get("name"), str):
        library_payload["name"] = library_value["name"]
    locks = _engine_call(module, "命名引擎并发锁初始化",
                        lambda: module.SerializedLocks(lock_dir, ctx.target, library_payload))
    _engine_call(module, "命名引擎并发锁获取", locks.acquire)
    try:
        # 锁内重新取现状并校验（双检）：等待锁期间目录可能已被其他批次改变。
        current_files = _list_library_files(ctx, library_id, directory)
        validation = _engine_call(
            module, "命名引擎 validate_plan",
            lambda: module.validate_plan(plan, sorted(current_files), confirmed=True))
        if not isinstance(validation, dict):
            raise errors.internal_error("命名引擎 validate_plan 返回非法结构")
        if not validation.get("ok"):
            details = validation.get("errors") or []
            codes = {item.get("code") for item in details if isinstance(item, dict)}
            if "needs_confirmation" in codes:
                raise errors.not_confirmed("media.organize.apply")
            if "plan_fingerprint_mismatch" in codes:
                raise errors.ToolError(
                    "PLAN_FINGERPRINT_MISMATCH",
                    "命名计划指纹不一致：计划内容已被修改，必须重新 prepare",
                    category="plan", exit_code=1,
                    recovery="重新调用 media.organize.prepare 生成新计划；禁止修改计划后执行。",
                    details=details)
            if "stale_precondition" in codes:
                raise errors.precondition_failed(
                    "目标目录现状在 prepare 后已变化，必须重新规划", details=details)
            raise errors.precondition_failed("命名计划校验失败，必须重新规划", details=details)
        steps = _engine_call(module, "命名引擎 plan_steps", lambda: module.plan_steps(plan))
        executed: list[dict] = []
        total = len(steps)
        failure: tuple[str, str] | None = None
        for step in steps:
            name = step.get("sourceName")
            asset_id = step.get("assetId") or current_files.get(name)
            if not isinstance(asset_id, str):
                executed.append({"index": step.get("index"), "status": "failed",
                                 "error": "unknown_source", "sourceName": name})
                failure = ("failed", "无法按文件名解析 assetId；请重新规划。")
                break
            body = step.get("body") if isinstance(step.get("body"), dict) else {
                "targetDir": step.get("targetDir") or directory,
                "newName": step.get("targetName"),
            }
            try:
                ctx.client.post("/api/v1/assets/" + parse.quote(asset_id, safe="") + "/move",
                                json_body=body)
            except http_client.HTTPStatusError as exc:
                executed.append({"index": step.get("index"), "assetId": asset_id,
                                 "sourceName": name, "targetName": step.get("targetName"),
                                 "status": "failed", "httpStatus": exc.status, "error": exc.body})
                committed = sum(1 for item in executed if item.get("status") == "committed")
                failure = ("partial" if committed else "failed",
                           "服务端拒绝（HTTP 错误）；停止后续移动并保留已执行步骤。")
                break
            executed.append({"index": step.get("index"), "assetId": asset_id,
                             "sourceName": name, "targetName": step.get("targetName"),
                             "status": "committed"})
        # 无论成败都尝试回读（失败诊断也保留实际文件名），回读不可用不掩盖失败步骤。
        report = None
        try:
            after_files = _list_library_files(ctx, library_id, directory)
            report = _engine_call(
                module, "命名引擎 readback_report",
                lambda: module.readback_report(plan, sorted(after_files),
                                               committed_steps=executed))
        except errors.ToolError:
            if failure is None:
                raise
        if failure is not None:
            status, message = failure
            if isinstance(report, dict):
                message += " 回读：" + ("一致" if report.get("ok") else "不一致（见 readback）")
        elif isinstance(report, dict) and not report.get("ok", True):
            status = "failed"
            message = "回读不一致（含服务端自动改名/缺文件/多余文件）；按冲突终止处理。"
        else:
            status = "applied"
            message = "全部移动已提交，回读一致。"
        return {
            "status": status,
            "tool": "media.organize.apply",
            "planFingerprint": plan.get("planFingerprint"),
            "idempotencyKey": plan.get("idempotencyKey"),
            "executedSteps": executed,
            "stepCount": total,
            "readback": report,
            "summary": message,
        }
    finally:
        locks.release()
