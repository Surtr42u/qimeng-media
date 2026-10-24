import unittest

from release_manifest import validate_identity


class ReleaseIdentityTests(unittest.TestCase):
    def test_stale_unknown_and_dirty_are_not_releases(self):
        good = {'version': '1.2.3', 'revision': 'synthetic-revision', 'modified': False}
        validate_identity(good, '1.2.3', 'synthetic-revision', False, False)
        for change in [{'version': 'old'}, {'revision': 'unknown'}, {'modified': True}, {'modified': 'false'}]:
            with self.assertRaises(ValueError):
                validate_identity(good | change, '1.2.3', 'synthetic-revision', False, False)
        dirty = good | {'modified': True}
        with self.assertRaises(ValueError):
            validate_identity(dirty, '1.2.3', 'synthetic-revision', True, False)
        validate_identity(dirty, '1.2.3', 'synthetic-revision', True, True)


if __name__ == '__main__':
    unittest.main()
