ARG BUILD_IMAGE=eclipse-temurin:25-jdk
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre
FROM ${BUILD_IMAGE} AS probe
COPY deploy/container/Healthcheck.java /src/Healthcheck.java
RUN javac -d /classes /src/Healthcheck.java
FROM ${RUNTIME_IMAGE}
WORKDIR /opt/roots
COPY --chown=10001:10001 examples/kanban/target/roots-kanban-example-0.1.0-SNAPSHOT-app.jar application.jar
COPY --from=probe --chown=10001:10001 /classes/ /opt/roots/health/
COPY deploy/kanban/rds-ca.pem /opt/roots/rds-ca.pem
USER 10001:10001
ENV KANBAN_BIND_ADDRESS=0.0.0.0 ROOTS_PORT=8080 ROOTS_HEALTH_PATH=/actuator/health/readiness \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD ["java", "-XX:MaxRAMPercentage=10", "-cp", "/opt/roots/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/roots/application.jar"]
