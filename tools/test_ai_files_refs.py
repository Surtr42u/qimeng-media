"""本地执行域回归：读写根授权、fileRef、原语策略、recipe 门禁/恢复（合成数据）。

覆盖 2026-11-10 定向优化的 13 个验收点；全部使用隔离临时目录与合成文件，
不访问 8420、不读真实媒体、不操作 Android 设备。
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest import mock

TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))

from ai_maintenance import (device_adb, errors, execution, fileref,  # noqa: E402
                            media_scan, pathguard, recipes, registry, tools_research)
from ai_maintenance.runtime import ToolContext  # noqa: E402

ROOT = TOOLS_DIR.parent
OPENAPI = ROOT / "api" / "openapi.yaml"
RULES = ROOT / "tools" / "ai_maintenance" / "rules.json"


def _base_env(workspace: Path, *, extra_roots: bool = True, storages: bool = True) -> dict:
    env = {
        "QIMENG_AI_RULES": str(RULES),
        "QIMENG_AI_OPENAPI": str(OPENAPI),
        "QIMENG_AI_MEDIA_CACHE": str(workspace / "cache"),
    }
    if storages:
        env["QIMENG_AI_STORAGES"] = "downloads=" + str(workspace / "storage")
    if extra_roots:
        env["QIMENG_AI_READ_ROOTS"] = str(workspace)
        env["QIMENG_AI_WRITE_ROOTS"] = str(workspace)
    return env


class _Workspace:
    def __init__(self):
        self._temp = tempfile.TemporaryDirectory(prefix="qimeng-fileref-")
        self.root = Path(self._temp.name)
        (self.root / "storage" / "nested").mkdir(parents=True)
        (self.root / "out").mkdir()
        self.storage = self.root / "storage"

    def cleanup(self) -> None:
        self._temp.cleanup()


def _recipe_dir(workspace: Path) -> Path:
    directory = workspace / "recipes"
    directory.mkdir(exist_ok=True)
    return directory


def _write_recipe(directory: Path, recipe: dict) -> str:
    recipe = {"schemaVersion": 1, "requires": [], **recipe}
    (directory / (recipe["id"] + ".json")).write_text(
        json.dumps(recipe, ensure_ascii=False), encoding="utf-8")
    return recipe["id"]


class PathAuthorizationTests(unittest.TestCase):
    def setUp(self):
        self.work = _Workspace()
        self.addCleanup(self.work.cleanup)
        self.env = _base_env(self.work.root)
        self.source = self.work.storage / "nested" / "source.txt"
        self.source.write_text("synthetic", encoding="utf-8")

    def test_authorized_read_and_write_roots(self):
        resolved = pathguard.resolve_local_path(
            str(self.source), env=self.env, must_exist=True, mode="read")
        self.assertTrue(resolved.is_file())
        target = self.work.storage / "nested" / "new.txt"
        written = pathguard.resolve_local_path(
            str(target), env=self.env, must_exist=False, mode="write")
        self.assertEqual(target.resolve(), written)
        self.assertIn(str(self.work.root.resolve()),
                      [str(item) for item in pathguard.write_roots(self.env)])
        info = fileref.storage_info(self.env)
        self.assertEqual("downloads", info[0]["storageId"])
        self.assertTrue(info[0]["writable"])

    def test_unconfigured_path_is_rejected(self):
        with self.assertRaises(errors.ToolError) as caught:
            pathguard.resolve_local_path(str(self.source), env={}, mode="read",
                                         must_exist=True)
        self.assertEqual("PATH_NOT_AUTHORIZED", caught.exception.code)
        self.assertEqual("permission", caught.exception.category)

    def test_repo_source_path_is_rejected(self):
        with self.assertRaises(errors.ToolError) as caught:
            pathguard.resolve_local_path(str(ROOT / "server" / "x.txt"), env={},
                                         mode="read")
        self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_dotdot_escape_is_rejected(self):
        escape = self.work.storage / "nested" / ".." / ".." / ".." / "outside.txt"
        with self.assertRaises(errors.ToolError) as caught:
            pathguard.resolve_local_path(str(escape), env=self.env, mode="write")
        self.assertEqual("PATH_NOT_AUTHORIZED", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            pathguard.resolve_local_path("relative/path.txt", env=self.env, mode="read")
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            pathguard.resolve_local_path(str(self.work.root) + "\x00bad", env=self.env,
                                         mode="read")
        self.assertEqual("PATH_UNSAFE", caught.exception.code)

    def test_symlink_escape_and_write_link_are_rejected(self):
        outside = self.work.root.parent / ("outside-" + uuid.uuid4().hex)
        outside.mkdir()
        self.addCleanup(shutil.rmtree, outside, True)
        try:
            os.symlink(outside, self.work.storage / "escape-dir",
                       target_is_directory=True)
        except (OSError, NotImplementedError) as exc:
            self.skipTest("当前环境无法创建符号链接：" + type(exc).__name__)
        with self.assertRaises(errors.ToolError) as caught:
            pathguard.resolve_local_path(
                str(self.work.storage / "escape-dir" / "x.txt"), env=self.env,
                mode="read")
        self.assertEqual("PATH_NOT_AUTHORIZED", caught.exception.code)
        inside_target = self.work.storage / "nested" / "inside.txt"
        inside_target.write_text("inside", encoding="utf-8")
        link = self.work.storage / "nested" / "link.txt"
        os.symlink(inside_target, link)
        with self.assertRaises(errors.ToolError) as caught:
            pathguard.resolve_local_path(str(link), env=self.env, mode="write")
        self.assertEqual("PATH_UNSAFE", caught.exception.code)

    def test_junction_root_is_usable_and_inner_links_still_rejected(self):
        if os.name != "nt":
            self.skipTest("junction 仅 Windows")
        real = self.work.root / "real-root"
        real.mkdir()
        (real / "file.txt").write_text("junction-root", encoding="utf-8")
        junction = self.work.root / "junction-root"
        completed = subprocess.run(
            ["cmd", "/c", "mklink", "/J", str(junction), str(real)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if completed.returncode != 0 or not junction.is_dir():
            self.skipTest("当前环境无法创建 junction")
        env = _base_env(self.work.root)
        env["QIMENG_AI_STORAGES"] = "downloads=" + str(junction)
        env["QIMENG_AI_READ_ROOTS"] = str(junction)
        env["QIMENG_AI_WRITE_ROOTS"] = str(junction)
        resolved = pathguard.resolve_local_path(
            str(junction / "file.txt"), env=env, must_exist=True, mode="write")
        self.assertEqual((real / "file.txt").resolve(), resolved)
        self.assertTrue(fileref.storage_info(env)[0]["writable"])
        metadata = fileref.make_ref(real / "file.txt", env=env)
        self.assertEqual("file.txt", metadata["name"])
        self.assertEqual(resolved, fileref.resolve_ref(metadata["fileRef"], env=env))
        outside = self.work.root / "outside-junction-dir"
        outside.mkdir()
        try:
            os.symlink(outside, real / "escape", target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("当前环境无法在 junction 根内创建符号链接")
        with self.assertRaises(errors.ToolError) as caught:
            pathguard.resolve_local_path(str(real / "escape" / "x"), env=env,
                                         mode="read")
        self.assertEqual("PATH_NOT_AUTHORIZED", caught.exception.code)

    def test_hash_rechecks_root(self):
        other = self.work.root / "other"
        other.mkdir()
        with self.assertRaises(errors.ToolError) as caught:
            pathguard.sha256_file(self.source, root=other)
        self.assertEqual("PATH_UNSAFE", caught.exception.code)


class FileRefTests(unittest.TestCase):
    def setUp(self):
        self.work = _Workspace()
        self.addCleanup(self.work.cleanup)
        self.file = self.work.storage / "nested" / "target.png"
        self.file.write_bytes(b"file-ref-content")
        self.storages_only = {"QIMENG_AI_STORAGES": "downloads=" + str(self.work.storage)}

    def test_make_resolve_describe_without_extra_read_root(self):
        metadata = fileref.make_ref(self.file, env=self.storages_only)
        self.assertEqual("downloads", metadata["storageId"])
        self.assertEqual("nested/target.png", metadata["relativePath"])
        self.assertEqual("image", metadata["mediaType"])
        self.assertEqual(len(b"file-ref-content"), metadata["size"])
        self.assertRegex(metadata["modifiedAt"], r"^\d{4}-\d{2}-\d{2}T.*Z$")
        self.assertEqual(self.file.resolve(),
                         fileref.resolve_ref(metadata["fileRef"],
                                             env=self.storages_only))
        described = fileref.describe_ref(metadata["fileRef"], env=self.storages_only)
        self.assertEqual(metadata, described)
        serialized = json.dumps([metadata, described], ensure_ascii=False)
        self.assertNotIn(str(self.work.storage), serialized)
        self.assertNotIn(str(self.work.storage.resolve()), serialized)

    def test_write_resolution_requires_write_root(self):
        metadata = fileref.make_ref(self.file, env=self.storages_only)
        with self.assertRaises(errors.ToolError) as caught:
            fileref.resolve_ref(metadata["fileRef"], env=self.storages_only,
                                mode="write")
        self.assertEqual("PATH_NOT_AUTHORIZED", caught.exception.code)
        writable = dict(self.storages_only)
        writable["QIMENG_AI_WRITE_ROOTS"] = str(self.work.storage)
        self.assertEqual(self.file.resolve(),
                         fileref.resolve_ref(metadata["fileRef"], env=writable,
                                             mode="write"))
        self.assertFalse(fileref.storage_info(self.storages_only)[0]["writable"])
        self.assertTrue(fileref.storage_info(writable)[0]["writable"])

    def test_ref_is_stale_after_replace_or_delete(self):
        metadata = fileref.make_ref(self.file, env=self.storages_only)
        self.file.write_bytes(b"changed-content-that-is-longer")
        with self.assertRaises(errors.ToolError) as caught:
            fileref.resolve_ref(metadata["fileRef"], env=self.storages_only)
        self.assertEqual("FILE_REF_STALE", caught.exception.code)
        self.assertEqual("conflict", caught.exception.category)
        self.file.unlink()
        with self.assertRaises(errors.ToolError) as caught:
            fileref.resolve_ref(metadata["fileRef"], env=self.storages_only)
        self.assertEqual("FILE_REF_STALE", caught.exception.code)

    def test_unconfigured_storage_is_unavailable(self):
        metadata = fileref.make_ref(self.file, env=self.storages_only)
        with self.assertRaises(errors.ToolError) as caught:
            fileref.resolve_ref(metadata["fileRef"], env={})
        self.assertEqual("FILE_REF_UNAVAILABLE", caught.exception.code)
        self.assertEqual("config", caught.exception.category)

    def test_forged_and_unsafe_refs_are_invalid(self):
        import base64
        bad_refs = [
            "not-a-ref",
            "file_%%%_1_1",
            "file_" + base64.urlsafe_b64encode(
                b"downloads\x00../escape").decode().rstrip("=") + "_1_1",
            "file_" + base64.urlsafe_b64encode(
                b"downloads\x00/abs/path").decode().rstrip("=") + "_1_1",
            "file_" + base64.urlsafe_b64encode(
                b"downloads\x00C:\\abs\\path").decode().rstrip("=") + "_1_1",
            "file_" + base64.urlsafe_b64encode(
                b"downloads\x00a\\b").decode().rstrip("=") + "_1_1",
            "file_" + base64.urlsafe_b64encode(
                b"downloads\x00a//b").decode().rstrip("=") + "_1_1",
            "file_" + "a" * 520 + "_1_1",
        ]
        for reference in bad_refs:
            with self.subTest(reference=reference[:40]):
                with self.assertRaises(errors.ToolError) as caught:
                    fileref.resolve_ref(reference, env=self.storages_only)
                self.assertEqual("FILE_REF_INVALID", caught.exception.code)

    def test_resolve_input_requires_exactly_one_source(self):
        with self.assertRaises(errors.ToolError) as caught:
            fileref.resolve_input(env=self.storages_only)
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        metadata = fileref.make_ref(self.file, env=self.storages_only)
        with self.assertRaises(errors.ToolError) as caught:
            fileref.resolve_input(str(self.file), metadata["fileRef"],
                                  env=self.storages_only)
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        self.assertEqual(self.file.resolve(),
                         fileref.resolve_input(file_ref=metadata["fileRef"],
                                               env=self.storages_only))

    def test_make_ref_rejects_file_outside_storage(self):
        outside = self.work.root / "outside.bin"
        outside.write_bytes(b"x")
        with self.assertRaises(errors.ToolError) as caught:
            fileref.make_ref(outside, env=self.storages_only)
        self.assertEqual("FILE_REF_INVALID", caught.exception.code)

    def test_storage_config_errors_do_not_echo_paths(self):
        env = {"QIMENG_AI_STORAGES": "bad id=" + str(self.work.storage)}
        with self.assertRaises(errors.ToolError) as caught:
            fileref.storages(env)
        self.assertEqual("CONFIGURATION_ERROR", caught.exception.code)
        self.assertNotIn(str(self.work.storage), json.dumps(caught.exception.to_dict(),
                                                             ensure_ascii=False))
        duplicate = {"QIMENG_AI_STORAGES": "a=" + str(self.work.storage) +
                     ";a=" + str(self.work.storage)}
        with self.assertRaises(errors.ToolError) as caught:
            fileref.storages(duplicate)
        self.assertEqual("CONFIGURATION_ERROR", caught.exception.code)
        self.assertNotIn(str(self.work.storage), json.dumps(caught.exception.to_dict(),
                                                             ensure_ascii=False))


class PrimitivePolicyAndTransferTests(unittest.TestCase):
    def setUp(self):
        self.work = _Workspace()
        self.addCleanup(self.work.cleanup)
        self.env = _base_env(self.work.root)
        self.source = self.work.storage / "source.bin"
        self.source.write_bytes(b"synthetic-source")
        self.target = self.work.storage / "nested" / "target.bin"
        self.ctx = recipes.RunContext(self.env, self.work.root / "run")

    def test_default_transfer_refuses_overwrite_and_resume_reuses_identical(self):
        first = recipes._primitive_media_transfer(
            {"items": [{"source": str(self.source), "target": str(self.target)}]},
            self.ctx)
        self.assertEqual("ok", first["status"])
        second = recipes._primitive_media_transfer(
            {"items": [{"source": str(self.source), "target": str(self.target)}]},
            self.ctx)
        self.assertEqual("failed", second["status"])
        self.assertEqual("TARGET_EXISTS", second["failed"][0]["code"])
        resumed = recipes._primitive_media_transfer(
            {"items": [{"source": str(self.source), "target": str(self.target)}],
             "allowIdenticalReuse": True}, self.ctx)
        self.assertEqual("ok", resumed["status"])
        self.assertEqual(1, resumed["alreadyPresent"])
        self.assertTrue(resumed["copied"][0]["alreadyPresent"])
        self.target.write_bytes(b"different-content")
        mismatch = recipes._primitive_media_transfer(
            {"items": [{"source": str(self.source), "target": str(self.target)}],
             "allowIdenticalReuse": True}, self.ctx)
        self.assertEqual("failed", mismatch["status"])
        self.assertEqual("TARGET_EXISTS", mismatch["failed"][0]["code"])

    def test_transfer_accepts_file_ref_source(self):
        metadata = fileref.make_ref(self.source, env=self.env)
        result = recipes._primitive_media_transfer(
            {"items": [{"sourceFileRef": metadata["fileRef"],
                        "target": str(self.target)}]}, self.ctx)
        self.assertEqual("ok", result["status"])
        self.assertEqual(self.source.read_bytes(), self.target.read_bytes())

    def test_partial_copy_failure_removes_only_new_target(self):
        good_target = self.work.storage / "nested" / "good.bin"
        blocked_parent = self.work.storage / "blocked"
        blocked_parent.write_bytes(b"not-a-directory")
        bad_target = blocked_parent / "broken.bin"
        result = recipes._primitive_media_transfer(
            {"items": [
                {"source": str(self.source), "target": str(good_target)},
                {"source": str(self.source), "target": str(bad_target)},
            ]}, self.ctx)
        self.assertEqual("partial", result["status"])
        self.assertEqual(1, result["completedCount"])
        self.assertEqual(1, result["failedCount"])
        self.assertEqual("COPY_FAILED", result["failed"][0]["code"])
        self.assertTrue(good_target.is_file())
        self.assertFalse(bad_target.exists())
        self.assertTrue(self.source.is_file())

    def test_media_probe_and_hash_file_ref_hide_absolute_paths(self):
        metadata = fileref.make_ref(self.source, env=self.env)
        probe = recipes._primitive_media_probe(
            {"fileRef": metadata["fileRef"], "kind": "image"}, self.ctx)
        self.assertNotIn(str(self.work.storage), json.dumps(probe, ensure_ascii=False))
        self.assertEqual(metadata["fileRef"], probe["fileRef"])
        digest = recipes._primitive_media_fingerprint(
            {"fileRef": metadata["fileRef"], "algorithms": ["md5", "sha256"]},
            self.ctx)
        self.assertNotIn(str(self.work.storage), json.dumps(digest, ensure_ascii=False))
        self.assertIn("md5", digest)
        self.assertIn("sha256", digest)

    def test_policy_denial_is_shared_with_tools(self):
        config = execution.load_config(self.env)
        spec = recipes.PRIMITIVE_SPECS["media.transfer"]
        self.assertEqual("", execution.policy_denial(spec, config))
        readonly = execution.load_config(dict(self.env, QIMENG_AI_READONLY="1"))
        self.assertIn("readOnlyMode", execution.policy_denial(spec, readonly))
        limited = execution.load_config(dict(self.env,
                                             QIMENG_AI_GRANTED_PERMISSIONS="read"))
        self.assertEqual("local.files.write",
                         execution.policy_denial(spec, limited))
        denied = execution.policy_denied_error(spec, limited)
        self.assertEqual("PERMISSION_DENIED", denied.code)


class RecipeGuardTests(unittest.TestCase):
    def setUp(self):
        self.work = _Workspace()
        self.addCleanup(self.work.cleanup)
        self.env = _base_env(self.work.root)
        self.recipe_dir = _recipe_dir(self.work.root)
        self.env["QIMENG_AI_RECIPE_DIR"] = str(self.recipe_dir)
        self.source = self.work.storage / "source.bin"
        self.source.write_bytes(b"recipe-source")
        self.target = self.work.storage / "nested" / "target.bin"
        self.run_dir = self.work.root / "run"

    def _transfer_recipe(self, *, permissions=None, gate=True, name="test.transfer"):
        steps = []
        if gate:
            steps.append({"id": "gate", "gate": True, "message": "确认复制"})
        steps.append({
            "id": "transfer",
            "primitive": "media.transfer",
            "input": {"items": [{"source": "$inputs.source",
                                 "target": "$inputs.target"}]},
        })
        recipe = {
            "id": name,
            "version": "1.0.0",
            "title": "测试复制",
            "whenToUse": "测试",
            "inputs": {"type": "object", "properties": {
                "source": {"type": "string"}, "target": {"type": "string"}},
                "required": ["source", "target"]},
            "steps": steps,
        }
        if permissions is not None:
            recipe["permissions"] = permissions
        return _write_recipe(self.recipe_dir, recipe)

    def test_recipe_permissions_must_cover_primitive_steps(self):
        recipe_id = self._transfer_recipe(permissions=["read"])
        with self.assertRaises(recipes.RecipeError) as caught:
            recipes.run_recipe(recipe_id, {"source": str(self.source),
                                           "target": str(self.target)},
                               env=self.env, state_dir=str(self.run_dir), confirm=True)
        self.assertEqual("RECIPE_INVALID", caught.exception.code)
        self.assertFalse(self.target.exists())

    def test_missing_granted_permission_fails_before_first_step(self):
        recipe_id = self._transfer_recipe(permissions=["local.files.write"])
        env = dict(self.env, QIMENG_AI_GRANTED_PERMISSIONS="read")
        with self.assertRaises(errors.ToolError) as caught:
            recipes.run_recipe(recipe_id, {"source": str(self.source),
                                           "target": str(self.target)},
                               env=env, state_dir=str(self.run_dir), confirm=True)
        self.assertEqual("PERMISSION_DENIED", caught.exception.code)
        self.assertFalse(self.target.exists())

    def test_readonly_mode_blocks_side_effect_primitive(self):
        recipe_id = self._transfer_recipe(permissions=["local.files.write"])
        env = dict(self.env, QIMENG_AI_READONLY="1")
        with self.assertRaises(errors.ToolError) as caught:
            recipes.run_recipe(recipe_id, {"source": str(self.source),
                                           "target": str(self.target)},
                               env=env, state_dir=str(self.run_dir), confirm=True)
        self.assertEqual("TOOL_DISABLED", caught.exception.code)
        self.assertFalse(self.target.exists())

    def test_transfer_before_any_gate_requires_confirmation(self):
        recipe_id = self._transfer_recipe(permissions=["local.files.write"], gate=False)
        with self.assertRaises(recipes.RecipeError) as caught:
            recipes.run_recipe(recipe_id, {"source": str(self.source),
                                           "target": str(self.target)},
                               env=self.env, state_dir=str(self.run_dir), confirm=True)
        self.assertEqual("RECIPE_CONFIRMATION_REQUIRED", caught.exception.code)
        self.assertFalse(self.target.exists())

    def test_gate_blocks_then_resume_copies(self):
        recipe_id = self._transfer_recipe(permissions=["local.files.write"])
        with self.assertRaises(recipes.RecipeError) as caught:
            recipes.run_recipe(recipe_id, {"source": str(self.source),
                                           "target": str(self.target)},
                               env=self.env, state_dir=str(self.run_dir))
        self.assertEqual("RECIPE_CONFIRMATION_REQUIRED", caught.exception.code)
        result = recipes.run_recipe(
            recipe_id, {"source": str(self.source), "target": str(self.target)},
            env=self.env, state_dir=str(self.run_dir), confirm=True, resume=True)
        self.assertEqual("completed", result["status"])
        self.assertEqual(self.source.read_bytes(), self.target.read_bytes())

    def test_primitive_can_be_disabled_by_env(self):
        recipe_id = self._transfer_recipe(permissions=["local.files.write"])
        env = dict(self.env, QIMENG_AI_DISABLED_TOOLS="media.transfer")
        config = execution.load_config(env)
        self.assertIn("media.transfer", config.disabled)
        with self.assertRaises(errors.ToolError) as caught:
            recipes.run_recipe(recipe_id, {"source": str(self.source),
                                           "target": str(self.target)},
                               env=env, config=config, state_dir=str(self.run_dir),
                               confirm=True)
        self.assertEqual("TOOL_DISABLED", caught.exception.code)
        self.assertFalse(self.target.exists())

    def test_artifact_tamper_stops_resume(self):
        recipe = {
            "id": "test.artifact",
            "version": "1.0.0",
            "title": "产物校验",
            "whenToUse": "测试",
            "inputs": {"type": "object", "properties": {}},
            "steps": [{"id": "pick", "primitive": "select.first",
                       "input": {"values": [1]}}],
            "permissions": ["read"],
        }
        recipe_id = _write_recipe(self.recipe_dir, recipe)
        first = recipes.run_recipe(recipe_id, {}, env=self.env,
                                   state_dir=str(self.run_dir))
        self.assertEqual("completed", first["status"])
        artifact = self.run_dir / "artifacts" / "pick.json"
        artifact.write_text(artifact.read_text(encoding="utf-8") + "\n",
                            encoding="utf-8")
        with self.assertRaises(errors.ToolError) as caught:
            recipes.run_recipe(recipe_id, {}, env=self.env, state_dir=str(self.run_dir),
                               resume=True)
        self.assertEqual("RECIPE_ARTIFACT_TAMPERED", caught.exception.code)

    def test_running_side_effect_step_requires_manual_review(self):
        recipe_id = self._transfer_recipe(permissions=["local.files.write"])
        calls = {"count": 0}

        def explode(payload, ctx):
            calls["count"] += 1
            raise RuntimeError("simulated crash")

        with mock.patch.dict(recipes.PRIMITIVES, {"media.transfer": explode}):
            with self.assertRaises(recipes.RecipeError):
                recipes.run_recipe(recipe_id, {"source": str(self.source),
                                               "target": str(self.target)},
                                   env=self.env, state_dir=str(self.run_dir),
                                   confirm=True)
        state = json.loads((self.run_dir / "state.json").read_text(encoding="utf-8"))
        self.assertEqual("running", state["steps"]["transfer"]["status"])
        with self.assertRaises(errors.ToolError) as caught:
            recipes.run_recipe(recipe_id, {"source": str(self.source),
                                           "target": str(self.target)},
                               env=self.env, state_dir=str(self.run_dir),
                               confirm=True, resume=True)
        self.assertEqual("RECIPE_STEP_UNCERTAIN", caught.exception.code)
        self.assertEqual(1, calls["count"])

    def test_tool_and_primitive_version_changes_stop_resume(self):
        tool_recipe = {
            "id": "test.tool-version",
            "version": "1.0.0",
            "title": "工具版本",
            "whenToUse": "测试",
            "inputs": {"type": "object", "properties": {}},
            "steps": [{"id": "lookup", "tool": "author.query",
                       "input": {"name": "x"}}],
        }
        recipe_id = _write_recipe(self.recipe_dir, tool_recipe)
        recipes.run_recipe(recipe_id, {}, env=self.env, state_dir=str(self.run_dir),
                           dry_run=True)
        state_path = self.run_dir / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["toolVersions"]["lookup"] = "0.0.0"
        state_path.write_text(json.dumps(state), encoding="utf-8")
        with self.assertRaises(recipes.RecipeError) as caught:
            recipes.run_recipe(recipe_id, {}, env=self.env, state_dir=str(self.run_dir),
                               resume=True, dry_run=True)
        self.assertEqual("RECIPE_STATE_VERSION", caught.exception.code)

        prim_recipe = {
            "id": "test.primitive-version",
            "version": "1.0.0",
            "title": "原语版本",
            "whenToUse": "测试",
            "inputs": {"type": "object", "properties": {}},
            "steps": [{"id": "pick", "primitive": "select.first",
                       "input": {"values": ["a"]}}],
        }
        prim_id = _write_recipe(self.recipe_dir, prim_recipe)
        prim_run = self.work.root / "prim-run"
        recipes.run_recipe(prim_id, {}, env=self.env, state_dir=str(prim_run),
                           dry_run=True)
        prim_state_path = prim_run / "state.json"
        prim_state = json.loads(prim_state_path.read_text(encoding="utf-8"))
        prim_state["toolVersions"]["pick"] = "0.0.0"
        prim_state_path.write_text(json.dumps(prim_state), encoding="utf-8")
        with self.assertRaises(recipes.RecipeError) as caught:
            recipes.run_recipe(prim_id, {}, env=self.env, state_dir=str(prim_run),
                               resume=True, dry_run=True)
        self.assertEqual("RECIPE_STATE_VERSION", caught.exception.code)

    def test_legacy_artifact_state_only_checks_existence(self):
        recipe = {
            "id": "test.legacy",
            "version": "1.0.0",
            "title": "旧状态兼容",
            "whenToUse": "测试",
            "inputs": {"type": "object", "properties": {}},
            "steps": [{"id": "pick", "primitive": "select.first",
                       "input": {"values": [1]}}],
            "permissions": ["read"],
        }
        recipe_id = _write_recipe(self.recipe_dir, recipe)
        recipes.run_recipe(recipe_id, {}, env=self.env, state_dir=str(self.run_dir))
        state_path = self.run_dir / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        # 模拟旧版 state：去掉顶层 bytes，保留 compact JSON 的旧 sha256。
        state["steps"]["pick"].pop("bytes", None)
        state["steps"]["pick"]["sha256"] = "0" * 64
        state_path.write_text(json.dumps(state), encoding="utf-8")
        result = recipes.run_recipe(recipe_id, {}, env=self.env,
                                    state_dir=str(self.run_dir), resume=True)
        self.assertEqual("completed", result["status"])
        self.assertEqual("existence-only", result["artifactVerification"])


class ResearchFileRefTests(unittest.TestCase):
    def setUp(self):
        self.work = _Workspace()
        self.addCleanup(self.work.cleanup)
        self.env = _base_env(self.work.root)
        self.file = self.work.storage / "nested" / "probe.png"
        self.file.write_bytes(b"probe-synthetic")

    def _run_handler(self, payload, *, out_dir):
        ctx = ToolContext(client=None, env=self.env, rules={})
        fake_run = {"exitCode": 0, "timedOut": False, "elapsedMs": 1,
                    "stdout": b"{}", "stderr": b""}
        with mock.patch.object(tools_research, "_script", return_value=Path("media_probe.py")), \
                mock.patch.object(tools_research, "_output_dir", return_value=out_dir), \
                mock.patch.object(tools_research, "_execute", return_value=fake_run):
            return tools_research.research_media_probe(ctx, payload)

    def test_file_refs_are_used_and_redacted_in_args(self):
        metadata = fileref.make_ref(self.file, env=self.env)
        out_dir = self.work.root / "out"
        result = self._run_handler({"fileRefs": [metadata["fileRef"]]}, out_dir=out_dir)
        serialized = json.dumps(result, ensure_ascii=False)
        self.assertNotIn(str(self.work.storage), serialized)
        self.assertIn("<fileRef:1>", result["summary"]["args"])
        self.assertTrue(any("fileRef 输入不会把物理路径" in note
                            for note in result["summary"]["notes"]))

    def test_path_input_keeps_existing_args(self):
        out_dir = self.work.root / "out"
        result = self._run_handler({"files": [str(self.file)]}, out_dir=out_dir)
        self.assertIn(str(self.file), result["summary"]["args"])
        self.assertNotIn("<media:1>", result["summary"]["args"])

    def test_files_and_file_refs_are_merged(self):
        other = self.work.storage / "nested" / "other.png"
        other.write_bytes(b"other-synthetic")
        metadata = fileref.make_ref(other, env=self.env)
        out_dir = self.work.root / "out"
        result = self._run_handler(
            {"files": [str(self.file)], "fileRefs": [metadata["fileRef"]]},
            out_dir=out_dir)
        args = result["summary"]["args"]
        self.assertIn("<media:1>", args)
        self.assertIn("<fileRef:1>", args)
        self.assertNotIn(str(self.work.storage), json.dumps(result, ensure_ascii=False))


class ExistingContractTests(unittest.TestCase):
    def setUp(self):
        self.work = _Workspace()
        self.addCleanup(self.work.cleanup)
        self.env = _base_env(self.work.root)

    def test_primitives_and_specs_are_aligned(self):
        self.assertEqual(set(recipes.PRIMITIVES), set(recipes.PRIMITIVE_SPECS))
        self.assertEqual(10, len(recipes.PRIMITIVES))
        self.assertEqual("local.files.write",
                         recipes.PRIMITIVE_SPECS["media.contact_sheet"].permission)
        self.assertFalse(recipes.PRIMITIVE_SPECS["media.contact_sheet"].requires_confirmation)
        for name in ("media.transfer", "device.push", "device.pull"):
            self.assertTrue(recipes.PRIMITIVE_SPECS[name].requires_confirmation, name)

    def test_existing_path_arguments_still_work(self):
        source = self.work.storage / "legacy.bin"
        source.write_bytes(b"legacy")
        ctx = recipes.RunContext(self.env, self.work.root / "run")
        probe = recipes._primitive_media_probe({"path": str(source), "kind": "image"}, ctx)
        self.assertEqual(str(source.resolve()), probe["path"])
        digest = recipes._primitive_media_fingerprint(
            {"path": str(source), "algorithms": ["md5", "sha256"]}, ctx)
        self.assertEqual(str(source.resolve()), digest["path"])
        self.assertIn("md5", digest)

    def test_media_scan_still_reads_authorized_root(self):
        root = self.work.storage / "scan"
        root.mkdir()
        (root / "a.png").write_bytes(b"a" * 10)
        (root / "b.png").write_bytes(b"b" * 10)
        result = media_scan.scan_directory(str(root), env=self.env, perceptual_limit=0)
        self.assertEqual(2, result["summary"]["fileCount"])

    def test_device_adb_local_paths_follow_read_write_modes(self):
        env = {"QIMENG_AI_READ_ROOTS": str(self.work.storage)}
        outside = self.work.root / "outside-device.bin"
        outside.write_bytes(b"x")
        with self.assertRaises(errors.ToolError) as caught:
            device_adb.push_file(str(outside), "/sdcard/qimeng", env=env)
        self.assertEqual("PATH_NOT_AUTHORIZED", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            device_adb.pull_file("/sdcard/qimeng/x.bin", str(outside),
                                 env={"QIMENG_AI_WRITE_ROOTS": str(self.work.storage)})
        self.assertEqual("PATH_NOT_AUTHORIZED", caught.exception.code)

    def test_research_media_probe_schema_keeps_files_and_adds_file_refs(self):
        spec = registry.get("research.media.probe")
        self.assertIsNotNone(spec)
        problems = __import__("ai_maintenance.schema", fromlist=["validate"]).validate(
            {"files": [str(self.work.storage / "x.png")]}, spec.input_schema)
        self.assertEqual([], problems)
        self.assertIn("fileRefs", spec.input_schema.get("properties", {}))

    def test_task_card_documents_resume_gate_policy(self):
        card = recipes.task_card("media.batch", self.env)
        self.assertIn("已完成的门禁不重复", card["resumePolicy"])


if __name__ == "__main__":
    unittest.main()
