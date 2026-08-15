# Roots conventions

An application calls `Roots.run(Application.class, arguments)`. If `Application` is in `com.acme`, Roots scans `com.acme.pages` and `com.acme.api`.

## Pages and layouts

Every concrete `Page` class named `Page` becomes a route. Every concrete `Layout` class named `Layout` wraps pages in its package and all descendant packages. Layouts nest from the application root toward the page.

| Package suffix | Segment |
|---|---|
| `customers` | `/customers` |
| `customer_admin` | `/customer-admin` |
| `$customerId` | `/{customerId}` |
| `$$path` | `/{*path}` and must be last |
| `group_admin` | no URL segment |

Static routes win over dynamic routes. Duplicate resolved routes fail at startup.

Use `@Route` when a URL cannot or should not follow the package:

```java
@Route("/activity")
public final class Page implements dev.roots.Page { ... }
```

Explicit templates accept `{parameter}` and a final `{*catchAll}`.

## Metadata

Static metadata is annotation-first:

```java
@PageMetadata(
    title = "Customers",
    description = "Manage customer accounts",
    stylesheets = "/app.css"
)
```

Override `metadata(PageContext)` for route-dependent values. Do not combine that method with `@PageMetadata`; the annotation intentionally wins.

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

## Public assets

Classpath resources under `public` are exposed at the root URL. Roots assigns conservative content types and disables caching in development mode.
