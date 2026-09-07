#!/usr/bin/env python3
"""Run browser/transaction contracts on owned PostgreSQL, then restart the packaged application."""
import argparse
import contextlib
import html.parser
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
parser.add_argument('--maven', required=True)
parser.add_argument('--java', required=True)
args = parser.parse_args()
args.maven = str(Path(args.maven).resolve())
args.java = str(Path(args.java).resolve())
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
container = 'roots-kanban-verify-' + uuid.uuid4().hex[:12]
password, admin = secrets.token_urlsafe(32), secrets.token_urlsafe(24)
process = output = container_id = None

def run(command, **kwargs):
    return subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=True,
                          creationflags=flags, timeout=kwargs.pop('timeout', 60), **kwargs)

def stop():
    global process, output
    if process:
        if process.poll() is None:
            process.kill()  # Deliberately abrupt loss of this verifier's own application process.
        process.wait(timeout=15)
        process = None
    if output:
        output.close()
        output = None

def start(scratch, env, number):
    global process, output
    path = Path(scratch) / ('server-' + str(number) + '.log')
    output = path.open('w', encoding='utf-8')
    jar = ROOT / 'examples/kanban/target/roots-kanban-example-0.1.0-SNAPSHOT-app.jar'
    process = subprocess.Popen([args.java, '-jar', str(jar), '--server.port=0'], cwd=ROOT,
                               env=env, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
    for _ in range(180):
        log = path.read_text(encoding='utf-8', errors='replace')
        if process.poll() is not None:
            raise AssertionError('App startup failed: ' + log[-2000:])
        match = re.search(r'Tomcat started on port (\d+)', log)
        if match:
            base = 'http://127.0.0.1:' + match[1]
            try:
                with urllib.request.urlopen(base + '/actuator/health/readiness', timeout=2) as r:
                    if r.status == 200:
                        return base
            except OSError:
                pass
        time.sleep(.25)
    raise AssertionError('Readiness timed out')

class Forms(html.parser.HTMLParser):
    def __init__(self, body):
        super().__init__()
        self.root = {}
        self.forms = []
        self.inputs = {}
        self.feed(body)
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if attrs.get('id') == 'roots': self.root = attrs
        if tag == 'form': self.forms.append(attrs)
        if tag == 'input' and 'name' in attrs: self.inputs[attrs['name']] = attrs.get('value', '')

def login(base):
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    page = Forms(client.open(base + '/login').read().decode())
    body = urllib.parse.urlencode({'username':'admin', 'password':admin, '_csrf':page.inputs['_csrf']}).encode()
    client.open(urllib.request.Request(base + '/login', body, {'Origin':base})).read()
    return client

try:
    run(['docker','pull','postgres:16.13'],timeout=300)
    container_id = run(['docker','run','-d','--name',container,'--label','roots.verification=kanban',
                        '-e','POSTGRES_USER=roots_test','-e','POSTGRES_DB=roots_kanban_test',
                        '-e','POSTGRES_PASSWORD','-p','127.0.0.1::5432','postgres:16.13'],
                       env=dict(os.environ,POSTGRES_PASSWORD=password)).stdout.strip()
    for _ in range(120):
        probe = subprocess.run(['docker','exec',container_id,'pg_isready','-U','roots_test','-d','roots_kanban_test'],
                               capture_output=True,creationflags=flags)
        if probe.returncode == 0: break
        time.sleep(.25)
    else: raise AssertionError('PostgreSQL did not become ready')
    mapping = json.loads(run(['docker','inspect',container_id]).stdout)[0]['NetworkSettings']['Ports']['5432/tcp'][0]
    url = 'jdbc:postgresql://127.0.0.1:' + mapping['HostPort'] + '/roots_kanban_test'
    env = dict(os.environ, KANBAN_TEST_POSTGRES_URL=url, KANBAN_TEST_POSTGRES_USER='roots_test',
               KANBAN_TEST_POSTGRES_PASSWORD=password, KANBAN_JDBC_URL=url, KANBAN_JDBC_USER='roots_test',
               KANBAN_JDBC_PASSWORD=password, KANBAN_ADMIN_PASSWORD=admin, KANBAN_AUTH='local',
               KANBAN_SECURE_COOKIES='false', KANBAN_BIND_ADDRESS='127.0.0.1')
    result = run([args.maven,'-pl','examples/kanban','-am','package','-Dtest=KanbanTest',
                  '-Dsurefire.failIfNoSpecifiedTests=false','-Dmaven.javadoc.skip=true'],env=env,timeout=300)
    print('PostgreSQL repository, concurrency, and browser contracts passed.', flush=True)
    jar = ROOT / 'examples/kanban/target/roots-kanban-example-0.1.0-SNAPSHOT-app.jar'
    run([args.java,'-jar',str(jar),'--migrate'],env=env,timeout=90)
    with tempfile.TemporaryDirectory(prefix='roots-kanban-') as scratch, contextlib.ExitStack() as lifecycle:
        lifecycle.callback(stop)
        base = start(scratch,env,1)
        client = login(base)
        form = Forms(client.open(base + '/app/?new=1').read().decode())
        action = next(f['data-roots-on-submit'] for f in form.forms if f.get('class') == 'task-form')
        protocol = form.root.get('data-roots-protocol')
        if not protocol:
            source=(ROOT/'roots-core/src/main/java/com/chaplin/roots/Roots.java').read_text()
            protocol=re.search(r'PROTOCOL_VERSION\s*=\s*"([^"]+)"', source)[1]
        values={'_view':form.root['data-roots-view'],'_csrf':form.root['data-roots-csrf'],
                '_action':action,'_event':'submit','_protocol':protocol,
                'title':'Survives an application restart','description':'界'*8000,
                'status':'READY','priority':'HIGH','assignee':'Kyle','due':'2026-10-12','version':'0'}
        response = client.open(urllib.request.Request(base + '/app/_roots/action',
                    urllib.parse.urlencode(values).encode(),{'Origin':base,'Content-Type':'application/x-www-form-urlencoded'}))
        assert response.status == 200
        response.read()
        assert 'Survives an application restart' in client.open(base + '/app/').read().decode()
        stop()
        base = start(scratch,env,2)
        client = login(base)
        assert 'Survives an application restart' in client.open(base + '/app/').read().decode()
        print('Packaged PostgreSQL app: authenticated create, abrupt restart, and durable recovery passed.',flush=True)
except subprocess.CalledProcessError as failure:
    # Commands never print environment or generated passwords. Maven logs can include JDBC URLs, but no secrets.
    print((failure.stdout or '')[-9000:])
    print((failure.stderr or '')[-2000:])
    raise SystemExit(failure.returncode)
except subprocess.TimeoutExpired:
    raise SystemExit('A verification command timed out; inspect Docker and retry. Generated credentials were not logged.')
finally:
    stop()
    if container_id:
        run(['docker','rm','-f','-v',container_id])
