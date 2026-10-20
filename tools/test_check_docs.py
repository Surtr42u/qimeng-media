import tempfile
import unittest
from pathlib import Path

from check_docs import check_impact, check_links, check_workflows


class DocumentationGateTest(unittest.TestCase):
    def test_workflow_requires_sha_and_readonly_baseline(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            directory = root / '.github/workflows'
            directory.mkdir(parents=True)
            workflow = directory / 'test.yml'
            workflow.write_text('jobs:\n  job:\n    steps:\n      - uses: actions/checkout@v5\n', encoding='utf-8')
            self.assertEqual(len(check_workflows(root)), 2)
            workflow.write_text('permissions:\n  contents: read\njobs:\n  job:\n    steps:\n      - uses: actions/checkout@' + 'a' * 40 + '\n', encoding='utf-8')
            self.assertEqual(check_workflows(root), [])

    def test_missing_file_and_heading_are_reported(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'a.md').write_text('[missing](missing.md)\n[bad](b.md#missing)\n[good](b.md#有效章节)', encoding='utf-8')
            (root / 'b.md').write_text('# 有效章节\n', encoding='utf-8')
            self.assertEqual(len(check_links(root, ['a.md'])), 2)

    def test_changelog_alone_does_not_satisfy_business_sync(self):
        _, errors = check_impact(['server/internal/stats/aggregate.go', 'docs/CHANGELOG.md'], '')
        self.assertTrue(errors)

    def test_real_no_impact_explanation_allows_internal_refactor(self):
        body = '文档影响：无；原因：只调整内部变量命名，输入输出与既有业务语义不变。'
        _, errors = check_impact(['server/internal/stats/aggregate.go'], body)
        self.assertFalse(errors)

    def test_actual_topic_update_satisfies_impact(self):
        _, errors = check_impact(['server/internal/stats/aggregate.go', 'docs/domain/statistics.md'], '')
        self.assertFalse(errors)


if __name__ == '__main__':
    unittest.main()
