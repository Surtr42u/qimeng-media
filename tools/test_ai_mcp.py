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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from ai_maintenance import mcp_server, registry

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
        return {"QIMENG_API_BASE": self.base, "QIMENG_API_TOKEN": TOKEN}

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
        initialize, listing, call, ping = responses
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
        self.assertIn("工具状态", content["text"])
        self.assertFalse(call["result"]["isError"])
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
        planned = client.request({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                  "params": {"name": "backup.export", "arguments": {}}})
        self.assertTrue(planned["result"]["isError"])
        self.assertEqual("TOOL_NOT_IMPLEMENTED",
                         planned["result"]["structuredContent"]["error"])

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
