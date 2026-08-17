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
- Every patch/redirect carries its originating view identity, and latest-wins
  navigation rejects old-view actions/SSE work while disposing unused responses;
  queued actions retain their originating credentials and validation/error outcomes
  cannot target a newer page.
- Server-Sent Event streams block virtual threads rather than platform threads.
- Admission permits enforce exact per-JVM request and live-view limits; the
  configured session repository atomically enforces its session limit.
- Readiness can be lowered before a bounded graceful shutdown.
- Static routes are resolved ahead of dynamic routes.
- Public assets and unmatched/scanner traffic do not allocate sessions.
- Production assets use in-memory byte caching and conditional SHA-256 entity tags.
- Local PNG/JPEG optimization is session-free, byte/pixel/dimension bounded, and
  retained in a 256-entry production LRU; local font CSS is validated and bounded.
- Prerendered public pages avoid live views and sessions; incremental regeneration
  coalesces per route and keeps serving the last good HTML after a failure.
- Multipart forms are binary-safe, constrained by exact total/text-part limits,
  and spool above a configurable heap threshold with deterministic cleanup.
- API downloads can stream lazily with fixed or transport-framed lengths on both
  built-in and Servlet transports, avoiding a response-sized heap allocation.
- Data-cache misses are coalesced and retained under an exact entry limit.
- The browser runtime has no framework dependency or hydration workload.
- A validated global CSP is enforced at the final boundary for every HTML response.

## Current limits

Each open page owns a server-side live view containing its page, layouts,
components, action table, metadata, context, and patch queue. Memory therefore
scales with open tabs and the size of each application object graph.

The default sessions, ownership leases, and views use process-local concurrent
maps. A second JVM cannot resume a live view created by the first JVM. A shared
`SessionRepository` and `LiveViewOwnership` implementation plus sticky or
owner-aware routing can keep a small cluster functional and explicitly diagnose
wrong-node traffic, but they do not solve component-graph failover, rolling
deployment state migration, or owner loss.

Request bodies remain in memory through a configurable threshold (64 KiB by
default) and then spool to a configurable temporary directory. Multipart files
are repeatable slices rather than heap copies, and Roots deletes request-scoped
storage after the synchronous pipeline on success or failure. The total body
default remains 1 MiB and each non-file multipart field defaults to a 1 MiB cap.
Calling `Request.body()` or `UploadedFile.content()` intentionally materializes
the selected content; use `bodyStream()`, `UploadedFile.openStream()`, or
`transferTo(...)` for large data. Direct-to-object-store flows remain
application-owned. Client filenames are untrusted metadata and must never be
treated as filesystem paths.

Outbound API streaming is synchronous and applies backpressure through the
transport output stream. The response writer must release application-owned
files, cursors, database results, or other sources in its own `try`-with-resources
block. Fixed-length output is byte-count checked. Once headers have been committed,
a writer failure closes a partial response rather than becoming a structured
error response, so applications should complete validation and authorization
before returning the stream and make mid-stream failures observable.

The default tagged data cache is process-local. Its TTL, least-recently-used
eviction, and entry cap bound retained values, but tag invalidation in one JVM is
not broadcast to another JVM. Multi-node deployments can configure the bundled
`JdbcRootsCache` for shared TTL, capacity, miss ownership, and key/tag invalidation,
provide another coordinated `RootsCache`, or accept node-local freshness windows.
JDBC cache counters remain per process, all nodes must share capacity/codec
configuration, and the adapter requires workload-specific database/pool testing.
Cache values must not contain request-scoped persistence contexts or mutable
live-view state.

Image optimization is deliberately local and codec-limited. It does not proxy
remote URLs, subset fonts, convert to WebP/AVIF, preserve animation, fingerprint
source content into generated URLs, or provide a shared multi-node transform
cache. A production node computes each parameter tuple once per process and uses
ETag revalidation plus a one-day browser lifetime. Applications that need a
global image CDN, modern-codec negotiation, signed remote sources, or persistent
derived assets should put that service in front of Roots and render its URLs as
ordinary image elements.

Incrementally regenerated static HTML is also process-local after startup. Every
node begins from the same packaged build artifact, but nodes can refresh at
different times and do not broadcast new HTML to peers. The rendering contract is
public and session-free: prerendered routes bypass middleware and cannot carry
authorization policies or personalized session state.

Every action rerenders the Java tree for authoritative state and action-table
correctness. Roots sends only the narrowest element-root component when an exact
before/after proof shows that one boundary fully contains the change. It sends no
DOM fragment for an unchanged tree and falls back to the complete root for changes
outside a boundary, fragments, ambiguity, portals, and queued SSE updates. This
reduces transfer and reconciliation work, but server rendering cost still scales
with the Java tree; very large pages or high-frequency events require measurement.

The JDK HTTP server remains useful for dependency-free applications even though
Java 26 deprecates that API for removal. Roots now also supplies a Jakarta Servlet
6.1 adapter with context-path routing, container I/O, async SSE, shared readiness,
and graceful lifecycle behavior. That removes the runtime's hard dependency on
the JDK listener, but broader container certification remains release work. Roots
provides a transport-neutral authentication-provider SPI, strict bounded Bearer
credential parsing, immutable identities, Servlet principal resolution, an optional
Spring Security name/authority bridge, typed middleware, an instance-factory
SPI, named declarative authorization, readiness, exact admission limits, runtime
counters, graceful draining, bounded multipart uploads, W3C trace propagation,
structured request-completion events, explicit trusted-proxy resolution, and a
pre-session rate-limiter SPI with a bounded node-local implementation. It still
lacks bundled OAuth/OIDC verification and a distributed tracing SDK or exporter.
Credential verification remains application/container-owned. Multi-node rate limits require an application-owned coordinated
implementation.

Application cookies use a strict bounded API across both transports. Roots ignores
malformed request pairs, never silently encodes response values, enforces modern
SameSite/Secure/Partitioned and cookie-prefix relationships, scopes action helpers
to the deployment mount, preserves repeated `Set-Cookie` fields, and discards
cookies from failed live actions. Applications still own consent, retention,
signing/encryption, key rotation, and the decision to expose a cookie to browser
script by disabling HttpOnly.

`roots-spring-boot-starter` makes configuration, dependency injection, and
shutdown ownership conventional in Boot applications. Its JDK transport remains
the compatibility default; `roots.transport=servlet` instead registers Roots at
`/*` in Boot's embedded Servlet server and shares its port and context path. This
translates authenticated Spring Security names and exact authorities into Roots
request/page/action identity without storing credentials or native principals.
Authentication configuration and user storage remain application-owned.

Browsers explicitly dispose superseded views during client navigation and tab
unload, and `RunningApplication.runtimeSnapshot()` exposes live view/session
counts. A daemon maintenance task expires quiet views and asks the configured
session repository to expire quiet sessions even when an
application receives no later traffic; view expiry runs lifecycle cleanup and
wakes an attached SSE stream. Per-node view admission is bounded;
`SessionRepository` and `LiveViewOwnership` are pluggable. The optional
`roots-jdbc` module bundles shared session/value and ownership implementations
with migration-ready schema statements and a non-serializing codec boundary. Boot
applications automatically discover unique beans for both SPIs. Boot applications with Actuator
receive a `roots` health contributor, Micrometer meters for the existing runtime
counters, and a bounded-tag request timer; exporter selection remains
application-owned.

The reactor runs the browser-neutral contract in real headless Chrome, Edge, and Firefox,
covering live actions, revision
advancement, node preservation, optimistic success and failure rollback,
offline/online connection-state transitions, superseded SSE leases, queued-update
catch-up after reconnect,
component-scoped action patches, document-wide optimistic portal targets, native modal focus containment,
Escape/backdrop dismissal and focus return, nested pending-scope isolation, pending
cleanup after success and failure, ordered action and client-navigation
view-transition commits with unsupported and reduced-motion fallback, portal pending boundaries, multipart uploads, portal
mount/reconciliation, cross-root actions and focus, portal cleanup, client
navigation, rapid latest-wins navigation, late old-view action rejection and
unused-view disposal, same-resource and cross-route fragments, action redirects,
back/forward scroll restoration, stylesheet replacement, same-document live 404/500 navigation and
managed description/canonical/robots/theme-color/Open Graph action updates and navigation removal,
status reporting, safe error-page fallback, delegated input/keyboard/pointer actions,
user-versus-framework focus behavior, typed blur and editable-text selection,
polite/assertive permanent ARIA live-region announcements, malformed event
metadata rejection, view
disposal, accessible field-validation rendering and edit cleanup, markup-safe
validation messages, distinct browser validation/error events, markup-safe
development compiler diagnostics, CSP-compatible overlay styling, and successful
development document reload, plus authenticated component/action inspection and
DOM binding discovery.
The same Chrome, Edge, and Firefox suites run axe against the initial live document, server
validation errors, typed browser-effect announcements, a custom live not-found
page, a custom live production-error page, an open Roots native modal, a client-navigation destination, the
chat example, and the enterprise overview and customer pages. It requires no
WCAG 2.2 AA or best-practice violations and no unresolved automated-review
findings. This test-only audit does not certify an application or replace manual
keyboard, zoom, high-contrast, or assistive-technology testing.
Java integration tests also exercise 32
simultaneous actions against one view, prompt SSE shutdown, timeout cleanup,
expired-session rejection, failure isolation during unmount, one-slot
view/session saturation and recovery, concurrent-request saturation and recovery,
graceful in-flight shutdown, 48 parallel actions across six views, conditional
asset caching, and parallel scanner traffic against a one-session limit. This is
supplemented by a dedicated HTTP load module: its default gate executes 608 live
actions across 16 parallel views and a 128-request hot-view contention case, and
an opt-in profile runs configurable user counts for a configurable duration. Both
require exact revisions, authoritative final state, zero admission rejection,
and complete view cleanup. This is regression evidence, not workload-specific
capacity certification, manual accessibility, security, or product-specific
browser certification.

The application-facing core, Servlet, Spring, and Spring Boot APIs now have exact
committed compiled-signature baselines, and every build rejects unreviewed drift
in types, members, generics, visibility, nested types, or annotation contracts.
Browser protocol version 1 is
embedded in live documents, requests, response headers, JSON patches, and SSE
connections. Incompatible actions and streams cause a fresh-document reload,
which prevents an old tab from applying a new server's unknown wire shape during
a rolling deployment. This is a compatibility guard, not yet a final 1.0 API
freeze; real mixed-version deployment certification remains.

Each live view accepts one SSE connection. Keep `maxConcurrentRequests` above
`maxLiveViews`; the configuration enforces this so a full set of streams cannot
consume every action slot.

## Safe pilot rules

For a controlled pilot:

1. deploy one JVM, or configure sticky sessions;
2. terminate TLS at a trusted reverse proxy;
3. keep authentication and named policy implementations in an audited application layer;
4. keep database transactions scoped to a request or action;
5. do not store non-thread-safe persistence contexts in components;
6. set memory limits and observe live-view count, heap use, action latency, and SSE connections;
7. configure only the actual reverse-proxy CIDRs and verify forwarded-header sanitization;
8. enable a suitable node-local or distributed rate limiter for exposed deployments;
9. load test the real page shapes and user concurrency before launch;
10. place request spooling on a private, capacity-limited temporary volume and
    monitor its free space when accepting large uploads;
11. treat framework upgrades as potentially breaking until 1.0.

## Release gates

General production readiness requires:

- production-database and owner-aware proxy certification for the JDBC state
  adapters (shared sessions, cache, ownership leases, node identity, and the
  session-safe wrong-node contract are implemented and H2 concurrency-tested);
- node-failover semantics beyond the implemented per-view SSE replacement,
  reconnect catch-up snapshot, and per-node graceful drain;
- certification across additional Servlet containers and/or another transport
  such as Netty (Boot Servlet path ownership is configurable);
- additional packaged OAuth/OIDC authentication integrations beyond the Core
  provider/Bearer boundary, Servlet principals, and Spring Security bridge;
- production exporter configuration, full distributed spans, broader structured
  application logging, and administrative visibility (request trace propagation,
  safe completion JSON, and Boot metrics are implemented);
- owner-aware proxy integration and production database certification (shared
  JDBC sessions, values, cache entries/tags/load leases, view leases, and
  ownership cleanup are implemented, but
  external state alone does not remove node affinity);
- additional render-cost measurement and subtree-evaluation strategies (exact
  component-scoped transfer/reconciliation is implemented);
- workload-specific capacity/load certification, Safari/WebKit, manual
  accessibility, and security suites beyond the implemented generic load/soak,
  reconnect, and automated WCAG audit coverage;
- repository publication and signed releases (source and strict-doclint Javadoc
  artifacts are already produced); the compatibility policy and core API/protocol
  enforcement and adapter baselines are implemented, while the final 1.0 freeze remains.

These items are tracked in the [roadmap](roadmap.md).
