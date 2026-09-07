# AWS: Fargate and a TLS Application Load Balancer

`automation.yaml` provisions a two-task stateless command API in an existing VPC,
with private task interfaces, an HTTPS ALB, health checks, bounded shutdown, log
retention, and rollback on failed service deployment. It uses the
[durable automation application](../../examples/automation/README.md). PostgreSQL,
subnets, NAT/service endpoints, ECR, ACM, IAM roles, DNS, and secrets are supplied by
your platform. Creating this stack incurs AWS charges; these instructions are a
template, not a record of a deployed cloud environment.

## Prepare the artifact and dependencies

Build and test the automation JAR, then build the shared container with
`--build-arg APP_JAR=examples/automation/target/roots-automation-example-0.1.0-SNAPSHOT-app.jar`.
Push it through your reviewed ECR pipeline and use its immutable Linux/amd64 image
digest. Pin the base image digests too. Copy `parameters.example.json` to an ignored
local file and replace every placeholder. Parameters contain secret identifiers,
never secret values.

The execution role needs the ECS task trust policy, image-pull/log permissions,
`secretsmanager:GetSecretValue` limited to these secret ARNs, and `kms:Decrypt` when
their encryption key requires it. The application task role needs no AWS API
permissions for this sample. Store each secret as its entire text value. The JDBC
URL must use verified TLS and bounded connection/socket timeouts; include the
trusted PostgreSQL CA in the image where needed. The stack adds a port-5432 ingress
rule to `DatabaseSecurityGroupId`, scoped to its new task security group, before
starting replicas. Supply the actual database security group and review this
network change. Review task outbound rules against your endpoints and database.

Run reviewed database migrations once from a controlled runner with private
database access **before** starting the service. For a new example database, the
same image accepts `--migrate`; supply migration credentials from the secret store.
Do not run migrations in each replica's startup. The sample bootstrap DDL is not a
general migration engine. Schedule bounded receipt pruning separately; see the
application's retention and worker contracts.

## Review and deploy

After authenticating AWS CLI to the intended account/region:

```sh
aws cloudformation validate-template --template-body file://deploy/aws/automation.yaml
aws cloudformation create-change-set --stack-name roots-automation \
  --change-set-name candidate --change-set-type CREATE \
  --template-body file://deploy/aws/automation.yaml \
  --parameters file://.tooling/aws-parameters.json
aws cloudformation describe-change-set --stack-name roots-automation --change-set-name candidate
# Execute only the reviewed change set in your deployment process:
aws cloudformation execute-change-set --stack-name roots-automation --change-set-name candidate
aws cloudformation wait stack-create-complete --stack-name roots-automation
aws cloudformation describe-stacks --stack-name roots-automation
```

Use `UPDATE` for an existing stack and a new change-set name. This template creates
no IAM role, so no IAM capability acknowledgement is required. Configure your
application hostname to resolve to the output ALB DNS name; the hostname must
match the ACM certificate. The raw ALB hostname is not your certificate hostname.

Verify HTTPS readiness and submit a command with the maintained Python client,
then repeat its saved command UUID through a task replacement. Verify one inbox
row, refusal of bad credentials/signatures, and expected database recovery. The
listener readiness probe does not test database availability; monitor pool health
and run an authorized synthetic transaction. CloudWatch receives application
stdout/stderr and Container Insights; alert on ALB errors/latency, ECS restarts,
memory/GC, database pool saturation, and receipt failures. Do not log credentials,
request bodies, or future browser SSE query tokens.

## Scaling, live UI, and rollback

This template uses fixed `DesiredCount`, not an autoscaler. At eight connections
per application pool, budget for up to twice the desired task count during a
rolling deployment, plus migration/maintenance connections. Tune limits from a
representative workload. Do not introduce automatic POST retries at another
gateway. Machine clients may retry only with the same durable operation identity.

Stateless APIs need no affinity. To adapt this topology to a Roots live UI, enable
ALB target-group cookie stickiness (`stickiness.enabled=true`,
`stickiness.type=lb_cookie`, `stickiness.lb_cookie.duration_seconds=86400`), set
appropriate live-view/request capacities, and supply the application's auth,
persistence, readiness, and narrowly trusted ALB subnet CIDRs. A 120-second ALB
idle timeout permits the Roots SSE heartbeat; verify unbuffered delivery through
every additional hop. Before enabling forwarded-header trust, ensure your edge
rejects or sanitizes client-supplied `Forwarded`/`X-Forwarded-Host` metadata that
the ALB does not reconstruct. Trusting the ALB peer address alone does not make
all incoming forwarding headers trustworthy. ALB affinity does not preserve an unhealthy or removed JVM.
Old tabs must recover saved business drafts after rollout; Spring HTTP sessions
also need their own persistence strategy if re-login is unacceptable.

Deploy a prior reviewed image digest through another change set to roll back the
application. Keep migrations compatible with both versions; reverting a task
definition does not revert database writes. ECS circuit-breaker rollback handles
failed service deployment, not application correctness or data rollback. Tasks
receive 45 seconds to stop against a 30-second Roots drain; ALB deregistration also
waits up to 45 seconds. A lost response remains an uncertain outcome to be resolved
with the command UUID.

References: [ALB target-group attributes](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-target-group-attributes.html),
[ECS task definitions](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ecs-taskdefinition.html).
