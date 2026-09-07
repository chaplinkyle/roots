# Enterprise implementation and verification

This historical implementation record tracks work on the Roots Java runtime,
with native interoperability and automation support and deployable cloud and
on-premises examples. Completion requires implementation and evidence, not just
API declarations or documentation claims.

## Requirements

- [x] Preserve user edits across queued actions, unrelated patches, SSE, validation,
  and intentional successful form resets; verify in Chrome, Edge, and Firefox.
- [x] Maintain browser JavaScript as ordinary packaged resources; keep the runtime
  dependency-free and verify executable-JAR and development loading.
- [x] Add explicit high-frequency event scheduling, bounded pending work, and
  cancellation/recovery behavior without silently dropping business mutations.
- [x] Profile representative large component trees and concurrent live views;
  reduce measured rendering, binding, allocation, and transport overhead. Publish
  reproducible latency, throughput, memory, and SSE workload measurements.
- [x] Support an enterprise LTS Java baseline and verify the supported JDK matrix.
- [x] Use the canonical `com.chaplin.roots` Java package and Maven group ID
  consistently across artifacts, adapters, resources, examples, and generated projects.
- [x] Provide native standards-based HTTP interoperability and automation building
  blocks: structured API errors, request correlation, webhook verification,
  idempotent mutation handling, and documented machine-client examples with tests.
- [x] Define view expiry, deployment interruption, and uncertain mutation outcomes;
  support recoverable application drafts and persistent business state. Never
  describe JDBC session sharing as component failover.
- [x] Supply a database-backed business reference application with authentication
  integration, authorization, validation, pagination, optimistic concurrency, and
  transaction/restart tests.
- [x] Supply a supported browser-widget integration boundary with lifecycle and
  reconciliation rules, compatible with CSP and without imposing a JS build tool.
- [x] Provide versioned-release build tooling, artifact metadata, checksums, and a
  quick-start path; distinguish local verification from actual registry publication.
- [x] Add runnable container, Compose/reverse-proxy, Kubernetes, AWS, Azure, GCP,
  and on-premises service/Servlet deployment templates and README walkthroughs.
  Cover TLS termination, sticky routing, SSE buffering/timeouts, readiness/drain,
  secrets, database migration, resource limits, observability, scaling, and rollback.
- [x] Reconcile README, architecture, protocol, security, compatibility, and readiness
  documentation with the implemented contracts.
- [x] Run full reactor checks, real-browser contracts, packaging/archetype checks,
  appropriate load/soak tests, and deployment-template validation. Clearly identify
  cloud execution or production certification that has not actually been performed.

## Evidence log

This log is chronological. Outstanding-work notes in older entries describe the
state at that time; the requirements checklist above and final entries are current.

- Initial review: 280 selected Java tests passed. Browser reproductions showed
  delayed search responses dropping newer typing and unrelated filter changes
  clearing an unfinished customer form. These are the first correctness gates.
- Existing changes in the chat page and its application test predate this work and
  are preserved.
- Browser verification on JDK 26: 49 Chrome/Edge/Firefox contracts passed,
  including new draft, delayed-input, multiple-selection, upload, SSE, successful
  reset, debouncing, and saturation checks. Existing validation/error tests passed.
- Added opt-in input debouncing and a 128-item combined active/queued/delayed work
  bound. Transport timeout and uncertain-mutation recovery remain outstanding.
- Added a reproducible large-table render/patch benchmark. The initial JDK 26
  1,000-row run changed from 59.202 ms mean and 60,030,589 allocated bytes per
  operation to 3.449 ms and 5,802,488 bytes after skipping unchanged regions,
  eliminating suffix copies, caching method metadata, and reusing validation
  patterns. These measurements exclude network, persistence, and browser work.
- Minimum compilation target changed to Java 25 LTS; CI now has a 25/26 matrix.
  The full local Java 25 `clean install` passed: 387 tests, zero failures/errors,
  including 50 browser tests and the development reload test, all coverage/doclint
  gates, and the generated archetype project's packaged build. Hosted CI has not
  been invoked by this work. The current baseline still needs a full Java 26 run.
- The Java 25 executable enterprise JAR returned HTTP 200 for `/customers` and
  `/_roots/client.js`; its browser source matched the packaged resource source
  exactly (49,481 characters). Binary, sources, and Javadoc artifacts were built.
- Verification logs are in ignored local `.tooling/enterprise-full-jdk25.log`,
  `.tooling/enterprise-browsers.log`, and `.tooling/render-{before,optimized}.log`.
  The next implementation tranche must add evidence for its own changes.

## Next implementation tranche

Current verified additions:

- API-only applications now work through scanning, compilation, and packaged
  manifests. `@Stateless` machine routes retain authentication/authorization,
  middleware, limits, and tracing without session storage. Tests exercise storage
  outage independence, fail-closed session access, and Servlet container identity.
- Native `ProblemDetail`, `WebhookVerifier`, and process-local `IdempotencyStore`
  APIs are implemented and covered by malformed-input, signature-vector, concurrency,
  expiry/capacity, and uncertain-outcome tests. Durable receipts/reference workflows
  are still outstanding; documentation makes that boundary explicit.
- Environment configuration and bounded `Roots.run` shutdown are implemented.
  An integration test confirms readiness falls while an accepted API request drains.
- Per-root browser action queues and 30-second response deadlines cover headers and
  body. Lost-response tests confirm a completed server mutation is not retried,
  later mutations are withheld, local drafts remain, and new views can act independently.
  These regressions pass in Chrome, Edge, and Firefox.
- Full Java 25 reactor passed (412 tests before the additional Servlet test);
  the later 14-test Servlet suite also passed on Java 25. The final full Java 26
  reactor passed 413 tests, including 56 browser tests and all coverage, doclint,
  compatibility, packaging, archetype, and development-runner checks. An Edge
  metadata assertion race was fixed by reading its selector/attribute atomically.
- A 64-user/60-second Java 25 loopback soak completed 1,244,841 actions with
  p99 14.6531 ms, exact revisions, no rejection, and full view/request cleanup.
  This is the tiny counter workload, not business/database/SSE capacity evidence.
- The non-root Java 25 image builds. Two Compose JVMs run behind HAProxy with
  exact owner affinity, CSRF actions, revision advancement, streamed SSE, and
  disposal verified by `deploy/smoke.py`. A stopped owner exits on SIGTERM (143,
  no OOM); after health detection, its old view receives 409 and a fresh document
  succeeds on the surviving owner. Initial failed-node connection attempts can
  receive a bounded 503 before health detection completes; the proxy never retries
  a mutation automatically.
- Kubernetes Kustomize renders 10 resources that pass Kubernetes v1.35.0 strict
  JSON schemas; its TLS HAProxy configuration validates with an ephemeral test
  certificate. No Kubernetes context is configured, so cluster execution remains
  unverified. The systemd template is provided but not executed on a Linux service host.
- Current logs: `.tooling/enterprise-recovery-jdk25.log`,
  `.tooling/enterprise-recovery-jdk26-final.log`, `.tooling/recovery-browsers-jdk26.log`,
  `.tooling/stateless-servlet-jdk25.log`, `.tooling/enterprise-soak-jdk25.log`,
  `.tooling/container-build.log`, `.tooling/compose-smoke.log`,
  `.tooling/compose-restart.log`, and `.tooling/kubernetes-validation.log`.

Continue with browser widget lifecycle integration, representative heap/SSE/database
performance measurements, release tooling, AWS/Azure/GCP provisioning examples,
and remaining deployment templates and local validation.
Preserve the full requirements above. No cloud account has been changed or cloud
deployment claimed by this work.

Deployment design references checked during implementation:

- [AWS ALB affinity and deregistration](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-target-group-attributes.html)
- [Azure Container Apps session affinity](https://learn.microsoft.com/en-us/azure/container-apps/sticky-sessions)
- [Cloud Run affinity limitations](https://docs.cloud.google.com/run/docs/configuring/session-affinity)
- [Kubernetes termination lifecycle](https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/)

Cloud Run's best-effort affinity is not a transparent failover mechanism for a
live Roots component graph. Use the supplied stateless API examples there or
explicitly account for view loss; use stable routing for stateful UI deployments.

## Namespace and action-outcome follow-up

- User-selected canonical Java package and Maven group ID: `com.chaplin.roots`.
  Framework and fixture packages, resources, reflective names, processor service
  registration, Spring auto-configuration, archetype coordinates, and documentation
  have been migrated together. This is an explicitly documented breaking pre-release
  change; existing compiled applications require rebuilding.
- The review reproduced a completed business mutation followed by a render failure:
  the original request returned 500 with one write; a retry returned 200 with two.
  The implementation now marks unexpected handler and post-handler render failures
  uncertain on the server and refuses subsequent actions on that view. The browser
  also pauses on uncertainty headers and 5xx responses, closes live updates, and
  retains drafts. Validation before mutation remains correctable. Durable operation
  identifiers and persistent draft recovery remain separate required work.
- Added core and real-browser regressions for these outcomes, including a mapped
  422 after execution and a proxy 502 after a successful upstream response. Full
  Java 25 and 26 `clean install` builds both pass: 441 tests reported, 438 passed,
  three opt-in PostgreSQL tests skipped, zero failures/errors. This includes 58
  Chrome/Edge/Firefox tests, coverage/doclint, signature baselines, Spring/Servlet
  integration, the generated archetype build, load regression checks, and the
  development runner. The three PostgreSQL receipt tests were separately enabled
  and passed under the canonical namespace.
- Added durable JDBC receipt documentation covering transaction ownership,
  retention/cleanup, response limits, retries, outboxes, and the real PostgreSQL
  test profile. CI now includes a dedicated PostgreSQL receipt job; hosted CI has
  not been executed by this work.
- Added `examples/automation`, an authenticated stateless command inbox with
  application-owned HikariCP, JDBC receipts, persistent command IDs, signed webhook
  ingestion, bounded maintenance, and a Python client that retains identity when
  retrying. Four file-backed integration tests cover concurrent submissions,
  database reopen, expiry, authentication, signatures, and invalid input. The
  inbox intentionally leaves command execution to an application worker.
- The executable JAR was verified against real PostgreSQL: abrupt JVM restart,
  a client retry after a proxy 502 following commit, duplicate signed delivery,
  and receipt cleanup all preserve one command insert. The portable
  `examples/automation/verify_packaged.py` repeats these checks in its own disposable
  PostgreSQL 16.14 container with generated credentials and cleanup; that exact
  script has passed locally and is included in CI.
- Artifact inspection confirms canonical package paths and Maven metadata in all
  eight runtime/tooling JARs and the correct executable automation main, generated
  API manifest, and merged PostgreSQL/H2 JDBC service registration. Only the
  migration note retains the former namespace in maintained source/documentation.
- New evidence: `.tooling/chaplin-enterprise-jdk25.log`,
  `.tooling/chaplin-enterprise-jdk26.log`, `.tooling/chaplin-postgres-receipts.log`,
  `.tooling/outcomes-browser-regressions.log`, `.tooling/automation-packaged-smoke.log`,
  and `.tooling/automation-portable-postgres.log`. The original HTTP reproduction
  now returns 500 then 409 with the write count held at one, recorded in
  `.tooling/project-review-mutation-fixed.log`. All verification-created Java
  servers and PostgreSQL containers have been stopped and removed.

## Durable business workflow and recovery

- Added `examples/workflow`, an executable Spring Boot Servlet application with
  Spring Security form login for local evaluation, an OIDC provider integration,
  exact reader/editor role mapping, private owner-scoped drafts, keyset pagination,
  business validation, explicit concurrent-edit review, application-owned HikariCP,
  and versioned Flyway migrations. Normal startup validates migrations; a separate
  `--migrate` invocation applies them without starting HTTP.
- Draft UUIDs are durable operation identities. Completing a draft locks its row
  and commits the customer, completion marker, and audit entry together. Replays
  return the original customer; conflicts preserve the committed private draft.
  Only acknowledged database saves are restart durable. Unsent typing remains in
  the browser, and the documentation states that distinction explicitly.
- Five repository contracts passed on file-backed H2 and real PostgreSQL 16.14,
  including 64 concurrent completions, pool/database reopen, cross-owner rejection,
  optimistic conflicts, bounded pagination, and injected audit failure rollback.
  `examples/workflow/verify_postgres.py` repeats those PostgreSQL tests in an owned
  disposable container and verifies the executable Boot JAR's login, partial draft
  recovery after abrupt JVM loss, and recovery of a completed operation with one
  customer and one audit row. That exact script passed and cleaned up its resources.
- Three Chrome/Edge/Firefox workflows passed autosave, server/pool restart and
  login recovery, corrected validation, a 502 substituted after successful
  completion, and explicit review of another editor's saved changes. Screenshots
  were inspected at desktop and narrow sizes; narrow conflict layouts present
  current values before the editor/review action. Hosted OIDC has not been exercised.
- Added a native browser recovery guard for expired views, incompatible protocol,
  invalid patch boundaries, and development reload. Dirty controls and queued work
  pause the view and retain the document rather than silently reloading. Idle,
  unedited views still reload automatically. Application persistence remains
  separate; no component-graph failover or browser-storage durability is claimed.
- A real Boot/SSE regression exposed the container waiting its entire graceful
  shutdown interval before servlet destruction. A high-phase lifecycle now drains
  Roots first; the regression requires completion before the container's timeout.
  Servlet async listeners also complete disconnected/error contexts and interrupt
  waiting stream workers, and cleanup tolerates recycled response facades.
- Logs: `.tooling/workflow-recovery-final.log`,
  `.tooling/workflow-postgres-packaged.log`, and the Java 25/26 reactor and browser
  verification logs recorded in the final verification follow-up. The executable
  Boot JAR's canonical main, configuration, CSS, and migration resources were
  inspected and matched maintained source exactly.
- A queued-review regression withholds an autosave response after the server has
  rendered a newer customer version, then queues the user's review of the older
  displayed version. The application now takes that version from the captured
  form, rejects the unseen update, and requires another review. This passes in
  Chrome, Edge, and Firefox on both Java 25 and 26.
- The Java 26 full `clean install` passed all reactor gates: 464 tests reported,
  456 passed and eight optional PostgreSQL tests skipped, with zero failures or
  errors. Java 25 passed every module; its browser-module rerun passed all 65
  tests after making a new reload assertion atomic. Final Servlet/workflow module
  verification also passed on Java 25 and the final workflow module on Java 26.
  The full reactor includes 65 framework/development browser tests and three
  additional browser workflows. PostgreSQL workflow contracts run separately.
- Final logs: `.tooling/workflow-full-jdk25.log`,
  `.tooling/workflow-browsers-jdk25.log`, `.tooling/workflow-full-jdk26.log`,
  `.tooling/workflow-final-modules-jdk25.log`, and
  `.tooling/workflow-final-module-jdk26.log`. CI YAML and package/resource audits
  passed; hosted CI and a live identity provider have not been exercised.
- Same-draft conflicts now return validation without acknowledging captured
  controls, preserving typing that lost an optimistic write to another tab.
  Completed operations reject a different expected draft version instead of
  treating that different intent as a successful replay. The browser workflow
  covers preservation of rejected typing and reopening the newer saved draft;
  repository contracts cover version-bound completion replay. Final verification
  is recorded in `.tooling/workflow-final-recovery-jdk25.log`,
  `.tooling/workflow-final-recovery-jdk26.log`, and
  `.tooling/workflow-postgres-packaged-final.log`.

## Versioned candidates and deployment templates

- Added `tools/release.py` to capture source, stamp a non-snapshot version in an
  isolated copy, run the reactor, collect 37 Maven artifacts with source/Javadoc
  attachments and checksums, record source/toolchain/test provenance, and optionally
  compare a second build. The original worktree version remains a snapshot; the
  tool never commits, tags, signs, or uploads. Registry publication is explicitly
  separate. The archetype now includes source and generator-guide attachments.
- Candidate `0.1.0-rc.local1` passed the complete Java 25 reactor and all 37
  collected artifact bytes matched a second build. A fresh-cache consumer generated
  and verified a new application using that bundle, then exercised its packaged
  live page, browser resource, and prerendered page. The current inspector verifies
  checksums/inventory and passes six integrity-corruption/path regressions.
  Logs are in `.tooling/release-check-1` and `.tooling/release-consumer-2`.
- Added AWS CloudFormation, Azure Bicep, and GCP Cloud Run templates for the durable
  stateless automation application, with existing platform network/database/identity
  resources, secret references, TLS ingress, bounded resources, migration/retention
  guidance, scaling budgets, and rollback instructions. AWS validation passes
  cfn-lint 1.56.0; Azure compiles with Bicep 0.46.1; GCP validates against the public
  Cloud Run v1 discovery property/type schema. Cloud deployment, provider admission,
  IAM/network readiness, and production capacity remain unverified. No cloud
  account resources have been changed.
- Added a real Servlet WAR module and on-premises Tomcat container/configuration.
  Running the packed WAR exposed archive scanning that ignored Tomcat's `war:`
  resource URLs and the `WEB-INF/classes` root. The scanner now maps these to
  standard JDK archive connections and closes its own uncached archive handles.
  Three regressions cover ordinary JARs, prefixed classpath roots, and Tomcat URLs,
  including temporary archive cleanup on Windows.
- The packaged WAR passes mounted routing, CSP, cookie paths, three CSRF actions
  with exact revisions, packaged JavaScript, streamed SSE, and shutdown with an
  open stream on Tomcat 11.0.24/Java 25. Shutdown took 2.562 seconds, exited 143,
  and was neither forced nor OOM-killed. The owned verifier removes its container
  and volumes. Logs: `.tooling/servlet-war-scanner-final.log`,
  `.tooling/servlet-container-build.log`, and `.tooling/servlet-container-verification.log`.
- The shared container now supplies writable non-root `/tmp` volume metadata for
  ECS and a configurable readiness probe path for Boot. A local read-only container
  passed UID/volume writes, default/custom health paths, and invalid/404 rejection.
  Candidate and deployment-template CI workflows are supplied but have not been
  invoked on hosted CI. Representative performance and widget lifecycle work remain
  required before completing the full goal.
- Final candidate `0.1.0-rc.local2` captures the completed release/deployment and
  archive-scanning changes. Its Java 25 full reactor passed: 467 tests reported,
  459 passed, eight optional PostgreSQL tests skipped, zero failures/errors.
  All 37 collected artifacts were byte-identical in a second local build.
  The maintained fresh-cache consumer command passed against that final bundle,
  including executable live/static pages and the browser resource, then stopped
  its server. Evidence is in `.tooling/release-check-2` and
  `.tooling/release-consumer-3`; the bundle remains explicitly unpublished.
- The final Java 26 `clean install` also passed all reactor gates with the same
  467/459/eight test counts, including 68 framework/development/workflow browser
  contracts, archive regressions, adapter integration, coverage/doclint, signature
  baselines, and archetype packaging. Log: `.tooling/releases-deployments-jdk26.log`.
  Final workflow/parameter syntax, local documentation links, source namespace,
  WAR provided-dependency packaging, and whitespace checks pass. No verification
  containers remain. These results do not close the representative performance,
  browser-widget, remaining documentation reconciliation, or final whole-goal
  verification requirements above.

## Browser integration and measured performance

- Added `Element.widget(key, moduleUrl)` and the documented browser module
  mount/update/destroy contract: same-origin assets, keyed DOM ownership,
  serial/latest-props asynchronous updates, 30-second deadlines, abort/cleanup,
  failed-widget fallback, custom-editor dirty-state recovery, and native form
  bridges. The core remains JDK-only; no JavaScript build tool is required.
- Tests cover identity through actions/SSE/reorder, module replacement and missing
  assets, portals, asynchronous coalescing and late mounts, a real lifecycle
  timeout, preservation of unsaved editor content, native form validation, and
  scoped-host replacement. The runnable enterprise latency chart preserves its
  local range. Chrome/Edge/Firefox accessibility and form/example checks passed;
  final whole-reactor browser verification is recorded below when complete.
- The PostgreSQL workload now combines large retained component trees, validated
  actions, optimistic SQL writes/readback/commit, an eight-connection pool, open
  SSE streams, and concurrent background fan-out. Bounded 4,096-bucket histograms
  replace unbounded latency retention in the original soak. The runner owns and
  removes its PostgreSQL container and captures logs, source identity and JFR.
- JFR identified eager element collections, regex matchers, and repeated event
  enumeration. Lazy/smaller collections, allocation-free ASCII validation, and
  cached event enumeration improved the same 32-view/1,000-row comparison from
  530 to 664 actions/s and reduced JVM-wide allocation per completed action by
  40.7%. These totals include clients and pushes. Grammar equivalence is checked
  against the old regex definitions for all UTF-16 code units.
- Final runs include waiting for view locks in SSE latency and concurrent fan-out.
  With a 512 MiB JVM, 32 views × 1,000 rows completed 39,657 actions in 60.259 s
  (658.1/s, HTTP p99 93.028 ms, SSE p99 264.458 ms). 128 views × 100 rows completed
  74,240 actions in 60.942 s (1,218.2/s, HTTP p99 230.069 ms, SSE p99 202.153 ms).
  Database versions matched committed actions; no requests were rejected and
  every view, request and subscription drained. GC/heap/pool pressure and payload
  volumes are published in `docs/load-testing.md`; full snapshots and database
  pool contention remain explicit sizing constraints, not hidden capacity claims.
- Final performance source SHA-256 is
  `68296272f6f582088d27173b2396879447e9d3fc2e9cd2e0ee5967e261850649`, matching both
  `.tooling/business-final-32x1000` and `.tooling/business-final-128x100`.
  These are short local measurements on eight logical CPUs, with client/server
  sharing the JVM and PostgreSQL in Docker; no proxy, browser, production identity
  provider, or remote-network latency is represented.
- README, architecture, feature, protocol, compatibility, security, integration,
  roadmap and readiness documents now describe the implemented widget, runtime,
  release and deployment contracts and distinguish remaining production
  certification/publication from supplied implementation. Local documentation
  links, namespace/coordinate checks, six release integrity tests, CloudFormation
  lint, Bicep compilation and Cloud Run discovery-schema validation pass.

## Final whole-goal verification

- Java 26 `clean install` passed all 17 reactor modules: 489 tests reported,
  481 passed, eight optional PostgreSQL tests skipped, zero failures/errors.
  This includes 87 framework/development/workflow browser checks, API/protocol
  baselines, coverage/doclint, generated-archetype build, and executable/WAR
  packaging. Log: `.tooling/enterprise-final-jdk26.log`.
- The final Java 26 rendering benchmark measured 100 rows at 0.419 ms mean and
  329,781 allocated bytes per action; 1,000 rows at 2.662 ms and 3,042,496 bytes.
  Its 219,458-character full table produced a 220-character row patch.
  This in-process microbenchmark excludes transport/database/browser costs;
  log: `.tooling/render-final-jdk26.log`.
- Java 25 candidate `0.1.0-rc.local3` passed the complete reactor with the same
  489/481/eight test counts and zero failures/errors. All 37 published-layout
  artifact bytes matched a second local build. Source input SHA-256:
  `8843b66166549cbc9abc557c872c01c0e6879c85f99c254477f57cdd33f7dbe5`.
  The bundle is `.tooling/release-check-3/roots-0.1.0-rc.local3-bundle.zip`;
  it is checksummed and explicitly unpublished. This captures the final code;
  subsequent documentation-only evidence entries describe the completed checks.
- A new Maven cache generated, built, tested, and launched a consumer from that
  candidate's repository. Live page, packaged browser resource and prerendered
  page checks passed, and the JVM was stopped. Evidence:
  `.tooling/release-consumer-4/consumer-evidence.json` and its generation/build logs.
- Fresh PostgreSQL packaged checks passed durable receipt replay after abrupt
  JVM loss, client retry after a post-commit proxy 502, signed webhook duplicate
  delivery, and receipt retention with permanent command deduplication. Fresh
  workflow PostgreSQL contracts and packaged sign-in/draft/completion recovery
  after abrupt loss also passed. Logs:
  `.tooling/automation-postgres-enterprise-final.log` and
  `.tooling/workflow-postgres-enterprise-final.log`.
- The final packed WAR passed context routing, cookie paths, CSP, current packaged
  JavaScript, exact action revisions, SSE and shutdown with an open stream on
  Tomcat 11/Java 25. Shutdown took 2.578 seconds, exit 143, without forced timeout.
  Logs: `.tooling/servlet-enterprise-final-build.log` and
  `.tooling/servlet-enterprise-final-verify.log`. All verifier-owned containers
  and application processes were stopped and container volumes removed.
- The latency example passes accessibility, range-preservation and narrow-viewport
  overflow checks. Desktop and compact screenshots were inspected; the final page
  uses the existing workspace spacing. Evidence: `.tooling/widgets-visual-final.log`,
  `.tooling/latency-widget-final.png` and its `.mobile.png` companion.
- Completion audit: every implementation requirement above has code, documentation
  and verification evidence. Registry publication/signing, live cloud-account
  deployment, live external identity-provider certification, production load/security
  certification, Safari/WebKit and transparent component-graph failover are not
  claimed. Cloud templates, native adapters, durable application recovery and
  controlled-pilot guidance are supplied within the requested scope.
