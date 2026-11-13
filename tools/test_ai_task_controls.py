"""Behavioral regressions for default access, trusted grants, discovery and budgets."""
import io
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from ai_maintenance import errors, execution, mcp_server, registry, task_controls, recipes


class TrustedGrantTests(unittest.TestCase):
    def test_task_grant_also_covers_recipe_primitive(self):
        with tempfile.TemporaryDirectory() as directory:
            grant_path = Path(directory) / "grant.json"
            env = {"QIMENG_API_BASE": "http://127.0.0.1:18568",
                   "QIMENG_AI_AUTHORIZATION_FILE": str(grant_path),
                   "QIMENG_AI_GRANTED_PERMISSIONS": "read,local.files.write"}
            step = {"id": "transfer", "primitive": "media.transfer"}
            payload = {"source": "synthetic-a", "target": "synthetic-b"}
            ctx = recipes.RunContext(env, Path(directory) / "run")
            for allowed, expect_success in (("author.create", False), ("media.transfer", True)):
                grant_path.write_text(json.dumps({"version": 1, "apiBase": env["QIMENG_API_BASE"],
                    "expiresAt": time.time() + 60,
                    "allow": [{"tool": allowed, "arguments": {"source": "synthetic-a"}}]}), encoding="utf-8")
                config = execution.load_config(env)
                with patch.dict(recipes.PRIMITIVES, {"media.transfer": lambda *_: {"status": "ok"}}):
                    if expect_success:
                        result = recipes._run_step(step, {}, ctx, {}, {}, config, True,
                                                   payload=payload, authorized=True)
                        self.assertEqual("ok", result["status"])
                    else:
                        with self.assertRaises(errors.ToolError) as caught:
                            recipes._run_step(step, {}, ctx, {}, {}, config, True,
                                              payload=payload, authorized=True)
                        self.assertEqual("AUTHORIZATION_MISMATCH", caught.exception.code)
                self.assertEqual(0, config.budget.report()["pendingWrites"])

    def test_default_read_only_and_explicit_grants(self):
        config = execution.load_config({})
        self.assertEqual(frozenset({"read"}), config.granted_permissions)
        with self.assertRaises(errors.ToolError) as caught:
            execution.execute_tool("author.create", {"displayName": "synthetic", "confirm": True},
                                   env={}, config=config)
        self.assertEqual("PERMISSION_DENIED", caught.exception.code)
        self.assertIn("author.profile.write", execution.load_config(
            {"QIMENG_AI_GRANTED_PERMISSIONS": "read,author.profile.write"}).granted_permissions)

    def test_grant_binds_target_action_arguments_and_expiry(self):
        spec = registry.get("author.create")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "grant.json"
            grant = {"version": 1, "apiBase": "http://127.0.0.1:18568", "expiresAt": time.time() + 60,
                     "allow": [{"tool": spec.id, "arguments": {"displayName": "authorized"}}]}
            path.write_text(json.dumps(grant), encoding="utf-8")
            env = {"QIMENG_API_BASE": grant["apiBase"], "QIMENG_AI_AUTHORIZATION_FILE": str(path)}
            task_controls.authorize(spec, {"displayName": "authorized", "confirm": True}, env)
            for payload, override in [({"displayName": "other", "confirm": True}, {}),
                                      ({"displayName": "authorized"}, {"QIMENG_API_BASE": "http://127.0.0.1:18569"})]:
                with self.assertRaises(errors.ToolError) as caught:
                    task_controls.authorize(spec, payload, {**env, **override})
                self.assertEqual("AUTHORIZATION_MISMATCH", caught.exception.code)
            grant["expiresAt"] = time.time() - 1
            path.write_text(json.dumps(grant), encoding="utf-8")
            with self.assertRaises(errors.ToolError) as caught:
                task_controls.authorize(spec, {"displayName": "authorized"}, env)
            self.assertEqual("AUTHORIZATION_EXPIRED", caught.exception.code)

    def test_missing_and_malformed_grants_fail_closed(self):
        spec = registry.get("author.create")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "grant.json"
            for content in (None, "not json", "[]", '{"version":1,"expiresAt":true}'):
                if content is not None:
                    path.write_text(content, encoding="utf-8")
                with self.assertRaises(errors.ToolError):
                    task_controls.authorize(spec, {"confirm": True},
                        {"QIMENG_AI_AUTHORIZATION_FILE": str(path)})


class BudgetTests(unittest.TestCase):
    def test_pending_write_blocks_other_client_until_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            env = {"QIMENG_AI_TASK_ID": "synthetic-write", "QIMENG_AI_STATE_DIR": directory}
            first = task_controls.SessionBudget(env)
            key = first.reserve("write", {"id": "one"}, side_effects=True)
            second = task_controls.SessionBudget(env)
            with self.assertRaises(errors.ToolError) as caught:
                second.reserve("write", {"id": "two"}, side_effects=True)
            self.assertEqual("TASK_OPERATION_PENDING", caught.exception.code)
            read_key = second.reserve("readback", {"id": "one"})
            second.complete(read_key, {"status": "applied"})
            self.assertEqual(1, second.report()["pendingWrites"])
            first.complete(key, {"status": "applied"})
            second.reserve("write", {"id": "two"}, side_effects=True)

    def test_concurrent_read_reservations_count_towards_no_progress_limit(self):
        budget = task_controls.SessionBudget({"QIMENG_AI_MAX_UNCHANGED": "2"})
        first = budget.reserve("same", {})
        second = budget.reserve("same", {})
        with self.assertRaises(errors.ToolError) as caught:
            budget.reserve("same", {})
        self.assertEqual("TASK_NO_PROGRESS", caught.exception.code)
        budget.complete(first, {"value": 1})
        budget.complete(second, {"value": 1})

    def test_uncertain_write_remains_pending_and_preflight_error_does_not(self):
        config = execution.load_config({})
        with self.assertRaises(errors.ToolError):
            execution.execute_tool("author.create", {"confirm": True}, env={}, config=config)
        self.assertEqual(0, config.budget.report()["pendingWrites"])

        def interrupted(mark_started):
            mark_started()
            raise RuntimeError("synthetic disconnect after operation")

        with self.assertRaises(RuntimeError) as caught:
            execution.controlled_action("synthetic.write", {}, interrupted,
                                        env={}, config=config, side_effects=True)
        self.assertTrue(caught.exception.operation_may_have_completed)
        self.assertEqual(1, config.budget.report()["pendingWrites"])

    def test_handler_input_rejection_can_be_corrected_without_pending_write(self):
        config = execution.load_config({})

        def rejected(mark_started):
            mark_started()
            raise errors.invalid_input("synthetic invalid source")

        with self.assertRaises(errors.ToolError):
            execution.controlled_action("synthetic.write", {"bad": True}, rejected,
                env={}, config=config, side_effects=True)
        self.assertEqual(0, config.budget.report()["pendingWrites"])

    def test_task_budget_survives_new_client_instances(self):
        with tempfile.TemporaryDirectory() as directory:
            env = {"QIMENG_AI_TASK_ID": "synthetic-task", "QIMENG_AI_STATE_DIR": directory,
                   "QIMENG_AI_MAX_CALLS": "1"}
            first = task_controls.SessionBudget(env)
            first.reserve("query", {})
            second = task_controls.SessionBudget(env)
            self.assertEqual("task", second.report()["scope"])
            self.assertEqual(1, second.report()["calls"])
            with self.assertRaises(errors.ToolError) as caught:
                second.reserve("query", {"cursor": "new"})
            self.assertEqual("TASK_BUDGET_EXHAUSTED", caught.exception.code)

    def test_total_calls_are_reserved_before_side_effects(self):
        budget = task_controls.SessionBudget({"QIMENG_AI_MAX_CALLS": "2"})
        budget.reserve("query", {"cursor": "a"})
        budget.reserve("write", {"confirm": True})
        with self.assertRaises(errors.ToolError) as caught:
            budget.reserve("write", {"confirm": True})
        self.assertEqual("TASK_BUDGET_EXHAUSTED", caught.exception.code)
        self.assertEqual(2, budget.report()["calls"])

    def test_unchanged_results_stop_but_new_pages_continue(self):
        budget = task_controls.SessionBudget({"QIMENG_AI_MAX_UNCHANGED": "2"})
        for _ in range(2):
            key = budget.reserve("query", {})
            budget.complete(key, {"status": "pending"})
        with self.assertRaises(errors.ToolError) as caught:
            budget.reserve("query", {})
        self.assertEqual("TASK_NO_PROGRESS", caught.exception.code)
        budget.reserve("query", {"cursor": "next"})

    def test_changed_state_resets_no_progress_counter(self):
        budget = task_controls.SessionBudget({"QIMENG_AI_MAX_UNCHANGED": "2"})
        for status in ("queued", "running", "done"):
            key = budget.reserve("query", {})
            budget.complete(key, {"status": status})
        budget.reserve("query", {})

    def test_time_and_invalid_budget(self):
        with patch.object(task_controls.time, "monotonic", return_value=10):
            budget = task_controls.SessionBudget({"QIMENG_AI_MAX_SECONDS": "1"})
        with patch.object(task_controls.time, "monotonic", return_value=12):
            with self.assertRaises(errors.ToolError) as caught:
                budget.reserve("query", {})
        self.assertEqual("TASK_BUDGET_EXHAUSTED", caught.exception.code)
        for value in ("0", "x", "10001"):
            with self.assertRaises(errors.ToolError):
                task_controls.SessionBudget({"QIMENG_AI_MAX_CALLS": value})


class LazyDiscoveryTests(unittest.TestCase):
    def test_long_original_rule_is_readable_beyond_excerpt(self):
        config = execution.load_config({})
        original = {"text": "规则后半段也必须可读取" * 500}
        config.rules["syntheticLongRule"] = original
        _, excerpt = execution.execute_tool("platform.rules", {"sections": ["syntheticLongRule"]},
                                             env={}, config=config)
        self.assertTrue(excerpt["truncated"])
        self.assertIn("continuation", excerpt["sections"]["syntheticLongRule"])
        fragments = []
        offset = 0
        while offset is not None:
            _, page = execution.execute_tool("platform.rules",
                {"sections": ["syntheticLongRule"], "offset": offset, "limit": 1000},
                env={}, config=config)
            self.assertLess(len(json.dumps(page, ensure_ascii=False).encode()), 12288)
            fragments.append(page["fragment"])
            offset = page["nextOffset"]
        self.assertEqual(original, json.loads("".join(fragments)))

    def test_cold_discovery_is_small_and_requested_tool_is_published(self):
        output = io.StringIO()
        server = mcp_server.McpServer(env={}, stdout=output)
        cold = server._tools_list()["tools"]
        full = mcp_server.McpServer(env={"QIMENG_AI_MCP_DISCOVERY": "full"})._tools_list()["tools"]
        self.assertLess(len(json.dumps(cold)), len(json.dumps(full)) / 2)
        self.assertNotIn("author.query", {item["name"] for item in cold})
        result = server._tools_call(1, {"name": "platform.tool.describe",
                                     "arguments": {"toolId": "author.query"}}, set())
        self.assertFalse(result["result"]["isError"])
        self.assertIn("author.query", {item["name"] for item in server._tools_list()["tools"]})
        self.assertEqual("notifications/tools/list_changed", json.loads(output.getvalue())["method"])

    def test_discovery_does_not_expand_domain_or_grant_permission(self):
        server = mcp_server.McpServer(env={"QIMENG_AI_MCP_DOMAINS": "media"}, stdout=io.StringIO())
        server._publish_discovered("platform.tool.describe", {"tool": {"id": "author.create"}})
        self.assertNotIn("author.create", {spec.id for spec in server._visible_tools()})
        self.assertIn("platform.result.read", {spec.id for spec in server._visible_tools()})
        description = server._scope_discovery_result("platform.tool.describe",
            {"tool": {"id": "author.create", "enabled": True}})
        self.assertFalse(description["tool"]["exposedInConnection"])
        self.assertTrue(description["tool"]["disabled"])
        context = server._scope_discovery_result("platform.context",
            {"tools": [{"id": "author.create"}], "next": ["author.create"]})
        self.assertEqual([], context["next"])
        with self.assertRaises(errors.ToolError) as caught:
            execution.execute_tool("author.create", {"displayName": "synthetic", "confirm": True}, env={})
        self.assertEqual("PERMISSION_DENIED", caught.exception.code)


if __name__ == "__main__":
    unittest.main()
