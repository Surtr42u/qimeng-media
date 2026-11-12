"""真实隔离 Go + stdio MCP 行为回归；由云端显式提供同 revision 的二进制。

不使用生产地址/凭据；所有库、源文件与服务数据都由本测试创建在临时根内。
没有 QIMENG_TEST_SERVER_BINARY 时跳过，不回退已有服务。
"""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import queue
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from urllib import error, request

from ai_maintenance import recipes

ROOT = Path(__file__).resolve().parents[1]
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


class StdioClient:
    def __init__(self, env):
        self.process = subprocess.Popen(
            [sys.executable, "-X", "utf8", str(ROOT / "tools/ai_maintenance/mcp_server.py")],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8", env=env, cwd=ROOT,
        )
        self.responses = queue.Queue()
        self.sequence = 0
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()
        self.send("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                 "clientInfo": {"name": "isolated-go-pilot", "version": "1"}})

    def read(self):
        for line in self.process.stdout:
            self.responses.put(json.loads(line))

    def send(self, method, params):
        self.sequence += 1
        self.process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.sequence,
                                            "method": method, "params": params}) + "\n")
        self.process.stdin.flush()
        result = self.responses.get(timeout=30)
        if result.get("id") != self.sequence or "error" in result:
            raise AssertionError("MCP protocol response mismatch")
        return result["result"]

    def call(self, tool, arguments):
        return self.send("tools/call", {"name": tool, "arguments": arguments})

    def close(self):
        stop(self.process)
        for stream in (self.process.stdin, self.process.stdout):
            stream.close()
        self.reader.join(timeout=5)


@unittest.skipUnless(os.environ.get("QIMENG_TEST_SERVER_BINARY"), "仅云端提供隔离 Go 二进制时运行")
class GoMcpIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="qimeng-ai-pilot-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.media = cls.root / "media"
        cls.sources = cls.root / "sources"
        cls.media.mkdir()
        cls.sources.mkdir()
        cls.source = cls.sources / "输入.png"
        cls.source.write_bytes(PNG)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        if port == 8420:
            raise AssertionError("不能使用生产端口")
        cls.base = f"http://127.0.0.1:{port}"
        cls.secret = secrets.token_urlsafe(32)
        config = cls.root / "config.json"
        # JSON 同时是 YAML；路径来自本测试新建根，不读取本机配置。
        config.write_text(json.dumps({"listen": f"127.0.0.1:{port}",
                                     "data_dir": str(cls.root / "data"),
                                     "auth_dev_mode": True, "auth_dev_shared_secret": cls.secret,
                                     "allowed_library_roots": [str(cls.media)],
                                     "backup": {"enabled": False},
                                     "scanner": {"poll_interval": "1h", "watch_enabled": False}}), encoding="utf-8")
        cls.env = {k: v for k, v in os.environ.items() if not k.startswith("QIMENG_")}
        log = (cls.root / "server.log").open("wb")
        cls.addClassCleanup(log.close)
        cls.server = subprocess.Popen([os.environ["QIMENG_TEST_SERVER_BINARY"], "-config", str(config)],
                                      cwd=cls.root, env=cls.env, stdout=log, stderr=log)
        cls.addClassCleanup(stop, cls.server)
        for _ in range(200):
            try:
                if cls.http("GET", "/api/v1/readyz")[0] == 200:
                    break
            except OSError:
                pass
            if cls.server.poll() is not None:
                raise AssertionError("隔离 Go 服务启动失败；不回退生产服务")
            time.sleep(0.1)
        else:
            raise AssertionError("隔离服务未就绪")
        status, auth = cls.http("POST", "/api/v1/auth/dev-login", {},
                                headers={"X-Qimeng-Dev-Secret": cls.secret})
        if status != 200:
            raise AssertionError("隔离管理员初始化失败")
        cls.admin = auth["token"]
        status, library = cls.http("POST", "/api/v1/libraries",
                                   {"name": "合成库", "rootPath": str(cls.media)}, cls.admin)
        if status != 201:
            raise AssertionError("合成库创建失败")
        cls.library = library["id"]

    @classmethod
    def http(cls, method, path, body=None, token=None, headers=None):
        request_headers = {"Content-Type": "application/json", **(headers or {})}
        if token:
            request_headers["Authorization"] = "Bearer " + token
        req = request.Request(cls.base + path, data=None if body is None else json.dumps(body).encode(),
                              method=method, headers=request_headers)
        try:
            response = request.urlopen(req, timeout=10)
        except error.HTTPError as exc:
            response = exc
        with response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None

    def client(self, *, scopes=None, permissions=None, readonly=False):
        scopes = scopes or ["read", "author.write", "asset.upload", "library.write", "maintenance.read", "maintenance.execute"]
        status, secret = self.http("POST", "/api/v1/ai-clients",
                                    {"label": "pilot-" + secrets.token_hex(6), "scopes": scopes}, self.admin)
        self.assertEqual(201, status)
        env = dict(self.env, QIMENG_API_BASE=self.base, QIMENG_API_TOKEN=secret["token"],
                   QIMENG_AI_READ_ROOTS=str(self.sources),
                   QIMENG_AI_GRANTED_PERMISSIONS=permissions or "read,author.profile.write,asset.upload,library.write,maintenance.execute")
        if readonly:
            env["QIMENG_AI_READONLY"] = "1"
        client = StdioClient(env)
        self.addCleanup(client.close)
        return client, env

    def result(self, client, tool, arguments):
        response = client.call(tool, arguments)
        self.assertFalse(response["isError"], tool)
        return response["structuredContent"]["result"]

    def test_discover_upload_replay_readback_and_scan(self):
        client, _ = self.client()
        listing = client.send("tools/list", {})
        self.assertIn("asset.upload", [t["name"] for t in listing["tools"]])
        card = self.result(client, "platform.recipe", {"action": "card", "recipeId": "media.ingest"})
        self.assertEqual("media.ingest", card["card"]["recipe"])
        plan = self.result(client, "asset.upload.prepare", {"libraryId": self.library,
                           "path": str(self.source), "fileName": "合成 1.png"})["plan"]
        self.assertTrue(client.call("asset.upload", {"plan": plan, "confirm": False})["isError"])
        applied = self.result(client, "asset.upload", {"plan": plan, "confirm": True})
        replay = self.result(client, "asset.upload", {"plan": plan, "confirm": True})
        self.assertEqual(applied["assetId"], replay["assetId"])
        self.assertEqual(PNG, (self.media / "合成 1.png").read_bytes())
        self.assertEqual("accepted", self.result(client, "library.scan", {"libraryId": self.library, "confirm": True})["status"])

    def test_profile_confirmation_revision_and_unchanged_fields(self):
        client, _ = self.client()
        status, author = self.http("POST", "/api/v1/authors", {"displayName": "合成作者", "type": "regular"}, self.admin)
        self.assertEqual(201, status)
        author_id = author["id"]
        initial = self.result(client, "author.profile.read", {"authorId": author_id})
        prepared = self.result(client, "author.profile.prepare", {"authorId": author_id,
                                "changes": {"biography": "合成简介"}})["plan"]
        self.assertTrue(client.call("author.profile.update", {"plan": prepared, "confirm": False})["isError"])
        self.result(client, "author.profile.update", {"plan": prepared, "confirm": True})
        final = self.result(client, "author.profile.read", {"authorId": author_id})
        self.assertEqual(initial["revision"] + 1, final["revision"])
        self.assertEqual("合成简介", final["biography"])
        self.assertEqual(initial["nickname"], final["nickname"])
        self.assertEqual("already_applied", self.result(client, "author.profile.update", {"plan": prepared, "confirm": True})["status"])

    def test_server_scope_and_local_readonly_reject_writes(self):
        client, _ = self.client(scopes=["read"])
        self.assertTrue(client.call("library.scan", {"libraryId": self.library, "confirm": True})["isError"])
        client, _ = self.client(readonly=True)
        self.assertTrue(client.call("library.scan", {"libraryId": self.library, "confirm": True})["isError"])
        client, _ = self.client(permissions="read")
        self.assertTrue(client.call("library.scan", {"libraryId": self.library, "confirm": True})["isError"])

    def test_recipe_gate_and_resume_on_real_go(self):
        _, env = self.client()
        state = self.root / "recipe"
        inputs = {"upload": {"libraryId": self.library, "path": str(self.source), "fileName": "配方 1.png"}}
        first = recipes.run_recipe("media.ingest", inputs, env=env, state_dir=state)
        self.assertFalse((self.media / "配方 1.png").exists())
        self.assertNotEqual("completed", first["status"])
        recipes.run_recipe("media.ingest", inputs, env=env, state_dir=state, confirm=True)
        self.assertEqual(PNG, (self.media / "配方 1.png").read_bytes())

    def test_lease_fencing_and_terminal_readback(self):
        client, _ = self.client()
        status, job = self.http("POST", "/api/v1/maintenance/jobs",
                               {"kind": "custom", "idempotencyKey": "synthetic-job-1", "payload": {}}, self.admin)
        self.assertEqual(201, status)
        lease = self.result(client, "maintenance.claim", {"kind": "custom"})
        args = {"jobId": job["id"], "leaseToken": lease["leaseToken"]}
        self.result(client, "maintenance.heartbeat", args)
        self.result(client, "maintenance.step.record", dict(args, step="readback", status="completed"))
        done = self.result(client, "maintenance.complete", dict(args, status="completed"))
        self.assertEqual("completed", done["job"]["status"])
        self.assertTrue(client.call("maintenance.heartbeat", args)["isError"])
        self.assertFalse(self.result(client, "maintenance.claim", {"kind": "custom"})["claimed"])

