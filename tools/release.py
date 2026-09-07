#!/usr/bin/env python3
"""Build and inspect an unpublished Roots release bundle. Never commits, tags, signs or uploads."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
TEXT = {'.xml', '.java', '.md', '.properties', '.yaml', '.yml', '.json', '.txt', '.py', '.sh', '.cmd', '.ps1'}
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0


def command(args, cwd=ROOT, log=None, env=None):
    if log:
        with log.open('w', encoding='utf-8') as output:
            result = subprocess.run(args, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT,
                                    creationflags=FLAGS, check=False)
        if result.returncode:
            raise RuntimeError(f'Command failed ({result.returncode}); see {log}')
        return ''
    return subprocess.check_output(args, cwd=cwd, env=env, text=True, encoding='utf-8', creationflags=FLAGS).strip()


def digest(data, algorithm='sha256'):
    return hashlib.new(algorithm, data).hexdigest()


def source_files():
    names = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z'], cwd=ROOT)
    result = []
    for raw in sorted(set(names.split(b'\0'))):
        if not raw:
            continue
        relative = Path(os.fsdecode(raw))
        path = ROOT / relative
        if not path.exists():  # Deleted paths can remain in the index during a worktree evaluation.
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT.resolve()):
            raise ValueError(f'Source must be an ordinary in-repository file: {relative}')
        if not path.is_file() or any(part in {'.git', '.tooling', 'target', '__pycache__'} for part in relative.parts):
            continue
        result.append((relative.as_posix(), path.read_bytes(), path.stat().st_mode & 0o777))
    return result


def tree_digest(files):
    value = hashlib.sha256()
    for name, data, mode in files:
        value.update(name.encode() + b'\0' + str(mode).encode() + b'\0' + digest(data).encode() + b'\n')
    return value.hexdigest()


def archive(path, files):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name, data, mode in sorted(files):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100000 | mode) << 16
            output.writestr(info, data)


def checksums(path):
    data = path.read_bytes()
    for algorithm in ('sha256', 'sha512', 'sha1', 'md5'):
        path.with_name(path.name + '.' + algorithm).write_text(digest(data, algorithm) + '\n', encoding='ascii')


def test_counts(source):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for module in ET.parse(source / 'pom.xml').getroot().findall('m:modules/m:module', NS):
        for report in (source / module.text / 'target/surefire-reports').glob('TEST-*.xml'):
            suite = ET.parse(report).getroot()
            for key in totals:
                totals[key] += int(suite.get(key, '0'))
    if totals['tests'] <= totals['skipped'] or totals['errors'] or totals['failures']:
        raise ValueError(f'Reactor test evidence is absent or failing: {totals}')
    return totals


def collect(source, repository, version, source_id):
    modules = ['.'] + [m.text for m in ET.parse(source / 'pom.xml').getroot().findall('m:modules/m:module', NS)
                       if m.text not in ('roots-load-tests', 'roots-browser-tests') and not m.text.startswith('examples/')]
    artifacts = []
    for module in modules:
        pom = ET.parse(source / module / 'pom.xml').getroot()
        artifact = pom.findtext('m:artifactId', namespaces=NS)
        packaging = pom.findtext('m:packaging', default='jar', namespaces=NS)
        destination = repository / 'com/chaplin/roots' / artifact / version
        destination.mkdir(parents=True, exist_ok=True)
        base = artifact + '-' + version
        paths = [(source / module / 'pom.xml', destination / (base + '.pom'))]
        if packaging != 'pom':
            paths.extend((source / module / 'target' / (base + suffix), destination / (base + suffix))
                         for suffix in ('.jar', '-sources.jar', '-javadoc.jar'))
        for original, target in paths:
            if not original.is_file():
                raise ValueError(f'Missing required release artifact: {original}')
            data = original.read_bytes()
            if target.suffix == '.pom':
                if b'SNAPSHOT' in data:
                    raise ValueError(f'Snapshot dependency in published POM: {original}')
                current = ET.fromstring(data)
                actual = current.findtext('m:version', namespaces=NS) or current.findtext('m:parent/m:version', namespaces=NS)
                if actual != version:
                    raise ValueError(f'Incorrect POM version: {original}')
            elif target.name == base + '.jar' and artifact != 'roots-archetype':
                with zipfile.ZipFile(original) as jar:
                    manifest = jar.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '')
                    if f'Implementation-Version: {version}' not in manifest or f'Build-Source-Id: {source_id}' not in manifest:
                        raise ValueError(f'Missing release provenance in {original}')
                    if any(name.startswith('dev/roots/') for name in jar.namelist()):
                        raise ValueError(f'Obsolete package in {original}')
            target.write_bytes(data)
            checksums(target)
            artifacts.append({'path': target.relative_to(repository.parent).as_posix(), 'bytes': len(data),
                              'sha256': digest(data), 'sha512': digest(data, 'sha512')})
    return artifacts


def verify(bundle):
    manifest = json.loads((bundle / 'release-manifest.json').read_text(encoding='utf-8'))
    if manifest.get('format') != 1 or manifest.get('publication') != 'unpublished':
        raise ValueError('Unknown bundle format or publication state')
    expected_sums = {'release-manifest.json': digest((bundle / 'release-manifest.json').read_bytes())}
    expected_repository = set()
    for item in manifest['artifacts'] + [manifest['sourceArchive']]:
        path = bundle / item['path']
        if path.is_symlink() or not path.resolve().is_relative_to(bundle.resolve()):
            raise ValueError('Artifact escapes the bundle')
        data = path.read_bytes()
        if len(data) != item['bytes'] or digest(data) != item['sha256']:
            raise ValueError(f'Artifact integrity failure: {item["path"]}')
        if 'sha512' in item and digest(data, 'sha512') != item['sha512']:
            raise ValueError(f'Artifact integrity failure: {item["path"]}')
        if item['path'] in expected_sums:
            raise ValueError(f'Duplicate artifact: {item["path"]}')
        expected_sums[item['path']] = item['sha256']
        if item in manifest['artifacts']:
            if not path.resolve().is_relative_to((bundle / 'repository').resolve()):
                raise ValueError('Maven artifact must be inside repository')
            expected_repository.add(item['path'])
            for algorithm in ('sha256', 'sha512', 'sha1', 'md5'):
                sidecar = path.with_name(path.name + '.' + algorithm)
                if sidecar.is_symlink() or sidecar.read_text(encoding='ascii').strip() != digest(data, algorithm):
                    raise ValueError(f'Checksum sidecar integrity failure: {sidecar.name}')
                expected_repository.add(sidecar.relative_to(bundle).as_posix())
    actual_repository = {p.relative_to(bundle).as_posix() for p in (bundle / 'repository').rglob('*') if p.is_file() or p.is_symlink()}
    if actual_repository != expected_repository:
        raise ValueError('Repository contains missing or unlisted files')
    expected_text = ''.join(f'{value}  {name}\n' for name, value in sorted(expected_sums.items()))
    if (bundle / 'SHA256SUMS').read_text(encoding='utf-8') != expected_text:
        raise ValueError('SHA256SUMS does not match manifest and artifacts')
    print(f'Verified {len(manifest["artifacts"])} artifacts for {manifest["version"]}; publication: unpublished')


def build(args):
    if (not re.fullmatch(r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*)?', args.version)
            or len(args.version) > 80 or 'SNAPSHOT' in args.version.upper()):
        raise ValueError('Use a non-snapshot SemVer version, e.g. 0.1.0-rc.1')
    dirty = bool(command(['git', 'status', '--porcelain']))
    commit = command(['git', 'rev-parse', 'HEAD'])
    if dirty and not args.worktree:
        raise ValueError('Commit reviewed changes first, or pass --worktree for an explicitly unpublished evaluation build')
    files = source_files()
    source_id = tree_digest(files)
    output = Path(args.output).resolve()
    # Output must be ignored and must not contain the source workspace.
    if output == ROOT or ROOT.is_relative_to(output) or output.exists():
        raise ValueError('Choose a new output directory outside the source inputs')
    if output.is_relative_to(ROOT) and '.tooling' not in output.relative_to(ROOT).parts:
        raise ValueError('In-workspace release output must be under .tooling')
    output.mkdir(parents=True)
    source = output / 'source'
    previous = ET.parse(ROOT / 'pom.xml').getroot().findtext('m:version', namespaces=NS)
    stamped = []
    for name, data, mode in files:
        if Path(name).suffix in TEXT:
            data = data.replace(previous.encode(), args.version.encode())
        destination = source / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        destination.chmod(mode)
        stamped.append((name, data, mode))
    source_archive = output / f'roots-{args.version}-source.zip'
    archive(source_archive, stamped)
    checksums(source_archive)
    maven = str(Path(args.maven).resolve()) if args.maven else str(source / ('mvnw.cmd' if os.name == 'nt' else 'mvnw'))
    env = {key: value for key, value in os.environ.items() if not key.startswith(('ROOTS_TEST_', 'WORKFLOW_', 'AUTOMATION_'))}
    # Candidate coordinates are installed locally for the archetype's generated-project test.
    common = [maven, '--batch-mode', '--no-transfer-progress', '-Droots.build.source=' + source_id]
    log = output / 'verify.log'
    print(f'Building {args.version} from source {source_id[:12]}; log: {log}', flush=True)
    command(common + ['clean', 'install'], cwd=source, env=env, log=log)
    totals = test_counts(source)
    repository = output / 'repository'
    artifacts = collect(source, repository, args.version, source_id)
    reproducible = None
    if args.rebuild:
        print('Rebuilding the captured source to compare artifact bytes', flush=True)
        command(common + ['clean', 'install', '-DskipTests'], cwd=source, env=env, log=output / 'rebuild.log')
        second = collect(source, output / 'rebuild-repository', args.version, source_id)
        first_hashes = {Path(a['path']).relative_to('repository').as_posix(): a['sha256'] for a in artifacts}
        second_hashes = {Path(a['path']).relative_to('rebuild-repository').as_posix(): a['sha256'] for a in second}
        if first_hashes != second_hashes:
            changed = [key for key in first_hashes if first_hashes[key] != second_hashes.get(key)]
            raise ValueError('Rebuild differs: ' + ', '.join(changed))
        reproducible = 'identical artifact bytes in two local builds of the captured source and toolchain'
    manifest = {'format': 1, 'version': args.version, 'groupId': 'com.chaplin.roots', 'publication': 'unpublished',
                'gitCommit': commit, 'dirtyWorktree': dirty,
                'sourceInputSha256': source_id, 'stampedSourceSha256': tree_digest(stamped),
                'maven': command([maven, '--version'], cwd=source, env=env),
                'tests': totals, 'rebuildComparison': reproducible, 'artifacts': artifacts,
                'sourceArchive': {'path': source_archive.name, 'bytes': source_archive.stat().st_size,
                                  'sha256': digest(source_archive.read_bytes())}}
    (output / 'release-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    sums = [(a['path'], a['sha256']) for a in artifacts]
    sums += [(source_archive.name, manifest['sourceArchive']['sha256']),
             ('release-manifest.json', digest((output / 'release-manifest.json').read_bytes()))]
    (output / 'SHA256SUMS').write_text(''.join(f'{value}  {name}\n' for name, value in sorted(sums)), encoding='utf-8')
    verify(output)
    bundle_files = [(p.relative_to(output).as_posix(), p.read_bytes(), 0o644) for p in repository.rglob('*') if p.is_file()]
    bundle_files += [(name, (output / name).read_bytes(), 0o644) for name in ('release-manifest.json', 'SHA256SUMS', source_archive.name)]
    bundle = output / f'roots-{args.version}-bundle.zip'
    archive(bundle, bundle_files)
    checksums(bundle)
    print(f'Unpublished bundle: {bundle}')


def smoke(args):
    bundle = args.bundle.resolve()
    verify(bundle)
    version = json.loads((bundle / 'release-manifest.json').read_text(encoding='utf-8'))['version']
    output = Path(args.output).resolve()
    if output.exists():
        raise ValueError('Consumer output must be a new directory, including its fresh Maven cache')
    output.mkdir(parents=True)
    maven = str(Path(args.maven).resolve()) if args.maven else str(ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw'))
    env = {key: value for key, value in os.environ.items() if not key.startswith(('ROOTS_', 'WORKFLOW_', 'AUTOMATION_'))}
    common = [maven, '--batch-mode', '--no-transfer-progress', '-s', str(ROOT / 'tools/release-settings.xml'),
              '-Droots.repository=' + (bundle / 'repository').as_uri(), '-Dmaven.repo.local=' + str(output / 'maven-cache')]
    print(f'Generating consumer from {version} with an empty Maven cache; logs: {output}', flush=True)
    command(common + ['org.apache.maven.plugins:maven-archetype-plugin:3.3.1:generate',
                      '-DarchetypeGroupId=com.chaplin.roots', '-DarchetypeArtifactId=roots-archetype',
                      '-DarchetypeVersion=' + version, '-DrootsVersion=' + version,
                      '-DgroupId=com.example', '-DartifactId=orders', '-Dpackage=com.example.orders',
                      '-Dversion=1.0.0-SNAPSHOT', '-DinteractiveMode=false', '-DarchetypeCatalog=internal'],
            cwd=output, env=env, log=output / 'generate.log')
    command(common + ['-f', 'orders/pom.xml', 'verify'], cwd=output, env=env, log=output / 'consumer-build.log')
    # Exercise the generated executable independently of Maven/the source checkout.
    java = str(Path(env['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if 'JAVA_HOME' in env else 'java'
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    with (output / 'application.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen([java, '-jar', str(output / 'orders/target/orders-1.0.0-SNAPSHOT.jar'),
                                    '--host=127.0.0.1', '--port=' + str(port), '--production'],
                                   cwd=output, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
        try:
            base = f'http://127.0.0.1:{port}'
            deadline = time.monotonic() + 30
            while True:
                if process.poll() is not None:
                    raise ValueError('Generated application exited; see application.log')
                try:
                    with urllib.request.urlopen(base + '/_roots/health', timeout=2) as response:
                        if response.status == 200:
                            break
                except (urllib.error.URLError, TimeoutError):
                    if time.monotonic() >= deadline:
                        raise ValueError('Generated application did not become ready')
                    time.sleep(0.1)
            for route, content in [('/', b'Increment in Java'), ('/_roots/client.js', b'data-roots-view'),
                                   ('/about', b'About')]:
                with urllib.request.urlopen(base + route, timeout=5) as response:
                    if response.status != 200 or content not in response.read():
                        raise ValueError(f'Generated executable failed route {route}')
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
    (output / 'consumer-evidence.json').write_text(json.dumps({
        'version': version, 'bundleManifestSha256': digest((bundle / 'release-manifest.json').read_bytes()),
        'checks': ['empty-cache archetype generation', 'generated project verify', 'packaged readiness',
                   'live page', 'packaged browser resource', 'prerendered page']}, indent=2) + '\n', encoding='utf-8')
    print('Consumer verified; the generated application process has been stopped')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prepare = commands.add_parser('build')
    prepare.add_argument('--version', required=True)
    prepare.add_argument('--output', required=True)
    prepare.add_argument('--maven', help='Optional Maven executable; defaults to captured Maven wrapper')
    prepare.add_argument('--worktree', action='store_true', help='Include current uncommitted/untracked non-ignored source and record that provenance')
    prepare.add_argument('--rebuild', action='store_true', help='Compare a second local build of published artifacts')
    inspect = commands.add_parser('verify')
    inspect.add_argument('bundle', type=Path, help='Extracted bundle directory')
    consumer = commands.add_parser('smoke', help='Generate, verify, and run an application from a bundle using a fresh Maven cache')
    consumer.add_argument('bundle', type=Path)
    consumer.add_argument('--output', required=True)
    consumer.add_argument('--maven', help='Optional Maven executable; defaults to repository wrapper')
    args = parser.parse_args()
    try:
        if args.command == 'build':
            build(args)
        elif args.command == 'smoke':
            smoke(args)
        else:
            verify(args.bundle.resolve())
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as failure:
        parser.exit(1, str(failure) + '\n')


if __name__ == '__main__':
    main()
