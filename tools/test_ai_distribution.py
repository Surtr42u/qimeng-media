"""最小可移植 AI 工具包分发回归：只用临时目录与合成文件，不访问真实 API/媒体。"""
from __future__ import annotations

import contextlib
import io
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

from ai_maintenance import package, versions

ROOT = Path(__file__).resolve().parents[1]
_TEMP_ROOT = ROOT / "tools" / "ai_maintenance" / ".test-temp"
try:
    _TEMP_ROOT.mkdir(parents=True, exist_ok=True)
except OSError:
    _TEMP_ROOT = Path(tempfile.gettempdir())


def new_temp_dir(prefix: str) -> Path:
    path = _TEMP_ROOT / (prefix + uuid.uuid4().hex)
    os.makedirs(path)
    return path


def remove_tree(path: Path) -> None:
    shutil.rmtree(path, ignore_errors=True)


class DistributionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.root = new_temp_dir("distribution-class-")
        output = cls.root / "out"
        cls.build_result = package.build_bundle(
            output, include_research_kit=True, include_docs=True
        )
        cls.bundle = output / cls.build_result["bundle"]
        cls.manifest_path = cls.bundle / package.MANIFEST_NAME
        cls.manifest = json.loads(cls.manifest_path.read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls) -> None:
        remove_tree(cls.root)

    def copy_bundle(self) -> Path:
        case = new_temp_dir("distribution-case-")
        self.addCleanup(remove_tree, case)
        target = case / "bundle"
        shutil.copytree(self.bundle, target)
        return target

    def doctor_env(self) -> dict:
        env = {
            key: value for key, value in os.environ.items()
            if key not in ("PYTHONPATH", "PYTHONHOME", "PYTHONSAFEPATH")
            and not key.startswith("QIMENG_")
        }
        missing = self.root / "missing-optional"
        env.update({
            "QIMENG_API_BASE": "http://127.0.0.1:18568",
            "QIMENG_API_TOKEN": "synthetic-test-token",
            "PATH": "",
            "QIMENG_RESEARCH_KIT_NODE": str(missing / "node"),
            "QIMENG_AI_NODE": str(missing / "node"),
            "QIMENG_AI_FFMPEG": str(missing / "ffmpeg"),
            "QIMENG_FFMPEG": str(missing / "ffmpeg"),
            "QIMENG_RESEARCH_KIT_FFMPEG": str(missing / "ffmpeg"),
            "QIMENG_AI_FFPROBE": str(missing / "ffprobe"),
            "QIMENG_FFPROBE": str(missing / "ffprobe"),
            "QIMENG_RESEARCH_KIT_FFPROBE": str(missing / "ffprobe"),
            "QIMENG_RESEARCH_KIT_EDGE": str(missing / "browser"),
            "QIMENG_ADB": str(missing / "adb"),
            "QIMENG_ADB_SERIAL": "",
        })
        return env

    # ------------------------------------------------------------------ #
    # 构建与清单
    # ------------------------------------------------------------------ #

    def test_build_layout_and_manifest(self) -> None:
        bundle = self.bundle
        for rel in (
            "tools/ai_maintenance/cli.py",
            "tools/ai_maintenance/package.py",
            "tools/ai_maintenance/rules.json",
            "tools/ai_maintenance/versions.py",
            "tools/ai_maintenance/recipes/author.discover.json",
            "tools/research_kit/media_probe.py",
            "api/openapi.yaml",
            "qai",
            "qai.cmd",
            "INSTALL.md",
            "README.md",
        ):
            self.assertTrue((bundle / rel).is_file(), rel)
        self.assertTrue((bundle / "docs" / "AI_PLATFORM_DISTRIBUTION.md").is_file())
        self.assertFalse(any(path.name == "__pycache__" for path in bundle.rglob("*")))
        self.assertFalse(any(path.name == ".work" for path in bundle.rglob("*")))
        self.assertFalse(any(path.name == ".test-temp" for path in bundle.rglob("*")))
        self.assertFalse(any(path.name == "rulepack.json" for path in bundle.rglob("*")))

        manifest = self.manifest
        self.assertEqual(manifest["schemaVersion"], package.MANIFEST_SCHEMA_VERSION)
        self.assertEqual(manifest["platformVersion"], versions.PLATFORM_VERSION)
        self.assertEqual(manifest["toolContractVersion"], versions.TOOL_CONTRACT_VERSION)
        self.assertEqual(manifest["planVersion"], versions.PLAN_VERSION)
        self.assertTrue(manifest["rulepackVersion"])
        self.assertRegex(manifest["openapiSha256"], r"^[0-9a-f]{64}$")
        self.assertIsInstance(manifest["gitRevision"], str)
        self.assertTrue(manifest["gitRevision"])
        self.assertTrue(manifest["files"])

        paths = [item["path"] for item in manifest["files"]]
        self.assertEqual(paths, sorted(paths))
        self.assertNotIn(package.MANIFEST_NAME, paths)
        for prefix in ("server/", "web/", "android/", "desktop/"):
            self.assertFalse(any(path.startswith(prefix) for path in paths), prefix)
        self.assertFalse(any(path.endswith((".db", ".sqlite", ".sqlite3")) for path in paths))
        for item in manifest["files"]:
            path = item["path"]
            self.assertFalse(path.startswith("/"))
            self.assertNotIn("\\", path)
            self.assertNotIn("..", Path(path).parts)
            self.assertIs(type(item["bytes"]), int)
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")

        raw = self.manifest_path.read_text(encoding="utf-8")
        self.assertNotIn(str(ROOT), raw)
        self.assertNotIn(str(ROOT).replace("\\", "/"), raw)
        home = str(Path.home())
        if len(home) > 2:
            self.assertNotIn(home, raw)
            self.assertNotIn(home.replace("\\", "/"), raw)
        self.assertNotIn("{{", raw)
        install = (bundle / "INSTALL.md").read_text(encoding="utf-8")
        self.assertNotIn("{{", install)
        self.assertIn(versions.PLATFORM_VERSION, install)
        self.assertIn(package.MANIFEST_NAME, install)

    def test_build_without_research_kit_and_docs(self) -> None:
        output = self.root / "minimal-out"
        result = package.build_bundle(
            output, include_research_kit=False, include_docs=False
        )
        bundle = output / result["bundle"]
        self.assertFalse((bundle / "tools" / "research_kit").exists())
        self.assertFalse((bundle / "docs" / "AI_PLATFORM_DISTRIBUTION.md").exists())
        self.assertTrue((bundle / "INSTALL.md").is_file())
        self.assertTrue((bundle / "README.md").is_file())
        manifest = json.loads((bundle / package.MANIFEST_NAME).read_text(encoding="utf-8"))
        paths = [item["path"] for item in manifest["files"]]
        self.assertFalse(any(path.startswith("tools/research_kit/") for path in paths))
        self.assertFalse(any(path.startswith("docs/") for path in paths))
        self.assertIn("tools/ai_maintenance/cli.py", paths)

    def test_manifest_rebuild_is_stable_and_read_only(self) -> None:
        before = self.manifest_path.read_bytes()
        rebuilt = package.manifest_for_bundle(self.bundle)
        after = self.manifest_path.read_bytes()
        self.assertEqual(before, after)
        self.assertEqual(rebuilt["files"], self.manifest["files"])
        self.assertEqual(rebuilt["generatedAt"], self.manifest["generatedAt"])
        self.assertEqual(rebuilt["gitRevision"], self.manifest["gitRevision"])

        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = package.main([
                "manifest", "--bundle", str(self.bundle),
                "--out", str(self.bundle / "INSTALL.md"),
            ])
        self.assertEqual(code, 2)
        self.assertTrue((self.bundle / "INSTALL.md").is_file())

    # ------------------------------------------------------------------ #
    # verify
    # ------------------------------------------------------------------ #

    def test_verify_accepts_clean_bundle(self) -> None:
        result = package.verify_bundle(self.bundle)
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["missing"], [])
        self.assertEqual(result["extra"], [])
        self.assertEqual(result["modified"], [])
        self.assertEqual(result["checked"], len(self.manifest["files"]))

    def test_verify_reports_modified(self) -> None:
        bundle = self.copy_bundle()
        target = bundle / "tools" / "ai_maintenance" / "rules.json"
        target.write_bytes(target.read_bytes() + b"\n")
        result = package.verify_bundle(bundle)
        self.assertFalse(result["ok"])
        changed = [item for item in result["modified"]
                   if item["path"] == "tools/ai_maintenance/rules.json"]
        self.assertEqual(len(changed), 1)
        self.assertIn(changed[0]["reason"], ("bytes", "sha256"))

    def test_verify_reports_sha256_change(self) -> None:
        bundle = self.copy_bundle()
        target = bundle / "tools" / "ai_maintenance" / "rules.json"
        raw = bytearray(target.read_bytes())
        raw[0] = ord("[") if raw[0] != ord("[") else ord("{")
        target.write_bytes(bytes(raw))
        result = package.verify_bundle(bundle)
        self.assertFalse(result["ok"])
        changed = [item for item in result["modified"]
                   if item["path"] == "tools/ai_maintenance/rules.json"]
        self.assertEqual([{"path": "tools/ai_maintenance/rules.json", "reason": "sha256"}],
                         changed)

    def test_verify_reports_missing(self) -> None:
        bundle = self.copy_bundle()
        (bundle / "api" / "openapi.yaml").unlink()
        result = package.verify_bundle(bundle)
        self.assertFalse(result["ok"])
        self.assertIn("api/openapi.yaml", result["missing"])
        self.assertNotIn(str(bundle), json.dumps(result, ensure_ascii=False))

    def test_verify_reports_extra(self) -> None:
        bundle = self.copy_bundle()
        (bundle / "extra-note.txt").write_text("synthetic extra", encoding="utf-8")
        result = package.verify_bundle(bundle)
        self.assertFalse(result["ok"])
        self.assertIn("extra-note.txt", result["extra"])
        self.assertNotIn(str(bundle), json.dumps(result, ensure_ascii=False))

    def test_verify_does_not_follow_symlink(self) -> None:
        bundle = self.copy_bundle()
        outside = bundle.parent / "outside.txt"
        outside.write_text("synthetic outside", encoding="utf-8")
        link = bundle / "extra-link.txt"
        try:
            os.symlink(outside, link)
        except (OSError, NotImplementedError):
            self.skipTest("当前环境不支持创建符号链接")
        result = package.verify_bundle(bundle)
        self.assertFalse(result["ok"])
        self.assertIn("extra-link.txt", result["extra"])
        self.assertEqual([], [item for item in result["modified"]
                              if item["path"] == "extra-link.txt"])

    # ------------------------------------------------------------------ #
    # 仓库外运行与 doctor
    # ------------------------------------------------------------------ #

    def test_bundle_cli_runs_outside_workspace(self) -> None:
        outside = new_temp_dir("distribution-outside-")
        self.addCleanup(remove_tree, outside)
        env = {
            key: value for key, value in os.environ.items()
            if key not in ("PYTHONPATH", "PYTHONHOME", "PYTHONSAFEPATH")
            and not key.startswith("QIMENG_")
        }
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        process = subprocess.run(
            [sys.executable, "-B", "-X", "utf8",
             str(self.bundle / "tools" / "ai_maintenance" / "cli.py"), "version"],
            cwd=outside, env=env, capture_output=True, encoding="utf-8",
            errors="replace", timeout=60,
        )
        self.assertEqual(process.returncode, 0, process.stderr)
        report = json.loads(process.stdout)
        self.assertEqual(report["platformVersion"], versions.PLATFORM_VERSION)
        self.assertEqual(report["toolContractVersion"], versions.TOOL_CONTRACT_VERSION)
        self.assertEqual(report["rulepackVersion"], self.manifest["rulepackVersion"])
        self.assertEqual(report["openapi"]["sha256"], self.manifest["openapiSha256"])

    def test_doctor_warns_for_missing_optional_dependencies(self) -> None:
        report = package.doctor(self.bundle, env=self.doctor_env())
        self.assertEqual(report["exitCode"], 0, json.dumps(report, ensure_ascii=False))
        self.assertTrue(report["ok"])
        warnings = {
            check["id"]: check for check in report["checks"]
            if check["status"] == "warning"
        }
        for check_id in (
            "optional.node", "optional.ffmpeg", "optional.ffprobe",
            "optional.browser", "optional.adb", "optional.sidecar",
        ):
            self.assertIn(check_id, warnings)
        components = report["capabilities"]["research.local"]["components"]
        self.assertEqual(components["lensBatch"]["status"], "partial")
        self.assertEqual(components["mediaProbe"]["status"], "partial")
        self.assertEqual(components["yandexCbir"]["status"], "available")
        rendered = json.dumps(report, ensure_ascii=False)
        self.assertNotIn("synthetic-test-token", rendered)
        self.assertNotIn(str(self.root), rendered)
        self.assertNotIn(str(self.bundle), rendered)

    def test_doctor_fails_for_missing_required_file(self) -> None:
        bundle = self.copy_bundle()
        (bundle / "tools" / "ai_maintenance" / "rules.json").unlink()
        report = package.doctor(bundle, env=self.doctor_env())
        self.assertEqual(report["exitCode"], 2)
        self.assertFalse(report["ok"])
        required = [check for check in report["checks"]
                    if check["id"] == "files.required"]
        self.assertEqual(len(required), 1)
        self.assertEqual(required[0]["status"], "error")
        self.assertIn("tools/ai_maintenance/rules.json", required[0]["message"])

    def test_doctor_fails_on_extra_file(self) -> None:
        bundle = self.copy_bundle()
        (bundle / "extra-note.txt").write_text("synthetic extra", encoding="utf-8")
        report = package.doctor(bundle, env=self.doctor_env())
        self.assertEqual(report["exitCode"], 2)
        extra = [check for check in report["checks"]
                 if check["id"] == "manifest.extra"]
        self.assertEqual(len(extra), 1)
        self.assertEqual(extra[0]["status"], "error")

    def test_doctor_fails_on_manifest_mismatch(self) -> None:
        bundle = self.copy_bundle()
        target = bundle / "tools" / "ai_maintenance" / "versions.py"
        target.write_text(target.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        report = package.doctor(bundle, env=self.doctor_env())
        self.assertEqual(report["exitCode"], 2)
        integrity = [check for check in report["checks"]
                     if check["id"] == "manifest.integrity"]
        self.assertEqual(integrity[0]["status"], "error")

    # ------------------------------------------------------------------ #
    # 安全与失败边界
    # ------------------------------------------------------------------ #

    def test_build_accepts_empty_existing_output(self) -> None:
        output = self.root / "empty-out"
        output.mkdir()
        result = package.build_bundle(
            output, include_research_kit=False, include_docs=False
        )
        self.assertTrue((output / result["bundle"]).is_dir())
        self.assertTrue(package.verify_bundle(output / result["bundle"])["ok"])

    def test_build_refuses_non_empty_output(self) -> None:
        with self.assertRaises(package.PackageError) as caught:
            package.build_bundle(self.root / "out")
        self.assertEqual(caught.exception.code, "OUT_NOT_EMPTY")
        self.assertTrue(package.verify_bundle(self.bundle)["ok"])

    def test_build_failure_leaves_no_partial_output(self) -> None:
        output = self.root / "atomic-out"
        with mock.patch.object(package, "_write_manifest",
                               side_effect=RuntimeError("synthetic failure")):
            with self.assertRaises(RuntimeError):
                package.build_bundle(output)
        self.assertFalse(output.exists())
        leftovers = [
            path for path in self.root.iterdir()
            if path.name.startswith(".qimeng-ai-toolkit-")
        ]
        self.assertEqual(leftovers, [])


if __name__ == "__main__":
    unittest.main()
