"""媒体批次内容扫描（只读）：根目录对齐、最大文件去重、序号延顺与拼图目检计划。

设计口径（M71，rules.json mediaOrganize）：
- **按内容对齐，不按文件名**：md5 是精确内容证据；图片用 dHash64（ffmpeg 可选），
  视频用时长+分辨率+抽帧 dHash64。文件名只作来源/批次/序列线索。
- **重复内容保留最大文件**；SFW/NSFW、v1/v2、P1/P2、4K/2K、voice/unblur 等变体
  不得因相似而互删；只有 md5 完全一致才允许跨变体列为候选。
- **永不自动删除**：只输出 review_remove 候选并要求人工确认；删除只走回收站或
  独立管理操作。
- **只读**：本模块不写文件、不移动、不删除、不请求业务写入 API；扫描根必须由
  pathguard 显式授权（QIMENG_AI_MEDIA_ROOTS）。

命名算法仍唯一实现在 media_naming.py；本模块只给序号建议（最大页+1），不复制
作品编号分配逻辑。
"""
from __future__ import annotations

import os
import re
import shutil
from pathlib import Path
from typing import Any, Iterable

from . import errors, media_primitives, pathguard, plans

SCAN_ENGINE_VERSION = "1.0.0"
SCAN_PLAN_VERSION = "content-scan/1"
TOOL_ID = "media.organize.prepare"

DEFAULT_MAX_FILES = 500
HARD_MAX_FILES = 2000
DEFAULT_MAX_DEPTH = 4
HARD_MAX_DEPTH = 8
DEFAULT_PERCEPTUAL_LIMIT = 100
HARD_PERCEPTUAL_LIMIT = 500
DEFAULT_MAX_FILE_BYTES = 8 << 30
DEFAULT_MAX_TOTAL_HASH_BYTES = 32 << 30

IMAGE_EXTENSIONS = {
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".tif", ".tiff", ".avif", ".heic",
}
VIDEO_EXTENSIONS = {
    ".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v", ".wmv", ".flv", ".mpg", ".mpeg", ".ts",
}
VARIANT_TOKENS = {
    "sfw", "nsfw", "v1", "v2", "v3", "p1", "p2", "p3", "4k", "2k", "1080p", "720p",
    "voice", "unblur", "uncensored", "censored", "raw", "clean", "alt", "variant",
    "diff", "difference", "sub", "dub", "eng", "jpn", "zh", "cn",
}
_VARIANT_PATTERNS = (
    re.compile(r"^v\d{1,3}$", re.I),
    re.compile(r"^p\d{1,3}$", re.I),
    re.compile(r"^\d{3,4}[pk]$", re.I),
    re.compile(r"^\d{3,4}x\d{3,4}$", re.I),
)
_PAGE_RE = re.compile(
    r"^(?P<base>.*?)(?:\s*[\(\[]\s*(?P<p1>\d{1,4})\s*[\)\]]"
    r"|\s+(?:page|p|第)\s*(?P<p2>\d{1,4}))\s*$",
    re.I,
)
_GARBAGE_HINTS = (
    "download", "unnamed", "untitled", "copy", "screenshot", "微信图片", "image",
)


def _canonical(value: Any) -> str:
    return plans.canonical_json(value)


def _plan_digest(plan: dict) -> str:
    payload = {key: value for key, value in plan.items()
               if key not in ("planFingerprint", "fingerprint")}
    return plans.digest(payload)


def _text(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise errors.invalid_input(f"{field_name} 必须是非空字符串")
    if any(ord(ch) < 32 for ch in value):
        raise errors.invalid_input(f"{field_name} 含控制字符")
    return value.strip()


def _bounded_int(value: Any, field_name: str, minimum: int, maximum: int,
                 default: int) -> int:
    if value is None:
        return default
    if isinstance(value, bool) or not isinstance(value, int):
        raise errors.invalid_input(f"{field_name} 必须是整数")
    if not minimum <= value <= maximum:
        raise errors.invalid_input(f"{field_name} 必须在 {minimum}..{maximum}")
    return value


def split_extension(file_name: str) -> tuple[str, str]:
    stem, dot, extension = file_name.rpartition(".")
    if not dot or not stem:
        return file_name, ""
    return stem, "." + extension.lower()


def media_type(file_name: str) -> str:
    extension = split_extension(file_name)[1]
    if extension in IMAGE_EXTENSIONS:
        return "image"
    if extension in VIDEO_EXTENSIONS:
        return "video"
    return "other"


def page_info(file_name: str) -> tuple[str, int | None]:
    """返回 (base, page)；只识别明确的括号页码或 page/p/第 标记。

    仅括号序号（``基名 (2).ext``）在 media_naming 中属于旧序号语义，但用于
    “序号延顺”时它仍是当前最大页的证据，因此这里识别为 page。
    """
    stem = split_extension(file_name)[0]
    match = _PAGE_RE.match(stem)
    if not match:
        return stem, None
    base = (match.group("base") or "").rstrip(" _-")
    page = match.group("p1") or match.group("p2")
    try:
        number = int(page) if page is not None else None
    except ValueError:
        number = None
    return base or stem, number


def base_key(file_name: str) -> str:
    base, _page = page_info(file_name)
    return " ".join(base.casefold().split())


def variant_signature(file_name: str) -> tuple[str, ...]:
    """提取变体标记（SFW/NSFW、v1/v2、P1/P2、4K/2K、voice/unblur 等）。"""
    stem = split_extension(file_name)[0]
    tokens = re.split(r"[^0-9A-Za-z\u4e00-\u9fff]+", stem.casefold())
    found = set()
    for token in tokens:
        if not token:
            continue
        if token in VARIANT_TOKENS or any(pattern.match(token) for pattern in _VARIANT_PATTERNS):
            found.add(token)
    return tuple(sorted(found))


def hamming(left: str, right: str) -> int:
    """两个 64 位十六进制感知哈希的汉明距离。"""
    try:
        return (int(left, 16) ^ int(right, 16)).bit_count()
    except (TypeError, ValueError):
        return 64


def choose_keeper(entries: Iterable[dict]) -> dict:
    """重复内容保留最大文件；同大小优先规范命名与更短路径。"""
    candidates = list(entries)
    if not candidates:
        raise errors.invalid_input("重复组不能为空")

    def score(item: dict) -> tuple:
        name = str(item.get("fileName") or "")
        lower = name.casefold()
        garbage = sum(1 for hint in _GARBAGE_HINTS if hint in lower)
        bracket_only = bool(re.search(r"\(\d+\)", split_extension(name)[0])) and \
            page_info(name)[1] is None
        return (
            -int(item.get("sizeBytes") or 0),
            garbage,
            int(bracket_only),
            len(str(item.get("relPath") or "")),
            lower,
        )

    return min(candidates, key=score)


def _same_strong(left: dict, right: dict) -> bool:
    return bool(left.get("md5")) and left.get("md5") == right.get("md5")


def _perceptual_similar(left: dict, right: dict) -> tuple[bool, int]:
    """同变体、同尺寸/时长的近似判定；返回 (similar, maxDistance)。"""
    if left.get("mediaType") != right.get("mediaType"):
        return False, 64
    if tuple(left.get("variantSignature") or ()) != tuple(right.get("variantSignature") or ()):
        return False, 64
    media = left.get("mediaType")
    left_perceptual = left.get("perceptual") or {}
    right_perceptual = right.get("perceptual") or {}
    left_hashes = left_perceptual.get("frames") or []
    right_hashes = right_perceptual.get("frames") or []
    if not left_hashes or not right_hashes:
        return False, 64
    left_dim = left.get("dimensions") or {}
    right_dim = right.get("dimensions") or {}
    if media == "image":
        if left_dim.get("width") != right_dim.get("width") or \
                left_dim.get("height") != right_dim.get("height"):
            return False, 64
        distance = hamming(left_hashes[0], right_hashes[0])
        return distance <= 4, distance
    if media == "video":
        if left_dim.get("width") != right_dim.get("width") or \
                left_dim.get("height") != right_dim.get("height"):
            return False, 64
        left_duration = left.get("durationMs")
        right_duration = right.get("durationMs")
        if not isinstance(left_duration, int) or not isinstance(right_duration, int):
            return False, 64
        if abs(left_duration - right_duration) > 1000:
            return False, 64
        pairs = list(zip(left_hashes, right_hashes))
        if not pairs:
            return False, 64
        distance = max(hamming(a, b) for a, b in pairs)
        return distance <= 6, distance
    return False, 64


# ---------------------------------------------------------------------------
# 只读扫描与内容指纹
# ---------------------------------------------------------------------------


def _tool_path(env, name: str) -> str:
    override = (env or {}).get("QIMENG_" + name.upper())
    if isinstance(override, str) and override.strip():
        return override.strip()
    return shutil.which(name) or ""


def _probe_media(ffprobe: str, path: Path) -> dict:
    return media_primitives.probe_media(path, ffprobe)


def _perceptual(ffmpeg: str, ffprobe: str, path: Path, kind: str, *, env=None) -> dict:
    # 唯一实现在 media_primitives；按 (path,size,mtime,参数) 缓存，跨任务复用。
    record = media_primitives.perceptual_record(path, kind, env=env, cache=True)
    if not ffmpeg and record.get("status") == "ok":
        record = dict(record)
        record["status"] = "unavailable"
        record["reason"] = "ffmpeg_not_found"
    return record


def _iter_files(root: Path, *, recursive: bool, max_depth: int,
                include_hidden: bool) -> tuple[list[Path], int, int]:
    files: list[Path] = []
    skipped_links = 0
    unreadable_dirs = 0

    def visit(directory: Path, depth: int) -> None:
        nonlocal skipped_links, unreadable_dirs
        try:
            entries = sorted(os.scandir(directory), key=lambda item: item.name.casefold())
        except OSError:
            unreadable_dirs += 1
            return
        for entry in entries:
            if not include_hidden and entry.name.startswith("."):
                continue
            try:
                if entry.is_symlink() or (
                        hasattr(entry, "is_junction") and entry.is_junction()):
                    skipped_links += 1
                    continue
                if entry.is_dir(follow_symlinks=False):
                    if recursive and depth < max_depth:
                        visit(Path(entry.path), depth + 1)
                    continue
                if entry.is_file(follow_symlinks=False):
                    files.append(Path(entry.path))
            except OSError:
                continue

    visit(root, 0)
    return files, skipped_links, unreadable_dirs


def scan_directory(root_value: str, *, env=None, recursive: bool = True,
                   max_files: int = DEFAULT_MAX_FILES,
                   max_depth: int = DEFAULT_MAX_DEPTH,
                   perceptual_limit: int = DEFAULT_PERCEPTUAL_LIMIT,
                   include_hidden: bool = False,
                   max_file_bytes: int = DEFAULT_MAX_FILE_BYTES,
                   max_total_hash_bytes: int = DEFAULT_MAX_TOTAL_HASH_BYTES,
                   dedupe_mode: str = "perceptual") -> dict:
    """只读扫描一个显式授权的本地根，返回文件清单与内容指纹。"""
    root = pathguard.resolve_media_path(root_value, env=env)
    max_files = _bounded_int(max_files, "maxFiles", 1, HARD_MAX_FILES, DEFAULT_MAX_FILES)
    max_depth = _bounded_int(max_depth, "maxDepth", 0, HARD_MAX_DEPTH, DEFAULT_MAX_DEPTH)
    perceptual_limit = _bounded_int(perceptual_limit, "perceptualLimit", 0,
                                    HARD_PERCEPTUAL_LIMIT, DEFAULT_PERCEPTUAL_LIMIT)
    max_file_bytes = _bounded_int(max_file_bytes, "maxFileBytes", 1,
                                  DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_FILE_BYTES)
    max_total_hash_bytes = _bounded_int(max_total_hash_bytes, "maxTotalHashBytes", 1,
                                        DEFAULT_MAX_TOTAL_HASH_BYTES,
                                        DEFAULT_MAX_TOTAL_HASH_BYTES)
    if dedupe_mode not in ("exact", "perceptual", "off"):
        raise errors.invalid_input("dedupeMode 只能是 exact/perceptual/off")
    paths, skipped_links, unreadable_dirs = _iter_files(
        root, recursive=recursive, max_depth=max_depth, include_hidden=include_hidden)
    if len(paths) > max_files:
        raise errors.invalid_input(
            f"扫描文件数 {len(paths)} 超过 maxFiles={max_files}；请缩小根目录或提高上限（硬上限 {HARD_MAX_FILES}）")
    ffmpeg = _tool_path(env, "ffmpeg")
    ffprobe = _tool_path(env, "ffprobe")
    files: list[dict] = []
    total_bytes = 0
    total_hash_bytes = 0
    perceptual_used = 0
    perceptual_status = "ok" if ffmpeg else "unavailable"
    for path in paths:
        try:
            stat = path.stat(follow_symlinks=False)
            size = int(stat.st_size)
        except OSError:
            continue
        rel_path = path.relative_to(root).as_posix()
        kind = media_type(path.name)
        record: dict = {
            "relPath": rel_path,
            "fileName": path.name,
            "sizeBytes": size,
            "mediaType": kind,
            "eligibleForMediaPlan": kind in ("image", "video"),
            "md5": None,
            "hashStatus": "ok",
            "dimensions": None,
            "durationMs": None,
            "perceptual": None,
            "perceptualStatus": "not_applicable" if kind == "other" else "skipped",
            "variantSignature": list(variant_signature(path.name)),
            "baseKey": base_key(path.name),
            "pageNumber": page_info(path.name)[1],
        }
        total_bytes += size
        if size > max_file_bytes:
            record["hashStatus"] = "skipped_size"
        elif total_hash_bytes + size > max_total_hash_bytes:
            record["hashStatus"] = "skipped_budget"
        else:
            try:
                digest, hashed_bytes = pathguard.md5_file(path, max_bytes=max_file_bytes)
            except errors.ToolError:
                record["hashStatus"] = "error"
            else:
                total_hash_bytes += hashed_bytes
                record["md5"] = digest
                record["hashStatus"] = "ok" if digest else "skipped_size"
        if kind in ("image", "video") and perceptual_limit > 0 and \
                perceptual_used < perceptual_limit:
            record["perceptual"] = _perceptual(ffmpeg, ffprobe, path, kind, env=env)
            record["perceptualStatus"] = record["perceptual"].get("status", "error")
            record["dimensions"] = record["perceptual"].get("dimensions")
            record["durationMs"] = record["perceptual"].get("durationMs")
            if record["perceptualStatus"] == "ok":
                perceptual_used += 1
            elif record["perceptualStatus"] == "unavailable":
                perceptual_status = "unavailable"
        files.append(record)
    return {
        "root": root,
        "files": files,
        "summary": {
            "fileCount": len(files),
            "totalBytes": total_bytes,
            "imageCount": sum(1 for item in files if item["mediaType"] == "image"),
            "videoCount": sum(1 for item in files if item["mediaType"] == "video"),
            "otherCount": sum(1 for item in files if item["mediaType"] == "other"),
            "hashedBytes": total_hash_bytes,
            "perceptualFiles": perceptual_used,
            "perceptualStatus": perceptual_status,
            "skippedSymlinks": skipped_links,
            "unreadableDirs": unreadable_dirs,
        },
    }


# ---------------------------------------------------------------------------
# 去重、序列与对齐
# ---------------------------------------------------------------------------


def _duplicate_groups(files: list[dict], dedupe_mode: str) -> list[dict]:
    if dedupe_mode == "off":
        return []
    # DB/TXT/其他非媒体文件不进入去重候选：它们属于业务真相，不能由媒体工具建议删除。
    files = [item for item in files if item.get("mediaType") in ("image", "video")]
    groups: list[dict] = []
    by_md5: dict[str, list[dict]] = {}
    for item in files:
        if item.get("md5"):
            by_md5.setdefault(item["md5"], []).append(item)
    for digest, members in sorted(by_md5.items()):
        if len(members) < 2:
            continue
        keeper = choose_keeper(members)
        remove = [item for item in members if item is not keeper]
        signatures = {tuple(item.get("variantSignature") or ()) for item in members}
        groups.append({
            "groupId": "md5-" + digest[:12],
            "reason": "exact-md5",
            "confidence": 1.0,
            "variantMismatch": len(signatures) > 1,
            "keep": _file_ref(keeper),
            "removeCandidates": [_file_ref(item) for item in remove],
            "bytesReclaimable": sum(int(item.get("sizeBytes") or 0) for item in remove),
            "requiresConfirmation": True,
            "action": "review_remove",
            "safety": "no-auto-delete",
        })
    if dedupe_mode != "perceptual":
        return groups
    eligible = [item for item in files
                if item.get("perceptualStatus") == "ok" and item.get("mediaType") in ("image", "video")]
    parent = {item["relPath"]: item["relPath"] for item in eligible}
    distances: dict[tuple[str, str], int] = {}

    def find(name: str) -> str:
        while parent[name] != name:
            parent[name] = parent[parent[name]]
            name = parent[name]
        return name

    def union(left: str, right: str) -> None:
        left_root, right_root = find(left), find(right)
        if left_root != right_root:
            parent[right_root] = left_root

    for index, left in enumerate(eligible):
        for right in eligible[index + 1:]:
            if left.get("md5") and left.get("md5") == right.get("md5"):
                continue
            similar, distance = _perceptual_similar(left, right)
            if similar:
                union(left["relPath"], right["relPath"])
                distances[(left["relPath"], right["relPath"])] = distance
    components: dict[str, list[dict]] = {}
    for item in eligible:
        components.setdefault(find(item["relPath"]), []).append(item)
    existing_exact = {frozenset([group["keep"]["relPath"]] +
                                [item["relPath"] for item in group["removeCandidates"]])
                      for group in groups if group.get("reason") == "exact-md5"}
    for root_key, members in sorted(components.items()):
        if len(members) < 2:
            continue
        signatures = {tuple(item.get("variantSignature") or ()) for item in members}
        if len(signatures) > 1:
            continue
        member_paths = frozenset(item["relPath"] for item in members)
        if member_paths in existing_exact:
            continue
        keeper = choose_keeper(members)
        remove = [item for item in members if item is not keeper]
        pair_distances = [distance for (left, right), distance in distances.items()
                          if left in member_paths and right in member_paths]
        max_distance = max(pair_distances) if pair_distances else 64
        confidence = round(max(0.5, 0.9 - max_distance * 0.05), 2)
        groups.append({
            "groupId": "phash-" + plans.digest({"members": sorted(member_paths)})[:12],
            "reason": "perceptual-similar",
            "confidence": confidence,
            "maxDistance": max_distance,
            "variantMismatch": False,
            "keep": _file_ref(keeper),
            "removeCandidates": [_file_ref(item) for item in remove],
            "bytesReclaimable": sum(int(item.get("sizeBytes") or 0) for item in remove),
            "requiresConfirmation": True,
            "action": "review_remove",
            "safety": "no-auto-delete",
        })
    return sorted(groups, key=lambda item: item["groupId"])


def _file_ref(item: dict) -> dict:
    return {
        "relPath": item.get("relPath"),
        "fileName": item.get("fileName"),
        "sizeBytes": item.get("sizeBytes"),
        "mediaType": item.get("mediaType"),
        "md5": item.get("md5"),
        "variantSignature": item.get("variantSignature") or [],
        "pageNumber": item.get("pageNumber"),
    }


def _sequence_groups(files: list[dict]) -> tuple[list[dict], list[dict]]:
    # 序号建议只面向媒体文件；TXT/DB 不参与命名规划。
    files = [item for item in files if item.get("mediaType") in ("image", "video")]
    buckets: dict[str, list[dict]] = {}
    for item in files:
        key = item.get("baseKey") or item.get("fileName") or ""
        if not key:
            continue
        buckets.setdefault(key, []).append(item)
    groups: list[dict] = []
    sheets: list[dict] = []
    for key, members in sorted(buckets.items()):
        members = sorted(members, key=lambda item: (item.get("pageNumber") is None,
                                                    item.get("pageNumber") or 0,
                                                    item.get("relPath") or ""))
        pages = sorted({int(item["pageNumber"]) for item in members
                        if isinstance(item.get("pageNumber"), int)})
        if len(members) < 2 and not pages:
            continue
        next_page = (max(pages) + 1) if pages else 1
        suggested: dict[str, int] = {}
        for item in members:
            if isinstance(item.get("pageNumber"), int):
                continue
            suggested[item["relPath"]] = next_page
            next_page += 1
        missing = [number for number in range(1, (max(pages) if pages else 0) + 1)
                   if number not in pages]
        label = members[0].get("baseKey") or key
        sheets.append({
            "groupId": "sheet-" + plans.digest({"baseKey": key})[:12],
            "label": label,
            "fileCount": len(members),
            "files": [
                {
                    "order": index + 1,
                    "fileName": item.get("fileName"),
                    "relPath": item.get("relPath"),
                    "sizeBytes": item.get("sizeBytes"),
                    "pageNumber": item.get("pageNumber"),
                    "suggestedPage": suggested.get(item["relPath"]),
                }
                for index, item in enumerate(members)
            ],
            "note": "按组生成带序号/文件名/尺寸或时长标签的 contact sheet，逐张确认顺序/重复/变体。",
        })
        groups.append({
            "baseKey": key,
            "label": label,
            "fileCount": len(members),
            "pages": pages,
            "missingPages": missing,
            "nextPage": (max(pages) + 1) if pages else 1,
            "suggestedPages": suggested,
            "requiresConfirmation": bool(suggested) or bool(missing),
            "files": [_file_ref(item) for item in members],
        })
    return groups, sheets


def _align(source: dict, target: dict) -> dict:
    source_files = {item["relPath"].casefold(): item for item in source["files"]
                    if item.get("mediaType") in ("image", "video")}
    target_files = {item["relPath"].casefold(): item for item in target["files"]
                    if item.get("mediaType") in ("image", "video")}
    common_keys = sorted(set(source_files) & set(target_files))
    same: list[dict] = []
    conflicts: list[dict] = []
    for key in common_keys:
        left, right = source_files[key], target_files[key]
        if _same_strong(left, right):
            same.append({"relPath": left["relPath"], "reason": "exact-md5"})
            continue
        similar, distance = _perceptual_similar(left, right)
        if similar:
            same.append({"relPath": left["relPath"], "reason": "perceptual-similar",
                         "maxDistance": distance})
            continue
        keeper = choose_keeper([left, right])
        conflicts.append({
            "relPath": left["relPath"],
            "source": _file_ref(left),
            "target": _file_ref(right),
            "reason": "content-conflict",
            "keep": "source" if keeper is left else "target",
            "largestSize": max(int(left.get("sizeBytes") or 0),
                               int(right.get("sizeBytes") or 0)),
            "requiresConfirmation": True,
        })
    # 文件名不同但内容相同：按内容对齐，不计入缺失/多余。
    remaining_source = {key: item for key, item in source_files.items() if key not in common_keys}
    remaining_target = {key: item for key, item in target_files.items() if key not in common_keys}
    exact_target: dict[str, list[str]] = {}
    for key, item in remaining_target.items():
        if item.get("md5"):
            exact_target.setdefault(item["md5"], []).append(key)
    renamed: list[dict] = []
    for key in sorted(list(remaining_source)):
        item = remaining_source[key]
        candidates = exact_target.get(item.get("md5") or "", [])
        if not candidates:
            continue
        target_key = candidates.pop(0)
        renamed.append({
            "sourceRelPath": item["relPath"],
            "targetRelPath": remaining_target[target_key]["relPath"],
            "reason": "exact-md5",
        })
        del remaining_source[key]
        del remaining_target[target_key]
    only_source = [_file_ref(remaining_source[key]) for key in sorted(remaining_source)]
    only_target = [_file_ref(remaining_target[key]) for key in sorted(remaining_target)]
    merge_allowed = not conflicts
    suggested_copies = []
    for item in only_target:
        suggested_copies.append({"direction": "target_to_source", "file": item})
    for item in only_source:
        suggested_copies.append({"direction": "source_to_target", "file": item})
    notes = []
    if conflicts:
        notes.append("共同文件内容不一致：以最大文件为准，停止批量合并并逐项人工核对。")
    elif suggested_copies:
        notes.append("共同文件内容一致：可补缺失项（本地 a b / 文件夹 a b c → 补 c），仍需确认后执行。")
    elif renamed:
        notes.append("文件名不同但内容一致；按内容对齐，无需重复复制。")
    else:
        notes.append("两侧共同文件一致且无缺失项，无需合并。")
    return {
        "commonCount": len(common_keys),
        "sameCount": len(same) + len(renamed),
        "conflictCount": len(conflicts),
        "renamedCount": len(renamed),
        "onlyInSourceCount": len(only_source),
        "onlyInTargetCount": len(only_target),
        "mergeAllowed": merge_allowed,
        "same": same,
        "renamed": renamed,
        "conflicts": conflicts,
        "onlyInSource": only_source,
        "onlyInTarget": only_target,
        "suggestedCopies": [] if not merge_allowed else suggested_copies,
        "blockedCopies": suggested_copies if not merge_allowed else [],
        "notes": notes,
    }


# ---------------------------------------------------------------------------
# 计划
# ---------------------------------------------------------------------------


def _validate_scan(payload: dict) -> dict:
    scan = payload.get("scan")
    if not isinstance(scan, dict):
        raise errors.invalid_input("scan 必须是对象（包含 root 等扫描参数）")
    root_value = scan.get("root")
    if root_value in (None, "") and scan.get("libraryId"):
        # libraryId 模式由 tools_media 先解析为 rootPath 后传入；直接调用时缺少 root。
        raise errors.invalid_input("scan.libraryId 模式缺少已解析的 root（请通过 media.organize.prepare 调用）")
    if root_value in (None, ""):
        raise errors.invalid_input("scan.root 或 scan.libraryId 必须提供一个")
    root = _text(root_value, "scan.root")
    target_root = scan.get("targetRoot")
    if target_root is not None:
        target_root = _text(target_root, "scan.targetRoot")
    recursive = scan.get("recursive", True)
    if not isinstance(recursive, bool):
        raise errors.invalid_input("scan.recursive 必须是布尔值")
    include_hidden = scan.get("includeHidden", False)
    if not isinstance(include_hidden, bool):
        raise errors.invalid_input("scan.includeHidden 必须是布尔值")
    dedupe_mode = scan.get("dedupeMode", "perceptual")
    if dedupe_mode not in ("exact", "perceptual", "off"):
        raise errors.invalid_input("scan.dedupeMode 只能是 exact/perceptual/off")
    return {
        "root": root,
        "libraryId": scan.get("libraryId"),
        "targetRoot": target_root,
        "recursive": recursive,
        "includeHidden": include_hidden,
        "dedupeMode": dedupe_mode,
        "maxFiles": _bounded_int(scan.get("maxFiles"), "scan.maxFiles", 1,
                                 HARD_MAX_FILES, DEFAULT_MAX_FILES),
        "maxDepth": _bounded_int(scan.get("maxDepth"), "scan.maxDepth", 0,
                                 HARD_MAX_DEPTH, DEFAULT_MAX_DEPTH),
        "perceptualLimit": _bounded_int(scan.get("perceptualLimit"),
                                        "scan.perceptualLimit", 0,
                                        HARD_PERCEPTUAL_LIMIT, DEFAULT_PERCEPTUAL_LIMIT),
        "maxFileBytes": _bounded_int(scan.get("maxFileBytes"), "scan.maxFileBytes", 1,
                                     DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_FILE_BYTES),
        "maxTotalHashBytes": _bounded_int(scan.get("maxTotalHashBytes"),
                                          "scan.maxTotalHashBytes", 1,
                                          DEFAULT_MAX_TOTAL_HASH_BYTES,
                                          DEFAULT_MAX_TOTAL_HASH_BYTES),
    }


def build_scan_plan(payload: dict, *, env=None) -> dict:
    """从 scan 输入生成只读内容对齐计划（不含绝对路径）。"""
    options = _validate_scan(payload)
    source = scan_directory(
        options["root"], env=env, recursive=options["recursive"],
        max_files=options["maxFiles"], max_depth=options["maxDepth"],
        perceptual_limit=options["perceptualLimit"],
        include_hidden=options["includeHidden"],
        max_file_bytes=options["maxFileBytes"],
        max_total_hash_bytes=options["maxTotalHashBytes"],
        dedupe_mode=options["dedupeMode"],
    )
    target = None
    if options["targetRoot"]:
        target = scan_directory(
            options["targetRoot"], env=env, recursive=options["recursive"],
            max_files=options["maxFiles"], max_depth=options["maxDepth"],
            perceptual_limit=0,
            include_hidden=options["includeHidden"],
            max_file_bytes=options["maxFileBytes"],
            max_total_hash_bytes=options["maxTotalHashBytes"],
            dedupe_mode=options["dedupeMode"],
        )
    duplicates = _duplicate_groups(source["files"], options["dedupeMode"])
    if target is not None:
        duplicates.extend(_duplicate_groups(target["files"], options["dedupeMode"]))
    sequences, sheets = _sequence_groups(source["files"])
    alignment = _align(source, target) if target is not None else None
    suggestions: list[str] = []
    if duplicates:
        suggestions.append(
            "发现重复内容候选：保留最大文件，其余 review_remove 需人工逐条确认；变体标记不同的文件不得互删。")
    if sequences:
        suggestions.append("按现有最大页+1 追加新变体页码；不覆盖、不重排、不跳到无关编号。")
    if sheets:
        suggestions.append("按 contactSheets 分组生成带标签拼图，逐张确认顺序/重复/变体。")
    if alignment is not None:
        suggestions.extend(alignment["notes"])
    status = "ready"
    if alignment is not None and not alignment["mergeAllowed"]:
        status = "blocked"
    elif duplicates or any(item["requiresConfirmation"] for item in sequences) or \
            (alignment is not None and alignment["suggestedCopies"]):
        status = "needs_confirmation"
    impact = {
        "sourceFileCount": source["summary"]["fileCount"],
        "sourceBytes": source["summary"]["totalBytes"],
        "targetFileCount": target["summary"]["fileCount"] if target else 0,
        "duplicateGroups": len(duplicates),
        "duplicateCandidates": sum(len(group["removeCandidates"]) for group in duplicates),
        "bytesReclaimable": sum(int(group.get("bytesReclaimable") or 0) for group in duplicates),
        "sequenceGroups": len(sequences),
        "missingPages": sum(len(item["missingPages"]) for item in sequences),
        "suggestedPageCount": sum(len(item["suggestedPages"]) for item in sequences),
        "alignmentConflicts": alignment["conflictCount"] if alignment else 0,
        "suggestedCopies": len(alignment["suggestedCopies"]) if alignment else 0,
    }
    plan: dict = {
        "schemaVersion": SCAN_PLAN_VERSION,
        "planVersion": "1.0",
        "mode": "content-scan",
        "engineVersion": SCAN_ENGINE_VERSION,
        "tool": TOOL_ID,
        "toolVersion": SCAN_ENGINE_VERSION,
        "status": status,
        "roots": {
            "source": {
                "label": source["root"].name or "root",
                "pathHash": plans.digest({"root": str(source["root"])}),
                **({"libraryId": options["libraryId"]} if options.get("libraryId") else {}),
            },
            **({
                "target": {
                    "label": target["root"].name or "root",
                    "pathHash": plans.digest({"root": str(target["root"])}),
                }
            } if target else {}),
        },
        "scan": {
            "recursive": options["recursive"],
            "includeHidden": options["includeHidden"],
            "dedupeMode": options["dedupeMode"],
            "maxFiles": options["maxFiles"],
            "maxDepth": options["maxDepth"],
            "perceptualLimit": options["perceptualLimit"],
            "summary": source["summary"],
            "targetSummary": target["summary"] if target else None,
        },
        "files": source["files"],
        "targetFiles": target["files"] if target else [],
        "duplicates": duplicates,
        "sequences": sequences,
        "contactSheets": sheets,
        "alignment": alignment,
        "impact": impact,
        "suggestions": suggestions,
        "confirmation": {
            "required": status != "ready",
            "reasons": [reason for reason in (
                "duplicate-candidates" if duplicates else "",
                "sequence-suggestions" if any(item["requiresConfirmation"] for item in sequences) else "",
                "alignment-conflicts" if alignment is not None and not alignment["mergeAllowed"] else "",
                "missing-copies" if alignment is not None and alignment["suggestedCopies"] else "",
            ) if reason],
        },
        "apply": {
            "status": "planned",
            "reason": "本地内容扫描计划为只读建议；受控合并尚未实现。命名计划请用 libraryId+groups 模式。",
        },
        "guardrails": [
            "本计划不写文件、不移动、不删除、不请求业务写入 API。",
            "review_remove 只是候选，不得自动删除；删除只走回收站或独立管理操作。",
            "变体标记不同的文件不得因感知相似而互删；跨变体只有 md5 完全一致才是候选。",
            "拼图目检发现重复/错序/变体混入时停止合并并重新分组。",
            "不得用脚本直接写 SQLite/TXT；媒体移动/删除只走正式 API 或受控维护流程。",
        ],
    }
    plan["planFingerprint"] = _plan_digest(plan)
    return plan
