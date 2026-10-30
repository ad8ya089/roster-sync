# Stop/start and teardown — Sydney

Run in Git Bash/Linux with authenticated AWS CLI and jq. A temporary stop retains EBS and the Elastic IP; those two resources remain chargeable while compute is stopped.

```bash
set -euo pipefail
export AWS_PROFILE=moveinsync-dev AWS_DEFAULT_REGION=ap-southeast-2
INSTANCE_ID=$(aws ec2 describe-instances --filters Name=tag:Project,Values=rostersync Name=tag:Name,Values=rostersync-demo Name=instance-state-name,Values=pending,running,stopping,stopped --query 'Reservations[].Instances[].InstanceId' --output text)
[[ "$INSTANCE_ID" =~ ^i-[0-9a-f]+$ ]] || { echo 'Expected exactly one project instance'; exit 1; }
# Pause between interviews. Keep the EIP; DNS will not change.
aws ec2 stop-instances --instance-ids "$INSTANCE_ID"
aws ec2 wait instance-stopped --instance-ids "$INSTANCE_ID"
# Resume. Enabled Docker and container restart policies restore the application.
aws ec2 start-instances --instance-ids "$INSTANCE_ID"
aws ec2 wait instance-status-ok --instance-ids "$INSTANCE_ID"
```

## Permanent teardown

Destructive: first run/verify a final backup if you need the data. Execute this only when you choose to remove the deployment, not as part of a pause. Root EBS has DeleteOnTermination=true; the existing VPC/subnet/internet gateway and AWS-managed policies/keys are not removed. If provisioning stopped halfway, use only the resources actually recorded in the local target/aws-deployment-state.json ledger.

```bash
set -euo pipefail
export AWS_PROFILE=moveinsync-dev AWS_DEFAULT_REGION=ap-southeast-2
ACCOUNT_ID=687971794741
BUCKET=rostersync-backups-687971794741-ap-southeast-2
INSTANCE_ID=$(aws ec2 describe-instances --filters Name=tag:Project,Values=rostersync Name=tag:Name,Values=rostersync-demo Name=instance-state-name,Values=pending,running,stopping,stopped --query 'Reservations[].Instances[].InstanceId' --output text)
ALLOCATION_ID=$(aws ec2 describe-addresses --filters Name=tag:Project,Values=rostersync Name=tag:Name,Values=rostersync-eip --query 'Addresses[].AllocationId' --output text)
SG_ID=$(aws ec2 describe-security-groups --filters Name=tag:Project,Values=rostersync Name=group-name,Values=rostersync-web-sg --query 'SecurityGroups[].GroupId' --output text)
[[ "$INSTANCE_ID" =~ ^i-[0-9a-f]+$ && "$ALLOCATION_ID" =~ ^eipalloc-[0-9a-f]+$ && "$SG_ID" =~ ^sg-[0-9a-f]+$ ]] || { echo 'Resource identity check failed'; exit 1; }
# 1. Terminate compute and its encrypted root disk.
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID"
aws ec2 wait instance-terminated --instance-ids "$INSTANCE_ID"
# 2. Release the public address and the now-unused security group.
aws ec2 release-address --allocation-id "$ALLOCATION_ID"
for attempt in $(seq 1 12); do
  aws ec2 delete-security-group --group-id "$SG_ID" && break
  ((attempt < 12)) || exit 1
  sleep 5 # ENI deletion can lag the terminated state.
done
# 3. Remove this profile/role, keeping the AWS-managed SSM policy itself.
aws iam remove-role-from-instance-profile --instance-profile-name rostersync-ec2-profile --role-name rostersync-ec2-role
aws iam delete-instance-profile --instance-profile-name rostersync-ec2-profile
aws iam detach-role-policy --role-name rostersync-ec2-role --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam delete-role-policy --role-name rostersync-ec2-role --policy-name rostersync-runtime-policy
for attempt in $(seq 1 12); do
  aws iam delete-role --role-name rostersync-ec2-role && break
  ((attempt < 12)) || exit 1
  sleep 5 # IAM profile/policy detach is eventually consistent.
done
# 4. Delete application parameters and the global budget.
aws ssm delete-parameters --names /rostersync/JWT_SECRET /rostersync/POSTGRES_PASSWORD /rostersync/DEMO_PASSWORD
aws budgets delete-budget --region us-east-1 --account-id "$ACCOUNT_ID" --budget-name rostersync-monthly-25-usd
# 5. Abort incomplete uploads, remove backup objects, then the bucket.
aws s3api list-multipart-uploads --bucket "$BUCKET" --output json |
  jq -r '.Uploads[]? | [.Key,.UploadId] | @tsv' |
  while IFS=$'\t' read -r key upload_id; do
    aws s3api abort-multipart-upload --bucket "$BUCKET" --key "$key" --upload-id "$upload_id"
  done
aws s3 rm "s3://$BUCKET" --recursive
aws s3api delete-bucket --bucket "$BUCKET"
```

Remove/update the DuckDNS record yourself after releasing the IP. Check EC2 for remaining tagged volumes/addresses and verify the bucket, parameters, role/profile and budget are gone. This deployment creates no snapshots, custom AMIs, NAT gateways, load balancers, endpoints, SSH keys, customer-managed KMS keys, monitoring servers or CloudWatch log groups. SSM/CloudTrail audit records remain managed by AWS.
