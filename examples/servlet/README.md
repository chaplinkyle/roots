# Servlet WAR example

This small anonymous, in-memory counter demonstrates deployment under a real WAR
context path. It is not the authenticated database-backed workflow application.
The container owns startup, HTTP, and shutdown; there is no `main` method or extra
JDK HTTP listener. Programmatic `RootsServlet` registration enables async SSE and
applies bounded environment configuration. Servlet API classes remain provided by
the target Servlet 6.1 container.

```sh
./mvnw -pl examples/servlet -am package
```

Deploy `target/roots-servlet-example-0.1.0-SNAPSHOT.war` with Java 25+ and a Servlet
6.1 container. The [on-premises Tomcat walkthrough](../../deploy/servlet/README.md)
includes an actual container image, server configuration, packaged-WAR smoke
verification, TLS/proxy setup, identity integration, and rollout requirements.
