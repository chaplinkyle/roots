# React and Next.js feature contract

Roots aims for equivalent application capabilities, not API mimicry. Java already has classes, records, exceptions, threads, annotations, and structured control flow, so reproducing hook syntax would be worse Java.

## React-class component semantics

| Capability | Roots equivalent | Status |
|---|---|---|
| Function/class components | `Component` | Implemented |
| Props | constructor arguments and records | Implemented |
| Children/composition | any `Node` accepted as a child | Implemented |
| Local state | retained fields and `State<T>` | Implemented |
| Events | lambdas or `@ServerAction` | Implemented for click, submit, change |
| Conditional rendering | Java `if`, `switch`, ternary | Implemented |
| Lists | streams, collections, `Iterable` children | Implemented |
| Keys | `Element.key(...)` | Implemented |
| Context | `ContextKey<T>` via `PageContext` | Implemented |
| Memoization | `Memo<T>` | Implemented |
| Mount/unmount effects | component lifecycle methods | Implemented on server |
| Error boundaries | `ErrorBoundary` / `Html.boundary` | Implemented |
| Suspense-style fallback | `AsyncComponent<T>` + SSE | Implemented |
| Refs | `Ref` and action effects | Implemented for focus/scroll/clipboard |
| Concurrent stale-result protection | per-view action queue + revisions | Implemented |
| Virtual DOM reconciliation | keyed DOM morphing | Implemented as HTML reconciliation |
| Portals | a first-class overlay/dialog target | Planned |
| Optimistic state / transitions | explicit optimistic patch API | Planned |
| Arbitrary browser effects | typed capability API | Partial; no raw JS hook by design |
| Offline/local-first state | client-side Java/Wasm island | Research; not part of server model |

There is no hydration step: the server remains authoritative and the runtime attaches delegated events once. There is no rules-of-hooks concept because state and lifecycle belong to ordinary Java object identity.

## Next.js-class framework semantics

| Capability | Roots equivalent | Status |
|---|---|---|
| App entry | `Roots.run` | Implemented |
| File-system pages | package/source conventions | Implemented |
| Nested layouts | `Layout.java` hierarchy | Implemented |
| Dynamic/catch-all routes | `$id` / `$$path` packages | Implemented |
| Route overrides | `@Route` | Implemented |
| Metadata | `@PageMetadata` / method | Implemented |
| Server rendering | Java HTML tree | Implemented |
| Server actions | `@ServerAction` | Implemented |
| Route handlers | `ApiRoute` | Implemented |
| Static assets | `resources/public` | Implemented |
| Client navigation | Roots links + History API | Implemented |
| Streaming updates | SSE live patches | Implemented |
| Loading/error UI | async fallback / error boundary | Implemented |
| New-project CLI | Maven archetype | Implemented |
| Development hot reload | source watcher and classloader restart | Planned |
| Middleware/guards | typed request pipeline | Planned |
| Static generation/ISR | build-time page renderer and cache policy | Planned |
| Cache tags/revalidation | cache SPI | Planned |
| Image/font optimization | build/runtime asset pipeline | Planned |
| Deployment adapters | JDK server now; servlet/Netty adapters | Planned |
| Compile-time route manifest | annotation processor | Planned |

The planned rows are required before Roots can honestly call itself a broad Next.js replacement. The implemented rows form a coherent live enterprise application framework today.
