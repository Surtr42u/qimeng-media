"""AI 接入层契约一致性回归（2026-11-10，子代理 B）。

覆盖：
1. 按领域发现工具且安全元工具可见；
2. 必要元工具在 MCP 领域过滤下仍可 list/call；
3. 任务上下文相关性（media.organize 不返回 author 工具）；
4. 可用性维度（registered/implemented/contract/runtime/locallyAuthorized/serverVerified）；
5. 禁用/planned/废弃工具不被 platform.context 的 next 推荐；
6. platform.rules 点路径读取与未知路径；
7. 截断结果必须显式 truncated；
8. 工具 Schema 关键字白名单与示例全部通过校验；
9. 规则引用存在；
10. playbook baseTools/ruleSections 引用存在；
11. CLI/MCP 同一注册表与执行路径；
12. files.search 契约（fileRef/无绝对路径/limit/cursor/截断）；
13. 大结果返回限制（字符上限、分页、Schema 摘要截断）；
14. 版本一致性；
15. 一致性检查函数能抓出人为构造的坏引用。

全部使用合成数据/临时目录；fileref 用假模块注入或 skipUnless 真实实现，
不依赖并行子代理的文件是否已落地。
"""
from __future__ import annotations

import copy
import dataclasses
import io
import json
import os
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

from ai_maintenance import (catalog, errors, execution, mcp_server, registry,
                            rulepack, schema, tools_files, versions)

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "api" / "openapi.yaml"
RULES = ROOT / "tools" / "ai_maintenance" / "rules.json"
CLI = ROOT / "tools" / "ai_maintenance" / "cli.py"

try:  # 真实 fileref 由并行子代理提供；缺失时相关集成测试跳过。
    from ai_maintenance import fileref as real_fileref  # noqa: F401
    HAS_FILEREF = True
except ImportError:  # pragma: no cover - 仅在缺文件时触发
    HAS_FILEREF = False


def local_env(**extra) -> dict:
    env = {"QIMENG_AI_OPENAPI": str(OPENAPI), "QIMENG_AI_RULES": str(RULES)}
    env.update({key: value for key, value in extra.items() if value is not None})
    return env


def local_config(env=None):
    return execution.load_config(env or local_env())


def run_cli(argv, env=None):
    merged = dict(os.environ)
    merged.update(env or local_env())
    return subprocess.run([sys.executable, "-X", "utf8", str(CLI)] + list(argv),
                          cwd=str(ROOT), capture_output=True, text=True, timeout=180,
                          env=merged)


class _Ctx:
    """最小 ToolContext 替身，用于直接调用元工具 handler。"""

    def __init__(self, rules, *, env=None, granted=None):
        self.rules = rules
        self.rulepack_version = "test-rulepack"
        self.document = None
        self.env = dict(env or {})
        self.granted_permissions = (frozenset(registry.PERMISSIONS)
                                    if granted is None else frozenset(granted))
        self.disabled_tools = frozenset()
        self.read_only_mode = False


def _within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


class FakeFileref(types.ModuleType):
    """假 fileref：只实现 tools_files 使用的契约，不泄露绝对路径。"""

    def __init__(self, roots: dict[str, Path]):
        super().__init__("ai_maintenance.fileref")
        self._roots = dict(roots)

    def storages(self, env=None):
        return dict(self._roots)

    def storage_info(self, env=None):
        return [{"storageId": storage_id, "writable": False}
                for storage_id in self._roots]

    def make_ref(self, path, *, env=None):
        candidate = Path(path)
        best = None
        for storage_id, root in self._roots.items():
            if _within(candidate, root) and (
                    best is None or len(root.parts) > len(best[1].parts)):
                best = (storage_id, root)
        if best is None:
            raise RuntimeError("not in any storage")
        storage_id, root = best
        relative = candidate.relative_to(root).as_posix()
        info = candidate.stat()
        suffix = candidate.suffix.lower()
        media = "image" if suffix in (".jpg", ".jpeg", ".png") else (
            "video" if suffix in (".mp4", ".mkv") else "other")
        return {
            "fileRef": "ref_" + storage_id + "_" + relative.replace("/", "_"),
            "storageId": storage_id,
            "relativePath": relative,
            "name": candidate.name,
            "mediaType": media,
            "size": int(info.st_size),
            "modifiedAt": "2026-01-01T00:00:00Z",
        }


class FakeFilerefInjection:
    """把假模块同时注入 sys.modules 与包属性，保证 `from . import fileref` 命中。"""

    def __init__(self, roots):
        self.module = FakeFileref(roots)

    def __enter__(self):
        self._modules = mock.patch.dict(
            sys.modules, {"ai_maintenance.fileref": self.module})
        self._modules.start()
        import ai_maintenance
        self._attr = mock.patch.object(ai_maintenance, "fileref", self.module,
                                       create=True)
        self._attr.start()
        return self.module

    def __exit__(self, exc_type, exc, tb):
        self._attr.stop()
        self._modules.stop()
        return False


def _temp_tree(root: Path) -> None:
    (root / "a.jpg").write_bytes(b"a" * 10)
    (root / "b.txt").write_bytes(b"b" * 20)
    sub = root / "sub"
    sub.mkdir()
    (sub / "c.png").write_bytes(b"c" * 30)
    (root / ".hidden.jpg").write_bytes(b"h" * 5)


class McpDomainVisibilityTest(unittest.TestCase):
    def _server(self, domains="author"):
        return mcp_server.McpServer(
            stdin=io.StringIO(), stdout=io.StringIO(),
            env=local_env(QIMENG_AI_MCP_DOMAINS=domains))

    def test_domain_filter_keeps_discovery_meta_tools(self):
        server = self._server("author")
        visible = {spec.id: spec for spec in server._visible_tools()}
        self.assertIn("platform.catalog", visible)
        self.assertIn("platform.context", visible)
        self.assertIn("platform.rules", visible)
        self.assertIn("files.storages", visible)
        self.assertNotIn("files.search", visible)  # 业务工具仍受领域过滤
        for tool_id, spec in visible.items():
            if tool_id.startswith("platform.") or tool_id == "files.storages":
                continue
            self.assertEqual("author", spec.domain, tool_id)

    def test_meta_tools_are_listed_and_callable_under_filter(self):
        server = self._server("author")
        listing = server.handle_message(
            {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, set())
        names = [tool["name"] for tool in listing["result"]["tools"]]
        self.assertIn("platform.catalog", names)
        self.assertIn("files.storages", names)
        self.assertNotIn("files.search", names)

        call = server.handle_message(
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
             "params": {"name": "platform.catalog", "arguments": {}}}, set())
        self.assertFalse(call["result"]["isError"])
        payload = json.loads(call["result"]["content"][0]["text"])
        self.assertEqual("ok", payload["status"])
        self.assertEqual(len(registry.TOOLS), payload["result"]["toolCount"])

        storages = server.handle_message(
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
             "params": {"name": "files.storages", "arguments": {}}}, set())
        self.assertFalse(storages["result"]["isError"])

        hidden = server.handle_message(
            {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
             "params": {"name": "files.search", "arguments": {}}}, set())
        self.assertEqual(-32602, hidden["error"]["code"])


class ContextRelevanceTest(unittest.TestCase):
    def _context(self, payload, env=None):
        _spec, result = execution.execute_tool("platform.context", payload,
                                               config=local_config(env))
        return result

    def test_media_organize_does_not_return_author_tools(self):
        result = self._context({"task": "media.organize"})
        ids = [item["id"] for item in result["tools"]]
        self.assertIn("media.organize.prepare", ids)
        self.assertTrue(all(not tool_id.startswith("author.") for tool_id in ids))
        self.assertNotIn("authorActions", result["rules"])
        self.assertNotIn("fields", result["rules"])

    def test_availability_dimensions_are_not_conflated(self):
        config = local_config()
        document = config.document()
        spec = registry.get("media.organize.prepare")
        full = registry.availability(spec, document,
                                     granted_permissions=frozenset({"read"}),
                                     env={})
        self.assertEqual({"registered", "implemented", "contract", "runtime",
                          "locallyAuthorized", "serverVerified"}, set(full))
        self.assertTrue(full["registered"])
        self.assertTrue(full["implemented"])
        self.assertEqual("compatible", full["contract"])
        self.assertFalse(full["locallyAuthorized"])
        self.assertFalse(full["serverVerified"])
        # 未授权不影响 status/契约维度
        self.assertEqual("supported", registry.tool_status(spec, document)["status"])

        planned = dataclasses.replace(registry.get("asset.query"), id="media.planned",
                                      planned=True, planned_reason="合成 planned",
                                      handler_ref="")
        planned_status = registry.tool_status(planned, None)
        self.assertEqual("planned", planned_status["status"])
        planned_availability = registry.availability(planned, None)
        self.assertFalse(planned_availability["implemented"])
        self.assertEqual("not_checked", planned_availability["contract"])
        self.assertEqual("not_checked", planned_availability["runtime"])
        self.assertIsNone(planned_availability["locallyAuthorized"])

        missing = dataclasses.replace(registry.get("author.query"), id="author.missing",
                                      handler_ref="ai_maintenance.no_such_module:fn")
        self.assertEqual("unavailable", registry.tool_status(missing, None)["status"])
        self.assertFalse(registry.availability(missing, None)["implemented"])

    def test_next_excludes_unpermitted_planned_disabled_deprecated(self):
        env = {"QIMENG_AI_GRANTED_PERMISSIONS": "read"}
        result = self._context({"task": "media.organize"}, env=env)
        self.assertIn("asset.query", result["next"])
        self.assertNotIn("media.organize.prepare", result["next"])
        self.assertNotIn("media.organize.apply", result["next"])
        # tools 数组本身不因 next 过滤被裁剪或重排
        self.assertIn("media.organize.prepare",
                      [item["id"] for item in result["tools"]])

        base = registry.get("asset.query")
        planned = dataclasses.replace(base, id="media.planned", domain="media",
                                      planned=True, planned_reason="合成",
                                      handler_ref="")
        deprecated = dataclasses.replace(base, id="media.legacy", domain="media",
                                         deprecated=True, deprecated_reason="合成",
                                         replaced_by="asset.query")
        tools = list(registry.TOOLS) + [planned, deprecated]
        index = {tool.id: tool for tool in tools}
        with mock.patch.object(registry, "TOOLS", tuple(tools)), \
                mock.patch.object(registry, "_TOOL_INDEX", index):
            result = self._context({"domain": "media", "maxTools": 24})
        ids = [item["id"] for item in result["tools"]]
        self.assertIn("media.planned", ids)
        self.assertIn("media.legacy", ids)
        self.assertNotIn("media.planned", result["next"])
        self.assertNotIn("media.legacy", result["next"])
        self.assertTrue(all(tool_id.startswith("media.") or tool_id.startswith("asset.")
                            or tool_id.startswith("library.") or tool_id.startswith("trash.")
                            or tool_id.startswith("files.")
                            for tool_id in ids))


class RuleDotPathAndTruncationTest(unittest.TestCase):
    def test_dot_path_reads_nested_playbook_and_keeps_key(self):
        config = local_config()
        _spec, payload = execution.execute_tool(
            "platform.rules", {"sections": ["playbooks.media.organize", "permissions"]},
            config=config)
        self.assertEqual(["playbooks.media.organize", "permissions"],
                         list(payload["sections"].keys()))
        self.assertEqual("media", payload["sections"]["playbooks.media.organize"]["domain"])
        self.assertFalse(payload["truncated"])

    def test_unknown_and_illegal_paths_are_invalid(self):
        config = local_config()
        for bad in ("playbooks.no.such", "playbooks..media", "a.b.c.d.e",
                    "playbooks[0]", "  ", "playbooks .media"):
            with self.assertRaises(errors.ToolError) as caught:
                execution.execute_tool("platform.rules", {"sections": [bad]},
                                       config=config)
            self.assertEqual("INVALID_INPUT", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            execution.execute_tool("platform.rules", {"sections": ["noSuchSection"]},
                                   config=config)
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        self.assertIn("availableSections", caught.exception.details)

    def test_rule_and_playbook_truncation_is_explicit(self):
        big = {"items": [{"index": index, "text": "x" * 40} for index in range(400)]}
        ctx = _Ctx({"permissions": {}, "big": big,
                    "playbooks": {"big": {"domain": "media", "title": "big",
                                          "steps": [{"action": "x" * 200} for _ in range(80)]}}})
        payload = catalog.platform_rules(ctx, {"sections": ["big"]})
        self.assertTrue(payload["truncated"])
        self.assertIn("big", payload["truncatedSections"])
        self.assertTrue(payload["sections"]["big"]["truncated"])

        playbook = catalog.platform_playbook(ctx, {"name": "big"})
        self.assertTrue(playbook["truncated"])

        summary = catalog._schema_summary(
            {"type": "object",
             "properties": {f"p{index}": {"type": "string"} for index in range(40)}})
        self.assertTrue(summary["truncated"])

    def test_context_marks_tool_truncation(self):
        config = local_config()
        _spec, result = execution.execute_tool(
            "platform.context", {"task": "media.organize", "maxTools": 1},
            config=config)
        self.assertTrue(result["truncated"])
        self.assertTrue(result["task"]["truncatedTools"])
        self.assertEqual(1, len(result["tools"]))


class SchemaContractTest(unittest.TestCase):
    def test_all_tool_schema_keywords_are_supported(self):
        unsupported = []

        def walk(node, path):
            if not isinstance(node, dict):
                return
            for key, value in node.items():
                if key == "properties" and isinstance(value, dict):
                    for name, sub in value.items():
                        walk(sub, f"{path}.properties.{name}")
                elif key == "items":
                    walk(value, path + ".items")
                elif key == "additionalProperties" and isinstance(value, dict):
                    walk(value, path + ".additionalProperties")
                else:
                    if key not in schema.SUPPORTED_KEYWORDS and \
                            key not in schema.ANNOTATION_KEYWORDS:
                        unsupported.append(f"{path}.{key}")

        for spec in registry.TOOLS:
            walk(spec.input_schema, spec.id + ".input")
            walk(spec.output_schema, spec.id + ".output")
        self.assertEqual([], unsupported)

    def test_audit_would_catch_unknown_keyword(self):
        planted = {"type": "object",
                   "properties": {"url": {"type": "string", "format": "uri"}}}
        found = set()

        def walk(node):
            if not isinstance(node, dict):
                return
            for key, value in node.items():
                found.add(key)
                if key == "properties" and isinstance(value, dict):
                    for sub in value.values():
                        walk(sub)
                elif isinstance(value, dict):
                    walk(value)

        walk(planted)
        self.assertIn("format", found - schema.SUPPORTED_KEYWORDS - schema.ANNOTATION_KEYWORDS)

    def test_all_tool_examples_validate(self):
        for tool_id, examples in registry.TOOL_EXAMPLES.items():
            spec = registry.get(tool_id)
            self.assertIsNotNone(spec, tool_id)
            for index, example in enumerate(examples):
                problems = schema.validate(example, spec.input_schema)
                self.assertEqual([], problems, f"{tool_id}[{index}]")


class RuleConsistencyTest(unittest.TestCase):
    def test_real_rules_have_no_error_level_problems(self):
        rules = rulepack.load_rules(RULES)
        problems = rulepack.consistency_problems(rules, local_config().document())
        errors = [item for item in problems if item["severity"] == "error"]
        self.assertEqual([], errors)
        for item in problems:
            self.assertIn("code", item)
            self.assertIn("path", item)
            self.assertIn("detail", item)

    def test_rule_and_playbook_references_exist(self):
        rules = rulepack.load_rules(RULES)
        for tool_id, sections in registry.TOOL_RULES_REFS.items():
            self.assertIsNotNone(registry.get(tool_id), tool_id)
            for section in sections:
                self.assertIn(section, rules, f"{tool_id} -> {section}")
        for name, playbook in rules["playbooks"].items():
            for tool_id in playbook.get("baseTools") or []:
                spec = registry.get(tool_id)
                self.assertIsNotNone(spec, f"{name} -> {tool_id}")
                self.assertFalse(spec.planned, f"{name} -> {tool_id} planned")
                self.assertFalse(spec.deprecated, f"{name} -> {tool_id} deprecated")
            for section in playbook.get("ruleSections") or []:
                self.assertIn(section, rules, f"{name} -> {section}")

    def test_consistency_check_catches_planted_bad_references(self):
        bad_rules = copy.deepcopy(rulepack.load_rules(RULES))
        bad_rules["playbooks"]["media.organize"]["baseTools"].append("no.such.tool")
        bad_rules["playbooks"]["media.organize"]["ruleSections"].append("noSuchSection")
        bad_rules["platform"]["platformVersion"] = "0.0.0"
        bad_rules["permissions"]["toolPermissions"].pop("local.device", None)
        bad_rules["toolCatalog"]["tools"].append({"id": "no.such.tool"})
        extra_refs = {"no.such.tool": ("permissions",),
                      "author.query": ("noSuchSection",)}
        extra_examples = {"author.query": ({"page": "not-an-integer"},)}
        extra_domains = {"media": ("noSuchSection",)}
        with mock.patch.dict(registry.TOOL_RULES_REFS, extra_refs), \
                mock.patch.dict(registry.TOOL_EXAMPLES, extra_examples), \
                mock.patch.dict(registry.DOMAIN_RULE_SECTIONS, extra_domains):
            problems = rulepack.consistency_problems(bad_rules, None)
        codes = {item["code"] for item in problems if item["severity"] == "error"}
        for expected in ("RULE_REF_UNKNOWN_TOOL", "RULE_REF_UNKNOWN_SECTION",
                         "EXAMPLE_SCHEMA_MISMATCH", "PLAYBOOK_UNKNOWN_TOOL",
                         "PLAYBOOK_UNKNOWN_SECTION", "DOMAIN_RULE_SECTION_MISSING",
                         "TOOLCATALOG_UNKNOWN_TOOL", "PERMISSION_UNDESCRIBED",
                         "VERSION_MISMATCH"):
            self.assertIn(expected, codes)
        # 每条问题都有稳定定位
        for item in problems:
            self.assertTrue(item["path"])
            self.assertIn(item["severity"], ("error", "warning", "info"))


class CliMcpParityTest(unittest.TestCase):
    def test_cli_and_mcp_share_registry_and_catalog(self):
        env = local_env()
        cli_list = run_cli(["tools", "list", "--json"], env=env)
        self.assertEqual(0, cli_list.returncode, cli_list.stderr)
        cli_ids = [tool["id"] for tool in json.loads(cli_list.stdout)["tools"]]

        server = mcp_server.McpServer(stdin=io.StringIO(), stdout=io.StringIO(), env=env)
        listing = server.handle_message(
            {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, set())
        mcp_ids = [tool["name"] for tool in listing["result"]["tools"]]
        self.assertEqual(cli_ids, mcp_ids)
        self.assertEqual([spec.id for spec in registry.TOOLS], cli_ids)

        cli_call = run_cli(["tool", "call", "platform.catalog", "--input-json", "{}"],
                           env=env)
        self.assertEqual(0, cli_call.returncode, cli_call.stderr)
        cli_result = json.loads(cli_call.stdout)["result"]
        mcp_call = server.handle_message(
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
             "params": {"name": "platform.catalog", "arguments": {}}}, set())
        mcp_result = mcp_call["result"]["structuredContent"]["result"]
        self.assertEqual(cli_result["toolCount"], mcp_result["toolCount"])
        self.assertEqual(cli_result["rulepackVersion"], mcp_result["rulepackVersion"])


class FilesContractTest(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory(prefix="qm-files-contract-")
        self.root = Path(self._temp.name)
        _temp_tree(self.root)
        self.env = local_env(QIMENG_AI_STORAGES="outer=" + str(self.root))

    def tearDown(self):
        self._temp.cleanup()

    def test_files_storages_unavailable_without_fileref(self):
        with mock.patch.object(tools_files, "_fileref_module", lambda: None):
            result = tools_files.files_storages(_Ctx({}, env={}), {})
            self.assertFalse(result["available"])
            self.assertEqual([], result["storages"])
            self.assertIn("hint", result)
            search = tools_files.files_search(_Ctx({}, env={}), {})
            self.assertFalse(search["available"])
            self.assertEqual([], search["files"])
            self.assertIn("hint", search)

    def test_files_storages_without_configuration_returns_hint(self):
        result = tools_files.files_storages(_Ctx({}, env={}), {})
        self.assertFalse(result["configured"])
        self.assertEqual([], result["storages"])
        self.assertIn("hint", result)
        search = tools_files.files_search(_Ctx({}, env={}), {})
        self.assertFalse(search["available"])
        self.assertEqual([], search["files"])
        self.assertIn("hint", search)

    def test_files_search_contract_with_fake_fileref(self):
        roots = {"outer": self.root, "inner": self.root / "sub"}
        with FakeFilerefInjection(roots):
            ctx = _Ctx({}, env=self.env)
            listing = tools_files.files_storages(ctx, {})
            self.assertTrue(listing["available"])
            self.assertTrue(listing["configured"])
            self.assertEqual({"outer", "inner"},
                             {row["storageId"] for row in listing["storages"]})
            self.assertNotIn(str(self.root), json.dumps(listing, ensure_ascii=False))

            first = tools_files.files_search(
                ctx, {"storageId": "outer", "limit": 2, "recursive": True})
            self.assertTrue(first["available"])
            self.assertEqual(2, first["count"])
            self.assertTrue(first["truncated"])
            self.assertIsNotNone(first["nextCursor"])
            self.assertNotIn(str(self.root), json.dumps(first, ensure_ascii=False))
            for item in first["files"]:
                self.assertEqual({"fileRef", "name", "storageId", "relativePath",
                                  "mediaType", "size", "modifiedAt"}, set(item))
                self.assertTrue(item["fileRef"])
                self.assertFalse(item["relativePath"].startswith("/"))
                self.assertNotIn(":", item["relativePath"])
                self.assertNotIn(str(self.root), json.dumps(item, ensure_ascii=False))

            second = tools_files.files_search(
                ctx, {"storageId": "outer", "limit": 2,
                      "cursor": first["nextCursor"]})
            self.assertFalse(second["truncated"])
            self.assertIsNone(second["nextCursor"])
            combined = {item["relativePath"] for item in first["files"] + second["files"]}
            self.assertEqual({"a.jpg", "b.txt", "c.png"}, combined)
            # 嵌套 storage：sub/c.png 的 fileRef 元数据指向最具体 storage
            nested = next(item for item in second["files"]
                          if item["relativePath"] == "c.png")
            self.assertEqual("inner", nested["storageId"])

            images = tools_files.files_search(
                ctx, {"storageId": "outer", "extensions": ["jpg"]})
            self.assertEqual(["a.jpg"], [item["name"] for item in images["files"]])
            videos = tools_files.files_search(ctx, {"storageId": "outer",
                                                    "mediaType": "video"})
            self.assertEqual(0, videos["count"])
            shallow = tools_files.files_search(
                ctx, {"storageId": "outer", "maxDepth": 0})
            self.assertNotIn("sub/c.png",
                             [item["relativePath"] for item in shallow["files"]])
            hidden = tools_files.files_search(
                ctx, {"storageId": "outer", "includeHidden": True})
            self.assertIn(".hidden.jpg",
                          [item["name"] for item in hidden["files"]])

            # 符号链接（Windows 无权限时跳过该断言）：不得被跟随/返回
            link = self.root / "link.jpg"
            try:
                link.symlink_to(self.root / "a.jpg")
            except (OSError, NotImplementedError):
                link = None
            if link is not None:
                linked = tools_files.files_search(ctx, {"storageId": "outer"})
                self.assertNotIn("link.jpg",
                                 [item["name"] for item in linked["files"]])

            with self.assertRaises(errors.ToolError) as caught:
                tools_files.files_search(ctx, {"storageId": "no-such-storage"})
            self.assertEqual("INVALID_INPUT", caught.exception.code)

            for bad in ("!!!", "abc", tools_files._encode_cursor("inner", "a.jpg")):
                with self.assertRaises(errors.ToolError) as caught:
                    tools_files.files_search(ctx, {"storageId": "outer", "cursor": bad})
                self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_scan_limit_is_reported_as_truncated(self):
        for index in range(4):
            (self.root / f"extra-{index}.jpg").write_bytes(b"x")
        with FakeFilerefInjection({"outer": self.root}), \
                mock.patch.object(tools_files, "MAX_SCAN_ENTRIES", 2):
            result = tools_files.files_search(_Ctx({}, env=self.env),
                                              {"storageId": "outer", "limit": 50})
        self.assertTrue(result["truncated"])
        self.assertTrue(result["scanned"]["scanLimitReached"])
        self.assertIn("hint", result)

    @unittest.skipUnless(HAS_FILEREF, "fileref.py 尚未提供（并行子代理）")
    def test_files_search_with_real_fileref_uses_storage_as_read_authorization(self):
        # 只配置 QIMENG_AI_STORAGES、不配置 READ_ROOTS/MEDIA_ROOTS 也应可用。
        env = local_env(QIMENG_AI_STORAGES="inbox=" + str(self.root))
        config = execution.load_config(env)
        _spec, listing = execution.execute_tool("files.storages", {}, env=env,
                                                config=config)
        self.assertTrue(listing["available"])
        self.assertEqual(["inbox"], [row["storageId"] for row in listing["storages"]])
        _spec, result = execution.execute_tool(
            "files.search", {"storageId": "inbox", "limit": 10}, env=env, config=config)
        self.assertTrue(result["available"])
        self.assertGreaterEqual(result["count"], 3)
        self.assertNotIn(str(self.root), json.dumps(result, ensure_ascii=False))
        self.assertTrue(all(item["fileRef"].startswith("file_")
                            for item in result["files"]))
        # 真实 fileref 生成的引用可被引用层解析
        from ai_maintenance import fileref
        resolved = fileref.resolve_ref(result["files"][0]["fileRef"], env=env)
        self.assertTrue(Path(resolved).is_file())


class BoundedOutputTest(unittest.TestCase):
    def test_context_and_catalog_are_bounded(self):
        config = local_config()
        _spec, context = execution.execute_tool(
            "platform.context", {"task": "media.organize"}, config=config)
        _spec, catalog_payload = execution.execute_tool(
            "platform.catalog", {}, config=config)
        full = execution.tool_listing(config)
        context_chars = len(json.dumps(context, ensure_ascii=False))
        catalog_chars = len(json.dumps(catalog_payload, ensure_ascii=False))
        full_chars = len(json.dumps(full, ensure_ascii=False))
        self.assertLess(context_chars, 60000)
        self.assertLess(catalog_chars, 60000)
        self.assertLess(catalog_chars * 2, full_chars)

    def test_rules_total_limit_marks_truncated(self):
        rules = {"permissions": {}, "a": "x" * 9000, "b": "y" * 9000,
                 "c": "z" * 9000}
        payload = catalog.platform_rules(_Ctx(rules), {"sections": ["a", "b", "c"]})
        self.assertTrue(payload["truncated"])
        self.assertTrue(payload["truncatedSections"])
        self.assertLessEqual(len(json.dumps(payload["sections"], ensure_ascii=False)),
                             catalog.RULES_TOTAL_LIMIT + 2000)


class VersionConsistencyTest(unittest.TestCase):
    def test_versions_match_between_code_and_rules(self):
        rules = rulepack.load_rules(RULES)
        self.assertEqual(versions.PLATFORM_VERSION, rules["platform"]["platformVersion"])
        self.assertEqual(versions.TOOL_CONTRACT_VERSION,
                         rules["platform"]["toolContractVersion"])
        self.assertEqual(versions.PLAN_VERSION, rules["platform"]["planVersion"])
        self.assertEqual(rules["rulepackVersion"], rules["rulesVersion"])
        self.assertTrue(rules["toolCatalog"]["version"])
        self.assertEqual(registry.TOOL_CONTRACT_VERSION,
                         versions.TOOL_CONTRACT_VERSION)


if __name__ == "__main__":
    unittest.main()
