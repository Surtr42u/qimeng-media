"""规则包生成与读取（分离版本 + 按工具能力核对）。

规则包 = 手写领域规则（rules.json）+ 当前 OpenAPI 的分块解析结果 + 工具注册表
状态。本模块不联网、不读取凭据、不引入 YAML 依赖。

版本语义（ADR-0056 §3）：
- platformVersion / toolContractVersion 由代码常量（versions.py）定义，
  rules.json 内的副本必须与之一致，否则拒绝生成（防规则源/代码漂移）；
- rulepackVersion 随 rules.json 变化递增；
- openapiSha256 只做来源/构建追踪，不单独决定工具失效；
- 每个工具按声明的端点/方法/必需字段独立核对，结果写入 pack["tools"]。
"""
from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path

from . import openapi, registry, schema, versions

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RULES_PATH = Path(__file__).with_name("rules.json")
DEFAULT_OPENAPI_PATH = REPO_ROOT / "api" / "openapi.yaml"

REQUIRED_RULE_KEYS = (
    "schemaVersion",
    "rulepackVersion",
    "platform",
    "toolConfig",
    "guide",
    "capabilities",
    "fields",
    "authorActions",
    "tags",
    "backup",
    "errors",
    "workflows",
    "permissions",
    "mediaNaming",
    "mediaOrganize",
)
REQUIRED_PACK_KEYS = (
    "schemaVersion",
    "rulesVersion",
    "rulepackVersion",
    "platformVersion",
    "toolContractVersion",
    "openapiSha256",
    "paths",
    "capabilities",
    "fields",
    "authorActions",
    "tags",
    "backup",
    "errors",
    "workflows",
    "permissions",
    "tools",
    "mediaNaming",
    "mediaOrganize",
)


class RulepackError(Exception):
    """规则包无法生成或读取；消息只含固定类别与路径，不含凭据。"""


def _load_json(path: Path) -> dict:
    try:
        raw = path.read_bytes()
    except OSError:
        raise RulepackError(f"无法读取 JSON 文件：{path}") from None
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeError, ValueError):
        raise RulepackError(f"JSON 文件不是合法 UTF-8 JSON：{path}") from None
    if not isinstance(data, dict):
        raise RulepackError(f"JSON 顶层必须是对象：{path}")
    return data


def load_rules(rules_path: str | Path | None = None) -> dict:
    """读取手写规则源并校验必需键与版本一致性。"""
    path = Path(rules_path) if rules_path is not None else DEFAULT_RULES_PATH
    rules = _load_json(path)
    if "rulepackVersion" not in rules and isinstance(rules.get("rulesVersion"), str):
        rules["rulepackVersion"] = rules["rulesVersion"]
    missing = [key for key in REQUIRED_RULE_KEYS if key not in rules]
    if missing:
        raise RulepackError("rules.json 缺少必需键：" + ", ".join(missing))
    if type(rules["schemaVersion"]) is not int:
        raise RulepackError("rules.json 的 schemaVersion 必须是整数")
    if rules["schemaVersion"] != versions.RULEPACK_SCHEMA_VERSION:
        raise RulepackError(
            f"rules.json schemaVersion={rules['schemaVersion']} 与工具包支持的 "
            f"{versions.RULEPACK_SCHEMA_VERSION} 不一致")
    if not isinstance(rules["rulepackVersion"], str) or not rules["rulepackVersion"].strip():
        raise RulepackError("rules.json 的 rulepackVersion 必须是非空字符串")
    platform = rules.get("platform")
    if not isinstance(platform, dict):
        raise RulepackError("rules.json 的 platform 必须是对象")
    checks = (
        ("platformVersion", versions.PLATFORM_VERSION),
        ("toolContractVersion", versions.TOOL_CONTRACT_VERSION),
    )
    for key, expected in checks:
        if platform.get(key) != expected:
            raise RulepackError(
                f"rules.json platform.{key}={platform.get(key)!r} 与工具包 {expected} 不一致；"
                "规则源与代码版本必须同源更新")
    tool_config = rules.get("toolConfig")
    if not isinstance(tool_config, dict) or not isinstance(tool_config.get("disabled", []), list):
        raise RulepackError("rules.json 的 toolConfig.disabled 必须是数组")
    if not isinstance(tool_config.get("readOnlyMode", False), bool):
        raise RulepackError("rules.json 的 toolConfig.readOnlyMode 必须是布尔值")
    if not isinstance(rules.get("mediaNaming"), dict):
        raise RulepackError("rules.json 的 mediaNaming 必须是对象")
    return rules


def read_openapi(openapi_path: str | Path | None = None) -> bytes:
    """读取 OpenAPI 原始字节；指纹必须基于字节，而不是格式化后的文本。"""
    path = Path(openapi_path) if openapi_path is not None else DEFAULT_OPENAPI_PATH
    try:
        return path.read_bytes()
    except OSError:
        raise RulepackError(f"无法读取 OpenAPI 文件：{path}") from None


def _parse_openapi(raw: bytes):
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        raise RulepackError("OpenAPI 文件不是合法 UTF-8") from None
    try:
        document = openapi.parse(text)
    except openapi.OpenAPIParseError as exc:
        raise RulepackError(str(exc)) from None
    return document, text


def extract_paths(openapi_text: str) -> list[str]:
    """从 OpenAPI 文本提取当前 /api/v1 路径（分块解析，仅用于兼容旧调用）。"""
    try:
        document = openapi.parse(openapi_text)
    except openapi.OpenAPIParseError as exc:
        raise RulepackError(str(exc)) from None
    paths = document.paths()
    if not paths:
        raise RulepackError("OpenAPI 文本中没有找到 /api/v1 路径")
    return paths


extract_api_paths = extract_paths


def _endpoint_methods(value) -> tuple[list[str], str | None]:
    """把 "GET/PUT /api/v1/x" 或 "DELETE /api/v1/y" 解析为 (methods, path)。"""
    if not isinstance(value, str):
        return [], None
    text = value.strip()
    if not text:
        return [], None
    parts = text.split(None, 1)
    if len(parts) != 2:
        return [], None
    methods, path = parts
    parsed_methods = [item.upper() for item in methods.split("/") if item.strip()]
    return parsed_methods, path.strip()


def _requirement_from_mapping(item: dict, fallback: dict | None = None) -> openapi.ApiRequirement | None:
    """把一个 endpoint 声明（字符串或对象）转换为 ApiRequirement。"""
    fallback = fallback or {}
    method = item.get("method") or fallback.get("method")
    path = item.get("path") or fallback.get("path")
    if not isinstance(method, str) or not isinstance(path, str) or not method.strip() or not path.strip():
        return None
    fields = item.get("requiredFields", fallback.get("requiredFields"))
    required_fields = tuple(fields) if isinstance(fields, list) else ()
    schema_name = item.get("requestSchema", fallback.get("requestSchema"))
    schema_name = schema_name if isinstance(schema_name, str) else None
    parameters = item.get("parameters", fallback.get("parameters"))
    required_parameters = item.get("requiredParameters", fallback.get("requiredParameters"))
    body_required = item.get("bodyRequired", fallback.get("bodyRequired"))
    if body_required is None and (schema_name or required_fields):
        body_required = True
    return openapi.ApiRequirement(
        path=path.strip(),
        method=method.strip().upper(),
        request_schema=schema_name,
        required_body_fields=required_fields,
        body_required=body_required if isinstance(body_required, bool) else None,
        parameters=tuple(parameters) if isinstance(parameters, list) else (),
        required_parameters=(tuple(required_parameters)
                             if isinstance(required_parameters, list) else ()),
    )


def _requirements_from_entry(entry: dict):
    """解析能力的端点声明；返回 (requirements, malformed_code)。

    兼容两种写法：
    - ``endpoint: "GET/PUT /api/v1/..."``（旧写法，方法从字符串解析）；
    - ``endpoints: [{method, path, requestSchema, requiredFields, parameters}, ...]``
      （新写法，适合一个能力覆盖多个路径/方法，并逐端点声明必需字段）。
    路径存在不等于可用：缺方法、缺字段或方法被删除都会在核对中降级。
    """
    requirements: list[openapi.ApiRequirement] = []
    malformed = ""
    endpoint = entry.get("endpoint")
    endpoints = entry.get("endpoints")
    # endpoints 存在时以逐端点声明为准；endpoint 只保留为人类可读摘要。
    if endpoint is not None and endpoints is None:
        methods, path = _endpoint_methods(endpoint)
        if not path or not methods:
            malformed = "endpoint_invalid"
        else:
            for method in methods:
                requirement = _requirement_from_mapping({"method": method, "path": path}, entry)
                if requirement is None:
                    malformed = malformed or "endpoint_invalid"
                else:
                    requirements.append(requirement)
    if endpoints is not None:
        if not isinstance(endpoints, list) or not endpoints:
            malformed = malformed or "endpoints_invalid"
        else:
            for item in endpoints:
                if not isinstance(item, dict):
                    malformed = malformed or "endpoints_invalid"
                    continue
                requirement = _requirement_from_mapping(item, entry)
                if requirement is None:
                    malformed = malformed or "endpoint_invalid"
                else:
                    requirements.append(requirement)
    return requirements, malformed


def _api_status(capability_status: str) -> str:
    return "available" if capability_status == "supported" else capability_status


def _implementation_status(entry: dict) -> str:
    value = entry.get("implementationStatus") or entry.get("status") or "unspecified"
    if value in ("supported", "implemented"):
        return "implemented"
    if value in ("planned", "api-only", "unavailable", "incompatible"):
        return value
    return str(value)


def _check_entry(entry: dict, document) -> dict:
    """按端点/方法/请求字段核对一个能力/动作条目，返回状态补丁。

    输出拆成两个维度：
    - ``implementationStatus``：AI 工具层是否实现（implemented/planned/api-only）；
    - ``apiStatus``：当前 OpenAPI 端点能力是否可用（available/unavailable/
      incompatible/not_checked）。
    ``status`` 保留为兼容字段（combined），不再用单个 planned 混表。
    """
    requirements, malformed = _requirements_from_entry(entry)
    implementation = _implementation_status(entry)
    patch: dict = {"implementationStatus": implementation}
    if malformed:
        patch.update({
            "capabilityStatus": "incompatible",
            "apiStatus": "incompatible",
            "apiStatusReason": malformed,
            "endpointPresent": False,
            "methodsPresent": False,
            "statusReason": malformed,
        })
        if implementation in ("implemented", "api-only"):
            patch["status"] = "incompatible"
        return patch
    if not requirements:
        patch["capabilityStatus"] = "not_checked"
        patch["apiStatus"] = "not_checked"
        return patch
    checks = openapi.check_requirements(document, requirements)
    overall = openapi.overall_status(checks)
    endpoint_present = all(check.code != "endpoint_not_in_openapi" for check in checks)
    patch.update({
        "endpointPresent": endpoint_present,
        "methodsPresent": endpoint_present and all(
            check.code != "method_not_in_openapi" for check in checks),
        "capabilityStatus": overall,
        "apiStatus": _api_status(overall),
        "checks": [check.to_dict() for check in checks],
    })
    if overall != "supported":
        patch["apiStatusReason"] = next(
            (check.code for check in checks if check.status == overall),
            "capability_incompatible")
        if implementation in ("implemented", "api-only"):
            patch["status"] = overall
            patch["statusReason"] = patch["apiStatusReason"]
    return patch


def _tool_implementation_status(spec, status: dict) -> str:
    if spec.planned or status.get("status") == "planned":
        return "planned"
    if spec.handler_ref and not registry.handler_module_available(spec):
        return "missing"
    return "implemented"


def _apply_tool_status(entry: dict, tool_id: str, document) -> dict:
    """用注册表工具状态核对能力条目：实现状态与最近一次 API 可用性分开。"""
    spec = registry.get(tool_id)
    if spec is None:
        return _check_entry(entry, document)
    status = registry.tool_status(spec, document)
    checks = status.get("checks", [])
    capability_status = status.get("capabilityStatus", "not_checked")
    implementation = _tool_implementation_status(spec, status)
    patch = {
        "toolId": tool_id,
        "toolStatus": status["status"],
        "implementationStatus": implementation,
        "capabilityStatus": capability_status,
        "apiStatus": _api_status(capability_status),
        "checks": checks,
        "endpointPresent": not any(check.get("code") == "endpoint_not_in_openapi"
                                   for check in checks),
        "methodsPresent": not any(check.get("code") == "method_not_in_openapi"
                                  for check in checks),
    }
    if status["status"] == "planned":
        if entry.get("status") not in ("planned", "api-only"):
            patch["status"] = "planned"
        if status.get("reason"):
            patch["statusReason"] = status.get("reason", "")
        return patch
    patch["status"] = status["status"]
    if status["status"] != "supported":
        patch["statusReason"] = status.get("reason", "")
        patch["apiStatusReason"] = status.get("reason", "")
    return patch


def _reconcile_rules(rules: dict, document) -> tuple[dict, dict, dict]:
    capabilities = copy.deepcopy(rules.get("capabilities", {}))
    if isinstance(capabilities, dict):
        for group in ("read", "write"):
            entries = capabilities.get(group)
            if not isinstance(entries, dict):
                continue
            for name, entry in entries.items():
                if not isinstance(entry, dict):
                    continue
                if registry.get(name) is not None:
                    entry.update(_apply_tool_status(entry, name, document))
                else:
                    entry.update(_check_entry(entry, document))
    actions = copy.deepcopy(rules.get("authorActions", {}))
    if isinstance(actions, dict):
        for name, action in actions.items():
            if not isinstance(action, dict):
                continue
            if name == "merge" and registry.get("author.merge") is not None:
                status = registry.tool_status(registry.get("author.merge"), document)
                capability_status = status.get("capabilityStatus", "not_checked")
                action.update({
                    "implementationStatus": _implementation_status(action),
                    "apiStatus": _api_status(capability_status),
                    "endpointPresent": not any(
                        check.get("code") == "endpoint_not_in_openapi"
                        for check in status.get("checks", [])),
                    "methodsPresent": not any(
                        check.get("code") == "method_not_in_openapi"
                        for check in status.get("checks", [])),
                    "checks": status.get("checks", []),
                })
                continue
            action.update(_check_entry(action, document))
    fields = copy.deepcopy(rules.get("fields", {}))
    profile = fields.get("authorProfile") if isinstance(fields, dict) else None
    if isinstance(profile, dict):
        methods, path = _endpoint_methods(profile.get("endpoint"))
        profile_required = profile.get("requiredForPut") if isinstance(profile.get("requiredForPut"), list) else []
        put_schema = profile.get("putRequestSchema")
        requirements = []
        for method in methods or []:
            build_put = method == "PUT"
            requirements.append(openapi.ApiRequirement(
                path=path,
                method=method,
                request_schema=put_schema if build_put and isinstance(put_schema, str) else None,
                required_body_fields=tuple(profile_required) if build_put else (),
                body_required=True if build_put else None,
            ))
        if requirements:
            checks = openapi.check_requirements(document, requirements)
            overall = openapi.overall_status(checks)
            profile["endpointPresent"] = all(
                check.code != "endpoint_not_in_openapi" for check in checks)
            profile["methodsPresent"] = all(
                check.code != "method_not_in_openapi" for check in checks)
            profile["capabilityStatus"] = overall
            profile["apiStatus"] = _api_status(overall)
            profile["checks"] = [check.to_dict() for check in checks]
    return capabilities, actions, fields


def build_rulepack(openapi_path: str | Path | None = None,
                   rules_path: str | Path | None = None) -> dict:
    """生成规则包；相同输入字节得到逐字节相同的输出（不含生成时刻）。"""
    rules = load_rules(rules_path)
    raw = read_openapi(openapi_path)
    document, _text = _parse_openapi(raw)
    paths = document.paths()
    capabilities, actions, fields = _reconcile_rules(rules, document)
    tools = [registry.tool_summary(spec, document) for spec in registry.TOOLS]
    sha = hashlib.sha256(raw).hexdigest()
    pack = {
        "schemaVersion": rules["schemaVersion"],
        "rulesVersion": rules["rulepackVersion"],
        "rulepackVersion": rules["rulepackVersion"],
        "platformVersion": versions.PLATFORM_VERSION,
        "toolContractVersion": versions.TOOL_CONTRACT_VERSION,
        "openapiSha256": sha,
        "openapi": {
            "pathCount": len(paths),
            "methods": {path: sorted(document.operations[path]) for path in paths},
        },
        "paths": paths,
        "tools": tools,
        "toolConfig": copy.deepcopy(rules["toolConfig"]),
        "mediaNaming": copy.deepcopy(rules["mediaNaming"]),
        "mediaOrganize": copy.deepcopy(rules["mediaOrganize"]),
        "compatibility": {
            "platformVersion": versions.PLATFORM_VERSION,
            "toolContractVersion": versions.TOOL_CONTRACT_VERSION,
            "rulepackVersion": rules["rulepackVersion"],
            "openapiSha256": sha,
            "note": "openapiSha256 仅追踪来源；每个工具按自身端点/方法/字段独立核对。",
        },
        "capabilities": capabilities,
        "fields": fields,
        "authorActions": actions,
        "tags": copy.deepcopy(rules["tags"]),
        "backup": copy.deepcopy(rules["backup"]),
        "errors": copy.deepcopy(rules["errors"]),
        "workflows": copy.deepcopy(rules["workflows"]),
        "permissions": copy.deepcopy(rules["permissions"]),
        "guide": copy.deepcopy(rules["guide"]),
    }
    return pack


generate_rulepack = build_rulepack


def read_rulepack(path: str | Path) -> dict:
    """读取已生成的规则包并校验必需键；用于交接和离线检查。"""
    data = _load_json(Path(path))
    missing = [key for key in REQUIRED_PACK_KEYS if key not in data]
    if missing:
        raise RulepackError("规则包缺少必需键：" + ", ".join(missing))
    return data


def dumps_pack(pack: dict) -> str:
    """稳定序列化：排序键、UTF-8、末尾换行，便于比较指纹与差异。"""
    return json.dumps(pack, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def write_pack(pack: dict, path: str | Path) -> Path:
    """原子写入规则包；父目录不存在时创建。"""
    target = Path(path)
    temporary = target.with_name(target.name + ".tmp")
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary.write_text(dumps_pack(pack), encoding="utf-8")
        os.replace(temporary, target)
    except OSError:
        try:
            temporary.unlink()
        except OSError:
            pass
        raise RulepackError(f"无法写入规则包输出：{target}") from None
    return target


def consistency_problems(rules: dict, document=None) -> list[dict]:
    """只读一致性检查：规则引用、示例、playbook、recipe、权限与版本同源。

    返回稳定的 ``{code, path, detail, severity}`` 列表（按 code/path 排序）：
    - severity=error：规则源/注册表/示例/引用/版本不一致，必须修复；
    - severity=warning：可继续但建议核对（如原语权限表缺少条目）；
    - severity=info：依赖尚不存在而跳过（如 recipes.PRIMITIVE_SPECS 未落地）。

    纯函数：只读 rules 与注册表；recipe 读取使用函数内惰性 import，避免
    rulepack ↔ recipes ↔ execution 循环导入。不新建第二套规则源。
    """
    problems: list[dict] = []

    def add(code: str, path: str, detail, severity: str = "error") -> None:
        problems.append({"code": code, "path": path, "detail": detail,
                         "severity": severity})

    if not isinstance(rules, dict):
        add("RULES_INVALID", "rules", "规则源必须是对象")
        return problems

    # 1) 工具 → 规则节引用
    for tool_id, sections in registry.TOOL_RULES_REFS.items():
        if registry.get(tool_id) is None:
            add("RULE_REF_UNKNOWN_TOOL", f"TOOL_RULES_REFS.{tool_id}", "引用了未注册工具")
        for section in sections:
            if section not in rules:
                add("RULE_REF_UNKNOWN_SECTION", f"TOOL_RULES_REFS.{tool_id}.{section}",
                    "规则引用节在 rules.json 中不存在")

    # 2) 工具示例必须通过输入 Schema
    for tool_id, examples in registry.TOOL_EXAMPLES.items():
        spec = registry.get(tool_id)
        if spec is None:
            add("EXAMPLE_UNKNOWN_TOOL", f"TOOL_EXAMPLES.{tool_id}", "示例引用了未注册工具")
            continue
        for index, example in enumerate(examples):
            found = schema.validate(example, spec.input_schema)
            if found:
                add("EXAMPLE_SCHEMA_MISMATCH", f"TOOL_EXAMPLES.{tool_id}[{index}]",
                    {"errors": found})

    # 3) playbook 的 baseTools / ruleSections
    playbooks = rules.get("playbooks")
    if playbooks is not None and not isinstance(playbooks, dict):
        add("PLAYBOOK_INVALID", "playbooks", "playbooks 必须是对象")
    elif isinstance(playbooks, dict):
        for name, playbook in playbooks.items():
            path = f"playbooks.{name}"
            if not isinstance(playbook, dict):
                add("PLAYBOOK_INVALID", path, "playbook 必须是对象")
                continue
            base_tools = playbook.get("baseTools")
            if not isinstance(base_tools, list) or not base_tools:
                add("PLAYBOOK_INVALID", path + ".baseTools", "baseTools 必须是非空数组")
            else:
                for tool_id in base_tools:
                    spec = registry.get(tool_id) if isinstance(tool_id, str) else None
                    if spec is None:
                        add("PLAYBOOK_UNKNOWN_TOOL", path + ".baseTools", {"tool": tool_id})
                        continue
                    if spec.deprecated:
                        add("PLAYBOOK_DEPRECATED_TOOL", path + ".baseTools",
                            {"tool": tool_id, "replacedBy": spec.replaced_by})
                    status = registry.tool_status(spec, document)
                    if spec.planned or status.get("status") == "planned":
                        add("PLAYBOOK_PLANNED_TOOL", path + ".baseTools",
                            {"tool": tool_id, "reason": status.get("reason", "")})
            rule_sections = playbook.get("ruleSections")
            if rule_sections is None:
                continue
            if not isinstance(rule_sections, list):
                add("PLAYBOOK_INVALID", path + ".ruleSections", "ruleSections 必须是数组")
                continue
            for section in rule_sections:
                if section not in rules:
                    add("PLAYBOOK_UNKNOWN_SECTION", path + ".ruleSections",
                        {"section": section})

    # 4) 领域 → 默认规则节
    for domain, sections in registry.DOMAIN_RULE_SECTIONS.items():
        for section in sections:
            if section not in rules:
                add("DOMAIN_RULE_SECTION_MISSING",
                    f"DOMAIN_RULE_SECTIONS.{domain}", {"section": section})

    # 5) recipe：工具/原语步骤已注册且权限被 recipe.permissions 覆盖
    try:
        from . import recipes as recipes_module  # 惰性导入，避免循环依赖
    except Exception as exc:  # noqa: BLE001 - 依赖缺失按 info 跳过，不掩盖其他问题
        add("RECIPE_MODULE_UNAVAILABLE", "recipes", type(exc).__name__, "info")
        recipes_module = None
    if recipes_module is not None:
        primitive_specs = getattr(recipes_module, "PRIMITIVE_SPECS", None)
        if primitive_specs is None:
            add("PRIMITIVE_SPECS_MISSING", "recipes.PRIMITIVE_SPECS",
                "原语权限声明表尚未落地：跳过原语权限覆盖检查", "info")
        try:
            listed = recipes_module.list_recipes(None)
        except Exception as exc:  # noqa: BLE001
            add("RECIPE_LIST_FAILED", "recipes", type(exc).__name__)
            listed = []
        for item in listed:
            recipe_id = item.get("id") if isinstance(item, dict) else None
            if not isinstance(recipe_id, str) or not recipe_id:
                add("RECIPE_INVALID", "recipes", {"item": item})
                continue
            path = f"recipes.{recipe_id}"
            if item.get("status") != "ok":
                add("RECIPE_INVALID", path,
                    item.get("error") or item.get("code") or "recipe 校验失败")
                continue
            try:
                recipe = recipes_module.get_recipe(recipe_id, None)
            except Exception as exc:  # noqa: BLE001
                add("RECIPE_LOAD_FAILED", path, type(exc).__name__)
                continue
            declared = set(recipe.get("permissions") or [])
            for step in recipe.get("steps") or []:
                if not isinstance(step, dict):
                    continue
                step_id = step.get("id")
                step_path = f"{path}.steps.{step_id}"
                if step.get("tool"):
                    spec = registry.get(step["tool"])
                    if spec is None:
                        add("RECIPE_UNKNOWN_TOOL", step_path, {"tool": step["tool"]})
                    elif spec.permission not in declared:
                        add("RECIPE_PERMISSION_NOT_DECLARED", step_path,
                            {"tool": step["tool"], "permission": spec.permission})
                elif step.get("primitive"):
                    primitive_id = step["primitive"]
                    if primitive_id not in recipes_module.PRIMITIVES:
                        add("RECIPE_UNKNOWN_PRIMITIVE", step_path,
                            {"primitive": primitive_id})
                        continue
                    if not isinstance(primitive_specs, dict):
                        continue
                    primitive_spec = primitive_specs.get(primitive_id)
                    if primitive_spec is None:
                        add("PRIMITIVE_SPEC_MISSING", step_path,
                            {"primitive": primitive_id}, "warning")
                        continue
                    if isinstance(primitive_spec, dict):
                        permission = primitive_spec.get("permission")
                    else:
                        permission = getattr(primitive_spec, "permission", None)
                    if permission and permission not in declared:
                        add("RECIPE_PERMISSION_NOT_DECLARED", step_path,
                            {"primitive": primitive_id, "permission": permission})

    # 6) rules.json toolCatalog 中引用的工具 ID（只校验已存在的 tools 条目）
    catalog = rules.get("toolCatalog")
    if catalog is not None and not isinstance(catalog, dict):
        add("TOOLCATALOG_INVALID", "toolCatalog", "toolCatalog 必须是对象")
    elif isinstance(catalog, dict):
        entries = catalog.get("tools")
        if entries is not None and not isinstance(entries, list):
            add("TOOLCATALOG_INVALID", "toolCatalog.tools", "tools 必须是数组")
        elif isinstance(entries, list):
            seen: set[str] = set()
            for index, entry in enumerate(entries):
                path = f"toolCatalog.tools[{index}]"
                if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
                    add("TOOLCATALOG_INVALID", path, "条目必须含字符串 id")
                    continue
                tool_id = entry["id"]
                if tool_id in seen:
                    add("TOOLCATALOG_DUPLICATE", path, {"tool": tool_id})
                seen.add(tool_id)
                spec = registry.get(tool_id)
                if spec is None:
                    add("TOOLCATALOG_UNKNOWN_TOOL", path, {"tool": tool_id})
                    continue
                if entry.get("domain") not in (None, spec.domain):
                    add("TOOLCATALOG_DOMAIN_MISMATCH", path,
                        {"tool": tool_id, "declared": entry.get("domain"),
                         "registered": spec.domain})
                if entry.get("permission") not in (None, spec.permission):
                    add("TOOLCATALOG_PERMISSION_MISMATCH", path,
                        {"tool": tool_id, "declared": entry.get("permission"),
                         "registered": spec.permission})
        domains = catalog.get("domains")
        known_domains = {item["id"] for item in registry.DOMAINS}
        if isinstance(domains, list):
            for index, entry in enumerate(domains):
                domain_id = entry.get("id") if isinstance(entry, dict) else None
                if domain_id not in known_domains:
                    add("TOOLCATALOG_UNKNOWN_DOMAIN", f"toolCatalog.domains[{index}]",
                        {"domain": domain_id})

    # 7) 权限：工具权限都在 PERMISSIONS，规则说明与声明不矛盾
    declared_permissions = rules.get("permissions")
    tool_permissions = (declared_permissions.get("toolPermissions")
                        if isinstance(declared_permissions, dict) else None)
    if not isinstance(tool_permissions, dict):
        add("PERMISSION_TABLE_MISSING", "permissions.toolPermissions",
            "缺少权限说明表")
    else:
        for name in sorted(registry.PERMISSIONS):
            if name not in tool_permissions:
                add("PERMISSION_UNDESCRIBED", f"permissions.toolPermissions.{name}",
                    "工具权限缺少规则说明")
        for name in sorted(tool_permissions):
            if name not in registry.PERMISSIONS:
                add("PERMISSION_UNKNOWN", f"permissions.toolPermissions.{name}",
                    "规则声明了未注册的工具权限")
    for spec in registry.TOOLS:
        path = f"tools.{spec.id}"
        if spec.permission not in registry.PERMISSIONS:
            add("TOOL_PERMISSION_UNKNOWN", path + ".permission",
                {"permission": spec.permission})
        if spec.side_effects and spec.read_only:
            add("TOOL_FLAGS_CONTRADICT", path, "sideEffects 与 readOnly 同时为真")
        if spec.side_effects and spec.permission == "read":
            add("TOOL_PERMISSION_CONTRADICT", path,
                "有副作用工具不能声明 read 权限")

    # 8) 版本一致性
    platform = rules.get("platform") if isinstance(rules.get("platform"), dict) else {}
    for key, expected in (("platformVersion", versions.PLATFORM_VERSION),
                          ("toolContractVersion", versions.TOOL_CONTRACT_VERSION)):
        if platform.get(key) != expected:
            add("VERSION_MISMATCH", f"platform.{key}",
                {"expected": expected, "actual": platform.get(key)})
    if platform.get("planVersion") not in (None, versions.PLAN_VERSION):
        add("VERSION_MISMATCH", "platform.planVersion",
            {"expected": versions.PLAN_VERSION, "actual": platform.get("planVersion")})
    rulepack_version = rules.get("rulepackVersion")
    if not isinstance(rulepack_version, str) or not rulepack_version.strip():
        add("RULEPACK_VERSION_MISSING", "rulepackVersion",
            "rulepackVersion 必须是非空字符串")
    if "rulesVersion" in rules and rules.get("rulesVersion") != rulepack_version:
        add("RULEPACK_VERSION_DRIFT", "rulesVersion",
            {"rulepackVersion": rulepack_version, "rulesVersion": rules.get("rulesVersion")})
    # toolCatalog.version 是目录结构版本（如 1.0.0），与 rulepackVersion 语义不同：
    # 只校验非空，不要求与规则版本相等。
    if isinstance(catalog, dict):
        catalog_version = catalog.get("version")
        if not isinstance(catalog_version, str) or not catalog_version.strip():
            add("TOOLCATALOG_VERSION_INVALID", "toolCatalog.version",
                "目录结构版本必须是非空字符串")

    problems.sort(key=lambda item: (item["code"], item["path"],
                                    json.dumps(item["detail"], ensure_ascii=False,
                                               sort_keys=True)))
    return problems


def render_markdown(pack: dict) -> str:
    """将规则包渲染为简明中文指南（机器可读内容仍以 JSON 为准）。"""
    guide = pack.get("guide", {}) if isinstance(pack.get("guide"), dict) else {}
    lines = [f"# {guide.get('title', '绮梦影库 AI 维护规则包')}", ""]
    if guide.get("summary"):
        lines.extend([guide["summary"], ""])
    lines.extend([
        f"- 平台版本：`{pack.get('platformVersion', '')}`",
        f"- 工具契约版本：`{pack.get('toolContractVersion', '')}`",
        f"- 规则包版本：`{pack.get('rulepackVersion', '')}`",
        f"- OpenAPI SHA-256：`{pack.get('openapiSha256', '')}`",
        f"- OpenAPI /api/v1 路径数：{pack.get('openapi', {}).get('pathCount', len(pack.get('paths', [])))}",
        "",
    ])
    for section in guide.get("sections", []) or []:
        if not isinstance(section, dict):
            continue
        lines.append(f"## {section.get('heading', '')}")
        for point in section.get("points", []) or []:
            lines.append(f"- {point}")
        lines.append("")

    lines.extend(["## CLI 入口", ""])
    for command in (
        "version",
        "tools list [--json]",
        "tool call <toolId> --input-json '<json>' | --input-file <path>",
        "context [--markdown] [--out <path>]",
        "health [--ready]",
        "libraries | assets | authors | author-profile | author-sources",
        "research context | provider | lens",
    ):
        lines.append(f"- `python -X utf8 tools/ai_maintenance/cli.py {command}`")
    lines.extend([
        "",
        "网络工具需要 `QIMENG_API_BASE` 与 `QIMENG_API_TOKEN`；缺少配置退出码 2。",
        "写工具必须先 prepare 生成计划、人工确认后带 confirm=true 与计划指纹调用 apply；"
        "未确认计划不得执行。",
        "",
    ])

    tools = pack.get("tools") if isinstance(pack.get("tools"), list) else []
    if tools:
        lines.extend(["## 工具状态（按当前 OpenAPI 独立核对）", ""])
        for tool in tools:
            status = tool.get("status")
            suffix = ""
            if status != "supported" and tool.get("reason"):
                suffix = "；" + str(tool.get("reason"))
            lines.append(f"- `{tool.get('id')}`：{status}{suffix}")
        lines.append("")

    missing = [name for name, entry in (pack.get("capabilities", {}).get("write") or {}).items()
               if isinstance(entry, dict) and entry.get("status") in ("unavailable", "incompatible")]
    if missing:
        lines.extend(["## 能力降级", ""])
        for name in sorted(missing):
            entry = pack["capabilities"]["write"][name]
            lines.append(f"- `{name}`：{entry.get('status')}（{entry.get('statusReason', '')}）")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"
