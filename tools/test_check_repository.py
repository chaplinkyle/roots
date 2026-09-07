import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check_repository


class RepositoryBoundaryTest(unittest.TestCase):
    def test_valid_links_and_ignored_local_material(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            (root / '.gitignore').write_text('.tooling/\n')
            (root / '.tooling').mkdir()
            (root / '.tooling/private.env').write_text('local data')
            (root / 'README.md').write_text('[Contract](contract.md#state-and-lifetime)')
            (root / 'contract.md').write_text('# State and lifetime\nDocumented.')
            with patch.object(check_repository, 'ROOT', root), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, check_repository.main())

    def test_missing_links_and_build_artifacts_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            (root / 'README.md').write_text('[Missing](missing.md)')
            (root / 'application.jar').write_bytes(b'not publishable source')
            output = io.StringIO()
            with patch.object(check_repository, 'ROOT', root), contextlib.redirect_stdout(output):
                self.assertEqual(1, check_repository.main())
            self.assertIn('missing source link', output.getvalue())
            self.assertIn('generated/local/private', output.getvalue())

    def test_possible_secrets_are_rejected_without_logging_values(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            token = 'ghp_' + 'A' * 36
            (root / 'config.txt').write_text(token)
            output = io.StringIO()
            with patch.object(check_repository, 'ROOT', root), contextlib.redirect_stdout(output):
                self.assertEqual(1, check_repository.main())
            self.assertIn('value redacted', output.getvalue())
            self.assertNotIn(token, output.getvalue())


if __name__ == '__main__':
    unittest.main()
