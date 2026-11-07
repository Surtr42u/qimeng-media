"""计划结构、稳定指纹与幂等键（写入流程的契约核心）。

一次写入必须走：查询现状 → 生成计划 → 校验前置版本/授权 → 确认 →
正式 API 执行 → 回读 → 结构化报告。本模块只负责计划的数据契约，不联网：

- 计划不含生成时刻，相同现状与相同输入生成逐字节相同的计划；
- 计划指纹 = 计划规范化 JSON（去掉 fingerprint/idempotencyKey）的 SHA-256；
- 幂等键 = tool + 指纹 + 目标实例 的稳定摘要；
- apply 侧重新计算并比对指纹，伪造/过期计划直接拒绝；
- 计划绑定 toolContractVersion 与 rulepackVersion（规则/契约变化即失效），
  openapiSha256 只记录来源与构建追踪，不单独决定全部计划失效。
"""
from __future__ import annotations

import hashlib
import json

from . import errors, versions

PLAN_REQUIRED_KEYS = (
    "planVersion",
    "tool",
    "toolVersion",
    "kind",
    "target",
    "changes",
    "impact",
    "evidence",
    "versions",
    "confirmation",
    "recoverableSteps",
    "expectedReadback",
    "idempotencyKey",
    "planFingerprint",
)


def canonical_json(value) -> str:
    """稳定序列化：排序键、紧凑分隔符、保留非 ASCII。"""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def _fingerprint_source(plan: dict) -> dict:
    return {key: value for key, value in plan.items()
            if key not in ("planFingerprint", "idempotencyKey")}


def compute_fingerprint(plan: dict) -> str:
    return digest(_fingerprint_source(plan))


def compute_idempotency_key(tool: str, fingerprint: str, instance: str) -> str:
    seed = "|".join((tool, fingerprint, instance))
    return hashlib.sha256(seed.encode("utf-8")).hexdigest()[:32]


def make_plan(*, tool: str, tool_version: str, kind: str, target: dict,
              changes: list, impact: dict, evidence: list, versions_info: dict,
              confirmation: dict, recoverable_steps: list, expected_readback: list,
              extra: dict | None = None) -> dict:
    """构造计划并计算指纹/幂等键；返回可直接交给对应 apply 工具的 JSON。"""
    instance = str((target or {}).get("instance") or "")
    plan = {
        "planVersion": versions.PLAN_VERSION,
        "tool": tool,
        "toolVersion": tool_version,
        "kind": kind,
        "target": target,
        "changes": changes,
        "impact": impact,
        "evidence": evidence,
        "versions": {
            "platformVersion": versions.PLATFORM_VERSION,
            "toolContractVersion": versions.TOOL_CONTRACT_VERSION,
            "rulepackVersion": versions_info.get("rulepackVersion", ""),
            "openapiSha256": versions_info.get("openapiSha256", ""),
        },
        "confirmation": confirmation,
        "recoverableSteps": recoverable_steps,
        "expectedReadback": expected_readback,
    }
    if extra:
        # 工具特有的前置条件/当前值/保护字段等；参与指纹，apply 侧只能原样信任。
        plan.update(extra)
    plan["planFingerprint"] = compute_fingerprint(plan)
    plan["idempotencyKey"] = compute_idempotency_key(tool, plan["planFingerprint"], instance)
    return plan


def verify_plan(plan, *, tool_id: str, rulepack_version: str) -> dict:
    """校验计划结构与指纹/版本；通过返回计划对象。"""
    if not isinstance(plan, dict):
        raise errors.plan_invalid("plan 必须是对象")
    missing = [key for key in PLAN_REQUIRED_KEYS if key not in plan]
    if missing:
        raise errors.plan_invalid("plan 缺少必需字段：" + ", ".join(missing))
    if plan.get("planVersion") != versions.PLAN_VERSION:
        raise errors.plan_version_mismatch(
            {"planVersion": plan.get("planVersion"), "expected": versions.PLAN_VERSION})
    if plan.get("tool") != tool_id:
        raise errors.plan_invalid(f"plan.tool 应为 {tool_id}，实际为 {plan.get('tool')}")
    bound = plan.get("versions") if isinstance(plan.get("versions"), dict) else {}
    issues = []
    if bound.get("toolContractVersion") != versions.TOOL_CONTRACT_VERSION:
        issues.append({"field": "toolContractVersion",
                       "plan": bound.get("toolContractVersion"),
                       "current": versions.TOOL_CONTRACT_VERSION})
    if bound.get("rulepackVersion") != rulepack_version:
        issues.append({"field": "rulepackVersion",
                       "plan": bound.get("rulepackVersion"),
                       "current": rulepack_version})
    if issues:
        raise errors.plan_version_mismatch({"mismatches": issues})
    expected_fingerprint = compute_fingerprint(plan)
    if plan.get("planFingerprint") != expected_fingerprint:
        raise errors.plan_fingerprint_mismatch(expected_fingerprint, plan.get("planFingerprint"))
    target = plan.get("target") if isinstance(plan.get("target"), dict) else {}
    expected_key = compute_idempotency_key(tool_id, expected_fingerprint, str(target.get("instance") or ""))
    if plan.get("idempotencyKey") != expected_key:
        raise errors.plan_invalid("plan.idempotencyKey 与计划内容不一致")
    return plan


def version_snapshot(rulepack_version: str, openapi_sha256: str = "") -> dict:
    return {
        "platformVersion": versions.PLATFORM_VERSION,
        "toolContractVersion": versions.TOOL_CONTRACT_VERSION,
        "rulepackVersion": rulepack_version,
        "openapiSha256": openapi_sha256,
    }
