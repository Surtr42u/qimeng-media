"""MCP stdio 适配器（JSON-RPC 2.0 最小可用子集，标准库）。

与 CLI 共用同一注册表与 execution.execute_tool：工具发现、输入 Schema、
确认与错误语义完全一致；不注册 shell/任意 Python/任意文件执行工具，也
不新增写入通道。管理员 token 只由进程环境继承，工具参数中不允许出现 token，
任何响应/错误都不回显 token。

支持的方法：
- initialize：返回 protocolVersion/capabilities/serverInfo；
- notifications/initialized：无响应通知；
- ping：返回空结果；
- tools/list：从注册表导出 name/description/inputSchema；
- tools/call：dispatch 到注册表；结果以 content[0].text（结构化 JSON 文本）
  + structuredContent + isError 返回；工具调用有超时（默认至少120s，并覆盖声明的工具执行预算，可用
  QIMENG_AI_MCP_TIMEOUT 覆盖）并尽力响应 notifications/cancelled；
- 收到 tools/call 后由独立线程执行，主循环继续读取 stdin，因此调用期间仍能
  及时处理取消/初始化等通知；有副作用工具被取消时不提前返回（避免“已取消”
  与实际继续写入的不确定状态），只读工具可立即返回 -32800；
- 其他方法：-32601 Method not found；非法参数/未知工具：-32602。

默认full提供稳定完整工具清单；显式lazy时从发现工具开始，context/describe/recipe
成功后发布相关工具。lazy要求宿主刷新列表；实际 token/费用仍须
宿主遥测，不能由 JSON 字节推算。领域过滤时始终保留安全发现元工具
（全部 `platform.*` 与 `files.storages`），`tools/list` 与 `tools/call` 使用同一可见
集合，不扩大任何执行权限。
"""
from __future__ import annotations

import json
import math
import os
import queue
import sys
import threading
import time

if __package__ in (None, ""):  # 支持 python tools/ai_maintenance/mcp_server.py 直接运行
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    __package__ = "ai_maintenance"

from . import client as http_client
from . import errors, execution, registry, model_output, versions

SERVER_NAME = "qimeng-ai-toolkit"
SERVER_VERSION = versions.PLATFORM_VERSION
PROTOCOL_VERSION = "2025-06-18"
SUPPORTED_PROTOCOL_VERSIONS = ("2024-11-05", "2025-03-26", "2025-06-18")
DEFAULT_CALL_TIMEOUT_SECONDS = 120.0
# Research cleanup can wait 10s for process exit and two 5s pipe pumps; leave 5s
# for output collection as well, rather than timing out while cleanup runs.
HANDLER_COMPLETION_GRACE_SECONDS = 25.0
CANCEL_REQUEST_METHOD = "notifications/cancelled"
INITIALIZED_NOTIFICATION = "notifications/initialized"
# 领域过滤下始终保留的安全发现元工具（只读、纯本地）：不因领域白名单隐藏，
# 否则客户端无法发现/读取上下文与规则；它们不扩大执行权限。
DISCOVERY_META_PREFIX = "platform."
DISCOVERY_META_TOOLS = frozenset({"files.storages"})

PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603
CALL_TIMEOUT = -32001
CALL_CANCELLED = -32800


def _log(message: str) -> None:
    # 协议消息只走 stdout；诊断信息走 stderr，且不得包含 token/载荷正文。
    sys.stderr.write(message.rstrip() + "\n")


def _error_response(request_id, code: int, message: str, data=None) -> dict:
    error = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    return {"jsonrpc": "2.0", "id": request_id, "error": error}


def _result_response(request_id, result) -> dict:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def _tool_descriptor(spec: registry.ToolSpec, document, *,
                     granted_permissions=None, env=None) -> dict:
    summary = registry.tool_summary(spec, document,
                                    granted_permissions=granted_permissions, env=env)
    description = summary["description"]
    if summary["status"] != "supported":
        description += f"（当前状态：{summary['status']}）"
    return {
        "name": spec.id,
        "description": description,
        "inputSchema": spec.input_schema,
        "annotations": {
            "title": spec.id,
            "readOnlyHint": spec.read_only,
            "destructiveHint": spec.side_effects,
            "idempotentHint": not spec.side_effects,
            "openWorldHint": spec.network,
        },
        # MCP 允许的 _meta：领域/版本用于客户端分组展示，不改变标准 tools/list 行为。
        "_meta": {"domain": spec.domain, "toolVersion": spec.version,
                  "requiresConfirmation": spec.requires_confirmation},
    }


def _is_discovery_meta_tool(tool_id: str) -> bool:
    """安全发现元工具：platform.* 元工具与 files.storages（只读、纯本地）。"""
    return tool_id.startswith(DISCOVERY_META_PREFIX) or tool_id in DISCOVERY_META_TOOLS


class McpServer:
    """可注入 stdin/stdout/execute 的 MCP 服务实现，便于合成客户端测试。"""

    def __init__(self, stdin=None, stdout=None, env=None, execute=None, timeout_seconds=None):
        self.stdin = stdin or sys.stdin
        self.stdout = stdout or sys.stdout
        self.env = dict(os.environ if env is None else env)
        self._execute = execute or self._default_execute
        self.timeout_seconds = float(
            timeout_seconds if timeout_seconds is not None
            else self.env.get("QIMENG_AI_MCP_TIMEOUT", DEFAULT_CALL_TIMEOUT_SECONDS))
        if not math.isfinite(self.timeout_seconds) or self.timeout_seconds <= 0:
            raise errors.configuration_error("QIMENG_AI_MCP_TIMEOUT 必须是有限正数秒")
        self._explicit_timeout = timeout_seconds is not None or "QIMENG_AI_MCP_TIMEOUT" in self.env
        self.config = None
        self._config_lock = threading.Lock()
        self.discovery = self.env.get("QIMENG_AI_MCP_DISCOVERY", "full")
        if self.discovery not in ("lazy", "full"):
            raise errors.configuration_error("QIMENG_AI_MCP_DISCOVERY 必须为 lazy 或 full")
        self._published = {spec.id for spec in registry.TOOLS
                           if _is_discovery_meta_tool(spec.id) or spec.id == "health.check"}
        self._publication_lock = threading.Lock()
        self._initialized = False
        # 写 stdout 与工具执行线程分离：调用期间主循环仍可处理取消通知。
        self._write_lock = threading.Lock()
        self._pending_calls: list[threading.Thread] = []
        self._shutdown_deadline = 0.0
        # 并发上限：主循环异步化后可能同时堆积多个 tools/call；默认 4，可用
        # QIMENG_AI_MCP_MAX_CONCURRENCY 调整。有副作用工具串行执行，避免两个
        # 写计划同时通过前置校验后并发落盘。
        try:
            max_concurrency = int(self.env.get("QIMENG_AI_MCP_MAX_CONCURRENCY", "4"))
        except ValueError:
            max_concurrency = 4
        self._call_slots = threading.Semaphore(max(1, min(max_concurrency, 32)))
        self._write_tool_lock = threading.Lock()

    # ---- 协议无关的辅助 ---------------------------------------------------- #

    def _load_config(self):
        with self._config_lock:
            if self.config is None:
                self.config = execution.load_config(self.env)
            return self.config

    def _default_execute(self, tool_id: str, payload: dict):
        return execution.execute_tool(tool_id, payload, env=self.env, config=self._load_config())

    def handle_message(self, message: dict, cancelled: set) -> dict | None:
        """处理一条 JSON-RPC 消息；通知返回 None。异常在内部转为错误响应。"""
        if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
            return _error_response(None, INVALID_REQUEST, "Invalid Request")
        method = message.get("method")
        request_id = message.get("id")
        is_notification = "id" not in message
        try:
            if method == CANCEL_REQUEST_METHOD:
                params = message.get("params") or {}
                cancel_id = params.get("requestId")
                if cancel_id is not None:
                    cancelled.add(cancel_id)
                return None
            if method == INITIALIZED_NOTIFICATION:
                self._initialized = True
                return None
            if is_notification:
                return None
            if method == "initialize":
                return _result_response(request_id, self._initialize(message.get("params") or {}))
            if method == "ping":
                return _result_response(request_id, {})
            if method == "tools/list":
                return _result_response(request_id, self._tools_list())
            if method == "tools/call":
                return self._tools_call(request_id, message.get("params") or {}, cancelled)
            return _error_response(request_id, METHOD_NOT_FOUND, f"Method not found: {method}")
        except errors.ToolError as exc:
            return _error_response(request_id, INVALID_PARAMS, exc.message, exc.to_dict())
        except Exception as exc:  # noqa: BLE001 - 协议层兜底，不输出敏感正文
            _log("internal error: " + type(exc).__name__)
            return _error_response(request_id, INTERNAL_ERROR, "Internal error")

    def _initialize(self, params: dict) -> dict:
        requested = params.get("protocolVersion")
        if requested in SUPPORTED_PROTOCOL_VERSIONS:
            version = requested
        else:
            version = PROTOCOL_VERSION
        self._initialized = True
        return {
            "protocolVersion": version,
            "capabilities": {"tools": {"listChanged": self.discovery == "lazy"}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            "instructions": (
                "绮梦影库 AI 工具平台。已有任务先用 platform.context(task)，如 author.profile/"
                "author.discover/media.ingest/media.batch/device.sync；简单查询可按领域取 context。"
                "该操作自动公布相关工具；取单工具 platform.tool.describe(toolId) 也会公布它，"
                "仅显式lazy模式动态发布并通知tools/list_changed。"
                "工具较多时也可先调用 platform.catalog 取精简领域目录，再用 platform.tool.describe "
                "按需取单个工具的 Schema/示例，用 platform.rules 按需读规则节，需要工作流模板时用 "
                "platform.playbook（可选模板，不是唯一路径）；默认不要读取全量规则包。"
                "默认full提供稳定完整工具清单，不依赖宿主刷新；支持动态发现的宿主可显式选择lazy。"
                "QIMENG_AI_MCP_DOMAINS 服务端过滤只是减少暴露面，不能当作上下文节省保证。"
                "本地文件用 files.storages/files.search 发现（只读、有界、不返回绝对路径）；"
                "fileRef 失效需重新搜索，不要手工拼接。"
                "大结果有 resultRef/truncated，用 platform.result.read 取必要部分；"
                "prepare 返回 planRef 时直接传给 apply，不抄写或重造计划。"
                "写入须在可信配置授权范围内，confirm=true 不授予新权限；"
                "看见工具不代表有权执行（权限由可信执行端强制）；不得声称执行了 planned/已禁用工具；"
                "不得在参数中放 token。"
            ),
        }

    def _allowed_tools(self) -> list:
        """QIMENG_AI_MCP_DOMAINS 只暴露相关领域，减少 tools/list 与误调用面。

        安全发现元工具（全部 platform.* 与 files.storages）始终可见：它们只读、
        纯本地，不扩大执行权限；tools/list 与 tools/call 共用本方法。
        """
        raw = str(self.env.get("QIMENG_AI_MCP_DOMAINS", "")).strip()
        if not raw:
            return list(registry.TOOLS)
        allowed = {item.strip() for item in raw.split(",") if item.strip()}
        return [spec for spec in registry.TOOLS
                if spec.domain in allowed or _is_discovery_meta_tool(spec.id)]

    def _visible_tools(self) -> list:
        tools = self._allowed_tools()
        if self.discovery == "full":
            return tools
        with self._publication_lock:
            return [spec for spec in tools if spec.id in self._published]

    def _publish_discovered(self, tool_id, result):
        if self.discovery != "lazy" or not isinstance(result, dict):
            return
        ids = []
        if tool_id == "platform.tool.describe":
            ids = [(result.get("tool") or {}).get("id")]
        elif tool_id == "platform.context":
            ids = [item.get("id") for item in result.get("tools", [])]
        elif tool_id == "platform.recipe":
            card = result.get("card") or {}
            ids = [item.get("action") for item in card.get("steps", [])]
        allowed = {spec.id for spec in self._allowed_tools()}
        with self._publication_lock:
            added = set(ids) & allowed - self._published
            self._published.update(added)
        if added:
            self._write({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})

    def _scope_discovery_result(self, tool_id, result):
        if tool_id not in ("platform.catalog", "platform.context", "platform.tool.describe") \
                or not isinstance(result, dict):
            return result
        if not self.env.get("QIMENG_AI_MCP_DOMAINS", "").strip():
            return result
        allowed = {spec.id for spec in self._allowed_tools()}

        def annotated(item):
            if not isinstance(item, dict):
                return item
            visible = item.get("id") in allowed
            projected = {**item, "exposedInConnection": visible}
            if not visible:
                projected.update(disabled=True, enabled=False,
                                 disabledReason="当前MCP领域配置未暴露此工具；目录可见不代表本连接可调用")
            return projected

        projected = dict(result)
        if isinstance(result.get("tools"), list):
            projected["tools"] = [annotated(item) for item in result["tools"]]
        if isinstance(result.get("tool"), dict):
            projected["tool"] = annotated(result["tool"])
        if isinstance(result.get("next"), list):
            projected["next"] = [name for name in result["next"] if name in allowed]
        return projected

    def _tools_list(self) -> dict:
        config = self._load_config()
        document = config.document()
        return {"tools": [
            _tool_descriptor(spec, document,
                             granted_permissions=config.granted_permissions,
                             env=self.env)
            for spec in self._visible_tools()
        ]}

    def _call_timeout(self, spec, arguments):
        if self._explicit_timeout:
            return self.timeout_seconds
        handler_timeout = spec.timeout_seconds
        declared = spec.input_schema.get("properties", {}).get("timeoutSeconds", {})
        requested = arguments.get("timeoutSeconds")
        if declared and type(requested) is int \
                and declared.get("minimum", 1) <= requested <= declared.get("maximum", 900):
            handler_timeout = max(handler_timeout, requested)
        return max(self.timeout_seconds, handler_timeout + HANDLER_COMPLETION_GRACE_SECONDS)

    def _tools_call(self, request_id, params: dict, cancelled: set) -> dict:
        name = params.get("name")
        arguments = params.get("arguments", {})
        if not isinstance(name, str) or not name:
            return _error_response(request_id, INVALID_PARAMS, "tools/call 缺少 name")
        if not isinstance(arguments, dict):
            return _error_response(request_id, INVALID_PARAMS, "arguments 必须是对象")
        if request_id in cancelled:
            cancelled.discard(request_id)
            return _error_response(request_id, CALL_CANCELLED, "Request cancelled")
        spec = registry.get(name)
        visible = {item.id for item in self._visible_tools()}
        if spec is None or name not in visible:
            return _error_response(request_id, INVALID_PARAMS,
                                   "Unknown tool: " + name)
        outcome: queue.Queue = queue.Queue(maxsize=1)

        def worker():
            try:
                if spec.side_effects or not spec.read_only:
                    # 有副作用工具全局串行：异步 dispatch 后不允许两个写计划
                    # 同时执行（客户端并发/多标签页场景）。
                    with self._write_tool_lock:
                        outcome.put(("ok", self._execute(name, arguments)))
                else:
                    outcome.put(("ok", self._execute(name, arguments)))
            except errors.ToolError as exc:
                outcome.put(("tool_error", exc))
            except (http_client.HTTPStatusError, http_client.NetworkError, http_client.ResponseError) as exc:
                outcome.put(("remote_error", exc))
            except Exception as exc:  # noqa: BLE001
                outcome.put(("error", exc))

        thread = threading.Thread(target=worker, daemon=True, name="mcp-tool-call")
        thread.start()
        deadline = time.monotonic() + self._call_timeout(spec, arguments)
        cancel_noted = False
        while True:
            try:
                kind, payload = outcome.get(timeout=0.05)
                break
            except queue.Empty:
                if request_id in cancelled:
                    if spec.side_effects or not spec.read_only:
                        # 有副作用：不提前返回。客户端已发取消，但无法安全中断已开始
                        # 的写入；返回“已取消”会让调用方误以为没执行。这里等真实结果，
                        # 由调用方按幂等键/回读处理重复提交风险。
                        if not cancel_noted:
                            _log("cancel ignored for side-effect tool: " + name)
                            cancel_noted = True
                    else:
                        return _error_response(request_id, CALL_CANCELLED, "Request cancelled")
                if time.monotonic() >= deadline:
                    _log("tool call timeout: " + name)
                    return _error_response(request_id, CALL_TIMEOUT,
                                           "Tool call timed out: " + name,
                                           {"operationMayHaveCompleted": bool(spec.side_effects or not spec.read_only),
                                            "recovery": "按原操作ID回读结果，勿换ID或盲目重试。"})
        thread.join(timeout=0.1)
        # 完成后清掉取消标记：避免客户端复用 request id 时把新请求误判为已取消。
        cancelled.discard(request_id)
        if kind == "tool_error":
            return _result_response(request_id, _tool_error_result(payload))
        if kind == "remote_error":
            base = self.env.get("QIMENG_API_BASE", "")
            target = http_client.target_label(base) if base else "unknown"
            if isinstance(payload, http_client.HTTPStatusError):
                failure = {"error": "http_error", "status": payload.status,
                           "body": payload.body, "target": target}
            else:
                failure = {"error": payload.kind, "target": target}
            if getattr(payload, "operation_may_have_completed", False):
                failure["operationMayHaveCompleted"] = True
                failure["recovery"] = "按原操作ID回读，勿换ID或盲目重试。"
            return _result_response(request_id, _error_result(failure))
        if kind == "error":
            _log("tool failure: " + name + " " + type(payload).__name__)
            return _result_response(request_id, _tool_error_result(
                errors.internal_error("工具调用内部错误")))
        spec, result = payload
        result = self._scope_discovery_result(spec.id, result)
        self._publish_discovered(spec.id, result)
        is_error = isinstance(result, dict) and result.get("status") in ("partial", "failed")
        try:
            result = model_output.present_result(
                spec.id, result, env=self.env,
                rulepack_version=self._load_config().rulepack_version)
            text = json.dumps({"tool": spec.id, "toolVersion": spec.version,
                               "status": "error" if is_error else "ok", "result": result},
                              ensure_ascii=False)
        except Exception as exc:  # presentation occurs after a handler may have written data
            if spec.side_effects or not spec.read_only:
                result = model_output.presentation_failure(
                    spec.id, payload[1], exc, payload=arguments, env=self.env)
                _log("result presentation failed after execution: " + spec.id + " "
                     + result["presentationErrorCode"])
                text = json.dumps({"tool": spec.id, "toolVersion": spec.version,
                                   "status": "error" if is_error else "ok", "result": result},
                                  ensure_ascii=False)
            elif isinstance(exc, errors.ToolError):
                return _result_response(request_id, _tool_error_result(exc))
            else:
                return _result_response(request_id, _tool_error_result(
                    errors.internal_error("工具结果无法安全呈现")))
        return _result_response(request_id, {
            "content": [{"type": "text", "text": text}],
            "structuredContent": {"tool": spec.id, "status": "error" if is_error else "ok",
                                  "result": result},
            "isError": bool(is_error),
        })

    # ---- stdio 主循环 ------------------------------------------------------ #

    def serve_forever(self) -> int:
        cancelled: set = set()

        def dispatch(message: dict, needs_slot: bool) -> None:
            if needs_slot:
                self._call_slots.acquire()
            try:
                try:
                    response = self.handle_message(message, cancelled)
                except Exception as exc:  # noqa: BLE001 - 线程内兜底，不让进程静默退出
                    _log("dispatch error: " + type(exc).__name__)
                    response = _error_response(
                        message.get("id") if isinstance(message, dict) else None,
                        INTERNAL_ERROR, "Internal error")
                if response is not None:
                    self._write(response)
            finally:
                if needs_slot:
                    self._call_slots.release()

        for line in self.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except ValueError:
                self._write(_error_response(None, PARSE_ERROR, "Parse error"))
                continue
            is_async = (isinstance(message, dict)
                        and message.get("method") == "tools/call"
                        and "id" in message)
            if is_async:
                params = message.get("params") or {}
                name = params.get("name") if isinstance(params, dict) else None
                spec = registry.get(name) if isinstance(name, str) else None
                arguments = params.get("arguments", {}) if isinstance(params, dict) else {}
                if spec is not None and isinstance(arguments, dict):
                    self._shutdown_deadline = max(self._shutdown_deadline,
                        time.monotonic() + self._call_timeout(spec, arguments))
                # 工具调用独立线程执行，主循环不阻塞；notifications/cancelled 等
                # 通知在调用进行中即可被读取并写入共享 cancelled 集合。
                thread = threading.Thread(target=dispatch, args=(message, True),
                                          daemon=True, name="mcp-call-dispatch")
                thread.start()
                self._pending_calls.append(thread)
                self._pending_calls = [item for item in self._pending_calls if item.is_alive()]
            else:
                dispatch(message, False)
        # stdin 结束：给仍在执行的调用留出收尾/写出响应的机会（不无限等待）。
        deadline = max(time.monotonic() + max(1.0, self.timeout_seconds), self._shutdown_deadline)
        for thread in self._pending_calls:
            thread.join(timeout=max(0.0, deadline - time.monotonic()))
        return 0

    def _write(self, payload: dict) -> None:
        serialized = json.dumps(payload, ensure_ascii=False)
        with self._write_lock:
            self.stdout.write(serialized + "\n")
            self.stdout.flush()


def _tool_error_result(exc: errors.ToolError) -> dict:
    return _error_result(exc.to_dict())


def _error_result(payload: dict) -> dict:
    text = json.dumps(payload, ensure_ascii=False)
    return {
        "content": [{"type": "text", "text": text}],
        "structuredContent": payload,
        "isError": True,
    }


def main(argv=None) -> int:
    server = McpServer()
    return server.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
