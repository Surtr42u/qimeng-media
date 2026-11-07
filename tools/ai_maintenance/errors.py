"""结构化错误分类：工具层、能力层、远程 HTTP、计划与冲突。

设计目标（ADR-0056 §3/§4）：
- 每个错误有稳定 code、category、exitCode 与 recovery 建议，供 CLI/MCP 与
  自动化执行器稳定判断，而不是解析中文 message。
- 消息与细节不得包含 token、完整 URL 凭据或上游正文以外的敏感内容；
  远程 HTTP 错误由 main 适配层单独格式化为 {status, body, target}。
- 退出码约定：2 = 用法/配置/能力/Schema 问题（修正后才能重试）；
  1 = 远程失败/冲突/前置条件变化/回读不一致（需要重新读取或人工决策）。
"""
from __future__ import annotations


class ToolError(Exception):
    """工具层可分类错误；code 稳定，message 面向人，recovery 可执行。"""

    def __init__(self, code: str, message: str, *, category: str = "tool",
                 exit_code: int = 2, recovery: str = "", details=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.category = category
        self.exit_code = exit_code
        self.recovery = recovery
        self.details = details

    def to_dict(self) -> dict:
        payload = {
            "error": self.code,
            "category": self.category,
            "message": self.message,
        }
        if self.recovery:
            payload["recovery"] = self.recovery
        if self.details is not None:
            payload["details"] = self.details
        return payload


def unknown_tool(tool_id: str) -> ToolError:
    return ToolError(
        "UNKNOWN_TOOL",
        f"未知工具 ID：{tool_id}",
        category="usage",
        exit_code=2,
        recovery="运行 tools list --json 查看已注册工具 ID。",
    )


def tool_disabled(tool_id: str, reason: str) -> ToolError:
    return ToolError(
        "TOOL_DISABLED",
        f"工具 {tool_id} 已被禁用：{reason}",
        category="permission",
        exit_code=2,
        recovery="检查 rules.json 的 toolConfig.disabled 与 QIMENG_AI_DISABLED_TOOLS / QIMENG_AI_READONLY。",
    )


def tool_not_implemented(tool_id: str, reason: str) -> ToolError:
    return ToolError(
        "TOOL_NOT_IMPLEMENTED",
        f"工具 {tool_id} 在当前版本未实现：{reason}",
        category="capability",
        exit_code=2,
        recovery="该工具状态为 planned；不要假装执行，等待实现或走既有脚本。",
    )


def invalid_input(message: str, details=None) -> ToolError:
    return ToolError(
        "INVALID_INPUT",
        message,
        category="usage",
        exit_code=2,
        recovery="按 message 修正输入后重试；不要盲目重试同一载荷。",
        details=details,
    )


def configuration_error(message: str) -> ToolError:
    return ToolError(
        "CONFIGURATION_ERROR",
        message,
        category="config",
        exit_code=2,
        recovery="检查 QIMENG_API_BASE / QIMENG_API_TOKEN / QIMENG_AI_* 环境变量；不回显或记录 token。",
    )


def capability_unavailable(tool_id: str, checks) -> ToolError:
    return ToolError(
        "CAPABILITY_UNAVAILABLE",
        f"工具 {tool_id} 依赖的 API 能力在当前 OpenAPI 中不可用",
        category="capability",
        exit_code=2,
        recovery="升级/切换目标实例或更新工具包；不要猜测旧参数继续执行。",
        details={"checks": checks},
    )


def capability_incompatible(tool_id: str, checks) -> ToolError:
    return ToolError(
        "CAPABILITY_INCOMPATIBLE",
        f"工具 {tool_id} 依赖的 API 契约与当前 OpenAPI 不兼容",
        category="capability",
        exit_code=2,
        recovery="核对 OpenAPI 变更与工具契约版本；需要新契约时升级工具包，不要用旧参数执行。",
        details={"checks": checks},
    )


def not_confirmed(tool_id: str) -> ToolError:
    return ToolError(
        "NOT_CONFIRMED",
        f"工具 {tool_id} 是写入动作用，必须显式确认",
        category="permission",
        exit_code=2,
        recovery="先调用对应的 prepare 工具生成计划，人工核对后带 confirm=true 与计划指纹再提交。",
    )


def plan_invalid(message: str, details=None) -> ToolError:
    return ToolError(
        "PLAN_INVALID",
        message,
        category="plan",
        exit_code=2,
        recovery="重新调用对应 prepare 工具生成计划，不要手工拼造或修改计划。",
        details=details,
    )


def plan_fingerprint_mismatch(expected: str, actual: str) -> ToolError:
    return ToolError(
        "PLAN_FINGERPRINT_MISMATCH",
        "计划指纹与计划内容不一致，计划已过期或被修改",
        category="plan",
        exit_code=1,
        recovery="重新 prepare 生成新计划（现状可能已变化），禁止绕过指纹直接执行。",
        details={"expected": expected, "actual": actual},
    )


def plan_version_mismatch(details: dict) -> ToolError:
    return ToolError(
        "PLAN_VERSION_MISMATCH",
        "计划绑定的工具契约/规则版本与当前工具包不一致",
        category="plan",
        exit_code=1,
        recovery="用当前工具包重新 prepare；旧规则下的计划不得继续执行。",
        details=details,
    )


def precondition_failed(message: str, details=None) -> ToolError:
    return ToolError(
        "PRECONDITION_FAILED",
        message,
        category="conflict",
        exit_code=1,
        recovery="重新读取目标现状并重新 prepare；不要覆盖或盲目重试。",
        details=details,
    )


def conflict(message: str, details=None) -> ToolError:
    return ToolError(
        "CONFLICT",
        message,
        category="conflict",
        exit_code=1,
        recovery="读取当前状态与冲突载荷，人工核对后重新计划；409 不得静默覆盖。",
        details=details,
    )


def readback_mismatch(message: str, details=None) -> ToolError:
    return ToolError(
        "READBACK_MISMATCH",
        message,
        category="verification",
        exit_code=1,
        recovery="停止后续写入，重新读取目标核对差异；把已执行步骤记录交给人工处理。",
        details=details,
    )


def internal_error(message: str = "工具内部错误", details=None) -> ToolError:
    return ToolError(
        "INTERNAL_ERROR",
        message,
        category="internal",
        exit_code=1,
        recovery="这是工具缺陷；保留输入与错误详情以便修复，不要重复写入。",
        details=details,
    )
