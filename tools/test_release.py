"""Integrity regressions for the local release inspector; no Maven/network calls."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import release


class ReleaseIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='roots-release-integrity-')
        self.addCleanup(self.directory.cleanup)
        self.bundle = Path(self.directory.name)
        self.artifact = self.bundle / 'repository/com/chaplin/roots/example/1.0.0/example-1.0.0.pom'
        self.artifact.parent.mkdir(parents=True)
        self.artifact.write_bytes(b'<project/>')
        release.checksums(self.artifact)
        self.source = self.bundle / 'source.zip'
        release.archive(self.source, [('README.md', b'Example', 0o644)])
        def item(path):
            data = path.read_bytes()
            return {'path': path.relative_to(self.bundle).as_posix(), 'bytes': len(data), 'sha256': release.digest(data)}
        self.manifest = {'format': 1, 'publication': 'unpublished', 'version': '1.0.0',
                         'artifacts': [item(self.artifact)], 'sourceArchive': item(self.source)}
        self.write_manifest()

    def write_manifest(self):
        target = self.bundle / 'release-manifest.json'
        target.write_text(json.dumps(self.manifest), encoding='utf-8')
        sums = {a['path']: a['sha256'] for a in self.manifest['artifacts'] + [self.manifest['sourceArchive']]}
        sums['release-manifest.json'] = release.digest(target.read_bytes())
        (self.bundle / 'SHA256SUMS').write_text(''.join(f'{value}  {name}\n' for name, value in sorted(sums.items())), encoding='utf-8')

    def test_valid_bundle(self):
        with contextlib.redirect_stdout(io.StringIO()):
            release.verify(self.bundle)

    def test_artifact_corruption(self):
        self.artifact.write_bytes(b'<broken/>')
        with self.assertRaisesRegex(ValueError, 'Artifact integrity failure'):
            release.verify(self.bundle)

    def test_checksum_corruption(self):
        self.artifact.with_name(self.artifact.name + '.sha1').write_text('0' * 40)
        with self.assertRaisesRegex(ValueError, 'Checksum sidecar'):
            release.verify(self.bundle)

    def test_unlisted_repository_file(self):
        (self.artifact.parent / 'unlisted.jar').write_bytes(b'unreviewed')
        with self.assertRaisesRegex(ValueError, 'unlisted files'):
            release.verify(self.bundle)

    def test_artifact_path_escape(self):
        self.manifest['artifacts'][0]['path'] = '../outside.jar'
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'escapes the bundle'):
            release.verify(self.bundle)

    def test_manifest_inventory_corruption(self):
        (self.bundle / 'SHA256SUMS').write_text('')
        with self.assertRaisesRegex(ValueError, 'SHA256SUMS'):
            release.verify(self.bundle)


if __name__ == '__main__':
    unittest.main()
