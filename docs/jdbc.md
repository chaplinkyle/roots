# JDBC shared state

The optional `roots-jdbc` artifact provides shared sessions, session values,
tagged application caching, and node-affinity leases without adding Spring, an
ORM, a driver, or a connection pool to the runtime:

```xml
<dependency>
  <groupId>dev.roots</groupId>
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
