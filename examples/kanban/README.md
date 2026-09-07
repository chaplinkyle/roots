# Roots Kanban

A working shared task board built with Roots, Spring Boot, and PostgreSQL. Java packages are under `com.chaplin.roots.examples.kanban`.

The board supports task creation, descriptions, priority, assignee, due dates, drag-and-drop between four columns, status changes in the keyboard-accessible editor, search, archive/restore, and a per-task activity history. Data is stored in PostgreSQL. Every change checks the submitted version and commits its activity entry in the same transaction. A stale editor keeps the user's typing and explains how to recover.

The production database starts empty. Sample tasks are created only inside isolated verification databases.

## Run locally

Use Java 25 or 26, Maven, Docker, and Chrome for the browser tests.

```powershell
mvn -pl examples/kanban -am package -Dtest=KanbanTest -Dsurefire.failIfNoSpecifiedTests=false

# Set these through your environment or a secret manager; do not commit passwords.
$env:KANBAN_JDBC_URL = 'jdbc:postgresql://127.0.0.1:5432/roots_kanban'
$env:KANBAN_JDBC_USER = 'roots_kanban'
$env:KANBAN_JDBC_PASSWORD = '<database password>'
$env:KANBAN_ADMIN_PASSWORD = '<unique password, 16 to 72 UTF-8 bytes>'
$env:KANBAN_AUTH = 'local'
$env:KANBAN_SECURE_COOKIES = 'false' # Loopback HTTP only.

java -jar examples/kanban/target/roots-kanban-example-0.1.0-SNAPSHOT-app.jar --migrate
java -jar examples/kanban/target/roots-kanban-example-0.1.0-SNAPSHOT-app.jar
```

Open `http://127.0.0.1:8080/`, and sign in as `admin`. Startup refuses pending migrations. `--migrate` is the explicit schema migration command; normal startup only validates the database.

For a persistent Docker preview of the built JAR, run `python deploy/kanban/preview.py`. It starts the application at `http://127.0.0.1:8090/` and writes its generated login to `.tooling/kanban-preview/login.txt` (git-ignored). PostgreSQL is reachable only on the preview's private Docker network. The script refuses to replace existing preview resources. Stop or start both `roots-kanban-preview` and `roots-kanban-preview-db` with Docker; data remains in the `roots-kanban-preview-data` volume.

## Verification

The default Maven tests use a temporary H2 database. The following command creates its own loopback-only PostgreSQL container, runs the repository and Chrome browser tests against isolated schemas, packages the application, creates a task through its authenticated HTTP action endpoint, abruptly stops the JVM, and verifies recovery after restarting and signing in again. The container and generated credentials are removed afterward.

```powershell
python examples/kanban/verify.py --maven <absolute-path-to-mvn.cmd> --java <absolute-path-to-java.exe>
```

Screenshots are written under `examples/kanban/target/screenshots/`.

## AWS deployment

See [the deployment guide](../../deploy/kanban/README.md). It provisions an isolated VPC, private RDS PostgreSQL, ECS Fargate, an HTTPS load balancer, and Secrets Manager credentials. The database connection verifies the RDS certificate and hostname.

## Scope and operational limits

- This is one shared workspace. Local authentication is one generated bootstrap `admin` account. For individual accounts, configure the inherited OIDC integration (`KANBAN_AUTH=oidc`, `KANBAN_OIDC_ISSUER`, `KANBAN_OIDC_CLIENT_ID`, `KANBAN_OIDC_CLIENT_SECRET`, `KANBAN_OIDC_REDIRECT_URI`). Only verified ID-token groups `roots-editor` and `roots-viewer` grant access.
- Refresh the board to see teammates' latest changes. Cross-user push notifications, multiple boards, attachments, comments, and notifications are not implemented.
- Cards are ordered newest first within each column; dragging changes the column, not the ordering. At most 500 matching cards are displayed; search narrows the result. The editor shows the latest 12 activity entries.
- Unsaved typing belongs to the open browser view. Save before closing it. Committed tasks and history survive restarts; browser sessions do not.
- The initial AWS deployment uses one application instance and a single-AZ database. Deployments briefly interrupt sessions. Scaling the Roots component graph requires an owner-routing strategy. Do not just raise the task count.
- The initial database account owns this dedicated application database and runs migrations. Separate migration and runtime database roles before broader organizational use. Bootstrap secret rotation requires a coordinated database password update when rotating database credentials, and an ECS task restart for either password.
- The framework and app remain a pilot; the verification evidence is local until an actual AWS deployment and its public browser flow have passed.
