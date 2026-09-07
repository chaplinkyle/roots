# Load and soak testing

## Rendering benchmark

Run `./mvnw -pl roots-load-tests -am -Proots-benchmark test` to measure full
rendering and exact component-patch selection for 100- and 1,000-row business
tables. Each row has an annotated action; every iteration changes one row and
checks that the patch remains confined to that row. The harness warms up for 100
iterations, measures 200, and reports mean combined time, separate p95 render and
patch times, and thread allocations when the JVM exposes them. Override
`-Droots.benchmark.iterations=1000` for a longer measurement.

An initial Windows/JDK 26 development-machine run produced the following results.
These are in-process measurements, exclude network/database/browser costs, and
are not capacity promises. Repeat on deployment hardware with representative pages.

| 1,000-row workload | Before | After |
|---|---:|---:|
| Mean render + patch | 59.202 ms | 3.449 ms |
| p95 patch selection | 58.906 ms | 0.979 ms |
| Allocated bytes per operation | 60,030,589 | 5,802,488 |

The implementation now skips unchanged component candidates, compares suffixes
without copying strings, caches annotated method metadata by classloader-safe
`ClassValue`, and reuses compiled HTML validation patterns. HTML and security
validation semantics are unchanged. The table has approximately 219 KB of HTML;
the transferred changed-row fragment is approximately 220 characters.

After the element-allocation changes documented below, the final local Java 26
run measured 2.662 ms mean total and 3,042,496 allocated bytes per operation for
1,000 rows (200 measured iterations after 100 warm-ups). The 100-row case measured
0.419 ms and 329,781 bytes. Log: `.tooling/render-final-jdk26.log`. These remain
microbenchmarks rather than end-to-end latency estimates.

The benchmark and soak profiles are independent and opt-in. Timings are printed,
not enforced as brittle machine-specific test assertions.

Roots has a dedicated HTTP-level verification module. It starts a real JDK
transport on an ephemeral port, creates independent session-bound live views,
and sends the same CSRF- and protocol-bound action requests used by the browser.

The default CI gate runs two scenarios:

- 16 concurrent users each perform 30 sequential actions, proving isolated
  state and exact revision advancement across 480 actions;
- 128 simultaneous actions contend on one live view, proving serialization,
  128 unique consecutive revisions, and the final authoritative state.

Together they cover 608 live actions plus initial-page and disposal traffic.
The tests require zero admission rejections, zero lost or duplicate revisions,
zero retained live views, zero active requests after cleanup, observed request
overlap, and a deliberately generous five-second p99 action ceiling. That ceiling
detects stalls in the test environment; it is not a production SLA.

Run the bounded gate with Java 25 LTS or newer:

```powershell
.\mvnw.cmd -pl roots-load-tests -am test
```

The `roots-soak` profile enables a duration- and user-configurable sustained test.
Defaults are 32 users for 60 seconds:

```powershell
.\mvnw.cmd -pl roots-load-tests -am -Proots-soak test
```

For a longer run:

```powershell
.\mvnw.cmd -pl roots-load-tests -am -Proots-soak `
  -Droots.soak.seconds=900 -Droots.soak.users=64 test
```

Each soak user keeps one live view and performs sequential actions until the
shared deadline. The suite verifies every response revision, then disposes every
view and checks runtime counters for leaks, active work, and admission failures.

This is framework regression evidence, not a universal capacity claim. Before a
production launch, repeat load testing with the application's real component
trees, database latency, middleware, authentication, upload mix, SSE connection
count, JVM heap, container, proxy, and deployment topology. Record throughput,
latency distributions, allocation/GC behavior, database pool pressure, and
recovery after drain or node loss.

## Recorded 64-user run

A local Windows/Java 25 run on 2026-09-06 used 64 concurrent users for 60 seconds
and completed 1,244,841 HTTP actions with p99 latency 14.6531 ms. The assertions
verified exact revisions, zero admission rejection, and no remaining active
requests/live views after disposal. The command was:

```sh
./mvnw -pl roots-load-tests -Proots-soak -Droots.soak.seconds=60 -Droots.soak.users=64 test
```

Install the reactor first when running the module without `-am`. This is the tiny
counter fixture over loopback, with no database, proxy, browser rendering, or
simultaneous SSE streams. It is not an enterprise capacity claim. The local log
is `.tooling/enterprise-soak-jdk25.log`. Current harnesses use a fixed 4,096-bucket
logarithmic histogram (32 KiB of counters per measured series); percentiles round
up by at most 1%, capped by the observed maximum. They no longer retain every
latency sample. The recorded older run used exact retained samples.

## Database tables with simultaneous SSE

`BusinessLoadTest` measures a business-shaped workload: independent session-bound
views, each retaining 100 or 1,000 row components and action bindings, a validated
native form, optimistic SQL update/read/commit through HikariCP, and a continuously
open SSE stream. Each user submits one request at a time, with configurable think
time; a producer concurrently updates all views, waits for the bounded batch to
finish, then waits the configured push interval. Revisions must
increase, committed row versions must equal successful action counts, every view
is disposed, and active requests and update subscriptions must drain to zero.

The maintained runner creates a private PostgreSQL container, uses generated
credentials, records a JFR profile and summaries, and removes its container/volume:

```powershell
.\mvnw.cmd install -DskipTests
python roots-load-tests/verify_postgres.py --output .tooling/business-run `
  --users 32 --rows 1000 --seconds 60 --pool 8 --think-ms 10 --push-ms 1000
```

Use a new output directory for each run. Java must be available through `JAVA_HOME`.
The runner fixes the forked test JVM at `-Xms512m -Xmx512m` and uses JFR's `profile`
settings. Override `--maven` and `--postgres-image` if needed. A fast H2 check is:

```sh
./mvnw -pl roots-load-tests -Proots-soak test -Dtest=BusinessLoadTest \
  -Droots.business.users=4 -Droots.business.seconds=3
```

Direct execution can use `ROOTS_LOAD_JDBC_URL`, `ROOTS_LOAD_DB_USER`, and
`ROOTS_LOAD_DB_PASSWORD`. **Use a dedicated empty disposable database**: the test
creates and drops `roots_load_account`, and fails if that table already exists.
The opt-in `roots-soak` profile runs both the tiny counter and business workloads;
select `-Dtest=RootsLoadTest` to retain only the original counter scenario.

Output separates complete HTTP latency, end-to-end SSE delivery, pool checkout,
and SQL transaction time. It reports transferred payload bytes, sampled peak heap,
post-GC baseline/live/cleanup heap, JVM-wide allocation, GC count/time, CPU time,
and peak pool activity/waiters sampled every 50 ms. Explicit measurement GCs are
included. JVM-wide allocation/CPU/heap includes both client and server, test
assertions and JFR; it cannot be labeled server allocation per request. The live
heap delta is an estimate including HTTP/SSE client state, not a per-view contract.

The traffic interval includes its initial JIT warm-up; page creation and seeding
are outside throughput. Delivery latency starts before waiting for the view lock
and server push rendering.
Pushed messages are full snapshots, so SSE can transfer far more bytes than scoped
HTTP action patches; coalesced snapshots are counted. Closed-loop clients throttle
when responses slow, so results do not characterize an open-loop overload or
coordinated omission. No browser rendering, network delay, production identity
provider, proxy, or distributed deployment is included. Use the workflow example
for business recovery tests and repeat capacity work with the actual application.

### Allocation profile and optimization

A local Windows/Java 25, PostgreSQL 16.14 comparison on 2026-09-06 used 32 users,
1,000 row components per view, an eight-connection pool, 10 ms think time, a
512 MiB JVM, and 60 seconds of traffic. The two comparison runs used the same
earlier sequential push producer; use the final concurrent-producer results below
for SSE latency. A single before/after pair is indicative, not a statistical SLA.

| Measure | Before element allocation changes | After |
|---|---:|---:|
| Completed actions/second | 530.2 | 664.3 |
| HTTP p99 | 115.793 ms | 89.398 ms |
| JVM-wide allocation / completed action, including client and pushes | 6,924,176 bytes | 4,107,075 bytes |
| GC collections, including measurement GCs | 829 | 664 |
| GC time | 4,673 ms | 3,604 ms |

JFR allocation samples identified eager element maps/lists, regex matchers, and
event enumeration as major contributors. Elements now allocate attribute/event/
effect collections only when used, size child lists from initial content, validate
the same ASCII name grammars without regex matcher objects, and resolve browser
event names without per-call enum-array/stream allocation. Tests compare the name
validators with the prior regex definitions for every UTF-16 code unit in first
and subsequent positions. Dataset names are normalized to the browser's lowercase
spelling, closing a case-based bypass of widget ownership checks.

Local evidence: `.tooling/business-32x1000-baseline` and
`.tooling/business-32x1000-optimized`; each includes its Maven log and JFR profile.
Recordings are diagnostic application data; review them before sharing. The current
runner disables initial environment/system-property and process-list JFR events
and never uploads recordings. Older local comparison recordings predate that change.

### Final concurrent-push measurements

The final harness starts its SSE timer before acquiring the view lock, fans out
each update batch concurrently, verifies receipt on every stream, and releases its
closed-stream references before the cleanup heap sample. On the same Windows 11
host (eight logical CPUs), Java 25.0.4.1 and PostgreSQL 16.14 produced:

| Measure | 32 views × 1,000 rows | 128 views × 100 rows |
|---|---:|---:|
| Traffic interval | 60.259 s | 60.942 s |
| Completed actions | 39,657 | 74,240 |
| Actions/second | 658.1 | 1,218.2 |
| HTTP p50 / p95 / p99 | 34.053 / 69.020 / 93.028 ms | 85.910 / 144.130 / 230.069 ms |
| Delivered background snapshots | 1,760 | 6,784 |
| SSE p50 / p95 / p99 | 39.930 / 101.743 / 264.458 ms | 49.701 / 125.388 / 202.153 ms |
| Pool checkout p99 | 62.482 ms | 204.175 ms |
| SQL transaction p99 | 32.079 ms | 16.307 ms |
| Peak pool waiters (pool size 8) | 24 | 120 |
| Sampled peak heap / max heap | 430.3 / 512 MiB | 345.2 / 512 MiB |
| Post-GC baseline / live / cleanup heap | 9.6 / 17.1 / 15.1 MiB | 9.5 / 26.0 / 24.6 MiB |
| JVM-wide allocated bytes | 168,989,014,976 | 47,920,615,448 |
| GC collections / time | 634 / 3,504 ms | 162 / 684 ms |
| Process CPU time, including setup and cleanup | 239.719 s | 124.219 s |
| HTTP action payload / SSE bytes | 25,831,970 / 706,172,155 | 48,267,242 / 274,453,625 |

Both used pool size 8, 10 ms think time, a 1,000 ms pause between completed push
batches, fixed 512 MiB heap, JFR profiling, and the same source. Database row
versions matched all successful actions; there were zero coalesced push snapshots,
zero request-admission rejections, and zero live views, active requests, or
background subscriptions after disposal. These short runs do not prove an absence
of long-term leaks. After-GC heap includes client caches, warmed JVM/application
state, and test instrumentation.

The eight-connection pool dominated the 128-view action tail. Size pools against
database capacity and measure contention; increasing virtual-thread counts does
not remove the database limit. Full SSE snapshots consumed approximately 11.7 MB/s
for the 1,000-row workload. Paginate large tables and limit push frequency according
to the application's needs. Roots still evaluates the entire Java tree on each
render even when its HTTP patch is component-scoped.

Logs, source SHA-256, PostgreSQL image identity, and JFR files are in
`.tooling/business-final-32x1000` and `.tooling/business-final-128x100` locally.
The reproducible commands are the runner command above, then the same command
with a new output directory, `--users 128 --rows 100`. Neither result includes
browser work, remote database latency, proxy behavior, or a production auth stack.
