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
        "discovery": {
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
