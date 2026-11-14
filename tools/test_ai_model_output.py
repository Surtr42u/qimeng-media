"""AI 模型输出预算、本地结果续读与无损 planRef 回归（全合成数据）。"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))

from ai_maintenance import model_output, recipes  # noqa: E402


class _Context:
    def __init__(self, env, rulepack_version="rules-test"):
        self.env = env
        self.rulepack_version = rulepack_version


class ModelOutputTests(unittest.TestCase):
    def test_character_sized_reads_cannot_reconstruct_absolute_path(self):
        original_path = str(self.root / "private" / "original.jpg")
        response = model_output.present_result("test.query",
            {"padding": "x" * 6000, "localPath": original_path, "empty": ""},
            env=self.env, rulepack_version="rules-test")
        cursor = None
        fragments = []
        while True:
            arguments = {"resultRef": response["resultRef"], "path": "/localPath", "limit": 1}
            if cursor is not None:
                arguments["cursor"] = cursor
            page = model_output.read_result(self.ctx, arguments)
            fragments.append(page["value"])
            cursor = page["nextCursor"]
            if cursor is None:
                break
        self.assertNotEqual(original_path, "".join(fragments))
        self.assertNotIn(str(self.root), "".join(fragments))
        empty = model_output.read_result(self.ctx,
            {"resultRef": response["resultRef"], "path": "/empty", "limit": 1})
        self.assertEqual("", empty["value"])
        self.assertIsNone(empty["nextCursor"])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qimeng-output-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {
            "QIMENG_API_BASE": "http://127.0.0.1:8420",
            "QIMENG_API_TOKEN": "synthetic-token-A",
            "QIMENG_AI_STATE_DIR": str(self.root / "state"),
            "QIMENG_AI_OUTPUT_BUDGET_BYTES": "1024",
        }
        self.ctx = _Context(self.env)

    @staticmethod
    def _encoded_size(value):
        return len(json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("ascii"))

    def _artifact_path(self, result_ref):
        matches = list((self.root / "state").rglob(result_ref + ".json"))
        self.assertEqual(len(matches), 1)
        return matches[0]

    def test_small_result_keeps_its_shape(self):
        result = {"status": "ok", "count": 2, "items": [{"id": "a"}, {"id": "b"}]}
        self.assertEqual(result, model_output.present_result(
            "test.query", result, env=self.env, rulepack_version="rules-test"))

    def test_large_result_pages_conserve_all_items_within_budget(self):
        source = [{"id": index, "name": "synthetic-" + str(index)} for index in range(120)]
        response = model_output.present_result(
            "test.query", source, env=self.env, rulepack_version="rules-test")
        self.assertTrue(response["truncated"])
        self.assertEqual(len(source), response["count"])
        self.assertIn("resultRef", response)

        assembled = []
        cursor = None
        while True:
            page = model_output.read_result(self.ctx, {
                "resultRef": response["resultRef"], "limit": 9, "cursor": cursor,
            })
            self.assertLessEqual(self._encoded_size(page), model_output.MAX_PAGE_BYTES)
            self.assertEqual("array", page["kind"])
            assembled.extend(page["value"])
            cursor = page["nextCursor"]
            if cursor is None:
                break
        self.assertEqual(source, assembled)

    def test_large_context_summary_keeps_discovery_and_tool_permissions(self):
        tools = [{
            "id": f"media.tool{index}", "version": "1.0.0", "domain": "media",
            "summary": "synthetic tool description " + ("x" * 80), "status": "supported",
            "permission": "media.write", "permitted": True, "requiresConfirmation": True,
            "goApiRequirements": [{"path": "/api/v1/assets", "method": "POST",
                                   "requiredBodyFields": ["assetId"]}],
        } for index in range(40)]
        source = {
            "platformVersion": "1.5.0", "toolContractVersion": "1.5.0",
            "rulepackVersion": "rules-test", "task": {"domain": "media"},
            "tools": tools, "next": [item["id"] for item in tools],
            "permissions": {"declared": ["media.write"], "granted": ["media.write"]},
            "discovery": {"describe": "platform.tool.describe", "rules": "platform.rules"},
            "rules": {"large": "x" * 8000}, "truncated": True,
        }
        response = model_output.present_result(
            "platform.context", source, env=self.env, rulepack_version="rules-test")
        self.assertLessEqual(self._encoded_size(response), 1024)
        self.assertTrue(response["truncated"])
        self.assertIn("resultRef", response)
        self.assertTrue(response["tools"])
        self.assertEqual("media.tool0", response["tools"][0]["id"])
        self.assertEqual("media.write", response["tools"][0]["permission"])
        self.assertEqual("platform.tool.describe", response["discovery"]["describe"])
        self.assertIn("next", response)
        self.assertGreater(response["summaryOmittedCounts"]["tools"], 0)

    def test_large_describe_summary_keeps_tool_identity_permission_and_schema(self):
        source = {
            "rulepackVersion": "rules-test", "truncated": True,
            "tool": {
                "id": "asset.upload.prepare", "version": "1.0.0", "domain": "asset",
                "description": "Synthetic upload preparation tool", "status": "supported",
                "permission": "asset.write", "readOnly": True,
                "requiresConfirmation": False,
                "inputSchema": {"type": "object", "required": ["items"],
                                 "properties": {"items": {"type": "array"},
                                                "mode": {"type": "string"}}},
                "outputSchema": {"type": "object", "properties": {"plan": {"type": "object"}}},
                "rulesExcerpt": {"large": "x" * 8000},
                "goApiRequirements": [{"path": "/api/v1/assets", "method": "POST"}],
            },
            "discovery": {"rules": "platform.rules"},
        }
        response = model_output.present_result(
            "platform.tool.describe", source, env=self.env, rulepack_version="rules-test")
        self.assertLessEqual(self._encoded_size(response), 1024)
        self.assertEqual("asset.upload.prepare", response["tool"]["id"])
        self.assertEqual("asset.write", response["tool"]["permission"])
        self.assertIn("inputSchema", response["tool"])
        self.assertIn("items", response["tool"]["inputSchema"]["properties"])

    def test_plan_ref_restores_exact_full_plan_and_hides_local_path(self):
        plan = {
            "tool": "asset.upload", "planVersion": "1.0", "planFingerprint": "synthetic",
            "operations": [{"index": index, "action": "upload"} for index in range(200)],
            "sourcePath": r"C:\\private\\synthetic\\file.bin",
        }
        source = {"status": "planned", "plan": plan, "diagnostics": {"count": 200}}
        response = model_output.present_result(
            "asset.upload.prepare", source, env=self.env, rulepack_version="rules-test")
        self.assertIn("planRef", response)
        self.assertNotIn(r"C:\\private\\synthetic\\file.bin", json.dumps(response))
        self.assertNotIn("plan", response)
        self.assertTrue(response["planPreviewOnly"])
        self.assertFalse(response["planSummary"]["executable"])
        resolved = model_output.resolve_plan_ref(
            response["planRef"], env=self.env, rulepack_version="rules-test", tool_id="asset.upload")
        self.assertEqual(plan, resolved)

    def test_plan_storage_failure_never_returns_an_over_budget_plan(self):
        plan = {"tool": "asset.upload", "operations": [{"index": i} for i in range(500)]}
        source = {"status": "planned", "plan": plan}
        with mock.patch.object(model_output, "_store_result",
                               side_effect=model_output.ModelOutputError(
                                   "RESULT_STORE_LIMIT", "synthetic store limit")):
            response = model_output.present_result(
                "asset.upload.prepare", source, env=self.env, rulepack_version="rules-test")
        self.assertLessEqual(self._encoded_size(response), 1024)
        self.assertTrue(response["truncated"])
        self.assertFalse(response["executable"])
        self.assertIn("planRefUnavailable", response)
        self.assertNotIn("plan", response)

    def test_oversized_item_has_a_point_path_and_can_be_read_separately(self):
        source = {"records": [
            {"id": "large", "content": "x" * 16000},
            {"id": "small", "content": "synthetic"},
        ]}
        response = model_output.present_result(
            "test.query", source, env=self.env, rulepack_version="rules-test")
        page = model_output.read_result(self.ctx, {
            "resultRef": response["resultRef"], "path": "/records", "limit": 10,
        })
        self.assertEqual("/records/0", page["omitted"][0]["path"])
        large_item = model_output.read_result(self.ctx, {
            "resultRef": response["resultRef"], "path": page["omitted"][0]["path"],
        })
        self.assertIn("id", large_item["value"])
        content_page = model_output.read_result(self.ctx, {
            "resultRef": response["resultRef"], "path": "/records/0",
            "cursor": large_item["nextCursor"],
        })
        self.assertEqual("/records/0/content", content_page["omitted"][0]["path"])

    def test_tampered_result_is_rejected(self):
        source = [{"id": index, "value": "x" * 80} for index in range(40)]
        response = model_output.present_result(
            "test.query", source, env=self.env, rulepack_version="rules-test")
        path = self._artifact_path(response["resultRef"])
        envelope = json.loads(path.read_text(encoding="utf-8"))
        envelope["result"][0]["value"] = "tampered"
        path.write_text(json.dumps(envelope), encoding="utf-8")
        with self.assertRaises(model_output.ModelOutputError) as caught:
            model_output.read_result(self.ctx, {"resultRef": response["resultRef"]})
        self.assertEqual("RESULT_TAMPERED", caught.exception.code)

    def test_reference_is_bound_to_instance_credential_and_rule_version(self):
        plan = {"tool": "asset.upload", "planVersion": "1.0",
                "operations": [{"index": index, "action": "upload"} for index in range(120)]}
        response = model_output.present_result(
            "asset.upload.prepare", {"plan": plan}, env=self.env,
            rulepack_version="rules-test")
        reference = response["resultRef"]

        other_token = {**self.env, "QIMENG_API_TOKEN": "synthetic-token-B"}
        with self.assertRaises(model_output.ModelOutputError):
            model_output.read_result(_Context(other_token), {"resultRef": reference})
        other_instance = {**self.env, "QIMENG_API_BASE": "http://127.0.0.2:8420"}
        with self.assertRaises(model_output.ModelOutputError):
            model_output.read_result(_Context(other_instance), {"resultRef": reference})
        with self.assertRaises(model_output.ModelOutputError):
            model_output.read_result(_Context(self.env, "rules-next"), {"resultRef": reference})
        with mock.patch.object(model_output.versions, "TOOL_CONTRACT_VERSION", "test-next"):
            with self.assertRaises(model_output.ModelOutputError):
                model_output.read_result(self.ctx, {"resultRef": reference})
        with self.assertRaises(model_output.ModelOutputError) as caught:
            model_output.resolve_plan_ref(
                response["planRef"], env=self.env, rulepack_version="rules-test", tool_id="wrong.tool")
        self.assertEqual("PLAN_REF_TOOL_MISMATCH", caught.exception.code)

    def test_credentials_are_never_echoed_or_persisted(self):
        result = {
            "status": "ok", "token": "synthetic-token-A",
            "items": [{"id": index, "note": "synthetic"} for index in range(80)],
        }
        response = model_output.present_result(
            "test.query", result, env=self.env, rulepack_version="rules-test")
        self.assertNotIn("synthetic-token-A", json.dumps(response))
        artifact = self._artifact_path(response["resultRef"])
        artifact_text = artifact.read_text(encoding="utf-8")
        self.assertNotIn("synthetic-token-A", artifact_text)
        page = model_output.read_result(self.ctx, {"resultRef": response["resultRef"], "path": "/items"})
        self.assertNotIn("synthetic-token-A", json.dumps(page))

    def test_bad_ref_and_pointer_are_rejected_without_path_disclosure(self):
        with self.assertRaises(model_output.ModelOutputError) as caught:
            model_output.read_result(self.ctx, {"resultRef": "r1_../../outside"})
        self.assertEqual("RESULT_REF_INVALID", caught.exception.code)
        # Small results are not stored, so create a larger object with a reference.
        response = model_output.present_result(
            "test.query", {"items": [{"id": index} for index in range(180)]},
            env=self.env, rulepack_version="rules-test")
        with self.assertRaises(model_output.ModelOutputError) as caught:
            model_output.read_result(self.ctx, {
                "resultRef": response["resultRef"], "path": "/missing/key",
            })
        self.assertEqual("RESULT_PATH_NOT_FOUND", caught.exception.code)
        self.assertNotIn(str(self.root), caught.exception.message)

    def test_recipe_summaries_declare_clipping_without_cutting_json_text(self):
        clipped = recipes._clip({f"key-{index}": index for index in range(15)})
        self.assertTrue(clipped["_truncated"])
        self.assertEqual(3, clipped["_omittedKeys"])
        long_result = {"status": "failed", "code": "SYNTHETIC", "count": 12,
                       "message": "detail " * 2000,
                       "plan": {f"field-{index}": "x" * 500 for index in range(15)}}
        summary = recipes._pick_summary(long_result)
        self.assertTrue(summary["truncated"])
        self.assertEqual("failed", summary["status"])
        self.assertEqual("SYNTHETIC", summary["code"])
        self.assertNotIn("preview", summary)

    def test_task_card_does_not_repeat_gate_message(self):
        card = recipes.task_card("author.discover", {})
        self.assertTrue(card["gates"])
        self.assertTrue(all(set(gate) == {"id"} for gate in card["gates"]))


if __name__ == "__main__":
    unittest.main()
