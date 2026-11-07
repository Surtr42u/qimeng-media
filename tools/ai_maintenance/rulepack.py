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

from . import openapi, registry, versions

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


def _requirement_from_entry(entry: dict):
    methods, path = _endpoint_methods(entry.get("endpoint"))
    if not path or not methods:
        return []
    required_fields = entry.get("requiredFields")
    fields = tuple(required_fields) if isinstance(required_fields, list) else ()
    schema_name = entry.get("requestSchema") if isinstance(entry.get("requestSchema"), str) else None
    parameters = entry.get("parameters") if isinstance(entry.get("parameters"), list) else ()
    return [
        openapi.ApiRequirement(path=path, method=method, request_schema=schema_name,
                               required_body_fields=fields,
                               body_required=True if (schema_name or fields) else None,
                               parameters=tuple(parameters))
        for method in methods
    ]


def _check_entry(entry: dict, document) -> dict:
    """按端点字符串核对一个能力/动作条目，返回状态补丁。"""
    requirements = _requirement_from_entry(entry)
    if not requirements:
        patch = {"capabilityStatus": "not_checked"}
        if "endpoint" in entry:
            patch["endpointPresent"] = False
            patch["methodsPresent"] = False
        return patch
    checks = openapi.check_requirements(document, requirements)
    overall = openapi.overall_status(checks)
    endpoint_present = all(check.code != "endpoint_not_in_openapi" for check in checks)
    patch = {
        "endpointPresent": endpoint_present,
        "methodsPresent": endpoint_present and all(
            check.code != "method_not_in_openapi" for check in checks),
        "capabilityStatus": overall,
        "checks": [check.to_dict() for check in checks],
    }
    if overall == "unavailable" and entry.get("status") in ("supported", "available"):
        patch["status"] = "unavailable"
        patch["statusReason"] = next((check.code for check in checks
                                      if check.status == "unavailable"), "endpoint_not_in_openapi")
    elif overall == "incompatible" and entry.get("status") in ("supported", "available"):
        patch["status"] = "incompatible"
        patch["statusReason"] = next((check.code for check in checks
                                      if check.status == "incompatible"), "capability_incompatible")
    if "apiStatus" in entry:
        patch["apiStatus"] = "available" if overall == "supported" else overall
    return patch


def _apply_tool_status(entry: dict, tool_id: str, document) -> dict:
    spec = registry.get(tool_id)
    if spec is None:
        return _check_entry(entry, document)
    status = registry.tool_status(spec, document)
    checks = status.get("checks", [])
    patch = {
        "toolId": tool_id,
        "toolStatus": status["status"],
        "capabilityStatus": status.get("capabilityStatus", "supported"),
        "checks": checks,
        "endpointPresent": not any(check.get("code") == "endpoint_not_in_openapi"
                                   for check in checks),
        "methodsPresent": not any(check.get("code") == "method_not_in_openapi"
                                  for check in checks),
    }
    capability_status = status.get("capabilityStatus", "supported")
    if spec.planned or status["status"] == "planned":
        patch["apiStatus"] = "available" if capability_status == "supported" else capability_status
        if entry.get("status") != "planned":
            patch["status"] = "planned"
        return patch
    patch["status"] = status["status"]
    if status["status"] != "supported":
        patch["statusReason"] = status.get("reason", "")
    if "apiStatus" in entry:
        patch["apiStatus"] = "available" if status["status"] == "supported" else status["status"]
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
                capability_status = status.get("capabilityStatus", "supported")
                action.update({
                    "apiStatus": "available" if capability_status == "supported" else capability_status,
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
