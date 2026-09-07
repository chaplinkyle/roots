# JDBC shared state

The optional `roots-jdbc` artifact provides shared sessions, session values,
tagged application caching, node-affinity leases, and durable operation receipts without adding Spring, an
ORM, a driver, or a connection pool to the runtime:

```xml
<dependency>
  <groupId>com.chaplin.roots</groupId>
  <artifactId>roots-jdbc</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The application supplies its own JDBC driver and `DataSource`. Install the schema
with Flyway, Liquibase, or the application's normal migration process. The
ownership statements are equivalent to:

```sql
CREATE TABLE roots_live_view_ownership (
    view_id VARCHAR(512) PRIMARY KEY,
    node_id VARCHAR(256) NOT NULL,
    session_id VARCHAR(512) NOT NULL,
    expires_at_epoch_ms BIGINT NOT NULL
);

CREATE INDEX roots_live_view_ownership_expiry_idx
    ON roots_live_view_ownership (expires_at_epoch_ms);
```

`JdbcLiveViewOwnership.schemaStatements()` returns these exact statements.
`createSchema(dataSource)` executes them once for tests or first-run tooling; it
is deliberately not an idempotent production migration. An unqualified custom
table name can be supplied to both the schema and adapter APIs. Names are
validated as SQL identifiers before interpolation.

`JdbcSessionRepository.schemaStatements()` separately returns the session schema:

- `roots_sessions` stores the cookie-safe identifier and idle expiry;
- `roots_sessions_mutex` contains one row locked while creating a session, making
  `maximumSessions` exact across nodes;
- `roots_sessions_values` stores encoded values with a foreign key and cascade
  cleanup;
- an expiry index supports maintenance deletion.

The default value column accepts 8,192 encoded characters and keys are limited to
255 non-control characters. Use the returned DDL as the reviewed starting point
for the target database rather than invoking `createSchema` during concurrent
application startup.

`JdbcRootsCache.schemaStatements()` returns eight statements for the cache:

- `roots_cache` stores bounded encoded entries, UTC expiry, and insertion order;
- `roots_cache_tags` indexes committed entries by revalidation tag;
- `roots_cache_loads` stores per-key loader ownership, generation, and lease expiry;
- `roots_cache_load_tags` makes in-flight loads visible to tag invalidation;
- `roots_cache_meta` provides the short transaction mutex and invalidation generation;
- expiry indexes support entry and abandoned-load cleanup.

Cache keys accept up to 1,024 control-free characters, tags accept 256, and the
bundled value column accepts 8,192 encoded characters. Custom unqualified base
table names are supported. As with sessions, use the returned DDL in a reviewed
Flyway/Liquibase migration and reserve `createSchema` for tests or first-run tools.

## Standalone configuration

Every application process needs a stable, deployment-unique node identifier:

```java
var sessions = new JdbcSessionRepository(dataSource);
var ownership = new JdbcLiveViewOwnership(dataSource, System.getenv("ROOTS_NODE_ID"));
var cache = new JdbcRootsCache(dataSource);

var config = RootsConfig.forApplication(Application.class)
        .sessionRepository(sessions)
        .liveViewOwnership(ownership)
        .cache(cache)
        .build();
```

The adapter borrows and closes a connection for each operation and never closes
the `DataSource`. The application remains responsible for pool sizing, driver
configuration, credentials, TLS, migrations, backups, and database monitoring.

The built-in `JdbcSessionValueCodec.standard()` preserves strings, booleans,
primitive number wrappers, big integers/decimals, characters, UUIDs, instants,
and byte arrays. It rejects every other type instead of falling back to Java
serialization. Supply a thread-safe `JdbcSessionValueCodec` when application
records need a stable JSON or otherwise versioned representation. Values are
loaded lazily, so two repository instances always observe committed database
state rather than node-local copies.

`JdbcCacheValueCodec.standard()` supports the same safe scalar families. It
receives the requested `Class<T>` while decoding so a JSON/domain codec can
restore records explicitly. Cache nodes sharing a table must use compatible
codecs and the same configured capacity. Java serialization is never used.

## Spring Boot configuration

The starter automatically adopts a unique `LiveViewOwnership` bean:

```java
@Bean
SessionRepository rootsSessions(DataSource dataSource) {
    return new JdbcSessionRepository(dataSource);
}

@Bean
LiveViewOwnership rootsOwnership(DataSource dataSource,
                                 @Value("${ROOTS_NODE_ID}") String nodeId) {
    return new JdbcLiveViewOwnership(dataSource, nodeId);
}

@Bean
RootsConfigCustomizer rootsJdbcCache(DataSource dataSource) {
    return builder -> builder.cache(new JdbcRootsCache(dataSource));
}
```

No direct coupling exists between `roots-jdbc` and Spring. The same adapter works
with a container-managed Jakarta `DataSource`, HikariCP, or a direct JDBC data
source.

## Concurrency and deployment contract

Session creation locks one database mutex row, checks capacity, and inserts the
cryptographically random identifier in the same local JDBC transaction. Nodes
must use the same configured capacity. Value writes use conditional update,
primary-key insert, and collision retry, so first-write races do not lose data.
Expired session deletion cascades to its stored values.

Ownership claims use a conditional update, primary-key insert, and collision retry rather
than a vendor-specific upsert. Renew and release statements include the node and
session identifiers, so a stale process or session cannot extend or remove a
replacement lease. Expiry is stored as UTC epoch milliseconds and cleanup uses
the indexed expiry column.

Cache misses first claim a per-key database lease. The application loader runs
outside every database transaction, so a slow query does not hold the metadata
mutex or a pooled connection. Other nodes observe the lease and poll for the
published value; an expired lease can be replaced after a failed process. Before
publication the owner locks the metadata row and rechecks its token and generation.
Key, tag, or full invalidation deletes matching entries and in-flight leases and
advances that generation, which prevents a late stale loader from repopulating the
cache. Capacity is checked in the same publication transaction. The five-minute
lease and ten-millisecond poll defaults are configurable; size the lease above the
longest expected loader and size the connection pool for concurrent cache traffic.

The target database must provide atomic conditional row updates and primary-key
uniqueness under normal read-committed operation. Nodes must have synchronized
clocks; choose a view timeout comfortably above expected clock skew and database
latency. The included suite verifies the SQL contract against H2 with 200
contending ownership claims, 100 cross-node capacity attempts, 100 concurrent
value writes, 200 contending cross-node cache misses, invalidation during active
loads, lease recovery, capacity eviction, scripted JDBC fault injection, codec
corruption cases, and real Roots servers sharing sessions, cache values, and
leases. The session/ownership lifecycle is smoke-tested
in H2's PostgreSQL, MySQL, MariaDB, Oracle, and SQL Server compatibility modes.
Production databases still require
deployment-specific compatibility, failover, pool, and latency testing.

This table contains routing leases, not page or component state. A load balancer
must still route the browser to `X-Roots-Owner`, and loss of that JVM requires a
fresh document. Roots does not serialize arbitrary Java object graphs.

## Durable operation receipts

`JdbcIdempotencyStore` coordinates a business transaction and its replayable HTTP
response. Use `schemaStatements()` or `schemaStatements(tableName)` in your normal
migration process. `createSchema(dataSource)` creates the default schema once for
tests; it does not track schema versions or handle concurrent startup migrations.
The schema contains a hashed scope/key primary key, request fingerprint, expiry,
status, bounded encoded headers, and a Base64 response body. No Java serialization
or plaintext idempotency key is stored.

```java
var receipts = new JdbcIdempotencyStore(dataSource);
var response = receipts.execute(scope, key, IdempotencyStore.fingerprint(request),
        Duration.ofHours(24), connection -> {
            // Authentication, authorization and input validation precede this call.
            try (var update = connection.prepareStatement(
                    "UPDATE orders SET status = ? WHERE tenant_id = ? AND id = ? AND status = ?")) {
                update.setQueryTimeout(5);
                update.setString(1, "approved");
                update.setString(2, tenantId);
                update.setString(3, orderId);
                update.setString(4, "pending");
                if (update.executeUpdate() != 1) {
                    return Response.text(409, "Order is no longer pending");
                }
            }
            return Response.text(200, "Approved");
        });
```

The application owns the `orders` schema and validates the identifiers. Scope
must unambiguously include tenant, authenticated principal, and operation. Authorize
every attempt before calling the store, including replays. Use the supplied
connection for **all** business SQL; a separately committed service or an ORM
transaction does not participate automatically. The callback must not close,
commit, roll back, or otherwise manage the connection. Returning commits both
the business change and receipt; throwing rolls back both. A returned error status
also commits: the example's 409 is safe because its conditional update changed no rows.

Same-key calls serialize using row locks and primary-key uniqueness at
`READ_COMMITTED`. The adapter retries only claim collisions before the callback
starts; it never reruns a callback that has started within one `execute` call.
Same key with changed request content returns 422. An incomplete committed receipt
returns 409 and requires reconciliation; this can indicate application transaction
misuse or damaged data. A receipt decoding error does not rerun the callback.

If commit succeeds but the response or acknowledgement is lost, retrying with the
same scope/key/fingerprint replays the committed result. If the transaction rolled
back, the retry can execute. This guarantee covers SQL using that connection and
the configured database's durability guarantees. External effects require an outbox
in the transaction and idempotent delivery; they cannot be rolled back by this store.

Defaults are a 128 KiB response body limit and ten-second receipt statement timeout.
The constructor permits body limits up to 786,432 bytes and statement timeouts up
to 300 seconds. Only final buffered responses without `Set-Cookie` are supported;
headers have separate bounded counts and encoded size. Every node sharing a receipt
table must use compatible limits. Configure pool acquisition, driver/network, and
callback statement timeouts separately; the receipt timeout is not an end-to-end
operation deadline.

Retention is measured after callback completion, from one second to seven days.
Run bounded maintenance periodically:

```java
int removed = receipts.deleteExpired(Instant.now(), 1_000);
```

The expiry index supports cleanup. The store does not enforce a global row quota;
budget database capacity for arrival rate, retention, and response sizes. After
expiry, a key can execute again, so permanent business uniqueness constraints remain
necessary for operations that must never duplicate. Keep node clocks synchronized.

Tests cover concurrent independent store instances, unrelated-key progress, rollback,
response rejection/corruption, expiry, bounded cleanup, file-backed database reopen,
lost commit acknowledgements, and connection cleanup failures. Real PostgreSQL 16.14
verification additionally covers 64 contending calls, a terminated backend session,
and a same-key lock timeout without invoking the contender's business callback.
Those results certify these exercised cases, not every PostgreSQL topology or other
vendor. The other JDBC adapters' compatibility-mode tests are not real vendor tests.

To repeat PostgreSQL verification, provision a disposable database named exactly
`roots_receipts_test` and set `ROOTS_TEST_POSTGRES_URL`, `ROOTS_TEST_POSTGRES_USER`,
and `ROOTS_TEST_POSTGRES_PASSWORD` in the process environment. The test user must be
able to create a schema and terminate its own sessions. The suite creates and removes
its own uniquely named schema and refuses any other database name.

```sh
./mvnw -pl roots-jdbc -am -Proots-postgres verify
```

The PostgreSQL driver is a test-only profile dependency. Application drivers and
connection pools remain application-owned. The default suite skips the three real
PostgreSQL tests when this profile is not enabled.
