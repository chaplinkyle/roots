#!/usr/bin/env python3
"""Verify the packaged automation app against an isolated disposable PostgreSQL container."""
from pathlib import Path
import argparse
import tempfile
import base64
from contextlib import closing
import http.client
import http.server
import json
import os
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', default=str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if 'JAVA_HOME' in os.environ else 'java')
parser.add_argument('--jar', type=Path, default=ROOT / 'examples/automation/target/roots-automation-example-0.1.0-SNAPSHOT-app.jar')
parser.add_argument('--postgres-image', default='postgres:16.14')
args = parser.parse_args()
JAVA = args.java
JAR = args.jar.resolve()
if not JAR.is_file():
    parser.error('Build the automation app JAR before running this check')
SCHEMA = 'automation_smoke_' + uuid.uuid4().hex
assert re.fullmatch(r'automation_smoke_[a-f0-9]{32}', SCHEMA)
CONTAINER = 'roots-automation-verify-' + uuid.uuid4().hex[:12]
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
scratch = tempfile.TemporaryDirectory(prefix='roots-automation-verify-')
password = secrets.token_urlsafe(32)
env_file = Path(scratch.name) / 'postgres.env'
env_file.write_text('POSTGRES_DB=roots_receipts_test\nPOSTGRES_USER=roots_test\nPOSTGRES_PASSWORD=' + password + '\n', encoding='utf-8')
env = dict({key: value for key, value in os.environ.items() if not key.startswith('ROOTS_')},
           ROOTS_HOST='127.0.0.1', AUTOMATION_JDBC_USER='roots_test', AUTOMATION_JDBC_PASSWORD=password,
           AUTOMATION_TOKEN=secrets.token_urlsafe(32), AUTOMATION_WEBHOOK_SECRET='whsec_' + base64.b64encode(secrets.token_bytes(32)).decode())
container_id = None
process = None
output = None
proxy = None
created = False


def sql(statement):
    result = subprocess.run(['docker', 'exec', CONTAINER, 'psql', '-U', 'roots_test', '-d', 'roots_receipts_test', '-At', '-v', 'ON_ERROR_STOP=1', '-c', statement],
                            capture_output=True, text=True, check=True, creationflags=flags)
    return result.stdout.strip()


def run_jar(argument):
    result = subprocess.run([str(JAVA), '-jar', str(JAR), argument], env=env, cwd=ROOT, capture_output=True, text=True, creationflags=flags, timeout=30)
    assert result.returncode == 0, result.stderr[-2000:]
    return result.stdout.strip()


def start(number):
    global process, output
    log = Path(scratch.name) / ('server-' + str(number) + '.log')
    output = log.open('w', encoding='utf-8')
    process = subprocess.Popen([str(JAVA), '-jar', str(JAR), '--port=0'], env=env, cwd=ROOT,
                               stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
    for _ in range(200):
        text = log.read_text(encoding='utf-8')
        match = re.search(r'Roots running at (http://[^\s]+)', text)
        if match:
            return match.group(1)
        assert process.poll() is None, text[-2000:]
        time.sleep(.05)
    raise AssertionError('Packaged application startup timed out')


def stop():
    global process, output
    if process is not None:
        process.terminate()  # Deliberate abrupt process loss, not a graceful-drain test.
        process.wait(timeout=15)
        process = None
    if output is not None:
        output.close()
        output = None


def submit(base, identity, webhook=False):
    command = [sys.executable, 'examples/automation/client.py', 'reindex', '--url', base, '--id', identity]
    if webhook:
        command.append('--webhook')
    result = subprocess.run(command, env=env, cwd=ROOT, capture_output=True, text=True, creationflags=flags, timeout=35)
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


try:
    started = subprocess.run(['docker', 'run', '-d', '--name', CONTAINER, '--label', 'roots.verification=automation-packaged',
                              '--env-file', str(env_file), '-p', '127.0.0.1::5432', args.postgres_image],
                             capture_output=True, text=True, creationflags=flags, check=True)
    container_id = started.stdout.strip()
    assert re.fullmatch(r'[a-f0-9]{64}', container_id)
    inspect = json.loads(subprocess.run(['docker', 'inspect', container_id], capture_output=True, text=True,
                                        creationflags=flags, check=True).stdout)[0]
    port = inspect['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort']
    for _ in range(120):
        ready = subprocess.run(['docker', 'exec', container_id, 'pg_isready', '-h', '127.0.0.1', '-U', 'roots_test', '-d', 'roots_receipts_test'],
                               capture_output=True, creationflags=flags)
        if ready.returncode == 0:
            break
        time.sleep(.25)
    else:
        raise AssertionError('Disposable PostgreSQL did not become ready')
    env['AUTOMATION_JDBC_URL'] = ('jdbc:postgresql://127.0.0.1:' + port + '/roots_receipts_test?currentSchema=' + SCHEMA
                                + '&connectTimeout=5&socketTimeout=15&tcpKeepAlive=true')
    sql('CREATE SCHEMA ' + SCHEMA)
    created = True
    assert 'Created' in run_jar('--migrate')
    base = start(1)
    first_id = str(uuid.uuid4())
    assert submit(base, first_id)['replayed'] == 'false'
    stop()
    base = start(2)
    assert submit(base, first_id)['replayed'] == 'true'
    print('PASS: packaged PostgreSQL receipt replay after abrupt JVM restart', flush=True)

    target = urllib.parse.urlsplit(base)

    class LostResponse(http.server.BaseHTTPRequestHandler):
        count = 0

        def log_message(self, *args):
            pass

        def do_POST(self):
            size = int(self.headers['Content-Length'])
            assert size <= 16384
            body = self.rfile.read(size)
            with closing(http.client.HTTPConnection(target.hostname, target.port, timeout=15)) as upstream:
                upstream.request('POST', self.path, body, {key: self.headers[key] for key in ('Authorization', 'Idempotency-Key', 'Content-Type')})
                response = upstream.getresponse()
                payload = response.read()
                replayed = response.getheader('Idempotency-Replayed')
                type(self).count += 1
                if self.count == 1:
                    assert response.status == 201 and replayed == 'false'
                    status = 502
                    payload = b'{"error":"Simulated lost upstream response"}'
                else:
                    status = response.status
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload)))
                if replayed:
                    self.send_header('Idempotency-Replayed', replayed)
                self.end_headers()
                self.wfile.write(payload)

    proxy = http.server.ThreadingHTTPServer(('127.0.0.1', 0), LostResponse)
    threading.Thread(target=proxy.serve_forever, daemon=True).start()
    second_id = str(uuid.uuid4())
    assert submit('http://127.0.0.1:' + str(proxy.server_port), second_id)['replayed'] == 'true'
    assert LostResponse.count == 2
    assert sql('SELECT count(*) FROM ' + SCHEMA + '.automation_commands') == '2'
    print('PASS: client retries a proxy 502 after commit without a duplicate insert', flush=True)

    third_id = str(uuid.uuid4())
    assert submit(base, third_id, webhook=True)['replayed'] == 'false'
    assert submit(base, third_id, webhook=True)['replayed'] == 'true'
    assert sql('SELECT count(*) FROM ' + SCHEMA + '.automation_commands') == '3'
    print('PASS: signed webhook CLI and durable duplicate-delivery receipt', flush=True)
    sql('UPDATE ' + SCHEMA + '.roots_operation_receipts SET expires_at_epoch_ms=0')
    assert 'Removed 3' in run_jar('--prune-receipts')
    result = submit(base, first_id)
    assert result['status'] == 200 and result['replayed'] == 'false'
    assert sql('SELECT count(*) FROM ' + SCHEMA + '.automation_commands') == '3'
    print('PASS: bounded receipt cleanup retains permanent command deduplication', flush=True)
finally:
    try:
        stop()
        if proxy is not None:
            proxy.shutdown()
            proxy.server_close()
    finally:
        try:
            if container_id is not None:
                # Only this run's newly created container and its anonymous volume.
                subprocess.run(['docker', 'rm', '-f', '-v', container_id], capture_output=True, creationflags=flags, check=True)
        finally:
            scratch.cleanup()
