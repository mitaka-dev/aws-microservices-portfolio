#!/usr/bin/env bash
set -euo pipefail

AWS_REGION="${AWS_REGION:-eu-west-1}"
export AWS_PROFILE="${AWS_PROFILE:-aws-microservices-portfolio}"

USER_POOL_ID=$(tofu -chdir=infra/envs/dev output -raw cognito_user_pool_id)
APP_CLIENT_ID=$(tofu -chdir=infra/envs/dev output -raw cognito_app_client_id)

TEST_EMAIL="${1:-test@example.com}"
TEST_PASSWORD="${2:-TestPassword1!}"

echo "Creating test user: $TEST_EMAIL"
aws cognito-idp admin-create-user \
  --user-pool-id "$USER_POOL_ID" \
  --username "$TEST_EMAIL" \
  --user-attributes Name=email,Value="$TEST_EMAIL" Name=email_verified,Value=true \
  --message-action SUPPRESS \
  --region "$AWS_REGION" 2>/dev/null || true

echo "Setting permanent password..."
aws cognito-idp admin-set-user-password \
  --user-pool-id "$USER_POOL_ID" \
  --username "$TEST_EMAIL" \
  --password "$TEST_PASSWORD" \
  --permanent \
  --region "$AWS_REGION"

echo "Authenticating..."
TOKEN=$(aws cognito-idp initiate-auth \
  --client-id "$APP_CLIENT_ID" \
  --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters "USERNAME=$TEST_EMAIL,PASSWORD=$TEST_PASSWORD" \
  --region "$AWS_REGION" \
  --query 'AuthenticationResult.IdToken' \
  --output text)

API_URL=$(tofu -chdir=infra/envs/dev output -raw api_gateway_endpoint)

echo ""
echo "ID Token:"
echo "$TOKEN"
echo ""
echo "Test commands:"
echo "  curl -s -o /dev/null -w \"%{http_code}\" $API_URL/health"
echo "  curl -s -o /dev/null -w \"%{http_code}\" -H \"Authorization: Bearer \$TOKEN\" $API_URL/health"
