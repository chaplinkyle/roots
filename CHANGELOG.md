# Changelog

Changes are recorded against source and released tags. The entries below describe
unreleased work; they do not imply Maven Central availability.

## Unreleased

### Breaking changes

- Java packages and Maven group IDs now use `com.chaplin.roots`, replacing the
  earlier `dev.roots` snapshot. Rebuild application imports, processor configuration,
  and generated manifests; see [migration instructions](docs/compatibility.md#canonical-namespace).
- Java 25 is the minimum baseline; Java 25 and 26 are the verification matrix.

### Runtime and integrations

- Preserve event-time form edits across queued actions, validation, and SSE;
  bound browser scheduling and expose uncertain mutation outcomes.
- Add the explicit browser-widget lifecycle and native form bridge.
- Add structured API errors, tracing, webhook verification, and idempotency
  primitives, including transactional JDBC receipts.
- Add Jakarta Servlet transport and Spring/Boot integrations with contract tests.
- Add durable automation, customer workflow, and Kanban reference applications.
- Add reproducible load scenarios, deployment recipes, and versioned candidate
  bundles with checksum and fresh-consumer verification.

### Project and publication

- Define framework/application boundaries and document server view lifetime,
  persistence, failure recovery, and adoption limits.
- Separate the concise README from the detailed application guide.
- Add source hygiene checks and explicit pre-release publication documentation.
