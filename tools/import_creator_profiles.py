"""受控填充作者公开档案：默认离线dry-run，真实写入仅通过显式API。"""
import argparse
import http.client
import ipaddress
import json
import os
from pathlib import Path
import sys
from urllib import error, parse, request

MAX_REPORT_BYTES = 8 << 20
MAX_RESPONSE_BYTES = 2 << 20
REQUEST_TIMEOUT_SECONDS = 20
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
    if not isinstance(value, str) or len(value) > 2000 or any(ord(c) < 32 for c in value):
        return False
    try:
        url = parse.urlsplit(value)
        if url.scheme != "https" or not url.hostname or url.username is not None or url.password is not None or url.fragment or url.port not in (None, 443):
            return False
        host = url.hostname.lower()
        if host == "localhost" or host.endswith((".local", ".localhost")) or "." not in host:
            return False
        try:
            return ipaddress.ip_address(host).is_global
        except ValueError:
            return True
    except ValueError:
        return False


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
        links.sort(key=lambda item: 0 if str(item.get("platform", "")).lower() in ("x", "twitter", "x/twitter") else 1)
        body = {"revision": 0, "nickname": record.get("public_nickname") or "", "biography": record.get("public_bio") or "", "avatarUrl": record.get("avatar_source_url") or "", "homepageUrl": links[0]["url"], "evidenceUrls": evidence, "status": "verified" if author_id in reviewed else "candidate", "autoRefresh": enable_auto_refresh and author_id in reviewed, "feedUrl": "", "trackUpdates": False}
        if any(not isinstance(body[key], str) or len(body[key]) > limit for key, limit in TEXT_LIMITS.items()) or (body["avatarUrl"] and not public_url(body["avatarUrl"])):
            skipped += 1
            continue
        plan.append((record, body))
    if reviewed - seen:
        raise ImportFailure("reviewed_ids_not_in_report")
    return plan, {"records": len(report["records"]), "eligible": len(plan), "skipped_report": skipped}


def validate_base_url(value):
    try:
        url = parse.urlsplit(value)
        if url.scheme not in ("http", "https") or not url.hostname or url.username is not None or url.password is not None or url.query or url.fragment or url.path not in ("", "/"):
            raise ImportFailure("invalid_base_url")
        if url.scheme == "http":
            try:
                address = ipaddress.ip_address(url.hostname)
                trusted = address.is_loopback or address.is_private
            except ValueError:
                trusted = url.hostname.lower() == "localhost"
            if not trusted:
                raise ImportFailure("http_requires_local_address")
        _ = url.port
        return value.rstrip("/")
    except (TypeError, ValueError) as exc:
        raise ImportFailure("invalid_base_url") from exc


class API:
    def __init__(self, base_url, token):
        self.base_url = validate_base_url(base_url)
        if not token or any(ord(char) < 32 for char in token):
            raise ImportFailure("missing_or_invalid_api_token")
        self.token = token
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = request.Request(self.base_url + path, data=data, method=method, headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        try:
            with self.opener.open(req, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                if len(raw) > MAX_RESPONSE_BYTES:
                    raise ImportFailure("response_too_large")
                return json.loads(raw)
        except error.HTTPError as exc:
            status = exc.code
            exc.close()
            raise ImportFailure("conflict" if status == 409 else "api_http_failure") from None
        except (OSError, ValueError, UnicodeError, http.client.HTTPException) as exc:
            raise ImportFailure("api_unavailable_or_invalid_response") from exc


def pristine(profile):
    return (isinstance(profile, dict) and type(profile.get("revision")) is int and profile["revision"] >= 0
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
    # 所有预检先于第一笔PUT，错库/错实体导致整轮零写入。
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report")
    parser.add_argument("--base-url", default=os.environ.get("QIMENG_BASE_URL"))
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--reviewed-ids")
    parser.add_argument("--enable-auto-refresh", action="store_true")
    args = parser.parse_args(argv)
    try:
        reviewed = read_json(args.reviewed_ids) if args.reviewed_ids else []
        if not isinstance(reviewed, list) or any(not isinstance(item, str) for item in reviewed):
            raise ImportFailure("invalid_reviewed_ids")
        plan, summary = plan_records(read_json(args.report), reviewed, args.enable_auto_refresh)
        summary["dry_run"] = not args.apply
        if args.apply:
            if not args.base_url:
                raise ImportFailure("explicit_base_url_required")
            summary.update(apply_plan(API(args.base_url, os.environ.get("QIMENG_API_TOKEN")), plan))
        print(json.dumps(summary))
        return 1 if summary.get("failed") or summary.get("conflicts") else 0
    except ImportFailure as exc:
        print(json.dumps({"error": str(exc)}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
