from pathlib import Path
import subprocess
import tempfile
import unittest

from check_migrations import check_migrations


class MigrationGuardTests(unittest.TestCase):
    def test_history_and_worktree(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL)
            git('init')
            git('config', 'user.name', 'Synthetic Test')
            git('config', 'user.email', 'synthetic@example.invalid')
            git('config', 'core.autocrlf', 'false')
            migrations = root / 'server/migrations'
            migrations.mkdir(parents=True)
            original = migrations / '0001_init.up.sql'
            original.write_text('SELECT 1;\n', encoding='utf-8')
            git('add', '.')
            git('commit', '-m', 'synthetic baseline')
            base = git('rev-parse', 'HEAD').decode().strip()
            (migrations / '0002_next.up.sql').write_text('SELECT 2;\n', encoding='utf-8')
            self.assertEqual(check_migrations(root, base), [])
            original.write_text('SELECT 3;\n', encoding='utf-8')
            self.assertEqual(len(check_migrations(root, base)), 1)
            git('add', '.')
            git('commit', '-m', 'synthetic forbidden edit')
            self.assertEqual(len(check_migrations(root, base)), 1)
            original.unlink()
            self.assertEqual(len(check_migrations(root, base)), 1)


if __name__ == '__main__':
    unittest.main()
