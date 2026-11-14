"""task-11 五个已报告问题的定向回归（合成 stub，不访问真实 8420）。

覆盖：
1) media.organize 目录筛选改用 GET /assets 的 directory 参数（库根/子目录/非法路径）；
2) 计划绑定目标实例：verify_plan(instance=...) 拒绝跨实例 apply；
3) author.cleanup.apply delete 前按计划 inventory 复检档案/来源；
4) author.cleanup.apply merge 分支参数读取（不再读不存在的 authorId）；
5) asset.authors.apply 按名解析出的新作者 id 不再误判 unexpected。
"""
from __future__ import annotations

import contextlib
import json
import os
import shutil
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib import parse

from ai_maintenance import errors, execution, plans, tools_media

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "api" / "openapi.yaml"
TOKEN = "-".join(("synthetic", "regression", "credential"))

_TEMP_ROOT = ROOT / "tools" / "ai_maintenance" / ".test-temp"
try:
    _TEMP_ROOT.mkdir(parents=True, exist_ok=True)
except OSError:
    _TEMP_ROOT = Path(__file__).resolve().parent


@contextlib.contextmanager
def temp_dir():
    path = _TEMP_ROOT / ("regression-" + uuid.uuid4().hex)
    os.makedirs(path)
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


class StubHandler(BaseHTTPRequestHandler):
    routes = {}
    seen = []

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_PUT(self):
        self._handle("PUT")

    def do_DELETE(self):
        self._handle("DELETE")

    def _handle(self, method):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        body = self.rfile.read(length) if length > 0 else b""
        parsed = parse.urlsplit(self.path)
        type(self).seen.append({"method": method, "path": parsed.path,
                                "query": parse.parse_qs(parsed.query,
                                                        keep_blank_values=True),
                                "body": body})
        route = type(self).routes.get((method, parsed.path), (404, {"code": "NOT_FOUND"}))
        if isinstance(route, list):
            status, payload = route.pop(0) if len(route) > 1 else route[0]
        else:
            status, payload = route
        raw = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *args):
        pass


def profile(author_id, revision=0, **changes):
    body = {"authorId": author_id, "revision": revision, "nickname": "", "biography": "",
            "avatarUrl": "", "homepageUrl": "", "evidenceUrls": [], "status": "empty",
            "autoRefresh": False, "feedUrl": "", "trackUpdates": False,
            "candidate": {}, "updates": []}
    body.update(changes)
    return body


class StubCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), StubHandler)
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        StubHandler.routes = {}
        StubHandler.seen = []

    def env(self, base=None):
        return {"QIMENG_API_BASE": base or self.base, "QIMENG_API_TOKEN": TOKEN, "QIMENG_AI_GRANTED_PERMISSIONS": "all", "QIMENG_AI_MCP_DISCOVERY": "full"}

    def call(self, tool_id, payload, base=None):
        env = self.env(base)
        config = execution.load_config(env)
        return execution.execute_tool(tool_id, payload, env=env, config=config)


# --------------------------------------------------------------------------- #
# 1) 媒体目录筛选：使用 GET /assets 的 directory 参数
# --------------------------------------------------------------------------- #

MEDIA_FAMILY = "守望先锋 朱诺"


class MediaDirectoryTest(StubCase):
    RAW = ["browser_a.png", "browser_b.png"]

    def assets_page(self):
        items = [{"id": "old-1", "fileName": f"{MEDIA_FAMILY} 1.png"},
                 {"id": "raw-1", "fileName": self.RAW[0]},
                 {"id": "raw-2", "fileName": self.RAW[1]}]
        return {"items": items, "nextCursor": None}

    def groups(self):
        return [{"family": MEDIA_FAMILY,
                 "files": [{"name": name} for name in self.RAW],
                 "confidence": 1.0, "confirmed": True}]

    def test_subdirectory_filter_is_sent_to_server(self):
        StubHandler.routes[("GET", "/api/v1/assets")] = (200, self.assets_page())
        StubHandler.routes[("GET", "/api/v1/libraries")] = (
            200, [{"id": "lib-1", "name": "合成库"}])
        _spec, result = self.call("media.organize.prepare",
                                  {"libraryId": "lib-1", "directory": "合集/朱诺",
                                   "groups": self.groups()})
        queries = [item["query"] for item in StubHandler.seen
                   if item["path"] == "/api/v1/assets"]
        self.assertEqual(["合集/朱诺"], queries[0]["directory"])
        # 目录内已有作品 1，新组应分配作品编号 2，而不是按全库递归编号
        self.assertEqual([2], result["plan"]["impact"]["workNumbers"])
        self.assertEqual("合集/朱诺", result["plan"]["target"]["directory"])

    def test_root_directory_sends_empty_string_and_keeps_files_without_relpath(self):
        # 旧实现依赖列表项 relPath 猜目录；真实 GET /assets 列表项没有 relPath，
        # 库根场景不能因此漏文件。这里故意不返回 relPath。
        StubHandler.routes[("GET", "/api/v1/assets")] = (200, self.assets_page())
        StubHandler.routes[("GET", "/api/v1/libraries")] = (
            200, [{"id": "lib-1", "name": "合成库"}])
        _spec, result = self.call("media.organize.prepare",
                                  {"libraryId": "lib-1", "groups": self.groups()})
        queries = [item["query"] for item in StubHandler.seen
                   if item["path"] == "/api/v1/assets"]
        self.assertEqual([""], queries[0]["directory"])
        self.assertEqual([2], result["plan"]["impact"]["workNumbers"])

    def test_invalid_directory_is_rejected_before_any_listing(self):
        StubHandler.routes[("GET", "/api/v1/libraries")] = (
            200, [{"id": "lib-1", "name": "合成库"}])
        for value in ("../escape", "/abs/path", "C:\\media", "a//b", "a/./b"):
            with self.assertRaises(errors.ToolError) as caught:
                self.call("media.organize.prepare",
                          {"libraryId": "lib-1", "directory": value, "groups": self.groups()})
            self.assertEqual("INVALID_INPUT", caught.exception.code)
        self.assertFalse([item for item in StubHandler.seen
                          if item["path"] == "/api/v1/assets"])


# --------------------------------------------------------------------------- #
# 2) 计划绑定目标实例
# --------------------------------------------------------------------------- #

AUTHOR = {"id": "a1", "displayName": "Synthetic Author", "type": "regular", "fileCount": 0}


class PlanInstanceBindingTest(StubCase):
    def make_plan(self, instance):
        return plans.make_plan(
            tool="author.profile.update", tool_version="1.0.0", kind="profile.update",
            target={"instance": instance, "library": None, "objects": []},
            changes=[], impact={}, evidence=[],
            versions_info={"rulepackVersion": "r1", "openapiSha256": ""},
            confirmation={"required": False}, recoverable_steps=[], expected_readback=[])

    def test_verify_plan_rejects_cross_instance(self):
        plan = self.make_plan(self.base)
        plans.verify_plan(plan, tool_id="author.profile.update",
                          rulepack_version="r1", instance=self.base)
        with self.assertRaises(errors.ToolError) as caught:
            plans.verify_plan(plan, tool_id="author.profile.update",
                              rulepack_version="r1", instance="127.0.0.1:1")
        self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
        self.assertEqual("instance_mismatch", caught.exception.details["reason"])

    def test_apply_refuses_plan_from_other_instance_before_network(self):
        StubHandler.routes[("GET", "/api/v1/authors")] = (200, [AUTHOR])
        StubHandler.routes[("GET", "/api/v1/authors/a1/profile")] = (200, profile("a1"))
        _spec, prepared = self.call("author.profile.prepare",
                                    {"authorId": "a1", "changes": {"nickname": "New"}})
        # 换到另一个实例（同一 stub 端口不同 label 需要一个真实可达但无路由的服务）
        with temp_dir() as directory:
            other = ThreadingHTTPServer(("127.0.0.1", 0), StubHandler)
            other.daemon_threads = True
            thread = threading.Thread(target=other.serve_forever, daemon=True)
            thread.start()
            try:
                other_base = f"http://127.0.0.1:{other.server_port}"
                before = len(StubHandler.seen)
                with self.assertRaises(errors.ToolError) as caught:
                    self.call("author.profile.update",
                              {"plan": prepared["plan"], "confirm": True}, base=other_base)
                self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
                self.assertEqual(0, len(StubHandler.seen) - before)
            finally:
                other.shutdown()
                other.server_close()
                thread.join(timeout=5)


# --------------------------------------------------------------------------- #
# 3) 删除前复检档案/来源
# --------------------------------------------------------------------------- #

class CleanupDeletePreconditionTest(StubCase):
    ORPHAN = {"id": "a1", "displayName": "Orphan", "type": "regular", "fileCount": 0}

    def routes(self, profile_sequence):
        StubHandler.routes[("GET", "/api/v1/authors")] = [
            (200, [self.ORPHAN]),
            (200, [self.ORPHAN]),
        ]
        StubHandler.routes[("GET", "/api/v1/authors/a1/profile")] = [
            (200, item) for item in profile_sequence]
        StubHandler.routes[("GET", "/api/v1/authors/a1/sources")] = [
            (200, {"sources": []}), (200, {"sources": []})]
        StubHandler.routes[("DELETE", "/api/v1/authors/a1")] = (204, {})

    def prepare(self):
        self.routes([profile("a1"), profile("a1")])
        _spec, prepared = self.call("author.cleanup.prepare",
                                    {"actions": [{"action": "delete", "authorId": "a1"}]})
        return prepared["plan"]

    def test_profile_changed_after_prepare_blocks_delete(self):
        plan = self.prepare()
        # apply：authors digest 读 1 次；inventory 复检再读 profile/sources
        self.routes([profile("a1", nickname="Human Edit"), profile("a1", nickname="Human Edit")])
        with self.assertRaises(errors.ToolError) as caught:
            self.call("author.cleanup.apply", {"plan": plan, "confirm": True})
        self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
        self.assertFalse([item for item in StubHandler.seen if item["method"] == "DELETE"])

    def test_sources_changed_right_before_delete_blocks_delete(self):
        plan = self.prepare()
        StubHandler.routes[("GET", "/api/v1/authors")] = [
            (200, [self.ORPHAN]), (200, [self.ORPHAN])]
        StubHandler.routes[("GET", "/api/v1/authors/a1/profile")] = [
            (200, profile("a1")), (200, profile("a1"))]
        StubHandler.routes[("GET", "/api/v1/authors/a1/sources")] = [
            (200, {"sources": []}), (200, {"sources": ["新来源"]})]
        StubHandler.routes[("DELETE", "/api/v1/authors/a1")] = (204, {})
        # 全局复检通过、删除前最后一次复检发现来源变化：返回结构化 failed 结果
        # （保留已执行步骤记录），不得执行 DELETE。
        _spec, result = self.call("author.cleanup.apply", {"plan": plan, "confirm": True})
        self.assertEqual("failed", result["status"])
        self.assertEqual("PRECONDITION_FAILED",
                         result["executedActions"][0]["error"]["error"])
        self.assertFalse([item for item in StubHandler.seen if item["method"] == "DELETE"])


# --------------------------------------------------------------------------- #
# 4) cleanup.apply 的 merge 分支参数读取
# --------------------------------------------------------------------------- #

class CleanupMergeActionTest(StubCase):
    SOURCE = {"id": "s1", "displayName": "Dup", "type": "regular", "fileCount": 1,
              "followed": False}
    TARGET = {"id": "t1", "displayName": "Keep", "type": "regular", "fileCount": 2}

    def test_merge_action_is_executed_without_missing_author_id(self):
        StubHandler.routes[("GET", "/api/v1/authors")] = [
            (200, [self.SOURCE, self.TARGET]),   # prepare
            (200, [self.SOURCE, self.TARGET]),   # apply digest
            (200, [self.TARGET]),                # merge readback
            (200, [self.TARGET]),                # final readback
        ]
        StubHandler.routes[("GET", "/api/v1/authors/s1/profile")] = [
            (200, profile("s1", nickname="Dup")), (200, profile("s1", nickname="Dup"))]
        StubHandler.routes[("GET", "/api/v1/authors/t1/profile")] = [
            (200, profile("t1", nickname="Keep")), (200, profile("t1", nickname="Keep"))]
        StubHandler.routes[("GET", "/api/v1/authors/s1/sources")] = [
            (200, {"sources": ["A"]}), (200, {"sources": ["A"]})]
        StubHandler.routes[("GET", "/api/v1/authors/t1/sources")] = [
            (200, {"sources": ["B"]}), (200, {"sources": ["B"]})]
        StubHandler.routes[("POST", "/api/v1/authors/merge")] = (
            200, {"sourceAuthorId": "s1", "targetAuthorId": "t1", "deletedSource": True})
        _spec, prepared = self.call("author.cleanup.prepare", {"actions": [
            {"action": "merge", "sourceAuthorId": "s1", "targetAuthorId": "t1"}]})
        _spec, result = self.call("author.cleanup.apply",
                                  {"plan": prepared["plan"], "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertEqual("merge", result["executedActions"][0]["action"])
        self.assertEqual(1, len([item for item in StubHandler.seen if item["method"] == "POST"]))


# --------------------------------------------------------------------------- #
# 5) 按作者名关联后的回读
# --------------------------------------------------------------------------- #

class AssetAuthorsReadbackTest(StubCase):
    ASSET = {"id": "asset-1", "fileName": "a.png", "libraryId": "lib-1",
             "relPath": "dir/a.png", "authors": []}
    AUTHOR = {"id": "a1", "displayName": "One", "type": "regular", "fileCount": 0}

    def routes(self, readback_authors):
        readback = dict(self.ASSET, authors=readback_authors, libraryId="lib-1",
                        fileName="a.png", relPath="dir/a.png")
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = [
            (200, self.ASSET), (200, self.ASSET), (200, readback)]
        StubHandler.routes[("GET", "/api/v1/authors")] = (200, [self.AUTHOR])
        StubHandler.routes[("GET", "/api/v1/libraries")] = (
            200, [{"id": "lib-1", "name": "合成库", "capabilities": {"authorAttach": True}}])
        StubHandler.routes[("PUT", "/api/v1/assets/asset-1/authors")] = (200, readback)

    def prepare(self):
        _spec, prepared = self.call("asset.authors.prepare",
                                    {"assetId": "asset-1", "authorIds": [],
                                     "newAuthorNames": ["New Author"]})
        return prepared["plan"]

    def test_server_created_author_id_counts_as_expected(self):
        # 服务端把 New Author 解析成新 id；不再被 unexpected 误判失败。
        self.routes([{"id": "new-id", "displayName": "New Author"}])
        plan = self.prepare()
        _spec, result = self.call("asset.authors.apply", {"plan": plan, "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertEqual(["new-id"], result["authorIds"])

    def test_unmatched_extra_author_still_fails(self):
        self.routes([{"id": "intruder", "displayName": "Other Name"}])
        plan = self.prepare()
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.authors.apply", {"plan": plan, "confirm": True})
        self.assertEqual("READBACK_MISMATCH", caught.exception.code)
        self.assertEqual(["intruder"], caught.exception.details["unexpectedAuthorIds"])

    def test_reused_existing_author_with_different_display_name_is_expected(self):
        StubHandler.routes[("GET", "/api/v1/assets/asset-1")] = [
            (200, dict(self.ASSET)), (200, dict(self.ASSET)),
            (200, dict(self.ASSET, authors=[
                {"id": "existing-2", "displayName": "Existing Display"}]))]
        StubHandler.routes[("GET", "/api/v1/authors")] = (
            200, [self.AUTHOR, {"id": "existing-2", "displayName": "Existing Display",
                                "type": "regular", "fileCount": 1}])
        StubHandler.routes[("GET", "/api/v1/libraries")] = (
            200, [{"id": "lib-1", "name": "合成库", "capabilities": {"authorAttach": True}}])
        StubHandler.routes[("PUT", "/api/v1/assets/asset-1/authors")] = (
            200, dict(self.ASSET, authors=[{"id": "existing-2",
                                            "displayName": "Existing Display"}]))
        plan = self.prepare()
        _spec, result = self.call("asset.authors.apply", {"plan": plan, "confirm": True})
        self.assertEqual("applied", result["status"])
        self.assertEqual(["existing-2"], result["authorIds"])

    def test_requested_name_not_resolved_at_all_fails(self):
        self.routes([])
        plan = self.prepare()
        with self.assertRaises(errors.ToolError) as caught:
            self.call("asset.authors.apply", {"plan": plan, "confirm": True})
        self.assertEqual("READBACK_MISMATCH", caught.exception.code)
        self.assertEqual(["New Author"], caught.exception.details["unresolvedNewAuthorNames"])


if __name__ == "__main__":
    unittest.main()
