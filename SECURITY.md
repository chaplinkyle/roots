# Security policy

Roots 0.1 is pre-release software and has no production-supported version yet.
Security reports are still taken seriously.

## Reporting a vulnerability

Use GitHub's **Security → Report a vulnerability** flow for this repository.
Please do not open a public issue for a suspected vulnerability.

Include:

- the affected version or commit;
- a minimal reproduction;
- impact and expected behavior;
- any suggested mitigation;
- whether the report may be credited publicly.

Do not include real credentials, customer data, or destructive proof-of-concept
payloads. You should receive an acknowledgement within seven days.

## Current security boundary

Roots currently provides output escaping, a restrictive configurable Content Security Policy,
session-bound CSRF tokens, HttpOnly/SameSite cookies, same-origin browser
transport, request-size limits, server-registered live-view actions, and
fail-closed named `@Authorize` policies. Policy enforcement covers initial route
dispatch, live actions before mutation, and protected SSE streams.

The configured CSP is applied centrally to every `text/html` response: framework
pages and errors, application HTML responses, and static HTML assets. The default
permits only same-origin scripts, styles, connections, and form actions; blocks
objects and framing; and restricts base URLs. Custom policies are bounded and
reject control characters. Treat policy relaxation as an application security
decision and test it against every deployed integration.

Multipart parsing rejects malformed framing and headers, caps each request at the
configured byte limit, caps part count and header size, and preserves file bytes
without interpreting them. Uploaded filenames and content types remain untrusted
client metadata. Applications must validate allowed types/content, scan when
appropriate, generate storage keys, and never join an uploaded filename directly
to a filesystem path.

Roots provides an authentication-provider SPI and strict Bearer parsing, named
authorization policies, a bounded node-local rate limiter, explicit proxy trust,
and a Jakarta Servlet adapter. Credential verification and identity storage,
cross-node rate limiting, TLS termination, dependency scanning, and production
container hardening remain deployment responsibilities. Secure cookies must be
enabled explicitly when TLS terminates at a proxy. Deployments should follow
[Production readiness](docs/production-readiness.md).

`@Stateless` bypasses browser-session storage, not authentication or authorization.
Use verified machine identity or signed webhook verification and authorize every
operation, including idempotent replays. `WebhookVerifier` checks signatures and
timestamps; applications must atomically deduplicate verified deliveries with
their business writes. The in-memory idempotency store cannot survive process
loss or coordinate multiple nodes. Browser response deadlines cannot roll back
server mutations; uncertain outcomes are held without automatic retries.

SSE URLs contain view credentials. Proxy access logs must omit or redact query
strings, cookies, and authorization headers. The deployment proxy templates use
an explicit metadata-only log format. No application secret belongs in an image,
tracked environment file, problem detail, or diagnostic response.

Browser widgets load same-origin ES modules under the page CSP. Modules execute
with page privileges; the DOM ownership boundary is not a sandbox. Keep module
URLs application-controlled and audit third-party imports. Widget props and
hidden form fields are untrusted input at the server: validate and authorize
their mutations like all other actions. See [widget integration](docs/browser-widgets.md).
