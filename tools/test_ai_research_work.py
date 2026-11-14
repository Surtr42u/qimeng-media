"""research.work 行为回归：单图/视频只读研究、证据分级、失败降级与 recipe 兼容。

全部使用隔离临时目录、合成字节和假客户端/假媒体原语；不访问真实网络、
不使用真实媒体或凭据，也不要求本机安装 ffmpeg/ffprobe。
"""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))

from ai_maintenance import (artifact_ref, client as http_client, errors, recipes,  # noqa: E402
                            registry, schema, tools_research_work as work)
from ai_maintenance.runtime import ToolContext  # noqa: E402


class _Workspace:
    def __init__(self):
        self._temp = tempfile.TemporaryDirectory(prefix="qimeng-research-work-")
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
            "QIMENG_AI_MEDIA_CACHE": str(self.root / "cache"),
            "QIMENG_AI_OUTPUT_BUDGET_BYTES": "12288",
        }


class _FakeClient:
    """只实现 research.context/Lens/provider 的假客户端，记录所有调用。"""

    def __init__(self, *, context=None, lens=None, provider=None):
        self.context = context
        self.lens = lens
        self.provider = provider
        self.calls: list[tuple] = []
        self.lens_payload_bytes = 0
        self.lens_content_type = ""
        self.provider_body = None

    def get(self, path, query=None):
        self.calls.append(("GET", path, None))
        return self._resolve(self.context)

    def post(self, path, json_body=None, data=None, content_type=None, timeout=None):
        self.calls.append(("POST", path, json_body))
        if path.endswith("/lens"):
            self.lens_payload_bytes = len(data or b"")
            self.lens_content_type = content_type or ""
            return self._resolve(self.lens)
        if "/providers/" in path:
            self.provider_body = json_body
            return self._resolve(self.provider)
        raise AssertionError("unexpected research path: " + path)

    @staticmethod
    def _resolve(value):
        if isinstance(value, Exception):
            raise value
        return value


def _context(*, enabled=True, sidecar_available=True, bing_enabled=True):
    return {
        "enabled": enabled,
        "sidecar": {"configured": sidecar_available,
                    "available": sidecar_available, "pending": 0},
        "providers": [{"name": "bing", "operations": ["search"],
                       "enabled": bing_enabled,
                       "limits": {"perMinute": 20, "perDay": 0}}],
        "limits": {"lensPerHour": 20, "lensPerDay": 100},
        "fetchedAt": "2026-11-14T00:00:00Z",
    }


def _lens(*, links=None, top_titles=None, top_domains=None, cites=None,
          status="ok"):
    return {
        "status": status,
        "frameSha256": "a" * 64,
        "topTitles": top_titles or [],
        "topDomains": top_domains or [],
        "cites": cites or [],
        "links": links or [],
        "elapsedMs": 1200,
    }


def _provider(*, items=None, status="ok"):
    return {
        "provider": "bing", "operation": "search", "target": "synthetic",
        "status": status, "fetchedAt": "2026-11-14T00:00:00Z",
        "latencyMs": 12, "items": items or [],
    }


class ResearchWorkTests(unittest.TestCase):
    def setUp(self):
        self.work = _Workspace()
        self.addCleanup(self.work.cleanup)
        self.env = self.work.env()
        self.rulepack = "rules-test"

    # ------------------------------------------------------------------ #
    # 测试辅助
    # ------------------------------------------------------------------ #

    def _run(self, payload, client, *, probe=None, perceptual=None,
             extract=None, ffmpeg="ffmpeg", ffprobe="ffprobe"):
        context = ToolContext(client=client, rulepack_version=self.rulepack,
                              env=self.env)
        probe_value = {"dimensions": {"width": 640, "height": 480}} \
            if probe is None else probe
        perceptual_value = {"status": "ok", "hashKind": "dhash64",
                            "frames": ["0" * 16]} \
            if perceptual is None else perceptual
        with mock.patch.object(work.media_primitives, "find_ffmpeg",
                               return_value=ffmpeg), \
                mock.patch.object(work.media_primitives, "find_ffprobe",
                                  return_value=ffprobe), \
                mock.patch.object(work.media_primitives, "probe_media",
                                  return_value=probe_value), \
                mock.patch.object(work.media_primitives, "perceptual_record",
                                  return_value=perceptual_value):
            if extract is None:
                with mock.patch.object(
                        work.media_primitives, "extract_frames",
                        side_effect=AssertionError("不应为静态图片抽帧")):
                    result = work.research_work(context, payload)
            else:
                with mock.patch.object(work.media_primitives, "extract_frames",
                                       side_effect=extract):
                    result = work.research_work(context, payload)
        problems = schema.validate(
            result, registry.get("research.work").output_schema)
        self.assertEqual([], problems)
        return result

    def _image(self, name="still.png"):
        path = self.work.inputs / name
        path.write_bytes(b"\x89PNG\r\n\x1a\nsynthetic-image")
        return path

    def _video(self, name="clip.mp4"):
        path = self.work.inputs / name
        path.write_bytes(b"synthetic-video-bytes")
        return path

    def _read_evidence(self, reference):
        chunks = []
        offset = 0
        while True:
            page = artifact_ref.read(reference, env=self.env,
                                     rulepack_version=self.rulepack,
                                     offset=offset, limit=4096)
            chunks.append(page["text"])
            if page["nextOffset"] is None:
                break
            offset = page["nextOffset"]
        return json.loads("".join(chunks))

    def _assert_private(self, result):
        encoded = json.dumps(result, ensure_ascii=False)
        self.assertNotIn(str(self.work.root), encoded)
        self.assertNotIn("synthetic-token-A", encoded)
        self.assertNotIn("127.0.0.1:8541", encoded)

    # ------------------------------------------------------------------ #
    # T01 纯图片、无 nameHint/profileChanges
    # ------------------------------------------------------------------ #

    def test_image_without_name_hint_is_read_only_and_candidate_only(self):
        self._image()
        client = _FakeClient(
            context=_context(),
            lens=_lens(
                links=[
                    {"url": "https://x.com/alice/status/123",
                     "domain": "x.com", "title": "Alice original post"},
                    {"url": "https://www.pixiv.net/users/456",
                     "domain": "www.pixiv.net", "title": "Alice on Pixiv"},
                    {"url": "https://x.com/alice/status/123",
                     "domain": "x.com", "title": "duplicate"},
                    {"url": "https://www.bing.com/search?q=alice",
                     "domain": "www.bing.com", "title": "aggregate"},
                ],
                top_domains=["x.com", "www.pixiv.net"],
            ),
        )
        result = self._run(
            {"path": str(self.work.inputs / "still.png")}, client)

        self.assertEqual("ok", result["status"])
        self.assertEqual(0, result["businessWrites"])
        self.assertEqual("not_started", result["writePhase"])
        self.assertFalse(result["confirmed"])
        self.assertEqual([], result["frames"])
        self.assertEqual([], result["frameRefs"])
        self.assertGreater(client.lens_payload_bytes, 0)
        self.assertTrue(client.lens_content_type.startswith("multipart/form-data"))
        # 没有 nameHint 时不发文字检索；所有网络调用都在研究只读端点上。
        self.assertIsNone(client.provider_body)
        self.assertEqual(
            ["/api/v1/research/context", "/api/v1/research/lens"],
            [path for _method, path, _body in client.calls])

        platforms = sorted(item["platform"] for item in result["candidates"])
        self.assertIn("x", platforms)
        self.assertIn("pixiv", platforms)
        for candidate in result["candidates"]:
            self.assertEqual("unconfirmed", candidate["verification"])
            self.assertFalse(candidate["authorConfirmed"])
            self.assertIn(candidate["evidenceLevel"], ("weak", "medium"))
        self.assertTrue(any(item["kind"] == "work" and item["stableId"] == "123"
                            for item in result["workSources"]))
        excluded_kinds = {item["kind"] for item in result["excluded"]}
        self.assertIn("duplicate", excluded_kinds)
        self.assertIn("aggregate", excluded_kinds)
        for item in result["excluded"]:
            self.assertTrue(item["reason"])
        self.assertTrue(any("未取得原帖/作品页与作者主页互证" in text
                            for text in result["unresolved"]))
        self.assertTrue(result["evidenceRef"].startswith("a1_"))
        self.assertTrue(any(item["usage"] == "evidence"
                            for item in result["artifactRefs"]))
        self._assert_private(result)

        evidence = self._read_evidence(result["evidenceRef"])
        self.assertEqual(0, evidence["businessWrites"])
        self.assertEqual("not_started", evidence["writePhase"])
        self.assertEqual(result["candidates"], evidence["candidates"])

    # ------------------------------------------------------------------ #
    # T02 纯视频：采样百分比、缓存入参与 frameRefs 接续
    # ------------------------------------------------------------------ #

    def test_video_samples_percentages_and_frame_refs_are_continuable(self):
        self._video()
        calls = []

        def fake_extract(path, out_dir, *, percentages=None, kind="video",
                         env=None, cache=True):
            calls.append({"percentages": tuple(percentages or ()), "kind": kind,
                          "cache": cache})
            directory = Path(out_dir)
            directory.mkdir(parents=True, exist_ok=True)
            frames = []
            for index, percent in enumerate(percentages or ()):
                payload = ("frame-%d-%d" % (index, percent)).encode("utf-8")
                target = directory / ("frame-%d-%03d.jpg" % (index, percent))
                target.write_bytes(payload)
                frames.append({
                    "percentage": percent, "seekMs": percent * 100,
                    "path": str(target),
                    "sha256": hashlib.sha256(payload).hexdigest(),
                    "bytes": len(payload),
                })
            return {"status": "ok", "durationMs": 10000, "frames": frames}

        client = _FakeClient(
            context=_context(),
            lens=_lens(links=[{"url": "https://www.pixiv.net/artworks/999",
                               "domain": "www.pixiv.net", "title": "work"}]),
        )
        result = self._run(
            {"path": str(self.work.inputs / "clip.mp4"), "frames": "15,50,85"},
            client, probe={"dimensions": {"width": 1280, "height": 720},
                           "durationMs": 10000},
            extract=fake_extract)

        self.assertEqual("ok", result["status"])
        self.assertEqual([15, 50, 85],
                         [item["percent"] for item in result["frames"]])
        self.assertEqual(10000, result["media"]["durationMs"])
        self.assertEqual(3, len(result["frameRefs"]))
        self.assertEqual([15, 50, 85],
                         [item["percent"] for item in result["frameRefs"]])
        self.assertEqual((15, 50, 85), calls[0]["percentages"])
        self.assertEqual("video", calls[0]["kind"])
        self.assertTrue(calls[0]["cache"])
        self.assertGreater(client.lens_payload_bytes, 0)
        self.assertTrue(result["evidenceRef"].startswith("a1_"))
        for frame_ref in result["frameRefs"]:
            self.assertNotIn("path", frame_ref)
            resolved = artifact_ref.resolve_path(
                frame_ref["artifactRef"], env=self.env,
                rulepack_version=self.rulepack)
            self.assertTrue(resolved.is_file())
        evidence = self._read_evidence(result["evidenceRef"])
        self.assertEqual(3, len(evidence["frames"]))
        self.assertTrue(any(item["stableId"] == "999"
                            for item in result["workSources"]))
        self._assert_private(result)

        # 再次运行可只采一帧；缓存参数与百分比仍由调用方明确传入。
        single = self._run(
            {"path": str(self.work.inputs / "clip.mp4"), "frames": [50]},
            client, probe={"dimensions": {"width": 1280, "height": 720},
                           "durationMs": 10000},
            extract=fake_extract)
        self.assertEqual([50], [item["percent"] for item in single["frames"]])
        self.assertEqual((50,), calls[-1]["percentages"])

    def test_cached_frames_from_previous_run_are_reused(self):
        """media_primitives 缓存会返回上一轮工作目录的帧；仍应可发布与接续。"""
        self._video()
        old_dir = self.work.research / "research-work" / "old-run" / "frames"
        old_dir.mkdir(parents=True)
        cached = old_dir / "cached-frame.jpg"
        cached.write_bytes(b"cached-frame-bytes")

        def fake_extract(path, out_dir, *, percentages=None, kind="video",
                         env=None, cache=True):
            return {
                "status": "ok", "durationMs": 10000,
                "frames": [{
                    "percentage": 15, "seekMs": 1500, "path": str(cached),
                    "sha256": hashlib.sha256(cached.read_bytes()).hexdigest(),
                    "bytes": cached.stat().st_size,
                }],
            }

        client = _FakeClient(context=_context(), lens=_lens())
        result = self._run(
            {"path": str(self.work.inputs / "clip.mp4")}, client,
            probe={"dimensions": {"width": 1280, "height": 720},
                   "durationMs": 10000},
            extract=fake_extract)

        self.assertEqual(1, len(result["frames"]))
        self.assertEqual(1, len(result["frameRefs"]))
        self.assertNotIn("FRAME_PATH_UNSAFE",
                         {item["code"] for item in result["limitations"]})
        resolved = artifact_ref.resolve_path(
            result["frameRefs"][0]["artifactRef"], env=self.env,
            rulepack_version=self.rulepack)
        self.assertEqual(cached.resolve(), resolved)
        self._assert_private(result)

    def test_artifact_ref_media_source_is_resolved_without_echoing_path(self):
        self._image()
        meta = artifact_ref.publish_file(
            self.work.inputs / "still.png", env=self.env,
            tool_id="research.media.probe", rulepack_version=self.rulepack,
            kind="image", label="合成图片")
        client = _FakeClient(
            context=_context(),
            lens=_lens(links=[{"url": "https://x.com/alice",
                               "domain": "x.com", "title": "Alice"}]),
        )
        result = self._run({"artifactRef": meta["artifactRef"]}, client)

        self.assertEqual("artifactRef", result["media"]["sourceKind"])
        self.assertEqual("ok", result["status"])
        self.assertTrue(any(item["handle"] == "alice"
                            for item in result["candidates"]))
        self._assert_private(result)

    # ------------------------------------------------------------------ #
    # T04 不同平台 ID 不合并；排除只在有具体信号时出现
    # ------------------------------------------------------------------ #

    def test_different_platform_ids_do_not_merge(self):
        self._image()
        client = _FakeClient(
            context=_context(),
            lens=_lens(
                links=[
                    {"url": "https://www.pixiv.net/users/456",
                     "domain": "www.pixiv.net", "title": "Alice"},
                    {"url": "https://www.pixiv.net/users/789",
                     "domain": "www.pixiv.net", "title": "Other Alice"},
                    {"url": "https://www.pixiv.net/users/456",
                     "domain": "www.pixiv.net", "title": "duplicate same id"},
                ],
                top_titles=[{"title": "Repost of Alice", "domain": "x.com"}],
            ),
            provider=_provider(items=[
                {"title": "Alice on Pixiv", "url": "https://www.pixiv.net/users/456",
                 "domain": "www.pixiv.net", "author": "Alice"},
                {"title": "Other on Pixiv", "url": "https://www.pixiv.net/users/789",
                 "domain": "www.pixiv.net", "author": "Other Alice"},
                {"title": "Alice on X", "url": "https://x.com/alice",
                 "domain": "x.com", "author": "Alice"},
            ]),
        )
        result = self._run({"path": str(self.work.inputs / "still.png"),
                            "nameHint": "Alice"}, client)

        pixiv_ids = sorted(item["stableId"] for item in result["candidates"]
                           if item["platform"] == "pixiv")
        self.assertEqual(["456", "789"], pixiv_ids)
        x_candidates = [item for item in result["candidates"]
                        if item["platform"] == "x"]
        self.assertEqual(1, len(x_candidates))
        self.assertEqual("alice", x_candidates[0]["handle"])
        for candidate in result["candidates"]:
            self.assertEqual("unconfirmed", candidate["verification"])
            self.assertFalse(candidate["authorConfirmed"])
        duplicates = [item for item in result["excluded"]
                      if item["kind"] == "duplicate"]
        self.assertTrue(duplicates)
        self.assertTrue(all(item["reason"] for item in duplicates))
        self.assertFalse(any(not item["reason"] for item in result["excluded"]))
        # 转载/同名标题只进入作品来源或弱证据，不得变成已确认作者。
        self.assertTrue(any(item["kind"] == "title-clue"
                            for item in result["workSources"]))
        self._assert_private(result)

    # ------------------------------------------------------------------ #
    # T05/T11 受限 provider、Lens 不可用与关闭研究：不冒充成功
    # ------------------------------------------------------------------ #

    def test_provider_blocked_and_lens_unavailable_degrades_honestly(self):
        self._image()
        client = _FakeClient(
            context=_context(sidecar_available=False),
            provider=_provider(status="blocked"),
        )
        result = self._run(
            {"path": str(self.work.inputs / "still.png"), "nameHint": "Alice"},
            client)

        self.assertEqual("unresolved", result["status"])
        codes = {item["code"] for item in result["limitations"]}
        self.assertIn("LENS_UNAVAILABLE", codes)
        self.assertIn("PROVIDER_BLOCKED", codes)
        self.assertEqual([], result["candidates"])
        self.assertEqual([], result["evidence"])
        self.assertEqual(1, len(result["queries"]))
        self.assertEqual("blocked", result["queries"][0]["status"])
        self.assertFalse(any("Alice" in json.dumps(item)
                             for item in result["queries"]))
        # Lens 未执行；provider 调用仍走只读研究端点。
        self.assertNotIn("/api/v1/research/lens",
                         [path for _method, path, _body in client.calls])
        self.assertIn("/api/v1/research/providers/bing/query",
                      [path for _method, path, _body in client.calls])
        self._assert_private(result)

    def test_lens_non_ok_status_does_not_ingest_links(self):
        self._image()
        client = _FakeClient(
            context=_context(),
            lens=_lens(status="blocked", links=[
                {"url": "https://x.com/alice/status/123",
                 "domain": "x.com", "title": "stale link from blocked lens"},
            ]),
        )
        result = self._run(
            {"path": str(self.work.inputs / "still.png")}, client)

        self.assertEqual([], result["candidates"])
        self.assertEqual([], result["workSources"])
        self.assertEqual([], result["evidence"])
        self.assertIn("LENS_BLOCKED",
                      {item["code"] for item in result["limitations"]})
        self.assertEqual("unresolved", result["status"])

    def test_context_disabled_and_missing_ffmpeg_returns_unresolved(self):
        self._video()
        client = _FakeClient(context=_context(enabled=False),
                             provider=_provider(items=[
                                 {"title": "should not be used",
                                  "url": "https://x.com/alice"}]))
        result = self._run(
            {"path": str(self.work.inputs / "clip.mp4"), "nameHint": "Alice"},
            client, probe={}, perceptual={"status": "unavailable", "frames": []},
            ffmpeg="", ffprobe="")

        self.assertEqual("unresolved", result["status"])
        codes = {item["code"] for item in result["limitations"]}
        self.assertIn("RESEARCH_DISABLED", codes)
        self.assertIn("FFMPEG_MISSING", codes)
        self.assertEqual([], result["frames"])
        self.assertEqual([], result["candidates"])
        self.assertIsNone(client.provider_body)
        self.assertEqual([("GET", "/api/v1/research/context", None)], client.calls)
        self._assert_private(result)

    def test_context_503_is_limitation_but_401_is_raised(self):
        self._image()
        unavailable = _FakeClient(
            context=http_client.HTTPStatusError(
                503, {"code": "RESEARCH_DISABLED"}))
        result = self._run(
            {"path": str(self.work.inputs / "still.png")}, unavailable)
        self.assertEqual("unresolved", result["status"])
        codes = {item["code"] for item in result["limitations"]}
        self.assertIn("RESEARCH_DISABLED", codes)
        self.assertEqual([], result["candidates"])

        unauthorized = _FakeClient(
            context=http_client.HTTPStatusError(401, {"code": "UNAUTHORIZED"}))
        with self.assertRaises(http_client.HTTPStatusError):
            work.research_work(
                ToolContext(client=unauthorized, rulepack_version=self.rulepack,
                            env=self.env),
                {"path": str(self.work.inputs / "still.png")})

    # ------------------------------------------------------------------ #
    # 输入边界、recipe 兼容与发现入口
    # ------------------------------------------------------------------ #

    def test_input_requires_exactly_one_media_source(self):
        self._image()
        client = _FakeClient(context=_context())
        context = ToolContext(client=client, rulepack_version=self.rulepack,
                              env=self.env)
        with self.assertRaises(errors.ToolError) as caught:
            work.research_work(context, {})
        self.assertEqual("INVALID_INPUT", caught.exception.code)

        with self.assertRaises(errors.ToolError) as caught:
            work.research_work(context, {
                "path": str(self.work.inputs / "still.png"),
                "fileRef": "file_x_00_00",
            })
        self.assertEqual("INVALID_INPUT", caught.exception.code)

        with self.assertRaises(errors.ToolError) as caught:
            work.research_work(context, {
                "path": str(self.work.inputs / "still.png"),
                "frames": [15, 101],
            })
        self.assertEqual("INVALID_INPUT", caught.exception.code)

    def test_author_discover_contract_unchanged_and_work_recipe_discovered(self):
        old = recipes.get_recipe("author.discover", {})
        self.assertEqual(["nameHint", "profileChanges"],
                         old["inputs"].get("required"))
        self.assertTrue(any(step.get("tool") == "author.create"
                            for step in old["steps"]))
        self.assertTrue(any(step.get("gate") for step in old["steps"]))

        recipe = recipes.get_recipe("work.research", {})
        self.assertEqual("work.research", recipe["id"])
        self.assertEqual(["research.context", "research.work"],
                         [step.get("tool") for step in recipe["steps"]])
        self.assertFalse(any(step.get("gate") for step in recipe["steps"]))
        self.assertNotIn("profileChanges",
                         recipe["inputs"].get("properties", {}))

        spec = registry.get("research.work")
        self.assertIsNotNone(spec)
        self.assertEqual("research", spec.domain)
        self.assertEqual("research", spec.permission)
        self.assertFalse(spec.read_only)
        self.assertTrue(spec.side_effects)
        self.assertTrue(spec.network)
        self.assertIn("research", registry.TOOL_RULES_REFS.get("research.work", ()))


if __name__ == "__main__":
    unittest.main()
