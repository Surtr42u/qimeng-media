"""统一工具执行器：禁用策略、Schema 校验、能力核对、确认与 dispatch。

CLI 与 MCP 适配器共用这里的 execute_tool：
- 禁用：rules.json toolConfig.disabled ∪ QIMENG_AI_DISABLED_TOOLS，
  QIMENG_AI_READONLY=1 或 toolConfig.readOnlyMode 关闭全部非只读工具；
- 输入 Schema 校验失败 → INVALID_INPUT（不进入 handler）；
- planned/unavailable/incompatible 工具在发起网络请求前失败；
- 写工具 requires_confirmation 且 mode=always 时，confirm!=true 直接拒绝；
- handler 只收到 ToolContext；远程异常（HTTP/网络）原样抛出，由 CLI/MCP 适配层
  统一格式化为各自的错误结构（legacy CLI 保持既有 {status,error,body,target}）。
"""
from __future__ import annotations

import os
from pathlib import Path

from . import client as http_client
from . import errors, openapi, plans, registry, rulepack, schema, versions, task_controls
from .runtime import ToolContext

TRUE_VALUES = ("1", "true", "yes", "on")
DISABLED_TOOLS_ENV = "QIMENG_AI_DISABLED_TOOLS"
READONLY_ENV = "QIMENG_AI_READONLY"
OPENAPI_ENV = "QIMENG_AI_OPENAPI"
RULES_ENV = "QIMENG_AI_RULES"
GRANTED_PERMISSIONS_ENV = "QIMENG_AI_GRANTED_PERMISSIONS"
def known_permissions() -> frozenset[str]:
    """已声明权限全集；唯一权威是 registry.PERMISSIONS（含本地原语权限）。"""
    return frozenset(registry.PERMISSIONS.keys())


def _primitive_ids() -> frozenset[str]:
    """惰性读取原语 ID，避免 execution ↔ recipes 顶层循环导入。"""
    try:
        from . import recipes as recipes_module
        return frozenset(recipes_module.PRIMITIVE_SPECS)
    except (ImportError, AttributeError):
        return frozenset()


class RuntimeConfig:
    """一次调用使用的规则/OpenAPI/禁用配置快照（运行中不热加载）。"""

    def __init__(self, *, openapi_path: Path, rules_path: Path, rules: dict,
                 disabled: frozenset[str] = frozenset(), read_only_mode: bool = False,
                 granted_permissions: frozenset[str] | None = None):
        self.openapi_path = openapi_path
        self.rules_path = rules_path
        self.rules = rules
        self.disabled = disabled
        self.read_only_mode = read_only_mode
        # 首次绑定只读；额外权限必须来自可信配置，不能来自模型参数。
        self.granted_permissions = (frozenset({"read"})
                                    if granted_permissions is None else granted_permissions)
        self.budget = task_controls.SessionBudget({})
        self._document = None
        self._openapi_sha256 = ""
        self._openapi_paths: list[str] = []

    @property
    def rulepack_version(self) -> str:
        return str(self.rules.get("rulepackVersion") or "")

    @property
    def openapi_sha256(self) -> str:
        if self._document is None:
            self._load_openapi()
        return self._openapi_sha256

    @property
    def openapi_paths(self) -> list[str]:
        if self._document is None:
            self._load_openapi()
        return self._openapi_paths

    def document(self):
        if self._document is None:
            self._load_openapi()
        return self._document

    def _load_openapi(self) -> None:
        try:
            document, raw = openapi.parse_file(self.openapi_path)
        except openapi.OpenAPIParseError as exc:
            raise errors.configuration_error(str(exc)) from None
        import hashlib
        self._document = document
        self._openapi_sha256 = hashlib.sha256(raw).hexdigest()
        self._openapi_paths = document.paths()


def _parse_list(value: str) -> list[str]:
    return [item for item in value.replace(";", ",").replace(" ", ",").split(",") if item]


def load_config(env=None) -> RuntimeConfig:
    env = os.environ if env is None else env
    openapi_path = env.get(OPENAPI_ENV) or str(rulepack.DEFAULT_OPENAPI_PATH)
    rules_path = env.get(RULES_ENV) or str(rulepack.DEFAULT_RULES_PATH)
    rules = rulepack.load_rules(rules_path)
    tool_config = rules.get("toolConfig") if isinstance(rules.get("toolConfig"), dict) else {}
    disabled = set(tool_config.get("disabled") or [])
    if not isinstance(tool_config.get("disabled", []), list):
        raise errors.configuration_error("rules.json toolConfig.disabled 必须是数组")
    for tool_id in _parse_list(env.get(DISABLED_TOOLS_ENV, "")):
        disabled.add(tool_id)
    unknown = sorted(item for item in disabled
                     if registry.get(item) is None and item not in _primitive_ids())
    if unknown:
        raise errors.configuration_error("禁用列表包含未知工具/原语 ID：" + ", ".join(unknown))
    read_only_mode = bool(tool_config.get("readOnlyMode")) or \
        env.get(READONLY_ENV, "").strip().lower() in TRUE_VALUES
    granted = _parse_granted_permissions(env, tool_config)
    from . import model_output
    model_output.validate_output_config(env)
    config = RuntimeConfig(openapi_path=Path(openapi_path), rules_path=Path(rules_path),
                           rules=rules, disabled=frozenset(disabled),
                           read_only_mode=read_only_mode, granted_permissions=granted)
    config.budget = task_controls.SessionBudget(env)
    return config


def _parse_granted_permissions(env, tool_config: dict) -> frozenset[str]:
    """解析可信执行端授予的权限；首次未配置只授予 read。"""
    known = known_permissions()
    raw = env.get(GRANTED_PERMISSIONS_ENV, "").strip()
    configured = tool_config.get("grantedPermissions")
    if raw:
        values = [item.strip() for item in raw.replace(";", ",").split(",") if item.strip()]
    elif isinstance(configured, list):
        values = [str(item).strip() for item in configured if str(item).strip()]
    else:
        return frozenset({"read"})
    if values == ["all"]:
        return known
    unknown = sorted(item for item in values if item not in known)
    if unknown:
        raise errors.configuration_error(
            "授予列表包含未知权限：" + ", ".join(unknown) +
            "（合法值：" + ", ".join(sorted(known)) + "）")
    return frozenset(values)


def _policy_status(spec, config: RuntimeConfig) -> tuple[str, str]:
    """工具与原语共用的拒绝规则；返回 (kind, reason)，kind 为空表示允许。

    spec 只需鸭子类型具备 id/permission/read_only/side_effects；工具与原语
    因此走同一套禁用/只读/权限判断，避免两套规则漂移。
    """
    name = str(getattr(spec, "id", "") or "")
    permission = str(getattr(spec, "permission", "") or "")
    read_only = bool(getattr(spec, "read_only", False))
    side_effects = bool(getattr(spec, "side_effects", False))
    if name in config.disabled:
        return "disabled", "在 toolConfig.disabled / QIMENG_AI_DISABLED_TOOLS 中禁用"
    if config.read_only_mode and (side_effects or not read_only):
        return "readonly", "readOnlyMode 开启：禁止副作用工具/原语"
    if permission not in config.granted_permissions:
        return "permission", permission
    return "", ""


def policy_denial(spec, config: RuntimeConfig) -> str:
    """返回拒绝原因（空串表示允许）；工具与原语共用同一策略。"""
    return _policy_status(spec, config)[1]


def policy_denied_error(spec, config: RuntimeConfig):
    """把 policy_denial 的判定映射成稳定 ToolError；允许时返回 None。"""
    kind, reason = _policy_status(spec, config)
    if not kind:
        return None
    name = str(getattr(spec, "id", "") or "")
    if kind == "permission":
        return errors.permission_denied(name, reason,
                                        sorted(config.granted_permissions))
    return errors.tool_disabled(name, reason)


def permission_reason(spec: registry.ToolSpec, config: RuntimeConfig) -> str:
    """返回未授予权限的说明；空串表示允许执行。权限声明≠有权限，必须执行端强制。"""
    kind, reason = _policy_status(spec, config)
    return reason if kind == "permission" else ""


def disabled_reason(spec: registry.ToolSpec, config: RuntimeConfig) -> str:
    kind, reason = _policy_status(spec, config)
    return reason if kind in ("disabled", "readonly") else ""


def _client_context(spec: registry.ToolSpec, env) -> ToolContext:
    base = env.get("QIMENG_API_BASE", "")
    token = env.get("QIMENG_API_TOKEN", "")
    missing = [name for name, value in (("QIMENG_API_BASE", base), ("QIMENG_API_TOKEN", token))
               if not value]
    if missing:
        raise errors.configuration_error("缺少环境变量：" + ", ".join(missing))
    try:
        validated = http_client.validate_base_url(base)
    except http_client.ConfigError as exc:
        raise errors.configuration_error(str(exc)) from None
    if len(token) > http_client.TOKEN_MAX_LENGTH or any(ord(ch) < 32 for ch in token):
        raise errors.configuration_error("QIMENG_API_TOKEN 非法：含控制字符或过长")
    if token != token.strip():
        raise errors.configuration_error("QIMENG_API_TOKEN 首尾不能有空白")
    client = http_client.ApiClient(validated, token, timeout=spec.timeout_seconds)
    return client


def controlled_action(tool_id, payload, operation, *, env, config, side_effects=False):
    """One lifecycle for registered tools and recipe primitives, including pending writes."""
    request_key = config.budget.reserve(tool_id, payload, side_effects=side_effects)
    started = False

    def mark_started():
        nonlocal started
        started = True

    try:
        result = operation(mark_started)
    except Exception as exc:
        # INVALID_INPUT is the handler's explicit rejection contract, before mutation.
        # Entering a handler alone must not turn a correctable input error into an
        # unresolved write; unexpected/remote failures remain conservative.
        rejected_input = isinstance(exc, errors.ToolError) and exc.code == "INVALID_INPUT"
        uncertain = side_effects and started and not rejected_input
        config.budget.fail(request_key, {"error": getattr(exc, "code", type(exc).__name__),
                                         "status": getattr(exc, "status", None)}, uncertain=uncertain)
        if uncertain:
            exc.operation_may_have_completed = True
        raise
    try:
        config.budget.complete(request_key, result)
    except errors.ToolError as exc:
        if not side_effects or not isinstance(result, dict):
            raise
        result = {**result, "executorWarnings": [exc.to_dict()], "executorStopped": True}
    return result


def execute_tool(tool_id: str, payload, *, env=None, config: RuntimeConfig | None = None,
                 skip_capability: bool = False) -> tuple[registry.ToolSpec, object]:
    """Validate and execute through the same trusted lifecycle as recipe primitives."""
    env = os.environ if env is None else env
    config = config or load_config(env)
    spec = registry.get(tool_id)

    def operation(mark_started):
        _, result = _execute_tool(tool_id, payload, env=env, config=config,
                                  skip_capability=skip_capability, mark_started=mark_started)
        return result

    result = controlled_action(tool_id, payload, operation, env=env, config=config,
                               side_effects=bool(spec and spec.side_effects))
    return spec, result


def _execute_tool(tool_id, payload, *, env, config, skip_capability, mark_started):
    spec = registry.get(tool_id)
    if spec is None:
        raise errors.unknown_tool(tool_id)
    if spec.deprecated:
        raise errors.tool_deprecated(tool_id, spec.deprecated_reason, spec.replaced_by)
    reason = disabled_reason(spec, config)
    if reason:
        raise errors.tool_disabled(tool_id, reason)
    missing_permission = permission_reason(spec, config)
    if missing_permission:
        raise errors.permission_denied(tool_id, missing_permission,
                                       sorted(config.granted_permissions))
    if isinstance(payload, dict) and "plan" in spec.input_schema.get("properties", {}):
        if ("plan" in payload) == ("planRef" in payload):
            raise errors.invalid_input("必须且只能提供 plan 或 planRef 之一")
        if "planRef" in payload:
            from . import model_output
            payload = dict(payload)
            reference = payload.pop("planRef")
            payload["plan"] = model_output.resolve_plan_ref(
                reference, env=env, rulepack_version=config.rulepack_version, tool_id=tool_id)
    problems = schema.validate(payload, spec.input_schema)
    if problems:
        raise errors.invalid_input("工具输入不符合 Schema", details=problems)
    if spec.planned or (callable(spec.dynamic_planned) and spec.dynamic_planned()[0]):
        reason = spec.planned_reason or (spec.dynamic_planned()[0] and spec.dynamic_planned()[1]) or "未实现"
        raise errors.tool_not_implemented(tool_id, reason)
    if spec.requires_confirmation and spec.confirmation_mode == "always" and payload.get("confirm") is not True:
        raise errors.not_confirmed(tool_id)
    task_controls.authorize(spec, payload, env)
    document = None
    if not skip_capability and spec.requirements:
        document = config.document()
        checks = registry.capability_checks(spec, document)
        overall = openapi.overall_status(checks)
        if overall == "unavailable":
            raise errors.capability_unavailable(tool_id, [check.to_dict() for check in checks])
        if overall == "incompatible":
            raise errors.capability_incompatible(tool_id, [check.to_dict() for check in checks])
    elif spec.requirements:
        try:
            document = config.document()
        except errors.ToolError:
            document = None
    client = _client_context(spec, env) if spec.network else None
    try:
        handler = registry.resolve_handler(spec)
    except (ImportError, AttributeError, ValueError):
        raise errors.tool_not_implemented(
            tool_id, "实现模块/函数缺失：" + (spec.handler_ref or "handler")) from None
    if not callable(handler):
        raise errors.tool_not_implemented(tool_id, "实现缺失：" + (spec.handler_ref or "handler"))
    context = ToolContext(
        client=client,
        document=document,
        openapi_sha256=config.openapi_sha256 if document is not None else "",
        rulepack_version=config.rulepack_version,
        target=client.target if client is not None else "",
        env=dict(env),
        rules=config.rules,
        disabled_tools=config.disabled,
        read_only_mode=config.read_only_mode,
        granted_permissions=config.granted_permissions,
        extras={"executorBudget": config.budget.report()},
    )
    mark_started()
    result = handler(context, payload)
    output_problems = schema.validate(result, spec.output_schema)
    if output_problems:
        raise errors.internal_error("工具输出不符合声明的 outputSchema",
                                    details=output_problems)
    return spec, result


def version_report(config: RuntimeConfig | None = None) -> dict:
    from . import versions

    config = config or load_config()
    return {
        "platformVersion": versions.PLATFORM_VERSION,
        "toolContractVersion": versions.TOOL_CONTRACT_VERSION,
        "rulepackVersion": config.rulepack_version,
        "rulepackSchemaVersion": config.rules.get("schemaVersion"),
        "planVersion": versions.PLAN_VERSION,
        "python": ".".join(str(part) for part in __import__("sys").version_info[:3]),
        "openapi": {
            "sha256": config.openapi_sha256,
            "pathCount": len(config.openapi_paths),
        },
    }


def tool_listing(config: RuntimeConfig | None = None) -> dict:
    config = config or load_config()
    document = config.document()
    tools = []
    for spec in registry.TOOLS:
        tools.append(registry.tool_summary(spec, document, disabled_reason(spec, config)))
    return {
        "platformVersion": _platform_version(),
        "toolContractVersion": registry.contracts_version(),
        "rulepackVersion": config.rulepack_version,
        "openapi": {"sha256": config.openapi_sha256, "pathCount": len(config.openapi_paths)},
        "permissions": registry.PERMISSIONS,
        "domains": list(registry.DOMAINS),
        "discovery": {
            "catalog": "platform.catalog",
            "describe": "platform.tool.describe",
            "rules": "platform.rules",
            "note": "工具数量增长时先取 platform.catalog 精简目录，再按需 describe；FULL tools list 仍保留完整 Schema 供 MCP tools/list。",
        },
        "tools": tools,
        "disabledTools": sorted(config.disabled),
        "readOnlyMode": config.read_only_mode,
    }


def _platform_version() -> str:
    return versions.PLATFORM_VERSION
