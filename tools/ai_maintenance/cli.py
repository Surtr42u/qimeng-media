"""绮梦影库 AI 工具平台统一 CLI（标准库实现）。

两层入口共用同一注册表与 dispatch（ADR-0056 §2/§7）：
- 兼容层：既有只读子命令（context/health/libraries/assets/authors/
  author-profile/author-sources/research）保持原有参数、JSON 输出与退出码；
  内部改为通过 execution.execute_tool 调用注册表工具（skip_capability，保持旧行为）；
- 通用层：version、tools list [--json]、tools show <id>、
  tool call <id> --input-json|--input-file；与未来 MCP 适配器共用注册表。

token 只从 QIMENG_API_TOKEN 环境变量读取，不打印、不落盘、不进日志；
错误输出只有固定类别、host:port 与脱敏后的上游 body。
"""
from __future__ import annotations

import argparse
import json
import os
import sys

if __package__ in (None, ""):  # 支持 python tools/ai_maintenance/cli.py 直接运行
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    __package__ = "ai_maintenance"

from . import client as http_client
from . import errors, execution, recipes, registry, rulepack, schema
from .client import (ApiClient, ConfigError, HTTPStatusError, LENS_CONTENT_TYPES,  # noqa: F401
                     LENS_TIMEOUT_SECONDS, MAX_RESPONSE_BYTES, NetworkError, NoRedirect,
                     REQUEST_TIMEOUT_SECONDS, ResponseError, TOKEN_MAX_LENGTH,
                     read_config, target_label, validate_base_url)

DEFAULT_AUTHOR_PAGE_SIZE = 50
MAX_AUTHOR_PAGE_SIZE = 1000
ASSET_LIMIT_MAX = 200
ASSET_DEFAULT_LIMIT = 60
MAX_ASSET_PAGE = 50
MAX_LENS_BYTES = 20 << 20

RESULT_FAILURE_STATUSES = ("partial", "failed")


class UsageError(Exception):
    """命令行参数之间的约束错误（兼容层退出码 2）。"""


# --------------------------------------------------------------------------- #
# 参数解析
# --------------------------------------------------------------------------- #

def build_parser() -> argparse.ArgumentParser:
    json_parent = argparse.ArgumentParser(add_help=False)
    json_parent.add_argument(
        "--json",
        action="store_true",
        default=argparse.SUPPRESS,
        help="输出原始 JSON（研究命令默认人读摘要；只读查询命令本就输出 JSON）",
    )
    parser = argparse.ArgumentParser(
        prog="qimeng-ai-maintenance",
        description="绮梦影库 AI 工具平台 CLI：只读查询、工具发现、计划与受控写入。",
    )
    parser.add_argument("--json", action="store_true", help="输出原始 JSON（研究命令默认人读摘要）")
    sub = parser.add_subparsers(dest="command", required=True)

    context = sub.add_parser("context", help="输出规则包 JSON 或中文指南（纯本地）", parents=[json_parent])
    context.add_argument("--markdown", action="store_true", help="输出中文指南摘要")
    context.add_argument("--out", metavar="PATH", help="同时把规则包 JSON 写入文件")
    context.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    context.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")

    version = sub.add_parser("version", help="输出平台/工具契约/规则包版本与 OpenAPI 指纹（纯本地）", parents=[json_parent])
    version.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    version.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")

    tools = sub.add_parser("tools", help="工具注册表：list / show（纯本地）")
    tools_sub = tools.add_subparsers(dest="tools_command", required=True)
    tools_list = tools_sub.add_parser(
        "list", help="列出工具、状态、能力核对与 Schema；--compact/--domain 输出精简领域目录", parents=[json_parent])
    tools_list.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    tools_list.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")
    tools_list.add_argument("--compact", action="store_true",
                            help="只输出精简目录（无 Schema；工具多时优先）")
    tools_list.add_argument("--domain", metavar="DOMAIN",
                            help="只看某个领域：author/media/research/backup/device/maintenance/system")
    tools_list.add_argument("--include-deprecated", action="store_true", help="包含已废弃工具")
    tools_show = tools_sub.add_parser(
        "show", help="按需查看单个工具完整声明（Schema/示例/规则摘录）", parents=[json_parent])
    tools_show.add_argument("tool_id", metavar="TOOL_ID")
    tools_show.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    tools_show.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")

    catalog = sub.add_parser(
        "catalog", help="精简能力目录：按领域分组的工具元数据（无 Schema，纯本地）", parents=[json_parent])
    catalog.add_argument("--domain", metavar="DOMAIN",
                         help="只看某个领域：author/media/research/backup/device/maintenance/system")
    catalog.add_argument("--include-deprecated", action="store_true", help="包含已废弃工具")
    catalog.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    catalog.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")

    task_context = sub.add_parser(
        "task-context",
        help="按任务/领域加载相关工具、Schema 摘要、规则节与 playbook（纯本地）",
        parents=[json_parent],
    )
    task_context.add_argument("--task", metavar="TASK",
                              help="任务名/playbook/领域/工具 ID，如 media.organize、author.cleanup、backup.sync")
    task_context.add_argument("--domain", metavar="DOMAIN",
                              help="领域过滤：author/media/research/backup/device/maintenance/system")
    task_context.add_argument("--no-schemas", action="store_true", help="不返回工具 Schema 摘要")
    task_context.add_argument("--max-tools", type=int, metavar="N", help="最多返回工具数（默认 12，上限 24）")
    task_context.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    task_context.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")

    playbook = sub.add_parser(
        "playbook", help="工作流模板（可选）：列目录或按名称按需读取，不加载完整规则包", parents=[json_parent])
    playbook.add_argument("--name", metavar="NAME", help="playbook 名称，如 author.profile")
    playbook.add_argument("--domain", metavar="DOMAIN", help="按领域筛选")
    playbook.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    playbook.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")

    recipes_parser = sub.add_parser(
        "recipes", help="可执行编排配方：list / show（默认只读，不加载完整规则包）",
        parents=[json_parent])
    recipes_sub = recipes_parser.add_subparsers(dest="recipes_command", required=True)
    recipes_sub.add_parser("list", help="列出可用 recipe 与状态", parents=[json_parent])
    recipes_show = recipes_sub.add_parser("show", help="查看单个 recipe 完整定义", parents=[json_parent])
    recipes_show.add_argument("recipe_id", metavar="RECIPE_ID")

    task = sub.add_parser(
        "task", help="取 2–4KB 任务卡：步骤、工具、门禁、输入与执行命令（按需上下文入口）",
        parents=[json_parent])
    task.add_argument("recipe_id", metavar="RECIPE_ID")
    task.add_argument("--target", metavar="TARGET",
                      help="运行目标：pc / android-embedded / nas（默认 QIMENG_AI_TARGET 或 pc）")

    run = sub.add_parser(
        "run", help="执行 recipe：逐步执行、门禁、断点续跑与产物落盘",
        parents=[json_parent])
    run.add_argument("recipe_id", metavar="RECIPE_ID")
    run.add_argument("--input-json", metavar="JSON", help="输入 JSON 对象；传 - 从 stdin 读取")
    run.add_argument("--input-file", metavar="PATH", help="从文件读取输入 JSON（- 表示 stdin）")
    run.add_argument("--state-dir", metavar="PATH", help="运行目录（续跑时传同一目录）")
    run.add_argument("--yes", action="store_true", help="通过 recipe 的人工确认门（写入步骤）")
    run.add_argument("--dry-run", action="store_true", help="只校验与计划，不调用工具/原语")
    run.add_argument("--resume", action="store_true", help="从 state-dir 续跑已完成步骤")
    run.add_argument("--target", metavar="TARGET",
                     help="运行目标：pc / android-embedded / nas（默认 QIMENG_AI_TARGET 或 pc）")
    run.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    run.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")

    tool = sub.add_parser("tool", help="通用工具调用（机器可读入口）")
    tool_sub = tool.add_subparsers(dest="tool_command", required=True)
    tool_call = tool_sub.add_parser("call", help="调用工具；输入必须是 JSON 对象", parents=[json_parent])
    tool_call.add_argument("tool_id", metavar="TOOL_ID")
    tool_call.add_argument("--input-json", metavar="JSON", help="JSON 对象；传 - 从 stdin 读取")
    tool_call.add_argument("--input-file", metavar="PATH", help="从文件读取 JSON 对象（- 表示 stdin）")
    tool_call.add_argument("--openapi", metavar="PATH", help="覆盖 OpenAPI 路径（排障/验证）")
    tool_call.add_argument("--rules", metavar="PATH", help="覆盖 rules.json 路径（排障/验证）")

    health = sub.add_parser("health", help="GET /api/v1/healthz 或 /api/v1/readyz", parents=[json_parent])
    health.add_argument("--ready", action="store_true", help="使用 /api/v1/readyz")

    sub.add_parser("libraries", help="GET /api/v1/libraries", parents=[json_parent])

    assets = sub.add_parser("assets", help="GET /api/v1/assets（cursor + limit；--page 便利取页）", parents=[json_parent])
    assets.add_argument("--limit", "--size", dest="limit", type=int, metavar="N", help="每页数量 1..200（OpenAPI limit）")
    assets.add_argument("--page", type=int, metavar="N", help="页码，从 1 开始；顺序跟随 nextCursor，不发送 page 参数")
    assets.add_argument("--cursor", metavar="CURSOR", help="分页游标 nextCursor")
    assets.add_argument("--q", metavar="TEXT", help="全文搜索")
    assets.add_argument("--library-id", metavar="ID", help="库 ID")
    assets.add_argument("--media-type", choices=("image", "animated_image", "video"), help="媒体类型")
    assets.add_argument(
        "--sort",
        choices=("default", "fileDate", "addedDate", "viewCount", "playCount", "sizeBytes", "name", "favoriteAt"),
        help="排序键",
    )
    assets.add_argument("--order", choices=("asc", "desc"), help="排序方向")

    authors = sub.add_parser("authors", help="GET /api/v1/authors（--page/--size 为客户端切片）", parents=[json_parent])
    authors.add_argument("--page", type=int, metavar="N", help="页码，从 1 开始")
    authors.add_argument("--size", type=int, metavar="N", help="每页数量 1..1000")

    for command, help_text in (
        ("author-profile", "GET /api/v1/authors/{authorId}/profile"),
        ("author-sources", "GET /api/v1/authors/{authorId}/sources"),
    ):
        item = sub.add_parser(command, help=help_text, parents=[json_parent])
        item.add_argument("author_id", nargs="?", metavar="AUTHOR_ID", help="作者 ID（位置参数）")
        item.add_argument("--author-id", dest="author_id_option", metavar="AUTHOR_ID", help="作者 ID（选项）")

    research = sub.add_parser(
        "research", help="研究核验：context/provider/lens（服务端默认关闭）", parents=[json_parent]
    )
    research_sub = research.add_subparsers(dest="research_command", required=True)
    research_sub.add_parser("context", help="GET /api/v1/research/context", parents=[json_parent])
    provider = research_sub.add_parser(
        "provider", help="POST /api/v1/research/providers/{provider}/query", parents=[json_parent]
    )
    provider.add_argument(
        "--provider",
        required=True,
        metavar="NAME",
        help="provider 名：fxtwitter/pixiv/yandere/e621/wayback/steam/bing（矩阵以服务端为准）",
    )
    provider.add_argument(
        "--operation",
        required=True,
        metavar="OP",
        help="author|post|work|artist|tags|url|workshop|search（矩阵外由服务端 400）",
    )
    provider.add_argument("--target", required=True, metavar="VALUE", help="账号/ID/标签/URL/搜索词（1–500 字符）")
    provider.add_argument("--limit", type=int, metavar="N", help="期望条数 1..50（缺省 10；响应最多 20 条）")
    lens = research_sub.add_parser(
        "lens", help="POST /api/v1/research/lens（multipart file）", parents=[json_parent]
    )
    lens.add_argument("--file", required=True, metavar="PATH", help="本地图片：JPEG/PNG/WebP，≤20MiB")
    return parser


# --------------------------------------------------------------------------- #
# 兼容层：旧参数 → 工具 payload
# --------------------------------------------------------------------------- #

def _author_id(args) -> str:
    positional = getattr(args, "author_id", None)
    option = getattr(args, "author_id_option", None)
    values = [value for value in (positional, option) if value]
    if len(set(values)) > 1:
        raise UsageError("作者 ID 只能提供一次")
    value = values[0] if values else ""
    if not value:
        raise UsageError("缺少作者 ID：传位置参数或 --author-id")
    if len(value) > 512 or any(ord(ch) < 32 for ch in value):
        raise UsageError("作者 ID 非法")
    return value


def _legacy_tool_call(args) -> tuple[str, dict]:
    if args.command == "health":
        return "health.check", {"ready": bool(args.ready)}
    if args.command == "libraries":
        return "library.query", {}
    if args.command == "assets":
        if args.limit is not None and not 1 <= args.limit <= ASSET_LIMIT_MAX:
            raise UsageError(f"--limit/--size 必须在 1..{ASSET_LIMIT_MAX}")
        if args.page is not None and args.cursor:
            raise UsageError("--page 与 --cursor 不能同时使用")
        if args.page is not None and not 1 <= args.page <= MAX_ASSET_PAGE:
            raise UsageError(f"--page 必须在 1..{MAX_ASSET_PAGE}")
        payload = {
            "limit": args.limit,
            "cursor": args.cursor,
            "q": args.q,
            "libraryId": args.library_id,
            "mediaType": args.media_type,
            "sort": args.sort,
            "order": args.order,
            "page": args.page,
        }
        return "asset.query", {key: value for key, value in payload.items() if value is not None}
    if args.command == "authors":
        payload = {"page": args.page, "size": args.size}
        return "author.query", {key: value for key, value in payload.items() if value is not None}
    if args.command == "author-profile":
        return "author.profile.read", {"authorId": _author_id(args)}
    if args.command == "author-sources":
        return "author.sources.read", {"authorId": _author_id(args)}
    if args.command == "research":
        if args.research_command == "context":
            return "research.context", {}
        if args.research_command == "provider":
            payload = {"provider": args.provider, "operation": args.operation, "target": args.target}
            if args.limit is not None:
                payload["limit"] = args.limit
            return "research.provider.query", payload
        if args.research_command == "lens":
            return "research.lens", {"file": args.file}
        raise UsageError("未知研究命令")
    raise UsageError("未知命令")


def _render_research_context(data) -> str:
    if not isinstance(data, dict):
        raise ResponseError("invalid_json_response")
    enabled = data.get("enabled")
    lines = ["研究：" + ("已启用" if enabled is True else "未启用（research.enabled=false）" if enabled is False else "未知")]
    sidecar = data.get("sidecar") if isinstance(data.get("sidecar"), dict) else {}
    parts = [
        "已配置" if sidecar.get("configured") is True else "未配置",
        "可用" if sidecar.get("available") is True else "不可用",
    ]
    pending = sidecar.get("pending")
    if isinstance(pending, int) and not isinstance(pending, bool):
        parts.append(f"在途 {pending}")
    lines.append("sidecar：" + "，".join(parts))
    if sidecar.get("configured") is not True:
        lines.append("提示：sidecar 未配置时 Lens 返回 503 RESEARCH_SIDECAR_DISABLED；需另行部署本机 sidecar。")
    providers = data.get("providers")
    if isinstance(providers, list):
        lines.append("provider 矩阵：")
        for entry in providers:
            if not isinstance(entry, dict):
                continue
            name = entry.get("name") or "?"
            operations = [str(operation) for operation in entry.get("operations") or []]
            details = "、".join(operations) if operations else "（无 operation）"
            limits = entry.get("limits") if isinstance(entry.get("limits"), dict) else {}
            budget = []
            per_minute = limits.get("perMinute")
            if isinstance(per_minute, int) and not isinstance(per_minute, bool) and per_minute > 0:
                budget.append(f"每分钟 {per_minute} 次")
            per_day = limits.get("perDay")
            if isinstance(per_day, int) and not isinstance(per_day, bool) and per_day > 0:
                budget.append(f"每日 {per_day} 次")
            suffix = "；" + "，".join(budget) if budget else ""
            flag = "" if entry.get("enabled", True) else "（已禁用）"
            lines.append(f"- {name}{flag}：{details}{suffix}")
    limits = data.get("limits") if isinstance(data.get("limits"), dict) else {}
    if limits:
        lines.append(
            "Lens 限额：每小时 {} 次，每日 {} 次".format(
                limits.get("lensPerHour", "?"), limits.get("lensPerDay", "?")
            )
        )
    fetched_at = data.get("fetchedAt")
    if isinstance(fetched_at, str) and fetched_at:
        lines.append("读取时刻：" + fetched_at)
    if enabled is not True:
        lines.append("研究关闭时 provider 查询返回 503 RESEARCH_DISABLED；启用需服务端 research.enabled=true 与已部署 sidecar。")
    return "\n".join(lines) + "\n"


def _render_research_provider(data) -> str:
    if not isinstance(data, dict):
        raise ResponseError("invalid_json_response")
    target = data.get("target")
    header = "{}／{}".format(data.get("provider") or "?", data.get("operation") or "?")
    lines = [f"查询：{header}，target={target if isinstance(target, str) else '?'}"]
    lines.append("status：" + str(data.get("status")))
    items = data.get("items") if isinstance(data.get("items"), list) else []
    lines.append(f"items：{len(items)} 条")
    error = data.get("error")
    if isinstance(error, dict) and error:
        lines.append("error：{}：{}".format(error.get("code"), error.get("message")))
    latency = data.get("latencyMs")
    if isinstance(latency, int) and not isinstance(latency, bool):
        lines.append(f"耗时：{latency} ms")
    for item in items[:5]:
        if not isinstance(item, dict):
            continue
        title = item.get("title") or item.get("url") or "（无标题）"
        lines.append("  - " + " ".join(str(title).split()))
    if len(items) > 5:
        lines.append(f"  …另有 {len(items) - 5} 条，--json 查看全部")
    return "\n".join(lines) + "\n"


def _render_research_lens(data) -> str:
    if not isinstance(data, dict):
        raise ResponseError("invalid_json_response")
    lines = ["status：" + str(data.get("status"))]
    frame = data.get("frameSha256")
    if isinstance(frame, str) and frame:
        lines.append("frameSha256：" + frame)
    top_titles = data.get("topTitles") if isinstance(data.get("topTitles"), list) else []
    links = data.get("links") if isinstance(data.get("links"), list) else []
    lines.append(f"topTitles：{len(top_titles)} 条")
    lines.append(f"links：{len(links)} 条（best-effort，空数组不代表失败）")
    elapsed = data.get("elapsedMs")
    if isinstance(elapsed, int) and not isinstance(elapsed, bool):
        lines.append(f"耗时：{elapsed} ms")
    error = data.get("error")
    if isinstance(error, dict) and error:
        lines.append("error：{}：{}".format(error.get("code"), error.get("message")))
    return "\n".join(lines) + "\n"


def _render_research_summary(command: str, data) -> str:
    if command == "context":
        return _render_research_context(data)
    if command == "provider":
        return _render_research_provider(data)
    if command == "lens":
        return _render_research_lens(data)
    raise ResponseError("invalid_json_response")


# --------------------------------------------------------------------------- #
# 配置与输出辅助
# --------------------------------------------------------------------------- #

def _runtime_config(args, env):
    merged = dict(env)
    if getattr(args, "openapi", None):
        merged[execution.OPENAPI_ENV] = args.openapi
    if getattr(args, "rules", None):
        merged[execution.RULES_ENV] = args.rules
    return execution.load_config(merged)


def _print_json(value, stream) -> None:
    stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def _print_json_compact(value, stream) -> None:
    # 任务卡/结果摘要面向模型上下文：紧凑 JSON，减少空白 token。
    stream.write(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n")


def _tool_envelope(spec, result) -> dict:
    return {
        "tool": spec.id,
        "toolVersion": spec.version,
        "status": "ok",
        "result": result,
    }


def _human_tool_list(listing: dict) -> str:
    lines = [
        f"平台版本 {listing.get('platformVersion')}；工具契约 {listing.get('toolContractVersion')}；"
        f"规则包 {listing.get('rulepackVersion')}；工具 {len(listing.get('tools') or [])} 个",
    ]
    if listing.get("readOnlyMode"):
        lines.append("只读模式开启：全部有副作用工具已禁用。")
    for tool in listing.get("tools") or []:
        flags = []
        if tool.get("readOnly"):
            flags.append("只读")
        if tool.get("sideEffects"):
            flags.append("有副作用")
        if tool.get("requiresConfirmation"):
            flags.append("需确认")
        if not tool.get("enabled", True):
            flags.append("已禁用")
        reason = f"；{tool['reason']}" if tool.get("reason") else ""
        lines.append(f"- {tool['id']} [{tool['status']}] {'/'.join(flags)}{reason}")
    return "\n".join(lines) + "\n"


def _human_catalog(catalog: dict) -> str:
    lines = [
        f"平台版本 {catalog.get('platformVersion')}；工具契约 {catalog.get('toolContractVersion')}；"
        f"规则包 {catalog.get('rulepackVersion')}；工具 {catalog.get('toolCount')} 个；"
        f"领域 {len(catalog.get('domains') or [])} 个",
        "先用 catalog 找工具，再 tools show <id> 取 Schema/示例/规则摘录；看见不等于有权执行。",
    ]
    if catalog.get("readOnlyMode"):
        lines.append("只读模式开启：全部有副作用工具已禁用。")
    tools_by_id = {tool["id"]: tool for tool in catalog.get("tools") or []}
    for domain in catalog.get("domains") or []:
        ids = [tool_id for tool_id in domain.get("tools") or [] if tool_id in tools_by_id]
        if not ids:
            continue
        lines.append(f"[{domain['label']}] {domain['id']}（{len(ids)}）")
        for tool_id in ids:
            tool = tools_by_id[tool_id]
            flags = []
            if tool.get("readOnly"):
                flags.append("只读")
            if tool.get("requiresConfirmation"):
                flags.append("需确认")
            if not tool.get("enabled", True):
                flags.append("已禁用")
            if tool.get("deprecated"):
                flags.append("已废弃")
            reason = f"；{tool['reason']}" if tool.get("reason") else ""
            lines.append(f"  - {tool_id} [{tool['status']}] {'/'.join(flags)}{reason}")
    return "\n".join(lines) + "\n"


def _read_stdin_text() -> str:
    return sys.stdin.read()


def _tool_payload(args) -> dict:
    provided = [value for value in (args.input_json, args.input_file) if value is not None]
    if len(provided) != 1:
        raise UsageError("--input-json 与 --input-file 必须且只能提供一个")
    if args.input_json is not None:
        raw = _read_stdin_text() if args.input_json == "-" else args.input_json
    else:
        try:
            if args.input_file == "-":
                raw = _read_stdin_text()
            else:
                with open(args.input_file, "r", encoding="utf-8") as handle:
                    raw = handle.read()
        except OSError:
            raise UsageError("无法读取 --input-file") from None
    try:
        payload = json.loads(raw)
    except ValueError:
        raise UsageError("输入不是合法 JSON") from None
    if not isinstance(payload, dict):
        raise UsageError("工具输入必须是 JSON 对象")
    return payload


# --------------------------------------------------------------------------- #
# 子命令执行
# --------------------------------------------------------------------------- #

def _run_context(args) -> int:
    pack = rulepack.build_rulepack(openapi_path=args.openapi, rules_path=args.rules)
    if args.out:
        rulepack.write_pack(pack, args.out)
    if args.markdown:
        sys.stdout.write(rulepack.render_markdown(pack))
    else:
        sys.stdout.write(rulepack.dumps_pack(pack))
    return 0


def _run_version(args, env) -> int:
    config = _runtime_config(args, env)
    _print_json(execution.version_report(config), sys.stdout)
    return 0


def _catalog_payload(args) -> dict:
    payload = {}
    domain = getattr(args, "domain", None)
    if domain:
        payload["domain"] = domain
    if getattr(args, "include_deprecated", False):
        payload["includeDeprecated"] = True
    return payload


def _run_catalog(args, env) -> int:
    config = _runtime_config(args, env)
    _, catalog = execution.execute_tool("platform.catalog", _catalog_payload(args), config=config)
    if getattr(args, "json", False):
        _print_json(catalog, sys.stdout)
    else:
        sys.stdout.write(_human_catalog(catalog))
    return 0


def _run_task_context(args, env) -> int:
    payload = {}
    if getattr(args, "task", None):
        payload["task"] = args.task
    if getattr(args, "domain", None):
        payload["domain"] = args.domain
    if getattr(args, "no_schemas", False):
        payload["includeSchemas"] = False
    if getattr(args, "max_tools", None) is not None:
        payload["maxTools"] = args.max_tools
    config = _runtime_config(args, env)
    _, result = execution.execute_tool("platform.context", payload, config=config)
    _print_json(result, sys.stdout)
    return 0


def _run_playbook(args, env) -> int:
    config = _runtime_config(args, env)
    payload = {}
    if getattr(args, "name", None):
        payload["name"] = args.name
    if getattr(args, "domain", None):
        payload["domain"] = args.domain
    _, result = execution.execute_tool("platform.playbook", payload, config=config)
    _print_json(result, sys.stdout)
    return 0


def _run_tools(args, env) -> int:
    config = _runtime_config(args, env)
    if args.tools_command == "list":
        if getattr(args, "compact", False) or getattr(args, "domain", None):
            _, catalog = execution.execute_tool("platform.catalog", _catalog_payload(args),
                                                config=config)
            if getattr(args, "json", False):
                _print_json(catalog, sys.stdout)
            else:
                sys.stdout.write(_human_catalog(catalog))
            return 0
        listing = execution.tool_listing(config)
        if getattr(args, "json", False):
            _print_json(listing, sys.stdout)
        else:
            sys.stdout.write(_human_tool_list(listing))
        return 0
    spec = registry.get(args.tool_id)
    if spec is None:
        raise errors.unknown_tool(args.tool_id)
    described = registry.describe_spec(
        spec, config.document(), execution.disabled_reason(spec, config), rules=config.rules)
    _print_json(described, sys.stdout)
    return 0


def _run_recipes(args, env) -> int:
    if args.recipes_command == "list":
        _print_json({"recipes": recipes.list_recipes(env)}, sys.stdout)
        return 0
    _print_json({"recipe": recipes.get_recipe(args.recipe_id, env)}, sys.stdout)
    return 0


def _run_task(args, env) -> int:
    merged = dict(env)
    if getattr(args, "target", None):
        merged["QIMENG_AI_TARGET"] = args.target
    _print_json_compact({"card": recipes.task_card(args.recipe_id, merged)}, sys.stdout)
    return 0


def _run_recipe(args, env) -> int:
    merged = dict(env)
    if getattr(args, "target", None):
        merged["QIMENG_AI_TARGET"] = args.target
    if getattr(args, "input_json", None) is not None and getattr(args, "input_file", None) is not None:
        raise UsageError("--input-json 与 --input-file 只能提供一个")
    payload = {}
    if getattr(args, "input_json", None) is not None:
        raw = _read_stdin_text() if args.input_json == "-" else args.input_json
        try:
            payload = json.loads(raw)
        except ValueError:
            raise UsageError("输入不是合法 JSON") from None
    elif getattr(args, "input_file", None) is not None:
        try:
            raw = (_read_stdin_text() if args.input_file == "-"
                   else open(args.input_file, "r", encoding="utf-8").read())
        except OSError:
            raise UsageError("无法读取 --input-file") from None
        try:
            payload = json.loads(raw)
        except ValueError:
            raise UsageError("输入不是合法 JSON") from None
    if not isinstance(payload, dict):
        raise UsageError("recipe 输入必须是 JSON 对象")
    config = _runtime_config(args, merged)
    state_dir = args.state_dir
    if state_dir:
        from pathlib import Path
        state_dir = str(Path(state_dir).resolve())
    result = recipes.run_recipe(
        args.recipe_id, payload, env=merged, state_dir=state_dir,
        config=config, confirm=bool(args.yes), dry_run=bool(args.dry_run),
        resume=bool(args.resume),
    )
    _print_json(result, sys.stdout)
    return 0 if result.get("status") in ("completed", "awaiting_confirmation") else 1


def _run_tool_call(args, env) -> int:
    payload = _tool_payload(args)
    config = _runtime_config(args, env)
    spec, result = execution.execute_tool(args.tool_id, payload, env=env, config=config)
    _print_json(_tool_envelope(spec, result), sys.stdout)
    if isinstance(result, dict) and result.get("status") in RESULT_FAILURE_STATUSES:
        return 1
    return 0


def _run_legacy(args, env) -> int:
    # 兼容旧行为：网络命令先校验目标配置（错误只列变量名）。
    read_config()
    tool_id, payload = _legacy_tool_call(args)
    config = execution.load_config(env)
    spec, result = execution.execute_tool(tool_id, payload, env=env, config=config,
                                          skip_capability=True)
    if args.command == "research" and not getattr(args, "json", False):
        sys.stdout.write(_render_research_summary(args.research_command, result))
    else:
        _print_json(result, sys.stdout)
    return 0


def _legacy_error_payload(exc) -> dict:
    if exc.code == "INVALID_INPUT":
        return {"error": "usage_error", "message": exc.message}
    payload = exc.to_dict()
    if exc.code in ("NOT_CONFIRMED", "TOOL_DISABLED", "TOOL_NOT_IMPLEMENTED"):
        payload["error"] = "usage_error" if exc.code != "TOOL_NOT_IMPLEMENTED" else "tool_unavailable"
    return payload


def _error_target() -> str:
    try:
        configured_base, _token = read_config()
    except ConfigError:
        return "unknown"
    return target_label(configured_base)


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "context":
            return _run_context(args)
        env = dict(os.environ)
        if args.command == "version":
            return _run_version(args, env)
        if args.command == "catalog":
            return _run_catalog(args, env)
        if args.command == "task-context":
            return _run_task_context(args, env)
        if args.command == "playbook":
            return _run_playbook(args, env)
        if args.command == "recipes":
            return _run_recipes(args, env)
        if args.command == "task":
            return _run_task(args, env)
        if args.command == "run":
            return _run_recipe(args, env)
        if args.command == "tools":
            return _run_tools(args, env)
        if args.command == "tool":
            return _run_tool_call(args, env)
        return _run_legacy(args, env)
    except UsageError as exc:
        _print_json({"error": "usage_error", "message": str(exc)}, sys.stderr)
        return 2
    except ConfigError as exc:
        _print_json({"error": "configuration_error", "message": str(exc)}, sys.stderr)
        return 2
    except rulepack.RulepackError as exc:
        _print_json({"error": "rulepack_error", "message": str(exc)}, sys.stderr)
        return 2
    except recipes.RecipeError as exc:
        _print_json({"error": exc.code, "message": str(exc), "details": exc.details}, sys.stderr)
        return 2 if exc.code in ("RECIPE_INPUT_INVALID", "RECIPE_INVALID", "RECIPE_UNKNOWN",
                                 "RECIPE_CAPABILITY_MISSING", "RECIPE_CONFIRMATION_REQUIRED",
                                 "RECIPE_TEMPLATE") else 1
    except errors.ToolError as exc:
        payload = _legacy_error_payload(exc) if args.command in (
            "health", "libraries", "assets", "authors", "author-profile",
            "author-sources", "research") else exc.to_dict()
        _print_json(payload, sys.stderr)
        return exc.exit_code
    except HTTPStatusError as exc:
        _print_json({"status": exc.status, "error": "http_error", "body": exc.body,
                     "target": _error_target()}, sys.stderr)
        return 1
    except NetworkError as exc:
        _print_json({"error": exc.kind, "target": _error_target()}, sys.stderr)
        return 1
    except ResponseError as exc:
        _print_json({"error": exc.kind, "target": _error_target()}, sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())