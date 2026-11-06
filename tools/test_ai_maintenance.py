"""AI 维护规则包与只读 CLI 回归；全部合成数据，不访问真实 8420 或外网。"""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import socket
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch
from urllib import error, parse
from urllib.error import HTTPError, URLError

from ai_maintenance import cli, rulepack

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "api" / "openapi.yaml"
RULES = ROOT / "tools" / "ai_maintenance" / "rules.json"
SYNTHETIC_TOKEN = "-".join(("synthetic", "test", "credential"))


class StubHandler(BaseHTTPRequestHandler):
    routes = {}
    seen = []
    asset_page_responses = None

    def do_GET(self):
        parsed = parse.urlsplit(self.path)
        type(self).seen.append((self.path, self.headers.get("Authorization")))
        if parsed.path == "/api/v1/assets" and type(self).asset_page_responses is not None:
            cursor = parse.parse_qs(parsed.query).get("cursor", [None])[0]
            status, payload, content_type = type(self).asset_page_responses.get(
                cursor, (200, {"items": [], "nextCursor": None}, "application/json")
            )
        else:
            status, payload, content_type = type(self).routes.get(
                parsed.path, (404, {"code": "NOT_FOUND", "message": "synthetic"}, "application/json")
            )
        body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *args):
        pass


class RulepackTest(unittest.TestCase):
    def snapshot(self, directory):
        path = Path(directory) / "openapi.yaml"
        path.write_bytes(OPENAPI.read_bytes())
        return path

    def test_required_shape_hash_and_known_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.snapshot(directory)
            pack = rulepack.build_rulepack(openapi_path=path)
            raw = path.read_bytes()

        for key in (
            "schemaVersion", "rulesVersion", "openapiSha256", "paths", "capabilities",
            "fields", "authorActions", "tags", "backup", "errors", "workflows", "permissions",
        ):
            self.assertIn(key, pack)
        self.assertEqual(1, pack["schemaVersion"])
        self.assertNotIn("generatedAt", pack)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), pack["openapiSha256"])
        for known in (
            "/api/v1/healthz",
            "/api/v1/libraries",
            "/api/v1/assets",
            "/api/v1/authors",
            "/api/v1/authors/{authorId}/profile",
            "/api/v1/authors/{authorId}/sources",
        ):
            self.assertIn(known, pack["paths"])
        self.assertTrue(all(entry["status"] == "planned" for entry in pack["capabilities"]["write"].values()))
        for name, entry in pack["capabilities"]["read"].items():
            if entry["status"] == "supported" and entry.get("endpoint"):
                self.assertTrue(entry.get("endpointPresent"), name)
        for name, action in pack["authorActions"].items():
            if action.get("apiStatus") == "available":
                self.assertTrue(action.get("endpointPresent"), name)
        self.assertEqual("read-only", pack["tags"]["access"])
        self.assertEqual("not_supported", pack["tags"]["write"])
        self.assertTrue(pack["backup"]["exportBeforeImport"])
        self.assertEqual(3, len(pack["workflows"]))
        self.assertEqual("QIMENG_API_TOKEN", pack["permissions"]["api"]["tokenEnv"])

        profile = pack["fields"]["authorProfile"]
        self.assertEqual(2000, profile["biography"]["maxLength"])
        self.assertEqual(1000, profile["xProfile"]["biography"]["maxLength"])
        self.assertEqual(2000, profile["biographyZh"]["maxLength"])
        self.assertEqual(8, profile["evidenceUrls"]["maxItems"])
        self.assertEqual(2000, profile["evidenceUrls"]["items"]["maxLength"])
        self.assertEqual(["empty", "candidate", "verified"], profile["status"]["enum"])

    def test_merge_marker_follows_openapi_and_write_stays_planned(self):
        with tempfile.TemporaryDirectory() as directory:
            pack = rulepack.build_rulepack(openapi_path=self.snapshot(directory))
        merge_paths = [path for path in pack["paths"] if path.startswith("/api/v1/authors") and path.endswith("/merge")]
        merge = pack["authorActions"]["merge"]
        self.assertEqual("planned", merge["status"])
        if merge_paths:
            self.assertEqual("available", merge["apiStatus"])
            self.assertIn(merge_paths[0], merge["endpoint"])
            self.assertTrue(merge["endpointPresent"])
        else:
            self.assertEqual("planned", merge["apiStatus"])
            self.assertIsNone(merge["endpoint"])
            self.assertFalse(merge["endpointPresent"])

    def test_same_openapi_bytes_are_stable(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.snapshot(directory)
            first = rulepack.build_rulepack(openapi_path=path)
            second = rulepack.build_rulepack(openapi_path=path)
            self.assertEqual(first["openapiSha256"], second["openapiSha256"])
            self.assertEqual(rulepack.dumps_pack(first), rulepack.dumps_pack(second))

            output = Path(directory) / "rulepack.json"
            rulepack.write_pack(first, output)
            loaded = rulepack.read_rulepack(output)
            self.assertEqual(first["openapiSha256"], loaded["openapiSha256"])

    def test_fingerprint_changes_when_openapi_bytes_change(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.snapshot(directory)
            first = rulepack.build_rulepack(openapi_path=path)
            path.write_bytes(path.read_bytes() + b"\n# synthetic fingerprint probe\n")
            second = rulepack.build_rulepack(openapi_path=path)
        self.assertNotEqual(first["openapiSha256"], second["openapiSha256"])
        self.assertEqual(first["paths"], second["paths"])

    def test_unknown_endpoint_is_downgraded_instead_of_claimed(self):
        with tempfile.TemporaryDirectory() as directory:
            rules = json.loads(RULES.read_text(encoding="utf-8"))
            rules["capabilities"]["read"]["synthetic.query"] = {
                "status": "supported",
                "endpoint": "GET /api/v1/does-not-exist",
            }
            rules_path = Path(directory) / "rules.json"
            rules_path.write_text(json.dumps(rules, ensure_ascii=False), encoding="utf-8")
            pack = rulepack.build_rulepack(openapi_path=self.snapshot(directory), rules_path=rules_path)
        entry = pack["capabilities"]["read"]["synthetic.query"]
        self.assertEqual("planned", entry["status"])
        self.assertFalse(entry["endpointPresent"])

    def test_markdown_guide_reports_fingerprint_and_planned_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            pack = rulepack.build_rulepack(openapi_path=self.snapshot(directory))
        text = rulepack.render_markdown(pack)
        self.assertIn(pack["openapiSha256"], text)
        self.assertIn("写入能力（本切片一律 planned）", text)
        self.assertIn("context", text)


class CliContextTest(unittest.TestCase):
    def run_cli(self, argv, env=None):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, env or {}, clear=True), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = cli.main(argv)
        return status, stdout.getvalue(), stderr.getvalue()

    def snapshot(self, directory):
        path = Path(directory) / "openapi.yaml"
        path.write_bytes(OPENAPI.read_bytes())
        return path

    def test_context_is_local_and_parseable(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.snapshot(directory)
            status, stdout, stderr = self.run_cli(["context", "--openapi", str(path)])
        self.assertEqual(0, status)
        self.assertEqual("", stderr)
        pack = json.loads(stdout)
        self.assertIn("openapiSha256", pack)
        self.assertIn("/api/v1/authors", pack["paths"])
        self.assertNotIn(SYNTHETIC_TOKEN, stdout)

    def test_context_out_writes_same_document(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.snapshot(directory)
            output = Path(directory) / "rulepack.json"
            status, stdout, stderr = self.run_cli(["context", "--openapi", str(path), "--out", str(output)])
            self.assertEqual(0, status)
            self.assertEqual("", stderr)
            self.assertEqual(stdout, output.read_text(encoding="utf-8"))

    def test_context_markdown(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.snapshot(directory)
            status, stdout, stderr = self.run_cli(["context", "--openapi", str(path), "--markdown"])
        self.assertEqual(0, status)
        self.assertEqual("", stderr)
        self.assertIn("规则包", stdout)
        self.assertIn("SHA-256", stdout)

    def test_missing_or_invalid_config_exits_2_without_token(self):
        cases = (
            {},
            {"QIMENG_API_TOKEN": SYNTHETIC_TOKEN},
            {"QIMENG_API_BASE": "http://127.0.0.1:9"},
            {"QIMENG_API_BASE": "http://example.com", "QIMENG_API_TOKEN": SYNTHETIC_TOKEN},
        )
        for env in cases:
            status, stdout, stderr = self.run_cli(["health"], env=env)
            self.assertEqual(2, status)
            self.assertEqual("", stdout)
            self.assertNotIn(SYNTHETIC_TOKEN, stdout + stderr)
            payload = json.loads(stderr)
            self.assertEqual("configuration_error", payload["error"])


class CliHTTPTest(unittest.TestCase):
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
        StubHandler.routes = {
            "/api/v1/healthz": (200, {"status": "alive"}, "application/json"),
            "/api/v1/readyz": (200, {"status": "ready"}, "application/json"),
            "/api/v1/libraries": (200, [{"id": "lib", "name": "合成库"}], "application/json"),
            "/api/v1/assets": (200, {"items": [], "nextCursor": None}, "application/json"),
            "/api/v1/authors": (200, [{"id": f"a{index}", "displayName": f"合成作者{index}"} for index in range(1, 6)], "application/json"),
            "/api/v1/authors/synthetic%20id/profile": (200, {"authorId": "synthetic id", "revision": 0}, "application/json"),
            "/api/v1/authors/synthetic%20id/sources": (200, {"sources": []}, "application/json"),
        }
        StubHandler.seen = []
        StubHandler.asset_page_responses = None

    def run_cli(self, argv, env=None):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, env if env is not None else self.api_env(), clear=True), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = cli.main(argv)
        return status, stdout.getvalue(), stderr.getvalue()

    def api_env(self):
        return {"QIMENG_API_BASE": self.base, "QIMENG_API_TOKEN": SYNTHETIC_TOKEN}

    def test_health_and_libraries_send_expected_path_and_bearer(self):
        status, stdout, stderr = self.run_cli(["health"])
        self.assertEqual(0, status)
        self.assertEqual("", stderr)
        self.assertEqual({"status": "alive"}, json.loads(stdout))
        self.assertEqual("/api/v1/healthz", StubHandler.seen[-1][0])
        self.assertEqual("Bearer " + SYNTHETIC_TOKEN, StubHandler.seen[-1][1])

        status, stdout, stderr = self.run_cli(["health", "--ready"])
        self.assertEqual(0, status)
        self.assertEqual("/api/v1/readyz", StubHandler.seen[-1][0])

        status, stdout, stderr = self.run_cli(["libraries"])
        self.assertEqual(0, status)
        self.assertEqual("/api/v1/libraries", StubHandler.seen[-1][0])
        self.assertEqual("Bearer " + SYNTHETIC_TOKEN, StubHandler.seen[-1][1])
        self.assertNotIn(SYNTHETIC_TOKEN, stdout + stderr)

    def test_assets_uses_openapi_query_names(self):
        status, stdout, stderr = self.run_cli([
            "assets", "--size", "2", "--q", "合成", "--media-type", "image",
            "--sort", "fileDate", "--order", "asc", "--library-id", "lib-1",
        ])
        self.assertEqual(0, status)
        self.assertEqual("", stderr)
        query = parse.parse_qs(parse.urlsplit(StubHandler.seen[-1][0]).query)
        self.assertEqual(["2"], query["limit"])
        self.assertEqual(["合成"], query["q"])
        self.assertEqual(["image"], query["mediaType"])
        self.assertEqual(["fileDate"], query["sort"])
        self.assertEqual(["asc"], query["order"])
        self.assertEqual(["lib-1"], query["libraryId"])
        self.assertNotIn("page", query)

    def test_authors_page_size_are_client_side_slices(self):
        status, stdout, stderr = self.run_cli(["authors", "--page", "2", "--size", "2"])
        self.assertEqual(0, status)
        self.assertEqual("", stderr)
        self.assertEqual(["a3", "a4"], [item["id"] for item in json.loads(stdout)])
        self.assertEqual("/api/v1/authors", StubHandler.seen[-1][0])

    def test_author_profile_and_sources_quote_author_id(self):
        for argv, expected in (
            (["author-profile", "synthetic id"], "/api/v1/authors/synthetic%20id/profile"),
            (["author-sources", "--author-id", "synthetic id"], "/api/v1/authors/synthetic%20id/sources"),
        ):
            status, stdout, stderr = self.run_cli(argv)
            self.assertEqual(0, status)
            self.assertEqual("", stderr)
            self.assertEqual(expected, StubHandler.seen[-1][0])

    def test_token_is_redacted_even_if_upstream_echoes_it(self):
        StubHandler.routes["/api/v1/healthz"] = (
            500,
            {"code": "INTERNAL", "message": "upstream echoed " + SYNTHETIC_TOKEN},
            "application/json",
        )
        status, stdout, stderr = self.run_cli(["health"])
        self.assertEqual(1, status)
        self.assertEqual("", stdout)
        self.assertNotIn(SYNTHETIC_TOKEN, stderr)
        self.assertIn("<redacted>", stderr)

    def test_http_error_has_status_error_body_on_stderr(self):
        StubHandler.routes["/api/v1/healthz"] = (409, {"code": "CONFLICT", "message": "合成冲突"}, "application/json")
        status, stdout, stderr = self.run_cli(["health"])
        self.assertEqual(1, status)
        self.assertEqual("", stdout)
        payload = json.loads(stderr)
        self.assertEqual(409, payload["status"])
        self.assertEqual("http_error", payload["error"])
        self.assertEqual({"code": "CONFLICT", "message": "合成冲突"}, payload["body"])
        self.assertIn(str(self.server.server_port), payload["target"])
        self.assertNotIn(SYNTHETIC_TOKEN, stderr)

    def test_redirect_is_not_followed(self):
        StubHandler.routes["/api/v1/healthz"] = (302, b"", "text/plain")
        status, stdout, stderr = self.run_cli(["health"])
        self.assertEqual(1, status)
        self.assertEqual("", stdout)
        payload = json.loads(stderr)
        self.assertEqual(302, payload["status"])
        self.assertEqual("http_error", payload["error"])
        self.assertEqual(1, len([item for item in StubHandler.seen if item[0].startswith("/api/v1/healthz")]))

    def test_network_and_timeout_are_classified(self):
        client = cli.ApiClient(self.base, SYNTHETIC_TOKEN)
        client.opener = Mock()
        client.opener.open.side_effect = URLError(ConnectionRefusedError(111, "synthetic refused"))
        with self.assertRaises(cli.NetworkError) as caught:
            client.get("/api/v1/healthz")
        self.assertEqual("network_error", caught.exception.kind)

        client.opener.open.side_effect = URLError(socket.timeout())
        with self.assertRaises(cli.NetworkError) as caught:
            client.get("/api/v1/healthz")
        self.assertEqual("timeout", caught.exception.kind)

    def test_http_error_is_classified_without_response_leak(self):
        client = cli.ApiClient(self.base, SYNTHETIC_TOKEN)
        client.opener = Mock()
        client.opener.open.side_effect = HTTPError(
            "http://127.0.0.1/synthetic", 401, "unauthorized", {},
            io.BytesIO(b'{"code":"UNAUTHORIZED","message":"synthetic"}'),
        )
        with self.assertRaises(cli.HTTPStatusError) as caught:
            client.get("/api/v1/healthz")
        self.assertEqual(401, caught.exception.status)
        self.assertEqual({"code": "UNAUTHORIZED", "message": "synthetic"}, caught.exception.body)

    def test_assets_page_follows_cursor_without_page_parameter(self):
        StubHandler.asset_page_responses = {
            None: (200, {"items": [{"id": "first"}], "nextCursor": "cursor-2", "totalMatched": 2}, "application/json"),
            "cursor-2": (200, {"items": [{"id": "second"}], "nextCursor": None, "totalMatched": 2}, "application/json"),
        }
        status, stdout, stderr = self.run_cli(["assets", "--page", "2", "--size", "1"])
        self.assertEqual(0, status)
        self.assertEqual("", stderr)
        self.assertEqual(["second"], [item["id"] for item in json.loads(stdout)["items"]])
        paths = [item[0] for item in StubHandler.seen]
        self.assertEqual(2, len(paths))
        first = parse.parse_qs(parse.urlsplit(paths[0]).query)
        second = parse.parse_qs(parse.urlsplit(paths[1]).query)
        self.assertNotIn("page", first)
        self.assertNotIn("page", second)
        self.assertEqual(["1"], first["limit"])
        self.assertNotIn("cursor", first)
        self.assertEqual(["cursor-2"], second["cursor"])

    def test_unknown_assets_parameter_is_rejected(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, self.api_env(), clear=True), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as caught:
                cli.main(["assets", "--unknown-option", "1"])
        self.assertEqual(2, caught.exception.code)

    def test_limit_bounds_are_rejected(self):
        for value in ("0", "201"):
            status, stdout, stderr = self.run_cli(["assets", "--limit", value])
            self.assertEqual(2, status)
            self.assertEqual("usage_error", json.loads(stderr)["error"])


if __name__ == "__main__":
    unittest.main()
