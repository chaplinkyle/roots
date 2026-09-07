#!/usr/bin/env python3
"""Verify customer transactions and the executable Boot app in an owned PostgreSQL container."""
import argparse
import html
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--maven', default=str(ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')))
parser.add_argument('--java', default=str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if 'JAVA_HOME' in os.environ else 'java')
parser.add_argument('--jar', type=Path, default=ROOT / 'examples/workflow/target/roots-workflow-example-0.1.0-SNAPSHOT-app.jar')
parser.add_argument('--postgres-image', default='postgres:16.14')
args = parser.parse_args()
if Path(args.maven).is_file():
    args.maven = str(Path(args.maven).resolve())
if Path(args.java).is_file():
    args.java = str(Path(args.java).resolve())
jar = args.jar.resolve()
if not jar.is_file():
    parser.error('Build the workflow executable JAR before running this verifier')
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
container = 'roots-workflow-verify-' + uuid.uuid4().hex[:12]
schema = 'workflow_smoke_' + uuid.uuid4().hex
password = secrets.token_urlsafe(32)
local_password = secrets.token_urlsafe(24)
process = output = container_id = None


def run(command, **kwargs):
    return subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=True,
                          creationflags=flags, timeout=kwargs.pop('timeout', 60), **kwargs)


def sql(statement):
    return run(['docker', 'exec', container_id, 'psql', '-U', 'roots_test', '-d', 'roots_receipts_test',
                '-At', '-v', 'ON_ERROR_STOP=1', '-c', statement]).stdout.strip()


def terminate():
    global process, output
    if process is not None:
        if process.poll() is None:
            process.kill()  # Deliberately abrupt process loss; this verifier owns this JVM.
        process.wait(timeout=15)
        process = None
    if output is not None:
        output.close()
        output = None


def start(number, scratch, env):
    global process, output
    path = Path(scratch) / ('server-' + str(number) + '.log')
    output = path.open('w', encoding='utf-8')
    process = subprocess.Popen([args.java, '-jar', str(jar), '--server.port=0', '--server.address=127.0.0.1'],
                               cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
    for _ in range(240):
        log = path.read_text(encoding='utf-8', errors='replace')
        if process.poll() is not None:
            raise AssertionError('Packaged application failed: ' + log[-3000:])
        match = re.search(r'Tomcat started on port (\d+)', log)
        if match:
            base = 'http://127.0.0.1:' + match[1]
            try:
                with urllib.request.urlopen(base + '/actuator/health/readiness', timeout=2) as response:
                    if response.status == 200:
                        return base
            except OSError:
                pass
        time.sleep(.1)
    raise AssertionError('Packaged application did not become ready')


def client(base):
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    page = get(opener, base + '/login')
    token = attribute(page, 'name="_csrf"[^>]*value')
    data = urllib.parse.urlencode({'username': 'editor', 'password': local_password, '_csrf': token}).encode()
    with opener.open(urllib.request.Request(base + '/login', data=data, headers={'Origin': base}), timeout=10) as response:
        assert response.url.endswith('/app/customers'), 'Login did not reach the directory'
    return opener


def get(opener, uri):
    with opener.open(uri, timeout=10) as response:
        return response.read().decode()


def attribute(page, pattern):
    match = re.search(pattern + r'="([^"]+)"', page)
    assert match, 'Missing expected HTML attribute: ' + pattern
    return html.unescape(match[1])


def action(opener, base, page, event, fields):
    values = dict(fields, _view=attribute(page, 'data-roots-view'), _csrf=attribute(page, 'data-roots-csrf'),
                  _protocol=attribute(page, 'data-roots-protocol'),
                  _action=attribute(page, 'data-roots-on-' + event), _event=event)
    request = urllib.request.Request(base + '/app/_roots/action', data=urllib.parse.urlencode(values).encode(),
                                     headers={'Origin': base, 'Content-Type': 'application/x-www-form-urlencoded'})
    with opener.open(request, timeout=10) as response:
        return json.load(response)


try:
    with tempfile.TemporaryDirectory(prefix='roots-workflow-verify-') as scratch:
        try:
            envfile = Path(scratch) / 'postgres.env'
            envfile.write_text('POSTGRES_DB=roots_receipts_test\nPOSTGRES_USER=roots_test\nPOSTGRES_PASSWORD=' + password + '\n', encoding='utf-8')
            container_id = run(['docker', 'run', '-d', '--name', container, '--label', 'roots.verification=workflow',
                                '--env-file', str(envfile), '-p', '127.0.0.1::5432', args.postgres_image]).stdout.strip()
            assert re.fullmatch(r'[a-f0-9]{64}', container_id)
            info = json.loads(run(['docker', 'inspect', container_id]).stdout)[0]
            port = info['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort']
            for _ in range(150):
                ready = subprocess.run(['docker', 'exec', container_id, 'pg_isready', '-h', '127.0.0.1', '-U', 'roots_test', '-d', 'roots_receipts_test'],
                                       capture_output=True, creationflags=flags, timeout=5)
                if ready.returncode == 0:
                    break
                time.sleep(.2)
            else:
                raise AssertionError('Owned PostgreSQL did not start')
            target = f'jdbc:postgresql://127.0.0.1:{port}/roots_receipts_test?connectTimeout=5&socketTimeout=15'
            env = {k: v for k, v in os.environ.items() if not k.startswith(('ROOTS_', 'WORKFLOW_', 'SPRING_'))}
            env.update(ROOTS_TEST_POSTGRES_URL=target, ROOTS_TEST_POSTGRES_USER='roots_test', ROOTS_TEST_POSTGRES_PASSWORD=password)
            checked = run([args.maven, '--batch-mode', '--no-transfer-progress', '-pl', 'examples/workflow', '-am', 'test',
                           '-Dtest=CustomerPostgresTest', '-Dsurefire.failIfNoSpecifiedTests=false'], env=env, timeout=300)
            assert 'Tests run: 5, Failures: 0, Errors: 0, Skipped: 0' in checked.stdout, 'PostgreSQL workflow contracts did not execute'
            print('PASS: PostgreSQL concurrency, ownership, rollback, validation, reopen and pagination contracts')
            assert re.fullmatch(r'workflow_smoke_[a-f0-9]{32}', schema)
            sql('CREATE SCHEMA ' + schema)
            env.update(WORKFLOW_JDBC_URL=target + '&currentSchema=' + schema, WORKFLOW_JDBC_USER='roots_test',
                       WORKFLOW_JDBC_PASSWORD=password, WORKFLOW_AUTH='local', WORKFLOW_SECURE_COOKIES='false',
                       WORKFLOW_EDITOR_PASSWORD=local_password, WORKFLOW_VIEWER_PASSWORD=secrets.token_urlsafe(24))
            run([args.java, '-jar', str(jar), '--migrate'], env=env)
            base = start(1, scratch, env)
            opener = client(base)
            page = get(opener, base + '/app/customers')
            result = action(opener, base, page, 'click', {})
            draft_id = str(uuid.UUID(result['redirect'].rsplit('/', 1)[-1]))
            draft_path = '/app/drafts/' + draft_id
            page = get(opener, base + draft_path)
            action(opener, base, page, 'input', {'company': 'Restart-safe customer', 'contact': '', 'email': ''})
            terminate()
            base = start(2, scratch, env)
            opener = client(base)
            page = get(opener, base + draft_path)
            assert 'value="Restart-safe customer"' in page
            assert 'Private until saved' in page
            print('PASS: packaged login and partial private draft recovered after abrupt JVM loss')
            action(opener, base, page, 'submit', {'company': 'Restart-safe customer', 'contact': 'Mina Patel', 'email': 'mina@example.com'})
            # Discard the completion result and lose the process. The original draft remains the recovery key.
            terminate()
            base = start(3, scratch, env)
            opener = client(base)
            page = get(opener, base + draft_path)
            assert 'Customer saved' in page
            assert sql(f'SELECT COUNT(*) FROM {schema}.workflow_customers') == '1'
            assert sql(f'SELECT COUNT(*) FROM {schema}.workflow_audit') == '1'
            assert 'Customer operations' in get(opener, base + '/app/customers')
            assert 'color-scheme' in get(opener, base + '/app/workflow.css')
            print('PASS: completed draft recovers one customer and one audit entry after process loss')
            terminate()
        finally:
            terminate()

except subprocess.CalledProcessError as failure:
    print((failure.stdout or '')[-3000:] + (failure.stderr or '')[-2000:])
    raise
finally:
    terminate()
    if container_id:
        run(['docker', 'rm', '-f', '-v', container_id])
