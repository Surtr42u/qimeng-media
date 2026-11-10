"""可执行 recipe / 原语 / author.create 回归（合成数据，不写真实库）。"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))

from ai_maintenance import (device_adb, errors, media_primitives, mcp_server,  # noqa: E402
                            pathguard, recipes, registry, targets, tools_author,
                            tools_query)


class _FakeClient:
    def __init__(self, authors=None, created=None):
        self.authors = authors or []
        self.created = created or {"id": "new-author"}
        self.posts = []

    def get(self, path, query=None):
        if path == "/api/v1/authors":
            return self.authors
        raise AssertionError("unexpected GET " + path)

    def post(self, path, json_body=None, **kwargs):
        self.posts.append((path, json_body))
        return self.created


class RecipeCatalogTests(unittest.TestCase):
    def test_all_recipes_validate_and_task_cards_are_bounded(self):
        items = recipes.list_recipes({})
        self.assertTrue(items)
        for item in items:
            self.assertEqual("ok", item["status"], item.get("error"))
            card = recipes.task_card(item["id"], {})
            encoded = json.dumps(card, ensure_ascii=False, separators=(",", ":"))
            self.assertLess(len(encoded), 5200, item["id"])
            step_ids = {step["id"] for step in card["steps"]}
            self.assertTrue(step_ids)

    def test_recipe_tool_references_are_registered(self):
        for item in recipes.list_recipes({}):
            recipe = recipes.get_recipe(item["id"], {})
            for step in recipe["steps"]:
                if "tool" in step:
                    self.assertIsNotNone(registry.get(step["tool"]), step["tool"])
                if "primitive" in step:
                    self.assertIn(step["primitive"], recipes.PRIMITIVES)

    def test_author_discover_declares_create_and_gate(self):
        recipe = recipes.get_recipe("author.discover", {})
        tools = [step.get("tool") for step in recipe["steps"]]
        self.assertIn("author.create", tools)
        self.assertIn("author.profile.update", tools)
        self.assertTrue(any(step.get("gate") for step in recipe["steps"]))


class RecipeRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.recipe_dir = Path(self.temp.name) / "recipes"
        self.recipe_dir.mkdir()
        self.recipe = {
            "schemaVersion": 1,
            "id": "test.flow",
            "version": "1.0.0",
            "title": "测试编排",
            "whenToUse": "测试",
            "requires": [],
            "inputs": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "doWrite": {"type": "boolean", "default": True},
                },
                "required": ["name"],
            },
            "steps": [
                {"id": "lookup", "tool": "author.query", "input": {"name": "$inputs.name"}},
                {"id": "gate", "gate": True, "message": "确认写入"},
                {
                    "id": "create",
                    "tool": "author.create",
                    "confirm": True,
                    "when": {"truthy": "$inputs.doWrite"},
                    "input": {"displayName": "$inputs.name", "confirm": True},
                },
            ],
        }
        (self.recipe_dir / "test.flow.json").write_text(
            json.dumps(self.recipe, ensure_ascii=False), encoding="utf-8")
        self.env = {"QIMENG_AI_RECIPE_DIR": str(self.recipe_dir)}
        self.calls = []

    def _fake_execute(self, tool_id, payload, env=None, config=None):
        self.calls.append((tool_id, payload))
        if tool_id == "author.query":
            return (None, [{"id": "a1", "displayName": payload.get("name")}])
        if tool_id == "author.create":
            return (None, {"status": "ok", "authorId": "a1"})
        raise AssertionError("unexpected tool " + tool_id)

    def test_gate_blocks_then_resumes_with_confirm(self):
        run_dir = Path(self.temp.name) / "run"
        with mock.patch.object(recipes.execution, "execute_tool", side_effect=self._fake_execute):
            with self.assertRaises(recipes.RecipeError) as caught:
                recipes.run_recipe("test.flow", {"name": "Alice"}, env=self.env,
                                   state_dir=str(run_dir))
            self.assertEqual("RECIPE_CONFIRMATION_REQUIRED", caught.exception.code)
            result = recipes.run_recipe(
                "test.flow", {"name": "Alice"}, env=self.env, state_dir=str(run_dir),
                confirm=True, resume=True)
        self.assertEqual("completed", result["status"])
        self.assertEqual(["author.query", "author.create"], [item[0] for item in self.calls])
        self.assertTrue((run_dir / "state.json").is_file())
        artifacts = list((run_dir / "artifacts").glob("*.json"))
        self.assertEqual(3, len(artifacts))  # lookup + gate + create

    def test_skipped_step_does_not_call_tool(self):
        run_dir = Path(self.temp.name) / "skip"
        with mock.patch.object(recipes.execution, "execute_tool", side_effect=self._fake_execute):
            result = recipes.run_recipe(
                "test.flow", {"name": "Alice", "doWrite": False}, env=self.env,
                state_dir=str(run_dir), confirm=True)
        self.assertEqual("completed", result["status"])
        self.assertEqual(["author.query"], [item[0] for item in self.calls])

    def test_dry_run_marks_planned_without_calls(self):
        run_dir = Path(self.temp.name) / "dry"
        with mock.patch.object(recipes.execution, "execute_tool", side_effect=self._fake_execute):
            result = recipes.run_recipe(
                "test.flow", {"name": "Alice"}, env=self.env, state_dir=str(run_dir),
                dry_run=True)
        self.assertEqual("completed", result["status"])
        self.assertEqual([], self.calls)
        self.assertTrue(all(item["status"] == "planned" for item in result["steps"]))

    def test_missing_capability_fails_before_steps(self):
        self.recipe["requires"] = ["adbDevice"]
        (self.recipe_dir / "test.flow.json").write_text(
            json.dumps(self.recipe, ensure_ascii=False), encoding="utf-8")
        with mock.patch.object(recipes.execution, "execute_tool", side_effect=self._fake_execute):
            with self.assertRaises(recipes.RecipeError) as caught:
                recipes.run_recipe("test.flow", {"name": "Alice"}, env=self.env)
        self.assertEqual("RECIPE_CAPABILITY_MISSING", caught.exception.code)
        self.assertEqual([], self.calls)


class McpDomainFilterTests(unittest.TestCase):
    def test_domain_filter_limits_visible_tools(self):
        server = mcp_server.McpServer(env={"QIMENG_AI_MCP_DOMAINS": "author"})
        visible = server._visible_tools()
        self.assertTrue(visible)
        self.assertTrue(all(spec.domain == "author" for spec in visible))
        unfiltered = mcp_server.McpServer(env={})._visible_tools()
        self.assertGreater(len(unfiltered), len(visible))


class AuthorToolTests(unittest.TestCase):
    def test_author_query_name_filter(self):
        class Client:
            def get(self, path):
                return [
                    {"id": "a1", "displayName": "Alice"},
                    {"id": "b2", "displayName": "Bob"},
                ]

        class Ctx:
            client = Client()

        result = tools_query.author_query(Ctx(), {"name": "ali"})
        self.assertEqual(["a1"], [item["id"] for item in result])

    def test_author_create_requires_confirm_and_reads_back(self):
        client = _FakeClient(authors=[{"id": "a1", "displayName": "Alice", "type": "regular",
                                       "fileCount": 0}], created={"id": "a1"})
        ctx = type("Ctx", (), {"client": client})()
        with self.assertRaises(errors.ToolError) as caught:
            tools_author.author_create(ctx, {"displayName": "Alice", "confirm": False})
        self.assertEqual("NOT_CONFIRMED", caught.exception.code)
        result = tools_author.author_create(ctx, {"displayName": "Alice", "confirm": True})
        self.assertEqual("a1", result["authorId"])
        self.assertEqual([("/api/v1/authors", {"displayName": "Alice"})], client.posts)


class TargetTests(unittest.TestCase):
    def test_explicit_device_serial_is_required(self):
        detected = targets.check(["adbDevice"], {})
        self.assertIn("adbDevice", detected["missing"])
        with self.assertRaises(device_adb.AdbError) as caught:
            device_adb.serial({})
        self.assertEqual("ADB_SERIAL_REQUIRED", caught.exception.code)
        with self.assertRaises(device_adb.AdbError):
            device_adb.serial({"QIMENG_ADB_SERIAL": "emulator-5554"})


class MediaPrimitiveTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not media_primitives.find_ffmpeg():
            raise unittest.SkipTest("ffmpeg not available")
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.video = cls.root / "clip.mp4"
        subprocess.run(
            [media_primitives.find_ffmpeg(), "-v", "error", "-y", "-f", "lavfi",
             "-i", "testsrc=size=160x120:rate=10:duration=2", "-pix_fmt", "yuv420p",
             str(cls.video)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_video_perceptual_records_three_frames_and_cache(self):
        first = media_primitives.perceptual_record(self.video, "video", cache=False)
        self.assertEqual("ok", first["status"])
        self.assertEqual(3, len(first["frames"]))
        second = media_primitives.perceptual_record(self.video, "video")
        self.assertEqual(first["frames"], second["frames"])

    def test_extract_frames_seeks_by_percentage(self):
        result = media_primitives.extract_frames(
            self.video, self.root / "frames", percentages=(15, 85))
        self.assertEqual("ok", result["status"])
        self.assertEqual([300, 1700], [item["seekMs"] for item in result["frames"]])
        self.assertTrue(all(Path(item["path"]).is_file() for item in result["frames"]))

    def test_media_transfer_copies_and_refuses_overwrite(self):
        source = self.root / "source.txt"
        source.write_text("hello", encoding="utf-8")
        target = self.root / "target.txt"
        ctx = recipes.RunContext({}, self.root)
        result = recipes._primitive_media_transfer(
            {"items": [{"source": str(source), "target": str(target)}]}, ctx)
        self.assertEqual("ok", result["status"])
        self.assertEqual("hello", target.read_text(encoding="utf-8"))
        again = recipes._primitive_media_transfer(
            {"items": [{"source": str(source), "target": str(target)}]}, ctx)
        self.assertEqual("partial", again["status"])
        self.assertEqual("TARGET_EXISTS", again["failed"][0]["code"])

    def test_contact_sheet_renders_labeled_grid(self):
        images = []
        for index, color in enumerate(("red", "blue", "green")):
            target = self.root / f"{index}.png"
            subprocess.run(
                [media_primitives.find_ffmpeg(), "-v", "error", "-y", "-f", "lavfi",
                 "-i", f"color=c={color}:s=160x120:d=1", "-frames:v", "1", str(target)],
                check=True)
            images.append({"path": str(target), "label": f"test {index + 1}"})
        result = media_primitives.build_contact_sheet(images, self.root / "sheet.png",
                                                      columns=2)
        self.assertEqual("ok", result["status"])
        self.assertTrue((self.root / "sheet.png").is_file())


if __name__ == "__main__":
    unittest.main()
