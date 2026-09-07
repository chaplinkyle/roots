# Building and consuming a versioned candidate

Roots uses the Maven group and Java namespace `com.chaplin.roots`. The working
tree remains `0.1.0-SNAPSHOT`. A local build or a candidate ZIP does not mean that
these artifacts have been published to Maven Central or an internal registry.

## Capture and verify

Use Python 3.10+, Git, JDK 25, and the checked-in Maven wrapper. Chrome, Edge, and
Firefox must be available for the browser contracts. The build downloads Maven
dependencies as needed and installs the candidate coordinates in your local Maven
cache for the generated-project test.

From reviewed, committed source:

```bash
python3 tools/release.py build --version 0.1.0-rc.1 \
  --output .tooling/releases/0.1.0-rc.1 --rebuild
```

PowerShell:

```powershell
python tools/release.py build --version 0.1.0-rc.1 `
  --output .tooling/releases/0.1.0-rc.1 --rebuild
```

Choose a new output directory each time. `--maven /absolute/path/to/mvn` can
select an existing Maven installation. `--worktree` explicitly permits an
evaluation of uncommitted changes, including non-ignored untracked source files;
the manifest records that fact. Inspect those files before capturing a candidate.
Ignored build outputs, local tooling, `.env` files, and deleted index entries are
excluded. The tool does not change the original POM versions, commit, tag, sign,
or upload anything.

The tool captures the source bytes, stamps the requested non-snapshot version in
the copy, runs the complete `clean install` reactor, and requires passing test
reports. Optional database contracts are reported as skipped; the PostgreSQL
verification documented in the automation/workflow examples remains a separate
release check. `--rebuild` builds the same captured source a second time and
compares all published POM/JAR bytes. This demonstrates reproducibility for that
source and local toolchain, not across different operating systems or JDK builds.

The output includes:

- `roots-VERSION-bundle.zip`: a Maven-layout repository, source archive,
  `release-manifest.json`, and `SHA256SUMS`.
- Binary, source, and Javadoc JARs for runtime/tooling modules and the application
  archetype; the archetype's Javadoc attachment is its generator guide.
- SHA-256/SHA-512 plus Maven-compatible SHA-1/MD5 sidecars for repository artifacts.
  Use SHA-256 or SHA-512 for integrity checks.
- Version/source identifiers in runtime/tooling JAR manifests. The bundle records
  Git HEAD, dirty-worktree status, hashes of captured and stamped source, the
  Maven/JDK/OS description, test counts, and the optional rebuild comparison.
- `verify.log`, optional `rebuild.log`, and an inspectable source copy outside the
  distributable ZIP. Retain these logs with the reviewed candidate.

Verify an extracted bundle with the trusted tool from this checkout:

```bash
python3 tools/release.py verify /path/to/extracted-bundle
```

Verification checks artifact bytes, checksum sidecars, repository membership, and
the checksums inventory. Checksums detect corruption; they do not authenticate a
publisher. Obtain the bundle's SHA-256 through your trusted release channel, or
use your organization's signing and attestation process before distribution.

## Generate an application from the bundle

For an automated fresh-cache check, including the generated executable's live
page, packaged browser resource, and prerendered page:

```bash
python3 tools/release.py smoke /path/to/extracted-bundle \
  --output .tooling/consumer-check
```

This creates an isolated consumer project/cache, downloads its third-party Maven
dependencies, starts the generated JAR on an ephemeral loopback port, and stops
that process when finished. Logs and `consumer-evidence.json` stay in the output.
Use a new output path for each run. It makes no registry changes.

Extract the bundle and keep the repository path stable while building. The
provided `tools/release-settings.xml` adds its Maven repository and plugin
repository; Maven Central remains available for third-party dependencies. Use a
fresh consumer cache to confirm the application actually resolves Roots from the
bundle. This example assumes Maven is on PATH:

```bash
REPOSITORY_URL="$(python3 -c 'from pathlib import Path; print(Path("/path/to/extracted-bundle/repository").as_uri())')"
mvn --batch-mode --no-transfer-progress -s /path/to/roots/tools/release-settings.xml \
  -Droots.repository="$REPOSITORY_URL" -Dmaven.repo.local=/path/to/consumer-cache \
  org.apache.maven.plugins:maven-archetype-plugin:3.3.1:generate \
  -DarchetypeGroupId=com.chaplin.roots -DarchetypeArtifactId=roots-archetype \
  -DarchetypeVersion=0.1.0-rc.1 -DrootsVersion=0.1.0-rc.1 \
  -DgroupId=com.example -DartifactId=orders -Dpackage=com.example.orders \
  -Dversion=1.0.0-SNAPSHOT -DinteractiveMode=false -DarchetypeCatalog=internal
mvn --batch-mode --no-transfer-progress -s /path/to/roots/tools/release-settings.xml \
  -Droots.repository="$REPOSITORY_URL" -Dmaven.repo.local=/path/to/consumer-cache \
  -f orders/pom.xml verify
java -jar orders/target/orders-1.0.0-SNAPSHOT.jar
```

PowerShell repository URL and argument forms:

```powershell
$repositoryUrl = ([System.Uri](Resolve-Path '/path/to/extracted-bundle/repository').Path).AbsoluteUri
# Use the same Maven commands, with PowerShell backtick continuations and quoted
# dotted arguments: "-Droots.repository=$repositoryUrl", '-DrootsVersion=0.1.0-rc.1', etc.
```

Open `http://localhost:8080`. The generated project is independent of the source
checkout; keep the repository settings for future builds until the same version
is available in your managed Maven repository. For a source-only quick start, see
the [README](../README.md#quick-start) and [archetype guide](archetype.md).

## Publication is a separate operation

The manual candidate workflow uploads build output as a GitHub Actions artifact
when run by a maintainer. It neither creates a GitHub Release nor publishes Maven
coordinates. Local execution of the tool does not invoke that workflow.

For a GitHub pre-release, first push the reviewed source and wait for CI on that
exact commit. Build a non-snapshot candidate from that clean commit, run its
fresh-consumer smoke check, and verify its bundle checksums. Create an annotated
version tag at the same commit and attach the bundle plus its SHA-256/SHA-512
sidecars to an explicitly marked GitHub pre-release. Include the commit, Java and
PostgreSQL evidence, migration notes, and remaining operational limits in the
release notes. Never substitute a candidate from a dirty or different source tree.

The build manifest deliberately records `publication: unpublished`: it describes
the artifact's state when constructed. An accompanying GitHub Release is the
subsequent distribution record. It does not mean the Maven coordinates are in
Maven Central, and the bundle must not be modified to rewrite that build evidence.

Before external publication, choose the final reviewed source/version, complete
the supported Java and PostgreSQL checks, review dependency/license/security
evidence, and follow the target registry's credentials, namespace ownership,
signing, and staging process. `com.chaplin.roots` namespace ownership has not been
verified with Maven Central by this project. Never replace an already released
version with different bytes. An application rollout promotes the reviewed image
digest or JAR; database rollback requires a separately tested migration plan.

References: [Maven reproducible builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)
and [Maven Central artifact requirements](https://central.sonatype.org/publish/requirements/).
