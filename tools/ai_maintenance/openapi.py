"""OpenAPI 分块解析与工具能力核对（标准库，只读）。

为什么不用第三方 YAML 库：规则包/工具链禁止新增 pip 依赖，且解析行为必须
可测试、可锁定（ADR-0056 §3）。本模块只解析工具契约需要的结构，不试图通用：

1. 先按缩进定位 /api/v1 path 块；
2. 在 path 块内核对 HTTP method 块；
3. 在 method 块内核对 requestBody（required、schema 引用、内联 required 字段）
   与 parameters（path 级 + method 级，支持引用 components.parameters）；
4. 解析 components.schemas 的 required/properties，用于核对写入请求的必需字段。

未识别的结构不会误判为 supported：缺少 requestBody/schema/字段时能力核对返回
incompatible。所有函数无副作用且确定性。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

HTTP_METHODS = ("get", "post", "put", "delete", "patch", "head", "options")

_METHOD_RE = re.compile(r"^(get|post|put|delete|patch|head|options):\s*$")
_REF_RE = re.compile(r"\$ref:\s*[\"']?#/components/(schemas|parameters)/([A-Za-z0-9_.-]+)")
_BLOCK_SCALAR_RE = re.compile(r"^(description|summary):\s*[|>][-+]?\s*$")
_SCHEMA_REF_RE = re.compile(r"schema:\s*\{\s*\$ref:\s*[\"']?#/components/schemas/([A-Za-z0-9_.-]+)")
_PARAM_NAME_RE = re.compile(r"\bname:\s*[\"']?([^,\s}\"']+)")
_PARAM_IN_RE = re.compile(r"\bin:\s*[\"']?([a-z]+)")
_PARAM_REQUIRED_RE = re.compile(r"\brequired:\s*(true|false)")
_FLOW_LIST_RE = re.compile(r"^\[\s*(.*?)\s*\]$")



@dataclass(frozen=True)
class Parameter:
    name: str
    location: str
    required: bool


@dataclass(frozen=True)
class RequestBody:
    required: bool
    schema_ref: str | None = None
    inline_required: tuple[str, ...] = ()
    inline_properties: tuple[str, ...] = ()


@dataclass(frozen=True)
class Operation:
    path: str
    method: str
    parameters: tuple[Parameter, ...] = ()
    request_body: RequestBody | None = None


@dataclass(frozen=True)
class Schema:
    name: str
    required: tuple[str, ...] = ()
    properties: tuple[str, ...] = ()


@dataclass(frozen=True)
class Document:
    operations: dict[str, dict[str, Operation]] = field(default_factory=dict)
    schemas: dict[str, Schema] = field(default_factory=dict)
    parameters: dict[str, Parameter] = field(default_factory=dict)

    def paths(self) -> list[str]:
        return sorted(self.operations)

    def operation(self, path: str, method: str) -> Operation | None:
        return self.operations.get(path, {}).get(method.lower())


class OpenAPIParseError(Exception):
    """输入不是可解析的 OpenAPI 文本；消息只含固定类别。"""

def _indent(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _structural_lines(text: str) -> list[tuple[int, str]]:
    """把文本变成 (缩进, 去空白内容) 列表，并跳过块标量（description: |）。

    跳过块标量是为了避免描述正文里的路径、requestBody 等字样被误当结构行；
    缩进规则来自本仓 OpenAPI 的固定排版约定。
    """
    raw_lines = text.splitlines()
    lines: list[tuple[int, str]] = []
    index = 0
    while index < len(raw_lines):
        line = raw_lines[index]
        content = line.strip()
        if not content or content.startswith("#"):
            lines.append((_indent(line), ""))
            index += 1
            continue
        lines.append((_indent(line), content))
        if _BLOCK_SCALAR_RE.match(content):
            base = _indent(line)
            index += 1
            while index < len(raw_lines):
                nxt = raw_lines[index]
                if nxt.strip() and _indent(nxt) <= base:
                    break
                index += 1
            continue
        index += 1
    return lines


def _parse_flow_list(value: str) -> tuple[str, ...]:
    value = value.strip()
    match = _FLOW_LIST_RE.match(value)
    if not match:
        return ()
    inner = match.group(1).strip()
    if not inner:
        return ()
    return tuple(item.strip().strip("\"'") for item in inner.split(",") if item.strip())


def _parse_parameter_text(text: str) -> Parameter | None:
    name = _PARAM_NAME_RE.search(text)
    location = _PARAM_IN_RE.search(text)
    if not name or not location:
        return None
    required = bool(_PARAM_REQUIRED_RE.search(text))
    return Parameter(name=name.group(1), location=location.group(1), required=required)


def _iter_parameter_items(lines, start: int, end: int) -> list[str]:
    """解析 parameters 列表，返回条目文本列表。

    兼容两种排版：单行 flow mapping（本仓风格）与 block mapping（多行）。
    """
    items: list[str] = []
    index = start
    while index < end:
        indent, content = lines[index]
        if content == "":
            index += 1
            continue
        if content.startswith("- "):
            item_indent = indent
            parts = [content[2:].strip()]
            index += 1
            while index < end:
                sub_indent, sub_content = lines[index]
                if sub_content == "":
                    index += 1
                    continue
                if sub_indent <= item_indent:
                    break
                parts.append(sub_content)
                index += 1
            items.append(" ".join(parts))
            continue
        index += 1
    return items


def _parse_parameters_region(lines, start: int, end: int, parameters: dict[str, Parameter]):
    """解析 parameters 区块并返回参数元组。"""
    result: list[Parameter] = []
    for text in _iter_parameter_items(lines, start, end):
        ref = _REF_RE.search(text)
        if ref and ref.group(1) == "parameters":
            name = ref.group(2)
            if name in parameters:
                result.append(parameters[name])
            else:
                # 悬空引用：保留占位，能力核对会把它视为缺失能力。
                result.append(Parameter(name="?" + name, location="ref", required=False))
            continue
        parameter = _parse_parameter_text(text)
        if parameter is not None:
            result.append(parameter)
    return tuple(result)


def _find_block_end(lines, start: int, parent_indent: int) -> int:
    index = start
    while index < len(lines):
        indent, content = lines[index]
        if content and indent <= parent_indent:
            break
        index += 1
    return index


def _parse_request_body(lines, start: int, end: int) -> RequestBody | None:
    """解析 requestBody 区块；未声明返回 None。"""
    declared = False
    required = False
    schema_ref: str | None = None
    inline_required: tuple[str, ...] = ()
    inline_properties: tuple[str, ...] = ()
    index = start
    while index < end:
        indent, content = lines[index]
        if content == "":
            index += 1
            continue
        if content.startswith("required:"):
            required = required or "true" in content
        ref_match = _SCHEMA_REF_RE.search(content)
        if ref_match:
            schema_ref = ref_match.group(1)
        if content.startswith("required: [") and schema_ref is None:
            inline_required = _parse_flow_list(content[len("required:"):])
        if content.startswith("properties:"):
            prop_indent = indent
            index += 1
            properties: list[str] = []
            while index < end:
                sub_indent, sub_content = lines[index]
                if sub_content and sub_indent <= prop_indent:
                    break
                if sub_content and sub_indent == prop_indent + 2:
                    key = sub_content.split(":", 1)[0].strip()
                    if key:
                        properties.append(key)
                index += 1
            inline_properties = tuple(properties)
            continue
        if content.startswith("requestBody:"):
            declared = True
        index += 1
    if not declared and schema_ref is None and not inline_required:
        return None
    return RequestBody(
        required=required,
        schema_ref=schema_ref,
        inline_required=inline_required,
        inline_properties=inline_properties,
    )


def _parse_method_block(lines, start: int, end: int, path: str, method: str,
                        components_parameters: dict[str, Parameter]) -> Operation:
    parameters: list[Parameter] = []
    body: RequestBody | None = None
    index = start
    while index < end:
        indent, content = lines[index]
        if content == "":
            index += 1
            continue
        if content.startswith("parameters:"):
            block_end = _find_block_end(lines, index + 1, indent)
            parameters.extend(_parse_parameters_region(lines, index + 1, block_end, components_parameters))
            index = block_end
            continue
        if content.startswith("requestBody:"):
            block_end = _find_block_end(lines, index + 1, indent)
            parsed = _parse_request_body(lines, index, block_end)
            if parsed is not None:
                body = parsed
            index = block_end
            continue
        index += 1
    return Operation(path=path, method=method, parameters=tuple(parameters), request_body=body)


def _parse_schemas(lines, start: int, end: int, schemas: dict[str, Schema]):
    index = start
    while index < end:
        indent, content = lines[index]
        if content == "":
            index += 1
            continue
        if indent == 4 and content.endswith(":"):
            name = content[:-1].strip()
            block_end = _find_block_end(lines, index + 1, 4)
            required: tuple[str, ...] = ()
            properties: list[str] = []
            cursor = index + 1
            while cursor < block_end:
                sub_indent, sub_content = lines[cursor]
                if sub_content == "":
                    cursor += 1
                    continue
                if sub_content.startswith("required:"):
                    value = sub_content[len("required:"):].strip()
                    if value:
                        required = _parse_flow_list(value)
                    else:
                        items = []
                        look = cursor + 1
                        while look < block_end and lines[look][1].startswith("- "):
                            items.append(lines[look][1][2:].strip())
                            look += 1
                        required = tuple(items)
                elif sub_content.startswith("properties:"):
                    prop_indent = sub_indent
                    cursor += 1
                    while cursor < block_end:
                        p_indent, p_content = lines[cursor]
                        if p_content and p_indent <= prop_indent:
                            break
                        if p_content and p_indent == prop_indent + 2:
                            key = p_content.split(":", 1)[0].strip()
                            if key:
                                properties.append(key)
                        cursor += 1
                    continue
                cursor += 1
            schemas[name] = Schema(name=name, required=required, properties=tuple(properties))
            index = block_end
            continue
        index += 1


def _parse_components(lines, start: int, end: int, schemas: dict[str, Schema],
                      parameters: dict[str, Parameter]):
    index = start
    while index < end:
        indent, content = lines[index]
        if content == "":
            index += 1
            continue
        if indent == 2 and content in ("parameters:", "schemas:"):
            block_end = _find_block_end(lines, index + 1, 2)
            if content == "parameters:":
                cursor = index + 1
                while cursor < block_end:
                    p_indent, p_content = lines[cursor]
                    if p_content and p_indent == 4 and ":" in p_content:
                        name = p_content.split(":", 1)[0].strip()
                        parameter = _parse_parameter_text(p_content)
                        if parameter is not None:
                            parameters[name] = parameter
                    cursor += 1
            else:
                _parse_schemas(lines, index + 1, block_end, schemas)
            index = block_end
            continue
        index += 1


def _parse_paths(lines, start: int, end: int, operations: dict[str, dict[str, Operation]],
                 parameters: dict[str, Parameter]):
    index = start
    while index < end:
        indent, content = lines[index]
        if content == "":
            index += 1
            continue
        if indent == 2 and content.endswith(":") and content.startswith("/"):
            path = content[:-1].strip()
            block_end = _find_block_end(lines, index + 1, 2)
            path_parameters: list[Parameter] = []
            method_entries: list[tuple[str, int, int]] = []
            cursor = index + 1
            while cursor < block_end:
                sub_indent, sub_content = lines[cursor]
                if sub_content == "":
                    cursor += 1
                    continue
                if sub_indent == 4 and sub_content.startswith("parameters:"):
                    params_end = _find_block_end(lines, cursor + 1, 4)
                    path_parameters.extend(
                        _parse_parameters_region(lines, cursor + 1, params_end, parameters)
                    )
                    cursor = params_end
                    continue
                method_match = _METHOD_RE.match(sub_content) if sub_indent == 4 else None
                if method_match:
                    method = method_match.group(1)
                    method_end = _find_block_end(lines, cursor + 1, 4)
                    method_entries.append((method, cursor + 1, method_end))
                    cursor = method_end
                    continue
                cursor += 1
            for method, method_start, method_end in method_entries:
                operation = _parse_method_block(lines, method_start, method_end, path, method, parameters)
                operations.setdefault(path, {})[method] = Operation(
                    path=path,
                    method=method,
                    parameters=tuple(path_parameters) + operation.parameters,
                    request_body=operation.request_body,
                )
            index = block_end
            continue
        index += 1


def parse(text: str) -> Document:
    """解析 OpenAPI 文本；确定性、无副作用。"""
    if not isinstance(text, str):
        raise OpenAPIParseError("OpenAPI 输入必须是文本")
    lines = _structural_lines(text)
    schemas: dict[str, Schema] = {}
    parameters: dict[str, Parameter] = {}
    operations: dict[str, dict[str, Operation]] = {}

    # 先解析 components（paths 里的参数引用需要先有定义）。
    index = 0
    while index < len(lines):
        indent, content = lines[index]
        if content and indent == 0 and content == "components:":
            end = _find_block_end(lines, index + 1, 0)
            _parse_components(lines, index + 1, end, schemas, parameters)
            index = end
            continue
        index += 1

    index = 0
    while index < len(lines):
        indent, content = lines[index]
        if content and indent == 0 and content == "paths:":
            end = _find_block_end(lines, index + 1, 0)
            _parse_paths(lines, index + 1, end, operations, parameters)
            index = end
            continue
        index += 1
    if not operations:
        raise OpenAPIParseError("OpenAPI 文本中没有找到 paths 区块")
    return Document(operations=operations, schemas=schemas, parameters=parameters)


def parse_file(path: str | Path) -> tuple[Document, bytes]:
    """读取并解析 OpenAPI；指纹基于原始字节，解析只读。"""
    target = Path(path)
    try:
        raw = target.read_bytes()
    except OSError:
        raise OpenAPIParseError(f"无法读取 OpenAPI 文件：{target}") from None
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        raise OpenAPIParseError("OpenAPI 文件不是合法 UTF-8") from None
    return parse(text), raw


# --------------------------------------------------------------------------- #
# 能力核对
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class ApiRequirement:
    """一个工具对主 API 的最小依赖声明。"""

    path: str
    method: str
    request_schema: str | None = None
    required_body_fields: tuple[str, ...] = ()
    body_required: bool | None = None
    parameters: tuple[str, ...] = ()
    required_parameters: tuple[str, ...] = ()
    body_properties: tuple[str, ...] = ()


@dataclass(frozen=True)
class CheckResult:
    status: str  # supported / unavailable / incompatible
    code: str
    detail: str
    requirement: ApiRequirement

    def to_dict(self) -> dict:
        return {
            "path": self.requirement.path,
            "method": self.requirement.method.upper(),
            "status": self.status,
            "code": self.code,
            "detail": self.detail,
        }


def _parameter_names(operation: Operation) -> dict[str, Parameter]:
    return {parameter.name: parameter for parameter in operation.parameters if parameter.location != "ref"}


def check_requirement(document: Document, requirement: ApiRequirement) -> CheckResult:
    """按路径、方法、requestBody/schema/必需字段、参数逐级核对。

    任一级不满足都返回明确 code；绝不让「路径存在」单独等于工具可用。
    """
    method = requirement.method.lower()
    path_operations = document.operations.get(requirement.path)
    if path_operations is None:
        return CheckResult("unavailable", "endpoint_not_in_openapi",
                           "OpenAPI 缺少路径 " + requirement.path, requirement)
    operation = path_operations.get(method)
    if operation is None:
        return CheckResult("unavailable", "method_not_in_openapi",
                           "OpenAPI 路径 " + requirement.path + " 缺少 " + method.upper() + " 方法", requirement)

    body = operation.request_body
    needs_body = bool(requirement.request_schema or requirement.required_body_fields or requirement.body_properties
                      or requirement.body_required is not None)
    if needs_body:
        if body is None:
            return CheckResult("incompatible", "request_body_not_declared",
                               method.upper() + " " + requirement.path + " 未声明 requestBody", requirement)
        if requirement.body_required is not None and body.required != requirement.body_required:
            return CheckResult("incompatible", "request_body_required_mismatch",
                               method.upper() + " " + requirement.path + " requestBody.required 与契约不符", requirement)
        if requirement.request_schema:
            schema = document.schemas.get(requirement.request_schema)
            if schema is None:
                return CheckResult("incompatible", "request_schema_missing",
                                   "OpenAPI 缺少 schema " + requirement.request_schema, requirement)
            if body.schema_ref != requirement.request_schema:
                return CheckResult(
                    "incompatible", "request_schema_mismatch",
                    method.upper() + " " + requirement.path + " 请求 schema 为 "
                    + (body.schema_ref or "内联") + "，期望 " + requirement.request_schema,
                    requirement,
                )
            schema_required, schema_properties = schema.required, schema.properties
        else:
            schema_required = body.inline_required
            schema_properties = body.inline_properties
        missing = [field for field in requirement.required_body_fields if field not in schema_required]
        if missing:
            return CheckResult("incompatible", "required_field_missing",
                               "schema 必需字段缺失：" + ", ".join(missing), requirement)
        missing_properties = [field for field in requirement.body_properties if field not in schema_properties]
        if missing_properties:
            return CheckResult("incompatible", "body_property_missing",
                               "schema 未声明工具使用字段：" + ", ".join(missing_properties), requirement)
        undeclared = [field for field in requirement.required_body_fields if field not in schema_properties]
        if undeclared:
            return CheckResult("incompatible", "required_field_not_in_properties",
                               "schema 未声明字段：" + ", ".join(undeclared), requirement)

    names = _parameter_names(operation)
    missing_parameters = [name for name in requirement.parameters if name not in names]
    if missing_parameters:
        return CheckResult("incompatible", "parameter_missing",
                           "缺少参数声明：" + ", ".join(missing_parameters), requirement)
    missing_required = [name for name in requirement.required_parameters
                        if name not in names or not names[name].required]
    if missing_required:
        return CheckResult("incompatible", "required_parameter_not_required",
                           "参数未声明为必填：" + ", ".join(missing_required), requirement)
    return CheckResult("supported", "supported", "能力满足", requirement)


def check_requirements(document: Document, requirements) -> list[CheckResult]:
    return [check_requirement(document, requirement) for requirement in requirements]


def overall_status(checks) -> str:
    """把多个依赖合成工具状态：unavailable 优先于 incompatible。"""
    statuses = {check.status for check in checks}
    if "unavailable" in statuses:
        return "unavailable"
    if "incompatible" in statuses:
        return "incompatible"
    return "supported"
