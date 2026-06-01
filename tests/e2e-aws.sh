#!/usr/bin/env bash
# E2E test against the deployed AWS environment.
# Requires: aws CLI (profile aws-microservices-portfolio), tofu, curl, jq
#
# Usage:
#   ./tests/e2e-aws.sh                         # fetch config from tofu, create test user
#   AWS_TOKEN=eyJ... ./tests/e2e-aws.sh --no-token  # skip Cognito user creation, use provided token
set -euo pipefail

# ── helpers ──────────────────────────────────────────────────────────────────

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

ok()      { echo -e "${GREEN}[OK]${NC}   $*"; }
info()    { echo -e "${CYAN}[..]${NC}   $*"; }
step()    { echo -e "\n${YELLOW}==>${NC} $*"; }
fail()    { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }
confirm() {
  local msg=$1
  echo -e "\n${YELLOW}${msg} [y/N]${NC} " >&2
  read -r answer
  [[ "$answer" =~ ^[Yy]$ ]]
}

CLUSTER=portfolio-dev-cluster
SERVICES=(user-service catalog-service order-service file-service)

on_error() {
  echo -e "\n${RED}==> Something failed. Where to look:${NC}"
  for svc in "${SERVICES[@]}"; do
    echo "  aws logs tail /ecs/portfolio-dev-$svc --follow --region ${REGION:-eu-west-1}"
  done
  echo "  Console: https://${REGION:-eu-west-1}.console.aws.amazon.com/cloudwatch/home#logsV2:log-groups"
}
trap on_error ERR

wait_ecs_healthy() {
  step "Waiting for ECS services to be healthy (up to 5 min)..."
  local svcs=("portfolio-dev-user-service" "portfolio-dev-catalog-service"
               "portfolio-dev-order-service" "portfolio-dev-file-service")
  for svc in "${svcs[@]}"; do
    info "Waiting for $svc..."
    for i in $(seq 1 30); do
      running=$(aws ecs describe-services \
        --cluster "$CLUSTER" --services "$svc" --region "$REGION" \
        --query 'services[0].runningCount' --output text 2>/dev/null || echo 0)
      pending=$(aws ecs describe-services \
        --cluster "$CLUSTER" --services "$svc" --region "$REGION" \
        --query 'services[0].pendingCount' --output text 2>/dev/null || echo 1)
      if [ "$running" -ge 1 ] && [ "$pending" -eq 0 ]; then
        ok "$svc — running=$running pending=$pending"
        break
      fi
      info "Attempt $i/30 — running=$running pending=$pending"
      [ "$i" -eq 30 ] && fail "$svc did not stabilise after 5 minutes"
      sleep 10
    done
  done
}

assert_http() {
  local label=$1 expected=$2 method=$3 url=$4
  shift 4
  local code
  code=$(curl -s -o /tmp/e2e_assert.txt -w "%{http_code}" -X "$method" "$url" \
    -H "Authorization: Bearer $TOKEN" "$@")
  [ "$code" = "$expected" ] \
    && ok "$label → HTTP $code" \
    || fail "$label — expected HTTP $expected, got $code: $(cat /tmp/e2e_assert.txt)"
}

require() { command -v "$1" &>/dev/null || fail "'$1' is required but not installed."; }
require curl
require jq
require aws
require tofu

NO_TOKEN=false
[[ "${1:-}" == "--no-token" ]] && NO_TOKEN=true

export AWS_PROFILE=aws-microservices-portfolio
REGION=eu-west-1
INFRA_DIR="$(cd "$(dirname "$0")/../infra/envs/dev" && pwd)"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ── Step 1: Ensure infra is deployed ─────────────────────────────────────────

JUST_APPLIED=false

step "Checking if infra is deployed..."
cd "$INFRA_DIR"

RAW_URL=$(tofu output -raw api_gateway_endpoint 2>/dev/null || true)
if [ -z "$RAW_URL" ] || [[ "$RAW_URL" != https://* ]]; then
  info "Infra not deployed. Running tofu plan..."
  tofu plan -out=/tmp/portfolio-e2e.tfplan
  confirm "Apply this plan?" || { rm -f /tmp/portfolio-e2e.tfplan; info "Aborted."; exit 0; }
  tofu apply /tmp/portfolio-e2e.tfplan
  rm -f /tmp/portfolio-e2e.tfplan
  ok "tofu apply complete"
  JUST_APPLIED=true
  RAW_URL=$(tofu output -raw api_gateway_endpoint)
fi
BASE="${RAW_URL%/}"
POOL_ID=$(tofu output -raw cognito_user_pool_id)
CLIENT_ID=$(tofu output -raw cognito_app_client_id)
SQS_QUEUE_URL=$(tofu output -raw sqs_orders_queue_url)
DYNAMO_TABLE=$(tofu output -raw dynamodb_catalog_table)
S3_BUCKET=$(tofu output -raw s3_files_bucket_name)

cd - > /dev/null

info "API Gateway: $BASE"
info "User Pool:   $POOL_ID"
info "Client:      $CLIENT_ID"

step "Checking ECR images..."
ECR_PUSH_NEEDED=false
for svc in "${SERVICES[@]}"; do
  count=$(aws ecr describe-images --region "$REGION" \
    --repository-name "portfolio-dev-$svc" \
    --image-ids imageTag=latest \
    --query 'length(imageDetails)' --output text 2>/dev/null || echo 0)
  if [ "$count" = "0" ] || [ -z "$count" ] || [ "$count" = "None" ]; then
    info "portfolio-dev-$svc: no images"
    ECR_PUSH_NEEDED=true
  else
    ok "portfolio-dev-$svc: $count image(s)"
  fi
done

if [ "$ECR_PUSH_NEEDED" = true ]; then
  step "Building and pushing all service images to ECR..."
  (cd "$REPO_ROOT" && ./scripts/build-and-push.sh)
  ok "Images pushed"
fi

if [ "$JUST_APPLIED" = true ] || [ "$ECR_PUSH_NEEDED" = true ]; then
  wait_ecs_healthy
fi

# ── Step 2: Cognito — create test user + get JWT token ────────────────────────

RUN_ID=$(date +%s)
TEST_EMAIL="e2e+${RUN_ID}@example.com"
TEST_PASS="E2eTest1!"
TOKEN=""

cleanup() {
  if [ -n "${POOL_ID:-}" ] && [ -n "${TEST_EMAIL:-}" ] && [ "$NO_TOKEN" = false ]; then
    info "Deleting test Cognito user $TEST_EMAIL ..."
    aws cognito-idp admin-delete-user \
      --region "$REGION" \
      --user-pool-id "$POOL_ID" \
      --username "$TEST_EMAIL" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if [ "$NO_TOKEN" = true ]; then
  TOKEN="${AWS_TOKEN:-}"
  [ -z "$TOKEN" ] && fail "AWS_TOKEN env var must be set when using --no-token"
  ok "Using provided token"
else
  step "Creating temporary Cognito test user ($TEST_EMAIL)..."
  aws cognito-idp admin-create-user \
    --region "$REGION" \
    --user-pool-id "$POOL_ID" \
    --username "$TEST_EMAIL" \
    --temporary-password "Temp1234!" \
    --message-action SUPPRESS > /dev/null
  ok "User created"

  aws cognito-idp admin-set-user-password \
    --region "$REGION" \
    --user-pool-id "$POOL_ID" \
    --username "$TEST_EMAIL" \
    --password "$TEST_PASS" \
    --permanent > /dev/null
  ok "Password set to permanent"

  step "Getting JWT token..."
  TOKEN=$(aws cognito-idp initiate-auth \
    --region "$REGION" \
    --auth-flow USER_PASSWORD_AUTH \
    --client-id "$CLIENT_ID" \
    --auth-parameters "USERNAME=$TEST_EMAIL,PASSWORD=$TEST_PASS" \
    --query 'AuthenticationResult.IdToken' \
    --output text)
  ok "Token acquired (${#TOKEN} chars)"
fi

# ── Step 3: Create a user ─────────────────────────────────────────────────────

step "Creating user (alice+${RUN_ID}@example.com)..."
USER_EMAIL="alice+${RUN_ID}@example.com"
USER_RESP=$(curl -sf -X POST "$BASE/users" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$USER_EMAIL\",\"name\":\"Alice Smith\"}")
echo "$USER_RESP" | jq .
USER_ID=$(echo "$USER_RESP" | jq -r '.id')
ok "User created — id=$USER_ID"

GET_USER=$(curl -sf "$BASE/users/$USER_ID" -H "Authorization: Bearer $TOKEN")
echo "$GET_USER" | jq .
[ "$(echo "$GET_USER" | jq -r '.id')" = "$USER_ID" ] \
  && ok "GET /users/$USER_ID → user retrieved" \
  || fail "GET /users/$USER_ID returned wrong id"

# ── Step 4: Duplicate email → 409 ────────────────────────────────────────────

step "Sending duplicate email — expecting 409..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/users" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$USER_EMAIL\",\"name\":\"Alice Again\"}")
[ "$HTTP_CODE" = "409" ] && ok "Duplicate email → 409 Conflict" || fail "Expected 409, got $HTTP_CODE"

# ── Step 5: Create catalog item ───────────────────────────────────────────────

step "Creating catalog item (Laptop Pro, stock=20)..."
ITEM_RESP=$(curl -sf -X POST "$BASE/catalog" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Laptop Pro","category":"electronics","price":1299.99,"stock":20}')
echo "$ITEM_RESP" | jq .
ITEM_ID=$(echo "$ITEM_RESP" | jq -r '.id')
INITIAL_STOCK=$(echo "$ITEM_RESP" | jq -r '.stock')
ok "Catalog item created — id=$ITEM_ID stock=$INITIAL_STOCK"

# ── Step 6: List catalog items ────────────────────────────────────────────────

step "Listing catalog items..."
curl -sf "$BASE/catalog" -H "Authorization: Bearer $TOKEN" | jq .
ok "GET /catalog OK"

# ── Step 7: Place an order ────────────────────────────────────────────────────

step "Placing order (userId=$USER_ID, productId=$ITEM_ID, qty=2)..."
ORDER_RESP=$(curl -sf -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{
    \"userId\": \"$USER_ID\",
    \"items\": [{
      \"productId\": \"$ITEM_ID\",
      \"quantity\": 2,
      \"unitPrice\": 1299.99
    }]
  }")
echo "$ORDER_RESP" | jq .
ORDER_ID=$(echo "$ORDER_RESP" | jq -r '.id')
ok "Order placed — id=$ORDER_ID status=PENDING"

# ── Step 8: Poll until CONFIRMED ──────────────────────────────────────────────

step "Polling order $ORDER_ID until CONFIRMED (max 60s)..."
for i in $(seq 1 20); do
  POLL=$(curl -sf "$BASE/orders/$ORDER_ID" -H "Authorization: Bearer $TOKEN")
  POLL_STATUS=$(echo "$POLL" | jq -r '.status')
  info "Attempt $i/20 — status=$POLL_STATUS"
  if [ "$POLL_STATUS" = "CONFIRMED" ]; then
    ok "Order CONFIRMED"
    echo "$POLL" | jq .
    break
  elif [ "$POLL_STATUS" = "FAILED" ]; then
    echo "$POLL" | jq .
    fail "Order was FAILED — check CloudWatch logs for order-service"
  fi
  [ "$i" -eq 20 ] && fail "Order still $POLL_STATUS after 60s"
  sleep 3
done

# ── Step 9: Verify stock decremented ─────────────────────────────────────────

step "Verifying stock was decremented..."
UPDATED_ITEM=$(curl -sf "$BASE/catalog/$ITEM_ID" -H "Authorization: Bearer $TOKEN")
UPDATED_STOCK=$(echo "$UPDATED_ITEM" | jq -r '.stock')
EXPECTED_STOCK=$(( INITIAL_STOCK - 2 ))
info "stock: $INITIAL_STOCK → $UPDATED_STOCK (expected $EXPECTED_STOCK)"
[ "$UPDATED_STOCK" -eq "$EXPECTED_STOCK" ] \
  && ok "Stock correctly decremented by 2" \
  || fail "Stock mismatch — expected $EXPECTED_STOCK, got $UPDATED_STOCK"

# ── Step 10: List all orders ──────────────────────────────────────────────────

step "Listing all orders..."
curl -sf "$BASE/orders" -H "Authorization: Bearer $TOKEN" | jq .
ok "GET /orders OK"

# ── Step 11: Presign upload ───────────────────────────────────────────────────

step "Requesting presigned S3 upload URL..."
PRESIGN_RESP=$(curl -sf -X POST "$BASE/files/presign-upload" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"filename":"test.txt","contentType":"text/plain"}')
echo "$PRESIGN_RESP" | jq .
FILE_ID=$(echo "$PRESIGN_RESP" | jq -r '.fileId')
UPLOAD_URL=$(echo "$PRESIGN_RESP" | jq -r '.uploadUrl')
ok "Got presigned upload URL — fileId=$FILE_ID"

# ── Step 12: Upload file ──────────────────────────────────────────────────────

step "Uploading file to S3 via presigned URL..."
HTTP_CODE=$(curl -s -o /tmp/e2e_upload.txt -w "%{http_code}" -X PUT "$UPLOAD_URL" \
  -H 'Content-Type: text/plain' \
  -d 'Hello from AWS E2E test!')
[ "$HTTP_CODE" = "200" ] \
  && ok "File uploaded (HTTP $HTTP_CODE)" \
  || fail "Upload failed with HTTP $HTTP_CODE: $(cat /tmp/e2e_upload.txt)"

# ── Step 13: Presign download + verify ───────────────────────────────────────

step "Requesting presigned download URL and verifying content..."
DOWNLOAD_RESP=$(curl -sf "$BASE/files/$FILE_ID/presign-download" \
  -H "Authorization: Bearer $TOKEN")
echo "$DOWNLOAD_RESP" | jq .
DOWNLOAD_URL=$(echo "$DOWNLOAD_RESP" | jq -r '.downloadUrl')
CONTENT=$(curl -sf "$DOWNLOAD_URL")
[ "$CONTENT" = "Hello from AWS E2E test!" ] \
  && ok "File content verified: '$CONTENT'" \
  || fail "Content mismatch — got: '$CONTENT'"

# ── Step 14: 404 — resources that don't exist ────────────────────────────────

step "404 — resources that don't exist..."
assert_http "GET /users/99999"            404 GET "$BASE/users/99999"
assert_http "GET /catalog/<bad-uuid>"    404 GET "$BASE/catalog/00000000-0000-0000-0000-000000000000"
assert_http "GET /orders/<bad-uuid>"     404 GET "$BASE/orders/00000000-0000-0000-0000-000000000000"

# ── Step 15: 400 — invalid request bodies ────────────────────────────────────

step "400 — invalid request bodies..."
assert_http "POST /users missing email"    400 POST "$BASE/users" \
  -H 'Content-Type: application/json' -d '{"name":"No Email"}'

assert_http "POST /users bad email format" 400 POST "$BASE/users" \
  -H 'Content-Type: application/json' -d '{"email":"not-an-email","name":"Bad"}'

assert_http "POST /catalog negative price" 400 POST "$BASE/catalog" \
  -H 'Content-Type: application/json' -d '{"name":"X","category":"y","price":-1,"stock":10}'

assert_http "POST /orders empty items"     400 POST "$BASE/orders" \
  -H 'Content-Type: application/json' -d "{\"userId\":\"$USER_ID\",\"items\":[]}"

assert_http "POST /orders zero quantity"   400 POST "$BASE/orders" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$USER_ID\",\"items\":[{\"productId\":\"$ITEM_ID\",\"quantity\":0,\"unitPrice\":9.99}]}"

assert_http "POST /files/presign-upload missing filename"    400 POST "$BASE/files/presign-upload" \
  -H 'Content-Type: application/json' -d '{"contentType":"text/plain"}'

assert_http "POST /files/presign-upload missing contentType" 400 POST "$BASE/files/presign-upload" \
  -H 'Content-Type: application/json' -d '{"filename":"test.txt"}'

# ── Step 16: Insufficient stock → order FAILED ───────────────────────────────

step "Insufficient stock — order should be FAILED..."
LOW_ITEM=$(curl -sf -X POST "$BASE/catalog" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Scarce Widget","category":"test","price":9.99,"stock":1}')
LOW_ITEM_ID=$(echo "$LOW_ITEM" | jq -r '.id')
ok "Created item with stock=1 — id=$LOW_ITEM_ID"

FAIL_ORDER=$(curl -sf -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$USER_ID\",\"items\":[{\"productId\":\"$LOW_ITEM_ID\",\"quantity\":5,\"unitPrice\":9.99}]}")
FAIL_ORDER_ID=$(echo "$FAIL_ORDER" | jq -r '.id')
ok "Order placed — id=$FAIL_ORDER_ID"

for i in $(seq 1 20); do
  POLL=$(curl -sf "$BASE/orders/$FAIL_ORDER_ID" -H "Authorization: Bearer $TOKEN")
  POLL_STATUS=$(echo "$POLL" | jq -r '.status')
  info "Attempt $i/20 — status=$POLL_STATUS"
  if [ "$POLL_STATUS" = "FAILED" ]; then
    ok "Order correctly FAILED (insufficient stock)"
    break
  elif [ "$POLL_STATUS" = "CONFIRMED" ]; then
    fail "Order should have FAILED but was CONFIRMED"
  fi
  [ "$i" -eq 20 ] && fail "Order never reached FAILED after 60s"
  sleep 3
done

# ── Step 17: AWS resource inspection ─────────────────────────────────────────

step "AWS resource inspection..."

info "SQS queue depths:"
aws sqs get-queue-attributes \
  --region "$REGION" \
  --queue-url "$SQS_QUEUE_URL" \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
  | jq .

info "DynamoDB catalog item count:"
aws dynamodb scan \
  --region "$REGION" \
  --table-name "$DYNAMO_TABLE" \
  --select COUNT | jq .

info "S3 bucket objects:"
aws s3 ls "s3://$S3_BUCKET/" --region "$REGION"

# ── Step 18: Business metrics ─────────────────────────────────────────────────

step "Business metrics (Micrometer actuator via API Gateway)..."

get_metric() {
  local path=$1 name=$2
  curl -sf "$BASE/$path/actuator/metrics/$name" \
    -H "Authorization: Bearer $TOKEN" 2>/dev/null \
    | jq -r '.measurements[0].value // "not registered yet"'
}

echo ""
printf "  %-40s %s\n" "users.created.total"         "$(get_metric users    users.created.total)"
printf "  %-40s %s\n" "catalog.items.created.total" "$(get_metric catalog  catalog.items.created.total)"
printf "  %-40s %s\n" "orders.created.total"        "$(get_metric orders   orders.created.total)"
printf "  %-40s %s\n" "files.presign_upload.total"  "$(get_metric files    files.presign_upload.total)"
echo ""

# ── Done ──────────────────────────────────────────────────────────────────────

echo ""
echo -e "${GREEN}╔══════════════════════════════════════╗${NC}"
echo -e "${GREEN}║      All E2E checks passed!          ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════╝${NC}"
echo ""

# ── Destroy prompt ────────────────────────────────────────────────────────────

if confirm "Destroy infra now to avoid charges?"; then
  cd "$INFRA_DIR"
  info "Running tofu plan -destroy..."
  tofu plan -destroy -out=/tmp/portfolio-e2e-destroy.tfplan
  confirm "Confirm destroy?" || { rm -f /tmp/portfolio-e2e-destroy.tfplan; info "Destroy cancelled. Infra left running."; cd - > /dev/null; exit 0; }
  tofu apply /tmp/portfolio-e2e-destroy.tfplan
  rm -f /tmp/portfolio-e2e-destroy.tfplan
  ok "Infrastructure destroyed."
  cd - > /dev/null
else
  echo ""
  info "Infra left running. Destroy when done:"
  info "  cd infra/envs/dev && tofu destroy"
  echo ""
  echo "Useful commands:"
  echo ""
  echo "  # Tail CloudWatch logs"
  echo "  aws logs tail /ecs/portfolio-dev-order-service --follow --region $REGION"
  echo ""
  echo "  # Filter for order processing events"
  echo "  aws logs filter-log-events --log-group-name /ecs/portfolio-dev-order-service \\"
  echo "    --region $REGION --filter-pattern 'CONFIRMED OR FAILED OR grpc OR sqs'"
  echo ""
  echo "  # Check X-Ray traces (last 10 min)"
  echo "  aws xray get-trace-summaries --start-time \$(date -d '10 min ago' +%s) \\"
  echo "    --end-time \$(date +%s) --region $REGION | jq '.TraceSummaries | length'"
  echo ""
  echo "  # Re-run without creating a new Cognito user:"
  echo "  AWS_TOKEN=\$TOKEN ./tests/e2e-aws.sh --no-token"
fi
