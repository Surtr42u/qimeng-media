"""研究工作流工具：research.image.* / research.author.probe / research.media.probe / research.avatar.fetch。

算法唯一实现仍在 `tools/research_kit/`（run_pipeline / lens_batch / yandex /
saucenao / fxtwitter / kemono / media_probe / avatar_fetch）；本模块只做：
- 输入校验与 argv 组装（始终列表 + shell=False，避免命令注入）；
- 路径边界：输入/输出必须是绝对路径，仓库内只允许被忽略的工作根
  （QIMENG_AI_RESEARCH_DIR，默认 tools/ai_maintenance/.work/research）；
- 子进程超时（默认 300s、上限 900s）与有界输出捕获（stdout/stderr 各 256KiB，
  只把摘要尾部放进工具结果，不回传完整日志）；
- 产物枚举：{exitCode, artifacts:[{path,sha256,bytes}], summary}。

方法学与证据分级见 [tools/IMAGE_RESEARCH.md](../../IMAGE_RESEARCH.md)、
[tools/AUTHOR_RESEARCH.md](../../AUTHOR_RESEARCH.md) 与 research_kit/README.md；
优先 Lens、再 Yandex/SauceNAO、最后才考虑作者探针（见 rules.json research 节）。
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

from . import errors, fileref, pathguard
from .runtime import ToolContext

REPO_ROOT = Path(__file__).resolve().parents[2]
RESEARCH_KIT_DIR = REPO_ROOT / "tools" / "research_kit"

DEFAULT_TIMEOUT_SECONDS = 300
MAX_TIMEOUT_SECONDS = 900
LOG_LIMIT_BYTES = 256 << 10
STDERR_TAIL_CHARS = 1200
STDOUT_TAIL_CHARS = 400
MAX_ARTIFACTS = 50
MAX_MEDIA_FILES = 50
MAX_AVATAR_URLS = 20

NODE_ENV = "QIMENG_RESEARCH_KIT_NODE"
SEARCH_SCRIPTS = ("lens", "yandex", "saucenao")
AUTHOR_SCRIPTS = ("fxtwitter", "kemono")
_FRAMES_RE = re.compile(r"^\d{1,3}(,\d{1,3}){0,3}$")


def _script(name: str) -> Path:
    path = RESEARCH_KIT_DIR / name
    if not path.is_file():
        raise errors.tool_not_implemented(
            "research", "research_kit 脚本缺失：" + name + "（随发行物安装后可用）")
    return path


def _timeout(payload: dict) -> int:
    value = payload.get("timeoutSeconds")
    if value is None:
        return DEFAULT_TIMEOUT_SECONDS
    if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= MAX_TIMEOUT_SECONDS:
        raise errors.invalid_input(f"timeoutSeconds 必须在 1..{MAX_TIMEOUT_SECONDS}")
    return value


def _node_binary(env) -> str:
    configured = (env or {}).get(NODE_ENV, "") if env is not None else os.environ.get(NODE_ENV, "")
    if isinstance(configured, str) and configured.strip():
        candidate = Path(configured.strip())
        if candidate.is_file():
            return str(candidate)
        found = shutil.which(configured.strip())
        if found:
            return found
        raise errors.tool_not_implemented(
            "research.image.search", NODE_ENV + " 指向的 Node 可执行文件不存在")
    found = shutil.which("node")
    if not found:
        raise errors.tool_not_implemented(
            "research.image.search",
            "缺少 Node.js 24+（设置 " + NODE_ENV + " 或加入 PATH）；Lens 批处理无法启动")
    return found


def _input_path(ctx: ToolContext, value, kind: str) -> str:
    return str(pathguard.resolve_local_path(value, env=ctx.env, must_exist=True,
                                            kind=kind, mode="read"))


def _output_dir(ctx: ToolContext, value, tool_label: str, *, required: bool = False) -> Path:
    """输出目录：显式绝对路径或工作根下的默认目录；创建后返回。"""
    if value is None:
        if required:
            raise errors.invalid_input("缺少 outDir（必须是绝对路径）")
        root = pathguard.work_root(ctx.env) / tool_label
        directory = root / (time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
                            + "-" + os.urandom(4).hex())
    else:
        directory = pathguard.resolve_local_path(value, env=ctx.env, kind="outDir",
                                                  mode="write")
    return pathguard.ensure_directory(directory)


def _clean_env(ctx: ToolContext) -> dict:
    """子进程环境：剔除 QIMENG_API_*（研究脚本不需要服务端凭据）。"""
    source = ctx.env if isinstance(ctx.env, dict) else dict(os.environ)
    env = {key: value for key, value in source.items()
           if not key.startswith("QIMENG_API_")}
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    return env


def _pump(stream, sink: list[bytes], limit: int) -> None:
    total = 0
    try:
        while True:
            chunk = stream.read(64 << 10)
            if not chunk:
                break
            if total < limit:
                sink.append(chunk[: limit - total])
            total += len(chunk)
    except (OSError, ValueError):
        pass
    finally:
        try:
            stream.close()
        except (OSError, ValueError):
            pass


def _execute(argv: list, *, env: dict, timeout: int) -> dict:
    """运行 argv（列表，shell=False）；超时 kill 并按 124 报告。"""
    try:
        process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   cwd=str(REPO_ROOT), env=env, shell=False)
    except OSError:
        raise errors.tool_not_implemented(
            "research", "研究脚本无法启动（解释器/可执行文件不可用）") from None
    stdout_chunks: list[bytes] = []
    stderr_chunks: list[bytes] = []
    threads = [
        threading.Thread(target=_pump, args=(process.stdout, stdout_chunks, LOG_LIMIT_BYTES),
                         daemon=True),
        threading.Thread(target=_pump, args=(process.stderr, stderr_chunks, LOG_LIMIT_BYTES),
                         daemon=True),
    ]
    started = time.monotonic()
    for thread in threads:
        thread.start()
    timed_out = False
    try:
        exit_code = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        process.kill()
        try:
            exit_code = process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            exit_code = 124
    for thread in threads:
        thread.join(timeout=5)
    elapsed_ms = int((time.monotonic() - started) * 1000)
    return {
        "exitCode": 124 if timed_out else exit_code,
        "timedOut": timed_out,
        "elapsedMs": elapsed_ms,
        "stdout": b"".join(stdout_chunks),
        "stderr": b"".join(stderr_chunks),
    }


def _text(raw: bytes) -> str:
    return raw.decode("utf-8", "replace")


def _artifact(path: Path) -> dict | None:
    try:
        if not path.is_file() or path.is_symlink():
            return None
        sha256, size = pathguard.sha256_file(path)
    except errors.ToolError:
        return None
    return {"path": str(path), "sha256": sha256, "bytes": size}


def _artifacts(paths, *, limit: int = MAX_ARTIFACTS) -> list[dict]:
    rows: list[dict] = []
    seen: set[str] = set()
    for path in paths:
        if len(rows) >= limit:
            break
        key = str(Path(path))
        if key in seen:
            continue
        seen.add(key)
        row = _artifact(Path(path))
        if row:
            rows.append(row)
    return rows


def _directory_artifacts(directory: Path, *, limit: int = MAX_ARTIFACTS) -> list[Path]:
    """有界枚举目录内普通文件（按相对路径排序）；不跟随符号链接。

    返回路径列表而不是已序列化的工件字典：调用方会与其它路径合并后再交给
    ``_artifacts`` 统一去重/摘要，避免 dict 与 Path 混用。
    """
    collected: list[Path] = []
    for root, dirs, files in os.walk(directory):
        dirs.sort()
        for name in sorted(files):
            collected.append(Path(root) / name)
            if len(collected) >= limit:
                break
        if len(collected) >= limit:
            break
    return collected


def _write_log(directory: Path, name: str, raw: bytes) -> Path | None:
    if not raw:
        return None
    path = directory / name
    try:
        path.write_bytes(raw)
    except OSError:
        return None
    return path


def _summary(run: dict, *, script: str, args: list, artifacts: list[dict],
             notes: list[str]) -> dict:
    stdout_text = _text(run["stdout"])
    stderr_text = _text(run["stderr"])
    return {
        "script": script,
        "exitCode": run["exitCode"],
        "timedOut": run["timedOut"],
        "elapsedMs": run["elapsedMs"],
        "stdoutBytes": len(run["stdout"]),
        "stdoutTruncated": len(run["stdout"]) >= LOG_LIMIT_BYTES,
        "stdoutChars": len(stdout_text),
        "stdoutTail": stdout_text[-STDOUT_TAIL_CHARS:] if stdout_text else "",
        "stderrTail": stderr_text[-STDERR_TAIL_CHARS:] if stderr_text else "",
        "artifacts": [row["path"] for row in artifacts],
        "args": args,
        "notes": notes,
    }


def _result(run: dict, *, script: str, args: list, artifact_paths,
            notes: list[str], directory: Path | None = None,
            log_prefix: str = "tool") -> dict:
    if directory is not None and run["exitCode"] != 0:
        log = _write_log(directory, log_prefix + ".stderr.log", run["stderr"])
        if log is not None:
            artifact_paths = list(artifact_paths) + [log]
    artifacts = _artifacts(artifact_paths)
    return {
        "exitCode": run["exitCode"],
        "artifacts": artifacts,
        "summary": _summary(run, script=script, args=args, artifacts=artifacts,
                            notes=notes),
    }


def _python() -> list:
    return [sys.executable, "-X", "utf8"]


# --------------------------------------------------------------------------- #
# research.image.pipeline
# --------------------------------------------------------------------------- #

def research_image_pipeline(ctx: ToolContext, payload: dict) -> dict:
    jobs = _input_path(ctx, payload.get("jobsPath"), "jobsPath")
    directory = _output_dir(ctx, payload.get("outDir"), "image-pipeline")
    out = directory / "results.jsonl"
    script = _script("run_pipeline.py")
    argv = _python() + [str(script), "--jobs", jobs, "--out", str(out), "--json-summary"]
    stages = payload.get("stages")
    if stages is not None:
        argv += ["--stages", stages]
    for name, flag in (("maxLens", "--max-lens"), ("maxYandex", "--max-yandex"),
                       ("maxSaucenao", "--max-saucenao")):
        if payload.get(name) is not None:
            argv += [flag, str(payload[name])]
    if payload.get("deep") is True:
        argv.append("--deep")
    if payload.get("dryRun") is True:
        argv.append("--dry-run")
    run = _execute(argv, env=_clean_env(ctx), timeout=_timeout(payload))
    artifact_paths = [out, out.with_name(out.name + ".failures.jsonl")]
    return _result(run, script="run_pipeline.py", args=argv[1:],
                   artifact_paths=artifact_paths, directory=directory,
                   notes=["按阶梯调度 Lens→Yandex→SauceNAO；预算由 maxLens/maxYandex/maxSaucenao 控制。",
                          "results.jsonl 为追加式结果；失败单列在 .failures.jsonl，不把 stdout 全量放进上下文。"])


# --------------------------------------------------------------------------- #
# research.image.search
# --------------------------------------------------------------------------- #

def _search_argv(ctx: ToolContext, payload: dict, directory: Path) -> tuple:
    script_name = payload.get("script")
    out = directory / (script_name + "-results.jsonl")
    if script_name == "lens":
        jobs = _input_path(ctx, payload.get("jobsPath"), "jobsPath")
        node = _node_binary(ctx.env)
        argv = [node, str(_script("lens_batch.mjs")), "--jobs", jobs, "--out", str(out)]
        if payload.get("limit") is not None:
            argv += ["--limit", str(payload["limit"])]
        if payload.get("maxAgeHours") is not None:
            argv += ["--max-age-hours", str(payload["maxAgeHours"])]
        return argv, out, "lens_batch.mjs"
    if script_name == "yandex":
        image = _input_path(ctx, payload.get("imagePath"), "imagePath")
        argv = _python() + [str(_script("yandex_cbir.py")), image, "--out", str(out)]
        if payload.get("maxAgeHours") is not None:
            argv += ["--max-age-hours", str(payload["maxAgeHours"])]
        return argv, out, "yandex_cbir.py"
    jobs = _input_path(ctx, payload.get("jobsPath"), "jobsPath")
    argv = _python() + [str(_script("saucenao_batch.py")), "--jobs", jobs, "--out", str(out)]
    if payload.get("limit") is not None:
        argv += ["--limit", str(payload["limit"])]
    if payload.get("maxAgeHours") is not None:
        argv += ["--max-age-hours", str(payload["maxAgeHours"])]
    return argv, out, "saucenao_batch.py"


def research_image_search(ctx: ToolContext, payload: dict) -> dict:
    script_name = payload.get("script")
    if script_name not in SEARCH_SCRIPTS:
        raise errors.invalid_input("script 必须是 " + "/".join(SEARCH_SCRIPTS))
    if script_name == "yandex":
        if payload.get("imagePath") is None:
            raise errors.invalid_input("yandex 需要 imagePath")
        if payload.get("jobsPath") is not None:
            raise errors.invalid_input("yandex 只接受 imagePath，不接受 jobsPath")
    else:
        if payload.get("jobsPath") is None:
            raise errors.invalid_input(script_name + " 需要 jobsPath")
        if payload.get("imagePath") is not None:
            raise errors.invalid_input(script_name + " 只接受 jobsPath，不接受 imagePath")
    directory = _output_dir(ctx, payload.get("outDir"), "image-search")
    argv, out, script_file = _search_argv(ctx, payload, directory)
    run = _execute(argv, env=_clean_env(ctx), timeout=_timeout(payload))
    return _result(run, script=script_file, args=argv[1:], artifact_paths=[out],
                   directory=directory,
                   notes=["Lens 优先、结果仅作线索；links/topTitles 为空不代表失败，也不能单独支撑作者核验。",
                          "限速/预算由脚本自身（Lens 间隔、SauceNAO 4 次/30s 与日上限）控制，不要并发重试。"])


# --------------------------------------------------------------------------- #
# research.author.probe
# --------------------------------------------------------------------------- #

def research_author_probe(ctx: ToolContext, payload: dict) -> dict:
    script_name = payload.get("script")
    if script_name not in AUTHOR_SCRIPTS:
        raise errors.invalid_input("script 必须是 " + "/".join(AUTHOR_SCRIPTS))
    directory = _output_dir(ctx, payload.get("outDir"), "author-probe")
    out = directory / (script_name + "-result.json")
    if script_name == "fxtwitter":
        target = payload.get("target")
        if not isinstance(target, str) or not target.strip() or len(target) > 500:
            raise errors.invalid_input("fxtwitter 需要 target（handle/主页 URL/帖子 ID）")
        argv = _python() + [str(_script("fxtwitter_probe.py")), target.strip(),
                            "--out", str(out)]
        run = _execute(argv, env=_clean_env(ctx), timeout=_timeout(payload))
        return _result(run, script="fxtwitter_probe.py", args=argv[1:],
                       artifact_paths=[out], directory=directory,
                       notes=["平台字段（昵称/数字 ID/简介/头像 URL）来自 FxTwitter 公开接口。",
                              "昵称或头像相似只是弱线索，verified 需要主页与至少 1 条证据。"])
    service = payload.get("service")
    creator = payload.get("creator")
    if not isinstance(service, str) or not service.strip():
        raise errors.invalid_input("kemono 需要 service（如 patreon/fanbox）")
    if not isinstance(creator, str) or not creator.strip() or len(creator) > 200:
        raise errors.invalid_input("kemono 需要 creator（服务内作者 ID）")
    argv = _python() + [str(_script("kemono_probe.py")), "--service", service.strip(),
                        "--creator", creator.strip(), "--pretty"]
    run = _execute(argv, env=_clean_env(ctx), timeout=_timeout(payload))
    result_path = directory / "kemono-result.json"
    if run["exitCode"] == 0 and run["stdout"]:
        _write_log(directory, result_path.name, run["stdout"])
    return _result(run, script="kemono_probe.py", args=argv[1:],
                   artifact_paths=[result_path], directory=directory,
                   notes=["Kemono 只用于公开 API 元数据；不访问付费内容、不登录。",
                          "脚本不支持 --out 时把 stdout 存为 kemono-result.json 工件（有界捕获）。"])


# --------------------------------------------------------------------------- #
# research.media.probe
# --------------------------------------------------------------------------- #

def research_media_probe(ctx: ToolContext, payload: dict) -> dict:
    files = payload.get("files")
    file_refs = payload.get("fileRefs")
    if files is None:
        files = []
    if file_refs is None:
        file_refs = []
    if not isinstance(files, list):
        raise errors.invalid_input("files 必须是数组")
    if not isinstance(file_refs, list):
        raise errors.invalid_input("fileRefs 必须是数组")
    if not files and not file_refs:
        raise errors.invalid_input("files 或 fileRefs 必须提供非空数组")
    if len(files) + len(file_refs) > MAX_MEDIA_FILES:
        raise errors.invalid_input(f"files+fileRefs 最多 {MAX_MEDIA_FILES} 项")
    resolved = []
    placeholders: dict[str, str] = {}
    for index, item in enumerate(files, 1):
        path = _input_path(ctx, item, "files[]")
        resolved.append(path)
        placeholders.setdefault(path, f"<media:{index}>")
    for index, item in enumerate(file_refs, 1):
        path = str(fileref.resolve_input(None, item, env=ctx.env, mode="read",
                                         kind="fileRefs[]"))
        resolved.append(path)
        placeholders.setdefault(path, f"<fileRef:{index}>")
    directory = _output_dir(ctx, None, "media-probe")
    out = directory / "media-probe.jsonl"
    if payload.get("frameDir") is None:
        frame_dir = pathguard.ensure_directory(directory / "frames")
    else:
        frame_dir = pathguard.ensure_directory(
            pathguard.resolve_local_path(payload.get("frameDir"), env=ctx.env,
                                         kind="frameDir", mode="write"))
    argv = _python() + [str(_script("media_probe.py"))] + resolved + [
        "--out", str(out), "--frame-dir", str(frame_dir)]
    frames = payload.get("frames")
    if frames is not None:
        if not isinstance(frames, str) or not _FRAMES_RE.match(frames):
            raise errors.invalid_input("frames 必须是逗号分隔的百分比，如 15,50,85")
        argv += ["--frames", frames]
    run = _execute(argv, env=_clean_env(ctx), timeout=_timeout(payload))
    artifact_paths = [out] + _directory_artifacts(frame_dir, limit=MAX_ARTIFACTS - 2)
    display_args = argv[1:]
    notes = ["帧抽到 <frameDir>/frames（默认工作根），只作识图输入，不入媒体库。",
             "缺少 ffmpeg/ffprobe 时脚本以退出码 2 报环境错误；不要把它当识别结果。"]
    if file_refs:
        # fileRef 输入的隐私收益：展示用 args 把已解析媒体路径替换成占位符，
        # 不替换脚本/输出目录/抽帧目录等执行元数据。
        display_args = [placeholders.get(item, item) for item in argv[1:]]
        notes.append("fileRef 输入不会把物理路径放进结果；args 中媒体路径以 <fileRef:N>/<media:N> 显示。")
    return _result(run, script="media_probe.py", args=display_args,
                   artifact_paths=artifact_paths, directory=directory, notes=notes)


# --------------------------------------------------------------------------- #
# research.avatar.fetch
# --------------------------------------------------------------------------- #

def research_avatar_fetch(ctx: ToolContext, payload: dict) -> dict:
    urls = payload.get("urls")
    jobs = payload.get("jobsPath")
    if urls is None and jobs is None:
        raise errors.invalid_input("urls 与 jobsPath 至少提供一个")
    if urls is not None and jobs is not None:
        raise errors.invalid_input("urls 与 jobsPath 不能同时使用")
    directory = _output_dir(ctx, payload.get("outDir"), "avatar-fetch", required=True)
    manifest = directory / "manifest.jsonl"
    if jobs is not None:
        argv = _python() + [str(_script("avatar_fetch.py")), "--jobs",
                            _input_path(ctx, jobs, "jobsPath")]
    else:
        if not isinstance(urls, list) or not urls:
            raise errors.invalid_input("urls 必须是非空数组")
        if len(urls) > MAX_AVATAR_URLS:
            raise errors.invalid_input(f"urls 最多 {MAX_AVATAR_URLS} 项")
        argv = _python() + [str(_script("avatar_fetch.py"))]
        for position, url in enumerate(urls):
            if not isinstance(url, str) or not url.lower().startswith(("http://", "https://")):
                raise errors.invalid_input(f"urls[{position}] 必须是 http(s) URL")
            argv += ["--url", url]
    argv += ["--out-dir", str(directory), "--manifest", str(manifest)]
    run = _execute(argv, env=_clean_env(ctx), timeout=_timeout(payload))
    artifact_paths = [manifest] + _directory_artifacts(directory, limit=MAX_ARTIFACTS - 1)
    return _result(run, script="avatar_fetch.py", args=argv[1:],
                   artifact_paths=artifact_paths, directory=directory,
                   notes=["头像只下载到显式 outDir；尺寸/格式/摘要以 manifest.jsonl 为准。",
                          "不把头像 URL 或字节写进仓库；真实作者资料只放忽略目录。"])
