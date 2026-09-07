#!/usr/bin/env python3
"""Run the packaged Servlet counter in an owned Tomcat container and remove it afterward."""
import argparse
import http.cookiejar
import http.client
from html.parser import HTMLParser
import json
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class Bindings(HTMLParser):
    def __init__(self):
        super().__init__()
        self.root = {}
        self.action = None

    def handle_starttag(self, tag, attributes):
        values = dict(attributes)
        if values.get('id') == 'roots':
            self.root = values
        if values.get('data-roots-on-click', '').endswith(':increment'):
            self.action = values['data-roots-on-click']


def docker(*args, check=True):
    return subprocess.run(['docker', *args], check=check, capture_output=True, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='roots-servlet:local')
    args = parser.parse_args()
    name = 'roots-servlet-check-' + uuid.uuid4().hex[:12]
    container = None
    stream = None
    try:
        container = docker('run', '-d', '--name', name, '--label', 'roots.verification=servlet',
                           '--read-only', '--memory', '768m', '--cpus', '1', '--cap-drop', 'ALL',
                           '--security-opt', 'no-new-privileges', '--stop-timeout', '45',
                           '--tmpfs', '/usr/local/tomcat/temp:rw,size=64m,uid=10001,gid=10001,mode=0700',
                           '--tmpfs', '/usr/local/tomcat/work:rw,size=64m,uid=10001,gid=10001,mode=0700',
                           '--tmpfs', '/usr/local/tomcat/logs:rw,size=32m,uid=10001,gid=10001,mode=0700',
                           '-e', 'ROOTS_SECURE_COOKIES=false', '-p', '127.0.0.1::8080', args.image).stdout.strip()
        info = json.loads(docker('inspect', container).stdout)[0]
        port = info['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
        origin = 'http://127.0.0.1:' + port
        base = origin + '/roots'
        for _ in range(150):
            try:
                with urllib.request.urlopen(base + '/_roots/health', timeout=2) as response:
                    if response.status == 200:
                        break
            except (urllib.error.URLError, http.client.HTTPException, TimeoutError):
                time.sleep(.2)
        else:
            logs = docker('logs', container)
            raise RuntimeError('Tomcat WAR did not become ready: ' + (logs.stdout + logs.stderr)[-3000:])
        cookies = http.cookiejar.CookieJar()
        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
        with client.open(base + '/', timeout=5) as response:
            document = response.read().decode('utf-8')
            assert response.headers['Content-Security-Policy']
        binding = Bindings()
        binding.feed(document)
        assert binding.action and 'Application mount: /roots' in document
        assert any(c.name == 'ROOTS_SESSION' and c.path == '/roots' for c in cookies)
        with client.open(base + '/_roots/client.js', timeout=5) as response:
            assert b'data-roots-view' in response.read()
        root = binding.root
        form = {'_view': root['data-roots-view'], '_csrf': root['data-roots-csrf'],
                '_protocol': root['data-roots-protocol'], '_event': 'click', '_action': binding.action}
        revision = int(root['data-roots-revision'])
        for _ in range(3):
            request = urllib.request.Request(base + '/_roots/action', data=urllib.parse.urlencode(form).encode(),
                                            headers={'Origin': origin, 'X-Roots-Request': 'action',
                                                     'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded'})
            with client.open(request, timeout=5) as response:
                patch = json.load(response)
            revision += 1
            assert patch['view'] == form['_view'] and patch['revision'] == revision
        query = urllib.parse.urlencode({'view': form['_view'], 'csrf': form['_csrf'], 'protocol': form['_protocol']})
        stream = client.open(base + '/_roots/stream?' + query, timeout=5)
        assert stream.headers['Content-Type'].startswith('text/event-stream')
        for _ in range(12):
            line = stream.readline().decode()
            if line.startswith('data: '):
                assert json.loads(line[6:])['revision'] == revision
                break
        else:
            raise AssertionError('Initial SSE snapshot was not delivered')
        # Keep SSE open while stopping; cleanup must not wait for the client to disconnect.
        started = time.monotonic()
        docker('stop', '--time', '45', container)
        elapsed = time.monotonic() - started
        state = json.loads(docker('inspect', container).stdout)[0]['State']
        assert not state['OOMKilled'] and state['ExitCode'] != 137 and elapsed < 40
        print(json.dumps({'checks': ['WAR context path', 'CSP', 'session cookie path', 'packaged JavaScript',
                                     'CSRF actions', 'exact revisions', 'SSE', 'shutdown with open SSE'],
                          'shutdownSeconds': round(elapsed, 3), 'exitCode': state['ExitCode']}))
    finally:
        if stream:
            stream.close()
        if container:
            docker('rm', '-f', '-v', container)


if __name__ == '__main__':
    main()
