"""媒体原语：抽帧、dHash64、媒体元数据、拼图目检与结果缓存。

设计（2026-11-10，AI API 统一能力层）：
- 抽帧/感知哈希/拼图只在这里实现一次；`media_scan`、`research.media.probe`
  与 recipe（如 media.batch / author.discover）都调用本模块，不再各写一套
  ffmpeg 参数。
- 结果按 `(绝对路径, 大小, mtime_ns, 参数)` 缓存到
  `QIMENG_AI_MEDIA_CACHE`（默认仓库 `build/ai-maintenance/cache/media`）。
  同一视频在“作者识图 / 文件比对 / 角色识别”之间只抽一次帧、只算一次
  dHash；文件变化后缓存键自动失效。
- 本模块只读源文件；除 `extract_frames` / `build_contact_sheet` 写显式
  输出目录外，不移动、不删除任何媒体。
- ffmpeg/ffprobe 缺失时返回结构化 `unavailable`，不伪造哈希或空结论。
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from . import plans

PRIMITIVES_VERSION = "1.0.0"
DEFAULT_FRAME_PERCENTAGES = (15, 50, 85)
DEFAULT_CONTACT_COLUMNS = 4
DEFAULT_CONTACT_CELL = (320, 240)
MAX_CONTACT_CELLS = 48
_CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _env(env) -> dict:
    return dict(env or {})


def _tool(env, name: str) -> str:
    # 兼容既有 media_scan 的 QIMENG_FFMPEG/QIMENG_FFPROBE 与统一层的 QIMENG_AI_*。
    for key in ("QIMENG_AI_" + name.upper(), "QIMENG_" + name.upper()):
        override = (env or {}).get(key)
        if override:
            return str(override)
    found = shutil.which(name)
    return found or ""


def find_ffmpeg(env=None) -> str:
    return _tool(env, "ffmpeg")


def find_ffprobe(env=None) -> str:
    return _tool(env, "ffprobe")


def cache_root(env=None) -> Path:
    override = (env or {}).get("QIMENG_AI_MEDIA_CACHE")
    if override:
        return Path(override).expanduser().resolve()
    return Path(__file__).resolve().parents[2] / "build" / "ai-maintenance" / "cache" / "media"


def _cache_path(kind: str, path: Path, params: dict, env=None) -> Path:
    stat = path.stat()
    payload = plans.canonical_json({
        "version": PRIMITIVES_VERSION,
        "kind": kind,
        "path": str(path.resolve()),
        "size": stat.st_size,
        "mtimeNs": stat.st_mtime_ns,
        "params": params,
    })
    digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    return cache_root(env) / kind / (digest + ".json")


def _read_cache(file: Path):
    try:
        with file.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def _write_cache(file: Path, value) -> None:
    try:
        file.parent.mkdir(parents=True, exist_ok=True)
        temporary = file.with_name(file.name + ".tmp-" + str(os.getpid()))
        with temporary.open("w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, sort_keys=True)
        os.replace(temporary, file)
    except OSError:
        # 缓存不可写不影响主流程；下次重新计算。
        return


def _run_capture(argv, timeout: float = 60.0) -> tuple[int, bytes, str]:
    try:
        completed = subprocess.run(
            argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=timeout, check=False, creationflags=_CREATE_NO_WINDOW,
        )
    except subprocess.TimeoutExpired:
        return -1, b"", "timeout"
    except (OSError, subprocess.SubprocessError) as exc:
        return -1, b"", type(exc).__name__
    return completed.returncode, completed.stdout[:4096], ""


def probe_media(path: Path, ffprobe: str | None = None, *, env=None) -> dict:
    """ffprobe 宽高/时长；失败返回空对象（不编造元数据）。"""
    ffprobe = ffprobe if ffprobe is not None else find_ffprobe(env)
    if not ffprobe:
        return {}
    code, output, _error = _run_capture([
        ffprobe, "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=width,height,duration:format=duration",
        "-of", "json", str(path),
    ])
    if code != 0 or not output:
        return {}
    try:
        data = json.loads(output.decode("utf-8", "replace"))
    except ValueError:
        return {}
    stream = {}
    streams = data.get("streams")
    if isinstance(streams, list) and streams and isinstance(streams[0], dict):
        stream = streams[0]
    format_info = data.get("format") if isinstance(data.get("format"), dict) else {}
    width = stream.get("width")
    height = stream.get("height")
    duration = stream.get("duration") or format_info.get("duration")
    result: dict = {}
    if isinstance(width, int) and width > 0 and isinstance(height, int) and height > 0:
        result["dimensions"] = {"width": width, "height": height}
    if duration not in (None, ""):
        try:
            result["durationMs"] = max(0, int(float(duration) * 1000))
        except (TypeError, ValueError):
            pass
    return result


def dhash64(path: Path, ffmpeg: str | None = None, *,
            seek_seconds: float | None = None, env=None) -> str | None:
    """单帧 9x8 灰度 dHash64；视频可用 seek_seconds 定位。"""
    ffmpeg = ffmpeg if ffmpeg is not None else find_ffmpeg(env)
    if not ffmpeg:
        return None
    argv = [ffmpeg, "-v", "error", "-nostdin"]
    if seek_seconds is not None:
        argv.extend(["-ss", f"{max(0.0, seek_seconds):.3f}"])
    argv.extend([
        "-i", str(path), "-frames:v", "1",
        "-vf", "scale=9:8:flags=area,format=gray",
        "-f", "rawvideo", "-",
    ])
    code, output, _error = _run_capture(argv)
    if code != 0 or len(output) < 72:
        return None
    pixels = output[:72]
    value = 0
    for row in range(8):
        offset = row * 9
        for column in range(8):
            left = pixels[offset + column]
            right = pixels[offset + column + 1]
            value = (value << 1) | (1 if left > right else 0)
    return f"{value:016x}"


def perceptual_record(path: Path, kind: str, *, env=None,
                      cache: bool = True) -> dict:
    """图片单帧 / 视频 15/50/85% 抽帧的 dHash64 记录（带缓存）。"""
    resolved = path.resolve()
    params = {"kind": kind, "version": 1}
    if cache:
        cached = _read_cache(_cache_path("perceptual", resolved, params, env))
        if isinstance(cached, dict):
            return cached
    ffmpeg = find_ffmpeg(env)
    ffprobe = find_ffprobe(env)
    record: dict = {"status": "error", "hashKind": "dhash64", "frames": []}
    probe = probe_media(resolved, ffprobe)
    record.update(probe)
    if not ffmpeg:
        record["status"] = "unavailable"
        record["reason"] = "ffmpeg_not_found"
        _write_cache(_cache_path("perceptual", resolved, params, env), record) if cache else None
        return record
    duration_ms = probe.get("durationMs")
    if kind == "video":
        if isinstance(duration_ms, int) and duration_ms > 0:
            # durationMs 是毫秒；百分比先除以 100 再换算秒。
            seeks = [duration_ms * ratio / 100000.0 for ratio in DEFAULT_FRAME_PERCENTAGES]
        else:
            seeks = [0.0]
    else:
        seeks = [None]
    frames = []
    for seek in seeks:
        hashed = dhash64(resolved, ffmpeg, seek_seconds=seek)
        if hashed:
            frames.append(hashed)
    record["frames"] = frames
    record["status"] = "ok" if frames else "error"
    if cache:
        _write_cache(_cache_path("perceptual", resolved, params, env), record)
    return record


def parse_percentages(value) -> tuple[int, ...]:
    if value is None or value == "":
        return DEFAULT_FRAME_PERCENTAGES
    parts = []
    for item in str(value).split(","):
        item = item.strip()
        if not item:
            continue
        try:
            number = int(item)
        except ValueError:
            raise ValueError("抽帧百分比必须是整数")
        if not 0 <= number <= 100:
            raise ValueError("抽帧百分比必须在 0..100")
        parts.append(number)
    if not parts:
        raise ValueError("抽帧百分比不能为空")
    return tuple(parts)


def extract_frames(path: Path, out_dir: Path, *, percentages=None, kind: str = "video",
                   env=None, cache: bool = True) -> dict:
    """按百分比抽帧到显式目录；返回帧路径、时间点与 sha256。"""
    resolved = path.resolve()
    percentage_values = tuple(percentages or DEFAULT_FRAME_PERCENTAGES)
    params = {"kind": kind, "percentages": list(percentage_values), "version": 1}
    cache_file = _cache_path("frames", resolved, params, env)
    if cache:
        cached = _read_cache(cache_file)
        if isinstance(cached, dict) and cached.get("status") == "ok":
            frames = cached.get("frames") or []
            if all(Path(item.get("path", "")).is_file() for item in frames if isinstance(item, dict)):
                return cached
    ffmpeg = find_ffmpeg(env)
    ffprobe = find_ffprobe(env)
    record: dict = {"status": "error", "frames": [], "source": str(resolved)}
    probe = probe_media(resolved, ffprobe)
    record.update(probe)
    if not ffmpeg:
        record["status"] = "unavailable"
        record["reason"] = "ffmpeg_not_found"
        return record
    out_dir = out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    duration_ms = probe.get("durationMs")
    frames = []
    for percentage in percentage_values:
        seek = None
        if kind == "video" and isinstance(duration_ms, int) and duration_ms > 0:
            seek = duration_ms * percentage / 100000.0
        target = out_dir / (resolved.stem.replace(" ", "_")[:60] + f"-{percentage:03d}.jpg")
        argv = [ffmpeg, "-v", "error", "-nostdin", "-y"]
        if seek is not None:
            argv.extend(["-ss", f"{max(0.0, seek):.3f}"])
        argv.extend(["-i", str(resolved), "-frames:v", "1", "-q:v", "2", str(target)])
        code, _output, _error = _run_capture(argv, timeout=120.0)
        if code != 0 or not target.is_file():
            continue
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        frames.append({
            "percentage": percentage,
            "seekMs": int(seek * 1000) if seek is not None else None,
            "path": str(target),
            "sha256": digest,
            "bytes": target.stat().st_size,
        })
    record["frames"] = frames
    record["status"] = "ok" if frames else "error"
    if cache:
        _write_cache(cache_file, record)
    return record


def _font_path(env=None) -> str:
    override = (env or {}).get("QIMENG_AI_FONT")
    candidates = [override] if override else []
    candidates.extend([
        "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/simhei.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/System/Library/Fonts/PingFang.ttc",
    ])
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate).resolve()).replace("\\", "/")
    return ""


def _escape_filter_text(value: str) -> str:
    text = str(value).replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")
    text = text.replace("%", "\\%").replace("[", "\\[").replace("]", "\\]")
    return text


def _escape_filter_path(value: str) -> str:
    return str(value).replace("\\", "/").replace(":", "\\:").replace("'", "\\'")


def _cell_label(entry: dict) -> str:
    label = entry.get("label")
    if isinstance(label, str) and label.strip():
        return label.strip()[:80]
    return Path(str(entry.get("path", ""))).name[:80]


def build_contact_sheet(entries, out_path, *, columns: int = DEFAULT_CONTACT_COLUMNS,
                        cell=DEFAULT_CONTACT_CELL, title: str = "", env=None,
                        max_cells: int = MAX_CONTACT_CELLS) -> dict:
    """生成带序号/名称标签的拼图；超过 max_cells 时分成多张。"""
    ffmpeg = find_ffmpeg(env)
    if not ffmpeg:
        raise RuntimeError("ffmpeg_not_found")
    items = [dict(item) for item in entries if isinstance(item, dict)]
    if not items:
        raise ValueError("contact sheet 需要至少一个条目")
    columns = max(1, min(int(columns), 8))
    max_cells = max(1, min(int(max_cells), MAX_CONTACT_CELLS))
    width = max(120, int(cell[0]))
    height = max(90, int(cell[1]))
    font = _font_path(env)
    output = Path(out_path).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    sheets = []
    with tempfile.TemporaryDirectory(prefix="qimeng-contact-") as temporary:
        temporary_root = Path(temporary)
        for sheet_index, start in enumerate(range(0, len(items), max_cells), 1):
            chunk = items[start:start + max_cells]
            target = output if len(items) <= max_cells else output.with_name(
                output.stem + f"-{sheet_index}" + output.suffix)
            built = _build_one_sheet(ffmpeg, chunk, target, temporary_root,
                                     columns=columns, width=width, height=height,
                                     font=font, title=title)
            if not built:
                raise RuntimeError("contact_sheet_render_failed")
            sheets.append({
                "path": str(target),
                "bytes": target.stat().st_size,
                "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                "cellCount": len(chunk),
            })
    return {
        "status": "ok",
        "sheets": sheets,
        "totalCells": len(items),
        "columns": columns,
        "cell": {"width": width, "height": height},
        "font": font,
    }


def _build_one_sheet(ffmpeg: str, chunk: list[dict], target: Path,
                     temporary_root: Path, *, columns: int, width: int,
                     height: int, font: str, title: str) -> bool:
    label_height = 26 if font else 0
    prepared: list[Path] = []
    for index, entry in enumerate(chunk, 1):
        source = Path(str(entry.get("path", "")))
        if not source.is_file():
            raise ValueError("contact sheet 源文件不存在：" + str(source))
        prepared.append(source)
    rows = (len(prepared) + columns - 1) // columns
    argv = [ffmpeg, "-v", "error", "-nostdin", "-y"]
    for source in prepared:
        argv.extend(["-i", str(source)])
    # 占位黑格（最后一行不足时）。
    missing = rows * columns - len(prepared)
    if missing:
        for _ in range(missing):
            argv.extend(["-f", "lavfi", "-i", f"color=c=0x101010:s={width}x{height}:d=1"])
    filter_parts = []
    for index in range(rows * columns):
        scale = (f"[{index}:v]scale={width}:{height}:force_original_aspect_ratio=decrease,"
                 f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=0x101010")
        if index < len(prepared) and font:
            label = f"{index + 1}. {_cell_label(chunk[index])}"
            scale += (
                ",drawtext=fontfile='" + _escape_filter_path(font) + "'"
                ":text='" + _escape_filter_text(label) + "'"
                ":fontcolor=white:fontsize=14:x=6:y=h-20:box=1:boxcolor=0x000000AA"
            )
        scale += f"[v{index}]"
        filter_parts.append(scale)
    outputs = []
    for row in range(rows):
        inputs = "".join(f"[v{row * columns + column}]" for column in range(columns))
        filter_parts.append(f"{inputs}hstack=inputs={columns}[row{row}]")
        outputs.append(f"[row{row}]")
    if rows > 1:
        filter_parts.append("".join(outputs) + f"vstack=inputs={rows}[out]")
        map_label = "[out]"
    else:
        map_label = "[row0]"
    command = argv + ["-filter_complex", ";".join(filter_parts), "-map", map_label,
                      "-frames:v", "1", str(target)]
    code, _output, _error = _run_capture(command, timeout=300.0)
    return code == 0 and target.is_file()
