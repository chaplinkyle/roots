# Linux VM / on-premises service

Install Java 25 LTS and a dedicated unprivileged `roots` service account. Place the
reviewed JAR at `/opt/roots/releases/<version>/application.jar`, owned by the
administrator and readable by the service. Point `/opt/roots/current` to that
release; switch the symlink atomically instead of overwriting a running JAR.

Install [roots.service](roots.service) as `/etc/systemd/system/roots.service` and
[roots.env.example](roots.env.example) as `/etc/roots/roots.env` (root-owned, 0600).
Adapt capacities and proxy CIDRs. Defaults assume a TLS proxy on this host forwarding
to `127.0.0.1:8080`. systemd creates `/var/lib/roots`; the service creates its private
spooling subdirectory. It cannot write to the installed application artifact.

```sh
sudo systemd-analyze verify /etc/systemd/system/roots.service
sudo systemctl daemon-reload
sudo systemctl enable --now roots.service
curl --fail http://127.0.0.1:8080/_roots/health
sudo journalctl -u roots.service -f
```

Configure the site's TLS proxy to preserve the public Host, replace forwarding
headers, stream SSE, and use idle timeouts above the heartbeat. Disable automatic
mutation retries. For multiple VMs, configure affinity as in [HAProxy](../compose/haproxy.cfg),
replace Docker DNS with private host addresses, bind `ROOTS_HOST` to the private
interface, and restrict ingress/trust to the actual proxy addresses.

Deliver identity/database/exporter credentials through the site's secret facility;
environment settings do not provision those services. Run migrations once before
rollout and verify backup/restore and schema compatibility.

To upgrade or roll back, switch `current` to a tested version and run
`sudo systemctl restart roots`. SIGTERM invokes the 30-second drain; systemd permits
45 seconds before forced termination. Verify readiness and business operations
through TLS afterward. Persistent records/drafts must live outside component graphs.

For an existing Servlet platform, use `roots-servlet` or Boot Servlet mode instead
of this standalone unit. Ports, TLS, identity, DataSources, and lifecycle then
belong to that platform; see [integrations](../../docs/integrations.md).
