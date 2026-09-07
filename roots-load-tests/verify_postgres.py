#!/usr/bin/env python3
"""Run the business workload against an owned disposable PostgreSQL container."""
import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven', default=str(ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')))
    parser.add_argument('--users', type=int, default=32)
    parser.add_argument('--rows', type=int, default=1000)
    parser.add_argument('--seconds', type=int, default=60)
    parser.add_argument('--pool', type=int, default=8)
    parser.add_argument('--think-ms', type=int, default=10)
    parser.add_argument('--push-ms', type=int, default=1000)
    parser.add_argument('--postgres-image', default='postgres:16.14')
    parser.add_argument('--output', type=Path, required=True, help='New evidence directory; will not overwrite')
    args = parser.parse_args()
    if min(args.users, args.rows, args.seconds, args.pool, args.push_ms) <= 0 or args.think_ms < 0:
        parser.error('Positive counts/durations required; think-ms may be zero')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    maven = str(Path(args.maven).resolve()) if Path(args.maven).is_file() else args.maven
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    name = 'roots-business-' + uuid.uuid4().hex[:12]
    container = None

    def source_hash():
        digest = hashlib.sha256()
        for path in sorted([*ROOT.glob('roots-core/src/**/*.java'), *ROOT.glob('roots-core/src/**/*.js'),
                            *ROOT.glob('roots-load-tests/src/**/*.java')]):
            digest.update(path.relative_to(ROOT).as_posix().encode() + b'\0' + path.read_bytes())
        return digest.hexdigest()

    def run(command, **kwargs):
        return subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=True,
                              timeout=kwargs.pop('timeout', 60), creationflags=flags, **kwargs)

    try:
        with tempfile.TemporaryDirectory(prefix='roots-business-') as scratch:
            password = secrets.token_urlsafe(32)
            envfile = Path(scratch) / 'database.env'
            envfile.write_text('POSTGRES_DB=roots_load\nPOSTGRES_USER=roots_load\nPOSTGRES_PASSWORD=' + password + '\n', encoding='utf-8')
            container = run(['docker', 'run', '-d', '--name', name, '--env-file', str(envfile),
                             '-p', '127.0.0.1::5432', args.postgres_image], timeout=180).stdout.strip()
            for _ in range(120):
                status = subprocess.run(['docker', 'exec', container, 'pg_isready', '-U', 'roots_load'],
                                        capture_output=True, timeout=10, creationflags=flags)
                if status.returncode == 0:
                    break
                time.sleep(.25)
            else:
                raise RuntimeError('PostgreSQL did not become ready')
            port = run(['docker', 'port', container, '5432/tcp']).stdout.strip().rsplit(':', 1)[1]
            env = os.environ.copy()
            env.update(ROOTS_LOAD_JDBC_URL=f'jdbc:postgresql://127.0.0.1:{port}/roots_load',
                       ROOTS_LOAD_DB_USER='roots_load', ROOTS_LOAD_DB_PASSWORD=password)
            recording_name = 'business-' + uuid.uuid4().hex + '.jfr'
            command = [maven, '-pl', 'roots-load-tests', '-am', '-Proots-soak', 'test', '-Dtest=BusinessLoadTest',
                       '-Dsurefire.failIfNoSpecifiedTests=false',
                       f'-Droots.business.users={args.users}', f'-Droots.business.rows={args.rows}',
                       f'-Droots.business.seconds={args.seconds}', f'-Droots.business.pool={args.pool}',
                       f'-Droots.business.thinkMs={args.think_ms}', f'-Droots.business.pushMs={args.push_ms}',
                       '-DargLine=-Xms512m -Xmx512m -XX:StartFlightRecording=filename=target/' + recording_name
                       + ',settings=profile,dumponexit=true,jdk.InitialEnvironmentVariable#enabled=false,jdk.InitialSystemProperty#enabled=false,jdk.SystemProcess#enabled=false']
            before_source = source_hash()
            result = subprocess.run(command, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=args.seconds + 300, creationflags=flags)
            (output / 'maven.log').write_text(result.stdout, encoding='utf-8')
            recording = ROOT / 'roots-load-tests/target' / recording_name
            if recording.is_file():
                shutil.copyfile(recording, output / 'workload.jfr')
                recording.unlink()
            summaries = [line for line in result.stdout.splitlines() if line.startswith(('Roots business:', 'Business '))]
            print('\n'.join(summaries), flush=True)
            after_source = source_hash()
            (output / 'evidence.json').write_text(json.dumps({
                'scenario': {key: value for key, value in vars(args).items() if key not in ('output', 'maven')},
                'java_home': os.environ.get('JAVA_HOME'), 'platform': platform.platform(),
                'logical_cpus': os.cpu_count(), 'source_sha256': before_source,
                'source_unchanged_during_run': before_source == after_source,
                'postgres_image_id': run(['docker', 'inspect', '--format', '{{.Image}}', container]).stdout.strip(),
                'summaries': summaries, 'exit_code': result.returncode,
                'scope': 'Client and server in one JVM; PostgreSQL in local Docker; no proxy or browser'
            }, indent=2) + '\n', encoding='utf-8')
            if result.returncode:
                print(result.stdout[-6000:])
                raise SystemExit(result.returncode)
            if before_source != after_source:
                raise RuntimeError('Source changed during measurement; repeat with stable source')
    finally:
        if container:
            run(['docker', 'rm', '-f', '-v', container])


if __name__ == '__main__':
    main()
