# Roadmap

The working tree is `0.1.0-SNAPSHOT`. Implemented capabilities live in the
[feature contract](feature-contract.md); this roadmap lists remaining work,
not a release schedule or a claim that a stable version already exists.

## Before a public candidate

- Pass the complete Java 25/26 reactor and Chrome/Edge/Firefox browser contracts.
- Run PostgreSQL transaction and packaged restart checks for the reference apps.
- Review public API changes and document migration requirements.
- Scan the source and Git history for accidental secrets and generated artifacts.
- Validate the versioned repository bundle and generate a fresh consumer from it.
- Publish an immutable tag and explicit pre-release notes with verification limits.

## Before Maven Central publication

- Verify ownership of the `com.chaplin.roots` namespace with the registry.
- Configure publishing credentials, artifact signing, and the registry's staging
  process. No credentials or signing keys belong in the repository.
- Verify the published coordinates from an empty consumer cache.

GitHub source publication, GitHub candidate releases, and Maven Central publication
are separate outcomes. See [release instructions](releases.md).

## Before a stable 1.0

- Freeze a reviewed public API and protocol compatibility contract, with upgrade
  tests against previous released artifacts.
- Complete independent application security review and manual accessibility
  testing; expand supported browser evidence to Safari/WebKit.
- Run representative production pilots with explicit latency, capacity, recovery,
  and operational targets. Local benchmarks do not certify production capacity.
- Exercise actual cloud rollouts, restart/drain recovery, backups, and restoration.
- Provide a demonstrated strategy for multi-instance live-view ownership and
  deployment recovery. Shared sessions alone are insufficient.
- Establish a sustainable maintenance and vulnerability-response process.

## Deliberate future work

Subtree-only Java evaluation, additional persistence adapters, browser editor
integrations, and migration automation need concrete application requirements
and measured value before expanding the API. Offline UI execution and transparent
component failover are not existing features.

The [historical implementation evidence](enterprise-implementation-plan.md)
records earlier local checks. CI on the relevant commit and release manifests are
the evidence for a new candidate.
