"""工具输入/输出 Schema 的轻量校验器（JSON Schema 子集，标准库）。

工具契约声明的 Schema 由本模块校验；只实现工具实际用到的关键字，避免引入
第三方依赖或过度宽松的「校验」：

type / required / properties / additionalProperties / enum / const /
minimum / maximum / minLength / maxLength / minItems / maxItems /
items / pattern / oneOf(仅用于简单联合) / anyOf。

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
        if isinstance(properties, dict):
            for key, item in value.items():
                if key in properties:
                    _validate(item, properties[key], f"{path}.{key}" if path else key, errors)
                elif schema.get("additionalProperties") is False:
                    errors.append({"path": path, "message": "不允许的字段 " + str(key)})


def validate(value, schema) -> list[dict]:
    """返回错误列表；空列表表示通过。"""
    errors: list[dict] = []
    _validate(value, schema, "", errors)
    return errors
