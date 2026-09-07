# Kubernetes with stable JVM owners and TLS

This portable template uses two application pods with stable DNS names, two
HAProxy pods terminating TLS, and a TCP `LoadBalancer` Service. It requires a
cluster with a load-balancer implementation and a CNI enforcing NetworkPolicy.
It can be adapted to EKS, AKS, GKE, or on-premises Kubernetes; provider provisioning
and execution are separate work. The repository does not claim a live cluster test.

Before applying:

1. Build/push the [application image](../container/README.md). Set `images` in
   `kustomization.yaml` to your registry and immutable application/proxy digests.
   Configure image-pull credentials if required by the registry.
2. Set `ROOTS_TRUSTED_PROXIES` in `roots.env` to the actual source CIDR(s) of proxy
   pods. The placeholder intentionally fails startup. Backend NetworkPolicy allows
   only proxy pods in this namespace; confirm enforcement and actual source addresses.
3. Supply a TLS PEM containing the private key plus certificate/full chain. Keep
   it outside the repository. Create the namespace, then the secret:

```sh
kubectl apply -f deploy/kubernetes/namespace.yaml
kubectl -n roots create secret generic roots-tls --from-file=tls.pem=/secure/path/tls.pem
kubectl kustomize deploy/kubernetes > rendered-roots.yaml
kubectl apply --dry-run=server -f rendered-roots.yaml
kubectl apply -f rendered-roots.yaml
kubectl -n roots rollout status deployment/roots-proxy
kubectl -n roots wait --for=condition=Ready pod/roots-0 pod/roots-1 --timeout=180s
kubectl -n roots get service roots-web
```

The outer load balancer must forward TCP/TLS to HAProxy, not terminate HTTP and
forge another forwarding chain. `externalTrafficPolicy: Local` requests source IP
preservation; verify behavior with the actual provider. Point the public DNS name
covered by the certificate to the load balancer. Probe and test through HTTPS,
including affinity cookies, CSRF-protected mutations, SSE, and node replacement.

The [StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
provides stable names; it does **not** persist Java object graphs. `OnDelete`
prevents automatic replacement when configuration changes. Apply a new image/config,
then replace one application pod at a time, wait for readiness, and verify business
operations before replacing the other. Repeat with the previous digest to roll back;
do not automatically reverse database migrations. PodDisruptionBudgets govern
eligible voluntary evictions, not arbitrary deletion or node failure.

SIGTERM uses the 30-second Roots drain within a 45-second pod grace period.
[Pod termination](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/)
removes terminating endpoints from normal routing; HAProxy also checks readiness.
Old tabs can lose their views and need fresh documents. Persist records/drafts and
transaction receipts in an external database. Run reviewed migrations once before
rollout; deliver credentials with the site's secret operator or secret references.

The proxy has exactly two configured owner names. Change its server list together
with StatefulSet replica changes; do not attach an HPA and assume affinity scales
automatically. Proxy replicas use the same owner-cookie mapping. Their readiness
only proves the proxy is listening; backend availability must also be monitored.
Export logs and application/Boot metrics and observe GC, pools, views, SSE, and
admission limits under real load. See the [common deployment contract](../README.md).

Local rendering validates Kustomize assembly; server dry-run and workload tests
must run against the target cluster before rollout. The `roots` namespace, image
names, resource budgets, CIDRs, registry credentials, certificate management, and
database/exporter configuration are explicit customization points.
