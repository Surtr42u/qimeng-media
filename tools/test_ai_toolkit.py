"""R1-R3 工具核心回归：分块 OpenAPI、能力降级、注册表、计划与受控写入。

全部合成数据/临时目录/127.0.0.1 stub；不访问真实 8420 或外网。
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import shutil
import tempfile
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib import parse

from ai_maintenance import (cli, errors, execution, openapi, plans, registry, rulepack,
                           schema, tools_media)

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "api" / "openapi.yaml"
RULES = ROOT / "tools" / "ai_maintenance" / "rules.json"
TOKEN = "-".join(("synthetic", "toolkit", "credential"))

_TEMP_ROOT = ROOT / "tools" / "ai_maintenance" / ".test-temp"
try:
    _TEMP_ROOT.mkdir(parents=True, exist_ok=True)
except OSError:
    _TEMP_ROOT = Path(tempfile.gettempdir())


@contextlib.contextmanager
def temp_dir():
    path = _TEMP_ROOT / ("toolkit-" + uuid.uuid4().hex)
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
        type(self).seen.append({"method": method, "path": parsed.path,
                                "query": parse.parse_qs(parsed.query), "body": body,
                                "authorization": self.headers.get("Authorization")})
        route = type(self).routes.get((method, parsed.path), (404, {"code": "NOT_FOUND"}))
        if isinstance(route, list):
            status, payload = route.pop(0) if len(route) > 1 else route[0]
        else:
            status, payload = route
        raw = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *args):
        pass


def profile(author_id="a1", revision=0, **changes):
    body = {"authorId": author_id, "revision": revision, "nickname": "", "biography": "",
            "avatarUrl": "", "homepageUrl": "", "evidenceUrls": [], "status": "empty",
            "autoRefresh": False, "feedUrl": "", "trackUpdates": False,
            "candidate": {}, "updates": []}
    body.update(changes)
    return body


AUTHOR = {"id": "a1", "displayName": "Synthetic Author", "type": "regular", "fileCount": 0}


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

    def env(self):
        return {"QIMENG_API_BASE": self.base, "QIMENG_API_TOKEN": TOKEN}

    def config(self, **extra_env):
        env = dict(self.env(), **extra_env)
        return execution.load_config(env)

    def call(self, tool_id, payload, config=None):
        return execution.execute_tool(tool_id, payload, env=self.env(),
                                      config=config or self.config())


# --------------------------------------------------------------------------- #
# 分块 OpenAPI 解析与能力降级
# --------------------------------------------------------------------------- #

SYNTHETIC_OPENAPI = """openapi: 3.0.3
paths:
  /api/v1/authors/{authorId}/profile:
    parameters:
      - { name: authorId, in: path, required: true, schema: { type: string } }
    get:
      summary: read profile
      responses:
        "200": { description: OK }
    put:
      summary: write profile
      description: |
        这段描述里可能出现 /api/v1/fake/path: 与 requestBody: 字样，必须被跳过。
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/CreatorProfileWrite" }
      responses:
        "200": { description: OK }
  /api/v1/authors:
    get:
      summary: list authors
      parameters:
        - { name: limit, in: query, required: false, schema: { type: integer } }
      responses:
        "200": { description: OK }
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [displayName]
              properties:
                displayName: { type: string }
      responses:
        "200": { description: OK }
components:
  parameters:
    AuthorId: { name: authorId, in: path, required: true, schema: { type: string } }
  schemas:
    CreatorProfileWrite:
      type: object
      required: [revision, nickname]
      properties:
        revision: { type: integer }
        nickname: { type: string }
    Error:
      type: object
      required: [code, message]
      properties:
        code: { type: string }
        message: { type: string }
"""


class OpenApiParserTest(unittest.TestCase):
    def test_block_parser_locates_paths_methods_request_body_and_parameters(self):
        document = openapi.parse(SYNTHETIC_OPENAPI)
        self.assertEqual(["/api/v1/authors", "/api/v1/authors/{authorId}/profile"],
                         document.paths())
        operation = document.operation("/api/v1/authors/{authorId}/profile", "PUT")
        self.assertIsNotNone(operation)
        self.assertEqual("CreatorProfileWrite", operation.request_body.schema_ref)
        self.assertTrue(operation.request_body.required)
        parameters = {item.name for item in operation.parameters}
        self.assertIn("authorId", parameters)
        schema = document.schemas["CreatorProfileWrite"]
        self.assertEqual(("revision", "nickname"), schema.required)
        self.assertIn("nickname", schema.properties)
        inline = document.operation("/api/v1/authors", "POST").request_body
        self.assertEqual(("displayName",), inline.inline_required)

    def test_description_block_scalars_do_not_create_fake_paths(self):
        document = openapi.parse(SYNTHETIC_OPENAPI)
        self.assertNotIn("/api/v1/fake/path", document.operations)

    def test_requirement_check_reports_missing_method_schema_and_fields(self):
        document = openapi.parse(SYNTHETIC_OPENAPI)
        ok = openapi.ApiRequirement("/api/v1/authors/{authorId}/profile", "PUT",
                                    request_schema="CreatorProfileWrite",
                                    required_body_fields=("revision", "nickname"),
                                    body_required=True)
        self.assertEqual("supported", openapi.check_requirement(document, ok).status)
        missing_method = openapi.ApiRequirement("/api/v1/authors/{authorId}/profile", "DELETE")
        self.assertEqual("method_not_in_openapi",
                         openapi.check_requirement(document, missing_method).code)
        missing_path = openapi.ApiRequirement("/api/v1/does-not-exist", "GET")
        self.assertEqual("endpoint_not_in_openapi",
                         openapi.check_requirement(document, missing_path).code)
        missing_field = openapi.ApiRequirement("/api/v1/authors/{authorId}/profile", "PUT",
                                               request_schema="CreatorProfileWrite",
                                               required_body_fields=("avatarUrl",),
                                               body_required=True)
        self.assertEqual("required_field_missing",
                         openapi.check_requirement(document, missing_field).code)
        wrong_schema = openapi.ApiRequirement("/api/v1/authors/{authorId}/profile", "PUT",
                                              request_schema="Error",
                                              body_required=True)
        self.assertEqual("request_schema_mismatch",
                         openapi.check_requirement(document, wrong_schema).code)
        missing_schema = openapi.ApiRequirement("/api/v1/authors/{authorId}/profile", "PUT",
                                                request_schema="NoSuchSchema",
                                                body_required=True)
        self.assertEqual("request_schema_missing",
                         openapi.check_requirement(document, missing_schema).code)


class CapabilityTest(unittest.TestCase):
    def test_synthetic_document_only_degrades_tools_that_depend_on_missing_capability(self):
        document = openapi.parse(SYNTHETIC_OPENAPI)
        profile_update = registry.get("author.profile.update")
        library_query = registry.get("library.query")
        merge_tool = registry.get("author.merge")
        update_status = registry.tool_status(profile_update, document)
        self.assertIn(update_status["status"], ("unavailable", "incompatible"))
        self.assertEqual("unavailable", registry.tool_status(library_query, document)["status"])
        self.assertEqual("unavailable", registry.tool_status(merge_tool, document)["status"])

    def test_real_openapi_keeps_implemented_tools_supported(self):
        document, _raw = openapi.parse_file(OPENAPI)
        for tool_id in ("project.context", "health.check", "library.query", "asset.query",
                        "author.query", "author.profile.read", "author.profile.prepare",
                        "author.profile.update", "author.cleanup.prepare",
                        "author.cleanup.apply", "author.merge", "asset.authors.prepare",
                        "asset.authors.apply", "maintenance.inventory", "maintenance.status"):
            spec = registry.get(tool_id)
            self.assertIsNotNone(spec, tool_id)
            self.assertEqual("supported", registry.tool_status(spec, document)["status"], tool_id)

    def test_external_tool_platform_tools_are_supported(self):
        # 2026-11-08：备份导出/设备结果包/执行器/研究/回收站工具均已实现。
        document, _raw = openapi.parse_file(OPENAPI)
        for tool_id in ("backup.export", "device.apply.prepare", "device.apply.submit",
                        "device.apply.status", "maintenance.claim",
                        "maintenance.heartbeat", "maintenance.step.record",
                        "maintenance.artifact.record", "maintenance.complete",
                        "research.image.pipeline", "research.image.search",
                        "research.author.probe", "research.media.probe",
                        "research.avatar.fetch", "trash.list", "trash.restore",
                        "asset.trash", "asset.tags.replace"):
            self.assertEqual("supported", registry.tool_status(registry.get(tool_id), document)["status"],
                             tool_id)


class RulepackTest(unittest.TestCase):
    def test_version_separation_and_stability(self):
        with temp_dir() as directory:
            first = rulepack.build_rulepack()
            second = rulepack.build_rulepack()
        self.assertEqual(first["platformVersion"], "1.3.0")
        self.assertEqual(first["toolContractVersion"], "1.3.0")
        self.assertEqual(first["rulepackVersion"], "2026-11-10.2")
        self.assertNotEqual(first["platformVersion"], first["rulepackVersion"])
        self.assertEqual(rulepack.dumps_pack(first), rulepack.dumps_pack(second))
        self.assertEqual(len(first["tools"]), len(registry.TOOLS))

    def test_rule_source_version_drift_is_rejected(self):
        with temp_dir() as directory:
            rules = json.loads(RULES.read_text(encoding="utf-8"))
            rules["platform"]["toolContractVersion"] = "9.9.9"
            path = directory / "rules.json"
            path.write_text(json.dumps(rules, ensure_ascii=False), encoding="utf-8")
            with self.assertRaisesRegex(rulepack.RulepackError, "toolContractVersion"):
                rulepack.load_rules(path)

    def test_unrelated_openapi_bytes_change_keeps_tools_and_only_source_hash_moves(self):
        with temp_dir() as directory:
            path = directory / "openapi.yaml"
            path.write_bytes(OPENAPI.read_bytes())
            first = rulepack.build_rulepack(openapi_path=path)
            path.write_bytes(path.read_bytes() + b"\n# synthetic unrelated comment\n")
            second = rulepack.build_rulepack(openapi_path=path)
        self.assertNotEqual(first["openapiSha256"], second["openapiSha256"])
        first_status = {tool["id"]: tool["status"] for tool in first["tools"]}
        second_status = {tool["id"]: tool["status"] for tool in second["tools"]}
        self.assertEqual(first_status, second_status)
        self.assertTrue(all(status == "supported" for tool_id, status in second_status.items()
                            if not tool_id.startswith(("media.organize.", "backup.", "device."))))

    def test_changed_required_field_only_degrades_dependent_write_tools(self):
        with temp_dir() as directory:
            path = directory / "openapi.yaml"
            text = OPENAPI.read_text(encoding="utf-8")
            needle = ("required: [revision, nickname, biography, avatarUrl, homepageUrl, "
                      "evidenceUrls, status, autoRefresh, feedUrl, trackUpdates]")
            self.assertIn(needle, text)
            text = text.replace(needle, needle.replace("homepageUrl, ", ""), 1)
            path.write_text(text, encoding="utf-8")
            pack = rulepack.build_rulepack(openapi_path=path)
        statuses = {tool["id"]: tool["status"] for tool in pack["tools"]}
        self.assertEqual("incompatible", statuses["author.profile.update"])
        self.assertEqual("supported", statuses["author.profile.prepare"])
        self.assertEqual("supported", statuses["author.merge"])
        self.assertEqual("supported", statuses["asset.authors.apply"])
        update_tool = next(tool for tool in pack["tools"] if tool["id"] == "author.profile.update")
        self.assertTrue(any(check["code"] in ("required_field_missing", "required_field_not_in_properties")
                            for check in update_tool["checks"]))


# --------------------------------------------------------------------------- #
# 注册表 / Schema / 禁用
# --------------------------------------------------------------------------- #

class RegistryTest(StubServerTest):
    def test_listing_exposes_contract_and_schema(self):
        listing = execution.tool_listing(self.config())
        self.assertEqual("1.3.0", listing["platformVersion"])
        by_id = {tool["id"]: tool for tool in listing["tools"]}
        self.assertEqual(set(by_id), set(registry.tool_ids()))
        sample = by_id["author.profile.update"]
        for key in ("version", "status", "permission", "readOnly", "sideEffects",
                    "requiresConfirmation", "timeoutSeconds", "inputSchema",
                    "outputSchema", "errorCodes", "checks"):
            self.assertIn(key, sample)
        self.assertFalse(sample["readOnly"])
        self.assertTrue(sample["sideEffects"])
        self.assertTrue(sample["requiresConfirmation"])
        self.assertEqual("object", sample["inputSchema"]["type"])
        self.assertEqual("object", by_id["project.context"]["inputSchema"]["type"])

    def test_disable_by_environment_and_rules(self):
        config = self.config(QIMENG_AI_DISABLED_TOOLS="library.query")
        listing = execution.tool_listing(config)
        entry = next(tool for tool in listing["tools"] if tool["id"] == "library.query")
        self.assertFalse(entry["enabled"])
        self.assertIn("禁用", entry["disabledReason"])
        with self.assertRaises(errors.ToolError) as caught:
            self.call("library.query", {}, config=config)
        self.assertEqual("TOOL_DISABLED", caught.exception.code)
        with self.assertRaisesRegex(errors.ToolError, "未知工具"):
            execution.load_config(dict(self.env(), QIMENG_AI_DISABLED_TOOLS="no.such.tool"))

    def test_readonly_mode_blocks_side_effects_but_allows_reads(self):
        config = self.config(QIMENG_AI_READONLY="1")
        with self.assertRaises(errors.ToolError) as caught:
            self.call("author.profile.update", {"plan": {}, "confirm": True}, config=config)
        self.assertEqual("TOOL_DISABLED", caught.exception.code)
        StubHandler.routes[("GET", "/api/v1/healthz")] = (200, {"status": "alive"})
        _spec, result = execution.execute_tool("health.check", {}, env=self.env(), config=config)
        self.assertEqual({"status": "alive"}, result)

    def test_rules_file_disabled_list(self):
        with temp_dir() as directory:
            rules = json.loads(RULES.read_text(encoding="utf-8"))
            rules["toolConfig"]["disabled"] = ["author.merge"]
            path = directory / "rules.json"
            path.write_text(json.dumps(rules, ensure_ascii=False), encoding="utf-8")
            config = execution.load_config(dict(self.env(), QIMENG_AI_RULES=str(path)))
            listing = execution.tool_listing(config)
            entry = next(tool for tool in listing["tools"] if tool["id"] == "author.merge")
            self.assertFalse(entry["enabled"])

    def test_input_schema_rejects_wrong_type_and_extra_fields(self):
        with self.assertRaises(errors.ToolError) as caught:
            self.call("health.check", {"ready": "yes"})
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            self.call("health.check", {"unknown": 1})
        self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_unknown_and_invalid_and_unconfirmed_tools(self):
        with self.assertRaises(errors.ToolError) as caught:
            self.call("no.such.tool", {})
        self.assertEqual("UNKNOWN_TOOL", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            self.call("backup.export", {})
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            self.call("author.profile.update", {"plan": {}, "confirm": False})
        self.assertEqual("NOT_CONFIRMED", caught.exception.code)

    def test_schema_validator_supports_nested_arrays_and_enums(self):
        payload = {"items": [{"action": "delete"}], "mode": "x"}
        definition = {"type": "object", "additionalProperties": False,
                      "properties": {
                          "items": {"type": "array", "minItems": 1,
                                    "items": {"type": "object", "required": ["action"],
                                              "properties": {"action": {"type": "string",
                                                                        "enum": ["delete", "merge"]}}}},
                          "mode": {"type": "string", "enum": ["x", "y"]}}}
        self.assertEqual([], schema.validate(payload, definition))
        errors_list = schema.validate({"items": [{"action": "no"}], "mode": "z"}, definition)
        self.assertTrue(any("action" in str(item) for item in errors_list))


# --------------------------------------------------------------------------- #
# 计划结构与指纹
# --------------------------------------------------------------------------- #

class PlanTest(unittest.TestCase):
    def make(self, **overrides):
        arguments = dict(
            tool="author.profile.update", tool_version="1.0.0", kind="profile.update",
            target={"instance": "127.0.0.1:8420", "library": None, "objects": []},
            changes=[{"field": "nickname", "oldValue": "", "newValue": "x"}],
            impact={"changedFieldCount": 1}, evidence=[{"source": "api"}],
            versions_info={"rulepackVersion": "r1", "openapiSha256": "a" * 64},
            confirmation={"required": True, "risk": "medium"},
            recoverable_steps=["无写入"], expected_readback=["revision+1"])
        arguments.update(overrides)
        return plans.make_plan(**arguments)

    def test_fingerprint_and_idempotency_are_stable_and_change_with_content(self):
        first = self.make()
        second = self.make()
        self.assertEqual(first["planFingerprint"], second["planFingerprint"])
        self.assertEqual(first["idempotencyKey"], second["idempotencyKey"])
        changed = self.make(changes=[{"field": "nickname", "oldValue": "", "newValue": "y"}])
        self.assertNotEqual(first["planFingerprint"], changed["planFingerprint"])
        self.assertNotIn("generatedAt", first)

    def test_tampered_plan_is_rejected(self):
        plan = self.make()
        plans.verify_plan(plan, tool_id="author.profile.update", rulepack_version="r1")
        tampered = json.loads(json.dumps(plan))
        tampered["changes"][0]["newValue"] = "tampered"
        with self.assertRaises(errors.ToolError) as caught:
            plans.verify_plan(tampered, tool_id="author.profile.update", rulepack_version="r1")
        self.assertEqual("PLAN_FINGERPRINT_MISMATCH", caught.exception.code)

    def test_stale_rule_version_is_rejected(self):
        plan = self.make()
        with self.assertRaises(errors.ToolError) as caught:
            plans.verify_plan(plan, tool_id="author.profile.update", rulepack_version="r2")
        self.assertEqual("PLAN_VERSION_MISMATCH", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            plans.verify_plan(plan, tool_id="author.merge", rulepack_version="r1")
        self.assertEqual("PLAN_INVALID", caught.exception.code)


# --------------------------------------------------------------------------- #
# 作者档案 prepare / update
# --------------------------------------------------------------------------- #

class AuthorProfileFlowTest(StubServerTest):
    def routes_for(self, profile_sequence, put_status=200, put_response=None, authors=None):
        """profile_sequence：每次 GET profile 依次返回的档案；PUT 固定同一响应。"""
        StubHandler.routes[("GET", "/api/v1/authors")] = (200, authors or [AUTHOR])
        StubHandler.routes[("GET", "/api/v1/authors/a1/profile")] = [
            (200, item) for item in profile_sequence]
        if put_status == 200:
            response = put_response or {
                "authorId": "a1", "revision": profile_sequence[0]["revision"] + 1}
            StubHandler.routes[("PUT", "/api/v1/authors/a1/profile")] = (200, response)
        else:
            StubHandler.routes[("PUT", "/api/v1/authors/a1/profile")] = (
                put_status, {"code": "CONFLICT", "message": "revision changed"})

    def prepare(self, changes, **payload):
        _spec, result = self.call("author.profile.prepare",
                                  dict({"authorId": "a1", "changes": changes}, **payload))
        return result["plan"]

    def test_prepare_builds_plan_without_writing(self):
        current = profile()
        self.routes_for([current])
        plan = self.prepare({"nickname": "Synthetic New"})
        self.assertEqual("author.profile.update", plan["tool"])
        self.assertEqual("1.3.0", plan["versions"]["toolContractVersion"])
        self.assertEqual(plan["planFingerprint"], plans.compute_fingerprint(plan))
        self.assertEqual(["nickname"], [change["field"] for change in plan["changes"]])
        self.assertEqual(0, plan["preconditions"]["revision"])
        self.assertFalse([item for item in StubHandler.seen if item["method"] == "PUT"])

    def test_update_executes_confirmed_plan_and_reads_back(self):
        current = profile()
        readback = profile(revision=1, nickname="Synthetic New", biography="Bio")
        # prepare 读 1 次 profile；update 读 1 次；PUT 后回读 1 次。
        self.routes_for([current, current, readback])
        plan = self.prepare({"nickname": "Synthetic New", "biography": "Bio"})
        _spec, result = self.call("author.profile.update", {"plan": plan, "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertEqual(1, result["revision"])
        put = next(item for item in StubHandler.seen if item["method"] == "PUT")
        body = json.loads(put["body"])
        self.assertEqual(0, body["revision"])
        self.assertEqual("Synthetic New", body["nickname"])
        self.assertEqual("Bio", body["biography"])
        self.assertNotIn("candidate", body)

    def test_manual_field_is_protected_unless_explicitly_allowed(self):
        current = profile(nickname="Human Nickname")
        self.routes_for([current])
        plan = self.prepare({"nickname": "AI Nickname", "homepageUrl": "https://example.com/a"})
        self.assertEqual(["nickname"], [change["field"] for change in plan["protectedFields"]])
        self.assertEqual(["homepageUrl"], [change["field"] for change in plan["changes"]])
        self.assertTrue(plan["impact"]["writes"])
        self.routes_for([current])
        allowed = self.prepare({"nickname": "AI Nickname"}, allowProtectedOverwrite=True)
        self.assertEqual([], allowed["protectedFields"])
        self.assertEqual(["nickname"], [change["field"] for change in allowed["changes"]])

    def test_conflicting_revision_or_409_is_not_overwritten(self):
        current = profile(revision=0)
        self.routes_for([current])
        plan = self.prepare({"nickname": "Synthetic New"})
        changed = profile(revision=3, nickname="Human Edit")
        self.routes_for([changed])
        with self.assertRaises(errors.ToolError) as caught:
            self.call("author.profile.update", {"plan": plan, "confirm": True})
        self.assertEqual("CONFLICT", caught.exception.code)
        self.assertFalse([item for item in StubHandler.seen if item["method"] == "PUT"])
        # 服务端 409 原样传播，不静默重试
        self.routes_for([current])
        plan = self.prepare({"nickname": "Synthetic New"})
        self.routes_for([current], put_status=409)
        with self.assertRaises(errors.ToolError) as caught:
            self.call("author.profile.update", {"plan": plan, "confirm": True})
        self.assertEqual("CONFLICT", caught.exception.code)
        self.assertEqual(1, len([item for item in StubHandler.seen if item["method"] == "PUT"]))

    def test_replay_of_already_applied_plan_is_detected_without_second_write(self):
        current = profile()
        self.routes_for([current])
        plan = self.prepare({"nickname": "Synthetic New"})
        applied = profile(revision=1, nickname="Synthetic New")
        self.routes_for([applied])
        _spec, result = self.call("author.profile.update", {"plan": plan, "confirm": True})
        self.assertEqual("already_applied", result["status"])
        self.assertFalse([item for item in StubHandler.seen if item["method"] == "PUT"])

    def test_invalid_profile_fields_are_rejected_before_network_write(self):
        for changes in ({"nickname": "x" * 201}, {"status": "verified"},
                        {"evidenceUrls": ["http://insecure.example/x"]},
                        {"homepageUrl": "https://example.com/ok", "status": "verified"},
                        {"xProfile": {"url": "https://x.com/search", "biography": "b"}}):
            self.routes_for([profile()])
            with self.assertRaises(errors.ToolError) as caught:
                self.prepare(changes)
            self.assertEqual("INVALID_INPUT", caught.exception.code)


# --------------------------------------------------------------------------- #
# 作者清理 prepare / apply
# --------------------------------------------------------------------------- #

class CleanupFlowTest(StubServerTest):
    ACTIVE = {"id": "a2", "displayName": "Active", "type": "regular", "fileCount": 3,
              "txtOrder": 2}
    ORPHAN = {"id": "a1", "displayName": "Orphan", "type": "regular", "fileCount": 0}
    COS = {"id": "cos_x", "displayName": "Cos", "type": "cos", "fileCount": 0}

    def routes(self):
        StubHandler.routes[("GET", "/api/v1/authors")] = (200, [self.ACTIVE, self.ORPHAN, self.COS])
        StubHandler.routes[("GET", "/api/v1/authors/a1/profile")] = (200, profile("a1"))
        StubHandler.routes[("GET", "/api/v1/authors/a1/sources")] = (200, {"sources": []})
        StubHandler.routes[("GET", "/api/v1/authors/a2/profile")] = (
            200, profile("a2", nickname="Active"))
        StubHandler.routes[("GET", "/api/v1/authors/a2/sources")] = (200, {"sources": ["站A"]})

    def test_scan_is_read_only_multi_signal_and_never_deletes(self):
        self.routes()
        _spec, result = self.call("author.cleanup.prepare", {"scan": {"maxAuthors": 5}})
        self.assertIsNone(result["plan"])
        by_id = {candidate["author"]["id"]: candidate for candidate in result["candidates"]}
        self.assertEqual(["zeroAssociations", "emptyProfile", "noSources", "notInTxt"],
                         [name for name, value in by_id["a1"]["signals"].items() if value])
        self.assertEqual("delete_candidate", by_id["a1"]["suggestedAction"])
        # a2 只有 2 个信号，不得给删除建议
        self.assertNotEqual("delete_candidate", by_id["a2"]["suggestedAction"])
        self.assertFalse(by_id["a2"]["signals"]["noSources"])
        self.assertFalse([item for item in StubHandler.seen if item["method"] in ("PUT", "DELETE", "POST")])
        self.assertTrue(any("零关联" in note for note in result["diagnostics"]["notices"]))

    def test_actions_plan_has_fingerprint_and_apply_deletes_with_readback(self):
        self.routes()
        _spec, prepared = self.call("author.cleanup.prepare",
                                    {"actions": [{"action": "delete", "authorId": "a1"}]})
        plan = prepared["plan"]
        self.assertEqual("high", plan["confirmation"]["risk"])
        self.assertEqual(1, plan["impact"]["deleteAuthors"])
        StubHandler.routes[("GET", "/api/v1/authors")] = [
            (200, [self.ACTIVE, self.ORPHAN, self.COS]),
            (200, [self.ACTIVE, self.COS]),
        ]
        StubHandler.routes[("DELETE", "/api/v1/authors/a1")] = (204, {})
        _spec, result = self.call("author.cleanup.apply", {"plan": plan, "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertEqual("applied", result["executedActions"][0]["status"])
        self.assertFalse(result["readback"]["authors"][0]["present"])

    def test_stale_snapshot_blocks_apply(self):
        self.routes()
        _spec, prepared = self.call("author.cleanup.prepare",
                                    {"actions": [{"action": "delete", "authorId": "a1"}]})
        changed = dict(self.ORPHAN, fileCount=5)
        StubHandler.routes[("GET", "/api/v1/authors")] = (200, [self.ACTIVE, changed, self.COS])
        with self.assertRaises(errors.ToolError) as caught:
            self.call("author.cleanup.apply", {"plan": prepared["plan"], "confirm": True})
        self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
        self.assertFalse([item for item in StubHandler.seen if item["method"] == "DELETE"])


# --------------------------------------------------------------------------- #
# 原子合并
# --------------------------------------------------------------------------- #

class MergeFlowTest(StubServerTest):
    SOURCE = {"id": "s1", "displayName": "Dup", "type": "regular", "fileCount": 1,
              "followed": True}
    TARGET = {"id": "t1", "displayName": "Keep", "type": "regular", "fileCount": 2}

    def routes(self):
        StubHandler.routes[("GET", "/api/v1/authors")] = [
            (200, [self.SOURCE, self.TARGET]),
            (200, [self.SOURCE, self.TARGET]),
            (200, [self.TARGET]),
        ]
        StubHandler.routes[("GET", "/api/v1/authors/s1/profile")] = (200, profile("s1", nickname="Dup"))
        StubHandler.routes[("GET", "/api/v1/authors/t1/profile")] = (200, profile("t1", nickname="Keep"))
        StubHandler.routes[("GET", "/api/v1/authors/s1/sources")] = (200, {"sources": ["A"]})
        StubHandler.routes[("GET", "/api/v1/authors/t1/sources")] = (200, {"sources": ["B"]})
        StubHandler.routes[("POST", "/api/v1/authors/merge")] = (
            200, {"sourceAuthorId": "s1", "targetAuthorId": "t1", "deletedSource": True,
                  "assetsMoved": 1, "associationsDeduped": 0})

    def test_dry_run_returns_high_risk_plan_without_writing(self):
        self.routes()
        _spec, result = self.call("author.merge", {"sourceAuthorId": "s1", "targetAuthorId": "t1",
                                                   "confirm": False})
        plan = result["plan"]
        self.assertEqual("high", plan["confirmation"]["risk"])
        self.assertIn("不可撤销", plan["confirmation"]["reason"])
        self.assertTrue(plan["impact"]["irreversible"])
        self.assertFalse([item for item in StubHandler.seen if item["method"] == "POST"])

    def test_confirm_requires_matching_fingerprint_then_executes_and_reads_back(self):
        self.routes()
        _spec, dry = self.call("author.merge", {"sourceAuthorId": "s1", "targetAuthorId": "t1",
                                                "confirm": False})
        fingerprint = dry["plan"]["planFingerprint"]
        with self.assertRaises(errors.ToolError) as caught:
            self.call("author.merge", {"sourceAuthorId": "s1", "targetAuthorId": "t1",
                                       "confirm": True, "planFingerprint": "wrong"})
        self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
        self.routes()
        _spec, result = self.call("author.merge", {"sourceAuthorId": "s1", "targetAuthorId": "t1",
                                                   "confirm": True,
                                                   "planFingerprint": fingerprint})
        self.assertEqual("applied", result["status"])
        self.assertEqual("absent", result["readback"]["source"])
        self.assertEqual(1, len([item for item in StubHandler.seen if item["method"] == "POST"]))

    def test_cos_and_same_identity_are_rejected(self):
        StubHandler.routes[("GET", "/api/v1/authors")] = (
            200, [{"id": "cos_1", "displayName": "COS", "type": "cos", "fileCount": 0},
                  self.TARGET])
        with self.assertRaises(errors.ToolError) as caught:
            self.call("author.merge", {"sourceAuthorId": "cos_1", "targetAuthorId": "t1",
                                       "confirm": False})
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        with self.assertRaises(errors.ToolError):
            self.call("author.merge", {"sourceAuthorId": "t1", "targetAuthorId": "t1",
                                       "confirm": False})


# --------------------------------------------------------------------------- #
# 资产作者关联
# --------------------------------------------------------------------------- #

class AssetAuthorsFlowTest(StubServerTest):
    ASSET = {"id": "asset-1", "fileName": "a.png", "libraryId": "lib-1",
             "relPath": "dir/a.png", "authors": [{"id": "a1", "displayName": "One"}]}
    AUTHOR = {"id": "a1", "displayName": "One", "type": "regular", "fileCount": 1}
    AUTHOR2 = {"id": "a2", "displayName": "Two", "type": "regular", "fileCount": 0}

    def routes(self, author_attach=True, readback=None):
        readback = readback or dict(self.ASSET, authors=[
            {"id": "a1", "displayName": "One"}, {"id": "a2", "displayName": "Two"}])
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = [
            (200, self.ASSET), (200, self.ASSET), (200, readback)]
        StubHandler.routes[("GET", "/api/v1/authors")] = (200, [self.AUTHOR, self.AUTHOR2])
        StubHandler.routes[("GET", "/api/v1/libraries")] = (
            200, [{"id": "lib-1", "name": "合成库",
                   "capabilities": {"authorAttach": author_attach}}])
        StubHandler.routes[("PUT", "/api/v1/assets/asset-1/authors")] = (200, readback)

    def test_prepare_and_apply_replaces_authors_with_readback(self):
        self.routes()
        _spec, prepared = self.call("asset.authors.prepare",
                                    {"assetId": "asset-1", "authorIds": ["a1", "a2"]})
        plan = prepared["plan"]
        self.assertEqual(1, plan["impact"]["associationsAdded"])
        self.assertEqual(0, plan["impact"]["associationsRemoved"])
        self.assertTrue(plan["target"]["library"]["id"] == "lib-1")
        _spec, result = self.call("asset.authors.apply", {"plan": plan, "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertEqual(["a1", "a2"], result["authorIds"])
        put = next(item for item in StubHandler.seen if item["method"] == "PUT")
        self.assertEqual({"authorIds": ["a1", "a2"]}, json.loads(put["body"]))

    def test_library_without_author_attach_is_unavailable(self):
        self.routes(author_attach=False)
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.authors.prepare", {"assetId": "asset-1", "authorIds": []})
        self.assertEqual("CAPABILITY_UNAVAILABLE", caught.exception.code)

    def test_cos_author_is_rejected(self):
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = [
            (200, self.ASSET), (200, self.ASSET), (200, self.ASSET)]
        StubHandler.routes[("GET", "/api/v1/authors")] = (
            200, [self.AUTHOR, {"id": "cos_1", "displayName": "COS", "type": "cos"}])
        StubHandler.routes[("GET", "/api/v1/libraries")] = (
            200, [{"id": "lib-1", "name": "库", "capabilities": {"authorAttach": True}}])
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.authors.prepare", {"assetId": "asset-1", "authorIds": ["cos_1"]})
        self.assertEqual("INVALID_INPUT", caught.exception.code)


# --------------------------------------------------------------------------- #
# CLI：version / tools / tool call / 脱敏
# --------------------------------------------------------------------------- #

class CliToolTest(StubServerTest):
    def run_cli(self, argv, env=None):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, env if env is not None else self.env(), clear=True), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = cli.main(argv)
        return status, stdout.getvalue(), stderr.getvalue()

    def test_version_and_tools_list_are_machine_readable(self):
        status, stdout, stderr = self.run_cli(["version"])
        self.assertEqual(0, status)
        payload = json.loads(stdout)
        self.assertEqual("1.3.0", payload["platformVersion"])
        self.assertEqual("2026-11-10.2", payload["rulepackVersion"])
        self.assertIn("sha256", payload["openapi"])
        status, stdout, stderr = self.run_cli(["tools", "list", "--json"])
        self.assertEqual(0, status)
        listing = json.loads(stdout)
        self.assertEqual(len(registry.TOOLS), len(listing["tools"]))
        self.assertIn("inputSchema", listing["tools"][0])
        self.assertNotIn(TOKEN, stdout + stderr)

    def test_tools_show_and_unknown_tool(self):
        status, stdout, _stderr = self.run_cli(["tools", "show", "author.merge"])
        self.assertEqual(0, status)
        payload = json.loads(stdout)
        self.assertEqual("author.merge", payload["id"])
        status, stdout, stderr = self.run_cli(["tools", "show", "no.such.tool"])
        self.assertEqual(2, status)
        self.assertEqual("UNKNOWN_TOOL", json.loads(stderr)["error"])

    def test_tool_call_reads_input_and_returns_envelope(self):
        StubHandler.routes[("GET", "/api/v1/healthz")] = (200, {"status": "alive"})
        status, stdout, stderr = self.run_cli(
            ["tool", "call", "health.check", "--input-json", '{"ready": false}'])
        self.assertEqual(0, status)
        payload = json.loads(stdout)
        self.assertEqual("health.check", payload["tool"])
        self.assertEqual("ok", payload["status"])
        self.assertEqual({"status": "alive"}, payload["result"])
        self.assertNotIn(TOKEN, stdout + stderr)

    def test_tool_call_input_file_and_invalid_payload(self):
        with temp_dir() as directory:
            path = directory / "input.json"
            path.write_text(json.dumps({"ready": False}), encoding="utf-8")
            StubHandler.routes[("GET", "/api/v1/healthz")] = (200, {"status": "alive"})
            status, stdout, stderr = self.run_cli(
                ["tool", "call", "health.check", "--input-file", str(path)])
            self.assertEqual(0, status)
            self.assertEqual("ok", json.loads(stdout)["status"])
            path.write_text("not json", encoding="utf-8")
            status, stdout, stderr = self.run_cli(
                ["tool", "call", "health.check", "--input-file", str(path)])
            self.assertEqual(2, status)
            self.assertEqual("usage_error", json.loads(stderr)["error"])

    def test_tool_call_reports_invalid_and_disabled(self):
        status, stdout, stderr = self.run_cli(["tool", "call", "backup.export", "--input-json", "{}"])
        self.assertEqual(2, status)
        self.assertEqual("INVALID_INPUT", json.loads(stderr)["error"])
        env = dict(self.env(), QIMENG_AI_DISABLED_TOOLS="health.check")
        status, stdout, stderr = self.run_cli(
            ["tool", "call", "health.check", "--input-json", "{}"], env=env)
        self.assertEqual(2, status)
        self.assertEqual("TOOL_DISABLED", json.loads(stderr)["error"])

    def test_http_error_redacts_token_in_tool_output(self):
        StubHandler.routes[("GET", "/api/v1/healthz")] = (
            500, {"code": "INTERNAL", "message": "echoed " + TOKEN})
        status, stdout, stderr = self.run_cli(
            ["tool", "call", "health.check", "--input-json", "{}"])
        self.assertEqual(1, status)
        self.assertNotIn(TOKEN, stdout + stderr)
        self.assertIn("<redacted>", stderr)


# --------------------------------------------------------------------------- #
# 组图命名集成（media_naming 引擎已安装）
# --------------------------------------------------------------------------- #

MEDIA_FAMILY = "守望先锋 朱诺"
MEDIA_EXISTING = [f"{MEDIA_FAMILY} {number}.png" for number in range(1, 11)]
MEDIA_RAW = ["browser_download_7f3a.png", "browser_download_7f3b.png",
             "browser_download_7f3c.png", "browser_download_7f3d.png"]


class MediaOrganizeTest(StubServerTest):
    FAMILY = MEDIA_FAMILY
    EXISTING = MEDIA_EXISTING
    RAW = MEDIA_RAW
    ASSET_IDS = {name: f"asset-{index}" for index, name in enumerate(RAW, start=1)}

    def page(self, names):
        items = []
        for name in names:
            items.append({"id": self.ASSET_IDS.get(name, "asset-" + name.replace(" ", "-")),
                          "fileName": name, "relPath": name, "libraryId": "lib-1"})
        return {"items": items, "nextCursor": None}

    def groups(self):
        return [{
            "family": self.FAMILY,
            "files": [{"name": name} for name in self.RAW],
            "confidence": 1.0,
            "confirmed": True,
            "evidence": ["同一下载批次 + 同目录"],
        }]

    def routes(self, pages):
        StubHandler.routes[("GET", "/api/v1/assets")] = [(200, page) for page in pages]
        StubHandler.routes[("GET", "/api/v1/libraries")] = (
            200, [{"id": "lib-1", "name": "合成库", "capabilities": {"authorAttach": True}}])
        for asset_id in self.ASSET_IDS.values():
            StubHandler.routes[("POST", f"/api/v1/assets/{asset_id}/move")] = (200, {})

    def prepare(self):
        _spec, result = self.call("media.organize.prepare",
                                  {"libraryId": "lib-1", "groups": self.groups()})
        return result

    def test_prepare_assigns_one_work_number_for_a_whole_group(self):
        self.routes([self.page(self.EXISTING + self.RAW)])
        prepared = self.prepare()
        plan = prepared["plan"]
        self.assertEqual("ready", plan["status"])
        self.assertEqual([11], plan["impact"]["workNumbers"])
        self.assertEqual(4, plan["impact"]["groupedFiles"])
        targets = [page["targetName"] for group in plan["groups"] for page in group["pages"]]
        self.assertEqual([
            f"{self.FAMILY} 11 (1).png",
            f"{self.FAMILY} 11 (2).png",
            f"{self.FAMILY} 11 (3).png",
            f"{self.FAMILY} 11 (4).png",
        ], targets)
        self.assertTrue(any("11" in line for line in prepared["diagnostics"]["preview"]))
        # prepare 只读：没有移动/写入请求
        self.assertFalse([item for item in StubHandler.seen
                          if item["method"] in ("POST", "PUT", "DELETE")])

    def test_apply_moves_with_lock_and_verifies_readback(self):
        after = self.page(self.EXISTING + [
            f"{self.FAMILY} 11 (1).png", f"{self.FAMILY} 11 (2).png",
            f"{self.FAMILY} 11 (3).png", f"{self.FAMILY} 11 (4).png"])
        self.routes([self.page(self.EXISTING + self.RAW),
                     self.page(self.EXISTING + self.RAW), after])
        plan = self.prepare()["plan"]
        with temp_dir() as lock_parent:
            lock_root = lock_parent / "locks"
            with patch.object(tools_media, "_resolve_lock_dir",
                              return_value=str(lock_root)):
                _spec, result = self.call("media.organize.apply",
                                          {"plan": plan, "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertEqual(4, result["stepCount"])
        self.assertTrue(all(step["status"] == "committed"
                            for step in result["executedSteps"]))
        self.assertTrue(result["readback"]["ok"])
        moves = [item for item in StubHandler.seen if item["method"] == "POST"]
        self.assertEqual(4, len(moves))
        bodies = [json.loads(item["body"]) for item in moves]
        self.assertEqual(sorted(f"{self.FAMILY} 11 ({index}).png" for index in range(1, 5)),
                         sorted(body["newName"] for body in bodies))
        self.assertFalse([item for item in moves if not item["path"].endswith("/move")])

    def test_apply_rejects_stale_plan_after_directory_changes(self):
        after_change = self.page(self.EXISTING + self.RAW + ["new_unrelated.png"])
        self.routes([self.page(self.EXISTING + self.RAW), after_change])
        plan = self.prepare()["plan"]
        with temp_dir() as lock_parent, patch.object(
                tools_media, "_resolve_lock_dir", return_value=str(lock_parent / "locks")):
            with self.assertRaises(errors.ToolError) as caught:
                self.call("media.organize.apply", {"plan": plan, "confirm": True})
        self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
        self.assertFalse([item for item in StubHandler.seen if item["method"] == "POST"])

    def test_tampered_plan_is_rejected(self):
        self.routes([self.page(self.EXISTING + self.RAW)])
        plan = self.prepare()["plan"]
        tampered = json.loads(json.dumps(plan))
        tampered["changes"][0]["targetName"] = "被篡改.png"
        with temp_dir() as lock_parent, patch.object(
                tools_media, "_resolve_lock_dir", return_value=str(lock_parent / "locks")):
            with self.assertRaises(errors.ToolError) as caught:
                self.call("media.organize.apply", {"plan": tampered, "confirm": True})
        self.assertEqual("PLAN_FINGERPRINT_MISMATCH", caught.exception.code)
        self.assertFalse([item for item in StubHandler.seen if item["method"] == "POST"])

    def test_engine_missing_keeps_tools_planned(self):
        # 惰性加载后引擎存在性由 registry 的 find_spec 探测；mock 探测函数即可模拟未安装。
        with patch.object(registry, "_engine_available", return_value=False), \
                patch.object(registry, "_engine_version", return_value=""):
            document, _raw = openapi.parse_file(OPENAPI)
            for tool_id in ("media.organize.prepare", "media.organize.apply"):
                self.assertEqual("planned",
                                 registry.tool_status(registry.get(tool_id), document)["status"])
            with self.assertRaises(errors.ToolError) as caught:
                self.call("media.organize.prepare", {"libraryId": "lib-1", "groups": []})
            self.assertEqual("TOOL_NOT_IMPLEMENTED", caught.exception.code)

    def test_engine_installed_marks_tools_supported_with_engine_version(self):
        document, _raw = openapi.parse_file(OPENAPI)
        for tool_id in ("media.organize.prepare", "media.organize.apply"):
            summary = registry.tool_summary(registry.get(tool_id), document)
            self.assertEqual("supported", summary["status"])
            self.assertEqual("1.0.0", summary["implementationVersion"])