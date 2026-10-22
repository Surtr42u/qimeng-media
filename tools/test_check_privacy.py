"""隐私门禁的合成回归；不接触用户的媒体、备份、密钥或现用库。"""
from pathlib import Path
import subprocess
import tempfile
import unittest

from check_privacy import content_codes, private_path, scan


class PrivacyTests(unittest.TestCase):
    def test_private_payload_paths_are_blocked_even_if_force_added(self):
        for name in ('nested/qimeng_backup.json', 'temporary/library.db', 'notes-private.json',
                     'key.pem', '.env', '.env.production', 'qimeng-data/source', 'settings.local.json'):
            with self.subTest(name=name):
                self.assertTrue(private_path(name))
        for name in ('.env.example', 'api/openapi.yaml', 'config/dev.example.yaml', 'debug.keystore'):
            with self.subTest(name=name):
                self.assertFalse(private_path(name))

    def test_personal_identifiers_and_unknown_backup_name(self):
        email = ('synthetic-owner' + '@' + 'qq.com').encode()
        self.assertIn('personal-email', content_codes('notes.md', email))
        path = ('C:' + '\\Users\\' + 'synthetic-real-profile' + '\\documents').encode()
        self.assertIn('personal-profile-path', content_codes('notes.md', path))
        serial = ('adb -s ' + '123456789012').encode()
        self.assertIn('device-serial-literal', content_codes('script.ps1', serial))
        payload = b'{"format":"qimeng_backup","schemaVersion":2,"data":{}}'
        self.assertIn('private-backup-or-audit-payload', content_codes('renamed.json', payload))
        self.assertIn('private-backup-or-audit-payload', content_codes('renamed.txt', payload))
        self.assertIn('local-private-identifier', content_codes('notes.md', b'value synthetic-local-id', ['synthetic-local-id']))
        self.assertFalse(content_codes('notes.md', b'synthetic-owner@example.com'))

    def test_staged_bytes_not_changed_worktree_are_scanned(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def git(*args):
                subprocess.run(['git', *args], cwd=root, check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            git('init')
            git('config', 'user.name', 'Synthetic Author')
            git('config', 'user.email', 'synthetic@users.noreply.github.com')
            value = ('synthetic-owner' + '@' + 'qq.com')
            file = root / 'notes.md'
            file.write_text(value)
            git('add', 'notes.md')
            file.write_text('safe worktree replacement')
            self.assertIn('personal-email', scan(root, 'staged'))
            self.assertFalse(scan(root, 'worktree'))

    def test_history_checks_author_metadata_and_private_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def git(*args):
                subprocess.run(['git', *args], cwd=root, check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            git('init')
            git('config', 'user.name', 'Synthetic Author')
            git('config', 'user.email', 'synthetic@users.noreply.github.com')
            (root / 'old.db').write_bytes(b'synthetic-database-placeholder')
            git('add', 'old.db')
            # 单次合成提交覆盖作者元数据；不修改开发机的 Git 配置。
            git('-c', 'user.email=' + 'synthetic-owner' + '@' + 'qq.com',
                '-c', 'core.hooksPath=/dev/null', 'commit', '-m', 'synthetic fixture')
            result = scan(root, 'history')
            self.assertIn('personal-email-in-commit-metadata', result)
            self.assertIn('private-file-path', result)


if __name__ == '__main__':
    unittest.main()
