"""作品编号与组图页码命名规划/校验引擎（标准库、纯逻辑）。

用户 2026-11-07 补充需求的 AI 工具层实现基础，供 media.organize.prepare /
media.organize.apply 注册为工具时复用，禁止另写第二套命名算法：

- 本模块只做解析、规划、校验、指纹、回读与并发原语；
- 不做 HTTP 调用、不读写媒体文件、不接触真实库/数据库；
- 唯一例外是 SerializedLocks 的 O_EXCL 锁文件（调用方显式传入临时锁目录）；
- 最终写入由调用方 adapter 走正式上传/移动 API，并在回读后用
  readback_report 判定；服务端同名自动改名（基名 (2).ext）一律判失败。

命名语法（规则正文见 docs/domain/files.md「作品编号与组图页码」）：

- 独立作品：{基名} {作品编号}.ext
- 组图页：  {基名} {作品编号} ({页码}).ext

作品编号按作品递增：一组多图只占一个作品编号，组内用页码区分；下一部独立作品
从下一作品编号开始。仅括号序号而无主编号的旧格式（{基名} (2).ext，含上传
同名自动改名产物）不是作品编号，不参与作品编号占用。
"""

from __future__ import annotations

import contextlib
import hashlib
import json
import os
import re
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator, Mapping, Sequence

from .media_types import numbering_category

# ---------------------------------------------------------------------------
# 版本与工具契约（registry.py 注册 media.organize.* 时引用）
# ---------------------------------------------------------------------------

ENGINE_VERSION = "1.1.0"
PLAN_SCHEMA_VERSION = "2"
TOOL_PREPARE_ID = "media.organize.prepare"
TOOL_APPLY_ID = "media.organize.apply"

# ---------------------------------------------------------------------------
# 命名规则默认值（可在请求的 rules 中覆盖，但默认即用户口径）
# ---------------------------------------------------------------------------

DEFAULT_MIN_CONFIDENCE = 0.8
DEFAULT_MAX_WORK_NUMBER = 9999
DEFAULT_SEPARATOR = " "
DEFAULT_PAGE_BRACKET = ("(", ")")
MAX_GROUPS = 500
MAX_FILES_PER_GROUP = 200
MAX_FAMILY_LENGTH = 200
MAX_FILE_NAME_LENGTH = 255

# 并发原语：进程内锁 + 锁文件；锁文件只保存进程号/时间，不写实例地址或库名。
DEFAULT_LOCK_TIMEOUT_SECONDS = 10.0
DEFAULT_LOCK_STALE_SECONDS = 120.0
LOCK_POLL_INTERVAL_SECONDS = 0.05

# ---------------------------------------------------------------------------
# 错误码（与 registry 错误分类对齐；消息只描述规则，不夹带绝对路径/凭据）
# ---------------------------------------------------------------------------

ERR_INVALID_REQUEST = "invalid_request"
ERR_DUPLICATE_SOURCE = "duplicate_source"
ERR_UNKNOWN_SOURCE = "unknown_source"
ERR_INVALID_FAMILY = "invalid_family"
ERR_INVALID_CONFIDENCE = "invalid_confidence"
ERR_NEEDS_CONFIRMATION = "needs_confirmation"
ERR_STALE_PRECONDITION = "stale_precondition"
ERR_PLAN_FINGERPRINT = "plan_fingerprint_mismatch"
ERR_TARGET_CONFLICT = "target_conflict"
ERR_WORK_NUMBER_CONFLICT = "work_number_conflict"
ERR_WORK_NUMBER_GAP = "work_number_gap"
ERR_READBACK = "readback_mismatch"
ERR_LOCK_TIMEOUT = "lock_timeout"
ERR_LOCK_STALE = "lock_stale"


class NamingError(Exception):
    """命名规划/校验失败；code 为稳定机器可读错误码。"""

    def __init__(self, code: str, message: str = "", *, details: Mapping[str, Any] | None = None) -> None:
        self.code = code
        self.details = dict(details or {})
        super().__init__(message or code)


# ---------------------------------------------------------------------------
# 候选分组提案（AI 层输入；乱码文件名只出现在这里，不参与目标命名）
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class CandidateFile:
    """组内一个成员：库内现有文件名 + 可选资产 ID/证据摘要。"""

    name: str
    asset_id: str = ""
    evidence: str = ""

    @classmethod
    def from_mapping(cls, raw: Mapping[str, Any]) -> "CandidateFile":
        if not isinstance(raw, Mapping):
            raise NamingError(ERR_INVALID_REQUEST, "files 元素必须是对象")
        name = raw.get("name")
        if not isinstance(name, str) or not name:
            raise NamingError(ERR_INVALID_REQUEST, "files[].name 必填且为非空字符串")
        asset_id = raw.get("assetId")
        if asset_id is not None and not isinstance(asset_id, str):
            raise NamingError(ERR_INVALID_REQUEST, "files[].assetId 必须是字符串")
        evidence = raw.get("evidence")
        if evidence is not None and not isinstance(evidence, str):
            raise NamingError(ERR_INVALID_REQUEST, "files[].evidence 必须是字符串")
        return cls(name=name, asset_id=asset_id or "", evidence=evidence or "")


@dataclass(frozen=True)
class CandidateGroup:
    """一个候选作品分组：family 为该作品的基名，files 为待改名成员。"""

    family: str
    files: tuple[CandidateFile, ...]
    confidence: float = 0.0
    confirmed: bool = False
    evidence: tuple[str, ...] = ()
    order: tuple[str, ...] = ()
    existing_work_number: int | None = None

    @classmethod
    def from_mapping(cls, raw: Mapping[str, Any], index: int = 0) -> "CandidateGroup":
        if not isinstance(raw, Mapping):
            raise NamingError(ERR_INVALID_REQUEST, "groups 元素必须是对象", details={"group": index})
        family = raw.get("family")
        if not isinstance(family, str) or not family.strip():
            raise NamingError(ERR_INVALID_FAMILY, "groups[].family 必填且为非空字符串", details={"group": index})
        files_raw = raw.get("files")
        if not isinstance(files_raw, Sequence) or isinstance(files_raw, (str, bytes)) or not files_raw:
            raise NamingError(ERR_INVALID_REQUEST, "groups[].files 必须是非空数组", details={"group": index})
        if len(files_raw) > MAX_FILES_PER_GROUP:
            raise NamingError(
                ERR_INVALID_REQUEST,
                f"单组文件数超过上限 {MAX_FILES_PER_GROUP}",
                details={"group": index},
            )
        files = tuple(CandidateFile.from_mapping(item) for item in files_raw)
        confidence = raw.get("confidence", 0.0)
        if not isinstance(confidence, (int, float)) or isinstance(confidence, bool):
            raise NamingError(ERR_INVALID_CONFIDENCE, "confidence 必须是 0..1 数字", details={"group": index})
        confidence = float(confidence)
        if confidence < 0.0 or confidence > 1.0:
            raise NamingError(ERR_INVALID_CONFIDENCE, "confidence 必须落在 0..1", details={"group": index})
        confirmed = raw.get("confirmed", False)
        if not isinstance(confirmed, bool):
            raise NamingError(ERR_INVALID_REQUEST, "confirmed 必须是布尔值", details={"group": index})
        evidence_raw = raw.get("evidence", ())
        if evidence_raw is None:
            evidence_raw = ()
        if not isinstance(evidence_raw, Sequence) or isinstance(evidence_raw, (str, bytes)):
            raise NamingError(ERR_INVALID_REQUEST, "evidence 必须是字符串数组", details={"group": index})
        evidence = tuple(str(item) for item in evidence_raw)
        order_raw = raw.get("order", ())
        if order_raw is None:
            order_raw = ()
        if not isinstance(order_raw, Sequence) or isinstance(order_raw, (str, bytes)):
            raise NamingError(ERR_INVALID_REQUEST, "order 必须是文件名数组", details={"group": index})
        order = tuple(str(item) for item in order_raw)
        existing_raw = raw.get("existingWorkNumber")
        existing_work_number: int | None
        if existing_raw is None:
            existing_work_number = None
        elif isinstance(existing_raw, int) and not isinstance(existing_raw, bool) and existing_raw >= 1:
            existing_work_number = existing_raw
        else:
            raise NamingError(
                ERR_INVALID_REQUEST,
                "existingWorkNumber 必须是正整数（补图时指向既有作品编号）",
                details={"group": index},
            )
        return cls(
            family=family,
            files=files,
            confidence=confidence,
            confirmed=confirmed,
            evidence=evidence,
            order=order,
            existing_work_number=existing_work_number,
        )


@dataclass(frozen=True)
class NamingRules:
    """命名规则（用户需求默认值见字段默认；调用方可显式覆盖）。"""

    min_confidence: float = DEFAULT_MIN_CONFIDENCE
    require_confirmed: bool = True
    page_bracket: tuple[str, str] = DEFAULT_PAGE_BRACKET
    max_work_number: int = DEFAULT_MAX_WORK_NUMBER

    @classmethod
    def coerce(cls, rules: "NamingRules | Mapping[str, Any] | None") -> "NamingRules":
        if rules is None:
            return cls()
        if isinstance(rules, cls):
            return rules
        if not isinstance(rules, Mapping):
            raise NamingError(ERR_INVALID_REQUEST, "rules 必须是对象")
        min_confidence = rules.get("minConfidence", DEFAULT_MIN_CONFIDENCE)
        if not isinstance(min_confidence, (int, float)) or isinstance(min_confidence, bool):
            raise NamingError(ERR_INVALID_CONFIDENCE, "rules.minConfidence 必须是数字")
        min_confidence = float(min_confidence)
        if min_confidence < 0.0 or min_confidence > 1.0:
            raise NamingError(ERR_INVALID_CONFIDENCE, "rules.minConfidence 必须落在 0..1")
        require_confirmed = rules.get("requireConfirmed", True)
        if not isinstance(require_confirmed, bool):
            raise NamingError(ERR_INVALID_REQUEST, "rules.requireConfirmed 必须是布尔值")
        page_bracket = rules.get("pageBracket", DEFAULT_PAGE_BRACKET)
        if (
            not isinstance(page_bracket, Sequence)
            or isinstance(page_bracket, (str, bytes))
            or len(page_bracket) != 2
            or not all(isinstance(part, str) and part for part in page_bracket)
        ):
            raise NamingError(ERR_INVALID_REQUEST, "rules.pageBracket 必须是两个非空字符串")
        max_work_number = rules.get("maxWorkNumber", DEFAULT_MAX_WORK_NUMBER)
        if not isinstance(max_work_number, int) or isinstance(max_work_number, bool) or max_work_number < 1:
            raise NamingError(ERR_INVALID_REQUEST, "rules.maxWorkNumber 必须是正整数")
        return cls(
            min_confidence=min_confidence,
            require_confirmed=require_confirmed,
            page_bracket=(page_bracket[0], page_bracket[1]),
            max_work_number=max_work_number,
        )

    def as_dict(self) -> dict[str, Any]:
        return {
            "minConfidence": self.min_confidence,
            "requireConfirmed": self.require_confirmed,
            "pageBracket": list(self.page_bracket),
            "maxWorkNumber": self.max_work_number,
        }


# ---------------------------------------------------------------------------
# 文件名解析（与 server/internal/filing/workname.go 同语法）
# ---------------------------------------------------------------------------

_MARKERS = ("No.", "no.", "NO.", "No ", "no ", "NO ", "#", "EP", "ep", "EP.", "ep.", "Vol.", "vol.")
_PAGE_BRACKETS = (("(", ")"), ("（", "）"))
_LEGACY_BRACKETS = (("(", ")"), ("（", "）"), ("[", "]"), ("【", "】"))
_FAMILY_FORBIDDEN_RE = re.compile(r'[\x00-\x1f\x7f<>:"/\\|?*]')
_ASCII_LOWER_TABLE = str.maketrans("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz")


def _ascii_lower(text: str) -> str:
    """只折叠 ASCII 大小写（与 Go asciiLower 一致，保留中文/Unicode 原样）。"""
    return text.translate(_ASCII_LOWER_TABLE)


def _is_ascii_digits(text: str) -> bool:
    return bool(text) and all("0" <= ch <= "9" for ch in text)


def _is_ascii_letter(ch: str) -> bool:
    return ("a" <= ch <= "z") or ("A" <= ch <= "Z")


def normalize_family(family: str) -> str:
    """作品族键：空白折叠 + ASCII 小写；用于索引与锁 key，不改变展示名。"""
    return _ascii_lower(" ".join(family.split()))


def validate_family_name(family: str) -> str:
    """校验用于生成文件名的作品基名；返回折叠空白后的展示名。"""
    if not isinstance(family, str) or not family.strip():
        raise NamingError(ERR_INVALID_FAMILY, "family 必须是非空字符串")
    text = " ".join(family.split())
    if len(text) > MAX_FAMILY_LENGTH:
        raise NamingError(ERR_INVALID_FAMILY, f"family 超过 {MAX_FAMILY_LENGTH} 字符")
    if text in (".", ".."):
        raise NamingError(ERR_INVALID_FAMILY, "family 不能是 . 或 ..")
    if _FAMILY_FORBIDDEN_RE.search(text):
        raise NamingError(ERR_INVALID_FAMILY, "family 含文件名非法字符", details={"family": text})
    if text != text.rstrip(" ."):
        raise NamingError(ERR_INVALID_FAMILY, "family 不能以空格或点结尾")
    return text


def split_extension(file_name: str) -> tuple[str, str]:
    """拆分扩展名：与 Go path.Ext/stripExt 一致，最后一个点后的后缀为扩展名。"""
    stem, extension = os.path.splitext(file_name)
    if not stem:
        # 形如 ".gitignore"：整个名字视为无扩展名主体（与原 normNameBase 口径一致）。
        return file_name, ""
    return stem, extension


def _parse_bare_series(base: str) -> tuple[str, str, str, int, int] | None:
    """解析裸作品编号：返回 (family, separator, marker, number, width)。"""
    if not base:
        return None
    end = len(base)
    start = end
    while start > 0 and "0" <= base[start - 1] <= "9":
        start -= 1
    if start == end or start == 0:
        return None
    digit_str = base[start:end]
    number = int(digit_str)
    width = len(digit_str)

    rem = base[:start]
    marker = ""
    for candidate in _MARKERS:
        if rem.endswith(candidate):
            marker = candidate
            rem = rem[: len(rem) - len(candidate)]
            break

    stripped = rem.rstrip()
    if stripped == rem:
        separator = ""
        family = rem
    else:
        separator = rem[len(stripped):]
        family = stripped
    if not family:
        return None
    if not separator and not marker:
        # 无空白紧贴单个 ASCII 字母（如 v2）不按作品编号处理；中文紧贴/多位数字允许。
        if _is_ascii_letter(family[-1]) and width == 1 and len(family.encode("utf-8")) <= 2:
            return None
    return family, separator, marker, number, width


def _trailing_page_suffix(base: str) -> tuple[int, int, str] | None:
    """识别尾随组内页码；返回 (page, page_width, work_part)。"""
    for open_bracket, close_bracket in _PAGE_BRACKETS:
        if not base.endswith(close_bracket):
            continue
        open_index = base.rfind(open_bracket)
        if open_index <= 0:
            continue
        inner = base[open_index + len(open_bracket): len(base) - len(close_bracket)]
        if not _is_ascii_digits(inner):
            continue
        page = int(inner)
        if page <= 0:
            continue
        work_part = base[:open_index].rstrip()
        if not work_part:
            continue
        return page, len(inner), work_part
    return None


def _legacy_bracket_suffix(base: str) -> str | None:
    """识别旧「仅括号序号」格式（{基名} (2)），返回基名；不视为作品编号。"""
    for open_bracket, close_bracket in _LEGACY_BRACKETS:
        if not base.endswith(close_bracket):
            continue
        open_index = base.rfind(open_bracket)
        if open_index <= 0:
            continue
        inner = base[open_index + len(open_bracket): len(base) - len(close_bracket)].strip()
        if not _is_ascii_digits(inner):
            continue
        family = base[:open_index].rstrip()
        if family:
            return family
    return None


@dataclass(frozen=True)
class FileNameInfo:
    """单个库内文件名的解析结果；无法归属编号族时 family 为空串。"""

    file_name: str
    stem: str
    extension: str
    family: str
    family_key: str
    work_number: int | None
    work_width: int
    separator: str
    marker: str
    page_number: int | None
    page_width: int
    is_group_page: bool
    is_legacy_bracket_series: bool

    def as_dict(self) -> dict[str, Any]:
        return {
            "fileName": self.file_name,
            "family": self.family,
            "familyKey": self.family_key,
            "workNumber": self.work_number,
            "workWidth": self.work_width,
            "separator": self.separator,
            "marker": self.marker,
            "pageNumber": self.page_number,
            "pageWidth": self.page_width,
            "isGroupPage": self.is_group_page,
            "isLegacyBracketSeries": self.is_legacy_bracket_series,
        }


def parse_file_name(file_name: str) -> FileNameInfo:
    """解析既有库文件名（独立作品 / 组图页 / 旧括号序号 / 无结构）。"""
    if not isinstance(file_name, str) or not file_name:
        raise NamingError(ERR_INVALID_REQUEST, "file_name 必须是非空字符串")
    stem, extension = split_extension(file_name)
    base = stem

    def make(**kwargs: Any) -> FileNameInfo:
        defaults: dict[str, Any] = {
            "file_name": file_name,
            "stem": stem,
            "extension": extension,
            "family": "",
            "family_key": "",
            "work_number": None,
            "work_width": 0,
            "separator": "",
            "marker": "",
            "page_number": None,
            "page_width": 0,
            "is_group_page": False,
            "is_legacy_bracket_series": False,
        }
        defaults.update(kwargs)
        return FileNameInfo(**defaults)

    page_suffix = _trailing_page_suffix(base)
    if page_suffix is not None:
        page, page_width, work_part = page_suffix
        bare = _parse_bare_series(work_part)
        if bare is not None:
            family, separator, marker, number, width = bare
            return make(
                family=family,
                family_key=normalize_family(family),
                work_number=number,
                work_width=width,
                separator=separator,
                marker=marker,
                page_number=page,
                page_width=page_width,
                is_group_page=True,
            )
        legacy_family = _legacy_bracket_suffix(base)
        return make(
            family=legacy_family or "",
            family_key=normalize_family(legacy_family or ""),
            is_legacy_bracket_series=legacy_family is not None,
        )

    bare = _parse_bare_series(base)
    if bare is not None:
        family, separator, marker, number, width = bare
        return make(
            family=family,
            family_key=normalize_family(family),
            work_number=number,
            work_width=width,
            separator=separator,
            marker=marker,
        )

    legacy_family = _legacy_bracket_suffix(base)
    if legacy_family is not None:
        return make(
            family=legacy_family,
            family_key=normalize_family(legacy_family),
            is_legacy_bracket_series=True,
        )
    return make()


# ---------------------------------------------------------------------------
# 既有库作品索引（family -> 已占作品编号/位宽风格/已占页号）
# ---------------------------------------------------------------------------


@dataclass
class FamilyIndex:
    """一个作品族在目标库/目录中已占用的作品编号与页码。"""

    family: str
    family_key: str
    work_numbers: dict[int, tuple[int, ...]]
    work_width: int
    separator: str
    marker: str

    def next_work_number(self) -> int:
        """下一可用作品编号 = 已占最大编号 + 1（不回收空号，避免复用已删作品编号）。"""
        return max(self.work_numbers) + 1 if self.work_numbers else 1

    def max_page(self, work_number: int) -> int:
        pages = self.work_numbers.get(work_number, ())
        return max(pages) if pages else 0

    def as_dict(self) -> dict[str, Any]:
        return {
            "family": self.family,
            "workNumbers": sorted(self.work_numbers),
            "maxWorkNumber": max(self.work_numbers) if self.work_numbers else 0,
            "workWidth": self.work_width,
            "separator": self.separator,
            "marker": self.marker,
            "pages": {str(number): list(pages) for number, pages in sorted(self.work_numbers.items())},
        }


@dataclass
class WorkIndex:
    """既有库目录的作品索引（纯数据；precondition 指纹另算）。"""

    file_names: tuple[str, ...]
    files: tuple[FileNameInfo, ...]
    families: dict[str, FamilyIndex]

    def family(self, family: str) -> FamilyIndex | None:
        return self.families.get(normalize_family(family))

    def next_work_number(self, family: str) -> int:
        found = self.family(family)
        return found.next_work_number() if found else 1

    def max_page(self, family: str, work_number: int) -> int:
        found = self.family(family)
        return found.max_page(work_number) if found else 0

    def as_dict(self) -> dict[str, Any]:
        return {
            "fileNames": list(self.file_names),
            "families": {
                key: value.as_dict() for key, value in sorted(self.families.items())
            },
        }


def build_work_index(file_names: Iterable[str]) -> WorkIndex:
    """从库内现有文件名构建作品索引（组内页只记页码，不占额外作品编号）。"""
    names = tuple(file_names)
    files = tuple(parse_file_name(name) for name in names)

    grouped: dict[str, list[FileNameInfo]] = {}
    for info in files:
        if info.work_number is None or not info.family_key:
            continue
        grouped.setdefault(info.family_key, []).append(info)

    families: dict[str, FamilyIndex] = {}
    for family_key, infos in grouped.items():
        work_numbers: dict[int, list[int]] = {}
        for info in infos:
            assert info.work_number is not None
            pages = work_numbers.setdefault(info.work_number, [])
            if info.is_group_page and info.page_number is not None and info.page_number not in pages:
                pages.append(info.page_number)
        # 位宽/分隔风格取「最大作品编号 + 最大位宽 + 字典序最小文件名」的观测，
        # 保证同一库同样输入得到确定结果（不依赖文件名传入顺序）。
        max_number = max(work_numbers)
        style_candidates = [info for info in infos if info.work_number == max_number]
        style = sorted(style_candidates, key=lambda item: (-item.work_width, item.file_name))[0]
        families[family_key] = FamilyIndex(
            family=style.family,
            family_key=family_key,
            work_numbers={number: tuple(sorted(pages)) for number, pages in work_numbers.items()},
            work_width=style.work_width,
            separator=style.separator,
            marker=style.marker,
        )
    return WorkIndex(file_names=names, files=files, families=families)


# ---------------------------------------------------------------------------
# 指纹与格式化工具
# ---------------------------------------------------------------------------


def file_names_fingerprint(file_names: Iterable[str]) -> str:
    """前置状态指纹：对目录内文件名全集（排序后）取 SHA-256。"""
    payload = json.dumps(sorted(file_names), ensure_ascii=False, separators=(",", ":"))
    digest = hashlib.sha256(("file-names-v1\n" + payload).encode("utf-8")).hexdigest()
    return "sha256:" + digest


def _format_work_number(number: int, width: int) -> str:
    digits = str(number)
    if width > len(digits):
        return digits.zfill(width)
    return digits


def _build_target_name(
    family: str,
    separator: str,
    number_str: str,
    extension: str,
    page_number: int | None,
    page_bracket: tuple[str, str],
) -> str:
    if page_number is None:
        return f"{family}{separator}{number_str}{extension}"
    open_bracket, close_bracket = page_bracket
    return f"{family}{separator}{number_str} {open_bracket}{page_number}{close_bracket}{extension}"


def _normalize_directory(directory: str) -> str:
    if not isinstance(directory, str):
        raise NamingError(ERR_INVALID_REQUEST, "directory 必须是字符串")
    text = directory.strip()
    if not text or text in ("/", "\\"):
        return ""
    if _FAMILY_FORBIDDEN_RE.search(text.replace("/", "").replace("\\", "")):
        raise NamingError(ERR_INVALID_REQUEST, "directory 含非法控制字符")
    normalized = text.replace("\\", "/").strip("/")
    segments = normalized.split("/")
    if any(segment in ("", ".", "..") for segment in segments):
        raise NamingError(ERR_INVALID_REQUEST, "directory 必须是库内规范化相对路径")
    if re.match(r"^[A-Za-z]:", normalized):
        raise NamingError(ERR_INVALID_REQUEST, "directory 必须是库内相对路径，不能是绝对路径")
    return normalized


def _require_text(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise NamingError(ERR_INVALID_REQUEST, f"{field_name} 必须是非空字符串")
    return value.strip()


def _coerce_library(library: Any) -> dict[str, str]:
    """库标识：接受 {"id","name"} 或纯字符串，统一为稳定字典（id 必有）。"""
    if isinstance(library, Mapping):
        raw_id = library.get("id", "")
        raw_name = library.get("name", "")
        library_id = raw_id.strip() if isinstance(raw_id, str) else ""
        library_name = raw_name.strip() if isinstance(raw_name, str) else ""
        if not library_id and not library_name:
            raise NamingError(ERR_INVALID_REQUEST, "library 必须包含非空 id 或 name")
        return {"id": library_id or library_name, "name": library_name or library_id}
    if isinstance(library, str) and library.strip():
        text = library.strip()
        return {"id": text, "name": text}
    raise NamingError(ERR_INVALID_REQUEST, "library 必须是库标识对象 {id,name} 或字符串")


def _validate_source_file_name(name: str) -> None:
    if not isinstance(name, str) or not name.strip():
        raise NamingError(ERR_INVALID_REQUEST, "文件名必须是非空字符串")
    if "/" in name or "\\" in name:
        raise NamingError(ERR_INVALID_REQUEST, "文件名不能包含路径分隔符", details={"fileName": name})
    if _FAMILY_FORBIDDEN_RE.search(name):
        raise NamingError(ERR_INVALID_REQUEST, "文件名含控制字符或 Windows 非法字符", details={"fileName": name})
    if len(name) > MAX_FILE_NAME_LENGTH:
        raise NamingError(ERR_INVALID_REQUEST, "文件名超长", details={"fileName": name})


def _coerce_current_file_names(file_names: Iterable[str]) -> tuple[str, ...]:
    if isinstance(file_names, (str, bytes)) or not isinstance(file_names, Sequence):
        raise NamingError(ERR_INVALID_REQUEST, "current_file_names 必须是字符串数组")
    names = tuple(file_names)
    for name in names:
        _validate_source_file_name(name)
    if len(set(names)) != len(names):
        raise NamingError(ERR_INVALID_REQUEST, "current_file_names 存在重复文件名")
    return names


# ---------------------------------------------------------------------------
# 规划（prepare）：识别分组 -> 分配作品编号 -> 生成页码/目标名 -> 计划 JSON
# ---------------------------------------------------------------------------


def prepare_plan(
    groups: Sequence[Mapping[str, Any] | CandidateGroup],
    current_file_names: Sequence[str],
    *,
    instance: str,
    library: str | Mapping[str, Any],
    directory: str = "",
    rules: NamingRules | Mapping[str, Any] | None = None,
    revision: int | str | None = None,
) -> dict[str, Any]:
    """生成机器可读命名计划（不写任何文件/不调用 API）。

    分组提案中的乱码原始文件名只出现在 changes 的旧名与素材证据里，目标名只由
    family + 作品编号 + 页码 + 原扩展名决定；同 family 多组按提案顺序连续
    分配作品编号（每组只占一个），confidence 不足或未 confirmed 时计划返回
    needs_confirmation，apply 入口必须拒绝。
    """
    instance_text = _require_text(instance, "instance")
    library_value = _coerce_library(library)
    directory_text = _normalize_directory(directory)
    rule_set = NamingRules.coerce(rules)

    current = _coerce_current_file_names(current_file_names)
    current_set = set(current)

    if isinstance(groups, (str, bytes)) or not isinstance(groups, Sequence) or not groups:
        raise NamingError(ERR_INVALID_REQUEST, "groups 必须是非空数组")
    if len(groups) > MAX_GROUPS:
        raise NamingError(ERR_INVALID_REQUEST, f"分组数超过上限 {MAX_GROUPS}")
    candidate_groups: list[CandidateGroup] = []
    for index, raw_group in enumerate(groups):
        if isinstance(raw_group, CandidateGroup):
            coerced = raw_group
        else:
            coerced = CandidateGroup.from_mapping(raw_group, index)
        candidate_groups.append(
            CandidateGroup(
                family=validate_family_name(coerced.family),
                files=coerced.files,
                confidence=coerced.confidence,
                confirmed=coerced.confirmed,
                evidence=coerced.evidence,
                order=coerced.order,
                existing_work_number=coerced.existing_work_number,
            )
        )

    source_names: set[str] = set()
    for index, group in enumerate(candidate_groups):
        names = [item.name for item in group.files]
        for name in names:
            _validate_source_file_name(name)
            if name in source_names:
                raise NamingError(ERR_DUPLICATE_SOURCE, "同一文件出现在多个分组", details={"fileName": name})
            source_names.add(name)
            if name not in current_set:
                raise NamingError(
                    ERR_UNKNOWN_SOURCE,
                    "分组文件不在目标库/目录当前文件名中（先上传入库再准备改名计划）",
                    details={"fileName": name, "group": index},
                )
        if group.order:
            if len(group.order) != len(set(group.order)) or set(group.order) != set(names):
                raise NamingError(
                    ERR_INVALID_REQUEST,
                    "order 必须且只能列出该组全部文件各一次",
                    details={"group": index},
                )

    # 保留整个目录的前置指纹/重名检查；只有编号索引按图片/视频分开。
    indexes = {category: build_work_index([name for name in current
                                          if numbering_category(name) == category])
               for category in {numbering_category(name) for name in current}}
    assignments: list[dict[str, Any]] = []
    used_targets: set[str] = set()
    assigned_last: dict[tuple[str, str], int] = {}

    for group in candidate_groups:
        family_key = normalize_family(group.family)
        categories = {numbering_category(item.name) for item in group.files}
        if len(categories) != 1 or "other" in categories:
            raise NamingError(ERR_INVALID_REQUEST, "一个编号组必须属于图片或视频同一类别")
        category = next(iter(categories))
        numbering_key = (family_key, category)
        family_index = indexes[category].families.get(family_key)
        ordered_names = list(group.order) if group.order else [item.name for item in group.files]
        file_by_name = {item.name: item for item in group.files}

        if group.existing_work_number is not None:
            work_number = group.existing_work_number
            if family_index is None or work_number not in family_index.work_numbers:
                raise NamingError(
                    ERR_INVALID_REQUEST,
                    "补图的既有作品编号在该 family 下不存在",
                    details={"family": group.family, "workNumber": work_number},
                )
            kind = "group"
            existing_work_number = True
            pages_start = family_index.max_page(work_number) + 1
            work_width = family_index.work_width
            separator = family_index.separator
        else:
            kind = "group" if len(ordered_names) > 1 else "standalone"
            existing_work_number = False
            pages_start = 1 if kind == "group" else 0
            if family_index is not None:
                work_width = family_index.work_width
                separator = family_index.separator
                next_number = family_index.next_work_number()
            else:
                next_number = 1
                work_width = 1
                separator = DEFAULT_SEPARATOR
            work_number = max(next_number, assigned_last.get(numbering_key, 0) + 1)
            if work_number > rule_set.max_work_number:
                raise NamingError(
                    ERR_WORK_NUMBER_CONFLICT,
                    "作品编号超过规则上限",
                    details={"family": group.family, "workNumber": work_number},
                )
            assigned_last[numbering_key] = work_number

        number_str = _format_work_number(work_number, work_width)
        pages: list[dict[str, Any]] = []
        for page_index, source_name in enumerate(ordered_names):
            member = file_by_name[source_name]
            _stem, extension = split_extension(source_name)
            if kind == "group":
                page_number: int | None = pages_start + page_index
            else:
                page_number = None
            target_name = _build_target_name(
                family=group.family,
                separator=separator,
                number_str=number_str,
                extension=extension,
                page_number=page_number,
                page_bracket=rule_set.page_bracket,
            )
            if len(target_name) > MAX_FILE_NAME_LENGTH:
                raise NamingError(
                    ERR_TARGET_CONFLICT,
                    "目标文件名超长",
                    details={"family": group.family, "targetName": target_name},
                )
            if target_name in used_targets:
                raise NamingError(
                    ERR_TARGET_CONFLICT,
                    "计划内目标文件名重复",
                    details={"targetName": target_name},
                )
            used_targets.add(target_name)
            pages.append(
                {
                    "sourceName": source_name,
                    "pageNumber": page_number,
                    "targetName": target_name,
                    "assetId": member.asset_id or None,
                    "evidence": member.evidence,
                }
            )
        assignments.append(
            {
                "family": group.family,
                "familyKey": family_key,
                "numberingCategory": category,
                "kind": kind,
                "workNumber": work_number,
                "workNumberStyle": {"separator": separator, "width": work_width},
                "existingWorkNumber": existing_work_number,
                "confidence": group.confidence,
                "confirmed": group.confirmed,
                "evidence": list(group.evidence),
                "pages": pages,
            }
        )

    changes: list[dict[str, Any]] = []
    for assignment in assignments:
        for page in assignment["pages"]:
            if page["sourceName"] == page["targetName"]:
                continue  # 已符合计划，幂等重跑不产生变更
            if page["targetName"] in current_set:
                raise NamingError(
                    ERR_TARGET_CONFLICT,
                    "目标文件名已被目标目录其他文件占用",
                    details={"targetName": page["targetName"]},
                )
            changes.append(
                {
                    "sourceName": page["sourceName"],
                    "targetName": page["targetName"],
                    "assetId": page["assetId"],
                    "instance": instance_text,
                    "library": library_value,
                    "targetDir": directory_text,
                    "action": "move-rename",
                    "family": assignment["family"],
                    "evidence": page["evidence"],
                }
            )

    sources = {change["sourceName"] for change in changes}
    targets = {change["targetName"] for change in changes}
    expected_names = sorted((current_set - sources) | targets)

    reasons: list[dict[str, Any]] = []
    for assignment in assignments:
        if assignment["confidence"] < rule_set.min_confidence:
            reasons.append(
                {
                    "code": "low_confidence",
                    "family": assignment["family"],
                    "confidence": assignment["confidence"],
                    "threshold": rule_set.min_confidence,
                }
            )
        if rule_set.require_confirmed and not assignment["confirmed"]:
            reasons.append(
                {
                    "code": "unconfirmed",
                    "family": assignment["family"],
                    "confidence": assignment["confidence"],
                }
            )

    grouped_files = sum(len(a["pages"]) for a in assignments if a["kind"] == "group")
    standalone_files = sum(len(a["pages"]) for a in assignments if a["kind"] == "standalone")
    appended_pages = sum(len(a["pages"]) for a in assignments if a["existingWorkNumber"])

    plan: dict[str, Any] = {
        "schemaVersion": PLAN_SCHEMA_VERSION,
        "engineVersion": ENGINE_VERSION,
        "tool": {"id": TOOL_PREPARE_ID, "version": ENGINE_VERSION},
        "status": "needs_confirmation" if reasons else "ready",
        "target": {"instance": instance_text, "library": library_value, "directory": directory_text},
        "rules": rule_set.as_dict(),
        "precondition": {
            "fileNames": sorted(current),
            "fingerprint": file_names_fingerprint(current),
            "revision": revision,
        },
        "planFingerprint": "",
        "idempotencyKey": "",
        "permissions": {
            "required": [TOOL_APPLY_ID],
            "confirmationRequired": bool(reasons),
            "confirmed": not reasons,
            "reasons": reasons,
        },
        "noOp": not changes,
        "impact": {
            "groups": len(assignments),
            "works": len(assignments),
            "files": sum(len(a["pages"]) for a in assignments),
            "groupedFiles": grouped_files,
            "standaloneFiles": standalone_files,
            "appendedPages": appended_pages,
            "workNumbers": [a["workNumber"] for a in assignments],
            "renamed": len(changes),
        },
        "groups": assignments,
        "changes": changes,
        "expectedReadback": {
            "directory": directory_text,
            "fileNames": expected_names,
            "removedFileNames": sorted(sources),
        },
        "preview": [],
    }
    plan["preview"] = _build_preview(plan)
    fingerprint = plan_fingerprint(plan)
    plan["planFingerprint"] = fingerprint
    plan["idempotencyKey"] = _build_idempotency_key(plan, fingerprint)
    return plan


def _build_idempotency_key(plan: Mapping[str, Any], fingerprint: str) -> str:
    target = plan["target"]
    material = json.dumps(
        ["idempotency-v1", TOOL_APPLY_ID, target["instance"], target["library"], target["directory"], fingerprint],
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return "sha256:" + hashlib.sha256(material.encode("utf-8")).hexdigest()


def plan_fingerprint(plan: Mapping[str, Any]) -> str:
    """计划指纹：同一逻辑计划稳定（与输入顺序无关的部分已排序）。"""
    groups = []
    for assignment in plan.get("groups", ()):
        pages = [
            {
                "sourceName": page["sourceName"],
                "pageNumber": page["pageNumber"],
                "targetName": page["targetName"],
            }
            for page in assignment.get("pages", ())
        ]
        pages.sort(key=lambda page: (page["pageNumber"] is None, page["pageNumber"] or 0, page["sourceName"]))
        groups.append(
            {
                "familyKey": assignment["familyKey"],
                "numberingCategory": assignment.get("numberingCategory"),
                "kind": assignment["kind"],
                "workNumber": assignment["workNumber"],
                "existingWorkNumber": assignment["existingWorkNumber"],
                "pages": pages,
            }
        )
    groups.sort(key=lambda item: (item["familyKey"], item["workNumber"], item["kind"]))
    changes = [
        {
            "sourceName": change["sourceName"],
            "targetName": change["targetName"],
            "assetId": change.get("assetId"),
        }
        for change in plan.get("changes", ())
    ]
    changes.sort(key=lambda item: (item["sourceName"], item["targetName"]))
    material = {
        "schemaVersion": plan.get("schemaVersion"),
        "engineVersion": plan.get("engineVersion"),
        "target": plan.get("target"),
        "rules": plan.get("rules"),
        "preconditionFingerprint": plan.get("precondition", {}).get("fingerprint"),
        "groups": groups,
        "changes": changes,
    }
    encoded = json.dumps(material, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return "sha256:" + hashlib.sha256(("plan-v1\n" + encoded).encode("utf-8")).hexdigest()


def verify_plan_fingerprint(plan: Mapping[str, Any]) -> bool:
    """校验计划未被外部篡改（apply 前必查）。"""
    try:
        expected = plan.get("planFingerprint")
        return isinstance(expected, str) and expected == plan_fingerprint(plan)
    except (KeyError, TypeError):
        return False


def _build_preview(plan: Mapping[str, Any]) -> list[str]:
    status_label = "可执行" if plan.get("status") == "ready" else "待确认"
    target = plan["target"]
    impact = plan["impact"]
    library = target["library"]
    library_label = " / ".join(
        part for part in (library.get("id"), library.get("name")) if part
    ) if isinstance(library, Mapping) else str(library)
    lines = [
        f"作品命名计划（{status_label}）",
        f"目标：实例 {target['instance']} · 库 {library_label} · 目录 {target['directory'] or '（库根）'}",
        f"现状：{len(plan['precondition']['fileNames'])} 个文件名 · 指纹 {plan['precondition']['fingerprint']}",
        (
            f"影响：{impact['groups']} 个作品 / {impact['files']} 个文件 / "
            f"实际改名 {impact['renamed']} 个 / 补图 {impact['appendedPages']} 页"
        ),
    ]
    for assignment in plan["groups"]:
        pages = assignment["pages"]
        if assignment["kind"] == "group":
            page_desc = (
                f"第{pages[0]['pageNumber']}–{pages[-1]['pageNumber']}页"
                if len(pages) > 1
                else f"第{pages[0]['pageNumber']}页"
            )
            prefix = "补图 " if assignment["existingWorkNumber"] else "组图 "
            lines.append(f"- {prefix}{assignment['family']} · 作品编号 {assignment['workNumber']} · {page_desc}")
        else:
            lines.append(f"- 独立作品 {assignment['family']} · 作品编号 {assignment['workNumber']}")
        for page in pages:
            arrow = "（已符合）" if page["sourceName"] == page["targetName"] else "->"
            lines.append(f"    {page['sourceName']} {arrow} {page['targetName']}")
    if plan.get("noOp"):
        lines.append("（无需改名：现状已符合计划）")
    if plan["permissions"]["reasons"]:
        lines.append("需确认：")
        for reason in plan["permissions"]["reasons"]:
            if reason["code"] == "low_confidence":
                lines.append(
                    f"  · {reason['family']} 置信度 {reason['confidence']:.2f} < {reason['threshold']:.2f}"
                )
            else:
                lines.append(f"  · {reason['family']} 尚未确认分组")
    return lines


def render_preview(plan: Mapping[str, Any]) -> str:
    """人读预览（计划中的 preview 已是人读行，可等宽/中文直接打印）。"""
    preview = plan.get("preview")
    if isinstance(preview, Sequence) and not isinstance(preview, (str, bytes)) and preview:
        return "\n".join(str(line) for line in preview)
    return "\n".join(_build_preview(plan))


# ---------------------------------------------------------------------------
# 校验（apply 前）：重新取现状 -> 前置指纹/计划指纹/占用/编号连续性
# ---------------------------------------------------------------------------


def validate_plan(
    plan: Mapping[str, Any],
    current_file_names: Sequence[str],
    *,
    confirmed: bool = True,
) -> dict[str, Any]:
    """apply 前强制校验；返回 {"ok", "errors": [...], "warnings": [...]}。

    调用方必须重新查询目标目录现状后调用：前置状态指纹变化、计划被改动、
    目标重名/占用、同 family 作品编号重复或人为跳号、未确认计划都会失败。
    """
    errors: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []

    def fail(code: str, message: str, **details: Any) -> None:
        item = {"code": code, "message": message}
        item.update(details)
        errors.append(item)

    if not isinstance(plan, Mapping):
        fail(ERR_INVALID_REQUEST, "plan 必须是对象")
        return {"ok": False, "errors": errors, "warnings": warnings}

    if plan.get("schemaVersion") != PLAN_SCHEMA_VERSION or plan.get("engineVersion") != ENGINE_VERSION:
        fail(ERR_INVALID_REQUEST, "命名规则版本已变化，请重新 prepare")
        return {"ok": False, "errors": errors, "warnings": warnings}

    try:
        rule_set = NamingRules.coerce(plan.get("rules"))
        precondition = plan["precondition"]
        target = plan["target"]
        groups = plan["groups"]
        changes = plan["changes"]
        expected_readback = plan["expectedReadback"]
    except (KeyError, TypeError):
        fail(ERR_INVALID_REQUEST, "plan 缺少必需字段")
        return {"ok": False, "errors": errors, "warnings": warnings}

    if not verify_plan_fingerprint(plan):
        fail(ERR_PLAN_FINGERPRINT, "计划指纹不一致：计划内容已被修改，必须重新 prepare")
    if plan.get("status") != "ready":
        fail(ERR_NEEDS_CONFIRMATION, "计划尚未确认，不允许执行")
    if plan.get("permissions", {}).get("confirmationRequired") and not confirmed:
        fail(ERR_NEEDS_CONFIRMATION, "计划需要用户确认后才能执行")

    try:
        current = _coerce_current_file_names(current_file_names)
    except NamingError as exc:
        fail(exc.code, str(exc))
        return {"ok": False, "errors": errors, "warnings": warnings}
    current_set = set(current)
    observed_fingerprint = file_names_fingerprint(current)
    if observed_fingerprint != precondition.get("fingerprint"):
        fail(
            ERR_STALE_PRECONDITION,
            "目标目录文件名已变化：计划前置指纹失效，必须重新 prepare",
            expected=precondition.get("fingerprint"),
            observed=observed_fingerprint,
        )

    sources: list[str] = []
    targets: list[str] = []
    for change in changes:
        source = change.get("sourceName")
        target_name = change.get("targetName")
        if not isinstance(source, str) or not isinstance(target_name, str):
            fail(ERR_INVALID_REQUEST, "changes 缺 sourceName/targetName")
            continue
        sources.append(source)
        targets.append(target_name)
        if source not in current_set:
            fail(ERR_UNKNOWN_SOURCE, "待改名文件已不在目标目录", fileName=source)
        if change.get("targetDir", target.get("directory")) != target.get("directory"):
            fail(ERR_INVALID_REQUEST, "change.targetDir 与计划目标目录不一致", fileName=source)
    if len(sources) != len(set(sources)):
        fail(ERR_INVALID_REQUEST, "changes.sourceName 重复")
    if len(targets) != len(set(targets)):
        fail(ERR_TARGET_CONFLICT, "计划内目标文件名重复")
    for change in changes:
        target_name = change.get("targetName")
        if isinstance(target_name, str) and target_name in current_set and target_name != change.get("sourceName"):
            fail(ERR_TARGET_CONFLICT, "目标文件名已被其他现存文件占用", targetName=target_name)

    # 编号连续性/占用复核：作品编号必须从当前最大已占 +1 起连续分配；
    # 补图必须指向该 family 既有作品编号且页号从既有最大页 +1 起连续。
    current_indexes = {category: build_work_index([name for name in current
                                                 if numbering_category(name) == category])
                       for category in {numbering_category(name) for name in current}}
    last_assigned: dict[tuple[str, str], int] = {}
    for group in groups:
        family = group.get("family")
        family_key = group.get("familyKey")
        work_number = group.get("workNumber")
        if not isinstance(family, str) or not isinstance(family_key, str) or not isinstance(work_number, int):
            fail(ERR_INVALID_REQUEST, "groups 缺 family/familyKey/workNumber")
            continue
        category = group.get("numberingCategory")
        sources_categories = {numbering_category(str(page.get("sourceName", "")))
                              for page in group.get("pages", ())}
        if category not in ("image", "video") or sources_categories != {category}:
            fail(ERR_INVALID_REQUEST, "编号类别与源文件不符", family=family)
            continue
        numbering_key = (family_key, category)
        family_index = current_indexes[category].families.get(family_key)
        if group.get("existingWorkNumber"):
            if family_index is None or work_number not in family_index.work_numbers:
                fail(ERR_WORK_NUMBER_CONFLICT, "补图作品编号不存在", family=family, workNumber=work_number)
                continue
            expected_start = family_index.max_page(work_number) + 1
            pages = [page.get("pageNumber") for page in group.get("pages", ())]
            expected_pages = list(range(expected_start, expected_start + len(pages)))
            if pages != expected_pages:
                fail(ERR_WORK_NUMBER_CONFLICT, "补图页码必须从既有最大页 +1 起连续", family=family)
        else:
            current_max = max(family_index.work_numbers) if family_index and family_index.work_numbers else 0
            expected = max(current_max, last_assigned.get(numbering_key, 0)) + 1
            if work_number < expected:
                fail(ERR_WORK_NUMBER_CONFLICT, "作品编号与已占编号冲突", family=family, workNumber=work_number)
            elif work_number > expected:
                fail(ERR_WORK_NUMBER_GAP, "作品编号不得人为跳号", family=family, workNumber=work_number)
            else:
                last_assigned[numbering_key] = work_number
        if work_number > rule_set.max_work_number:
            fail(ERR_WORK_NUMBER_CONFLICT, "作品编号超过规则上限", family=family)
        group_pages = list(group.get("pages") or ())
        if group.get("kind") == "group":
            if not group_pages:
                fail(ERR_INVALID_REQUEST, "组图组缺少页码", family=family)
        elif group.get("kind") == "standalone":
            # 独立作品只有 1 个成员且 pageNumber 必须为空（页码仅属于组图页）。
            if len(group_pages) != 1 or group_pages[0].get("pageNumber") is not None:
                fail(ERR_INVALID_REQUEST, "独立作品应恰好一个文件且无组内页码", family=family)
        else:
            fail(ERR_INVALID_REQUEST, "group.kind 必须是 group 或 standalone", family=family)

    # 目标文件名必须能由 family/workNumber/page 重建（防直接篡改 targetName 绕过）
    page_bracket = rule_set.page_bracket
    for group in groups:
        family = group.get("family")
        style = group.get("workNumberStyle") or {}
        separator = style.get("separator", DEFAULT_SEPARATOR)
        width = style.get("width", 1)
        number_str = _format_work_number(int(group.get("workNumber", 0)), int(width))
        for page in group.get("pages", ()):
            source = page.get("sourceName", "")
            _stem, extension = split_extension(str(source))
            rebuilt = _build_target_name(
                family=str(family),
                separator=str(separator),
                number_str=number_str,
                extension=extension,
                page_number=page.get("pageNumber"),
                page_bracket=page_bracket,
            )
            if rebuilt != page.get("targetName"):
                fail(ERR_PLAN_FINGERPRINT, "组内目标文件名与作品编号/页码不一致", targetName=page.get("targetName"))

    computed_expected = sorted((current_set - set(sources)) | set(targets))
    if sorted(expected_readback.get("fileNames", ())) != computed_expected:
        fail(ERR_INVALID_REQUEST, "expectedReadback 与计划变更不一致")
    if not changes:
        warnings.append({"code": "no_op", "message": "计划没有实际改名动作（现状已符合）"})

    return {"ok": not errors, "errors": errors, "warnings": warnings}


# ---------------------------------------------------------------------------
# 执行编排所需纯逻辑（adapter 按此逐步调用正式移动 API）
# ---------------------------------------------------------------------------


def plan_steps(plan: Mapping[str, Any]) -> list[dict[str, Any]]:
    """把计划翻译成逐资产移动步骤；未确认/非 ready 计划直接拒绝执行。"""
    if not isinstance(plan, Mapping):
        raise NamingError(ERR_INVALID_REQUEST, "plan 必须是对象")
    if plan.get("status") != "ready":
        raise NamingError(ERR_NEEDS_CONFIRMATION, "计划尚未确认，apply 拒绝执行")
    if plan.get("permissions", {}).get("confirmationRequired"):
        raise NamingError(ERR_NEEDS_CONFIRMATION, "计划需要用户确认，apply 拒绝执行")
    target = plan.get("target", {})
    steps: list[dict[str, Any]] = []
    for index, change in enumerate(plan.get("changes", ()), start=1):
        asset_id = change.get("assetId")
        steps.append(
            {
                "index": index,
                "family": change.get("family"),
                "assetId": asset_id,
                "sourceName": change["sourceName"],
                "targetDir": change["targetDir"],
                "targetName": change["targetName"],
                "method": "POST",
                "pathTemplate": "/api/v1/assets/{assetId}/move",
                "resolution": "assetId" if asset_id else "byFileName",
                "body": {"targetDir": change["targetDir"], "newName": change["targetName"]},
                "library": target.get("library"),
                "instance": target.get("instance"),
                "onConflict": "abort",
                "expectedReadback": {
                    "directory": target.get("directory", ""),
                    "fileName": change["targetName"],
                },
            }
        )
    return steps


def readback_report(
    plan: Mapping[str, Any],
    actual_file_names: Sequence[str],
    *,
    committed_steps: Sequence[Mapping[str, Any]] = (),
) -> dict[str, Any]:
    """执行后回读核对：预期文件名集合 vs 目标目录实际文件名集合。

    缺失、多余、服务端同名自动改名（基名 (2).ext）任一出现即失败；
    committed_steps 原样保留，供失败报告记录已提交步骤。
    """
    if not isinstance(plan, Mapping):
        raise NamingError(ERR_INVALID_REQUEST, "plan 必须是对象")
    try:
        precondition_names = set(plan["precondition"]["fileNames"])
        changes = plan["changes"]
    except (KeyError, TypeError):
        raise NamingError(ERR_INVALID_REQUEST, "plan 缺少 precondition/changes") from None
    if isinstance(actual_file_names, (str, bytes)) or not isinstance(actual_file_names, Sequence):
        raise NamingError(ERR_INVALID_REQUEST, "actual_file_names 必须是字符串数组")
    actual = sorted({str(name) for name in actual_file_names})

    sources = {change["sourceName"] for change in changes}
    target_names = {change["targetName"] for change in changes}
    expected = sorted((precondition_names - sources) | target_names)
    expected_set = set(expected)
    actual_set = set(actual)

    missing = sorted(expected_set - actual_set)
    extra_candidates = set(actual_set - expected_set)
    auto_renamed: list[dict[str, str]] = []
    still_missing: list[str] = []
    for expected_name in missing:
        stem, extension = split_extension(expected_name)
        pattern = re.compile(r"^" + re.escape(stem) + r" \(\d+\)" + re.escape(extension) + r"$")
        matches = sorted(name for name in extra_candidates if pattern.match(name))
        if matches:
            auto_renamed.append({"expected": expected_name, "actual": matches[0]})
            extra_candidates.discard(matches[0])
        else:
            still_missing.append(expected_name)

    unexpected = sorted(extra_candidates)
    ok = not still_missing and not unexpected and not auto_renamed
    if auto_renamed:
        reason = "auto_renamed"
    elif still_missing:
        reason = "missing"
    elif unexpected:
        reason = "unexpected"
    else:
        reason = None
    return {
        "ok": ok,
        "status": "verified" if ok else "failed",
        "expected": expected,
        "actual": actual,
        "missing": still_missing,
        "unexpected": unexpected,
        "autoRenamed": auto_renamed,
        "reason": reason,
        "committedSteps": [dict(step) for step in committed_steps],
        "expectedFingerprint": file_names_fingerprint(expected),
        "actualFingerprint": file_names_fingerprint(actual),
    }


# ---------------------------------------------------------------------------
# 并发串行化：进程内锁 + 锁文件 O_EXCL，按 instance+library+family 作用域
# ---------------------------------------------------------------------------


def lock_scope(instance: str, library: Any, family: str = "") -> str:
    """锁作用域标识（不含凭据；用于日志/详情，不写库名到锁文件）。"""
    library_values = _coerce_library(library)
    instance_text = instance.strip() if isinstance(instance, str) else str(instance)
    return f"{instance_text}|{library_values['id']}|{normalize_family(family)}"


def _lock_file_name(scope: str) -> str:
    digest = hashlib.sha256(("lock-v1\n" + scope).encode("utf-8")).hexdigest()
    return digest[:32] + ".lock"


class SerializedLocks:
    """按 instance+library 串行化 apply 的并发原语（可再细化到 family）。

    用法一（库级，tools_media.apply 推荐）::

        with SerializedLocks(lock_dir, instance, library):
            # 取现状 -> validate_plan -> 逐步 move -> 回读
            ...

    用法二（显式加锁）::

        locks = SerializedLocks(lock_dir, instance, library)
        locks.acquire(timeout=5)
        try:
            ...
        finally:
            locks.release()

    用法三（只串行化该作品族；内部仍先占库级锁，保证与库级用法互斥）::

        with locks.family("守望先锋 朱诺"):
            ...

    - 进程内：每个作用域一个 threading.Lock，防同进程并发；
    - 跨进程：锁目录下 O_CREAT|O_EXCL 锁文件，第二个持有者等待/超时失败；
    - 锁文件只写 pid/时间/引擎版本，不写实例地址、库名、令牌；
    - 锁 mtime 超过 stale_after 视为失效但**不自动删除**，抛 lock_stale
      供人工核对清理（避免误删仍存活进程的锁）。
    """

    def __init__(
        self,
        lock_dir: str | os.PathLike[str],
        instance: str,
        library: Any,
        *,
        timeout: float = DEFAULT_LOCK_TIMEOUT_SECONDS,
        stale_after: float = DEFAULT_LOCK_STALE_SECONDS,
    ) -> None:
        self._dir = Path(lock_dir)
        self._instance = _require_text(instance, "instance")
        self._library = _coerce_library(library)
        if not isinstance(timeout, (int, float)) or isinstance(timeout, bool) or timeout <= 0:
            raise NamingError(ERR_INVALID_REQUEST, "timeout 必须是正数")
        if not isinstance(stale_after, (int, float)) or isinstance(stale_after, bool) or stale_after <= 0:
            raise NamingError(ERR_INVALID_REQUEST, "stale_after 必须是正数")
        self._timeout = float(timeout)
        self._stale_after = float(stale_after)
        self._guard = threading.Lock()
        self._in_process: dict[str, threading.Lock] = {}
        self._apply_scope = lock_scope(self._instance, self._library, "")
        self._held: tuple[threading.Lock, Path] | None = None

    def _in_process_lock(self, scope: str) -> threading.Lock:
        with self._guard:
            lock = self._in_process.get(scope)
            if lock is None:
                lock = threading.Lock()
                self._in_process[scope] = lock
            return lock

    def scope(self, family: str = "") -> str:
        """作用域标识；family 为空串即库级 apply 锁。"""
        return lock_scope(self._instance, self._library, family)

    def _ensure_idle(self) -> None:
        if self._held is not None:
            raise NamingError(ERR_INVALID_REQUEST, "锁实例已被持有，不能重复加锁")

    def _acquire_one(self, scope: str, deadline: float) -> tuple[threading.Lock, Path]:
        in_process = self._in_process_lock(scope)
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not in_process.acquire(timeout=remaining):
            raise NamingError(
                ERR_LOCK_TIMEOUT,
                "同进程内命名锁等待超时",
                details={"scope": scope},
            )
        try:
            lock_file = self._acquire_file_lock(scope, deadline)
        except BaseException:
            in_process.release()
            raise
        return in_process, lock_file

    @staticmethod
    def _release_one(in_process: threading.Lock, lock_file: Path) -> None:
        try:
            SerializedLocks._release_file_lock(lock_file)
        finally:
            in_process.release()

    def acquire(self, timeout: float | None = None) -> dict[str, str]:
        """获取库级 apply 锁；已持有则报 invalid_request，超时抛 lock_timeout。"""
        self._ensure_idle()
        effective = self._timeout if timeout is None else timeout
        if not isinstance(effective, (int, float)) or isinstance(effective, bool) or effective <= 0:
            raise NamingError(ERR_INVALID_REQUEST, "timeout 必须是正数")
        deadline = time.monotonic() + float(effective)
        held = self._acquire_one(self._apply_scope, deadline)
        self._held = held
        return {"scope": self._apply_scope, "lockFile": held[1].name, "engineVersion": ENGINE_VERSION}

    def release(self) -> None:
        held = self._held
        if held is None:
            return
        self._held = None
        self._release_one(*held)

    def __enter__(self) -> "SerializedLocks":
        self.acquire()
        return self

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> bool:
        self.release()
        return False

    @contextlib.contextmanager
    def family(self, family: str) -> Iterator[dict[str, str]]:
        """只串行化指定作品族（内部先占库级锁，与库级用法互斥）。"""
        self._ensure_idle()
        scopes = (self._apply_scope, self.scope(family))
        deadline = time.monotonic() + self._timeout
        held: list[tuple[threading.Lock, Path]] = []
        try:
            for scope in scopes:
                held.append(self._acquire_one(scope, deadline))
            yield {"scope": scopes[-1], "lockFile": held[-1][1].name, "engineVersion": ENGINE_VERSION}
        finally:
            for in_process, lock_file in reversed(held):
                self._release_one(in_process, lock_file)

    def _acquire_file_lock(self, scope: str, deadline: float) -> Path:
        try:
            self._dir.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise NamingError(
                ERR_LOCK_TIMEOUT,
                "无法准备锁目录",
                details={"error": type(exc).__name__},
            ) from None
        path = self._dir / _lock_file_name(scope)
        while True:
            try:
                fd = os.open(str(path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                try:
                    age = time.time() - path.stat().st_mtime
                except OSError:
                    age = 0.0
                if age > self._stale_after:
                    raise NamingError(
                        ERR_LOCK_STALE,
                        "命名锁已超过失效时限但未自动删除；请人工确认无进程持有后清理锁文件",
                        details={"lockFile": path.name, "ageSeconds": round(age, 3)},
                    )
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise NamingError(
                        ERR_LOCK_TIMEOUT,
                        "目标作品族的命名锁被其他批次持有，等待超时",
                        details={"lockFile": path.name},
                    )
                time.sleep(min(LOCK_POLL_INTERVAL_SECONDS, remaining))
            except OSError as exc:
                raise NamingError(
                    ERR_LOCK_TIMEOUT,
                    "无法创建命名锁文件",
                    details={"lockFile": path.name, "error": type(exc).__name__},
                ) from None
            else:
                try:
                    payload = json.dumps(
                        {"pid": os.getpid(), "createdAt": round(time.time(), 3), "engineVersion": ENGINE_VERSION}
                    )
                    os.write(fd, payload.encode("utf-8"))
                finally:
                    os.close(fd)
                return path

    @staticmethod
    def _release_file_lock(path: Path) -> None:
        try:
            path.unlink()
        except FileNotFoundError:
            return
        except OSError:
            # 释放失败不吞错误会掩盖残留锁；抛给调用方在退出/人工清理时可见。
            raise NamingError(ERR_LOCK_TIMEOUT, "释放命名锁文件失败", details={"lockFile": path.name}) from None


# ---------------------------------------------------------------------------
# 模块导出（registry 适配只依赖本节与上面的函数签名）
# ---------------------------------------------------------------------------

__all__ = [
    "ENGINE_VERSION",
    "PLAN_SCHEMA_VERSION",
    "TOOL_PREPARE_ID",
    "TOOL_APPLY_ID",
    "DEFAULT_MIN_CONFIDENCE",
    "DEFAULT_MAX_WORK_NUMBER",
    "DEFAULT_SEPARATOR",
    "DEFAULT_PAGE_BRACKET",
    "DEFAULT_LOCK_TIMEOUT_SECONDS",
    "DEFAULT_LOCK_STALE_SECONDS",
    "NamingError",
    "CandidateFile",
    "CandidateGroup",
    "NamingRules",
    "FileNameInfo",
    "FamilyIndex",
    "WorkIndex",
    "parse_file_name",
    "build_work_index",
    "normalize_family",
    "validate_family_name",
    "split_extension",
    "file_names_fingerprint",
    "prepare_plan",
    "plan_fingerprint",
    "verify_plan_fingerprint",
    "render_preview",
    "validate_plan",
    "plan_steps",
    "readback_report",
    "lock_scope",
    "SerializedLocks",
]
