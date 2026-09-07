# What Roots is

Roots is a server-rendered Java web framework with retained component instances,
typed server actions, convention-based routing, and a small browser integration
layer. It targets connected business interfaces maintained by Java teams.

The canonical Java namespace and Maven group ID are `com.chaplin.roots`.
`roots-core` uses only the JDK at runtime. Optional adapters add integration with
JDBC, Jakarta Servlet, Spring, and Spring Boot. Applications supply their own
domain model, authentication provider, persistence, styles, and infrastructure.

The current development version is `0.1.0-SNAPSHOT`. Implemented behavior is
defined by the [feature contract](feature-contract.md), [protocol](protocol.md),
Java API signature baselines, and tests. A pre-release is not a stable API freeze,
an independent security audit, or a claim of production certification.

## Execution model

1. A route resolves to a Java page and its enclosing layouts. Pages implement
   `Page`; reusable UI implements `Component`. Their render methods return HTML
   `Node` trees made with the `Html` builders.
2. A dynamic page creates a live view on one JVM. Component identity determines
   which Java instances survive subsequent renders. Keys also guide DOM identity.
3. Roots sends HTML and its packaged browser driver. The driver attaches event
   handlers; it does not hydrate a separate application component tree.
4. A browser event captures its form values and originating view credentials.
   The server validates transport, identity, authorization, and CSRF rules, then
   serializes action execution within that view.
5. Application code changes view state and, where necessary, commits business
   transactions. Roots renders the resulting tree and returns revisioned HTML.
   Proven component boundaries can narrow the transferred patch; Java evaluation
   is not guaranteed to be limited to that subtree.
6. The browser reconciles the update, preserving keyed DOM and unacknowledged
   edits. Server-initiated updates use Server-Sent Events. View identity and
   revisions prevent late responses from changing a newer page.

Static/prerendered public routes and stateless API routes are separate contracts;
they need not allocate a live UI view. See [prerendering](guide.md#static-pages-and-incremental-regeneration)
and [machine APIs](automation.md).

## State and lifetime

| State | Owner | Lifetime and recovery |
|---|---|---|
| DOM and unsaved typing | Browser | Protected during supported reconciliation; not durable storage |
| Page/component fields and `State<T>` | One server live view | Expires with the view or JVM; not automatically serializable |
| Authentication identity | Application or container | Verified by an authentication integration; bound to live access |
| Session values | Configured session repository | In memory by default; optional JDBC persistence |
| Cache entries | Configured cache | Bounded and expiring; never the source of truth |
| Business records, operation receipts, saved drafts | Application database | Durable according to the application's transaction and backup design |

Sharing a session database does not move Java component objects between nodes.
Load balancing must route each live view to its owner. Restarts and deployments
can require a fresh view and sign-in; recovering work requires application-owned
saved state. Backups and schema migrations also remain application concerns.

## Failure and security boundaries

Output is escaped by default. Roots provides a configurable CSP, same-origin
action checks, session-bound CSRF protection, request limits, view-bound action
registrations, and authorization policy hooks. These are mechanisms, not an
authentication product or a substitute for application authorization.

Validate every submitted value, including hidden fields and widget props. An
authorized action can still contain invalid business input. Keep database writes
and their durable receipts/activity entries in transactions when correctness
depends on both being committed.

A network timeout or lost response cannot roll back an action that already
committed. Roots distinguishes uncertain outcomes and does not silently retry
business mutations. Applications choose idempotency keys, reconciliation, and
recovery UX appropriate to the operation.

Widgets are same-origin JavaScript modules with page privileges. Their DOM
ownership and lifecycle contract is not a security sandbox. The standard UI
requires JavaScript and an active server connection; Roots' server component model
does not provide offline execution.

## Module boundaries

| Module | Responsibility |
|---|---|
| `roots-core` | Runtime, components, HTML builders, routing, actions, browser driver, built-in HTTP server |
| `roots-processor` | Compile-time discovery and validation; generated route manifests |
| `roots-dev` | Development compilation/restart, reload, and diagnostics |
| `roots-maven-plugin` | Maven integration, including prerendering |
| `roots-archetype` | New application scaffolding |
| `roots-servlet` | Jakarta Servlet transport integration |
| `roots-jdbc` | Optional shared session/cache/ownership storage and transactional receipts |
| `roots-spring` | Spring-managed instance integration |
| `roots-spring-boot-starter` | Boot auto-configuration, transport selection, and health integration |
| `roots-browser-tests`, `roots-load-tests` | Verification tooling; not application runtime dependencies |
| `examples/`, `deploy/` | Reference applications and deployment recipes; not the framework core |

Published library candidates exclude the examples and test harnesses as Maven
artifacts. Source bundles retain them so users can inspect and reproduce the
contracts. Only the packages listed in [compatibility](compatibility.md) are
supported public API; `.internal` packages and private browser-driver functions
are implementation details.

## Intended fit and non-goals

Use a controlled pilot to evaluate forms, operational dashboards, approval flows,
and administrative tools where a server round trip per action is acceptable.
Measure representative view counts, database work, rendered tree sizes, and
network conditions before choosing capacity.

Roots does not aim to reproduce React hooks, ship an npm component ecosystem,
provide a database/ORM, manage users, replace a distributed job scheduler, or
offer transparent multi-node UI failover. Browser-heavy editors can use widgets,
but integrating an editor does not make its local state automatically durable.

There is no blanket claim of parity with React, Next.js, Vaadin, or other
frameworks. Comparisons explain individual capabilities; the execution and
operational tradeoffs remain different. See [production readiness](production-readiness.md)
for adoption limits and [roadmap](roadmap.md) for unresolved release gates.
