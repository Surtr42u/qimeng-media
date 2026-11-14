"""Trusted readback for unresolved writes; read-only GETs, never creates or completes.

回读只沿 pending identity 中的原操作定位字段（uploadId/assetId/authorId/jobId 等）
查询正式 API，把结果收敛为三种判定：
- confirmed_applied：可验证原操作已经生效；
- confirmed_not_applied：可验证原操作尚未生效；
- uncertain：无法确证（含 HTTP/网络/解析失败、404 语义不明、候选不唯一）。

本模块只发 GET/只读查询，绝不调用 upload complete、写入或维护终态接口。
任何失败都返回 uncertain，不把“查不到”当成“已执行”。
"""
from __future__ import annotations

from datetime import datetime, timezone
from urllib import parse

from . import client as http_client
from . import errors, plans, task_controls

CONFIRMED_APPLIED = "confirmed_applied"
CONFIRMED_NOT_APPLIED = "confirmed_not_applied"
UNCERTAIN = "uncertain"

UPLOAD_CANDIDATE_LIMIT = 5
ASSET_QUERY_LIMIT = 200
# 入库时间与操作开始时间在同一主机；留出 5 分钟时钟/接收延迟余量。
UPLOAD_CLOCK_SKEW_MS = 5 * 60 * 1000
TERMINAL_JOB_STATUSES = ("completed", "partial", "failed")
NON_TERMINAL_JOB_STATUSES = ("queued", "inventory", "research", "planned", "applying",
                             "verifying", "waiting_evidence", "waiting_authorization",
                             "waiting_device", "paused")


def _result(tool, status, reason, *, readback=None):
    return {
        "tool": tool,
        "status": status,
        "verdict": status,
        "reason": reason,
        "readback": readback or {},
    }


def _text(identity: dict, key: str) -> str:
    value = identity.get(key)
    return value if isinstance(value, str) and value else ""


def _int(identity: dict, key: str):
    value = identity.get(key)
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _iso_millis(value):
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return round(parsed.timestamp() * 1000)


def _client(env):
    base = str(env.get("QIMENG_API_BASE") or "")
    token = str(env.get("QIMENG_API_TOKEN") or "")
    missing = [name for name, value in (("QIMENG_API_BASE", base), ("QIMENG_API_TOKEN", token))
               if not value]
    if missing:
        raise errors.configuration_error("缺少环境变量：" + ", ".join(missing))
    try:
        validated = http_client.validate_base_url(base)
    except http_client.ConfigError as exc:
        # 消息只含变量名，不回显配置值。
        raise errors.configuration_error(str(exc)) from None
    if len(token) > http_client.TOKEN_MAX_LENGTH or any(ord(ch) < 32 for ch in token):
        raise errors.configuration_error("QIMENG_API_TOKEN 非法：含控制字符或过长")
    if token != token.strip():
        raise errors.configuration_error("QIMENG_API_TOKEN 首尾不能有空白")
    return http_client.ApiClient(validated, token)


def _get(client, path: str, query: dict | None = None):
    """只读 GET：返回 (data, problem)；problem 为 None 表示成功，int 为 HTTP 状态。"""
    try:
        return client.get(path, query), None
    except http_client.HTTPStatusError as exc:
        return None, exc.status
    except Exception:  # 网络/超时/解析/响应超限：一律按不确定处理
        return None, "error"


def _asset_path(asset_id: str) -> str:
    return "/api/v1/assets/" + parse.quote(asset_id, safe="")


def _asset_detail(client, asset_id: str):
    data, problem = _get(client, _asset_path(asset_id))
    return (data if isinstance(data, dict) else None), problem


def _author_index(client):
    data, problem = _get(client, "/api/v1/authors")
    if problem is not None or not isinstance(data, list):
        return None, problem or "invalid_response"
    index = {}
    for item in data:
        if isinstance(item, dict) and isinstance(item.get("id"), str):
            index[item["id"]] = item
    return index, None


def _readback_upload(client, identity, _evidence):
    upload_id = _text(identity, "uploadId")
    if not upload_id:
        return _result("asset.upload", UNCERTAIN, "missing_identity.uploadId")
    data, problem = _get(client, "/api/v1/uploads/" + parse.quote(upload_id, safe=""))
    session_missing = False
    upload_state = {}
    if problem == 404:
        # 会话可能已过期/被清理；仍按原 identity 查询资产，不能凭 404 断言未执行。
        session_missing = True
    elif problem is not None:
        return _result("asset.upload", UNCERTAIN, "upload_status_unavailable",
                       readback={"uploadId": upload_id})
    elif not isinstance(data, dict):
        return _result("asset.upload", UNCERTAIN, "invalid_upload_status",
                       readback={"uploadId": upload_id})
    else:
        offset = data.get("offset")
        size = data.get("size")
        if (not isinstance(offset, int) or isinstance(offset, bool)
                or not isinstance(size, int) or isinstance(size, bool)):
            return _result("asset.upload", UNCERTAIN, "invalid_upload_offset",
                           readback={"uploadId": upload_id})
        if offset < size:
            return _result("asset.upload", CONFIRMED_NOT_APPLIED, "upload_incomplete",
                           readback={"uploadId": upload_id, "offset": offset, "size": size})
        if offset != size:
            return _result("asset.upload", UNCERTAIN, "upload_offset_out_of_range",
                           readback={"uploadId": upload_id, "offset": offset, "size": size})
        upload_state = {"offset": offset, "size": size}
    file_name = _text(identity, "fileName")
    library_id = _text(identity, "libraryId")
    expected_bytes = _int(identity, "bytes")
    source_mtime = _int(identity, "sourceMtime")
    directory = identity.get("directory") if isinstance(identity.get("directory"), str) else ""
    summary = {"uploadId": upload_id, **upload_state}
    if not file_name:
        reason = "upload_session_missing_or_expired" if session_missing else "missing_identity.fileName"
        return _result("asset.upload", UNCERTAIN, reason, readback=summary)
    query = {"q": file_name, "limit": ASSET_QUERY_LIMIT}
    if library_id:
        query["libraryId"] = library_id
    listing, problem = _get(client, "/api/v1/assets", query)
    if problem is not None:
        return _result("asset.upload", UNCERTAIN, "asset_query_unavailable", readback=summary)
    items = listing.get("items") if isinstance(listing, dict) else listing
    if not isinstance(items, list):
        return _result("asset.upload", UNCERTAIN, "invalid_asset_query", readback=summary)
    candidates = []
    for item in items:
        if not isinstance(item, dict) or item.get("fileName") != file_name:
            continue
        if expected_bytes is not None and item.get("sizeBytes") != expected_bytes:
            continue
        if source_mtime is not None:
            modified = _iso_millis(item.get("modifiedAt"))
            if modified is not None and modified != source_mtime:
                continue
        candidates.append(item)
    if not candidates:
        reason = ("upload_session_missing_and_asset_not_found" if session_missing
                  else "uploaded_asset_not_found")
        return _result("asset.upload", UNCERTAIN, reason, readback=summary)
    started = identity.get("operationStartedAt")
    if isinstance(started, bool) or not isinstance(started, (int, float)):
        return _result("asset.upload", UNCERTAIN, "missing_identity.operationStartedAt",
                       readback=summary)
    started_ms = int(started * 1000) - UPLOAD_CLOCK_SKEW_MS
    expected_rel = "/".join(part for part in (directory, file_name) if part)
    matches = []
    for candidate in candidates[:UPLOAD_CANDIDATE_LIMIT]:
        asset_id = candidate.get("id")
        if not isinstance(asset_id, str) or not asset_id:
            continue
        detail, problem = _asset_detail(client, asset_id)
        if problem is not None or detail is None:
            return _result("asset.upload", UNCERTAIN, "asset_detail_unavailable",
                           readback={"uploadId": upload_id, "assetId": asset_id})
        if detail.get("relPath") != expected_rel:
            continue
        if expected_bytes is not None and detail.get("sizeBytes") != expected_bytes:
            continue
        if source_mtime is not None and _iso_millis(detail.get("modifiedAt")) != source_mtime:
            continue
        added = _iso_millis(detail.get("addedAt"))
        if added is None or added < started_ms:
            # 无法证明该资产是本次操作之后创建的：可能是预先存在的同元数据资产。
            continue
        matches.append(detail)
    if len(matches) == 1:
        return _result("asset.upload", CONFIRMED_APPLIED, "uploaded_asset_matches",
                       readback={"uploadId": upload_id, "assetId": matches[0].get("id"),
                                 "fileName": file_name})
    if len(matches) > 1:
        return _result("asset.upload", UNCERTAIN, "multiple_uploaded_assets",
                       readback={"uploadId": upload_id, "matches": len(matches)})
    reason = ("upload_session_missing_and_asset_unmatched" if session_missing
              else "uploaded_asset_unmatched")
    return _result("asset.upload", UNCERTAIN, reason, readback=summary)


def _readback_profile(client, identity, _evidence):
    author_id = _text(identity, "authorId")
    expected_revision = _int(identity, "expectedRevision")
    if not author_id or expected_revision is None:
        return _result("author.profile.update", UNCERTAIN, "missing_identity")
    data, problem = _get(client, "/api/v1/authors/" + parse.quote(author_id, safe="") + "/profile")
    if problem is not None or not isinstance(data, dict):
        return _result("author.profile.update", UNCERTAIN, "profile_unavailable",
                       readback={"authorId": author_id})
    revision = data.get("revision")
    if not isinstance(revision, int) or isinstance(revision, bool):
        return _result("author.profile.update", UNCERTAIN, "invalid_profile_revision",
                       readback={"authorId": author_id})
    if revision == expected_revision:
        return _result("author.profile.update", CONFIRMED_NOT_APPLIED,
                       "profile_revision_unchanged",
                       readback={"authorId": author_id, "revision": revision})
    if revision != expected_revision + 1:
        return _result("author.profile.update", UNCERTAIN, "profile_revision_advanced",
                       readback={"authorId": author_id, "revision": revision,
                                 "expectedRevision": expected_revision})
    changes = identity.get("changes")
    if not isinstance(changes, list) or not changes:
        return _result("author.profile.update", UNCERTAIN, "planned_changes_unavailable",
                       readback={"authorId": author_id, "revision": revision})
    try:
        from . import tools_author
        flat = tools_author._flat_view(tools_author._normalize_profile(data))
    except Exception:
        return _result("author.profile.update", UNCERTAIN, "profile_parse_failed",
                       readback={"authorId": author_id, "revision": revision})
    for change in changes:
        if not isinstance(change, dict):
            return _result("author.profile.update", UNCERTAIN, "invalid_planned_change",
                           readback={"authorId": author_id, "revision": revision})
        field = change.get("field")
        expected = change.get("digest")
        if not isinstance(field, str) or not isinstance(expected, str):
            return _result("author.profile.update", UNCERTAIN, "invalid_planned_change",
                           readback={"authorId": author_id, "revision": revision})
        if plans.digest(flat.get(field)) != expected:
            return _result("author.profile.update", UNCERTAIN, "profile_changes_unmatched",
                           readback={"authorId": author_id, "revision": revision})
    return _result("author.profile.update", CONFIRMED_APPLIED, "profile_revision_and_changes_match",
                   readback={"authorId": author_id, "revision": revision})


def _readback_asset_authors(client, identity, _evidence):
    asset_id = _text(identity, "assetId")
    target_ids = identity.get("authorIds")
    if not asset_id or not isinstance(target_ids, list):
        return _result("asset.authors.apply", UNCERTAIN, "missing_identity")
    detail, problem = _asset_detail(client, asset_id)
    if problem == 404 or detail is None:
        return _result("asset.authors.apply", UNCERTAIN, "asset_unavailable",
                       readback={"assetId": asset_id})
    authors = [item for item in detail.get("authors") or [] if isinstance(item, dict)]
    current = sorted({item.get("id") for item in authors
                      if isinstance(item.get("id"), str)})
    current_names = {item.get("displayName") for item in authors
                     if isinstance(item.get("displayName"), str)}
    expected = sorted({item for item in target_ids if isinstance(item, str)})
    new_names = {item for item in identity.get("newAuthorNames") or []
                 if isinstance(item, str)}
    previous_ids = identity.get("previousAuthorIds")
    previous = None
    if isinstance(previous_ids, list):
        previous = sorted({item for item in previous_ids if isinstance(item, str)})
        if current == previous:
            return _result("asset.authors.apply", CONFIRMED_NOT_APPLIED,
                           "asset_authors_match_previous",
                           readback={"assetId": asset_id, "authorIds": current})
    if current == expected and (not new_names or new_names <= current_names):
        return _result("asset.authors.apply", CONFIRMED_APPLIED, "asset_authors_match",
                       readback={"assetId": asset_id, "authorIds": current})
    # newAuthorNames 由服务端按规范身份解析/新建：目标作者 ID 全在，且计划中的
    # 新名字都能在当前作者显示名中找到，才可确认（仍要求 current != previous）。
    if new_names and new_names <= current_names and set(expected) <= set(current):
        return _result("asset.authors.apply", CONFIRMED_APPLIED,
                       "asset_authors_and_new_names_match",
                       readback={"assetId": asset_id, "authorIds": current})
    return _result("asset.authors.apply", UNCERTAIN, "asset_authors_not_confirmed",
                   readback={"assetId": asset_id, "authorIds": current})


def _readback_asset_tags(client, identity, _evidence):
    asset_id = _text(identity, "assetId")
    target_ids = identity.get("tagIds")
    if not asset_id or not isinstance(target_ids, list):
        return _result("asset.tags.replace", UNCERTAIN, "missing_identity")
    detail, problem = _asset_detail(client, asset_id)
    if problem == 404 or detail is None:
        return _result("asset.tags.replace", UNCERTAIN, "asset_unavailable",
                       readback={"assetId": asset_id})
    current = sorted({item.get("id") for item in detail.get("tags") or []
                      if isinstance(item, dict) and isinstance(item.get("id"), str)})
    expected = sorted({item for item in target_ids if isinstance(item, str)})
    if current == expected:
        return _result("asset.tags.replace", CONFIRMED_APPLIED, "asset_tags_match",
                       readback={"assetId": asset_id, "tagIds": current})
    previous_ids = identity.get("previousTagIds")
    if isinstance(previous_ids, list):
        previous = sorted({item for item in previous_ids if isinstance(item, str)})
        if current == previous:
            return _result("asset.tags.replace", CONFIRMED_NOT_APPLIED,
                           "asset_tags_match_previous",
                           readback={"assetId": asset_id, "tagIds": current})
    return _result("asset.tags.replace", UNCERTAIN, "asset_tags_not_confirmed",
                   readback={"assetId": asset_id, "tagIds": current})


def _readback_rename(client, identity, _evidence):
    asset_id = _text(identity, "assetId")
    old_name = _text(identity, "oldName")
    new_name = _text(identity, "newName")
    if not asset_id or not new_name:
        return _result("asset.rename.apply", UNCERTAIN, "missing_identity")
    detail, problem = _asset_detail(client, asset_id)
    if problem == 404 or detail is None:
        return _result("asset.rename.apply", UNCERTAIN, "asset_unavailable",
                       readback={"assetId": asset_id})
    current = detail.get("fileName")
    if current == new_name:
        return _result("asset.rename.apply", CONFIRMED_APPLIED, "asset_name_matches_new",
                       readback={"assetId": asset_id, "fileName": current})
    if old_name and current == old_name:
        return _result("asset.rename.apply", CONFIRMED_NOT_APPLIED, "asset_name_matches_old",
                       readback={"assetId": asset_id, "fileName": current})
    return _result("asset.rename.apply", UNCERTAIN, "asset_name_unknown",
                   readback={"assetId": asset_id, "fileName": current})


def _readback_trash(client, identity, _evidence):
    asset_id = _text(identity, "assetId")
    if not asset_id:
        return _result("asset.trash", UNCERTAIN, "missing_identity.assetId")
    detail, problem = _asset_detail(client, asset_id)
    if problem == 404:
        # 删除后回读 404 是既定验收口径；资产 ID 唯一，仍记录为已执行。
        return _result("asset.trash", CONFIRMED_APPLIED, "asset_gone",
                       readback={"assetId": asset_id})
    if problem is not None or detail is None:
        return _result("asset.trash", UNCERTAIN, "asset_status_unavailable",
                       readback={"assetId": asset_id})
    return _result("asset.trash", CONFIRMED_NOT_APPLIED, "asset_still_exists",
                   readback={"assetId": asset_id})


def _readback_author_create(client, identity, evidence):
    display_name = _text(identity, "displayName")
    if not display_name:
        return _result("author.create", UNCERTAIN, "missing_identity.displayName")
    evidence_author_id = evidence.get("authorId") if isinstance(evidence, dict) else None
    if isinstance(evidence_author_id, str) and evidence_author_id:
        index, problem = _author_index(client)
        if problem is not None or index is None:
            return _result("author.create", UNCERTAIN, "author_index_unavailable")
        author = index.get(evidence_author_id)
        if author is None:
            return _result("author.create", UNCERTAIN, "evidence_author_not_found",
                           readback={"authorId": evidence_author_id})
        if author.get("displayName") == display_name:
            return _result("author.create", CONFIRMED_APPLIED, "author_exists_with_display_name",
                           readback={"authorId": evidence_author_id})
        return _result("author.create", UNCERTAIN, "evidence_author_name_mismatch",
                       readback={"authorId": evidence_author_id})
    data, problem = _get(client, "/api/v1/authors/query", {"q": display_name, "limit": 20})
    if problem is not None:
        return _result("author.create", UNCERTAIN, "author_query_unavailable")
    items = data.get("items") if isinstance(data, dict) else None
    if not isinstance(items, list):
        return _result("author.create", UNCERTAIN, "invalid_author_query")
    exists = any(isinstance(item, dict) and item.get("displayName") == display_name
                 for item in items)
    if exists:
        return _result("author.create", UNCERTAIN, "author_reuse_or_create_ambiguous")
    if evidence:
        return _result("author.create", CONFIRMED_NOT_APPLIED, "author_absent")
    return _result("author.create", UNCERTAIN, "author_create_requires_evidence")


def _readback_author_merge(client, identity, _evidence):
    source_id = _text(identity, "sourceAuthorId")
    target_id = _text(identity, "targetAuthorId")
    if not source_id or not target_id or source_id == target_id:
        return _result("author.merge", UNCERTAIN, "missing_identity")
    index, problem = _author_index(client)
    if problem is not None or index is None:
        return _result("author.merge", UNCERTAIN, "author_index_unavailable")
    source_exists = source_id in index
    target_exists = target_id in index
    if not target_exists:
        return _result("author.merge", UNCERTAIN, "target_author_missing",
                       readback={"sourceAuthorId": source_id, "targetAuthorId": target_id})
    if not source_exists:
        return _result("author.merge", CONFIRMED_APPLIED, "source_author_absent",
                       readback={"targetAuthorId": target_id})
    return _result("author.merge", CONFIRMED_NOT_APPLIED, "both_authors_exist",
                   readback={"sourceAuthorId": source_id, "targetAuthorId": target_id})


def _readback_maintenance_step(client, identity, evidence, tool):
    job_id = _text(identity, "jobId")
    if not job_id:
        return _result(tool, UNCERTAIN, "missing_identity.jobId")
    data, problem = _get(client, "/api/v1/maintenance/jobs/" + parse.quote(job_id, safe=""))
    if problem == 404:
        return _result(tool, UNCERTAIN, "job_not_found", readback={"jobId": job_id})
    if problem is not None or not isinstance(data, dict):
        return _result(tool, UNCERTAIN, "job_query_unavailable", readback={"jobId": job_id})
    if tool == "maintenance.complete":
        state = data.get("status")
        expected = _text(identity, "terminalStatus")
        if state in TERMINAL_JOB_STATUSES and data.get("finishedAt"):
            if expected and state != expected:
                return _result(tool, UNCERTAIN, "job_terminal_status_differs",
                               readback={"jobId": job_id, "status": state})
            return _result(tool, CONFIRMED_APPLIED, "job_terminal",
                           readback={"jobId": job_id, "status": state})
        if state in NON_TERMINAL_JOB_STATUSES:
            return _result(tool, CONFIRMED_NOT_APPLIED, "job_not_terminal",
                           readback={"jobId": job_id, "status": state})
        return _result(tool, UNCERTAIN, "job_state_unknown",
                       readback={"jobId": job_id, "status": state})
    if tool == "maintenance.step.record":
        step_name = _text(identity, "step")
        step_status = _text(identity, "stepStatus")
        if not step_name:
            return _result(tool, UNCERTAIN, "missing_identity.step")
        steps = data.get("steps") if isinstance(data.get("steps"), list) else []
        found = [item for item in steps
                 if isinstance(item, dict) and item.get("step") == step_name]
        if found:
            current = found[0].get("status")
            if step_status and current == step_status:
                return _result(tool, CONFIRMED_APPLIED, "step_status_matches",
                               readback={"jobId": job_id, "step": step_name, "status": current})
            return _result(tool, UNCERTAIN, "step_status_differs",
                           readback={"jobId": job_id, "step": step_name, "status": current})
        if len(steps) >= 50:
            return _result(tool, UNCERTAIN, "step_history_truncated",
                           readback={"jobId": job_id, "step": step_name})
        return _result(tool, CONFIRMED_NOT_APPLIED, "step_absent",
                       readback={"jobId": job_id, "step": step_name})
    if tool == "maintenance.artifact.record":
        artifact_sha = _text(identity, "artifactSha256")
        kind = _text(identity, "kind")
        if not artifact_sha:
            return _result(tool, UNCERTAIN, "missing_identity.artifactSha256")
        artifacts = data.get("artifacts") if isinstance(data.get("artifacts"), list) else []
        for item in artifacts:
            if not isinstance(item, dict) or item.get("sha256") != artifact_sha:
                continue
            if kind and item.get("kind") != kind:
                continue
            return _result(tool, CONFIRMED_APPLIED, "artifact_matches",
                           readback={"jobId": job_id, "kind": kind})
        if len(artifacts) >= 100:
            return _result(tool, UNCERTAIN, "artifact_history_truncated",
                           readback={"jobId": job_id})
        return _result(tool, CONFIRMED_NOT_APPLIED, "artifact_absent",
                       readback={"jobId": job_id})
    # heartbeat/claim 等没有可核对的终态或稳定身份，保持阻断。
    return _result(tool, UNCERTAIN, "maintenance_tool_not_confirmable",
                   readback={"jobId": job_id, "status": data.get("status")})


_HANDLERS = {
    "asset.upload": _readback_upload,
    "author.profile.update": _readback_profile,
    "asset.authors.apply": _readback_asset_authors,
    "asset.tags.replace": _readback_asset_tags,
    "asset.rename.apply": _readback_rename,
    "asset.trash": _readback_trash,
    "author.create": _readback_author_create,
    "author.merge": _readback_author_merge,
    "maintenance.complete": _readback_maintenance_step,
    "maintenance.step.record": _readback_maintenance_step,
    "maintenance.artifact.record": _readback_maintenance_step,
}


def readback(tool, identity, *, env, evidence=None):
    """按原操作身份只读回读并返回三态判定；配置错误抛 ToolError，其余失败为 uncertain。"""
    if not isinstance(identity, dict):
        identity = {}
    if evidence is None:
        evidence = {}
    problems = task_controls.evidence_problems(identity, evidence, require_locators=False)
    if problems:
        return _result(tool, UNCERTAIN, "evidence_mismatch", readback={"problems": problems})
    if identity.get("truncated") is True:
        # 有界截断后的 identity 可能缺少定位字段；不猜测、不确认。
        return _result(tool, UNCERTAIN, "identity_truncated")
    client = _client(env)
    handler = _HANDLERS.get(tool)
    if handler is None:
        return _result(tool, UNCERTAIN, "unsupported_tool")
    try:
        if handler is _readback_maintenance_step:
            return handler(client, identity, evidence, tool)
        if handler is _readback_author_create:
            return handler(client, identity, evidence)
        return handler(client, identity, evidence)
    except Exception:
        # 任何 HTTP/网络/解析/结构异常都不当作已执行。
        return _result(tool, UNCERTAIN, "readback_failed")
