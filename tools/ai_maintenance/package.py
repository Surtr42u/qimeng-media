"""最小可移植 AI 工具包：构建、清单核验与依赖诊断（仅标准库）。

目标不是打包整份源码或数据库，而是生成一个可从仓库外空目录运行的最小发行物：

- ``tools/ai_maintenance/**``：工具核心、rules.json、recipes 与版本常量；
- ``tools/research_kit/**``：可选本地研究脚本（存在且未禁用时）；
- ``api/openapi.yaml``：协议快照；
- ``qai`` / ``qai.cmd``：与仓库根一致的短入口；
- ``README.md`` / ``INSTALL.md``：最小安装与接入说明；
- ``source-manifest.json``：来源版本、协议指纹与逐文件长度/摘要。

约束：
- 只使用 Python 标准库；不联网（``doctor --online`` 例外，仅 healthz/readyz）；
- 不复制数据库、真实媒体、凭据或服务端业务实现；
- 输出路径一律 POSIX 相对路径，不记录本机用户名或开发工作区绝对路径；
- ``verify`` 不跟随符号链接，``doctor`` 只报告准确状态，不把缺失依赖伪装成成功。
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import ipaddress
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

if __name__ == "__main__":
    # 分发物内直接运行时不要把 __pycache__ 写进包内，否则 verify 会报告清单外文件。
    sys.dont_write_bytecode = True

if __package__ in (None, ""):  # 支持 python tools/ai_maintenance/package.py 直接运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    __package__ = "ai_maintenance"

from . import versions

PACKAGE_ROOT = Path(__file__).resolve().parent
REPO_ROOT = PACKAGE_ROOT.parents[1]
BUNDLE_PREFIX = "qimeng-ai-toolkit-"
MANIFEST_NAME = "source-manifest.json"
MANIFEST_SCHEMA_VERSION = 1
MIN_PYTHON = (3, 13)
MAX_TOKEN_LENGTH = 8192  # 与 client.TOKEN_MAX_LENGTH 保持一致

GENERATED_DIR_NAMES = frozenset({
    "__pycache__", ".work", ".test-temp", ".pytest_cache", ".mypy_cache",
    ".ruff_cache", "node_modules", ".venv", "venv", "dist", "build", "htmlcov",
    ".git", ".hg", ".svn",
})
GENERATED_FILE_NAMES = frozenset({
    "rulepack.json", "rulepack.json.tmp", ".DS_Store", "Thumbs.db", "desktop.ini",
    ".coverage",
})
GENERATED_SUFFIXES = (".pyc", ".pyo")
LOCAL_CONFIG_SUFFIXES = (".local", ".token", ".key", ".pem")
RESEARCH_GENERATED_DIRS = frozenset({"cache", "frames", "avatars", "evidence", "out"})
RESEARCH_KIT_SCRIPTS = (
    "lens_batch.mjs",
    "media_probe.py",
    "dhash_compare.py",
    "avatar_fetch.py",
    "yandex_cbir.py",
    "saucenao_batch.py",
    "fxtwitter_probe.py",
    "kemono_probe.py",
    "run_pipeline.py",
)

CORE_REQUIRED = (
    "tools/ai_maintenance/cli.py",
    "tools/ai_maintenance/execution.py",
    "tools/ai_maintenance/registry.py",
    "tools/ai_maintenance/rulepack.py",
    "tools/ai_maintenance/recipes.py",
    "tools/ai_maintenance/openapi.py",
    "tools/ai_maintenance/rules.json",
    "tools/ai_maintenance/versions.py",
    "tools/ai_maintenance/package.py",
    "tools/ai_maintenance/mcp_server.py",
    "tools/ai_maintenance/recipes",
    "api/openapi.yaml",
    "qai",
    "qai.cmd",
)
BUNDLE_REQUIRED = (
    "README.md",
    "INSTALL.md",
    "source-manifest.json",
)

_MANIFEST_SHA_RE = re.compile(r"^[0-9a-f]{64}$")
_GIT_REVISION_RE = re.compile(r"^[0-9a-f]{40}(?:[0-9a-f]{24})?$")


class PackageError(RuntimeError):
    """分发工具的可诊断失败；消息不包含凭据或本机绝对路径。"""

    def __init__(self, code: str, message: str, *, exit_code: int = 1):
        super().__init__(message)
        self.code = code
        self.exit_code = exit_code


# --------------------------------------------------------------------------- #
# 基础工具
# --------------------------------------------------------------------------- #

def _utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1 << 20)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _is_linkish(path: Path) -> bool:
    """符号链接、junction 或任意 Windows reparse point。"""
    try:
        info = os.lstat(path)
    except OSError:
        return False
    if stat.S_ISLNK(info.st_mode):
        return True
    attributes = getattr(info, "st_file_attributes", 0)
    reparse = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    return bool(reparse and attributes & reparse)


def _within(path: Path, root: Path) -> bool:
    try:
        Path(path).resolve().relative_to(Path(root).resolve())
        return True
    except (OSError, RuntimeError, ValueError):
        return False


def _sanitize_detail(value, root: Path | None = None) -> str:
    """错误详情只保留类别与占位符，避免回显工作区/用户名/凭据。"""
    text = "" if value is None else str(value)
    if root is not None:
        for candidate in {str(root), str(root).replace("\\", "/")}:
            text = text.replace(candidate, "<bundle>")
    text = re.sub(r"[A-Za-z]:[\\/][^\s\"'<>]+", "<path>", text)
    text = re.sub(r"(?<![\w.-])/(?:home|Users)/[^\s\"'<>]+", "<path>", text)
    return text[:300]


def _rmtree(path: Path) -> None:
    def _on_error(function, target, _exc):
        try:
            os.chmod(target, stat.S_IWRITE | stat.S_IREAD | stat.S_IEXEC)
            function(target)
        except OSError:
            pass

    if sys.version_info >= (3, 12):
        shutil.rmtree(path, onexc=_on_error)
    else:
        shutil.rmtree(path, onerror=_on_error)


# --------------------------------------------------------------------------- #
# 源文件遍历与复制
# --------------------------------------------------------------------------- #

def _excluded(rel: PurePosixPath, extra_dirs: frozenset[str]) -> bool:
    parts = rel.parts
    if not parts:
        return False
    for part in parts[:-1]:
        if part in GENERATED_DIR_NAMES or part in extra_dirs:
            return True
    name = parts[-1]
    if name in GENERATED_DIR_NAMES or name in extra_dirs:
        return True
    if name in GENERATED_FILE_NAMES or name.endswith(GENERATED_SUFFIXES):
        return True
    if name == ".env" or name.startswith(".env.") or name.startswith(".coverage.") \
            or name.endswith(LOCAL_CONFIG_SUFFIXES):
        return True  # 本机配置/凭据/覆盖率文件不进分发物
    return False


def _walk_source_files(root: Path, *, extra_dirs: frozenset[str] = frozenset(),
                       excluded_roots: tuple[Path, ...] = ()) -> list[PurePosixPath]:
    """遍历源目录，拒绝链接；不跟随任何符号链接/junction。"""
    root = Path(root)
    if _is_linkish(root) or not root.is_dir():
        raise PackageError("SOURCE_INVALID", "源目录不存在或不是普通目录")
    found: list[PurePosixPath] = []
    stack: list[tuple[Path, PurePosixPath]] = [(root, PurePosixPath())]
    while stack:
        current, rel_dir = stack.pop()
        try:
            with os.scandir(current) as iterator:
                entries = sorted(list(iterator), key=lambda entry: entry.name)
        except OSError:
            raise PackageError("SOURCE_UNREADABLE", "无法读取源目录") from None
        for entry in entries:
            rel = rel_dir / entry.name
            if _excluded(rel, extra_dirs):
                continue
            path = Path(entry.path)
            if any(_within(path, candidate) for candidate in excluded_roots):
                continue
            if _is_linkish(path):
                raise PackageError("SOURCE_LINK", "源目录包含符号链接/junction，拒绝打包")
            if entry.is_dir(follow_symlinks=False):
                stack.append((path, rel))
            elif entry.is_file(follow_symlinks=False):
                found.append(rel)
    return sorted(found, key=lambda item: item.as_posix())


def _copy_tree(source: Path, destination: Path, *,
               extra_dirs: frozenset[str] = frozenset(),
               excluded_roots: tuple[Path, ...] = ()) -> int:
    copied = 0
    for rel in _walk_source_files(source, extra_dirs=extra_dirs,
                                  excluded_roots=excluded_roots):
        target = destination.joinpath(*rel.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source.joinpath(*rel.parts), target)
        copied += 1
    return copied


def _copy_file(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


# --------------------------------------------------------------------------- #
# 清单
# --------------------------------------------------------------------------- #

def _safe_manifest_path(value) -> PurePosixPath:
    if not isinstance(value, str) or not value:
        raise PackageError("MANIFEST_PATH_INVALID", "清单包含非法路径")
    if value != value.strip() or "\\" in value:
        raise PackageError("MANIFEST_PATH_INVALID", "清单路径必须是 POSIX 相对路径")
    if ":" in value or any(ord(ch) < 32 for ch in value):
        raise PackageError("MANIFEST_PATH_INVALID", "清单路径不能包含盘符/控制字符")
    path = PurePosixPath(value)
    if path.is_absolute() or path.as_posix() != value:
        raise PackageError("MANIFEST_PATH_INVALID", "清单路径必须是规范化 POSIX 相对路径")
    if any(part in ("", ".", "..") for part in path.parts):
        raise PackageError("MANIFEST_PATH_INVALID", "清单路径不能包含 . 或 ..")
    return path


def _validate_manifest(data) -> dict[PurePosixPath, dict]:
    if not isinstance(data, dict):
        raise PackageError("MANIFEST_INVALID", "清单顶层必须是对象")
    for key in ("schemaVersion", "platformVersion", "toolContractVersion",
                "rulepackVersion", "planVersion", "openapiSha256",
                "generatedAt", "gitRevision", "files"):
        if key not in data:
            raise PackageError("MANIFEST_INVALID", "清单缺少字段：" + key)
    if type(data["schemaVersion"]) is not int or data["schemaVersion"] != MANIFEST_SCHEMA_VERSION:
        raise PackageError("MANIFEST_INVALID", "不支持的清单 schemaVersion")
    for key in ("platformVersion", "toolContractVersion", "rulepackVersion",
                "planVersion", "generatedAt", "gitRevision"):
        if not isinstance(data[key], str) or not data[key]:
            raise PackageError("MANIFEST_INVALID", "清单字段必须是非空字符串：" + key)
    if not isinstance(data["openapiSha256"], str) or not _MANIFEST_SHA_RE.match(data["openapiSha256"]):
        raise PackageError("MANIFEST_INVALID", "清单 openapiSha256 非法")
    if not isinstance(data["files"], list):
        raise PackageError("MANIFEST_INVALID", "清单 files 必须是数组")
    expected: dict[PurePosixPath, dict] = {}
    for item in data["files"]:
        if not isinstance(item, dict):
            raise PackageError("MANIFEST_INVALID", "清单 files 条目必须是对象")
        path = _safe_manifest_path(item.get("path"))
        if path.as_posix() == MANIFEST_NAME:
            raise PackageError("MANIFEST_INVALID", "清单不能包含自身")
        if path in expected:
            raise PackageError("MANIFEST_INVALID", "清单包含重复路径")
        size = item.get("bytes")
        digest = item.get("sha256")
        if type(size) is not int or size < 0:
            raise PackageError("MANIFEST_INVALID", "清单 bytes 必须是非负整数")
        if not isinstance(digest, str) or not _MANIFEST_SHA_RE.match(digest):
            raise PackageError("MANIFEST_INVALID", "清单 sha256 非法")
        expected[path] = item
    return expected


def _walk_bundle_entries(bundle: Path) -> list[tuple[str, bool]]:
    """返回 (POSIX 相对路径, 是否链接)。不跟随链接，不进入链接目录。"""
    entries: list[tuple[str, bool]] = []
    stack: list[tuple[Path, PurePosixPath]] = [(bundle, PurePosixPath())]
    while stack:
        current, rel_dir = stack.pop()
        try:
            with os.scandir(current) as iterator:
                items = sorted(list(iterator), key=lambda entry: entry.name)
        except OSError:
            raise PackageError("BUNDLE_UNREADABLE", "无法读取工具包目录") from None
        for entry in items:
            rel = rel_dir / entry.name
            path = Path(entry.path)
            if _is_linkish(path):
                entries.append((rel.as_posix(), True))
                continue
            if entry.is_dir(follow_symlinks=False):
                stack.append((path, rel))
            elif entry.is_file(follow_symlinks=False):
                entries.append((rel.as_posix(), False))
    return sorted(entries, key=lambda item: item[0])


def _bundle_versions(bundle: Path) -> dict:
    """从包内 versions.py 读取常量（只解析文本，不执行包代码）。"""
    values = {
        "platformVersion": versions.PLATFORM_VERSION,
        "toolContractVersion": versions.TOOL_CONTRACT_VERSION,
        "planVersion": versions.PLAN_VERSION,
    }
    path = bundle / "tools" / "ai_maintenance" / "versions.py"
    try:
        text = path.read_text(encoding="utf-8-sig")
    except OSError:
        return values
    for constant, key in (
        ("PLATFORM_VERSION", "platformVersion"),
        ("TOOL_CONTRACT_VERSION", "toolContractVersion"),
        ("PLAN_VERSION", "planVersion"),
    ):
        match = re.search(rf"^{constant}\s*=\s*['\"]([^'\"]+)['\"]", text, re.M)
        if match:
            values[key] = match.group(1)
    return values


def _read_rulepack_version(bundle: Path, *, strict: bool,
                           expected_versions: dict | None = None) -> str:
    rules_path = bundle / "tools" / "ai_maintenance" / "rules.json"
    try:
        data = json.loads(rules_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise PackageError("RULES_UNREADABLE", "无法读取 rules.json") from None
    if not isinstance(data, dict):
        raise PackageError("RULES_INVALID", "rules.json 顶层必须是对象")
    value = data.get("rulepackVersion") or data.get("rulesVersion")
    if not isinstance(value, str) or not value.strip():
        raise PackageError("RULES_INVALID", "rules.json 缺少 rulepackVersion")
    if strict:
        expected = expected_versions or _bundle_versions(bundle)
        platform = data.get("platform") if isinstance(data.get("platform"), dict) else {}
        for key in ("platformVersion", "toolContractVersion"):
            if platform.get(key) != expected.get(key):
                raise PackageError("RULES_VERSION_DRIFT",
                                   f"rules.json platform.{key} 与工具包版本不一致")
        if platform.get("planVersion") not in (None, expected.get("planVersion")):
            raise PackageError("RULES_VERSION_DRIFT",
                               "rules.json platform.planVersion 与工具包版本不一致")
    return value


def _bundle_metadata(bundle: Path, *, strict: bool = True) -> dict:
    package_versions = _bundle_versions(bundle)
    rulepack_version = _read_rulepack_version(
        bundle, strict=strict, expected_versions=package_versions)
    openapi_path = bundle / "api" / "openapi.yaml"
    try:
        raw = openapi_path.read_bytes()
    except OSError:
        raise PackageError("OPENAPI_MISSING", "无法读取 OpenAPI 快照") from None
    return {
        "platformVersion": package_versions["platformVersion"],
        "toolContractVersion": package_versions["toolContractVersion"],
        "rulepackVersion": rulepack_version,
        "planVersion": package_versions["planVersion"],
        "openapiSha256": hashlib.sha256(raw).hexdigest(),
    }


def _git_revision(cwd: Path) -> str:
    if not (Path(cwd) / ".git").exists():
        return "unknown"  # 发行目录/解压后的包不带 .git，不借用父仓库身份
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=str(cwd), capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    value = (result.stdout or "").strip().lower()
    return value if _GIT_REVISION_RE.match(value) else "unknown"


def _build_manifest(bundle: Path, *, generated_at: str | None = None,
                    git_revision: str | None = None) -> dict:
    metadata = _bundle_metadata(bundle, strict=True)
    files: list[dict] = []
    for rel, linkish in _walk_bundle_entries(bundle):
        if rel == MANIFEST_NAME:
            continue
        if linkish:
            raise PackageError("BUNDLE_LINK", "工具包包含符号链接，拒绝生成清单")
        rel_path = _safe_manifest_path(rel)
        path = bundle.joinpath(*rel_path.parts)
        try:
            size = path.stat().st_size
        except OSError:
            raise PackageError("BUNDLE_UNREADABLE", "无法读取工具包文件") from None
        files.append({"path": rel, "bytes": size, "sha256": _sha256_file(path)})
    files.sort(key=lambda item: item["path"])
    return {
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        **metadata,
        "generatedAt": generated_at or _utc_now(),
        "gitRevision": git_revision if git_revision is not None
                       else _git_revision(bundle),
        "files": files,
    }


def _write_manifest(bundle: Path, manifest: dict) -> Path:
    target = bundle / MANIFEST_NAME
    text = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    try:
        with target.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(text)
    except OSError:
        raise PackageError("MANIFEST_WRITE_FAILED", "无法写入来源清单", exit_code=2) from None
    return target


def _read_existing_manifest(bundle: Path) -> dict:
    path = bundle / MANIFEST_NAME
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def manifest_for_bundle(bundle: str | Path) -> dict:
    """重建并返回清单；保留已有 generatedAt/gitRevision，不写回原清单。"""
    bundle = Path(bundle)
    if _is_linkish(bundle) or not bundle.is_dir():
        raise PackageError("BUNDLE_INVALID", "工具包目录不存在或不是普通目录", exit_code=2)
    existing = _read_existing_manifest(bundle)
    generated_at = existing.get("generatedAt")
    git_revision = existing.get("gitRevision")
    return _build_manifest(
        bundle,
        generated_at=generated_at if isinstance(generated_at, str) and generated_at else None,
        git_revision=git_revision if isinstance(git_revision, str) and git_revision else None,
    )


# --------------------------------------------------------------------------- #
# 构建
# --------------------------------------------------------------------------- #

def _render_template(text: str, values: dict[str, str]) -> str:
    rendered = text
    for key, value in values.items():
        rendered = rendered.replace("{{" + key + "}}", value)
    if "{{" in rendered or "}}" in rendered:
        raise PackageError("TEMPLATE_PLACEHOLDER", "INSTALL 模板存在未替换占位符")
    return rendered


def _readme_text(values: dict[str, str]) -> str:
    return (
        f"# 绮梦影库 AI 工具包 {values['BUNDLE_NAME']}\n\n"
        "本目录是从仓库发布的最小可移植分发物，不包含服务端数据库、真实媒体、"
        "凭据或 Go 业务实现。\n\n"
        "- 安装与接入：`INSTALL.md`\n"
        "- 来源版本、协议指纹与逐文件摘要：`source-manifest.json`\n"
        "- 短入口：`qai`（POSIX shell）/ `qai.cmd`（Windows）\n"
        "- 规则与协议快照：`tools/ai_maintenance/rules.json`、`api/openapi.yaml`\n"
        "- 自检：`python -X utf8 tools/ai_maintenance/package.py verify --bundle .`\n"
        "  `python -X utf8 tools/ai_maintenance/package.py doctor --bundle . --json`\n\n"
        f"- 工具包版本：`{values['PLATFORM_VERSION']}`；工具契约："
        f"`{values['TOOL_CONTRACT_VERSION']}`；规则包：`{values['RULEPACK_VERSION']}`；"
        f"计划结构：`{values['PLAN_VERSION']}`\n"
        f"- research_kit：{values['RESEARCH_KIT_STATUS']}\n"
        f"- 额外分发文档：{values['DOCS_STATUS']}\n"
        "- `tools/ai_maintenance/README.md` 是仓库版开发说明，部分链接指向未随包复制的"
        "仓库文档；部署与接入以本 README、`INSTALL.md` 和 `source-manifest.json` 为准。\n\n"
        "远程 HTTP MCP 尚未实现（后续独立范围）；当前只支持 CLI 与 stdio MCP。\n"
    )


def build_bundle(out: str | Path, *, include_research_kit: bool = True,
                 include_docs: bool = True, source_root: str | Path | None = None) -> dict:
    """在空目录 out 下原子生成 qimeng-ai-toolkit-<platformVersion>/。"""
    out = Path(out)
    source_root = Path(source_root) if source_root is not None else REPO_ROOT
    bundle_name = BUNDLE_PREFIX + versions.PLATFORM_VERSION
    if _is_linkish(out):
        raise PackageError("OUT_LINK", "输出目录不能是符号链接", exit_code=2)
    if out.exists():
        if not out.is_dir():
            raise PackageError("OUT_NOT_DIRECTORY", "输出目标不是目录", exit_code=2)
        try:
            non_empty = any(out.iterdir())
        except OSError:
            raise PackageError("OUT_UNREADABLE", "无法读取输出目录", exit_code=2) from None
        if non_empty:
            raise PackageError("OUT_NOT_EMPTY", "输出目录必须为空；拒绝覆盖已有文件", exit_code=2)
    created_out = not out.exists()
    try:
        out.parent.mkdir(parents=True, exist_ok=True)
        if created_out:
            out.mkdir()
    except OSError:
        raise PackageError("OUT_UNWRITABLE", "无法创建输出目录", exit_code=2) from None

    try:
        temporary_root = Path(tempfile.mkdtemp(prefix=".qimeng-ai-toolkit-", dir=str(out.parent)))
    except OSError:
        if created_out and out.exists():
            try:
                out.rmdir()
            except OSError:
                pass
        raise PackageError("OUT_UNWRITABLE", "无法创建临时构建目录", exit_code=2) from None
    excluded_roots = (out.resolve(), temporary_root.resolve())
    try:
        bundle = temporary_root / bundle_name
        bundle.mkdir()
        _copy_tree(PACKAGE_ROOT, bundle / "tools" / "ai_maintenance",
                   excluded_roots=excluded_roots)
        research_included = False
        if include_research_kit:
            research_source = source_root / "tools" / "research_kit"
            if research_source.is_dir():
                copied = _copy_tree(research_source, bundle / "tools" / "research_kit",
                                    extra_dirs=RESEARCH_GENERATED_DIRS,
                                    excluded_roots=excluded_roots)
                research_included = copied > 0

        openapi_source = source_root / "api" / "openapi.yaml"
        if not openapi_source.is_file():
            raise PackageError("SOURCE_OPENAPI_MISSING", "源目录缺少 api/openapi.yaml")
        _copy_file(openapi_source, bundle / "api" / "openapi.yaml")

        for name in ("qai", "qai.cmd"):
            source = source_root / name
            if not source.is_file():
                raise PackageError("SOURCE_ENTRY_MISSING", "源目录缺少入口：" + name)
            _copy_file(source, bundle / name)
        if os.name != "nt":
            try:
                os.chmod(bundle / "qai", 0o755)
            except OSError:
                pass

        docs_included = False
        if include_docs:
            docs_source = source_root / "docs" / "AI_PLATFORM_DISTRIBUTION.md"
            if docs_source.is_file():
                _copy_file(docs_source, bundle / "docs" / "AI_PLATFORM_DISTRIBUTION.md")
                docs_included = True

        incomplete = []
        for rel in CORE_REQUIRED:
            path = bundle.joinpath(*PurePosixPath(rel).parts)
            if rel == "tools/ai_maintenance/recipes":
                if not path.is_dir() or not any(path.glob("*.json")):
                    incomplete.append(rel + "/*.json")
            elif not path.is_file():
                incomplete.append(rel)
        if incomplete:
            raise PackageError("SOURCE_CORE_INCOMPLETE",
                               "源工具核心缺少必需文件：" + ", ".join(incomplete))

        try:
            document, _raw = _parse_openapi_from_root(bundle)
        except Exception as exc:  # noqa: BLE001 - 构建时拒绝分发不可解析的协议快照
            raise PackageError("SOURCE_OPENAPI_INVALID",
                               "OpenAPI 快照无法解析：" + _sanitize_detail(
                                   str(exc) or type(exc).__name__, bundle)) from None
        if not document.paths():
            raise PackageError("SOURCE_OPENAPI_INVALID", "OpenAPI 快照没有 /api/v1 路径")

        template_path = bundle / "tools" / "ai_maintenance" / "INSTALL_TEMPLATE.md"
        try:
            template = template_path.read_text(encoding="utf-8")
        except OSError:
            raise PackageError("SOURCE_TEMPLATE_MISSING", "工具核心缺少 INSTALL_TEMPLATE.md") from None
        metadata = _bundle_metadata(bundle, strict=True)
        if metadata["platformVersion"] != versions.PLATFORM_VERSION:
            raise PackageError("SOURCE_VERSION_DRIFT",
                               "包内 versions.py 平台版本与构建工具不一致")
        generated_at = _utc_now()
        values = {
            "BUNDLE_NAME": bundle_name,
            "PLATFORM_VERSION": metadata["platformVersion"],
            "TOOL_CONTRACT_VERSION": metadata["toolContractVersion"],
            "RULEPACK_VERSION": metadata["rulepackVersion"],
            "PLAN_VERSION": metadata["planVersion"],
            "OPENAPI_SHA256": metadata["openapiSha256"],
            "GENERATED_AT": generated_at,
            "RESEARCH_KIT_STATUS": ("已包含（本地脚本可用性取决于可选依赖）"
                                    if research_included
                                    else "未包含（research.* 本地工具会返回明确的不可用原因）"),
            "DOCS_STATUS": ("已包含 docs/AI_PLATFORM_DISTRIBUTION.md"
                            if docs_included else "未包含（仅保留本 README 与 INSTALL.md）"),
            "MANIFEST_NAME": MANIFEST_NAME,
        }
        install_text = _render_template(template, values)
        (bundle / "INSTALL.md").write_text(install_text, encoding="utf-8", newline="\n")
        (bundle / "README.md").write_text(_readme_text(values), encoding="utf-8", newline="\n")

        manifest = _build_manifest(
            bundle,
            generated_at=generated_at,
            git_revision=_git_revision(source_root),
        )
        _write_manifest(bundle, manifest)
        target = out / bundle_name
        try:
            os.replace(bundle, target)
        except OSError:
            raise PackageError("OUT_REPLACE_FAILED", "无法原子发布工具包目录", exit_code=2) from None
        return {
            "ok": True,
            "bundle": bundle_name,
            "manifest": MANIFEST_NAME,
            "files": len(manifest["files"]),
            "researchKit": research_included,
            "docs": docs_included,
            "platformVersion": manifest["platformVersion"],
            "toolContractVersion": manifest["toolContractVersion"],
            "rulepackVersion": manifest["rulepackVersion"],
            "openapiSha256": manifest["openapiSha256"],
            "gitRevision": manifest["gitRevision"],
        }
    finally:
        if temporary_root.exists():
            _rmtree(temporary_root)
        if created_out and out.exists():
            try:
                out.rmdir()  # 只在失败且仍为空时移除本次创建的目录
            except OSError:
                pass


# --------------------------------------------------------------------------- #
# 核验
# --------------------------------------------------------------------------- #

def verify_bundle(bundle: str | Path) -> dict:
    """逐文件核对清单；不跟随符号链接，输出只含相对路径。"""
    bundle = Path(bundle)
    if _is_linkish(bundle) or not bundle.is_dir():
        raise PackageError("BUNDLE_INVALID", "工具包目录不存在或不是普通目录", exit_code=2)
    manifest_path = bundle / MANIFEST_NAME
    if _is_linkish(manifest_path) or not manifest_path.is_file():
        raise PackageError("MANIFEST_MISSING", "缺少 source-manifest.json", exit_code=2)
    try:
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise PackageError("MANIFEST_INVALID", "无法读取或解析来源清单", exit_code=2) from None
    expected = _validate_manifest(data)

    actual = dict(_walk_bundle_entries(bundle))
    missing: list[str] = []
    modified: list[dict] = []

    for rel_path, item in expected.items():
        rel = rel_path.as_posix()
        current = bundle
        linked = False
        absent = False
        for part in rel_path.parts:
            current = current / part
            if _is_linkish(current):
                linked = True
                break
            if not current.exists():
                absent = True
                break
        if linked:
            modified.append({"path": rel, "reason": "symlink"})
            continue
        if absent:
            missing.append(rel)
            continue
        if not current.is_file():
            modified.append({"path": rel, "reason": "not_regular"})
            continue
        try:
            size = current.stat().st_size
        except OSError:
            missing.append(rel)
            continue
        if size != item["bytes"]:
            modified.append({"path": rel, "reason": "bytes"})
            continue
        try:
            digest = _sha256_file(current)
        except OSError:
            modified.append({"path": rel, "reason": "unreadable"})
            continue
        if digest != item["sha256"]:
            modified.append({"path": rel, "reason": "sha256"})

    extra: list[str] = []
    for rel in actual:
        if rel == MANIFEST_NAME:
            continue
        try:
            rel_path = _safe_manifest_path(rel)
        except PackageError:
            extra.append(rel)  # 非法文件名也算清单外条目，不中断核验
            continue
        if rel_path not in expected:
            extra.append(rel)
    extra.sort()
    missing.sort()
    modified.sort(key=lambda item: item["path"])
    return {
        "ok": not missing and not extra and not modified,
        "manifest": MANIFEST_NAME,
        "checked": len(expected),
        "platformVersion": data.get("platformVersion"),
        "toolContractVersion": data.get("toolContractVersion"),
        "rulepackVersion": data.get("rulepackVersion"),
        "openapiSha256": data.get("openapiSha256"),
        "missing": missing,
        "extra": extra,
        "modified": modified,
    }


# --------------------------------------------------------------------------- #
# 依赖诊断
# --------------------------------------------------------------------------- #

def _api_base_problem(value: str) -> str:
    text = value.strip()
    if not text:
        return "QIMENG_API_BASE 未设置"
    if text != value:
        return "QIMENG_API_BASE 首尾不能有空白"
    try:
        parts = urllib.parse.urlsplit(text)
    except ValueError:
        return "QIMENG_API_BASE 不是合法 URL"
    if parts.scheme not in ("http", "https"):
        return "QIMENG_API_BASE 必须是 http(s) origin"
    if parts.username or parts.password:
        return "QIMENG_API_BASE 不能包含用户名或口令"
    if parts.path not in ("", "/") or parts.query or parts.fragment:
        return "QIMENG_API_BASE 只能是 origin，不能带子路径、查询或片段"
    try:
        host = parts.hostname
        parts.port
    except ValueError:
        return "QIMENG_API_BASE 端口非法"
    if not host:
        return "QIMENG_API_BASE 缺少主机名"
    if parts.scheme == "http":
        try:
            address = ipaddress.ip_address(host)
            trusted = address.is_loopback or address.is_private
        except ValueError:
            trusted = host == "localhost" or host.endswith(".localhost")
        if not trusted:
            return "http 仅允许回环或私有地址；远程请用 https 或既有隧道"
    return ""


def _token_problem(value: str) -> str:
    if not value:
        return "QIMENG_API_TOKEN 未设置"
    if len(value) > MAX_TOKEN_LENGTH or any(ord(ch) < 32 for ch in value):
        return "QIMENG_API_TOKEN 非法：含控制字符或过长"
    if value != value.strip():
        return "QIMENG_API_TOKEN 首尾不能有空白"
    return ""


def _probe_cli(root: Path, arguments: list[str], env: dict) -> dict:
    script = root / "tools" / "ai_maintenance" / "cli.py"
    if not script.is_file():
        return {"ok": False, "exitCode": None, "error": "script_missing"}
    clean = {
        key: value for key, value in env.items()
        if key not in ("PYTHONPATH", "PYTHONHOME", "PYTHONSAFEPATH")
        and not key.startswith("QIMENG_")
    }
    clean["PYTHONIOENCODING"] = "utf-8"
    clean["PYTHONUTF8"] = "1"
    clean["PYTHONDONTWRITEBYTECODE"] = "1"  # 诊断不得在包内留下 __pycache__
    try:
        process = subprocess.run(
            [sys.executable or "python", "-B", "-X", "utf8", str(script), *arguments],
            cwd=tempfile.gettempdir(), env=clean, capture_output=True,
            encoding="utf-8", errors="replace", timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        return {"ok": False, "exitCode": None, "error": type(exc).__name__}
    if process.returncode != 0:
        return {"ok": False, "exitCode": process.returncode, "error": "nonzero_exit"}
    try:
        payload = json.loads(process.stdout)
    except ValueError:
        return {"ok": False, "exitCode": process.returncode, "error": "invalid_json"}
    return {"ok": True, "exitCode": 0, "data": payload}


def _parse_openapi_from_root(root: Path):
    script = root / "tools" / "ai_maintenance" / "openapi.py"
    if not script.is_file():
        raise PackageError("OPENAPI_MODULE_MISSING", "工具核心缺少 openapi.py")
    spec = importlib.util.spec_from_file_location("_qimeng_bundle_openapi_probe", script)
    if spec is None or spec.loader is None:
        raise PackageError("OPENAPI_MODULE_INVALID", "无法加载 OpenAPI 解析器")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    previous = sys.dont_write_bytecode
    sys.dont_write_bytecode = True  # 诊断不得在包内写入 __pycache__
    try:
        spec.loader.exec_module(module)
    finally:
        sys.dont_write_bytecode = previous
        sys.modules.pop(spec.name, None)
    return module.parse_file(root / "api" / "openapi.yaml")


def _which(env: dict, name: str) -> str:
    return shutil.which(name, path=env.get("PATH") or "") or ""


def _detect_binary(env: dict, override_keys: tuple[str, ...], name: str) -> dict:
    for key in override_keys:
        raw = str(env.get(key) or "").strip()
        if not raw:
            continue
        candidate = Path(raw)
        if candidate.is_file():
            return {"available": True, "binary": candidate.name, "_path": str(candidate)}
        found = shutil.which(raw, path=env.get("PATH") or "")
        if found:
            return {"available": True, "binary": Path(found).name, "_path": found}
        return {"available": False, "binary": None,
                "reason": key + " 指向的可执行文件不存在"}
    found = _which(env, name)
    if found:
        return {"available": True, "binary": Path(found).name, "_path": found}
    return {"available": False, "binary": None, "reason": "未找到 " + name}


def _binary_version(path: str, arguments: list[str]) -> str:
    try:
        process = subprocess.run(
            [path, *arguments], capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    text = ((process.stdout or "") + "\n" + (process.stderr or "")).strip()
    match = re.search(r"(\d+\.\d+(?:\.\d+)?)", text)
    return match.group(1) if match else ""


def _detect_browser(env: dict) -> dict:
    for key in ("QIMENG_RESEARCH_KIT_EDGE", "QIMENG_RESEARCH_SIDECAR_EDGE"):
        raw = str(env.get(key) or "").strip()
        if not raw:
            continue
        candidate = Path(raw)
        return {
            "available": candidate.is_file(),
            "binary": candidate.name if candidate.is_file() else None,
            "reason": "" if candidate.is_file() else key + " 指向的浏览器不存在",
        }
    candidates = (
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        "/usr/bin/microsoft-edge",
        "/usr/bin/microsoft-edge-stable",
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
    )
    for candidate in candidates:
        if os.path.isfile(candidate):
            return {"available": True, "binary": Path(candidate).name, "reason": ""}
    return {"available": False, "binary": None,
            "reason": "未找到 Edge/Chrome/Chromium；Lens 批处理无法启动"}


def _online_get(base: str, token: str, path: str) -> dict:
    class _NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    url = base.rstrip("/") + path
    request = urllib.request.Request(
        url,
        headers={"Authorization": "Bearer " + token, "Accept": "application/json"},
        method="GET",
    )
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), _NoRedirect()
    )
    started = datetime.now(timezone.utc)
    try:
        with opener.open(request, timeout=5) as response:
            status = getattr(response, "status", 0)
            response.read(1)  # 只读 1 字节，确认响应可读；不输出正文
    except urllib.error.HTTPError as exc:
        status = exc.code
        try:
            exc.close()
        except Exception:  # noqa: BLE001 - 关闭失败不影响诊断
            pass
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return {"ok": False, "status": None, "error": type(exc).__name__,
                "latencyMs": int((datetime.now(timezone.utc) - started).total_seconds() * 1000)}
    except Exception as exc:  # noqa: BLE001 - 诊断不因单个请求构造异常中断
        return {"ok": False, "status": None, "error": type(exc).__name__,
                "latencyMs": int((datetime.now(timezone.utc) - started).total_seconds() * 1000)}
    latency = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
    return {"ok": 200 <= status < 300, "status": status, "error": "",
            "latencyMs": latency}


def doctor(bundle: str | Path | None = None, *, online: bool = False,
           env: dict | None = None) -> dict:
    """检查基础/可选依赖与工具包完整性；返回可 JSON 化的报告。

    退出码语义：0=通过（可选依赖缺失只 warning）；2=基础依赖/完整性失败；
    显式 --online 且 health 不通过时为 1。
    """
    source_env = dict(os.environ if env is None else env)
    root = Path(bundle) if bundle is not None else REPO_ROOT
    mode = "bundle" if bundle is not None else "source"
    checks: list[dict] = []

    def add(check_id: str, category: str, status: str, code: str, message: str) -> None:
        checks.append({
            "id": check_id, "class": category, "status": status,
            "code": code, "message": message,
        })

    if _is_linkish(root) or not root.is_dir():
        add("bundle.root", "base", "error", "ROOT_INVALID",
            "工具包目录不存在或不是普通目录")
        return _doctor_report(mode, checks, {}, None, 2)
    root = root.resolve()  # 允许 --bundle .；探测子进程的 cwd 不在原目录

    if sys.version_info >= MIN_PYTHON:
        add("python", "base", "ok", "PYTHON_OK",
            "Python " + ".".join(str(part) for part in sys.version_info[:3]))
    else:
        add("python", "base", "error", "PYTHON_VERSION",
            f"需要 Python {MIN_PYTHON[0]}.{MIN_PYTHON[1]}+，当前版本过低")

    required = list(CORE_REQUIRED) + (list(BUNDLE_REQUIRED) if mode == "bundle" else [])
    missing_required: list[str] = []
    for rel in required:
        path = root.joinpath(*PurePosixPath(rel).parts)
        if rel == "tools/ai_maintenance/recipes":
            if not path.is_dir() or not any(path.glob("*.json")):
                missing_required.append(rel + "/*.json")
        elif _is_linkish(path) or not path.is_file():
            missing_required.append(rel)
    if missing_required:
        add("files.required", "base", "error", "REQUIRED_FILE_MISSING",
            "缺少必需文件：" + ", ".join(missing_required))
    else:
        add("files.required", "base", "ok", "REQUIRED_FILES_OK",
            "工具核心、recipes、rules 与 OpenAPI 快照齐全")

    manifest_info = None
    if mode == "bundle":
        try:
            manifest_info = verify_bundle(root)
        except (PackageError, OSError) as exc:
            code = exc.code if isinstance(exc, PackageError) else "MANIFEST_UNREADABLE"
            add("manifest.integrity", "base", "error", code,
                "来源清单不可用：" + _sanitize_detail(exc, root))
        else:
            if manifest_info["missing"] or manifest_info["modified"]:
                detail = []
                if manifest_info["missing"]:
                    detail.append("missing=" + str(len(manifest_info["missing"])))
                if manifest_info["modified"]:
                    detail.append("modified=" + str(len(manifest_info["modified"])))
                sample = (manifest_info["missing"] + [item["path"] for item in manifest_info["modified"]])[:3]
                add("manifest.integrity", "base", "error", "MANIFEST_MISMATCH",
                    "清单不一致（" + ", ".join(detail) + "）：" + ", ".join(sample))
            else:
                add("manifest.integrity", "base", "ok", "MANIFEST_OK",
                    f"{manifest_info['checked']} 个文件与清单一致")
            if manifest_info["extra"]:
                sample = manifest_info["extra"][:3]
                add("manifest.extra", "base", "error", "MANIFEST_EXTRA",
                    "包内存在清单外文件（可能为本地状态或人工添加）：" + ", ".join(sample))

    openapi_sha = ""
    try:
        document, raw = _parse_openapi_from_root(root)
        openapi_sha = hashlib.sha256(raw).hexdigest()
        paths = document.paths()
        if not paths:
            add("openapi.parse", "base", "error", "OPENAPI_EMPTY",
                "OpenAPI 可解析但没有找到 /api/v1 路径")
        else:
            add("openapi.parse", "base", "ok", "OPENAPI_OK",
                f"OpenAPI 可解析，/api/v1 路径 {len(paths)} 个")
        if manifest_info and manifest_info.get("openapiSha256") != openapi_sha:
            add("openapi.sha256", "base", "error", "OPENAPI_SHA_MISMATCH",
                "OpenAPI 快照与来源清单指纹不一致")
    except Exception as exc:  # noqa: BLE001 - 解析器/文件错误都按基础失败报告
        add("openapi.parse", "base", "error", "OPENAPI_PARSE_FAILED",
            "OpenAPI 无法解析：" + _sanitize_detail(str(exc) or type(exc).__name__, root))

    version_probe = _probe_cli(root, ["version", "--json"], source_env)
    if not version_probe["ok"]:
        add("rules.load", "base", "error", "TOOLKIT_LOAD_FAILED",
            "工具核心/规则加载失败（退出码 "
            + str(version_probe.get("exitCode") or "unknown") + "）")
    elif not isinstance(version_probe.get("data"), dict):
        add("rules.load", "base", "error", "TOOLKIT_OUTPUT_INVALID",
            "工具核心 version 输出不是 JSON 对象")
    else:
        version_data = version_probe["data"]
        add("rules.load", "base", "ok", "RULES_OK",
            "规则包 " + str(version_data.get("rulepackVersion") or "unknown") + " 可加载")
        if manifest_info:
            mismatches = []
            for key in ("platformVersion", "toolContractVersion", "rulepackVersion"):
                if version_data.get(key) != manifest_info.get(key):
                    mismatches.append(key)
            if openapi_sha and manifest_info.get("openapiSha256") != openapi_sha:
                mismatches.append("openapiSha256")
            if mismatches:
                add("version.consistency", "base", "error", "VERSION_DRIFT",
                    "工具核心输出与清单不一致：" + ", ".join(mismatches))
            else:
                add("version.consistency", "base", "ok", "VERSION_OK",
                    "工具核心输出与清单版本一致")

    recipes_probe = _probe_cli(root, ["recipes", "list", "--json"], source_env)
    if not recipes_probe["ok"]:
        add("recipes.load", "base", "error", "RECIPES_LOAD_FAILED",
            "recipes 加载失败（退出码 " + str(recipes_probe.get("exitCode") or "unknown") + "）")
    else:
        payload = recipes_probe.get("data") if isinstance(recipes_probe.get("data"), dict) else {}
        items = payload.get("recipes") if isinstance(payload.get("recipes"), list) else []
        invalid = [str(item.get("id") or "?") for item in items
                   if isinstance(item, dict) and item.get("status") != "ok"]
        if not items:
            add("recipes.load", "base", "error", "RECIPES_EMPTY", "没有可加载的 recipe")
        elif invalid:
            add("recipes.load", "base", "error", "RECIPES_INVALID",
                "recipe 校验失败：" + ", ".join(invalid[:5]))
        else:
            add("recipes.load", "base", "ok", "RECIPES_OK",
                f"{len(items)} 个 recipe 可加载")

    base_value = str(source_env.get("QIMENG_API_BASE") or "")
    token_value = str(source_env.get("QIMENG_API_TOKEN") or "")
    api_ok = True
    base_problem = _api_base_problem(base_value)
    if base_problem:
        add("api.base", "base", "error", "API_BASE_INVALID", base_problem)
        api_ok = False
    else:
        add("api.base", "base", "ok", "API_BASE_OK",
            "QIMENG_API_BASE 已配置（未显示值）")
    token_problem = _token_problem(token_value)
    if token_problem:
        add("api.token", "base", "error", "API_TOKEN_INVALID", token_problem)
        api_ok = False
    else:
        add("api.token", "base", "ok", "API_TOKEN_OK",
            "QIMENG_API_TOKEN 已配置（未显示值）")

    research_kit_dir = root / "tools" / "research_kit"
    research_kit = research_kit_dir.is_dir()
    kit_scripts = {
        name: (research_kit_dir / name).is_file() for name in RESEARCH_KIT_SCRIPTS
    }
    if research_kit and all(kit_scripts.values()):
        add("optional.research_kit", "optional", "ok", "RESEARCH_KIT_OK",
            "research_kit 已包含")
    elif research_kit:
        missing_scripts = [name for name, present in kit_scripts.items() if not present]
        add("optional.research_kit", "optional", "warning", "RESEARCH_KIT_INCOMPLETE",
            "research_kit 缺少脚本：" + ", ".join(missing_scripts)
            + "；对应 research.* 能力不可用")
    else:
        add("optional.research_kit", "optional", "warning", "RESEARCH_KIT_ABSENT",
            "research_kit 未包含：research.* 本地工具会返回明确的不可用原因，不会伪装成功")

    node = _detect_binary(source_env, ("QIMENG_RESEARCH_KIT_NODE", "QIMENG_AI_NODE"), "node")
    node_kit = _detect_binary(source_env, ("QIMENG_RESEARCH_KIT_NODE",), "node")
    node_ok = bool(node["available"])
    node_version = ""
    node_major = 0
    if node_ok:
        node_version = _binary_version(node["_path"], ["--version"])
        match = re.match(r"(\d+)", node_version)
        node_major = int(match.group(1)) if match else 0
    kit_version = ""
    kit_major = 0
    if node_kit["available"]:
        kit_version = _binary_version(node_kit["_path"], ["--version"])
        kit_match = re.match(r"(\d+)", kit_version)
        kit_major = int(kit_match.group(1)) if kit_match else 0
    if not node_ok:
        add("optional.node", "optional", "warning", "NODE_MISSING",
            node.get("reason", "未找到 node")
            + "：Lens 批处理不可用；Yandex/SauceNAO/作者探针仅在 research_kit 脚本存在时可用")
    elif node_kit["available"]:
        if kit_major and kit_major < 24:
            add("optional.node", "optional", "warning", "NODE_VERSION",
                f"Node 主版本 {kit_major} < 24：Lens 批处理不可用；Python 研究脚本不受影响")
        elif kit_major:
            add("optional.node", "optional", "ok", "NODE_OK", "Node " + kit_version)
        else:
            add("optional.node", "optional", "warning", "NODE_VERSION_UNKNOWN",
                "Node 已找到但无法确认版本：Lens 批处理要求 Node 24+，可能不可用")
    elif node_major and node_major < 24:
        add("optional.node", "optional", "warning", "NODE_VERSION",
            f"Node 主版本 {node_major} < 24：Lens 批处理不可用；Python 研究脚本不受影响")
    elif node_major:
        add("optional.node", "optional", "ok", "NODE_OK",
            "Node " + node_version + "（research kit 需 QIMENG_RESEARCH_KIT_NODE 或 PATH）")
    else:
        add("optional.node", "optional", "warning", "NODE_VERSION_UNKNOWN",
            "Node 已找到但无法确认版本：Lens 批处理要求 Node 24+，可能不可用")

    ffmpeg = _detect_binary(
        source_env, ("QIMENG_AI_FFMPEG", "QIMENG_FFMPEG", "QIMENG_RESEARCH_KIT_FFMPEG"), "ffmpeg")
    ffprobe = _detect_binary(
        source_env, ("QIMENG_AI_FFPROBE", "QIMENG_FFPROBE", "QIMENG_RESEARCH_KIT_FFPROBE"), "ffprobe")
    ffmpeg_kit = _detect_binary(source_env, ("QIMENG_RESEARCH_KIT_FFMPEG",), "ffmpeg")
    ffprobe_kit = _detect_binary(source_env, ("QIMENG_RESEARCH_KIT_FFPROBE",), "ffprobe")
    ffmpeg_ok = bool(ffmpeg["available"])
    ffprobe_ok = bool(ffprobe["available"])
    ffmpeg_version = _binary_version(ffmpeg["_path"], ["-version"]) if ffmpeg_ok else ""
    ffprobe_version = _binary_version(ffprobe["_path"], ["-version"]) if ffprobe_ok else ""
    ffmpeg_kit_ok = bool(ffmpeg_kit["available"]
                         and _binary_version(ffmpeg_kit["_path"], ["-version"]))
    ffprobe_kit_ok = bool(ffprobe_kit["available"]
                          and _binary_version(ffprobe_kit["_path"], ["-version"]))
    if ffmpeg_ok and ffmpeg_version:
        add("optional.ffmpeg", "optional", "ok", "FFMPEG_OK", "ffmpeg " + ffmpeg_version)
    elif ffmpeg_ok:
        add("optional.ffmpeg", "optional", "warning", "FFMPEG_VERSION_UNKNOWN",
            "ffmpeg 存在但无法确认版本/执行；视频抽帧/dHash/拼图可能不可用")
    else:
        add("optional.ffmpeg", "optional", "warning", "FFMPEG_MISSING",
            ffmpeg.get("reason", "缺少 ffmpeg") + "：视频抽帧/dHash/拼图不可用")
    if ffprobe_ok and ffprobe_version:
        add("optional.ffprobe", "optional", "ok", "FFPROBE_OK",
            "ffprobe " + ffprobe_version)
    elif ffprobe_ok:
        add("optional.ffprobe", "optional", "warning", "FFPROBE_VERSION_UNKNOWN",
            "ffprobe 存在但无法确认版本/执行；媒体宽高/时长元数据可能不可用")
    else:
        add("optional.ffprobe", "optional", "warning", "FFPROBE_MISSING",
            ffprobe.get("reason", "缺少 ffprobe") + "：媒体宽高/时长元数据不可用")

    browser = _detect_browser(source_env)
    browser_ok = bool(browser["available"])
    if browser_ok:
        add("optional.browser", "optional", "ok", "BROWSER_OK",
            "浏览器 " + str(browser.get("binary") or "已找到") + " 可用于 Lens 批处理")
    else:
        add("optional.browser", "optional", "warning", "BROWSER_MISSING",
            browser.get("reason", "缺少浏览器")
            + "；Yandex/SauceNAO 在 research_kit 脚本存在时仍可用")

    add("optional.sidecar", "optional", "warning", "SIDECAR_UNVERIFIED",
        "工具端无法确认 Lens sidecar 运行状态；需服务端 research.enabled=true 且 sidecar 可用，"
        "缺失时 /research/lens 返回 503，不能据此判定作者/作品不存在")

    adb = _detect_binary(source_env, ("QIMENG_ADB",), "adb")
    adb_serial = str(source_env.get("QIMENG_ADB_SERIAL") or "").strip()
    if adb["available"]:
        add("optional.adb", "optional", "ok", "ADB_OK",
            "adb 已找到；设备操作仍须显式 QIMENG_ADB_SERIAL（禁止默认模拟器）")
    else:
        add("optional.adb", "optional", "warning", "ADB_MISSING",
            adb.get("reason", "未找到 adb") + "：设备推送/拉取不可用；查询/整理不受影响")

    lens_ready = (research_kit and kit_scripts["lens_batch.mjs"]
                  and bool(node_kit["available"]) and kit_major >= 24 and browser_ok)
    media_ready = (research_kit and kit_scripts["media_probe.py"]
                   and ffmpeg_kit_ok and ffprobe_kit_ok)
    components = {
        "lensBatch": {
            "status": "available" if lens_ready else ("partial" if research_kit else "unavailable"),
            "reason": "" if lens_ready else
                      "需要 research_kit/lens_batch.mjs、Node 24+"
                      "（QIMENG_RESEARCH_KIT_NODE 或 PATH）与 Edge/Chrome/Chromium",
        },
        "mediaProbe": {
            "status": "available" if media_ready else ("partial" if research_kit else "unavailable"),
            "reason": "" if media_ready else
                      "需要 research_kit/media_probe.py 与 ffmpeg/ffprobe"
                      "（QIMENG_RESEARCH_KIT_FFMPEG/_FFPROBE 或 PATH）",
        },
        "dhashCompare": {
            "status": ("available" if research_kit and kit_scripts["dhash_compare.py"]
                       and ffmpeg_kit_ok else "unavailable"),
            "reason": ("" if research_kit and kit_scripts["dhash_compare.py"]
                       and ffmpeg_kit_ok else
                       "需要 research_kit/dhash_compare.py 与 ffmpeg"
                       "（QIMENG_RESEARCH_KIT_FFMPEG 或 PATH）"),
        },
        "avatarFetch": {
            "status": "available" if research_kit and kit_scripts["avatar_fetch.py"] else "unavailable",
            "reason": ("" if research_kit and kit_scripts["avatar_fetch.py"] else
                       "需要 research_kit/avatar_fetch.py；--to-jpeg 另需 ffmpeg"),
        },
        "yandexCbir": {
            "status": "available" if research_kit and kit_scripts["yandex_cbir.py"] else "unavailable",
            "reason": ("" if research_kit and kit_scripts["yandex_cbir.py"] else
                       "需要 research_kit/yandex_cbir.py（Python 标准库）"),
        },
        "saucenaoBatch": {
            "status": ("available" if research_kit and kit_scripts["saucenao_batch.py"]
                       else "unavailable"),
            "reason": ("" if research_kit and kit_scripts["saucenao_batch.py"] else
                       "需要 research_kit/saucenao_batch.py（Python 标准库）"),
        },
        "authorProbe": {
            "status": ("available" if research_kit and kit_scripts["fxtwitter_probe.py"]
                       and kit_scripts["kemono_probe.py"] else "unavailable"),
            "reason": ("" if research_kit and kit_scripts["fxtwitter_probe.py"]
                       and kit_scripts["kemono_probe.py"] else
                       "需要 research_kit 的 fxtwitter_probe.py 与 kemono_probe.py"),
        },
        "imagePipeline": {
            "status": ("available" if research_kit and kit_scripts["run_pipeline.py"]
                       else "unavailable"),
            "reason": ("" if research_kit and kit_scripts["run_pipeline.py"] else
                       "需要 research_kit/run_pipeline.py；依赖可用性仍按各阶段检查"),
        },
    }
    local_available = all(item["status"] == "available" for item in components.values())
    capabilities = {
        "api": {
            "status": "configured" if api_ok else "unavailable",
            "reason": "" if api_ok else "缺少或非法的 QIMENG_API_BASE / QIMENG_API_TOKEN",
        },
        "research.local": {
            "status": ("available" if local_available
                       else "partial" if research_kit else "unavailable"),
            "components": components,
        },
        "research.lens.api": {
            "status": "unverified",
            "reason": ("需服务端 research.enabled=true 且本机 sidecar 可用；"
                       "recipe 的 lens 能力还需 QIMENG_AI_LENS_ENABLED=1；工具端不伪装可用"),
        },
        "device.adb": {
            "status": ("available" if adb["available"] and adb_serial
                       else "partial" if adb["available"] else "unavailable"),
            "reason": ("" if adb["available"] and adb_serial
                       else "需要 adb 与显式 QIMENG_ADB_SERIAL" if adb["available"]
                       else "未找到 adb"),
        },
    }

    online_report = None
    exit_code = 2 if any(item["class"] == "base" and item["status"] == "error" for item in checks) else 0
    if online:
        if not api_ok:
            online_report = {"attempted": False, "reason": "API 基础配置缺失或非法"}
        else:
            online_report = {}
            for name, path in (("healthz", "/api/v1/healthz"), ("readyz", "/api/v1/readyz")):
                result = _online_get(base_value, token_value, path)
                online_report[name] = result
                if result["ok"]:
                    add("online." + name, "online", "ok", "ONLINE_OK",
                        f"{name} 返回 HTTP {result['status']}（{result['latencyMs']} ms）")
                else:
                    status_text = ("HTTP " + str(result["status"])) if result.get("status") else result.get("error") or "不可达"
                    add("online." + name, "online", "error", "ONLINE_FAILED",
                        f"{name} 检查失败：{status_text}")
            if any(not item["ok"] for key, item in online_report.items()
                   if key in ("healthz", "readyz")):
                if exit_code == 0:
                    exit_code = 1
                capabilities["api"]["status"] = "unreachable"
                capabilities["api"]["reason"] = "healthz/readyz 未通过"
            else:
                capabilities["api"]["status"] = "reachable"
                capabilities["api"]["reason"] = ""
    return _doctor_report(mode, checks, capabilities, online_report, exit_code)


def _doctor_report(mode: str, checks: list[dict], capabilities: dict,
                   online: dict | None, exit_code: int) -> dict:
    return {
        "schemaVersion": 1,
        "mode": mode,
        "ok": exit_code == 0,
        "exitCode": exit_code,
        "checks": checks,
        "capabilities": capabilities,
        "online": online,
    }


def render_doctor(report: dict) -> str:
    markers = {"ok": "[通过]", "warning": "[警告]", "error": "[错误]", "unknown": "[未知]"}
    lines = [
        "AI 工具包诊断（" + ("发布物" if report.get("mode") == "bundle" else "仓库源") + "）："
        + ("通过" if report.get("ok") else "存在问题")
    ]
    for check in report.get("checks") or []:
        if not isinstance(check, dict):
            continue
        lines.append(markers.get(check.get("status"), "[未知]") + " " + str(check.get("message") or ""))
    capabilities = report.get("capabilities") or {}
    if capabilities:
        lines.append("能力状态：")
        for name in sorted(capabilities):
            entry = capabilities[name]
            if isinstance(entry, dict):
                reason = entry.get("reason")
                lines.append("- " + name + "：" + str(entry.get("status") or "unknown")
                             + (("（" + str(reason) + "）") if reason else ""))
                components = entry.get("components")
                if isinstance(components, dict):
                    for component in sorted(components):
                        detail = components[component]
                        if isinstance(detail, dict):
                            component_reason = detail.get("reason")
                            lines.append("  - " + component + "："
                                         + str(detail.get("status") or "unknown")
                                         + (("（" + str(component_reason) + "）")
                                            if component_reason else ""))
    online = report.get("online")
    if online:
        for name in ("healthz", "readyz"):
            entry = online.get(name)
            if isinstance(entry, dict):
                lines.append("- online." + name + "：" + str(entry.get("status") or entry.get("error") or "unknown"))
    lines.append("退出码：" + str(report.get("exitCode")))
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="qimeng-ai-toolkit-package",
        description="构建、核验和诊断最小可移植 AI 工具包（仅标准库）。",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    build = sub.add_parser("build", help="在空目录生成 qimeng-ai-toolkit-<version>/")
    build.add_argument("--out", required=True, metavar="DIR", help="空输出目录")
    build.add_argument("--without-research-kit", action="store_true",
                       help="不包含可选 tools/research_kit")
    build.add_argument("--without-docs", action="store_true",
                       help="不包含额外 docs/AI_PLATFORM_DISTRIBUTION.md")
    build.add_argument("--json", action="store_true", help="输出 JSON 摘要")

    verify = sub.add_parser("verify", help="逐文件核对 source-manifest.json")
    verify.add_argument("--bundle", required=True, metavar="DIR", help="工具包目录")
    verify.add_argument("--json", action="store_true", help="输出 JSON（默认人读摘要）")

    doctor_parser = sub.add_parser("doctor", help="检查基础/可选依赖与工具包完整性")
    doctor_parser.add_argument("--bundle", metavar="DIR", help="工具包目录；缺省检查当前仓库")
    doctor_parser.add_argument("--online", action="store_true",
                               help="仅额外调用 healthz/readyz，不打印 token、不写数据")
    doctor_parser.add_argument("--json", action="store_true", help="输出 JSON")

    manifest = sub.add_parser("manifest", help="重建并打印来源清单，不覆盖已有文件")
    manifest.add_argument("--bundle", required=True, metavar="DIR", help="工具包目录")
    manifest.add_argument("--out", metavar="PATH", help="写入新的清单文件；拒绝覆盖已有文件")
    return parser


def _render_verify(result: dict) -> str:
    lines = ["清单核验：" + ("通过" if result.get("ok") else "失败")
             + f"（核对 {result.get('checked', 0)} 个文件）"]
    for name in ("missing", "extra"):
        values = result.get(name) or []
        if values:
            lines.append(name + "：" + ", ".join(str(value) for value in values[:20])
                         + (" ..." if len(values) > 20 else ""))
    for item in (result.get("modified") or [])[:20]:
        lines.append("modified：" + str(item.get("path")) + "（" + str(item.get("reason")) + "）")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "build":
            result = build_bundle(
                args.out,
                include_research_kit=not args.without_research_kit,
                include_docs=not args.without_docs,
            )
            if args.json:
                print(json.dumps(result, ensure_ascii=False, indent=2))
            else:
                print("已生成 " + result["bundle"] + "："
                      + str(result["files"]) + " 个文件；research_kit="
                      + ("是" if result["researchKit"] else "否")
                      + "；docs=" + ("是" if result["docs"] else "否"))
            return 0
        if args.command == "verify":
            result = verify_bundle(args.bundle)
            if args.json:
                print(json.dumps(result, ensure_ascii=False, indent=2))
            else:
                sys.stdout.write(_render_verify(result))
            return 0 if result["ok"] else 1
        if args.command == "doctor":
            report = doctor(args.bundle, online=args.online)
            if args.json:
                print(json.dumps(report, ensure_ascii=False, indent=2))
            else:
                sys.stdout.write(render_doctor(report))
            return report["exitCode"]
        if args.command == "manifest":
            manifest = manifest_for_bundle(args.bundle)
            text = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
            if args.out:
                target = Path(args.out)
                if target.exists() or target.is_symlink():
                    raise PackageError("OUT_EXISTS", "清单输出文件已存在；拒绝覆盖", exit_code=2)
                try:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with target.open("x", encoding="utf-8", newline="\n") as stream:
                        stream.write(text)
                except OSError:
                    raise PackageError("OUT_WRITE_FAILED", "无法写入清单输出文件", exit_code=2) from None
            sys.stdout.write(text)
            return 0
    except PackageError as exc:
        print(json.dumps({"error": exc.code, "message": str(exc)},
                         ensure_ascii=False), file=sys.stderr)
        return exc.exit_code
    except OSError:
        print(json.dumps({"error": "IO_ERROR", "message": "文件操作失败"},
                         ensure_ascii=False), file=sys.stderr)
        return 1
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
