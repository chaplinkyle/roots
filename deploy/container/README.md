# Single-container application

From the repository root with Java 25 LTS or newer:

```sh
./mvnw -pl examples/enterprise -am clean install
docker build -f deploy/container/Dockerfile -t roots-enterprise:local .
docker run --name roots-evaluation --read-only --tmpfs /tmp:rw,size=64m,mode=1777 \
  --cap-drop ALL --security-opt no-new-privileges --memory 768m --cpus 1 \
  --pids-limit 256 --stop-timeout 45 -p 127.0.0.1:8080:8080 roots-enterprise:local
```

On Windows use `mvnw.cmd` and join the Docker command onto one line, or use
PowerShell backticks. Open <http://localhost:8080/customers>. The JVM is the
entrypoint, so SIGTERM reaches its shutdown hook directly. UID/GID 10001 owns the
artifact and uses bounded temporary storage under a read-only root filesystem.

```sh
docker inspect --format '{{.State.Health.Status}}' roots-evaluation
docker logs roots-evaluation
docker stop --time 45 roots-evaluation
docker rm -v roots-evaluation
```

A small Java readiness probe is compiled in a separate stage and calls
`/_roots/health` with a bounded timeout; it needs no shell HTTP client or session.
The application JAR must already exist. For another shaded artifact, supply
`--build-arg APP_JAR=path/to/target/application-app.jar`. The root `.dockerignore`
admits container files and `target/*-app.jar` artifacts, excluding source/tooling/secrets.

For the durable Boot workflow JAR, also set `WORKFLOW_BIND_ADDRESS=0.0.0.0` and
`ROOTS_HEALTH_PATH=/actuator/health/readiness`, then follow its authentication,
database, migration, and secure-cookie configuration. The container probe path is
configurable; the framework's native health endpoint itself is unchanged.

The image declares `/tmp` as writable volume metadata with UID 10001 ownership
and mode 1777 so ECS task volumes permit non-root temporary files. A supplied tmpfs
or Kubernetes/platform mount overrides it. Remove anonymous Docker volumes with
the owned container (`docker rm -v`); never store the application database there.

For release builds, pin `BUILD_IMAGE` and `RUNTIME_IMAGE` to reviewed digests, scan
the output, record its digest/SBOM, and promote it unchanged. Moving Java 25 tags
are evaluation defaults. Configure TLS, authentication, databases, and exporters
under the [deployment contract](../README.md).
