# Contributing to Roots

Roots is experimental and the public API is still moving. Small, focused changes
with tests are easier to review than broad rewrites.

## Development setup

Requirements:

- JDK 26;
- Git;
- Chrome or Chromium for the Java-driven real-browser contract test;
- no global Maven installation is required.

Run the full reactor:

```bash
./mvnw clean install
```

On Windows:

```powershell
.\mvnw.cmd clean install
```

`roots-core` and `roots-spring-boot-starter` publish JaCoCo HTML reports below
their respective `target/site/jacoco` directories. Both enforce at least 80% line
coverage and 65% branch coverage; treat those as floors, not targets. New
protocol behavior also needs a failure-path assertion and real-browser evidence.
The package phase also runs full JDK doclint with warnings treated as errors and
attaches `roots-core-*-javadoc.jar` and `roots-core-*-sources.jar`. The install
phase stages the current reactor snapshots before the archetype's nested
generated-application build, ensuring that test uses the code under review. Every
exposed type, constructor, method, record component, generic parameter, and annotation
member therefore needs useful Javadoc before the reactor can pass.
The `roots-browser-tests` module uses Selenium Manager to resolve the matching
driver; Selenium is test-scoped and is not a Roots runtime dependency.
The default reactor also runs the bounded `roots-load-tests` gate. Longer soak
runs are opt-in; see [Load and soak testing](docs/load-testing.md) for the profile
and tuning properties.

Run the example:

```bash
java -jar examples/enterprise/target/roots-enterprise-example-0.1.0-SNAPSHOT-app.jar
```

## Design guardrails

- Application behavior should remain expressible in Java.
- Do not add a runtime dependency to `roots-core` without an explicit design discussion.
- Escape output by default; unsafe output must remain explicit.
- Preserve the server-authoritative state model and stale-revision protection.
- Public APIs need Javadoc and tests.
- Protocol changes need Java and browser-runtime tests together.
- Resource-owning changes need saturation, release, timeout, and shutdown-path tests.
- New features must update the feature contract and relevant example.

## Pull requests

1. Explain the user problem and the chosen design.
2. Add or update focused unit tests.
3. Add an end-to-end test when routing, sessions, actions, patches, or assets change.
4. Run `./mvnw clean install`.
5. Update documentation for public behavior.

Use GitHub issues for feature proposals. Report vulnerabilities privately as
described in [SECURITY.md](SECURITY.md).
