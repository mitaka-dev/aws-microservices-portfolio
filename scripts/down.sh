#!/usr/bin/env bash
set -euo pipefail

export AWS_PROFILE="${AWS_PROFILE:-aws-microservices-portfolio}"
INFRA_DIR="infra/envs/dev"

echo "This will destroy ALL AWS resources in the dev environment."
echo "Retained after destroy: S3 state bucket, ECR repos (minimal cost)."
echo ""
read -rp "Type 'yes' to confirm: " confirm
if [[ "$confirm" != "yes" ]]; then
  echo "Aborted."
  exit 0
fi

echo ""
echo "==> Destroying infrastructure..."
tofu -chdir="$INFRA_DIR" destroy -auto-approve

echo ""
echo "Done. All compute, database, and networking resources have been removed."
