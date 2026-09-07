# On-premises Servlet deployment

The [WAR example](../../examples/servlet/README.md) deploys a small anonymous counter
at `/roots` under Tomcat 11/Servlet 6.1 with Java 25. It demonstrates container
ownership, context paths, session/CSRF handling, async SSE, and graceful shutdown.
For real users and business data, adapt the authenticated
[customer workflow](../../examples/workflow/README.md) or integrate your platform's
identity and persistence services; the counter is deliberately an in-memory probe.

## Build and run the packaged WAR

From the repository root:

```sh
./mvnw -pl examples/servlet -am package
docker build -f deploy/servlet/Dockerfile -t roots-servlet:local .
python deploy/servlet/verify.py
```

The verifier creates only its own labeled container, checks the context path,
cookies, CSP, actions, revisions, packaged browser resource, and SSE, then stops
Tomcat while SSE remains open. It removes that container and its volumes. Docker
is required; it does not contact an existing server or database.

For a local interactive evaluation:

```sh
docker run --name roots-servlet-evaluation --read-only --memory 768m --cpus 1 \
  --cap-drop ALL --security-opt no-new-privileges --stop-timeout 45 \
  --tmpfs /usr/local/tomcat/temp:rw,size=64m,uid=10001,gid=10001,mode=0700 \
  --tmpfs /usr/local/tomcat/work:rw,size=64m,uid=10001,gid=10001,mode=0700 \
  --tmpfs /usr/local/tomcat/logs:rw,size=32m,uid=10001,gid=10001,mode=0700 \
  -e ROOTS_SECURE_COOKIES=false -p 127.0.0.1:8080:8080 roots-servlet:local
```

Open `http://localhost:8080/roots/`. Use `mvnw.cmd` on Windows and PowerShell
backticks or single-line Docker commands. Stop/remove the evaluation with
`docker stop --time 45 roots-servlet-evaluation` then
`docker rm -v roots-servlet-evaluation`. The cookie override is for this loopback
HTTP evaluation; secure cookies are enabled by default for TLS deployments.

## Install on an existing server

Copy the built WAR as `roots.war` to the approved Tomcat webapps directory and
start Tomcat with Java 25+. The WAR packages Roots and omits Servlet API classes.
Use the supplied `server.xml` as a reviewed standalone-server template, not as an
automatic replacement for a shared server's configuration. It has a virtual-thread
HTTP connector, connection/header limits, no remote shutdown port, no AJP listener,
and no automatic WAR hot deployment. Provide writable private `temp`, `work`, and
logs directories owned by the service identity. Pin the Tomcat/JDK image digest
and apply your platform's patching process; the default image tag is a tested
example version, not a promise of current security certification.

Programmatic registration in `Bootstrap` enables async support and applies
`ROOTS_*` limits, cookie settings, proxy policy, and CSP. The Tomcat connector owns
host/port; `ROOTS_HOST` and `ROOTS_PORT` do not reconfigure it. The image's
`ROOTS_PORT` only tells the local health probe where to connect. Every filter in
the request chain must support async dispatch. Do not add a second listener or
call `Roots.run` from a WAR.

Terminate TLS at your corporate ingress and preserve `/roots` in the forwarded
path. Probe `/roots/_roots/health`, enable secure cookies, remove untrusted
forwarding headers, and set `ROOTS_TRUSTED_PROXIES` to actual proxy CIDRs. For an
Nginx single-server frontend, the relevant location is:

```nginx
location /roots/ {
    proxy_pass http://private_tomcat:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header Forwarded "";
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Port $server_port;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_request_buffering off;
    proxy_read_timeout 75s;
    proxy_next_upstream off;
}
```

Place this inside your approved TLS virtual host with the correct certificate.
For multiple Tomcat nodes, use the [HAProxy affinity pattern](../compose/README.md)
with private Tomcat backends and the mounted health path. Give the Roots owner
cookie a stable node mapping. Ordinary round-robin or distributed HTTP sessions
do not replicate a live component graph. SSE needs prompt streaming and idle
timeouts beyond its heartbeat, and proxies must not retry mutations.

## Identity, data, and operations

The default adapter copies `getUserPrincipal()` as a name-only Roots identity.
Install your container security realm/SSO filter or Spring Security first. Supply a
`ServletIdentityResolver` to copy exact allowed roles using `isUserInRole`, and
enforce matching `AuthorizationPolicy` rules on pages and actions. Never derive
identity from unsigned client headers. Roots CSRF protection covers its action
protocol; the platform still protects login/logout and other application forms.
See [integration contracts](../../docs/integrations.md) and the durable workflow
for a concrete login/OIDC, authorization, and database implementation.

Application services own connection pools, transactions, and shutdown. Configure
secrets through the service manager/container platform, run migrations once before
rollout with separate credentials, and budget pool capacity for simultaneous old
and new nodes. Use the [systemd service guidance](../systemd/README.md) for VM
resource/identity/environment handling while retaining Tomcat's own launcher.
Collect container stdout or managed logs, JMX/metrics, heap/GC, connection counts,
live views/SSE, admission rejection, and application pool/transaction health.
Avoid raw query-string access logging because SSE URLs carry view credentials.

Drain/remove the node from ingress before stopping it; give the supervisor at
least 45 seconds against the default 30-second Roots drain. Redeploy the reviewed
WAR/image and return the node only after readiness. Live tabs on a removed owner
must recover saved business drafts or reopen. Roll back to the previous artifact
with a compatible schema; container/WAR rollback does not undo data changes.

Reference: [Tomcat HTTP connector configuration](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html).
