"""PR #53 合并前定向修复回归：部分失败、覆盖安全、只读副作用、Gate 范围、ADB 拉取。

全部使用临时目录/合成文件与模拟 ADB；不联网、不操作真实设备、不写业务库。
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from ai_maintenance import (device_adb, errors, execution, fileref,  # noqa: E402
                            media_primitives, recipes, safefile)

OPENAPI = ROOT / "api" / "openapi.yaml"
RULES = ROOT / "tools" / "ai_maintenance" / "rules.json"


def _ffmpeg() -> str:
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


class RecipeSafetyBase(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory(prefix="qimeng-pr53-")
        self.addCleanup(self._temp.cleanup)
        self.root = Path(self._temp.name)
        self.source_a = self.root / "src-a.bin"
        self.source_b = self.root / "src-b.bin"
        self.source_a.write_bytes(b"AAAA")
        self.source_b.write_bytes(b"BBBB")
        self.target_a = self.root / "out-a.bin"
        self.target_b = self.root / "out-b.bin"
        self.recipe_dir = self.root / "recipes"
        self.recipe_dir.mkdir()
        self.env = {
            "QIMENG_AI_OPENAPI": str(OPENAPI),
            "QIMENG_AI_RULES": str(RULES),
            "QIMENG_AI_RECIPE_DIR": str(self.recipe_dir),
            "QIMENG_AI_READ_ROOTS": str(self.root),
            "QIMENG_AI_WRITE_ROOTS": str(self.root),
            "QIMENG_AI_MEDIA_CACHE": str(self.root / "cache"),
        }
        self.calls: list[str] = []
        self._fake = mock.patch.object(
            recipes.execution, "execute_tool", side_effect=self._fake_execute)
        self._fake.start()
        self.addCleanup(self._fake.stop)

    def _fake_execute(self, tool_id, payload, env=None, config=None):
        self.calls.append(tool_id)
        return (None, {"status": "ok", "tool": tool_id})

    def _recipe(self, steps, *, permissions=None, inputs=None):
        recipe = {
            "schemaVersion": 1,
            "id": "test.safety",
            "version": "1.0.0",
            "title": "PR53 safety",
            "whenToUse": "test",
            "requires": [],
            "inputs": inputs or {
                "type": "object",
                "properties": {
                    "sourceA": {"type": "string"},
                    "sourceB": {"type": "string"},
                    "targetA": {"type": "string"},
                    "targetB": {"type": "string"},
                },
                "required": ["sourceA", "sourceB", "targetA", "targetB"],
            },
            "permissions": permissions or ["read", "local.files.write"],
            "steps": steps,
        }
        (self.recipe_dir / (recipe["id"] + ".json")).write_text(
            json.dumps(recipe, ensure_ascii=False), encoding="utf-8")
        return recipe

    def _inputs(self):
        return {
            "sourceA": str(self.source_a),
            "sourceB": str(self.source_b),
            "targetA": str(self.target_a),
            "targetB": str(self.target_b),
        }

    def _state(self, run_dir: Path) -> dict:
        return json.loads((run_dir / "state.json").read_text(encoding="utf-8"))

    def _transfer_items(self):
        return [
            {"source": "$inputs.sourceA", "target": "$inputs.targetA"},
            {"source": "$inputs.sourceB", "target": "$inputs.targetB"},
        ]


class PartialFailureRecipeTest(RecipeSafetyBase):
    def _recipe_with_after_step(self):
        return self._recipe([
            {"id": "confirm", "gate": True, "message": "确认复制"},
            {"id": "transfer", "primitive": "media.transfer",
             "input": {"items": self._transfer_items()}},
            {"id": "after", "tool": "author.query", "input": {}},
        ])

    def test_partial_primitive_stops_following_steps_and_preserves_items(self):
        self.target_b.write_bytes(b"pre-existing-different")
        self._recipe_with_after_step()
        run_dir = self.root / "run-partial"
        with self.assertRaises(recipes.RecipeError) as caught:
            recipes.run_recipe("test.safety", self._inputs(), env=self.env,
                               state_dir=str(run_dir), confirm=True)
        self.assertEqual("RECIPE_STEP_PARTIAL", caught.exception.code)
        self.assertEqual([], self.calls)  # 后续步骤未执行
        state = self._state(run_dir)
        self.assertEqual("partial", state["status"])
        self.assertEqual("partial", state["steps"]["transfer"]["status"])
        self.assertEqual({"copied": 1, "failed": 1},
                         state["steps"]["transfer"]["counts"])
        artifact = json.loads(
            (run_dir / "artifacts" / state["steps"]["transfer"]["artifact"])
            .read_text(encoding="utf-8"))
        self.assertEqual("partial", artifact["status"])
        self.assertEqual(1, len(artifact["copied"]))
        self.assertEqual("TARGET_EXISTS", artifact["failed"][0]["code"])
        self.assertTrue(self.target_a.is_file())
        self.assertEqual(b"pre-existing-different", self.target_b.read_bytes())

    def test_partial_failure_resume_skips_completed_and_finishes(self):
        self.target_b.write_bytes(b"pre-existing-different")
        self._recipe_with_after_step()
        run_dir = self.root / "run-resume"
        with self.assertRaises(recipes.RecipeError):
            recipes.run_recipe("test.safety", self._inputs(), env=self.env,
                               state_dir=str(run_dir), confirm=True)
        copied_stat = self.target_a.stat().st_mtime_ns
        self.target_b.unlink()
        result = recipes.run_recipe("test.safety", self._inputs(), env=self.env,
                                    state_dir=str(run_dir), resume=True)
        self.assertEqual("completed", result["status"])
        self.assertEqual(b"BBBB", self.target_b.read_bytes())
        self.assertEqual(copied_stat, self.target_a.stat().st_mtime_ns)
        self.assertIn("author.query", self.calls)
        state = self._state(run_dir)
        self.assertEqual("ok", state["steps"]["transfer"]["status"])
        self.assertGreaterEqual(state["steps"]["transfer"]["counts"].get("copied", 0), 2)

    def test_failed_primitive_is_not_recorded_as_success(self):
        self.target_a.write_bytes(b"old-a")
        self.target_b.write_bytes(b"old-b")
        self._recipe_with_after_step()
        run_dir = self.root / "run-failed"
        with self.assertRaises(recipes.RecipeError) as caught:
            recipes.run_recipe("test.safety", self._inputs(), env=self.env,
                               state_dir=str(run_dir), confirm=True)
        self.assertEqual("RECIPE_STEP_FAILED", caught.exception.code)
        state = self._state(run_dir)
        self.assertEqual("failed", state["status"])
        self.assertEqual("failed", state["steps"]["transfer"]["status"])
        self.assertNotIn("author.query", self.calls)
        self.assertEqual(b"old-a", self.target_a.read_bytes())
        self.assertEqual(b"old-b", self.target_b.read_bytes())


class TransferSafetyTest(RecipeSafetyBase):
    def _ctx(self, **env_extra):
        env = dict(self.env)
        env.update(env_extra)
        return recipes.RunContext(env, self.root / "run")

    def _transfer(self, items, ctx=None, **extra):
        payload = {"items": items}
        payload.update(extra)
        return recipes._primitive_media_transfer(payload, ctx or self._ctx())

    def test_same_path_is_rejected_without_touching_source(self):
        result = self._transfer([
            {"source": str(self.source_a), "target": str(self.source_a)}],
            overwrite=True)
        self.assertEqual("failed", result["status"])
        self.assertEqual("SOURCE_TARGET_SAME", result["failed"][0]["code"])
        self.assertEqual(b"AAAA", self.source_a.read_bytes())

    def test_hardlink_target_is_rejected_without_truncation(self):
        try:
            os.link(self.source_a, self.target_a)
        except (OSError, NotImplementedError) as exc:
            self.skipTest("hardlink unsupported: " + type(exc).__name__)
        result = self._transfer([
            {"source": str(self.source_a), "target": str(self.target_a)}],
            overwrite=True)
        self.assertEqual("failed", result["status"])
        self.assertEqual("SOURCE_TARGET_SAME", result["failed"][0]["code"])
        self.assertEqual(b"AAAA", self.source_a.read_bytes())

    def test_overwrite_success_replaces_atomically_without_temp_leftovers(self):
        self.target_a.write_bytes(b"old-content")
        result = self._transfer([
            {"source": str(self.source_a), "target": str(self.target_a)}],
            overwrite=True)
        self.assertEqual("ok", result["status"])
        self.assertEqual(b"AAAA", self.target_a.read_bytes())
        self.assertEqual([], [p for p in self.root.iterdir() if ".part" in p.name])

    def test_publish_failure_keeps_old_target_and_cleans_temp(self):
        self.target_a.write_bytes(b"old-content")
        with mock.patch.object(recipes.safefile, "publish_replace",
                               side_effect=OSError("simulated")):
            result = self._transfer([
                {"source": str(self.source_a), "target": str(self.target_a)}],
                overwrite=True)
        self.assertEqual("failed", result["status"])
        self.assertEqual("PUBLISH_FAILED", result["failed"][0]["code"])
        self.assertEqual(b"old-content", self.target_a.read_bytes())
        self.assertEqual([], [p for p in self.root.iterdir() if ".part" in p.name])

    def test_verify_failure_keeps_old_target(self):
        self.target_a.write_bytes(b"old-content")
        with mock.patch.object(recipes, "_copy_to_temp",
                               side_effect=recipes._VerifyError("simulated")):
            result = self._transfer([
                {"source": str(self.source_a), "target": str(self.target_a)}],
                overwrite=True)
        self.assertEqual("failed", result["status"])
        self.assertEqual("VERIFY_MISMATCH", result["failed"][0]["code"])
        self.assertEqual(b"old-content", self.target_a.read_bytes())

    def test_transfer_ref_output_has_no_absolute_source(self):
        self.env["QIMENG_AI_STORAGES"] = f"downloads={self.root}"
        metadata = fileref.make_ref(self.source_a, env=self.env)
        result = self._transfer([
            {"sourceFileRef": metadata["fileRef"], "target": str(self.target_a)}],
            ctx=recipes.RunContext(self.env, self.root / "run"))
        self.assertEqual("ok", result["status"])
        encoded = json.dumps(result, ensure_ascii=False)
        self.assertNotIn(str(self.root), encoded)
        self.assertNotIn("source", result["copied"][0])
        self.assertEqual(metadata["fileRef"], result["copied"][0]["sourceFileRef"])


class ProbeSideEffectTest(RecipeSafetyBase):
    def _probe_recipe(self, *, frames: bool):
        steps = [{"id": "probe", "primitive": "media.probe",
                  "input": {"path": "$inputs.mediaPath"}}]
        inputs = {"type": "object",
                  "properties": {"mediaPath": {"type": "string"}},
                  "required": ["mediaPath"]}
        if frames:
            steps[0]["input"]["frames"] = "15,50,85"
            steps[0]["input"]["frameDir"] = "$inputs.frameDir"
            inputs["properties"]["frameDir"] = {"type": "string"}
            inputs["required"].append("frameDir")
        self._recipe(steps, permissions=["read", "local.files.write"],
                     inputs=inputs)
        return steps

    def setUp(self):
        super().setUp()
        self.video = self.root / "clip.mp4"
        self.has_video = _make_video(self.video)
        if not self.has_video:
            self.video.write_bytes(b"synthetic-not-a-real-video")

    def test_metadata_only_probe_runs_in_readonly_and_writes_no_cache(self):
        self._probe_recipe(frames=False)
        env = dict(self.env, QIMENG_AI_READONLY="1")
        result = recipes.run_recipe(
            "test.safety", {"mediaPath": str(self.video)}, env=env,
            state_dir=str(self.root / "run-readonly"))
        self.assertEqual("completed", result["status"])
        self.assertTrue((self.root / "cache").is_dir() is False
                        or not any((self.root / "cache").rglob("*")))

    def test_probe_with_frames_is_denied_in_readonly(self):
        self._probe_recipe(frames=True)
        env = dict(self.env, QIMENG_AI_READONLY="1")
        with self.assertRaises(errors.ToolError) as caught:
            recipes.run_recipe(
                "test.safety",
                {"mediaPath": str(self.video), "frameDir": str(self.root / "frames")},
                env=env, state_dir=str(self.root / "run-frames-readonly"))
        self.assertIn(caught.exception.code, ("TOOL_DISABLED", "PERMISSION_DENIED"))
        self.assertFalse((self.root / "frames").exists())

    def test_probe_with_frames_and_write_permission_works(self):
        if not self.has_video:
            self.skipTest("ffmpeg unavailable")
        self._probe_recipe(frames=True)
        env = dict(self.env,
                   QIMENG_AI_GRANTED_PERMISSIONS="read,local.files.write")
        result = recipes.run_recipe(
            "test.safety",
            {"mediaPath": str(self.video), "frameDir": str(self.root / "frames")},
            env=env, state_dir=str(self.root / "run-frames-ok"))
        self.assertEqual("completed", result["status"])
        self.assertTrue(any((self.root / "frames").rglob("*.jpg")))


class GateScopeTest(RecipeSafetyBase):
    def test_gate_authorizes_only_scope_and_resume(self):
        self._recipe([
            {"id": "gateOne", "gate": True, "message": "第一道"},
            {"id": "transferA", "primitive": "media.transfer",
             "input": {"items": [{"source": "$inputs.sourceA",
                                  "target": "$inputs.targetA"}]}},
            {"id": "gateTwo", "gate": True, "message": "第二道"},
            {"id": "transferB", "primitive": "media.transfer",
             "input": {"items": [{"source": "$inputs.sourceB",
                                  "target": "$inputs.targetB"}]}},
        ])
        run_dir = self.root / "run-gates"
        with self.assertRaises(recipes.RecipeError) as caught:
            recipes.run_recipe("test.safety", self._inputs(), env=self.env,
                               state_dir=str(run_dir), confirm=True)
        self.assertEqual("RECIPE_CONFIRMATION_REQUIRED", caught.exception.code)
        self.assertTrue(self.target_a.is_file())
        self.assertFalse(self.target_b.exists())
        result = recipes.run_recipe("test.safety", self._inputs(), env=self.env,
                                    state_dir=str(run_dir), confirm=True, resume=True)
        self.assertEqual("completed", result["status"])
        self.assertTrue(self.target_b.is_file())

    def test_unrelated_explicit_gate_does_not_authorize_transfer(self):
        self._recipe([
            {"id": "gateOne", "gate": True, "message": "只确认查询",
             "authorizes": ["lookup"]},
            {"id": "lookup", "tool": "author.query", "input": {}},
            {"id": "transfer", "primitive": "media.transfer",
             "input": {"items": [{"source": "$inputs.sourceA",
                                  "target": "$inputs.targetA"}]}},
        ])
        with self.assertRaises(recipes.RecipeError) as caught:
            recipes.run_recipe("test.safety", self._inputs(), env=self.env,
                               state_dir=str(self.root / "run-unrelated"),
                               confirm=True)
        self.assertEqual("RECIPE_CONFIRMATION_REQUIRED", caught.exception.code)
        self.assertFalse(self.target_a.exists())

    def test_skipped_conditional_gate_keeps_existing_flow(self):
        inputs = {
            "type": "object",
            "properties": {
                "sourceA": {"type": "string"}, "targetA": {"type": "string"},
                "sourceB": {"type": "string"}, "targetB": {"type": "string"},
                "optional": {"type": "string", "default": ""},
            },
            "required": ["sourceA", "targetA", "sourceB", "targetB"],
        }
        self._recipe([
            {"id": "gateOne", "gate": True, "message": "第一道"},
            {"id": "transferA", "primitive": "media.transfer",
             "input": {"items": [{"source": "$inputs.sourceA",
                                  "target": "$inputs.targetA"}]}},
            {"id": "gateTwo", "gate": True, "message": "条件门禁",
             "when": {"notEmpty": "$inputs.optional"}},
            {"id": "transferB", "primitive": "media.transfer",
             "input": {"items": [{"source": "$inputs.sourceB",
                                  "target": "$inputs.targetB"}]}},
        ], inputs=inputs)
        result = recipes.run_recipe(
            "test.safety",
            {"sourceA": str(self.source_a), "targetA": str(self.target_a),
             "sourceB": str(self.source_b), "targetB": str(self.target_b)},
            env=self.env, state_dir=str(self.root / "run-skip-gate"),
            confirm=True)
        self.assertEqual("completed", result["status"])
        self.assertTrue(self.target_b.is_file())

    def test_recorded_gate_scope_drift_blocks_resume(self):
        self._recipe([
            {"id": "gateOne", "gate": True, "message": "确认"},
            {"id": "transferA", "primitive": "media.transfer",
             "input": {"items": [{"source": "$inputs.sourceA",
                                  "target": "$inputs.targetA"}]}},
        ])
        run_dir = self.root / "run-drift"
        recipes.run_recipe("test.safety", self._inputs(), env=self.env,
                           state_dir=str(run_dir), confirm=True)
        state = self._state(run_dir)
        state["steps"]["gateOne"]["authorizedSteps"] = ["otherStep"]
        (run_dir / "state.json").write_text(
            json.dumps(state, ensure_ascii=False), encoding="utf-8")
        with self.assertRaises(recipes.RecipeError) as caught:
            recipes.run_recipe("test.safety", self._inputs(), env=self.env,
                               state_dir=str(run_dir), resume=True)
        self.assertEqual("RECIPE_STATE_VERSION", caught.exception.code)


class DevicePullSafetyTest(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory(prefix="qimeng-adb-pull-")
        self.addCleanup(self._temp.cleanup)
        self.root = Path(self._temp.name)
        self.target = self.root / "pulled.bin"
        self.env = {
            "QIMENG_ADB": "fake-adb",
            "QIMENG_ADB_SERIAL": "synthetic-device",
            "QIMENG_AI_READ_ROOTS": str(self.root),
            "QIMENG_AI_WRITE_ROOTS": str(self.root),
        }

    def _fake_run(self, argv, *, timeout=120.0):
        if "pull" in argv:
            Path(argv[-1]).write_bytes(b"pulled-bytes")
        return ""

    def test_pull_success_writes_target_and_leaves_no_temp(self):
        with mock.patch.object(device_adb, "_run", side_effect=self._fake_run), \
                mock.patch.object(device_adb, "remote_size", return_value=12):
            result = device_adb.pull_file("/sdcard/a.bin", str(self.target),
                                          env=self.env)
        self.assertEqual(12, result["bytes"])
        self.assertEqual(b"pulled-bytes", self.target.read_bytes())
        self.assertEqual([], [p for p in self.root.iterdir() if ".part" in p.name])

    def test_pull_refuses_existing_target_by_default(self):
        self.target.write_bytes(b"old")
        with mock.patch.object(device_adb, "_run") as run:
            with self.assertRaises(device_adb.AdbError) as caught:
                device_adb.pull_file("/sdcard/a.bin", str(self.target), env=self.env)
        self.assertEqual("LOCAL_TARGET_EXISTS", caught.exception.code)
        run.assert_not_called()
        self.assertEqual(b"old", self.target.read_bytes())

    def test_pull_failure_keeps_existing_target_and_cleans_temp(self):
        self.target.write_bytes(b"old")
        with mock.patch.object(device_adb, "_run",
                               side_effect=device_adb.AdbError("simulated")):
            with self.assertRaises(device_adb.AdbError):
                device_adb.pull_file("/sdcard/a.bin", str(self.target),
                                     env=self.env, overwrite=True)
        self.assertEqual(b"old", self.target.read_bytes())
        self.assertEqual([], [p for p in self.root.iterdir() if ".part" in p.name])

    def test_pull_rejects_unauthorized_target_root(self):
        outside = self.root / "outside"
        outside.mkdir()
        env = {k: v for k, v in self.env.items()
               if k != "QIMENG_AI_WRITE_ROOTS"}
        ctx = recipes.RunContext(env, self.root / "run")
        with self.assertRaises(errors.ToolError) as caught:
            recipes._primitive_device_pull(
                {"remotePath": "/sdcard/a.bin",
                 "localPath": str(outside / "x.bin")}, ctx)
        self.assertEqual("PATH_NOT_AUTHORIZED", caught.exception.code)

    def test_push_reuse_skips_already_pushed_items(self):
        local = self.root / "a.bin"
        local.write_bytes(b"AAAA")
        remote_dir = "/sdcard/qimeng"
        reuse = [{"local": str(local), "remote": remote_dir + "/a.bin", "bytes": 4}]
        with mock.patch.object(device_adb, "remote_size", return_value=4), \
                mock.patch.object(device_adb, "_run") as run:
            result = device_adb.push_many(
                [(str(local), None)], remote_dir, env=self.env,
                reuse_pushed=reuse)
        self.assertEqual("ok", result["status"])
        self.assertEqual(1, result["alreadyPresent"])
        self.assertTrue(result["pushed"][0]["alreadyPresent"])
        run.assert_not_called()

    def test_pull_rejects_symlink_target(self):
        link = self.root / "link.bin"
        try:
            os.symlink(self.root / "outside.bin", link)
        except (OSError, NotImplementedError) as exc:
            self.skipTest("symlink unsupported: " + type(exc).__name__)
        with self.assertRaises((device_adb.AdbError, errors.ToolError)) as caught:
            device_adb.pull_file("/sdcard/a.bin", str(link), env=self.env)
        self.assertIn(caught.exception.code,
                      ("ADB_TARGET_UNSAFE", "PATH_UNSAFE"))


if __name__ == "__main__":
    unittest.main()
