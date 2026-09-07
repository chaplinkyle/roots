# GCP: stateless automation on Cloud Run

`automation.yaml` runs the [durable automation API](../../examples/automation/README.md)
using managed HTTPS, Secret Manager references, a dedicated service account,
Direct VPC egress to an existing PostgreSQL network, and bounded instances and
concurrency. It creates no database, VPC, registry, service account, secret, or IAM
grant. Deployment incurs Google Cloud charges; the template has not been executed
against a cloud project by this work.

## Prepare and deploy

Build the shared container with the automation `APP_JAR` and promote its reviewed
Linux/amd64 image digest to Artifact Registry. Copy the YAML to `.tooling/gcp-service.yaml`
and replace every `REPLACE_*` placeholder, including each pinned numeric secret
version. Set the existing network/subnet in the selected region. Configure service
agent network permissions, subnet address capacity, firewall rules, and private
PostgreSQL connectivity through your platform setup. The runtime service account
needs Secret Manager access only to these secrets. Restrict who may deploy or act
as that identity.

The JDBC URL uses the private database hostname, verified TLS with the correct CA,
and bounded connection/socket timeouts. This template uses ordinary PostgreSQL
JDBC over Direct VPC egress; it does not silently add a Cloud SQL connector or
create a database. Run migrations once from a controlled private-network runner
with migration credentials before deploying the service. The example's `--migrate`
bootstraps a new database; use reviewed versioned migrations for subsequent schema
changes. Schedule receipt pruning as a separate job. The command inbox does not
execute jobs in a Cloud Run request or rely on idle-instance background threads.

Review the fully substituted YAML, then deploy through your release process:

For local schema-shape validation with Python and PyYAML, run
`python deploy/gcp/validate.py deploy/gcp/automation.yaml`. It reads Google's
public discovery schema without credentials. `--discovery FILE` uses a saved
schema for offline checks. This validates resource property names/types, not
annotation semantics, IAM/network prerequisites, or cloud admission policies.

```sh
gcloud run services replace .tooling/gcp-service.yaml --project YOUR_PROJECT --region YOUR_REGION
gcloud run services describe roots-automation --project YOUR_PROJECT --region YOUR_REGION --format='value(status.url)'
```

This template explicitly disables the Cloud Run Invoker IAM check so the native
Bearer API and signed webhook can receive external requests. It does **not**
disable application authentication. Keep the IAM check enabled instead if your
organization requires it, and integrate callers/gateway authentication accordingly;
the provided machine client does not itself acquire Google identity tokens.
Organization policy may forbid public invocation. Review ingress and authentication
before deploying; do not grant broader IAM roles to bypass that policy.

Verify readiness, unauthorized-request rejection, authenticated submission,
signature rejection, and an unchanged command UUID retried across a revision
replacement. Cloud Run supplies startup and liveness probes here. Roots listener
health does not certify database health; add a synthetic transaction and pool
monitoring. Observe request latency/errors, instance count, memory/GC, pool waits,
and durable receipt results. Never log Bearer tokens, webhook secrets, or request
bodies. The local filesystem is ephemeral and consumes the container's resources.

## Lifetime, scaling, and rollback

The template uses one warm instance, at most four per revision, 32 concurrent
requests per instance, and eight database connections per application pool. These
are starting limits; deployment overlap and platform scaling behavior require
additional database headroom. The 30-second request timeout bounds the HTTP
response, not whether a transaction committed. Preserve command identity when
retrying an interrupted result. The Roots drain is eight seconds because Cloud
Run's SIGTERM grace period is ten seconds; a longer transaction may need recovery
from its durable receipt after termination.

Cloud Run affinity is best effort and request streams have finite lifetimes. This
sample deliberately deploys stateless machine APIs. For a live component UI, use
the Kubernetes/HAProxy topology on GKE or another platform with reviewed owner
routing, then verify SSE streaming and recovery during node loss. Neither minimum
instances nor affinity makes a live Java component graph durable.

Roll back by replacing the service with the previous reviewed image/configuration
or route all traffic to a retained compatible revision:

```sh
gcloud run services update-traffic roots-automation --project YOUR_PROJECT \
  --region YOUR_REGION --to-revisions PREVIOUS_REVISION=100
```

Check that the retained revision uses valid secret versions and compatible schema.
Traffic changes do not undo committed database work. Request-based CPU allocation
is intentional for this request-driven inbox; implement command execution in a
separate durable worker with its own lifecycle and retries.

References: [Cloud Run public invocation](https://docs.cloud.google.com/run/docs/authenticating/public),
[Direct VPC egress](https://docs.cloud.google.com/run/docs/configuring/vpc-direct-vpc),
[Secret Manager integration](https://docs.cloud.google.com/run/docs/configuring/services/secrets),
[container shutdown contract](https://docs.cloud.google.com/run/docs/container-contract),
[affinity limits](https://docs.cloud.google.com/run/docs/configuring/session-affinity).
