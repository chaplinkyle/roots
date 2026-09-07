# Deploying Roots

Build an executable artifact once and promote it between environments. The shipped
enterprise application is an in-memory evaluation example. The cloud templates
instead use the durable stateless automation inbox with an external PostgreSQL
database. Choose the artifact and application contract explicitly.

| Architecture | Template |
|---|---|
| Single container | [Java container](container/README.md) |
| Two JVMs with affinity and SSE | [Compose + HAProxy](compose/README.md) |
| Linux VM / on-premises host | [systemd service](systemd/README.md) |
| Kubernetes with TLS and stable owner routing | [StatefulSet + HAProxy](kubernetes/README.md) |
| AWS private Fargate tasks + TLS ALB | [CloudFormation](aws/README.md) |
| Azure Container Apps + Key Vault | [Bicep](azure/README.md) |
| GCP Cloud Run + Secret Manager + Direct VPC | [Service YAML](gcp/README.md) |
| Existing Servlet / on-premises Tomcat | [Packaged WAR and container](servlet/README.md) |
| Spring Boot platform | [Durable workflow](../examples/workflow/README.md) |
| Kanban application + new private AWS PostgreSQL | [Kanban Fargate/RDS deployment](kanban/README.md) |

The automation cloud templates require your existing network, database, identity, registry, and
secret resources. They are reviewable deployment inputs, not evidence of a cloud
deployment. The [implementation plan](../docs/enterprise-implementation-plan.md)
records local validation and outstanding verification.

## Environment configuration

The marker-class `Roots.run/start` helpers apply the environment before command-line
arguments. Explicit builders opt in; later builder calls override earlier values:

```java
Roots.run(RootsConfig.forApplication(Application.class)
        .environment().arguments(args).build());
```

Boot applications retain the starter's `roots.*` binding and container lifecycle;
this helper does not replace Boot configuration.

| Variable | Format |
|---|---|
| `ROOTS_HOST`, `ROOTS_PORT` | Bind address, integer port |
| `ROOTS_DEVELOPMENT`, `ROOTS_SECURE_COOKIES` | `true` or `false` |
| `ROOTS_VIEW_TIMEOUT`, `ROOTS_SESSION_TIMEOUT` | Positive ISO-8601 duration, e.g. `PT30M` |
| `ROOTS_MAX_REQUEST_BYTES` | Positive bytes |
| `ROOTS_MAX_CONCURRENT_REQUESTS`, `ROOTS_MAX_LIVE_VIEWS`, `ROOTS_MAX_SESSIONS` | Positive capacities; requests must exceed views |
| `ROOTS_REQUEST_BODY_MEMORY_THRESHOLD` | Heap threshold in bytes |
| `ROOTS_MAX_MULTIPART_TEXT_FIELD_BYTES` | Text-field byte limit |
| `ROOTS_REQUEST_BODY_TEMPORARY_DIRECTORY` | Private writable temporary directory |
| `ROOTS_CONTENT_SECURITY_POLICY` | Validated CSP header value |
| `ROOTS_TRUSTED_PROXIES` | Comma-separated numeric IPv4/IPv6 CIDRs |
| `ROOTS_SHUTDOWN_TIMEOUT` | `Roots.run` drain bound: zero through `PT1H`, default `PT30S` |

Malformed recognized values fail startup. Environment configuration does not
provision authentication, databases, secrets, certificates, or shared component state.

## Operational contract

1. Terminate TLS at a reviewed proxy, enable secure application/affinity cookies,
   restrict backend ingress, and rebuild forwarding headers. Trust only real proxy CIDRs.
2. Route a browser to the JVM owning its live view. Shared sessions/cache/leases do
   not migrate component graphs; JVM loss may require a fresh document.
3. Stream SSE without buffering and allow idle intervals longer than the heartbeat.
   Disable automatic mutation retries at every proxy/service-mesh hop.
4. Probe `/_roots/health` for readiness. SIGTERM lowers readiness and drains. Allow
   more supervisor time than the drain bound. Shutdown does not preserve live views.
5. Budget heap/native memory, component graphs, request spooling, pools, and probe
   processes together. Configure capacities and load test representative pages.
6. Use platform secret facilities and one reviewed migration step before rollout.
   Maintain tested backups and compatible schemas; see [JDBC requirements](../docs/jdbc.md).
7. Export observations/Boot metrics. Monitor latency, GC, views/SSE, admission
   rejection, pools, temporary storage, readiness, and uncertain action outcomes.
8. Promote immutable image digests/versioned JARs. Rollback changes the artifact,
   not automatically the data; existing tabs may reload after either direction.

References: [Compose service controls](https://docs.docker.com/reference/compose-file/services/)
and [HAProxy cookie persistence/timeouts](https://www.haproxy.com/documentation/haproxy-configuration-manual/latest/).
