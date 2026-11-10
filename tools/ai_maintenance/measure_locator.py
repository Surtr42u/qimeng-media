"""隔离合成测量：路径式定位 vs fileRef 定位（不联网、不读真实媒体）。

用法：
  python -X utf8 tools/ai_maintenance/measure_locator.py --json

在系统临时目录创建 1 个逻辑 storage（3 层、60 个合成小文件、1 个目标媒体
+ 同名干扰项），设置 QIMENG_AI_STORAGES / 读写根，比较：

1. pathBaseline：模拟 AI 逐层 os.scandir 列目录（投影不含物理路径）→ 最终把
   绝对路径传给 media.probe/file.hash；统计调用次数、返回字符数、绝对路径出现次数。
2. fileRef：一次搜索（优先 tools_files.files.search；不可用时用 fileref +
   本地遍历等价实现并标注 fallback）→ 后续 probe/hash 复用 fileRef。

目标媒体优先用 ffmpeg 生成 1 秒 160x120 mp4；ffmpeg 不可用/编码失败时退化为
PNG，再退化为文本并只跑 file.hash，通过 scanMode/scanStatus/mediaKind 如实标注，
避免把“定位正确”误写成“媒体扫描成功”。

输出 JSON 顶层是 fileRef 流程指标，`flows` 同时给出两条流程的完整指标。
不输出物理临时目录；只输出相对路径/计数。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import types
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ai_maintenance import errors, fileref, media_primitives, recipes  # noqa: E402

TARGET_RELATIVE_MP4 = "2026/11/batchA/target clip.mp4"
DISTRACTOR_RELATIVE_MP4 = "2026/11/batchB/target clip.mp4"
TARGET_RELATIVE_PNG = "2026/11/batchA/target clip.png"
DISTRACTOR_RELATIVE_PNG = "2026/11/batchB/target clip.png"
TARGET_RELATIVE_TEXT = "2026/11/batchA/target clip.bin"
DISTRACTOR_RELATIVE_TEXT = "2026/11/batchB/target clip.bin"
_ABS_PATTERN = re.compile(r"(?:[A-Za-z]:[\\/]|\\\\[^\\/\s]+[\\/])")


def _json(value) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _count_absolute_paths(value, root: str) -> int:
    if isinstance(value, str):
        count = value.count(root)
        if count:
            return count
        return len(_ABS_PATTERN.findall(value))
    if isinstance(value, dict):
        return sum(_count_absolute_paths(item, root) for item in value.values())
    if isinstance(value, list):
        return sum(_count_absolute_paths(item, root) for item in value)
    return 0


class Flow:
    def __init__(self, name: str):
        self.name = name
        self.tool_calls = 0
        self.returned_chars = 0
        self.absolute_paths = 0
        self.repeat_chars = 0
        self.repeat_calls = 0
        self.correct = False
        self.probe_status = "not_run"
        self.fingerprint_algorithms: list[str] = []
        self.actions: list[str] = []

    def record(self, action: str, payload, *, root: str, repeat: bool = False) -> None:
        text = _json(payload)
        self.tool_calls += 1
        self.returned_chars += len(text)
        self.absolute_paths += _count_absolute_paths(payload, root)
        self.actions.append(action)
        if repeat:
            self.repeat_calls += 1
            self.repeat_chars += len(text)

    def as_dict(self, scan_status: str) -> dict:
        return {
            "toolCalls": self.tool_calls,
            "returnedChars": self.returned_chars,
            "absolutePathOccurrences": self.absolute_paths,
            "repeatCallChars": self.repeat_chars,
            "repeatCalls": self.repeat_calls,
            "correct": self.correct,
            "scanStatus": scan_status,
            "probeStatus": self.probe_status,
            "fingerprintAlgorithms": list(self.fingerprint_algorithms),
            "actions": self.actions,
        }


def _scandir_projection(directory: Path) -> dict:
    entries = []
    try:
        scanned = sorted(os.scandir(directory), key=lambda item: item.name.casefold())
    except OSError:
        return {"entries": [], "error": "unreadable"}
    reparse = getattr(__import__("stat"), "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    for entry in scanned:
        try:
            info = entry.stat(follow_symlinks=False)
            attributes = getattr(info, "st_file_attributes", 0)
            if entry.is_symlink() or (reparse and attributes & reparse):
                continue
            entries.append({
                "name": entry.name,
                "type": "directory" if entry.is_dir(follow_symlinks=False) else "file",
                "size": int(info.st_size) if entry.is_file(follow_symlinks=False) else None,
            })
        except OSError:
            continue
    return {"entries": entries, "count": len(entries)}


def _write_synthetic_files(root: Path) -> None:
    for index in range(60):
        sub = ("2026/11/batchA" if index % 3 else "2026/11/batchB") if index % 2 \
            else ("2026/10/batchC" if index % 5 else "2025/12/batchD")
        directory = root / sub
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"asset-{index:03d}.png").write_bytes(
            (f"synthetic-{index}".encode("ascii") * 8))


def _run_ffmpeg(argv) -> bool:
    try:
        completed = subprocess.run(argv, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, timeout=30, check=False)
    except (OSError, subprocess.SubprocessError):
        return False
    return completed.returncode == 0


def _build_synthetic_tree(root: Path) -> dict:
    """优先真实 mp4，其次 PNG，最后文本；返回扫描模式与相对路径。"""
    _write_synthetic_files(root)
    ffmpeg = media_primitives.find_ffmpeg()
    if ffmpeg:
        mp4_target = root / TARGET_RELATIVE_MP4
        mp4_distractor = root / DISTRACTOR_RELATIVE_MP4
        for path in (mp4_target, mp4_distractor):
            path.parent.mkdir(parents=True, exist_ok=True)
        built = _run_ffmpeg([
            ffmpeg, "-v", "error", "-y", "-f", "lavfi",
            "-i", "testsrc=size=160x120:rate=10:duration=1",
            "-pix_fmt", "yuv420p", str(mp4_target),
        ]) and _run_ffmpeg([
            ffmpeg, "-v", "error", "-y", "-f", "lavfi",
            "-i", "testsrc=size=160x120:rate=10:duration=1",
            "-pix_fmt", "yuv420p", str(mp4_distractor),
        ])
        if built and mp4_target.is_file() and mp4_distractor.is_file():
            return {"target": mp4_target, "targetRelative": TARGET_RELATIVE_MP4,
                    "targetName": mp4_target.name, "scanMode": "ffmpeg-mp4",
                    "mediaKind": "video", "probeSupported": True}
        png_target = root / TARGET_RELATIVE_PNG
        png_distractor = root / DISTRACTOR_RELATIVE_PNG
        for path in (png_target, png_distractor):
            path.parent.mkdir(parents=True, exist_ok=True)
        built = _run_ffmpeg([
            ffmpeg, "-v", "error", "-y", "-f", "lavfi",
            "-i", "color=c=red:s=160x120:d=1", "-frames:v", "1", str(png_target),
        ]) and _run_ffmpeg([
            ffmpeg, "-v", "error", "-y", "-f", "lavfi",
            "-i", "color=c=blue:s=160x120:d=1", "-frames:v", "1", str(png_distractor),
        ])
        if built and png_target.is_file() and png_distractor.is_file():
            return {"target": png_target, "targetRelative": TARGET_RELATIVE_PNG,
                    "targetName": png_target.name, "scanMode": "ffmpeg-png",
                    "mediaKind": "image", "probeSupported": True}
    target = root / TARGET_RELATIVE_TEXT
    distractor = root / DISTRACTOR_RELATIVE_TEXT
    target.parent.mkdir(parents=True, exist_ok=True)
    distractor.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(b"qimeng-target-text-fallback" * 32)
    distractor.write_bytes(b"qimeng-distractor-text-fallback" * 32)
    return {"target": target, "targetRelative": TARGET_RELATIVE_TEXT,
            "targetName": target.name, "scanMode": "no-media-parse",
            "mediaKind": "text", "probeSupported": False}


def _local_search(storage_id: str, query: str, root: Path, env) -> dict:
    """tools_files 不可用时的等价本地遍历（只返回 fileRef/相对路径）。"""
    matches = []
    for current, dirs, files in os.walk(root):
        dirs[:] = sorted(d for d in dirs if not os.path.islink(Path(current) / d))
        for name in sorted(files):
            path = Path(current) / name
            if path.is_symlink():
                continue
            relative = path.relative_to(root).as_posix()
            if query.casefold() not in relative.casefold() and \
                    query.casefold() not in name.casefold():
                continue
            try:
                metadata = fileref.make_ref(path, env=env)
            except errors.ToolError:
                continue
            matches.append({key: metadata[key] for key in
                            ("fileRef", "storageId", "relativePath", "name",
                             "mediaType", "size", "modifiedAt")})
            if len(matches) >= 50:
                break
        if len(matches) >= 50:
            break
    return {"files": matches, "count": len(matches), "truncated": False,
            "searchMode": "fallback-local"}


def _search(storage_id: str, query: str, root: Path, env) -> tuple[dict, str, list[str]]:
    notes = []
    try:
        from ai_maintenance import tools_files  # 并行批次新增；缺失时走 fallback
    except ImportError:
        notes.append("tools_files 不可用：使用 fileref+本地遍历等价实现（fallback）。")
        return _local_search(storage_id, query, root, env), "fallback-local", notes
    try:
        ctx = types.SimpleNamespace(env=env)
        payload = tools_files.files_search(
            ctx, {"storageId": storage_id, "query": query, "limit": 50})
    except Exception as exc:  # noqa: BLE001 - tools_files 在并行批次中可能不完整
        notes.append("tools_files.files.search 不可用（" + type(exc).__name__ +
                     "）：使用 fileref+本地遍历等价实现（fallback）。")
        return _local_search(storage_id, query, root, env), "fallback-local", notes
    if not isinstance(payload, dict) or not payload.get("available", True) \
            or not isinstance(payload.get("files"), list):
        notes.append("tools_files.files.search 返回不可用：使用 fileref+本地遍历等价实现（fallback）。")
        return _local_search(storage_id, query, root, env), "fallback-local", notes
    return payload, "tools_files", notes


def measure() -> dict:
    with tempfile.TemporaryDirectory(prefix="qimeng-locator-") as temporary:
        work = Path(temporary)
        storage_root = work / "storage"
        storage_root.mkdir()
        synthetic = _build_synthetic_tree(storage_root)
        target = synthetic["target"]
        target_relative = synthetic["targetRelative"]
        target_name = synthetic["targetName"]
        probe_supported = synthetic["probeSupported"]
        cache = work / "cache"
        run_dir = work / "run"
        env = {
            "QIMENG_AI_STORAGES": f"downloads={storage_root}",
            # 测量脚本同时授权读写根，避免把授权行为混入定位指标。
            "QIMENG_AI_READ_ROOTS": str(storage_root),
            "QIMENG_AI_WRITE_ROOTS": str(storage_root),
            "QIMENG_AI_MEDIA_CACHE": str(cache),
        }
        ctx = recipes.RunContext(env, run_dir)
        root_text = str(storage_root.resolve())
        cache_enabled = media_primitives._cache_root_for_write(env) is not None

        # 1) 路径式基线：逐层列目录（投影不含物理路径）→ 绝对路径 probe/hash。
        baseline = Flow("pathBaseline")
        for relative in ("2026", "2026/11", "2026/11/batchA"):
            baseline.record("scandir:" + relative,
                            _scandir_projection(storage_root / relative), root=root_text)
        baseline_path = str(target.resolve())
        baseline.correct = Path(baseline_path).resolve() == target.resolve()
        if probe_supported:
            probe = recipes._primitive_media_probe(
                {"path": baseline_path, "kind": synthetic["mediaKind"]}, ctx)
            baseline.probe_status = str(probe.get("status") or "missing")
            baseline.record("media.probe(path)", probe, root=root_text, repeat=True)
        digest = recipes._primitive_media_fingerprint(
            {"path": baseline_path, "algorithms": ["md5", "sha256"]}, ctx)
        baseline.fingerprint_algorithms = sorted(
            key for key in ("md5", "sha256") if digest.get(key))
        baseline.record("file.hash(path)", digest, root=root_text, repeat=True)

        # 2) fileRef：一次搜索 → 复用 fileRef 的 probe/hash。
        ref_flow = Flow("fileRef")
        search, search_mode, notes = _search("downloads", target_name, storage_root, env)
        ref_flow.record("files.search", search, root=root_text)
        chosen = next(
            (item for item in search.get("files") or []
             if item.get("relativePath") == target_relative),
            None,
        )
        if chosen is None:
            ref_flow.correct = False
            return _result(
                baseline, ref_flow, synthetic, search_mode, notes, cache_enabled,
                extra_notes=["搜索结果未包含目标相对路径。"],
            )
        reference = chosen["fileRef"]
        try:
            ref_flow.correct = fileref.resolve_ref(reference, env=env).resolve() == \
                target.resolve()
        except errors.ToolError:
            ref_flow.correct = False
        if probe_supported:
            probe_ref = recipes._primitive_media_probe(
                {"fileRef": reference, "kind": synthetic["mediaKind"]}, ctx)
            ref_flow.probe_status = str(probe_ref.get("status") or "missing")
            ref_flow.record("media.probe(fileRef)", probe_ref, root=root_text, repeat=True)
        digest_ref = recipes._primitive_media_fingerprint(
            {"fileRef": reference, "algorithms": ["md5", "sha256"]}, ctx)
        ref_flow.fingerprint_algorithms = sorted(
            key for key in ("md5", "sha256") if digest_ref.get(key))
        ref_flow.record("file.hash(fileRef)", digest_ref, root=root_text, repeat=True)
        return _result(baseline, ref_flow, synthetic, search_mode, notes, cache_enabled)


def _result(baseline: Flow, ref_flow: Flow, synthetic: dict, search_mode: str,
            notes: list[str], cache_enabled: bool, extra_notes=None) -> dict:
    scan_status = ("ok" if baseline.probe_status == "ok" else baseline.probe_status) \
        if synthetic["probeSupported"] else "not_run"
    saved = baseline.returned_chars - ref_flow.returned_chars
    ratio = (ref_flow.returned_chars / baseline.returned_chars
             if baseline.returned_chars else 0.0)
    notes = list(notes) + [
        "metrics: absolutePathOccurrences 统计返回 JSON 中物理 storage 根出现次数。",
        "returnedChars 为返回给模型的 JSON 紧凑序列化字符数："
        f"baseline={baseline.returned_chars}，fileRef={ref_flow.returned_chars}，"
        f"差值={saved}，fileRef/baseline={ratio:.2%}。",
        "repeatCallChars 为搜索/列目录之后 probe+hash 两次调用的返回字符数。",
        "QIMENG_AI_MEDIA_CACHE 不在写授权根内：缓存已按安全策略静默禁用，"
        "本次指标不包含缓存命中收益。",
    ]
    if not synthetic["probeSupported"]:
        notes.append("未做媒体解析（缺少可用的 ffmpeg 或编码失败）：只执行 file.hash。")
    if extra_notes:
        notes.extend(extra_notes)
    flows = {
        "pathBaseline": baseline.as_dict(scan_status),
        "fileRef": ref_flow.as_dict(scan_status),
    }
    return {
        "toolCalls": ref_flow.tool_calls,
        "returnedChars": ref_flow.returned_chars,
        "absolutePathOccurrences": ref_flow.absolute_paths,
        "repeatCallChars": ref_flow.repeat_chars,
        "correct": ref_flow.correct,
        "flow": "fileRef",
        "flows": flows,
        "scanMode": synthetic["scanMode"],
        "scanStatus": scan_status,
        "mediaKind": synthetic["mediaKind"],
        "targetRelativePath": synthetic["targetRelative"],
        "searchMode": search_mode,
        "searchFallback": search_mode == "fallback-local",
        "cacheEnabled": cache_enabled,
        "notes": notes,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="fileRef 与路径式定位的隔离合成测量")
    parser.add_argument("--json", action="store_true", help="输出 JSON（默认也输出 JSON）")
    args = parser.parse_args(argv)
    result = measure()
    sys.stdout.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    return 0 if result.get("correct") else 1


if __name__ == "__main__":
    raise SystemExit(main())
