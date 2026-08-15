# Integrations

Roots is intentionally dependency-free at runtime. Application projects remain
normal Maven applications and may add the libraries they need.

## Persistence

JDBC and standard persistence libraries are compatible because Roots does not
wrap or replace the Java database stack. Applications can use:

- JDBC and `DataSource`;
- JPA providers such as Hibernate;
- jOOQ or MyBatis;
- HikariCP or another connection pool;
- Flyway or Liquibase for migrations;
- Spring Data repositories when a Spring context owns them.

Keep persistence behind an application service. Treat a server action like a
request boundary: acquire a connection or persistence context, start a
transaction, perform work, commit, and close it. `EntityManager`, Hibernate
`Session`, and JDBC `Connection` instances are not component state and should
not be retained across rerenders.

Virtual threads make straightforward blocking database code viable, but the
database pool still sets the real concurrency ceiling. Size it for the database,
not for the number of virtual threads.

## Spring

Spring libraries can exist in the same process, and a Roots application can call
services or repositories created by a Spring application context. Full framework
integration is not implemented yet.

Current limitations:

- Roots instantiates pages, layouts, and API routes using no-argument constructors;
- there is no Spring Boot starter or bean-factory adapter;
- Roots starts a JDK `HttpServer` instead of registering with Spring Boot's web server;
- Spring Security filters do not automatically protect Roots routes;
- Spring configuration properties and lifecycle events are not bridged.

Until a supported bridge exists, applications should not describe Roots as a
Spring MVC or Spring Boot UI framework. A pre-1.0 integration should provide:

1. an instance-factory SPI so a container can construct convention classes;
2. a servlet adapter for Boot's embedded server;
3. request/security-context propagation;
4. lifecycle and graceful-shutdown integration;
5. a small `roots-spring-boot-starter` module with integration tests.

## Other libraries

Logging, JSON, validation, mail, messaging, cloud SDKs, and observability libraries
can be used in application services today. Roots does not yet adapt their types
into framework APIs; applications own configuration and lifecycle.
