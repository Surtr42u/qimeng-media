"""授权/字段校验规则（作者档案、URL、名称）。

这些规则是 rules.json fields/authorProfile 的机器可读校验实现，供
author.profile.prepare 与旧 import_creator_profiles 共用同一份实现；
服务端仍是最终权威，这里只做「计划前可判定」的校验，避免把明显非法载荷
发到正式 API。
"""
from __future__ import annotations

import ipaddress
from urllib import parse

PROFILE_STRING_LIMITS = {
    "nickname": 200,
    "biography": 2000,
    "avatarUrl": 2000,
    "homepageUrl": 2000,
    "feedUrl": 2000,
}
EVIDENCE_MAX_ITEMS = 8
EVIDENCE_ITEM_MAX_LENGTH = 2000
X_PROFILE_LIMITS = {"url": 2000, "biography": 1000, "biographyZh": 2000}
PROFILE_STATUSES = ("empty", "candidate", "verified")
PROFILE_TEXT_FIELDS = ("nickname", "biography", "avatarUrl", "homepageUrl", "feedUrl")
PROFILE_BOOL_FIELDS = ("autoRefresh", "trackUpdates")
PROFILE_WRITE_FIELDS = PROFILE_TEXT_FIELDS + PROFILE_BOOL_FIELDS + ("evidenceUrls", "status", "xProfile")
X_PROFILE_FIELDS = ("url", "biography", "biographyZh")

# X/Twitter 账号主页中不是账号的保留一级路径。
_X_RESERVED_PATHS = {
    "search", "settings", "i", "intent", "home", "explore", "notifications",
    "messages", "compose", "login", "logout", "signup", "tos", "privacy",
    "hashtag", "share", "account", "about", "download", "jobs",
}


def has_control_characters(value: str) -> bool:
    """除换行与制表符外的控制字符在服务端会 400，计划前直接拒绝。"""
    return any(ord(ch) < 32 and ch not in "\n\t" for ch in value)


def public_url(value) -> bool:
    """HTTPS 公网 URL（与 import_creator_profiles 历史口径一致）。"""
    if not isinstance(value, str) or len(value) > 2000 or any(ord(c) < 32 for c in value):
        return False
    try:
        url = parse.urlsplit(value)
        if (url.scheme != "https" or not url.hostname or url.username is not None
                or url.password is not None or url.fragment or url.port not in (None, 443)):
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


def x_profile_url(value: str) -> bool:
    """校验 X/Twitter 账号主页 URL；搜索/设置等非账号路径拒绝。"""
    if not isinstance(value, str) or not value:
        return False
    if len(value) > X_PROFILE_LIMITS["url"] or any(ord(c) < 32 for c in value):
        return False
    try:
        url = parse.urlsplit(value)
    except ValueError:
        return False
    if url.scheme != "https" or url.username is not None or url.password is not None:
        return False
    if url.query or url.fragment:
        return False
    host = (url.hostname or "").lower()
    if host not in ("x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com"):
        return False
    segments = [segment for segment in url.path.split("/") if segment]
    if len(segments) != 1:
        return False
    account = segments[0]
    if account.lower() in _X_RESERVED_PATHS:
        return False
    return bool(account) and len(account) <= 64


def validate_profile_changes(changes: dict) -> list[dict]:
    """校验 author.profile.prepare 的 changes 结构；返回结构化错误列表。"""
    errors: list[dict] = []
    if not isinstance(changes, dict):
        return [{"path": "changes", "message": "必须是对象"}]
    unknown = [key for key in changes if key not in PROFILE_WRITE_FIELDS]
    if unknown:
        errors.append({"path": "changes", "message": "不允许的字段：" + ", ".join(sorted(unknown))})
    for field in PROFILE_TEXT_FIELDS:
        if field not in changes:
            continue
        value = changes[field]
        if not isinstance(value, str):
            errors.append({"path": "changes." + field, "message": "必须是字符串"})
            continue
        if len(value) > PROFILE_STRING_LIMITS[field]:
            errors.append({"path": "changes." + field,
                           "message": f"长度不能超过 {PROFILE_STRING_LIMITS[field]} 字符"})
        if has_control_characters(value):
            errors.append({"path": "changes." + field, "message": "包含非法控制字符"})
    nickname = changes.get("nickname")
    if isinstance(nickname, str) and nickname and nickname != nickname.strip():
        errors.append({"path": "changes.nickname", "message": "首尾不能有空白"})
    for field in ("avatarUrl", "homepageUrl", "feedUrl"):
        value = changes.get(field)
        if isinstance(value, str) and value and not public_url(value):
            errors.append({"path": "changes." + field, "message": "必须是非空 HTTPS 公网 URL"})
    for field in PROFILE_BOOL_FIELDS:
        if field in changes and not isinstance(changes[field], bool):
            errors.append({"path": "changes." + field, "message": "必须是布尔值"})
    if "status" in changes:
        if changes["status"] not in PROFILE_STATUSES:
            errors.append({"path": "changes.status", "message": "必须是 empty/candidate/verified"})
    if "evidenceUrls" in changes:
        evidence = changes["evidenceUrls"]
        if not isinstance(evidence, list):
            errors.append({"path": "changes.evidenceUrls", "message": "必须是数组"})
        elif len(evidence) > EVIDENCE_MAX_ITEMS:
            errors.append({"path": "changes.evidenceUrls",
                           "message": f"最多 {EVIDENCE_MAX_ITEMS} 条"})
        else:
            for index, value in enumerate(evidence):
                if not isinstance(value, str) or not value or len(value) > EVIDENCE_ITEM_MAX_LENGTH:
                    errors.append({"path": f"changes.evidenceUrls[{index}]",
                                   "message": "必须是非空字符串且不超过 2000 字符"})
                elif not public_url(value):
                    errors.append({"path": f"changes.evidenceUrls[{index}]",
                                   "message": "必须是 HTTPS 公网 URL"})
    if "xProfile" in changes:
        x_profile = changes["xProfile"]
        if not isinstance(x_profile, dict):
            errors.append({"path": "changes.xProfile", "message": "必须是对象"})
        else:
            unknown_x = [key for key in x_profile if key not in X_PROFILE_FIELDS]
            if unknown_x:
                errors.append({"path": "changes.xProfile",
                               "message": "不允许的字段：" + ", ".join(sorted(unknown_x))})
            for field in X_PROFILE_FIELDS:
                if field not in x_profile:
                    continue
                value = x_profile[field]
                if not isinstance(value, str):
                    errors.append({"path": "changes.xProfile." + field, "message": "必须是字符串"})
                    continue
                if len(value) > X_PROFILE_LIMITS[field]:
                    errors.append({"path": "changes.xProfile." + field,
                                   "message": f"长度不能超过 {X_PROFILE_LIMITS[field]} 字符"})
                if has_control_characters(value):
                    errors.append({"path": "changes.xProfile." + field, "message": "包含非法控制字符"})
            url = x_profile.get("url")
            if isinstance(url, str) and url and not x_profile_url(url):
                errors.append({"path": "changes.xProfile.url",
                               "message": "必须是 HTTPS X/Twitter 账号主页（不能是搜索/设置等路径）"})
            if isinstance(url, str) and not url:
                for field in ("biography", "biographyZh"):
                    if isinstance(x_profile.get(field), str) and x_profile.get(field):
                        errors.append({"path": "changes.xProfile." + field,
                                       "message": "url 为空时原文与译文必须为空"})
    return errors


def validate_author_name(name) -> list[dict]:
    """资产作者按名新建的保守预检（服务端仍做完整身份规则校验）。"""
    errors: list[dict] = []
    if not isinstance(name, str) or not name:
        return [{"path": "newAuthorNames", "message": "作者名必须是非空字符串"}]
    if not name.strip():
        return [{"path": "newAuthorNames", "message": "作者名不能只有空白"}]
    if len(name) > 200:
        return [{"path": "newAuthorNames", "message": "作者名不能超过 200 字符"}]
    if has_control_characters(name):
        return [{"path": "newAuthorNames", "message": "作者名包含非法控制字符"}]
    return errors


def verify_profile_constraints(merged: dict) -> list[str]:
    """校验合并后档案的跨字段约束；返回人类可读错误（空列表=通过）。"""
    problems: list[str] = []
    if merged.get("status") == "verified":
        if not merged.get("homepageUrl"):
            problems.append("status=verified 需要非空 homepageUrl")
        if not merged.get("evidenceUrls"):
            problems.append("status=verified 需要至少 1 条 evidenceUrls")
    if merged.get("autoRefresh") is True:
        if merged.get("status") != "verified" or not merged.get("homepageUrl"):
            problems.append("autoRefresh 开启需要 status=verified 且 homepageUrl 非空")
    if merged.get("trackUpdates") is True:
        if merged.get("status") != "verified" or not merged.get("feedUrl"):
            problems.append("trackUpdates 开启需要 status=verified 且 feedUrl 非空")
    x_profile = merged.get("xProfile")
    if isinstance(x_profile, dict) and not x_profile.get("url"):
        if x_profile.get("biography") or x_profile.get("biographyZh"):
            problems.append("xProfile.url 为空时 biography/biographyZh 必须为空")
    return problems
