# Compatibility policy

Roots is currently a `0.x` framework. The project therefore distinguishes the
contracts it can mechanically protect today from the stronger promise that begins
at 1.0.

## Supported API surface

The application-facing Java API is the public and protected surface in:

- `dev.roots`;
- `dev.roots.annotation`;
- `dev.roots.html`;
- `dev.roots.validation`;
- `dev.roots.servlet`;
- `dev.roots.jdbc`;
- `dev.roots.spring`;
- `dev.roots.spring.boot`.

Anything in `dev.roots.internal` is an implementation detail even when Java
visibility must be `public` so another Roots module can call it. Applications
must not compile against that package.

The complete reviewed core API is committed as
`roots-core/src/test/resources/dev/roots/public-api-v1.txt`. Every `verify` run
reflects over the compiled classes and compares type kinds, generic signatures,
public/protected fields, constructors and methods, nested types, sealed permits,
runtime annotations, and annotation defaults with that baseline. Removal,
addition, visibility drift, changed generic types, and changed annotation
contracts therefore fail the build until a maintainer reviews the change.

The Servlet, Spring, and Spring Boot surfaces have a second reviewed baseline at
`roots-spring-boot-starter/src/test/resources/dev/roots/adapter-public-api-v1.txt`.
It is enforced by the starter's tests and intentionally includes public
auto-configuration annotations and property-record shape as well as ordinary
members. Package-private adapter plumbing is not a supported application API.

The deliberately small `dev.roots.jdbc` surface is checked independently for its
exact constructors, constant, and methods by the `roots-jdbc` test suite.

To inspect a proposed baseline after `test-compile`:

```powershell
java -cp "roots-core\target\test-classes;roots-core\target\classes" `
  dev.roots.compatibility.PublicApiSnapshot
```

To inspect the adapter baseline, first install the current reactor artifacts and
then run its test-classpath generator:

```powershell
.\mvnw.cmd -q -pl roots-spring-boot-starter -am -DskipTests install
.\mvnw.cmd -q -pl roots-spring-boot-starter `
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java `
  -Dexec.mainClass=dev.roots.spring.boot.compatibility.AdapterPublicApiSnapshot `
  -Dexec.classpathScope=test
```

Changing the committed baseline is not a mechanical fix for a red test. The same
change must state whether it is source compatible, binary compatible, behavioral,
or breaking and provide a migration note when application code must change.

## Version rules

Before 1.0, minor versions may make reviewed breaking changes when completing a
coherent design. Patch versions must remain source and binary compatible with the
previous release in the same minor line. Deprecated APIs should normally remain
for the rest of that minor line.

Starting at 1.0, Roots follows semantic versioning for the supported surface:

- patch releases preserve source, binary, and wire compatibility;
- minor releases may add APIs and deprecate old APIs, but do not remove them;
- a deprecated application API remains for at least two minor releases;
- removals or incompatible signature changes require a major release;
- security fixes may narrow unsafe behavior, with the exception called out in
  the release notes.

Behavioral compatibility is not completely reducible to signatures. Framework
behavior remains covered by unit, transport, Servlet, Spring, example, and real
browser suites, and intentional behavior changes require release notes.

## Browser protocol

The browser/server contract has its own integer string version, exposed as
`Roots.PROTOCOL_VERSION`. The exact fields and deployment behavior are documented
in [Browser protocol](protocol.md). A protocol version changes only when an older
driver cannot safely interpret a request or response. Additive optional fields do
not require a new version.

During a rolling deployment, an incompatible action receives `409` with a reload
instruction and an incompatible SSE connection receives a one-shot `reload`
event. The bundled version-1 driver handles both by fetching a fresh document.
Supporting two protocol generations simultaneously is allowed but must be explicit
and covered by contract tests; silently interpreting an unknown version is not.

## Browser certification

Every reactor build runs the browser-neutral live-runtime contract against Chrome,
Edge, and Firefox through Selenium. All three browsers exercise live
actions, revisioned component and root patches, optimistic rollback, navigation,
stylesheets, portals, uploads, focus/blur and editable-text-selection effects,
polite/assertive ARIA announcements, typed action and navigation view-transition
commits and fallbacks, rapid latest-wins navigation, originating-view rejection
for late success/validation/failure responses and queued actions, browser events,
fragment navigation, action redirects, back/forward scroll restoration, view
disposal, and automated WCAG 2.2
AA/best-practice audits. Chrome additionally runs the deterministic
DevTools-based offline/reconnect and queued-update recovery fixture. Safari/WebKit
is not yet release-certified.

Route-manifest version 2 added `NOT_FOUND`; version 3 adds `ERROR_PAGE` for the
root production-error convention. The runtime still accepts version-1 and
version-2 manifests and treats missing rows as absent conventions. Regenerate
manifests with a clean compile to use them; there is no browser-protocol version
change.

## Migration notes

No stable release has shipped yet. The current baseline records the API assembled
for `0.1.0-SNAPSHOT`. One pre-baseline design change worth noting is that `Request`
and `UploadedFile` are final classes rather than records. Their existing accessor
and byte-array constructor shapes remain, while the classes can now own repeatable,
request-scoped streaming content and deterministic cleanup. Code should use their
public accessors rather than record-pattern deconstruction.

Core authentication is an additive pre-1.0 API: `AuthenticationProvider`,
`Request.withIdentity(...)`, and the final `RootsConfig` component were added while
retaining the immediately preceding maximal configuration constructor with
transport-identity behavior. Builder users require no migration; they remain on
the adapter-supplied identity unless they call `authenticationProvider(...)`.

Lazy response streaming changes `Response` from a record to a final class in the
pre-1.0 line. Its public constructor, `status()`, `headers()`, `body()`, existing
factories, header/cookie copies, and value methods remain. Code using ordinary
accessors requires no migration; record-pattern deconstruction or APIs requiring
`Class.isRecord()` must move to the accessors. Streaming responses deliberately
reject `body()`/`bodyText()` because materializing them would defeat the contract.
The new `Response.stream(...)`, `Response.file(...)`, `contentLength()`, and
`transferTo(...)` APIs are additive beyond that reviewed type-kind change.

`ApiRoute.options(Request)` is an additive pre-1.0 default method. Existing route
classes compile unchanged. Their `OPTIONS` behavior changes from `405` to a
body-free `204`, and all default method-not-allowed responses now carry a
route-specific `Allow` header derived from overridden handler methods. Applications
that previously implemented CORS through a catch-all `405` path should override
`options` explicitly and keep origin and credential decisions application-owned.
