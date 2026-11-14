"""Side-effect recovery notice when bounded result presentation fails."""
from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest import mock

TOOLS_DIR = __import__("pathlib").Path(__file__).resolve().parent
import sys
sys.path.insert(0, str(TOOLS_DIR))

from ai_maintenance import cli, execution, mcp_server, model_output, registry  # noqa: E402


class OutputPresentationFailureTests(unittest.TestCase):
    def setUp(self):
        self.spec = registry.get("asset.upload")
        self.operation_result = {
            "status": "applied", "assetId": "asset-synthetic-1", "uploadId": "upload-synthetic-1",
        }
        self.present_error = model_output.ModelOutputError(
            "RESULT_STORE_LIMIT", "synthetic presentation failure")

    def test_mcp_reports_completed_write_and_does_not_return_retry_error(self):
        calls = []

        def execute(tool_id, payload):
            calls.append((tool_id, payload))
            return self.spec, self.operation_result

        env = {"QIMENG_AI_MCP_DISCOVERY": "full"}
        server = mcp_server.McpServer(env=env, execute=execute)
        message = {
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": "asset.upload", "arguments": {
                "planRef": "p1_" + "0" * 32, "confirm": True,
            }},
        }
        with mock.patch.object(model_output, "present_result", side_effect=self.present_error):
            response = server.handle_message(message, set())["result"]

        self.assertEqual(1, len(calls))
        self.assertFalse(response["isError"])
        notice = response["structuredContent"]["result"]
        self.assertTrue(notice["executionCompleted"])
        self.assertTrue(notice["resultPresentationFailed"])
        self.assertEqual("applied", notice["originalStatus"])
        self.assertEqual("RESULT_STORE_LIMIT", notice["presentationErrorCode"])
        self.assertIn({"kind": "uploadId", "value": "upload-synthetic-1"}, notice["operationIds"])
        self.assertIn("不要自动重试", notice["recovery"])

    def test_cli_preserves_business_exit_status_after_presentation_failure(self):
        for status, expected_exit in (("applied", 0), ("failed", 1)):
            with self.subTest(status=status):
                calls = []
                captured = []
                result = {**self.operation_result, "status": status}
                config = SimpleNamespace(rulepack_version="rules-test")

                def execute(*args, **kwargs):
                    calls.append(args)
                    return self.spec, result

                args = SimpleNamespace(tool_id="asset.upload")
                with (
                    mock.patch.object(cli, "_tool_payload", return_value={"planRef": "ref", "confirm": True}),
                    mock.patch.object(cli, "_runtime_config", return_value=config),
                    mock.patch.object(cli.execution, "execute_tool", side_effect=execute),
                    mock.patch.object(model_output, "present_result", side_effect=self.present_error),
                    mock.patch.object(cli, "_print_json_compact",
                                      side_effect=lambda payload, _stream: captured.append(payload)),
                ):
                    exit_code = cli._run_tool_call(args, {})

                self.assertEqual(1, len(calls))
                self.assertEqual(expected_exit, exit_code)
                notice = captured[0]["result"]
                self.assertTrue(notice["executionCompleted"])
                self.assertTrue(notice["resultPresentationFailed"])
                self.assertEqual(status, notice["originalStatus"])

    def test_output_budget_is_rejected_by_preflight_api(self):
        with self.assertRaises(model_output.ModelOutputError) as caught:
            model_output.validate_output_config({"QIMENG_AI_OUTPUT_BUDGET_BYTES": "invalid"})
        self.assertEqual("OUTPUT_BUDGET_INVALID", caught.exception.code)

    def test_invalid_budget_prevents_handler_execution(self):
        with mock.patch.object(registry, "resolve_handler") as handler:
            with self.assertRaises(model_output.ModelOutputError) as caught:
                execution.execute_tool("author.create", {"displayName": "synthetic", "confirm": True},
                    env={"QIMENG_AI_OUTPUT_BUDGET_BYTES": "invalid", "QIMENG_AI_GRANTED_PERMISSIONS": "all"})
        handler.assert_not_called()
        self.assertEqual("OUTPUT_BUDGET_INVALID", caught.exception.code)

    def test_recovery_notice_preserves_unicode_identity_within_budget(self):
        notice = model_output.presentation_failure("author.profile.update",
            {"status": "applied", "authorId": "合成作者甲"}, self.present_error,
            payload={}, env={"QIMENG_AI_OUTPUT_BUDGET_BYTES": "1024"})
        self.assertIn({"kind": "authorId", "value": "合成作者甲"}, notice["operationIds"])
        import json
        self.assertLessEqual(len(json.dumps(notice, ensure_ascii=False).encode()), 1024)


if __name__ == "__main__":
    unittest.main()
