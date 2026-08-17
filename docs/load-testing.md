# Load and soak testing

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

Run the bounded gate with Java 26:

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
