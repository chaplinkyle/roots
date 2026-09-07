#!/usr/bin/env python3
"""Start a persistent, loopback-only preview of the actual Linux deployment image."""
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.parse
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
names={'app':'roots-kanban-preview','db':'roots-kanban-preview-db','network':'roots-kanban-preview','volume':'roots-kanban-preview-data'}
label='com.chaplin.roots.preview=kanban'
def run(command,**kwargs):
    result=subprocess.run(command,cwd=ROOT,text=True,capture_output=True,creationflags=flags,
                          timeout=kwargs.pop('timeout',60),**kwargs)
    if result.returncode: raise RuntimeError((result.stderr or result.stdout)[-2500:])
    return result.stdout.strip()
for name in (names['app'],names['db']):
    found=run(['docker','ps','-a','--filter','name=^/'+name+'$','--format','{{.Names}}'])
    if found: raise SystemExit('Preview container already exists: '+name+'. Reuse it or stop it explicitly; this script does not replace existing data.')
for kind,name in [('network',names['network']),('volume',names['volume'])]:
    found=subprocess.run(['docker',kind,'inspect',name],capture_output=True,creationflags=flags)
    if found.returncode==0: raise SystemExit('Preview '+kind+' already exists. Inspect it before creating another preview.')
run(['docker','build','--platform','linux/amd64','-f','deploy/kanban/Dockerfile','-t','roots-kanban:local','.'],timeout=600)
database_password,admin_password=secrets.token_urlsafe(32),secrets.token_urlsafe(30)
env=dict(os.environ,POSTGRES_PASSWORD=database_password,KANBAN_JDBC_PASSWORD=database_password,
         KANBAN_ADMIN_PASSWORD=admin_password)
run(['docker','network','create','--label',label,names['network']])
run(['docker','volume','create','--label',label,names['volume']])
created=[]
try:
    run(['docker','run','-d','--name',names['db'],'--label',label,'--network',names['network'],
         '--mount','type=volume,source='+names['volume']+',target=/var/lib/postgresql/data',
         '-e','POSTGRES_USER=roots_kanban','-e','POSTGRES_DB=roots_kanban','-e','POSTGRES_PASSWORD','postgres:16.13'],env=env,timeout=300)
    created.append(names['db'])
    for _ in range(120):
        probe=subprocess.run(['docker','exec',names['db'],'pg_isready','-U','roots_kanban','-d','roots_kanban'],capture_output=True,creationflags=flags)
        if probe.returncode==0: break
        time.sleep(.25)
    else: raise RuntimeError('PostgreSQL readiness timed out')
    options=['--network',names['network'],'--read-only','--tmpfs','/tmp:rw,nosuid,noexec,size=64m,mode=1777',
             '-e','KANBAN_JDBC_URL=jdbc:postgresql://'+names['db']+':5432/roots_kanban',
             '-e','KANBAN_JDBC_USER=roots_kanban','-e','KANBAN_JDBC_PASSWORD','-e','KANBAN_ADMIN_PASSWORD',
             '-e','KANBAN_AUTH=local','-e','KANBAN_SECURE_COOKIES=false']
    run(['docker','run','--rm',*options,'roots-kanban:local','--migrate'],env=env,timeout=90)
    run(['docker','run','-d','--name',names['app'],'--label',label,'-p','127.0.0.1:8090:8080',*options,'roots-kanban:local'],env=env)
    created.append(names['app'])
    base='http://127.0.0.1:8090'
    for _ in range(120):
        try:
            with urllib.request.urlopen(base+'/actuator/health/readiness',timeout=2) as response:
                if response.status==200: break
        except OSError: pass
        time.sleep(.5)
    else: raise RuntimeError('Container readiness timed out; inspect docker logs roots-kanban-preview')
    client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    login=client.open(base+'/login').read().decode()
    csrf=re.search(r'name="_csrf" value="([^"]+)"',login)[1]
    result=client.open(urllib.request.Request(base+'/login',urllib.parse.urlencode(
        {'username':'admin','password':admin_password,'_csrf':csrf}).encode(),{'Origin':base})).read().decode()
    assert 'Engineering board' in result
    scratch=ROOT/'.tooling/kanban-preview'
    scratch.mkdir(parents=True,exist_ok=True)
    (scratch/'login.txt').write_text('URL: '+base+'/app/\nUsername: admin\nPassword: '+admin_password+'\n',encoding='utf-8')
    # Container health and non-root/readonly settings mirror the AWS task.
    state=json.loads(run(['docker','inspect',names['app']]))[0]
    assert state['HostConfig']['ReadonlyRootfs'] and state['Config']['User']=='10001:10001'
    print('Preview running: '+base+'/app/')
    print('Credentials: '+str(scratch/'login.txt'))
    print('Linux container, read-only filesystem, PostgreSQL migration/readiness, and sign-in passed.')
except Exception:
    for name in reversed(created): run(['docker','rm','-f',name])
    # Retain the named database volume for inspection/recovery; never destroy it in a failure handler.
    raise
