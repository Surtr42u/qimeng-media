"""扩展性优化回归（2026-11-07）：精简领域目录、按需 Schema/规则、惰性模块加载。

全部合成数据/临时文件；本地 meta 工具不访问网络。覆盖任务要求：
大量工具注册、模块缺失、版本/规则变化、并发调用、禁用工具、权限与发现分离、
CLI/MCP 共用注册与执行、MCP 完整 tools/list 与本目录入口并存。
"""
from __future__ import annotations

import contextlib
import dataclasses
import io
import json
import os
import subprocess
import sys
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from ai_maintenance import cli, errors, execution, mcp_server, registry

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "api" / "openapi.yaml"
RULES = ROOT / "tools" / "ai_maintenance" / "rules.json"


def local_config(env=None):
    env = dict(env or {})
    env.setdefault("QIMENG_AI_OPENAPI", str(OPENAPI))
    env.setdefault("QIMENG_AI_RULES", str(RULES))
    return execution.load_config(env)


def run_cli(argv, env=None):
    stdout, stderr = io.StringIO(), io.StringIO()
    with patch.dict(os.environ, env or {}, clear=False):
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = cli.main(argv)
    return status, stdout.getvalue(), stderr.getvalue()


def synthetic_spec(tool_id, domain="author", **overrides):
    base = registry.get("author.query")
    assert base is not None
    fields = dict(id=tool_id, version="1.0.0", description="合成工具 " + tool_id,
                  kind="context", output_schema={"type": "object"},
                  handler_ref="ai_maintenance.tools_query:author_query",
                  permission="read", read_only=True, side_effects=False,
                  network=False, domain=domain)
    fields.update(overrides)
    return dataclasses.replace(base, **fields)


@contextlib.contextmanager
def patched_registry(tools):
    tools = tuple(tools)
    index = {tool.id: tool for tool in tools}
    with patch.object(registry, "TOOLS", tools), patch.object(registry, "_TOOL_INDEX", index):
        yield tools


class CatalogDiscoveryTest(unittest.TestCase):
    def test_catalog_is_compact_grouped_and_complete(self):
        config = local_config()
        _, catalog = execution.execute_tool("platform.catalog", {}, config=config)
        self.assertEqual(len(registry.TOOLS), catalog["toolCount"])
        self.assertEqual(len(registry.DOMAINS), len(catalog["domains"]))
        domain_ids = {item["id"] for item in catalog["domains"]}
        self.assertEqual({item["id"] for item in registry.DOMAINS}, domain_ids)
        for item in catalog["tools"]:
            self.assertNotIn("inputSchema", item)
            self.assertNotIn("outputSchema", item)
            self.assertIn("id", item)
            self.assertIn("domain", item)
            self.assertIn("status", item)
        # 目录必须显著小于完整 tools list（模型上下文预算）
        _, full = execution.execute_tool("platform.tools.list.probe", {}, config=config) if False else (None, execution.tool_listing(config))
        compact_bytes = len(json.dumps(catalog, ensure_ascii=False))
        full_bytes = len(json.dumps(full, ensure_ascii=False))
        self.assertLess(compact_bytes * 3, full_bytes)
        # 领域分组内的 id 与扁平列表一致
        grouped = [tool_id for domain in catalog["domains"] for tool_id in domain["tools"]]
        self.assertEqual(sorted(grouped), sorted(item["id"] for item in catalog["tools"]))

    def test_catalog_domain_filter_and_unknown_domain(self):
        config = local_config()
        _, catalog = execution.execute_tool("platform.catalog", {"domain": "author"}, config=config)
        self.assertGreater(catalog["toolCount"], 0)
        for item in catalog["tools"]:
            self.assertEqual("author", item["domain"])
        self.assertTrue(all(domain["id"] == "author" for domain in catalog["domains"]))
        with self.assertRaises(errors.ToolError) as raised:
            execution.execute_tool("platform.catalog", {"domain": "no.such"}, config=config)
        self.assertEqual("INVALID_INPUT", raised.exception.code)

    def test_describe_returns_schema_examples_and_rules_excerpt(self):
        config = local_config()
        _, described = execution.execute_tool(
            "platform.tool.describe", {"toolId": "media.organize.prepare"}, config=config)
        tool = described["tool"]
        self.assertEqual("media", tool["domain"])
        self.assertIn("inputSchema", tool)
        self.assertTrue(tool["examples"])
        self.assertIn("mediaNaming", tool["rulesRefs"])
        self.assertIn("mediaNaming", tool["rulesExcerpt"])
        self.assertTrue(tool["implementationAvailable"])
        self.assertTrue(tool["goApiRequirements"])
        with self.assertRaises(errors.ToolError) as raised:
            execution.execute_tool("platform.tool.describe", {"toolId": "no.such"}, config=config)
        self.assertEqual("UNKNOWN_TOOL", raised.exception.code)

    def test_playbook_list_get_and_unknown(self):
        config = local_config()
        _, listed = execution.execute_tool("platform.playbook", {}, config=config)
        names = [row["name"] for row in listed["playbooks"]]
        self.assertIn("media.organize", names)
        self.assertIn("author.profile", names)
        for row in listed["playbooks"]:
            self.assertIn("baseTools", row)
            self.assertIn("whenToUse", row)
        _, by_domain = execution.execute_tool("platform.playbook", {"domain": "author"}, config=config)
        for row in by_domain["playbooks"]:
            self.assertEqual("author", row["domain"])
        _, single = execution.execute_tool("platform.playbook", {"name": "media.organize"}, config=config)
        playbook = single["playbook"]
        self.assertEqual("media.organize", playbook["name"])
        self.assertTrue(playbook["steps"])
        self.assertTrue(playbook["guardrails"])
        with self.assertRaises(errors.ToolError) as raised:
            execution.execute_tool("platform.playbook", {"name": "no.such.playbook"}, config=config)
        self.assertEqual("INVALID_INPUT", raised.exception.code)
        with self.assertRaises(errors.ToolError) as raised:
            execution.execute_tool("platform.playbook", {"domain": "no.such.domain"}, config=config)
        self.assertEqual("INVALID_INPUT", raised.exception.code)

    def test_playbook_base_tools_reference_registered_tools(self):
        config = local_config()
        _, listed = execution.execute_tool("platform.playbook", {}, config=config)
        for row in listed["playbooks"]:
            for tool_id in row["baseTools"]:
                self.assertIsNotNone(registry.get(tool_id), f"{row['name']} 引用了未知工具 {tool_id}")

    def test_playbook_cli_lists_and_reads(self):
        status, stdout, stderr = run_cli(["playbook", "--json"])
        self.assertEqual(0, status, stderr)
        self.assertIn("playbooks", json.loads(stdout))
        status, stdout, stderr = run_cli(["playbook", "--name", "author.profile", "--json"])
        self.assertEqual(0, status, stderr)
        self.assertEqual("author.profile", json.loads(stdout)["playbook"]["name"])

    def test_catalog_advertises_playbook_discovery(self):
        config = local_config()
        _, catalog = execution.execute_tool("platform.catalog", {}, config=config)
        self.assertEqual("platform.playbook", catalog["discovery"]["playbook"])

    def test_rules_on_demand(self):
        config = local_config()
        _, payload = execution.execute_tool("platform.rules", {"domain": "media"}, config=config)
        self.assertEqual(config.rulepack_version, payload["rulepackVersion"])
        self.assertIn("mediaNaming", payload["sections"])
        self.assertNotIn("authorActions", payload["sections"])
        _, single = execution.execute_tool(
            "platform.rules", {"sections": ["permissions"]}, config=config)
        self.assertEqual(["permissions"], list(single["sections"].keys()))
        with self.assertRaises(errors.ToolError) as raised:
            execution.execute_tool("platform.rules", {"sections": ["noSuchSection"]}, config=config)
        self.assertEqual("INVALID_INPUT", raised.exception.code)

    def test_rules_change_reflected_without_full_load(self):
        # 规则版本变化后，catalog/describe/rules 都返回新版本，且不要求完整包进上下文
        raw = json.loads(RULES.read_text(encoding="utf-8"))
        raw["rulepackVersion"] = "2099-01-01.1"
        raw["rulesVersion"] = "2099-01-01.1"
        temp = ROOT / "tools" / "ai_maintenance" / ".test-temp" / "rules-version.json"
        temp.parent.mkdir(parents=True, exist_ok=True)
        temp.write_text(json.dumps(raw, ensure_ascii=False), encoding="utf-8")
        try:
            config = local_config({"QIMENG_AI_RULES": str(temp)})
            _, catalog = execution.execute_tool("platform.catalog", {}, config=config)
            self.assertEqual("2099-01-01.1", catalog["rulepackVersion"])
            _, rules = execution.execute_tool("platform.rules", {"sections": ["permissions"]},
                                              config=config)
            self.assertEqual("2099-01-01.1", rules["rulepackVersion"])
        finally:
            temp.unlink(missing_ok=True)

    def test_deprecated_tools_hidden_by_default(self):
        deprecated = synthetic_spec("author.legacy", deprecated=True,
                                    deprecated_reason="改用新工具", replaced_by="author.query")
        with patched_registry(list(registry.TOOLS) + [deprecated]):
            config = local_config()
            _, catalog = execution.execute_tool("platform.catalog", {}, config=config)
            ids = [item["id"] for item in catalog["tools"]]
            self.assertNotIn("author.legacy", ids)
            _, included = execution.execute_tool(
                "platform.catalog", {"includeDeprecated": True}, config=config)
            self.assertIn("author.legacy", [item["id"] for item in included["tools"]])
            with self.assertRaises(errors.ToolError) as raised:
                execution.execute_tool("author.legacy", {}, config=config)
            self.assertEqual("TOOL_DEPRECATED", raised.exception.code)


class LazyLoadingAndRegistryTest(unittest.TestCase):
    def test_registry_import_does_not_load_implementations(self):
        code = (
            "import sys; sys.path.insert(0, 'tools');"
            "import ai_maintenance.registry as r;"
            "loaded=[m for m in ('ai_maintenance.tools_author','ai_maintenance.tools_query',"
            "'ai_maintenance.tools_media','ai_maintenance.media_naming','ai_maintenance.catalog')"
            " if m in sys.modules];"
            "print(','.join(loaded))"
        )
        result = subprocess.run([sys.executable, "-c", code], cwd=str(ROOT),
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout.strip(),
                         "注册表导入不应触发实现模块加载：" + result.stdout)

    def test_handler_resolution_is_lazy_and_cached(self):
        spec = registry.get("author.query")
        self.assertIsNotNone(spec)
        self.assertFalse(spec.handler_ref == "")
        first = registry.resolve_handler(spec)
        second = registry.resolve_handler(spec)
        self.assertIs(first, second)
        self.assertIn("ai_maintenance.tools_query", sys.modules)

    def test_missing_implementation_module_is_unavailable(self):
        broken = synthetic_spec("author.broken", handler_ref="ai_maintenance.no_such_module:fn")
        with patched_registry(list(registry.TOOLS) + [broken]):
            status = registry.tool_status(broken, local_config().document())
            self.assertEqual("unavailable", status["status"])
            compact = registry.compact_summary(broken, local_config().document())
            self.assertEqual("unavailable", compact["status"])
            with self.assertRaises(errors.ToolError) as raised:
                execution.execute_tool("author.broken", {}, config=local_config())
            self.assertEqual("TOOL_NOT_IMPLEMENTED", raised.exception.code)

    def test_disabled_tool_rejected_before_execution(self):
        env = {"QIMENG_AI_DISABLED_TOOLS": "author.merge",
               "QIMENG_AI_OPENAPI": str(OPENAPI), "QIMENG_AI_RULES": str(RULES)}
        with self.assertRaises(errors.ToolError) as raised:
            execution.execute_tool("author.merge", {}, env=env)
        self.assertEqual("TOOL_DISABLED", raised.exception.code)

    def test_many_tools_catalog_stays_bounded(self):
        many = [synthetic_spec("author.synthetic.%03d" % index) for index in range(120)]
        with patched_registry(list(registry.TOOLS) + many):
            config = local_config()
            _, catalog = execution.execute_tool("platform.catalog", {}, config=config)
            self.assertEqual(len(registry.TOOLS), catalog["toolCount"])
            bounded = len(json.dumps(catalog, ensure_ascii=False))
            # 120 个合成工具进入目录后仍有界（阈值给足，只防爆炸性增长）
            self.assertLess(bounded, 200_000)
            for item in catalog["tools"]:
                self.assertNotIn("inputSchema", item)

    def test_concurrent_catalog_and_rules_calls(self):
        # This concurrency test explicitly grants a repeat budget for identical discovery.
        config = local_config({"QIMENG_AI_MAX_UNCHANGED": "100"})

        def work(_index):
            _, catalog = execution.execute_tool("platform.catalog", {}, config=config)
            _, rules = execution.execute_tool("platform.rules", {"sections": ["permissions"]},
                                              config=config)
            return catalog["toolCount"], sorted(rules["sections"].keys())

        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(work, range(24)))
        expected = (len(registry.TOOLS), ["permissions"])
        self.assertTrue(all(item == expected for item in results))


class PermissionDiscoverySeparationTest(unittest.TestCase):
    def test_permission_denied_for_write_tool(self):
        env = {"QIMENG_AI_GRANTED_PERMISSIONS": "read",
               "QIMENG_AI_OPENAPI": str(OPENAPI), "QIMENG_AI_RULES": str(RULES)}
        config = execution.load_config(env)
        spec = registry.get("author.profile.prepare")
        self.assertEqual("author.profile.write", execution.permission_reason(spec, config))
        with self.assertRaises(errors.ToolError) as raised:
            execution.execute_tool("author.profile.prepare", {}, env=env)
        self.assertEqual("PERMISSION_DENIED", raised.exception.code)
        # 只读工具仍允许（不会因权限层被误伤）
        self.assertEqual("", execution.permission_reason(registry.get("author.query"), config))

    def test_default_only_grants_read_permission(self):
        config = local_config()
        for spec in registry.TOOLS:
            self.assertEqual(spec.permission != "read", bool(execution.permission_reason(spec, config)))
        self.assertEqual({"read"}, set(config.granted_permissions))

    def test_unknown_granted_permission_is_configuration_error(self):
        with self.assertRaises(errors.ToolError) as raised:
            execution.load_config({"QIMENG_AI_GRANTED_PERMISSIONS": "no.such.permission",
                                   "QIMENG_AI_OPENAPI": str(OPENAPI),
                                   "QIMENG_AI_RULES": str(RULES)})
        self.assertEqual("CONFIGURATION_ERROR", raised.exception.code)

    def test_catalog_visible_but_not_authorized_is_explicit(self):
        env = {"QIMENG_AI_GRANTED_PERMISSIONS": "read",
               "QIMENG_AI_OPENAPI": str(OPENAPI), "QIMENG_AI_RULES": str(RULES)}
        config = execution.load_config(env)
        _, catalog = execution.execute_tool("platform.catalog", {}, config=config)
        ids = [item["id"] for item in catalog["tools"]]
        self.assertIn("author.merge", ids)  # 可以发现
        with self.assertRaises(errors.ToolError) as raised:
            execution.execute_tool("author.merge", {}, config=config)  # 但无权执行
        self.assertEqual("PERMISSION_DENIED", raised.exception.code)


class CliMcpParityTest(unittest.TestCase):
    def test_cli_catalog_compact_and_tools_show(self):
        status, stdout, stderr = run_cli(["catalog", "--json"])
        self.assertEqual(0, status, stderr)
        catalog = json.loads(stdout)
        self.assertEqual(len(registry.TOOLS), catalog["toolCount"])
        self.assertNotIn("inputSchema", catalog["tools"][0])
        status, stdout, _ = run_cli(["tools", "list", "--compact", "--json"])
        self.assertEqual(0, status)
        self.assertEqual(catalog["toolCount"], json.loads(stdout)["toolCount"])
        status, stdout, _ = run_cli(["tools", "list", "--json"])
        self.assertEqual(0, status)
        full = json.loads(stdout)
        self.assertIn("inputSchema", full["tools"][0])
        status, stdout, _ = run_cli(["tools", "show", "author.merge"])
        self.assertEqual(0, status)
        described = json.loads(stdout)
        self.assertEqual("author.merge", described["id"])
        self.assertTrue(described["examples"])
        self.assertTrue(described["rulesRefs"])
        status, stdout, _ = run_cli(["catalog", "--domain", "author", "--json"])
        self.assertEqual(0, status)
        self.assertTrue(all(item["domain"] == "author" for item in json.loads(stdout)["tools"]))

    def test_mcp_keeps_full_tools_list_and_exposes_catalog(self):
        server = mcp_server.McpServer(
            stdin=io.StringIO(), stdout=io.StringIO(),
            env={"QIMENG_AI_OPENAPI": str(OPENAPI), "QIMENG_AI_RULES": str(RULES),
                 "QIMENG_AI_MCP_DISCOVERY": "full"},
        )
        identity = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                    "params": {"protocolVersion": "2025-06-18"}}
        init = server.handle_message(identity, set())
        self.assertIn("capabilities", init["result"])
        listing = server.handle_message(
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, set())["result"]["tools"]
        ids = [tool["name"] for tool in listing]
        self.assertEqual(len(registry.TOOLS), len(ids))
        self.assertIn("platform.catalog", ids)
        self.assertIn("inputSchema", listing[0])
        catalog_call = server.handle_message(
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
             "params": {"name": "platform.catalog", "arguments": {}}}, set())
        envelope = json.loads(catalog_call["result"]["content"][0]["text"])
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(len(registry.TOOLS), envelope["result"]["toolCount"])
        describe_call = server.handle_message(
            {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
             "params": {"name": "platform.tool.describe",
                        "arguments": {"toolId": "author.query"}}}, set())
        envelope = json.loads(describe_call["result"]["content"][0]["text"])
        self.assertIn("inputSchema", envelope["result"]["tool"]["inputSchema"] and envelope["result"]["tool"])


if __name__ == "__main__":
    unittest.main()
