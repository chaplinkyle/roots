# Browser protocol version 1

Roots ships the browser driver and server implementation together, but an open tab
can outlive a deployment. The live protocol is therefore explicit and fail-closed.
Its current value is `Roots.PROTOCOL_VERSION == "1"`.

## Version advertisement

A live HTML document carries `data-roots-protocol="1"` on `#roots`. Every
framework protocol HTTP response carries `X-Roots-Protocol: 1`. Live documents
and protocol responses also carry `X-Roots-Node` with the serving node's stable
identifier. Successful patch, redirect, and inspector JSON include
`"protocol":"1"`.

The bundled driver sends the document's version in these locations:

| Endpoint | Method | Version location |
|---|---:|---|
| `/_roots/action` | `POST` | `_protocol` form or multipart field |
| `/_roots/dispose` | `POST` | `_protocol` form field |
| `/_roots/stream` | `GET` | `protocol` query parameter |
| `/_roots/inspect` | `GET` | `protocol` query parameter |

The stream and inspector also receive `view` and `csrf` query values. Actions and
disposal receive `_view` and `_csrf` fields. Action-only fields are `_action`,
`_event`, and the bounded optional `_event_*` metadata described in
[Conventions](conventions.md). Fields without a leading underscore are
application form values; uploaded application fields follow the same rule.

## Successful action response

A normal action response is JSON with this required shape:

```json
{
  "protocol": "1",
  "view": "01J...",
  "html": "<section>...</section>",
  "scope": "c0",
  "title": "Page title",
  "description": "Page description",
  "head": "<meta name=\"description\" data-roots-head=\"description\" content=\"Page description\">",
  "baseRevision": 4,
  "revision": 5,
  "effects": []
}
```

`view` is the required live-view ID that produced the response. `html` may be
`null` when the DOM is unchanged. `scope` is either `null` for the
live root or an exact component boundary identifier. A scoped patch is applied
only when `baseRevision` equals the browser's current revision and the one expected
boundary exists. `head` is escaped server-generated markup containing only
framework-managed description, canonical, robots, theme-color, and Open Graph
elements. The browser replaces the complete managed set, so removed declarations
cannot leak from an older render. `description` remains a required compatibility
field; current clients consume its equivalent managed element from `head`.
A redirect response contains required `protocol`, `view`,
`redirect`, and `effects` fields instead. Effects are objects with a required string `type`
and nullable string `target` and `value` fields. Version 1 defines `FOCUS`,
`BLUR`, `SELECT_TEXT`, `SCROLL_INTO_VIEW`, `COPY_TO_CLIPBOARD`,
`ANNOUNCE_POLITE`, `ANNOUNCE_ASSERTIVE`, and `VIEW_TRANSITION`. The four
element-directed effects require a ref target of at most 128 validated
characters and no value. Clipboard text is limited to 16,384 characters.
Announcement text is nonblank, limited to 2,048 characters, has no target, and
is written to a permanent polite or assertive ARIA live region. View transition
requires neither target nor value. Each action carries at most 32 effects.

The additional effect types are additive within protocol version 1: an older
driver ignores unknown effect types while still applying the authoritative
patch. The current driver also treats a missing target or unsupported browser
capability as a safe no-op. A completed announcement emits `roots:announce`
with `{message, priority}` detail.

The bundled driver treats a validated application redirect as Roots client
navigation rather than an unconditional document load. It normalizes the path
against the current origin, preserves optional fragments, uses the same
latest-wins/disposal checks as a link, and carries `VIEW_TRANSITION` intent into
the navigation commit. A fetch/protocol/non-Roots failure still falls back to a
normal browser navigation.

Validation failures use HTTP `422` and the bounded validation error schema in
[Conventions](conventions.md). Other failures contain a safe `error` string. The
response header still advertises the server protocol even when the response body
is not a patch.

## Live stream

`/_roots/stream` is `text/event-stream`. On every accepted connection the server
sends a comment followed by a complete, idempotent `patch` event. Later `patch`
events use the same JSON shape. This initial snapshot is the reconnect catch-up
contract; clients need not replay missed event identifiers.

Only one stream lease may own a view. A replacement connection interrupts the old
lease. Heartbeat comments keep intermediaries from treating a quiet stream as
idle.

## Node affinity

Live component graphs remain in the JVM that rendered them. `LiveViewOwnership`
records a time-bounded `(node, session, expiry)` lease for each view; Roots claims
it before publishing the view, renews it on actions, inspector access, stream
attachment, and stream activity, and releases it on disposal, expiry, failed
construction, or shutdown.

When a well-formed request reaches another node and the lease belongs to the same
session, action, disposal, stream, and inspector handling return HTTP `409`,
`Cache-Control: no-store`, `X-Roots-Owner`, and this retry-safe body:

```json
{"error":"This live view belongs to another node","retry":true,"owner":"node-a"}
```

The owner is never disclosed to a different session. Action and disposal
affinity is diagnosed before application middleware can replace the routing
signal. Registry availability failures fail closed with `503`.

This is an affinity contract, not live-state migration. The version-1 browser
driver does not translate a node identifier into a URL or automatically move a
view. A multi-node deployment must provide a shared `LiveViewOwnership`
implementation and configure its load balancer or proxy for sticky/owner-aware
routing. If the owning process dies, the browser must load a new document; an
ordinary Java component graph is not serialized or failed over.

## Version mismatch

An absent or unsupported action/inspector version returns HTTP `409`,
`Cache-Control: no-store`, `X-Roots-Protocol`, and a reload instruction:

```json
{"error":"Roots protocol version mismatch","reload":true,"protocol":"1"}
```

An absent or unsupported stream version receives one `reload` SSE event containing
the server version and then closes. The version-1 driver reloads on either signal.
Disposal rejects a mismatch without disposing the referenced live view.
Client navigation also compares the fetched document's protocol with the active
document before replacing the root. A difference performs a full navigation so
the matching browser driver is loaded with the new document.

Every current-version patch and action redirect is bound to the live view that
produced it. A missing/non-string `view` is malformed and causes a reload. A
well-formed payload for a view that is no longer active is ignored and emits the
observable `roots:stale` browser event with `{kind: "patch", view}` detail.
The driver also captures the originating root when an action is enqueued. Any
success, validation failure, authorization response, protocol error, or transport
failure that completes after that root is superseded is discarded before reload,
rollback, validation binding, error publication, effects, or redirect, and emits
`{kind: "action", view}`. A queued old-root action is discarded before transport;
it is never rebound to the current view's credentials or action table.
Rapid client navigations are latest-wins: an older response is parsed only far
enough to dispose its newly allocated but unused live view, then emits
`roots:stale` with `{kind: "navigation", view}`. It cannot replace the newer
document, alter history, run effects, or redirect it. This required identity is
an additive hardening of protocol version 1 because Roots ships the paired server
and driver together; it does not make an older wire shape safe to apply.

Protocol routes never create a new session when the session cookie is absent or
expired. Version checks supplement rather than replace session binding, constant-
time CSRF comparison, identity binding, authorization, mount-path binding, request
limits, or stale-revision checks.
