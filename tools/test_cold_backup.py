import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from cold_backup import MANIFEST, create, restore, verify


class ColdBackupTests(unittest.TestCase):
    def test_copy_failure_leaves_no_complete_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / 'source'
            source.mkdir()
            original = source / 'original'
            original.write_bytes(b'synthetic-original')
            target = root / 'partial'
            with patch('cold_backup.shutil.copy2', side_effect=OSError('synthetic storage failure')):
                with self.assertRaises(OSError):
                    create([source], target, True)
            self.assertFalse((target / MANIFEST).exists())
            self.assertEqual(original.read_bytes(), b'synthetic-original')

    def test_complete_copy_restore_and_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            data, media = root / 'data', root / 'media'
            data.mkdir()
            media.mkdir()
            (data / 'synthetic.db-wal').write_bytes(b'synthetic-wal')
            (data / 'media-secret').write_bytes(b'synthetic-secret-not-a-real-key')
            (media / 'example.jpg').write_bytes(b'synthetic-original')
            backup, restored = root / 'backup', root / 'restored'
            with self.assertRaises(ValueError):
                create([data, media], backup, False)
            self.assertFalse(backup.exists())
            result = create([data, media], backup, True)
            self.assertEqual(len(result['files']), 3)
            self.assertNotIn(str(data), (backup / MANIFEST).read_text(encoding='utf-8'))
            restore(backup, restored)
            self.assertEqual((restored / 'root-2/example.jpg').read_bytes(), b'synthetic-original')
            self.assertEqual((restored / 'root-1/media-secret').read_bytes(), b'synthetic-secret-not-a-real-key')
            with self.assertRaises(ValueError):
                restore(backup, restored)
            (backup / 'unexpected').write_bytes(b'extra')
            with self.assertRaises(ValueError):
                verify(backup)
            (backup / 'unexpected').unlink()
            (backup / 'root-2/example.jpg').write_bytes(b'corrupt')
            with self.assertRaises(ValueError):
                restore(backup, root / 'must-not-exist')
            self.assertFalse((root / 'must-not-exist').exists())

    def test_paths_and_incomplete_copy(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / 'source'
            source.mkdir()
            (source / 'example').write_bytes(b'example')
            with self.assertRaises(ValueError):
                create([source], source / 'nested', True)
            with self.assertRaises(ValueError):
                create([source, source], root / 'duplicate', True)
            backup = root / 'backup'
            create([source], backup, True)
            manifest = json.loads((backup / MANIFEST).read_text(encoding='utf-8'))
            for malicious in ['../escape', '/absolute', 'C:/escape', 'root-1/../escape', 'root-1\\escape', 'root-1/.. /escape', 'root-1//escape']:
                manifest['files'][0]['path'] = malicious
                (backup / MANIFEST).write_text(json.dumps(manifest), encoding='utf-8')
                with self.assertRaises(ValueError):
                    verify(backup)
            (backup / MANIFEST).unlink()
            with self.assertRaises(FileNotFoundError):
                verify(backup)


if __name__ == '__main__':
    unittest.main()
