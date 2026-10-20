import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from check_glib_patch import check_patch


class GlibPatchGateTest(unittest.TestCase):
    def make_snapshot(self, root):
        vendor = root / 'desktop/vendor/glib'
        vendor.mkdir(parents=True)
        (vendor / 'source.rs').write_bytes(b'fixed upstream source\n')
        (vendor / 'Cargo.lock').write_text('version = 4\n', encoding='utf-8')
        metadata = {'version': '0.18.5', 'files': {
            'source.rs': hashlib.sha256((vendor / 'source.rs').read_bytes()).hexdigest()}}
        (vendor.parent / 'glib-source.json').write_text(json.dumps(metadata), encoding='utf-8')
        cargo = root / 'desktop/src-tauri'
        cargo.mkdir()
        (cargo / 'Cargo.toml').write_text(
            '[patch.crates-io]\nglib = { path = "../vendor/glib" }\n', encoding='utf-8')
        (cargo / 'Cargo.lock').write_text(
            'version = 4\n[[package]]\nname = "glib"\nversion = "0.18.5"\n', encoding='utf-8')
        return vendor, cargo

    def test_patched_source_and_local_resolution_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.make_snapshot(root)
            self.assertEqual(check_patch(root), [])

    def test_reverted_or_modified_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            vendor, _ = self.make_snapshot(root)
            (vendor / 'source.rs').write_bytes(b'unpatched upstream source\n')
            self.assertTrue(any('内容变化' in error for error in check_patch(root)))

    def test_registry_dependency_cannot_bypass_source_patch(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            _, cargo = self.make_snapshot(root)
            with (cargo / 'Cargo.lock').open('a', encoding='utf-8') as out:
                out.write('source = "registry+https://github.com/rust-lang/crates.io-index"\n')
            self.assertTrue(any('锁文件' in error for error in check_patch(root)))

    def test_untracked_extra_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            vendor, _ = self.make_snapshot(root)
            (vendor / 'extra.rs').write_bytes(b'extra code\n')
            self.assertTrue(any('新增' in error for error in check_patch(root)))
