"""平台能力目录与按需发现工具实现（platform.catalog / platform.tool.describe / platform.rules / platform.playbook）。

设计（2026-11-07 扩展性补充，ADR-0056）：
- 工具数量持续增长时，外部 AI 先取 **精简目录**（按领域分组，不含 Schema），
  再按需取单个工具的完整 Schema/示例/规则摘录，避免一次性把完整规则包与全部
  工具 Schema 塞进模型上下文；
- 元工具与业务工具共用同一 runtim/registry（CLI 与 MCP 同一 dispatch），只做
  只读投影，不复制任何领域规则，也不改变工具状态或权限；
- 返回内容有界：规则摘录超长时返回 truncated 标记而不是整包 JSON；
- 权限由 execution 层在调用前强制校验，本模块只展示声明，不据此放行。
"""
from __future__ import annotations

import json

from . import errors, registry, versions

# 单个规则节的截断上限（字符）：目录/描述工具面向模型上下文，必须有界。
RULES_EXCERPT_LIMIT = 4000
# 一次 platform.rules 请求的总体上限（字符）与节数上限。
RULES_TOTAL_LIMIT = 16000
MAX_RULE_SECTIONS = 10
# 单个 playbook 的返回上限（字符）：操作提示词/规范也必须有界。
PLAYBOOK_LIMIT = 8000
PLAYBOOK_LIST_LIMIT = 20000
AVAILABLE_DOMAINS = tuple(item["id"] for item in registry.DOMAINS)
CONTEXT_DEFAULT_TOOLS = 12
CONTEXT_MAX_TOOLS = 24
CONTEXT_RULES_LIMIT = 12000
CONTEXT_SCHEMA_DEPTH = 2
CONTEXT_AVAILABLE_TASKS = 40

# 任务别名：让外部 AI 用业务语言（backup.sync 等）找到同一组工具/模板。
TASK_ALIASES: dict = {
    "backup.sync": {"playbook": "device.apply", "extraTools": ("backup.export",)},
    "device.sync": {"playbook": "device.apply"},
    "maintenance.jobs": {"playbook": "maintenance.jobs"},
    "media.batch": {"playbook": "media.organize"},
    "media.organize": {"playbook": "media.organize"},
    "author.cleanup": {"playbook": "author.cleanup"},
    "author.profile": {"playbook": "author.profile"},
}


def _disabled_reason(ctx, tool_id: str) -> str:
    disabled = getattr(ctx, "disabled_tools", frozenset()) or frozenset()
    if tool_id in disabled:
        return "在 toolConfig.disabled / QIMENG_AI_DISABLED_TOOLS 中禁用"
    spec = registry.get(tool_id)
    read_only_mode = bool(getattr(ctx, "read_only_mode", False))
    if spec is not None and read_only_mode and (spec.side_effects or not spec.read_only):
        return "readOnlyMode 开启：禁止副作用工具"
    return ""


def _compact(ctx, spec) -> dict:
    return registry.compact_summary(spec, getattr(ctx, "document", None),
                                    _disabled_reason(ctx, spec.id))


def _bounded(value, limit: int = RULES_EXCERPT_LIMIT):
    try:
        encoded = json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        return {"truncated": True, "reason": "规则节无法序列化"}
    if len(encoded) <= limit:
        return value
    return {"truncated": True, "chars": limit, "preview": encoded[:limit]}


def platform_catalog(ctx, payload: dict) -> dict:
    """精简能力目录：按领域分组的工具元数据（无输入/输出 Schema）。"""
    domain = payload.get("domain")
    if isinstance(domain, str) and domain != "" and domain not in AVAILABLE_DOMAINS:
        raise errors.invalid_input(
            "未知领域：" + domain,
            details={"availableDomains": list(AVAILABLE_DOMAINS)},
        )
    include_deprecated = payload.get("includeDeprecated") is True
    tools = []
    groups: dict[str, list[str]] = {}
    for spec in registry.TOOLS:
        if isinstance(domain, str) and domain != "" and spec.domain != domain:
            continue
        if spec.deprecated and not include_deprecated:
            continue
        item = _compact(ctx, spec)
        tools.append(item)
        groups.setdefault(spec.domain, []).append(spec.id)
    domain_rows = []
    for meta in registry.DOMAINS:
        if isinstance(domain, str) and domain != "" and meta["id"] != domain:
            continue
        ids = groups.get(meta["id"], [])
        domain_rows.append({
            "id": meta["id"],
            "label": meta["label"],
            "description": meta["description"],
            "toolCount": len(ids),
            "tools": ids,
        })
    return {
        "platformVersion": versions.PLATFORM_VERSION,
        "toolContractVersion": versions.TOOL_CONTRACT_VERSION,
        "rulepackVersion": str(getattr(ctx, "rulepack_version", "") or ""),
        "domains": domain_rows,
        "tools": tools,
        "toolCount": len(tools),
        "recipes": [
            {"id": item["id"], "title": item["title"],
             "requires": item.get("requires") or [], "targets": item.get("targets") or []}
            for item in _recipe_map(ctx).values()
        ],
        "discovery": {
            "recipe": "platform.recipe（action=card 取 2–4KB 任务卡）",
            "describeTool": "platform.tool.describe",
            "rules": "platform.rules",
            "playbook": "platform.playbook",
            "note": "目录仅含 id/摘要/状态/权限；单个工具的输入/输出 Schema、示例与规则摘录按需调用 describe。看见工具不等于有权执行。",
        },
        "disabledTools": sorted(getattr(ctx, "disabled_tools", frozenset()) or ()),
        "readOnlyMode": bool(getattr(ctx, "read_only_mode", False)),
    }


def platform_tool_describe(ctx, payload: dict) -> dict:
    """按需取单个工具完整定义：Schema、示例、规则摘录、Go API 依赖与状态。"""
    tool_id = payload.get("toolId")
    if not isinstance(tool_id, str) or not tool_id:
        raise errors.invalid_input("缺少 toolId")
    spec = registry.get(tool_id)
    if spec is None:
        raise errors.unknown_tool(tool_id)
    described = registry.describe_spec(
        spec,
        getattr(ctx, "document", None),
        _disabled_reason(ctx, tool_id),
        rules=getattr(ctx, "rules", None),
    )
    return {
        "tool": described,
        "rulepackVersion": str(getattr(ctx, "rulepack_version", "") or ""),
        "discovery": {
            "catalog": "platform.catalog",
            "rules": "platform.rules",
            "playbook": "platform.playbook",
            "note": "Schema 与规则摘录只用于理解与构造计划；执行仍由 execution 层做权限/能力/确认校验。",
        },
    }


def platform_rules(ctx, payload: dict) -> dict:
    """按需读取规则节（不加载/回显完整 rules.json）。"""
    rules = getattr(ctx, "rules", None)
    if not isinstance(rules, dict):
        raise errors.configuration_error("当前上下文缺少规则源（rules.json 未加载）")
    requested = payload.get("sections")
    domain = payload.get("domain")
    sections: list[str] = []
    if isinstance(requested, list) and requested:
        sections = [item for item in requested if isinstance(item, str) and item]
        if len(sections) > MAX_RULE_SECTIONS:
            raise errors.invalid_input(f"sections 最多 {MAX_RULE_SECTIONS} 项")
    elif isinstance(domain, str) and domain:
        if domain not in AVAILABLE_DOMAINS:
            raise errors.invalid_input("未知领域：" + domain,
                                       details={"availableDomains": list(AVAILABLE_DOMAINS)})
        sections = list(registry.DOMAIN_RULE_SECTIONS.get(domain, ()))
    else:
        sections = ["permissions"]
    unknown = [name for name in sections if name not in rules]
    if unknown:
        raise errors.invalid_input("未知规则节：" + ", ".join(unknown),
                                   details={"availableSections": sorted(rules.keys())})
    selected: dict[str, object] = {}
    used = 0
    truncated: list[str] = []
    for name in sections:
        excerpt = _bounded(rules[name])
        encoded_len = len(json.dumps(excerpt, ensure_ascii=False))
        if used + encoded_len > RULES_TOTAL_LIMIT:
            truncated.append(name)
            continue
        selected[name] = excerpt
        used += encoded_len
    return {
        "rulepackVersion": str(getattr(ctx, "rulepack_version", "") or ""),
        "sections": selected,
        "truncatedSections": truncated,
        "availableSections": sorted(rules.keys()),
        "note": "节选用于按需加载领域规则；完整规则源由可信执行端保管，执行仍以服务端与执行器校验为准。",
    }

def _playbooks(ctx) -> dict:
    rules = getattr(ctx, "rules", None)
    if not isinstance(rules, dict):
        raise errors.configuration_error("当前上下文缺少规则源（rules.json 未加载）")
    section = rules.get("playbooks")
    if not isinstance(section, dict):
        raise errors.invalid_input("当前规则包未提供 playbooks 节",
                                   details={"availableSections": sorted(rules.keys())})
    return section


def _playbook_compact(name: str, playbook: dict) -> dict:
    base_tools = playbook.get("baseTools") if isinstance(playbook.get("baseTools"), list) else []
    steps = playbook.get("steps") if isinstance(playbook.get("steps"), list) else []
    return {
        "name": name,
        "domain": playbook.get("domain", ""),
        "title": playbook.get("title", name),
        "whenToUse": playbook.get("whenToUse", ""),
        "stepCount": len(steps),
        "baseTools": base_tools,
        "risk": playbook.get("risk", "normal"),
    }


def platform_playbook(ctx, payload: dict) -> dict:
    """按需读取操作提示词/业务规范/工作流模板（playbook），不加载完整规则包。

    预置工作流只是可选模板：baseTools 指向基础工具，AI 可自由组合，不强制按步骤执行。
    """
    playbooks = _playbooks(ctx)
    name = payload.get("name")
    domain = payload.get("domain")
    if isinstance(name, str) and name:
        playbook = playbooks.get(name)
        if not isinstance(playbook, dict):
            raise errors.invalid_input("未知 playbook：" + name,
                                       details={"availablePlaybooks": sorted(playbooks.keys())})
        bounded = _bounded(playbook, PLAYBOOK_LIMIT)
        return {
            "rulepackVersion": str(getattr(ctx, "rulepack_version", "") or ""),
            "playbook": {"name": name, **bounded} if isinstance(bounded, dict) else bounded,
            "availablePlaybooks": sorted(playbooks.keys()),
            "note": "playbook 是可选模板；执行仍按基础工具的计划/确认/回读与权限校验。",
        }
    if isinstance(domain, str) and domain:
        if domain not in AVAILABLE_DOMAINS:
            raise errors.invalid_input("未知领域：" + domain,
                                       details={"availableDomains": list(AVAILABLE_DOMAINS)})
    rows = []
    used = 0
    for playbook_name in sorted(playbooks.keys()):
        playbook = playbooks.get(playbook_name)
        if not isinstance(playbook, dict):
            continue
        if isinstance(domain, str) and domain and playbook.get("domain") != domain:
            continue
        row = _playbook_compact(playbook_name, playbook)
        encoded = len(json.dumps(row, ensure_ascii=False))
        if used + encoded > PLAYBOOK_LIST_LIMIT:
            break
        rows.append(row)
        used += encoded
    return {
        "rulepackVersion": str(getattr(ctx, "rulepack_version", "") or ""),
        "playbooks": rows,
        "availablePlaybooks": sorted(playbooks.keys()),
        "note": "按领域列出可选工作流模板；基础工具可自由组合，不要求按模板执行。",
    }


# --------------------------------------------------------------------------- #
# platform.context：按任务/领域加载相关工具、Schema 摘要、规则节与 playbook
# --------------------------------------------------------------------------- #


def _schema_summary(schema, depth: int = CONTEXT_SCHEMA_DEPTH) -> dict:
    """有界 Schema 摘要（属性名/类型/必填/枚举），不返回完整嵌套 Schema。"""
    if not isinstance(schema, dict):
        return {"type": type(schema).__name__}
    summary: dict = {}
    for key in ("type", "enum", "default", "minLength", "maxLength",
                "minimum", "maximum", "minItems", "maxItems", "pattern"):
        if key in schema:
            summary[key] = schema[key]
    if isinstance(schema.get("description"), str):
        summary["description"] = schema["description"][:200]
    required = schema.get("required")
    if isinstance(required, list):
        summary["required"] = required[:30]
    properties = schema.get("properties")
    if isinstance(properties, dict) and depth > 0:
        summary["properties"] = {
            name: _schema_summary(value, depth - 1)
            for name, value in list(properties.items())[:30]
        }
    items = schema.get("items")
    if isinstance(items, dict) and depth > 0:
        summary["items"] = _schema_summary(items, depth - 1)
    if "additionalProperties" in schema:
        summary["additionalProperties"] = schema["additionalProperties"]
    return summary


def _recipe_map(ctx) -> dict:
    try:
        from . import recipes
        env = getattr(ctx, "env", None)
        loaded = {}
        for item in recipes.list_recipes(env):
            if item.get("status") != "ok":
                continue
            try:
                loaded[item["id"]] = recipes.get_recipe(item["id"], env)
            except Exception:
                continue
        return loaded
    except Exception:  # recipe 目录损坏不应让基础上下文不可用
        return {}


def _resolve_context_task(task, domain, playbooks: dict, recipe_map=None):
    """解析 task/domain，返回 (tool_ids, playbook_name, target_domain, matched_by)。"""
    tool_ids: list[str] = []
    playbook_name = ""
    target_domain = ""
    matched_by = ""
    if isinstance(task, str) and task:
        if recipe_map and task in recipe_map:
            matched_by = "recipe"
            recipe = recipe_map[task]
            if isinstance(recipe, dict):
                for step in recipe.get("steps") or []:
                    tool_id = step.get("tool") if isinstance(step, dict) else None
                    if isinstance(tool_id, str) and registry.get(tool_id) is not None \
                            and tool_id not in tool_ids:
                        tool_ids.append(tool_id)
        elif task in playbooks:
            playbook_name = task
            matched_by = "playbook"
        elif task in AVAILABLE_DOMAINS:
            target_domain = task
            matched_by = "domain"
        elif registry.get(task) is not None:
            tool_ids = [task]
            matched_by = "tool"
            for name, playbook in playbooks.items():
                if isinstance(playbook, dict) and task in (playbook.get("baseTools") or []):
                    playbook_name = name
                    break
        else:
            alias = TASK_ALIASES.get(task)
            if isinstance(alias, dict):
                playbook_name = str(alias.get("playbook") or "")
                tool_ids = [str(item) for item in alias.get("extraTools") or ()]
                matched_by = "alias"
            else:
                matches = [spec.id for spec in registry.TOOLS
                           if spec.id == task or spec.id.startswith(task + ".")]
                if matches:
                    tool_ids = matches
                    matched_by = "tool-prefix"
    if playbook_name:
        playbook = playbooks.get(playbook_name)
        if not isinstance(playbook, dict):
            raise errors.invalid_input("未知 playbook：" + playbook_name,
                                       details={"availablePlaybooks": sorted(playbooks.keys())})
        target_domain = str(playbook.get("domain") or "")
        for item in playbook.get("baseTools") or []:
            if isinstance(item, str) and registry.get(item) is not None and item not in tool_ids:
                tool_ids.append(item)
    if isinstance(domain, str) and domain:
        if domain not in AVAILABLE_DOMAINS:
            raise errors.invalid_input("未知领域：" + domain,
                                       details={"availableDomains": list(AVAILABLE_DOMAINS)})
        if target_domain and target_domain != domain:
            raise errors.invalid_input(
                f"task 解析出的领域 {target_domain} 与 domain={domain} 不一致")
        target_domain = domain
        if not tool_ids:
            tool_ids = [spec.id for spec in registry.TOOLS if spec.domain == domain]
    if not tool_ids and not playbook_name and not target_domain:
        raise errors.invalid_input(
            "需要提供 task 或 domain（如 media.organize、author.cleanup、backup.sync）",
            details={
                "availableDomains": list(AVAILABLE_DOMAINS),
                "availablePlaybooks": sorted(playbooks.keys()),
                "aliases": sorted(TASK_ALIASES.keys()),
            },
        )
    if not tool_ids and target_domain:
        tool_ids = [spec.id for spec in registry.TOOLS if spec.domain == target_domain]
    return tool_ids, playbook_name, target_domain, matched_by


def _context_tool(ctx, spec, include_schemas: bool, compact: bool = False) -> dict:
    status = registry.tool_status(spec, getattr(ctx, "document", None))
    if compact:
        return {
            "id": spec.id,
            "version": spec.version,
            "status": status["status"],
            "summary": spec.description,
            "permission": spec.permission,
            "permitted": spec.permission in (getattr(ctx, "granted_permissions", frozenset()) or ()),
            "requiresConfirmation": spec.requires_confirmation,
        }
    item = {
        "id": spec.id,
        "version": spec.version,
        "domain": spec.domain,
        "kind": spec.kind,
        "summary": spec.description,
        "status": status["status"],
        "reason": status.get("reason", ""),
        "permission": spec.permission,
        "permissionDescription": registry.PERMISSIONS.get(spec.permission, ""),
        "permitted": spec.permission in (getattr(ctx, "granted_permissions", frozenset()) or ()),
        "readOnly": spec.read_only,
        "sideEffects": spec.side_effects,
        "requiresConfirmation": spec.requires_confirmation,
        "confirmationMode": spec.confirmation_mode,
        "timeoutSeconds": spec.timeout_seconds,
        "network": spec.network,
        "deprecated": bool(spec.deprecated),
        "disabled": _disabled_reason(ctx, spec.id) != "",
        "examples": [dict(example) for example in registry.TOOL_EXAMPLES.get(spec.id, ())],
        "rulesRefs": list(registry.TOOL_RULES_REFS.get(spec.id, ())),
    }
    if include_schemas:
        item["schemaSummary"] = {
            "input": _schema_summary(spec.input_schema),
            "output": _schema_summary(spec.output_schema),
        }
    item["goApiRequirements"] = [
        {
            "path": requirement.path,
            "method": requirement.method.upper(),
            "requestSchema": requirement.request_schema,
            "requiredBodyFields": list(requirement.required_body_fields or ()),
            "parameters": list(requirement.parameters or ()),
        }
        for requirement in spec.requirements
    ]
    return item


def _context_rule_sections(tool_ids: list[str], playbook: dict | None,
                           target_domain: str, rules: dict) -> list[str]:
    sections: list[str] = []
    if isinstance(playbook, dict) and isinstance(playbook.get("ruleSections"), list):
        sections.extend(item for item in playbook["ruleSections"] if isinstance(item, str))
    for tool_id in tool_ids:
        sections.extend(registry.TOOL_RULES_REFS.get(tool_id, ()))
    if target_domain:
        sections.extend(registry.DOMAIN_RULE_SECTIONS.get(target_domain, ()))
    ordered: list[str] = []
    for name in sections:
        if name in rules and name not in ordered:
            ordered.append(name)
    return ordered[:MAX_RULE_SECTIONS]


def platform_context(ctx, payload: dict) -> dict:
    """按任务/领域返回相关工具、Schema 摘要、规则节、playbook、示例与权限。

    只返回相关子集；与 catalog/describe/rules/playbook 共用同一注册表与执行路径。
    """
    rules = getattr(ctx, "rules", None)
    if not isinstance(rules, dict):
        raise errors.configuration_error("当前上下文缺少规则源（rules.json 未加载）")
    playbooks = rules.get("playbooks") if isinstance(rules.get("playbooks"), dict) else {}
    task = payload.get("task")
    domain = payload.get("domain")
    if task is not None and (not isinstance(task, str) or not task.strip()):
        raise errors.invalid_input("task 必须是非空字符串")
    if task is None and not domain:
        raise errors.invalid_input("需要提供 task 或 domain")
    recipe_map = _recipe_map(ctx)
    tool_ids, playbook_name, target_domain, matched_by = _resolve_context_task(
        task.strip() if isinstance(task, str) else "", domain, playbooks, recipe_map)
    recipe_matched = matched_by == "recipe"
    # recipe 任务卡已含步骤与输入；默认不再叠加工具 Schema/规则节，避免 40KB+ 上下文。
    include_schemas = (payload.get("includeSchemas") is True) if recipe_matched \
        else (payload.get("includeSchemas") is not False)
    include_rules = (payload.get("includeRules") is True) if recipe_matched \
        else (payload.get("includeRules") is not False)
    max_tools = payload.get("maxTools")
    if max_tools is None:
        max_tools = CONTEXT_DEFAULT_TOOLS
    if isinstance(max_tools, bool) or not isinstance(max_tools, int) or \
            not 1 <= max_tools <= CONTEXT_MAX_TOOLS:
        raise errors.invalid_input(f"maxTools 必须在 1..{CONTEXT_MAX_TOOLS}")
    truncated_tools = tool_ids[max_tools:]
    selected_ids = tool_ids[:max_tools]
    tools = []
    for tool_id in selected_ids:
        spec = registry.get(tool_id)
        if spec is None:
            continue
        tools.append(_context_tool(ctx, spec, include_schemas, compact=recipe_matched))
    recipe_card = None
    if matched_by == "recipe" and isinstance(task, str):
        try:
            from . import recipes
            recipe_card = recipes.task_card(task, getattr(ctx, "env", None))
        except Exception:
            recipe_card = None
    playbook = playbooks.get(playbook_name) if playbook_name else None
    bounded_playbook = None
    if isinstance(playbook, dict):
        bounded = _bounded(playbook, PLAYBOOK_LIMIT)
        bounded_playbook = {"name": playbook_name, **bounded} if isinstance(bounded, dict) else bounded
    sections = _context_rule_sections(selected_ids, playbook if isinstance(playbook, dict) else None,
                                      target_domain, rules)
    if not include_rules:
        sections = []
    selected_rules: dict = {}
    used = 0
    truncated_sections: list[str] = []
    for name in sections:
        excerpt = _bounded(rules[name])
        size = len(json.dumps(excerpt, ensure_ascii=False))
        if used + size > CONTEXT_RULES_LIMIT:
            truncated_sections.append(name)
            continue
        selected_rules[name] = excerpt
        used += size
    permission_names = sorted({item["permission"] for item in tools})
    guardrails = []
    if isinstance(playbook, dict):
        guardrails.extend(str(item) for item in playbook.get("guardrails") or ())
    guardrails.extend([
        "看见工具不等于有权执行：permitted=false 的工具必须由可信执行端先授权。",
        "写入工具必须先 prepare、人工确认后带 confirm=true 与计划指纹执行，并回读。",
        "不得把真实媒体、令牌或含个人信息的任务记录发送到外部模型上下文。",
    ])
    available_tasks = sorted(playbooks.keys())[:CONTEXT_AVAILABLE_TASKS]
    return {
        "platformVersion": versions.PLATFORM_VERSION,
        "toolContractVersion": versions.TOOL_CONTRACT_VERSION,
        "rulepackVersion": str(getattr(ctx, "rulepack_version", "") or ""),
        "task": {
            "input": task if isinstance(task, str) and task else None,
            "domain": target_domain or None,
            "matchedBy": matched_by,
            "playbook": playbook_name or None,
            "toolCount": len(tools),
            "requestedToolCount": len(tool_ids),
            "truncatedTools": truncated_tools[:CONTEXT_MAX_TOOLS],
        },
        "recipe": recipe_card,
        "playbook": bounded_playbook,
        "tools": tools,
        "permissions": {
            "declared": permission_names,
            "granted": sorted(getattr(ctx, "granted_permissions", frozenset()) or ()),
            "note": "permitted 只反映可信执行端授予；服务端仍按 AI 客户端 scope 强制校验。",
        },
        "rules": selected_rules,
        "truncatedSections": truncated_sections,
        "guardrails": guardrails,
        "next": [item["id"] for item in tools if item.get("status") == "supported"][:max_tools],
        "discovery": {
            "catalog": "platform.catalog",
            "describe": "platform.tool.describe",
            "rules": "platform.rules",
            "playbook": "platform.playbook",
            "recipe": "platform.recipe",
            "note": "platform.context 只返回相关子集；需要单个工具完整 Schema 时再调用 describe。",
        },
        "availableTasks": available_tasks,
        "availableRecipes": sorted(recipe_map.keys()),
        "availableAliases": sorted(TASK_ALIASES.keys()),
        "availableDomains": list(AVAILABLE_DOMAINS),
        "disabledTools": sorted(getattr(ctx, "disabled_tools", frozenset()) or ()),
        "readOnlyMode": bool(getattr(ctx, "read_only_mode", False)),
    }
