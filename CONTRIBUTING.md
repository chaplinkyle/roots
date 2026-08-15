# Contributing to Roots

Roots is experimental and the public API is still moving. Small, focused changes
with tests are easier to review than broad rewrites.

## Development setup

Requirements:

- JDK 26;
- Git;
- no global Maven installation is required.

Run the full reactor:

```bash
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

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
- New features must update the feature contract and relevant example.

## Pull requests

1. Explain the user problem and the chosen design.
2. Add or update focused unit tests.
3. Add an end-to-end test when routing, sessions, actions, patches, or assets change.
4. Run `./mvnw clean verify`.
5. Update documentation for public behavior.

Use GitHub issues for feature proposals. Report vulnerabilities privately as
described in [SECURITY.md](SECURITY.md).
