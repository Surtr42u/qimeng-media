"""锁定 APK 候选上传的失败阻断契约；由 docs 云端 unittest 执行。

仅审计本仓固定单 job/步骤缩进，不是通用 YAML 解析器或完整打包器。
GitHub 默认 success() 的语义：
https://docs.github.com/en/actions/reference/workflows-and-actions/expressions#status-check-functions
"""

import re
import unittest
from pathlib import Path


def release_gate_errors(text):
    # 与现行模板绑定，意外改成不同 job/步骤布局时失败，要求重新审查。
    blocks = re.split(r"(?m)^      - ", text)
    gate = [block for block in blocks if "./gradlew testDebugUnitTest :core:model:test lintRelease" in block]
    uploads = [block for block in blocks if "uses: actions/upload-artifact@" in block]
    errors = []
    if len(gate) != 1 or len(uploads) != 1:
        return ["必须有唯一完整单测/Lint门禁及候选上传步骤"]
    gate, upload = gate[0], uploads[0]
    if blocks.index(gate) >= blocks.index(upload):
        errors.append("候选上传必须在单测/Lint之后")
    # 失败不能通过 workflow/job/step continue-on-error 或自定义 if 绕过。
    prefix = "      - ".join(blocks[:blocks.index(upload) + 1])
    if re.search(r"(?m)^\s*(?:-\s*)?continue-on-error:", prefix):
        errors.append("候选上传前不得放宽失败")
    for block in (gate, upload):
        if re.search(r"(?m)^\s*if:", block):
            errors.append("门禁及候选上传须保持默认success条件")
    # 固定 shell 片段，避免 || true / 后续成功命令吞掉 Gradle 退出状态。
    run = re.search(r"(?m)^        run: \|\n((?:          .*\n?)*)", gate)
    commands = [] if run is None else [line.strip() for line in run[1].splitlines() if line.strip()]
    if commands != ["cd android", "./gradlew testDebugUnitTest :core:model:test lintRelease"]:
        errors.append("门禁命令不可跳过或掩盖退出码")
    return errors


class ReleaseGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (Path(__file__).resolve().parents[1] / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

    def test_current_candidate_requires_successful_tests(self):
        self.assertEqual(release_gate_errors(self.source), [])

    def test_controlled_swallowed_failure_is_rejected(self):
        broken = self.source.replace("./gradlew testDebugUnitTest :core:model:test lintRelease", "./gradlew testDebugUnitTest :core:model:test lintRelease || true")
        self.assertIn("门禁命令不可跳过或掩盖退出码", release_gate_errors(broken))

    def test_controlled_continue_on_error_is_rejected(self):
        broken = self.source.replace("      - name: 单测与 Release Lint", "      - continue-on-error: true\n        name: 单测与 Release Lint")
        self.assertIn("候选上传前不得放宽失败", release_gate_errors(broken))

    def test_controlled_always_upload_is_rejected(self):
        broken = self.source.replace("      - name: 上传候选包", "      - if: always()\n        name: 上传候选包")
        self.assertIn("门禁及候选上传须保持默认success条件", release_gate_errors(broken))

    def test_upload_before_tests_is_rejected(self):
        blocks = re.split(r"(?m)^      - ", self.source)
        gate = next(i for i, block in enumerate(blocks) if "./gradlew testDebugUnitTest" in block)
        upload = next(i for i, block in enumerate(blocks) if "uses: actions/upload-artifact@" in block)
        blocks[gate], blocks[upload] = blocks[upload], blocks[gate]
        self.assertIn("候选上传必须在单测/Lint之后", release_gate_errors("      - ".join(blocks)))


if __name__ == "__main__":
    unittest.main()
