---
name: terraform-module-conventions
description: IaC conventions for infra/. Use when writing or editing any .tf, .tfvars file, or adding a new OpenTofu module.
allowed-tools: Read, Edit, Write, Bash(tofu fmt *), Bash(tofu validate)
---

# OpenTofu Module Conventions

All infrastructure lives in `infra/`. OpenTofu 1.x+, AWS provider ~> 5.x.

## Repository Layout

```
infra/
├── modules/          — reusable modules (vpc, ecr, rds, dynamodb-table, ecs-service, etc.)
│   └── {name}/
│       ├── main.tf
│       ├── variables.tf
│       ├── outputs.tf
│       └── versions.tf
├── envs/
│   ├── shared/       — cross-env: ECR, IAM
│   └── production/   — the only active env (no staging yet)
└── scripts/
```

## Naming Convention

Every resource: `{org}-{env}-{service}-{resource-type}`, implemented via locals:

```hcl
locals {
  name_prefix = "${var.org}-${var.environment}"
}

resource "aws_iam_role" "task" {
  name = "${local.name_prefix}-${var.service_name}-task"
}
```

Examples: `portfolio-prod-user-service-task`, `portfolio-prod-catalog-service-db`.

## Standard Tags (every taggable resource)

```hcl
provider "aws" {
  default_tags {
    tags = {
      Project     = "aws-microservices-portfolio"
      Environment = var.environment
      Service     = var.service_name
      ManagedBy   = "opentofu"
    }
  }
}
```

## Variable Conventions

```hcl
variable "environment" {
  description = "Deployment environment (production)"
  type        = string
  validation {
    condition     = contains(["production", "shared"], var.environment)
    error_message = "Must be 'production' or 'shared'."
  }
}
```

- Every variable has `description` and `type`
- Sensitive vars: `sensitive = true`
- Never set a default for values without a sensible platform-wide default — require them

## State Backend (every env root)

```hcl
terraform {
  backend "s3" {
    bucket         = "portfolio-tfstate-{account-id}"
    key            = "envs/{env}/{module}.tfstate"
    region         = "eu-west-1"
    dynamodb_table = "portfolio-tfstate-locks"
    encrypt        = true
  }
}
```

## Secrets — Never in .tfvars

```hcl
# Correct: generate and store in Secrets Manager
resource "random_password" "db_master" {
  length  = 32
  special = true
}

resource "aws_secretsmanager_secret_version" "db_creds" {
  secret_id     = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({ password = random_password.db_master.result })
}

# Correct: read existing secret
data "aws_secretsmanager_secret_version" "cognito_config" {
  secret_id = "/production/user-service/cognito-config"
}
```

## Loops: for_each over count

```hcl
# for_each — stable identity under add/remove
resource "aws_ecr_repository" "services" {
  for_each = toset(var.service_names)
  name     = each.key
}

# count — index shifts when items are removed (avoid)
```

## ECS Task Role Pattern (every service)

Every ECS service needs two IAM roles: an **execution role** (used by the ECS agent to pull images and
write logs) and a **task role** (used by the application container at runtime).

```hcl
data "aws_iam_policy_document" "ecs_assume" {
  statement {
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "task" {
  name               = "${local.name_prefix}-${var.service_name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role" "execution" {
  name               = "${local.name_prefix}-${var.service_name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_basic" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}
```

Attach least-privilege inline policies to `aws_iam_role.task` for the specific AWS services the
application uses (SQS, DynamoDB, S3, Secrets Manager, etc.).

## Anti-Patterns — Flag Immediately

| Anti-pattern | Fix |
|---|---|
| Hardcoded account ID, region string, AZ | Use `data.aws_caller_identity`, `data.aws_region`, `data.aws_availability_zones` |
| `Action: "*"` in IAM | Enumerate specific actions |
| `Resource: "*"` on sensitive actions (s3:Delete, kms:Decrypt) | Scope to ARN |
| `0.0.0.0/0` ingress on non-443/80 ports | Restrict to VPC CIDR or specific SGs |
| Resources without tags | Add to `default_tags` in provider |
| `count` for resource lists | Use `for_each` |
| Inline IAM policy on role | Use attached managed policy or `aws_iam_role_policy` |
| Secrets in `.tfvars` or variables | Use Secrets Manager |
| Missing `prevent_destroy` on RDS/DynamoDB | Add lifecycle block |
| `tofu fmt` failures | Run `tofu fmt -recursive .` before commit |

## Required Checks Before Commit

```bash
tofu fmt -check -recursive infra/
tofu validate          # per env directory
```
