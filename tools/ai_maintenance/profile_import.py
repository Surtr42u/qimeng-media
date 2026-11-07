"""作者公开档案受控填充（唯一实现的迁移目标）。

本模块承接原 tools/import_creator_profiles.py 的成熟流程：
输入校验、人工资料保护（仅 pristine 档案可写）、先全量身份预检再逐条 GET/PUT、
revision 使用、409 单独计数、错误只输出固定类别。旧脚本保留兼容入口并转发
到这里，不保留第二份写入算法（ADR-0056 §1/任务书 6.5）。

安全：Bearer 只从 QIMENG_API_TOKEN 环境读取；禁代理/重定向；单请求 20 秒、
响应上限 2 MiB；输出只含计数与固定错误类别，不含作者名/链接/上游正文。
"""
from __future__ import annotations

import argparse
import http.client
import json
import os
from pathlib import Path
import sys
from urllib import error, parse, request

from . import client as http_client
from . import validation

MAX_REPORT_BYTES = 8 << 20
MAX_RESPONSE_BYTES = http_client.MAX_RESPONSE_BYTES
REQUEST_TIMEOUT_SECONDS = http_client.REQUEST_TIMEOUT_SECONDS
TEXT_LIMITS = {"nickname": 200, "biography": 2000, "avatarUrl": 2000, "homepageUrl": 2000}


class ImportFailure(Exception):
    """只携带固定错误类别，禁止附加作者/URL/令牌/上游正文。"""


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def read_json(path):
    try:
        with Path(path).open("rb") as source:
            raw = source.read(MAX_REPORT_BYTES + 1)
        if len(raw) > MAX_REPORT_BYTES:
            raise ImportFailure("input_too_large")
        return json.loads(raw)
    except (OSError, ValueError, UnicodeError) as exc:
        raise ImportFailure("invalid_input") from exc


def public_url(value):
    """兼容旧名；实现与统一校验模块同一份。"""
    return validation.public_url(value)


def plan_records(report, reviewed_ids=(), enable_auto_refresh=False):
    if not isinstance(report, dict) or not isinstance(report.get("records"), list):
        raise ImportFailure("invalid_report")
    reviewed = set(reviewed_ids)
    if any(not isinstance(value, str) or not value for value in reviewed):
        raise ImportFailure("invalid_reviewed_ids")
    if enable_auto_refresh and not reviewed:
        raise ImportFailure("auto_refresh_requires_review")
    seen, plan = set(), []
    skipped = 0
    for record in report["records"]:
        if not isinstance(record, dict):
            raise ImportFailure("invalid_record")
        author_id = record.get("internal_id")
        name, kind = record.get("original_name"), record.get("kind")
        if not isinstance(author_id, str) or not author_id or author_id in seen or not isinstance(name, str) or not name or kind not in ("regular", "cos"):
            raise ImportFailure("invalid_identity")
        seen.add(author_id)
        if record.get("searched") is not True or record.get("status") not in ("candidate", "verified"):
            skipped += 1
            continue
        platforms, evidence = record.get("candidate_platforms", []), record.get("evidence_urls", [])
        if not isinstance(platforms, list) or not isinstance(evidence, list) or len(evidence) > 8 or not evidence or not all(public_url(url) for url in evidence):
            skipped += 1
            continue
        links = [item for item in platforms if isinstance(item, dict) and public_url(item.get("url"))]
        if not links:
            skipped += 1
            continue
        # 维护优先指向本人官网/链接主页；X 常要求登录，保留为可打开的身份链接。
        def source_priority(item):
            platform = str(item.get("platform", "")).lower()
            if platform in ("website", "linktree", "carrd", "official website", "website/patreon"):
                return 0
            if platform in ("x", "twitter", "x/twitter", "twitter/x"):
                return 2
            return 1
        links.sort(key=source_priority)
        evidence = list(dict.fromkeys(evidence + [item["url"] for item in links]))[:8]
        body = {"revision": 0, "nickname": record.get("public_nickname") or "", "biography": record.get("public_bio") or "", "avatarUrl": record.get("avatar_source_url") or "", "homepageUrl": links[0]["url"], "evidenceUrls": evidence, "status": "verified" if author_id in reviewed else "candidate", "autoRefresh": enable_auto_refresh and author_id in reviewed, "feedUrl": "", "trackUpdates": False}
        if any(not isinstance(body[key], str) or len(body[key]) > limit for key, limit in TEXT_LIMITS.items()) or (body["avatarUrl"] and not public_url(body["avatarUrl"])):
            skipped += 1
            continue
        plan.append((record, body))
    if reviewed - seen:
        raise ImportFailure("reviewed_ids_not_in_report")
    return plan, {"records": len(report["records"]), "eligible": len(plan), "skipped_report": skipped}


def validate_base_url(value):
    """旧入口契约：只允许 origin；http 仅限回环/私有地址。"""
    try:
        scheme, host, _port = http_client.parse_origin(value)
    except http_client.OriginError as exc:
        mapping = {
            "empty": "invalid_base_url",
            "invalid_url": "invalid_base_url",
            "userinfo": "invalid_base_url",
            "path": "invalid_base_url",
            "query": "invalid_base_url",
            "fragment": "invalid_base_url",
            "scheme": "invalid_base_url",
            "host": "invalid_base_url",
            "port": "invalid_base_url",
        }
        raise ImportFailure(mapping.get(exc.kind, "invalid_base_url")) from exc
    if scheme == "http":
        trusted = host.lower() == "localhost"
        if not trusted:
            try:
                import ipaddress
                address = ipaddress.ip_address(host)
                trusted = bool(address.is_loopback or address.is_private)
            except ValueError:
                trusted = False
        if not trusted:
            raise ImportFailure("http_requires_local_address")
    return value.rstrip("/")


class API:
    def __init__(self, base_url, token):
        self.base_url = validate_base_url(base_url)
        if not token or any(ord(char) < 32 for char in token):
            raise ImportFailure("missing_or_invalid_api_token")
        self.token = token
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = request.Request(self.base_url + path, data=data, method=method,
                              headers={"Authorization": "Bearer " + self.token,
                                       "Content-Type": "application/json"})
        try:
            return http_client.send(self.opener, req, token=self.token,
                                    timeout=REQUEST_TIMEOUT_SECONDS,
                                    max_bytes=MAX_RESPONSE_BYTES)
        except http_client.HTTPStatusError as exc:
            raise ImportFailure("conflict" if exc.status == 409 else "api_http_failure") from None
        except (http_client.NetworkError, http_client.ResponseError, error.URLError,
                OSError, ValueError, UnicodeError, http.client.HTTPException) as exc:
            raise ImportFailure("api_unavailable_or_invalid_response") from exc


def pristine(profile):
    return (isinstance(profile, dict) and type(profile.get("revision")) is int and profile["revision"] >= 0
            and profile.get("xProfile") is None
            and profile.get("status") == "empty" and not profile.get("autoRefresh") and not profile.get("trackUpdates")
            and all(profile.get(key) == "" for key in ("nickname", "biography", "avatarUrl", "homepageUrl", "feedUrl"))
            and profile.get("evidenceUrls") == [] and profile.get("updates") == []
            and isinstance(profile.get("candidate"), dict)
            and all(not value for value in profile["candidate"].values()))


def apply_plan(api, plan):
    authors = api.call("GET", "/api/v1/authors")
    if not isinstance(authors, list):
        raise ImportFailure("invalid_author_response")
    indexed = {}
    for author in authors:
        if not isinstance(author, dict) or not isinstance(author.get("id"), str) or author["id"] in indexed:
            raise ImportFailure("invalid_author_response")
        indexed[author["id"]] = author
    # 所有预检先于第一笔 PUT，错库/错实体导致整轮零写入。
    for record, _ in plan:
        current = indexed.get(record["internal_id"])
        if not current or current.get("displayName") != record["original_name"] or current.get("type") != record["kind"]:
            raise ImportFailure("target_identity_mismatch")
    result = {"applied": 0, "skipped_existing": 0, "conflicts": 0, "failed": 0}
    for record, body in plan:
        path = "/api/v1/authors/" + parse.quote(record["internal_id"], safe="") + "/profile"
        try:
            current = api.call("GET", path)
            if not isinstance(current, dict) or current.get("authorId") != record["internal_id"]:
                raise ImportFailure("profile_identity_mismatch")
            if not pristine(current):
                result["skipped_existing"] += 1
                continue
            submission = dict(body, revision=current["revision"])
            saved = api.call("PUT", path, submission)
            if not isinstance(saved, dict) or saved.get("authorId") != record["internal_id"] or saved.get("revision") != current["revision"] + 1:
                result["failed"] += 1
            else:
                result["applied"] += 1
        except ImportFailure as exc:
            result["conflicts" if str(exc) == "conflict" else "failed"] += 1
    return result


def _resolve_base_url(explicit, env):
    """统一旧变量 QIMENG_BASE_URL → QIMENG_API_BASE，并输出弃用提示（不含值）。"""
    if explicit:
        return explicit
    legacy = env.get("QIMENG_BASE_URL")
    current = env.get("QIMENG_API_BASE")
    if legacy and not current:
        sys.stderr.write("warning: QIMENG_BASE_URL 已弃用，请改用 QIMENG_API_BASE（值不回显）\n")
        return legacy
    if legacy and current and legacy != current:
        sys.stderr.write("warning: 同时设置 QIMENG_BASE_URL 与 QIMENG_API_BASE，使用 QIMENG_API_BASE\n")
    return current


def run_cli(argv=None, api_factory=None, env=None) -> int:
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(description="受控填充作者公开档案（默认离线 dry-run）")
    parser.add_argument("report")
    parser.add_argument("--base-url", default=None)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--reviewed-ids")
    parser.add_argument("--enable-auto-refresh", action="store_true")
    args = parser.parse_args(argv)
    resolved_base = _resolve_base_url(args.base_url, env)
    try:
        reviewed = read_json(args.reviewed_ids) if args.reviewed_ids else []
        if not isinstance(reviewed, list) or any(not isinstance(item, str) for item in reviewed):
            raise ImportFailure("invalid_reviewed_ids")
        plan, summary = plan_records(read_json(args.report), reviewed, args.enable_auto_refresh)
        summary["dry_run"] = not args.apply
        if args.apply:
            if not resolved_base:
                raise ImportFailure("explicit_base_url_required")
            factory = api_factory or API
            summary.update(apply_plan(factory(resolved_base, env.get("QIMENG_API_TOKEN")), plan))
        print(json.dumps(summary))
        return 1 if summary.get("failed") or summary.get("conflicts") else 0
    except ImportFailure as exc:
        print(json.dumps({"error": str(exc)}))
        return 2
