# Prior art and positioning

Roots is an independent implementation, but it does not claim to have invented
server-driven UI, Java web components, or HTML diffing.

The current repository contains no vendored framework source. `roots-core` has
no runtime dependency on another web framework or JavaScript package, and its
browser driver is implemented directly against standard browser APIs. That is a
repository-level observation, not a legal opinion about patents, trademarks, or
the originality of every general-purpose programming technique.

## Relevant predecessors

| Project | Established idea | How Roots differs |
|---|---|---|
| [Vaadin Flow](https://vaadin.com/docs/latest/flow/what-is-flow) | Java-authored UI with server-side component objects synchronized to browser elements | Roots emits semantic HTML trees and HTML patches, has no component suite, and keeps a much smaller JDK-only core |
| [Apache Wicket](https://wicket.apache.org/) | Stateful, component-oriented, server-side Java web applications without application JavaScript | Roots uses Java HTML construction instead of paired markup and adopts package routing and live-view conventions |
| [Jakarta Faces](https://jakarta.ee/specifications/faces/) | Standardized server-side UI components, lifecycle, events, and Ajax updates | Roots is not a Jakarta specification; its optional Servlet adapter exposes a deliberately smaller runtime lifecycle |
| [Phoenix LiveView](https://hexdocs.pm/phoenix_live_view/Phoenix.LiveView.html) | Stateful server views that receive events, rerender, and push page updates | Roots applies the model to ordinary Java objects, virtual threads, annotations, and Maven conventions |
| [Next.js App Router](https://nextjs.org/docs/app) | Convention-based pages, layouts, route handlers, metadata, loading, and error UI | Roots borrows the product ergonomics, not React, JavaScript, or the Next.js implementation |

Other systems such as ZK, htmx, Hotwire, Blazor Server, and server-side UI
frameworks also occupy nearby design space.

## The Roots bet

The differentiator is the combination, not any single primitive:

- one application language for UI behavior, services, and APIs;
- a dependency-free JDK runtime rather than a broad platform;
- package conventions that feel natural in Java;
- server-side components built from normal classes and records;
- annotation-driven actions and metadata;
- full initial HTML with no hydration;
- a small, owned browser runtime that reconciles HTML;
- an archetype that produces an ordinary executable Maven project.

That combination can still be valuable without being historically unprecedented.
The standard for Roots should be clarity, small size, interoperability, and a
better Java developer experience—not novelty marketing.
