# Override with reviewed image digests when producing a release.
ARG BUILD_IMAGE=eclipse-temurin:25-jdk
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre
FROM ${BUILD_IMAGE} AS probe
COPY deploy/container/Healthcheck.java /src/Healthcheck.java
RUN javac -d /classes /src/Healthcheck.java

FROM ${RUNTIME_IMAGE}
ARG APP_JAR=examples/enterprise/target/roots-enterprise-example-0.1.0-SNAPSHOT-app.jar
WORKDIR /opt/roots
COPY --chown=10001:10001 ${APP_JAR} application.jar
COPY --from=probe --chown=10001:10001 /classes/ /opt/roots/health/
RUN chown 10001:10001 /tmp && chmod 1777 /tmp
# ECS copies the directory ownership/mode into its task-scoped writable volume.
# Explicit /tmp tmpfs or platform mounts take precedence over this image metadata.
VOLUME ["/tmp"]
USER 10001:10001
ENV ROOTS_HOST=0.0.0.0 ROOTS_PORT=8080 ROOTS_DEVELOPMENT=false \
    ROOTS_SHUTDOWN_TIMEOUT=PT30S ROOTS_REQUEST_BODY_TEMPORARY_DIRECTORY=/tmp \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD ["java", "-XX:MaxRAMPercentage=10", "-cp", "/opt/roots/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/roots/application.jar"]
