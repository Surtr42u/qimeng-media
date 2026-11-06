"""规则包生成与读取。

规则包 = 手写领域规则（rules.json）+ 当前 OpenAPI 的路径与指纹。
本模块不联网、不读取凭据；OpenAPI 只做行解析，不引入 YAML 依赖。
生成物默认输出到 stdout，可用 --out 落盘，不提交仓库。
"""
from __future__ import annotations

import copy
import hashlib
import json
import os
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RULES_PATH = Path(__file__).with_name("rules.json")
DEFAULT_OPENAPI_PATH = REPO_ROOT / "api" / "openapi.yaml"

PATH_LINE = re.compile(r"^  (/api/v1[^:\s]*):\s*$", re.MULTILINE)
ENDPOINT_PATH = re.compile(r"(/api/v1/[^\s]+)$")
METHOD_LINE = re.compile(r"^    (get|post|put|delete|patch|head|options):\s*$", re.MULTILINE)
MERGE_PATH = re.compile(r"^/api/v1/authors(?:/[^/]+)*/merge$")

REQUIRED_RULE_KEYS = (
    "schemaVersion",
    "rulesVersion",
    "guide",
    "capabilities",
    "fields",
    "authorActions",
    "tags",
    "backup",
    "errors",
    "workflows",
    "permissions",
)
REQUIRED_PACK_KEYS = (
    "schemaVersion",
    "rulesVersion",
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
    """读取手写规则源并校验必需顶层键。"""
    path = Path(rules_path) if rules_path is not None else DEFAULT_RULES_PATH
    rules = _load_json(path)
    missing = [key for key in REQUIRED_RULE_KEYS if key not in rules]
    if missing:
        raise RulepackError("rules.json 缺少必需键：" + ", ".join(missing))
    if type(rules["schemaVersion"]) is not int:
        raise RulepackError("rules.json 的 schemaVersion 必须是整数")
    if not isinstance(rules["rulesVersion"], str) or not rules["rulesVersion"].strip():
        raise RulepackError("rules.json 的 rulesVersion 必须是非空字符串")
    return rules


def read_openapi(openapi_path: str | Path | None = None) -> bytes:
    """读取 OpenAPI 原始字节；指纹必须基于字节，而不是格式化后的文本。"""
    path = Path(openapi_path) if openapi_path is not None else DEFAULT_OPENAPI_PATH
    try:
        return path.read_bytes()
    except OSError:
        raise RulepackError(f"无法读取 OpenAPI 文件：{path}") from None


def extract_paths(openapi_text: str) -> list[str]:
    """从 OpenAPI 文本提取当前 /api/v1 路径；只做行解析。"""
    paths = sorted({match.group(1) for match in PATH_LINE.finditer(openapi_text)})
    if not paths:
        raise RulepackError("OpenAPI 文本中没有找到 /api/v1 路径")
    return paths


extract_api_paths = extract_paths


def _extract_path_methods(openapi_text: str) -> dict[str, list[str]]:
    """辅助合并端点识别：记录每个路径下的 HTTP 方法；仍是行解析。"""
    methods: dict[str, list[str]] = {}
    current: str | None = None
    for line in openapi_text.splitlines():
        match = PATH_LINE.match(line)
        if match:
            current = match.group(1)
            methods.setdefault(current, [])
            continue
        if current is None:
            continue
        method = METHOD_LINE.match(line)
        if method:
            methods[current].append(method.group(1).upper())
        elif line.startswith("  /"):
            current = None
    return methods


def _endpoint_path(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    match = ENDPOINT_PATH.search(value.strip())
    return match.group(1) if match else None


def _reconcile_endpoint(entry: dict, path_set: set[str]) -> None:
    """端点未出现在当前 OpenAPI 时，把 supported/available 降级为 planned。"""
    if not isinstance(entry, dict):
        return
    path = _endpoint_path(entry.get("endpoint"))
    if path is None:
        if entry.get("endpoint") is None and "endpoint" in entry:
            entry["endpointPresent"] = False
        return
    present = path in path_set
    entry["endpointPresent"] = present
    if not present and entry.get("status") in ("supported", "available"):
        entry["status"] = "planned"
        entry["statusReason"] = "endpoint-not-in-openapi"


def _merge_endpoint(paths: list[str], methods: dict[str, list[str]]) -> str | None:
    matches = [path for path in paths if MERGE_PATH.match(path)]
    if not matches:
        return None
    path = matches[0]
    verbs = methods.get(path, [])
    if len(verbs) == 1:
        return f"{verbs[0]} {path}"
    if verbs:
        return f"{'/'.join(verbs)} {path}"
    return path


def _reconcile(pack: dict, openapi_text: str, paths: list[str]) -> None:
    path_set = set(paths)
    methods = _extract_path_methods(openapi_text)

    capabilities = pack.get("capabilities", {})
    if isinstance(capabilities, dict):
        for group in ("read", "write"):
            entries = capabilities.get(group, {})
            if isinstance(entries, dict):
                for entry in entries.values():
                    _reconcile_endpoint(entry, path_set)

    actions = pack.get("authorActions", {})
    if isinstance(actions, dict):
        for name, action in actions.items():
            if not isinstance(action, dict):
                continue
            if name == "merge":
                detected = _merge_endpoint(paths, methods)
                capability = None
                if isinstance(capabilities, dict):
                    write_entries = capabilities.get("write", {})
                    if isinstance(write_entries, dict):
                        capability = write_entries.get("author.merge")
                if detected:
                    action["apiStatus"] = "available"
                    action["endpoint"] = detected
                    action["endpointPresent"] = True
                    if isinstance(capability, dict):
                        capability["apiStatus"] = "available"
                        capability["endpoint"] = detected
                        capability["endpointPresent"] = True
                        capability["status"] = "planned"
                else:
                    action["apiStatus"] = "planned"
                    action["endpoint"] = None
                    action["endpointPresent"] = False
                    if isinstance(capability, dict):
                        capability["apiStatus"] = "planned"
                        capability["endpoint"] = None
                        capability["endpointPresent"] = False
                        capability["status"] = "planned"
                action["status"] = "planned"
                continue
            _reconcile_endpoint(action, path_set)
            if action.get("apiStatus") == "available" and action.get("endpointPresent") is False:
                action["apiStatus"] = "planned"
                action["status"] = "planned"
                action["statusReason"] = "endpoint-not-in-openapi"

    profile = pack.get("fields", {}).get("authorProfile", {})
    if isinstance(profile, dict):
        _reconcile_endpoint(profile, path_set)


def build_rulepack(openapi_path: str | Path | None = None, rules_path: str | Path | None = None) -> dict:
    """生成规则包；相同输入字节得到相同输出（不写入不确定时间）。"""
    rules = load_rules(rules_path)
    raw = read_openapi(openapi_path)
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        raise RulepackError("OpenAPI 文件不是合法 UTF-8") from None
    paths = extract_paths(text)
    pack = {
        "schemaVersion": rules["schemaVersion"],
        "rulesVersion": rules["rulesVersion"],
        "openapiSha256": hashlib.sha256(raw).hexdigest(),
        "paths": paths,
        "capabilities": copy.deepcopy(rules["capabilities"]),
        "fields": copy.deepcopy(rules["fields"]),
        "authorActions": copy.deepcopy(rules["authorActions"]),
        "tags": copy.deepcopy(rules["tags"]),
        "backup": copy.deepcopy(rules["backup"]),
        "errors": copy.deepcopy(rules["errors"]),
        "workflows": copy.deepcopy(rules["workflows"]),
        "permissions": copy.deepcopy(rules["permissions"]),
        "guide": copy.deepcopy(rules["guide"]),
    }
    _reconcile(pack, text, paths)
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
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".tmp")
        temporary.write_text(dumps_pack(pack), encoding="utf-8")
        os.replace(temporary, target)
    except OSError:
        try:
            temporary.unlink()
        except (OSError, UnboundLocalError):
            pass
        raise RulepackError(f"无法写入规则包输出：{target}") from None
    return target


def render_markdown(pack: dict) -> str:
    """将规则包渲染为简明中文指南；内容来自 rules.json 的 guide。"""
    guide = pack.get("guide", {})
    lines = [f"# {guide.get('title', '绮梦影库 AI 维护规则包')}", ""]
    if guide.get("summary"):
        lines.extend([guide["summary"], ""])
    lines.extend([
        f"- 规则版本：`{pack.get('rulesVersion', '')}`",
        f"- OpenAPI SHA-256：`{pack.get('openapiSha256', '')}`",
        f"- OpenAPI /api/v1 路径数：{len(pack.get('paths', []))}",
        "",
    ])
    for section in guide.get("sections", []):
        if not isinstance(section, dict):
            continue
        lines.append(f"## {section.get('heading', '')}")
        for point in section.get("points", []):
            lines.append(f"- {point}")
        lines.append("")

    lines.extend([
        "## 只读命令",
        "",
        "- `python -X utf8 tools/ai_maintenance/cli.py context [--markdown] [--out <path>]`",
        "- `python -X utf8 tools/ai_maintenance/cli.py health [--ready]`",
        "- `python -X utf8 tools/ai_maintenance/cli.py libraries`",
        "- `python -X utf8 tools/ai_maintenance/cli.py assets [--page N | --cursor C] [--limit N] [--q TEXT]`",
        "- `python -X utf8 tools/ai_maintenance/cli.py authors [--page N] [--size N]`",
        "- `python -X utf8 tools/ai_maintenance/cli.py author-profile --author-id <authorId>`",
        "- `python -X utf8 tools/ai_maintenance/cli.py author-sources --author-id <authorId>`",
        "- `python -X utf8 tools/ai_maintenance/cli.py research context [--json]`",
        "- `python -X utf8 tools/ai_maintenance/cli.py research provider --provider <name> --operation <op> --target <值> [--limit N] [--json]`",
        "- `python -X utf8 tools/ai_maintenance/cli.py research lens --file <本地图片> [--json]`",
        "",
        "网络命令需要 `QIMENG_API_BASE` 与 `QIMENG_API_TOKEN`；缺少配置时退出码为 2。research 命令默认输出人读摘要，`--json` 输出原始 JSON。",
        "",
    ])

    write_entries = pack.get("capabilities", {}).get("write", {})
    if isinstance(write_entries, dict) and write_entries:
        lines.extend(["## 写入能力（本切片一律 planned）", ""])
        for name, entry in sorted(write_entries.items()):
            status = entry.get("status") if isinstance(entry, dict) else "planned"
            endpoint = entry.get("endpoint") if isinstance(entry, dict) else None
            suffix = f"；端点：`{endpoint}`" if endpoint else ""
            lines.append(f"- `{name}`：{status}{suffix}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"
