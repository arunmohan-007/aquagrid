# One-time AWS setup for auto-deploy

`.github/workflows/deploy.yml` builds the backend image and rolls it out to EC2 on every
push to `main` that passes CI. Everything below is account-specific setup GitHub Actions
cannot do on its own — run this once, from a machine with the AWS CLI configured against
the target account (`aws sts get-caller-identity` should show the right account before you
start).

Replace `<ACCOUNT_ID>`, `<REGION>` and `<INSTANCE_ID>` with your values throughout.

## 1. ECR repository

```bash
aws ecr create-repository \
  --repository-name aquagrid/api \
  --image-scanning-configuration scanOnPush=true \
  --region <REGION>
```

## 2. GitHub's OIDC identity provider (skip if your account already has one)

```bash
aws iam list-open-id-connect-providers | grep token.actions.githubusercontent.com
```

If nothing prints:

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

## 3. IAM role GitHub Actions assumes

Trust policy — scoped to this repo, and to `main` specifically, so a workflow run on a
fork or a feature branch cannot assume it.

**The `sub` claim is not just `repo:OWNER/REPO:...`.** GitHub appends the org's and
repo's immutable numeric IDs — `repo:OWNER@ORG_ID/REPO@REPO_ID:...` — as an anti-hijack
measure against repo renames and transfers. A trust policy written against the plain
`OWNER/REPO` form (the form GitHub's own OIDC docs lead with) will reject every run with
`Not authorized to perform sts:AssumeRoleWithWebIdentity` and no further detail. Find the
real values once, from a workflow that has `permissions: id-token: write`:

```bash
RESPONSE=$(curl -sS -H "Authorization: bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" \
  "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=sts.amazonaws.com")
echo "$RESPONSE" | jq -r '.value' | cut -d. -f2 | tr '_-' '/+' | \
  python3 -c "import sys,base64,json; p=sys.stdin.read(); print(json.loads(base64.b64decode(p+'='*(-len(p)%4))))" \
  | jq '.sub'
```

For this repo that's `arunmohan-007@298143198` and `aquagrid@1330807990`. The job also
runs under `environment: production` in `deploy.yml`, which changes the claim's suffix
from `:ref:refs/heads/main` to `:environment:production` — the trust policy has to allow
both forms, one for jobs without an environment gate and one for jobs with it:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": [
          "repo:arunmohan-007@298143198/aquagrid@1330807990:ref:refs/heads/main",
          "repo:arunmohan-007@298143198/aquagrid@1330807990:environment:production"
        ]
      }
    }
  }]
}
```

Permissions policy — push to the one ECR repo, send SSM commands to the one instance:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcrAuth",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "EcrPush",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:PutImage",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:BatchGetImage"
      ],
      "Resource": "arn:aws:ecr:<REGION>:<ACCOUNT_ID>:repository/aquagrid/api"
    },
    {
      "Sid": "RolloutViaSsm",
      "Effect": "Allow",
      "Action": ["ssm:SendCommand"],
      "Resource": [
        "arn:aws:ec2:<REGION>:<ACCOUNT_ID>:instance/<INSTANCE_ID>",
        "arn:aws:ssm:<REGION>::document/AWS-RunShellScript"
      ]
    },
    {
      "Sid": "ReadRolloutResult",
      "Effect": "Allow",
      "Action": ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"],
      "Resource": "*"
    }
  ]
}
```

Create both:

```bash
aws iam create-role \
  --role-name github-actions-aquagrid-deploy \
  --assume-role-policy-document file://trust-policy.json

aws iam put-role-policy \
  --role-name github-actions-aquagrid-deploy \
  --policy-name aquagrid-deploy-permissions \
  --policy-document file://permissions-policy.json
```

## 4. The EC2 instance's own role

The workflow never touches the instance directly — SSM does — but the instance needs
permission to receive that command and to pull the image itself. Attach an instance
profile with:

- The AWS managed policy `AmazonSSMManagedInstanceCore` (lets the SSM agent register and
  receive commands — most current Amazon Linux / Ubuntu AMIs ship the agent preinstalled;
  confirm with `aws ssm describe-instance-information` and check your instance is listed).
- An inline policy granting `ecr:GetAuthorizationToken` (`Resource: "*"`) and
  `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer` scoped to
  `arn:aws:ecr:<REGION>:<ACCOUNT_ID>:repository/aquagrid/api` — so `docker compose pull`
  on the box can authenticate and pull.

The instance also needs the AWS CLI installed (`docker login` in the rollout script shells
out to `aws ecr get-login-password`) and this repo checked out at `/home/ubuntu/aquagrid`
(the path `deploy.yml`'s SSM step `cd`s into — update both if you relocate it) with
`deploy/docker-compose.yml`, `deploy/docker-compose.prod.yml` and a populated `.env`
(`POSTGRES_PASSWORD`, `AQUAGRID_MASTER_KEY`, `JWT_*`, etc. — see
[docker-compose.yml](../../deploy/docker-compose.yml)) already in place. The rollout
command only pulls and restarts `api`; it does not create or update the `.env`, the
database, or any other service, so that first-time setup is a manual, one-off step.

## 5. GitHub repository variables

Settings → Secrets and variables → Actions → **Variables** tab (not Secrets — none of
these four values are credentials in themselves):

| Name | Value |
|---|---|
| `AWS_ACCOUNT_ID` | your 12-digit account ID |
| `AWS_REGION` | e.g. `ap-south-1` |
| `ECR_REPOSITORY` | `aquagrid/api` |
| `EC2_INSTANCE_ID` | `i-xxxxxxxxxxxxxxxxx` |

## 6. Verify before trusting it to a real push

```bash
aws sts get-caller-identity   # confirms which account you just configured
aws ssm describe-instance-information --filters "Key=InstanceIds,Values=<INSTANCE_ID>"
```

The second command must return the instance with `PingStatus: Online` — if it doesn't,
`deploy.yml` will fail at the SSM step regardless of how correct everything else is.

Once all of the above is in place, merging to `main` is the entire deploy process: CI
runs, and on green, `deploy.yml` builds, pushes to ECR and rolls the new image out to the
instance automatically — nothing further to trigger by hand.
