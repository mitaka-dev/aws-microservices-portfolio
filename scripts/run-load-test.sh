#!/usr/bin/env bash
# Resolve BASE_URL and TOKEN automatically, then run a k6 load test.
#
# Usage:
#   ./scripts/run-load-test.sh [test]   # default: autoscale
#   ./scripts/run-load-test.sh smoke
#   ./scripts/run-load-test.sh scale
#   ./scripts/run-load-test.sh order-flow
set -euo pipefail

AWS_REGION="${AWS_REGION:-eu-west-1}"
export AWS_PROFILE="${AWS_PROFILE:-aws-microservices-portfolio}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INFRA_DIR="$REPO_ROOT/infra/envs/dev"
TEST="${1:-autoscale}"
TEST_EMAIL="${K6_EMAIL:-k6-load@example.com}"
TEST_PASSWORD="${K6_PASSWORD:-TestPassword1!}"

echo "==> Resolving API endpoint..."
BASE_URL=$(tofu -chdir="$INFRA_DIR" output -raw api_gateway_endpoint)
BASE_URL="${BASE_URL%/}"
echo "    $BASE_URL"

echo "==> Getting Cognito token for $TEST_EMAIL..."
USER_POOL_ID=$(tofu -chdir="$INFRA_DIR" output -raw cognito_user_pool_id)
APP_CLIENT_ID=$(tofu -chdir="$INFRA_DIR" output -raw cognito_app_client_id)

aws cognito-idp admin-create-user \
  --user-pool-id "$USER_POOL_ID" \
  --username "$TEST_EMAIL" \
  --user-attributes Name=email,Value="$TEST_EMAIL" Name=email_verified,Value=true \
  --message-action SUPPRESS \
  --region "$AWS_REGION" 2>/dev/null || true

aws cognito-idp admin-set-user-password \
  --user-pool-id "$USER_POOL_ID" \
  --username "$TEST_EMAIL" \
  --password "$TEST_PASSWORD" \
  --permanent \
  --region "$AWS_REGION"

TOKEN=$(aws cognito-idp initiate-auth \
  --client-id "$APP_CLIENT_ID" \
  --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters "USERNAME=$TEST_EMAIL,PASSWORD=$TEST_PASSWORD" \
  --region "$AWS_REGION" \
  --query 'AuthenticationResult.IdToken' \
  --output text)

echo "==> Running k6 test: $TEST"
echo ""
k6 run \
  -e BASE_URL="$BASE_URL" \
  -e TOKEN="$TOKEN" \
  "$REPO_ROOT/tests/load/${TEST}.js"
