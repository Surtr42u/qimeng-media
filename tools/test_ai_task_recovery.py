"""S3 定向回归：任务状态查询、未决写入核销与预算续期。

全部使用合成数据、临时目录与 127.0.0.1 stub；不访问真实 8420、不联网、不执行
任何媒体写入。测试覆盖 identity 白名单/有界、只读状态查询、按 requestHash 精确
核销、跨任务/凭据隔离、并发幂等、renew 保留计数、授权过期与只读回读分支。
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import shutil
import tempfile
import threading
import time
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib import parse

from ai_maintenance import cli, errors, execution, model_output, plans, task_admin, task_controls

ROOT = Path(__file__).resolve().parents[1]
TOKEN = "-".join(("synthetic", "task", "recovery", "credential"))
OTHER_TOKEN = "-".join(("synthetic", "task", "other", "credential"))
UPLOAD_ID = "00000000-0000-4000-8000-000000000001"
SOURCE_MTIME = 1700000000000
SOURCE_ISO = "2023-11-14T22:13:20Z"

_TEMP_ROOT = ROOT / "tools" / "ai_maintenance" / ".test-temp"
try:
    _TEMP_ROOT.mkdir(parents=True, exist_ok=True)
except OSError:
    _TEMP_ROOT = Path(tempfile.gettempdir())


@contextlib.contextmanager
def temp_dir():
    path = _TEMP_ROOT / ("recovery-" + uuid.uuid4().hex)
    os.makedirs(path)
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


def task_env(directory, *, task_id="task-synthetic", token=TOKEN, base="http://127.0.0.1:1",
             **extra):
    env = {"QIMENG_AI_TASK_ID": task_id, "QIMENG_AI_STATE_DIR": str(directory)}
    if token:
        env["QIMENG_API_TOKEN"] = token
    if base:
        env["QIMENG_API_BASE"] = base
    env.update(extra)
    return env


def upload_plan():
    return {
        "planFingerprint": "a" * 64,
        "operationId": UPLOAD_ID,
        "target": {"instance": "127.0.0.1:1", "library": {"id": "lib-synthetic"}, "objects": []},
        "changes": [{"fileName": "synthetic.jpg", "directory": "dir", "bytes": 12}],
        "sourceMtime": SOURCE_MTIME,
        # 敏感定位原始信息只用于测试不会被写入 identity/状态文件。
        "source": {"path": "C:\\private\\synthetic.jpg", "fileRef": "file-ref-secret"},
    }


def upload_identity():
    return {
        "uploadId": UPLOAD_ID,
        "libraryId": "lib-synthetic",
        "directory": "dir",
        "fileName": "synthetic.jpg",
        "bytes": 12,
        "sourceMtime": SOURCE_MTIME,
        # 上传/入库时间校验：测试资产 addedAt=SOURCE_ISO，操作开始时间取同一秒。
        "operationStartedAt": SOURCE_MTIME / 1000,
    }


class IdentityAndReportTests(unittest.TestCase):
    def test_identity_is_bounded_whitelist_without_path_or_token(self):
        with temp_dir() as directory:
            env = task_env(directory)
            budget = task_controls.SessionBudget(env)
            key = budget.reserve("asset.upload", {"plan": upload_plan(), "confirm": True},
                                 side_effects=True)
            entry = budget.pending_entry(key)
            identity = entry["identity"]
            rendered = json.dumps(entry, ensure_ascii=False)
            self.assertNotIn("fileRef", rendered)
            self.assertNotIn("private", rendered)
            self.assertNotIn("file-ref-secret", rendered)
            self.assertNotIn(TOKEN, rendered)
            self.assertEqual(UPLOAD_ID, identity["uploadId"])
            self.assertEqual("lib-synthetic", identity["libraryId"])
            self.assertEqual("synthetic.jpg", identity["fileName"])
            self.assertEqual(12, identity["bytes"])
            self.assertLessEqual(len(json.dumps(identity, separators=(",", ":")).encode()),
                                 task_controls.IDENTITY_MAX_BYTES)
            raw_state = budget.path.read_text(encoding="utf-8")
            self.assertNotIn(TOKEN, raw_state)
            self.assertNotIn("C:\\\\private", raw_state)
            self.assertNotIn("file-ref-secret", raw_state)

    def test_oversized_identity_is_truncated_and_marked(self):
        with temp_dir() as directory:
            env = task_env(directory, base="")
            budget = task_controls.SessionBudget(env)
            tag_ids = ["tag-%03d-%s" % (index, "x" * 300) for index in range(100)]
            key = budget.reserve("asset.tags.replace",
                                 {"assetId": "asset-1", "tagIds": tag_ids, "confirm": True},
                                 side_effects=True)
            identity = budget.pending_entry(key)["identity"]
            self.assertTrue(identity.get("truncated"))
            self.assertLessEqual(len(json.dumps(identity, separators=(",", ":")).encode()),
                                 task_controls.IDENTITY_MAX_BYTES)

    def test_report_is_read_only_and_structured(self):
        with temp_dir() as directory:
            env = task_env(directory)
            budget = task_controls.SessionBudget(env)
            read_key = budget.reserve("asset.query", {"limit": 10})
            budget.complete(read_key, {"items": []})
            budget.reserve("asset.upload", {"plan": upload_plan(), "confirm": True},
                           side_effects=True)
            before = budget.path.read_bytes()
            report = task_controls.SessionBudget(env).report()
            after = budget.path.read_bytes()
            self.assertEqual(before, after)  # 查询不重写/不重置状态
            for key in ("startedAt", "startedAtIso", "deadline", "expiresAt",
                        "elapsedSeconds", "calls", "pending", "scope", "modelUsage"):
                self.assertIn(key, report)
            self.assertEqual("task", report["scope"])
            self.assertEqual(1, report["pendingWrites"])
            self.assertEqual(1, len(report["pending"]))
            pending = report["pending"][0]
            self.assertEqual(UPLOAD_ID, pending["identity"]["uploadId"])
            self.assertIn("createdAt", pending)
            self.assertEqual(64, len(pending["requestHash"]))
            rendered = json.dumps(report, ensure_ascii=False)
            self.assertNotIn(TOKEN, rendered)
            self.assertNotIn(str(directory), rendered)

    def test_model_safe_report_hides_media_names_but_keeps_locators(self):
        with temp_dir() as directory:
            env = task_env(directory)
            budget = task_controls.SessionBudget(env)
            budget.reserve("asset.upload", {"plan": upload_plan(), "confirm": True},
                           side_effects=True)
            safe = budget.report(include_identity=False)
            identity = safe["pending"][0]["identity"]
            self.assertEqual(UPLOAD_ID, identity["uploadId"])
            self.assertNotIn("fileName", identity)
            self.assertNotIn("directory", identity)
            self.assertNotIn("synthetic.jpg", json.dumps(safe, ensure_ascii=False))
            full = budget.report()
            self.assertEqual("synthetic.jpg", full["pending"][0]["identity"]["fileName"])

    def test_plan_ref_is_resolved_before_pending_reserve(self):
        with temp_dir() as directory:
            env = task_env(directory, base="http://127.0.0.1:18568")
            env["QIMENG_AI_GRANTED_PERMISSIONS"] = "all"
            config = execution.load_config(env)
            plan = {
                "planFingerprint": "c" * 64,
                "tool": "asset.upload",
                "kind": "asset.upload",
                "operationId": UPLOAD_ID,
                "target": {"instance": "127.0.0.1:18568",
                           "library": {"id": "lib-synthetic"}, "objects": []},
                "changes": [{"fileName": "synthetic.jpg", "directory": "dir", "bytes": 12}],
                "sourceMtime": SOURCE_MTIME,
            }

            def interrupted(_tool_id, _payload, *, env, config, skip_capability,
                            mark_started):
                mark_started()
                raise RuntimeError("synthetic disconnect after reservation")

            with patch.object(model_output, "resolve_plan_ref", return_value=plan), \
                    patch.object(execution, "_execute_tool", side_effect=interrupted):
                with self.assertRaises(RuntimeError):
                    execution.execute_tool(
                        "asset.upload",
                        {"planRef": "p1_0123456789abcdef0123456789abcdef",
                         "confirm": True},
                        env=env, config=config)
            pending = config.budget.report()["pending"]
            self.assertEqual(1, len(pending))
            self.assertEqual(UPLOAD_ID, pending[0]["identity"]["uploadId"])
            self.assertIn("operationStartedAt", pending[0]["identity"])
            self.assertEqual(1, config.budget.report()["pendingWrites"])

    def test_research_local_side_effect_does_not_create_business_pending(self):
        with temp_dir() as directory:
            env = task_env(directory, base="http://127.0.0.1:18568")
            env["QIMENG_AI_GRANTED_PERMISSIONS"] = "research"
            config = execution.load_config(env)

            def interrupted(_tool_id, _payload, *, env, config, skip_capability,
                            mark_started):
                mark_started()
                raise RuntimeError("synthetic local research failure")

            with patch.object(execution, "_execute_tool", side_effect=interrupted):
                with self.assertRaises(RuntimeError):
                    execution.execute_tool(
                        "research.work",
                        {"path": "/synthetic/research-input.png"},
                        env=env, config=config)
            report = config.budget.report()
            self.assertEqual(0, report["pendingWrites"])
            self.assertEqual(1, report["calls"])
            self.assertEqual([], report["pending"])

    def test_plan_preconditions_store_previous_sets(self):
        plan = {
            "planFingerprint": "b" * 64,
            "target": {"instance": "127.0.0.1:1", "objects": [
                {"type": "asset", "id": "asset-1"}]},
            "changes": [{"field": "tags.add", "oldValue": ["t0"], "newValue": ["t1"]}],
            "preconditions": {"currentTagIds": ["t0"], "currentAuthorIds": ["a0"]},
        }
        identity = task_controls._extract_identity("asset.tags.replace", {"plan": plan})
        self.assertEqual(["t0"], identity["previousTagIds"])
        self.assertEqual(["a0"], identity["previousAuthorIds"])
        rendered = json.dumps(identity, ensure_ascii=False)
        self.assertNotIn("path", rendered)
        self.assertNotIn(TOKEN, rendered)

    def test_legacy_pending_is_accepted_but_large_identity_fails_closed(self):
        with temp_dir() as directory:
            env = task_env(directory, base="")
            budget = task_controls.SessionBudget(env)
            budget.path.parent.mkdir(parents=True, exist_ok=True)
            legacy = {"startedAt": time.time() - 5, "calls": 1, "observations": {},
                      "pending": {"b" * 64: {"count": 1, "sideEffects": True}}}
            budget.path.write_text(json.dumps(legacy), encoding="utf-8")
            report = task_controls.SessionBudget(env).report()
            self.assertEqual("legacy", report["pending"][0]["tool"])
            self.assertEqual(1, report["pendingWrites"])

            oversized = {"startedAt": time.time(), "calls": 0, "observations": {},
                         "pending": {"c" * 64: {"count": 1, "sideEffects": True,
                                                "identity": {"value": "x" * 5000}}}}
            budget.path.write_text(json.dumps(oversized), encoding="utf-8")
            with self.assertRaises(errors.ToolError) as caught:
                task_controls.SessionBudget(env).report()
            self.assertEqual("TASK_BUDGET_UNAVAILABLE", caught.exception.code)

            budget.path.write_text("{not-json", encoding="utf-8")
            with self.assertRaises(errors.ToolError) as caught:
                task_controls.SessionBudget(env).report()
            self.assertEqual("TASK_BUDGET_UNAVAILABLE", caught.exception.code)


class ReconcileTests(unittest.TestCase):
    def test_reconcile_matches_exact_hash_and_is_idempotent(self):
        with temp_dir() as directory:
            env = task_env(directory, base="")
            budget = task_controls.SessionBudget(env)
            key = budget.reserve("asset.upload", {"plan": upload_plan(), "confirm": True},
                                 side_effects=True)
            missing = budget.reconcile("f" * 64, "confirmed_applied", {"uploadId": "other"})
            self.assertEqual("not_found", missing["status"])
            result = budget.reconcile(key, "confirmed_applied", {"uploadId": UPLOAD_ID})
            self.assertEqual("reconciled", result["status"])
            self.assertEqual(0, result["pendingWrites"])
            again = budget.reconcile(key, "confirmed_applied", {"uploadId": UPLOAD_ID})
            self.assertEqual("already_reconciled", again["status"])
            self.assertEqual(0, budget.report()["pendingWrites"])
            raw_state = budget.path.read_text(encoding="utf-8")
            self.assertNotIn("synthetic.jpg", raw_state)
            self.assertNotIn("file-ref-secret", raw_state)
            self.assertEqual(1, len(json.loads(raw_state)["reconciled"]))

    def test_reconcile_isolated_by_task_instance_and_credential(self):
        with temp_dir() as directory:
            env = task_env(directory, base="")
            key = task_controls.SessionBudget(env).reserve(
                "asset.upload", {"plan": upload_plan(), "confirm": True}, side_effects=True)
            other_task = task_controls.SessionBudget(task_env(directory, task_id="task-other", base=""))
            self.assertEqual("not_found", other_task.reconcile(
                key, "confirmed_applied", {"uploadId": UPLOAD_ID})["status"])
            other_credential = task_controls.SessionBudget(
                task_env(directory, token=OTHER_TOKEN, base=""))
            self.assertEqual("not_found", other_credential.reconcile(
                key, "confirmed_applied", {"uploadId": UPLOAD_ID})["status"])
            other_instance = task_controls.SessionBudget(
                task_env(directory, base="http://127.0.0.1:18569"))
            self.assertEqual("not_found", other_instance.reconcile(
                key, "confirmed_applied", {"uploadId": UPLOAD_ID})["status"])
            owner = task_controls.SessionBudget(env)
            self.assertEqual("reconciled", owner.reconcile(
                key, "confirmed_applied", {"uploadId": UPLOAD_ID})["status"])

    def test_evidence_cannot_override_or_carry_paths(self):
        with temp_dir() as directory:
            env = task_env(directory, base="")
            budget = task_controls.SessionBudget(env)
            key = budget.reserve("asset.upload", {"plan": upload_plan(), "confirm": True},
                                 side_effects=True)
            for evidence in (
                    {"uploadId": "different-upload"},
                    {},
                    {"uploadId": UPLOAD_ID, "path": "C:\\private\\synthetic.jpg"},
            ):
                result = budget.reconcile(key, "confirmed_applied", evidence)
                self.assertEqual("rejected", result["status"])
                self.assertEqual(1, budget.report()["pendingWrites"])
            with self.assertRaises(errors.ToolError) as caught:
                budget.reconcile(key, "uncertain", {"uploadId": UPLOAD_ID})
            self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_concurrent_reconcile_succeeds_once(self):
        with temp_dir() as directory:
            env = task_env(directory, base="")
            key = task_controls.SessionBudget(env).reserve(
                "asset.upload", {"plan": upload_plan(), "confirm": True}, side_effects=True)
            barrier = threading.Barrier(2)

            def worker():
                budget = task_controls.SessionBudget(env)
                barrier.wait(timeout=5)
                return budget.reconcile(key, "confirmed_applied",
                                        {"uploadId": UPLOAD_ID})["status"]

            with ThreadPoolExecutor(max_workers=2) as pool:
                statuses = sorted(pool.map(lambda _: worker(), range(2)))
            self.assertEqual(["already_reconciled", "reconciled"], statuses)
            self.assertEqual(0, task_controls.SessionBudget(env).report()["pendingWrites"])


class RenewTests(unittest.TestCase):
    def test_renew_preserves_counts_and_persists_caps(self):
        with temp_dir() as directory:
            env = task_env(directory, base="", QIMENG_AI_MAX_CALLS="2")
            budget = task_controls.SessionBudget(env)
            for cursor in ("a", "b"):
                key = budget.reserve("asset.query", {"cursor": cursor})
                budget.complete(key, {"items": [cursor]})
            before = json.loads(budget.path.read_text(encoding="utf-8"))
            renewal = budget.renew(max_calls=5, max_seconds=1200)
            after_state = json.loads(budget.path.read_text(encoding="utf-8"))
            after = task_controls.SessionBudget(env).report()
            self.assertEqual("renewed", renewal["status"])
            self.assertEqual(before["calls"], after_state["calls"])
            self.assertEqual(before["observations"], after_state["observations"])
            self.assertEqual(before["startedAt"], after_state["startedAt"])
            self.assertEqual(before["pending"], after_state["pending"])
            self.assertEqual(5, after["maxCalls"])
            self.assertEqual(1200, after["maxSeconds"])
            self.assertAlmostEqual(after_state["startedAt"] + 1200, after["deadline"], places=3)

    def test_renew_rejects_lowering_zero_and_negative(self):
        with temp_dir() as directory:
            budget = task_controls.SessionBudget(task_env(directory, base=""))
            for kwargs in ({"max_calls": 0}, {"max_calls": -1},
                           {"max_seconds": 0}, {"max_seconds": -5}, {}):
                with self.assertRaises(errors.ToolError) as caught:
                    budget.renew(**kwargs)
                self.assertEqual("INVALID_INPUT", caught.exception.code)
            budget.renew(max_calls=500)
            with self.assertRaises(errors.ToolError) as caught:
                budget.renew(max_calls=500)
            self.assertEqual("INVALID_INPUT", caught.exception.code)
            with self.assertRaises(errors.ToolError) as caught:
                budget.renew(max_calls=499)
            self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_renew_authorization_file_is_required_and_scoped(self):
        with temp_dir() as directory:
            base = "http://127.0.0.1:18568"
            env = task_env(directory, base=base)
            grant_path = Path(directory) / "grant.json"

            def write_grant(allow, *, expires_at=None, api_base=base):
                grant_path.write_text(json.dumps({
                    "version": 1, "apiBase": api_base,
                    "expiresAt": time.time() + 60 if expires_at is None else expires_at,
                    "allow": allow,
                }), encoding="utf-8")

            write_grant([{"tool": "author.create"}])
            with self.assertRaises(errors.ToolError) as caught:
                task_controls.SessionBudget(env).renew(max_calls=200,
                                                       authorization_file=str(grant_path))
            self.assertEqual("AUTHORIZATION_MISMATCH", caught.exception.code)

            write_grant([{"tool": "budget.renew"}], expires_at=time.time() - 1)
            with self.assertRaises(errors.ToolError) as caught:
                task_controls.SessionBudget(env).renew(max_calls=200,
                                                       authorization_file=str(grant_path))
            self.assertEqual("AUTHORIZATION_EXPIRED", caught.exception.code)

            write_grant([{"tool": "budget.renew"}], api_base="http://127.0.0.1:18569")
            with self.assertRaises(errors.ToolError) as caught:
                task_controls.SessionBudget(env).renew(max_calls=200,
                                                       authorization_file=str(grant_path))
            self.assertEqual("AUTHORIZATION_MISMATCH", caught.exception.code)

            write_grant([{"tool": "budget.renew", "arguments": {"max_calls": 200}}])
            with self.assertRaises(errors.ToolError) as caught:
                task_controls.SessionBudget(env).renew(max_calls=200,
                                                       authorization_file=str(grant_path))
            self.assertEqual("CONFIGURATION_ERROR", caught.exception.code)

            write_grant([{"tool": "budget.renew"}])
            result = task_controls.SessionBudget(env).renew(
                max_calls=200, authorization_file=str(grant_path))
            self.assertEqual("renewed", result["status"])

    def test_renewal_history_is_bounded(self):
        with temp_dir() as directory:
            env = task_env(directory, base="", QIMENG_AI_MAX_CALLS="1")
            budget = task_controls.SessionBudget(env)
            for cap in range(2, 57):
                budget.renew(max_calls=cap)
            state = json.loads(budget.path.read_text(encoding="utf-8"))
            self.assertEqual(task_controls.RENEW_HISTORY_LIMIT, len(state["renewals"]))


class _StubHandler(BaseHTTPRequestHandler):
    routes = {}

    def do_GET(self):
        parsed = parse.urlsplit(self.path)
        status, payload = type(self).routes.get(
            parsed.path, (404, {"code": "NOT_FOUND", "message": "synthetic"}))
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *args):
        pass


class ReadbackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _StubHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = "http://127.0.0.1:%d" % cls.server.server_port

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        _StubHandler.routes = {}
        self.env = {"QIMENG_API_BASE": self.base, "QIMENG_API_TOKEN": TOKEN}

    def readback(self, tool, identity, evidence=None):
        return task_admin.readback(tool, identity, env=self.env,
                                   evidence=evidence if evidence is not None else {})

    def test_upload_partial_full_and_missing(self):
        _StubHandler.routes["/api/v1/uploads/" + UPLOAD_ID] = (
            200, {"id": UPLOAD_ID, "offset": 5, "size": 12})
        partial = self.readback("asset.upload", upload_identity(), {"uploadId": UPLOAD_ID})
        self.assertEqual(task_admin.CONFIRMED_NOT_APPLIED, partial["status"])

        _StubHandler.routes["/api/v1/uploads/" + UPLOAD_ID] = (
            200, {"id": UPLOAD_ID, "offset": 12, "size": 12})
        _StubHandler.routes["/api/v1/assets"] = (200, {
            "items": [{"id": "asset-1", "fileName": "synthetic.jpg", "sizeBytes": 12,
                       "modifiedAt": SOURCE_ISO, "addedAt": SOURCE_ISO}],
            "nextCursor": None,
        })
        _StubHandler.routes["/api/v1/assets/asset-1"] = (200, {
            "id": "asset-1", "fileName": "synthetic.jpg", "sizeBytes": 12,
            "modifiedAt": SOURCE_ISO, "addedAt": SOURCE_ISO,
            "relPath": "dir/synthetic.jpg",
        })
        applied = self.readback("asset.upload", upload_identity(), {"uploadId": UPLOAD_ID})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, applied["status"])

        # 会话过期返回 404 时仍可凭唯一资产匹配确认已执行。
        _StubHandler.routes["/api/v1/uploads/" + UPLOAD_ID] = (404, {"code": "NOT_FOUND"})
        expired_but_applied = self.readback("asset.upload", upload_identity(),
                                            {"uploadId": UPLOAD_ID})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, expired_but_applied["status"])

        # 404 且找不到可确证资产时保持 uncertain，不能断言未执行。
        _StubHandler.routes["/api/v1/assets"] = (200, {"items": [], "nextCursor": None})
        missing = self.readback("asset.upload", upload_identity(), {"uploadId": UPLOAD_ID})
        self.assertEqual(task_admin.UNCERTAIN, missing["status"])

    def test_upload_404_does_not_confirm_preexisting_matching_asset(self):
        _StubHandler.routes["/api/v1/uploads/" + UPLOAD_ID] = (404, {"code": "NOT_FOUND"})
        _StubHandler.routes["/api/v1/assets"] = (200, {
            "items": [{"id": "asset-old", "fileName": "synthetic.jpg",
                       "sizeBytes": 12, "modifiedAt": SOURCE_ISO,
                       "addedAt": "2023-11-13T22:13:20Z"}],
            "nextCursor": None,
        })
        _StubHandler.routes["/api/v1/assets/asset-old"] = (200, {
            "id": "asset-old", "fileName": "synthetic.jpg", "sizeBytes": 12,
            "modifiedAt": SOURCE_ISO, "addedAt": "2023-11-13T22:13:20Z",
            "relPath": "dir/synthetic.jpg",
        })
        result = self.readback("asset.upload", upload_identity(), {"uploadId": UPLOAD_ID})
        self.assertEqual(task_admin.UNCERTAIN, result["status"])
        self.assertIn("unmatched", result["reason"])

    def test_upload_does_not_confirm_without_unique_asset(self):
        _StubHandler.routes["/api/v1/uploads/" + UPLOAD_ID] = (
            200, {"id": UPLOAD_ID, "offset": 12, "size": 12})
        _StubHandler.routes["/api/v1/assets"] = (200, {"items": [], "nextCursor": None})
        result = self.readback("asset.upload", upload_identity(), {"uploadId": UPLOAD_ID})
        self.assertEqual(task_admin.UNCERTAIN, result["status"])

    def test_profile_revision_branches(self):
        path = "/api/v1/authors/author-1/profile"
        identity = {"authorId": "author-1", "expectedRevision": 3,
                    "changes": [{"field": "nickname", "digest": plans.digest("新名")}]}
        _StubHandler.routes[path] = (200, {"authorId": "author-1", "revision": 3,
                                           "nickname": "旧名"})
        unchanged = self.readback("author.profile.update", identity, {"authorId": "author-1"})
        self.assertEqual(task_admin.CONFIRMED_NOT_APPLIED, unchanged["status"])

        _StubHandler.routes[path] = (200, {"authorId": "author-1", "revision": 4,
                                           "nickname": "新名"})
        applied = self.readback("author.profile.update", identity, {"authorId": "author-1"})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, applied["status"])

        _StubHandler.routes[path] = (200, {"authorId": "author-1", "revision": 5,
                                           "nickname": "新名"})
        advanced = self.readback("author.profile.update", identity, {"authorId": "author-1"})
        self.assertEqual(task_admin.UNCERTAIN, advanced["status"])

    def test_asset_author_tag_rename_and_trash_branches(self):
        path = "/api/v1/assets/asset-1"
        _StubHandler.routes[path] = (200, {"id": "asset-1", "fileName": "new.jpg",
                                           "authors": [{"id": "a1"}, {"id": "a2"}],
                                           "tags": [{"id": "t1"}]})
        authors = self.readback("asset.authors.apply",
                                {"assetId": "asset-1", "authorIds": ["a2", "a1"]},
                                {"assetId": "asset-1"})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, authors["status"])
        tags = self.readback("asset.tags.replace",
                             {"assetId": "asset-1", "tagIds": ["t1"]},
                             {"assetId": "asset-1"})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, tags["status"])

        # newAuthorNames 由服务端解析/新建后，回读按显示名与目标 ID 共同确认。
        _StubHandler.routes[path] = (200, {
            "id": "asset-1", "fileName": "new.jpg",
            "authors": [{"id": "a1", "displayName": "Existing"},
                        {"id": "new-a", "displayName": "New Author"}],
        })
        created_by_name = self.readback(
            "asset.authors.apply",
            {"assetId": "asset-1", "authorIds": ["a1"],
             "newAuthorNames": ["New Author"], "previousAuthorIds": ["a1"]},
            {"assetId": "asset-1"})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, created_by_name["status"])

        _StubHandler.routes[path] = (200, {"id": "asset-1", "fileName": "old.jpg",
                                           "authors": [{"id": "a0"}],
                                           "tags": [{"id": "t0"}]})
        authors_not_applied = self.readback(
            "asset.authors.apply",
            {"assetId": "asset-1", "authorIds": ["a1", "a2"],
             "previousAuthorIds": ["a0"]},
            {"assetId": "asset-1"})
        self.assertEqual(task_admin.CONFIRMED_NOT_APPLIED, authors_not_applied["status"])
        tags_not_applied = self.readback(
            "asset.tags.replace",
            {"assetId": "asset-1", "tagIds": ["t1"], "previousTagIds": ["t0"]},
            {"assetId": "asset-1"})
        self.assertEqual(task_admin.CONFIRMED_NOT_APPLIED, tags_not_applied["status"])

        _StubHandler.routes[path] = (200, {"id": "asset-1", "fileName": "new.jpg",
                                           "authors": [{"id": "a1"}, {"id": "a2"}],
                                           "tags": [{"id": "t1"}]})
        renamed = self.readback("asset.rename.apply",
                                {"assetId": "asset-1", "oldName": "old.jpg",
                                 "newName": "new.jpg"},
                                {"assetId": "asset-1"})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, renamed["status"])

        _StubHandler.routes[path] = (200, {"id": "asset-1", "fileName": "old.jpg"})
        not_renamed = self.readback("asset.rename.apply",
                                    {"assetId": "asset-1", "oldName": "old.jpg",
                                     "newName": "new.jpg"},
                                    {"assetId": "asset-1"})
        self.assertEqual(task_admin.CONFIRMED_NOT_APPLIED, not_renamed["status"])

        _StubHandler.routes[path] = (404, {"code": "NOT_FOUND"})
        trashed = self.readback("asset.trash", {"assetId": "asset-1"},
                                {"assetId": "asset-1"})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, trashed["status"])
        _StubHandler.routes[path] = (200, {"id": "asset-1"})
        kept = self.readback("asset.trash", {"assetId": "asset-1"}, {"assetId": "asset-1"})
        self.assertEqual(task_admin.CONFIRMED_NOT_APPLIED, kept["status"])

    def test_author_merge_create_and_maintenance(self):
        _StubHandler.routes["/api/v1/authors"] = (200, [
            {"id": "target-1", "displayName": "Target", "type": "regular"},
        ])
        merged = self.readback("author.merge",
                               {"sourceAuthorId": "source-1", "targetAuthorId": "target-1"},
                               {"sourceAuthorId": "source-1", "targetAuthorId": "target-1"})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, merged["status"])

        created = self.readback("author.create", {"displayName": "Target"},
                                {"authorId": "target-1"})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, created["status"])

        _StubHandler.routes["/api/v1/maintenance/jobs/job-1"] = (200, {
            "id": "job-1", "status": "applying",
            "steps": [{"step": "s1", "status": "completed"}],
        })
        step = self.readback("maintenance.step.record",
                             {"jobId": "job-1", "step": "s1", "stepStatus": "completed"},
                             {"jobId": "job-1"})
        self.assertEqual(task_admin.CONFIRMED_APPLIED, step["status"])
        complete = self.readback("maintenance.complete", {"jobId": "job-1"},
                                 {"jobId": "job-1"})
        self.assertEqual(task_admin.CONFIRMED_NOT_APPLIED, complete["status"])

    def test_http_or_network_failure_is_uncertain(self):
        env = {"QIMENG_API_BASE": "http://127.0.0.1:1", "QIMENG_API_TOKEN": TOKEN}
        result = task_admin.readback("asset.upload", upload_identity(), env=env,
                                     evidence={"uploadId": UPLOAD_ID})
        self.assertEqual(task_admin.UNCERTAIN, result["status"])

    def test_evidence_conflict_returns_uncertain(self):
        result = self.readback("asset.upload", upload_identity(),
                               {"uploadId": "different-upload"})
        self.assertEqual(task_admin.UNCERTAIN, result["status"])
        self.assertEqual("evidence_mismatch", result["reason"])


class AdminCliTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _StubHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = "http://127.0.0.1:%d" % cls.server.server_port

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        _StubHandler.routes = {}

    def _invoke(self, argv, env):
        output = io.StringIO()
        with patch.dict(os.environ, env), contextlib.redirect_stdout(output):
            code = cli.main(argv)
        return code, output.getvalue()

    def test_status_output_has_no_secret_and_reports_pending(self):
        with temp_dir() as directory:
            env = task_env(directory, token=TOKEN, base=self.base)
            budget = task_controls.SessionBudget(env)
            budget.reserve("asset.upload", {"plan": upload_plan(), "confirm": True},
                           side_effects=True)
            code, output = self._invoke([
                "admin", "task", "status", "--task-id", "task-synthetic",
                "--state-dir", str(directory),
            ], env)
            payload = json.loads(output)
            self.assertEqual(0, code)
            self.assertEqual("ok", payload["status"])
            self.assertEqual("unresolved", payload["verdict"])
            self.assertEqual(1, len(payload["pending"]))
            self.assertNotIn(TOKEN, output)
            self.assertNotIn(str(directory), output)
            self.assertNotIn("file-ref-secret", output)

    def test_reconcile_requires_confirm_and_uses_readback(self):
        with temp_dir() as directory:
            env = task_env(directory, token=TOKEN, base=self.base)
            budget = task_controls.SessionBudget(env)
            key = budget.reserve("asset.upload", {"plan": upload_plan(), "confirm": True},
                                 side_effects=True)
            _StubHandler.routes["/api/v1/uploads/" + UPLOAD_ID] = (
                200, {"id": UPLOAD_ID, "offset": 12, "size": 12})
            _StubHandler.routes["/api/v1/assets"] = (200, {
                "items": [{"id": "asset-1", "fileName": "synthetic.jpg", "sizeBytes": 12,
                           "modifiedAt": SOURCE_ISO}], "nextCursor": None})
            _StubHandler.routes["/api/v1/assets/asset-1"] = (200, {
                "id": "asset-1", "fileName": "synthetic.jpg", "sizeBytes": 12,
                "modifiedAt": SOURCE_ISO, "relPath": "dir/synthetic.jpg"})
            argv = ["admin", "task", "reconcile", "--task-id", "task-synthetic",
                    "--state-dir", str(directory), "--request-hash", key]
            code, _output = self._invoke(argv, env)
            self.assertEqual(2, code)
            code, output = self._invoke(argv + ["--confirm"], env)
            payload = json.loads(output)
            self.assertEqual(0, code)
            self.assertEqual("reconciled", payload["status"])
            self.assertEqual(task_admin.CONFIRMED_APPLIED, payload["verdict"])
            self.assertEqual(0, task_controls.SessionBudget(env).report()["pendingWrites"])

    def test_reconcile_uncertain_keeps_pending_then_not_applied_unblocks(self):
        with temp_dir() as directory:
            env = task_env(directory, token=TOKEN, base=self.base)
            budget = task_controls.SessionBudget(env)
            key = budget.reserve("asset.upload", {"plan": upload_plan(), "confirm": True},
                                 side_effects=True)
            _StubHandler.routes["/api/v1/uploads/" + UPLOAD_ID] = (404, {"code": "NOT_FOUND"})
            argv = ["admin", "task", "reconcile", "--task-id", "task-synthetic",
                    "--state-dir", str(directory), "--request-hash", key, "--confirm"]
            code, output = self._invoke(argv, env)
            payload = json.loads(output)
            self.assertEqual(1, code)
            self.assertEqual("uncertain", payload["status"])
            self.assertEqual(1, task_controls.SessionBudget(env).report()["pendingWrites"])
            with self.assertRaises(errors.ToolError) as caught:
                task_controls.SessionBudget(env).reserve(
                    "asset.trash", {"assetId": "asset-1", "confirm": True}, side_effects=True)
            self.assertEqual("TASK_OPERATION_PENDING", caught.exception.code)

            _StubHandler.routes["/api/v1/uploads/" + UPLOAD_ID] = (
                200, {"id": UPLOAD_ID, "offset": 3, "size": 12})
            code, output = self._invoke(argv, env)
            payload = json.loads(output)
            self.assertEqual(0, code)
            self.assertEqual(task_admin.CONFIRMED_NOT_APPLIED, payload["verdict"])
            self.assertEqual(0, task_controls.SessionBudget(env).report()["pendingWrites"])
            # 已确认未执行同样解除阻断，但不会伪造已执行。
            code, output = self._invoke(argv, env)
            self.assertEqual(0, code)
            self.assertEqual("already_reconciled", json.loads(output)["status"])

    def test_list_reports_ledgers_and_fails_closed_on_damage(self):
        with temp_dir() as directory:
            env = task_env(directory, token=TOKEN, base="")
            budget = task_controls.SessionBudget(env)
            budget.reserve("asset.query", {"limit": 5})
            (budget.path.parent / "damaged.json").write_text("{not-json", encoding="utf-8")
            code, output = self._invoke([
                "admin", "task", "list", "--state-dir", str(directory),
            ], env)
            payload = json.loads(output)
            self.assertEqual(1, code)
            self.assertEqual("partial", payload["status"])
            statuses = {item["ledgerId"]: item["status"] for item in payload["ledgers"]}
            self.assertEqual("unavailable", statuses["damaged"])
            self.assertEqual("ok", statuses[budget.path.stem])
            self.assertNotIn(str(directory), output)

    def test_usage_errors_and_renew(self):
        with temp_dir() as directory:
            env = task_env(directory, token="", base="")
            code, _output = self._invoke([
                "admin", "task", "status", "--task-id", "task-synthetic",
            ], {**env, "QIMENG_AI_STATE_DIR": ""})
            self.assertEqual(2, code)
            code, output = self._invoke([
                "admin", "task", "status", "--task-id", "task-missing",
                "--state-dir", str(directory),
            ], env)
            self.assertEqual(1, code)
            self.assertEqual("not_found", json.loads(output)["status"])
            self.assertFalse((Path(directory) / "budgets").exists())  # 查询不创建/重置账本
            code, output = self._invoke([
                "admin", "task", "renew", "--task-id", "task-synthetic",
                "--state-dir", str(directory), "--max-calls", "200",
            ], env)
            self.assertEqual(2, code)  # 缺少 --confirm
            code, output = self._invoke([
                "admin", "task", "renew", "--task-id", "task-synthetic",
                "--state-dir", str(directory), "--max-calls", "200", "--confirm",
            ], env)
            payload = json.loads(output)
            self.assertEqual(0, code)
            self.assertEqual("renewed", payload["status"])
            self.assertEqual(200, payload["renewal"]["maxCalls"])


if __name__ == "__main__":
    unittest.main()
