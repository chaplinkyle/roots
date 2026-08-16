# Roots chat example

Roots Relay is a small, multi-view chat room written entirely in Java application
code. It demonstrates that a state change in one browser view can rerender every
other connected view through Roots' built-in Server-Sent Events channel.

## Run it

From the repository root:

**macOS / Linux**

```bash
./mvnw -pl examples/chat -am package
java -jar examples/chat/target/roots-chat-example-0.1.0-SNAPSHOT-app.jar
```

**Windows PowerShell**

```powershell
.\mvnw.cmd -pl examples/chat -am package
java -jar examples\chat\target\roots-chat-example-0.1.0-SNAPSHOT-app.jar
```

Open <http://127.0.0.1:8080> in two browser tabs, use a different display name in
each tab, and send a message.

## What it demonstrates

- a reusable `@ViewComponent` with mount and unmount lifecycle callbacks;
- an annotated `@ServerAction` form handler;
- shared, synchronized Java state with a bounded 200-message history;
- cross-view rerendering and revisioned SSE patches;
- keyed message nodes for stable browser reconciliation;
- session-backed display names, refs, focus, and scroll effects;
- output escaping and Roots' normal CSRF-protected action transport;
- virtual-thread fan-out that does not block the sender's live-view lock.

The browser receives HTML and runs the small driver owned by `roots-core`. The
example contains no application JavaScript, JSON API, React component, Node.js
build, or WebSocket handler.

## Deliberate boundaries

This is a single-JVM example, not a production chat service. Messages are kept in
memory and reset on restart. There is no authentication, moderation, delivery
receipt, attachment storage, or durable history. A production deployment should
place messages in a database, publish room events through Redis/Kafka/NATS or a
similar broker, integrate an identity provider, and either use sticky routing or
implement a distributed Roots live-view/session store.
