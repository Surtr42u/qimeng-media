"""2026-11-28 本机直接入库工具回归：`asset.ingest.local.prepare` / `asset.ingest.local`。

全部使用合成文件、临时目录与 127.0.0.1 stub；不访问 8420、不移动真实媒体。
覆盖：注册与目录可见性、入参与路径逃逸拒绝、计划内容与预期状态、确认/指纹/实例、
already_present 幂等、回读不一致、服务端错误映射、源在 prepare 后变化与部分失败上报。
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock
from urllib import parse

from ai_maintenance import client as http_client
from ai_maintenance import errors, execution, plans, registry, tools_ingest_local

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "api" / "openapi.yaml"
RULES = ROOT / "tools" / "ai_maintenance" / "rules.json"
TOKEN = "-".join(("synthetic", "ingest", "local", "credential"))

_TEMP_ROOT = ROOT / "tools" / "ai_maintenance" / ".test-temp"
try:
    _TEMP_ROOT.mkdir(parents=True, exist_ok=True)
except OSError:
    _TEMP_ROOT = Path(tempfile.gettempdir())


class StubHandler(BaseHTTPRequestHandler):
    routes = {}
    seen = []

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def _handle(self, method):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        body = self.rfile.read(length) if length > 0 else b""
        parsed = parse.urlsplit(self.path)
        type(self).seen.append({"method": method, "path": parsed.path, "body": body,
                                "authorization": self.headers.get("Authorization")})
        route = type(self).routes.get((method, parsed.path), (404, {"code": "NOT_FOUND"}))
        if isinstance(route, list):
            status, payload = route.pop(0) if len(route) > 1 else route[0]
        else:
            status, payload = route
        raw = b"" if payload is None else \
            payload if isinstance(payload, bytes) else \
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
        self.work = Path(tempfile.mkdtemp(prefix="ingest-local-", dir=str(_TEMP_ROOT)))
        self.addCleanup(shutil.rmtree, self.work, ignore_errors=True)
        self.storage = self.work / "inbox"
        self.storage.mkdir()
        self.library_root = self.work / "library"
        self.library_root.mkdir()
        self.library_id = "lib-synthetic"
        self.library = {"id": self.library_id, "name": "合成库", "kind": "normal",
                        "rootPath": str(self.library_root)}
        StubHandler.routes[("GET", "/api/v1/libraries")] = (200, [self.library])

    # ---- 辅助 ------------------------------------------------------------- #

    def env(self, **extra):
        return dict({
            "QIMENG_API_BASE": self.base,
            "QIMENG_API_TOKEN": TOKEN,
            "QIMENG_AI_GRANTED_PERMISSIONS": "all",
            "QIMENG_AI_STORAGES": f"inbox={self.storage}",
            "QIMENG_AI_MCP_DISCOVERY": "full",
        }, **extra)

    def call(self, tool_id, payload, env=None):
        environment = self.env() if env is None else env
        return execution.execute_tool(tool_id, payload, env=environment,
                                      config=execution.load_config(environment))

    def source(self, name="合成 1.png", data=b"\x89PNG\r\n\x1a\n" + b"synthetic" * 4):
        path = self.storage / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def prepare(self, paths, **extra):
        payload = {"libraryId": self.library_id, "paths": [str(item) for item in paths]}
        payload.update(extra)
        _spec, result = self.call("asset.ingest.local.prepare", payload)
        return result["plan"], result["diagnostics"]

    def serve_ingest(self, http_status=201, **overrides):
        body = {"status": "ingested", "assetId": "asset-1", "libraryId": self.library_id,
                "relPath": "合成 1.png", "fileName": "合成 1.png", "mediaType": "image",
                "sizeBytes": 44, "sha256": None, "sourceKept": False}
        body.update(overrides)
        StubHandler.routes[("POST", f"/api/v1/libraries/{self.library_id}/ingest-local")] = \
            (http_status, body)
        return body

    def serve_asset(self, **overrides):
        asset = {"id": "asset-1", "libraryId": self.library_id, "relPath": "合成 1.png",
                 "fileName": "合成 1.png", "mediaType": "image", "sizeBytes": 44}
        asset.update(overrides)
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = (200, asset)
        return asset

    def posts(self):
        return [item for item in StubHandler.seen if item["method"] == "POST"]


class RegistrationTest(unittest.TestCase):
    def setUp(self):
        self.env = {"QIMENG_AI_OPENAPI": str(OPENAPI), "QIMENG_AI_RULES": str(RULES),
                    "QIMENG_AI_GRANTED_PERMISSIONS": "all",
                    "QIMENG_AI_MCP_DISCOVERY": "full"}
        self.config = execution.load_config(self.env)
        self.document = self.config.document()

    def test_tools_registered_with_expected_flags(self):
        prepare = registry.get("asset.ingest.local.prepare")
        apply_tool = registry.get("asset.ingest.local")
        self.assertIsNotNone(prepare)
        self.assertIsNotNone(apply_tool)
        # 复用上传的写入能力，不新增权限门（用户要求不额外加拦截）。
        self.assertEqual("asset.upload", prepare.permission)
        self.assertEqual("asset.upload", apply_tool.permission)
        self.assertEqual("plan", prepare.kind)
        self.assertTrue(prepare.read_only)
        self.assertFalse(prepare.side_effects)
        self.assertEqual("write", apply_tool.kind)
        self.assertTrue(apply_tool.side_effects)
        self.assertTrue(apply_tool.requires_confirmation)
        self.assertEqual("always", apply_tool.confirmation_mode)
        for spec in (prepare, apply_tool):
            self.assertEqual("media", spec.domain)
            self.assertEqual("supported", registry.tool_status(spec, self.document)["status"])

    def test_catalog_and_describe_list_new_tools(self):
        _spec, catalog = execution.execute_tool("platform.catalog", {"domain": "media"},
                                                env=self.env, config=self.config)
        ids = {item["id"] for item in catalog["tools"]}
        self.assertIn("asset.ingest.local.prepare", ids)
        self.assertIn("asset.ingest.local", ids)
        _spec, described = execution.execute_tool(
            "platform.tool.describe", {"toolId": "asset.ingest.local"},
            env=self.env, config=self.config)
        tool = described["tool"]
        self.assertIn("plan", tool["inputSchema"]["properties"])
        self.assertIn("ingestLocal", tool["rulesExcerpt"])
        self.assertEqual([], described["missingRuleSections"])
        _spec, prepare_described = execution.execute_tool(
            "platform.tool.describe", {"toolId": "asset.ingest.local.prepare"},
            env=self.env, config=self.config)
        properties = prepare_described["tool"]["inputSchema"]["properties"]
        self.assertEqual(["libraryId", "paths"],
                         prepare_described["tool"]["inputSchema"]["required"])
        self.assertEqual(50, properties["paths"]["maxItems"])
        self.assertEqual(1, properties["paths"]["minItems"])

    def test_declares_scaled_timeout_budget(self):
        spec = registry.get("asset.ingest.local")
        # 声明值取动态预算封顶（600s）：跨分区 3GB 级复制不能被默认 20s 打断。
        self.assertEqual(600.0, spec.timeout_seconds)
        self.assertIn("60s", spec.description)
        self.assertEqual(60, tools_ingest_local.request_timeout(0))
        self.assertEqual(120, tools_ingest_local.request_timeout(1024))  # 不足 1 GiB 按 1 GiB 计
        self.assertEqual(240, tools_ingest_local.request_timeout(3 << 30))
        self.assertEqual(300, tools_ingest_local.request_timeout(4 << 30))
        self.assertEqual(600, tools_ingest_local.request_timeout(64 << 30))

    def test_rules_describe_ingest_local_risk_and_limits(self):
        from ai_maintenance import rulepack
        rules = rulepack.load_rules(RULES)
        section = rules["ingestLocal"]
        self.assertEqual(["asset.ingest.local.prepare", "asset.ingest.local"],
                         section["tools"])
        self.assertIn("QIMENG_AI_STORAGES", section["invariants"][0])
        self.assertIn("READBACK_MISMATCH", section["errors"])
        self.assertIn("asset.upload", rules["permissions"]["toolPermissions"])
        self.assertIn("本机直接入库", rules["permissions"]["toolPermissions"]["asset.upload"])
        self.assertEqual([], [item for item in
                              rulepack.consistency_problems(rules, self.document)
                              if item["severity"] == "error"])


class PrepareTest(StubServerTest):
    def test_prepare_is_read_only_and_builds_plan(self):
        source = self.source()
        plan, diagnostics = self.prepare([source], directory="合成目录",
                                         operationId="00000000-0000-4000-8000-000000000011")
        self.assertEqual([], self.posts())
        self.assertEqual("asset.ingest.local", plan["tool"])
        self.assertEqual("asset.ingest.local", plan["kind"])
        self.assertEqual(self.library_id, plan["target"]["library"]["id"])
        change = plan["changes"][0]
        self.assertEqual(str(source), change["path"])
        self.assertEqual("合成 1.png", change["fileName"])
        self.assertEqual("合成目录", change["directory"])
        self.assertEqual("合成目录/合成 1.png", change["relPath"])
        self.assertEqual(source.stat().st_size, change["bytes"])
        self.assertIsNone(change["sha256"])
        self.assertEqual("move", change["expectedStatus"])
        self.assertTrue(plan["planFingerprint"])
        self.assertTrue(plan["idempotencyKey"])
        self.assertEqual("00000000-0000-4000-8000-000000000011", plan["operationId"])
        self.assertTrue(plan["confirmation"]["required"])
        self.assertEqual("合成目录/合成 1.png", diagnostics["files"][0]["relPath"])

    def test_prepare_predicts_already_present_for_same_size_target(self):
        source = self.source()
        target = self.library_root / "合成 1.png"
        target.write_bytes(source.read_bytes())
        plan, _diagnostics = self.prepare([source])
        self.assertEqual("already_present", plan["changes"][0]["expectedStatus"])

    def test_prepare_predicts_conflict_for_different_size_target(self):
        source = self.source()
        (self.library_root / "合成 1.png").write_bytes(b"tiny")
        plan, _diagnostics = self.prepare([source])
        self.assertEqual("conflict", plan["changes"][0]["expectedStatus"])

    def test_prepare_rejects_invalid_arguments(self):
        source = self.source()
        cases = [
            ({"paths": "not-a-list"}, "INVALID_INPUT"),
            ({"paths": []}, "INVALID_INPUT"),
            ({"paths": ["relative.png"]}, "INVALID_INPUT"),
            ({"paths": [str(self.storage)]}, "INVALID_INPUT"),  # 目录不是普通文件
            ({"paths": [str(source), str(source)]}, "INVALID_INPUT"),
            ({"paths": [str(source)], "directory": "/abs"}, "INVALID_INPUT"),
            ({"paths": [str(source)], "directory": "../x"}, "INVALID_INPUT"),
            ({"paths": [str(source)], "operationId": "not-a-uuid"}, "INVALID_INPUT"),
            ({"paths": [str(source)] + [f"{self.storage}/x{index}.png" for index in range(50)]},
             "INVALID_INPUT"),  # schema maxItems=50
        ]
        for payload, code in cases:
            with self.subTest(payload=payload):
                with self.assertRaises(errors.ToolError) as caught:
                    self.call("asset.ingest.local.prepare",
                              {"libraryId": self.library_id, **payload})
                self.assertEqual(code, caught.exception.code)
        self.assertEqual([], self.posts())

    def test_prepare_rejects_dotdot_and_outside_root(self):
        outside = self.work / "outside.png"
        outside.write_bytes(b"outside")
        for raw, code in ((str(outside), "NOT_IN_STORAGE_ROOT"),
                          (str(self.storage / ".." / "outside.png"), "PATH_UNSAFE")):
            with self.subTest(raw=raw):
                with self.assertRaises(errors.ToolError) as caught:
                    self.call("asset.ingest.local.prepare",
                              {"libraryId": self.library_id, "paths": [raw]})
                self.assertEqual(code, caught.exception.code)
                self.assertNotIn(str(outside), json.dumps(caught.exception.details or {},
                                                          ensure_ascii=False))

    def test_prepare_rejects_symlink_and_escape(self):
        outside = self.work / "outside.png"
        outside.write_bytes(b"outside")
        inside = self.source("链接内.png")
        link_out = self.storage / "逃逸.png"
        link_in = self.storage / "链接内.png.link"
        try:
            os.symlink(outside, link_out)
            os.symlink(inside, link_in)
        except (OSError, NotImplementedError):
            self.skipTest("当前环境无创建符号链接的权限")
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local.prepare",
                      {"libraryId": self.library_id, "paths": [str(link_out)]})
        self.assertIn(caught.exception.code, ("NOT_IN_STORAGE_ROOT", "PATH_UNSAFE"))
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local.prepare",
                      {"libraryId": self.library_id, "paths": [str(link_in)]})
        self.assertEqual("PATH_UNSAFE", caught.exception.code)

    def test_prepare_rejects_source_inside_library_root(self):
        inside_library = self.library_root / "已入库.png"
        inside_library.write_bytes(b"library")
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local.prepare",
                      {"libraryId": self.library_id, "paths": [str(inside_library)]},
                      env=self.env(QIMENG_AI_STORAGES=f"inbox={self.library_root}"))
        self.assertEqual("SOURCE_IN_LIBRARY_ROOT", caught.exception.code)

    def test_prepare_requires_storage_config(self):
        source = self.source()
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local.prepare",
                      {"libraryId": self.library_id, "paths": [str(source)]},
                      env=self.env(QIMENG_AI_STORAGES=""))
        self.assertEqual("NOT_IN_STORAGE_ROOT", caught.exception.code)

    def test_prepare_rejects_unknown_library(self):
        source = self.source()
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local.prepare",
                      {"libraryId": "lib-missing", "paths": [str(source)]})
        self.assertEqual("LIBRARY_NOT_FOUND", caught.exception.code)

    def test_prepare_rejects_duplicate_target_rel_path(self):
        first = self.source("同名.png")
        second_dir = self.storage / "子目录"
        second_dir.mkdir()
        second = second_dir / "同名.png"
        second.write_bytes(b"second")
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local.prepare",
                      {"libraryId": self.library_id, "paths": [str(first), str(second)]})
        self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_prepare_enforces_total_bytes_limit(self):
        source = self.source()
        with mock.patch.object(tools_ingest_local, "MAX_TOTAL_BYTES", 4):
            with self.assertRaises(errors.ToolError) as caught:
                self.call("asset.ingest.local.prepare",
                          {"libraryId": self.library_id, "paths": [str(source)]})
        self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_prepare_is_deterministic_for_same_operation_id(self):
        source = self.source()
        first, _ = self.prepare([source],
                                operationId="00000000-0000-4000-8000-000000000021")
        second, _ = self.prepare([source],
                                 operationId="00000000-0000-4000-8000-000000000021")
        self.assertEqual(first["planFingerprint"], second["planFingerprint"])
        self.assertEqual(first["idempotencyKey"], second["idempotencyKey"])


class ApplyTest(StubServerTest):
    def _plan(self, **kwargs):
        source = kwargs.pop("source", None) or self.source()
        plan, _diagnostics = self.prepare([source], **kwargs)
        return source, plan

    def test_apply_moves_and_reads_back(self):
        source, plan = self._plan()
        self.serve_ingest()
        self.serve_asset()
        _spec, result = self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertEqual(1, result["counts"]["ingested"])
        self.assertEqual(0, result["counts"]["alreadyPresent"])
        self.assertEqual("asset-1", result["files"][0]["assetId"])
        self.assertEqual("合成 1.png", result["files"][0]["relPath"])
        self.assertFalse(result["files"][0]["sourceKept"])
        self.assertEqual("合成 1.png", result["files"][0]["readback"]["relPath"])
        post = self.posts()[0]
        self.assertEqual({"sourcePath": str(source), "fileName": "合成 1.png"},
                         json.loads(post["body"].decode("utf-8")))
        methods = [item["method"] for item in StubHandler.seen]
        # prepare 已经读过一次库列表；apply 的顺序是 库列表 → 逐文件 POST → 回读资产。
        self.assertEqual(["GET", "GET", "POST", "GET"], methods)

    def test_apply_requires_confirmation(self):
        _source, plan = self._plan()
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": False})
        self.assertEqual("NOT_CONFIRMED", caught.exception.code)
        self.assertEqual([], self.posts())

    def test_apply_rejects_tampered_plan(self):
        _source, plan = self._plan()
        tampered = json.loads(json.dumps(plan))
        tampered["changes"][0]["bytes"] += 1
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": tampered, "confirm": True})
        self.assertEqual("PLAN_FINGERPRINT_MISMATCH", caught.exception.code)
        self.assertEqual([], self.posts())

    def test_apply_rejects_changed_source(self):
        source, plan = self._plan()
        source.write_bytes(source.read_bytes() + b"more")
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
        self.assertFalse(getattr(caught.exception, "operation_may_have_completed", False))
        self.assertEqual([], self.posts())

    def test_apply_rejects_plan_from_other_instance(self):
        _source, plan = self._plan()
        other = json.loads(json.dumps(plan))
        other["target"]["instance"] = "example.invalid:9"
        other["planFingerprint"] = plans.compute_fingerprint(other)
        other["idempotencyKey"] = plans.compute_idempotency_key(
            "asset.ingest.local", other["planFingerprint"], "example.invalid:9")
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": other, "confirm": True})
        self.assertEqual("PRECONDITION_FAILED", caught.exception.code)

    def test_apply_requires_storage_authorization_again(self):
        _source, plan = self._plan()
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True},
                      env=self.env(QIMENG_AI_STORAGES=""))
        self.assertEqual("NOT_IN_STORAGE_ROOT", caught.exception.code)
        self.assertEqual([], self.posts())

    def test_already_present_is_idempotent_success(self):
        source, plan = self._plan()
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        self.serve_ingest(http_status=200, status="already_present",
                          sha256=digest, sourceKept=True)
        self.serve_asset()
        for attempt in (1, 2):
            with self.subTest(attempt=attempt):
                _spec, result = self.call("asset.ingest.local",
                                          {"plan": plan, "confirm": True})
                self.assertEqual("already_applied", result["status"])
                self.assertEqual(1, result["counts"]["alreadyPresent"])
                self.assertTrue(result["files"][0]["sourceKept"])
                self.assertEqual(digest, result["files"][0]["sha256"])
        self.assertTrue(source.exists())  # already_present 时源不动（服务端语义）
        self.assertEqual(2, len(self.posts()))

    def test_apply_readback_size_mismatch_fails(self):
        _source, plan = self._plan()
        self.serve_ingest()
        self.serve_asset(sizeBytes=1)
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("READBACK_MISMATCH", caught.exception.code)
        self.assertEqual({"sizeBytes"}, set(caught.exception.details["mismatches"]))

    def test_apply_missing_asset_after_ingest_fails(self):
        _source, plan = self._plan()
        self.serve_ingest()
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = (404, {"code": "NOT_FOUND"})
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("READBACK_MISMATCH", caught.exception.code)

    def test_apply_detects_server_cleaned_rel_path(self):
        _source, plan = self._plan()
        self.serve_ingest(relPath="合成_1.png", fileName="合成_1.png")
        self.serve_asset(relPath="合成_1.png", fileName="合成_1.png")
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("READBACK_MISMATCH", caught.exception.code)
        self.assertEqual("合成 1.png", caught.exception.details["plannedRelPath"])

    def test_apply_maps_target_exists(self):
        _source, plan = self._plan()
        StubHandler.routes[("POST", f"/api/v1/libraries/{self.library_id}/ingest-local")] = \
            (409, {"code": "CONFLICT"})
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("TARGET_EXISTS", caught.exception.code)
        self.assertEqual(1, caught.exception.exit_code)
        # 409 在落位前返回：不进入未决写入，避免误导为“可能已写入”。
        self.assertFalse(getattr(caught.exception, "operation_may_have_completed", False))

    def test_apply_maps_forbidden_unavailable_and_storage(self):
        cases = ((403, {"code": "PATH_ESCAPE"}, "NOT_IN_STORAGE_ROOT"),
                 (404, {"code": "NOT_FOUND"}, "API_UNAVAILABLE"),
                 (405, {"code": "METHOD_NOT_ALLOWED"}, "API_UNAVAILABLE"),
                 (507, {"code": "STORAGE_UNAVAILABLE"}, "STORAGE_UNAVAILABLE"),
                 (400, {"code": "INVALID_PARAM"}, "INVALID_INPUT"))
        for status, body, code in cases:
            with self.subTest(status=status):
                _source, plan = self._plan()
                StubHandler.routes[
                    ("POST", f"/api/v1/libraries/{self.library_id}/ingest-local")] = (status, body)
                with self.assertRaises(errors.ToolError) as caught:
                    self.call("asset.ingest.local", {"plan": plan, "confirm": True})
                self.assertEqual(code, caught.exception.code)
                self.assertFalse(
                    getattr(caught.exception, "operation_may_have_completed", False))

    def test_apply_maps_library_not_found(self):
        _source, plan = self._plan()
        StubHandler.routes[("POST", f"/api/v1/libraries/{self.library_id}/ingest-local")] = \
            (404, {"code": "LIBRARY_NOT_FOUND"})
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("LIBRARY_NOT_FOUND", caught.exception.code)

    def test_apply_partial_failure_reports_completed(self):
        first = self.source("第一张.png")
        second = self.source("第二张.png")
        plan, _diagnostics = self.prepare([first, second])
        StubHandler.routes[("POST", f"/api/v1/libraries/{self.library_id}/ingest-local")] = [
            (201, {"status": "ingested", "assetId": "asset-1",
                   "libraryId": self.library_id, "relPath": "第一张.png",
                   "fileName": "第一张.png", "mediaType": "image",
                   "sizeBytes": first.stat().st_size, "sha256": None, "sourceKept": False}),
            (409, {"code": "CONFLICT"}),
        ]
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = (
            200, {"id": "asset-1", "libraryId": self.library_id, "relPath": "第一张.png",
                  "fileName": "第一张.png", "mediaType": "image",
                  "sizeBytes": first.stat().st_size})
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("TARGET_EXISTS", caught.exception.code)
        self.assertEqual([{"assetId": "asset-1", "status": "ingested",
                           "relPath": "第一张.png"}],
                         caught.exception.details["completed"])
        # 第一个文件已落位入库：整批不能当作“完全未写入”。
        self.assertTrue(getattr(caught.exception, "operation_may_have_completed", False))
        self.assertEqual(2, len(self.posts()))

    def test_post_uses_timeout_scaled_by_file_bytes(self):
        source, plan = self._plan()
        self.serve_ingest()
        self.serve_asset()
        captured = {}
        original = http_client.ApiClient.post

        def spy(instance, path, json_body=None, data=None, content_type=None, timeout=None):
            captured["timeout"] = timeout
            return original(instance, path, json_body=json_body, data=data,
                            content_type=content_type, timeout=timeout)

        with mock.patch.object(http_client.ApiClient, "post", spy):
            _spec, result = self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertEqual(tools_ingest_local.request_timeout(source.stat().st_size),
                         captured["timeout"])
        self.assertGreater(captured["timeout"], 20)

    def test_401_and_scope_rejection_are_not_pending_writes(self):
        cases = ((401, {"code": "UNAUTHORIZED"}, "CONFIGURATION_ERROR"),
                 (403, {"code": "FORBIDDEN_SCOPE"}, "FORBIDDEN_SCOPE"))
        for status, body, code in cases:
            with self.subTest(status=status):
                _source, plan = self._plan()
                StubHandler.routes[
                    ("POST", f"/api/v1/libraries/{self.library_id}/ingest-local")] = (status, body)
                with self.assertRaises(errors.ToolError) as caught:
                    self.call("asset.ingest.local", {"plan": plan, "confirm": True})
                self.assertEqual(code, caught.exception.code)
                # 确定未写入（鉴权/scope 在任何文件操作之前拒绝），不得进未决账本。
                self.assertFalse(
                    getattr(caught.exception, "operation_may_have_completed", False))
                self.assertEqual([], caught.exception.details["completed"])

    def test_source_not_removed_is_rejected(self):
        source, plan = self._plan()
        self.serve_ingest(sourceKept=True)
        self.serve_asset()
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("SOURCE_NOT_REMOVED", caught.exception.code)
        self.assertEqual("asset-1", caught.exception.details["assetId"])
        # 资产确实已入库：不能当作“完全未写入”。
        self.assertTrue(getattr(caught.exception, "operation_may_have_completed", False))
        self.assertTrue(source.exists())

    def test_already_present_content_mismatch_is_rejected(self):
        source, plan = self._plan()
        wrong = hashlib.sha256(b"other-content").hexdigest()
        self.serve_ingest(http_status=200, status="already_present",
                          sha256=wrong, sourceKept=True)
        self.serve_asset()
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("CONTENT_MISMATCH", caught.exception.code)
        self.assertEqual(wrong, caught.exception.details["serverSha256"])
        self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(),
                         caught.exception.details["localSha256"])
        self.assertTrue(source.exists())

    def test_already_present_without_sha256_is_rejected(self):
        source, plan = self._plan()
        self.serve_ingest(http_status=200, status="already_present",
                          sha256=None, sourceKept=True)
        self.serve_asset()
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("READBACK_MISMATCH", caught.exception.code)

    def test_readback_failure_after_completed_keeps_asset_refs(self):
        first = self.source("第一张.png")
        second = self.source("第二张.png")
        plan, _diagnostics = self.prepare([first, second])
        size = first.stat().st_size
        StubHandler.routes[("POST", f"/api/v1/libraries/{self.library_id}/ingest-local")] = [
            (201, {"status": "ingested", "assetId": "asset-1",
                   "libraryId": self.library_id, "relPath": "第一张.png",
                   "fileName": "第一张.png", "mediaType": "image",
                   "sizeBytes": size, "sha256": None, "sourceKept": False}),
            (201, {"status": "ingested", "assetId": "asset-2",
                   "libraryId": self.library_id, "relPath": "第二张.png",
                   "fileName": "第二张.png", "mediaType": "image",
                   "sizeBytes": size, "sha256": None, "sourceKept": False}),
        ]
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = (
            200, {"id": "asset-1", "libraryId": self.library_id, "relPath": "第一张.png",
                  "fileName": "第一张.png", "mediaType": "image", "sizeBytes": size})
        StubHandler.routes[("GET", "/api/v1/assets/asset-2")] = (
            200, {"id": "asset-2", "libraryId": self.library_id, "relPath": "第二张.png",
                  "fileName": "第二张.png", "mediaType": "image", "sizeBytes": size + 1})
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("READBACK_MISMATCH", caught.exception.code)
        # 第 N 个文件回读失败时，前面已完成的清单（含 assetId）必须保留。
        self.assertEqual([{"assetId": "asset-1", "status": "ingested",
                           "relPath": "第一张.png"}],
                         caught.exception.details["completed"])

    def test_apply_multiple_files_with_directory(self):
        first = self.source("甲.png")
        second = self.source("乙.png")
        plan, _diagnostics = self.prepare([first, second], directory="合成目录")
        size = first.stat().st_size
        StubHandler.routes[("POST", f"/api/v1/libraries/{self.library_id}/ingest-local")] = [
            (201, {"status": "ingested", "assetId": "asset-a",
                   "libraryId": self.library_id, "relPath": "合成目录/甲.png",
                   "fileName": "甲.png", "mediaType": "image",
                   "sizeBytes": size, "sha256": None, "sourceKept": False}),
            (201, {"status": "ingested", "assetId": "asset-b",
                   "libraryId": self.library_id, "relPath": "合成目录/乙.png",
                   "fileName": "乙.png", "mediaType": "image",
                   "sizeBytes": size, "sha256": None, "sourceKept": False}),
        ]
        for asset_id, rel, name in (("asset-a", "合成目录/甲.png", "甲.png"),
                                    ("asset-b", "合成目录/乙.png", "乙.png")):
            StubHandler.routes[("GET", f"/api/v1/assets/{asset_id}")] = (
                200, {"id": asset_id, "libraryId": self.library_id, "relPath": rel,
                      "fileName": name, "mediaType": "image", "sizeBytes": size})
        _spec, result = self.call("asset.ingest.local", {"plan": plan, "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertEqual(2, result["counts"]["ingested"])
        bodies = [json.loads(item["body"].decode("utf-8")) for item in self.posts()]
        self.assertEqual({"sourcePath": str(first), "fileName": "甲.png",
                          "directory": "合成目录"}, bodies[0])


if __name__ == "__main__":
    unittest.main()
