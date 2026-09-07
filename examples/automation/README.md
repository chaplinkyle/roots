# Durable automation inbox

This API-only application demonstrates the `com.chaplin.roots` namespace, native
Bearer authentication, named authorization, structured problems, trace propagation,
Standard Webhooks verification, and JDBC receipts used together. It accepts a
command into a durable inbox and exposes its status. It does **not execute** the
queued commands; connect an application-owned worker to perform those operations.

No browser session, live view, Spring runtime, Node.js, or JavaScript build is
required. HikariCP and the JDBC drivers are application dependencies. The Roots
core and JDBC adapter remain free of runtime library dependencies beyond the JDK
and the Roots core respectively.

## Run locally

Use Java 25 LTS or newer. From the repository root:

```sh
./mvnw -pl examples/automation -am package
```

In PowerShell, use `.\mvnw.cmd`. Configure a persistent local H2 database and
generate credentials in the process environment without printing them:

```powershell
$env:AUTOMATION_JDBC_URL = 'jdbc:h2:./.roots/automation;DB_CLOSE_ON_EXIT=FALSE'
$env:AUTOMATION_JDBC_USER = 'sa'
$env:AUTOMATION_TOKEN = (python -c "import secrets; print(secrets.token_urlsafe(32))")
$env:AUTOMATION_WEBHOOK_SECRET = (python -c "import secrets,base64; print('whsec_'+base64.b64encode(secrets.token_bytes(32)).decode())")
java -jar examples/automation/target/roots-automation-example-0.1.0-SNAPSHOT-app.jar --migrate
java -jar examples/automation/target/roots-automation-example-0.1.0-SNAPSHOT-app.jar
```

For a POSIX shell, set the same variables with `export NAME=value`; obtain secrets
from your secret manager or a local secure environment. Run `--migrate` exactly
once against a new example database. It creates tables without dropping existing
data. It is a first-run tool, not a versioned migration engine; production deployments
must install reviewed versioned migrations before starting replicas. A failed DDL
run may need migration repair before it is retried.

In another terminal with the same token in its environment:

```sh
python examples/automation/client.py reindex
python examples/automation/client.py reindex --id <previous-command-UUID>
python examples/automation/client.py refresh-catalog --webhook
```

The client prints the command UUID before sending. Save it to resume an interrupted
submission. It retries transport failures and 5xx responses at most twice with the
same command ID, idempotency key, and form body. It stops on 4xx. To inspect state:

```sh
curl -H "Authorization: Bearer $AUTOMATION_TOKEN" \
  http://127.0.0.1:8080/api/commands/<command-UUID>
```

Use `curl.exe` and `$env:AUTOMATION_TOKEN` in PowerShell. The response reports
`queued`; acceptance is not proof that a worker executed the command.

## HTTP contract

| Endpoint | Authentication | Behavior |
|---|---|---|
| `POST /api/commands` | Bearer token and `automation` authority | Accept a command with one `Idempotency-Key` header |
| `GET /api/commands/{id}` | Same identity/authority | Read the caller's command state |
| `POST /api/webhooks` | Valid Standard Webhooks HMAC signature | Accept a delivery using its verified `webhook-id` as the receipt key |

Both POST routes accept exactly two URL-encoded fields: `command_id` (canonical
UUID) and `operation` (`reindex` or `refresh-catalog`). Commands and their receipt
responses commit on the same JDBC connection. Duplicate identical receipt keys
replay the original response. Changed bodies under the same key return 422.
The permanent command primary key prevents a second insert after receipt expiry
or when callers choose different receipt keys. Reusing a command ID for different
content returns 409. A concurrent insert under a different receipt key can also
return 409; inspect the command or retry unchanged to resolve it.

The sample token represents one configured `automation-client` identity. Its signed
webhook sender can submit commands for that identity. For multiple clients or tenants,
replace this verifier with your issuer/audience/expiry-verifying provider, enforce
the appropriate authorities, and derive owner/scope from authenticated identity.
Never accept a client-supplied owner or tenant as authorization. Omit
`AUTOMATION_WEBHOOK_SECRET` to disable webhook ingestion. Rotate credentials through
the deployment secret store; the native verifier supports overlapping webhook keys
when you extend the example configuration for rotation.

## PostgreSQL and deployment

Set an application-owned PostgreSQL URL, database user, and password instead of the
H2 URL. For a remote database, use verified TLS and bounded driver timeouts, for example:

```text
AUTOMATION_JDBC_URL=jdbc:postgresql://database.internal/automation?sslmode=verify-full&connectTimeout=5&socketTimeout=15&tcpKeepAlive=true
AUTOMATION_JDBC_USER=automation
AUTOMATION_JDBC_PASSWORD=<provided by the platform secret store>
```

Install migrations with a migration identity; runtime credentials need only access
to the inbox and receipt tables. The pool has eight connections, two-second
acquisition timeout, and one idle connection. Callback statements have five-second
timeouts; receipt statements have ten-second timeouts. Size these budgets against
your database and workload. [HikariCP configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)
documents the pool settings and keepalive requirements.

Use the repository's [container and deployment templates](../../deploy/README.md),
setting `APP_JAR` to this example's `*-app.jar`. Pass database credentials and tokens
at runtime, terminate public TLS at the proxy, and use `ROOTS_HOST=0.0.0.0` in a
container. This API-only example needs no session affinity; replicas coordinate
through PostgreSQL. A local H2 file is for a single process and must not be shared
as a multi-node production database. Readiness checks listener/drain state; add
application dependency monitoring for database availability. `Roots.run` provides
bounded graceful shutdown and the application owns the pool lifecycle.

Receipts retain responses for 24 hours. Schedule the following maintenance command
with the same database environment; each invocation removes at most 1,000 expired
receipts and does not remove inbox commands:

```sh
java -jar examples/automation/target/roots-automation-example-0.1.0-SNAPSHOT-app.jar --prune-receipts
```

Define inbox retention separately. A worker must claim commands transactionally,
persist completion, and deduplicate external effects. Use an outbox when database
changes must trigger external delivery. Database receipts cannot atomically commit
an email, payment, or external service call.

## Verification

`./mvnw -pl examples/automation -am verify` exercises 64 concurrent authenticated
submissions, exact-once inbox insertion, replay after application/pool/database
reopen, command uniqueness after receipt cleanup, signed duplicate webhook delivery,
stale/unsigned webhook rejection, and invalid input. Those integration tests use
file-backed H2. The [JDBC PostgreSQL profile](../../docs/jdbc.md#durable-operation-receipts)
separately tests the receipt transaction on a real database. Repeat the packaged
client flow against your intended PostgreSQL topology before deployment.

With Docker and the built application JAR available, run the complete packaged check:

```sh
python examples/automation/verify_packaged.py
```

It uses `JAVA_HOME` (or `java` on `PATH`), starts its own PostgreSQL 16.14 container
on an ephemeral loopback port with generated credentials, and removes that container
and volume afterward. It verifies abrupt JVM restart, a proxy 502 after a committed
write, signed duplicate delivery, and receipt cleanup with permanent command
deduplication. Use `--java /path/to/java` or `--postgres-image <approved-image>` to
select local tooling. It does not connect to an existing application database.
