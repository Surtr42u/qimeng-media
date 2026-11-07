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
  + structuredContent + isError 返回；工具调用有超时（默认 120s，可用
  QIMENG_AI_MCP_TIMEOUT 覆盖）并尽力响应 notifications/cancelled；
- 其他方法：-32601 Method not found；非法参数/未知工具：-32602。
"""
from __future__ import annotations

import json
import os
import queue
import sys
import threading
import time

if __package__ in (None, ""):  # 支持 python tools/ai_maintenance/mcp_server.py 直接运行
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    __package__ = "ai_maintenance"

from . import errors, execution, registry

SERVER_NAME = "qimeng-ai-toolkit"
SERVER_VERSION = "1.0.0"
PROTOCOL_VERSION = "2025-06-18"
SUPPORTED_PROTOCOL_VERSIONS = ("2024-11-05", "2025-03-26", "2025-06-18")
DEFAULT_CALL_TIMEOUT_SECONDS = 120.0
CANCEL_REQUEST_METHOD = "notifications/cancelled"
INITIALIZED_NOTIFICATION = "notifications/initialized"

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


def _tool_descriptor(spec: registry.ToolSpec, document) -> dict:
    summary = registry.tool_summary(spec, document)
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
        self.config = None
        self._initialized = False

    # ---- 协议无关的辅助 ---------------------------------------------------- #

    def _load_config(self):
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
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            "instructions": (
                "绮梦影库 AI 工具平台。工具较多时先调用 platform.catalog 取精简领域目录（无 Schema），"
                "再用 platform.tool.describe 按需取单个工具的 Schema/示例，用 platform.rules 按需读规则节；"
                "tools/list 仍保留完整工具清单供标准客户端使用。写入工具必须先 prepare 计划、人工确认后"
                "带 confirm=true 与计划指纹调用 apply。看见工具不代表有权执行（权限由可信执行端强制）；"
                "不得声称执行了 planned/已禁用工具；不得在参数中放 token。"
            ),
        }

    def _tools_list(self) -> dict:
        document = self._load_config().document()
        return {"tools": [_tool_descriptor(spec, document) for spec in registry.TOOLS]}

    def _tools_call(self, request_id, params: dict, cancelled: set) -> dict:
        name = params.get("name")
        arguments = params.get("arguments", {})
        if not isinstance(name, str) or not name:
            return _error_response(request_id, INVALID_PARAMS, "tools/call 缺少 name")
        if not isinstance(arguments, dict):
            return _error_response(request_id, INVALID_PARAMS, "arguments 必须是对象")
        if request_id in cancelled:
            return _error_response(request_id, CALL_CANCELLED, "Request cancelled")
        spec = registry.get(name)
        if spec is None:
            return _error_response(request_id, INVALID_PARAMS,
                                   "Unknown tool: " + name)
        outcome: queue.Queue = queue.Queue(maxsize=1)

        def worker():
            try:
                outcome.put(("ok", self._execute(name, arguments)))
            except errors.ToolError as exc:
                outcome.put(("tool_error", exc))
            except Exception as exc:  # noqa: BLE001
                outcome.put(("error", exc))

        thread = threading.Thread(target=worker, daemon=True, name="mcp-tool-call")
        thread.start()
        deadline = time.monotonic() + self.timeout_seconds
        while True:
            try:
                kind, payload = outcome.get(timeout=0.05)
                break
            except queue.Empty:
                if request_id in cancelled:
                    return _error_response(request_id, CALL_CANCELLED, "Request cancelled")
                if time.monotonic() >= deadline:
                    _log("tool call timeout: " + name)
                    return _error_response(request_id, CALL_TIMEOUT,
                                           "Tool call timed out: " + name)
        thread.join(timeout=0.1)
        if kind == "tool_error":
            return _result_response(request_id, _tool_error_result(payload))
        if kind == "error":
            _log("tool failure: " + name + " " + type(payload).__name__)
            return _result_response(request_id, _tool_error_result(
                errors.internal_error("工具调用内部错误")))
        spec, result = payload
        try:
            text = json.dumps({"tool": spec.id, "toolVersion": spec.version,
                               "status": "ok", "result": result},
                              ensure_ascii=False)
        except (TypeError, ValueError):
            return _result_response(request_id, _tool_error_result(
                errors.internal_error("工具结果无法序列化")))
        is_error = isinstance(result, dict) and result.get("status") in ("partial", "failed")
        return _result_response(request_id, {
            "content": [{"type": "text", "text": text}],
            "structuredContent": {"tool": spec.id, "status": "error" if is_error else "ok",
                                  "result": result},
            "isError": bool(is_error),
        })

    # ---- stdio 主循环 ------------------------------------------------------ #

    def serve_forever(self) -> int:
        cancelled: set = set()
        for line in self.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except ValueError:
                self._write(_error_response(None, PARSE_ERROR, "Parse error"))
                continue
            response = self.handle_message(message, cancelled)
            if response is not None:
                self._write(response)
        return 0

    def _write(self, payload: dict) -> None:
        self.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
        self.stdout.flush()


def _tool_error_result(exc: errors.ToolError) -> dict:
    text = json.dumps(exc.to_dict(), ensure_ascii=False)
    return {
        "content": [{"type": "text", "text": text}],
        "structuredContent": exc.to_dict(),
        "isError": True,
    }


def main(argv=None) -> int:
    server = McpServer()
    return server.serve_forever()


if __name__ == "__main__":
    sys.exit(main())