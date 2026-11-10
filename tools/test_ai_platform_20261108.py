"""2026-11-08 外部 AI 工具平台回归：备份流式导出、设备结果包、执行器控制面、研究工具与回收站/标签。

全部使用合成数据、临时目录与 127.0.0.1 stub；不访问 8420、不联网、不要求
node/ffmpeg（研究工具的执行路径用假的 _execute 捕获 argv，另测超时与缺失）。
"""
from __future__ import annotations

import contextlib
import hashlib
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib import parse

from ai_maintenance import (client as http_client, errors, execution, openapi,
                            pathguard, registry, rulepack, tools_research, versions)

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "api" / "openapi.yaml"
RULES = ROOT / "tools" / "ai_maintenance" / "rules.json"
TOKEN = "-".join(("synthetic", "platform", "credential"))
LEASE_TOKEN = "-".join(("synthetic", "lease", "fencing", "token"))

_TEMP_ROOT = ROOT / "tools" / "ai_maintenance" / ".test-temp"
try:
    _TEMP_ROOT.mkdir(parents=True, exist_ok=True)
except OSError:
    _TEMP_ROOT = Path(tempfile.gettempdir())


@contextlib.contextmanager
def temp_dir():
    path = _TEMP_ROOT / ("platform-" + uuid.uuid4().hex)
    os.makedirs(path)
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


class StubHandler(BaseHTTPRequestHandler):
    routes = {}
    seen = []

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_PUT(self):
        self._handle("PUT")

    def do_DELETE(self):
        self._handle("DELETE")

    def _handle(self, method):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        body = self.rfile.read(length) if length > 0 else b""
        parsed = parse.urlsplit(self.path)
        type(self).seen.append({
            "method": method, "path": parsed.path,
            "query": parse.parse_qs(parsed.query), "body": body,
            "authorization": self.headers.get("Authorization"),
            "content_type": self.headers.get("Content-Type"),
        })
        route = type(self).routes.get((method, parsed.path), (404, {"code": "NOT_FOUND"}))
        if isinstance(route, list):
            status, payload = route.pop(0) if len(route) > 1 else route[0]
        else:
            status, payload = route
        if payload is None:
            payload = b""
        raw = payload if isinstance(payload, bytes) else \
            json.dumps(payload, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            if raw and status != 204:
                self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *args):
        pass


class StubServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), StubHandler)
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        StubHandler.routes = {}
        StubHandler.seen = []

    def env(self, **extra):
        return dict({"QIMENG_API_BASE": self.base, "QIMENG_API_TOKEN": TOKEN}, **extra)

    def config(self, **extra):
        return execution.load_config(self.env(**extra))

    def call(self, tool_id, payload, config=None):
        return execution.execute_tool(tool_id, payload, env=self.env(),
                                      config=config or self.config())


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _multipart_parts(body: bytes, content_type: str) -> dict:
    boundary = content_type.split("boundary=", 1)[1].strip().strip('"').encode()
    parts = {}
    for chunk in body.split(b"--" + boundary):
        if b"\r\n\r\n" not in chunk:
            continue
        head, _, data = chunk.partition(b"\r\n\r\n")
        if data.endswith(b"\r\n"):
            data = data[:-2]
        name = None
        for piece in head.split(b"\r\n"):
            if piece.startswith(b"Content-Disposition:"):
                for token in piece.decode("utf-8", "replace").split(";"):
                    token = token.strip()
                    if token.startswith("name="):
                        name = token[len("name="):].strip('"')
        if name:
            parts[name] = data
    return parts


def _trash_item(trash_id="trash-1"):
    return {"id": trash_id, "fileName": "synthetic.png",
            "originalPath": "synthetic/synthetic.png",
            "deletedAt": "2026-11-08T00:00:00Z", "sizeBytes": 123,
            "expiresAt": "2026-12-08T00:00:00Z"}


def _job(job_id="job-1", **extra):
    body = {"id": job_id, "kind": "asset_research", "status": "queued",
            "createdAt": "2026-11-08T00:00:00Z", "updatedAt": "2026-11-08T00:00:00Z",
            "stepCount": 0, "progress": 0, "steps": [], "artifacts": []}
    body.update(extra)
    return body


# --------------------------------------------------------------------------- #
# 注册表 / 规则包 / 目录状态
# --------------------------------------------------------------------------- #

NEW_TOOL_PERMISSIONS = {
    "backup.export": ("backup.export", False, False),
    "device.apply.prepare": ("device.apply", True, False),
    "device.apply.submit": ("device.apply", False, True),
    "device.apply.status": ("device.apply", True, False),
    "maintenance.claim": ("maintenance.execute", False, False),
    "maintenance.heartbeat": ("maintenance.execute", False, False),
    "maintenance.step.record": ("maintenance.execute", False, False),
    "maintenance.artifact.record": ("maintenance.execute", False, False),
    "maintenance.complete": ("maintenance.execute", False, False),
    "research.image.pipeline": ("research", False, False),
    "research.image.search": ("research", False, False),
    "research.author.probe": ("research", False, False),
    "research.media.probe": ("research", False, False),
    "research.avatar.fetch": ("research", False, False),
    "trash.list": ("read", True, False),
    "trash.restore": ("trash.restore", False, True),
    "asset.trash": ("asset.delete", False, True),
    "asset.tags.replace": ("asset.write", False, True),
}


class RegistrationTest(unittest.TestCase):
    def setUp(self):
        self.document, _raw = openapi.parse_file(OPENAPI)
        self.config = execution.load_config({"QIMENG_API_BASE": "http://127.0.0.1:1",
                                             "QIMENG_API_TOKEN": TOKEN})

    def test_new_tools_registered_supported_with_permissions(self):
        for tool_id, (permission, read_only, confirm) in NEW_TOOL_PERMISSIONS.items():
            spec = registry.get(tool_id)
            self.assertIsNotNone(spec, tool_id)
            self.assertEqual(permission, spec.permission, tool_id)
            self.assertEqual(read_only, spec.read_only, tool_id)
            self.assertEqual(confirm, spec.requires_confirmation, tool_id)
            status = registry.tool_status(spec, self.document)
            self.assertEqual("supported", status["status"], (tool_id, status))
            self.assertIn("timeoutSeconds", registry.tool_summary(spec, self.document))

    def test_catalog_lists_all_new_tools_without_failing(self):
        _spec, catalog = execution.execute_tool("platform.catalog", {}, config=self.config)
        by_id = {item["id"]: item for item in catalog["tools"]}
        for tool_id in NEW_TOOL_PERMISSIONS:
            self.assertIn(tool_id, by_id)
            self.assertEqual("supported", by_id[tool_id]["status"], tool_id)
        domains = {row["id"]: row for row in catalog["domains"]}
        self.assertIn("trash.restore", domains["media"]["tools"])
        self.assertIn("maintenance.claim", domains["maintenance"]["tools"])
        self.assertIn("research.image.pipeline", domains["research"]["tools"])

    def test_versions_and_rules_sections(self):
        self.assertEqual("1.4.0", versions.PLATFORM_VERSION)
        self.assertEqual("1.4.0", versions.TOOL_CONTRACT_VERSION)
        rules = rulepack.load_rules(RULES)
        self.assertEqual("2026-11-10.3", rules["rulepackVersion"])
        self.assertEqual(rules["rulesVersion"], rules["rulepackVersion"])
        self.assertEqual("1.4.0", rules["platform"]["platformVersion"])
        for section in ("research", "deviceApply", "maintenanceExecutor", "trash"):
            self.assertIn(section, rules)
        for code in ("PERMISSION_DENIED", "TOOL_DEPRECATED", "FORBIDDEN_SCOPE", "LEASE_LOST"):
            self.assertIn(code, {item["code"] for item in rules["errors"]["toolCodes"]})
        for playbook in ("device.apply", "backup.export", "research.author.verify"):
            self.assertIn(playbook, rules["playbooks"])
        self.assertNotIn("platform.playbook.list", registry.TOOL_EXAMPLES)

    def test_error_catalog_exposes_new_codes(self):
        codes = {item["code"] for item in registry.COMMON_ERROR_CODES}
        for code in ("PERMISSION_DENIED", "TOOL_DEPRECATED", "FORBIDDEN_SCOPE", "LEASE_LOST"):
            self.assertIn(code, codes)


class PermissionGatingTest(StubServerTest):
    def test_read_only_grant_rejects_new_write_permissions(self):
        config = self.config(QIMENG_AI_GRANTED_PERMISSIONS="read")
        for tool_id in ("backup.export", "device.apply.submit", "maintenance.claim",
                        "trash.restore", "asset.trash", "asset.tags.replace",
                        "device.apply.prepare"):
            with self.assertRaises(errors.ToolError) as caught:
                self.call(tool_id, {}, config=config)
            self.assertEqual("PERMISSION_DENIED", caught.exception.code, tool_id)
        self.assertEqual([], StubHandler.seen)

    def test_granted_permission_allows_read_query(self):
        StubHandler.routes[("GET", "/api/v1/trash")] = (200, [_trash_item()])
        config = self.config(QIMENG_AI_GRANTED_PERMISSIONS="read")
        _spec, result = self.call("trash.list", {}, config=config)
        self.assertEqual(1, len(result))


# --------------------------------------------------------------------------- #
# backup.export：流式落盘（3MiB 超过旧 2MiB 上限）
# --------------------------------------------------------------------------- #

class BackupExportTest(StubServerTest):
    def _payload(self):
        return b"Q" * (3 << 20)  # 3 MiB，超过旧 2MiB JSON 响应上限

    def test_streams_three_mib_to_disk_with_sha256(self):
        payload = self._payload()
        self.assertGreater(len(payload), http_client.MAX_RESPONSE_BYTES)
        StubHandler.routes[("GET", "/api/v1/export/qimeng-backup")] = (200, payload)
        with temp_dir() as directory:
            target = directory / "backup.zip"
            _spec, result = self.call("backup.export", {"outPath": str(target)})
            self.assertEqual(len(payload), result["bytes"])
            self.assertEqual(_sha256(payload), result["sha256"])
            self.assertEqual(str(target), result["path"])
            self.assertEqual(payload, target.read_bytes())
            self.assertFalse([path for path in directory.iterdir()
                              if ".part-" in path.name])
        self.assertNotIn(TOKEN, json.dumps(result, ensure_ascii=False))
        self.assertEqual("Bearer " + TOKEN, StubHandler.seen[0]["authorization"])

    def test_refuses_overwrite_and_cleans_temp_file(self):
        StubHandler.routes[("GET", "/api/v1/export/qimeng-backup")] = (200, b"first")
        with temp_dir() as directory:
            target = directory / "backup.zip"
            self.call("backup.export", {"outPath": str(target)})
            StubHandler.seen = []
            with self.assertRaises(errors.ToolError) as caught:
                self.call("backup.export", {"outPath": str(target)})
            self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
            self.assertEqual([], StubHandler.seen)
            _spec, result = self.call("backup.export", {"outPath": str(target),
                                                        "overwrite": True})
            self.assertEqual(5, result["bytes"])
            self.assertEqual(b"first", target.read_bytes())

    def test_budget_exceeded_fails_without_partial_file(self):
        StubHandler.routes[("GET", "/api/v1/export/qimeng-backup")] = (200, b"x" * 4096)
        with temp_dir() as directory:
            target = directory / "backup.zip"
            with self.assertRaises(http_client.ResponseError) as caught:
                self.call("backup.export", {"outPath": str(target), "maxBytes": 1024})
            self.assertEqual("response_too_large", caught.exception.kind)
            self.assertFalse(target.exists())
            self.assertEqual([], list(directory.iterdir()))

    def test_rejects_relative_and_repo_tracked_paths(self):
        with self.assertRaises(errors.ToolError) as caught:
            self.call("backup.export", {"outPath": "relative/backup.zip"})
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        inside_repo = ROOT / "tools" / "ai_maintenance" / "rules.json.bak"
        with self.assertRaises(errors.ToolError) as caught:
            self.call("backup.export", {"outPath": str(inside_repo)})
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        self.assertEqual([], StubHandler.seen)


# --------------------------------------------------------------------------- #
# device.apply.prepare / submit / status
# --------------------------------------------------------------------------- #

class DeviceApplyTest(StubServerTest):
    def _files(self, directory: Path):
        master = directory / "作者总表.txt"
        master.write_bytes("合成作者\t合成作品\n".encode("utf-8"))
        backup = directory / "backup.json"
        backup.write_bytes(b"v2-backup-" + b"B" * (2 << 20))
        return master, backup

    def test_prepare_is_read_only_and_builds_manifest(self):
        with temp_dir() as directory:
            master, backup = self._files(directory)
            _spec, result = self.call("device.apply.prepare", {
                "targetDeviceId": "phone-main",
                "masterTxtPath": str(master),
                "v2BackupPath": str(backup),
                "ttlSeconds": 3600,
                "expectedState": {"assets": 10, "authors": 3},
            })
            self.assertEqual([], StubHandler.seen)
            plan = result["plan"]
            manifest = plan["manifest"]
            self.assertEqual("phone-main", manifest["targetDeviceId"])
            self.assertEqual(1, manifest["protocolVersion"])
            self.assertEqual(3600, manifest["ttlSeconds"])
            self.assertEqual(["masterTxt", "v2Backup"],
                             [segment["kind"] for segment in manifest["segments"]])
            self.assertEqual(master.stat().st_size, manifest["segments"][0]["bytes"])
            self.assertEqual(hashlib.sha256(master.read_bytes()).hexdigest(),
                             manifest["segments"][0]["sha256"])
            self.assertEqual({"assets": 10, "authors": 3}, manifest["expectedState"])
            self.assertEqual(self.base.replace("http://", ""), plan["target"]["instance"])
            self.assertTrue(plan["planFingerprint"])
            self.assertTrue(plan["idempotencyKey"])
            self.assertEqual(64, len(manifest["rulesFingerprint"]))

    def test_prepare_enforces_go_limits(self):
        with temp_dir() as directory:
            master = directory / "too-big.txt"
            master.write_bytes(b"x" * ((1 << 20) + 1))
            with self.assertRaises(errors.ToolError) as caught:
                self.call("device.apply.prepare", {"targetDeviceId": "phone-main",
                                                   "masterTxtPath": str(master)})
            self.assertEqual("INVALID_INPUT", caught.exception.code)
            with self.assertRaises(errors.ToolError) as caught:
                self.call("device.apply.prepare", {"targetDeviceId": "phone-main"})
            self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_submit_rehashes_sources_and_posts_multipart(self):
        with temp_dir() as directory:
            master, backup = self._files(directory)
            _spec, prepared = self.call("device.apply.prepare", {
                "targetDeviceId": "phone-main",
                "masterTxtPath": str(master),
                "v2BackupPath": str(backup),
            })
            plan = prepared["plan"]
            task = {
                "id": "task-1", "targetDeviceId": "phone-main", "status": "pending",
                "protocolVersion": 1, "rulesFingerprint": plan["manifest"]["rulesFingerprint"],
                "createdAt": "2026-11-08T00:00:00Z", "updatedAt": "2026-11-08T00:00:00Z",
                "expiresAt": "2026-11-09T00:00:00Z",
                "segments": [{"kind": segment["kind"], "bytes": segment["bytes"],
                              "sha256": segment["sha256"]}
                             for segment in plan["manifest"]["segments"]],
            }
            StubHandler.routes[("POST", "/api/v1/device/apply-tasks")] = (201, task)
            StubHandler.routes[("GET", "/api/v1/device/apply-tasks/task-1")] = (200, task)
            _spec, result = self.call("device.apply.submit", {"plan": plan, "confirm": True})
            self.assertEqual("applied", result["status"])
            self.assertEqual("task-1", result["taskId"])
            posts = [item for item in StubHandler.seen if item["method"] == "POST"]
            self.assertEqual(1, len(posts))
            parts = _multipart_parts(posts[0]["body"], posts[0]["content_type"])
            self.assertEqual(plan["manifest"], json.loads(parts["manifest"].decode("utf-8")))
            self.assertEqual(master.read_bytes(), parts["masterTxt"])
            self.assertEqual(backup.read_bytes(), parts["v2Backup"])

    def test_submit_rejects_changed_source_after_prepare(self):
        with temp_dir() as directory:
            master, _backup = self._files(directory)
            _spec, prepared = self.call("device.apply.prepare", {
                "targetDeviceId": "phone-main", "masterTxtPath": str(master)})
            master.write_bytes(master.read_bytes() + b"drift")
            with self.assertRaises(errors.ToolError) as caught:
                self.call("device.apply.submit", {"plan": prepared["plan"], "confirm": True})
            self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
            self.assertEqual([], [item for item in StubHandler.seen if item["method"] == "POST"])

    def test_submit_requires_confirmation(self):
        with temp_dir() as directory:
            master, _backup = self._files(directory)
            _spec, prepared = self.call("device.apply.prepare", {
                "targetDeviceId": "phone-main", "masterTxtPath": str(master)})
            with self.assertRaises(errors.ToolError) as caught:
                self.call("device.apply.submit", {"plan": prepared["plan"], "confirm": False})
            self.assertEqual("NOT_CONFIRMED", caught.exception.code)

    def test_status_list_and_detail(self):
        summary = {"id": "task-1", "targetDeviceId": "phone-main", "status": "pending",
                   "protocolVersion": 1, "rulesFingerprint": "0" * 64,
                   "createdAt": "2026-11-08T00:00:00Z",
                   "updatedAt": "2026-11-08T00:00:00Z",
                   "expiresAt": "2026-11-09T00:00:00Z", "segmentCount": 1}
        StubHandler.routes[("GET", "/api/v1/device/apply-tasks")] = (
            200, {"items": [summary], "nextCursor": "cursor-2"})
        _spec, listed = self.call("device.apply.status", {"targetDevice": "phone-main",
                                                          "limit": 1})
        self.assertEqual(1, len(listed["tasks"]))
        self.assertEqual("cursor-2", listed["nextCursor"])
        query = StubHandler.seen[0]["query"]
        self.assertEqual(["phone-main"], query["targetDevice"])
        self.assertEqual(["1"], query["limit"])
        StubHandler.routes[("GET", "/api/v1/device/apply-tasks/task-1")] = (
            200, {"id": "task-1", "status": "pending", "segments": []})
        _spec, detail = self.call("device.apply.status", {"taskId": "task-1"})
        self.assertEqual("task-1", detail["task"]["id"])


# --------------------------------------------------------------------------- #
# 外部执行器控制面
# --------------------------------------------------------------------------- #

class ExecutorTest(StubServerTest):
    def test_claim_returns_lease_and_completed_steps(self):
        lease = {"job": _job(), "leaseToken": LEASE_TOKEN,
                 "leaseUntil": "2026-11-08T01:00:00Z",
                 "completedSteps": ["inventory"]}
        StubHandler.routes[("POST", "/api/v1/maintenance/executor/claim")] = (200, lease)
        _spec, result = self.call("maintenance.claim", {"kind": "asset_research",
                                                       "leaseSeconds": 300})
        self.assertTrue(result["claimed"])
        self.assertEqual(LEASE_TOKEN, result["leaseToken"])
        self.assertEqual(["inventory"], result["completedSteps"])
        body = json.loads(StubHandler.seen[0]["body"].decode("utf-8"))
        self.assertEqual({"kind": "asset_research", "leaseSeconds": 300}, body)

    def test_claim_204_means_no_job(self):
        StubHandler.routes[("POST", "/api/v1/maintenance/executor/claim")] = (204, b"")
        _spec, result = self.call("maintenance.claim", {"kind": "custom"})
        self.assertFalse(result["claimed"])
        self.assertIsNone(result["lease"])

    def test_heartbeat_maps_409_to_lease_lost_without_echoing_token(self):
        StubHandler.routes[("POST", "/api/v1/maintenance/executor/jobs/job-1/heartbeat")] = (
            409, {"code": "LEASE_LOST", "message": "lease expired"})
        with self.assertRaises(errors.ToolError) as caught:
            self.call("maintenance.heartbeat", {"jobId": "job-1", "leaseToken": LEASE_TOKEN,
                                                "leaseSeconds": 120})
        error = caught.exception
        self.assertEqual("LEASE_LOST", error.code)
        self.assertEqual(1, error.exit_code)
        self.assertNotIn(LEASE_TOKEN, json.dumps(error.to_dict(), ensure_ascii=False))
        body = json.loads(StubHandler.seen[0]["body"].decode("utf-8"))
        self.assertEqual(LEASE_TOKEN, body["leaseToken"])

    def test_heartbeat_success(self):
        StubHandler.routes[("POST", "/api/v1/maintenance/executor/jobs/job-1/heartbeat")] = (
            200, {"jobId": "job-1", "leaseUntil": "2026-11-08T01:00:00Z"})
        _spec, result = self.call("maintenance.heartbeat", {"jobId": "job-1",
                                                            "leaseToken": LEASE_TOKEN})
        self.assertEqual("job-1", result["jobId"])

    def test_step_artifact_and_complete_wrappers(self):
        StubHandler.routes[("POST", "/api/v1/maintenance/executor/jobs/job-1/steps")] = (
            200, _job(status="research", stepCount=1))
        _spec, stepped = self.call("maintenance.step.record", {
            "jobId": "job-1", "leaseToken": LEASE_TOKEN, "step": "inventory",
            "status": "completed", "attempt": 1, "detail": {"authors": 2}})
        self.assertEqual("research", stepped["job"]["status"])
        body = json.loads(StubHandler.seen[-1]["body"].decode("utf-8"))
        self.assertEqual({"leaseToken": LEASE_TOKEN, "step": "inventory",
                          "status": "completed", "attempt": 1,
                          "detail": {"authors": 2}}, body)

        StubHandler.routes[("POST", "/api/v1/maintenance/executor/jobs/job-1/artifacts")] = (
            200, _job(artifacts=[]))
        _spec, artifact = self.call("maintenance.artifact.record", {
            "jobId": "job-1", "leaseToken": LEASE_TOKEN, "kind": "report",
            "uri": "<local>", "sha256": "0" * 64, "bytes": 10})
        self.assertIn("job", artifact)
        body = json.loads(StubHandler.seen[-1]["body"].decode("utf-8"))
        self.assertEqual({"leaseToken": LEASE_TOKEN, "kind": "report", "uri": "<local>",
                          "sha256": "0" * 64, "bytes": 10}, body)

        StubHandler.routes[("POST", "/api/v1/maintenance/executor/jobs/job-1/complete")] = (
            200, _job(status="completed"))
        _spec, completed = self.call("maintenance.complete", {
            "jobId": "job-1", "leaseToken": LEASE_TOKEN, "status": "completed"})
        self.assertEqual("completed", completed["job"]["status"])
        body = json.loads(StubHandler.seen[-1]["body"].decode("utf-8"))
        self.assertEqual({"leaseToken": LEASE_TOKEN, "status": "completed"}, body)

    def test_forbidden_scope_mapping(self):
        StubHandler.routes[("POST", "/api/v1/maintenance/executor/claim")] = (
            403, {"code": "FORBIDDEN_SCOPE", "message": "scope missing"})
        with self.assertRaises(errors.ToolError) as caught:
            self.call("maintenance.claim", {"kind": "custom"})
        self.assertEqual("FORBIDDEN_SCOPE", caught.exception.code)
        self.assertNotIn(LEASE_TOKEN, json.dumps(caught.exception.to_dict()))

    def test_invalid_lease_token_is_rejected_before_network(self):
        with self.assertRaises(errors.ToolError) as caught:
            self.call("maintenance.complete", {"jobId": "job-1", "leaseToken": "",
                                               "status": "completed"})
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        self.assertEqual([], StubHandler.seen)


# --------------------------------------------------------------------------- #
# research_kit 适配：argv 组装、路径边界、超时与缺失脚本
# --------------------------------------------------------------------------- #

def _fake_run(argv, *, env, timeout):
    return {"exitCode": 0, "timedOut": False, "elapsedMs": 3,
            "stdout": b'{"ok":true}', "stderr": b""}


class ResearchAdapterTest(StubServerTest):
    def _files(self, directory: Path):
        jobs = directory / "jobs.jsonl"
        jobs.write_text('{"id":"job-1","file":"synthetic.webp"}\n', encoding="utf-8")
        image = directory / "frame.webp"
        image.write_bytes(b"synthetic-image")
        video = directory / "clip.mp4"
        video.write_bytes(b"synthetic-video")
        return jobs, image, video

    def _config(self, work):
        # 研究产物根必须与执行上下文 env 一致：config 与 env 都用同一覆盖值。
        self._work_env = {"QIMENG_AI_RESEARCH_DIR": str(work)}
        return self.config(**self._work_env)

    def call(self, tool_id, payload, config=None):
        env = self.env(**getattr(self, "_work_env", {}))
        return execution.execute_tool(tool_id, payload, env=env,
                                      config=config or execution.load_config(env))

    def test_pipeline_builds_list_argv_and_returns_artifacts(self):
        captured = {}

        def fake(argv, *, env, timeout):
            captured.update({"argv": argv, "env": env, "timeout": timeout})
            Path(argv[argv.index("--out") + 1]).write_text('{"id":"job-1"}\n',
                                                           encoding="utf-8")
            return _fake_run(argv, env=env, timeout=timeout)

        with temp_dir() as directory, temp_dir() as work:
            jobs, _image, _video = self._files(directory)
            with patch.object(tools_research, "_execute", side_effect=fake):
                _spec, result = self.call("research.image.pipeline", {
                    "jobsPath": str(jobs), "maxLens": 5, "dryRun": True,
                    "outDir": str(work),
                }, config=self._config(work))
            argv = captured["argv"]
            self.assertIsInstance(argv, list)
            self.assertTrue(all(isinstance(item, str) for item in argv))
            self.assertIn("--dry-run", argv)
            self.assertEqual("5", argv[argv.index("--max-lens") + 1])
            self.assertEqual(300, captured["timeout"])
            self.assertNotIn("QIMENG_API_TOKEN", captured["env"])
            self.assertEqual(0, result["exitCode"])
            self.assertEqual(1, len(result["artifacts"]))
            self.assertEqual(_sha256(Path(result["artifacts"][0]["path"]).read_bytes()),
                             result["artifacts"][0]["sha256"])
            self.assertNotIn("stdout", result)
            self.assertIn("summary", result)

    def test_search_variants_build_expected_argv(self):
        captured = []

        def fake(argv, *, env, timeout):
            captured.append(list(argv))
            return _fake_run(argv, env=env, timeout=timeout)

        with temp_dir() as directory, temp_dir() as work:
            jobs, image, _video = self._files(directory)
            config = self._config(work)
            with patch.object(tools_research, "_execute", side_effect=fake), \
                    patch.object(tools_research, "_node_binary", return_value="/fake/node"):
                self.call("research.image.search",
                          {"script": "lens", "jobsPath": str(jobs), "limit": 3},
                          config=config)
                self.call("research.image.search",
                          {"script": "yandex", "imagePath": str(image),
                           "maxAgeHours": 24}, config=config)
                self.call("research.image.search",
                          {"script": "saucenao", "jobsPath": str(jobs)}, config=config)
            lens, yandex, saucenao = captured
            self.assertEqual("/fake/node", lens[0])
            self.assertIn("lens_batch.mjs", lens[1])
            self.assertEqual("3", lens[lens.index("--limit") + 1])
            self.assertIn("yandex_cbir.py", " ".join(yandex))
            self.assertIn(str(image), yandex)
            self.assertEqual("24", yandex[yandex.index("--max-age-hours") + 1])
            self.assertIn("saucenao_batch.py", " ".join(saucenao))

    def test_search_rejects_mismatched_inputs(self):
        with temp_dir() as directory, temp_dir() as work:
            jobs, image, _video = self._files(directory)
            config = self._config(work)
            with self.assertRaises(errors.ToolError) as caught:
                self.call("research.image.search",
                          {"script": "yandex", "jobsPath": str(jobs)}, config=config)
            self.assertEqual("INVALID_INPUT", caught.exception.code)
            with self.assertRaises(errors.ToolError) as caught:
                self.call("research.image.search",
                          {"script": "lens", "jobsPath": str(jobs),
                           "imagePath": str(image)}, config=config)
            self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_lens_without_node_is_not_implemented(self):
        with temp_dir() as directory, temp_dir() as work:
            jobs, _image, _video = self._files(directory)
            with patch.object(tools_research.shutil, "which", return_value=None):
                with self.assertRaises(errors.ToolError) as caught:
                    self.call("research.image.search",
                              {"script": "lens", "jobsPath": str(jobs)},
                              config=self._config(work))
            self.assertEqual("TOOL_NOT_IMPLEMENTED", caught.exception.code)

    def test_author_probe_captures_stdout_artifact_when_script_has_no_out(self):
        captured = {}

        def fake(argv, *, env, timeout):
            captured["argv"] = list(argv)
            return dict(_fake_run(argv, env=env, timeout=timeout),
                        stdout=b'{"creator":"synthetic"}')

        with temp_dir() as work:
            config = self._config(work)
            with patch.object(tools_research, "_execute", side_effect=fake):
                _spec, result = self.call("research.author.probe", {
                    "script": "kemono", "service": "patreon", "creator": "synthetic"},
                    config=config)
            argv = captured["argv"]
            self.assertIn("kemono_probe.py", [Path(item).name for item in argv])
            self.assertIn("--service", argv)
            self.assertIn("patreon", argv)
            self.assertIn("--creator", argv)
            paths = [item["path"] for item in result["artifacts"]]
            self.assertTrue(any(path.endswith("kemono-result.json") for path in paths))

    def test_media_probe_and_avatar_fetch_argv(self):
        captured = []

        def fake(argv, *, env, timeout):
            captured.append(list(argv))
            return _fake_run(argv, env=env, timeout=timeout)

        with temp_dir() as directory, temp_dir() as work:
            _jobs, _image, video = self._files(directory)
            config = self._config(work)
            with patch.object(tools_research, "_execute", side_effect=fake):
                self.call("research.media.probe",
                          {"files": [str(video)], "frames": "15,50"}, config=config)
                self.call("research.avatar.fetch",
                          {"urls": ["https://example.com/a.png"], "outDir": str(work)},
                          config=config)
            media, avatar = captured
            self.assertIn(str(video), media)
            self.assertIn("--frame-dir", media)
            self.assertEqual("15,50", media[media.index("--frames") + 1])
            self.assertIn("--url", avatar)
            self.assertIn("https://example.com/a.png", avatar)
            self.assertIn("--out-dir", avatar)

    def test_avatar_requires_single_input_source(self):
        with temp_dir() as directory, temp_dir() as work:
            jobs, _image, _video = self._files(directory)
            config = self._config(work)
            with self.assertRaises(errors.ToolError) as caught:
                self.call("research.avatar.fetch",
                          {"jobsPath": str(jobs)}, config=config)
            self.assertEqual("INVALID_INPUT", caught.exception.code)
            with self.assertRaises(errors.ToolError) as caught:
                self.call("research.avatar.fetch",
                          {"jobsPath": str(jobs), "urls": ["https://example.com/a.png"],
                           "outDir": str(work)}, config=config)
            self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_relative_and_repo_paths_are_rejected(self):
        with temp_dir() as work:
            config = self._config(work)
            with self.assertRaises(errors.ToolError) as caught:
                self.call("research.image.pipeline", {"jobsPath": "relative/jobs.jsonl"},
                          config=config)
            self.assertEqual("INVALID_INPUT", caught.exception.code)
            with self.assertRaises(errors.ToolError) as caught:
                self.call("research.image.pipeline", {"jobsPath": str(RULES)},
                          config=config)
            self.assertEqual("INVALID_INPUT", caught.exception.code)
            self.assertEqual([], StubHandler.seen)

    def test_missing_script_is_reported_not_implemented(self):
        with temp_dir() as directory, temp_dir() as work, temp_dir() as empty_kit:
            jobs, _image, _video = self._files(directory)
            with patch.object(tools_research, "RESEARCH_KIT_DIR", empty_kit):
                with self.assertRaises(errors.ToolError) as caught:
                    self.call("research.image.pipeline", {"jobsPath": str(jobs)},
                              config=self._config(work))
            self.assertEqual("TOOL_NOT_IMPLEMENTED", caught.exception.code)

    def test_execute_enforces_timeout(self):
        run = tools_research._execute(
            [sys.executable, "-c", "import time; time.sleep(5)"],
            env={"PYTHONIOENCODING": "utf-8"}, timeout=1)
        self.assertTrue(run["timedOut"])
        self.assertEqual(124, run["exitCode"])

    def test_pathguard_allows_ignored_work_roots_only(self):
        with temp_dir() as directory:
            allowed = pathguard.resolve_local_path(str(directory), env={})
            self.assertTrue(str(allowed).startswith(str(_TEMP_ROOT)))
            with self.assertRaises(errors.ToolError):
                pathguard.resolve_local_path(str(ROOT / "server" / "x.txt"), env={})


# --------------------------------------------------------------------------- #
# 回收站与资产工具
# --------------------------------------------------------------------------- #

class TrashAndAssetTest(StubServerTest):
    def test_trash_list_is_read_only(self):
        StubHandler.routes[("GET", "/api/v1/trash")] = (200, [_trash_item()])
        _spec, result = self.call("trash.list", {})
        self.assertEqual("trash-1", result[0]["id"])

    def test_trash_restore_requires_confirmation_and_reads_back(self):
        StubHandler.routes[("GET", "/api/v1/trash")] = (200, [_trash_item()])
        with self.assertRaises(errors.ToolError) as caught:
            self.call("trash.restore", {"trashId": "trash-1", "confirm": False})
        self.assertEqual("NOT_CONFIRMED", caught.exception.code)

        StubHandler.routes[("GET", "/api/v1/trash")] = (
            [(200, [_trash_item()]), (200, [])])
        StubHandler.routes[("POST", "/api/v1/trash/trash-1/restore")] = (200, {})
        _spec, result = self.call("trash.restore", {"trashId": "trash-1", "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertFalse(result["readback"]["stillInTrash"])

    def test_trash_restore_missing_entry_fails_before_write(self):
        StubHandler.routes[("GET", "/api/v1/trash")] = (200, [])
        with self.assertRaises(errors.ToolError) as caught:
            self.call("trash.restore", {"trashId": "trash-1", "confirm": True})
        self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
        self.assertEqual([], [item for item in StubHandler.seen if item["method"] == "POST"])

    def test_asset_trash_moves_to_trash_and_reads_back_404(self):
        asset = {"id": "asset-1", "fileName": "synthetic.png", "libraryId": "lib-1",
                 "relPath": "synthetic.png", "tags": []}
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = (
            [(200, asset), (404, {"code": "NOT_FOUND"})])
        StubHandler.routes[("DELETE", "/api/v1/assets/asset-1")] = (200, None)
        _spec, result = self.call("asset.trash", {"assetId": "asset-1", "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertTrue(result["readback"]["assetGone"])
        methods = [item["method"] for item in StubHandler.seen]
        self.assertEqual(["GET", "DELETE", "GET"], methods)

    def test_asset_tags_replace_plan_confirm_and_readback(self):
        old_tags = [{"id": "tag-1", "name": "合成"}]
        asset_before = {"id": "asset-1", "fileName": "synthetic.png",
                        "relPath": "synthetic.png", "tags": old_tags}
        asset_after = dict(asset_before, tags=[{"id": "tag-2", "name": "合成二"}])
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = (200, asset_before)
        _spec, planned = self.call("asset.tags.replace", {
            "assetId": "asset-1", "tagIds": ["tag-2"], "confirm": False})
        plan = planned["plan"]
        self.assertTrue(plan["planFingerprint"])
        self.assertEqual([], [item for item in StubHandler.seen if item["method"] == "PUT"])

        StubHandler.seen = []
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = (
            [(200, asset_before), (200, asset_before), (200, asset_after)])
        StubHandler.routes[("PUT", "/api/v1/assets/asset-1/tags")] = (204, None)
        _spec, result = self.call("asset.tags.replace", {
            "assetId": "asset-1", "tagIds": ["tag-2"], "confirm": True,
            "planFingerprint": plan["planFingerprint"]})
        self.assertEqual("applied", result["status"])
        self.assertEqual(["tag-2"], result["readback"]["tagIds"])
        put = next(item for item in StubHandler.seen if item["method"] == "PUT")
        self.assertEqual({"tagIds": ["tag-2"]},
                         json.loads(put["body"].decode("utf-8")))

    def test_asset_tags_replace_rejects_stale_fingerprint(self):
        asset = {"id": "asset-1", "fileName": "synthetic.png", "tags": []}
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = (200, asset)
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.tags.replace", {
                "assetId": "asset-1", "tagIds": ["tag-2"], "confirm": True,
                "planFingerprint": "0" * 64})
        self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
        self.assertEqual([], [item for item in StubHandler.seen if item["method"] == "PUT"])

    def test_asset_tags_replace_detects_readback_mismatch(self):
        asset = {"id": "asset-1", "fileName": "synthetic.png", "tags": []}
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = (200, asset)
        _spec, planned = self.call("asset.tags.replace", {
            "assetId": "asset-1", "tagIds": ["tag-2"], "confirm": False})
        StubHandler.routes[("PUT", "/api/v1/assets/asset-1/tags")] = (204, None)
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.tags.replace", {
                "assetId": "asset-1", "tagIds": ["tag-2"], "confirm": True,
                "planFingerprint": planned["plan"]["planFingerprint"]})
        self.assertEqual("READBACK_MISMATCH", caught.exception.code)


if __name__ == "__main__":
    unittest.main()
