"""MCP stdio 适配器回归：两个独立合成客户端 + 协议错误/超时/取消/脱敏。

不启动真实 AI 客户端；仅在本机用 JSON-RPC 文本流与 stub HTTP 验证协议兼容。
Codex/Claude 等真实客户端接入未实测（见 README）。
"""
from __future__ import annotations

import io
import json
import threading
import time
import unittest
from dataclasses import replace
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from ai_maintenance import mcp_server, model_output, registry

TOKEN = "-".join(("synthetic", "mcp", "credential"))


class StubHandler(BaseHTTPRequestHandler):
    routes = {}

    def do_GET(self):
        status, payload = type(self).routes.get(self.path, (404, {"code": "NOT_FOUND"}))
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *args):
        pass


class SyntheticMcpClient:
    """合成客户端 A：完整 stdio 帧序列（initialize → initialized → list → call → ping）。"""

    def __init__(self, env):
        self.env = env
        self.stdout = io.StringIO()

    def run(self, messages):
        stdin = io.StringIO("".join(json.dumps(message, ensure_ascii=False) + "\n"
                                    for message in messages))
        server = mcp_server.McpServer(stdin=stdin, stdout=self.stdout, env=self.env)
        server.serve_forever()
        return [json.loads(line) for line in self.stdout.getvalue().splitlines() if line.strip()]


class DirectMcpClient:
    """合成客户端 B：直接逐条调用 handle_message（不同初始化序列与调用方式）。"""

    def __init__(self, env):
        self.server = mcp_server.McpServer(env=env)
        self.cancelled = set()

    def request(self, message):
        return self.server.handle_message(message, self.cancelled)


class McpProtocolTest(unittest.TestCase):
    def test_http_failure_keeps_status_server_code_and_redacts_token(self):
        path = "/api/v1/authors/synthetic-http-error/profile"
        StubHandler.routes[path] = (403, {"code": "FORBIDDEN_SCOPE", "message": TOKEN})
        client = DirectMcpClient(self.env())
        client.request({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                        "params": {"protocolVersion": "2025-06-18"}})
        response = client.request({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                   "params": {"name": "author.profile.read",
                                              "arguments": {"authorId": "synthetic-http-error"}}})["result"]
        self.assertTrue(response["isError"])
        self.assertEqual(403, response["structuredContent"]["status"])
        self.assertEqual("FORBIDDEN_SCOPE", response["structuredContent"]["body"]["code"])
        self.assertNotIn(TOKEN, json.dumps(response))

    @classmethod
    def setUpClass(cls):
        cls.http = ThreadingHTTPServer(("127.0.0.1", 0), StubHandler)
        cls.http.daemon_threads = True
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.http.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()
        cls.thread.join(timeout=5)

    def env(self):
        return {"QIMENG_API_BASE": self.base, "QIMENG_API_TOKEN": TOKEN, "QIMENG_AI_GRANTED_PERMISSIONS": "all", "QIMENG_AI_MCP_DISCOVERY": "full"}

    def test_stdio_client_initializes_lists_and_calls_tools(self):
        responses = SyntheticMcpClient(self.env()).run([
            {"jsonrpc": "2.0", "id": 1, "method": "initialize",
             "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                        "clientInfo": {"name": "synthetic-a", "version": "0.1"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
             "params": {"name": "project.context", "arguments": {"markdown": True}}},
            {"jsonrpc": "2.0", "id": 4, "method": "ping"},
        ])
        self.assertEqual(4, len(responses))
        # tools/call 在独立线程执行，响应允许与 ping 乱序（JSON-RPC 以 id 匹配）；
        # 这里按 id 取响应，不依赖到达顺序。
        by_id = {response["id"]: response for response in responses}
        self.assertEqual({1, 2, 3, 4}, set(by_id))
        initialize, listing, call, ping = (by_id[1], by_id[2], by_id[3], by_id[4])
        self.assertEqual("2024-11-05", initialize["result"]["protocolVersion"])
        self.assertEqual("qimeng-ai-toolkit", initialize["result"]["serverInfo"]["name"])
        self.assertIn("tools", initialize["result"]["capabilities"])
        tool_names = [tool["name"] for tool in listing["result"]["tools"]]
        self.assertEqual([spec.id for spec in registry.TOOLS], tool_names)
        context_tool = next(tool for tool in listing["result"]["tools"]
                            if tool["name"] == "author.profile.update")
        self.assertEqual(registry.get("author.profile.update").input_schema,
                         context_tool["inputSchema"])
        self.assertFalse(context_tool["annotations"]["readOnlyHint"])
        content = call["result"]["content"][0]
        self.assertEqual("text", content["type"])
        self.assertFalse(call["result"]["isError"])
        context_result = call["result"]["structuredContent"]["result"]
        self.assertTrue(context_result["truncated"])
        self.assertIn("resultRef", context_result)
        self.assertNotIn("markdown", context_result)
        encoded_result = json.dumps(context_result, ensure_ascii=True, separators=(",", ":"))
        self.assertLessEqual(len(encoded_result.encode("ascii")), model_output.DEFAULT_OUTPUT_BUDGET)

        # The full legacy Markdown remains available through bounded local continuation.
        reader = DirectMcpClient(self.env())
        reader.request({"jsonrpc": "2.0", "id": 9, "method": "initialize",
                        "params": {"protocolVersion": "2025-06-18"}})
        markdown_parts = []
        cursor = None
        for index in range(100):
            arguments = {
                "resultRef": context_result["resultRef"], "path": "/markdown", "limit": 500,
            }
            if cursor is not None:
                arguments["cursor"] = cursor
            read_call = reader.request({
                "jsonrpc": "2.0", "id": 10 + index, "method": "tools/call",
                "params": {"name": "platform.result.read", "arguments": arguments},
            })["result"]
            self.assertFalse(read_call["isError"], read_call)
            page = read_call["structuredContent"]["result"]
            self.assertLessEqual(
                len(json.dumps(page, ensure_ascii=True, separators=(",", ":")).encode("ascii")),
                model_output.MAX_PAGE_BYTES,
            )
            markdown_parts.append(page["value"])
            cursor = page["nextCursor"]
            if cursor is None:
                break
        else:
            self.fail("result.read 未在 100 页内完成")
        self.assertIn("工具状态", "".join(markdown_parts))
        self.assertEqual({}, ping["result"])
        self.assertNotIn(TOKEN, json.dumps(responses, ensure_ascii=False))

    def test_direct_client_dispatch_and_protocol_errors(self):
        StubHandler.routes["/api/v1/healthz"] = (200, {"status": "alive"})
        client = DirectMcpClient(self.env())
        initialized = client.request({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                      "params": {"protocolVersion": "2025-06-18"}})
        self.assertEqual("2025-06-18", initialized["result"]["protocolVersion"])
        called = client.request({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                 "params": {"name": "health.check",
                                            "arguments": {"ready": False}}})
        self.assertEqual({"status": "alive"}, called["result"]["structuredContent"]["result"])
        unknown_method = client.request({"jsonrpc": "2.0", "id": 3, "method": "no/such"})
        self.assertEqual(-32601, unknown_method["error"]["code"])
        unknown_tool = client.request({"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                                       "params": {"name": "no.such.tool", "arguments": {}}})
        self.assertEqual(-32602, unknown_tool["error"]["code"])
        bad_arguments = client.request({"jsonrpc": "2.0", "id": 5, "method": "tools/call",
                                        "params": {"name": "health.check", "arguments": []}})
        self.assertEqual(-32602, bad_arguments["error"]["code"])
        invalid_schema = client.request({"jsonrpc": "2.0", "id": 6, "method": "tools/call",
                                         "params": {"name": "health.check",
                                                    "arguments": {"ready": "yes"}}})
        self.assertTrue(invalid_schema["result"]["isError"])
        self.assertEqual("INVALID_INPUT",
                         invalid_schema["result"]["structuredContent"]["error"])

    def test_write_tool_without_confirmation_is_error_result(self):
        client = DirectMcpClient(self.env())
        response = client.request({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                   "params": {"name": "author.profile.update",
                                              "arguments": {"plan": {}, "confirm": False}}})
        self.assertTrue(response["result"]["isError"])
        self.assertEqual("NOT_CONFIRMED",
                         response["result"]["structuredContent"]["error"])
        invalid = client.request({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                  "params": {"name": "backup.export", "arguments": {}}})
        self.assertTrue(invalid["result"]["isError"])
        self.assertEqual("INVALID_INPUT",
                         invalid["result"]["structuredContent"]["error"])

    def test_unknown_protocol_version_falls_back_to_latest(self):
        client = DirectMcpClient(self.env())
        response = client.request({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                   "params": {"protocolVersion": "1999-01-01"}})
        self.assertEqual(mcp_server.PROTOCOL_VERSION, response["result"]["protocolVersion"])

    def test_parse_error_and_invalid_request(self):
        stdout = io.StringIO()
        server = mcp_server.McpServer(
            stdin=io.StringIO("not json\n" + json.dumps({"id": 1, "method": "ping"}) + "\n"),
            stdout=stdout, env=self.env())
        server.serve_forever()
        responses = [json.loads(line) for line in stdout.getvalue().splitlines() if line.strip()]
        self.assertEqual(-32700, responses[0]["error"]["code"])
        self.assertEqual(-32600, responses[1]["error"]["code"])

    def test_default_connection_can_call_tools_without_list_refresh(self):
        server = mcp_server.McpServer(env={}, stdout=io.StringIO(),
            execute=lambda name, args: (registry.get(name), {"items": [], "nextCursor": None}))
        before = server._tools_list()["tools"]
        self.assertIn("author.query", {tool["name"] for tool in before})
        self.assertFalse(server._initialize({})["capabilities"]["tools"]["listChanged"])
        response = server._tools_call(1, {"name": "author.query", "arguments": {}}, set())
        self.assertFalse(response["result"]["isError"])
        self.assertEqual(before, server._tools_list()["tools"])

    def test_default_deadline_covers_long_handler_and_explicit_limit_still_applies(self):
        original = registry.get("research.media.probe")
        spec = replace(original, timeout_seconds=.3)
        def execute(name, args):
            time.sleep(.12)
            return spec, {"exitCode": 0, "artifacts": []}
        packet = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                  "params": {"name": spec.id, "arguments": {}}}
        with patch.object(mcp_server, "DEFAULT_CALL_TIMEOUT_SECONDS", .02), \
             patch.object(mcp_server, "HANDLER_COMPLETION_GRACE_SECONDS", .01), \
             patch.object(registry, "get", return_value=spec):
            server = mcp_server.McpServer(env={}, execute=execute)
            self.assertFalse(server.handle_message(packet, set())["result"]["isError"])
            capped = mcp_server.McpServer(env={}, execute=execute, timeout_seconds=.02)
            failure = capped.handle_message(packet, set())["error"]
            self.assertEqual(mcp_server.CALL_TIMEOUT, failure["code"])
            self.assertTrue(failure["data"]["operationMayHaveCompleted"])

    def test_declared_research_budget_includes_completion_time(self):
        server = mcp_server.McpServer(env={})
        spec = registry.get("research.media.probe")
        self.assertGreater(server._call_timeout(spec, {"timeoutSeconds": 900}), 900)
        self.assertLess(server._call_timeout(spec, {"timeoutSeconds": 100000}), 100000)

    def test_tool_call_timeout(self):
        def slow_execute(tool_id, payload):
            time.sleep(0.3)
            return registry.get("project.context"), {"ok": True}

        server = mcp_server.McpServer(env=self.env(), execute=slow_execute,
                                      timeout_seconds=0.05)
        response = server.handle_message(
            {"jsonrpc": "2.0", "id": 9, "method": "tools/call",
             "params": {"name": "project.context", "arguments": {}}}, set())
        self.assertEqual(mcp_server.CALL_TIMEOUT, response["error"]["code"])

    def test_tool_call_cancellation_is_best_effort(self):
        def slow_execute(tool_id, payload):
            time.sleep(0.4)
            return registry.get("project.context"), {"ok": True}

        server = mcp_server.McpServer(env=self.env(), execute=slow_execute,
                                      timeout_seconds=5)
        cancelled = set()
        result = {}

        def run():
            result.update(server.handle_message(
                {"jsonrpc": "2.0", "id": 7, "method": "tools/call",
                 "params": {"name": "project.context", "arguments": {}}}, cancelled))

        thread = threading.Thread(target=run)
        thread.start()
        time.sleep(0.1)
        cancelled.add(7)
        thread.join(timeout=3)
        self.assertEqual(mcp_server.CALL_CANCELLED, result["error"]["code"])

    def test_stdio_processes_cancellation_while_call_in_flight(self):
        def slow_execute(tool_id, payload):
            time.sleep(0.5)
            return registry.get(tool_id), {"ok": True}

        class TimedStdin:
            def __init__(self, lines, delay):
                self.lines = lines
                self.delay = delay

            def __iter__(self):
                for line in self.lines:
                    time.sleep(self.delay)
                    yield line

        stdout = io.StringIO()
        stdin = TimedStdin([
            json.dumps({"jsonrpc": "2.0", "id": 7, "method": "tools/call",
                        "params": {"name": "project.context", "arguments": {}}}) + "\n",
            json.dumps({"jsonrpc": "2.0", "method": "notifications/cancelled",
                        "params": {"requestId": 7}}) + "\n",
        ], delay=0.05)
        server = mcp_server.McpServer(stdin=stdin, stdout=stdout, env=self.env(),
                                      execute=slow_execute, timeout_seconds=2)
        started = time.monotonic()
        server.serve_forever()
        elapsed = time.monotonic() - started
        responses = [json.loads(line) for line in stdout.getvalue().splitlines() if line.strip()]
        self.assertEqual(1, len(responses))
        self.assertEqual(mcp_server.CALL_CANCELLED, responses[0]["error"]["code"])
        # 取消通知在 0.5s 的调用结束前就被处理（否则要等满调用耗时）
        self.assertLess(elapsed, 0.4)

    def test_side_effect_tool_cancellation_waits_for_real_result(self):
        def slow_execute(tool_id, payload):
            time.sleep(0.25)
            return registry.get(tool_id), {"status": "applied"}

        class TimedStdin:
            def __init__(self, lines, delay):
                self.lines = lines
                self.delay = delay

            def __iter__(self):
                for line in self.lines:
                    time.sleep(self.delay)
                    yield line

        stdout = io.StringIO()
        stdin = TimedStdin([
            json.dumps({"jsonrpc": "2.0", "id": 8, "method": "tools/call",
                        "params": {"name": "media.organize.apply", "arguments": {}}}) + "\n",
            json.dumps({"jsonrpc": "2.0", "method": "notifications/cancelled",
                        "params": {"requestId": 8}}) + "\n",
        ], delay=0.03)
        server = mcp_server.McpServer(stdin=stdin, stdout=stdout, env=self.env(),
                                      execute=slow_execute, timeout_seconds=2)
        started = time.monotonic()
        server.serve_forever()
        elapsed = time.monotonic() - started
        responses = [json.loads(line) for line in stdout.getvalue().splitlines() if line.strip()]
        self.assertEqual(1, len(responses))
        # 有副作用工具不提前返回“已取消”，而是等待真实结果（避免调用方误以为未执行）
        self.assertNotIn("error", responses[0])
        self.assertEqual("ok", responses[0]["result"]["structuredContent"]["status"])
        self.assertGreaterEqual(elapsed, 0.2)

    def test_side_effect_tool_calls_are_serialized(self):
        guard = threading.Lock()
        state = {"active": 0, "max": 0}

        def slow_execute(tool_id, payload):
            with guard:
                state["active"] += 1
                state["max"] = max(state["max"], state["active"])
            time.sleep(0.15)
            with guard:
                state["active"] -= 1
            return registry.get(tool_id), {"status": "applied"}

        stdout = io.StringIO()
        stdin = io.StringIO(
            json.dumps({"jsonrpc": "2.0", "id": 11, "method": "tools/call",
                        "params": {"name": "media.organize.apply", "arguments": {}}}) + "\n" +
            json.dumps({"jsonrpc": "2.0", "id": 12, "method": "tools/call",
                        "params": {"name": "media.organize.apply", "arguments": {}}}) + "\n")
        server = mcp_server.McpServer(stdin=stdin, stdout=stdout, env=self.env(),
                                      execute=slow_execute, timeout_seconds=5)
        server.serve_forever()
        responses = [json.loads(line) for line in stdout.getvalue().splitlines() if line.strip()]
        self.assertEqual(2, len(responses))
        for response in responses:
            self.assertEqual("ok", response["result"]["structuredContent"]["status"])
        # 两个有副作用调用不能重叠执行（写工具全局串行）
        self.assertEqual(1, state["max"])

    def test_same_dispatch_as_cli(self):
        StubHandler.routes["/api/v1/healthz"] = (200, {"status": "alive"})
        env = self.env()
        from ai_maintenance import execution
        _spec, cli_result = execution.execute_tool("health.check", {}, env=env,
                                                   config=execution.load_config(env))
        client = DirectMcpClient(env)
        response = client.request({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                   "params": {"name": "health.check", "arguments": {}}})
        self.assertEqual(cli_result, response["result"]["structuredContent"]["result"])
        self.assertNotIn(TOKEN, json.dumps(response, ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
