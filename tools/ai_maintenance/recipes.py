"""可执行 recipe：任务卡、能力核对、逐步执行、断点续跑与产物落盘。

设计（2026-11-10，AI API 统一编排层）：
- recipe 是 JSON 数据，只引用已注册工具或本模块原语；不内嵌命令、不复制算法。
- 默认上下文只返回任务卡（约 2–4KB）；执行到某步才加载该工具 Schema。
- 每步产物写入运行目录（JSON + sha256），模型只拿到有界摘要与下一步。
- 写入步骤必须经过 gate + `--yes`；工具自身仍保留 confirm/plan 指纹校验。
- target（pc/android-embedded/nas）能力先核对；缺 ffmpeg/adb 等直接拒绝并给
  环境变量提示，不跑到一半才失败。
- 新增编排 = 新增 recipes/*.json（必要时加一个原语），不改 CLI/MCP 入口。

用法：
  python -X utf8 tools/ai_maintenance/cli.py task author.discover
  python -X utf8 tools/ai_maintenance/cli.py run author.discover --input-file in.json
  python -X utf8 tools/ai_maintenance/cli.py run author.discover --state-dir runs/xxx --yes
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import shutil
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from . import (device_adb, errors, execution, fileref, media_primitives,
               pathguard, plans, registry, schema, targets)

RECIPE_SCHEMA_VERSION = 1
RECIPE_ENGINE_VERSION = "1.0.0"
RECIPE_DIR = Path(__file__).resolve().parent / "recipes"
MAX_SUMMARY_CHARS = 2000
MAX_STEP_OUTPUT_BYTES = 8 << 20
_ID_RE = re.compile(r"^[a-z][a-z0-9_.-]{1,80}$")
_STEP_ID_RE = re.compile(r"^[a-z][A-Za-z0-9_-]{0,60}$")
_KNOWN_CAPABILITIES = set(targets.CAPABILITY_HINTS)


class RecipeError(RuntimeError):
    def __init__(self, message: str, *, code: str = "RECIPE_ERROR", details=None):
        super().__init__(message)
        self.code = code
        self.details = details or {}


class RecipeInputError(RecipeError):
    def __init__(self, message: str, details=None):
        super().__init__(message, code="RECIPE_INPUT_INVALID", details=details)


# --------------------------------------------------------------------------- #
# 原语
# --------------------------------------------------------------------------- #

def _ctx_env(ctx: "RunContext") -> dict:
    """原语使用的路径环境；直接构造 RunContext({}, dir) 时只授权该 run 目录。"""
    return ctx.path_env


def _input_path(payload: dict, ctx: "RunContext", *, path_key: str = "path",
                ref_key: str = "fileRef", kind: str | None = None,
                mode: str = "read", must_exist: bool = True) -> Path:
    return fileref.resolve_input(
        payload.get(path_key), payload.get(ref_key), env=_ctx_env(ctx), mode=mode,
        must_exist=must_exist, kind=kind or path_key)


def _local_file(payload: dict, key: str, ctx: "RunContext", *,
                ref_key: str = "") -> Path:
    return _input_path(payload, ctx, path_key=key, ref_key=ref_key or (key + "Ref"),
                       kind=key, mode="read", must_exist=True)


def _local_dir(payload: dict, key: str, ctx: "RunContext") -> Path:
    return pathguard.resolve_local_path(payload.get(key), env=_ctx_env(ctx),
                                        must_exist=False, kind=key, mode="write")


def _primitive_media_scan(payload: dict, ctx: "RunContext") -> dict:
    """本地内容扫描（只读）；不需要 API，供 media.batch 的第一步使用。"""
    from . import media_scan
    scan = payload.get("scan")
    if not isinstance(scan, dict):
        raise RecipeInputError("media.scan 需要 scan 对象")
    if scan.get("libraryId"):
        raise RecipeInputError(
            "media.scan 只接受 root；库根扫描需要 API，请直接调用 media.organize.prepare")
    plan = media_scan.build_scan_plan({"scan": scan}, env=_ctx_env(ctx))
    return {"plan": plan}


def _primitive_media_probe(payload: dict, ctx: "RunContext") -> dict:
    path_value = payload.get("path")
    ref_value = payload.get("fileRef")
    source = _input_path(payload, ctx, kind="媒体文件")
    extension = source.suffix.lower()
    kind = str(payload.get("kind") or "")
    if not kind:
        kind = "video" if extension in {".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v",
                                        ".wmv", ".flv", ".mpg", ".mpeg", ".ts"} else "image"
    if kind not in ("image", "video"):
        raise RecipeInputError("kind 只能是 image/video")
    record = media_primitives.perceptual_record(source, kind, env=_ctx_env(ctx))
    result = {"kind": kind, **record}
    if ref_value not in (None, "") and path_value in (None, ""):
        # 只回逻辑引用，不把物理绝对路径放进模型上下文。
        result["fileRef"] = ref_value
        result["name"] = source.name
    else:
        result["path"] = str(source)
    if payload.get("frames"):
        out_dir = pathguard.resolve_local_path(
            payload.get("frameDir") or str(ctx.run_dir / "frames" / ctx.current_step),
            env=_ctx_env(ctx), must_exist=False, kind="frameDir", mode="write")
        percentages = media_primitives.parse_percentages(payload.get("frames"))
        result["frameFiles"] = media_primitives.extract_frames(
            source, out_dir, percentages=percentages, kind=kind, env=_ctx_env(ctx))
    return result


def _primitive_media_fingerprint(payload: dict, ctx: "RunContext") -> dict:
    path_value = payload.get("path")
    ref_value = payload.get("fileRef")
    source = _input_path(payload, ctx, kind="文件")
    algorithms = payload.get("algorithms") or ["md5", "sha256", "dhash64"]
    if not isinstance(algorithms, list) or not algorithms:
        raise RecipeInputError("algorithms 必须是非空数组")
    root = pathguard.root_for_path(source, mode="read", env=_ctx_env(ctx))
    result = {"bytes": source.stat().st_size}
    if ref_value not in (None, "") and path_value in (None, ""):
        result["fileRef"] = ref_value
        result["name"] = source.name
    else:
        result["path"] = str(source)
    if "md5" in algorithms:
        result["md5"], _ = pathguard.md5_file(source, root=root)
    if "sha256" in algorithms:
        result["sha256"], _ = pathguard.sha256_file(source, root=root)
    if "dhash64" in algorithms:
        record = media_primitives.perceptual_record(source, "image", env=_ctx_env(ctx))
        result["dhash64"] = (record.get("frames") or [None])[0]
        result["dhashStatus"] = record.get("status")
    return result


def _primitive_media_contact_sheet(payload: dict, ctx: "RunContext") -> dict:
    out_dir = _local_dir(payload, "outDir", ctx)
    out_dir.mkdir(parents=True, exist_ok=True)
    columns = int(payload.get("columns") or 4)
    title = str(payload.get("title") or "")
    sheets = []
    entries = payload.get("entries")
    if isinstance(entries, list) and entries:
        normalized = []
        for index, entry in enumerate(entries, 1):
            if not isinstance(entry, dict):
                raise RecipeInputError("entries 项必须是对象")
            source = _input_path(entry, ctx, path_key="path", ref_key="fileRef",
                                 kind="entries.path", mode="read", must_exist=True)
            normalized.append({"path": str(source), "label": entry.get("label") or source.name})
        target = out_dir / (str(payload.get("name") or "contact-sheet") + ".png")
        result = media_primitives.build_contact_sheet(normalized, target, columns=columns,
                                                      title=title, env=_ctx_env(ctx))
        return {"status": "ok", "sheets": result.get("sheets") or []}
    root_value = payload.get("root")
    group_sheets = payload.get("sheets")
    if not isinstance(group_sheets, list) or not group_sheets:
        raise RecipeInputError("需要 entries，或 root+sheets（来自 media.organize.prepare 的 contactSheets）")
    root = pathguard.resolve_media_path(root_value, env=_ctx_env(ctx),
                                        kind="contactSheet.root", mode="read")
    for sheet in group_sheets:
        if not isinstance(sheet, dict):
            continue
        files = sheet.get("files") if isinstance(sheet.get("files"), list) else []
        normalized = []
        for item in files:
            rel = item.get("relPath") if isinstance(item, dict) else None
            if not isinstance(rel, str):
                continue
            source = (root / rel).resolve()
            try:
                source.relative_to(root)
            except ValueError:
                raise RecipeInputError("contactSheet.relPath 越出扫描根") from None
            pathguard.authorize_existing(source, mode="read", env=_ctx_env(ctx),
                                         kind="contactSheet.relPath")
            if not source.is_file():
                continue
            normalized.append({"path": str(source),
                               "label": f"{sheet.get('label') or ''} #{item.get('order') or ''} "
                                        f"{source.name}".strip()})
        if not normalized:
            continue
        name = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff._-]+", "_", str(sheet.get("label") or "sheet"))[:60]
        target = out_dir / (name + ".png")
        result = media_primitives.build_contact_sheet(normalized, target, columns=columns,
                                                      title=str(sheet.get("label") or ""),
                                                      env=_ctx_env(ctx))
        sheets.extend(result.get("sheets") or [])
    return {"status": "ok" if sheets else "empty", "sheets": sheets}


def _primitive_device_info(payload: dict, ctx: "RunContext") -> dict:
    return {"status": "ok", **device_adb.device_info(env=ctx.env)}


def _primitive_device_push(payload: dict, ctx: "RunContext") -> dict:
    remote_dir = payload.get("remoteDir")
    if not isinstance(remote_dir, str) or not remote_dir.startswith("/"):
        raise RecipeInputError("remoteDir 必须是设备上的绝对 POSIX 路径")
    local_paths = payload.get("localPaths")
    if local_paths is None and payload.get("localPath"):
        local_paths = [payload["localPath"]]
    if not isinstance(local_paths, list) or not local_paths:
        raise RecipeInputError("localPath 或 localPaths 必填")
    names = payload.get("names") if isinstance(payload.get("names"), list) else []
    items = []
    for index, value in enumerate(local_paths):
        local = pathguard.resolve_local_path(value, env=_ctx_env(ctx), must_exist=True,
                                             kind="localPaths", mode="read")
        name = names[index] if index < len(names) else None
        items.append((str(local), name))
    return device_adb.push_many(items, remote_dir, env=_ctx_env(ctx))


def _primitive_device_pull(payload: dict, ctx: "RunContext") -> dict:
    remote = payload.get("remotePath")
    if not isinstance(remote, str) or not remote.startswith("/"):
        raise RecipeInputError("remotePath 必须是设备上的绝对 POSIX 路径")
    # 本地落盘目标是写操作：必须落在显式写授权根内。
    local = pathguard.resolve_local_path(payload.get("localPath"), env=_ctx_env(ctx),
                                         must_exist=False, kind="localPath", mode="write")
    return device_adb.pull_file(remote, str(local), env=_ctx_env(ctx))


def _primitive_file_hash(payload: dict, ctx: "RunContext") -> dict:
    return _primitive_media_fingerprint(payload, ctx)


def _transfer_target(item: dict, index: int, ctx: "RunContext") -> Path:
    target_value = item.get("target")
    target_ref = item.get("targetFileRef")
    if target_value not in (None, "") and target_ref not in (None, ""):
        raise RecipeInputError(f"items[{index}] 不能同时提供 target 与 targetFileRef")
    if target_ref not in (None, ""):
        return fileref.resolve_ref(target_ref, env=_ctx_env(ctx), mode="write")
    return pathguard.resolve_local_path(target_value, env=_ctx_env(ctx),
                                        must_exist=False, mode="write",
                                        kind=f"items[{index}].target")


def _same_file_content(source: Path, target: Path, ctx: "RunContext") -> bool:
    if not target.is_file():
        return False
    try:
        if source.stat().st_size != target.stat().st_size:
            return False
        source_root = pathguard.root_for_path(source, mode="read", env=_ctx_env(ctx))
        target_root = pathguard.root_for_path(target, mode="write", env=_ctx_env(ctx))
        source_hash, _ = pathguard.sha256_file(source, root=source_root)
        target_hash, _ = pathguard.sha256_file(target, root=target_root)
    except (OSError, errors.ToolError):
        return False
    return source_hash == target_hash


def _copy_exclusive(source: Path, target: Path, *, overwrite: bool) -> None:
    """复制并保留元数据；默认排他创建，避免检查后被其他进程覆盖。

    非覆盖模式下若复制中途失败，只删除本次刚创建的目标半成品；已存在文件
    与源文件绝不删除。overwrite=True 的失败不自动删除，避免误删原文件。
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    flags = os.O_WRONLY | os.O_CREAT | getattr(os, "O_BINARY", 0)
    flags |= os.O_TRUNC if overwrite else os.O_EXCL
    descriptor = os.open(target, flags, 0o666)
    try:
        with os.fdopen(descriptor, "wb") as output, open(source, "rb") as source_handle:
            shutil.copyfileobj(source_handle, output, 1 << 20)
    except BaseException:
        if not overwrite:
            try:
                os.unlink(target)
            except OSError:
                pass
        raise
    try:
        shutil.copystat(source, target)
    except OSError:
        pass


def _remove_created_target(target: Path) -> None:
    """只删除本次排他创建的目标半成品；调用方保证不是已存在文件。"""
    try:
        os.unlink(target)
    except OSError:
        pass


def _primitive_media_transfer(payload: dict, ctx: "RunContext") -> dict:
    """按显式 items 复制文件到目标路径；只复制不删除，默认拒绝覆盖。

    `allowIdenticalReuse=True` 仅由 recipe 恢复执行注入：目标已存在且 sha256
    与源一致时记为 alreadyPresent 跳过；内容不一致仍 TARGET_EXISTS。
    """
    items = payload.get("items")
    if not isinstance(items, list) or not items:
        raise RecipeInputError("media.transfer 需要非空 items")
    overwrite = bool(payload.get("overwrite"))
    allow_identical_reuse = bool(payload.get("allowIdenticalReuse"))
    completed = []
    failed = []
    total = 0
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            raise RecipeInputError(f"items[{index}] 必须是对象")
        source = fileref.resolve_input(
            item.get("source"), item.get("sourceFileRef"), env=_ctx_env(ctx),
            mode="read", must_exist=True, kind=f"items[{index}].source")
        target = _transfer_target(item, index, ctx)
        if target.exists() and not overwrite:
            if allow_identical_reuse and _same_file_content(source, target, ctx):
                completed.append({"source": str(source), "target": str(target),
                                  "bytes": target.stat().st_size,
                                  "sha256": pathguard.sha256_file(
                                      target, root=pathguard.root_for_path(
                                          target, mode="write", env=_ctx_env(ctx)))[0],
                                  "alreadyPresent": True})
                continue
            failed.append({"source": str(source), "target": str(target),
                           "code": "TARGET_EXISTS"})
            continue
        try:
            _copy_exclusive(source, target, overwrite=overwrite)
        except FileExistsError:
            if allow_identical_reuse and _same_file_content(source, target, ctx):
                completed.append({"source": str(source), "target": str(target),
                                  "bytes": target.stat().st_size,
                                  "sha256": pathguard.sha256_file(
                                      target, root=pathguard.root_for_path(
                                          target, mode="write", env=_ctx_env(ctx)))[0],
                                  "alreadyPresent": True})
                continue
            failed.append({"source": str(source), "target": str(target),
                           "code": "TARGET_EXISTS"})
            continue
        except OSError as exc:
            failed.append({"source": str(source), "target": str(target),
                           "code": "COPY_FAILED", "message": type(exc).__name__})
            continue
        # 复制后再次校验：目标仍必须在授权写根内，且大小/hash 与源一致。
        try:
            pathguard.authorize_existing(target, mode="write", env=_ctx_env(ctx),
                                         kind=f"items[{index}].target")
            target_root = pathguard.root_for_path(target, mode="write", env=_ctx_env(ctx))
            source_root = pathguard.root_for_path(source, mode="read", env=_ctx_env(ctx))
            expected = source.stat().st_size
            actual = target.stat().st_size
            source_hash, _ = pathguard.sha256_file(source, root=source_root)
            target_hash, _ = pathguard.sha256_file(target, root=target_root)
        except (OSError, errors.ToolError) as exc:
            if not overwrite:
                _remove_created_target(target)
            failed.append({"source": str(source), "target": str(target),
                           "code": "VERIFY_MISMATCH", "message": type(exc).__name__})
            continue
        if expected != actual or source_hash != target_hash:
            if not overwrite:
                _remove_created_target(target)
            failed.append({"source": str(source), "target": str(target),
                           "code": "VERIFY_MISMATCH",
                           "expectedBytes": expected, "actualBytes": actual})
            continue
        total += actual
        completed.append({"source": str(source), "target": str(target),
                          "bytes": actual, "sha256": target_hash})
    return {"status": "ok" if not failed else "partial",
            "copied": completed, "failed": failed, "bytes": total,
            "alreadyPresent": sum(1 for item in completed if item.get("alreadyPresent"))}


def _primitive_select_first(payload: dict, ctx: "RunContext") -> dict:
    values = payload.get("values")
    if not isinstance(values, list) or not values:
        raise RecipeInputError("select.first 需要非空 values")
    for value in values:
        if value not in (None, "", [], {}):
            return {"value": value}
    return {"value": None}


PRIMITIVES = {
    "media.scan": _primitive_media_scan,
    "media.probe": _primitive_media_probe,
    "media.fingerprint": _primitive_media_fingerprint,
    "media.contact_sheet": _primitive_media_contact_sheet,
    "media.transfer": _primitive_media_transfer,
    "file.hash": _primitive_file_hash,
    "device.info": _primitive_device_info,
    "device.push": _primitive_device_push,
    "device.pull": _primitive_device_pull,
    "select.first": _primitive_select_first,
}

@dataclass(frozen=True)
class PrimitiveSpec:
    """原语的执行策略声明（不是 ToolSpec，不注册进工具注册表）。"""

    id: str
    version: str = "1.0.0"
    description: str = ""
    permission: str = "read"
    read_only: bool = True
    side_effects: bool = False
    requires_confirmation: bool = False
    timeout_seconds: float = 300.0
    runtime_checks: tuple = ()


PRIMITIVE_SPECS: dict[str, PrimitiveSpec] = {
    "media.scan": PrimitiveSpec(
        "media.scan", description="本地内容扫描（只读）：md5/dHash64、去重候选、序号建议、对齐与拼图计划",
        permission="read", read_only=True, side_effects=False,
        runtime_checks=("扫描根必须在 QIMENG_AI_MEDIA_ROOTS/读授权根内", "跳过符号链接/junction"),
    ),
    "media.probe": PrimitiveSpec(
        "media.probe", description="ffprobe 元数据 + dHash64；可选按百分比抽帧（缓存）",
        permission="read", read_only=True, side_effects=False,
        runtime_checks=("输入文件只读；fileRef 或 path 二选一",),
    ),
    "media.fingerprint": PrimitiveSpec(
        "media.fingerprint", description="md5/sha256/dHash64 内容指纹（缓存）",
        permission="read", read_only=True, side_effects=False,
    ),
    "media.contact_sheet": PrimitiveSpec(
        "media.contact_sheet", description="按组生成带标签拼图，供人工目检",
        permission="local.files.write", read_only=False, side_effects=True,
        requires_confirmation=False,
        runtime_checks=("输出目录必须是写授权根", "entries 文件只读"),
    ),
    "media.transfer": PrimitiveSpec(
        "media.transfer", description="按显式 items 复制文件到目标路径，sha256 回读；默认拒绝覆盖",
        permission="local.files.write", read_only=False, side_effects=True,
        requires_confirmation=True,
        runtime_checks=("源只读、目标写授权；默认排他创建", "恢复时目标一致可 alreadyPresent 跳过"),
    ),
    "file.hash": PrimitiveSpec(
        "file.hash", description="文件 md5/sha256/dHash64",
        permission="read", read_only=True, side_effects=False,
    ),
    "device.info": PrimitiveSpec(
        "device.info", description="读取已显式指定真机的型号/Android 版本",
        permission="read", read_only=True, side_effects=False,
    ),
    "device.push": PrimitiveSpec(
        "device.push", description="逐文件 adb push 并回读字节数",
        permission="local.device", read_only=False, side_effects=True,
        requires_confirmation=True,
        runtime_checks=("必须显式 QIMENG_ADB_SERIAL，拒绝默认模拟器", "本地源只读"),
    ),
    "device.pull": PrimitiveSpec(
        "device.pull", description="adb pull 并回读本地字节数",
        permission="local.device", read_only=False, side_effects=True,
        requires_confirmation=True,
        runtime_checks=("本地目标是写授权根", "必须显式 QIMENG_ADB_SERIAL"),
    ),
    "select.first": PrimitiveSpec(
        "select.first", description="取第一个非空值（用于已有 ID / 新建 ID / 查重命中的优先级选择）",
        permission="read", read_only=True, side_effects=False,
    ),
}

PRIMITIVE_SUMMARY = {key: spec.description for key, spec in PRIMITIVE_SPECS.items()}


# --------------------------------------------------------------------------- #
# 加载与校验
# --------------------------------------------------------------------------- #

def _read_json(path: Path) -> dict:
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except OSError:
        raise RecipeError("无法读取 recipe 文件", code="RECIPE_IO", details={"name": path.name}) from None
    except ValueError as exc:
        raise RecipeError("recipe 不是合法 JSON", code="RECIPE_INVALID",
                          details={"name": path.name, "error": str(exc)}) from None


def recipe_dir(env=None) -> Path:
    override = (env or {}).get("QIMENG_AI_RECIPE_DIR")
    return Path(override).resolve() if override else RECIPE_DIR


def list_recipes(env=None) -> list[dict]:
    directory = recipe_dir(env)
    if not directory.is_dir():
        return []
    items = []
    for path in sorted(directory.glob("*.json")):
        try:
            recipe = validate_recipe(_read_json(path))
        except RecipeError as exc:
            items.append({"id": path.stem, "status": "invalid", "error": str(exc),
                          "code": exc.code})
            continue
        items.append({"id": recipe["id"], "version": recipe["version"],
                      "title": recipe["title"], "whenToUse": recipe["whenToUse"],
                      "targets": recipe.get("targets") or [],
                      "requires": recipe.get("requires") or [],
                      "status": "ok"})
    return items


def get_recipe(recipe_id: str, env=None) -> dict:
    if not isinstance(recipe_id, str) or not _ID_RE.match(recipe_id):
        raise RecipeError("recipe id 非法", code="RECIPE_INVALID")
    path = recipe_dir(env) / (recipe_id + ".json")
    if not path.is_file():
        # 允许按文件名（含连字符）查找，id 仍是权威。
        matches = [item for item in recipe_dir(env).glob("*.json")
                   if item.stem == recipe_id]
        if not matches:
            raise RecipeError("未知 recipe：" + recipe_id, code="RECIPE_UNKNOWN",
                              details={"available": [item["id"] for item in list_recipes(env)
                                                     if item.get("status") == "ok"]})
        path = matches[0]
    return validate_recipe(_read_json(path))


def _collect_step_refs(value) -> list[str]:
    references = []
    if isinstance(value, dict):
        for item in value.values():
            references.extend(_collect_step_refs(item))
    elif isinstance(value, list):
        for item in value:
            references.extend(_collect_step_refs(item))
    elif isinstance(value, str) and value.startswith("$steps."):
        rest = value[len("$steps."):]
        step_id = rest.partition(".")[0]
        if step_id:
            references.append(step_id)
    return references


def _fill_defaults(inputs: dict, input_schema: dict) -> dict:
    result = dict(inputs)
    properties = input_schema.get("properties") if isinstance(input_schema, dict) else None
    if isinstance(properties, dict):
        for name, spec in properties.items():
            if name not in result:
                result[name] = spec.get("default") if isinstance(spec, dict) else None
    return result


def validate_recipe(recipe: dict) -> dict:
    if not isinstance(recipe, dict):
        raise RecipeError("recipe 必须是对象", code="RECIPE_INVALID")
    for key in ("schemaVersion", "id", "version", "title", "whenToUse", "inputs", "steps"):
        if key not in recipe:
            raise RecipeError("recipe 缺少字段：" + key, code="RECIPE_INVALID")
    if recipe["schemaVersion"] != RECIPE_SCHEMA_VERSION:
        raise RecipeError("不支持的 recipe schemaVersion", code="RECIPE_INVALID",
                          details={"expected": RECIPE_SCHEMA_VERSION})
    if not isinstance(recipe["id"], str) or not _ID_RE.match(recipe["id"]):
        raise RecipeError("recipe.id 非法", code="RECIPE_INVALID")
    if not isinstance(recipe["inputs"], dict) or recipe["inputs"].get("type") != "object":
        raise RecipeError("recipe.inputs 必须是 JSON object schema", code="RECIPE_INVALID")
    steps = recipe["steps"]
    if not isinstance(steps, list) or not steps:
        raise RecipeError("recipe.steps 必须是非空数组", code="RECIPE_INVALID")
    seen = set()
    for position, step in enumerate(steps):
        if not isinstance(step, dict):
            raise RecipeError(f"steps[{position}] 必须是对象", code="RECIPE_INVALID")
        step_id = step.get("id")
        if not isinstance(step_id, str) or not _STEP_ID_RE.match(step_id):
            raise RecipeError(f"steps[{position}].id 非法", code="RECIPE_INVALID")
        if step_id in seen:
            raise RecipeError("重复的 step id：" + step_id, code="RECIPE_INVALID")
        seen.add(step_id)
        forms = [key for key in ("tool", "primitive", "gate") if step.get(key)]
        if len(forms) != 1:
            raise RecipeError(f"step {step_id} 必须且只能有 tool/primitive/gate 之一",
                              code="RECIPE_INVALID")
        if "tool" in forms:
            spec = registry.get(step["tool"])
            if spec is None:
                raise RecipeError(f"step {step_id} 引用了未注册工具：{step['tool']}",
                                  code="RECIPE_INVALID")
            if spec.planned:
                raise RecipeError(f"step {step_id} 引用了 planned 工具：{step['tool']}",
                                  code="RECIPE_INVALID")
        if "primitive" in forms and step["primitive"] not in PRIMITIVES:
            raise RecipeError(f"step {step_id} 引用了未知原语：{step['primitive']}",
                              code="RECIPE_INVALID")
        condition = step.get("when")
        if condition is not None:
            _validate_condition(condition, step_id)
    declared = {step["id"] for step in steps}
    for step in steps:
        for reference in _collect_step_refs(step.get("input")):
            if reference not in declared:
                raise RecipeError(
                    f"step {step['id']} 引用了不存在的步骤：{reference}",
                    code="RECIPE_INVALID",
                )
        for reference in _collect_step_refs(step.get("when")):
            if reference not in declared:
                raise RecipeError(
                    f"step {step['id']}.when 引用了不存在的步骤：{reference}",
                    code="RECIPE_INVALID",
                )
    for capability in recipe.get("requires") or []:
        if capability not in _KNOWN_CAPABILITIES:
            raise RecipeError("未知能力声明：" + str(capability), code="RECIPE_INVALID")
    for target_name in recipe.get("targets") or []:
        if target_name not in targets.TARGETS:
            raise RecipeError("未知 target：" + str(target_name), code="RECIPE_INVALID")
    for permission in recipe.get("permissions") or []:
        if permission not in _known_permissions():
            raise RecipeError("未知权限声明：" + str(permission), code="RECIPE_INVALID")
    return recipe


def _known_permissions() -> frozenset[str]:
    return (frozenset(registry.PERMISSIONS.keys())
            | frozenset(spec.permission for spec in PRIMITIVE_SPECS.values()))


def _required_step_permissions(recipe: dict) -> dict[str, list[str]]:
    """收集 recipe 每个 tool/primitive 步骤所需权限（gate 不占权限）。"""
    required: dict[str, list[str]] = {}
    for step in recipe["steps"]:
        permission = ""
        if "tool" in step:
            spec = registry.get(step["tool"])
            if spec is not None:
                permission = spec.permission
        elif "primitive" in step:
            primitive = PRIMITIVE_SPECS.get(step["primitive"])
            if primitive is not None:
                permission = primitive.permission
        if permission:
            required.setdefault(permission, []).append(step["id"])
    return required


def _validate_condition(condition, step_id: str) -> None:
    if isinstance(condition, str):
        if not condition.startswith("$"):
            raise RecipeError(f"step {step_id}.when 字符串必须以 $ 开头", code="RECIPE_INVALID")
        return
    if not isinstance(condition, dict) or len(condition) != 1:
        raise RecipeError(f"step {step_id}.when 必须是单键对象", code="RECIPE_INVALID")
    key, value = next(iter(condition.items()))
    if key in ("notEmpty", "empty", "truthy"):
        if not isinstance(value, str) or not value.startswith("$"):
            raise RecipeError(f"step {step_id}.when.{key} 必须是 $ 引用", code="RECIPE_INVALID")
        return
    if key in ("equals", "notEquals"):
        if not isinstance(value, list) or len(value) != 2:
            raise RecipeError(f"step {step_id}.when.{key} 需要两个值", code="RECIPE_INVALID")
        return
    if key in ("and", "or"):
        if not isinstance(value, list) or not value:
            raise RecipeError(f"step {step_id}.when.{key} 必须是非空数组", code="RECIPE_INVALID")
        for item in value:
            _validate_condition(item, step_id)
        return
    raise RecipeError(f"step {step_id}.when 不支持的键：{key}", code="RECIPE_INVALID")


# --------------------------------------------------------------------------- #
# 模板与条件
# --------------------------------------------------------------------------- #

class RunContext:
    def __init__(self, env, run_dir: Path, current_step: str = "",
                 capabilities=None, resuming: bool = False):
        self.env = env
        self.run_dir = run_dir
        self.current_step = current_step
        self.capabilities = dict(capabilities or {})
        self.resuming = bool(resuming)
        self._path_env = None

    @property
    def path_env(self):
        """路径授权环境。

        正常 recipe 运行传入的是进程环境（非空），按 QIMENG_AI_* 根严格校验。
        仅当调用方直接构造 `RunContext({}, some_dir)` 做单元级原语调用时，
        把该 run 目录作为唯一读写根，保持既有直接调用测试的兼容性；真实
        `run_recipe` 会把空 env 回落到 os.environ，不会进入这个分支。
        """
        if self._path_env is None:
            env = dict(self.env or {})
            if not env and self.run_dir is not None:
                root = str(Path(self.run_dir).resolve(strict=False))
                env["QIMENG_AI_READ_ROOTS"] = root
                env["QIMENG_AI_WRITE_ROOTS"] = root
            self._path_env = env
        return self._path_env


def _lookup(value, path: str):
    current = value
    for part in path.split("."):
        if part == "":
            continue
        if current is None:
            return None
        if isinstance(current, dict):
            if part not in current:
                raise RecipeError("模板引用不存在：" + path, code="RECIPE_TEMPLATE")
            current = current[part]
        elif isinstance(current, list):
            try:
                index = int(part)
            except ValueError:
                raise RecipeError("模板引用需要列表下标：" + path, code="RECIPE_TEMPLATE")
            if not 0 <= index < len(current):
                raise RecipeError("模板引用下标越界：" + path, code="RECIPE_TEMPLATE")
            current = current[index]
        else:
            raise RecipeError("模板引用无法继续解析：" + path, code="RECIPE_TEMPLATE")
    return current


def _resolve_reference(reference: str, inputs: dict, outputs: dict, ctx: RunContext):
    if reference.startswith("$inputs"):
        rest = reference[len("$inputs"):].lstrip(".")
        return _lookup(inputs, rest) if rest else inputs
    if reference.startswith("$steps."):
        rest = reference[len("$steps."):]
        step_id, separator, path = rest.partition(".output")
        if not separator:
            step_id, separator, path = rest.partition(".")
        # 条件跳过的步骤输出为空；select.first 等步骤据此回退，不把跳过当错误。
        if step_id not in outputs:
            return None
        output = outputs[step_id]
        path = path.lstrip(".")
        return _lookup(output, path) if path else output
    if reference.startswith("$run."):
        key = reference[len("$run."):]
        if key == "dir":
            return str(ctx.run_dir)
        if key == "artifacts":
            return str(ctx.run_dir / "artifacts")
        if key.startswith("capabilities."):
            return ctx.capabilities.get(key[len("capabilities."):])
        raise RecipeError("未知 $run 引用：" + key, code="RECIPE_TEMPLATE")
    raise RecipeError("未知模板引用：" + reference, code="RECIPE_TEMPLATE")


def resolve_value(value, inputs: dict, outputs: dict, ctx: RunContext):
    if isinstance(value, dict):
        return {key: resolve_value(item, inputs, outputs, ctx) for key, item in value.items()}
    if isinstance(value, list):
        return [resolve_value(item, inputs, outputs, ctx) for item in value]
    if isinstance(value, str) and value.startswith("$"):
        if value.startswith("$inputs") or value.startswith("$steps.") or value.startswith("$run."):
            return _resolve_reference(value, inputs, outputs, ctx)
        raise RecipeError("未知模板引用：" + value, code="RECIPE_TEMPLATE")
    return value


def evaluate_condition(condition, inputs: dict, outputs: dict, ctx: RunContext) -> bool:
    if isinstance(condition, str):
        return bool(_resolve_reference(condition, inputs, outputs, ctx))
    key, value = next(iter(condition.items()))
    if key == "notEmpty":
        return _resolve_reference(value, inputs, outputs, ctx) not in (None, "", [], {})
    if key == "empty":
        return _resolve_reference(value, inputs, outputs, ctx) in (None, "", [], {})
    if key == "truthy":
        return bool(_resolve_reference(value, inputs, outputs, ctx))
    if key == "equals":
        return resolve_value(value[0], inputs, outputs, ctx) == resolve_value(
            value[1], inputs, outputs, ctx)
    if key == "notEquals":
        return resolve_value(value[0], inputs, outputs, ctx) != resolve_value(
            value[1], inputs, outputs, ctx)
    if key == "and":
        return all(evaluate_condition(item, inputs, outputs, ctx) for item in value)
    if key == "or":
        return any(evaluate_condition(item, inputs, outputs, ctx) for item in value)
    raise RecipeError("未知条件：" + key, code="RECIPE_INVALID")


# --------------------------------------------------------------------------- #
# 状态与摘要
# --------------------------------------------------------------------------- #

def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _write_json_atomic(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp-" + str(os.getpid()))
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2, sort_keys=True)
    os.replace(temporary, path)


def _clip(value, limit: int = 300):
    if isinstance(value, str):
        return value if len(value) <= limit else value[:limit] + "…"
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    if isinstance(value, list):
        return [_clip(item, limit) for item in value[:3]] + (["…"] if len(value) > 3 else [])
    if isinstance(value, dict):
        return {key: _clip(item, limit) for key, item in list(value.items())[:12]}
    return str(value)[:limit]


def summarize_output(value) -> dict:
    if isinstance(value, list):
        return {"kind": "list", "count": len(value),
                "sample": [_pick_summary(item) for item in value[:3]]}
    if isinstance(value, dict):
        return _pick_summary(value)
    return {"kind": type(value).__name__, "value": _clip(value)}


def _pick_summary(value: dict) -> dict:
    keys = ("status", "summary", "message", "count", "matchCount", "items",
            "authorId", "id", "displayName", "revision", "plan", "diagnostics",
            "applied", "artifacts", "sheets", "pushed", "failed", "bytes",
            "frames", "dimensions", "durationMs", "dhashStatus", "missing",
            "capabilities", "target", "next")
    result = {}
    for key in keys:
        if key in value:
            result[key] = _clip(value[key])
    if not result:
        result = {key: _clip(item) for key, item in list(value.items())[:8]}
    encoded = json.dumps(result, ensure_ascii=False)
    if len(encoded) > MAX_SUMMARY_CHARS:
        return {"truncated": True, "chars": len(encoded),
                "preview": encoded[:MAX_SUMMARY_CHARS]}
    return result


def _artifact_name(step_id: str) -> str:
    return re.sub(r"[^0-9A-Za-z._-]+", "_", step_id) + ".json"


# --------------------------------------------------------------------------- #
# 执行
# --------------------------------------------------------------------------- #

def _new_run_dir(recipe_id: str, state_dir=None, env=None) -> tuple[Path, bool]:
    """返回 (运行目录, 是否需要把该目录登记为本次运行的写根)。

    显式 `--state-dir` 是操作者提供的运行目录（不是模型输入）：未配置授权根时
    允许使用，但会仅把该目录追加为本进程运行的写根；仓库源码树仍拒绝。
    """
    if state_dir:
        try:
            return pathguard.resolve_local_path(str(state_dir), env=env, must_exist=False,
                                                kind="stateDir", mode="write"), False
        except errors.ToolError as exc:
            if exc.code != "PATH_NOT_AUTHORIZED":
                raise
            candidate = Path(str(state_dir))
            if not candidate.is_absolute() or any(ord(ch) < 32 for ch in str(state_dir)):
                raise
            return candidate.resolve(strict=False), True
    stamp = time.strftime("%Y%m%d-%H%M%S")
    return (pathguard.BUILD_ROOT / "ai-maintenance" / "runs" /
            f"{recipe_id}-{stamp}-{secrets.token_hex(3)}"), False


def _env_with_root(env: dict, env_name: str, root: Path) -> dict:
    merged = dict(env)
    existing = merged.get(env_name, "")
    merged[env_name] = (existing + ";" if existing else "") + str(root)
    return merged


def _load_state(run_dir: Path) -> dict | None:
    path = run_dir / "state.json"
    if not path.is_file():
        return None
    try:
        with path.open("r", encoding="utf-8") as handle:
            state = json.load(handle)
    except (OSError, ValueError) as exc:
        raise RecipeError("运行状态不可读：" + str(exc), code="RECIPE_STATE") from None
    if not isinstance(state, dict):
        raise RecipeError("运行状态不是对象", code="RECIPE_STATE")
    return state


def _load_output(run_dir: Path, step: dict) -> Any:
    artifact = step.get("artifact")
    if not artifact:
        return None
    path = run_dir / "artifacts" / artifact
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        raise RecipeError("步骤产物不可读：" + str(artifact), code="RECIPE_STATE") from None


def _tool_output(result):
    # execution.execute_tool 返回 (spec, result)
    if isinstance(result, tuple) and len(result) == 2:
        return result[1]
    return result


def _run_step(step: dict, recipe: dict, ctx: RunContext, inputs: dict,
              outputs: dict, config, confirm: bool,
              gate_confirmed: bool = False) -> Any:
    if step.get("gate"):
        if not confirm:
            raise RecipeError(
                step.get("message") or "需要人工确认后才能继续",
                code="RECIPE_CONFIRMATION_REQUIRED",
                details={"step": step["id"], "gate": step.get("message", "")},
            )
        return {"status": "confirmed", "step": step["id"]}
    payload = resolve_value(step.get("input") or {}, inputs, outputs, ctx)
    if not isinstance(payload, dict):
        raise RecipeError("步骤输入解析后不是对象", code="RECIPE_INVALID")
    if "tool" in step:
        if step.get("confirm") and confirm:
            payload = dict(payload)
            payload["confirm"] = True
        _spec, result = execution.execute_tool(step["tool"], payload, env=ctx.env,
                                               config=config)
        output = _tool_output(result)
        if isinstance(output, dict) and output.get("status") in ("failed", "partial"):
            raise RecipeError(
                "步骤返回失败状态，停止后续写入",
                code="RECIPE_STEP_FAILED",
                details={"step": step["id"], "status": output.get("status"),
                         "message": output.get("summary") or output.get("message")},
            )
        return output
    primitive_spec = PRIMITIVE_SPECS[step["primitive"]]
    denial = execution.policy_denial(primitive_spec, config)
    if denial:
        denied = execution.policy_denied_error(primitive_spec, config)
        if denied is not None:
            raise denied
        raise errors.tool_disabled(primitive_spec.id, denial)
    if primitive_spec.side_effects and primitive_spec.requires_confirmation \
            and not gate_confirmed:
        raise RecipeError(
            f"原语 {primitive_spec.id} 有副作用，必须先通过人工门禁",
            code="RECIPE_CONFIRMATION_REQUIRED",
            details={"step": step["id"], "primitive": primitive_spec.id},
        )
    if ctx.resuming and step["primitive"] == "media.transfer":
        # 恢复执行时允许“目标已存在且内容一致”按 alreadyPresent 跳过；默认调用不变。
        payload = dict(payload)
        payload.setdefault("allowIdenticalReuse", True)
    primitive = PRIMITIVES[step["primitive"]]
    try:
        return primitive(payload, ctx)
    except (RecipeError, errors.ToolError):
        raise
    except Exception as exc:  # noqa: BLE001 - 原语错误必须转成 recipe 结构化失败
        raise RecipeError(
            "原语执行失败：" + type(exc).__name__,
            code=str(getattr(exc, "code", "") or "RECIPE_STEP_FAILED"),
            details={"step": step["id"], "error": type(exc).__name__,
                     "message": str(exc)[:300]},
        ) from None


def _step_version(step: dict) -> str:
    """步骤绑定的实现版本：工具取 ToolSpec.version，原语取 PrimitiveSpec.version。"""
    if "tool" in step:
        spec = registry.get(step["tool"])
        return spec.version if spec is not None else ""
    if "primitive" in step:
        primitive = PRIMITIVE_SPECS.get(step["primitive"])
        return primitive.version if primitive is not None else ""
    return ""


def _step_side_effects(step: dict) -> bool:
    if step.get("gate"):
        return False
    if "tool" in step:
        spec = registry.get(step["tool"])
        return bool(spec and (spec.side_effects or not spec.read_only))
    primitive = PRIMITIVE_SPECS.get(step["primitive"])
    return bool(primitive and (primitive.side_effects or not primitive.read_only))


def _verify_completed_artifacts(run_dir: Path, state: dict) -> None:
    """恢复前逐个校验已完成步骤产物的存在、bytes 和 sha256。"""
    for step_id, record in (state.get("steps") or {}).items():
        if not isinstance(record, dict) or record.get("status") != "ok":
            continue
        artifact = record.get("artifact")
        if not artifact:
            raise errors.recipe_artifact_tampered(
                step_id, {"reason": "missing_artifact_name"})
        path = run_dir / "artifacts" / str(artifact)
        try:
            raw = path.read_bytes()
        except OSError:
            raise errors.recipe_artifact_tampered(
                step_id, {"reason": "artifact_missing", "artifact": str(artifact)}) from None
        expected_bytes = record.get("bytes")
        expected_sha = record.get("sha256")
        # 旧版运行状态的 bytes/sha256 基于 compact JSON，与落盘文件不一致；
        # 只有新格式（记录存在顶层 bytes）才校验 bytes+sha，旧状态只校验存在性。
        if expected_bytes is None:
            continue
        if len(raw) != int(expected_bytes):
            raise errors.recipe_artifact_tampered(
                step_id, {"reason": "bytes_mismatch", "artifact": str(artifact),
                          "expectedBytes": expected_bytes, "actualBytes": len(raw)})
        if expected_sha:
            actual_sha = hashlib.sha256(raw).hexdigest()
            if actual_sha != expected_sha:
                raise errors.recipe_artifact_tampered(
                    step_id, {"reason": "sha256_mismatch", "artifact": str(artifact),
                              "expectedSha256": expected_sha, "actualSha256": actual_sha})


def _legacy_artifact_state(state: dict) -> bool:
    """旧版 state 的 bytes/sha256 基准与落盘文件不一致；只做存在性校验。"""
    for record in (state.get("steps") or {}).values():
        if isinstance(record, dict) and record.get("status") == "ok" \
                and record.get("bytes") is None:
            return True
    return False


def run_recipe(recipe_id: str, inputs=None, *, env=None, state_dir=None,
               config=None, confirm: bool = False, dry_run: bool = False,
               resume: bool = False) -> dict:
    env = dict(env or os.environ)
    recipe = get_recipe(recipe_id, env)
    inputs = dict(inputs or {})
    problems = schema.validate(inputs, recipe["inputs"])
    if problems:
        raise RecipeInputError("recipe 输入校验失败", {"problems": problems})
    inputs = _fill_defaults(inputs, recipe["inputs"])
    requires = (recipe.get("requires") if "requires" in recipe
                else targets.TARGETS[targets.target_name(env)]["defaultRequires"])
    target = targets.check(requires, env)
    if target["missing"]:
        raise RecipeError(
            "当前 target 缺少运行能力：" + ", ".join(target["missing"]),
            code="RECIPE_CAPABILITY_MISSING",
            details={"target": target["name"], "missing": target["missing"],
                     "hints": target.get("hints") or {}},
        )
    config = config or execution.load_config(env)
    required_permissions = _required_step_permissions(recipe)
    declared_permissions = recipe.get("permissions") if "permissions" in recipe else None
    if declared_permissions is not None:
        missing_declared = sorted(set(required_permissions) - set(declared_permissions or []))
        if missing_declared:
            raise RecipeError(
                "recipe.permissions 未覆盖步骤所需权限：" + ", ".join(missing_declared),
                code="RECIPE_INVALID",
                details={"missing": missing_declared,
                         "steps": {name: required_permissions[name]
                                   for name in missing_declared}},
            )
    missing_granted = sorted(set(required_permissions) - set(config.granted_permissions))
    if missing_granted:
        raise errors.permission_denied(
            "recipe:" + recipe["id"], ", ".join(missing_granted),
            sorted(config.granted_permissions))
    run_dir, add_run_root = _new_run_dir(recipe["id"], state_dir, env=env)
    if add_run_root:
        # 显式 --state-dir 由操作者提供：只把该运行目录追加为本次运行的写根。
        env = _env_with_root(env, pathguard.WRITE_ROOTS_ENV, run_dir)
        env = _env_with_root(env, pathguard.READ_ROOTS_ENV, run_dir)
    state = _load_state(run_dir) if (resume or state_dir) else None
    resuming = state is not None
    if state is None:
        state = {
            "schemaVersion": RECIPE_SCHEMA_VERSION,
            "engineVersion": RECIPE_ENGINE_VERSION,
            "runId": run_dir.name,
            "recipe": {"id": recipe["id"], "version": recipe["version"]},
            "target": target,
            "inputs": inputs,
            "toolVersions": {
                step["id"]: _step_version(step)
                for step in recipe["steps"]
                if _step_version(step)
            },
            "status": "running",
            "startedAt": _now(),
            "updatedAt": _now(),
            "steps": {},
        }
        _write_json_atomic(run_dir / "state.json", state)
    else:
        if (state.get("recipe") or {}).get("id") != recipe["id"]:
            raise RecipeError("运行状态属于其他 recipe", code="RECIPE_STATE")
        if (state.get("recipe") or {}).get("version") != recipe["version"]:
            raise RecipeError("recipe 版本已变化；请新建运行而不是续跑",
                              code="RECIPE_STATE_VERSION")
        if state.get("inputs") != inputs:
            raise RecipeError("续跑输入与首次运行不一致", code="RECIPE_STATE_INPUT")
        recorded_versions = state.get("toolVersions") or {}
        for step in recipe["steps"]:
            current = _step_version(step)
            if not current:
                continue
            record = (state.get("steps") or {}).get(step["id"]) or {}
            if record.get("status") == "ok":
                continue
            recorded = recorded_versions.get(step["id"])
            if recorded and recorded != current:
                raise RecipeError(
                    "待执行工具/原语版本已变化；请新建运行而不是续跑",
                    code="RECIPE_STATE_VERSION",
                    details={"step": step["id"],
                             "tool": step.get("tool"),
                             "primitive": step.get("primitive"),
                             "recorded": recorded, "current": current},
                )
        # 上次崩溃在副作用步骤写入中：不能直接续跑。
        for step in recipe["steps"]:
            record = (state.get("steps") or {}).get(step["id"]) or {}
            if record.get("status") == "running" and _step_side_effects(step):
                raise errors.recipe_step_uncertain(
                    step["id"], {"recipe": recipe["id"], "runId": run_dir.name})
        _verify_completed_artifacts(run_dir, state)
    legacy_artifacts = _legacy_artifact_state(state)
    outputs: dict[str, Any] = {}
    for step_id, record in (state.get("steps") or {}).items():
        if record.get("status") == "ok":
            outputs[step_id] = _load_output(run_dir, record)
    artifacts: list[dict] = []
    ctx = RunContext(env, run_dir, capabilities=target.get("capabilities"),
                     resuming=resuming)
    # 一次运行只通过一个 gate：--yes 表示“本次已核对到下一个门禁”，
    # 后续步骤若再遇到门禁会再次停下，避免一次 --yes 越过多个人工审核点。
    # 已完成的 gate（上次运行人工确认过）继续为后续副作用原语提供确认依据。
    gate_passed_this_run = False
    gate_authorized = any(
        step.get("gate") and (state.get("steps") or {}).get(step["id"], {}).get("status") == "ok"
        for step in recipe["steps"]
    )
    try:
        for step in recipe["steps"]:
            step_id = step["id"]
            record = state["steps"].get(step_id) or {}
            if record.get("status") == "ok":
                artifacts.extend(record.get("artifacts") or [])
                continue
            condition = step.get("when")
            if condition is not None and not evaluate_condition(condition, inputs, outputs, ctx):
                state["steps"][step_id] = {"status": "skipped", "updatedAt": _now()}
                continue
            ctx.current_step = step_id
            state["steps"][step_id] = {"status": "running", "startedAt": _now()}
            _write_json_atomic(run_dir / "state.json", state)
            if dry_run:
                state["steps"][step_id] = {"status": "planned", "updatedAt": _now()}
                continue
            if step.get("gate"):
                if not confirm or gate_passed_this_run:
                    raise RecipeError(
                        step.get("message") or "需要人工确认后才能继续",
                        code="RECIPE_CONFIRMATION_REQUIRED",
                        details={"step": step_id, "gate": step.get("message", "")},
                    )
                gate_passed_this_run = True
                gate_authorized = True
                output = {"status": "confirmed", "step": step_id}
            else:
                output = _run_step(step, recipe, ctx, inputs, outputs, config,
                                   gate_authorized, gate_authorized)
            outputs[step_id] = output
            artifact = _artifact_name(step_id)
            artifact_path = run_dir / "artifacts" / artifact
            encoded = json.dumps(output, ensure_ascii=False)
            if len(encoded.encode("utf-8")) > MAX_STEP_OUTPUT_BYTES:
                raise RecipeError("步骤产物超过上限，停止运行", code="RECIPE_OUTPUT_TOO_LARGE",
                                  details={"step": step_id})
            _write_json_atomic(artifact_path, output)
            artifact_bytes = artifact_path.read_bytes()
            artifact_sha = hashlib.sha256(artifact_bytes).hexdigest()
            artifacts.append({
                "step": step_id,
                "path": str(artifact_path),
                "sha256": artifact_sha,
                "bytes": len(artifact_bytes),
            })
            record_payload = {
                "status": "ok",
                "startedAt": record.get("startedAt") or _now(),
                "finishedAt": _now(),
                "summary": summarize_output(output),
                "artifact": artifact,
                "sha256": artifact_sha,
                "bytes": len(artifact_bytes),
                "artifacts": [artifacts[-1]],
            }
            if "tool" in step:
                tool_spec = registry.get(step["tool"])
                if tool_spec is not None:
                    record_payload["toolVersion"] = tool_spec.version
            elif "primitive" in step:
                primitive_spec = PRIMITIVE_SPECS.get(step["primitive"])
                if primitive_spec is not None:
                    record_payload["primitiveVersion"] = primitive_spec.version
            state["steps"][step_id] = record_payload
            state["updatedAt"] = _now()
            _write_json_atomic(run_dir / "state.json", state)
        state["status"] = "completed"
        state["finishedAt"] = _now()
        state["updatedAt"] = _now()
        _write_json_atomic(run_dir / "state.json", state)
    except RecipeError as exc:
        state["status"] = ("awaiting_confirmation"
                           if exc.code == "RECIPE_CONFIRMATION_REQUIRED" else "failed")
        state["error"] = {"code": exc.code, "message": str(exc), "details": exc.details}
        state["updatedAt"] = _now()
        _write_json_atomic(run_dir / "state.json", state)
        raise
    except errors.ToolError as exc:
        state["status"] = "failed"
        state["error"] = {"code": exc.code, "message": exc.message,
                          "details": exc.details}
        state["updatedAt"] = _now()
        _write_json_atomic(run_dir / "state.json", state)
        raise
    return {
        "runId": run_dir.name,
        "recipe": {"id": recipe["id"], "version": recipe["version"]},
        "target": {"name": target["name"], "capabilities": target["capabilities"]},
        "status": state["status"],
        "steps": _step_list(state),
        "artifacts": artifacts,
        "artifactVerification": "existence-only" if legacy_artifacts else "bytes+sha256",
        "notices": (["旧版运行状态的产物摘要基准与落盘格式不同：本次只校验存在性；建议新建运行。"]
                    if legacy_artifacts else []),
        "runDir": str(run_dir),
        "next": _next_hint(state, recipe),
    }


def _step_list(state: dict) -> list[dict]:
    items = []
    for step_id, record in (state.get("steps") or {}).items():
        items.append({
            "id": step_id,
            "status": record.get("status"),
            "summary": record.get("summary") or {},
            "artifact": record.get("artifact"),
        })
    return items


def _next_hint(state: dict, recipe: dict) -> str:
    if state.get("status") == "awaiting_confirmation":
        return "人工核对任务卡/产物后，带 --yes 续跑同一 --state-dir 完成写入"
    if state.get("status") == "completed":
        return "按 recipe 的 readback/artifacts 核对；不要凭摘要宣称已写入"
    return "查看 state.json 的 error；修正后新建运行，不要盲改旧状态"


# --------------------------------------------------------------------------- #
# 任务卡
# --------------------------------------------------------------------------- #

def _compact_schema(value, depth: int = 0):
    if depth > 3 or not isinstance(value, dict):
        return value
    result = {}
    for key in ("type", "required", "enum", "maxLength", "maxItems", "minimum", "maximum"):
        if key in value:
            result[key] = value[key]
    if "description" in value:
        result["description"] = _clip(value["description"], 60)
    if isinstance(value.get("properties"), dict):
        result["properties"] = {name: _compact_schema(spec, depth + 1)
                                for name, spec in value["properties"].items()}
    if isinstance(value.get("items"), dict):
        result["items"] = _compact_schema(value["items"], depth + 1)
    return result


def platform_recipe(ctx, payload: dict) -> dict:
    """platform.recipe 元工具：list/show/card。"""
    action = payload.get("action")
    env = dict(getattr(ctx, "env", None) or os.environ)
    if action == "list":
        return {"recipes": list_recipes(env)}
    recipe_id = payload.get("recipeId")
    if action in ("show", "card") and not recipe_id:
        raise RecipeError("recipeId 必填", code="RECIPE_INPUT_INVALID")
    if action == "show":
        recipe = get_recipe(recipe_id, env)
        target = targets.check(recipe.get("requires") or [], env)
        return {"recipe": recipe, "target": target}
    if action == "card":
        return {"card": task_card(recipe_id, env)}
    raise RecipeError("action 必须是 list/show/card", code="RECIPE_INPUT_INVALID")


def _card_inputs(input_schema: dict) -> dict:
    properties = input_schema.get("properties") if isinstance(input_schema, dict) else {}
    required = set(input_schema.get("required") or []) if isinstance(input_schema, dict) else set()
    items = {}
    if isinstance(properties, dict):
        for name, spec in properties.items():
            spec = spec if isinstance(spec, dict) else {}
            entry = {"type": spec.get("type") or "object",
                     "required": name in required}
            if spec.get("enum"):
                entry["enum"] = spec["enum"]
            if spec.get("description"):
                entry["description"] = _clip(spec["description"], 70)
            items[name] = entry
    return {"required": sorted(required), "properties": items}


def task_card(recipe_id: str, env=None) -> dict:
    env = dict(env or os.environ)
    recipe = get_recipe(recipe_id, env)
    requires = (recipe.get("requires") if "requires" in recipe
                else targets.TARGETS[targets.target_name(env)]["defaultRequires"])
    target = targets.check(requires, env)
    steps = []
    gates = []
    for step in recipe["steps"]:
        action = (step.get("tool") or step.get("primitive") or "gate")
        if step.get("gate"):
            gates.append({"id": step["id"], "message": step.get("message", "")})
        steps.append({
            "id": step["id"],
            "action": action,
            "kind": "gate" if step.get("gate") else ("tool" if step.get("tool") else "primitive"),
            "note": _clip(step.get("note") or step.get("message") or "", 80),
            "conditional": step.get("when") is not None,
            "confirm": bool(step.get("confirm") or step.get("gate")),
        })
    card = {
        "recipe": recipe["id"],
        "version": recipe["version"],
        "title": recipe["title"],
        "whenToUse": recipe["whenToUse"],
        "target": {"name": target["name"], "label": target["label"],
                   "missing": target.get("missing") or [],
                   "hints": target.get("hints") or {}},
        "requires": list(requires),
        "permissions": recipe.get("permissions") or [],
        "inputs": _card_inputs(recipe["inputs"]),
        "steps": steps,
        "gates": gates,
        "resumePolicy": "恢复时已完成的门禁不重复要求 --yes；未完成的门禁仍需逐次 --yes（一次运行只通过一个门禁）。",
        "artifacts": recipe.get("artifacts") or [],
        "readback": recipe.get("readback") or [],
        "commands": {
            "inspect": f"python -X utf8 tools/ai_maintenance/cli.py task {recipe['id']}",
            "run": (f"python -X utf8 tools/ai_maintenance/cli.py run {recipe['id']} "
                    "--input-file <input.json>"),
            "resume": ("python -X utf8 tools/ai_maintenance/cli.py run "
                       f"{recipe['id']} --state-dir <runDir> --yes"),
        },
    }
    return card
