"""本地文件发现工具：`files.storages` / `files.search`（只读、有界、不返回绝对路径）。

职责边界
--------
- 只做**未入库文件**的发现：存储枚举与授权根内的有界搜索；不移动、不改名、不写
  文件，也不修改任何业务数据（已入库资产/索引/作者/标签必须走 Go `/api/v1`）。
- 存储、授权根与 `fileRef` 的唯一实现仍在 `fileref.py` / `pathguard.py`；本模块
  **惰性导入**，缺失时返回结构化 `available=false` + `hint`，不伪造 storage 或引用。

与 `fileref` 的接口（2026-11-10 实际实现，缺失时按 unavailable 处理）
--------------------------------------------------------------------
- ``storage_info(env) -> [{storageId, writable}]``：公开存储列表（不含物理路径）。
- ``storages(env) -> {storageId: Path}``：进程内使用的存储根（绝不回显）；
  `QIMENG_AI_STORAGES` 中的根**本身即读授权**，本模块不再要求它同时出现在
  `QIMENG_AI_READ_ROOTS`/`QIMENG_AI_MEDIA_ROOTS`。
- ``make_ref(absolute_path, env=env) -> dict``：生成 ``fileRef`` 与精简元数据
  （storageId/relativePath/name/mediaType/size/modifiedAt）；引用层自行完成
  授权与规范化，本模块不重复调用 pathguard 读根。
- 兼容别名：``list_storages``/``load_storages``（列表）与
  ``encode_ref``/``file_ref``/``build_ref``（引用），便于旧发行物降级。
- 结果绝不回显存储根：只有 ``storageId``、``relativePath`` 与不透明 ``fileRef``；
  错误详情也不含本机绝对路径。

安全与有界性
------------
- 只读：不创建/覆盖/删除任何文件，不需要确认，`permission=read`。
- 扫描条目硬上限 ``MAX_SCAN_ENTRIES``（20000）；每页最多 ``MAX_LIMIT``（50）条；
  递归深度上限 8；跳过符号链接、Windows junction 与隐藏项（默认）。
- 存储根来自可信执行端的显式配置（`QIMENG_AI_STORAGES`），只做绝对路径、
  存在性/目录类型与根内相对路径约束；写入仍由后续原语/工具按
  `QIMENG_AI_WRITE_ROOTS` 强制。
- cursor 无状态：base64 只编码 ``{v, storageId, after}``（``after`` 是请求 storage
  内已扫描到的相对路径）；解析时重新校验存储根，拒绝绝对路径、``..``、跨存储或
  超长游标。嵌套 storage 时单条结果的 storageId/relativePath 以 fileRef 元数据为准。
"""
from __future__ import annotations

import base64
import json
import os
import stat as stat_module
import time
from datetime import datetime, timezone
from pathlib import Path

from . import errors

DEFAULT_LIMIT = 20
MAX_LIMIT = 50
MAX_EXTENSIONS = 10
MAX_SCAN_ENTRIES = 20000
DEFAULT_MAX_DEPTH = 4
MAX_DEPTH_LIMIT = 8
MAX_CURSOR_CHARS = 2048
CURSOR_VERSION = 1
MEDIA_TYPES = ("image", "video", "other", "any")

# 仅在 fileref/media_scan 不可用时作为分类兜底；正常路径复用 media_scan.media_type。
IMAGE_EXTENSIONS = frozenset({
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif", ".heic", ".heif",
    ".tif", ".tiff",
})
VIDEO_EXTENSIONS = frozenset({
    ".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".ts", ".flv", ".wmv",
    ".mpg", ".mpeg",
})

UNAVAILABLE_HINT = (
    "本地文件发现不可用：缺少 fileref 实现或未配置任何 storage。"
    "请由可信执行端用 QIMENG_AI_STORAGES 配置授权存储（storageId=绝对路径）后重试；"
    "不要伪造 storage/fileRef，也不要用脚本直接遍历磁盘绕过授权根。"
)
NO_STORAGE_HINT = (
    "尚未配置任何本地存储：由可信执行端用 QIMENG_AI_STORAGES 配置 storageId=绝对路径"
    "（只读还需在 QIMENG_AI_READ_ROOTS / QIMENG_AI_MEDIA_ROOTS 内）后重试；"
    "不要猜测路径或伪造 storage。"
)
STORAGE_LOOKUP_HINT = (
    "fileref 未提供 storages/storage_info 接口：本地文件发现尚未接通，"
    "请更新发行物或联系维护者；不要伪造结果。"
)
FILE_REF_HINT = (
    "fileref 未提供 make_ref(encode_ref/file_ref/build_ref) 接口："
    "无法生成可被研究/媒体工具解析的引用，请更新发行物；不要手工拼接 fileRef。"
)


# --------------------------------------------------------------------------- #
# fileref 适配（惰性导入；缺失时返回结构化不可用，不崩溃）
# --------------------------------------------------------------------------- #

def _fileref_module():
    try:
        from . import fileref  # noqa: F401 - 惰性导入，缺失时由调用方降级
    except ImportError:
        return None
    except Exception:  # noqa: BLE001 - 依赖模块自身错误不应让工具进程崩溃
        return None
    return fileref


def _env(env) -> dict:
    return dict(env or {})


def _storage_listing(env) -> tuple[bool, list[dict], str]:
    """公开存储列表（storageId/writable）；不返回物理路径。"""
    module = _fileref_module()
    if module is None:
        return False, [], UNAVAILABLE_HINT
    info = getattr(module, "storage_info", None)
    if callable(info):
        raw = info(_env(env))
        return True, _normalize_storage_list(raw), ""
    rows = _storage_roots(module, env)
    if rows is None:
        return False, [], STORAGE_LOOKUP_HINT
    writable_roots = ()
    try:
        from . import pathguard
        writable_roots = tuple(pathguard.write_roots(_env(env)))
    except Exception:  # noqa: BLE001 - 无法判定写授权时保守标为不可写
        writable_roots = ()
    listing = []
    for storage_id, root in rows.items():
        listing.append({
            "storageId": storage_id,
            "writable": any(_within(root, item) for item in writable_roots),
        })
    return True, listing, ""


def _storage_roots(module, env) -> dict[str, Path] | None:
    loader = getattr(module, "storages", None)
    if not callable(loader):
        return None
    raw = loader(_env(env))
    if not isinstance(raw, dict):
        return None
    rows: dict[str, Path] = {}
    for storage_id, root in raw.items():
        if not isinstance(storage_id, str) or not storage_id.strip():
            continue
        if isinstance(root, Path):
            rows[storage_id] = root
        elif isinstance(root, str) and root.strip():
            rows[storage_id] = Path(root.strip())
    return rows


def _normalize_storage_list(raw) -> list[dict]:
    rows = []
    if isinstance(raw, dict):
        raw = raw.get("storages")
    if not isinstance(raw, (list, tuple)):
        return rows
    for item in raw:
        if not isinstance(item, dict):
            continue
        storage_id = item.get("storageId") or item.get("id")
        if not isinstance(storage_id, str) or not storage_id.strip():
            continue
        rows.append({"storageId": storage_id.strip(),
                     "writable": item.get("writable") is True})
    return rows


def _file_ref_builder():
    """返回 make_ref 适配函数：优先 make_ref(path, env=)，其次旧式 (id, rel)。"""
    module = _fileref_module()
    if module is None:
        return None
    make_ref = getattr(module, "make_ref", None)
    if callable(make_ref):
        def build(path: Path, storage_id: str, relative_path: str, env) -> dict:
            result = make_ref(str(path), env=_env(env))
            if not isinstance(result, dict):
                raise errors.file_ref_unavailable("make_ref 未返回元数据")
            return result
        return build
    for name in ("encode_ref", "file_ref", "build_ref"):
        function = getattr(module, name, None)
        if not callable(function):
            continue
        def build(path: Path, storage_id: str, relative_path: str, env,
                  _function=function) -> dict:
            reference = _function(storage_id, relative_path)
            if not isinstance(reference, str) or not reference:
                raise errors.file_ref_unavailable("fileref 返回了空引用")
            return {"fileRef": reference}
        return build
    return None


# --------------------------------------------------------------------------- #
# 存储根与安全边界
# --------------------------------------------------------------------------- #

def _within(path: Path, root: Path) -> bool:
    try:
        Path(path).relative_to(Path(root))
        return True
    except ValueError:
        return False


def _storage_root(row_root, storage_id: str, env) -> Path:
    """校验存储根是可解析的绝对目录。

    存储根由可信执行端在 `QIMENG_AI_STORAGES` 中显式配置，**本身即读授权**
    （2026-11-10 接口更新）：这里不再要求根同时出现在
    `QIMENG_AI_READ_ROOTS`/`QIMENG_AI_MEDIA_ROOTS`，也不调用 pathguard 读根；
    写入仍由后续原语/工具按 `QIMENG_AI_WRITE_ROOTS` 强制。相对路径、根内约束、
    存在性/类型与符号链接跳过由本模块与扫描器保证。
    """
    if isinstance(row_root, Path):
        candidate = row_root
    elif isinstance(row_root, str) and row_root.strip():
        candidate = Path(row_root.strip())
    else:
        raise errors.file_ref_unavailable(
            "存储 " + storage_id + " 未提供可解析的根目录",
            details={"storageId": storage_id})
    if not candidate.is_absolute():
        raise errors.path_not_authorized("存储根", env_names=("QIMENG_AI_STORAGES",),
                                         details={"storageId": storage_id})
    resolved = candidate.resolve(strict=False)
    if not resolved.is_dir():
        raise errors.file_ref_unavailable(
            "存储 " + storage_id + " 的根不存在或不是目录",
            details={"storageId": storage_id})
    return resolved


# --------------------------------------------------------------------------- #
# 枚举与过滤
# --------------------------------------------------------------------------- #

def _is_hidden(entry) -> bool:
    if entry.name.startswith("."):
        return True
    if os.name != "nt":
        return False
    try:
        attributes = entry.stat(follow_symlinks=False).st_file_attributes
    except (OSError, AttributeError):
        return False
    return bool(attributes & getattr(stat_module, "FILE_ATTRIBUTE_HIDDEN", 0))


def _is_link(entry, path: Path) -> bool:
    try:
        if entry.is_symlink():
            return True
    except OSError:
        return True
    is_junction = getattr(os.path, "isjunction", None)
    if is_junction is not None:
        try:
            if is_junction(path):
                return True
        except OSError:
            return True
    return False


def _scan(root: Path, *, recursive: bool, max_depth: int,
          include_hidden: bool) -> tuple[list[dict], dict]:
    """有界遍历：返回 (文件条目, scanned 统计)。不跟随链接、不返回目录。"""
    files: list[dict] = []
    stats = {"entries": 0, "files": 0, "directories": 0,
             "skippedHidden": 0, "skippedSymlinks": 0, "skippedDepth": 0,
             "scanLimitReached": False}
    stack: list[tuple[Path, int]] = [(root, 0)]
    while stack:
        directory, depth = stack.pop()
        try:
            with os.scandir(directory) as iterator:
                entries = sorted(iterator, key=lambda item: item.name)
        except OSError:
            continue
        for entry in entries:
            if stats["entries"] >= MAX_SCAN_ENTRIES:
                stats["scanLimitReached"] = True
                return files, stats
            stats["entries"] += 1
            path = Path(entry.path)
            try:
                if _is_link(entry, path):
                    stats["skippedSymlinks"] += 1
                    continue
                is_directory = entry.is_dir(follow_symlinks=False)
                is_file = entry.is_file(follow_symlinks=False)
            except OSError:
                continue
            if not include_hidden and _is_hidden(entry):
                stats["skippedHidden"] += 1
                continue
            if is_directory:
                stats["directories"] += 1
                if recursive and depth < max_depth:
                    stack.append((path, depth + 1))
                elif recursive:
                    stats["skippedDepth"] += 1
                continue
            if not is_file:
                continue
            try:
                file_stat = entry.stat(follow_symlinks=False)
            except OSError:
                continue
            stats["files"] += 1
            files.append({
                "relativePath": path.relative_to(root).as_posix(),
                "name": entry.name,
                "size": int(file_stat.st_size),
                "mtime": float(file_stat.st_mtime),
            })
    return files, stats


def _normalize_extensions(value) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise errors.invalid_input("extensions 必须是字符串数组")
    if len(value) > MAX_EXTENSIONS:
        raise errors.invalid_input(f"extensions 最多 {MAX_EXTENSIONS} 项")
    normalized: list[str] = []
    for item in value:
        if not isinstance(item, str) or not item.strip():
            raise errors.invalid_input("extensions[] 必须是非空字符串")
        text = item.strip().lower()
        if not text.startswith("."):
            text = "." + text
        if len(text) < 2 or len(text) > 11 or not text[1:].isalnum():
            raise errors.invalid_input(
                "extensions[] 只允许字母/数字扩展名（可带前导点）",
                details={"value": item})
        if text not in normalized:
            normalized.append(text)
    return normalized


def _parse_time(value, name: str) -> float | None:
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip():
        raise errors.invalid_input(name + " 必须是 ISO-8601 时间字符串")
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        raise errors.invalid_input(
            name + " 必须是 ISO-8601 时间字符串（如 2026-11-10T00:00:00Z）") from None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.timestamp()


def _media_type(name: str) -> str:
    try:
        from .media_scan import media_type  # 与 fileref 共用同一分类实现
        return media_type(name)
    except Exception:  # noqa: BLE001 - 分类模块缺失时使用本地兜底集合
        suffix = Path(name).suffix.lower()
        if suffix in IMAGE_EXTENSIONS:
            return "image"
        if suffix in VIDEO_EXTENSIONS:
            return "video"
        return "other"


def _iso_time(value: float) -> str:
    return datetime.fromtimestamp(value, tz=timezone.utc).isoformat().replace("+00:00", "Z")


# --------------------------------------------------------------------------- #
# cursor（无状态、不透明）
# --------------------------------------------------------------------------- #

def _invalid_cursor(detail: str) -> errors.ToolError:
    return errors.ToolError(
        "INVALID_INPUT", "cursor 非法：" + detail,
        category="usage", exit_code=2,
        recovery="cursor 只能原样使用上一次 files.search 返回的 nextCursor；不要手工构造或跨存储复用。",
    )


def _encode_cursor(storage_id: str, after: str) -> str:
    raw = json.dumps({"v": CURSOR_VERSION, "storageId": storage_id, "after": after},
                     ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _valid_relative(value: str) -> bool:
    if not value or len(value) > 1024:
        return False
    if any(ord(ch) < 32 for ch in value):
        return False
    if value.startswith(("/", "\\")) or ":" in value or "\\" in value:
        return False
    parts = value.split("/")
    return all(part not in ("", ".", "..") for part in parts)


def _decode_cursor(value) -> dict:
    if not isinstance(value, str) or not value or len(value) > MAX_CURSOR_CHARS:
        raise _invalid_cursor("长度或类型不合法")
    if any(ch not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_="
           for ch in value):
        raise _invalid_cursor("包含非法字符")
    padded = value + "=" * (-len(value) % 4)
    try:
        raw = base64.urlsafe_b64decode(padded.encode("ascii"))
        payload = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeError, TypeError):
        raise _invalid_cursor("无法解码") from None
    if not isinstance(payload, dict) or set(payload) != {"v", "storageId", "after"}:
        raise _invalid_cursor("结构不合法")
    if payload.get("v") != CURSOR_VERSION:
        raise _invalid_cursor("版本不匹配")
    storage_id = payload.get("storageId")
    after = payload.get("after")
    if not isinstance(storage_id, str) or not storage_id:
        raise _invalid_cursor("缺少 storageId")
    if not isinstance(after, str) or not _valid_relative(after):
        raise _invalid_cursor("after 必须是存储内相对路径")
    return {"storageId": storage_id, "after": after}


# --------------------------------------------------------------------------- #
# 工具实现
# --------------------------------------------------------------------------- #

def files_storages(ctx, payload: dict) -> dict:
    """列出已配置存储（storageId/writable）；无配置返回空列表 + 设置提示。"""
    env = getattr(ctx, "env", None) or {}
    available, rows, hint = _storage_listing(env)
    if not available:
        return {"storages": [], "configured": False, "available": False, "hint": hint}
    storages = [{"storageId": row["storageId"], "writable": row["writable"]}
                for row in rows]
    configured = bool(storages)
    result = {"storages": storages, "configured": configured, "available": True}
    if not configured:
        result["hint"] = NO_STORAGE_HINT
    return result


def files_search(ctx, payload: dict) -> dict:
    """在授权存储内有界搜索文件；返回不透明 fileRef 与可分页结果。"""
    env = getattr(ctx, "env", None) or {}
    limit = payload.get("limit")
    if limit is None:
        limit = DEFAULT_LIMIT
    if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= MAX_LIMIT:
        raise errors.invalid_input(f"limit 必须在 1..{MAX_LIMIT}")
    max_depth = payload.get("maxDepth")
    if max_depth is None:
        max_depth = DEFAULT_MAX_DEPTH
    if isinstance(max_depth, bool) or not isinstance(max_depth, int) or \
            not 0 <= max_depth <= MAX_DEPTH_LIMIT:
        raise errors.invalid_input(f"maxDepth 必须在 0..{MAX_DEPTH_LIMIT}")
    recursive = payload.get("recursive")
    recursive = True if recursive is None else recursive
    if not isinstance(recursive, bool):
        raise errors.invalid_input("recursive 必须是布尔值")
    include_hidden = payload.get("includeHidden")
    include_hidden = False if include_hidden is None else include_hidden
    if not isinstance(include_hidden, bool):
        raise errors.invalid_input("includeHidden 必须是布尔值")
    media_type = payload.get("mediaType") or "any"
    if media_type not in MEDIA_TYPES:
        raise errors.invalid_input("mediaType 必须是 " + "/".join(MEDIA_TYPES))
    extensions = _normalize_extensions(payload.get("extensions"))
    query = payload.get("query")
    if query is not None and not isinstance(query, str):
        raise errors.invalid_input("query 必须是字符串")
    query_text = (query or "").strip().casefold()
    modified_after = _parse_time(payload.get("modifiedAfter"), "modifiedAfter")
    modified_before = _parse_time(payload.get("modifiedBefore"), "modifiedBefore")
    if modified_after is not None and modified_before is not None and \
            modified_after > modified_before:
        raise errors.invalid_input("modifiedAfter 不能晚于 modifiedBefore")

    limits = {"limit": limit, "maxLimit": MAX_LIMIT, "maxScanEntries": MAX_SCAN_ENTRIES,
              "maxDepth": MAX_DEPTH_LIMIT, "maxExtensions": MAX_EXTENSIONS}
    unavailable = {
        "files": [], "count": 0, "truncated": False, "nextCursor": None,
        "limits": limits, "available": False, "hint": UNAVAILABLE_HINT,
        "scanned": {"entries": 0, "files": 0, "directories": 0,
                    "skippedHidden": 0, "skippedSymlinks": 0, "skippedDepth": 0,
                    "scanLimitReached": False},
    }

    module = _fileref_module()
    if module is None:
        return unavailable
    roots = _storage_roots(module, env)
    if roots is None:
        unavailable["hint"] = STORAGE_LOOKUP_HINT
        return unavailable
    if not roots:
        unavailable["hint"] = NO_STORAGE_HINT
        return unavailable

    cursor_payload = None
    if payload.get("cursor") is not None:
        cursor_payload = _decode_cursor(payload.get("cursor"))

    requested = payload.get("storageId")
    if requested is not None and not isinstance(requested, str):
        raise errors.invalid_input("storageId 必须是字符串")
    storage_id = (requested or "").strip() or (cursor_payload or {}).get("storageId", "")
    if cursor_payload is not None and storage_id != cursor_payload["storageId"]:
        raise _invalid_cursor("storageId 与 cursor 不一致")

    if not storage_id:
        if len(roots) == 1:
            storage_id = next(iter(roots))
        else:
            raise errors.invalid_input(
                "存在多个存储时必须指定 storageId",
                details={"availableStorageIds": sorted(roots)},
            )
    if storage_id not in roots:
        raise errors.invalid_input(
            "未知 storageId：" + storage_id,
            details={"availableStorageIds": sorted(roots)},
        )
    root = _storage_root(roots[storage_id], storage_id, env)

    started = time.monotonic()
    scanned_files, stats = _scan(root, recursive=recursive, max_depth=max_depth,
                                 include_hidden=include_hidden)
    candidates = []
    for item in scanned_files:
        name = item["name"]
        if query_text and query_text not in item["relativePath"].casefold():
            continue
        if extensions and not any(name.lower().endswith(ext) for ext in extensions):
            continue
        kind = _media_type(name)
        if media_type != "any" and kind != media_type:
            continue
        if modified_after is not None and item["mtime"] < modified_after:
            continue
        if modified_before is not None and item["mtime"] > modified_before:
            continue
        candidates.append({**item, "mediaType": kind})
    candidates.sort(key=lambda item: item["relativePath"])
    matched_before_cursor = len(candidates)
    if cursor_payload is not None:
        after = cursor_payload["after"]
        candidates = [item for item in candidates if item["relativePath"] > after]
    page = candidates[:limit]
    has_more = len(candidates) > len(page)
    scan_limit = bool(stats.get("scanLimitReached"))
    # 扫描达到硬上限时结果也可能不完整：truncated 必须为 true，不能把“没找到”说成搜全。
    truncated = has_more or scan_limit

    build_ref = _file_ref_builder()
    if page and build_ref is None:
        unavailable["hint"] = FILE_REF_HINT
        return unavailable
    files = []
    for item in page:
        try:
            metadata = build_ref(root / item["relativePath"], storage_id,
                                 item["relativePath"], env)
        except errors.ToolError:
            raise
        except Exception:  # noqa: BLE001 - 引用层异常按不可用处理，不伪造引用
            raise errors.file_ref_unavailable(
                "无法为搜索结果生成 fileRef",
                details={"storageId": storage_id}) from None
        file_ref = metadata.get("fileRef")
        if not isinstance(file_ref, str) or not file_ref:
            raise errors.file_ref_unavailable(
                "fileref 返回了空引用", details={"storageId": storage_id})
        # 嵌套 storage 时 make_ref 会选最具体的 storage：以元数据为准保持
        # {fileRef, storageId, relativePath} 自洽；cursor 仍按请求 storage 编码。
        ref_storage = metadata.get("storageId")
        ref_relative = metadata.get("relativePath")
        files.append({
            "fileRef": file_ref,
            "name": str(metadata.get("name") or item["name"]),
            "storageId": ref_storage if isinstance(ref_storage, str) and ref_storage
            else storage_id,
            "relativePath": ref_relative if isinstance(ref_relative, str) and ref_relative
            else item["relativePath"],
            "mediaType": str(metadata.get("mediaType") or item["mediaType"]),
            "size": int(metadata.get("size", item["size"])),
            "modifiedAt": str(metadata.get("modifiedAt") or _iso_time(item["mtime"])),
        })
    # 游标只使用请求 storage 内的扫描相对路径（与输出行可能被覆盖的字段无关）。
    next_cursor = _encode_cursor(storage_id, page[-1]["relativePath"]) \
        if has_more and page else None
    stats = dict(stats)
    stats["matched"] = len(candidates)
    stats["matchedBeforeCursor"] = matched_before_cursor
    stats["elapsedMs"] = int((time.monotonic() - started) * 1000)
    result = {
        "files": files,
        "count": len(files),
        "truncated": truncated,
        "nextCursor": next_cursor,
        "limits": limits,
        "scanned": stats,
        "available": True,
        "storageId": storage_id,
    }
    if scan_limit:
        result["hint"] = (
            f"扫描达到 {MAX_SCAN_ENTRIES} 条硬上限：结果可能不完整，"
            "请缩小 query/extensions/storage 范围或提高过滤条件后重试。")
    return result
