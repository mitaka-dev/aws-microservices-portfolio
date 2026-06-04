#!/usr/bin/env bash
set -euo pipefail

export AWS_PROFILE="${AWS_PROFILE:-aws-microservices-portfolio}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INFRA_DIR="$REPO_ROOT/infra/envs/dev"

echo "==> Initialising OpenTofu..."
tofu -chdir="$INFRA_DIR" init -reconfigure

echo ""
echo "==> Planning infrastructure..."
tofu -chdir="$INFRA_DIR" plan -out=tfplan

echo ""
echo "Cost reminder: NAT Gateway (~\$1.08/day), RDS t4g.micro (~\$0.38/day),"
echo "               ElastiCache t4g.micro (~\$0.38/day). Run ./scripts/down.sh when done."
echo ""
read -rp "Apply this plan? [yes/N] " confirm
if [[ "$confirm" != "yes" ]]; then
  echo "Aborted."
  exit 0
fi

echo ""
echo "==> Applying (this takes ~5 minutes on first run)..."
tofu -chdir="$INFRA_DIR" apply tfplan

echo ""
API_URL=$(tofu -chdir="$INFRA_DIR" output -raw api_gateway_endpoint)
API_URL="${API_URL%/}"

echo "Infrastructure is up."
echo ""
echo "  API endpoint: $API_URL"
echo ""
echo "Next steps:"
echo "  ./scripts/get-token.sh          # create a test user and print a JWT"
echo "  curl \"$API_URL/health\"           # smoke test (no auth required)"
echo "  ./scripts/down.sh               # tear down to stop billing"
