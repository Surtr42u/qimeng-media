"""有界模型输出、本地结果续读与无损计划引用。

完整工具结果只写入可信本机状态目录。模型收到小响应时保持原结构；超过预算时
收到语义摘要、resultRef 与 planRef，需查看完整数据时调用纯本地 result.read。
artifact 绑定 API origin、凭据摘要及当前工具/规则版本；外发内容会脱敏凭据和
本机绝对路径，完整 plan 只由可信执行端通过 resolve_plan_ref 取回。
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import stat
import tempfile
import time
from pathlib import Path

from . import client, errors, pathguard, versions

STATE_DIR_ENV = "QIMENG_AI_STATE_DIR"
OUTPUT_BUDGET_ENV = "QIMENG_AI_OUTPUT_BUDGET_BYTES"
DEFAULT_OUTPUT_BUDGET = 12 * 1024
MAX_OUTPUT_BUDGET = 1024 * 1024
MAX_PAGE_BYTES = 12 * 1024
MAX_PAGE_ITEMS = 500
MAX_ARTIFACT_BYTES = 32 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
MAX_ARTIFACTS = 128
ARTIFACT_TTL_SECONDS = 24 * 60 * 60
ARTIFACT_FORMAT_VERSION = 1
_RESULT_REF = re.compile(r"^r1_([0-9a-f]{32})$")
_PLAN_REF = re.compile(r"^p1_([0-9a-f]{32})$")
_BEARER = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*")
_WINDOWS_ABSOLUTE = re.compile(r"(?<![A-Za-z0-9_])[A-Za-z]:[\\/][^\s\"'<>]+")
_UNC_ABSOLUTE = re.compile(r"(?<!\S)\\\\[^\\/\s]+[\\/][^\s\"'<>]+")
_POSIX_ABSOLUTE = re.compile(r"(?<![:/\w])/(?!api(?:/|$))[^\s\"'<>]+")
_CREDENTIAL_NAMES = frozenset({
    "token", "accesstoken", "refreshtoken", "authorization", "password", "passwd",
    "secret", "clientsecret", "apikey", "credential", "credentials", "privatekey",
    "signingkey", "bearer",
})
_PERSISTED_SECRET_NAMES = _CREDENTIAL_NAMES | {"leasetoken"}
_PATH_NAMES = frozenset({
    "path", "filepath", "absolutepath", "outpath", "outdir", "outputpath",
    "outputdir", "directory", "rootpath", "rundir", "sourcepath", "targetpath",
})
_CONTEXT_LIST_KEYS = ("tools", "items", "domains", "steps", "recipes", "playbooks")
_SUMMARY_REQUIRED_KEYS = (
    "status", "error", "errorCode", "code", "message", "platformVersion",
    "toolContractVersion", "rulepackVersion", "planVersion", "count", "totalCount",
    "totalMatched", "toolCount", "requestedToolCount", "returned", "nextCursor", "cursor",
    "truncated", "task", "recipe",
    "playbook", "tool", "permissions", "tools", "next", "discovery", "items", "domains", "steps",
    "availableTasks", "availableRecipes", "availableDomains", "availablePlaybooks",
)


class ModelOutputError(errors.ToolError):
    """可安全交给调用端的模型输出/本地 artifact 错误，不携带本机路径或凭据。"""

    def __init__(self, code: str, message: str):
        super().__init__(code, message, category="local_result", exit_code=2,
                         recovery="检查当前实例/版本配置；结果失效或完整性错误时重新执行查询。")


def _ctx_env(ctx) -> dict:
    value = getattr(ctx, "env", None)
    if value is None and isinstance(ctx, dict):
        value = ctx.get("env")
    return dict(value or os.environ)


def _ctx_rulepack_version(ctx) -> str:
    value = getattr(ctx, "rulepack_version", None)
    if value is None and isinstance(ctx, dict):
        value = ctx.get("rulepack_version")
    if not isinstance(value, str) or not value:
        raise ModelOutputError("RESULT_CONTEXT_INVALID", "当前上下文缺少规则版本，无法读取本地结果")
    return value


def _binding(env, rulepack_version: str, tool_id: str) -> dict:
    if not isinstance(tool_id, str) or not tool_id or len(tool_id) > 160:
        raise ModelOutputError("RESULT_CONTEXT_INVALID", "工具标识非法，无法读取本地结果")
    try:
        origin = client.validate_base_url(str(env.get("QIMENG_API_BASE") or ""))
    except (client.ConfigError, TypeError):
        raise ModelOutputError("RESULT_CONTEXT_INVALID", "缺少有效 API 实例配置，无法读取本地结果") from None
    token = env.get("QIMENG_API_TOKEN")
    if not isinstance(token, str) or not token or len(token) > client.TOKEN_MAX_LENGTH \
            or any(ord(ch) < 32 for ch in token) or token != token.strip():
        raise ModelOutputError("RESULT_CONTEXT_INVALID", "缺少有效 API 客户端配置，无法读取本地结果")
    return {
        "toolId": tool_id,
        "apiInstanceSha256": hashlib.sha256(origin.encode("utf-8")).hexdigest(),
        "credentialSha256": hashlib.sha256(token.encode("utf-8")).hexdigest(),
        "platformVersion": versions.PLATFORM_VERSION,
        "toolContractVersion": versions.TOOL_CONTRACT_VERSION,
        "rulepackVersion": rulepack_version,
    }


def _state_root(env, binding: dict) -> Path:
    raw = env.get(STATE_DIR_ENV)
    identity = (binding["apiInstanceSha256"][:20] + "-"
                + binding["credentialSha256"][:20])
    if raw:
        if not isinstance(raw, str) or any(ord(ch) < 32 for ch in raw):
            raise ModelOutputError("RESULT_STATE_DIR_INVALID", "QIMENG_AI_STATE_DIR 配置非法")
        candidate = Path(raw)
        if not candidate.is_absolute():
            raise ModelOutputError("RESULT_STATE_DIR_INVALID", "QIMENG_AI_STATE_DIR 必须是绝对路径")
        base = candidate
    else:
        base = Path(tempfile.gettempdir()) / "qimeng-ai"
    try:
        base.mkdir(parents=True, exist_ok=True)
        trusted_base = base.resolve(strict=True)
        instance_root = trusted_base / "model-output" / identity
        # A configured state root is trusted. Everything created below it is owned by
        # this module and may not redirect to a path outside that root.
        for directory in (trusted_base / "model-output", instance_root, instance_root / "results"):
            if directory.exists() and pathguard.is_linkish(directory):
                raise ModelOutputError("RESULT_STATE_DIR_INVALID", "本地结果目录包含链接或重解析点")
            directory.mkdir(exist_ok=True)
        root = instance_root.resolve(strict=True)
        results = (root / "results").resolve(strict=True)
        results.relative_to(root)
        return root
    except ModelOutputError:
        raise
    except (OSError, ValueError):
        raise ModelOutputError("RESULT_STATE_DIR_INVALID", "本地结果目录不可用或越过授权根") from None


def _results_dir(root: Path) -> Path:
    result = root / "results"
    if pathguard.is_linkish(result):
        raise ModelOutputError("RESULT_STATE_DIR_INVALID", "本地结果目录包含链接或重解析点")
    try:
        resolved = result.resolve(strict=True)
        resolved.relative_to(root)
    except (OSError, ValueError):
        raise ModelOutputError("RESULT_STATE_DIR_INVALID", "本地结果目录不可用或越过授权根") from None
    if not resolved.is_dir():
        raise ModelOutputError("RESULT_STATE_DIR_INVALID", "本地结果目录不可用")
    return resolved


def _json_bytes(value) -> bytes:
    try:
        return json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True,
                          separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError, UnicodeError):
        raise ModelOutputError("RESULT_NOT_JSON", "工具结果不是可保存的 JSON 数据") from None


def _json_size(value) -> int:
    # ASCII escaping gives a conservative bound for the UTF-8 JSON the CLI/MCP emits.
    try:
        return len(json.dumps(value, ensure_ascii=True, allow_nan=False, sort_keys=True,
                              separators=(",", ":")).encode("ascii"))
    except (TypeError, ValueError):
        raise ModelOutputError("RESULT_NOT_JSON", "工具结果不是合法 JSON 数据") from None


def _output_budget(env) -> int:
    raw = env.get(OUTPUT_BUDGET_ENV)
    if raw in (None, ""):
        return DEFAULT_OUTPUT_BUDGET
    try:
        value = int(raw)
    except (TypeError, ValueError):
        raise ModelOutputError("OUTPUT_BUDGET_INVALID", OUTPUT_BUDGET_ENV + " 必须是字节数") from None
    if not 1024 <= value <= MAX_OUTPUT_BUDGET:
        raise ModelOutputError("OUTPUT_BUDGET_INVALID", OUTPUT_BUDGET_ENV + " 必须在 1024..1048576")
    return value


def _key_file(root: Path) -> Path:
    path = root / ".model-output-integrity-key"
    if path.exists() and pathguard.is_linkish(path):
        raise ModelOutputError("RESULT_STORE_INVALID", "本地结果完整性校验失败")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    try:
        descriptor = os.open(path, flags, 0o600)
    except FileExistsError:
        pass
    except OSError:
        raise ModelOutputError("RESULT_STORE_UNAVAILABLE", "本地结果完整性校验不可用") from None
    else:
        try:
            os.write(descriptor, secrets.token_bytes(32))
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    if pathguard.is_linkish(path):
        raise ModelOutputError("RESULT_STORE_INVALID", "本地结果完整性校验失败")
    try:
        key = path.read_bytes()
    except OSError:
        raise ModelOutputError("RESULT_STORE_UNAVAILABLE", "本地结果完整性校验不可用") from None
    if len(key) != 32:
        raise ModelOutputError("RESULT_STORE_INVALID", "本地结果完整性校验失败")
    return key


def _result_path(root: Path, reference: str) -> Path:
    match = _RESULT_REF.fullmatch(reference) if isinstance(reference, str) else None
    if not match:
        raise ModelOutputError("RESULT_REF_INVALID", "resultRef 格式非法")
    directory = _results_dir(root)
    path = directory / (reference + ".json")
    if pathguard.is_linkish(path):
        raise ModelOutputError("RESULT_STORE_INVALID", "本地结果完整性校验失败")
    try:
        path.resolve(strict=True).relative_to(directory)
    except (OSError, ValueError):
        raise ModelOutputError("RESULT_NOT_FOUND", "本地结果不存在或已过期") from None
    return path


def _mac_payload(reference: str, binding: dict, created_at: int, result_sha256: str) -> bytes:
    return _json_bytes({
        "formatVersion": ARTIFACT_FORMAT_VERSION,
        "resultRef": reference,
        "binding": binding,
        "createdAt": created_at,
        "resultSha256": result_sha256,
    })


def _clean_expired(directory: Path, now: float) -> list[Path]:
    files = []
    for path in directory.glob("r1_*.json"):
        if pathguard.is_linkish(path):
            continue
        try:
            info = path.stat()
        except OSError:
            continue
        if not stat.S_ISREG(info.st_mode):
            continue
        if now - info.st_mtime > ARTIFACT_TTL_SECONDS:
            try:
                path.unlink()
            except OSError:
                pass
            continue
        files.append(path)
    return files


def _store_result(result, binding: dict, env) -> tuple[str, str | None, int]:
    result_bytes = _json_bytes(result)
    if len(result_bytes) > MAX_ARTIFACT_BYTES:
        raise ModelOutputError("RESULT_TOO_LARGE", "本地结果超过单个 artifact 的 32 MiB 上限")
    root = _state_root(env, binding)
    directory = _results_dir(root)
    now = int(time.time())
    files = _clean_expired(directory, now)
    current_bytes = 0
    for path in files:
        try:
            current_bytes += path.stat().st_size
        except OSError:
            continue
    if len(files) >= MAX_ARTIFACTS:
        raise ModelOutputError("RESULT_STORE_LIMIT", "本地结果缓存达到容量限制，请缩小查询范围后重试")

    key = _key_file(root)
    result_ref = "r1_" + secrets.token_hex(16)
    digest = hashlib.sha256(result_bytes).hexdigest()
    mac = hmac.new(key, _mac_payload(result_ref, binding, now, digest), hashlib.sha256).hexdigest()
    envelope = {
        "formatVersion": ARTIFACT_FORMAT_VERSION,
        "resultRef": result_ref,
        "binding": binding,
        "createdAt": now,
        "resultSha256": digest,
        "integrityMac": mac,
        "result": result,
    }
    envelope_bytes = _json_bytes(envelope)
    if len(envelope_bytes) > MAX_ARTIFACT_BYTES:
        raise ModelOutputError("RESULT_TOO_LARGE", "本地结果超过单个 artifact 的 32 MiB 上限")
    if current_bytes + len(envelope_bytes) > MAX_TOTAL_BYTES:
        raise ModelOutputError("RESULT_STORE_LIMIT", "本地结果缓存达到容量限制，请缩小查询范围后重试")
    target = directory / (result_ref + ".json")
    descriptor = None
    temporary = None
    try:
        descriptor, name = tempfile.mkstemp(prefix=".result-", suffix=".tmp", dir=str(directory))
        temporary = Path(name)
        with os.fdopen(descriptor, "wb") as handle:
            descriptor = None
            handle.write(envelope_bytes)
            handle.flush()
            os.fsync(handle.fileno())
        if target.exists() or pathguard.is_linkish(target):
            raise ModelOutputError("RESULT_STORE_INVALID", "本地结果标识冲突，未覆盖已有数据")
        # Hard-link publication is atomic and does not overwrite a pre-existing artifact.
        os.link(temporary, target)
        temporary.unlink()
        temporary = None
    except ModelOutputError:
        raise
    except OSError:
        raise ModelOutputError("RESULT_STORE_UNAVAILABLE", "本地结果无法安全保存") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary is not None:
            try:
                temporary.unlink()
            except OSError:
                pass
    plan_ref = "p1_" + result_ref[3:] if isinstance(result, dict) and isinstance(result.get("plan"), dict) else None
    return result_ref, plan_ref, len(result_bytes)


def _read_envelope(reference: str, binding: dict, env, *, source_tool_any: bool = False) -> dict:
    root = _state_root(env, binding)
    path = _result_path(root, reference)
    try:
        info = path.stat()
        if info.st_size > MAX_ARTIFACT_BYTES or not stat.S_ISREG(info.st_mode):
            raise ModelOutputError("RESULT_STORE_INVALID", "本地结果完整性校验失败")
        raw = path.read_bytes()
        envelope = json.loads(raw.decode("utf-8"))
    except ModelOutputError:
        raise
    except (OSError, UnicodeError, ValueError):
        raise ModelOutputError("RESULT_STORE_INVALID", "本地结果完整性校验失败") from None
    if not isinstance(envelope, dict) or envelope.get("formatVersion") != ARTIFACT_FORMAT_VERSION \
            or envelope.get("resultRef") != reference:
        raise ModelOutputError("RESULT_BINDING_MISMATCH", "本地结果与当前实例、工具或版本不匹配")
    stored_binding = envelope.get("binding")
    if source_tool_any:
        source_tool = stored_binding.get("toolId") if isinstance(stored_binding, dict) else None
        if not isinstance(source_tool, str) or not source_tool:
            raise ModelOutputError("RESULT_BINDING_MISMATCH", "本地结果与当前实例、工具或版本不匹配")
        expected_binding = _binding(env, binding["rulepackVersion"], source_tool)
    else:
        expected_binding = binding
    if stored_binding != expected_binding:
        raise ModelOutputError("RESULT_BINDING_MISMATCH", "本地结果与当前实例、工具或版本不匹配")
    result = envelope.get("result")
    result_bytes = _json_bytes(result)
    digest = hashlib.sha256(result_bytes).hexdigest()
    expected_mac = hmac.new(
        _key_file(root),
        _mac_payload(reference, expected_binding, envelope.get("createdAt"), digest),
        hashlib.sha256,
    ).hexdigest()
    if not hmac.compare_digest(str(envelope.get("resultSha256") or ""), digest) \
            or not hmac.compare_digest(str(envelope.get("integrityMac") or ""), expected_mac):
        raise ModelOutputError("RESULT_TAMPERED", "本地结果完整性校验失败；请重新执行原查询")
    if int(time.time()) - int(envelope.get("createdAt") or 0) > ARTIFACT_TTL_SECONDS:
        raise ModelOutputError("RESULT_EXPIRED", "本地结果已过期，请重新执行原查询")
    return result


def _key_name(value) -> str:
    return re.sub(r"[^a-z0-9]", "", str(value).casefold())


def _replace_absolute_paths(value: str) -> tuple[str, bool]:
    if value.startswith("file://"):
        return "<本机路径已隐藏>", True
    output = _WINDOWS_ABSOLUTE.sub("<本机路径已隐藏>", value)
    output = _UNC_ABSOLUTE.sub("<本机路径已隐藏>", output)
    output = _POSIX_ABSOLUTE.sub("<本机路径已隐藏>", output)
    # A path-shaped value under a path key is hidden even when it is a relative path.
    return output, output != value


def _model_safe(value, *, token: str = "", path_key: bool = False):
    """递归脱敏模型可见数据；本机绝对路径和凭据字段永不从此函数返回。"""
    if isinstance(value, dict):
        cleaned = {}
        redacted_paths = False
        redacted_credentials = False
        for key, item in value.items():
            if _key_name(key) in _CREDENTIAL_NAMES:
                cleaned[key] = "<凭据已隐藏>"
                redacted_credentials = True
                continue
            safe_key, key_path_redacted = _replace_absolute_paths(str(key))
            if safe_key in cleaned:
                safe_key = safe_key + "<字段名已隐藏>"
            safe, flags = _model_safe(item, token=token, path_key=_key_name(key) in _PATH_NAMES)
            cleaned[safe_key] = safe
            redacted_paths = redacted_paths or key_path_redacted or flags.get("paths", False)
            redacted_credentials = redacted_credentials or flags.get("credentials", False)
        flags = {"paths": redacted_paths, "credentials": redacted_credentials}
        return cleaned, flags
    if isinstance(value, list):
        cleaned = []
        paths = credentials = False
        for item in value:
            safe, flags = _model_safe(item, token=token)
            cleaned.append(safe)
            paths = paths or flags.get("paths", False)
            credentials = credentials or flags.get("credentials", False)
        return cleaned, {"paths": paths, "credentials": credentials}
    if isinstance(value, str):
        output = value
        credentials = False
        if token:
            output = output.replace(token, "<凭据已隐藏>")
            credentials = output != value
        output, bearer_changed = _BEARER.subn("Bearer <凭据已隐藏>", output)
        credentials = credentials or bearer_changed > 0
        if path_key:
            # Keep URL and API route fields intact; only absolute local filesystem paths are redacted.
            if output.startswith("file://"):
                output = "<本机路径已隐藏>"
            elif output.startswith("/") and not output.startswith("/api/"):
                output = "<本机路径已隐藏>"
            elif re.match(r"^[A-Za-z]:[\\/]", output) or output.startswith("\\\\"):
                output = "<本机路径已隐藏>"
        else:
            output, path_changed = _replace_absolute_paths(output)
            return output, {"paths": path_changed, "credentials": credentials}
        return output, {"paths": output != value, "credentials": credentials}
    return value, {"paths": False, "credentials": False}


def _clip_summary(value, *, depth: int = 0, max_string: int = 220):
    if isinstance(value, str):
        if len(value) <= max_string:
            return value
        return {"preview": value[:max_string], "truncated": True,
                "omittedChars": len(value) - max_string}
    if isinstance(value, list):
        limit = 4 if depth < 2 else 2
        clipped = [_clip_summary(item, depth=depth + 1, max_string=max_string)
                   for item in value[:limit]]
        if len(value) > limit:
            clipped.append({"truncated": True, "omittedItems": len(value) - limit})
        return clipped
    if isinstance(value, dict):
        if depth >= 4:
            return {"kind": "object", "truncated": True, "omittedKeys": len(value)}
        limit = 12 if depth < 2 else 6
        items = list(value.items())
        clipped = {key: _clip_summary(item, depth=depth + 1, max_string=max_string)
                   for key, item in items[:limit]}
        if len(items) > limit:
            clipped["_truncated"] = True
            clipped["_omittedKeys"] = len(items) - limit
        return clipped
    return value


def _compact_tool(item: dict) -> dict:
    keys = ("id", "version", "domain", "kind", "summary", "status", "reason", "availability",
            "permission", "permissionDescription", "permitted", "readOnly", "sideEffects",
            "requiresConfirmation", "confirmationMode", "disabled", "deprecated")
    result = {key: item[key] for key in keys if key in item}
    requirements = item.get("goApiRequirements")
    if isinstance(requirements, list):
        result["goApiRequirements"] = [
            {key: _clip_summary(requirement[key], max_string=100)
             for key in ("path", "method", "requiredBodyFields", "parameters")
             if key in requirement}
            for requirement in requirements[:3] if isinstance(requirement, dict)
        ]
        if len(requirements) > 3:
            result["requirementsTruncated"] = len(requirements) - 3
    if "schemaSummary" in item:
        result["schemaOnDemand"] = "platform.tool.describe"
    return {key: _clip_summary(value, max_string=180) for key, value in result.items()}


def _compact_schema(schema, *, depth: int = 0) -> dict:
    if not isinstance(schema, dict):
        return {"kind": type(schema).__name__, "truncated": True}
    result = {key: schema[key] for key in (
        "type", "additionalProperties", "minLength", "maxLength", "minimum", "maximum",
        "minItems", "maxItems",
    ) if key in schema}
    for key in ("required", "enum"):
        values = schema.get(key)
        if isinstance(values, list):
            result[key] = [_clip_summary(value, max_string=80) for value in values[:24]]
            if len(values) > 24:
                result[key + "Truncated"] = len(values) - 24
                result["truncated"] = True
    if "default" in schema:
        result["default"] = _clip_summary(schema["default"], max_string=80)
    description = schema.get("description")
    if isinstance(description, str):
        result["description"] = description[:160]
        if len(description) > 160:
            result["descriptionTruncated"] = len(description) - 160
    properties = schema.get("properties")
    if isinstance(properties, dict):
        selected = list(properties.items())[:18]
        if depth < 2:
            result["properties"] = {
                name: _compact_schema(value, depth=depth + 1)
                for name, value in selected
            }
        else:
            result["properties"] = [name for name, _value in selected]
        if len(properties) > len(selected):
            result["propertiesTruncated"] = len(properties) - len(selected)
            result["truncated"] = True
    if isinstance(schema.get("items"), dict):
        result["items"] = _compact_schema(schema["items"], depth=depth + 1)
    return result


def _compact_described_tool(item: dict) -> dict:
    keys = ("id", "version", "kind", "domain", "status", "permission", "readOnly",
            "sideEffects", "requiresConfirmation", "confirmationMode", "availability",
            "description", "reason", "capabilityStatus", "enabled", "disabledReason",
            "deprecated", "replacedBy", "handlerRef", "implementationAvailable")
    result = {key: item[key] for key in keys if key in item}
    for key in ("inputSchema", "outputSchema"):
        if isinstance(item.get(key), dict):
            result[key] = _compact_schema(item[key])
    for key in ("examples", "goApiRequirements", "rulesRefs", "missingRuleSections"):
        values = item.get(key)
        if isinstance(values, list):
            result[key] = [_clip_summary(value, max_string=120) for value in values[:4]]
            if len(values) > 4:
                result[key + "Truncated"] = len(values) - 4
    if isinstance(item.get("rulesExcerpt"), dict):
        result["rulesExcerptAvailable"] = sorted(item["rulesExcerpt"].keys())
        result["rulesOnDemand"] = "platform.rules"
    result["detailsOnDemand"] = "platform.result.read"
    return {key: _clip_summary(value, max_string=160) for key, value in result.items()}


def _compact_recipe(value):
    if not isinstance(value, dict):
        return _clip_summary(value)
    result = {key: value[key] for key in (
        "recipe", "id", "version", "title", "whenToUse", "target", "requires", "permissions",
        "inputs", "resumePolicy", "status",
    ) if key in value}
    for key in ("steps", "gates", "artifacts", "readback"):
        entries = value.get(key)
        if isinstance(entries, list):
            result[key] = [_clip_summary(item, max_string=150) for item in entries[:8]]
            if len(entries) > 8:
                result[key + "Omitted"] = len(entries) - 8
    return _clip_summary(result, max_string=180)


def _summary(result, *, tool_id: str, result_ref: str | None,
             plan_ref: str | None, raw_bytes: int, budget: int, token: str = "",
             store_error: ModelOutputError | None = None) -> dict:
    safe, flags = _model_safe(result, token=token)
    summary: dict = {"truncated": True, "toolId": tool_id, "resultBytes": raw_bytes}
    if result_ref:
        summary["resultRef"] = result_ref
    if plan_ref:
        summary["planRef"] = plan_ref
    if isinstance(safe, list):
        summary.update({"kind": "list", "count": len(safe),
                        "sample": [_clip_summary(item) for item in safe[:3]]})
        if len(safe) > 3:
            summary["omittedItems"] = len(safe) - 3
    elif isinstance(safe, dict):
        for key in _SUMMARY_REQUIRED_KEYS:
            if key not in safe:
                continue
            value = safe[key]
            if key == "tools" and isinstance(value, list):
                summary[key] = [_compact_tool(item) if isinstance(item, dict)
                                else _clip_summary(item) for item in value[:16]]
                if len(value) > 16:
                    summary["omittedCounts"] = {**summary.get("omittedCounts", {}),
                                                "tools": len(value) - 16}
            elif key == "tool" and isinstance(value, dict):
                summary[key] = _compact_described_tool(value)
            elif key in ("recipe", "playbook"):
                summary[key] = _compact_recipe(value) if key == "recipe" else _clip_summary(value)
            elif key in _CONTEXT_LIST_KEYS and isinstance(value, list):
                summary[key] = [_clip_summary(item) for item in value[:8]]
                if len(value) > 8:
                    summary["omittedCounts"] = {**summary.get("omittedCounts", {}),
                                                key: len(value) - 8}
            else:
                summary[key] = _clip_summary(value)
        counts = {}
        omitted_fields = []
        for key, value in safe.items():
            if key not in summary:
                omitted_fields.append(key)
            if isinstance(value, list):
                counts[key] = len(value)
        if counts:
            summary["collectionCounts"] = counts
        if omitted_fields:
            summary["omittedFields"] = omitted_fields[:50]
            if len(omitted_fields) > 50:
                summary["omittedFieldCount"] = len(omitted_fields)
    else:
        summary["kind"] = type(safe).__name__
        summary["value"] = _clip_summary(safe)
    if flags.get("paths"):
        summary["localPathsRedacted"] = True
    if flags.get("credentials"):
        summary["credentialsRedacted"] = True
    if not result_ref:
        summary["continuationUnavailable"] = {
            "code": store_error.code if store_error else "RESULT_STORE_LIMIT",
            "message": store_error.message if store_error else "本地结果无法保存续读副本，请缩小查询范围后重试",
        }
    if not plan_ref and isinstance(result, dict) and isinstance(result.get("plan"), dict):
        summary["planRefUnavailable"] = {
            "code": store_error.code if store_error else "PLAN_REF_UNAVAILABLE",
            "message": "完整计划未能保存在本地，当前摘要不可执行；请重新 prepare",
        }
        summary["executable"] = False

    # Fit within the configured budget by trimming optional samples/sections, never JSON text.
    for key in ("rules", "guardrails", "availableAliases", "availableRecipes", "availableTasks",
                "availableDomains", "availablePlaybooks", "playbook"):
        if _json_size(summary) <= budget:
            break
        if key in summary:
            del summary[key]
            summary.setdefault("omittedFields", []).append(key)
    for list_key in ("tools", "items", "domains", "steps", "recipes", "playbooks", "next", "sample"):
        while _json_size(summary) > budget and isinstance(summary.get(list_key), list) \
                and len(summary[list_key]) > 1:
            summary[list_key].pop()
            summary["truncatedSummary"] = True
            omitted_counts = summary.setdefault("summaryOmittedCounts", {})
            omitted_counts[list_key] = omitted_counts.get(list_key, 0) + 1
    if _json_size(summary) > budget and isinstance(summary.get("tools"), list):
        summary["tools"] = [_compact_tool(item) for item in summary["tools"][:1]]
    if _json_size(summary) > budget and isinstance(summary.get("tool"), dict):
        tool = summary["tool"]
        for key in ("examples", "goApiRequirements", "rulesRefs", "rulesExcerptAvailable",
                    "runtimeChecks", "errorCodes", "outputSchema", "availability",
                    "description", "permissionDescription", "reason", "deprecatedReason"):
            tool.pop(key, None)
        schema_summary = tool.get("inputSchema")
        if isinstance(schema_summary, dict) and isinstance(schema_summary.get("properties"), dict):
            properties = schema_summary["properties"]
            names = list(properties)[:8]
            schema_summary["properties"] = {name: properties[name] for name in names}
            if len(properties) > len(names):
                schema_summary["propertiesTruncated"] = len(properties) - len(names)
            required = schema_summary.get("required")
            if isinstance(required, list) and len(required) > 8:
                schema_summary["required"] = required[:8]
                schema_summary["requiredTruncated"] = len(required) - 8
            schema_summary["truncated"] = True
    # The irreducible metadata is small; if user configured an unusually small budget, fail
    # explicitly instead of slicing a JSON string or dropping status/version/ref fields.
    if _json_size(summary) > budget:
        raise ModelOutputError("OUTPUT_BUDGET_TOO_SMALL", "输出预算不足以保留必需摘要字段")
    return summary


def present_result(tool_id: str, result, *, env=None, rulepack_version: str):
    """对工具成功结果实施统一响应预算，并保存需要续读或执行的完整结果。"""
    env = dict(os.environ if env is None else env)
    if tool_id == "platform.result.read":
        # Read pages must not be stored again; that would create a ref recursion loop.
        return result
    if not isinstance(rulepack_version, str) or not rulepack_version:
        raise ModelOutputError("RESULT_CONTEXT_INVALID", "当前上下文缺少规则版本，无法生成结果引用")
    token = str(env.get("QIMENG_API_TOKEN") or "")
    safe, flags = _model_safe(result, token=token)
    budget = _output_budget(env)
    is_plan = isinstance(result, dict) and isinstance(result.get("plan"), dict)
    try:
        safe_bytes = _json_bytes(result)
        display = safe
        if (flags.get("paths") or flags.get("credentials")) and not isinstance(display, dict):
            display = {"value": display}
        if flags.get("paths") and isinstance(display, dict):
            display = dict(display)
            display.setdefault("localPathsRedacted", True)
        if flags.get("credentials") and isinstance(display, dict):
            display = dict(display)
            display.setdefault("credentialsRedacted", True)
        safe_size = _json_size(display)
    except ModelOutputError:
        raise
    reference = None
    plan_ref = None
    store_error = None
    if safe_size > budget or is_plan:
        try:
            binding = _binding(env, rulepack_version, tool_id)
            # Artifact values preserve original plans; credential-like fields are rejected
            # inside a plan because replacing them would violate the no-loss execution contract.
            if is_plan:
                _reject_plan_credentials(result["plan"])
            artifact_result, _artifact_flags = _strip_credentials(result, token=token)
            reference, plan_ref, artifact_size = _store_result(artifact_result, binding, env)
        except ModelOutputError as exc:
            store_error = exc
            artifact_size = len(safe_bytes)
    else:
        artifact_size = len(safe_bytes)

    if safe_size <= budget and not is_plan:
        # Preserve normal small response fields and ordering; only add the opaque planRef.
        output = display
        if store_error:
            if isinstance(output, dict):
                output["planRefUnavailable"] = store_error.to_dict()
            return output
        return output

    if is_plan and plan_ref:
        output = display
        if isinstance(output, dict):
            output = dict(output)
            output["planRef"] = plan_ref
        if _json_size(output) <= budget:
            return output
    if is_plan and store_error and safe_size <= budget \
            and not flags.get("paths") and not flags.get("credentials"):
        # This is still a complete small plan. Keep the legacy plain-plan route available;
        # add a terse note only when it fits the same response budget.
        output = dict(display) if isinstance(display, dict) else {"value": display}
        marked = dict(output)
        marked["planRefUnavailable"] = True
        return marked if _json_size(marked) <= budget else output

    if reference:
        try:
            binding = _binding(env, rulepack_version, tool_id)
            # Use the persisted credential-free copy's size as the stable resultBytes value.
            persisted = _read_envelope(reference, binding, env)
            artifact_size = len(_json_bytes(persisted))
        except ModelOutputError:
            # A freshly written artifact must be readable; fail closed if the filesystem changed.
            raise ModelOutputError("RESULT_STORE_INVALID", "本地结果保存后无法通过完整性校验") from None
    output = _summary(result, tool_id=tool_id, result_ref=reference,
                      plan_ref=plan_ref, raw_bytes=artifact_size, budget=budget,
                      token=token, store_error=store_error)
    return output


def _strip_credentials(value, *, token: str = ""):
    """本地 artifact 不保留凭据字段或当前进程的 API token。"""
    flags = {"credentials": False}
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if _key_name(key) in _PERSISTED_SECRET_NAMES:
                flags["credentials"] = True
                continue
            cleaned, nested = _strip_credentials(item, token=token)
            result[key] = cleaned
            flags["credentials"] = flags["credentials"] or nested["credentials"]
        return result, flags
    if isinstance(value, list):
        result = []
        for item in value:
            cleaned, nested = _strip_credentials(item, token=token)
            result.append(cleaned)
            flags["credentials"] = flags["credentials"] or nested["credentials"]
        return result, flags
    if isinstance(value, str):
        if token and token in value or _BEARER.search(value):
            raise ModelOutputError("RESULT_CREDENTIAL_PRESENT", "结果包含凭据，已拒绝持久化")
        return value, flags
    return value, flags


def _reject_plan_credentials(plan: dict) -> None:
    if isinstance(plan, dict):
        for key, value in plan.items():
            if _key_name(key) in _PERSISTED_SECRET_NAMES:
                raise ModelOutputError("PLAN_CREDENTIAL_PRESENT", "计划包含凭据，已拒绝创建 planRef")
            _reject_plan_credentials(value)
    elif isinstance(plan, list):
        for item in plan:
            _reject_plan_credentials(item)
    elif isinstance(plan, str) and _BEARER.search(plan):
        raise ModelOutputError("PLAN_CREDENTIAL_PRESENT", "计划包含凭据，已拒绝创建 planRef")


def _pointer_tokens(path: str) -> list[str]:
    if path == "":
        return []
    if not isinstance(path, str) or len(path) > 2048 or not path.startswith("/"):
        raise ModelOutputError("RESULT_PATH_INVALID", "path 必须是 JSON Pointer（空字符串表示根结果）")
    tokens = []
    for token in path[1:].split("/"):
        if re.search(r"~(?![01])", token):
            raise ModelOutputError("RESULT_PATH_INVALID", "path 的 JSON Pointer 转义非法")
        tokens.append(token.replace("~1", "/").replace("~0", "~"))
    return tokens


def _pointer_value(value, tokens: list[str]):
    current = value
    for token in tokens:
        if isinstance(current, dict) and token in current:
            current = current[token]
        elif isinstance(current, list) and token.isdigit() and int(token) < len(current):
            current = current[int(token)]
        else:
            raise ModelOutputError("RESULT_PATH_NOT_FOUND", "path 在本地结果中不存在")
    return current


def _pointer_child(path: str, segment) -> str:
    escaped = str(segment).replace("~", "~0").replace("/", "~1")
    return path + "/" + escaped


def _safe_pointer(path: str) -> tuple[str | None, bool]:
    """Keep JSON Pointer useful while preventing a pointer from disclosing path-like keys."""
    tokens = _pointer_tokens(path)
    for token in tokens:
        if token.startswith("file://") or re.match(r"^[A-Za-z]:[\\/]", token) \
                or token.startswith("\\\\") \
                or (token.startswith("/") and not token.startswith("/api/")):
            return None, True
    return path, False


def _safe_omitted(entries: list[dict]) -> list[dict]:
    result = []
    for entry in entries:
        safe_entry, _ = _model_safe({key: value for key, value in entry.items() if key != "path"})
        pointer = entry.get("path")
        if isinstance(pointer, str):
            safe_path, redacted = _safe_pointer(pointer)
            if safe_path is not None:
                safe_entry["path"] = safe_path
            elif redacted:
                safe_entry["pathRedacted"] = True
        result.append(safe_entry)
    return result


def _with_safe_pointer(response: dict, path: str) -> dict:
    safe_path, redacted = _safe_pointer(path)
    if safe_path is not None:
        response["path"] = safe_path
    elif redacted:
        response["pathRedacted"] = True
    return response


def _with_redaction_markers(response: dict, flags: dict) -> dict:
    if flags.get("paths"):
        response["localPathsRedacted"] = True
    if flags.get("credentials"):
        response["credentialsRedacted"] = True
    return response


def _read_cursor(value) -> int:
    if value in (None, ""):
        return 0
    if not isinstance(value, str) or not re.fullmatch(r"o:[0-9]{1,12}", value):
        raise ModelOutputError("RESULT_CURSOR_INVALID", "cursor 格式非法")
    return int(value[2:])


def _read_page(result_ref: str, target, path: str, cursor: int, limit: int) -> dict:
    if isinstance(target, dict):
        entries = list(target.items())
        kind = "object"
    elif isinstance(target, list):
        entries = list(enumerate(target))
        kind = "array"
    else:
        if isinstance(target, str):
            start = cursor
            high = min(len(target), start + limit)
            # A string is returned as valid JSON text in code-point-safe chunks.
            while high > start:
                value = target[start:high]
                response = {"resultRef": result_ref, "kind": "string",
                            "count": len(target), "offset": start, "value": value,
                            "nextCursor": f"o:{high}" if high < len(target) else None,
                            "truncated": high < len(target)}
                response = _with_safe_pointer(response, path)
                safe_value, flags = _model_safe(value)
                response["value"] = safe_value
                response = _with_redaction_markers(response, flags)
                if _json_size(response) <= MAX_PAGE_BYTES:
                    return response
                high -= max(1, (high - start) // 4)
            raise ModelOutputError("RESULT_PAGE_ITEM_TOO_LARGE", "字符串片段无法放入续读页")
        response = {"resultRef": result_ref, "kind": "scalar", "count": 1,
                    "value": target, "nextCursor": None, "truncated": False}
        response = _with_safe_pointer(response, path)
        safe_value, flags = _model_safe(target)
        response["value"] = safe_value
        response = _with_redaction_markers(response, flags)
        safe = response
        if _json_size(safe) > MAX_PAGE_BYTES:
            raise ModelOutputError("RESULT_PAGE_ITEM_TOO_LARGE", "结果项超过续读页预算，请使用更深的 path")
        return safe

    if cursor > len(entries):
        raise ModelOutputError("RESULT_CURSOR_INVALID", "cursor 超出本地结果范围")
    end = min(len(entries), cursor + limit)
    selected = {}
    omitted = []
    index = cursor
    while index < end:
        key, item = entries[index]
        candidate = dict(selected)
        candidate[key] = item
        response = {
            "resultRef": result_ref,
            "kind": kind,
            "count": len(entries),
            "offset": cursor,
            "value": candidate if kind == "object" else list(candidate.values()),
            "omitted": omitted,
            "nextCursor": f"o:{index + 1}" if index + 1 < len(entries) else None,
            "truncated": index + 1 < len(entries),
        }
        response = _with_safe_pointer(response, path)
        safe_value, flags = _model_safe(response["value"])
        safe_omitted = _safe_omitted(response["omitted"])
        safe_response = {**response, "value": safe_value, "omitted": safe_omitted}
        safe_response = _with_redaction_markers(safe_response, flags)
        if _json_size(safe_response) > MAX_PAGE_BYTES:
            if not selected:
                pointer = _pointer_child(path, key)
                safe_child, path_redacted = _safe_pointer(pointer)
                entry = {"path": safe_child, "itemBytes": _json_size(item), "readAtPath": True}
                if path_redacted:
                    entry.pop("path", None)
                    entry["pathRedacted"] = True
                attempted = omitted + [entry]
                attempted_response = _with_safe_pointer({
                    "resultRef": result_ref, "kind": kind, "count": len(entries),
                    "offset": cursor,
                    "value": selected if kind == "object" else list(selected.values()),
                    "omitted": _safe_omitted(attempted),
                    "nextCursor": f"o:{index + 1}" if index + 1 < len(entries) else None,
                    "truncated": index + 1 < len(entries) or bool(attempted),
                }, path)
                if _json_size(attempted_response) > MAX_PAGE_BYTES:
                    break
                omitted.append(entry)
                index += 1
                continue
            break
        selected = candidate
        index += 1

    # If the page only contains omitted oversize values, advance past them and make the
    # point path explicit so no data disappears silently.
    next_offset = index
    value = selected if kind == "object" else list(selected.values())
    response = {
        "resultRef": result_ref,
        "kind": kind,
        "count": len(entries),
        "offset": cursor,
        "value": value,
        "omitted": omitted,
        "nextCursor": f"o:{next_offset}" if next_offset < len(entries) else None,
        "truncated": next_offset < len(entries) or bool(omitted),
    }
    response = _with_safe_pointer(response, path)
    safe_value, flags = _model_safe(response["value"])
    safe_omitted = _safe_omitted(response["omitted"])
    safe_response = {**response, "value": safe_value, "omitted": safe_omitted}
    safe_response = _with_redaction_markers(safe_response, flags)
    if _json_size(safe_response) > MAX_PAGE_BYTES:
        raise ModelOutputError("RESULT_PAGE_TOO_LARGE", "续读页超过 12 KiB，请减小 limit")
    return safe_response


def read_result(ctx, payload: dict) -> dict:
    """读取本地结果的有界 JSON 页；path 使用 RFC 6901 JSON Pointer。"""
    if not isinstance(payload, dict):
        raise ModelOutputError("RESULT_INPUT_INVALID", "result.read 输入必须是对象")
    result_ref = payload.get("resultRef")
    if not isinstance(result_ref, str) or not _RESULT_REF.fullmatch(result_ref):
        raise ModelOutputError("RESULT_REF_INVALID", "resultRef 格式非法")
    env = _ctx_env(ctx)
    rulepack_version = _ctx_rulepack_version(ctx)
    binding = _binding(env, rulepack_version, "platform.result.read")
    result = _read_envelope(result_ref, binding, env, source_tool_any=True)
    path = payload.get("path", "")
    if not isinstance(path, str):
        raise ModelOutputError("RESULT_PATH_INVALID", "path 必须是 JSON Pointer 字符串")
    target = _pointer_value(result, _pointer_tokens(path))
    cursor = _read_cursor(payload.get("cursor"))
    limit = payload.get("limit", MAX_PAGE_ITEMS)
    if type(limit) is not int or not 1 <= limit <= MAX_PAGE_ITEMS:
        raise ModelOutputError("RESULT_LIMIT_INVALID", "limit 必须在 1..500")
    return _read_page(result_ref, target, path, cursor, limit)


def resolve_plan_ref(ref, *, env=None, rulepack_version: str, tool_id: str) -> dict:
    """由可信执行端还原原始完整 plan；不返回模型上下文、不截断或重建计划。"""
    match = _PLAN_REF.fullmatch(ref) if isinstance(ref, str) else None
    if not match:
        raise ModelOutputError("PLAN_REF_INVALID", "planRef 格式非法")
    result_ref = "r1_" + match.group(1)
    env = dict(os.environ if env is None else env)
    binding = _binding(env, rulepack_version, tool_id)
    result = _read_envelope(result_ref, binding, env, source_tool_any=True)
    plan = result.get("plan") if isinstance(result, dict) else None
    if not isinstance(plan, dict):
        raise ModelOutputError("PLAN_REF_INVALID", "planRef 对应结果不含完整 plan")
    if plan.get("tool") != tool_id:
        raise ModelOutputError("PLAN_REF_TOOL_MISMATCH", "planRef 与当前执行工具不匹配")
    return plan

