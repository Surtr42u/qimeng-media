"""仅合成 SQLite/文件属性；CI 执行，不访问开发机日常库。"""
from copy import deepcopy
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from audit_library import audit, media_path, snapshot
from audit_backup import compare_backup


class AuditLibraryTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.media = self.root / 'media'
        self.media.mkdir()
        (self.media / 'synthetic.bin').write_bytes(b'synthetic')
        self.db = self.root / 'synthetic.db'
        conn = sqlite3.connect(self.db)
        conn.executescript('''
            CREATE TABLE libraries(id TEXT PRIMARY KEY, name TEXT, root_path TEXT, enabled INTEGER);
            CREATE TABLE assets(asset_id TEXT PRIMARY KEY, library_id TEXT REFERENCES libraries(id),
                rel_path TEXT, file_name TEXT, size_bytes INTEGER, media_type TEXT);
            CREATE TABLE authors(id TEXT PRIMARY KEY, display_name TEXT, followed INTEGER);
            CREATE TABLE tags(id TEXT PRIMARY KEY, name TEXT);
            CREATE TABLE asset_authors(asset_id TEXT REFERENCES assets(asset_id), author_id TEXT REFERENCES authors(id));
            CREATE TABLE asset_tags(asset_id TEXT REFERENCES assets(asset_id), tag_id TEXT REFERENCES tags(id));
            CREATE TABLE favorites(asset_id TEXT REFERENCES assets(asset_id));
            CREATE TABLE likes(asset_id TEXT REFERENCES assets(asset_id), day TEXT);
            CREATE TABLE timeline_tags(asset_id TEXT REFERENCES assets(asset_id), time_millis INTEGER, name TEXT);
            CREATE TABLE view_events(id INTEGER PRIMARY KEY, asset_id TEXT, kind TEXT, session_id TEXT,
                started_at TEXT, seconds INTEGER, client_event_id TEXT, day TEXT);
            CREATE TABLE portable_event_identity(event_id INTEGER REFERENCES view_events(id), stable_id TEXT);
            CREATE TABLE asset_daily_stats(asset_id TEXT REFERENCES assets(asset_id), day TEXT,
                view_count INTEGER, play_count INTEGER, browse_seconds INTEGER);
            CREATE TABLE kv_settings(key TEXT, value TEXT);
        ''')
        conn.execute('INSERT INTO libraries VALUES(?,?,?,?)', ('l', 'synthetic-library', str(self.media), 1))
        conn.execute("INSERT INTO assets VALUES('a','l','synthetic.bin','synthetic.bin',9,'video')")
        conn.execute("INSERT INTO authors VALUES('author','Synthetic Author',0)")
        conn.execute("INSERT INTO tags VALUES('tag','synthetic-tag')")
        conn.execute("INSERT INTO asset_authors VALUES('a','author')")
        conn.execute("INSERT INTO asset_tags VALUES('a','tag')")
        conn.execute("INSERT INTO view_events VALUES(1,'a','open','synthetic-session','2026-10-22T12:00:00.000Z',NULL,'synthetic-event',NULL)")
        # 本地日界由同一系统时区求得；覆盖 UTC 时间转换而非截取字符串。
        from datetime import datetime
        self.day = datetime.fromisoformat('2026-10-22T12:00:00+00:00').astimezone().date().isoformat()
        conn.execute('INSERT INTO asset_daily_stats VALUES(?,?,?,?,?)', ('a', self.day, 1, 0, 0))
        conn.commit()
        conn.close()
        self.backup = {'format': 'qimeng_backup', 'schemaVersion': 2, 'data': {
            'mediaFiles': [{'assetId': 'a', 'recordKey': 'r', 'fileName': 'synthetic.bin',
                            'sizeBytes': 9, 'mediaType': 'video'}],
            'viewEvents': [{'eventId': 'synthetic-event', 'recordKey': 'r', 'kind': 'open',
                            'sessionId': 'synthetic-session', 'startedAt': '2026-10-22T12:00:00.000Z'}],
            'authors': [{'authorId': 'author', 'displayName': 'Synthetic Author'}],
            'authorMediaRefs': [{'recordKey': 'r', 'authorId': 'author'}],
            'tags': [{'name': 'synthetic-tag'}],
            'mediaTagRefs': [{'recordKey': 'r', 'tagName': 'synthetic-tag'}]}}

    def change(self, sql):
        with sqlite3.connect(self.db) as conn:
            conn.executescript(sql)

    def with_backup(self):
        path = self.root / 'backup.json'
        path.write_text(json.dumps(self.backup), encoding='utf-8')
        return audit(self.db, path)

    def test_full_readonly_audit_and_backup(self):
        before = self.db.read_bytes()
        report = self.with_backup()
        self.assertEqual(report['status'], 'passed-within-scope')
        self.assertEqual(report['files']['sizeMatched'], 1)
        self.assertEqual(report['backup']['sections']['eventsExact']['matched'], 1)
        self.assertEqual(self.db.read_bytes(), before)
        text = json.dumps(report)
        for private in (str(self.media), 'synthetic.bin', 'Synthetic Author', 'synthetic-session'):
            self.assertNotIn(private, text)

    def test_differences_are_reported_without_repair(self):
        (self.media / 'synthetic.bin').write_bytes(b'truncated')
        self.change("UPDATE asset_daily_stats SET view_count=5; DELETE FROM asset_tags; INSERT INTO favorites VALUES('missing');")
        # 大小真的不同；同大小字节变化故意不归快速 stat 校验范围。
        (self.media / 'synthetic.bin').write_bytes(b'short')
        report = self.with_backup()
        self.assertIn('media-size-mismatch', report['issueCounts'])
        self.assertIn('daily-statistics-mismatch', report['issueCounts'])
        self.assertIn('database-foreign-key-violations', report['issueCounts'])
        self.assertEqual(report['backup']['sections']['tagRefs']['differentOrMissing'], 1)
        self.assertEqual((self.media / 'synthetic.bin').read_bytes(), b'short')

    def test_deleted_asset_events_are_history_not_corruption(self):
        self.change("INSERT INTO view_events VALUES(2,'deleted','open','old','2026-10-22T12:00:00.000Z',NULL,NULL,NULL); INSERT INTO portable_event_identity VALUES(2,'portable:synthetic');")
        report = self.with_backup()
        self.assertEqual(report['status'], 'passed-within-scope')
        self.assertEqual(report['events']['historicalNonresidentAssets'], 1)
        self.assertEqual(report['backup']['targetOnlyEvents'], 1)

    def test_event_identity_payload_conflict_detected(self):
        self.backup['data']['viewEvents'][0]['sessionId'] = 'different-source-session'
        report = self.with_backup()
        self.assertEqual(report['backup']['sections']['eventsExact']['differentOrMissing'], 1)

    def test_paths_rejected_before_stat_outside_root(self):
        for relative in ('../outside', '/absolute', 'C:/outside', 'x\\y', 'x:stream', 'x/.. /y'):
            with self.subTest(relative=relative), self.assertRaises(ValueError):
                media_path(self.media, relative)
        self.change("UPDATE assets SET rel_path='../outside';")
        self.assertIn('media-path-unsafe-or-unreadable', audit(self.db)['issueCounts'])

    def test_missing_inputs_not_created_and_v1_not_passed(self):
        absent = self.root / 'absent.db'
        with self.assertRaises(ValueError):
            audit(absent)
        self.assertFalse(absent.exists())
        self.backup['schemaVersion'] = 1
        with self.assertRaises(ValueError):
            self.with_backup()

    def test_cross_library_ambiguity_not_guessed(self):
        data, _, _ = snapshot(self.db)
        second = dict(data['assets'][0], asset_id='b')
        data['assets'].append(second)
        source = deepcopy(self.backup)
        source['data']['mediaFiles'][0].pop('assetId')
        report = compare_backup(data, source)
        self.assertEqual(report['sections']['mediaMappings']['matched'], 0)

    def test_wal_committed_rows_are_included(self):
        conn = sqlite3.connect(self.db)
        self.addCleanup(conn.close)
        conn.execute('PRAGMA journal_mode=WAL')
        conn.execute("INSERT INTO tags VALUES('wal-tag','synthetic-wal-tag')")
        conn.commit()
        self.assertEqual(audit(self.db)['counts']['tags'], 2)

    def test_active_upload_not_mislabeled_as_corruption(self):
        staging = self.root / 'uploads-tmp'
        staging.mkdir()
        (staging / 'synthetic-part').write_bytes(b'partial')
        report = audit(self.db)
        self.assertEqual(report['status'], 'passed-within-scope')
        self.assertEqual(report['staging']['filesPresent'], 1)
        self.assertTrue((staging / 'synthetic-part').exists())


if __name__ == '__main__':
    unittest.main()
