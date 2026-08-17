# Architecture

Roots is server-driven, not a JVM implementation inside the browser.

## Route discovery

The optional `roots-processor` runs in `javac` for an `@RootsApplication` or
Spring Boot `@EnableRoots` anchor. It applies the same public `RoutePaths`
normalization as the runtime, diagnoses invalid and ambiguous routes, resolves
nested layouts, and emits one versioned manifest resource named for that anchor.
Rows are deterministically ordered so clean builds are reproducible.

At runtime `ConventionRouter` loads only the current anchor's resource, validates
its version, size, row count, canonical paths, and referenced class contracts,
then revalidates registered authorization policies. A missing manifest or a
configured package override uses the legacy classpath scanner. Multiple or
malformed matching resources are rejected rather than silently changing the
route table. Anchor-specific names allow unrelated application manifests to
coexist in dependency and shaded JARs.

## Render model

Each page load creates a live view containing one page instance, its layout instances, a typed context, the last action table, metadata, and a monotonically increasing revision. Stateful child components are retained when the page keeps their instances in fields.

Rendering recursively evaluates Java `Node` and `Component` objects into escaped HTML. Event bindings become opaque action names in `data-roots-on-*` attributes. The browser never receives reflected method names as authority: an action also requires the live-view ID, a high-entropy CSRF token, and the matching `HttpOnly` session cookie.

## Rerender model

Browser actions are serialized per tab. The server invokes the Java handler while holding the live-view mutation lock and rebuilds the tree. Element-root component occurrences receive deterministic renderer-owned boundaries. Roots compares the previous and next complete HTML and emits the narrowest component region only when replacing that one old region with the new region exactly explains the entire document change. Otherwise it emits the complete root fragment. Fragment-root components, ambiguous structural changes, any render containing portals, and queued SSE updates conservatively use the complete root. An unchanged tree carries no DOM fragment.

Each component patch includes the revision from which it was computed. The browser applies it only when that base matches its current revision and the expected boundary exists with one valid replacement element; a mismatch reloads the document. The browser runtime parses the fragment and morphs the existing DOM. Stable `data-roots-key` values preserve identity during insertions and reordering. Focus and form properties are restored after reconciliation.

Delegated actions support a fixed browser-event capability set rather than an
unbounded attribute name that might never be observed. The driver snapshots
keyboard, modifier, and pointer metadata when the event occurs. Underscore-prefixed
protocol fields are removed from application form values, parsed into immutable
`BrowserEvent` data, bounded, and rejected on malformed or unsupported input.
Programmatic focus performed by reconciliation or a typed server effect is guarded
from the focus-action listener to prevent a server-action feedback loop.

An action element may also carry a bounded JSON declaration of typed optimistic
effects. After form capture and before transport, the driver can hide a referenced
node, replace its text or form value, or disable a control. It records inverse
operations and rolls them back on an HTTP, transport, or patch failure. A successful
server patch is authoritative. The target lookup is document-wide so refs inside
portals follow the same contract, and no application-authored script is evaluated.

A typed view-transition effect marks the exact action patch that should be
committed through the browser View Transition API. The render queue awaits the
API's update callback before the per-view action queue advances, so animation
cannot reorder revisions. Unsupported browsers and reduced-motion users take the
same synchronous morphing path. Transition CSS remains ordinary application CSS;
Roots does not inject inline styles or executable application code.
Anchors marked by `Element.viewTransition()` use the same commit primitive for
client navigation after the destination document has been fetched and parsed.
A `404` or `500` response containing a valid protocol-compatible Roots root is
also a committable document, enabling the root `pages.NotFound` and production
`pages.ErrorPage` conventions without a full reload. Other unsuccessful or
non-Roots navigation responses retain the hard-navigation fallback. The
`roots:navigate` event reports the committed HTTP status in `detail.status`.
Navigations are sequenced latest-wins. A late older response cannot replace a
newer destination; the driver disposes the unused view created by that response.
Every action, redirect, reconnect snapshot, and SSE patch also names its originating
live view. Once navigation changes the active view, a late old-view payload is
ignored before effects, redirects, revisions, or DOM work. Both rejection paths
emit `roots:stale` for diagnostics without turning expected races into errors.
The action queue captures its root and view when the browser event occurs. It
therefore rejects queued work before transport and rejects every late HTTP outcome,
including validation and failure responses, before it can reload or publish UI
state against a newer document.
Same-resource fragments remain native browser navigations and therefore reuse the
current view. Cross-route fragments are resolved after the new root commits.
Roots uses manual history scroll restoration for its push/pop entries, preserving
the current entry before departure and restoring exact finite coordinates on
back/forward traversal. Browser-owned cross-route text fragments use a hard
navigation. Internal action redirects enter this same client-navigation pipeline.

An element marked with `pendingScope()` is a Java-declared pending boundary. The
driver selects the closest marked ancestor of the invoked action and temporarily
sets `data-roots-pending="true"` plus `aria-busy="true"`. Nested scopes isolate
unrelated regions. Cleanup restores the exact preceding attributes after both
successful and failed actions; disconnected scopes need no cleanup. A global
document pending hook and root busy state remain available for application-wide
progress styling.

Forms without selected files use URL-encoded action transport. When a file is
selected, the browser runtime sends native `FormData`; the server's JDK-only
multipart parser preserves file bytes and repeated field names. The transport
streams the body under the exact `maxRequestBytes` cap into repeatable
`BinaryContent`: it stays in heap through `RequestBodyPolicy.memoryThreshold`
and then spills to a request-scoped temporary file. Multipart scanning operates
on that backing content without cloning file parts; each `UploadedFile` is a
repeatable bounded slice. Non-file parts have an independent decoded-byte cap.
The request pipeline deterministically closes and deletes temporary content after
the response is written, including malformed input and mapped failures.

Structured `ValidationException` responses take a separate expected-failure path.
`ActionEvent.bind` and `Request.bind` use a classloader-safe `ClassValue` cache to
compile record constructors, typed converters, field mappings, and built-in or
application-defined constraint annotations once per record type. Binding rejects
ambiguous repeated scalar values and aggregates conversion and constraint failures
into the same bounded `ValidationException` contract.
The runtime validates their bounded shape, rolls back optimistic effects, binds
field messages to same-name controls, and preserves prior ARIA and message-node
state for exact cleanup. Message content is assigned through text nodes rather
than interpreted as markup. Form and body-level portal scopes use the same
binding contract.

Application page, API, action, and authenticated disposal requests pass through
configured `Middleware` in declaration order. Public assets, the Roots browser
runtime, readiness health, and unmatched paths bypass middleware and session
allocation.
For a validated action or disposal request, `Request.path()` is the originating
page route while `Request.transportPath()` remains the internal protocol URL.
This makes a route guard apply to both the initial render and later live actions.

Declarative authorization is a named-policy layer above that transport pipeline.
`@Authorize` metadata is collected from layouts, pages, API routes, and annotated
server actions. Route policies are validated during discovery and action policies
when their binding is rendered. A live action evaluates its page/layout policies
and then its method policies while holding the view mutation lock, before calling
application code. Its logical route parameters and original page query are restored
on the protocol request. SSE evaluates page/layout policies at connection time and
again before heartbeats or patches, so revoked access closes the stream.

Patches from background work travel over Server-Sent Events. `AsyncComponent` starts work on a virtual thread, renders its fallback immediately, and calls `PageContext.update` on completion. The revision check prevents a slower response from overwriting newer state.

## Data cache

One configured `RootsCache` is shared by page contexts, API requests, and the
`RunningApplication` handle. The default implementation is a bounded,
access-ordered in-memory cache. It coalesces concurrent loads by key, expires
values against a monotonic clock, and supports key or tag invalidation. An
invalidation generation prevents a load that began before revalidation from
repopulating stale data after it finishes.

The public interface is replaceable, and the default store and its counters are
node-local. The optional `JdbcRootsCache` stores entries and tag indexes in shared
tables. A metadata-row transaction serializes the short claim/publication boundary,
while a per-key lease keeps expensive application loading outside the transaction.
Every invalidation advances a generation and removes matching active leases, so a
loader that began earlier may return to its caller but cannot repopulate stale
shared data. Publication enforces a shared entry cap and deterministic oldest-entry
eviction. Adapter counters are process-local; its entry count is shared. Live-view
affinity remains separate from this data-cache contract.

## Lifecycle

`Component.onMount` runs the first time a component identity appears in a live tree. `onUnmount` runs when it disappears or the view expires. Page and layout lifecycle callbacks live for the entire view. A component freshly allocated inside every `render` is a new identity; keep lifecycle-aware or stateful components in fields.

Portal children are rendered and lifecycle-reconciled as part of the same Java
tree. Their inert `<template>` transport is extracted after each browser patch
and reconciled into a stable body-level host. Removing a portal from the Java
tree removes that host; navigation also replaces or clears hosts according to the
new page. Nested portals are rejected because each portal is already document-level.

`Modal` is a constrained portal specialization. The Java renderer emits one
labeled native `dialog`, registers the same server action for explicit controls,
Escape, and optional backdrop dismissal, and reserves its structural attributes.
After portal reconciliation the browser promotes a new dialog with `showModal()`,
suppresses application focus events caused by framework focus movement, selects
the requested `Ref` or a bounded focusable fallback, and contains Tab movement in
the topmost modal. Native `cancel` is prevented until the action succeeds and the
next Java render removes the modal. Removal closes the native dialog and restores
the still-connected opener; rejection, transport failure, or validation leaves
the modal and authoritative state intact.

## Browser boundary

The built-in browser runtime owns:

- delegated event capture;
- typed keyboard, modifier, and pointer event transport;
- action transport;
- HTML parsing and keyed reconciliation;
- exact component-boundary patching with revision and shape validation;
- latest-wins history navigation with unused-view disposal;
- same-resource/cross-route fragment handling and back/forward scroll restoration;
- originating-view validation for action, redirect, reconnect, and SSE payloads;
- metadata stylesheet reconciliation plus managed canonical, robots, theme-color,
  description, and Open Graph replacement/removal across action renders,
  reconnects, and navigation; extended metadata inherits field by field from
  outer layouts through inner layouts to the page before annotation overrides;
- SSE background patches, observable connection state, and reconnect catch-up snapshots;
- typed focus, blur, editable-text selection, scroll, clipboard, and polite or
  assertive accessibility-announcement effects;
- permanent framework-owned ARIA live regions and same-origin CSP-compatible
  visually-hidden styling;
- reversible typed optimistic action effects;
- progressive, reduced-motion-aware action and navigation view-transition commits;
- nested pending-scope state and accessibility attributes;
- accessible field-validation binding and exact edit cleanup;
- portal host mounting, reconciliation, and cleanup;
- native modal promotion, topmost focus containment, Escape/backdrop action
  dispatch, and focus restoration;
- a development-only SSE connection that reloads after successful compilation
  or renders bounded compiler output with DOM `textContent`.

Successful client navigation and tab unload send an authenticated disposal
request for the previous view. Timeout cleanup remains the fallback for crashed
or disconnected clients that cannot send it. A daemon maintenance task performs
that cleanup independently of request volume. Expiry checks synchronize with
view mutation so an in-flight action cannot be removed from a stale timestamp.

Each authenticated SSE attachment owns a replaceable lease. A new connection
atomically supersedes and interrupts the previous stream, which avoids waiting
for a heartbeat to discover a dead socket. Every attachment begins with a full,
revisioned snapshot: an initial browser ignores the same revision, while a
reconnecting browser catches up even when the last queued write raced with the
disconnect. The root's `data-roots-connection` value and `roots:connection`
browser event expose `connecting`, `connected`, and `reconnecting` transitions.
The browser also gives every `EventSource` instance an ownership guard. Late
open/error/reload/patch callbacks from a closed or replaced stream cannot alter
the active root's connection state or reload a newer document. Development
streams and asynchronous inspector reads apply the same current-owner check.

Application code does not import browser APIs or author JavaScript. This is the same kind of runtime boundary used by LiveView-style systems. Compiling arbitrary Java application code to WebAssembly is not a goal of the first architecture because it would reintroduce a second state model and a large client runtime.

The browser runtime is a Roots-owned vanilla JavaScript implementation embedded in
`ClientRuntime.java`. It has no third-party client dependency.

## Development restart boundary

`roots-dev` is a separate tooling artifact; `roots-core` remains JDK-only. The
runner performs a clean Maven compile into `target/classes`, copies the result to
an immutable temporary generation, and loads the application's package child-first
while delegating Roots and dependency types to the parent. The active server reads
only its immutable generation, so compilation cannot partially replace running
code or leave deleted classes/resources behind. On success, Roots drains the old
server, binds the new generation to the same port, and publishes an application-keyed
reload event. If startup fails, it restores the previous snapshot before publishing
the diagnostic.

The `/_roots/development` channel exists only in development mode, allocates no
session, and is keyed by the binary application class name to isolate multiple
apps in one JVM. Diagnostics are JSON escaped, bounded to 32 KiB, and inserted by
the client with `textContent`. Its stylesheet is a same-origin asset so the default
Content Security Policy remains effective. This is full application restart, not
method-body instrumentation: live component state is recreated, and POM or
dependency changes require restarting the runner.

The development-only `/_roots/inspect` endpoint is different: it requires the
existing session plus the live view's CSRF capability and reapplies route
authorization. Its browser panel reports the current route/revision, page and
layout classes, distinct rendered component identities and occurrence counts,
action target methods and policies, and the actual DOM event/tag locations using
each wire binding. The panel constructs every label with `textContent` and the
endpoint is absent in production mode.

In production mode the browser runtime and successfully resolved classpath assets
have SHA-256 entity tags. `If-None-Match` supports strong, weak, list, and wildcard
matches and returns `304` without a body. Production asset bytes are retained in a
concurrent in-memory cache; missing paths are never negatively cached. Development
mode reloads public assets on each request and marks them `no-store`.

Optimized images are a separate session-free public path. `OptimizedImage` emits
ordinary `img` markup while `ImageOptimizer` validates a local `public/` PNG or
JPEG, reads dimensions before decoding, enforces byte/pixel/output limits, and
uses only JDK Image I/O plus Java2D for bicubic resizing. Production transforms
are serialized through a 256-entry access-ordered cache, receive content-derived
ETags and one-day browser caching, and never upscale; development re-reads and
recomputes. The endpoint cannot fetch a network URL, which removes an SSRF class
from this pipeline.

`WebFont` metadata produces one same-origin generated stylesheet per declaration.
The stylesheet endpoint reconstructs and validates the declaration from bounded
query fields, verifies that its WOFF2/WOFF/TTF/OTF source exists under `public/`,
and uses a relative CSS URL so Servlet mounts remain correct. Font stylesheets and
optional preload links participate in client-navigation head reconciliation.
Actual font bytes continue through the normal public-asset path.

Before an HTTP response is written, the server identifies `text/html` content and
replaces any response-local Content Security Policy with the validated global
policy from `RootsConfig`. This single boundary covers framework documents,
application HTML route responses, mapped errors, and static HTML. Non-HTML APIs
and assets do not receive an irrelevant CSP header.

`@Prerender` pages form a separate, explicitly public rendering path. The Maven
plugin loads compiled application classes, uses the same convention router and
document renderer as runtime startup, and writes an anchor-specific versioned
manifest plus hashed HTML resources. Production loads those resources before any
session allocation; absent output is rendered once during startup. Generated HTML
has no client runtime or live-view credentials. ETags, `HEAD`, and conditional
requests are served directly. Positive revalidation intervals use immutable last-
good content, a per-entry compare-and-set regeneration gate, virtual threads, and
bounded failure retry. Shutdown interrupts and joins the regeneration executor.

Because this path bypasses middleware and sessions, discovery rejects authorization
policies and rendering rejects actions, session mutation, portals, and asynchronous
components. These checks make public/static semantics fail closed at build or
startup rather than producing a partially interactive document.

Selenium exists only in the separate `roots-browser-tests` verification module.
It drives Chrome, Edge, and Firefox to test the owned browser runtime and is not packaged into
`roots-core` or applications.
HTTP concurrency and sustained traffic fixtures live in the test-only
`roots-load-tests` module; they are not packaged with applications.

## HTTP transport boundary

Routing, sessions, middleware, rendering, actions, assets, health, admission,
draining, and SSE operate on a small internal `TransportExchange` contract. The
built-in listener adapts JDK `HttpExchange`; the separately packaged
`roots-servlet` module adapts Jakarta Servlet 6.1 requests and responses. This
keeps Servlet classes and container dependencies out of `roots-core` while both
transports execute the same runtime code.

The same boundary frames buffered and lazy application responses. `Response`
holds either immutable bytes or a synchronous `ResponseBodyWriter`; copying
headers never evaluates the writer. Known lengths use fixed response framing and
an exact-count output guard, unknown lengths use transport streaming framing,
and no-body statuses plus `HEAD` never invoke application body production. File
responses read metadata at construction and open the file only during transfer.
The application writer owns source resources, while the transport owns and
closes the network output. If production fails after headers are committed, the
only safe result is a truncated/closed response; exception mapping cannot replace
an already-started body.

API method capability is derived once per concrete route class and cached through
`ClassValue`, so development classloader generations remain collectible. The
canonical method order drives both automatic `OPTIONS` and every default `405`
`Allow` header. A GET override implies the interface's body-free HEAD behavior;
an independently overridden HEAD does not imply GET. CORS remains a route or
middleware policy rather than an origin-reflecting transport default.

`RootsServlet` owns a listener-independent `RootsServer` lifecycle. Ordinary
requests execute on their container thread. Live and development SSE endpoints
enter a zero-timeout Servlet async context and execute on a Java virtual thread,
so the container thread returns after dispatch. Servlet destruction lowers
readiness, waits up to its configured drain timeout, closes remaining exchanges,
completes async contexts, expires views, and closes the session repository.

The adapter strips the web application context and servlet mapping through the
Servlet path contract before route matching. A Roots app can therefore run below
paths such as `/company/roots` without changing its Java route packages. The
transport supplies that external mount separately from the logical route.
`Request.mountPath()` and `PageContext.mountPath()` expose it, while
`PageContext.url(...)` resolves application paths explicitly. Generated HTML
automatically rebases root-relative `href`, `src`, `action`, `formaction`, and
`poster` attributes, metadata stylesheets, framework protocol endpoints, client
redirects, and prerendered HTML/ETags. Session cookies are scoped to the mount.
This lets a Boot application map Roots at `/roots/*` while Spring MVC owns other
paths.

Application cookies cross the same transport boundary as immutable validated
name/value pairs. Initial page rendering receives the page request; each action
atomically replaces the retained `PageContext` snapshot before invoking Java and
rerendering. API/middleware responses append independent `Set-Cookie` fields, and
live actions carry a bounded response-cookie list that is attached only after a
successful action and authoritative render. The cookie model never enters the
browser JSON protocol: the browser processes ordinary HTTP response headers.
`cookiePath()` derives `/` or the combined context/servlet mount so an application
does not accidentally escape its deployment path.

`AuthenticationProvider` runs after bounded body/session resolution and before
middleware, routing, or authorization for every session-bearing request. Its
default preserves the adapter-provided identity; its strict Bearer adapter performs
bounded credential syntax screening before application verification. Servlet
identity resolution still occurs before async handoff. A provider result becomes
an `AuthenticatedIdentity`: an immutable principal name plus exact authority
strings exposed through `Request`, `PageContext`, and `ActionEvent`. Live views
bind to the snapshot used at construction. A change in name or authority set closes
the view before action mutation, SSE attachment, or development inspection. Native
principals, credentials, and Spring Security objects never enter retained component
graphs.

Trace propagation is part of the same transport-neutral boundary. An observed
exchange validates an incoming W3C `traceparent`, creates a fresh server span,
and supplies one immutable `TraceContext` to middleware, routes, page renders,
and live actions. The logical application path remains separate from the physical
action or stream endpoint. A response `traceparent` supports direct correlation,
and exactly one `RequestObservation` is emitted when the response body or stream
closes. Completion events omit all request values and credentials by construction.
Observer failures are isolated from the HTTP result and from later observers.

Network identity is resolved at that same boundary. Each adapter supplies its
direct numeric peer, HTTP(S) scheme, and authority as `ClientConnection`.
`ProxyPolicy.directOnly()` is the default; `ProxyPolicy.trusted(...)` accepts
forwarding metadata only when the direct peer belongs to an explicit IPv4 or
IPv6 CIDR and selects the first untrusted hop while walking toward the client.
The resolved value flows into requests, page renders, and actions without
retaining a container request object. The configured `RateLimiter` evaluates
that value before reading a body or allocating a session. Health remains
available independently of client quotas; bounded decisions are reflected in
standard response headers and runtime rejection counters.

The core intentionally has no logging, Micrometer, or OpenTelemetry dependency.
Its JDK JSON-lines observer is optional. Spring Boot discovers ordered observer
beans and contributes a bounded-tag Micrometer timer when a registry exists;
application code can bridge the same event into another telemetry SDK.

## Current scaling model

Live views are process-local. `SessionRepository` is a public atomic lifecycle
SPI with a bounded in-memory default; an external implementation can return a
`Session` subclass that delegates values to durable storage. Repository failures
can explicitly signal availability loss and receive a session-free `503`.

External sessions do not make live views portable. One JVM or sticky routing is
still required because ordinary component graphs may hold services that should
never be serialized. `LiveViewOwnership` is the public lease/affinity boundary:
it maps each view and session to an owning node, is renewed through live activity,
and is released through every view cleanup path. The bounded default is
process-local. A shared implementation lets a proxy identify wrong-node traffic,
but deliberately does not serialize or migrate Java object graphs. Session-safe
wrong-node responses expose `X-Roots-Owner`; other sessions receive only the
ordinary expired-view response.

The optional `roots-jdbc` module implements both external boundaries. Its session
repository uses a database mutex for exact cross-node capacity and a delegated,
non-serializing value codec; its ownership implementation uses portable
conditional SQL, a primary-key collision retry, and epoch-millisecond leases. It
accepts an application-owned `DataSource` and adds no database driver, pool,
Spring, or ORM runtime dependency. Database-specific deployment certification
remains the application's responsibility.

Pages, layouts, and API routes are created through `InstanceFactory`. Reflection
with a no-argument constructor is the default. Roots calls the factory's matching
destruction hook when an API request or live-view scope ends, including failure
and shutdown paths. The separate `roots-spring` module bridges this ownership to
Spring bean initialization and destruction without changing the zero-dependency
core. `roots-spring-boot-starter` adds typed external configuration. Its default
JDK listener uses Spring's highest-phase `SmartLifecycle`; opt-in Servlet mode is
owned by Boot's container lifecycle and shares Boot's port and context path. Both
drain Roots before destroying application state. Optional, independently
conditional Boot auto-configurations translate Spring Security request
authentication into Roots identity data and the same runtime snapshot into a
`roots` health contributor and Micrometer meters without
adding dependencies to `roots-core`. `RunningApplication.runtimeSnapshot()` exposes current request,
rejection, concurrency, view, session, and configured-capacity counts for
lightweight diagnostics.

Live-view ownership is guarded by fair JVM-local permits; session capacity is
enforced atomically by the configured repository. A view permit is released
exactly once on explicit disposal, timeout, failed construction, or shutdown. A
matching external ownership lease is released on the same paths and stale-session
cleanup cannot release a replacement lease. A
separate admission permit bounds all executing HTTP exchanges,
including long-lived SSE streams, and is released in the handler's `finally`
path. Public and invalid protocol traffic bypasses session allocation, while
`beginDrain()` marks readiness down before `closeGracefully(Duration)` stops the
listener and waits for accepted exchanges.

Each live view permits only one attached SSE stream. The request limit must be
higher than the live-view limit, reserving at least one handler for actions even
when every admitted view has an active stream.

See [Production readiness](production-readiness.md) for the practical deployment
boundary and [Integrations](integrations.md) for persistence and Spring guidance.
