"""2026-11-10 批次回归：规则漂移、platform.context 与 media.organize 内容扫描。

全部使用合成数据/临时目录，不连接真实 8420、不读真实媒体库。
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import shutil
import subprocess
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from ai_maintenance import (cli, errors, execution, media_scan, openapi,
                             registry, rulepack, tools_media)
from ai_maintenance.runtime import ToolContext

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "api" / "openapi.yaml"
RULES = ROOT / "tools" / "ai_maintenance" / "rules.json"
TEMP_ROOT = ROOT / "tools" / "ai_maintenance" / ".test-temp"
try:
    TEMP_ROOT.mkdir(parents=True, exist_ok=True)
except OSError:
    TEMP_ROOT = Path(__file__).resolve().parent
TOKEN = "synthetic-20261110-token"


def local_config(env=None):
    env = dict(env or {})
    env.setdefault("QIMENG_AI_OPENAPI", str(OPENAPI))
    env.setdefault("QIMENG_AI_RULES", str(RULES))
    return execution.load_config(env)


def temp_dir(prefix: str = "platform-20261110-"):
    path = TEMP_ROOT / (prefix + uuid.uuid4().hex)
    path.mkdir(parents=True)
    return path


@contextlib.contextmanager
def temp_dir_context(prefix: str = "platform-20261110-"):
    path = temp_dir(prefix)
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


def run_cli(argv, env=None):
    stdout, stderr = io.StringIO(), io.StringIO()
    with patch.dict(os.environ, env or {}, clear=False):
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = cli.main(argv)
    return status, stdout.getvalue(), stderr.getvalue()


class RulepackCapabilityTest(unittest.TestCase):
    def test_capability_split_and_new_capabilities(self):
        pack = rulepack.build_rulepack()
        for group in ("read", "write"):
            for name, entry in pack["capabilities"][group].items():
                self.assertIn("implementationStatus", entry, name)
                self.assertIn("apiStatus", entry, name)
        maintenance = pack["capabilities"]["read"]["maintenance.jobs"]
        self.assertEqual("api-only", maintenance["implementationStatus"])
        self.assertEqual("available", maintenance["apiStatus"])
        self.assertTrue(maintenance["methodsPresent"])
        self.assertEqual(6, len(maintenance["checks"]))
        for name in ("device.apply.claim", "device.apply.segments",
                     "device.apply.result", "device.apply.cancel"):
            entry = pack["capabilities"]["write"][name]
            self.assertEqual("api-only", entry["implementationStatus"], name)
            self.assertEqual("available", entry["apiStatus"], name)
            self.assertTrue(entry["endpointPresent"], name)
            self.assertTrue(entry["methodsPresent"], name)
        author_delete = pack["capabilities"]["write"]["author.delete"]
        self.assertEqual("planned", author_delete["implementationStatus"])
        self.assertEqual("available", author_delete["apiStatus"])
        profile = pack["capabilities"]["write"]["author.profile.update"]
        self.assertEqual("implemented", profile["implementationStatus"])
        self.assertEqual("available", profile["apiStatus"])
        self.assertEqual("2026-11-12.1", pack["rulepackVersion"])
        self.assertIn("mediaOrganize", pack)
        self.assertEqual(["media.organize.prepare", "media.organize.apply"],
                         pack["mediaOrganize"]["tools"])

    def test_delete_method_and_required_field_drift_are_detected(self):
        text = (
            "openapi: 3.0.0\n"
            "components:\n"
            "  schemas:\n"
            "    Thing:\n"
            "      type: object\n"
            "      required: [name]\n"
            "      properties:\n"
            "        name: { type: string }\n"
            "paths:\n"
            "  /api/v1/authors/{authorId}:\n"
            "    put:\n"
            "      requestBody:\n"
            "        required: true\n"
            "        content:\n"
            "          application/json:\n"
            "            schema: { $ref: '#/components/schemas/Thing' }\n"
        )
        document = openapi.parse(text)
        missing_method = rulepack._check_entry(
            {"endpoint": "DELETE /api/v1/authors/{authorId}", "status": "api-only"},
            document)
        self.assertEqual("unavailable", missing_method["apiStatus"])
        self.assertEqual("unavailable", missing_method["status"])
        self.assertFalse(missing_method["methodsPresent"])
        ok = rulepack._check_entry(
            {"endpoints": [{"method": "PUT", "path": "/api/v1/authors/{authorId}",
                            "requestSchema": "Thing", "requiredFields": ["name"]}],
             "status": "api-only"},
            document)
        self.assertEqual("available", ok["apiStatus"])
        missing_field = rulepack._check_entry(
            {"endpoints": [{"method": "PUT", "path": "/api/v1/authors/{authorId}",
                            "requestSchema": "Thing", "requiredFields": ["absent"]}],
             "status": "api-only"},
            document)
        self.assertEqual("incompatible", missing_field["apiStatus"])
        self.assertTrue(any(check["code"] == "required_field_missing"
                            for check in missing_field["checks"]))
        malformed = rulepack._check_entry(
            {"endpoint": "/api/v1/authors/{authorId}", "status": "api-only"}, document)
        self.assertEqual("incompatible", malformed["apiStatus"])
        self.assertEqual("endpoint_invalid", malformed["apiStatusReason"])


class PlatformContextTest(unittest.TestCase):
    def _context(self, payload, env=None):
        config = local_config(env)
        _spec, result = execution.execute_tool("platform.context", payload, config=config)
        return result

    def test_media_organize_context_is_scoped(self):
        result = self._context({"task": "media.organize"})
        ids = [item["id"] for item in result["tools"]]
        self.assertIn("media.organize.prepare", ids)
        self.assertIn("media.organize.apply", ids)
        self.assertLess(len(ids), len(registry.TOOLS))
        self.assertNotIn("capabilities", result)
        self.assertIn("mediaOrganize", result["rules"])
        self.assertEqual("media.organize", result["playbook"]["name"])
        self.assertEqual("playbook", result["task"]["matchedBy"])
        for item in result["tools"]:
            self.assertIn("permitted", item)
            self.assertNotIn("inputSchema", item)
            self.assertIn("schemaSummary", item)
        by_id = {item["id"]: item for item in result["tools"]}
        self.assertEqual("mediaOrganize", by_id["media.organize.prepare"]["rulesRefs"][0])

    def test_backup_sync_alias_and_max_tools(self):
        result = self._context({"task": "backup.sync", "includeSchemas": False,
                                "maxTools": 2})
        ids = [item["id"] for item in result["tools"]]
        self.assertEqual(["backup.export", "device.apply.prepare"], ids)
        self.assertTrue(result["task"]["truncatedTools"])
        self.assertEqual("device.apply", result["playbook"]["name"])
        self.assertIn("backup", result["rules"])
        self.assertIn("deviceApply", result["rules"])
        for item in result["tools"]:
            self.assertNotIn("schemaSummary", item)

    def test_unknown_or_missing_task_is_invalid(self):
        with self.assertRaises(errors.ToolError) as caught:
            self._context({"task": "no.such.task"})
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            self._context({})
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            self._context({"domain": "no-such-domain"})
        self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_permission_flags_follow_granted_permissions(self):
        env = {"QIMENG_AI_GRANTED_PERMISSIONS": "read"}
        result = self._context({"task": "media.organize"}, env=env)
        by_id = {item["id"]: item for item in result["tools"]}
        self.assertTrue(by_id["asset.query"]["permitted"])
        self.assertFalse(by_id["media.organize.prepare"]["permitted"])
        self.assertFalse(by_id["media.organize.apply"]["permitted"])
        self.assertEqual(["read"], result["permissions"]["granted"])

    def test_cli_and_mcp_share_the_same_context_path(self):
        env = {"QIMENG_AI_OPENAPI": str(OPENAPI), "QIMENG_AI_RULES": str(RULES)}
        status, stdout, stderr = run_cli(
            ["task-context", "--task", "media.organize", "--json",
             "--openapi", str(OPENAPI), "--rules", str(RULES)], env=env)
        self.assertEqual(0, status, stderr)
        cli_result = json.loads(stdout)
        cli_ids = [item["id"] for item in cli_result["tools"]]
        mcp = __import__("ai_maintenance.mcp_server", fromlist=["McpServer"]).McpServer(
            stdin=io.StringIO(), stdout=io.StringIO(), env=env)
        response = mcp.handle_message(
            {"jsonrpc": "2.0", "id": 7, "method": "tools/call",
             "params": {"name": "platform.context",
                        "arguments": {"task": "media.organize"}}}, set())
        payload = response["result"]["structuredContent"]["result"]
        self.assertEqual(cli_ids, [item["id"] for item in payload["tools"]])
        self.assertEqual(cli_result["rulepackVersion"], payload["rulepackVersion"])


class MediaScanTest(unittest.TestCase):
    def _env(self, roots):
        return {
            "QIMENG_API_BASE": "http://127.0.0.1:1",
            "QIMENG_API_TOKEN": TOKEN,
            "QIMENG_AI_OPENAPI": str(OPENAPI),
            "QIMENG_AI_RULES": str(RULES),
            "QIMENG_AI_MEDIA_ROOTS": ",".join(str(item) for item in roots),
        }

    def _prepare(self, env, payload):
        config = execution.load_config(env)
        _spec, result = execution.execute_tool(
            "media.organize.prepare", payload, env=env, config=config)
        return result

    def test_scan_aligns_content_dedupes_largest_and_continues_sequence(self):
        with temp_dir_context() as root, temp_dir_context() as target:
            (root / "a.png").write_bytes(b"A" * 400)
            (root / "b copy.png").write_bytes(b"A" * 400)
            (root / "variant NSFW.png").write_bytes(b"N" * 300)
            (root / "seq 1 (1).png").write_bytes(b"1" * 100)
            (root / "seq 1 (2).png").write_bytes(b"2" * 100)
            (root / "seq 1 (3).png").write_bytes(b"3" * 100)
            (root / "seq 1.png").write_bytes(b"4" * 100)
            # DB/TXT 等非媒体文件只读清点，不进入去重/命名候选。
            (root / "authors.txt").write_bytes(b"author-table")
            (root / "authors copy.txt").write_bytes(b"author-table")
            (target / "a.png").write_bytes(b"A" * 400)
            (target / "c.png").write_bytes(b"Z" * 100)
            env = self._env([root, target])
            before = sorted(item.name for item in root.iterdir())
            first = self._prepare(env, {
                "scan": {"root": str(root), "targetRoot": str(target),
                         "perceptualLimit": 0}})
            second = self._prepare(env, {
                "scan": {"root": str(root), "targetRoot": str(target),
                         "perceptualLimit": 0}})
            plan = first["plan"]
            self.assertEqual("needs_confirmation", plan["status"])
            self.assertEqual(first["plan"]["planFingerprint"],
                             second["plan"]["planFingerprint"])
            self.assertEqual(before, sorted(item.name for item in root.iterdir()))
            duplicates = plan["duplicates"]
            self.assertEqual(1, len(duplicates))
            self.assertEqual("a.png", duplicates[0]["keep"]["fileName"])
            self.assertEqual(["b copy.png"],
                             [item["fileName"] for item in duplicates[0]["removeCandidates"]])
            self.assertFalse(any(item["fileName"].endswith(".txt")
                                 for group in duplicates
                                 for item in [group["keep"], *group["removeCandidates"]]))
            sequence = next(item for item in plan["sequences"]
                            if item["baseKey"] == "seq 1")
            self.assertEqual([1, 2, 3], sequence["pages"])
            self.assertEqual(4, sequence["nextPage"])
            self.assertEqual(4, sequence["suggestedPages"]["seq 1.png"])
            alignment = plan["alignment"]
            self.assertTrue(alignment["mergeAllowed"])
            self.assertEqual(1, alignment["onlyInTargetCount"])
            self.assertEqual("target_to_source",
                             alignment["suggestedCopies"][0]["direction"])
            self.assertEqual("c.png",
                             alignment["suggestedCopies"][0]["file"]["fileName"])
            self.assertEqual("planned", plan["apply"]["status"])

    def test_alignment_conflict_blocks_bulk_merge_and_keeps_largest(self):
        with temp_dir_context() as source, temp_dir_context() as target:
            (source / "a.png").write_bytes(b"A" * 400)
            (source / "b.png").write_bytes(b"B" * 10)
            (target / "a.png").write_bytes(b"X" * 100)
            (target / "b.png").write_bytes(b"B" * 10)
            (target / "c.png").write_bytes(b"C" * 10)
            env = self._env([source, target])
            plan = self._prepare(env, {
                "scan": {"root": str(source), "targetRoot": str(target),
                         "perceptualLimit": 0}})["plan"]
            alignment = plan["alignment"]
            self.assertFalse(alignment["mergeAllowed"])
            self.assertEqual("blocked", plan["status"])
            self.assertEqual(1, alignment["conflictCount"])
            self.assertEqual("source", alignment["conflicts"][0]["keep"])
            self.assertEqual([], alignment["suggestedCopies"])
            self.assertTrue(alignment["blockedCopies"])

    def test_exact_duplicate_across_variants_is_flagged_not_silently_merged(self):
        files = [
            {"relPath": "x SFW.png", "fileName": "x SFW.png", "sizeBytes": 10,
             "mediaType": "image", "md5": "0" * 32,
             "variantSignature": ["sfw"], "pageNumber": None},
            {"relPath": "x NSFW.png", "fileName": "x NSFW.png", "sizeBytes": 10,
             "mediaType": "image", "md5": "0" * 32,
             "variantSignature": ["nsfw"], "pageNumber": None},
        ]
        groups = media_scan._duplicate_groups(files, "exact")
        self.assertEqual(1, len(groups))
        self.assertTrue(groups[0]["variantMismatch"])
        self.assertTrue(groups[0]["requiresConfirmation"])

    def test_variant_tokens_prevent_perceptual_merges(self):
        base = {
            "mediaType": "image", "dimensions": {"width": 100, "height": 100},
            "perceptual": {"frames": ["0000000000000000"]},
            "variantSignature": ["sfw"], "sizeBytes": 10,
        }
        other = dict(base)
        other["variantSignature"] = ["nsfw"]
        self.assertEqual((False, 64), media_scan._perceptual_similar(base, other))
        same = dict(base)
        same["perceptual"] = {"frames": ["0000000000000000"]}
        self.assertEqual((True, 0), media_scan._perceptual_similar(base, same))
        self.assertIn("sfw", media_scan.variant_signature("x SFW.png"))
        self.assertIn("v2", media_scan.variant_signature("x v2.png"))
        self.assertIn("4k", media_scan.variant_signature("x 4K.png"))

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"),
                         "ffmpeg/ffprobe 未安装，跳过感知哈希集成用例")
    def test_perceptual_hash_detects_same_image(self):
        with temp_dir_context() as root:
            source = root / "src.png"
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi",
                            "-i", "testsrc=size=64x64:duration=1",
                            "-frames:v", "1", str(source)], check=True)
            shutil.copy(source, root / "copy.png")
            env = self._env([root])
            result = media_scan.scan_directory(str(root), env=env, perceptual_limit=5)
            hashes = [item["perceptual"]["frames"][0] for item in result["files"]]
            self.assertEqual(2, len(hashes))
            self.assertEqual(hashes[0], hashes[1])
            groups = media_scan._duplicate_groups(result["files"], "perceptual")
            self.assertTrue(any(group["reason"] == "exact-md5" for group in groups))

    def test_choose_keeper_prefers_largest(self):
        small = {"fileName": "small.png", "sizeBytes": 10, "relPath": "a/small.png"}
        large = {"fileName": "large.png", "sizeBytes": 20, "relPath": "a/large.png"}
        self.assertIs(large, media_scan.choose_keeper([small, large]))

    def test_outside_authorized_root_is_rejected(self):
        import tempfile
        with temp_dir_context() as authorized, tempfile.TemporaryDirectory() as outside:
            Path(outside, "x.png").write_bytes(b"x")
            env = self._env([authorized])
            config = execution.load_config(env)
            with self.assertRaises(errors.ToolError) as caught:
                execution.execute_tool(
                    "media.organize.prepare",
                    {"scan": {"root": outside, "perceptualLimit": 0}},
                    env=env, config=config)
            # 未授权路径按本地执行域统一策略返回更精确的 permission 类错误。
            self.assertEqual("PATH_NOT_AUTHORIZED", caught.exception.code)

    def test_scan_can_read_library_root_path(self):
        with temp_dir_context() as root:
            (root / "x.png").write_bytes(b"x")
            env = self._env([root])

            class FakeClient:
                def get(self, path, query=None):
                    self.last = (path, query)
                    return [{"id": "lib-1", "name": "合成库", "rootPath": str(root)}]

            ctx = ToolContext(client=FakeClient(), env=env, rules={"mediaOrganize": {}})
            result = tools_media.media_organize_prepare(
                ctx, {"scan": {"libraryId": "lib-1", "perceptualLimit": 0}})
            self.assertEqual("content-scan", result["diagnostics"]["mode"])
            self.assertEqual("lib-1", result["plan"]["roots"]["source"]["libraryId"])
            self.assertEqual(1, result["plan"]["impact"]["sourceFileCount"])

    def test_apply_rejects_local_scan_plan(self):
        with temp_dir_context() as root:
            (root / "x.png").write_bytes(b"x")
            env = self._env([root])
            plan = self._prepare(env, {"scan": {"root": str(root), "perceptualLimit": 0}})["plan"]
            config = execution.load_config(env)
            with self.assertRaises(errors.ToolError) as caught:
                execution.execute_tool("media.organize.apply",
                                       {"plan": plan, "confirm": True},
                                       env=env, config=config)
            self.assertEqual("TOOL_NOT_IMPLEMENTED", caught.exception.code)


if __name__ == "__main__":
    unittest.main()
