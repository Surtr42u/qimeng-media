"""研究产物受控引用（artifactRef）回归：发布、读取、边界与工具接续。

全部使用隔离临时目录、合成文本/图片和假子进程；不访问真实库、不使用真实凭据，
也不要求本机安装 ffmpeg/ffprobe。
"""
from __future__ import annotations

import base64
import json
import os
import sys
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest import mock

TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))

from ai_maintenance import (artifact_ref, errors, mcp_server, model_output, registry,  # noqa: E402
                            schema, tools_query, tools_research, versions)
from ai_maintenance.runtime import ToolContext  # noqa: E402

RULES = TOOLS_DIR / "ai_maintenance" / "rules.json"


class _Workspace:
    def __init__(self):
        self._temp = tempfile.TemporaryDirectory(prefix="qimeng-artifact-")
        self.root = Path(self._temp.name)
        self.inputs = self.root / "inputs"
        self.outputs = self.root / "outputs"
        self.research = self.root / "research"
        for directory in (self.inputs, self.outputs, self.research):
            directory.mkdir(parents=True)

    def cleanup(self):
        self._temp.cleanup()

    def env(self, *, token="synthetic-token-A", base="http://127.0.0.1:8541"):
        return {
            "QIMENG_API_BASE": base,
            "QIMENG_API_TOKEN": token,
            "QIMENG_AI_STATE_DIR": str(self.root / "state"),
            "QIMENG_AI_READ_ROOTS": str(self.inputs),
            "QIMENG_AI_WRITE_ROOTS": str(self.outputs),
            "QIMENG_AI_RESEARCH_DIR": str(self.research),
            "QIMENG_AI_OUTPUT_BUDGET_BYTES": "12288",
        }


class ArtifactRefTests(unittest.TestCase):
    def setUp(self):
        self.work = _Workspace()
        self.addCleanup(self.work.cleanup)
        self.env = self.work.env()
        self.rulepack = "rules-test"

    def _file(self, name="evidence.txt", data=b"synthetic evidence\n", directory=None):
        path = (directory or self.work.inputs) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def _publish(self, path, *, kind="evidence", label="合成证据",
                 source_step="test.publish", mime="", ttl=artifact_ref.DEFAULT_TTL_SECONDS):
        return artifact_ref.publish_file(
            path, env=self.env, tool_id="research.media.probe",
            rulepack_version=self.rulepack, kind=kind, label=label,
            source_step=source_step, mime=mime, ttl_seconds=ttl)

    def _manifest_file(self, reference):
        matches = list((self.work.root / "state").rglob(reference + ".json"))
        self.assertEqual(1, len(matches))
        return matches[0]

    def test_text_pages_preserve_utf8_and_offsets(self):
        source = ("第一行：合成证据\n" + "a" * 180 + "\n"
                  + "第三行：帧引用 a1_0123456789abcdef0123456789abcdef\n") * 3
        path = self._file("evidence.txt", source.encode("utf-8"))
        meta = self._publish(path)
        self.assertNotIn(str(self.work.root), json.dumps(meta))
        assembled = []
        offset = 0
        pages = 0
        while True:
            page = artifact_ref.read(
                meta["artifactRef"], env=self.env, rulepack_version=self.rulepack,
                offset=offset, limit=37)
            self.assertLessEqual(page["pageBytes"], 37)
            self.assertEqual("text", page["contentType"])
            assembled.append(page["text"])
            pages += 1
            if page["nextOffset"] is None:
                self.assertFalse(page["truncated"])
                break
            offset = page["nextOffset"]
            self.assertGreater(offset, 0)
        self.assertGreater(pages, 1)
        self.assertEqual(source, "".join(assembled))

    def test_expired_artifact_ref_is_rejected(self):
        path = self._file()
        meta = self._publish(path, ttl=5)
        with mock.patch("ai_maintenance.artifact_ref.time.time",
                        return_value=meta["expiresAt"] + 1):
            with self.assertRaises(errors.ToolError) as caught:
                artifact_ref.read(meta["artifactRef"], env=self.env,
                                  rulepack_version=self.rulepack)
        self.assertEqual("ARTIFACT_EXPIRED", caught.exception.code)

    def test_tampered_manifest_is_rejected(self):
        path = self._file()
        meta = self._publish(path)
        manifest_path = self._manifest_file(meta["artifactRef"])
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["binding"]["label"] = "tampered"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(errors.ToolError) as caught:
            artifact_ref.resolve_path(meta["artifactRef"], env=self.env,
                                      rulepack_version=self.rulepack)
        self.assertEqual("ARTIFACT_TAMPERED", caught.exception.code)

    def test_same_size_replacement_is_rejected(self):
        path = self._file("frame.webp", b"AAAA")
        meta = self._publish(path, kind="frame", mime="image/webp")
        path.write_bytes(b"BBBB")
        with self.assertRaises(errors.ToolError) as caught:
            artifact_ref.read(meta["artifactRef"], env=self.env,
                              rulepack_version=self.rulepack)
        self.assertEqual("ARTIFACT_CONTENT_CHANGED", caught.exception.code)

    def test_cross_credentials_instance_and_versions_are_rejected(self):
        path = self._file()
        meta = self._publish(path)
        other_credential = dict(self.env, QIMENG_API_TOKEN="synthetic-token-B")
        with self.assertRaises(errors.ToolError) as caught:
            artifact_ref.read(meta["artifactRef"], env=other_credential,
                              rulepack_version=self.rulepack)
        self.assertEqual("ARTIFACT_BINDING_MISMATCH", caught.exception.code)
        other_instance = dict(self.env, QIMENG_API_BASE="http://127.0.0.1:8542")
        with self.assertRaises(errors.ToolError) as caught:
            artifact_ref.read(meta["artifactRef"], env=other_instance,
                              rulepack_version=self.rulepack)
        self.assertEqual("ARTIFACT_BINDING_MISMATCH", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            artifact_ref.read(meta["artifactRef"], env=self.env,
                              rulepack_version="rules-other")
        self.assertEqual("ARTIFACT_BINDING_MISMATCH", caught.exception.code)
        with mock.patch.object(versions, "PLATFORM_VERSION", "9.9.9"):
            with self.assertRaises(errors.ToolError) as caught:
                artifact_ref.read(meta["artifactRef"], env=self.env,
                                  rulepack_version=self.rulepack)
        self.assertEqual("ARTIFACT_BINDING_MISMATCH", caught.exception.code)

    def test_removed_authorization_root_is_rejected(self):
        path = self._file()
        meta = self._publish(path)
        other_inputs = self.work.root / "other-inputs"
        other_outputs = self.work.root / "other-outputs"
        other_inputs.mkdir()
        other_outputs.mkdir()
        env = dict(self.env, QIMENG_AI_READ_ROOTS=str(other_inputs),
                   QIMENG_AI_WRITE_ROOTS=str(other_outputs))
        with self.assertRaises(errors.ToolError) as caught:
            artifact_ref.read(meta["artifactRef"], env=env,
                              rulepack_version=self.rulepack)
        self.assertEqual("ARTIFACT_ROOT_UNAVAILABLE", caught.exception.code)

    def test_links_directories_and_outside_paths_are_rejected(self):
        outside = self.work.root / "outside.txt"
        outside.write_text("outside", encoding="utf-8")
        with self.assertRaises(errors.ToolError) as caught:
            self._publish(outside)
        self.assertEqual("ARTIFACT_PATH_UNSAFE", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            self._publish(self.work.inputs)
        self.assertEqual("ARTIFACT_PATH_UNSAFE", caught.exception.code)
        target = self._file("target.webp", b"RIFF-synthetic")
        link = self.work.inputs / "link.webp"
        try:
            os.symlink(target, link)
        except (OSError, NotImplementedError) as exc:
            self.skipTest("当前环境无法创建符号链接：" + type(exc).__name__)
        with self.assertRaises(errors.ToolError) as caught:
            self._publish(link, kind="frame", mime="image/webp")
        self.assertEqual("ARTIFACT_PATH_UNSAFE", caught.exception.code)

    def test_artifact_count_and_private_blob_limits(self):
        with mock.patch.object(artifact_ref, "MAX_ARTIFACTS", 1):
            first = self._file("one.txt", b"one")
            self._publish(first)
            second = self._file("two.txt", b"two")
            with self.assertRaises(errors.ToolError) as caught:
                self._publish(second)
        self.assertEqual("ARTIFACT_STORE_LIMIT", caught.exception.code)
        with mock.patch.object(artifact_ref, "MAX_BLOB_TOTAL_BYTES", 4):
            with self.assertRaises(errors.ToolError) as caught:
                artifact_ref.publish_bytes(
                    b"12345", env=self.env, tool_id="research.media.probe",
                    rulepack_version=self.rulepack, kind="summary", label="合成摘要")
        self.assertEqual("ARTIFACT_STORE_LIMIT", caught.exception.code)

    def test_publish_bytes_and_json_round_trip(self):
        raw = artifact_ref.publish_bytes(
            b'{"candidate":"synthetic"}\n', env=self.env,
            tool_id="research.media.probe", rulepack_version=self.rulepack,
            kind="evidence", label="合成证据", mime="application/x-ndjson")
        page = artifact_ref.read(raw["artifactRef"], env=self.env,
                                 rulepack_version=self.rulepack)
        self.assertEqual('{"candidate":"synthetic"}\n', page["text"])
        encoded = artifact_ref.publish_json(
            {"candidate": "synthetic", "score": 0.5}, env=self.env,
            tool_id="research.media.probe", rulepack_version=self.rulepack,
            kind="summary", label="候选摘要")
        page = artifact_ref.read(encoded["artifactRef"], env=self.env,
                                 rulepack_version=self.rulepack)
        self.assertEqual({"candidate": "synthetic", "score": 0.5},
                         json.loads(page["text"]))
        resolved = artifact_ref.resolve_path(encoded["artifactRef"], env=self.env,
                                             rulepack_version=self.rulepack)
        self.assertTrue(resolved.is_file())
        self.assertIn("artifacts", str(resolved))

    def test_image_content_is_bounded_and_mcp_returns_image_block(self):
        image = b"\x89PNG\r\n\x1a\n" + b"synthetic-image" * 8
        path = self._file("frame.png", image)
        meta = self._publish(path, kind="frame", label="抽帧 15%", mime="image/png")
        page = artifact_ref.read(meta["artifactRef"], env=self.env,
                                 rulepack_version=self.rulepack, include_content=True)
        self.assertEqual(image, base64.b64decode(page["imageBase64"]))
        self.assertTrue(page["contentIncluded"])
        metadata_only = artifact_ref.read(meta["artifactRef"], env=self.env,
                                          rulepack_version=self.rulepack)
        self.assertNotIn("imageBase64", metadata_only)
        oversized = self._file("large.png", b"x" * (artifact_ref.MAX_IMAGE_BYTES + 1))
        oversized_meta = self._publish(oversized, kind="frame", mime="image/png")
        with self.assertRaises(errors.ToolError) as caught:
            artifact_ref.read(oversized_meta["artifactRef"], env=self.env,
                              rulepack_version=self.rulepack, include_content=True)
        self.assertEqual("ARTIFACT_CONTENT_TOO_LARGE", caught.exception.code)

        class _Config:
            rulepack_version = "rules-test"
            granted_permissions = frozenset({"read"})

            def document(self):
                return None

        fake = {
            "artifactRef": meta["artifactRef"], "sourceTool": "research.media.probe",
            "kind": "frame", "label": "抽帧 15%", "mime": "image/png",
            "bytes": len(image), "sha256": "0" * 64, "sourceStep": "test",
            "createdAt": 1, "expiresAt": 2, "contentType": "image",
            "contentIncluded": True, "imageBase64": base64.b64encode(image).decode("ascii"),
        }
        server = mcp_server.McpServer(
            env={"QIMENG_AI_STATE_DIR": str(self.work.root / "mcp-state")},
            execute=lambda name, _arguments: (registry.get(name), dict(fake)))
        server.config = _Config()
        response = server._tools_call(
            1, {"name": "platform.artifact.read",
                "arguments": {"artifactRef": meta["artifactRef"], "includeContent": True}},
            set())
        content = response["result"]["content"]
        self.assertEqual("text", content[0]["type"])
        self.assertEqual({"type": "image", "data": fake["imageBase64"],
                          "mimeType": "image/png"}, content[1])
        structured = json.dumps(response["result"]["structuredContent"])
        self.assertNotIn(fake["imageBase64"], structured)
        self.assertNotIn('"imageBase64"', structured)
        self.assertFalse(response["result"]["isError"])

    def test_concurrent_publish_and_read(self):
        def publish(index):
            path = self._file("frame-%d.txt" % index,
                              ("synthetic-%d" % index).encode("ascii"))
            return self._publish(path, kind="frame", mime="text/plain")

        with ThreadPoolExecutor(max_workers=6) as pool:
            metas = list(pool.map(publish, range(8)))
        refs = {item["artifactRef"] for item in metas}
        self.assertEqual(8, len(refs))
        for meta in metas:
            page = artifact_ref.read(meta["artifactRef"], env=self.env,
                                     rulepack_version=self.rulepack)
            self.assertTrue(page["text"].startswith("synthetic-"))

    def test_large_probe_summary_keeps_artifact_refs(self):
        reference = "a1_0123456789abcdef0123456789abcdef"
        frame_refs = [{"index": index, "percent": 15, "artifactRef": reference,
                       "label": "抽帧 15%", "mime": "image/webp", "bytes": 10,
                       "sha256": "0" * 64} for index in range(1, 7)]
        result = {
            "status": "ok", "exitCode": 0,
            "artifacts": [{"path": str(self.work.inputs / ("frame-%d.webp" % index)),
                           "sha256": "0" * 64, "bytes": 10} for index in range(50)],
            "summary": {"stdoutTail": "x" * 400, "stderrTail": "y" * 1200,
                        "args": ["--out", str(self.work.research / "out")],
                        "notes": ["z" * 400]},
            "artifactRefs": [{"artifactRef": reference, "kind": "frame"}],
            "frameRefs": frame_refs,
            "artifactRefStatus": {"status": "available", "code": "", "message": ""},
            "padding": "p" * 8000,
        }
        presented = model_output.present_result(
            "research.media.probe", result, env=self.env, rulepack_version="rules-test")
        self.assertTrue(presented["truncated"])
        self.assertIn("resultRef", presented)
        self.assertEqual(reference, presented["frameRefs"][0]["artifactRef"])
        self.assertEqual(5, len(presented["frameRefs"]))
        self.assertEqual(2, presented["frameRefs"][4]["omittedItems"])
        self.assertEqual(reference, presented["artifactRefs"][0]["artifactRef"])
        self.assertEqual("available", presented["artifactRefStatus"]["status"])

    def test_tool_registration_and_rules_reference(self):
        spec = registry.get("platform.artifact.read")
        self.assertIsNotNone(spec)
        self.assertEqual("read", spec.permission)
        self.assertTrue(spec.read_only)
        self.assertFalse(spec.side_effects)
        self.assertEqual("system", spec.domain)
        rules = json.loads(RULES.read_text(encoding="utf-8"))
        self.assertIn("researchArtifacts", rules)
        self.assertIn("researchArtifacts", registry.TOOL_RULES_REFS["platform.artifact.read"])
        problems = schema.validate(
            {"artifactRef": "a1_0123456789abcdef0123456789abcdef"},
            spec.input_schema)
        self.assertEqual([], problems)


class ProbeHandoffTests(unittest.TestCase):
    def setUp(self):
        self.work = _Workspace()
        self.addCleanup(self.work.cleanup)
        self.env = self.work.env()
        self.ctx = ToolContext(env=self.env, rulepack_version="rules-test")
        self.media = self.work.inputs / "synthetic.mp4"
        self.media.write_bytes(b"synthetic video placeholder")

    def _fake_probe(self, argv, *, env, timeout):
        out = Path(argv[argv.index("--out") + 1])
        frame_dir = Path(argv[argv.index("--frame-dir") + 1])
        frame_dir.mkdir(parents=True, exist_ok=True)
        frame = frame_dir / "frame-15.webp"
        frame.write_bytes(b"RIFF-synthetic-webp")
        record = {
            "status": "ok", "name": "synthetic.mp4", "frames": [
                {"pct": 15, "crop": "full", "file": frame.name,
                 "bytes": frame.stat().st_size},
            ],
        }
        out.write_text(json.dumps(record) + "\n", encoding="utf-8")
        return {"exitCode": 0, "timedOut": False, "elapsedMs": 5,
                "stdout": b"", "stderr": b""}

    def test_probe_publishes_frame_refs_and_lens_continues_without_paths(self):
        with mock.patch.object(tools_research, "_execute", side_effect=self._fake_probe):
            result = tools_research.research_media_probe(
                self.ctx, {"files": [str(self.media)], "frames": "15"})
        status = result["artifactRefStatus"]
        self.assertEqual("available", status["status"])
        self.assertEqual(1, len(result["frameRefs"]))
        self.assertGreaterEqual(len(result["artifactRefs"]), 2)
        frame_ref = result["frameRefs"][0]
        self.assertEqual(15, frame_ref["percent"])
        self.assertTrue(frame_ref["artifactRef"].startswith("a1_"))
        self.assertEqual("image/webp", frame_ref["mime"])
        resolved = artifact_ref.resolve_path(
            frame_ref["artifactRef"], env=self.env, rulepack_version="rules-test")
        self.assertTrue(resolved.is_file())

        class _Client:
            def __init__(self):
                self.data = b""

            def post(self, path, data=None, content_type=None, timeout=None):
                self.data = data
                return {"status": "ok"}

        client = _Client()
        lens_ctx = ToolContext(env=self.env, rulepack_version="rules-test", client=client)
        answer = tools_query.research_lens(
            lens_ctx, {"artifactRef": frame_ref["artifactRef"]})
        self.assertIn(b"RIFF-synthetic-webp", client.data)
        self.assertNotIn(str(resolved), json.dumps(answer))

    def test_yandex_and_jobs_refs_mask_absolute_paths(self):
        frame = self.work.inputs / "frame.webp"
        frame.write_bytes(b"RIFF-synthetic-webp")
        frame_meta = artifact_ref.publish_file(
            frame, env=self.env, tool_id="research.media.probe",
            rulepack_version="rules-test", kind="frame", label="抽帧 15%",
            mime="image/webp", source_step="test")
        jobs = self.work.inputs / "jobs.jsonl"
        jobs.write_text('{"id":"synthetic","file":"frame.webp"}\n', encoding="utf-8")
        jobs_meta = artifact_ref.publish_file(
            jobs, env=self.env, tool_id="research.media.probe",
            rulepack_version="rules-test", kind="jobs", label="jobs.jsonl",
            mime="application/x-ndjson", source_step="test")
        calls = []

        def fake_execute(argv, *, env, timeout):
            calls.append(argv)
            return {"exitCode": 0, "timedOut": False, "elapsedMs": 1,
                    "stdout": b"", "stderr": b""}

        with mock.patch.object(tools_research, "_execute", side_effect=fake_execute):
            yandex = tools_research.research_image_search(
                self.ctx, {"script": "yandex", "artifactRef": frame_meta["artifactRef"]})
            saucenao = tools_research.research_image_search(
                self.ctx, {"script": "saucenao", "jobsRef": jobs_meta["artifactRef"]})
        self.assertEqual(2, len(calls))
        self.assertIn("<artifactRef:artifact>", yandex["summary"]["args"])
        self.assertIn("<jobsRef:artifact>", saucenao["summary"]["args"])
        for result in (yandex, saucenao):
            text = json.dumps(result)
            self.assertNotIn(str(self.work.inputs), text)
        with self.assertRaises(errors.ToolError) as caught:
            tools_research.research_image_search(
                self.ctx, {"script": "yandex",
                           "imagePath": str(frame),
                           "artifactRef": frame_meta["artifactRef"]})
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        with self.assertRaises(errors.ToolError) as caught:
            tools_query.research_lens(
                ToolContext(env=self.ctx.env, rulepack_version=self.ctx.rulepack_version,
                            client=None), {})
        self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_probe_without_api_config_reports_unavailable_refs(self):
        env = {
            "QIMENG_AI_STATE_DIR": str(self.work.root / "state-no-api"),
            "QIMENG_AI_READ_ROOTS": str(self.work.inputs),
            "QIMENG_AI_WRITE_ROOTS": str(self.work.outputs),
            "QIMENG_AI_RESEARCH_DIR": str(self.work.research),
        }
        ctx = ToolContext(env=env, rulepack_version="rules-test")
        with mock.patch.object(tools_research, "_execute", side_effect=self._fake_probe):
            result = tools_research.research_media_probe(
                ctx, {"files": [str(self.media)], "frames": "15"})
        self.assertEqual("ok", result["status"])
        status = result["artifactRefStatus"]
        self.assertEqual("unavailable", status["status"])
        self.assertEqual("ARTIFACT_CONTEXT_INVALID", status["code"])
        self.assertTrue(status["message"])
        self.assertEqual([], result["frameRefs"])
        self.assertEqual([], result["artifactRefs"])


if __name__ == "__main__":
    unittest.main()
