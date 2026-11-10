"""运行目标能力档案：PC / Android 内嵌 / NAS 的差异只在这里声明与探测。

recipe 用 `requires` 声明能力（api/ffmpeg/ffprobe/node/adb/adbDevice/
mediaRoots/research/lens/writes），执行器在第一步之前核对；缺能力时给
可执行的环境变量提示，而不是跑到一半才失败。

设计（2026-11-10，扩展性）：
- 目标名只作标签，不参与业务规则；能力探测只看环境与 PATH。
- 不在仓库里保存设备序列号、内网地址或 token；只读取 QIMENG_* 变量名。
- 新增运行形态（如未来 NAS 容器）只增 TARGETS 条目与能力探测，不改 recipe。
"""
from __future__ import annotations

import os
import shutil

TARGETS_VERSION = "1.0.0"

TARGETS = {
    "pc": {
        "label": "Windows/macOS/Linux 主机（当前 PC 过渡形态）",
        "defaultRequires": ("api",),
        "notes": [
            "可运行 ffmpeg/ffprobe、Node Lens sidecar 与本机研究脚本。",
            "设备推送必须显式配置 QIMENG_ADB_SERIAL。",
        ],
    },
    "android-embedded": {
        "label": "Android 内嵌服务端（手机单机）",
        "defaultRequires": ("api", "mediaRoots"),
        "notes": [
            "服务端随 App 启动，research 默认关闭；不假设外部 AI 可直接使用 dev-login。",
            "设备领取/应用由 App 自身执行；不要在本机再启用 adb 推送同一设备。",
        ],
    },
    "nas": {
        "label": "NAS 部署（未来硬件，需另行验收）",
        "defaultRequires": ("api",),
        "notes": [
            "不假设有 adb；手机结果包应通过服务端网络通道处理。",
            "ffmpeg/Node 是否存在由部署验收决定；recipe 会先核对。",
        ],
    },
}

CAPABILITY_HINTS = {
    "api": "设置 QIMENG_API_BASE 与 QIMENG_API_TOKEN",
    "ffmpeg": "安装 ffmpeg 或设置 QIMENG_AI_FFMPEG",
    "ffprobe": "安装 ffprobe 或设置 QIMENG_AI_FFPROBE",
    "node": "安装 Node 24+ 或设置 QIMENG_AI_NODE",
    "adb": "安装 platform-tools 或设置 QIMENG_ADB",
    "adbDevice": "显式设置 QIMENG_ADB_SERIAL（禁止默认模拟器）",
    "mediaRoots": "设置 QIMENG_AI_MEDIA_ROOTS 为授权的绝对路径",
    "research": "服务端 research.enabled=true，或确认使用本地 research_kit 脚本",
    "lens": "启动 Lens sidecar 并设置 QIMENG_AI_LENS_ENABLED=1（或使用 Yandex/SauceNAO）",
    "writes": "当前为只读模式（QIMENG_AI_READONLY=1）；需要写入时由执行端显式关闭",
}


def _truthy(value) -> bool:
    return str(value or "").strip().lower() in ("1", "true", "yes", "on")


def target_name(env=None) -> str:
    source = env if env is not None else os.environ
    value = str(source.get("QIMENG_AI_TARGET") or "pc").strip().lower()
    if value in ("android", "app", "embedded"):
        value = "android-embedded"
    return value if value in TARGETS else "pc"


def detect(env=None) -> dict:
    source = env if env is not None else os.environ
    from . import media_primitives, pathguard

    node = str(source.get("QIMENG_AI_NODE") or "").strip() or (shutil.which("node") or "")
    adb = str(source.get("QIMENG_ADB") or "").strip() or (shutil.which("adb") or "")
    media_roots = ()
    try:
        media_roots = pathguard.media_roots(source)
    except Exception:  # 配置错误留给实际步骤报告，探测不抛异常
        media_roots = ()
    capabilities = {
        "api": bool(str(source.get("QIMENG_API_BASE") or "").strip()
                    and str(source.get("QIMENG_API_TOKEN") or "").strip()),
        "ffmpeg": bool(media_primitives.find_ffmpeg(source)),
        "ffprobe": bool(media_primitives.find_ffprobe(source)),
        "node": bool(node),
        "adb": bool(adb),
        "adbDevice": bool(str(source.get("QIMENG_ADB_SERIAL") or "").strip()),
        "mediaRoots": bool(media_roots),
        "research": _truthy(source.get("QIMENG_AI_RESEARCH_ENABLED")),
        "lens": _truthy(source.get("QIMENG_AI_LENS_ENABLED")),
        "writes": not _truthy(source.get("QIMENG_AI_READONLY")),
    }
    name = target_name(source)
    return {
        "name": name,
        "label": TARGETS[name]["label"],
        "capabilities": capabilities,
        "notes": list(TARGETS[name]["notes"]),
        "missing": [],
    }


def check(requires, env=None) -> dict:
    detected = detect(env)
    missing = [item for item in (requires or ()) if not detected["capabilities"].get(item)]
    detected["missing"] = missing
    detected["requires"] = list(requires or ())
    if missing:
        detected["hints"] = {item: CAPABILITY_HINTS.get(item, "缺少运行能力") for item in missing}
    return detected


def describe(env=None) -> dict:
    detected = detect(env)
    detected["availableTargets"] = [
        {"id": key, "label": value["label"], "defaultRequires": list(value["defaultRequires"])}
        for key, value in TARGETS.items()
    ]
    return detected
