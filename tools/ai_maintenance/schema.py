"""工具输入/输出 Schema 的轻量校验器（JSON Schema 子集，标准库）。

工具契约声明的 Schema 由本模块校验；只实现工具实际用到的关键字，避免引入
第三方依赖或过度宽松的「校验」。支持的断言关键字见 ``SUPPORTED_KEYWORDS``，
纯注解关键字见 ``ANNOTATION_KEYWORDS``（不参与校验，仅供模型理解）：

type / required / properties / additionalProperties / enum / const /
minimum / maximum / minLength / maxLength / minItems / maxItems /
items / pattern。

``additionalProperties`` 支持 false（拒绝未声明字段）、true（不限制）与对象
Schema（对未在 properties 中声明的字段按该 Schema 校验）。未支持的关键字
（如 format/allOf/minProperties/oneOf/anyOf）不应出现在工具 Schema 中，
由契约一致性测试扫描拦截。

校验失败返回结构化错误列表（path/message），供 CLI/MCP 稳定分类为
INVALID_INPUT，而不是让 handler 内部抛 KeyError。
"""
from __future__ import annotations

import re

_TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "boolean": bool,
    "number": (int, float),
    "integer": int,
}

# 本模块真正实现断言的关键字；工具 Schema 只能使用这些 + 注解关键字。
SUPPORTED_KEYWORDS = frozenset({
    "type", "required", "properties", "additionalProperties", "enum", "const",
    "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems",
    "items", "pattern",
})
ANNOTATION_KEYWORDS = frozenset({"description", "title", "default", "examples"})

_MAX_ERRORS = 20


def _type_ok(value, expected: str) -> bool:
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "null":
        return value is None
    expected_type = _TYPES.get(expected)
    if expected_type is None:
        return True
    return isinstance(value, expected_type)


def _validate(value, schema, path: str, errors: list[dict]) -> None:
    if len(errors) >= _MAX_ERRORS:
        return
    if not isinstance(schema, dict):
        return
    expected = schema.get("type")
    if expected is not None:
        options = expected if isinstance(expected, list) else [expected]
        if not any(_type_ok(value, option) for option in options):
            errors.append({"path": path, "message": "类型应为 " + "/".join(options)})
            return
    if "const" in schema and value != schema["const"]:
        errors.append({"path": path, "message": "值必须等于 " + repr(schema["const"])})
        return
    if "enum" in schema and isinstance(schema["enum"], list) and value not in schema["enum"]:
        errors.append({"path": path, "message": "必须是枚举值之一：" + ", ".join(str(item) for item in schema["enum"])})
        return
    if isinstance(value, str):
        pattern = schema.get("pattern")
        if isinstance(pattern, str):
            try:
                if re.search(pattern, value) is None:
                    errors.append({"path": path, "message": "不匹配模式 " + pattern})
            except re.error:
                errors.append({"path": path, "message": "Schema 模式非法"})
        min_length = schema.get("minLength")
        if isinstance(min_length, int) and len(value) < min_length:
            errors.append({"path": path, "message": f"长度不能小于 {min_length}"})
        max_length = schema.get("maxLength")
        if isinstance(max_length, int) and len(value) > max_length:
            errors.append({"path": path, "message": f"长度不能超过 {max_length}"})
    if isinstance(value, bool):
        return
    if isinstance(value, (int, float)):
        minimum = schema.get("minimum")
        if isinstance(minimum, (int, float)) and value < minimum:
            errors.append({"path": path, "message": f"不能小于 {minimum}"})
        maximum = schema.get("maximum")
        if isinstance(maximum, (int, float)) and value > maximum:
            errors.append({"path": path, "message": f"不能大于 {maximum}"})
    if isinstance(value, list):
        min_items = schema.get("minItems")
        if isinstance(min_items, int) and len(value) < min_items:
            errors.append({"path": path, "message": f"至少需要 {min_items} 项"})
        max_items = schema.get("maxItems")
        if isinstance(max_items, int) and len(value) > max_items:
            errors.append({"path": path, "message": f"最多 {max_items} 项"})
        items = schema.get("items")
        if isinstance(items, dict):
            for index, item in enumerate(value):
                _validate(item, items, f"{path}[{index}]", errors)
    if isinstance(value, dict):
        required = schema.get("required")
        if isinstance(required, list):
            for key in required:
                if key not in value:
                    errors.append({"path": path, "message": "缺少必需字段 " + str(key)})
        properties = schema.get("properties")
        additional = schema.get("additionalProperties")
        if isinstance(properties, dict):
            for key, item in value.items():
                if key in properties:
                    _validate(item, properties[key], f"{path}.{key}" if path else key, errors)
                elif additional is False:
                    errors.append({"path": path, "message": "不允许的字段 " + str(key)})
                elif isinstance(additional, dict):
                    _validate(item, additional, f"{path}.{key}" if path else key, errors)
        elif additional is False and value:
            errors.append({"path": path, "message": "不允许任何字段"})
        elif isinstance(additional, dict):
            for key, item in value.items():
                _validate(item, additional, f"{path}.{key}" if path else key, errors)


def validate(value, schema) -> list[dict]:
    """返回错误列表；空列表表示通过。"""
    errors: list[dict] = []
    _validate(value, schema, "", errors)
    return errors
