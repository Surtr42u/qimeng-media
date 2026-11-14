"""MCP 内真实抽帧与 artifactRef 接续回归（T01–T03，2026-11-14）。

与 test_ai_research_work 的直接 handler 测试不同，本文件启动真正的 stdio MCP
服务进程对象，用合成图片/短视频调用 `research.work`，再由 MCP 调用
`platform.artifact.read` / `research.lens` 接续。研究网关使用 127.0.0.1 stub，
不访问真实平台；ffmpeg/ffprobe 缺失时跳过（云端存在时执行，不能以本地手动抽帧
替代）。
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from ai_maintenance import mcp_server  # noqa: E402

TOKEN = "-".join(("synthetic", "research", "mcp"))


class ResearchStubHandler(BaseHTTPRequestHandler):
    def _write(self, status: int, payload: dict) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/api/v1/research/context":
            self._write(200, {
                "enabled": True,
                "sidecar": {"configured": True, "available": True, "pending": 0},
                "providers": [{"name": "bing", "operations": ["search"],
                               "enabled": True,
                               "limits": {"perMinute": 20, "perDay": 0}}],
                "limits": {"lensPerHour": 20, "lensPerDay": 100},
                "fetchedAt": "2026-11-14T00:00:00Z",
            })
            return
        self._write(404, {"code": "NOT_FOUND"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if self.path == "/api/v1/research/lens":
            self._write(200, {
                "status": "ok",
                "frameSha256": "a" * 64,
                "topTitles": [{"title": "Synthetic work", "domain": "x.com"}],
                "topDomains": ["x.com"],
                "cites": [],
                "links": [{"url": "https://x.com/synthetic_author/status/123",
                           "domain": "x.com", "title": "Synthetic original post"}],
                "elapsedMs": 12,
            })
            return
        if self.path.startswith("/api/v1/research/providers/"):
            self._write(200, {
                "provider": "bing", "operation": "search", "target": "synthetic",
                "status": "ok", "fetchedAt": "2026-11-14T00:00:00Z",
                "latencyMs": 12, "items": [],
            })
            return
        self._write(404, {"code": "NOT_FOUND"})

    def log_message(self, *args):
        pass


class McpResearchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
            raise unittest.SkipTest("ffmpeg/ffprobe 不可用；MCP 真实抽帧回归跳过")
        cls.http = ThreadingHTTPServer(("127.0.0.1", 0), ResearchStubHandler)
        cls.http.daemon_threads = True
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = "http://127.0.0.1:%d" % cls.http.server_port

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, "http", None) is None:
            return
        cls.http.shutdown()
        cls.http.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qimeng-mcp-research-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inputs = self.root / "inputs"
        self.outputs = self.root / "outputs"
        self.research = self.root / "research"
        self.state = self.root / "state"
        self.cache = self.root / "cache"
        for directory in (self.inputs, self.outputs, self.research, self.state, self.cache):
            directory.mkdir(parents=True, exist_ok=True)
        self.env = {
            "QIMENG_API_BASE": self.base,
            "QIMENG_API_TOKEN": TOKEN,
            "QIMENG_AI_STATE_DIR": str(self.state),
            "QIMENG_AI_READ_ROOTS": str(self.inputs),
            "QIMENG_AI_WRITE_ROOTS": str(self.outputs),
            "QIMENG_AI_RESEARCH_DIR": str(self.research),
            "QIMENG_AI_MEDIA_CACHE": str(self.cache),
            "QIMENG_AI_GRANTED_PERMISSIONS": "read,research,local.files.write",
            "QIMENG_AI_MCP_DISCOVERY": "full",
            "QIMENG_AI_OUTPUT_BUDGET_BYTES": "12288",
        }
        self.server = mcp_server.McpServer(env=self.env)
        self.server.handle_message(
            {"jsonrpc": "2.0", "id": 1, "method": "initialize",
             "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                        "clientInfo": {"name": "synthetic-research", "version": "0.1"}}},
            set())

    @staticmethod
    def _tool_response(response: dict) -> dict:
        structured = response.get("structuredContent")
        if not isinstance(structured, dict) or "result" not in structured:
            raise AssertionError(
                "MCP tool error: " + json.dumps(response, ensure_ascii=False)[:4000])
        return structured["result"]

    def _call(self, request_id: int, name: str, arguments: dict) -> dict:
        response = self.server.handle_message(
            {"jsonrpc": "2.0", "id": request_id, "method": "tools/call",
             "params": {"name": name, "arguments": arguments}}, set())
        return response["result"]

    def _synthetic_media(self, *, video: bool) -> Path:
        target = self.inputs / ("synthetic.mp4" if video else "synthetic.png")
        if video:
            argv = [shutil.which("ffmpeg"), "-v", "error", "-y",
                    "-f", "lavfi", "-i", "color=c=blue:s=160x120:d=1",
                    "-pix_fmt", "yuv420p", str(target)]
        else:
            argv = [shutil.which("ffmpeg"), "-v", "error", "-y",
                    "-f", "lavfi", "-i", "color=c=red:s=160x120",
                    "-frames:v", "1", str(target)]
        subprocess.run(argv, check=True, timeout=60)
        self.assertTrue(target.is_file())
        return target

    def test_mcp_video_frames_and_artifact_handoff(self):
        self._synthetic_media(video=True)
        response = self._call(2, "research.work", {
            "path": str(self.inputs / "synthetic.mp4"),
            "frames": "15,50,85",
        })
        self.assertFalse(response["isError"])
        result = self._tool_response(response)
        self.assertIn(result["status"], ("ok", "partial", "unresolved"))
        self.assertEqual(0, result["businessWrites"])
        self.assertEqual("not_started", result["writePhase"])
        self.assertEqual([15, 50, 85],
                         [item["percent"] for item in result["frameRefs"]])
        self.assertEqual(3, len(result["frameRefs"]))
        self.assertTrue(result["evidenceRef"].startswith("a1_"))
        self.assertTrue(all(item["artifactRef"].startswith("a1_")
                            for item in result["frameRefs"]))
        self.assertTrue(any(item["usage"] == "evidence"
                            for item in result["artifactRefs"]))
        encoded = json.dumps(result, ensure_ascii=False)
        self.assertNotIn(str(self.root), encoded)
        self.assertNotIn(TOKEN, encoded)

        # platform.artifact.read：证据文本可续读，帧图片走标准 MCP image content。
        evidence = self._call(3, "platform.artifact.read",
                              {"artifactRef": result["evidenceRef"], "limit": 4096})
        self.assertFalse(evidence["isError"])
        evidence_result = self._tool_response(evidence)
        self.assertEqual("text", evidence_result["contentType"])
        self.assertIn("businessWrites", evidence_result["text"])
        self.assertNotIn(str(self.root), evidence_result["text"])

        frame = self._call(4, "platform.artifact.read", {
            "artifactRef": result["frameRefs"][0]["artifactRef"],
            "includeContent": True,
        })
        self.assertFalse(frame["isError"])
        content = frame["content"]
        self.assertEqual("text", content[0]["type"])
        self.assertEqual("image", content[1]["type"])
        self.assertEqual("image/jpeg", content[1]["mimeType"])
        structured = json.dumps(frame["structuredContent"], ensure_ascii=False)
        self.assertNotIn('"imageBase64"', structured)

        # research.lens 直接接受帧 artifactRef；不得回显本机路径。
        lens = self._call(5, "research.lens",
                          {"artifactRef": result["frameRefs"][0]["artifactRef"]})
        self.assertFalse(lens["isError"])
        lens_result = self._tool_response(lens)
        self.assertEqual("ok", lens_result.get("status"))
        self.assertNotIn(str(self.root), json.dumps(lens_result, ensure_ascii=False))

    def test_mcp_image_without_name_hint_starts_research(self):
        self._synthetic_media(video=False)
        response = self._call(10, "research.work", {
            "path": str(self.inputs / "synthetic.png"),
        })
        self.assertFalse(response["isError"])
        result = self._tool_response(response)
        self.assertIn(result["status"], ("ok", "partial", "unresolved"))
        self.assertEqual(0, result["businessWrites"])
        self.assertEqual([], result["frameRefs"])
        self.assertGreaterEqual(
            len(result["candidates"]), 1,
            json.dumps({"research": result.get("research"),
                        "limitations": result.get("limitations"),
                        "lens": result.get("lens"),
                        "queries": result.get("queries"),
                        "workSources": result.get("workSources"),
                        "status": result.get("status")}, ensure_ascii=False))
        for candidate in result["candidates"]:
            self.assertEqual("unconfirmed", candidate["verification"])
            self.assertFalse(candidate["authorConfirmed"])
        self.assertTrue(result["evidenceRef"].startswith("a1_"))
        self.assertTrue(any("未取得原帖/作品页与作者主页互证" in text
                            for text in result["unresolved"]))
        self.assertNotIn(str(self.root), json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
