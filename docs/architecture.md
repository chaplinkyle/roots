# Architecture

Roots is server-driven, not a JVM implementation inside the browser.

## Render model

Each page load creates a live view containing one page instance, its layout instances, a typed context, the last action table, metadata, and a monotonically increasing revision. Stateful child components are retained when the page keeps their instances in fields.

Rendering recursively evaluates Java `Node` and `Component` objects into escaped HTML. Event bindings become opaque action names in `data-roots-on-*` attributes. The browser never receives reflected method names as authority: an action also requires the live-view ID, a high-entropy CSRF token, and the matching `HttpOnly` session cookie.

## Rerender model

Browser actions are serialized per tab. The server invokes the Java handler while holding the live-view mutation lock, rebuilds the tree, and returns HTML plus metadata and a revision. The browser runtime parses the fragment and morphs the existing DOM. Stable `data-roots-key` values preserve identity during insertions and reordering. Focus and form properties are restored after reconciliation.

Patches from background work travel over Server-Sent Events. `AsyncComponent` starts work on a virtual thread, renders its fallback immediately, and calls `PageContext.update` on completion. The revision check prevents a slower response from overwriting newer state.

## Lifecycle

`Component.onMount` runs the first time a component identity appears in a live tree. `onUnmount` runs when it disappears or the view expires. Page and layout lifecycle callbacks live for the entire view. A component freshly allocated inside every `render` is a new identity; keep lifecycle-aware or stateful components in fields.

## Browser boundary

The built-in browser runtime owns:

- delegated event capture;
- action transport;
- HTML parsing and keyed reconciliation;
- history navigation;
- SSE background patches;
- focus, scroll, and clipboard effects.

Application code does not import browser APIs or author JavaScript. This is the same kind of runtime boundary used by LiveView-style systems. Compiling arbitrary Java application code to WebAssembly is not a goal of the first architecture because it would reintroduce a second state model and a large client runtime.

The browser runtime is a Roots-owned vanilla JavaScript implementation embedded in
`ClientRuntime.java`. The current unminified response is about 9.7 KiB and has no
third-party client dependency.

## Current scaling model

Live views and sessions are process-local. One JVM or sticky sessions are required. The store boundary will become an SPI before 1.0; serialized component graphs are not assumed because ordinary enterprise components may hold services that should never be serialized.

See [Production readiness](production-readiness.md) for the practical deployment
boundary and [Integrations](integrations.md) for persistence and Spring guidance.
