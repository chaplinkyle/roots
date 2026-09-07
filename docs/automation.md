# Machine APIs and automation

For a runnable API-only application and Python client, see the
[durable command inbox](../examples/automation/README.md). It integrates authenticated
submission, signed webhooks, atomic JDBC receipts, and persistent command identity.

Applications can contain only API routes. The compiler, manifest reader, and
scanner accept at least one concrete `Page` or `ApiRoute`; applications with neither
are rejected.

## Session-free endpoints

```java
package com.acme.api.status;

@com.chaplin.roots.annotation.Stateless
@com.chaplin.roots.annotation.Authorize("automation")
public final class Route implements com.chaplin.roots.ApiRoute {
    public com.chaplin.roots.Response get(com.chaplin.roots.Request request) {
        return com.chaplin.roots.Response.text(200, "ready");
    }
}
```

Configure the `automation` policy and an `AuthenticationProvider` at startup.
For example, `AuthenticationProvider.bearer(tokenService::verify)` delegates to a
verifier returning `Optional<AuthenticatedIdentity>`. Token verification,
issuer/audience/expiry checking, authorities, and rotation belong to that provider.
Stateless does not mean public. Use named policies to authorize each operation.

Authentication, authorization, middleware, limits, tracing, and instance lifecycle
still apply. Roots never loads/touches/creates a browser session or emits its session
cookie. Access to session IDs or values fails explicitly. Adapt session-dependent
middleware before opting in. This annotation applies to API routes, not live UI.

## Structured errors

`ProblemDetail` creates bounded `application/problem+json` responses following
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html). HTTP and JSON status agree,
and responses use `Cache-Control: no-store`:

```java
return ProblemDetail.of(URI.create("https://api.example.com/problems/version-conflict"),
        409, "Record changed", "Reload the record before saving your changes.")
        .withExtension("currentVersion", "8").response();
```

Extensions are string-valued and bounded. Supply public diagnostics; never copy
credentials, SQL, or exception messages into them. This opt-in API does not change
existing framework errors or `Response.json` routes.

## Idempotent mutations

Use an application-scoped `IdempotencyStore` supplied through DI/instance factory,
not a new store for every route instance. Authenticate and authorize every attempt,
including replays. The application-service integration has this shape:

```java
var key = request.header("Idempotency-Key").orElseThrow();
var scope = tenantId + ":" + request.identity().orElseThrow().name() + ":create-order";
return receipts.execute(scope, key, IdempotencyStore.fingerprint(request),
        Duration.ofHours(24), () -> orders.create(request));
```

`tenantId`, `receipts`, and `orders` are application data/services. Use an
unambiguous scope encoding if identifiers may contain delimiters. Reject repeated
idempotency headers at the route boundary. The fingerprint covers method, path,
query values, content type, and original body bytes. Credentials are excluded;
authenticated scope is essential. Add other representation-affecting headers to
an application-defined fingerprint when needed.

| Condition | Result |
|---|---|
| New key | Execute within the store's coordination domain |
| Completed identical request | Replay buffered response; `Idempotency-Replayed: true` |
| Same key, different fingerprint | 422 |
| Same key while running or outcome uncertain | 409 |
| Store full | 503 with `Retry-After: 1` |

`IdempotencyStore.inMemory(maxEntries, maxResponseBytes)` is bounded and
thread-safe. Active work is retained, and completed receipts are not evicted before
retention expires. It cannot survive restart or coordinate multiple JVMs. Keys
can execute again after expiry: retention is a deduplication window. Exceptions or
unstoreable responses retain an uncertain result. Cookies, streaming responses,
and oversized responses cannot be replayed; check constraints before side effects.

### Durable JDBC receipts

The optional `com.chaplin.roots:roots-jdbc` artifact provides
`JdbcIdempotencyStore`. Its callback receives a JDBC `Connection`: business writes
and the completed response commit in the **same transaction**. It intentionally
has a different callback contract from the process-local store.

```java
var receipts = new JdbcIdempotencyStore(dataSource); // application-scoped
return receipts.execute(scope, key, IdempotencyStore.fingerprint(request),
        Duration.ofHours(24), connection -> orders.create(connection, request));
```

`orders.create` must perform every business write through the supplied connection.
It must not close it, commit, roll back, change auto-commit, or start a separate
transaction. Supply an independent auto-commit `DataSource`, not a transaction-bound
connection proxy. Returning a bounded final response commits, including a returned
4xx/5xx response; throwing rolls back SQL and the receipt. Use exceptions for
rollback, not an error status. Cookies and streaming responses are rejected.

Same-key callers serialize in the database; unrelated keys do not share a global
mutex. Only claim collisions before business execution are internally retried.
A lost response or ambiguous commit is resolved by submitting the same key and
fingerprint again: a committed receipt replays; a rolled-back operation can run.
Neither store automatically adds idempotency to browser `@ServerAction` methods.
An operation identifier must survive reload and be bound to the application workflow.

Use `JdbcIdempotencyStore.schemaStatements()` in a reviewed migration, and schedule
bounded `deleteExpired(Instant.now(), batchSize)` maintenance. Retention starts when
the callback completes and accepts one second through seven days. Expiry permits
execution again; keep permanent business uniqueness constraints for operations that
must never repeat. External effects need a transactional outbox and downstream
deduplication. See [JDBC deployment and verification](jdbc.md#durable-operation-receipts).

## Signed webhooks

`WebhookVerifier` implements HMAC-SHA256 `v1`
[Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md).
Construct an immutable verifier with one to four configured `whsec_` secrets and a
tolerance such as `Duration.ofMinutes(5)`. Deliver secrets through the platform
secret store; overlap old/new keys during rotation, then remove the old key.

```java
var delivery = verifier.verify(request);
if (delivery.isEmpty()) return Response.text(401, "Invalid webhook signature");
var verifiedId = delivery.orElseThrow().id();
// Persist the endpoint-scoped verifiedId and business change atomically before 2xx.
```

Verification streams original repeatable bytes; never parse/re-serialize JSON
before verification. It validates POST, bounded single headers, signature
candidates, and past/future timestamp skew. It supports symmetric `v1`, not `v1a`.
Invalid metadata/signatures return empty; body I/O failures remain errors.
Verification does not deduplicate deliveries.

## Browser action recovery

Actions default to a 30-second response deadline, including body delivery.
Override on an action-bound element with `.actionTimeout(Duration.ofSeconds(60))`;
the range is 100 ms to five minutes. Move longer jobs to background work with
persisted status. Each live view has its own ordered queue; a new page does not
wait for unresolved old actions. Unsubmitted old-view work reports `roots:stale`
and is discarded. Already submitted operations may still finish.

A network error, unreadable response, deadline, HTTP 5xx, or an explicit server
uncertainty header pauses further mutations in that view. Roots preserves local
drafts, stops live patches, clears pending presentation, rolls back optimistic
presentation, and shows a recovery notice. That cannot undo a server transaction.
Roots never automatically retries an uncertain mutation. Unexpected handler or
post-handler rendering failures also block subsequent actions on the server view.

- `roots:action-uncertain`: `{view, action, reason, error}`; reason `timeout`, `transport`, or `server`.
- `roots:action-blocked`: `{view, action}` for an attempt withheld while uncertain.
- `roots:backpressure`: saturation of the 128-item active/queued/delayed bound.
- `roots:error`: the corresponding error object.

The user can retain edits, explicitly reload, then check persistent business status
before trying the mutation again. A handler's `ValidationException` or
`IllegalArgumentException` remains correctable on the same view and must be raised
before business mutations. Exceptions during result rendering are uncertain even
if their type or configured mapper normally represents validation. Other HTTP 4xx
errors retain existing handling unless explicitly marked uncertain. Persistent
drafts, transaction design, and operation status remain application responsibilities.
