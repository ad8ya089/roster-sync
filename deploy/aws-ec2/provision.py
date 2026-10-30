"""Create only the reviewed Sydney deployment; never print credentials.

Run with `python deploy/aws-ec2/provision.py prerequisites`.
The installed CLI uses its normal authenticated profile; no credentials are exported. The ignored state file records IDs,
not secret values, so a partial failure can be inspected and safely resumed.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import re
import tempfile
import subprocess
import time


REGION = "ap-southeast-2"
ACCOUNT = "687971794741"
BUCKET = f"rostersync-backups-{ACCOUNT}-{REGION}"
ROLE = "rostersync-ec2-role"
PROFILE = "rostersync-ec2-profile"
BUDGET = "rostersync-monthly-25-usd"
ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / "target/aws-deployment-state.json"
TAGS = [{"Key": "Project", "Value": "rostersync"}, {"Key": "ManagedBy", "Value": "rostersync-deployment"}]


class ClientError(Exception):
    def __init__(self, operation, code):
        self.operation_name = operation
        self.response = {"Error": {"Code": code}}


class CliClient:
    def __init__(self, service, region):
        self.service, self.region = service, region

    def __getattr__(self, method):
        def call(**payload):
            # Private temporary JSON avoids exposing secret values in command arguments.
            with tempfile.TemporaryDirectory(prefix="rostersync-aws-") as directory:
                arguments = [str(Path(os.environ["LOCALAPPDATA"]) / "Programs/Amazon/AWSCLIV2/aws.exe") if os.name == "nt" else "aws", self.service, method.replace("_", "-"), "--profile", "moveinsync-dev", "--region", self.region, "--output", "json", "--no-cli-pager"]
                if method == "run_instances" and "UserData" in payload:
                    data = Path(directory, "user-data.sh")
                    data.write_text(payload.pop("UserData"), encoding="utf-8")
                    arguments += ["--user-data", "file://" + str(data)]
                if payload:
                    request = Path(directory, "request.json")
                    request.write_text(json.dumps(payload), encoding="utf-8")
                    request.chmod(0o600)
                    arguments += ["--cli-input-json", "file://" + str(request)]
                process = subprocess.run(arguments, capture_output=True, text=True)
                if process.returncode:
                    match = re.search(r"An error occurred \(([^)]+)\)", process.stderr)
                    raise ClientError(method, match.group(1) if match else "CLI_REQUEST_FAILED")
                return json.loads(process.stdout) if process.stdout.strip() else {}
        return call

    def get_waiter(self, name):
        client = self
        class Waiter:
            def wait(self, **kwargs):
                deadline = time.monotonic() + 600
                while time.monotonic() < deadline:
                    result = client.describe_instances(**kwargs)
                    if all(instance["State"]["Name"] == "running" for reservation in result["Reservations"] for instance in reservation["Instances"]):
                        return
                    time.sleep(3)
                raise RuntimeError("INSTANCE_START_TIMEOUT")
        return Waiter()


class CliSession:
    def client(self, name, region_name=REGION):
        return CliClient("s3api" if name == "s3" else name, region_name)


def session():
    return CliSession()


def save(state):
    STATE.parent.mkdir(exist_ok=True)
    STATE.write_text(json.dumps(state, indent=2), encoding="utf-8")


def named(name):
    return TAGS + [{"Key": "Name", "Value": name}]


def owned(tags):
    return any(tag["Key"] == "ManagedBy" and tag["Value"] == "rostersync-deployment" for tag in tags)


def missing(error):
    return error.response["Error"]["Code"] in {"NoSuchEntity", "NotFoundException", "ParameterNotFound", "404", "NoSuchBucket"}


def prerequisites(aws, state, email):
    print("deployment.date", dt.datetime.now(dt.timezone.utc).isoformat(), flush=True)
    budgets = aws.client("budgets", region_name="us-east-1")  # Global billing control plane.
    try:
        budget = budgets.describe_budget(AccountId=ACCOUNT, BudgetName=BUDGET)["Budget"]
        if not state.get("budget") or budget["BudgetLimit"]["Amount"] != "25.0" and float(budget["BudgetLimit"]["Amount"]) != 25:
            raise RuntimeError("EXISTING_BUDGET_REQUIRES_REVIEW")
    except ClientError as error:
        if not missing(error):
            raise
        alerts = [{"Notification": {"NotificationType": kind, "ComparisonOperator": "GREATER_THAN", "Threshold": threshold, "ThresholdType": "PERCENTAGE"}, "Subscribers": [{"SubscriptionType": "EMAIL", "Address": email}]} for kind, threshold in [("ACTUAL", 80), ("ACTUAL", 100), ("FORECASTED", 100)]]
        budgets.create_budget(AccountId=ACCOUNT, Budget={"BudgetName": BUDGET, "BudgetLimit": {"Amount": "25", "Unit": "USD"}, "TimeUnit": "MONTHLY", "BudgetType": "COST", "CostTypes": {"IncludeCredit": False, "IncludeRefund": False}}, NotificationsWithSubscribers=alerts)
        state["budget"] = BUDGET
        save(state)
    print("budget.ready", BUDGET, flush=True)

    iam = aws.client("iam")
    try:
        role = iam.get_role(RoleName=ROLE)["Role"]
        if not owned(role.get("Tags", [])):
            raise RuntimeError("EXISTING_ROLE_REQUIRES_REVIEW")
    except ClientError as error:
        if not missing(error):
            raise
        trust = {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
        iam.create_role(RoleName=ROLE, AssumeRolePolicyDocument=json.dumps(trust), Tags=named(ROLE))
        state["role"] = ROLE
        save(state)
    policy = (ROOT / "deploy/aws-ec2/instance-policy.template.json").read_text(encoding="utf-8").replace("${AWS_ACCOUNT_ID}", ACCOUNT)
    iam.put_role_policy(RoleName=ROLE, PolicyName="rostersync-runtime-policy", PolicyDocument=policy)
    iam.attach_role_policy(RoleName=ROLE, PolicyArn="arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore")
    try:
        profile = iam.get_instance_profile(InstanceProfileName=PROFILE)["InstanceProfile"]
        if not owned(profile.get("Tags", [])):
            raise RuntimeError("EXISTING_PROFILE_REQUIRES_REVIEW")
    except ClientError as error:
        if not missing(error):
            raise
        profile = iam.create_instance_profile(InstanceProfileName=PROFILE, Tags=named(PROFILE))["InstanceProfile"]
        state["instanceProfile"] = PROFILE
        save(state)
    if not any(role["RoleName"] == ROLE for role in profile.get("Roles", [])):
        iam.add_role_to_instance_profile(InstanceProfileName=PROFILE, RoleName=ROLE)
    print("iam.ready", ROLE, flush=True)

    s3 = aws.client("s3")
    try:
        s3.head_bucket(Bucket=BUCKET)
        if not owned(s3.get_bucket_tagging(Bucket=BUCKET)["TagSet"]):
            raise RuntimeError("EXISTING_BUCKET_REQUIRES_REVIEW")
    except ClientError as error:
        if not missing(error):
            raise
        s3.create_bucket(Bucket=BUCKET, CreateBucketConfiguration={"LocationConstraint": REGION}, ObjectOwnership="BucketOwnerEnforced")
        state["bucket"] = BUCKET
        save(state)
        s3.put_bucket_tagging(Bucket=BUCKET, Tagging={"TagSet": named(BUCKET)})
    s3.put_public_access_block(Bucket=BUCKET, PublicAccessBlockConfiguration={key: True for key in ["BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets"]})
    s3.put_bucket_encryption(Bucket=BUCKET, ServerSideEncryptionConfiguration={"Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]})
    s3.put_bucket_lifecycle_configuration(Bucket=BUCKET, LifecycleConfiguration={"Rules": [{"ID": "expire-project-backups", "Status": "Enabled", "Filter": {"Prefix": "backups/"}, "Expiration": {"Days": 30}, "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1}}]})
    print("backup_bucket.ready", BUCKET, flush=True)

    ssm = aws.client("ssm")
    state.setdefault("parameters", [])
    for name, length in [("JWT_SECRET", 48), ("POSTGRES_PASSWORD", 32), ("DEMO_PASSWORD", 24)]:
        path = "/rostersync/" + name
        try:
            ssm.get_parameter(Name=path)  # Ciphertext only; do not rotate credentials on retry.
            if path not in state["parameters"]:
                raise RuntimeError("EXISTING_PARAMETER_REQUIRES_REVIEW")
        except ClientError as error:
            if not missing(error):
                raise
            ssm.put_parameter(Name=path, Value=secrets.token_urlsafe(length), Type="SecureString", Tier="Standard", Tags=TAGS, Overwrite=False)
            state["parameters"].append(path)
            save(state)
    print("secure_parameters.ready count=3 values=not-printed", flush=True)


def launch(aws, state):
    if not all(key in state for key in ["budget", "role", "instanceProfile", "bucket", "parameters"]):
        raise RuntimeError("PREREQUISITES_NOT_COMPLETE")
    ec2 = aws.client("ec2")
    groups = ec2.describe_security_groups(Filters=[{"Name": "group-name", "Values": ["rostersync-web-sg"]}, {"Name": "vpc-id", "Values": ["vpc-0a9a664ef2dbb6dd4"]}])["SecurityGroups"]
    if groups:
        if not owned(groups[0].get("Tags", [])):
            raise RuntimeError("EXISTING_SG_REQUIRES_REVIEW")
        group = groups[0]["GroupId"]
    else:
        group = ec2.create_security_group(GroupName="rostersync-web-sg", Description="Roster Sync public HTTP HTTPS only", VpcId="vpc-0a9a664ef2dbb6dd4", TagSpecifications=[{"ResourceType": "security-group", "Tags": named("rostersync-web-sg")}])["GroupId"]
        state["securityGroup"] = group
        save(state)
        ec2.authorize_security_group_ingress(GroupId=group, IpPermissions=[{"IpProtocol": "tcp", "FromPort": port, "ToPort": port, "IpRanges": [{"CidrIp": "0.0.0.0/0"}]} for port in [80, 443]])
    if not state.get("allocationId"):
        address = ec2.allocate_address(Domain="vpc", TagSpecifications=[{"ResourceType": "elastic-ip", "Tags": named("rostersync-eip")}])
        state.update(allocationId=address["AllocationId"], publicIp=address["PublicIp"])
        save(state)
        print("DUCKDNS_IP", state["publicIp"], flush=True)
    if state.get("instanceId"):
        print("instance.already_recorded", state["instanceId"], flush=True)
        return
    image = aws.client("ssm").get_parameter(Name="/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id")["Parameter"]["Value"]
    metadata = ec2.describe_images(ImageIds=[image])["Images"][0]
    if metadata["OwnerId"] != "099720109477" or metadata["Architecture"] != "arm64" or not metadata.get("FreeTierEligible"):
        raise RuntimeError("AMI_VERIFICATION_FAILED")
    user_data = """#!/bin/bash
set -euo pipefail
for attempt in $(seq 1 120); do
  if curl -fsSL --connect-timeout 3 https://github.com >/dev/null; then break; fi
  sleep 3
done
apt-get update
apt-get install -y git
git clone https://github.com/ad8ya089/roster-sync.git /opt/roster-sync
exec bash /opt/roster-sync/deploy/aws-ec2/bootstrap.sh
"""
    # The instance has no ephemeral public IP; associate the sole EIP immediately.
    response = ec2.run_instances(ImageId=image, InstanceType="t4g.small", MinCount=1, MaxCount=1, ClientToken="rostersync-sydney-interview-v1", IamInstanceProfile={"Name": PROFILE}, NetworkInterfaces=[{"DeviceIndex": 0, "SubnetId": "subnet-04bf0920fa3b8d4fd", "Groups": [group], "AssociatePublicIpAddress": False}], BlockDeviceMappings=[{"DeviceName": metadata["RootDeviceName"], "Ebs": {"VolumeSize": 20, "VolumeType": "gp3", "Encrypted": True, "DeleteOnTermination": True}}], CreditSpecification={"CpuCredits": "standard"}, MetadataOptions={"HttpTokens": "required", "HttpPutResponseHopLimit": 1, "HttpEndpoint": "enabled"}, UserData=user_data, TagSpecifications=[{"ResourceType": kind, "Tags": named("rostersync-demo" if kind == "instance" else "rostersync-root-gp3")} for kind in ["instance", "volume"]])
    state["instanceId"] = response["Instances"][0]["InstanceId"]
    state["ami"] = image
    save(state)
    ec2.get_waiter("instance_running").wait(InstanceIds=[state["instanceId"]])
    associated = ec2.associate_address(InstanceId=state["instanceId"], AllocationId=state["allocationId"])
    state["associationId"] = associated["AssociationId"]
    save(state)
    print("instance.running", state["instanceId"], "DUCKDNS_IP", state["publicIp"], flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["prerequisites", "launch"])
    parser.add_argument("--email", default=os.environ.get("ROSTER_BUDGET_EMAIL"))
    args = parser.parse_args()
    try:
        aws = session()
        identity = aws.client("sts").get_caller_identity()
        if identity["Account"] != ACCOUNT or identity["Arn"].endswith(":root"):
            raise RuntimeError("UNEXPECTED_AWS_IDENTITY")
        state = json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else {}
        if args.stage == "prerequisites":
            if not args.email:
                raise RuntimeError("BUDGET_EMAIL_REQUIRED")
            prerequisites(aws, state, args.email)
        else:
            launch(aws, state)
    except ClientError as error:
        raise SystemExit(f"AWS_BLOCKED operation={error.operation_name} code={error.response['Error']['Code']}")
    except Exception as error:
        # Do not print generic SDK/CLI exception text: it may contain request values.
        code = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        raise SystemExit("DEPLOYMENT_STOPPED " + code)
