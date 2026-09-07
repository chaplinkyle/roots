# Azure: Container Apps with Key Vault references

`automation.bicep` deploys the [durable automation API](../../examples/automation/README.md)
into an existing Container Apps environment. It uses HTTPS-only ingress, two to
four replicas, one vCPU/2 GiB per replica, application readiness, a 45-second
termination grace period, and an existing user-assigned managed identity. It
creates no database, network, registry, vault, or role assignment. Running these
deployment commands creates billable resources; no Azure deployment has been
executed as part of local template verification.

## Prepare and review

Build the shared container with the automation `APP_JAR`, push it through your ACR
pipeline, and supply the immutable image digest. The existing identity needs ACR
pull access and Key Vault secret-read permissions scoped to the supplied secrets.
The Container Apps environment must reach Key Vault/ACR and the private PostgreSQL
endpoint and already have application logs/metrics configured. Enable registry
authentication through managed identity in your platform setup.

Copy `parameters.example.json` to an ignored local file and replace every
placeholder. Values are resource IDs and versioned Key Vault URLs, not secret
contents. PostgreSQL credentials remain application-owned; use verified TLS,
trusted CA material, and bounded driver timeouts. Apply migrations once with a
separate migration identity from a controlled runner that can reach the database.
For a new example database, invoke the same container with `--migrate`; do not add
it as per-replica initialization. Use a reviewed versioned migration process as the
application evolves. Schedule `--prune-receipts` separately and define inbox
retention/worker behavior in the application.

With Azure CLI authenticated to the intended subscription:

```sh
az bicep build --file deploy/azure/automation.bicep --outfile .tooling/azure-template.json
az deployment group what-if --resource-group YOUR_APPLICATION_GROUP \
  --template-file deploy/azure/automation.bicep --parameters @.tooling/azure-parameters.json
# Deploy the reviewed proposal in your release process:
az deployment group create --resource-group YOUR_APPLICATION_GROUP \
  --template-file deploy/azure/automation.bicep --parameters @.tooling/azure-parameters.json
```

In PowerShell quote `'@.tooling/azure-parameters.json'`. The deployment outputs the
managed HTTPS hostname. Configure a custom domain/certificate through the platform
if required. Test readiness, authenticated submission, signed delivery, and retry
of an unchanged command UUID across replica/revision replacement. Listener
readiness is not a database probe; monitor the eight-connection pool and perform
an authorized synthetic transaction. Budget database connections for deployment
overlap as well as maximum steady replicas, maintenance, and migration runners.

## Revision behavior and live UI

The stateless API intentionally disables sticky sessions. HTTP concurrency scaling
is a starting configuration, not measured production capacity. Monitor revision
errors, latency, replica count, memory/GC, pool waits, command/receipt failures, and
logs with credential/body redaction. The container image runs as UID 10001; the
managed platform's ephemeral filesystem is not durable database storage.

For a live UI, Azure affinity requires single-revision HTTP ingress; change
`stickySessions.affinity` to `sticky`, raise view/request limits, and add the
application's authentication, persistence, proxy trust, and correct readiness
configuration. Affinity can still be lost when a replica disappears. Verify SSE
heartbeats and the managed ingress's request-duration/reconnect behavior rather
than assuming an indefinitely open stream. Do not add a buffering proxy or
automatic mutation retries. Saved drafts and explicit recovery remain necessary;
shared JDBC sessions do not replicate live component graphs.

Roll back by redeploying the previous image digest and its compatible secret
references/configuration. Keep schema changes compatible with both revisions;
traffic rollback does not undo committed commands. The supplied single-revision
mode avoids splitting browser traffic between revisions but does not preserve a
running JVM during deployment.

References: [Container Apps resource schema](https://learn.microsoft.com/en-us/azure/templates/microsoft.app/2025-07-01/containerapps),
[session affinity constraints](https://learn.microsoft.com/en-us/azure/container-apps/sticky-sessions).
