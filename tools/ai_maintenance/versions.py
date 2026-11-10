"""平台版本常量：工具包版本、工具契约版本与计划版本。

版本语义（ADR-0056 §3）：
- platformVersion：工具包发行版本；新增/修复工具时递增。
- toolContractVersion：工具 ID、输入/输出 Schema 与错误语义的契约版本；
  CLI/MCP 适配器据此判断是否兼容。破坏工具契约时递增。
- rulepackVersion：rules.json 领域规则的版本；规则变化即递增。
- openapiSha256：api/openapi.yaml 原始字节指纹，只用于来源/构建追踪，
  不再单独决定所有工具失效；每个工具按声明的端点/方法/字段独立核对。

三个版本各自独立：修改规则不需要改代码，新增无关业务字段不应让工具失效。
"""
from __future__ import annotations

# 工具包发行版本（语义化版本）。
PLATFORM_VERSION = "1.4.0"

# 工具契约版本（工具 ID/Schema/错误语义）。
# 1.1.0（2026-11-08）：新增外部执行器控制面、设备结果包、备份导出、研究工具族
# 与回收站/标签工具，并新增 backup.export / device.apply / maintenance.execute /
# trash.restore / asset.delete / asset.write 权限。
# 1.2.0（2026-11-10）：新增 platform.context 元工具与 media.organize.prepare 的
# scan 模式（只读内容对齐/去重/序号建议）；能力核对拆分 apiStatus 与实现状态。
# 1.3.0（2026-11-10）：新增 author.create、platform.recipe 与可执行 recipe/原语
# （media.probe/media.fingerprint/media.contact_sheet/device.push 等）；新增 recipe
# 与 target 运行形态。旧计划必须用当前工具包重新 prepare。
# 1.4.0（2026-11-10）：新增 files.storages/files.search 本地文件发现（不透明
# fileRef）、research.media.probe 的 fileRefs 输入、local.files.write/local.device
# 权限与 PATH_*/FILE_REF_*/RECIPE_* 错误码；可用性维度拆分为 availability。
# 工具 ID 与权限面扩展属于契约变化，旧计划必须用当前工具包重新 prepare。
TOOL_CONTRACT_VERSION = "1.4.0"

# 规则包（rules.json 与生成物）的 Schema 版本。
RULEPACK_SCHEMA_VERSION = 2

# 计划结构版本；apply 侧拒绝其他版本的计划。
PLAN_VERSION = "1.0"


def version_info() -> dict:
    """返回不依赖文件/网络/环境变量的版本信息。"""
    return {
        "platformVersion": PLATFORM_VERSION,
        "toolContractVersion": TOOL_CONTRACT_VERSION,
        "rulepackSchemaVersion": RULEPACK_SCHEMA_VERSION,
        "planVersion": PLAN_VERSION,
    }
