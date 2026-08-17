# Application archetype

Until artifacts are published, run `mvn install` in the Roots repository. This
installs `roots-core`, `roots-dev`, `roots-processor`, `roots-maven-plugin`, and
`roots-archetype` in the local Maven repository.

## PowerShell

Quote every `-D` coordinate; PowerShell can otherwise truncate dotted Maven values when invoking a `.cmd` shim.

```powershell
mvn archetype:generate `
  '-DarchetypeGroupId=dev.roots' `
  '-DarchetypeArtifactId=roots-archetype' `
  '-DarchetypeVersion=0.1.0-SNAPSHOT' `
  '-DgroupId=com.example' `
  '-DartifactId=orders' `
  '-Dversion=1.0.0-SNAPSHOT' `
  '-Dpackage=com.example.orders' `
  '-DrootsVersion=0.1.0-SNAPSHOT' `
  '-DinteractiveMode=false' `
  '-DarchetypeCatalog=local'
```

## Bash

```bash
mvn archetype:generate \
  -DarchetypeGroupId=dev.roots \
  -DarchetypeArtifactId=roots-archetype \
  -DarchetypeVersion=0.1.0-SNAPSHOT \
  -DgroupId=com.example \
  -DartifactId=orders \
  -Dversion=1.0.0-SNAPSHOT \
  -Dpackage=com.example.orders \
  -DrootsVersion=0.1.0-SNAPSHOT \
  -DinteractiveMode=false \
  -DarchetypeCatalog=local
```

The generated project includes a root page, nested page, live not-found and safe
production-error pages, layout, stateful
annotated component, health API, stylesheet, JUnit smoke test, and shaded
executable-JAR packaging. Its application anchor and compiler configuration
enable compile-time route validation and package the deterministic route manifest
automatically. The included `/about` page demonstrates `@Prerender`; the configured
Roots Maven plugin generates and packages its static HTML during `mvn package`.

## Static generation

The generated POM binds `roots:prerender` to `process-classes` with the application
anchor. Add `@Prerender` to public, deterministic pages. Dynamic pages must supply
a `StaticPathProvider`. If construction needs application-specific dependency
injection or cache configuration, implement `PrerenderConfigFactory` and set the
plugin's `configFactory` parameter to its fully qualified class name.

Use `-Droots.skipPrerender=true` only for a build that deliberately omits generated
output. Development mode continues to render live pages and does not serve compiled
static artifacts.

## Development loop

From the generated project directory, run:

```shell
mvn compile exec:java@roots-dev
```

The configured MojoHaus Exec Maven Plugin runs `DevRunner` with the generated
application class. Roots watches `src/main/java` and `src/main/resources`,
debounces filesystem notifications, and performs a clean Maven compile. A good
build starts in a fresh child-first application classloader on the same port and
signals open pages to reload. A compile or startup failure retains or restores
an immutable last-good snapshot and sends a bounded, text-only diagnostic overlay.
The development page's **Roots** button (or `Ctrl+Shift+.`) opens the authenticated
live component/action inspector.

The watcher handles source and resource changes. Restart it after changing the
POM, dependencies, compiler configuration, environment variables, or JVM options.
Server-side component state is intentionally recreated on a successful restart.
`roots-dev` is `provided`, so it is not included in the shaded application JAR.
