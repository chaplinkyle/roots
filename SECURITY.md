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

Roots currently provides output escaping, a restrictive Content Security Policy,
session-bound CSRF tokens, HttpOnly/SameSite cookies, same-origin browser
transport, request-size limits, and server-registered live-view actions.

It does not yet provide authentication, authorization, rate limiting, secure-cookie
proxy detection, dependency scanning policy, or a hardened production server
adapter. Deployments must supply those controls and should follow
[Production readiness](docs/production-readiness.md).
