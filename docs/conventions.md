# Roots conventions

An application calls `Roots.run(Application.class, arguments)`. If `Application`
is in `com.acme`, its convention packages are `com.acme.pages` and `com.acme.api`.

## Compile-time route manifests

Mark the convention anchor and configure the Roots annotation processor:

```java
@RootsApplication
public final class Application { ... }
```

```xml
<dependency>
  <groupId>com.chaplin.roots</groupId>
  <artifactId>roots-processor</artifactId>
  <version>${roots.version}</version>
  <scope>provided</scope>
</dependency>
```

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>com.chaplin.roots</groupId>
        <artifactId>roots-processor</artifactId>
        <version>${roots.version}</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

The optional `provided` dependency gives multi-module Maven reactors ordering
without becoming a runtime dependency; the compiler path is what enables and
isolates processor execution. The Roots archetype includes the marker and
compiler-path setup. Spring Boot
applications may use `@EnableRoots(Application.class)` instead of adding
`@RootsApplication`; they still need the processor on the compiler path.

On a clean compilation the processor validates package and `@Route` templates,
authorization policy names, catch-all placement, and ambiguous page/API shapes.
For example, `/users/{id}` and `/users/{name}` fail compilation because they
match the same requests. It then writes a deterministic versioned resource at
`META-INF/roots/routes/<application-binary-name>.routes`, including each page's
ordered layout chain.
Manifest version 3 records the optional root not-found and error pages; the
current runtime continues to read version-1 and version-2 manifests for upgrade
compatibility.

At startup Roots prefers the manifest for the exact application anchor. A
missing manifest falls back to classpath scanning. Explicit `pagesPackage` or
`apiPackage` overrides that do not match the manifest also fall back to scanning.
A duplicate or malformed matching manifest fails closed. Release builds should
be clean builds so the index reflects the complete application source set.

## Pages and layouts

Every concrete `Page` class named `Page` becomes a route. Every concrete `Layout` class named `Layout` wraps pages in its package and all descendant packages. Layouts nest from the application root toward the page.

An optional concrete class named `NotFound` directly in the root pages package
is the application-wide unmatched-page convention:

```java
package com.acme.pages;

@PageMetadata(title = "Page not found")
public final class NotFound implements com.chaplin.roots.Page {
    public Node render(PageContext context) {
        return main(
            h1("Page not found"),
            p("No page matches ", context.path()),
            link("/", "Return home")
        );
    }
}
```

It receives the requested logical path, uses the root layout chain and normal
dependency construction, and may contain retained components and server actions.
Its document and `HEAD` responses retain status `404`; `HEAD` does not retain a
live view. Client navigation recognizes a valid Roots 404 document and commits
it in the same document. The resulting `roots:navigate` event has a numeric
`detail.status`. Missing API routes and asset-like requests remain session-free
framework 404s. `NotFound` cannot declare `@Route` or `@Prerender`.

An optional concrete `ErrorPage` directly in the root pages package is the safe
production document fallback for an otherwise-unmapped page-render failure. It
is an ordinary retained `Page`, so metadata, components, state, actions, and the
request `TraceContext` remain available. Roots does not pass it the exception,
does not use it for JSON/API/action/validation/mapped failures, and keeps detailed
development diagnostics unchanged. Its response and `HEAD` status are `500`, and
a valid error document may commit during client navigation with
`roots:navigate.detail.status == 500`.

`ErrorPage` deliberately renders without layouts or authorization: a failing or
restricted layout must not prevent the last-resort UI. It therefore must render
a complete accessible page tree, including a `main` landmark. `@Route`,
`@Prerender`, and `@Authorize` are rejected. A construction or render failure is
logged and falls back to Roots' built-in non-diagnostic production 500 document.

| Package suffix | Segment |
|---|---|
| `customers` | `/customers` |
| `customer_admin` | `/customer-admin` |
| `$customerId` | `/{customerId}` |
| `$$path` | `/{*path}` and must be last |
| `group_admin` | no URL segment |

Static routes win over dynamic routes. Ambiguous resolved routes fail during
compilation when the processor is enabled and still fail at startup on the
scanner fallback path.

Use `@Route` when a URL cannot or should not follow the package:

```java
@Route("/activity")
public final class Page implements com.chaplin.roots.Page { ... }
```

Explicit templates accept `{parameter}` and a final `{*catchAll}`.

## Authorization annotations

Configure `RootsConfig.Builder.authenticationProvider(...)` before named policies.
It receives each bounded, session-bearing request and returns either an immutable
`AuthenticatedIdentity` or an empty result for anonymous access. The default keeps
the identity supplied by the active transport. `AuthenticationProvider.bearer(...)`
strictly parses one RFC 6750 Authorization credential while leaving token
verification, expiry, revocation, and user lookup in application Java code.

`@Authorize("policy-name")` on a page or API route requires the registered named
policy before that route executes. On a nested `Layout`, the policy applies to
every descendant page. Multiple names in one annotation are evaluated in order
and all must allow the request.

The same annotation on a `@ServerAction` method adds action-specific policy checks
after its page/layout policies and before the method mutates state. Route policy
names must be registered at startup; action bindings are validated when rendered.
Policies are registered with `RootsConfig.Builder.authorize`.

## Metadata

Static metadata is annotation-first:

```java
@PageMetadata(
    title = "Customers",
    description = "Manage customer accounts",
    stylesheets = "/app.css",
    fonts = @PageFont(family = "Roots Sans", source = "/fonts/roots.woff2", preload = true),
    canonical = "https://console.example.com/customers",
    robots = "index, follow",
    themeColor = "#17324d",
    openGraphType = "website",
    openGraphImage = "https://console.example.com/customers.png",
    openGraphImageAlt = "Customer console"
)
```

`@PageFont` accepts WOFF2, WOFF, TTF, or OTF public paths plus weight, style,
display, and preload settings. Override `metadata(PageContext)` for route-dependent
values and attach `WebFont` declarations with `Metadata.withFonts(...)`. Do not
combine that method with `@PageMetadata`; the annotation intentionally wins.

Canonical, robots, theme-color, and Open Graph values may also be returned from
`headMetadata(PageContext)` using immutable `HeadMetadata` and
`OpenGraphMetadata` records. This method can be combined with `@PageMetadata`;
each non-empty extended value in the annotation takes precedence over the
dynamic value. A `Layout` may implement the same method for shared metadata.
Roots merges layouts from outermost to innermost, then merges the page and its
annotation, with each later non-empty field taking precedence; Open Graph fields
merge individually. Canonical and Open Graph URL/image values must be absolute
HTTP(S) URLs or application-root-relative paths. Root-relative paths are rebased
for Servlet mounts. Roots emits only escaped, named framework-owned tags and
synchronizes their replacement and removal after action renders, reconnects,
client navigation, and prerendering. When any Open Graph value is present, the
ordinary title and description supply omitted `og:title` and `og:description`.

## API routes

An `ApiRoute` named `Route` beneath the API package maps to `/api/...` and implements the relevant method:

```java
public final class Route implements ApiRoute {
    @Override
    public Response get(Request request) {
        return Response.json(200, "{\"status\":\"ok\"}");
    }
}
```

`ApiRoute` dispatches `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, and
`OPTIONS`.
Its default `head` delegates to `get`, while the transport suppresses the body;
override it when merely constructing the GET response performs expensive work.
The default `options` returns `204` with a canonical `Allow` value derived from
the Java methods that route overrides. `GET` implies `HEAD`, while a separately
overridden `head` can stand alone. Unsupported methods return `405` with the same
route-specific `Allow` header. Override `options` to attach an application-owned
CORS policy; Roots never reflects an unvalidated request origin into response
headers.

Buffered `Response.text`, `html`, and `json` results suit small values. For large
or generated bodies, use `Response.file(...)`, fixed-length
`Response.stream(..., length, writer)`, or unknown-length
`Response.stream(..., writer)`. A writer is invoked once, synchronously, only
when the response body is sent. It owns every source resource it opens but must
not close the supplied output stream. A declared length is an enforced contract,
not a hint: underflow and overflow terminate the response with an I/O failure.
Unknown-length output uses the active transport's streaming framing.

## Browser events

Use the fluent `onClick`, `onDoubleClick`, `onSubmit`, `onChange`, `onInput`,
`onKeyDown`, `onKeyUp`, `onFocus`, `onBlur`, `onPointerDown`, and `onPointerUp`
bindings. The generic `on` method accepts only those corresponding lowercase DOM
names; unsupported names fail immediately.

An `ActionEvent` exposes submitted values and `browser()` metadata. Keyboard
key/code, modifiers, pointer button, and viewport coordinates are optional or
typed rather than raw protocol strings. The server rejects duplicate, malformed,
unsupported, or unbounded event metadata. A single annotated method may be bound
to multiple event types on the same rendered tree.

Focus and blur are delegated in capture mode so they work for nested elements.
Roots-directed focus restoration, `ActionEvent.focus`, `ActionEvent.blur`, and
`ActionEvent.selectText` do not recursively fire bound focus or blur server
actions. Input actions rerender for each admitted event; prefer change events
when that frequency is unnecessary.

Actions may also request scroll into view, clipboard copy, and polite or
assertive accessibility announcements. Announcements use permanent framework
ARIA live regions outside the reconciled root, are inserted as text, and emit a
`roots:announce` browser event with `message` and `priority` detail. Prefer polite
announcements for ordinary status; reserve assertive announcements for urgent,
time-sensitive information. Arbitrary application script is not an effect.

Calling `ActionEvent.viewTransition()` asks the owned driver to commit that
action's non-empty patch inside `document.startViewTransition(...)`. The render
queue waits for `updateCallbackDone`, preserving action and revision order. This
is progressive enhancement: a missing API or a matching
`prefers-reduced-motion: reduce` query uses the ordinary synchronous commit. The
server remains authoritative in both paths, duplicate requests collapse, and an
action can emit at most 32 validated focus, blur, selection, scroll, clipboard,
announcement, and transition effects in total.

Calling `.viewTransition()` on a Roots `link(...)` opts that client navigation
into the same fallback policy. The helper rejects non-anchor elements. Fetching
and parsing occur first; the new root, stylesheets, metadata, history, portals,
stream attachment, and `roots:navigate` event commit within the transition update.
Concurrent navigations are latest-wins. Roots disposes a live view allocated for
an older response that arrives after a newer navigation, and ignores action or
stream payloads whose required `view` no longer matches the active root. Either
case emits `roots:stale`; its detail is
`{kind: "navigation" | "action" | "patch", view}`. `action` covers any action
completion discarded after its originating view changed, including validation,
authorization, protocol, and transport failures, plus queued actions rejected
before transport. Such work is never rebound to the new view's credentials.
The event is diagnostic—applications should not use it to repair state.

Links whose origin, path, and query already match the active document keep native
fragment behavior and do not create a live view. Cross-route ordinary fragments
scroll to the decoded `id` or legacy `name` after commit. Roots stores finite
scroll coordinates in `history.state.__rootsScroll` and restores them for
back/forward traversal. Cross-route `#:~:text=` links deliberately remain hard
navigations so the browser owns text-fragment matching. `ActionEvent.redirect`
uses client navigation for its validated application path and preserves fragment
and view-transition behavior.

## Forms and uploads

Bind forms with `.onSubmit(component, "action")`. Roots sends URL-encoded fields
unless a non-empty file input is selected, in which case it sends multipart data.
Read text with `ActionEvent.value/values` and files with
`ActionEvent.file/files`. API routes expose the same APIs on `Request`.

For typed forms, bind both text and uploads directly to a record with
`event.bind(FormType.class)` or `request.bind(FormType.class)`. Roots caches the
record schema per application classloader, rejects repeated values for scalar
components, and aggregates conversion and constraint failures by submitted field.
Use `@FormField` for field-name mapping and optional whitespace stripping,
`@FormModel` for the validation summary, and the built-in `@NotBlank`, `@Email`,
`@Size`, `@Min`, and `@Max` constraints. Records may contain strings, booleans,
numeric types, enums, UUIDs, supported `java.time` types, optionals, lists, and
uploads. Define application constraints by meta-annotating a runtime record-component
annotation with `@FormConstraint` and providing a stateless, no-argument
`ConstraintValidator`.

For server-validated live forms, place `validationMessage("field-name")` beside
each named control and `validationSummary()` inside the form. Throw a
`ValidationException` whose field keys match control names. On a structured `422`,
the browser runtime renders only text nodes, marks matching controls with
`aria-invalid`, merges message IDs into `aria-describedby`, and exposes the
summary as an alert. An input or change restores that field's previous state; a
new action clears the previous form validation. Validation emits the distinct
`roots:validation` browser event and does not emit `roots:error`.

Validation payloads accept at most 128 fields, 16 messages per field, 256 total
messages, and 2,048 characters per message. Field names are printable, non-blank,
and at most 128 characters. These limits are enforced when constructing the Java
exception and rechecked at the browser protocol boundary.

`UploadedFile` provides repeatable `openStream()`, `transferTo(...)`, filename,
content type, size, and text-decoding helpers. Small requests remain in heap;
larger requests spool to a request-scoped file and uploads reference bounded
slices without copying. `content()` is convenient but deliberately allocates the
whole file. Roots invalidates request-scoped content after the synchronous
pipeline, so transfer or consume accepted files before returning. Filenames and
content types came from the client; validate them for the application and never
use a filename as a direct path. Configure the total limit with
`maxRequestBytes`, and configure heap threshold, per-text-part limit, and temp
directory with `RequestBodyPolicy` or the corresponding builder methods.

## Request tracing and completion

Roots accepts the W3C `traceparent` header on every HTTP exchange. A valid trace
ID and sampling recommendation are continued with a new server span; malformed,
zero, uppercase, unsupported, or oversized values start a fresh random trace.
`Request.traceContext()`, `PageContext.traceContext()`, and
`ActionEvent.traceContext()` refer to the current request span. The action render
uses the same span as its handler.

The response includes that server `traceparent` for direct correlation. This is a
Roots response convention; normal downstream propagation still sends the value
as a request header. Register completion handling with
`RootsConfig.Builder.observeRequests`. Observations use the logical page path for
validated actions and streams while preserving the physical endpoint as
`transportPath`. They never contain request header values, query values, bodies,
forms, uploads, cookies, sessions, or identities.

## Application cookies

`Request.cookies()` exposes an immutable map of valid cookie pairs to middleware
and API routes; `Request.cookie(name)` returns one value. Parsing is deliberately
tolerant at the request boundary: malformed pairs are ignored, a duplicate keeps
its first value, and no application receives raw control characters through the
typed API.

`PageContext.cookies()` contains the initial page request and is atomically
replaced with the latest action request before its handler and rerender.
Background updates retain that latest snapshot. `ActionEvent.cookies()` describes
the exact action request.

Construct response cookies with `ResponseCookie`, then append them through
`Response.withCookie(...)` or `ActionEvent.setCookie(...)`. The builder emits one
field per cookie and rejects ambiguous values rather than encoding them. Its
defaults are root path, HttpOnly, and SameSite=Lax. Set `Secure` explicitly in
HTTPS deployments; SameSite=None and Partitioned require it. Modern secure/host/
HTTP prefix invariants are enforced.

An action may queue at most 32 cookies. They are committed only when the action
and authoritative render succeed; mapped failures do not leak partial cookie
state. Use `event.cookiePath()` when scoping a cookie to the application. It is
`/` for an origin-root application and the exact combined context/servlet path
for a mounted deployment. `event.deleteCookie(name)` removes a host-only cookie at
that path. Explicitly reproduce Domain and Partitioned attributes when expiring a
cookie that used them.

## Reverse proxies and rate limiting

Every transport supplies a direct `ClientConnection`. Forwarding headers are
ignored unless `RootsConfig.Builder.proxyPolicy(...)` explicitly trusts the
direct peer. `ProxyPolicy.trusted(...)` accepts validated IPv4 and IPv6 CIDRs,
prefers the standardized `Forwarded` header, falls back to
`X-Forwarded-For`, `-Proto`, `-Host`, and `-Port`, and walks multi-hop address
chains from right to left. Malformed, ambiguous, non-numeric, oversized, or
overlong chains are ignored as a unit; parsing never performs DNS resolution.

`Request.connection()`, `PageContext.connection()`, and
`ActionEvent.connection()` expose the resolved numeric client address, external
HTTP(S) origin, and whether trusted forwarding was applied. A live action updates
the retained page context before rerendering, so both the action and render see
the current request's connection. Trusting a CIDR asserts that every proxy in
that range sanitizes or correctly appends forwarding metadata. Never configure
an all-address range merely to make a header work.

`RootsConfig.Builder.rateLimiter(...)` installs a transport-neutral admission
SPI before body reads, multipart parsing, and session creation. The built-in
`RateLimiter.fixedWindow(requests, window, maxClients)` is node-local, exact
under concurrency, keyed by the resolved client address, and bounded by an exact
retained-key cap. Unknown clients share one key. If the key cap is full and no
window has expired, a new key is rejected rather than evicting an active entry
and allowing churn to bypass the limit.

Bounded decisions add `RateLimit-Limit`, `RateLimit-Remaining`, and
`RateLimit-Reset`. Rejections return `429`, `Retry-After`, `Cache-Control:
no-store`, and no session cookie. The health endpoint is exempt. Limiter failures
fail closed with a session-free `503` and increment the normal rejected-request
counter. A distributed deployment should provide a coordinated `RateLimiter`
implementation or accept per-node limits.

## Static page generation

`@Prerender` opts a public, deterministic page into static generation. A zero
`revalidateSeconds` value retains the packaged result for the deployment lifetime;
a positive value enables incremental regeneration. The first request after expiry
receives stale content while one virtual-thread render refreshes the entry. A
failed render retains the last good HTML and backs off before retrying.

Dynamic routes must name a `StaticPathProvider`:

```java
@Prerender(paths = ProductPaths.class, revalidateSeconds = 300)
public final class Page implements com.chaplin.roots.Page {
    // ...
}

public final class ProductPaths implements StaticPathProvider {
    public List<Map<String, String>> paths() {
        return List.of(Map.of("productId", "north-star"));
    }
}
```

The provider maps must exactly match the route parameters. Roots URL-encodes each
value, rejects duplicates, reserved framework paths, and paths shadowed by a more
specific page. Static routes cannot declare a provider.

Generated pages contain no browser driver, live token, session cookie, or action
binding. They bypass middleware and cannot use page/layout `@Authorize`, server
actions, session state, `AsyncComponent`, or `Portal`. Build and startup fail
instead of silently making those features inert. Query strings do not vary the
generated representation; use a live page when output is personalized, protected,
or query-dependent.

The Maven goal is configured explicitly:

```xml
<plugin>
  <groupId>com.chaplin.roots</groupId>
  <artifactId>roots-maven-plugin</artifactId>
  <version>${roots.version}</version>
  <executions>
    <execution>
      <phase>process-classes</phase>
      <goals><goal>prerender</goal></goals>
      <configuration><applicationClass>com.acme.Application</applicationClass></configuration>
    </execution>
  </executions>
</plugin>
```

At runtime, Roots loads the application-specific manifest and packaged HTML. A
production app without compiled output generates the same pages once at startup.
Development mode always follows the live rendering path.

## Modals

Use `Html.modal` for application dialogs instead of assembling a portal and a
`dialog` by hand. A modal is controlled by Java state: render it while open and
remove it after the close action updates that state.

```java
return modalOpen
        ? modal(
                "account-settings",
                "Account settings",
                this,
                "closeSettings",
                p("Changes are saved to your profile").id("settings-help"),
                input().ref(nameInput),
                button("Close").onClick(this, "closeSettings")
        ).initialFocus(nameInput)
         .describedBy("settings-help")
         .className("settings-dialog")
        : fragment();
```

The title is mandatory, visible, and wired to the native `dialog` through
`aria-labelledby`. Roots moves the modal into a body-level portal, calls
`showModal()`, selects the requested initial focus target or a safe fallback,
contains Tab navigation, and restores focus to the connected opener after the
server-authoritative close removes the modal.

Native Escape cancellation invokes the dismiss action as a `keydown` event with
the `Escape` key. A backdrop click invokes the same action as a `click` event.
Use `.dismissOnBackdrop(false)` when an accidental outside click must not close
the dialog; Escape remains enabled. Keep a visible close button bound to the
same action, and keep validation failures rendered so focus and entered values
remain available. Style the overlay with `dialog::backdrop`.

Modal IDs must be stable within a render. Because every modal owns a portal,
modals cannot be nested inside another `Portal` or `Modal`; render sibling modal
state at the page or layout level instead.

## Public assets

Classpath resources under `public` are exposed at the root URL. Application page
and API routes win when the same URL exists. Assets are deliberately public: they
bypass middleware and do not create sessions.

Roots assigns conservative content types. Development mode reloads bytes for each
request and sends `Cache-Control: no-store`. Production mode keeps resolved bytes
in memory, sends `Cache-Control: public, max-age=3600` with a SHA-256 `ETag`, and
returns `304` for matching `If-None-Match` requests. Missing resources are not
cached.

Known text, HTML, JSON, XML, manifest, image, font, and PDF extensions receive
their standard content types; unknown extensions remain
`application/octet-stream`. Static HTML receives the same configured CSP as every
other HTML response and remains session-free.

Use `Html.image(source, alt, width, height)` for optimized local PNG/JPEG output.
The fluent node supports `widths(...)`, `sizes(...)`, `quality(...)`, `priority()`,
CSS classes, and safe non-structural attributes. Roots owns its `src`, `srcset`,
dimensions, alternative text, loading, decoding, and priority attributes. Sources
must be root-relative files under `public`; Roots never fetches a remote URL.
Production transformations use a bounded in-memory LRU, one-day response caching,
SHA-256 ETags, and conditional responses. Development transformations are
uncached. Requests are bounded to 20 MiB source bytes, 40 million decoded source
pixels, 4096-pixel output dimensions, 12 responsive widths, and quality 1-100.

## Deployment mount paths

Logical page and API routes always begin at `/`, regardless of a Servlet context
or mapping. In Boot Servlet mode, `roots.servlet-path=/roots` maps that logical
root beneath `/roots/*`; `server.servlet.context-path=/company` makes the external
mount `/company/roots`.

Roots automatically prefixes generated root-relative `href`, `src`, `srcset`, `action`,
`formaction`, and `poster` attributes, metadata stylesheet paths, framework
actions/streams/assets, redirects, and prerendered documents. The session cookie
uses the same mount path. `Request`, `PageContext`, and `ActionEvent` expose
`mountPath()` and `url(...)` when application code needs the external prefix or
constructs a URL outside a normal rendered attribute. Relative, fragment,
absolute, and protocol-relative URLs are left unchanged. Raw HTML and URLs inside
CSS remain application-owned and should use relative URLs or an explicitly
resolved mount path.
