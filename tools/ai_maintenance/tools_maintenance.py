"""外部 AI 执行器控制面：maintenance.claim / heartbeat / step.record / artifact.record / complete。

薄封装 OpenAPI 的 /api/v1/maintenance/executor/*（ADR-0058）：
- claim 原子领取一个可执行任务，返回本次租约 fencing token（leaseToken）与
  completedSteps；没有任务时服务端 204，工具返回 claimed=false。
- heartbeat / steps / artifacts / complete 必须原样回传 leaseToken；服务端在
  租约过期、任务暂停/取消、终态或被重新领取时返回 409 LEASE_LOST，本层统一
  映射为稳定错误码 LEASE_LOST（退出码 1），不做自动重试或静默重领。
- **leaseToken 是能力凭据**：只在 claim 的工具结果里出现一次，转发后续调用时
  不要写入日志、任务记录、证据包或转发到其他模型上下文；错误信息也不回显。

所有 handler 只做输入整形、错误映射与结果裁剪，不实现第二套任务状态机。
"""
from __future__ import annotations

from urllib import parse

from . import client as http_client
from . import errors
from .runtime import ToolContext

EXECUTOR_PATH = "/api/v1/maintenance/executor"
LEASE_TOKEN_NOTE = ("leaseToken 是本次领取的能力凭据：只用于同一任务的续租/步骤/工件/终态，"
                    "不得写入日志、任务记录或证据包，也不要转发到其他模型上下文。")
_STEP_STATUSES = ("pending", "running", "completed", "failed", "skipped")
_COMPLETE_STATUSES = ("completed", "partial", "failed")


def _server_code(exc: http_client.HTTPStatusError) -> str:
    return str(exc.body.get("code") or "") if isinstance(exc.body, dict) else ""


def _job_id(payload: dict) -> str:
    value = payload.get("jobId")
    if not isinstance(value, str) or not value or len(value) > 100 \
            or any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input("jobId 非法")
    return value


def _job_path(job_id: str, suffix: str) -> str:
    return EXECUTOR_PATH + "/jobs/" + parse.quote(job_id, safe="") + suffix


def _lease_token(payload: dict) -> str:
    value = payload.get("leaseToken")
    if not isinstance(value, str) or not value.strip() or len(value) > 200 \
            or any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input("leaseToken 非法（必须来自最近一次 claim）")
    return value


def _executor_post(ctx: ToolContext, tool_id: str, path: str, body: dict,
                   job_id: str = ""):
    try:
        return ctx.client.post(path, json_body=body)
    except http_client.HTTPStatusError as exc:
        code = _server_code(exc)
        if exc.status == 409 and code == "LEASE_LOST":
            raise errors.lease_lost(tool_id, job_id,
                                    {"status": exc.status, "serverCode": code}) from None
        if exc.status == 403 and code == "FORBIDDEN_SCOPE":
            raise errors.forbidden_scope(tool_id, str(exc.body)[:500],
                                         {"status": exc.status}) from None
        raise


def maintenance_claim(ctx: ToolContext, payload: dict) -> dict:
    body = {"kind": payload.get("kind")}
    lease_seconds = payload.get("leaseSeconds")
    if lease_seconds is not None:
        body["leaseSeconds"] = lease_seconds
    lease = _executor_post(ctx, "maintenance.claim",
                           EXECUTOR_PATH + "/claim", body)
    if lease is None:  # 204：当前没有可领取任务
        return {"claimed": False, "lease": None, "job": None,
                "note": "当前没有可领取任务；不要轮询成写操作，稍后再 claim。" + LEASE_TOKEN_NOTE}
    if not isinstance(lease, dict) or not lease.get("leaseToken"):
        raise errors.readback_mismatch("领取响应缺少 leaseToken/job")
    job = lease.get("job") if isinstance(lease.get("job"), dict) else {}
    return {
        "claimed": True,
        "lease": lease,
        "job": job,
        "leaseToken": lease.get("leaseToken"),
        "leaseUntil": lease.get("leaseUntil"),
        "completedSteps": lease.get("completedSteps") or [],
        "note": LEASE_TOKEN_NOTE + "按 completedSteps 跳过已完成步骤。",
    }


def maintenance_heartbeat(ctx: ToolContext, payload: dict) -> dict:
    job_id = _job_id(payload)
    body = {"leaseToken": _lease_token(payload)}
    lease_seconds = payload.get("leaseSeconds")
    if lease_seconds is not None:
        body["leaseSeconds"] = lease_seconds
    status = _executor_post(ctx, "maintenance.heartbeat",
                            _job_path(job_id, "/heartbeat"), body, job_id)
    if not isinstance(status, dict):
        raise errors.readback_mismatch("续租响应不是对象")
    return {"jobId": status.get("jobId"), "leaseUntil": status.get("leaseUntil"),
            "note": LEASE_TOKEN_NOTE}


def maintenance_step_record(ctx: ToolContext, payload: dict) -> dict:
    job_id = _job_id(payload)
    status = payload.get("status")
    if status not in _STEP_STATUSES:
        raise errors.invalid_input("status 必须是 " + "/".join(_STEP_STATUSES))
    step = payload.get("step")
    if not isinstance(step, str) or not step or len(step) > 100:
        raise errors.invalid_input("step 必须是 1..100 字符的稳定步骤名")
    body = {"leaseToken": _lease_token(payload), "step": step, "status": status}
    if payload.get("attempt") is not None:
        body["attempt"] = payload["attempt"]
    if payload.get("detail") is not None:
        body["detail"] = payload["detail"]
    job = _executor_post(ctx, "maintenance.step.record",
                         _job_path(job_id, "/steps"), body, job_id)
    return {"job": job, "note": "步骤按 (job, step) 幂等 upsert；恢复时按步骤名跳过已完成项。"}


def maintenance_artifact_record(ctx: ToolContext, payload: dict) -> dict:
    job_id = _job_id(payload)
    body = {
        "leaseToken": _lease_token(payload),
        "kind": payload.get("kind"),
        "uri": payload.get("uri"),
        "sha256": payload.get("sha256"),
        "bytes": payload.get("bytes"),
    }
    job = _executor_post(ctx, "maintenance.artifact.record",
                         _job_path(job_id, "/artifacts"), body, job_id)
    return {"job": job,
            "note": "工件正文不入 SQLite，只登记 kind/uri/sha256/bytes；(job,kind,uri) 重复登记为 no-op。"}


def maintenance_complete(ctx: ToolContext, payload: dict) -> dict:
    job_id = _job_id(payload)
    status = payload.get("status")
    if status not in _COMPLETE_STATUSES:
        raise errors.invalid_input("status 必须是 " + "/".join(_COMPLETE_STATUSES))
    body = {"leaseToken": _lease_token(payload), "status": status}
    if payload.get("errorCode") is not None:
        body["errorCode"] = payload["errorCode"]
    if payload.get("errorMessage") is not None:
        body["errorMessage"] = payload["errorMessage"]
    job = _executor_post(ctx, "maintenance.complete",
                         _job_path(job_id, "/complete"), body, job_id)
    return {"job": job,
            "note": "终态不可再领取/改写；终态写入后重复回传返回 LEASE_LOST（不幂等重放），响应丢失应先查详情。"}
