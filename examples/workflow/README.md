# Durable customer workflow

A reference application for a Java operations team: Spring Security authenticates
users, Roots renders the directory and editor, and application-owned JDBC
transactions preserve customer records and private drafts. It uses
`com.chaplin.roots` packages and Maven coordinates.

This complements the in-memory UI showcase in `examples/enterprise` and the
stateless command inbox in `examples/automation`. It is one organization's
customer directory, not a multi-tenant CRM or an identity provider.

## Run locally

Use JDK 25 LTS or 26. From the repository root:

```bash
./mvnw -pl examples/workflow -am package -DskipTests
export WORKFLOW_JDBC_URL='jdbc:h2:file:./data/customer-workflow;LOCK_TIMEOUT=5000'
export WORKFLOW_AUTH=local
export WORKFLOW_SECURE_COOKIES=false # localhost HTTP only
export WORKFLOW_EDITOR_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
export WORKFLOW_VIEWER_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
java -jar examples/workflow/target/roots-workflow-example-0.1.0-SNAPSHOT-app.jar --migrate
java -jar examples/workflow/target/roots-workflow-example-0.1.0-SNAPSHOT-app.jar
```

Windows PowerShell equivalents for environment setup:

```powershell
.\mvnw.cmd -pl examples/workflow -am package -DskipTests
$env:WORKFLOW_JDBC_URL = 'jdbc:h2:file:./data/customer-workflow;LOCK_TIMEOUT=5000'
$env:WORKFLOW_AUTH = 'local'
$env:WORKFLOW_SECURE_COOKIES = 'false'
$env:WORKFLOW_EDITOR_PASSWORD = python -c 'import secrets; print(secrets.token_urlsafe(24))'
$env:WORKFLOW_VIEWER_PASSWORD = python -c 'import secrets; print(secrets.token_urlsafe(24))'
java -jar examples/workflow/target/roots-workflow-example-0.1.0-SNAPSHOT-app.jar --migrate
java -jar examples/workflow/target/roots-workflow-example-0.1.0-SNAPSHOT-app.jar
```

Open <http://localhost:8080/login>. Sign in as `editor` or `viewer` using the
corresponding password from your local environment. These two local identities
are for evaluation; passwords are hashed with BCrypt at startup and checked at
login. Do not deploy the local login as an organization's identity service.
No default password is supplied. The database URL is required even locally.

The editor can create a private draft, save incomplete values, finish it, and edit
a customer later. The viewer can read the directory and records. A customer save
records the actor, customer version, operation ID, and commit timestamp in the
audit table. It does not retain before/after field snapshots.

## Recovery and concurrency

* A draft has a permanent UUID in its URL and an authenticated owner. SQL queries
  check that owner on every read and write; an editor cannot open another editor's
  draft. The directory is shared by authorized users in this organization.
* Typing is debounced by 400 ms. The last-confirmed-save message advances only
  after the database commits and the browser receives the result. Partial values
  can be saved; business validation runs before creating/updating the customer.
* A restart requires signing in again but preserves committed drafts. Open
  **My drafts** or the original draft URL. Only the 25 most recent unfinished
  drafts appear in that list; older draft URLs continue to work.
* Typing that has not reached the database is not crash durable. Roots retains
  unacknowledged controls when automatic view recovery is required, and pauses
  further actions. Copy those values before manually reloading. Closing a browser
  or losing the device can lose that unconfirmed typing.
* Customer edits use optimistic versions. A conflicting save leaves the private
  draft intact and displays the current saved values. **I reviewed the saved
  values; keep my draft** explicitly accepts the version captured in that click's
  form data. A queued autosave cannot substitute a newer version the user has not
  seen. The following save checks the version again; it never silently overwrites
  a later edit.
* Separate tabs editing the same draft use a draft version as well. A stale tab
  cannot overwrite the newer saved draft. Copy its local changes and reopen the
  draft URL to compare/reapply them.
* Completing a draft locks its row, writes the customer, marks the draft complete,
  and writes one audit entry in the same database transaction. Repeating completion
  with the same saved draft version returns the original customer ID. Reusing the
  operation with a different version fails explicitly. This permanent completed row is the operation
  receipt; it has no short TTL. An ambiguous commit must be resolved by opening the
  same draft, not by creating a replacement operation.
* Database callbacks never retry automatically. External email/webhook/worker
  effects require a transactional outbox and a separate idempotent consumer; none
  is executed inside these transactions.

The application pages retain IDs and immutable DTOs. Connections are borrowed
per repository call from a pool of eight, with a two-second acquisition bound and
five-second statement deadlines. No connection is retained in a live view.
Customer pages use a 25-row keyset cursor plus one lookahead row. Creation time and
UUID make a stable ordering; filtering uses an escaped, case-normalized company
prefix. A database can still scan many rows for an unselective filter: profile
realistic data and collation before selecting production indexes.

## Enterprise sign-in

The default `WORKFLOW_AUTH=oidc` fails startup unless the identity-provider
configuration is supplied:

```text
WORKFLOW_AUTH=oidc
WORKFLOW_OIDC_ISSUER=https://identity.example.com/realms/company
WORKFLOW_OIDC_CLIENT_ID=customer-operations
WORKFLOW_OIDC_CLIENT_SECRET=<secret-manager value>
WORKFLOW_OIDC_REDIRECT_URI=https://customers.example.com/login/oauth2/code/company
WORKFLOW_SECURE_COOKIES=true
```

Register that exact callback with the provider. The application uses Spring
Security's authorization-code/OIDC implementation and issuer discovery. Map
`roots-viewer` or `roots-editor` into the **verified ID token's** `groups` claim.
Only those exact groups grant Roots roles. A successful login without a recognized
group cannot read the application. The stable draft owner hashes issuer and subject;
changing issuer or subject requires an explicit ownership migration. Email/display
names do not define ownership. Configure MFA, account lifecycle, session revocation,
and login abuse controls at your provider.
Roles are snapshots established at login. This example does not implement OIDC
back-channel logout or live group refresh. For immediate external revocation,
integrate provider/session invalidation and a Roots authorization policy that
checks current account state; checking the same role snapshot on an existing
stream does not refresh it.

Spring protects login and logout with its CSRF token. Only the exact Roots action
and disposal POST endpoints use Roots' own session-bound CSRF and origin checks.
Roots named policies run on pages and actions; repository methods also enforce
reader/editor roles. Sign out uses Spring's confirmation form outside the Roots
mount. The local login is integration-tested; a live company OIDC provider must
still be configured and verified in its deployment environment.

Sources: [Spring Servlet security configuration](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html),
[CSRF protection](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html),
and [OIDC login configuration](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html).

## Database and deployment

Use PostgreSQL for the production-shaped deployment. Supply an application-owned
database/schema and a restricted runtime role:

```text
WORKFLOW_JDBC_URL=jdbc:postgresql://db.internal:5432/customer_operations?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.pem&connectTimeout=5&socketTimeout=15&tcpKeepAlive=true&currentSchema=customer_operations
WORKFLOW_JDBC_USER=customer_runtime
WORKFLOW_JDBC_PASSWORD=<secret-manager value>
ROOTS_PORT=8080
WORKFLOW_BIND_ADDRESS=0.0.0.0
```

Run the same JAR with **only** `--migrate` in a separate migration job using a DDL
role. It applies the versioned Flyway resources and exits without opening HTTP or
requiring login credentials. Normal startup validates migration history and fails
when a migration is pending. Never change an already-applied migration; add V2,
V3, etc. Review lock and rollback implications and take tested backups. The local
H2 version currently emits Flyway's newer-than-verified-version warning; the tests
exercise the actual configured H2 version rather than treating that warning as a
compatibility certification.

Boot owns the HTTP listener and shutdown, so use Boot settings for this example:

| Setting | Contract |
|---|---|
| `server.port` / `ROOTS_PORT` | Listener, default 8080 |
| `server.address` / `WORKFLOW_BIND_ADDRESS` | Bind address, loopback by default; explicitly use `0.0.0.0` in a protected container network |
| `roots.servlet-path` | Keep `/app`; Spring owns `/login`, `/logout`, `/oauth2/**`, `/login/oauth2/**`, and Actuator |
| `roots.max-live-views` | 1,000 by default; tune after heap/load measurements |
| `roots.max-concurrent-requests` | 1,128 includes SSE; must exceed the live-view bound |
| `roots.shutdown-timeout` | Roots drain bound; keep below Boot's shutdown phase bound |
| `spring.lifecycle.timeout-per-shutdown-phase` | 30 seconds; supervisor grace must be longer |
| `roots.trusted-proxies` | Only actual proxy CIDRs; never trust arbitrary forwarded headers |
| `WORKFLOW_SECURE_COOKIES` | True by default for both Spring and Roots cookies |
| `/actuator/health/readiness` | Readiness includes Boot, Roots, and a bounded database probe |

Terminate TLS at a controlled proxy and restrict backend ingress. Configure Boot's
container forwarding only for that trusted proxy, including OAuth redirects and
secure cookies; Roots proxy configuration alone does not configure Spring's request
origin. Avoid URL rewriting across the `/app` mount. SSE needs unbuffered responses,
long idle timeouts, and no automatic mutation retry. Roots drains before Boot waits
for active Servlet requests, so open SSE streams do not consume the entire
container shutdown window.

The generic [deployment templates](../../deploy/README.md) target the JDK-server
showcase's `/_roots/health` path. This Boot application needs
`/actuator/health/readiness` instead. Choose its executable JAR and change probes,
mount-aware routing, secrets, and the migration job together. Do not simply swap
JARs while leaving those probes unchanged.

For multiple instances, preserve owner affinity and provision Spring HTTP session
sharing separately (for example Spring Session with an application-owned store).
Shared Roots JDBC sessions alone do not share Spring Security's `JSESSIONID` and
do not migrate live component graphs. This example's default Spring sessions are
process-local; a restart requires login and recovery of the durable draft. Database
transactions protect records across instances, but this does not certify transparent
UI failover. Promote immutable artifacts, use compatible migrations, and resolve
open drafts after rollouts. Rollback does not reverse database migrations.

Actuator exposes health only by default. The starter also registers Micrometer
runtime gauges. Add your chosen registry/exporter and an appropriate authenticated
metrics endpoint policy rather than exposing all Actuator endpoints. Monitor pool
waits, action latency, GC, active views, SSE, rejected requests, and draft conflicts.
Set organization-specific draft/audit retention and storage quotas. Deleting a
completed draft removes its recovery link; keep it as long as the operation can be
retried. Neither drafts nor audit rows are automatically deleted here.

## Verification

```bash
./mvnw -pl examples/workflow -am test
```

Repository tests use a real file-backed database and reopen it between pools. They
cover 64 concurrent completions, ownership and roles, incomplete drafts, optimistic
conflicts, pagination, and an injected audit failure that rolls back the customer
and completion marker. Servlet tests exercise real login, CSRF, role restrictions,
and readiness. Chrome, Edge, and Firefox workflow tests cover autosave, restart and
login recovery, explicit conflict review, and a proxy response lost after commit.
Browser screenshots are written under `target/workflow-screenshots`.
The small-screen layout places conflicting saved values before the editor so the
review action follows the values being reviewed.

Install these browsers before running the full test suite. No hosted SSO, cloud
account, or production database is provisioned by these tests.

To repeat the real PostgreSQL and executable-JAR verification, build the JAR,
start Docker, set `JAVA_HOME` to your JDK, and run:

```bash
python3 examples/workflow/verify_postgres.py
```

The script creates its own uniquely named PostgreSQL 16.14 container with random
credentials, runs the five repository contracts on unique schemas, migrates a
separate schema through the executable JAR, verifies login and restart recovery,
and removes its own JVMs, container, volume, and temporary files. It never reuses
an existing database. `--java`, `--maven`, `--jar`, and `--postgres-image` allow
explicit local toolchains/artifacts. The inherited repository tests also run
against the dedicated `roots_receipts_test` database when
`ROOTS_TEST_POSTGRES_URL`, `ROOTS_TEST_POSTGRES_USER`, and
`ROOTS_TEST_POSTGRES_PASSWORD` are configured; each test owns and removes its schema.
