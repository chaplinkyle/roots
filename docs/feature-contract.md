# Implemented feature contracts

This inventory defines individual capabilities in the current source. Comparisons to familiar frontend concepts explain terminology; they do not promise API equivalence, browser-local execution, or complete parity with another framework. Read the [project definition](definition.md) for scope and ownership boundaries.

## Component semantics

| Capability | Roots equivalent | Status |
|---|---|---|
| Function/class components | `Component` | Implemented |
| Props | constructor arguments and records | Implemented |
| Children/composition | any `Node` accepted as a child | Implemented |
| Local state | retained fields and `State<T>` | Implemented |
| Events | lambdas or `@ServerAction` + typed `BrowserEvent` | Implemented for click/double-click, submit, change/input, key down/up, focus/blur, and pointer down/up |
| Input scheduling | `Element.debounceInput(Duration)` | Opt-in quiet-period coalescing, flush-before-action ordering, navigation cancellation, and a bounded 128-action queue with observable saturation |
| Form edit preservation | event-time snapshots and control edit versions | Newer typing, unrelated drafts, selections, and uploads survive action/SSE patches; successful actions acknowledge only their captured edits |
| Conditional rendering | Java `if`, `switch`, ternary | Implemented |
| Lists | streams, collections, `Iterable` children | Implemented |
| Keys | `Element.key(...)` | Implemented |
| Context | `ContextKey<T>` via `PageContext` | Implemented |
| Memoization | `Memo<T>` | Implemented |
| Mount/unmount effects | component lifecycle methods | Implemented on server |
| Error boundaries | `ErrorBoundary` / `Html.boundary` | Implemented |
| Suspense-style fallback | `AsyncComponent<T>` + SSE | Implemented |
| Refs | `Ref` and action effects | Implemented for focus/blur/editable-text selection/scroll/clipboard |
| Concurrent stale-result protection | origin-bound action queue + revisions + view-bound payloads + latest-wins navigation | Implemented across success, validation, failure, redirect, SSE, and reconnect outcomes, including pre-transport rejection of queued old-view actions, disposal of unused navigation views, and observable rejection of late work |
| Virtual DOM reconciliation | keyed DOM morphing | Implemented as HTML reconciliation with exact narrowest component patches, no-DOM updates, base-revision validation, and conservative full-root fallback |
| Portals and modals | `Portal`, `Modal`, `Html.portal`, and `Html.modal` | Implemented with stable body hosts, native top-layer dialogs, mandatory visible labeling, explicit/fallback initial focus, topmost Tab/Shift+Tab containment, server-authoritative Escape and configurable backdrop dismissal, pending state, focus return after removal, reconciliation, cleanup, and Chrome/Edge/Firefox plus axe coverage |
| Browser widgets | `Element.widget(key, moduleUrl)` | Same-origin ES modules own host descendants; keyed preservation, async latest-props updates, 30-second deadlines, abort/destroy cleanup, fallback/error events, dirty-editor recovery guard, and native form bridge; [contract and runnable example](browser-widgets.md) |
| Optimistic state / transitions | typed reversible effects + pending scopes + action/link view transitions | Implemented with hide/text/value/disable rollback, nearest scoped pending boundaries, ordered `ActionEvent.viewTransition()` patches, opt-in `Element.viewTransition()` navigation, and unsupported/reduced-motion fallback |
| Form validation | record binding + constraint annotations + validation message/summary elements | Implemented with cached typed text/upload conversion, extensible `@FormConstraint` validators, bounded aggregated errors, accessible browser binding, safe text rendering, edit cleanup, and a distinct browser event |
| Browser effects | bounded typed capability API | Implemented for focus, blur, editable-text selection, scroll, clipboard, polite/assertive ARIA announcements, and view transitions; arbitrary script execution is intentionally unsupported |
| Offline/local-first state | client-side Java/Wasm island | Research; not part of server model |

There is no hydration step: the server remains authoritative and the runtime attaches delegated events once. There is no rules-of-hooks concept because state and lifecycle belong to ordinary Java object identity.

## Routing, transport, and application integration

| Capability | Roots equivalent | Status |
|---|---|---|
| App entry | `Roots.run` | Implemented |
| File-system pages | package/source conventions | Implemented |
| Nested layouts | `Layout.java` hierarchy | Implemented |
| Dynamic/catch-all routes | `$id` / `$$path` packages | Implemented |
| Route overrides | `@Route` | Implemented |
| Metadata | `@PageMetadata`, page/layout `headMetadata`, and page `metadata` | Implemented for title/description, styles/fonts, canonical, robots, theme color, and field-wise Open Graph inheritance with deterministic nested-layout/page precedence, URL validation, escaping, mount rebasing, prerendering, action updates, and navigation replacement/removal |
| Server rendering | Java HTML tree | Implemented |
| Server actions | `@ServerAction` | Implemented |
| Route handlers | `ApiRoute` | Implemented for GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS with override-derived `Allow`, automatic body-free HEAD, and route-specific 405 responses |
| Streaming API/download responses | `Response.stream` / `Response.file` | Implemented with lazy fixed-length and transport-framed bodies, exact byte-count enforcement, body-free HEAD delegation, and JDK/Servlet coverage |
| Multipart forms and files | `UploadedFile` on `Request` / `ActionEvent` | Implemented with exact total/text-part limits, thresholded temp-file spooling, repeatable zero-copy file slices, streaming/transfer APIs, and deterministic cleanup |
| Request/response cookies | `Request`, `PageContext`, `ActionEvent`, and `ResponseCookie` | Implemented with tolerant strict-pair parsing, immutable live-request snapshots, bounded literal values, safe builder defaults, SameSite/Secure/Partitioned and prefix invariants, repeated response fields, mount-aware action deletion, success-only action commits, and JDK/Servlet plus Chrome/Edge/Firefox coverage |
| Static assets | `resources/public`, production ETags/304 and byte cache | Implemented |
| Client navigation | Roots links + History API | Implemented with latest-wins sequencing, client-side action redirects, same-resource fragment reuse, cross-route fragment scrolling, manual back/forward scroll restoration, and unused-view disposal |
| Streaming updates | SSE live patches | Implemented with replaceable per-view stream leases, callback ownership guards, offline/online recovery, revisioned reconnect snapshots, and observable browser connection state |
| Loading/error UI | async fallback, error boundary, and root `pages.ErrorPage` | Implemented with component boundaries plus a safe layout-independent production 500 page supporting metadata/state/actions, trace references, same-document navigation, guarded built-in fallback, and unchanged API/development diagnostics |
| Not-found UI | root `pages.NotFound` convention | Implemented as an optional layout-wrapped, stateful live Java page with real 404/HEAD status, same-document client navigation, server actions, compile-time manifest discovery, and session-free API/asset miss isolation |
| Exception and validation responses | typed mappers + record schemas + bounded `ValidationException` field errors | Implemented with declarative conversion/constraints and browser-side accessible binding |
| New-project CLI | Maven archetype | Implemented |
| Development hot reload | `roots-dev` clean-compile watcher, isolated classloader restart, browser reload/error overlay | Implemented for conventional Maven source/resource changes; dependency/POM changes require restarting the runner |
| Development inspector | authenticated live route, revision, page/layout, component identity, action/policy, and DOM-event view | Implemented in development mode |
| Authentication, middleware, and guards | `AuthenticationProvider`, typed pipeline, and named `@Authorize` policies for pages, layouts, APIs, actions, and streams | Implemented with strict bounded Bearer parsing, transport-identity fallback, Boot bean discovery, immutable identities, built-in authenticated/authority/role policies, and identity-bound live views; credential verification and user storage remain application/container-owned |
| Static generation/ISR | `@Prerender`, `StaticPathProvider`, and `roots-maven-plugin` | Implemented for public deterministic pages with packaged HTML, session-free ETags/HEAD/304, stale-while-revalidate, single-flight virtual-thread regeneration, failure backoff, and startup fallback generation |
| Cache tags/revalidation | `RootsCache` + `CachePolicy` + optional `roots-jdbc` | Implemented with a bounded LRU node-local default and a bounded shared JDBC adapter providing cross-node TTLs, key/tag invalidation, load leases, safe codecs, and stale-publication prevention |
| Image/font optimization | `Html.image`, `WebFont`, and `@PageFont` | Implemented for bounded pure-JDK local PNG/JPEG resize/quality output, responsive `srcset`/sizes/priority hints, ETags/HEAD/304, production LRU, development re-read, generated same-origin `@font-face` CSS, optional font preload, client-navigation head synchronization, mount rebasing, and prerender-safe markup; remote fetching, modern image codecs, animation, subsetting, and source-content URL fingerprinting remain out of scope |
| Deployment adapters | JDK server and Jakarta Servlet 6.1 now; Netty/other adapters | Servlet implemented and tested on Tomcat 11; broader container certification and additional adapters planned |
| Operational lifecycle | readiness health, request/view/session admission limits, counters, graceful drain | Implemented across the built-in and Servlet transports |
| Session storage | `SessionRepository` + optional `roots-jdbc` | Implemented as an atomic lifecycle SPI with bounded node-local default, `503` failure mapping, Boot bean discovery, and a JDBC adapter with exact cross-node capacity, lazy shared values, safe scalar codec, custom codec boundary, and cascade cleanup |
| Live-view affinity | `LiveViewOwnership` + optional `roots-jdbc` | Implemented with session-bound atomic leases, generated or explicit node identity, renewal/cleanup across the full lifecycle, node and wrong-owner response headers, fail-closed outages, Boot bean discovery, and a standard-JDBC shared adapter; database/proxy certification and live-state failover remain |
| Request observability | W3C `TraceContext` + `RequestObserver` + safe JSON completion events | Implemented across JDK and Servlet transports with response correlation and exactly-once stream completion; telemetry SDK/exporter is application-owned |
| Proxy trust / rate limiting | `ClientConnection` + `ProxyPolicy` + `RateLimiter` | Implemented with direct-only defaults, explicit IPv4/IPv6 CIDRs, strict forwarded-chain resolution, bounded pre-session fixed windows, standard limit headers, and Boot properties/bean overrides; distributed limiting is application-owned |
| Boot observability | Actuator `roots` health contributor + Micrometer `roots.*` meters | Implemented conditionally with standard Boot enable properties, ordered observer-bean discovery, and bounded-tag request timers |
| Dependency-injection bridge | `InstanceFactory` / `roots-spring` | Implemented for fresh Spring-managed pages, layouts, and API routes with destruction callbacks |
| Spring Boot starter | `@EnableRoots`, typed `roots.*` properties, customizers, JDK/Servlet transport selection | Implemented with auto-configuration discovery, graceful drain, validated Servlet path ownership/MVC coexistence, health, transport-tagged metrics, and optional Spring Security identity propagation |
| HTML security policy | global validated CSP across framework, application, error, and static HTML | Implemented with a restrictive default and bounded override |
| Compile-time route manifest | `roots-processor` + `@RootsApplication` / `@EnableRoots` | Implemented with deterministic versioned resources, layout chains, root not-found/error discovery, ambiguity diagnostics, strict runtime validation, version-1/2 read compatibility, and scanner fallback |

The planned rows are required before Roots can honestly call itself a broad Next.js replacement. The implemented rows form a coherent live enterprise application framework today.
