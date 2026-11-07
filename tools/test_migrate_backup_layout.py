import hashlib
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import tempfile
import unittest

import migrate_backup_layout as mig


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class MigrateBackupLayoutTests(unittest.TestCase):
    """只用临时目录合成文件验证旧三代→两代迁移；不接触真实库或用户数据。"""

    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self._temporary.cleanup)
        self.root = Path(self._temporary.name) / '资料维护'
        (self.root / '作者资料').mkdir(parents=True)
        (self.root / '备份').mkdir()
        self.current_txt = self.root / '作者资料' / '作者总表.txt'
        self.previous_txt = self.root / '作者资料' / '作者总表（上次）.txt'
        self.older_txt = self.root / '作者资料' / '作者总表（上上次）.txt'
        self.latest_backup = self.root / '备份' / '影库备份.json'
        self.previous_backup = self.root / '备份' / '影库备份（上次）.json'
        self.older_backup = self.root / '备份' / '影库备份（上上次）.json'

    def write_artifact(self, path: Path, data: bytes, checksum: bool = True) -> None:
        path.write_bytes(data)
        if checksum:
            path.with_name(path.name + '.sha256').write_text(sha(data), encoding='utf-8')

    def populate(self, *, older: bool = True, checksums: bool = True) -> None:
        self.write_artifact(self.current_txt, '当前作者总表'.encode(), checksums)
        self.write_artifact(self.previous_txt, '上次作者总表'.encode(), checksums)
        if older:
            self.write_artifact(self.older_txt, '上上次作者总表'.encode(), checksums)
        self.write_artifact(self.latest_backup, b'{"data":"current"}', checksums)
        self.write_artifact(self.previous_backup, b'{"data":"previous"}', checksums)
        if older:
            self.write_artifact(self.older_backup, b'{"data":"older"}', checksums)
        (self.root / '作者资料' / '作者资料.md').write_text('# 合成阅读版', encoding='utf-8')
        (self.root / '作者资料' / '作者片段索引.json').write_text('["作者总表.txt"]', encoding='utf-8')

    def snapshot(self) -> dict[str, object]:
        return {
            path.relative_to(self.root).as_posix(): (path.read_bytes() if path.is_file() else None)
            for path in sorted(self.root.rglob('*'))
        }

    def test_check_maps_generations_without_writing(self):
        self.populate()
        before = self.snapshot()
        report = mig.build_report(self.root, 'check')
        self.assertEqual(report['mode'], 'check')
        self.assertEqual(report['status'], 'ok')
        self.assertFalse(report['applied'])
        # 只读：不创建 当前/上次，也不改动任何旧文件。
        self.assertEqual(self.snapshot(), before)
        self.assertFalse((self.root / '当前').exists())
        self.assertFalse((self.root / '上次').exists())
        targets = {entry['source']: entry['target'] for entry in report['artifacts']}
        self.assertEqual(targets['作者资料/作者总表.txt'], '当前/作者资料/作者总表.txt')
        self.assertEqual(targets['作者资料/作者总表（上次）.txt'], '上次/作者资料/作者总表.txt')
        self.assertEqual(targets['备份/影库备份（上次）.json'], '上次/备份/影库备份.json')
        self.assertEqual(targets['作者资料/作者资料.md'], '当前/作者资料/作者资料.md')
        self.assertEqual(targets['作者资料/作者片段索引.json'], '当前/作者资料/作者片段索引.json')
        self.assertEqual(report['counts']['copy'], 6)
        self.assertEqual(set(report['cleanupCandidates']), {
            '作者资料/作者总表（上上次）.txt',
            '作者资料/作者总表（上上次）.txt.sha256',
            '备份/影库备份（上上次）.json',
            '备份/影库备份（上上次）.json.sha256',
        })
        # 摘要只输出相对路径，不回显维护根本机绝对路径。
        self.assertNotIn(str(self.root), json.dumps(report, ensure_ascii=False))

    def test_apply_creates_generations_and_keeps_legacy_files(self):
        self.populate()
        legacy_before = self.snapshot()
        report = mig.build_report(self.root, 'apply')
        self.assertEqual(report['status'], 'ok')
        self.assertTrue(report['applied'])
        self.assertEqual(report['counts']['copied'], 6)
        current = self.root / '当前'
        previous = self.root / '上次'
        self.assertEqual((current / '作者资料' / '作者总表.txt').read_bytes(), '当前作者总表'.encode())
        self.assertEqual((previous / '作者资料' / '作者总表.txt').read_bytes(), '上次作者总表'.encode())
        self.assertEqual((current / '备份' / '影库备份.json').read_bytes(), b'{"data":"current"}')
        self.assertEqual((previous / '备份' / '影库备份.json').read_bytes(), b'{"data":"previous"}')
        self.assertEqual((current / '作者资料' / '作者资料.md').read_text(encoding='utf-8'), '# 合成阅读版')
        self.assertEqual((current / '作者资料' / '作者片段索引.json').read_text(encoding='utf-8'), '["作者总表.txt"]')
        # 每代独立摘要，按新正文复算。
        for body in (
            current / '作者资料' / '作者总表.txt',
            previous / '作者资料' / '作者总表.txt',
            current / '备份' / '影库备份.json',
            previous / '备份' / '影库备份.json',
            current / '作者资料' / '作者资料.md',
            current / '作者资料' / '作者片段索引.json',
        ):
            sidecar = body.with_name(body.name + '.sha256')
            self.assertEqual(sidecar.read_text(encoding='utf-8').strip(), sha(body.read_bytes()), body.name)
        # 上上次不复制；旧文件不删除、不改写。
        self.assertFalse((current / '作者资料' / '作者总表（上上次）.txt').exists())
        self.assertFalse((previous / '作者资料' / '作者总表（上上次）.txt').exists())
        after = self.snapshot()
        for name, data in legacy_before.items():
            self.assertEqual(after.get(name), data, name)
        self.assertEqual([], [str(path) for path in self.root.rglob(mig.TEMP_PREFIX + '*')])

    def test_apply_is_idempotent(self):
        self.populate()
        first = mig.build_report(self.root, 'apply')
        self.assertEqual(first['counts']['copied'], 6)
        second = mig.build_report(self.root, 'apply')
        self.assertEqual(second['status'], 'ok')
        self.assertTrue(second['applied'])
        self.assertEqual(second['counts']['copied'], 0)
        self.assertEqual(second['counts']['unchanged'], 6)
        self.assertEqual([], [str(path) for path in self.root.rglob(mig.TEMP_PREFIX + '*')])

    def test_sha_mismatch_blocks_apply(self):
        self.populate()
        self.current_txt.with_name(self.current_txt.name + '.sha256').write_text('0' * 64, encoding='utf-8')
        report = mig.build_report(self.root, 'check')
        self.assertEqual(report['status'], 'issues')
        self.assertIn('sha_mismatch', {item['code'] for item in report['issues']})
        apply_report = mig.build_report(self.root, 'apply')
        self.assertEqual(apply_report['status'], 'issues')
        self.assertEqual(apply_report['counts']['copied'], 0)
        # 阻塞时不写任何文件。
        self.assertFalse((self.root / '当前').exists())
        self.assertFalse((self.root / '上次').exists())

    def test_missing_checksum_warns_but_copies_with_fresh_digest(self):
        self.populate(checksums=False)
        report = mig.build_report(self.root, 'check')
        self.assertEqual(report['status'], 'ok')
        self.assertIn('sha_missing', {item['code'] for item in report['warnings']})
        apply_report = mig.build_report(self.root, 'apply')
        self.assertEqual(apply_report['status'], 'ok')
        body = self.root / '当前' / '作者资料' / '作者总表.txt'
        sidecar = body.with_name(body.name + '.sha256')
        self.assertEqual(sidecar.read_text(encoding='utf-8').strip(), sha(body.read_bytes()))

    def test_target_occupied_blocks_apply(self):
        self.populate()
        target_directory = self.root / '当前' / '作者资料'
        target_directory.mkdir(parents=True)
        occupied = target_directory / '作者总表.txt'
        occupied.write_bytes(b'other')
        report = mig.build_report(self.root, 'apply')
        self.assertEqual(report['status'], 'issues')
        self.assertIn('target_occupied', {item['code'] for item in report['issues']})
        self.assertEqual(occupied.read_bytes(), b'other')
        self.assertFalse((target_directory / '作者总表.txt.sha256').exists())
        self.assertFalse((self.root / '上次').exists())

    def test_older_corruption_is_warning_only(self):
        self.populate()
        self.older_txt.with_name(self.older_txt.name + '.sha256').write_text('f' * 64, encoding='utf-8')
        report = mig.build_report(self.root, 'check')
        self.assertEqual(report['status'], 'ok')
        self.assertIn('sha_mismatch', {item['code'] for item in report['warnings']})
        self.assertIn('作者资料/作者总表（上上次）.txt', report['cleanupCandidates'])
        apply_report = mig.build_report(self.root, 'apply')
        self.assertEqual(apply_report['status'], 'ok')
        self.assertFalse((self.root / '当前' / '作者资料' / '作者总表（上上次）.txt').exists())

    def test_missing_latest_with_previous_blocks(self):
        self.write_artifact(self.previous_txt, '上次'.encode())
        report = mig.build_report(self.root, 'check')
        self.assertEqual(report['status'], 'issues')
        self.assertIn('current_missing', {item['code'] for item in report['issues']})
        self.assertEqual(mig.build_report(self.root, 'apply')['counts']['copied'], 0)
        self.assertFalse((self.root / '当前').exists())

    def test_missing_previous_is_warning_and_copies_current_only(self):
        self.write_artifact(self.current_txt, '当前'.encode())
        report = mig.build_report(self.root, 'check')
        self.assertEqual(report['status'], 'ok')
        self.assertIn('previous_missing', {item['code'] for item in report['warnings']})
        apply_report = mig.build_report(self.root, 'apply')
        self.assertEqual(apply_report['status'], 'ok')
        self.assertEqual((self.root / '当前' / '作者资料' / '作者总表.txt').read_bytes(), '当前'.encode())
        self.assertFalse((self.root / '上次').exists())

    def test_non_regular_source_blocks_apply(self):
        self.populate()
        self.current_txt.unlink()
        self.current_txt.with_name(self.current_txt.name + '.sha256').unlink()
        self.current_txt.mkdir()
        report = mig.build_report(self.root, 'check')
        self.assertEqual(report['status'], 'issues')
        self.assertIn('source_unreadable', {item['code'] for item in report['issues']})
        self.assertEqual(mig.build_report(self.root, 'apply')['counts']['copied'], 0)
        self.assertFalse((self.root / '当前').exists())

    def test_invalid_root_exits_two(self):
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = mig.main(['--root', str(self.root / 'missing')])
        self.assertEqual(code, 2)
        payload = json.loads(buffer.getvalue())
        self.assertEqual(payload['status'], 'invalid')
        file_root = Path(self._temporary.name) / 'file-root'
        file_root.write_text('x', encoding='utf-8')
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = mig.main(['--root', str(file_root)])
        self.assertEqual(code, 2)
        self.assertEqual(json.loads(buffer.getvalue())['status'], 'invalid')

    def test_cli_check_and_apply_json(self):
        self.populate()
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = mig.main(['--root', str(self.root)])
        self.assertEqual(code, 0)
        payload = json.loads(buffer.getvalue())
        self.assertEqual(payload['tool'], mig.TOOL_NAME)
        self.assertEqual(payload['mode'], 'check')
        self.assertEqual(payload['status'], 'ok')
        self.assertFalse((self.root / '当前').exists())
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = mig.main(['--root', str(self.root), '--apply'])
        self.assertEqual(code, 0)
        payload = json.loads(buffer.getvalue())
        self.assertEqual(payload['mode'], 'apply')
        self.assertTrue(payload['applied'])
        self.assertTrue((self.root / '当前' / '作者资料' / '作者总表.txt').is_file())
        self.assertTrue((self.root / '上次' / '备份' / '影库备份.json').is_file())


if __name__ == '__main__':
    unittest.main()
