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
from pathlib import Path
from typing import Any

from . import (device_adb, errors, execution, media_primitives, pathguard, plans,
               registry, schema, targets)

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

def _local_file(payload: dict, key: str, ctx: "RunContext") -> Path:
    return pathguard.resolve_local_path(payload.get(key), env=ctx.env, must_exist=True,
                                        kind=key)


def _local_dir(payload: dict, key: str, ctx: "RunContext") -> Path:
    return pathguard.resolve_local_path(payload.get(key), env=ctx.env, must_exist=False,
                                        kind=key)


def _primitive_media_scan(payload: dict, ctx: "RunContext") -> dict:
    """本地内容扫描（只读）；不需要 API，供 media.batch 的第一步使用。"""
    from . import media_scan
    scan = payload.get("scan")
    if not isinstance(scan, dict):
        raise RecipeInputError("media.scan 需要 scan 对象")
    if scan.get("libraryId"):
        raise RecipeInputError(
            "media.scan 只接受 root；库根扫描需要 API，请直接调用 media.organize.prepare")
    plan = media_scan.build_scan_plan({"scan": scan}, env=ctx.env)
    return {"plan": plan}


def _primitive_media_probe(payload: dict, ctx: "RunContext") -> dict:
    source = _local_file(payload, "path", ctx)
    extension = source.suffix.lower()
    kind = str(payload.get("kind") or "")
    if not kind:
        kind = "video" if extension in {".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v",
                                        ".wmv", ".flv", ".mpg", ".mpeg", ".ts"} else "image"
    if kind not in ("image", "video"):
        raise RecipeInputError("kind 只能是 image/video")
    record = media_primitives.perceptual_record(source, kind, env=ctx.env)
    result = {"path": str(source), "kind": kind, **record}
    if payload.get("frames"):
        out_dir = pathguard.resolve_local_path(
            payload.get("frameDir") or str(ctx.run_dir / "frames" / ctx.current_step),
            env=ctx.env, must_exist=False, kind="frameDir")
        percentages = media_primitives.parse_percentages(payload.get("frames"))
        result["frameFiles"] = media_primitives.extract_frames(
            source, out_dir, percentages=percentages, kind=kind, env=ctx.env)
    return result


def _primitive_media_fingerprint(payload: dict, ctx: "RunContext") -> dict:
    source = _local_file(payload, "path", ctx)
    algorithms = payload.get("algorithms") or ["md5", "sha256", "dhash64"]
    if not isinstance(algorithms, list) or not algorithms:
        raise RecipeInputError("algorithms 必须是非空数组")
    result = {"path": str(source), "bytes": source.stat().st_size}
    if "md5" in algorithms:
        result["md5"], _ = pathguard.md5_file(source)
    if "sha256" in algorithms:
        result["sha256"], _ = pathguard.sha256_file(source)
    if "dhash64" in algorithms:
        record = media_primitives.perceptual_record(source, "image", env=ctx.env)
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
            source = pathguard.resolve_local_path(entry.get("path"), env=ctx.env,
                                                   must_exist=True, kind="entries.path")
            normalized.append({"path": str(source), "label": entry.get("label") or source.name})
        target = out_dir / (str(payload.get("name") or "contact-sheet") + ".png")
        result = media_primitives.build_contact_sheet(normalized, target, columns=columns,
                                                      title=title, env=ctx.env)
        return {"status": "ok", "sheets": result.get("sheets") or []}
    root_value = payload.get("root")
    group_sheets = payload.get("sheets")
    if not isinstance(group_sheets, list) or not group_sheets:
        raise RecipeInputError("需要 entries，或 root+sheets（来自 media.organize.prepare 的 contactSheets）")
    root = pathguard.resolve_media_path(root_value, env=ctx.env, kind="contactSheet.root")
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
                                                      env=ctx.env)
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
        local = pathguard.resolve_local_path(value, env=ctx.env, must_exist=True,
                                             kind="localPaths")
        name = names[index] if index < len(names) else None
        items.append((str(local), name))
    return device_adb.push_many(items, remote_dir, env=ctx.env)


def _primitive_device_pull(payload: dict, ctx: "RunContext") -> dict:
    remote = payload.get("remotePath")
    if not isinstance(remote, str) or not remote.startswith("/"):
        raise RecipeInputError("remotePath 必须是设备上的绝对 POSIX 路径")
    local = pathguard.resolve_local_path(payload.get("localPath"), env=ctx.env,
                                         must_exist=False, kind="localPath")
    return device_adb.pull_file(remote, str(local), env=ctx.env)


def _primitive_file_hash(payload: dict, ctx: "RunContext") -> dict:
    return _primitive_media_fingerprint(payload, ctx)


def _primitive_media_transfer(payload: dict, ctx: "RunContext") -> dict:
    """按显式 items 复制文件到目标路径；只复制不删除，默认拒绝覆盖。"""
    items = payload.get("items")
    if not isinstance(items, list) or not items:
        raise RecipeInputError("media.transfer 需要非空 items")
    overwrite = bool(payload.get("overwrite"))
    completed = []
    failed = []
    total = 0
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            raise RecipeInputError(f"items[{index}] 必须是对象")
        source = pathguard.resolve_local_path(item.get("source"), env=ctx.env,
                                               must_exist=True, kind=f"items[{index}].source")
        target = pathguard.resolve_local_path(item.get("target"), env=ctx.env,
                                               must_exist=False, kind=f"items[{index}].target")
        if target.exists() and not overwrite:
            failed.append({"source": str(source), "target": str(target),
                           "code": "TARGET_EXISTS"})
            continue
        try:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        except OSError as exc:
            failed.append({"source": str(source), "target": str(target),
                           "code": "COPY_FAILED", "message": type(exc).__name__})
            continue
        expected = source.stat().st_size
        actual = target.stat().st_size
        source_hash, _ = pathguard.sha256_file(source)
        target_hash, _ = pathguard.sha256_file(target)
        if expected != actual or source_hash != target_hash:
            failed.append({"source": str(source), "target": str(target),
                           "code": "VERIFY_MISMATCH",
                           "expectedBytes": expected, "actualBytes": actual})
            continue
        total += actual
        completed.append({"source": str(source), "target": str(target),
                          "bytes": actual, "sha256": target_hash})
    return {"status": "ok" if not failed else "partial",
            "copied": completed, "failed": failed, "bytes": total}


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

PRIMITIVE_SUMMARY = {
    "media.scan": "本地内容扫描（只读）：md5/dHash64、去重候选、序号建议、对齐与拼图计划",
    "media.probe": "ffprobe 元数据 + dHash64；可选按百分比抽帧（缓存）",
    "media.fingerprint": "md5/sha256/dHash64 内容指纹（缓存）",
    "media.contact_sheet": "按组生成带标签拼图，供人工目检",
    "media.transfer": "按显式 items 复制文件到目标路径，sha256 回读；默认拒绝覆盖",
    "file.hash": "文件 md5/sha256/dHash64",
    "device.info": "读取已显式指定真机的型号/Android 版本",
    "device.push": "逐文件 adb push 并回读字节数",
    "device.pull": "adb pull 并回读本地字节数",
    "select.first": "取第一个非空值（用于已有 ID / 新建 ID / 查重命中的优先级选择）",
}


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
        if permission not in registry.PERMISSIONS:
            raise RecipeError("未知权限声明：" + str(permission), code="RECIPE_INVALID")
    return recipe


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
                 capabilities=None):
        self.env = env
        self.run_dir = run_dir
        self.current_step = current_step
        self.capabilities = dict(capabilities or {})


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


def _sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


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

def _new_run_dir(recipe_id: str, state_dir=None, env=None) -> Path:
    if state_dir:
        # 运行目录同样受写盘边界约束：仓库内只允许忽略工作根，仓库外允许显式绝对路径。
        return pathguard.resolve_local_path(str(state_dir), env=env, must_exist=False,
                                            kind="stateDir")
    stamp = time.strftime("%Y%m%d-%H%M%S")
    return (pathguard.BUILD_ROOT / "ai-maintenance" / "runs" /
            f"{recipe_id}-{stamp}-{secrets.token_hex(3)}")


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
              outputs: dict, config, confirm: bool) -> Any:
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
    run_dir = _new_run_dir(recipe["id"], state_dir, env=env)
    state = _load_state(run_dir) if (resume or state_dir) else None
    if state is None:
        state = {
            "schemaVersion": RECIPE_SCHEMA_VERSION,
            "engineVersion": RECIPE_ENGINE_VERSION,
            "runId": run_dir.name,
            "recipe": {"id": recipe["id"], "version": recipe["version"]},
            "target": target,
            "inputs": inputs,
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
    outputs: dict[str, Any] = {}
    for step_id, record in (state.get("steps") or {}).items():
        if record.get("status") == "ok":
            outputs[step_id] = _load_output(run_dir, record)
    artifacts: list[dict] = []
    ctx = RunContext(env, run_dir, capabilities=target.get("capabilities"))
    # 一次运行只通过一个 gate：--yes 表示“本次已核对到下一个门禁”，
    # 后续步骤若再遇到门禁会再次停下，避免一次 --yes 越过多个人工审核点。
    gate_confirmed = False
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
                if not confirm or gate_confirmed:
                    raise RecipeError(
                        step.get("message") or "需要人工确认后才能继续",
                        code="RECIPE_CONFIRMATION_REQUIRED",
                        details={"step": step_id, "gate": step.get("message", "")},
                    )
                gate_confirmed = True
                output = {"status": "confirmed", "step": step_id}
            else:
                output = _run_step(step, recipe, ctx, inputs, outputs, config,
                                   gate_confirmed)
            outputs[step_id] = output
            artifact = _artifact_name(step_id)
            artifact_path = run_dir / "artifacts" / artifact
            encoded = json.dumps(output, ensure_ascii=False)
            if len(encoded.encode("utf-8")) > MAX_STEP_OUTPUT_BYTES:
                raise RecipeError("步骤产物超过上限，停止运行", code="RECIPE_OUTPUT_TOO_LARGE",
                                  details={"step": step_id})
            _write_json_atomic(artifact_path, output)
            artifacts.append({
                "step": step_id,
                "path": str(artifact_path),
                "sha256": _sha256_text(encoded),
                "bytes": len(encoded.encode("utf-8")),
            })
            state["steps"][step_id] = {
                "status": "ok",
                "startedAt": record.get("startedAt") or _now(),
                "finishedAt": _now(),
                "summary": summarize_output(output),
                "artifact": artifact,
                "sha256": artifacts[-1]["sha256"],
                "artifacts": [artifacts[-1]],
            }
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
