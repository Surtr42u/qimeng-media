"""工具执行上下文（不依赖其他内部模块，避免 registry/handler 循环导入）。"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping


@dataclass
class ToolContext:
    """交给 handler 的最小运行时上下文。

    - client：网络工具注入 ApiClient；纯本地工具为 None。
    - document/openapi_sha256：已解析的 OpenAPI 与来源指纹（能力核对用）。
    - rulepack_version：当前 rules.json 的 rulepackVersion（计划版本绑定）。
    - target：目标实例的 host:port 标签；绝不包含 token。
    - env：进程环境快照（handler 不得回显敏感值）。
    """

    client: Any = None
    document: Any = None
    openapi_sha256: str = ""
    rulepack_version: str = ""
    target: str = ""
    env: Mapping[str, str] | None = None
    extras: dict | None = None
