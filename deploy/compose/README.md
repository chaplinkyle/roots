# Two JVMs with HAProxy affinity

Build the JAR using [the container guide](../container/README.md), then:

```sh
docker compose -f deploy/compose/compose.yaml config --quiet
docker compose -f deploy/compose/compose.yaml up --build -d --wait
docker compose -f deploy/compose/compose.yaml ps
curl -i http://localhost:8080/_roots/health
python deploy/smoke.py --url http://localhost:8080 --expected-nodes 2
```

Open <http://localhost:8080/customers>. Only the proxy's loopback port is exposed.
Both JVMs have bounded memory, read-only filesystems, private temporary storage,
and a 45-second stop allowance around Roots' 30-second drain. Change `ROOTS_HTTP_PORT`
if 8080 is occupied. Set `ROOTS_NETWORK_SUBNET` to a non-overlapping private subnet
if necessary; it controls both the network and example proxy trust range.

The proxy inserts an HttpOnly/SameSite affinity cookie selecting a JVM. It checks
readiness and streams SSE progressively with 65-second HTTP idle timeouts.
Automatic retries are disabled. Incoming forwarding headers are rebuilt at this
local HTTP boundary. Component graphs and demo customers are not replicated.
Proxy logs omit URLs/query strings, cookies, and headers because SSE query strings
contain live-view credentials. The smoke probe mutates only the demo's in-memory
approval counter and verifies affinity, revisions, streamed SSE, and disposal.

For TLS exposure, terminate TLS at the proxy, forward scheme `https`, add `secure`
to the affinity-cookie directive, and enable `ROOTS_SECURE_COOKIES` on both JVMs.
If another TLS terminator precedes this proxy, explicitly trust that hop and its
verified scheme; the supplied HTTP frontend intentionally ignores incoming schemes.
For releases set `ROOTS_IMAGE` and `HAPROXY_IMAGE` to reviewed registry digests.

## Failure, upgrade, and rollback

Inspect the browser's `ROOTS_NODE` cookie (`a` or `b`). Stop the corresponding service,
for example `docker compose -f deploy/compose/compose.yaml stop roots-a`. The other
JVM remains available; a fresh document creates a new live view. The old graph is
lost. Repeat with persistent records and unsaved drafts before accepting a rollout
process. Another node's successful health check does not prove business recovery.

Bring up a tested replacement artifact, check readiness, then drain the previous
process with a bounded deadline. Existing tabs may reconnect/reload; there is no
zero-disruption view migration. Retain the previous image and use backward-compatible
database migrations to support rollback.

```sh
docker compose -f deploy/compose/compose.yaml logs --tail 100
docker compose -f deploy/compose/compose.yaml down
```
