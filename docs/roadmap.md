# Roadmap to 1.0

## 0.2 — development loop

- Java source watcher with isolated application classloader restart;
- browser reload/error overlay;
- compile-time route manifest and duplicate diagnostics;
- component/action development inspector.

## 0.3 — enterprise runtime

- request middleware, authorization guards, and exception mapping;
- instance-factory SPI, Spring bean integration, and a Spring Boot starter;
- pluggable sessions and live-view affinity;
- metrics, tracing, structured logs, health/readiness, and graceful drain;
- servlet and Netty adapters while retaining the zero-dependency JDK server;
- configurable CSP, proxy awareness, upload limits, and security audit.

## 0.4 — rendering and data

- cache and tag-invalidation SPI;
- static generation and incremental regeneration;
- optimistic actions and transition/pending scopes;
- overlay/portal primitives;
- multipart uploads and typed validation.

## 1.0 release gates

- stable public component and protocol APIs;
- load, soak, reconnect, accessibility, and browser compatibility suites;
- Maven Central publication and signed artifacts;
- migration tooling and compatibility policy;
- production reference deployments with more than one JVM node.
