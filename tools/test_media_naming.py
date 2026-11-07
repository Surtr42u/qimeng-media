"""作品编号/组图页码命名规划引擎回归；全部合成文件名，不访问真实库/网络/媒体文件。

覆盖：解析与索引（组图页共享作品编号、旧括号序号不占作品编号）、计划生成
（规范连续名、乱码名、单图+组图混合、多组、乱序、缺页、补图、位宽/分隔保留、
全角括号）、确认门禁、前置指纹/计划指纹、目标占用与跳号校验、执行步骤、
回读判定（自动改名/缺失/多余）、并发串行化（两线程模拟两进程）与锁超时/失效。
"""
from __future__ import annotations

import os
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path

from ai_maintenance import media_naming as naming

INSTANCE = "http://127.0.0.1:18430"
LIBRARY = {"id": "lib-synthetic", "name": "合成库"}


def group(family, names, *, confidence=0.95, confirmed=True, order=(), existing=None):
    files = [{"name": name, "assetId": "asset-%d" % index} for index, name in enumerate(names)]
    payload = {"family": family, "files": files, "confidence": confidence, "confirmed": confirmed}
    if order:
        payload["order"] = list(order)
    if existing is not None:
        payload["existingWorkNumber"] = existing
    return payload


def plan_for(groups, current, *, directory="作者/作品", library=LIBRARY, rules=None):
    return naming.prepare_plan(
        groups,
        current,
        instance=INSTANCE,
        library=library,
        directory=directory,
        rules=rules,
    )


def codes(result):
    return [error["code"] for error in result["errors"]]


class ParseAndIndexTest(unittest.TestCase):
    def test_group_page_parse_carries_work_number_and_page(self):
        info = naming.parse_file_name("守望先锋 朱诺 11 (2).png")
        self.assertEqual("守望先锋 朱诺", info.family)
        self.assertEqual(11, info.work_number)
        self.assertEqual(2, info.work_width)
        self.assertEqual(2, info.page_number)
        self.assertTrue(info.is_group_page)
        self.assertFalse(info.is_legacy_bracket_series)

    def test_standalone_film_parse(self):
        info = naming.parse_file_name("海景 007.png")
        self.assertEqual("海景", info.family)
        self.assertEqual(7, info.work_number)
        self.assertEqual(3, info.work_width)
        self.assertIsNone(info.page_number)
        self.assertFalse(info.is_group_page)

    def test_legacy_bracket_series_is_not_a_work_number(self):
        info = naming.parse_file_name("旅行 (2).jpg")
        self.assertIsNone(info.work_number)
        self.assertTrue(info.is_legacy_bracket_series)
        self.assertEqual("旅行", info.family)

    def test_work_index_counts_group_pages_once(self):
        names = ["守望先锋 朱诺 %d.png" % number for number in range(1, 11)]
        names += ["守望先锋 朱诺 11 (1).png", "守望先锋 朱诺 11 (2).png", "守望先锋 朱诺 11 (4).png"]
        index = naming.build_work_index(names)
        family = index.family("守望先锋 朱诺")
        self.assertIsNotNone(family)
        self.assertEqual(12, family.next_work_number())
        self.assertEqual({1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, set(family.work_numbers))
        self.assertEqual((1, 2, 4), family.work_numbers[11])
        self.assertEqual(4, family.max_page(11))

    def test_work_index_preserves_style_and_ignores_legacy_bracket_series(self):
        index = naming.build_work_index(["名 007 (1).png", "旅行 (1).jpg", "旅行 (2).jpg"])
        family = index.family("名")
        self.assertEqual(3, family.work_width)
        self.assertEqual(" ", family.separator)
        self.assertIsNone(index.family("旅行"))
        self.assertEqual(1, index.next_work_number("旅行"))

    def test_work_index_full_width_page_brackets(self):
        index = naming.build_work_index(["名 11（1）.png", "名 11（2）.png"])
        self.assertEqual((1, 2), index.family("名").work_numbers[11])
        self.assertEqual(12, index.next_work_number("名"))


class PreparePlanTest(unittest.TestCase):
    def test_sequential_works_plus_new_group_uses_next_work_number(self):
        current = ["守望先锋 朱诺 %d.png" % number for number in range(1, 11)]
        current += ["IMG_0001.png", "IMG_0002.png", "IMG_0003.png", "IMG_0004.png"]
        plan = plan_for(
            [group("守望先锋 朱诺", ["IMG_0001.png", "IMG_0002.png", "IMG_0003.png", "IMG_0004.png"])],
            current,
        )
        self.assertEqual("ready", plan["status"])
        self.assertEqual(11, plan["groups"][0]["workNumber"])
        self.assertEqual([1, 2, 3, 4], [page["pageNumber"] for page in plan["groups"][0]["pages"]])
        self.assertEqual(
            [
                "守望先锋 朱诺 11 (1).png",
                "守望先锋 朱诺 11 (2).png",
                "守望先锋 朱诺 11 (3).png",
                "守望先锋 朱诺 11 (4).png",
            ],
            [change["targetName"] for change in plan["changes"]],
        )
        self.assertEqual(1, plan["impact"]["works"])
        self.assertEqual(4, plan["impact"]["files"])

    def test_group_plus_standalone_mixed_assigns_consecutive_works(self):
        current = ["名 1.png", "名 2.png", "乱码A.png", "乱码B.png", "单图下载.png"]
        plan = plan_for(
            [
                group("名", ["乱码A.png", "乱码B.png"]),
                group("名", ["单图下载.png"]),
            ],
            current,
        )
        self.assertEqual([3, 4], [item["workNumber"] for item in plan["groups"]])
        self.assertEqual("group", plan["groups"][0]["kind"])
        self.assertEqual("standalone", plan["groups"][1]["kind"])
        self.assertEqual("名 4.png", plan["groups"][1]["pages"][0]["targetName"])
        self.assertEqual(3, len(plan["changes"]))

    def test_garbage_names_only_in_proposal_never_in_targets(self):
        current = ["IMG_9001_DxO.png", "IMG_9002_DxO.png"]
        plan = plan_for([group("守望先锋 朱诺", ["IMG_9001_DxO.png", "IMG_9002_DxO.png"])], current)
        for change in plan["changes"]:
            self.assertNotIn("IMG_", change["targetName"])
            self.assertTrue(change["targetName"].startswith("守望先锋 朱诺 1 ("))

    def test_disordered_current_names_produce_same_plan(self):
        current = ["名 %d.png" % number for number in range(1, 8)]
        plan_a = plan_for([group("名", ["a.png"])], current + ["a.png"])
        shuffled = list(reversed(current)) + ["a.png"]
        plan_b = plan_for([group("名", ["a.png"])], shuffled)
        self.assertEqual(8, plan_a["groups"][0]["workNumber"])
        self.assertEqual(plan_a["planFingerprint"], plan_b["planFingerprint"])

    def test_existing_group_pages_occupy_one_number(self):
        current = ["名 11 (1).png", "名 11 (2).png", "名 11 (3).png", "x.png"]
        plan = plan_for([group("名", ["x.png"])], current)
        self.assertEqual(12, plan["groups"][0]["workNumber"])

    def test_append_pages_keeps_work_number_and_skips_missing_pages(self):
        current = ["名 11 (1).png", "名 11 (2).png", "名 11 (4).png", "a.png", "b.png"]
        plan = plan_for([group("名", ["a.png", "b.png"], existing=11)], current)
        self.assertEqual(11, plan["groups"][0]["workNumber"])
        self.assertTrue(plan["groups"][0]["existingWorkNumber"])
        self.assertEqual([5, 6], [page["pageNumber"] for page in plan["groups"][0]["pages"]])
        self.assertEqual(["名 11 (5).png", "名 11 (6).png"], [change["targetName"] for change in plan["changes"]])
        self.assertEqual(2, plan["impact"]["appendedPages"])

    def test_zero_padding_work_number_width_preserved(self):
        current = ["名 0007 (1).png", "a.png", "b.png"]
        plan = plan_for([group("名", ["a.png", "b.png"])], current)
        self.assertEqual("名 0008 (1).png", plan["changes"][0]["targetName"])
        self.assertEqual("名 0008 (2).png", plan["changes"][1]["targetName"])

    def test_tight_separator_style_preserved_for_work_number(self):
        current = ["朱诺11(1).png", "朱诺11(2).png", "a.png", "b.png"]
        plan = plan_for([group("朱诺", ["a.png", "b.png"])], current)
        self.assertEqual("朱诺12 (1).png", plan["changes"][0]["targetName"])

    def test_full_width_existing_page_brackets_still_advance_work_number(self):
        current = ["名 11（1）.png", "名 11（2）.png", "a.png", "b.png"]
        plan = plan_for([group("名", ["a.png", "b.png"])], current)
        self.assertEqual(12, plan["groups"][0]["workNumber"])
        self.assertEqual("名 12 (1).png", plan["changes"][0]["targetName"])
        self.assertEqual("名 12 (2).png", plan["changes"][1]["targetName"])

    def test_multiple_families_get_independent_numbers(self):
        current = ["甲 1.png", "甲 2.png", "乙 1.png", "乙 2.png", "乙 3.png", "a.png", "b.png"]
        plan = plan_for(
            [group("甲", ["a.png"]), group("乙", ["b.png"])],
            current,
        )
        self.assertEqual([3, 4], [item["workNumber"] for item in plan["groups"]])

    def test_order_controls_page_numbers(self):
        current = ["a.png", "b.png"]
        plan = plan_for([group("名", ["a.png", "b.png"], order=["b.png", "a.png"])], current)
        pages = plan["groups"][0]["pages"]
        self.assertEqual(["b.png", "a.png"], [page["sourceName"] for page in pages])
        self.assertEqual([1, 2], [page["pageNumber"] for page in pages])
        self.assertEqual("名 1 (1).png", pages[0]["targetName"])

    def test_low_confidence_or_unconfirmed_needs_confirmation(self):
        current = ["a.png", "b.png"]
        low = plan_for([group("名", ["a.png", "b.png"], confidence=0.4, confirmed=True)], current)
        self.assertEqual("needs_confirmation", low["status"])
        self.assertTrue(low["permissions"]["confirmationRequired"])
        with self.assertRaises(naming.NamingError) as caught:
            naming.plan_steps(low)
        self.assertEqual(naming.ERR_NEEDS_CONFIRMATION, caught.exception.code)

        unconfirmed = plan_for([group("名", ["a.png", "b.png"], confidence=0.99, confirmed=False)], current)
        self.assertEqual("needs_confirmation", unconfirmed["status"])
        self.assertEqual(
            naming.ERR_NEEDS_CONFIRMATION,
            codes(naming.validate_plan(unconfirmed, current, confirmed=True))[0],
        )

    def test_custom_min_confidence_rule(self):
        current = ["a.png", "b.png"]
        plan = plan_for(
            [group("名", ["a.png", "b.png"], confidence=0.6)],
            current,
            rules={"minConfidence": 0.5, "requireConfirmed": True},
        )
        self.assertEqual("ready", plan["status"])

    def test_duplicate_and_unknown_sources_rejected(self):
        with self.assertRaises(naming.NamingError) as caught:
            plan_for([group("名", ["a.png"]), group("名", ["a.png"])], ["a.png"])
        self.assertEqual(naming.ERR_DUPLICATE_SOURCE, caught.exception.code)
        with self.assertRaises(naming.NamingError) as caught:
            plan_for([group("名", ["missing.png"])], ["a.png"])
        self.assertEqual(naming.ERR_UNKNOWN_SOURCE, caught.exception.code)

    def test_invalid_family_rejected(self):
        with self.assertRaises(naming.NamingError) as caught:
            plan_for([group("非法/基名", ["a.png"])], ["a.png"])
        self.assertEqual(naming.ERR_INVALID_FAMILY, caught.exception.code)

    def test_legacy_bracket_series_does_not_occupy_work_number(self):
        current = ["旅行 (1).jpg", "旅行 (2).jpg", "a.png"]
        plan = plan_for([group("旅行", ["a.png"])], current)
        self.assertEqual(1, plan["groups"][0]["workNumber"])
        self.assertEqual("旅行 1.png", plan["changes"][0]["targetName"])

    def test_plan_contains_machine_and_human_readable_fields(self):
        plan = plan_for([group("名", ["a.png", "b.png"])], ["a.png", "b.png"])
        for key in (
            "status",
            "target",
            "precondition",
            "planFingerprint",
            "idempotencyKey",
            "permissions",
            "impact",
            "groups",
            "changes",
            "expectedReadback",
            "preview",
        ):
            self.assertIn(key, plan)
        self.assertEqual(LIBRARY, plan["target"]["library"])
        self.assertIn("作品编号 1", naming.render_preview(plan))
        self.assertEqual(plan["planFingerprint"], naming.plan_fingerprint(plan))
        self.assertTrue(naming.verify_plan_fingerprint(plan))

    def test_fingerprint_stable_and_idempotency_key_instance_bound(self):
        current = ["a.png", "b.png"]
        first = plan_for([group("名", ["a.png", "b.png"])], current)
        second = plan_for([group("名", ["a.png", "b.png"])], list(reversed(current)))
        self.assertEqual(first["planFingerprint"], second["planFingerprint"])
        self.assertEqual(first["idempotencyKey"], second["idempotencyKey"])
        other_library = dict(LIBRARY, id="other-lib")
        self.assertNotEqual(
            first["idempotencyKey"],
            plan_for([group("名", ["a.png", "b.png"])], current, library=other_library)["idempotencyKey"],
        )


class ValidatePlanTest(unittest.TestCase):
    def make_plan(self):
        current = ["名 %d.png" % number for number in range(1, 6)] + ["a.png", "b.png"]
        return current, plan_for([group("名", ["a.png", "b.png"])], current)

    def test_valid_plan_passes(self):
        current, plan = self.make_plan()
        result = naming.validate_plan(plan, current, confirmed=True)
        self.assertTrue(result["ok"], result)
        self.assertEqual([], result["errors"])

    def test_changed_current_state_is_stale(self):
        current, plan = self.make_plan()
        result = naming.validate_plan(plan, current + ["新文件.png"], confirmed=True)
        self.assertFalse(result["ok"])
        self.assertIn(naming.ERR_STALE_PRECONDITION, codes(result))

    def test_tampered_plan_fingerprint_rejected(self):
        current, plan = self.make_plan()
        plan["changes"][0]["targetName"] = "被篡改.png"
        result = naming.validate_plan(plan, current, confirmed=True)
        self.assertFalse(result["ok"])
        self.assertIn(naming.ERR_PLAN_FINGERPRINT, codes(result))

    def test_work_number_gap_and_conflict_detected(self):
        current, plan = self.make_plan()
        gap_plan = dict(plan, groups=[dict(plan["groups"][0], workNumber=8)])
        gap_plan["planFingerprint"] = naming.plan_fingerprint(gap_plan)
        self.assertIn(naming.ERR_WORK_NUMBER_GAP, codes(naming.validate_plan(gap_plan, current, confirmed=True)))
        conflict_plan = dict(plan, groups=[dict(plan["groups"][0], workNumber=5)])
        conflict_plan["planFingerprint"] = naming.plan_fingerprint(conflict_plan)
        self.assertIn(
            naming.ERR_WORK_NUMBER_CONFLICT,
            codes(naming.validate_plan(conflict_plan, current, confirmed=True)),
        )

    def test_target_name_collision_detected(self):
        current, plan = self.make_plan()
        tampered = dict(plan)
        tampered["changes"] = [dict(change) for change in plan["changes"]]
        tampered["changes"][0]["targetName"] = "名 1.png"  # 已被现存文件占用
        tampered["planFingerprint"] = naming.plan_fingerprint(tampered)
        result = naming.validate_plan(tampered, current, confirmed=True)
        self.assertFalse(result["ok"])
        self.assertIn(naming.ERR_TARGET_CONFLICT, codes(result))

    def test_missing_source_detected(self):
        current, plan = self.make_plan()
        shrunk = [name for name in current if name != "a.png"] + ["其他.png", "另一.png"]
        result = naming.validate_plan(plan, shrunk, confirmed=True)
        self.assertFalse(result["ok"])
        self.assertIn(naming.ERR_STALE_PRECONDITION, codes(result))


class PlanStepsTest(unittest.TestCase):
    def test_steps_target_formal_move_endpoint(self):
        current = ["名 1.png", "a.png", "b.png"]
        plan = plan_for([group("名", ["a.png", "b.png"])], current)
        steps = naming.plan_steps(plan)
        self.assertEqual(2, len(steps))
        first = steps[0]
        self.assertEqual(1, first["index"])
        self.assertEqual("a.png", first["sourceName"])
        self.assertEqual("名 2 (1).png", first["targetName"])
        self.assertEqual("作者/作品", first["targetDir"])
        self.assertEqual("POST", first["method"])
        self.assertIn("/api/v1/assets/{assetId}/move", first["pathTemplate"])
        self.assertEqual("assetId", first["resolution"])
        self.assertEqual(
            {"targetDir": "作者/作品", "newName": "名 2 (1).png"},
            first["body"],
        )


class ReadbackTest(unittest.TestCase):
    def make_plan(self):
        current = ["名 1.png", "a.png", "b.png"]
        plan = plan_for([group("名", ["a.png", "b.png"])], current)
        after = ["名 1.png", "名 2 (1).png", "名 2 (2).png"]
        return plan, after

    def test_exact_readback_verified(self):
        plan, after = self.make_plan()
        report = naming.readback_report(plan, after)
        self.assertTrue(report["ok"])
        self.assertEqual("verified", report["status"])
        self.assertIsNone(report["reason"])

    def test_server_auto_rename_is_failure_and_keeps_committed_steps(self):
        plan, after = self.make_plan()
        after = ["名 1.png", "名 2 (1).png", "名 2 (2) (2).png"]
        committed = [{"index": 1, "assetId": "asset-0", "status": "committed"}]
        report = naming.readback_report(plan, after, committed_steps=committed)
        self.assertFalse(report["ok"])
        self.assertEqual("failed", report["status"])
        self.assertEqual("auto_renamed", report["reason"])
        self.assertEqual(
            [{"expected": "名 2 (2).png", "actual": "名 2 (2) (2).png"}],
            report["autoRenamed"],
        )
        self.assertEqual(committed, report["committedSteps"])

    def test_missing_and_unexpected_are_failures(self):
        plan, after = self.make_plan()
        report = naming.readback_report(plan, ["名 1.png", "名 2 (1).png"])
        self.assertFalse(report["ok"])
        self.assertEqual("missing", report["reason"])
        self.assertEqual(["名 2 (2).png"], report["missing"])

        report = naming.readback_report(plan, after + ["多余文件.png"])
        self.assertFalse(report["ok"])
        self.assertEqual("unexpected", report["reason"])
        self.assertEqual(["多余文件.png"], report["unexpected"])


class SerializedLocksTest(unittest.TestCase):
    def setUp(self):
        # 直接使用系统临时目录（不新建子目录）：本机受限沙箱只允许在已存在目录内写文件。
        self.lock_dir = Path(tempfile.gettempdir())
        self.library = {"id": "lib-media-naming-" + uuid.uuid4().hex, "name": "合成库"}
        self.current = ["名 %d.png" % number for number in range(1, 6)] + ["a.png", "b.png"]
        self.plan = plan_for([group("名", ["a.png", "b.png"])], self.current, library=self.library)

    def tearDown(self):
        # 只清理本用例产生的锁文件（文件名按 scope 哈希，不碰其他进程的锁）。
        for family in ("", "名"):
            try:
                self.lock_path(family).unlink()
            except FileNotFoundError:
                pass

    def lock_path(self, family=""):
        return self.lock_dir / naming._lock_file_name(naming.lock_scope(INSTANCE, self.library, family))

    def test_lock_acquire_release_and_context_manager(self):
        locks = naming.SerializedLocks(self.lock_dir, INSTANCE, self.library, timeout=2)
        with locks:
            self.assertTrue(self.lock_path().exists())
        self.assertFalse(self.lock_path().exists())
        info = locks.acquire(timeout=1)
        self.assertTrue(self.lock_path().exists())
        self.assertIn("lockFile", info)
        self.assertTrue(info["lockFile"].endswith(".lock"))
        self.assertNotIn(os.sep, info["lockFile"])
        locks.release()
        self.assertFalse(self.lock_path().exists())

    def test_family_lock_context_also_blocks_library_level_apply(self):
        holder = naming.SerializedLocks(self.lock_dir, INSTANCE, self.library, timeout=2)
        with holder.family("名"):
            self.assertTrue(self.lock_path("名").exists())
            waiter = naming.SerializedLocks(self.lock_dir, INSTANCE, self.library, timeout=0.1)
            with self.assertRaises(naming.NamingError) as caught:
                waiter.acquire()
            self.assertEqual(naming.ERR_LOCK_TIMEOUT, caught.exception.code)
        self.assertFalse(self.lock_path().exists())
        self.assertFalse(self.lock_path("名").exists())

    def test_lock_timeout_when_another_manager_holds_it(self):
        holder = naming.SerializedLocks(self.lock_dir, INSTANCE, self.library, timeout=2)
        holder.acquire()
        try:
            waiter = naming.SerializedLocks(self.lock_dir, INSTANCE, self.library, timeout=0.2)
            with self.assertRaises(naming.NamingError) as caught:
                waiter.acquire()
            self.assertEqual(naming.ERR_LOCK_TIMEOUT, caught.exception.code)
        finally:
            holder.release()

    def test_stale_lock_reports_lock_stale_without_auto_delete(self):
        path = self.lock_path()
        path.write_text("{}", encoding="utf-8")
        old = time.time() - 3600
        os.utime(path, (old, old))
        locks = naming.SerializedLocks(self.lock_dir, INSTANCE, self.library, timeout=1, stale_after=60)
        with self.assertRaises(naming.NamingError) as caught:
            locks.acquire()
        self.assertEqual(naming.ERR_LOCK_STALE, caught.exception.code)
        self.assertTrue(path.exists())  # 失效锁不自动删除，留给人工清理

    def test_two_batches_only_one_succeeds_and_no_duplicate_number(self):
        """两个独立锁实例（各自进程内锁）经同一 O_EXCL 锁文件串行化，
        模拟两进程并发：先到者成功写入，后到者拿到锁时重新校验现状，
        必须报 stale_precondition；最终目录中不会出现重复的作品编号目标名。"""
        results = {}
        final_names = list(self.current)
        state_lock = threading.Lock()
        first_holding = threading.Event()

        def batch(worker):
            locks = naming.SerializedLocks(self.lock_dir, INSTANCE, self.library, timeout=5)
            try:
                with locks:
                    if worker == 0:
                        first_holding.set()
                    else:
                        first_holding.wait(timeout=4)
                    with state_lock:
                        current_snapshot = list(final_names)
                    verdict = naming.validate_plan(self.plan, current_snapshot, confirmed=True)
                    if not verdict["ok"]:
                        results[worker] = ("failed", codes(verdict))
                        return
                    targets = [step["targetName"] for step in naming.plan_steps(self.plan)]
                    with state_lock:
                        merged = [name for name in final_names if name not in ("a.png", "b.png")]
                        merged.extend(targets)
                        final_names[:] = merged
                    results[worker] = ("ok", targets)
            except naming.NamingError as exc:
                results[worker] = ("failed", [exc.code])

        first = threading.Thread(target=batch, args=(0,))
        second = threading.Thread(target=batch, args=(1,))
        first.start()
        self.assertTrue(first_holding.wait(timeout=5))
        second.start()
        for thread in (first, second):
            thread.join(timeout=10)
            self.assertFalse(thread.is_alive())

        successes = [value for value in results.values() if value[0] == "ok"]
        failures = [value for value in results.values() if value[0] == "failed"]
        self.assertEqual(1, len(successes), results)
        self.assertEqual(1, len(failures), results)
        self.assertIn(naming.ERR_STALE_PRECONDITION, failures[0][1])
        work_targets = [name for name in final_names if name.startswith("名 6 (")]
        self.assertEqual(len(work_targets), len(set(work_targets)))
        self.assertEqual(2, len(work_targets))
        self.assertEqual(len(final_names), len(set(final_names)))


if __name__ == "__main__":
    unittest.main()
