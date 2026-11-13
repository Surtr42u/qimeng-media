"""显式工具注册表：稳定 ID、版本、Schema、权限、能力依赖与实现状态。

设计（ADR-0056 §2）：
- 每个工具一条声明，新增工具只增声明 + 模块，不改统一入口条件分支；
- 能力依赖用 openapi.ApiRequirement 表达「端点 + 方法 + 请求 schema/必需字段 +
  参数」，由 openapi.check_requirement 逐级核对，路径存在不等于可用；
- 工具可被 rules.json toolConfig.disabled 或环境变量单独禁用（开关在
  execution 层解析，本模块只做声明与状态计算）；
- 本模块不导入 rulepack（rulepack 反向依赖本模块），避免循环导入；
- media.organize.* 的命名算法只在 media_naming.py，缺失时动态降级为 planned。
"""
from __future__ import annotations

import importlib
import importlib.util
import os
import shutil
import threading
from dataclasses import dataclass, field, replace
from pathlib import Path

from . import openapi, versions

# 惰性实现加载（2026-11-07 扩展性优化，ADR-0056 补充）：
# 注册表只保存「模块:函数」引用，首次执行/描述时才导入实现模块；这样
# catalogo/领域发现不必把所有工具实现模块（以及其依赖）拉进进程与模型上下文。
_HANDLER_CACHE: dict[str, object] = {}
_HANDLER_LOCK = threading.Lock()


def _engine_available() -> bool:
    """media_naming 引擎是否随发行物存在（不导入模块本体，只看可发现性）。"""
    try:
        return importlib.util.find_spec("ai_maintenance.media_naming") is not None
    except (ImportError, ValueError):
        return False


def _engine_version() -> str:
    if not _engine_available():
        return ""
    module = importlib.import_module("ai_maintenance.media_naming")
    return str(getattr(module, "ENGINE_VERSION", ""))


def resolve_handler(spec: "ToolSpec") -> object | None:
    """按需解析工具实现（模块:属性）；planned 工具与本地 meta 工具不需要 handler_ref。"""
    ref = spec.handler_ref
    if not ref:
        return spec.handler if callable(spec.handler) else None
    cached = _HANDLER_CACHE.get(ref)
    if cached is not None:
        return cached
    with _HANDLER_LOCK:
        cached = _HANDLER_CACHE.get(ref)
        if cached is not None:
            return cached
        module_name, separator, attr = ref.partition(":")
        if not separator or not module_name or not attr:
            raise ValueError(f"非法 handler_ref：{ref}（应为 module:attr）")
        module = importlib.import_module(module_name)
        handler = getattr(module, attr, None)
        if not callable(handler):
            raise AttributeError(f"实现缺失：{ref}")
        _HANDLER_CACHE[ref] = handler
        return handler


def handler_available(spec: "ToolSpec") -> bool:
    """实现模块/属性是否存在（用于 catalog/describe 的可用性展示，不执行）。"""
    try:
        return resolve_handler(spec) is not None
    except (ImportError, AttributeError, ValueError):
        return False


def handler_module_available(spec: "ToolSpec") -> bool:
    """实现模块是否可发现（不导入模块本体，避免目录/状态计算触发全部实现加载）。"""
    ref = spec.handler_ref
    if not ref:
        return callable(spec.handler) or bool(spec.planned)
    module_name = ref.partition(":")[0]
    if not module_name:
        return False
    try:
        return importlib.util.find_spec(module_name) is not None
    except (ImportError, ValueError):
        return False

TOOL_CONTRACT_VERSION = versions.TOOL_CONTRACT_VERSION
DEFAULT_TIMEOUT_SECONDS = 20.0
LENS_TOOL_TIMEOUT_SECONDS = 150.0

PERMISSIONS = {
    "read": "只读查询：不写业务数据，不需要确认。",
    "research": "研究辅助：本机研究脚本/研究网关，只产出本地证据，不写业务数据。",
    "author.profile.write": "作者资料写入：完整 CreatorProfileWrite + revision，需计划与确认。",
    "author.cleanup": "作者清理：删除/改名/原子合并，高风险，不可撤销动作需确认。",
    "asset.authors.write": "资产作者关联写入：整体替换关联，需计划与确认。",
    "asset.write": "资产写入（标签整体替换）：先读现状、计划指纹、确认与回读。",
    "asset.upload": "正式上传入库：prepare 绑定源摘要/时间/目标，确认后稳定 uploadId 重放与回读。",
    "library.write": "触发指定库扫描；202 仅受理，须另行按清单核验资产。",
    "asset.delete": "资产删除：一律移入回收站（非物理删除），需确认与回读。",
    "trash.restore": "回收站恢复：需确认、前置检查与回读。",
    "media.organize": "混合媒体整理：按命名计划移动/改名，需计划、确认与回读。",
    "backup.export": "便携备份导出：流式落盘到授权的绝对路径，默认拒绝覆盖。",
    "device.apply": "设备结果包：登记/查询 PC→手机结果包（配对与手机 Worker 未实现）。",
    "maintenance.execute": "外部 AI 执行器控制面：领取租约/续报步骤/工件/终态；leaseToken 是能力凭据。",
    "admin.maintenance": "管理员维护：数据库/迁移/恢复等默认不向 AI 开放的能力。",
    "local.files.write": "授权写根内的本地文件操作（复制/整理/派生图）：不写业务数据，不修改已入库资产/索引/标签。",
    "local.device": "adb 推送/拉取：必须显式设备序列号（无默认设备），只操作授权真机。",
}

# 可用性维度（2026-11-10）：`status` 保持既有合并语义（supported/unavailable/
# incompatible/planned）供现有消费端使用；`availability` 把注册、实现、契约、运行时、
# 本地授权与服务端验证拆开，避免把“没权限/没配置/没实现”混成同一个 supported。
CONTRACT_AVAILABILITY = {
    "supported": "compatible",
    "unavailable": "unavailable",
    "incompatible": "incompatible",
    "not_checked": "not_checked",
}
RUNTIME_READY = "ready"
RUNTIME_UNCONFIGURED = "unconfigured"
RUNTIME_MISSING_DEPENDENCY = "missing_dependency"
RUNTIME_NOT_CHECKED = "not_checked"

COMMON_ERROR_CODES = (
    {"code": "UNKNOWN_TOOL", "category": "usage", "exitCode": 2,
     "recovery": "运行 tools list --json 查看已注册工具 ID。"},
    {"code": "TOOL_DISABLED", "category": "permission", "exitCode": 2,
     "recovery": "检查 rules.json toolConfig.disabled 与 QIMENG_AI_DISABLED_TOOLS / QIMENG_AI_READONLY。"},
    {"code": "TOOL_NOT_IMPLEMENTED", "category": "capability", "exitCode": 2,
     "recovery": "该工具状态为 planned：不要假装执行，等待实现或走既有脚本。"},
    {"code": "PERMISSION_DENIED", "category": "permission", "exitCode": 2,
     "recovery": "该工具所需权限未被可信执行端授予：检查 QIMENG_AI_GRANTED_PERMISSIONS / rules.json toolConfig；看见工具不等于有权执行。"},
    {"code": "FORBIDDEN_SCOPE", "category": "permission", "exitCode": 2,
     "recovery": "服务端 AI 客户端 scope 未覆盖该写请求：由管理员补授 scope（ADR-0057），不要重复提交。"},
    {"code": "TOOL_DEPRECATED", "category": "capability", "exitCode": 2,
     "recovery": "工具已废弃：改用 replacedBy 指向的新工具；旧工具不会继续获得修复。"},
    {"code": "INVALID_INPUT", "category": "usage", "exitCode": 2,
     "recovery": "按 message/details 修正输入；不要盲目重试同一载荷。"},
    {"code": "CAPABILITY_UNAVAILABLE", "category": "capability", "exitCode": 2,
     "recovery": "升级目标实例或工具包；不要猜测旧参数继续执行。"},
    {"code": "CAPABILITY_INCOMPATIBLE", "category": "capability", "exitCode": 2,
     "recovery": "核对 OpenAPI 与工具契约版本；需要新契约时升级工具包。"},
    {"code": "CONFIGURATION_ERROR", "category": "config", "exitCode": 2,
     "recovery": "检查 QIMENG_API_BASE / QIMENG_API_TOKEN；不回显或记录 token。"},
    {"code": "HTTP_ERROR", "category": "remote", "exitCode": 1,
     "recovery": "按 status/body.code 处理；409 重新读取现状并重新计划，不覆盖。"},
    {"code": "NETWORK_ERROR", "category": "remote", "exitCode": 1,
     "recovery": "检查目标 host:port、隧道或网络；不要重复提交写操作。"},
    {"code": "TIMEOUT", "category": "remote", "exitCode": 1,
     "recovery": "稍后重试并先回读现状；不要重复提交写操作。"},
    {"code": "RESPONSE_ERROR", "category": "remote", "exitCode": 1,
     "recovery": "响应非 JSON/超限：缩小查询范围或排查目标服务。"},
    {"code": "PRECONDITION_FAILED", "category": "conflict", "exitCode": 1,
     "recovery": "重新读取目标现状并重新 prepare；不要覆盖或盲目重试。"},
    {"code": "CONFLICT", "category": "conflict", "exitCode": 1,
     "recovery": "读取冲突载荷，人工核对后重新计划；409 不得静默覆盖。"},
    {"code": "LEASE_LOST", "category": "conflict", "exitCode": 1,
     "recovery": "维护任务租约已失效（过期/暂停/取消/被重领）：重新 claim 取得新 leaseToken，旧 token 的写入不会被接受。"},
    {"code": "PATH_NOT_AUTHORIZED", "category": "permission", "exitCode": 2,
     "recovery": "路径不在显式授权根内：由可信执行端配置授权根（存储/媒体根/工作根）后重试；不要绕过边界或扫描未授权目录。"},
    {"code": "PATH_UNSAFE", "category": "usage", "exitCode": 2,
     "recovery": "路径包含越界/符号链接逃逸/盘符或 UNC 等不安全形态：改用授权根内的规范化相对路径；不要尝试绕过。"},
    {"code": "FILE_REF_INVALID", "category": "usage", "exitCode": 2,
     "recovery": "fileRef 格式非法或不属于当前执行端：重新调用 files.search 获取；不要手工拼接、猜测 storage 或跨执行端复用。"},
    {"code": "FILE_REF_STALE", "category": "conflict", "exitCode": 1,
     "recovery": "文件已移动/改名/变化或不存在：重新 files.search 获取新 fileRef；不要用旧引用继续读取或写入。"},
    {"code": "FILE_REF_UNAVAILABLE", "category": "config", "exitCode": 2,
     "recovery": "本地文件发现未配置或实现缺失：检查 QIMENG_AI_STORAGES 是否仍配置该 storageId、fileref 是否随发行物安装；不要伪造 storage、路径或 fileRef。"},
    {"code": "RECIPE_STEP_UNCERTAIN", "category": "conflict", "exitCode": 1,
     "recovery": "步骤结果不确定（超时/中断/写入未回读）：停止自动续跑，先回读目标现状并交人工核对，再决定是否重跑。"},
    {"code": "RECIPE_ARTIFACT_TAMPERED", "category": "verification", "exitCode": 1,
     "recovery": "步骤产物 sha256/指纹与记录不一致：停止执行并保留现场，重新运行该步骤或人工核对；不要信任被改动的产物。"},
    {"code": "PLAN_INVALID", "category": "plan", "exitCode": 2,
     "recovery": "重新调用 prepare 生成计划，不要手工拼造或修改计划。"},
    {"code": "PLAN_FINGERPRINT_MISMATCH", "category": "plan", "exitCode": 1,
     "recovery": "重新 prepare 生成新计划；禁止绕过指纹直接执行。"},
    {"code": "PLAN_VERSION_MISMATCH", "category": "plan", "exitCode": 1,
     "recovery": "用当前工具包重新 prepare；旧规则下的计划不得继续执行。"},
    {"code": "NOT_CONFIRMED", "category": "permission", "exitCode": 2,
     "recovery": "先 prepare 并人工核对，再带 confirm=true 与计划指纹提交。"},
    {"code": "READBACK_MISMATCH", "category": "verification", "exitCode": 1,
     "recovery": "停止后续写入，重新读取核对差异并把已执行步骤交人工处理。"},
    {"code": "INTERNAL_ERROR", "category": "internal", "exitCode": 1,
     "recovery": "工具缺陷；保留输入与错误详情，不要重复写入。"},
    {"code": "AUTHORIZATION_EXPIRED", "category": "permission", "exitCode": 2,
     "recovery": "由可信执行端核对任务授权有效期，模型不能自授。"},
    {"code": "AUTHORIZATION_MISMATCH", "category": "permission", "exitCode": 2,
     "recovery": "核对实例、工具与参数范围；confirm 或指纹不能扩大权限。"},
    {"code": "TASK_BUDGET_EXHAUSTED", "category": "budget", "exitCode": 2,
     "recovery": "保留已完成结果；可信执行端决定是否为同任务增加预算。"},
    {"code": "TASK_NO_PROGRESS", "category": "budget", "exitCode": 2,
     "recovery": "相同请求无新结果，停止循环并报告缺失条件。"},
    {"code": "TASK_BUDGET_UNAVAILABLE", "category": "budget", "exitCode": 2,
     "recovery": "核对预算记录及已有结果；不换操作ID盲目重试写入。"},
)


@dataclass(frozen=True)
class ToolSpec:
    id: str
    version: str
    description: str
    kind: str
    output_schema: dict
    input_schema: dict = field(default_factory=dict)
    handler: object = None
    handler_ref: str = ""
    domain: str = "system"
    requirements: tuple = ()
    permission: str = "read"
    read_only: bool = True
    side_effects: bool = False
    requires_confirmation: bool = False
    confirmation_mode: str = "none"  # none / always / conditional
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    network: bool = True
    error_codes: tuple = ()
    runtime_checks: tuple = ()
    planned: bool = False
    planned_reason: str = ""
    dynamic_planned: object = None
    deprecated: bool = False
    deprecated_reason: str = ""
    replaced_by: str = ""


def _schema(properties: dict, required=(), additional=False) -> dict:
    body = {"type": "object", "properties": properties}
    if required:
        body["required"] = list(required)
    body["additionalProperties"] = additional
    return body


def _string(description: str = "", minimum: int | None = None, maximum: int | None = None) -> dict:
    body = {"type": "string"}
    if description:
        body["description"] = description
    if minimum is not None:
        body["minLength"] = minimum
    if maximum is not None:
        body["maxLength"] = maximum
    return body


def _integer(description: str = "", minimum: int | None = None, maximum: int | None = None,
             default: int | None = None) -> dict:
    body = {"type": "integer"}
    if description:
        body["description"] = description
    if minimum is not None:
        body["minimum"] = minimum
    if maximum is not None:
        body["maximum"] = maximum
    if default is not None:
        body["default"] = default
    return body


def _boolean(description: str = "", default: bool | None = None) -> dict:
    body = {"type": "boolean"}
    if description:
        body["description"] = description
    if default is not None:
        body["default"] = default
    return body


def _req(path: str, method: str, *, schema: str | None = None, fields=(),
         parameters=(), required_parameters=(), body_required: bool | None = None) -> openapi.ApiRequirement:
    return openapi.ApiRequirement(path=path, method=method, request_schema=schema,
                                  required_body_fields=tuple(fields),
                                  body_required=body_required,
                                  parameters=tuple(parameters),
                                  required_parameters=tuple(required_parameters))


_PROFILE_OBJECT_SCHEMA = {
    "type": "object",
    "description": "本次明确修改的档案字段（未列出的字段保持现状）",
    "additionalProperties": False,
    "properties": {
        "nickname": _string("显示昵称；200 字符内，首尾无空白", maximum=200),
        "biography": _string("整理后的简介；2000 字符内", maximum=2000),
        "avatarUrl": _string("HTTPS 公网头像来源 URL", maximum=2000),
        "homepageUrl": _string("作者主页 HTTPS URL；verified 必填", maximum=2000),
        "evidenceUrls": {"type": "array", "maxItems": 8,
                         "items": _string("证据 URL", maximum=2000)},
        "status": {"type": "string", "enum": ["empty", "candidate", "verified"]},
        "autoRefresh": _boolean("自动刷新维护开关；开启需 verified 且主页非空"),
        "feedUrl": _string("RSS/Atom 地址；trackUpdates 开启时必填", maximum=2000),
        "trackUpdates": _boolean("自动追踪开关；开启需 verified 且 feedUrl 非空"),
        "xProfile": {
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "url": _string("HTTPS X/Twitter 账号主页", maximum=2000),
                "biography": _string("X 简介原文；1000 字符内", maximum=1000),
                "biographyZh": _string("中文译文；2000 字符内", maximum=2000),
            },
        },
    },
}

_PLAN_REF = {"type": "object", "description": "prepare 工具返回的 plan 原样传入（禁止手工修改）"}

_EVIDENCE_SCHEMA = {"type": "array", "maxItems": 20,
                    "items": _string("证据/来源引用（URL 或占位描述）", maximum=2000)}

_QUERY_OUTPUT = {"type": "object"}
_LIST_OUTPUT = {"type": "array"}

_READ_ONLY_CODES = ()
_PLAN_RESULT_OUTPUT = _schema({"plan": {"type": ["object", "null"]},
                               "diagnostics": {"type": "object"}}, required=("plan",))
_WRITE_RESULT_OUTPUT = _schema({"status": _string("applied/partial/failed/already_applied"),
                                "tool": {"type": "string"},
                                "summary": {"type": "string"}}, required=("status", "tool"))
# 输出允许工具特有字段（candidates/readback/steps 等），只约束稳定顶层键。
_PLAN_RESULT_OUTPUT["additionalProperties"] = True
_WRITE_RESULT_OUTPUT["additionalProperties"] = True

# 研究/备份/设备工具共用的输出与范围片段（2026-11-08 外部 AI 工具平台）。
_RESEARCH_TIMEOUT = _integer("子进程超时秒数（默认 300，上限 900）", 1, 900)
_DEVICE_ID_SCHEMA = {"type": "string", "pattern": "^[A-Za-z0-9._:-]{1,100}$",
                     "description": "不透明设备标签（1..100 个字母/数字/._:-）"}
_SHA256_HEX_SCHEMA = {"type": "string", "pattern": "^[0-9a-f]{64}$",
                      "description": "64 位小写十六进制 SHA-256"}
_RESEARCH_OUTPUT = _schema({
    "exitCode": _integer("脚本退出码（0 成功；1 部分失败/受限；2 参数或环境错误；124 超时）"),
    "artifacts": {"type": "array", "maxItems": 50,
                  "items": _schema({"path": _string("产物绝对路径"),
                                    "sha256": _SHA256_HEX_SCHEMA,
                                    "bytes": _integer("字节数", 0)},
                                   required=("path", "sha256", "bytes"))},
    "summary": {"type": "object", "additionalProperties": True,
                "description": "有界摘要（退出码/耗时/stdout 尾/工件路径），不含完整 stdout"},
}, required=("exitCode", "artifacts", "summary"), additional=True)


def _cleanup_actions_schema() -> dict:
    return {
        "type": "array",
        "description": "显式清理动作；零关联等信号不能自动组成动作，必须由调用方给出",
        "items": {
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "action": {"type": "string", "enum": ["delete", "rename", "merge"]},
                "authorId": {"type": "string"},
                "displayName": {"type": "string", "maxLength": 200},
                "sourceAuthorId": {"type": "string"},
                "targetAuthorId": {"type": "string"},
            },
        },
    }


TOOLS: tuple[ToolSpec, ...] = (
    ToolSpec(
        id="asset.upload.prepare", version="1.0.0", kind="plan",
        description="本地源文件上传计划：绑定摘要/大小/时间和明确目标库；不写入库根。",
        handler_ref="ai_maintenance.tools_ingest:asset_upload_prepare", permission="asset.upload",
        input_schema=_schema({"libraryId": _string("目标库 ID", 1, 512),
                              "operationId": _string("新操作生成并持久化 UUID；本操作重试保持不变", 1, 36),
                              "path": _string("授权读根内的源文件；与 fileRef 二选一"),
                              "fileRef": _string("files.search 返回的文件引用"),
                              "fileName": _string("最终文件名；缺省源文件名", 1, 255),
                              "directory": _string("库内相对目录，空=根"),
                              "sourceMtime": _integer("源修改时间 Unix 毫秒；缺省源文件时间", 0, 253402300799999)},
                             required=("libraryId", "operationId")),
        output_schema=_PLAN_RESULT_OUTPUT,
        requirements=(_req("/api/v1/libraries", "GET"),),
    ),
    ToolSpec(
        id="asset.upload", version="1.0.0", kind="write",
        description="按已确认上传计划流式入库并回读；七天内同计划稳定 uploadId，不盲重试。",
        handler_ref="ai_maintenance.tools_ingest:asset_upload", permission="asset.upload",
        read_only=False, side_effects=True, requires_confirmation=True, confirmation_mode="always",
        timeout_seconds=120.0,
        input_schema=_schema({"plan": _PLAN_REF, "confirm": _boolean("核对计划后 true")},
                             required=("plan", "confirm")),
        output_schema=_WRITE_RESULT_OUTPUT,
        requirements=(_req("/api/v1/assets/upload", "POST", parameters=("libraryId", "dir", "filename", "uploadId", "sourceMtime")),
                      _req("/api/v1/assets/{assetId}", "GET"), _req("/api/v1/libraries", "GET")),
    ),
    ToolSpec(
        id="library.scan", version="1.0.0", kind="write",
        description="触发指定库异步扫描；仅返回受理状态，不宣称整批入库完成；直接上传无需重扫。",
        handler_ref="ai_maintenance.tools_ingest:library_scan", permission="library.write",
        read_only=False, side_effects=True, requires_confirmation=True, confirmation_mode="always",
        input_schema=_schema({"libraryId": _string("明确目标库 ID", 1, 512),
                              "confirm": _boolean("确认触发该库扫描")}, required=("libraryId", "confirm")),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/libraries", "GET"), _req("/api/v1/libraries/{libraryId}/scan", "POST")),
    ),
    ToolSpec(
        id="project.context", version="1.0.0", kind="context",
        description="返回规则包/版本/能力/工具清单（纯本地，不联网）；可选 markdown 中文指南。",
        handler_ref="ai_maintenance.tools_query:project_context", network=False, permission="read",
        input_schema=_schema({
            "markdown": _boolean("输出中文指南而不是规则包 JSON", False),
            "openapiPath": _string("覆盖 OpenAPI 路径（排障/验证）"),
            "rulesPath": _string("覆盖 rules.json 路径（排障/验证）"),
        }),
        output_schema={"type": "object", "additionalProperties": True,
                       "required": ["format"],
                       "properties": {"format": {"type": "string"}}},
    ),
    ToolSpec(
        id="health.check", version="1.0.0", kind="query",
        description="GET /api/v1/healthz 或 readyz 存活/就绪探针。",
        handler_ref="ai_maintenance.tools_query:health_check", permission="read",
        input_schema=_schema({"ready": _boolean("使用 readyz", False)}),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/healthz", "GET"),
                      _req("/api/v1/readyz", "GET")),
    ),
    ToolSpec(
        id="library.query", version="1.0.0", kind="query",
        description="GET /api/v1/libraries 库列表（含 capabilities）。",
        handler_ref="ai_maintenance.tools_query:library_query", permission="read",
        input_schema=_schema({}), output_schema=_LIST_OUTPUT,
        requirements=(_req("/api/v1/libraries", "GET"),),
    ),
    ToolSpec(
        id="asset.query", version="1.0.0", kind="query",
        description="GET /api/v1/assets 有界分页查询；page 为按 nextCursor 顺序取页的便利参数。",
        handler_ref="ai_maintenance.tools_query:asset_query", permission="read",
        input_schema=_schema({
            "limit": _integer("每页数量", 1, 200),
            "cursor": _string("分页游标 nextCursor"),
            "q": _string("全文搜索"),
            "libraryId": {"type": "string"},
            "mediaType": {"type": "string", "enum": ["image", "animated_image", "video"]},
            "sort": {"type": "string", "enum": ["default", "fileDate", "addedDate",
                                                "viewCount", "playCount", "sizeBytes",
                                                "name", "favoriteAt"]},
            "order": {"type": "string", "enum": ["asc", "desc"]},
            "page": _integer("客户端便利取页（1..50，不发送 page 参数）", 1, 50),
        }),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/assets", "GET", parameters=("limit", "cursor")),),
    ),
    ToolSpec(
        id="author.query", version="1.2.0", kind="query",
        description="GET /api/v1/authors/query；默认20条，使用 cursor 续页。q 按显示名/ID 子串过滤（ASCII 大小写不敏感，非 ASCII 区分大小写）；旧 name/page/size 仍接受，page 通过有界游标请求定位。",
        handler_ref="ai_maintenance.tools_query:author_query", permission="read",
        input_schema=_schema({
            "q": _string("按显示名/ID 子串过滤（未知作者查重）；ASCII 大小写不敏感", 0, 200),
            "cursor": _string("上一页返回的 nextCursor", maximum=4096),
            "limit": _integer("每页数量，默认20", 1, 100, 20),
            "page": _integer("兼容页码便利参数，从 1 开始，最多请求50页", 1, 50),
            "size": _integer("兼容每页数量别名；与 limit 不能同时提供", 1, 100),
            "name": _string("兼容旧参数，等同 q；与 q 不能同时提供", 0, 200),
        }),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/authors/query", "GET",
                            parameters=("q", "limit", "cursor")),),
    ),
    ToolSpec(
        id="author.create", version="1.0.0", kind="write",
        description="POST /api/v1/authors 按名新建或复用常规作者（只建作者行，不写 TXT、不关联文件）；未知作者建档第一步。",
        handler_ref="ai_maintenance.tools_author:author_create", permission="author.profile.write",
        read_only=False, side_effects=True, requires_confirmation=True, confirmation_mode="always",
        input_schema=_schema({
            "displayName": _string("作者显示名/别名串（服务端按首别名派生身份）", 1, 200),
            "confirm": _boolean("必须显式 true", False),
        }, required=("displayName", "confirm")),
        output_schema=_WRITE_RESULT_OUTPUT,
        requirements=(_req("/api/v1/authors", "GET"),
                      _req("/api/v1/authors", "POST", fields=("displayName",),
                           body_required=True)),
        error_codes=({"code": "CONFLICT", "category": "conflict", "exitCode": 1,
                      "recovery": "409=与 COS 作者行撞名：停止并交人工处理，不自动改名或覆盖。"},),
        runtime_checks=("author.regular-only（COS 作者 409）",
                        "只建作者行：不写 TXT 片段、不关联资产；关联走 asset.authors.apply"),
    ),
    ToolSpec(
        id="author.profile.read", version="1.0.0", kind="query",
        description="GET /api/v1/authors/{authorId}/profile 读取作者公开档案与 revision。",
        handler_ref="ai_maintenance.tools_query:author_profile_read", permission="read",
        input_schema=_schema({"authorId": _string("作者 ID", 1, 512)}, required=("authorId",)),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/authors/{authorId}/profile", "GET"),),
    ),
    ToolSpec(
        id="author.sources.read", version="1.0.0", kind="query",
        description="GET /api/v1/authors/{authorId}/sources 读取作者块来源区。",
        handler_ref="ai_maintenance.tools_query:author_sources_read", permission="read",
        input_schema=_schema({"authorId": _string("作者 ID", 1, 512)}, required=("authorId",)),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/authors/{authorId}/sources", "GET"),),
    ),
    ToolSpec(
        id="maintenance.inventory", version="1.0.0", kind="query",
        description="只读清点作者/库现状（有界），输出关联数/档案状态/来源数；不做清理结论。",
        handler_ref="ai_maintenance.tools_query:maintenance_inventory", permission="read",
        input_schema=_schema({
            "maxAuthors": _integer("最多返回作者数", 1, 200, 50),
            "includeProfiles": _boolean("是否逐个读取档案（额外请求，有界）", False),
            "zeroAssetsOnly": _boolean("只看零关联常规作者", False),
        }),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/authors", "GET"), _req("/api/v1/libraries", "GET"),
                      _req("/api/v1/authors/{authorId}/profile", "GET")),
        runtime_checks=("bounded:maxAuthors<=200",),
    ),
    ToolSpec(
        id="maintenance.status", version="1.0.0", kind="query",
        description="查询维护任务账本：GET /maintenance/jobs 列表或 /{id} 详情（只读，不领取/执行）。",
        handler_ref="ai_maintenance.tools_query:maintenance_status", permission="read",
        input_schema=_schema({
            "jobId": _string("任务 ID；给出时查询详情"),
            "status": _string("精确状态过滤"),
            "limit": _integer("列表页大小", 1, 100),
            "cursor": _string("keyset 游标 nextCursor"),
        }),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/maintenance/jobs", "GET",
                           parameters=("status", "limit", "cursor")),
                      _req("/api/v1/maintenance/jobs/{id}", "GET")),
    ),
    ToolSpec(
        id="research.context", version="1.0.0", kind="query",
        description="GET /api/v1/research/context：研究启停、sidecar 状态、provider 矩阵与限额。",
        handler_ref="ai_maintenance.tools_query:research_context", permission="research",
        input_schema=_schema({}), output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/research/context", "GET"),),
        runtime_checks=("research.enabled（服务端开关，关闭时仍可读 context）",),
    ),
    ToolSpec(
        id="research.provider.query", version="1.0.0", kind="query",
        description="POST /api/v1/research/providers/{provider}/query：平台公开字段查询（矩阵外由服务端 400）。",
        handler_ref="ai_maintenance.tools_query:research_provider_query", permission="research",
        input_schema=_schema({
            "provider": _string("provider 名", 1, 64),
            "operation": _string("operation 名", 1, 64),
            "target": _string("账号/ID/标签/URL/搜索词", 1, 500),
            "limit": _integer("期望条数", 1, 50),
        }, required=("provider", "operation", "target")),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/research/providers/{provider}/query", "POST",
                           body_required=True),),
        runtime_checks=("research.enabled（研究关闭时查询 503 RESEARCH_DISABLED）",),
    ),
    ToolSpec(
        id="research.lens", version="1.0.0", kind="query",
        description="POST /api/v1/research/lens：multipart 图片反查代理（需本机 sidecar；links 为空不代表失败）。",
        handler_ref="ai_maintenance.tools_query:research_lens", permission="research", timeout_seconds=LENS_TOOL_TIMEOUT_SECONDS,
        input_schema=_schema({"file": _string("本地图片路径（.jpg/.jpeg/.png/.webp，≤20MiB）", 1)},
                             required=("file",)),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/research/lens", "POST", body_required=True),),
        runtime_checks=("research.enabled 与 sidecar 可用性（服务端 503/504 表达）",),
    ),
    ToolSpec(
        id="author.profile.prepare", version="1.0.0", kind="plan",
        description="只读生成作者档案计划：读 revision、人工字段保护、字段校验与影响预览。",
        handler_ref="ai_maintenance.tools_author:author_profile_prepare", permission="author.profile.write",
        input_schema=_schema({
            "authorId": _string("作者 ID", 1, 512),
            "changes": _PROFILE_OBJECT_SCHEMA,
            "evidence": _EVIDENCE_SCHEMA,
            "allowProtectedOverwrite": _boolean("显式允许覆盖人工已填字段（默认保护）", False),
            "reason": _string("本次修改原因（记录到证据）", maximum=500),
        }, required=("authorId", "changes")),
        output_schema=_PLAN_RESULT_OUTPUT,
        requirements=(_req("/api/v1/authors", "GET"),
                      _req("/api/v1/authors/{authorId}/profile", "GET")),
        error_codes=(),
    ),
    ToolSpec(
        id="author.profile.update", version="1.0.0", kind="write",
        description="按已确认计划执行作者档案保存（完整 CreatorProfileWrite + revision），回读核对。",
        handler_ref="ai_maintenance.tools_author:author_profile_update", permission="author.profile.write",
        read_only=False, side_effects=True, requires_confirmation=True, confirmation_mode="always",
        input_schema=_schema({"plan": _PLAN_REF, "confirm": _boolean("必须显式 true", False)},
                             required=("plan", "confirm")),
        output_schema=_WRITE_RESULT_OUTPUT,
        requirements=(_req("/api/v1/authors", "GET"),
                      _req("/api/v1/authors/{authorId}/profile", "GET"),
                      _req("/api/v1/authors/{authorId}/profile", "PUT",
                           schema="CreatorProfileWrite",
                           fields=("revision", "nickname", "biography", "avatarUrl",
                                   "homepageUrl", "evidenceUrls", "status", "autoRefresh",
                                   "feedUrl", "trackUpdates"),
                           body_required=True)),
        error_codes=({"code": "CONFLICT", "category": "conflict", "exitCode": 1,
                      "recovery": "409=revision 已变化：重新读取并重新 prepare，禁止盲目重试。"},),
    ),
    ToolSpec(
        id="author.cleanup.prepare", version="1.0.0", kind="plan",
        description="只读清点清理候选/显式动作：影响数量、多信号证据、计划指纹；不写业务数据。",
        handler_ref="ai_maintenance.tools_author:author_cleanup_prepare", permission="author.cleanup",
        input_schema=_schema({
            "authorIds": {"type": "array", "maxItems": 50, "items": {"type": "string"},
                          "description": "只读清点的作者 ID（与 scan/actions 三选一）"},
            "scan": _schema({"maxAuthors": _integer("扫描作者数上限", 1, 500, 20)}),
            "actions": _cleanup_actions_schema(),
            "evidence": _EVIDENCE_SCHEMA,
        }),
        output_schema=_PLAN_RESULT_OUTPUT,
        requirements=(_req("/api/v1/authors", "GET"),
                      _req("/api/v1/authors/{authorId}/profile", "GET"),
                      _req("/api/v1/authors/{authorId}/sources", "GET")),
    ),
    ToolSpec(
        id="author.cleanup.apply", version="1.0.0", kind="write",
        description="按已确认计划执行删除/改名/原子合并，逐项回读；失败保留已执行步骤。",
        handler_ref="ai_maintenance.tools_author:author_cleanup_apply", permission="author.cleanup",
        read_only=False, side_effects=True, requires_confirmation=True, confirmation_mode="always",
        input_schema=_schema({"plan": _PLAN_REF, "confirm": _boolean("必须显式 true", False)},
                             required=("plan", "confirm")),
        output_schema=_WRITE_RESULT_OUTPUT,
        requirements=(_req("/api/v1/authors", "GET"),
                      _req("/api/v1/authors/{authorId}", "DELETE"),
                      _req("/api/v1/authors/{authorId}", "PUT", body_required=True,
                           fields=("displayName",)),
                      _req("/api/v1/authors/merge", "POST", schema="AuthorMergeRequest",
                           fields=("sourceAuthorId", "targetAuthorId"), body_required=True)),
        error_codes=({"code": "CONFLICT", "category": "conflict", "exitCode": 1,
                      "recovery": "409 不得静默覆盖：停止并重新 prepare；合并/删除无撤销端点。"},),
        runtime_checks=("author.regular-only（COS 作者 400）",),
    ),
    ToolSpec(
        id="author.merge", version="1.0.0", kind="write",
        description="重复作者原子合并：confirm=false 返回计划；confirm=true 校验指纹后 POST /authors/merge 并回读。",
        handler_ref="ai_maintenance.tools_author:author_merge", permission="author.cleanup",
        read_only=False, side_effects=True, requires_confirmation=True, confirmation_mode="conditional",
        input_schema=_schema({
            "sourceAuthorId": _string("被删除并入的源身份", 1, 512),
            "targetAuthorId": _string("保留的目标身份", 1, 512),
            "confirm": _boolean("false=只返回计划；true=执行", False),
            "planFingerprint": _string("confirm=true 时必填，取自本工具返回的计划"),
        }, required=("sourceAuthorId", "targetAuthorId", "confirm")),
        output_schema={"type": "object", "additionalProperties": True,
                       "properties": {"status": {"type": "string"},
                                      "plan": {"type": "object"},
                                      "tool": {"type": "string"}}},
        requirements=(_req("/api/v1/authors", "GET"),
                      _req("/api/v1/authors/{authorId}/profile", "GET"),
                      _req("/api/v1/authors/{authorId}/sources", "GET"),
                      _req("/api/v1/authors/merge", "POST", schema="AuthorMergeRequest",
                           fields=("sourceAuthorId", "targetAuthorId"), body_required=True)),
        error_codes=({"code": "CONFLICT", "category": "conflict", "exitCode": 1,
                      "recovery": "409/404：不自动重试或重建身份；重新读取后人工决策。"},),
        runtime_checks=("author.regular-only（COS 作者 400）", "irreversible（无撤销端点）"),
    ),
    ToolSpec(
        id="asset.authors.prepare", version="1.0.0", kind="plan",
        description="只读生成资产作者关联替换计划：校验库 authorAttach、当前/目标集合与影响。",
        handler_ref="ai_maintenance.tools_asset:asset_authors_prepare", permission="asset.authors.write",
        input_schema=_schema({
            "assetId": _string("资产 ID", 1, 512),
            "authorIds": {"type": "array", "items": {"type": "string"},
                          "description": "替换后的常规作者 ID 全集（空数组=解除全部关联）"},
            "newAuthorNames": {"type": "array", "maxItems": 20, "items": _string("按名新建/复用的作者名", maximum=200)},
            "evidence": _EVIDENCE_SCHEMA,
        }, required=("assetId", "authorIds")),
        output_schema=_PLAN_RESULT_OUTPUT,
        requirements=(_req("/api/v1/assets/{assetId}", "GET"),
                      _req("/api/v1/authors", "GET"),
                      _req("/api/v1/libraries", "GET")),
        runtime_checks=("library.capabilities.authorAttach=true（执行时读取 GET /libraries 校验）",),
    ),
    ToolSpec(
        id="asset.authors.apply", version="1.0.0", kind="write",
        description="按已确认计划 PUT /assets/{assetId}/authors 整体替换关联，回读核对。",
        handler_ref="ai_maintenance.tools_asset:asset_authors_apply", permission="asset.authors.write",
        read_only=False, side_effects=True, requires_confirmation=True, confirmation_mode="always",
        input_schema=_schema({"plan": _PLAN_REF, "confirm": _boolean("必须显式 true", False)},
                             required=("plan", "confirm")),
        output_schema=_WRITE_RESULT_OUTPUT,
        requirements=(_req("/api/v1/assets/{assetId}", "GET"),
                      _req("/api/v1/authors", "GET"),
                      _req("/api/v1/libraries", "GET"),
                      _req("/api/v1/assets/{assetId}/authors", "PUT",
                           schema="AssetAuthorsReplaceRequest", fields=("authorIds",),
                           body_required=True)),
    ),
    ToolSpec(
        id="media.organize.prepare", version="1.1.0", kind="plan",
        description="媒体整理规划（只读）：scan 模式按内容对齐/去重/序号延顺/拼图目检；libraryId+groups 模式生成组图命名计划。",
        handler_ref="ai_maintenance.tools_media:media_organize_prepare", permission="media.organize",
        input_schema=_schema({
            "libraryId": _string("目标库 ID（libraryId+groups 模式必填）", 1, 512),
            "directory": _string("库内目标目录（相对路径，空=库根）"),
            "groups": {"type": "array", "description": "候选分组（family/files/confidence/confirmed/evidence/order/existingWorkNumber）",
                       "items": {"type": "object"}},
            "scan": {
                "type": "object",
                "description": "本地内容扫描（只读）：根目录/目标目录必须显式授权；与 libraryId+groups 二选一",
                "additionalProperties": False,
                "properties": {
                    "root": _string("扫描根绝对路径（必须在 QIMENG_AI_MEDIA_ROOTS 内）"),
                    "libraryId": _string("可选：从 GET /libraries 的 rootPath 取扫描根（与 root 二选一）"),
                    "targetRoot": _string("可选目标根绝对路径（根目录对齐用）"),
                    "recursive": _boolean("递归子目录（默认 true）", True),
                    "includeHidden": _boolean("包含隐藏文件/目录（默认 false）", False),
                    "dedupeMode": {"type": "string", "enum": ["exact", "perceptual", "off"],
                                   "description": "去重依据：exact=仅 md5；perceptual=md5+感知哈希（默认）；off=不去重"},
                    "maxFiles": _integer("最多扫描文件数（默认 500，硬上限 2000）", 1, 2000),
                    "maxDepth": _integer("最大递归深度（默认 4，硬上限 8）", 0, 8),
                    "perceptualLimit": _integer("最多计算感知哈希的文件数（默认 100，0=跳过）", 0, 500),
                    "maxFileBytes": _integer("单文件哈希预算（字节）", 1, 8 << 30),
                    "maxTotalHashBytes": _integer("本轮总哈希预算（字节）", 1, 32 << 30),
                },
            },
        }),
        output_schema=_PLAN_RESULT_OUTPUT,
        requirements=(_req("/api/v1/libraries", "GET"),
                      _req("/api/v1/assets", "GET", parameters=("directory", "limit", "cursor")),
                      _req("/api/v1/assets/{assetId}/move", "POST", schema="MoveRequest",
                           fields=("targetDir",), body_required=True)),
        runtime_checks=("media_naming 引擎版本与计划 schema（libraryId+groups 模式）",
                        "scan 模式：只读内容指纹（md5 + 可选 dHash64/视频时长+分辨率+抽帧）",
                        "scan 根必须由 QIMENG_AI_MEDIA_ROOTS 显式授权，拒绝越界/符号链接逃逸",
                        "GET /assets directory 精确过滤目标目录（空串=库根，不递归）",
                        "有界翻页 GET /assets 获取目录现状（最多 20 页 × 200）"),
        planned=False, dynamic_planned=lambda: (not _engine_available(),
                                                "media_naming.py 命名引擎未安装（待发布物安装）"),
    ),
    ToolSpec(
        id="media.organize.apply", version="1.1.0", kind="write",
        description="按已确认命名计划逐步 POST /assets/{assetId}/move 并回读文件名；自动改名视为失败。",
        handler_ref="ai_maintenance.tools_media:media_organize_apply", permission="media.organize",
        read_only=False, side_effects=True, requires_confirmation=True, confirmation_mode="always",
        input_schema=_schema({"plan": _PLAN_REF, "confirm": _boolean("必须显式 true", False)},
                             required=("plan", "confirm")),
        output_schema=_WRITE_RESULT_OUTPUT,
        requirements=(_req("/api/v1/assets", "GET", parameters=("directory", "limit", "cursor")),
                      _req("/api/v1/assets/{assetId}/move", "POST", schema="MoveRequest",
                           fields=("targetDir",), body_required=True)),
        runtime_checks=("media_naming 引擎 validate_plan/plan_steps/readback_report",
                        "本地 content-scan 计划只读：apply 直接拒绝（受控合并仍为 planned）",
                        "GET /assets directory 精确过滤目标目录（空串=库根，不递归）",
                        "instance+library 级 SerializedLocks：锁内双检现状，锁超时/失效冲突终止",
                        "QIMENG_AI_LOCK_DIR 可覆盖锁目录；stale 锁不自动删除"),
        dynamic_planned=lambda: (not _engine_available(),
                                 "media_naming.py 命名引擎未安装（待发布物安装）"),
    ),
    ToolSpec(
        id="backup.export", version="1.0.0", kind="admin",
        description="GET /api/v1/export/qimeng-backup 流式落盘 v2 备份（临时文件 + 原子改名），返回 bytes/sha256；默认拒绝覆盖。",
        handler_ref="ai_maintenance.tools_backup:backup_export", permission="backup.export",
        read_only=False, side_effects=True, timeout_seconds=300.0,
        input_schema=_schema({
            "outPath": _string("目标文件绝对路径；仓库内只允许被忽略工作根（.work/.test-temp/build）", 1),
            "overwrite": _boolean("覆盖已有文件（默认 false）", False),
            "maxBytes": _integer("本次导出字节预算（默认 128MiB，硬上限 256MiB）", 1, 256 << 20),
        }, required=("outPath",)),
        output_schema=_schema({
            "path": _string("落盘绝对路径"),
            "bytes": _integer("备份字节数", 0),
            "sha256": _SHA256_HEX_SCHEMA,
        }, required=("path", "bytes", "sha256"), additional=True),
        requirements=(_req("/api/v1/export/qimeng-backup", "GET"),),
        runtime_checks=("256KiB 分块流式写临时文件 + os.replace 原子改名，不使用 2MiB JSON 响应上限",
                        "超过 maxBytes 明确失败并删除半成品；默认不覆盖现有文件",
                        "备份正文只落盘，不进入工具结果/模型上下文"),
    ),
    ToolSpec(
        id="device.apply.prepare", version="1.0.0", kind="plan",
        description="只读生成设备结果包计划：读取本地 TXT/v2 备份、计算 bytes+sha256、按 Go 上限校验并生成 manifest/指纹；不写数据。",
        handler_ref="ai_maintenance.tools_device:device_apply_prepare",
        permission="device.apply",
        input_schema=_schema({
            "targetDeviceId": _DEVICE_ID_SCHEMA,
            "masterTxtPath": _string("作者主表 TXT 绝对路径（≤1MiB）", 1),
            "v2BackupPath": _string("v2 便携备份绝对路径（≤64MiB）", 1),
            "ttlSeconds": _integer("结果包有效期（300..604800，默认 86400）", 300, 604800),
            "rulesFingerprint": _SHA256_HEX_SCHEMA,
            "expectedReadbackByKind": {"type": "object", "additionalProperties": True,
                                       "description": "按 kind 的期望回读指标（masterTxt/v2Backup；每段 ≤16KiB）"},
            "expectedState": {"type": "object", "additionalProperties": {"type": "integer"},
                              "description": "应用后的权威业务计数（assets/authors/assetAuthorRefs/tags/assetTagRefs/viewEvents）"},
        }, required=("targetDeviceId",)),
        output_schema=_PLAN_RESULT_OUTPUT,
        requirements=(_req("/api/v1/device/apply-tasks", "POST",
                           fields=("manifest",), body_required=True),
                      _req("/api/v1/device/apply-tasks/{id}", "GET")),
        runtime_checks=("masterTxt ≤1MiB、v2Backup ≤64MiB、manifest ≤256KiB（与 Go 同口径）",
                        "至少提供 masterTxtPath 与 v2BackupPath 之一",
                        "rulesFingerprint 默认取当前规则包摘要（服务端只校验格式并记录）",
                        "需要 QIMENG_API_BASE/TOKEN 以便把计划绑定到目标实例（不发起写请求）"),
    ),
    ToolSpec(
        id="device.apply.submit", version="1.0.0", kind="write",
        description="按已确认计划重新复核源文件 bytes/sha256 后 multipart 登记结果包，并回读段清单。",
        handler_ref="ai_maintenance.tools_device:device_apply_submit",
        permission="device.apply",
        read_only=False, side_effects=True, requires_confirmation=True,
        confirmation_mode="always", timeout_seconds=300.0,
        input_schema=_schema({"plan": _PLAN_REF, "confirm": _boolean("必须显式 true", False)},
                             required=("plan", "confirm")),
        output_schema=_WRITE_RESULT_OUTPUT,
        requirements=(_req("/api/v1/device/apply-tasks", "POST",
                           fields=("manifest",), body_required=True),
                      _req("/api/v1/device/apply-tasks/{id}", "GET")),
        error_codes=({"code": "PRECONDITION_FAILED", "category": "conflict", "exitCode": 1,
                      "recovery": "源文件在 prepare 后变化：重新 prepare 生成新计划，不要直接改 manifest。"},),
        runtime_checks=("multipart 字段 manifest=JSON 字符串，文件段名与 kind 一致",
                        "回读任务 segments 与 manifest 的 kind/bytes/sha256 完全一致",
                        "手机 Worker/配对未实现：只登记与查询，不宣称已应用"),
    ),
    ToolSpec(
        id="device.apply.status", version="1.0.0", kind="query",
        description="GET /api/v1/device/apply-tasks 列表（targetDevice 过滤 + keyset 分页）或 /{id} 详情。",
        handler_ref="ai_maintenance.tools_device:device_apply_status",
        permission="device.apply",
        input_schema=_schema({
            "targetDevice": _DEVICE_ID_SCHEMA,
            "taskId": _string("任务 ID；给出时查询详情", 1, 100),
            "status": {"type": "string",
                       "enum": ["pending", "claimed", "applied", "partial", "failed",
                                "cancelled", "expired"]},
            "limit": _integer("列表页大小", 1, 100),
            "cursor": _string("keyset 游标 nextCursor"),
        }),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/device/apply-tasks", "GET",
                           parameters=("targetDevice", "status", "limit", "cursor"),
                           required_parameters=("targetDevice",)),
                      _req("/api/v1/device/apply-tasks/{id}", "GET")),
        runtime_checks=("列表必须给 targetDevice；详情用 taskId",),
    ),
    # ---- 外部 AI 执行器控制面（ADR-0058）：leaseToken 是能力凭据 ----
    ToolSpec(
        id="maintenance.claim", version="1.0.0", kind="write",
        description="POST /maintenance/executor/claim 领取一个可执行维护任务；204 返回 claimed=false。leaseToken 只在工具结果出现。",
        handler_ref="ai_maintenance.tools_maintenance:maintenance_claim",
        permission="maintenance.execute",
        read_only=False, side_effects=True, timeout_seconds=60.0,
        input_schema=_schema({
            "kind": {"type": "string",
                     "enum": ["asset_research", "author_cleanup", "backup_export", "custom"]},
            "leaseSeconds": _integer("本次租约秒数（30..1800，默认服务端 120）", 30, 1800),
        }, required=("kind",)),
        output_schema=_schema({
            "claimed": _boolean(),
            "lease": {"type": ["object", "null"]},
        }, required=("claimed",), additional=True),
        requirements=(_req("/api/v1/maintenance/executor/claim", "POST",
                           schema="ClaimMaintenanceJobRequest", fields=("kind",),
                           body_required=True),),
        runtime_checks=("leaseToken 是 fencing token：不得写日志/证据包或转发到其他模型上下文",
                        "返回 completedSteps，恢复时按步骤名跳过已完成项",
                        "409 LEASE_LOST 统一映射为 LEASE_LOST，不自动重领"),
    ),
    ToolSpec(
        id="maintenance.heartbeat", version="1.0.0", kind="write",
        description="POST /maintenance/executor/jobs/{id}/heartbeat 续租；必带 leaseToken，409 LEASE_LOST 表示租约失效。",
        handler_ref="ai_maintenance.tools_maintenance:maintenance_heartbeat",
        permission="maintenance.execute",
        read_only=False, side_effects=True, timeout_seconds=60.0,
        input_schema=_schema({
            "jobId": _string("任务 ID", 1, 100),
            "leaseToken": _string("本次领取的 fencing token（来自 claim）", 1, 200),
            "leaseSeconds": _integer("续租秒数（30..1800）", 30, 1800),
        }, required=("jobId", "leaseToken")),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/maintenance/executor/jobs/{id}/heartbeat", "POST",
                           schema="HeartbeatMaintenanceJobRequest",
                           fields=("leaseToken",), body_required=True),),
        error_codes=({"code": "LEASE_LOST", "category": "conflict", "exitCode": 1,
                      "recovery": "重新 claim 取得新 leaseToken；旧 token 不得继续写入。"},),
    ),
    ToolSpec(
        id="maintenance.step.record", version="1.0.0", kind="write",
        description="POST /maintenance/executor/jobs/{id}/steps 登记步骤状态（按 job+step 幂等 upsert）。",
        handler_ref="ai_maintenance.tools_maintenance:maintenance_step_record",
        permission="maintenance.execute",
        read_only=False, side_effects=True, timeout_seconds=60.0,
        input_schema=_schema({
            "jobId": _string("任务 ID", 1, 100),
            "leaseToken": _string("本次领取的 fencing token（来自 claim）", 1, 200),
            "step": _string("稳定步骤名（恢复时按名跳过）", 1, 100),
            "status": {"type": "string",
                       "enum": ["pending", "running", "completed", "failed", "skipped"]},
            "attempt": _integer("尝试次数", 0),
            "detail": {"type": "object", "additionalProperties": True,
                       "description": "步骤明细（任意 JSON 对象 ≤16KiB）"},
        }, required=("jobId", "leaseToken", "step", "status")),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/maintenance/executor/jobs/{id}/steps", "POST",
                           schema="RecordMaintenanceStepRequest",
                           fields=("leaseToken", "step", "status"), body_required=True),),
        error_codes=({"code": "LEASE_LOST", "category": "conflict", "exitCode": 1,
                      "recovery": "重新 claim 后从 completedSteps 之后继续；不要伪造步骤完成。"},),
    ),
    ToolSpec(
        id="maintenance.artifact.record", version="1.0.0", kind="write",
        description="POST /maintenance/executor/jobs/{id}/artifacts 登记工件元数据（正文不入库，(job,kind,uri) 去重）。",
        handler_ref="ai_maintenance.tools_maintenance:maintenance_artifact_record",
        permission="maintenance.execute",
        read_only=False, side_effects=True, timeout_seconds=60.0,
        input_schema=_schema({
            "jobId": _string("任务 ID", 1, 100),
            "leaseToken": _string("本次领取的 fencing token（来自 claim）", 1, 200),
            "kind": _string("工件类别（如 report/backup/txt）", 1, 50),
            "uri": _string("工件引用（本地路径或结果 URI）", 1, 500),
            "sha256": _SHA256_HEX_SCHEMA,
            "bytes": _integer("工件字节数", 0),
        }, required=("jobId", "leaseToken", "kind", "uri", "sha256", "bytes")),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/maintenance/executor/jobs/{id}/artifacts", "POST",
                           schema="RecordMaintenanceArtifactRequest",
                           fields=("leaseToken", "kind", "uri", "sha256", "bytes"),
                           body_required=True),),
        error_codes=({"code": "LEASE_LOST", "category": "conflict", "exitCode": 1,
                      "recovery": "重新 claim 后重报未登记工件；不要重复伪造新条目。"},),
    ),
    ToolSpec(
        id="maintenance.complete", version="1.0.0", kind="write",
        description="POST /maintenance/executor/jobs/{id}/complete 回传终态（completed/partial/failed，受租约围栏）。",
        handler_ref="ai_maintenance.tools_maintenance:maintenance_complete",
        permission="maintenance.execute",
        read_only=False, side_effects=True, timeout_seconds=60.0,
        input_schema=_schema({
            "jobId": _string("任务 ID", 1, 100),
            "leaseToken": _string("本次领取的 fencing token（来自 claim）", 1, 200),
            "status": {"type": "string", "enum": ["completed", "partial", "failed"]},
            "errorCode": _string("失败/部分完成错误码", maximum=100),
            "errorMessage": _string("失败/部分完成说明", maximum=2000),
        }, required=("jobId", "leaseToken", "status")),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/maintenance/executor/jobs/{id}/complete", "POST",
                           schema="CompleteMaintenanceJobRequest",
                           fields=("leaseToken", "status"), body_required=True),),
        error_codes=({"code": "LEASE_LOST", "category": "conflict", "exitCode": 1,
                      "recovery": "终态不可再改；租约失效时不要反复重试，交由账本/人工处理。"},),
        runtime_checks=("completed 清空错误字段；终态写入后重复回传返回 LEASE_LOST（不幂等重放）",),
    ),
    # ---- research_kit 本机研究工作流（算法在 tools/research_kit，本层只组 argv/边界）----
    ToolSpec(
        id="research.image.pipeline", version="1.0.0", kind="query",
        description="run_pipeline.py：Lens→Yandex→SauceNAO 阶梯编排（预算/断点/失败单列），只产出本地结果文件。",
        handler_ref="ai_maintenance.tools_research:research_image_pipeline",
        permission="research", read_only=False, side_effects=True,
        timeout_seconds=300.0, network=False,
        input_schema=_schema({
            "jobsPath": _string("jobs JSONL 绝对路径（{id,file,rid?}）", 1),
            "stages": _string("auto 或逗号分隔阶段列表"),
            "maxLens": _integer("Lens 请求预算", 1, 100),
            "maxYandex": _integer("Yandex 请求预算", 1, 100),
            "maxSaucenao": _integer("SauceNAO 请求预算", 1, 100),
            "outDir": _string("输出目录绝对路径（默认工作根 image-pipeline）"),
            "deep": _boolean("Lens 命中后继续 Yandex/SauceNAO", False),
            "dryRun": _boolean("只规划不发外部请求", False),
            "timeoutSeconds": _RESEARCH_TIMEOUT,
        }, required=("jobsPath",)),
        output_schema=_RESEARCH_OUTPUT,
        runtime_checks=("argv 列表 + shell=False；工作目录固定仓库根（默认缓存落 build/ 忽略目录）",
                        "路径绝对且在仓库外或忽略工作根内；超时上限 900s",
                        "只返回 artifacts 与有界摘要，不回传完整 stdout"),
    ),
    ToolSpec(
        id="research.image.search", version="1.0.0", kind="query",
        description="按 script 调 lens_batch.mjs / yandex_cbir.py / saucenao_batch.py；Lens 优先、结果只作线索。",
        handler_ref="ai_maintenance.tools_research:research_image_search",
        permission="research", read_only=False, side_effects=True,
        timeout_seconds=300.0, network=False,
        input_schema=_schema({
            "script": {"type": "string", "enum": ["lens", "yandex", "saucenao"]},
            "imagePath": _string("yandex 的单图绝对路径"),
            "jobsPath": _string("lens/saucenao 的 jobs JSONL 绝对路径"),
            "outDir": _string("输出目录绝对路径（默认工作根 image-search）"),
            "maxAgeHours": _integer("缓存 TTL 小时", 1, 8760),
            "limit": _integer("最多处理 N 个待办任务", 1, 200),
            "timeoutSeconds": _RESEARCH_TIMEOUT,
        }, required=("script",)),
        output_schema=_RESEARCH_OUTPUT,
        runtime_checks=("lens 需要 Node 24+（QIMENG_RESEARCH_KIT_NODE 或 PATH），缺失返回 TOOL_NOT_IMPLEMENTED",
                        "yandex 只接受 imagePath；lens/saucenao 只接受 jobsPath",
                        "限速/预算由脚本自身控制，工具不并发重试"),
    ),
    ToolSpec(
        id="research.author.probe", version="1.0.0", kind="query",
        description="按 script 调 fxtwitter_probe.py（账号/帖子公开字段）或 kemono_probe.py（公开 API 元数据）。",
        handler_ref="ai_maintenance.tools_research:research_author_probe",
        permission="research", read_only=False, side_effects=True,
        timeout_seconds=300.0, network=False,
        input_schema=_schema({
            "script": {"type": "string", "enum": ["fxtwitter", "kemono"]},
            "target": _string("fxtwitter：handle/主页 URL/帖子 ID", 1, 500),
            "service": _string("kemono：服务键（如 patreon/fanbox）", 1, 64),
            "creator": _string("kemono：服务内作者 ID", 1, 200),
            "outDir": _string("输出目录绝对路径（默认工作根 author-probe）"),
            "timeoutSeconds": _RESEARCH_TIMEOUT,
        }, required=("script",)),
        output_schema=_RESEARCH_OUTPUT,
        runtime_checks=("fxtwitter 需要 target；kemono 需要 service+creator",
                        "kemono 无 --out：stdout 存为 kemono-result.json 工件（256KiB 上限）"),
    ),
    ToolSpec(
        id="research.media.probe", version="1.1.0", kind="query",
        description="media_probe.py：ffprobe 元数据 + SHA-256 + 15/50/85% 帧抽取（只作识图输入）；支持绝对路径或 files.search 返回的 fileRef。",
        handler_ref="ai_maintenance.tools_research:research_media_probe",
        permission="research", read_only=False, side_effects=True,
        timeout_seconds=300.0, network=False,
        input_schema=_schema({
            "files": {"type": "array", "minItems": 1, "maxItems": 50,
                      "items": {"type": "string"},
                      "description": "媒体文件绝对路径列表（与 fileRefs 至少提供一个）"},
            "fileRefs": {"type": "array", "minItems": 1, "maxItems": 20,
                         "items": {"type": "string", "minLength": 1, "maxLength": 2048},
                         "description": "files.search 返回的不透明 fileRef 列表（与 files 至少提供一个；失效需重新搜索）"},
            "frames": _string("抽帧百分比，如 15,50,85", 1, 40),
            "frameDir": _string("帧输出目录绝对路径（默认本次工作目录/frames）"),
            "timeoutSeconds": _RESEARCH_TIMEOUT,
        }),
        output_schema=_RESEARCH_OUTPUT,
        runtime_checks=("files 与 fileRefs 至少提供一个；fileRef 由 files.search 生成，失效需重新搜索",
                        "缺少 ffmpeg/ffprobe 时脚本退出码 2（环境错误），不当作识别结果",),
    ),
    ToolSpec(
        id="research.avatar.fetch", version="1.0.0", kind="query",
        description="avatar_fetch.py：头像 URL 规范化/下载/格式与像素校验/摘要（outDir 必填）。",
        handler_ref="ai_maintenance.tools_research:research_avatar_fetch",
        permission="research", read_only=False, side_effects=True,
        timeout_seconds=300.0, network=False,
        input_schema=_schema({
            "urls": {"type": "array", "minItems": 1, "maxItems": 20,
                     "items": {"type": "string"}, "description": "http(s) 头像 URL 列表"},
            "jobsPath": _string("jobs JSONL 绝对路径（{id,url}）"),
            "outDir": _string("输出目录绝对路径（必填）", 1),
            "timeoutSeconds": _RESEARCH_TIMEOUT,
        }, required=("outDir",)),
        output_schema=_RESEARCH_OUTPUT,
        runtime_checks=("urls 与 jobsPath 二选一；urls 最多 20 条",
                        "产物只写显式 outDir；manifest.jsonl 记录尺寸/格式/摘要"),
    ),
    # ---- 回收站与标签（只做正式端点，不做物理删除/标签池管理）----
    ToolSpec(
        id="trash.list", version="1.0.0", kind="query",
        description="GET /api/v1/trash 回收站列表（含原路径、大小、到期时刻）。",
        handler_ref="ai_maintenance.tools_trash:trash_list", permission="read",
        input_schema=_schema({}), output_schema=_LIST_OUTPUT,
        requirements=(_req("/api/v1/trash", "GET"),),
    ),
    ToolSpec(
        id="trash.restore", version="1.0.0", kind="write",
        description="POST /api/v1/trash/{trashId}/restore 从回收站恢复；需 confirm，前置检查与回读条目消失。",
        handler_ref="ai_maintenance.tools_trash:trash_restore", permission="trash.restore",
        read_only=False, side_effects=True, requires_confirmation=True,
        confirmation_mode="always",
        input_schema=_schema({
            "trashId": _string("回收站条目 ID", 1, 512),
            "confirm": _boolean("必须显式 true", False),
        }, required=("trashId", "confirm")),
        output_schema=_WRITE_RESULT_OUTPUT,
        requirements=(_req("/api/v1/trash", "GET"),
                      _req("/api/v1/trash/{trashId}/restore", "POST")),
        runtime_checks=("恢复前条目必须仍在回收站；恢复后回读确认已消失",
                        "同名冲突由服务端自动改名，工具不猜测实际文件名"),
    ),
    ToolSpec(
        id="asset.trash", version="1.0.0", kind="write",
        description="DELETE /api/v1/assets/{assetId}：删除一律移入回收站（非物理删除）；需 confirm 并回读 404。",
        handler_ref="ai_maintenance.tools_asset:asset_trash", permission="asset.delete",
        read_only=False, side_effects=True, requires_confirmation=True,
        confirmation_mode="always",
        input_schema=_schema({
            "assetId": _string("资产 ID", 1, 512),
            "confirm": _boolean("必须显式 true", False),
        }, required=("assetId", "confirm")),
        output_schema=_WRITE_RESULT_OUTPUT,
        requirements=(_req("/api/v1/assets/{assetId}", "GET"),
                      _req("/api/v1/assets/{assetId}", "DELETE")),
        runtime_checks=("永久删除只走独立管理操作，本工具不提供",),
    ),
    ToolSpec(
        id="asset.tags.replace", version="1.0.0", kind="write",
        description="PUT /api/v1/assets/{assetId}/tags 整体替换标签：先读详情出计划，confirm+planFingerprint 后写入并回读。",
        handler_ref="ai_maintenance.tools_asset:asset_tags_replace", permission="asset.write",
        read_only=False, side_effects=True, requires_confirmation=True,
        confirmation_mode="conditional",
        input_schema=_schema({
            "assetId": _string("资产 ID", 1, 512),
            "tagIds": {"type": "array", "maxItems": 200, "items": {"type": "string"},
                       "description": "替换后的标签 ID 全集（空数组=清空）"},
            "confirm": _boolean("false=只返回计划；true=执行", False),
            "planFingerprint": _string("confirm=true 时必填，取自本工具返回的计划"),
        }, required=("assetId", "tagIds", "confirm")),
        output_schema={"type": "object", "additionalProperties": True,
                       "properties": {"status": {"type": "string"},
                                      "plan": {"type": "object"},
                                      "tool": {"type": "string"}}},
        requirements=(_req("/api/v1/assets/{assetId}", "GET"),
                      # 该端点请求体是单行 flow schema，分块解析器不展开内联 required；
                      # 这里只核对端点/方法/requestBody，tagIds 由 handler 运行时强校验。
                      _req("/api/v1/assets/{assetId}/tags", "PUT", body_required=True)),
        runtime_checks=("请求体必须是 {tagIds:[...]}；服务端对未知标签 400，本工具按前置失败处理",
                        "整体替换会刷新全部标签关联时间；逐条解除用 DELETE /assets/{assetId}/tags/{tag}",
                        "确认前重新读取资产标签并与计划摘要比对",
                        "不新建/删除标签池条目"),
    ),
    # ---- 本地文件发现（未入库文件；fileRef 由 fileref 唯一实现）----
    ToolSpec(
        id="files.storages", version="1.0.0", kind="query",
        description="列出已配置的本地文件存储（storageId/writable/configured），不返回物理绝对路径；无配置时返回空列表与设置提示。",
        handler_ref="ai_maintenance.tools_files:files_storages", network=False,
        permission="read",
        input_schema=_schema({}),
        output_schema=_schema({
            "storages": {"type": "array", "maxItems": 64,
                         "items": _schema({
                             "storageId": _string("不透明存储 ID", 1, 120),
                             "writable": _boolean("该存储是否允许本地写入（local.files.write）"),
                         }, required=("storageId", "writable"))},
            "configured": _boolean("是否已配置至少一个存储"),
            "available": _boolean("本地文件发现实现是否可用"),
            "hint": _string("不可用/未配置时的设置提示"),
        }, required=("storages", "configured", "available")),
        runtime_checks=("只读：不创建/覆盖/删除文件，不需要确认",
                        "绝不返回物理绝对路径；storage 配置由可信执行端维护",
                        "fileref 缺失或未配置时返回 available=false + hint，不伪造结果"),
    ),
    ToolSpec(
        id="files.search", version="1.0.0", kind="query",
        description="在授权存储内有界搜索本地文件，返回不透明 fileRef、相对路径与分页游标；只读、跳过符号链接/隐藏项。",
        handler_ref="ai_maintenance.tools_files:files_search", network=False,
        permission="read",
        input_schema=_schema({
            "storageId": _string("存储 ID（存在多个存储时必填；取自 files.storages）", 1, 120),
            "query": _string("按相对路径/文件名子串过滤（不区分大小写）", 1, 200),
            "extensions": {"type": "array", "maxItems": 10,
                           "items": {"type": "string", "pattern": r"^\.?[A-Za-z0-9]{1,10}$",
                                     "description": "扩展名（可带前导点，如 jpg 或 .jpg）"}},
            "mediaType": {"type": "string", "enum": ["image", "video", "other", "any"],
                          "default": "any", "description": "按扩展名粗分类过滤"},
            "modifiedAfter": _string("修改时间下界（ISO-8601，含）", 1, 40),
            "modifiedBefore": _string("修改时间上界（ISO-8601，含）", 1, 40),
            "limit": _integer("每页条数（默认 20，上限 50）", 1, 50, 20),
            "cursor": _string("上一页 nextCursor（不透明，原样使用；不要与行内 storageId 重组）", 1, 2048),
            "recursive": _boolean("递归子目录（默认 true）", True),
            "maxDepth": _integer("最大递归深度（默认 4，上限 8）", 0, 8, 4),
            "includeHidden": _boolean("包含隐藏文件/目录（默认 false）", False),
        }),
        output_schema=_schema({
            "files": {"type": "array", "maxItems": 50,
                      "items": _schema({
                          "fileRef": _string("不透明文件引用（只能由 files.search 生成）", 1, 4096),
                          "name": _string("文件名", 1, 512),
                          "storageId": _string("fileRef 所属存储 ID（嵌套存储时取最具体项）", 1, 120),
                          "relativePath": _string("相对该 storage 的 POSIX 路径", 1, 1024),
                          "mediaType": {"type": "string", "enum": ["image", "video", "other"]},
                          "size": _integer("字节数", 0),
                          "modifiedAt": _string("修改时间（ISO-8601 UTC）", 1, 40),
                      }, required=("fileRef", "name", "storageId", "relativePath",
                                   "mediaType", "size", "modifiedAt"))},
            "count": _integer("本页条数", 0, 50),
            "truncated": _boolean("是否还有下一页（或扫描达到硬上限）"),
            "nextCursor": {"type": ["string", "null"],
                           "description": "下一页游标；truncated=false 时为 null"},
            "limits": _schema({
                "limit": _integer("本页上限", 1, 50),
                "maxLimit": _integer("单页硬上限", 1, 50),
                "maxScanEntries": _integer("单次扫描条目硬上限", 1),
                "maxDepth": _integer("递归深度硬上限", 0, 8),
                "maxExtensions": _integer("扩展名过滤上限", 1),
            }, required=("limit", "maxLimit", "maxScanEntries", "maxDepth", "maxExtensions")),
            "scanned": _schema({
                "entries": _integer("已访问目录条目数", 0),
                "files": _integer("已识别普通文件数", 0),
                "directories": _integer("已进入目录数", 0),
                "skippedHidden": _integer("跳过的隐藏项", 0),
                "skippedSymlinks": _integer("跳过的符号链接/junction", 0),
                "skippedDepth": _integer("因深度上限未进入的目录", 0),
                "scanLimitReached": _boolean("是否触发扫描条目硬上限"),
                "matched": _integer("游标过滤后、本页之前的命中数", 0),
                "matchedBeforeCursor": _integer("应用 cursor 前的命中总数", 0),
                "elapsedMs": _integer("扫描耗时毫秒", 0),
            }, required=("entries", "files", "directories", "skippedHidden",
                         "skippedSymlinks", "skippedDepth", "scanLimitReached")),
            "available": _boolean("本地文件发现实现是否可用"),
            "storageId": _string("本次搜索使用的存储 ID", 1, 120),
            "hint": _string("不可用/未配置时的设置提示"),
        }, required=("files", "count", "truncated", "nextCursor", "limits",
                     "scanned", "available")),
        runtime_checks=("只读：不写文件、不移动/改名、不修改业务数据",
                        "扫描条目硬上限 20000；每页最多 50；递归深度上限 8",
                        "跳过符号链接/junction/隐藏项（includeHidden=true 才包含隐藏项）",
                        "cursor 只编码 storageId + 已扫描相对路径，解析时重新校验存储根",
                        "不返回绝对路径或整棵目录树；fileRef 由 fileref 生成",
                        "已入库资产/索引/作者/标签必须走 Go API，不能用本地搜索替代"),
    ),
    # ---- 元工具：领域能力目录与按需描述（2026-11-07 扩展性优化）----
    # 工具数量增长后，外部 AI 先取 platform.catalog（精简、无 Schema），再按需
    # 调 platform.tool.describe / platform.rules 取单个工具 Schema、示例与规则节，
    # 避免把完整规则包与全部工具 Schema 一次性塞进模型上下文。
    ToolSpec(
        id="platform.catalog", version="1.0.0", kind="context",
        description="精简能力目录：按领域分组的工具 id/版本/摘要/状态/权限（不含输入输出 Schema；纯本地）。",
        handler_ref="ai_maintenance.catalog:platform_catalog", network=False, permission="read",
        input_schema=_schema({
            "domain": _string("只看某个领域（author/media/research/backup/device/maintenance/system）"),
            "includeDeprecated": _boolean("包含已废弃工具", False),
        }),
        output_schema={"type": "object", "additionalProperties": True,
                       "properties": {"domains": {"type": "array"}, "tools": {"type": "array"}}},
    ),
    ToolSpec(
        id="platform.context", version="1.0.0", kind="context",
        description="按任务/领域返回相关工具、版本、Schema 摘要、规则节、playbook、示例、权限与确认要求；不加载全量规则/工具。",
        handler_ref="ai_maintenance.catalog:platform_context", network=False, permission="read",
        input_schema=_schema({
            "task": _string("任务名/playbook/领域/工具 ID，如 media.organize、author.cleanup、backup.sync", 1, 200),
            "domain": _string("领域过滤（author/media/research/backup/device/maintenance/system）"),
            "includeSchemas": _boolean("返回相关工具的输入/输出 Schema 摘要（默认 true；recipe 任务卡默认 false）", True),
            "includeRules": _boolean("返回相关规则节（默认 true；recipe 任务卡默认 false）", True),
            "maxTools": _integer("最多返回工具数（默认 12，上限 24）", 1, 24, 12),
        }),
        output_schema={"type": "object", "additionalProperties": True,
                       "properties": {"task": {"type": "object"},
                                      "tools": {"type": "array"},
                                      "rules": {"type": "object"},
                                      "playbook": {"type": ["object", "null"]}}},
    ),
    ToolSpec(
        id="platform.tool.describe", version="1.0.0", kind="context",
        description="按需取单个工具的完整定义：输入/输出 Schema、示例、规则引用摘录、Go API 能力依赖与权限。",
        handler_ref="ai_maintenance.catalog:platform_tool_describe", network=False, permission="read",
        input_schema=_schema({"toolId": _string("工具 ID（取自 platform.catalog）", 1, 200)},
                             required=("toolId",)),
        output_schema={"type": "object", "additionalProperties": True,
                       "properties": {"tool": {"type": "object"}}},
    ),
    ToolSpec(
        id="platform.playbook", version="1.0.0", kind="context",
        description="按需读取操作提示词/业务规范/工作流模板（playbook）：列目录或取单个，不加载完整规则包；模板不是强制路径。",
        handler_ref="ai_maintenance.catalog:platform_playbook", network=False, permission="read",
        input_schema=_schema({
            "name": _string("playbook 名称（省略时按领域列目录）", 1, 120),
            "domain": _string("按领域筛选：author/media/research/backup/device/maintenance/system"),
        }),
        output_schema={"type": "object", "additionalProperties": True,
                       "properties": {"playbooks": {"type": "array"}, "playbook": {"type": "object"}}},
    ),
    ToolSpec(
        id="platform.recipe", version="1.0.0", kind="context",
        description="可执行编排配方（recipe）：list 列目录、show 取完整定义、card 取 精简任务卡；默认不加载工具 Schema/完整规则。",
        handler_ref="ai_maintenance.recipes:platform_recipe", network=False, permission="read",
        input_schema=_schema({
            "action": {"type": "string", "enum": ["list", "show", "card"]},
            "recipeId": _string("recipe ID，如 author.discover、media.batch", 1, 120),
        }, required=("action",)),
        output_schema={"type": "object", "additionalProperties": True,
                       "properties": {"recipes": {"type": "array"},
                                      "recipe": {"type": "object"},
                                      "card": {"type": "object"}}},
    ),
    ToolSpec(
        id="platform.rules", version="1.0.0", kind="context",
        description="按需读取规则节（如 mediaNaming/authorActions/fields/permissions），不加载完整 rules.json。",
        handler_ref="ai_maintenance.catalog:platform_rules", network=False, permission="read",
        input_schema=_schema({
            "offset": _integer("单节原始规则JSON续读字符偏移，需只指定一个 sections", 0),
            "limit": _integer("规则JSON片段字符数，按nextOffset续读；不是完整执行参数", 1, 1000),
            "sections": {"type": "array", "maxItems": 10,
                         "items": _string("rules.json 顶层节名或点路径", 1, 120)},
            "domain": _string("按领域取默认规则节：author/media/research/backup/device/maintenance/system"),
        }),
        output_schema={"type": "object", "additionalProperties": True,
                       "properties": {"rulepackVersion": {"type": "string"}}},
    ),
)

# 领域归属（工具 id 前缀 → 领域；领域用于精简目录与按需发现，不改变工具语义）。
_DOMAIN_PREFIXES = (
    ("platform.", "system"),
    ("project.", "system"),
    ("health.", "system"),
    ("research.", "research"),
    ("author.", "author"),
    ("asset.authors.", "author"),
    ("asset.", "media"),
    ("library.", "media"),
    ("trash.", "media"),
    ("files.", "media"),
    ("media.", "media"),
    ("backup.", "backup"),
    ("device.", "device"),
    ("maintenance.", "maintenance"),
)


def tool_domain(tool_id: str) -> str:
    for prefix, domain in _DOMAIN_PREFIXES:
        if tool_id.startswith(prefix):
            return domain
    return "system"


TOOLS = tuple(replace(tool, domain=tool_domain(tool.id)) for tool in TOOLS)

# Long plans remain on the trusted executor; model clients may pass the opaque reference.
# Exactly-one validation is performed by execution before resolving and validating the plan.
def _with_plan_reference(tool):
    if "plan" not in tool.input_schema.get("properties", {}):
        return tool
    input_schema = dict(tool.input_schema)
    input_schema["properties"] = {**input_schema["properties"],
        "planRef": _string("prepare 返回的原计划引用；与 plan 只能提供一个", 1, 512)}
    input_schema["required"] = [key for key in input_schema.get("required", []) if key != "plan"]
    return replace(tool, input_schema=input_schema)


TOOLS = tuple(_with_plan_reference(tool) for tool in TOOLS) + (
    ToolSpec(
        id="platform.result.read", version="1.0.0", kind="context", domain="system",
        description="按 resultRef 分页/点路径续读被省略结果；计划执行直接传 planRef，不重造计划。",
        handler_ref="ai_maintenance.model_output:read_result", network=False, permission="read",
        input_schema=_schema({"resultRef": _string("结果引用", 1, 512),
                              "cursor": _string("上一页 nextCursor", 1, 2048),
                              "limit": _integer("条数或字符串字符数上限", 1, 500),
                              "path": _string("RFC6901 JSON Pointer，如 /tools/0；省略即根对象", 0, 1000)},
                             required=("resultRef",)),
        output_schema=_QUERY_OUTPUT,
    ),
)

# 领域目录元数据：稳定顺序 + 中文标签 + 一句话说明（供 platform.catalog / CLI 展示）。
DOMAINS: tuple = (
    {"id": "author", "label": "作者", "description": "作者身份/档案/来源/清理/合并与作者关联"},
    {"id": "media", "label": "媒体", "description": "媒体库/资产查询、混合媒体命名整理"},
    {"id": "research", "label": "研究", "description": "本机研究工具包（Lens 优先）与可选研究网关（默认关闭）"},
    {"id": "backup", "label": "备份", "description": "便携备份流式导出与摘要"},
    {"id": "device", "label": "设备", "description": "设备结果包登记/查询（配对与 Android Worker 未实现）"},
    {"id": "maintenance", "label": "后台维护", "description": "维护任务账本查询、清点与外部执行器受控领取"},
    {"id": "system", "label": "平台", "description": "上下文/版本/能力目录与平台元工具"},
)

# 工具示例：按需在 platform.tool.describe 返回；不进入 tools list / MCP tools/list 默认载荷。
TOOL_EXAMPLES: dict = {
    "asset.upload.prepare": ({"libraryId": "lib-synthetic", "operationId": "00000000-0000-4000-8000-000000000001", "fileRef": "<files.search 返回的 fileRef>"},),
    "asset.upload": ({"plan": {"…": "asset.upload.prepare 返回的 plan"}, "confirm": True},),
    "library.scan": ({"libraryId": "lib-synthetic", "confirm": True},),
    "project.context": ({"markdown": True},),
    "asset.query": ({"limit": 20, "q": "朱诺 11", "cursor": "…"},),
    "author.query": (({"q": "Alice", "limit": 20}),),
    "author.profile.read": ({"authorId": "author-xxx"},),
    "author.profile.prepare": ({"authorId": "author-xxx",
                                "changes": {"biography": "合成示例", "status": "candidate"},
                                "evidence": ["https://example.com/source"]},),
    "author.profile.update": ({"plan": {"…": "prepare 返回的 plan 原样"}, "confirm": True},),
    "author.cleanup.prepare": ({"actions": [{"action": "merge",
                                             "sourceAuthorId": "src", "targetAuthorId": "dst"}]},),
    "author.merge": ({"sourceAuthorId": "src", "targetAuthorId": "dst", "confirm": False},),
    "asset.authors.prepare": ({"assetId": "asset-xxx", "authorIds": ["author-xxx"]},),
    "media.organize.prepare": ({"libraryId": "lib", "directory": "合成目录",
                                "groups": [{"family": "守望先锋 朱诺",
                                            "files": [{"name": "乱码1.png"}, {"name": "乱码2.png"}],
                                            "confidence": 0.95, "confirmed": True}]},
                               {"scan": {"root": "<授权媒体根绝对路径>",
                                         "targetRoot": "<授权目标根绝对路径>",
                                         "maxFiles": 500, "perceptualLimit": 100}}),
    "media.organize.apply": ({"plan": {"…": "prepare 返回的 plan 原样"}, "confirm": True},),
    "research.context": ({},),
    "maintenance.status": ({"status": "queued", "limit": 20},),
    "maintenance.claim": ({"kind": "asset_research"},),
    "maintenance.heartbeat": ({"jobId": "<jobId>", "leaseToken": "<claim 返回的 token>",
                               "leaseSeconds": 300},),
    "maintenance.step.record": ({"jobId": "<jobId>", "leaseToken": "<claim 返回的 token>",
                                 "step": "inventory", "status": "completed",
                                 "detail": {"authors": 12}},),
    "maintenance.artifact.record": ({"jobId": "<jobId>", "leaseToken": "<claim 返回的 token>",
                                     "kind": "report", "uri": "<本地绝对路径>",
                                     "sha256": "0" * 64, "bytes": 1024},),
    "maintenance.complete": ({"jobId": "<jobId>", "leaseToken": "<claim 返回的 token>",
                              "status": "completed"},),
    "backup.export": ({"outPath": "<仓库外绝对路径>/qimeng-backup.zip", "maxBytes": 134217728},),
    "device.apply.prepare": ({"targetDeviceId": "phone-main",
                              "masterTxtPath": "<仓库外绝对路径>/作者总表.txt",
                              "ttlSeconds": 86400},),
    "device.apply.submit": ({"plan": {"…": "device.apply.prepare 返回的 plan 原样"},
                             "confirm": True},),
    "device.apply.status": ({"targetDevice": "phone-main", "limit": 20},),
    "research.image.pipeline": ({"jobsPath": "<仓库外绝对路径>/jobs.jsonl",
                                 "maxLens": 15, "maxYandex": 10},),
    "research.image.search": ({"script": "yandex", "imagePath": "<仓库外绝对路径>/frame.webp"},),
    "research.author.probe": ({"script": "fxtwitter", "target": "@example"},),
    "research.media.probe": ({"files": ["<仓库外绝对路径>/video.mp4"], "frames": "15,50,85"},),
    "research.avatar.fetch": ({"urls": ["https://example.com/avatar.png"],
                               "outDir": "<仓库外绝对路径>/avatars"},),
    "trash.list": ({},),
    "trash.restore": ({"trashId": "<trashId>", "confirm": True},),
    "files.storages": ({},),
    "files.search": ({"storageId": "<storageId>", "query": "朱诺",
                      "extensions": ["jpg", "png"], "mediaType": "image", "limit": 20},),
    "asset.trash": ({"assetId": "<assetId>", "confirm": True},),
    "asset.tags.replace": ({"assetId": "<assetId>", "tagIds": ["<tagId>"], "confirm": False},),
    "platform.catalog": ({"domain": "author"},),
    "platform.context": ({"task": "media.organize"},
                         {"task": "backup.sync", "includeSchemas": False, "maxTools": 8}),
    "platform.tool.describe": ({"toolId": "author.profile.prepare"},),
    "platform.rules": ({"sections": ["mediaNaming", "permissions"]},),
    "platform.playbook": ({"name": "media.organize"},),
}

# 工具与规则节引用：按需描述时只摘录相关规则，避免整包加载进上下文。
TOOL_RULES_REFS: dict = {
    "asset.upload.prepare": ("mediaNaming", "permissions"),
    "asset.upload": ("mediaNaming", "permissions", "errors"),
    "library.scan": ("permissions", "errors"),
    "platform.context": ("permissions", "toolCatalog"),
    "platform.catalog": ("toolCatalog", "permissions"),
    "platform.tool.describe": ("toolCatalog", "permissions"),
    "platform.playbook": ("toolCatalog", "permissions"),
    "platform.rules": ("permissions",),
    "author.profile.read": ("authorActions", "fields"),
    "author.profile.prepare": ("authorActions", "fields", "permissions"),
    "author.profile.update": ("authorActions", "fields", "permissions", "errors"),
    "author.cleanup.prepare": ("authorActions", "workflows", "permissions"),
    "author.cleanup.apply": ("authorActions", "workflows", "permissions", "errors"),
    "author.merge": ("authorActions", "workflows", "permissions", "errors"),
    "asset.authors.prepare": ("workflows", "permissions"),
    "asset.authors.apply": ("workflows", "permissions", "errors"),
    "media.organize.prepare": ("mediaOrganize", "mediaNaming", "workflows", "permissions"),
    "media.organize.apply": ("mediaOrganize", "mediaNaming", "workflows", "permissions", "errors"),
    "backup.export": ("backup", "permissions"),
    "device.apply.prepare": ("deviceApply", "permissions"),
    "device.apply.submit": ("deviceApply", "permissions", "errors"),
    "device.apply.status": ("deviceApply", "permissions"),
    "maintenance.claim": ("maintenanceExecutor", "permissions"),
    "maintenance.heartbeat": ("maintenanceExecutor", "permissions", "errors"),
    "maintenance.step.record": ("maintenanceExecutor", "permissions", "errors"),
    "maintenance.artifact.record": ("maintenanceExecutor", "permissions", "errors"),
    "maintenance.complete": ("maintenanceExecutor", "permissions", "errors"),
    "maintenance.status": ("permissions",),
    "research.context": ("research", "permissions"),
    "research.provider.query": ("research", "permissions"),
    "research.lens": ("research", "permissions"),
    "research.image.pipeline": ("research", "permissions"),
    "research.image.search": ("research", "permissions"),
    "research.author.probe": ("research", "permissions"),
    "research.media.probe": ("research", "permissions"),
    "research.avatar.fetch": ("research", "permissions"),
    "trash.list": ("trash", "permissions"),
    "trash.restore": ("trash", "permissions", "errors"),
    "asset.trash": ("trash", "permissions", "errors"),
    "asset.tags.replace": ("tags", "permissions", "errors"),
    "files.storages": ("localFiles", "permissions"),
    "files.search": ("localFiles", "permissions", "errors"),
}

# 领域 → 默认规则节（platform.rules domain 模式）。
DOMAIN_RULE_SECTIONS: dict = {
    "author": ("authorActions", "fields", "permissions"),
    "media": ("mediaOrganize", "mediaNaming", "localFiles", "workflows", "permissions"),
    "research": ("research", "permissions"),
    "backup": ("backup", "permissions"),
    "device": ("deviceApply", "permissions"),
    "maintenance": ("maintenanceExecutor", "permissions"),
    "system": ("permissions", "workflows"),
}

_TOOL_INDEX = {tool.id: tool for tool in TOOLS}


def get(tool_id: str) -> ToolSpec | None:
    return _TOOL_INDEX.get(tool_id)


def tool_ids() -> list[str]:
    return [tool.id for tool in TOOLS]


def contracts_version() -> str:
    return TOOL_CONTRACT_VERSION


def capability_checks(spec: ToolSpec, document) -> list:
    if not spec.requirements:
        return []
    return list(openapi.check_requirements(document, spec.requirements))


def tool_status(spec: ToolSpec, document) -> dict:
    """计算工具最终状态：planned / unavailable / incompatible / supported。"""
    dynamic = spec.dynamic_planned() if callable(spec.dynamic_planned) else None
    if dynamic and dynamic[0]:
        status, reason = "planned", dynamic[1]
        checks = capability_checks(spec, document) if document is not None else []
        return {"status": status, "reason": reason, "checks": [c.to_dict() for c in checks],
                "capabilityStatus": openapi.overall_status(checks) if checks else "supported"}
    if spec.planned:
        return {"status": "planned", "reason": spec.planned_reason,
                "checks": [], "capabilityStatus": "not_checked"}
    if spec.handler_ref and not handler_module_available(spec):
        return {"status": "unavailable",
                "reason": "实现模块缺失：" + spec.handler_ref.partition(":")[0] +
                          "（随发行物安装后可用）",
                "checks": [], "capabilityStatus": "not_checked"}
    checks = capability_checks(spec, document) if document is not None else []
    overall = openapi.overall_status(checks) if checks else "supported"
    return {"status": overall,
            "reason": "" if overall == "supported" else "OpenAPI 能力核对未通过",
            "checks": [check.to_dict() for check in checks],
            "capabilityStatus": overall}


def _implemented_status(spec: ToolSpec, deep: bool = False) -> bool:
    """handler 模块/属性是否可解析；planned 工具不算已实现。"""
    if spec.planned:
        return False
    if not spec.handler_ref:
        return callable(spec.handler)
    return handler_available(spec) if deep else handler_module_available(spec)


def _runtime_status(spec: ToolSpec, env) -> str:
    """本地执行环境是否具备运行前提；只做可判定的检查，不猜测。"""
    if spec.planned:
        return RUNTIME_NOT_CHECKED
    source = env if env is not None else os.environ
    if spec.id.startswith("research.image."):
        configured = str(source.get("QIMENG_RESEARCH_KIT_NODE", "") or "").strip()
        if configured:
            if Path(configured).is_file() or shutil.which(configured):
                return RUNTIME_READY
            return RUNTIME_MISSING_DEPENDENCY
        return RUNTIME_READY if shutil.which("node") else RUNTIME_MISSING_DEPENDENCY
    if spec.id.startswith("files."):
        try:
            available = importlib.util.find_spec("ai_maintenance.fileref") is not None
        except (ImportError, ValueError):
            available = False
        return RUNTIME_READY if available else RUNTIME_MISSING_DEPENDENCY
    if spec.network:
        missing = [name for name in ("QIMENG_API_BASE", "QIMENG_API_TOKEN")
                   if not str(source.get(name, "") or "").strip()]
        return RUNTIME_UNCONFIGURED if missing else RUNTIME_READY
    return RUNTIME_READY


def availability(spec: ToolSpec, document, *, granted_permissions=None, env=None,
                 deep: bool = False) -> dict:
    """拆分可用性维度，避免把未实现/未配置/无权限混成同一个 supported。

    - registered：注册表存在（本函数只服务已注册工具，恒为 True）；
    - implemented：handler 模块/属性可解析（catalog 用 find_spec，describe 可完整）；
    - contract：与 OpenAPI 的能力核对（compatible/incompatible/unavailable/not_checked）；
    - runtime：本地执行环境（网络工具是否配置 API、research.image.* 是否有 node、
      files.* 是否有 fileref）；无法判定时 not_checked；
    - locallyAuthorized：权限是否在 granted_permissions（None 表示调用方未提供，未知）；
    - serverVerified：恒为 False —— 服务端 scope 未在本地验证。
    """
    status = tool_status(spec, document)
    contract = CONTRACT_AVAILABILITY.get(
        str(status.get("capabilityStatus") or "not_checked"), "not_checked")
    authorized = None if granted_permissions is None else spec.permission in granted_permissions
    return {
        "registered": True,
        "implemented": _implemented_status(spec, deep=deep),
        "contract": contract,
        "runtime": _runtime_status(spec, env),
        "locallyAuthorized": authorized,
        "serverVerified": False,
    }


def _compact_availability(full: dict) -> dict:
    """目录用精简可用性：不含 registered（条目存在即已注册）与 serverVerified
    （由 catalog 顶层统一说明），控制体积。"""
    return {key: full[key] for key in
            ("implemented", "contract", "runtime", "locallyAuthorized")}


def tool_summary(spec: ToolSpec, document, disabled_reason: str = "", *,
                 granted_permissions=None, env=None) -> dict:
    status = tool_status(spec, document)
    implementation_version = _engine_version() if spec.id.startswith("media.organize.") else spec.version
    return {
        "id": spec.id,
        "version": spec.version,
        "implementationVersion": implementation_version,
        "kind": spec.kind,
        "domain": spec.domain,
        "description": spec.description,
        "status": status["status"],
        "reason": status.get("reason", ""),
        "capabilityStatus": status.get("capabilityStatus", "supported"),
        # 惰性加载：summary 只做模块可发现性检查（find_spec），不导入实现模块。
        "availability": availability(spec, document, granted_permissions=granted_permissions,
                                      env=env),
        "checks": status.get("checks", []),
        "permission": spec.permission,
        "permissionDescription": PERMISSIONS.get(spec.permission, ""),
        "readOnly": spec.read_only,
        "sideEffects": spec.side_effects,
        "requiresConfirmation": spec.requires_confirmation,
        "confirmationMode": spec.confirmation_mode,
        "timeoutSeconds": spec.timeout_seconds,
        "network": spec.network,
        "deprecated": bool(spec.deprecated),
        "deprecatedReason": spec.deprecated_reason,
        "replacedBy": spec.replaced_by,
        "runtimeChecks": list(spec.runtime_checks),
        "inputSchema": spec.input_schema,
        "outputSchema": spec.output_schema,
        "errorCodes": list(COMMON_ERROR_CODES) + list(spec.error_codes),
        "enabled": not disabled_reason,
        "disabledReason": disabled_reason,
    }


def compact_summary(spec: ToolSpec, document, disabled_reason: str = "", *,
                    granted_permissions=None, env=None) -> dict:
    """精简目录条目：不含输入/输出 Schema、错误码全表与运行检查，控制模型上下文。"""
    status = tool_status(spec, document)
    return {
        "id": spec.id,
        "domain": spec.domain,
        "version": spec.version,
        "summary": spec.description,
        "kind": spec.kind,
        "status": status["status"],
        "reason": status.get("reason", ""),
        "availability": _compact_availability(
            availability(spec, document, granted_permissions=granted_permissions, env=env)),
        "permission": spec.permission,
        "readOnly": spec.read_only,
        "sideEffects": spec.side_effects,
        "requiresConfirmation": spec.requires_confirmation,
        "enabled": not disabled_reason,
        "disabledReason": disabled_reason,
        "deprecated": bool(spec.deprecated),
    }


def describe_spec(spec: ToolSpec, document, disabled_reason: str = "", rules: dict | None = None,
                  rules_excerpt_chars: int = 4000, *, granted_permissions=None,
                  env=None) -> dict:
    """单个工具的按需完整描述（含 Schema、示例、规则摘录与 Go API 依赖）。"""
    summary = tool_summary(spec, document, disabled_reason,
                           granted_permissions=granted_permissions, env=env)
    # 按需 describe 才做完整解析（会导入实现模块以确认属性存在）。
    summary["availability"] = availability(spec, document,
                                           granted_permissions=granted_permissions,
                                           env=env, deep=True)
    refs = TOOL_RULES_REFS.get(spec.id, ())
    excerpts = {}
    missing_sections = []
    if rules is not None:
        for section in refs:
            value = rules.get(section)
            if value is None:
                missing_sections.append(section)
                continue
            encoded = _bounded_json(value, rules_excerpt_chars)
            excerpts[section] = encoded
    requirements = []
    for requirement in spec.requirements:
        requirements.append({
            "path": requirement.path, "method": requirement.method,
            "requestSchema": requirement.request_schema,
            "requiredBodyFields": list(requirement.required_body_fields or ()),
            "parameters": list(requirement.parameters or ()),
            "requiredParameters": list(requirement.required_parameters or ()),
        })
    summary.update({
        "examples": [dict(example) for example in TOOL_EXAMPLES.get(spec.id, ())],
        "rulesRefs": list(refs),
        "rulesExcerpt": excerpts,
        "missingRuleSections": missing_sections,
        "goApiRequirements": requirements,
        "handlerRef": spec.handler_ref,
        "implementationAvailable": handler_available(spec),
    })
    return summary


def _bounded_json(value, limit: int) -> object:
    """规则摘录：超长时返回带 truncated 标记的字符串块，不整包塞入上下文。"""
    import json as _json
    try:
        encoded = _json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        return {"truncated": True, "reason": "规则节无法序列化"}
    if len(encoded) <= limit:
        return value
    return {"truncated": True, "chars": limit, "preview": encoded[:limit]}
