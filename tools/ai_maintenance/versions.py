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
PLATFORM_VERSION = "1.1.0"

# 工具契约版本（工具 ID/Schema/错误语义）。
# 1.1.0（2026-11-08）：新增外部执行器控制面、设备结果包、备份导出、研究工具族
# 与回收站/标签工具，并新增 backup.export / device.apply / maintenance.execute /
# trash.restore / asset.delete / asset.write 权限。工具 ID 与权限面扩展属于契约
# 变化，旧计划必须用当前工具包重新 prepare（rulepackVersion 同步变化）。
TOOL_CONTRACT_VERSION = "1.1.0"

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
