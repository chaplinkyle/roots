# Production readiness

Roots 0.1 is usable software, but it is not yet a general-purpose production
platform.

## Practical fit today

| Workload | Current assessment |
|---|---|
| Local development, demos, and prototypes | Good fit |
| Internal single-node tools with modest concurrency | Reasonable pilot fit |
| Database-backed CRUD and workflow applications | Reasonable with application-owned persistence and transactions |
| Internet-facing systems with sensitive data | Requires external auth, TLS, hardening, and a formal security review |
| Multi-node or zero-downtime deployments | Not ready without sticky sessions and careful draining |
| Multi-region, serverless, or stateless edge workloads | Not compatible with the current live-state model |
| Offline-first or interaction-heavy client applications | Poor fit |

## What scales well

- Requests run on a virtual-thread-per-task executor, so blocking application I/O
  does not require a large platform-thread pool.
- Each live view serializes actions, which prevents component state races.
- Revision numbers reject stale concurrent responses.
- Server-Sent Event streams block virtual threads rather than platform threads.
- Static routes are resolved ahead of dynamic routes.
- The browser runtime has no framework dependency or hydration workload.

## Current limits

Each open page owns a server-side live view containing its page, layouts,
components, action table, metadata, context, and patch queue. Memory therefore
scales with open tabs and the size of each application object graph.

Sessions and views are stored in process-local concurrent maps. A second JVM
cannot resume a live view created by the first JVM. Sticky routing can keep a
small cluster functional, but it does not solve failover, rolling deployment, or
state migration.

Every action rerenders the Java tree and sends the resulting root HTML fragment.
The browser reconciles that fragment. This is intentionally simple, but very
large pages or high-frequency events need measurement and may require
component-scoped patches.

The JDK HTTP server is useful and dependency-free, but it is not the final
enterprise deployment adapter. Roots also lacks first-class authentication,
authorization middleware, rate limiting, multipart uploads, proxy awareness,
structured telemetry, and graceful live-view draining.

## Safe pilot rules

For a controlled pilot:

1. deploy one JVM, or configure sticky sessions;
2. terminate TLS at a trusted reverse proxy;
3. keep authentication and authorization in an audited application layer;
4. keep database transactions scoped to a request or action;
5. do not store non-thread-safe persistence contexts in components;
6. set memory limits and observe live-view count, heap use, action latency, and SSE connections;
7. load test the real page shapes and user concurrency before launch;
8. treat framework upgrades as potentially breaking until 1.0.

## Release gates

General production readiness requires:

- a live-state/session SPI and explicit affinity strategy;
- graceful drain and reconnect semantics;
- servlet and/or Netty adapters;
- authentication, authorization, and middleware integration points;
- metrics, traces, structured logging, and administrative visibility;
- bounded view/session admission and deterministic cleanup;
- compile-time route indexing;
- component-scoped or measured patch strategies;
- load, soak, reconnect, browser, accessibility, and security suites;
- signed releases and a compatibility policy.

These items are tracked in the [roadmap](roadmap.md).
