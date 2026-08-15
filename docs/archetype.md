# Application archetype

Until artifacts are published, run `mvn install` in the Roots repository. This installs both `roots-core` and `roots-archetype` in the local Maven repository.

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

The generated project includes a root page, nested page, layout, stateful annotated component, health API, stylesheet, JUnit smoke test, and shaded executable-JAR packaging.
