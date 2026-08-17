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

It does not provide an identity store or authentication provider, built-in policy
decisions, rate limiting, secure-cookie proxy detection, dependency scanning
policy, or a hardened production server adapter. Deployments must supply those
controls and should follow
[Production readiness](docs/production-readiness.md).
