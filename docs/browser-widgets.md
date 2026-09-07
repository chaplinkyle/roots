# Browser widgets

Roots can host an editor, chart, map, or other JavaScript library in a keyed DOM
container. The library remains ordinary browser code; Roots does not require npm,
a bundler, an inline script, or `eval`. The runnable enterprise example at
`/latency` shows an SVG chart whose selected range survives Java actions.

```java
div(p("Loading chart…"))
    .widget("sales-chart", "/widgets/sales.js")
    .data("widget-values", "12,19,15");
```

Place the module in `src/main/resources/public/widgets/sales.js`:

```js
export function mount(host, { props, signal, setDirty }) {
  const output = document.createElement('output');
  host.replaceChildren(output);
  const draw = next => { output.textContent = next.values; };
  draw(props);
  // Register listeners with { signal }; abort cancels them on removal/failure.
  return {
    update(next) { draw(next); },
    destroy() { host.replaceChildren(); }
  };
}
```

## Ownership and lifecycle

- Java owns the host's attributes. JavaScript owns its descendants. Do not change
  the host identity or move it; do not mount nested Roots widgets or server-action
  bindings inside it. Keep native form fields and server buttons outside it.
- `widget(key, moduleUrl)` requires a stable key unique among its siblings,
  nonblank and at most 256 characters, and
  a same-origin application path starting with `/`, at most 2,048 characters,
  without a fragment, backslash, or authority. The Servlet context path is added
  automatically. Hosts must be `div`, `span`, `section`, `article`, `aside`,
  `figure`, or `main`. Fallback children are static HTML/text, without components,
  nested widgets, raw HTML, portals, scripts, or interactive controls.
- `props` is a frozen object of string values from `data-widget-*` attributes;
  `data-widget-values` becomes `props.values`. Parse and validate these explicitly.
  Java `.data(name, true)` produces an empty string; `false` removes the attribute.
- `mount` runs once per host identity and returns `{ update, destroy }`, optionally
  through a promise. Same-key patches and sibling reorder preserve the host and
  descendants. Changing key, module URL, or host tag remounts. Navigation disposes
  the previous view's widgets, including portals.
- Updates run serially; while asynchronous work is pending, only the newest props
  are retained. This is a state snapshot, not an event log. Each mount (including
  import) and update has a 30-second deadline. Honor `signal.aborted` after awaits
  before touching DOM or applying results. Roots cannot terminate arbitrary JS.
- Cleanup aborts the signal and calls `destroy` before normal DOM removal. A mount
  that finishes after removal has its returned handle destroyed. Release listeners,
  observers, timers, library instances, and network work. Complete DOM cleanup
  synchronously; asynchronous resource cleanup may finish later. Do not mutate the
  host after an asynchronous destroy continuation. Browser/process termination
  does not guarantee cleanup callbacks. BFCache restores preserve instances.
- Module imports are cached per document, including failures, with a 64-URL limit.
  Use a stable, versioned asset URL; do not generate a different URL per render.
  Reload to retry a failed module. A changed URL creates a new instance.

## Forms, drafts, and failure

Bridge a custom editor to a native hidden field **outside** the widget, in a Roots
form. On a user edit, set `field.value` and dispatch a bubbling native `input`
event; this marks the value as locally edited, protecting it across unrelated
patches. Submit the ordinary form to a Java `@ServerAction` and validate with
`ActionEvent.bind`. There is no additional browser mutation API or authentication
bypass. The browser fixture tests this bridge through validation and server patches.

```js
editor.addEventListener('input', () => {
  field.value = editor.textContent;
  field.dispatchEvent(new Event('input', { bubbles: true }));
  setDirty(true);
}, { signal });
```

For editors, call `setDirty(true)` when local content is unsaved, and clear it only
when a server acknowledgment matches the submitted content/version and there is
no newer edit. Mount/update hooks must not dispatch synthetic user-input events
in response to server props (including after awaits): that creates feedback loops.
Server acknowledgment must not overwrite newer editor content. Persist valuable
drafts in application storage; arbitrary library state is not serialized by Roots.

An import/mount/update failure aborts and disposes the instance. An unedited widget
returns to its latest static fallback. An edited widget retains a DOM copy of its
visible content with a copy-before-reload notice; library listeners/state are gone.
This is emergency copy recovery, not an operational editor or durable backup.
Listen on `window` for `roots:widget-error` with `{ host, module, phase, error }`
and disable submission that depends on the failed widget until the user recovers.
Other page actions remain available. View expiry will not automatically reload
and discard a widget marked dirty. Explicit navigation/removal remains intentional.

## CSP and trust

The default Roots CSP permits same-origin external modules. Keep modules and their
imports on that origin, serve them with a JavaScript content type, and package CSS
as external stylesheets. Libraries requiring inline styles, remote scripts, or
`eval` need their own CSP assessment. Never select executable module URLs from
untrusted request data. This is a DOM ownership contract, not a security sandbox;
loaded modules have the page's privileges.

Browser standards: [dynamic import](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Operators/import),
[AbortSignal](https://developer.mozilla.org/en-US/docs/Web/API/AbortSignal), and
[CSP script-src](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/script-src).
