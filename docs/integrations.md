# Integrations

Roots is intentionally dependency-free at runtime. Application projects remain
normal Maven applications and may add the libraries they need.

## Persistence

JDBC and standard persistence libraries are compatible because Roots does not
wrap or replace the Java database stack. Applications can use:

- JDBC and `DataSource`;
- JPA providers such as Hibernate;
- jOOQ or MyBatis;
- HikariCP or another connection pool;
- Flyway or Liquibase for migrations;
- Spring Data repositories when a Spring context owns them.

Keep persistence behind an application service. Treat a server action like a
request boundary: acquire a connection or persistence context, start a
transaction, perform work, commit, and close it. `EntityManager`, Hibernate
`Session`, and JDBC `Connection` instances are not component state and should
not be retained across rerenders.

Virtual threads make straightforward blocking database code viable, but the
database pool still sets the real concurrency ceiling. Size it for the database,
not for the number of virtual threads.

## Jakarta Servlet 6.1

The `roots-servlet` module runs Roots in a Jakarta Servlet 6.1 container without
adding Servlet APIs to `roots-core`. Its Servlet API dependency is `provided`, so
the target application server supplies the implementation.

Register `RootsServlet` at `/*` with async support. Descriptor-based registration
uses the required `roots.applicationClass` init parameter and accepts an optional
strict `roots.development=true|false` parameter. Programmatic registration can
pass the complete configuration directly:

```java
var roots = new RootsServlet(
        RootsConfig.forApplication(Application.class)
                .development(false)
                .build(),
        Duration.ofSeconds(30),
        request -> Optional.ofNullable(request.getUserPrincipal())
                .map(principal -> AuthenticatedIdentity.named(principal.getName()))
);
```

The mapping works below a container context path. Ordinary traffic, including
lazy fixed-length and container-framed application responses, uses container I/O
directly; live SSE uses Servlet async contexts and virtual threads. Container
shutdown invokes the same readiness, bounded drain, live-view cleanup, and session
repository lifecycle as the built-in listener. The integration suite runs against
real embedded Tomcat 11, including page/API/assets, streamed responses and HEAD,
sessions, CSP, async SSE, live actions, descriptor initialization, context paths,
and forced stream shutdown.

The default `ServletIdentityResolver.containerPrincipal()` maps the standard
container principal to a name-only `AuthenticatedIdentity`. Supply a custom
resolver when the container can enumerate roles or authorities. Roots invokes it
on the request thread before asynchronous SSE handoff and copies only immutable
name/authority data. Authentication still belongs to the container; the resolver
must never place credentials or mutable provider objects in the Roots identity.

## Spring Framework

Spring libraries can exist in the same process, and a Roots application can call
services or repositories created by a Spring application context. The separate
`roots-spring` module adapts the container without adding Spring to Roots Core:

```java
var config = RootsConfig.forApplication(Application.class)
        .instanceFactory(new SpringInstanceFactory(context))
        .build();
```

`SpringInstanceFactory` uses `AutowireCapableBeanFactory.createBean`, so every page
and layout remains a fresh live-view state holder and every API route is scoped to
one request. Spring constructor/field injection, bean post-processors, and
initialization callbacks run normally. Roots invokes the factory's destruction
contract on API completion and live-view disposal, so Spring destruction callbacks
also run on success, failure, timeout, explicit disposal, and server shutdown. The
application still owns and closes the Spring application context itself.

## Spring Boot

The `roots-spring-boot-starter` module supplies discoverable Boot
auto-configuration. Add `@EnableRoots(Application.class)` to a configuration
class, where `Application.class` anchors the conventional `.pages` and `.api`
packages. The starter:

- binds validated `roots.*` properties and publishes configuration metadata;
- creates fresh page, layout, and API-route instances through Spring;
- starts the default JDK transport after Spring finishes singleton creation, or
  registers the Servlet transport in Boot's existing web server;
- drains Roots before application services are destroyed;
- backs off when the application supplies `InstanceFactory`, `RootsConfig`, or
  `RootsApplicationLifecycle` beans;
- automatically uses a unique application-provided `SessionRepository` bean;
- automatically uses a unique application-provided `LiveViewOwnership` bean;
- automatically uses a unique application-provided `AuthenticationProvider` bean;
- forwards completion events to ordered application `RequestObserver` beans;
- applies ordered `RootsConfigCustomizer` beans after bound properties;
- maps Spring Security `Authentication` into Roots identity data in Servlet mode
  when Spring Security is present;
- conditionally contributes Roots health and metrics when Actuator/Micrometer is
  present, while keeping those dependencies optional for other consumers.

For a multi-node deployment, add `roots-jdbc` and expose
`new JdbcSessionRepository(dataSource)` and
`new JdbcLiveViewOwnership(dataSource, nodeId)` as the unique
`SessionRepository` and `LiveViewOwnership` beans. The starter adopts both
automatically. Configure `new JdbcRootsCache(dataSource)` through a
`RootsConfigCustomizer` when application-cache entries and tag invalidations must
also be shared. Schema and operational requirements are documented in
[JDBC shared state](jdbc.md).

The default `roots.transport=jdk` listener uses `127.0.0.1:8081` to avoid Boot's
usual port. Set `roots.transport=servlet` in a Servlet web application to map
Roots in Boot's existing server. It owns `/*` by default; set
`roots.servlet-path=/roots` to limit ownership to `/roots/*` and leave other
paths available to Spring MVC. That mode uses Boot's `server.port` and
`server.servlet.context-path` and ignores Roots host/port for listener binding.
Selecting it outside a Servlet web application fails startup explicitly. Supported
properties include packages, host/port, development mode, view/session/shutdown
timeouts, request/view/session admission limits, request-body memory threshold,
multipart text-field limit, request-body temporary directory, the Servlet prefix,
secure cookies, and the global Content Security Policy. The upload storage keys
are `roots.request-body-memory-threshold`,
`roots.max-multipart-text-field-bytes`, and
`roots.request-body-temporary-directory`. Use `roots.enabled=false` to suppress
the Roots server.

With Spring Boot Actuator present, the health contributor named `roots` reports
`UP` while accepting work, `OUT_OF_SERVICE` while draining, and `DOWN` while
stopped. Its optional details include request, live-view, session, and capacity
snapshots plus the local ownership node identifier. Boot's standard
`management.health.roots.enabled` property controls
registration.

Micrometer receives the following meters, tagged with the selected
`transport=jdk|servlet`:

| Meter | Type | Meaning |
|---|---|---|
| `roots.server.running` / `roots.server.accepting` | gauge | listener and readiness state |
| `roots.requests.handled` / `roots.requests.rejected` | function counter | cumulative request outcomes |
| `roots.requests.active` / `peak` / `capacity` | gauge | request concurrency |
| `roots.views.active` / `capacity` | gauge | retained live views |
| `roots.sessions.active` / `capacity` | gauge | retained sessions |
| `roots.http.server.requests` | timer | completed request latency with bounded `method`, `status`, `outcome`, and `transport` tags |

The cumulative counters remain monotonic across lifecycle restarts. Use Boot's
standard `management.metrics.enable.roots` property to suppress the binder.
Exporting to Prometheus, OTLP, Datadog, or another backend remains the
application's normal Micrometer configuration.

No request path, trace identifier, session identifier, or user value is used as a
Micrometer tag. Applications configure Prometheus, OTLP, Datadog, or another
exporter through normal Boot facilities.

Current limitations:

- Tomcat 11 is the tested Servlet container; broader container verification
  remains release work. A [packaged WAR deployment example](../deploy/servlet/README.md)
  provides the on-premises/container walkthrough. Local verification is not
  production certification.

Both JDK and Servlet transports expose direct numeric peer details. Forwarded
headers remain disabled until `roots.trusted-proxies` lists the actual proxy
CIDRs. Boot can also discover a single application-owned `ProxyPolicy` bean.
Set `roots.rate-limit-requests` above zero to install the built-in bounded
fixed-window limiter; `roots.rate-limit-window` and
`roots.max-rate-limit-clients` control its window and key cap. A single
application-owned `RateLimiter` bean takes precedence, which is the integration
point for a coordinated multi-node limiter.

The adapter makes dependency injection supported, but applications should not
describe Roots as a Spring MVC or Spring Boot UI framework. Further pre-1.0
integration should provide:

1. broader container certification;
2. an additional production transport where deployments require one.

In Servlet mode, configure Spring Security's normal `SecurityFilterChain`. The
optional Roots auto-configuration reads the request principal after that filter
chain, rejects anonymous authentication tokens, and copies the authenticated name
plus exact `GrantedAuthority` strings into `AuthenticatedIdentity`. Register Roots
named policies with `AuthorizationPolicy.authenticated`, `authority`, or `role`;
`role("ADMIN", ...)` checks the conventional `ROLE_ADMIN` authority. A custom
`ServletIdentityResolver` bean replaces the automatic bridge.

This bridge does not create users, login pages, password encoders, filters, or
authorization rules. Spring Security owns authentication and filter-chain access;
Roots policies independently guard pages, APIs, live actions, and SSE. A live view
stores only its immutable identity snapshot and expires when a later request has a
different name or authority set, preventing a session cookie from reusing state
created under another user or privilege set.

For the JDK transport or a non-Spring identity service, register a Core
`AuthenticationProvider` directly or expose one as a Boot bean. The provider sees
headers, parsed cookies, the resolved session, trusted connection metadata, and the
transport identity. `AuthenticationProvider.bearer(...)` supplies conservative
Authorization parsing; the application remains responsible for cryptographic
verification, issuer/audience checks, expiry, revocation, and authority mapping.

## Other libraries

Browser-side libraries use the [widget integration contract](browser-widgets.md):
same-origin ES modules, keyed hosts, abortable mount/update/destroy, native form
bridges, and explicit dirty-state recovery. The enterprise `/latency` example
ships a working accessible chart without an npm or bundler requirement.

Logging, JSON, validation, mail, messaging, cloud SDKs, and observability libraries
can be used in application services today. `RequestObserver` is the transport-neutral
completion boundary: it can emit stable JDK JSON-lines logs or feed an
application-owned telemetry SDK. Roots propagates W3C `traceparent` identifiers,
but does not bundle an OpenTelemetry SDK or exporter. Applications own those
libraries' configuration and lifecycle.

`RootsCache` is the exception: it is an explicit application-cache integration
contract. The default implementation is dependency-free and node-local. The
optional `JdbcRootsCache` supplies bounded coordinated storage, tag invalidation,
and miss leases using an application-owned `DataSource`; applications may still
supply Redis or another implementation with `RootsConfig.Builder.cache`. Each
implementation owns serialization, connections, timeouts, and shutdown. Pages receive it through
`PageContext.cache()`, API routes through `Request.cache()`, live actions through
`ActionEvent.cache()`, and jobs through the `RunningApplication` handle.
