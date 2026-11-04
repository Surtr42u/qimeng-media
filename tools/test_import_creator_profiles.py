"""作者档案导入工具回归；全部合成数据，API替身不访问真实服务。"""
import contextlib
import io
import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import Mock, patch
from urllib import error

import import_creator_profiles as importer


def record():
    return {"internal_id": "synthetic", "original_name": "Synthetic Creator", "kind": "regular", "candidate_platforms": [{"platform": "X", "handle": "synthetic", "url": "https://x.com/synthetic"}], "public_nickname": "Synthetic", "public_bio": "Public synthetic biography", "avatar_source_url": "", "evidence_urls": ["https://example.com/creator"], "status": "verified", "searched": True}


def profile():
    return {"authorId": "synthetic", "revision": 0, "nickname": "", "biography": "", "avatarUrl": "", "homepageUrl": "", "evidenceUrls": [], "status": "empty", "autoRefresh": False, "feedUrl": "", "trackUpdates": False, "candidate": {"nickname": "", "biography": "", "avatarUrl": "", "homepageUrl": "", "evidenceUrls": []}, "updates": []}


def fake_api(current=None):
    api = Mock()
    authors = [{"id": "synthetic", "displayName": "Synthetic Creator", "type": "regular"}]
    api.call.side_effect = [authors, current or profile(), {"authorId": "synthetic", "revision": 1}]
    return api


class ImportProfileTest(unittest.TestCase):
    def plan(self, item=None, reviewed=(), enable=False):
        return importer.plan_records({"records": [item or record()]}, reviewed, enable)[0]

    def test_research_verified_is_only_candidate(self):
        plan = self.plan()
        body = plan[0][1]
        self.assertEqual("candidate", body["status"])
        self.assertFalse(body["autoRefresh"])
        self.assertFalse(body["trackUpdates"])
        self.assertEqual("", body["feedUrl"])
        self.assertEqual(0, body["revision"])

    def test_review_and_explicit_flag_are_both_required_for_automation(self):
        self.assertFalse(self.plan(reviewed=["synthetic"])[0][1]["autoRefresh"])
        body = self.plan(reviewed=["synthetic"], enable=True)[0][1]
        self.assertTrue(body["autoRefresh"])
        self.assertEqual("verified", body["status"])
        with self.assertRaisesRegex(importer.ImportFailure, "requires_review"):
            self.plan(enable=True)

    def test_maintenance_prefers_creator_hub_and_retains_x_identity_link(self):
        item = record()
        item["candidate_platforms"] = [
            {"platform": "X", "url": "https://x.com/synthetic"},
            {"platform": "Linktree", "url": "https://linktr.ee/synthetic"},
        ]
        body = self.plan(item)[0][1]
        self.assertEqual("https://linktr.ee/synthetic", body["homepageUrl"])
        self.assertIn("https://x.com/synthetic", body["evidenceUrls"])

    def test_unknown_or_unsafe_sources_are_not_filled(self):
        for update in ({"status": "unresolved"}, {"searched": False}, {"evidence_urls": []}, {"candidate_platforms": []}, {"avatar_source_url": "https://127.0.0.1/avatar"}, {"evidence_urls": ["http://example.com"]}):
            item = record()
            item.update(update)
            self.assertEqual([], self.plan(item))

    def test_duplicate_ids_and_unknown_review_ids_reject_the_report(self):
        with self.assertRaisesRegex(importer.ImportFailure, "invalid_identity"):
            importer.plan_records({"records": [record(), record()]})
        with self.assertRaisesRegex(importer.ImportFailure, "not_in_report"):
            self.plan(reviewed=["missing"])

    def test_all_identities_are_checked_before_first_write(self):
        api = fake_api()
        api.call.side_effect = [[{"id": "synthetic", "displayName": "Other Creator", "type": "regular"}]]
        with self.assertRaisesRegex(importer.ImportFailure, "target_identity_mismatch"):
            importer.apply_plan(api, self.plan())
        self.assertEqual(1, api.call.call_count)
        self.assertEqual("GET", api.call.call_args.args[0])

    def test_cos_is_not_matched_to_same_named_regular_author(self):
        api = fake_api()
        item = record()
        item["kind"] = "cos"
        with self.assertRaisesRegex(importer.ImportFailure, "target_identity_mismatch"):
            importer.apply_plan(api, self.plan(item))
        self.assertEqual(1, api.call.call_count)

    def test_existing_revision_or_manual_field_is_never_overwritten(self):
        for update in ({"nickname": "Manual"}, {"avatarUrl": "https://example.com/avatar.png"}, {"status": "candidate"}, {"candidate": {"biography": "Pending review"}}):
            current = profile()
            current.update(update)
            api = fake_api(current)
            result = importer.apply_plan(api, self.plan())
            self.assertEqual(1, result["skipped_existing"])
            self.assertEqual(2, api.call.call_count)

    def test_empty_recreated_profile_uses_its_generation_revision(self):
        current = profile()
        current["revision"] = 7
        api = fake_api(current)
        api.call.side_effect = [[{"id": "synthetic", "displayName": "Synthetic Creator", "type": "regular"}], current, {"authorId": "synthetic", "revision": 8}]
        self.assertEqual(1, importer.apply_plan(api, self.plan())["applied"])
        self.assertEqual(7, api.call.call_args.args[2]["revision"])

    def test_apply_only_calls_formal_profile_api_and_counts_conflict(self):
        api = fake_api()
        result = importer.apply_plan(api, self.plan())
        self.assertEqual(1, result["applied"])
        self.assertEqual(("PUT", "/api/v1/authors/synthetic/profile"), api.call.call_args.args[:2])
        api = fake_api()
        api.call.side_effect = [[{"id": "synthetic", "displayName": "Synthetic Creator", "type": "regular"}], profile(), importer.ImportFailure("conflict")]
        self.assertEqual(1, importer.apply_plan(api, self.plan())["conflicts"])

    def test_default_dry_run_reads_no_token_and_prints_only_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "research.local"
            path.write_text(json.dumps({"records": [record()]}), encoding="utf-8")
            output = io.StringIO()
            with patch.object(importer, "API") as api, contextlib.redirect_stdout(output):
                status = importer.main([str(path)])
            api.assert_not_called()
            self.assertEqual(0, status)
            self.assertTrue(json.loads(output.getvalue())["dry_run"])
            self.assertNotIn("Synthetic", output.getvalue())
            self.assertNotIn("https", output.getvalue())

    def test_redirect_refused_and_http_errors_do_not_expose_response(self):
        self.assertIsNone(importer.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.example"))
        api = importer.API("http://127.0.0.1:18080", "synthetic-token")
        api.opener = Mock()
        api.opener.open.side_effect = error.HTTPError("https://secret.example", 302, "sensitive", {}, io.BytesIO(b"secret upstream body"))
        with self.assertRaises(importer.ImportFailure) as caught:
            api.call("GET", "/api/v1/authors")
        self.assertEqual("api_http_failure", str(caught.exception))
        self.assertEqual(importer.REQUEST_TIMEOUT_SECONDS, api.opener.open.call_args.kwargs["timeout"])

    def test_base_url_must_be_explicit_and_http_stays_local(self):
        for url in ("http://example.com", "https://u:p@example.com", "https://example.com/prefix", "https://example.com/?secret=yes"):
            with self.assertRaises(importer.ImportFailure):
                importer.validate_base_url(url)
        self.assertEqual("http://127.0.0.1:18430", importer.validate_base_url("http://127.0.0.1:18430/"))


if __name__ == "__main__":
    unittest.main()
