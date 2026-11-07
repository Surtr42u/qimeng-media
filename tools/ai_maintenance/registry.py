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

from dataclasses import dataclass, field

from . import openapi, versions
from .tools_media import engine_available, engine_version
from .tools_query import (asset_query, author_profile_read, author_query, author_sources_read,
                          health_check, library_query, maintenance_inventory, maintenance_status,
                          project_context, research_context, research_lens,
                          research_provider_query)
from .tools_author import (author_cleanup_apply, author_cleanup_prepare, author_merge,
                           author_profile_prepare, author_profile_update)
from .tools_asset import asset_authors_apply, asset_authors_prepare
from .tools_media import media_organize_apply, media_organize_prepare

TOOL_CONTRACT_VERSION = versions.TOOL_CONTRACT_VERSION
DEFAULT_TIMEOUT_SECONDS = 20.0
LENS_TOOL_TIMEOUT_SECONDS = 150.0

PERMISSIONS = {
    "read": "只读查询：不写业务数据，不需要确认。",
    "research": "研究辅助：只读研究网关，默认服务端关闭。",
    "author.profile.write": "作者资料写入：完整 CreatorProfileWrite + revision，需计划与确认。",
    "author.cleanup": "作者清理：删除/改名/原子合并，高风险，不可撤销动作需确认。",
    "asset.authors.write": "资产作者关联写入：整体替换关联，需计划与确认。",
    "media.organize": "混合媒体整理：按命名计划移动/改名，需计划、确认与回读。",
    "admin.maintenance": "管理员维护：备份/迁移等默认不向 AI 开放的能力。",
}

COMMON_ERROR_CODES = (
    {"code": "UNKNOWN_TOOL", "category": "usage", "exitCode": 2,
     "recovery": "运行 tools list --json 查看已注册工具 ID。"},
    {"code": "TOOL_DISABLED", "category": "permission", "exitCode": 2,
     "recovery": "检查 rules.json toolConfig.disabled 与 QIMENG_AI_DISABLED_TOOLS / QIMENG_AI_READONLY。"},
    {"code": "TOOL_NOT_IMPLEMENTED", "category": "capability", "exitCode": 2,
     "recovery": "该工具状态为 planned：不要假装执行，等待实现或走既有脚本。"},
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
)


@dataclass(frozen=True)
class ToolSpec:
    id: str
    version: str
    description: str
    kind: str
    handler: object
    input_schema: dict
    output_schema: dict
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
        id="project.context", version="1.0.0", kind="context",
        description="返回规则包/版本/能力/工具清单（纯本地，不联网）；可选 markdown 中文指南。",
        handler=project_context, network=False, permission="read",
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
        handler=health_check, permission="read",
        input_schema=_schema({"ready": _boolean("使用 readyz", False)}),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/healthz", "GET"),
                      _req("/api/v1/readyz", "GET")),
    ),
    ToolSpec(
        id="library.query", version="1.0.0", kind="query",
        description="GET /api/v1/libraries 库列表（含 capabilities）。",
        handler=library_query, permission="read",
        input_schema=_schema({}), output_schema=_LIST_OUTPUT,
        requirements=(_req("/api/v1/libraries", "GET"),),
    ),
    ToolSpec(
        id="asset.query", version="1.0.0", kind="query",
        description="GET /api/v1/assets 有界分页查询；page 为按 nextCursor 顺序取页的便利参数。",
        handler=asset_query, permission="read",
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
        id="author.query", version="1.0.0", kind="query",
        description="GET /api/v1/authors；page/size 为客户端切片（端点返回全量数组）。",
        handler=author_query, permission="read",
        input_schema=_schema({
            "page": _integer("页码，从 1 开始", 1),
            "size": _integer("每页数量", 1, 1000),
        }),
        output_schema=_LIST_OUTPUT,
        requirements=(_req("/api/v1/authors", "GET"),),
    ),
    ToolSpec(
        id="author.profile.read", version="1.0.0", kind="query",
        description="GET /api/v1/authors/{authorId}/profile 读取作者公开档案与 revision。",
        handler=author_profile_read, permission="read",
        input_schema=_schema({"authorId": _string("作者 ID", 1, 512)}, required=("authorId",)),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/authors/{authorId}/profile", "GET"),),
    ),
    ToolSpec(
        id="author.sources.read", version="1.0.0", kind="query",
        description="GET /api/v1/authors/{authorId}/sources 读取作者块来源区。",
        handler=author_sources_read, permission="read",
        input_schema=_schema({"authorId": _string("作者 ID", 1, 512)}, required=("authorId",)),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/authors/{authorId}/sources", "GET"),),
    ),
    ToolSpec(
        id="maintenance.inventory", version="1.0.0", kind="query",
        description="只读清点作者/库现状（有界），输出关联数/档案状态/来源数；不做清理结论。",
        handler=maintenance_inventory, permission="read",
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
        handler=maintenance_status, permission="read",
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
        handler=research_context, permission="research",
        input_schema=_schema({}), output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/research/context", "GET"),),
        runtime_checks=("research.enabled（服务端开关，关闭时仍可读 context）",),
    ),
    ToolSpec(
        id="research.provider.query", version="1.0.0", kind="query",
        description="POST /api/v1/research/providers/{provider}/query：平台公开字段查询（矩阵外由服务端 400）。",
        handler=research_provider_query, permission="research",
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
        handler=research_lens, permission="research", timeout_seconds=LENS_TOOL_TIMEOUT_SECONDS,
        input_schema=_schema({"file": _string("本地图片路径（.jpg/.jpeg/.png/.webp，≤20MiB）", 1)},
                             required=("file",)),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/research/lens", "POST", body_required=True),),
        runtime_checks=("research.enabled 与 sidecar 可用性（服务端 503/504 表达）",),
    ),
    ToolSpec(
        id="author.profile.prepare", version="1.0.0", kind="plan",
        description="只读生成作者档案计划：读 revision、人工字段保护、字段校验与影响预览。",
        handler=author_profile_prepare, permission="author.profile.write",
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
        handler=author_profile_update, permission="author.profile.write",
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
        handler=author_cleanup_prepare, permission="author.cleanup",
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
        handler=author_cleanup_apply, permission="author.cleanup",
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
        handler=author_merge, permission="author.cleanup",
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
        handler=asset_authors_prepare, permission="asset.authors.write",
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
        handler=asset_authors_apply, permission="asset.authors.write",
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
        id="media.organize.prepare", version="1.0.0", kind="plan",
        description="组图命名规划：先分组→每组一个作品编号→组内 (1)..(n) 页码；只读生成预览与计划指纹。",
        handler=media_organize_prepare, permission="media.organize",
        input_schema=_schema({
            "libraryId": _string("目标库 ID", 1, 512),
            "directory": _string("库内目标目录（相对路径，空=库根）"),
            "groups": {"type": "array", "description": "候选分组（family/files/confidence/confirmed/evidence/order/existingWorkNumber）",
                       "items": {"type": "object"}},
        }, required=("libraryId", "groups")),
        output_schema=_PLAN_RESULT_OUTPUT,
        requirements=(_req("/api/v1/assets", "GET", parameters=("limit", "cursor")),
                      _req("/api/v1/assets/{assetId}/move", "POST", schema="MoveRequest",
                           fields=("targetDir",), body_required=True)),
        runtime_checks=("media_naming 引擎版本与计划 schema",),
        planned=False, dynamic_planned=lambda: (not engine_available(),
                                                "media_naming.py 命名引擎未安装（待发布物安装）"),
    ),
    ToolSpec(
        id="media.organize.apply", version="1.0.0", kind="write",
        description="按已确认命名计划逐步 POST /assets/{assetId}/move 并回读文件名；自动改名视为失败。",
        handler=media_organize_apply, permission="media.organize",
        read_only=False, side_effects=True, requires_confirmation=True, confirmation_mode="always",
        input_schema=_schema({"plan": _PLAN_REF, "confirm": _boolean("必须显式 true", False)},
                             required=("plan", "confirm")),
        output_schema=_WRITE_RESULT_OUTPUT,
        requirements=(_req("/api/v1/assets", "GET", parameters=("limit", "cursor")),
                      _req("/api/v1/assets/{assetId}/move", "POST", schema="MoveRequest",
                           fields=("targetDir",), body_required=True)),
        runtime_checks=("media_naming 引擎 validate_plan/plan_steps/readback_report",),
        dynamic_planned=lambda: (not engine_available(),
                                 "media_naming.py 命名引擎未安装（待发布物安装）"),
    ),
    ToolSpec(
        id="backup.export", version="1.0.0", kind="admin",
        description="导出 v2 便携备份（计划中）：当前版本未实现保存/摘要/预算流程，不得假装执行。",
        handler=None, permission="admin.maintenance",
        input_schema=_schema({"savePath": _string("保存路径（发布版实现后使用）")}),
        output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/export/qimeng-backup", "GET"),),
        planned=True, planned_reason="导出涉及大响应、落盘摘要与预算，本版本不实现",
    ),
    ToolSpec(
        id="device.apply.prepare", version="1.0.0", kind="admin",
        description="设备结果包登记（计划中）：本轮只有服务端 API，Android Worker/配对未实现。",
        handler=None, permission="admin.maintenance",
        input_schema=_schema({}), output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/device/apply-tasks", "POST", body_required=True),),
        planned=True, planned_reason="Android Worker/配对未实现，不得假装已具备跨端应用",
    ),
    ToolSpec(
        id="device.apply.submit", version="1.0.0", kind="admin",
        description="设备结果包回传（计划中）：手机 Worker 未实现。",
        handler=None, permission="admin.maintenance",
        input_schema=_schema({}), output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/device/apply-tasks/{id}/result", "POST", body_required=True),),
        planned=True, planned_reason="手机 Worker 未实现",
    ),
    ToolSpec(
        id="device.apply.status", version="1.0.0", kind="admin",
        description="设备结果包状态查询（只读 API 已存在，但工具计划中，暂不向 AI 开放）。",
        handler=None, permission="admin.maintenance",
        input_schema=_schema({}), output_schema=_QUERY_OUTPUT,
        requirements=(_req("/api/v1/device/apply-tasks/{id}", "GET"),),
        planned=True, planned_reason="设备身份/配对未实现，暂不对 AI 开放",
    ),
)

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
    checks = capability_checks(spec, document) if document is not None else []
    overall = openapi.overall_status(checks) if checks else "supported"
    return {"status": overall,
            "reason": "" if overall == "supported" else "OpenAPI 能力核对未通过",
            "checks": [check.to_dict() for check in checks],
            "capabilityStatus": overall}


def tool_summary(spec: ToolSpec, document, disabled_reason: str = "") -> dict:
    status = tool_status(spec, document)
    implementation_version = engine_version() if spec.id.startswith("media.organize.") else spec.version
    return {
        "id": spec.id,
        "version": spec.version,
        "implementationVersion": implementation_version,
        "kind": spec.kind,
        "description": spec.description,
        "status": status["status"],
        "reason": status.get("reason", ""),
        "capabilityStatus": status.get("capabilityStatus", "supported"),
        "checks": status.get("checks", []),
        "permission": spec.permission,
        "permissionDescription": PERMISSIONS.get(spec.permission, ""),
        "readOnly": spec.read_only,
        "sideEffects": spec.side_effects,
        "requiresConfirmation": spec.requires_confirmation,
        "confirmationMode": spec.confirmation_mode,
        "timeoutSeconds": spec.timeout_seconds,
        "network": spec.network,
        "runtimeChecks": list(spec.runtime_checks),
        "inputSchema": spec.input_schema,
        "outputSchema": spec.output_schema,
        "errorCodes": list(COMMON_ERROR_CODES) + list(spec.error_codes),
        "enabled": not disabled_reason,
        "disabledReason": disabled_reason,
    }
