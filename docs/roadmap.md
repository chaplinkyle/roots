# Roadmap to 1.0

## 0.2 — development loop

- Java source/resource watcher with clean Maven compilation, immutable last-good
  snapshots, isolated application classloader restart, and same-port recovery (implemented);
- session-free browser reload/error SSE channel with CSP-compatible, text-only diagnostics (implemented);
- compile-time route manifest and duplicate diagnostics (implemented with
  anchor-specific versioned resources, root not-found/error discovery,
  legacy-version reading, strict runtime validation, and scanner fallback);
- authenticated live route/component/action inspector with DOM binding locations (implemented).

## 0.3 — enterprise runtime

- authentication-provider adapters and policy composition (a transport-neutral
  provider SPI, strict bounded Bearer parsing, immutable identity propagation,
  Boot bean discovery, identity-bound live views, and `@Authorize` named policies
  are implemented; packaged OAuth/OIDC verification remains application-owned);
- Spring bean adapter and a Spring Boot starter (`roots-spring` provides tested
  per-view/request creation and destruction; `roots-spring-boot-starter` provides
  typed properties, customizers, auto-configuration, and graceful lifecycle ownership;
  opt-in Servlet registration and Spring Security name/authority propagation are
  implemented; additional authentication-provider adapters remain);
- pluggable sessions and live-view affinity (`SessionRepository`, its atomic
  bounded default, external value delegation, and Boot bean discovery are
  implemented; `LiveViewOwnership`, its session-bound lease contract, node
  headers, wrong-node responses, lifecycle cleanup, and Boot bean discovery are
  implemented; `roots-jdbc` now supplies shared conditional-SQL leases with real
  database concurrency tests plus exact-capacity JDBC sessions and safe persisted
  values, as well as a bounded tagged distributed cache with cross-node
  single-flight loads and invalidation; production database/proxy certification
  and live-state failover remain);
- metrics export, tracing, and structured logs (runtime counts, health/readiness,
  admission limits, graceful drain, and optional Boot Actuator/Micrometer bridges
  are implemented; W3C trace propagation, safe structured completion events,
  response correlation, observer beans, and bounded-tag request timers are also
  implemented; full distributed span/exporter integration remains application-owned);
- Jakarta Servlet 6.1 adapter while retaining the zero-dependency JDK server
  (implemented with context-path routing, container I/O, virtual-thread async SSE,
  and bounded graceful shutdown); Boot registration at `/*` is implemented behind
  `roots.transport=servlet`; `roots.servlet-path` now provides validated path
  ownership with framework/application URL rebasing, mount-scoped cookies, MVC
  coexistence, and mounted prerender ETags; broader container certification and
  a Netty adapter remain;
- proxy awareness, streaming/temp-file upload adapters, and security audit
  (explicit IPv4/IPv6 proxy trust, strict `Forwarded`/`X-Forwarded-*` resolution,
  pre-session pluggable rate limiting, a bounded concurrent node-local limiter,
  central configurable CSP, strict request/response cookie parsing and construction,
  mount-aware success-only live-action cookie commits, exact total/text-part
  limits, thresholded request-body temp-file spooling, repeatable upload
  streams/transfers, deterministic cleanup, plus lazy fixed/chunked/file response
  streaming with exact-length enforcement and HEAD suppression are implemented;
  direct-to-object-store adapters remain application-owned).

## 0.4 — rendering and data

- exact component-scoped patch transfer and reconciliation (implemented for
  element-root components with narrowest-boundary proof, base-revision validation,
  no-DOM patches, and conservative root fallback for portals, fragments,
  ambiguity, or queued SSE updates; subtree-only Java evaluation remains future work);
- distributed cache adapters and cross-node invalidation (implemented through
  `roots-jdbc` with bounded shared entries, TTL, key/tag invalidation,
  generation-checked publication, abandoned-load leases, safe/custom codecs,
  exact capacity, and 200-way two-node contention coverage);
- static generation and incremental regeneration (implemented for explicit public
  pages with build-packaged HTML, dynamic path providers, ETags, session-free
  serving, single-flight stale regeneration, and failure backoff);
- local image and font optimization (implemented with responsive Java image
  nodes, pure-JDK bounded PNG/JPEG transforms, conditional caching, local font
  metadata/CSS/preloads, mount rebasing, and navigation synchronization; remote
  sources, modern codecs, font subsetting, and CDN-scale derived storage remain
  application-owned);
- document metadata (implemented with additive typed dynamic records and annotation
  fields for canonical, robots, theme-color, and Open Graph; includes URL validation,
  field-wise nested-layout inheritance, output escaping, mount rebasing, prerender
  output, live action/reconnect updates,
  and latest-page navigation replacement/removal; arbitrary script injection remains
  intentionally application-owned);
- browser view-transition animation helpers (implemented for typed action effects
  and opt-in Java links with render-queue ordering, standard CSS pseudo-elements,
  and automatic unsupported/reduced-motion fallback; optimistic effects and
  nested pending scopes are also implemented);
- modal convenience behavior and accessibility helpers (implemented with a
  first-class Java `Modal`, native top-layer semantics, required labeling,
  explicit/fallback focus, topmost Tab containment, server-authoritative Escape
  and configurable backdrop dismissal, focus return, and cross-browser axe coverage);
- bounded browser capabilities (implemented for Java-owned focus, blur,
  editable-text selection, scroll, clipboard, polite/assertive permanent ARIA
  announcements, and view transitions; arbitrary application script remains
  outside the framework contract);
- declarative validation schemas and reusable constraint helpers (implemented
  with cached Java-record binding for typed text/uploads, field mapping, built-in
  and application-defined constraint annotations, bounded aggregated responses,
  accessible client binding, exact edit cleanup, and multipart uploads).

## 1.0 release gates

- stable public component and protocol APIs (the reviewed core API now has an
  exact compiled-signature baseline; the Servlet, Spring, and Spring Boot
  adapter APIs now have a separate exact baseline; browser protocol version 1 is
  advertised, negotiated, contract-tested, view-bound against cross-navigation
  races, and reload-safe across incompatible
  deployments; a stable 1.0 freeze remains);
- load, soak, reconnect, accessibility, and browser compatibility suites
  (the complete browser-neutral contract runs in real Chrome, Edge, and Firefox;
  all three verify latest-wins navigation, origin-bound action queues, rejection
  of late old-view success/validation/failure outcomes, fragment/action-redirect
  navigation, and history scroll restoration,
  while Chrome additionally verifies offline/reconnect state and missed-update catch-up;
  automated WCAG 2.2 AA/best-practice audits cover live updates, validation,
  portals, navigation, and both shipped examples; a dedicated HTTP module now
  provides bounded multi-view/hot-view load gates and a configurable opt-in soak;
  workload-specific capacity certification, manual assistive-technology review,
  and Safari/WebKit certification remains);
- Maven Central publication and signed artifacts (source and warning-free
  Javadoc artifacts are attached; repository release and signing remain);
- migration tooling and compatibility policy (the supported packages, pre-1.0
  rules, 1.x semantic-versioning guarantees, deprecation window, protocol-version
  policy, baseline review workflow, and first migration note are documented;
  automated application migrations remain);
- production reference deployments with more than one JVM node.
