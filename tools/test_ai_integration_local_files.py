"""主代理集成验收：files.search → fileRef → 本地扫描/哈希 → recipe 复用。

只使用隔离临时目录与合成媒体；不联网、不写业务库、不触碰真实媒体。
覆盖三个跨模块契约：
1. files.search 只返回逻辑引用与相对路径，不泄露存储根；
2. fileRef 可被本地原语与 recipe 复用，文件变化后按 FILE_REF_STALE 失效；
3. MCP 领域过滤保留发现元工具，且不把 files.search 暴露给无关领域。
"""
from __future__ import annotations

import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from ai_maintenance import execution, fileref, mcp_server, recipes  # noqa: E402

OPENAPI = ROOT / "api" / "openapi.yaml"
RULES = ROOT / "tools" / "ai_maintenance" / "rules.json"


def _ffmpeg() -> str:
    from ai_maintenance import media_primitives
    return media_primitives.find_ffmpeg()


def _make_video(path: Path) -> bool:
    ffmpeg = _ffmpeg()
    if not ffmpeg:
        return False
    subprocess.run(
        [ffmpeg, "-v", "error", "-y", "-f", "lavfi",
         "-i", "testsrc=size=160x120:rate=10:duration=1", "-pix_fmt", "yuv420p",
         str(path)], check=True)
    return path.is_file()


class LocalFileRefIntegrationTest(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory(prefix="qimeng-integration-")
        self.addCleanup(self._temp.cleanup)
        self.temp = Path(self._temp.name)
        self.storage = self.temp / "storage"
        target = self.storage / "2026" / "11" / "batchA" / "target.mp4"
        target.parent.mkdir(parents=True)
        self.has_video = _make_video(target)
        if not self.has_video:
            target.write_bytes(b"synthetic-fallback" * 32)
        (self.storage / "2026" / "11" / "batchB").mkdir(parents=True)
        (self.storage / "2026" / "11" / "batchB" / "target.mp4").write_bytes(
            b"distractor" * 32)
        for index in range(6):
            (self.storage / "misc").mkdir(exist_ok=True)
            (self.storage / "misc" / f"asset-{index}.png").write_bytes(
                f"synthetic-{index}".encode("ascii") * 8)
        self.target = target
        self.env = {
            "QIMENG_AI_OPENAPI": str(OPENAPI),
            "QIMENG_AI_RULES": str(RULES),
            "QIMENG_AI_STORAGES": f"downloads={self.storage}",
            "QIMENG_AI_WRITE_ROOTS": str(self.storage),
            "QIMENG_AI_MEDIA_CACHE": str(self.temp / "cache"),
        }
        self.config = execution.load_config(self.env)

    def _search(self, payload):
        _spec, result = execution.execute_tool(
            "files.search", payload, env=self.env, config=self.config)
        return result

    def test_search_returns_refs_without_absolute_paths(self):
        result = self._search({"storageId": "downloads", "query": "batchA/target",
                               "limit": 5})
        encoded = json.dumps(result, ensure_ascii=False)
        self.assertEqual(1, result["count"])
        self.assertNotIn(str(self.storage), encoded)
        item = result["files"][0]
        self.assertTrue(item["fileRef"].startswith("file_"))
        self.assertEqual("2026/11/batchA/target.mp4", item["relativePath"])
        self.assertEqual("video", item["mediaType"])
        self.assertGreater(item["size"], 0)

    def test_cursor_pagination_is_bounded_and_has_no_duplicates(self):
        first = self._search({"storageId": "downloads", "limit": 2, "query": "asset-"})
        self.assertEqual(2, first["count"])
        self.assertTrue(first["truncated"])
        self.assertTrue(first["nextCursor"])
        seen = [item["relativePath"] for item in first["files"]]
        cursor = first["nextCursor"]
        while cursor:
            page = self._search({"storageId": "downloads", "limit": 2,
                                 "cursor": cursor, "query": "asset-"})
            seen.extend(item["relativePath"] for item in page["files"])
            cursor = page["nextCursor"] if page["truncated"] else None
        self.assertEqual(6, len(seen))
        self.assertEqual(6, len(set(seen)))

    def test_file_ref_reused_by_primitives_and_recipe(self):
        result = self._search({"storageId": "downloads", "query": "batchA/target"})
        reference = result["files"][0]["fileRef"]
        ctx = recipes.RunContext(self.env, self.temp / "run")
        probe = recipes._primitive_media_probe(
            {"fileRef": reference, "kind": "video"}, ctx)
        digest = recipes._primitive_media_fingerprint(
            {"fileRef": reference, "algorithms": ["md5", "sha256"]}, ctx)
        encoded = json.dumps({"probe": probe, "hash": digest}, ensure_ascii=False)
        self.assertNotIn(str(self.storage), encoded)
        self.assertEqual(reference, probe["fileRef"])
        self.assertEqual(reference, digest["fileRef"])
        if self.has_video:
            self.assertEqual("ok", probe["status"])
        self.assertEqual(32, len(digest["md5"]))
        self.assertEqual(64, len(digest["sha256"]))

        recipe_dir = self.temp / "recipes"
        recipe_dir.mkdir()
        recipe = {
            "schemaVersion": 1, "id": "test.integration-ref", "version": "1.0.0",
            "title": "集成 fileRef", "whenToUse": "集成测试", "requires": [],
            "inputs": {"type": "object",
                       "properties": {"fileRef": {"type": "string"}},
                       "required": ["fileRef"]},
            "permissions": ["read"],
            "steps": [
                {"id": "probe", "primitive": "media.probe",
                 "input": {"fileRef": "$inputs.fileRef", "kind": "video"}},
                {"id": "hash", "primitive": "file.hash",
                 "input": {"fileRef": "$steps.probe.output.fileRef",
                           "algorithms": ["md5", "sha256"]}},
            ],
            "artifacts": ["probe", "hash"], "readback": [],
        }
        (recipe_dir / "test.integration-ref.json").write_text(
            json.dumps(recipe, ensure_ascii=False), encoding="utf-8")
        env = dict(self.env)
        env["QIMENG_AI_RECIPE_DIR"] = str(recipe_dir)
        run_dir = self.temp / "run"
        result = recipes.run_recipe(
            "test.integration-ref", {"fileRef": reference}, env=env,
            state_dir=str(run_dir))
        encoded = json.dumps(result, ensure_ascii=False)
        self.assertNotIn(str(self.storage), encoded)
        self.assertEqual("completed", result["status"])
        self.assertEqual(["ok", "ok"], [step["status"] for step in result["steps"]])
        self.assertTrue(all(len(item["sha256"]) == 64 for item in result["artifacts"]))
        # 恢复执行：校验产物 sha256 后直接复用已完成步骤，不重复副作用。
        resumed = recipes.run_recipe(
            "test.integration-ref", {"fileRef": reference}, env=env,
            state_dir=str(run_dir), resume=True)
        self.assertEqual("completed", resumed["status"])

    def test_research_media_probe_accepts_file_ref_without_path_leak(self):
        script = ROOT / "tools" / "research_kit" / "media_probe.py"
        if not self.has_video or not script.is_file() or not shutil.which("ffprobe"):
            raise unittest.SkipTest("research_kit media_probe/ffprobe unavailable")
        result = self._search({"storageId": "downloads", "query": "batchA/target"})
        reference = result["files"][0]["fileRef"]
        env = dict(os.environ)
        env.update(self.env)
        env["QIMENG_AI_RESEARCH_DIR"] = str(self.temp / "research")
        config = execution.load_config(env)
        _spec, probe = execution.execute_tool(
            "research.media.probe", {"fileRefs": [reference]}, env=env, config=config)
        encoded = json.dumps(probe, ensure_ascii=False)
        self.assertEqual(0, probe["exitCode"], probe["summary"].get("stderrTail"))
        self.assertNotIn(str(self.storage), encoded)
        self.assertIn("<fileRef:1>", probe["summary"]["args"])
        self.assertTrue(probe["artifacts"])

    def test_file_ref_goes_stale_after_replace(self):
        result = self._search({"storageId": "downloads", "query": "batchA/target"})
        reference = result["files"][0]["fileRef"]
        self.target.write_bytes(b"replaced-content" * 64)
        with self.assertRaises(Exception) as caught:
            fileref.resolve_ref(reference, env=self.env)
        self.assertEqual("FILE_REF_STALE", getattr(caught.exception, "code", ""))
        fresh = self._search({"storageId": "downloads", "query": "batchA/target"})
        self.assertNotEqual(reference, fresh["files"][0]["fileRef"])

    def test_mcp_domain_filter_keeps_discovery_meta_tools(self):
        media = mcp_server.McpServer(env={**self.env, "QIMENG_AI_MCP_DOMAINS": "media"})
        ids = {spec.id for spec in media._visible_tools()}
        self.assertIn("files.search", ids)
        self.assertIn("files.storages", ids)
        self.assertIn("platform.catalog", ids)
        self.assertIn("platform.rules", ids)
        author = mcp_server.McpServer(env={**self.env, "QIMENG_AI_MCP_DOMAINS": "author"})
        author_ids = {spec.id for spec in author._visible_tools()}
        self.assertNotIn("files.search", author_ids)
        self.assertIn("files.storages", author_ids)
        self.assertIn("platform.rules", author_ids)
        response = author.handle_message(
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
             "params": {"name": "platform.rules",
                        "arguments": {"sections": ["localFiles"]}}}, set())
        self.assertIn("result", response)
        payload = response["result"]["structuredContent"]["result"]
        self.assertIn("localFiles", payload["sections"])
        search_response = author.handle_message(
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
             "params": {"name": "files.search",
                        "arguments": {"storageId": "downloads"}}}, set())
        self.assertEqual(-32602, search_response["error"]["code"])


if __name__ == "__main__":
    unittest.main()
