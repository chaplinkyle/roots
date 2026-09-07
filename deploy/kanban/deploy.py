#!/usr/bin/env python3
"""Build and deploy Roots Kanban to an explicitly selected AWS account and hostname."""
import argparse
import fnmatch
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--profile', required=True)
parser.add_argument('--region', default='us-east-1')
parser.add_argument('--account', required=True, help='Expected AWS account ID; prevents deploying under the wrong credentials.')
parser.add_argument('--domain', required=True, help='New DNS hostname; an existing unrelated record is never replaced.')
parser.add_argument('--zone-id', required=True)
parser.add_argument('--certificate', required=True, help='Issued ACM certificate ARN in the target region.')
parser.add_argument('--stack', default='roots-kanban')
parser.add_argument('--maven', required=True)
args = parser.parse_args()
args.zone_id = args.zone_id.removeprefix('/hostedzone/')
if not re.fullmatch(r'[a-zA-Z][a-zA-Z0-9-]{0,80}',args.stack): parser.error('Use a simple stack name of at most 81 characters')
if not re.fullmatch(r'[a-z0-9][a-z0-9.-]+[a-z0-9]',args.domain): parser.error('Use a DNS hostname without scheme or path')
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
aws_prefix=['aws','--profile',args.profile,'--region',args.region,'--no-cli-pager','--output','json']

def run(command, **kwargs):
    result=subprocess.run(command,cwd=ROOT,text=True,capture_output=True,creationflags=flags,
                          timeout=kwargs.pop('timeout',60),**kwargs)
    if result.returncode:
        # Never log command arguments: they may contain secret input supplied by future callers.
        raise RuntimeError((result.stderr or result.stdout)[-6000:])
    return result.stdout

def aws(*command, **kwargs):
    output=run(aws_prefix+list(command),**kwargs)
    return json.loads(output) if output.strip() else {}

def describe():
    try: return aws('cloudformation','describe-stacks','--stack-name',args.stack)['Stacks'][0]
    except RuntimeError as e:
        if 'does not exist' in str(e): return None
        raise

def wait_stack():
    previous=None
    deadline=time.monotonic()+2400
    while time.monotonic()<deadline:
        stack=describe()
        status=stack['StackStatus']
        if status!=previous: print('CloudFormation: '+status,flush=True); previous=status
        if status in ('CREATE_COMPLETE','UPDATE_COMPLETE'): return stack
        if 'IN_PROGRESS' not in status:
            events=aws('cloudformation','describe-stack-events','--stack-name',args.stack)['StackEvents']
            failures=[{'resource':e['LogicalResourceId'],'reason':e.get('ResourceStatusReason','')} for e in events if e['ResourceStatus'].endswith('FAILED')]
            raise RuntimeError('Stack did not complete: '+json.dumps(failures[:5]))
        time.sleep(10)
    raise RuntimeError('Stack is still running. Inspect it in CloudFormation before continuing.')

def apply(image_tag, desired, exists):
    params=[{'ParameterKey':k,'ParameterValue':str(v)} for k,v in {
        'DomainName':args.domain,'HostedZoneId':args.zone_id,'CertificateArn':args.certificate,
        'ImageTag':image_tag,'DesiredCount':desired}.items()]
    request={'StackName':args.stack,'TemplateBody':(ROOT/'deploy/kanban/aws.yaml').read_text(),
             'Parameters':params,'Capabilities':['CAPABILITY_IAM'],
             'Tags':[{'Key':'Application','Value':'roots-kanban'}]}
    try: aws('cloudformation','update-stack' if exists else 'create-stack','--cli-input-json',json.dumps(request))
    except RuntimeError as e:
        if 'No updates are to be performed' not in str(e): raise
    return wait_stack()

def outputs(stack): return {o['OutputKey']:o['OutputValue'] for o in stack['Outputs']}

identity=aws('sts','get-caller-identity')
if identity['Account']!=args.account: raise SystemExit('AWS account differs from --account. No changes made.')
existing=describe()
if existing and {'Key':'Application','Value':'roots-kanban'} not in existing.get('Tags',[]):
    raise SystemExit('That stack does not belong to Roots Kanban. No changes made.')
if existing and outputs(existing).get('Url') != 'https://'+args.domain+'/app/':
    raise SystemExit('Changing the hostname of an existing deployment requires a separate DNS migration.')
zone_info=aws('route53','get-hosted-zone','--id',args.zone_id)['HostedZone']
if zone_info.get('Config',{}).get('PrivateZone'):
    raise SystemExit('The public site requires a public Route 53 hosted zone.')
zone=zone_info['Name'].rstrip('.')
if args.domain==zone or not args.domain.endswith('.'+zone):
    raise SystemExit('Choose a subdomain of the selected zone; the zone apex is intentionally excluded.')
if not existing:
    records=aws('route53','list-resource-record-sets','--hosted-zone-id',args.zone_id)['ResourceRecordSets']
    if any(r['Name'].rstrip('.')==args.domain for r in records):
        raise SystemExit('That hostname already has DNS records. Choose an unused hostname.')
cert=aws('acm','describe-certificate','--certificate-arn',args.certificate)['Certificate']
if cert['Status']!='ISSUED' or not any(
        args.domain==name or (name.startswith('*.') and args.domain.count('.')==name.count('.') and fnmatch.fnmatchcase(args.domain,name))
        for name in cert['SubjectAlternativeNames']):
    raise SystemExit('The certificate is not issued or does not cover the hostname.')
if args.certificate.split(':')[3]!=args.region or args.certificate.split(':')[4]!=args.account:
    raise SystemExit('The certificate must be in the deployment account and region.')
aws('cloudformation','validate-template','--template-body','file://'+str(ROOT/'deploy/kanban/aws.yaml'))
print('Preflight passed. Building and testing the application.',flush=True)
run([str(Path(args.maven).resolve()),'-pl','examples/kanban','-am','package','-Dtest=KanbanTest',
     '-Dsurefire.failIfNoSpecifiedTests=false','-Dmaven.javadoc.skip=true'],timeout=300)
tag='release-'+time.strftime('%Y%m%d%H%M%S',time.gmtime())
local_image='roots-kanban:'+tag
run(['docker','build','--platform','linux/amd64','-f','deploy/kanban/Dockerfile','-t',local_image,'.'],timeout=600)
# The first stack creates the repository and database with zero app tasks. On updates,
# keep the running image unchanged until the new image is pushed and migrated.
if existing:
    stack=existing
else:
    print('Creating network, database, repository, and HTTPS endpoint.',flush=True)
    stack=apply(tag,0,False)
out=outputs(stack)
registry=out['Repository'].split('/')[0]
token=run(aws_prefix+['ecr','get-login-password']).strip()
run(['docker','login','--username','AWS','--password-stdin',registry],input=token)
del token
image=out['Repository']+':'+tag
run(['docker','tag',local_image,image])
run(['docker','push',image],timeout=600)
print('Image pushed. Running the database migration task.',flush=True)
# Register a migration-only revision with the newly pushed image without changing the service.
task=aws('ecs','describe-task-definition','--task-definition',out['TaskDefinition'])['taskDefinition']
allowed={'family','taskRoleArn','executionRoleArn','networkMode','containerDefinitions','volumes',
         'placementConstraints','requiresCompatibilities','cpu','memory','runtimePlatform','ephemeralStorage'}
task={k:v for k,v in task.items() if k in allowed}
for container in task['containerDefinitions']:
    if container['name']=='kanban': container['image']=image
migration_definition=aws('ecs','register-task-definition','--cli-input-json',json.dumps(task))['taskDefinition']['taskDefinitionArn']
request={'cluster':out['Cluster'],'taskDefinition':migration_definition,'launchType':'FARGATE',
         'networkConfiguration':{'awsvpcConfiguration':{'subnets':[out['TaskSubnet']],
            'securityGroups':[out['AppSecurityGroup']],'assignPublicIp':'ENABLED'}},
         'overrides':{'containerOverrides':[{'name':'kanban','command':['--migrate']}]}}
result=aws('ecs','run-task','--cli-input-json',json.dumps(request))
if result.get('failures'): raise RuntimeError('Migration could not start: '+json.dumps(result['failures']))
task_arn=result['tasks'][0]['taskArn']
deadline=time.monotonic()+600
while time.monotonic()<deadline:
    migration=aws('ecs','describe-tasks','--cluster',out['Cluster'],'--tasks',task_arn)['tasks'][0]
    if migration['lastStatus']=='STOPPED': break
    time.sleep(5)
else: raise RuntimeError('Migration task is still running. Inspect ECS before retrying.')
if not all(c.get('exitCode')==0 for c in migration['containers']):
    raise RuntimeError('Migration failed; the new app was not enabled. Inspect log group '+out['Logs'])
aws('ecs','deregister-task-definition','--task-definition',migration_definition)
print('Migration passed. Starting the application.',flush=True)
stack=apply(tag,1,True)
out=outputs(stack)
deadline=time.monotonic()+180
while time.monotonic()<deadline:
    try:
        with urllib.request.urlopen('https://'+args.domain+'/actuator/health/readiness',timeout=10) as r:
            if r.status==200: break
    except OSError: pass
    time.sleep(5)
else: raise RuntimeError('Stack completed but public HTTPS readiness is not available. Inspect DNS and ALB targets.')
print('Deployed: '+out['Url'])
print('Username: admin')
print('Retrieve the generated password from Secrets Manager: '+out['AdminSecretArn'])
print('Database endpoint (private): '+out['DatabaseEndpoint'])
