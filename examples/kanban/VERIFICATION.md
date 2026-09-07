# Verification evidence

Verified locally on September 6, 2026, with Java 25, PostgreSQL 16.13, and Chrome 151.

- `python examples/kanban/verify.py --maven .tooling/maven/apache-maven-3.9.11/bin/mvn.cmd --java .tooling/jdk/jdk-25.0.4.1+1/bin/java.exe` completed successfully. Three composite tests passed with no failures or skips: durable transactions/permissions/archive, eight competing writers with exactly one commit, and the full authenticated browser flow.
- Browser coverage includes task creation/editing, keyboard status selection, drag/drop through the actual Roots action bridge, reload persistence, search, archive/restore, and preserving unsaved typing after a concurrent edit. Authentication redirects and missing login CSRF rejection are checked.
- The packaged JAR accepted an 8,000-character Unicode description through the real authenticated HTTP action endpoint, survived abrupt JVM termination, and recovered the saved task after a new process and login.
- Desktop and true 390-pixel mobile screenshots were reviewed. Screenshots are under `target/screenshots/`.
- The Linux deployment image passed PostgreSQL migrations, readiness, and sign-in with a read-only filesystem and user `10001:10001`. The persistent local preview runs at `http://127.0.0.1:8090/app/`; its login details are in the git-ignored `.tooling/kanban-preview/login.txt`.
- The running container JAR matches the verified local artifact: SHA-256 `cb99196eadb9145b538484aa4dbe4e1907f84489ff55513c4ab664a24d284d2a`.
- `cfn-lint deploy/kanban/aws.yaml` and AWS CloudFormation `validate-template` both passed. Python deployment and verification scripts compile. Temporary PostgreSQL verification containers were removed; the two persistent preview containers remain intentionally running.

No AWS resources were created. Actual AWS deployment, TLS/proxy behavior, and a browser acceptance check at the public hostname remain pending selection of the target account and hostname. Template validation is not deployment evidence.
