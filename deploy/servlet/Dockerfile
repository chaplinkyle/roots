ARG TOMCAT_IMAGE=tomcat:11.0.24-jdk25-temurin
FROM ${TOMCAT_IMAGE} AS probe
COPY deploy/container/Healthcheck.java /src/Healthcheck.java
RUN javac -d /classes /src/Healthcheck.java

FROM ${TOMCAT_IMAGE}
ARG APP_WAR=examples/servlet/target/roots-servlet-example-0.1.0-SNAPSHOT.war
COPY --chown=10001:10001 ${APP_WAR} /usr/local/tomcat/webapps/roots.war
COPY deploy/servlet/server.xml /usr/local/tomcat/conf/server.xml
COPY --from=probe --chown=10001:10001 /classes/ /opt/roots/health/
RUN chown 10001:10001 /usr/local/tomcat/logs /usr/local/tomcat/temp /usr/local/tomcat/work
USER 10001:10001
ENV ROOTS_DEVELOPMENT=false ROOTS_SECURE_COOKIES=true ROOTS_SHUTDOWN_TIMEOUT=PT30S \
    ROOTS_MAX_LIVE_VIEWS=1000 ROOTS_MAX_CONCURRENT_REQUESTS=1128 ROOTS_MAX_SESSIONS=4000 \
    ROOTS_REQUEST_BODY_TEMPORARY_DIRECTORY=/usr/local/tomcat/temp ROOTS_PORT=8080 \
    ROOTS_HEALTH_PATH=/roots/_roots/health \
    CATALINA_OPTS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD ["java", "-XX:MaxRAMPercentage=10", "-cp", "/opt/roots/health", "Healthcheck"]
ENTRYPOINT ["catalina.sh", "run"]
