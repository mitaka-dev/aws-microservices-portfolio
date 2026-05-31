#!/usr/bin/env bash
# E2E local test — runs the full happy-path flow against docker compose.
# Requires: docker compose, curl, jq
# Optional: awslocal (pip install awscli-local) for LocalStack inspection
#
# Usage:
#   ./tests/e2e-local.sh          # start stack + run tests
#   ./tests/e2e-local.sh --no-up  # skip docker compose up (stack already running)
set -euo pipefail

# ── helpers ──────────────────────────────────────────────────────────────────

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

ok()   { echo -e "${GREEN}[OK]${NC}   $*"; }
info() { echo -e "${CYAN}[..]${NC}   $*"; }
step() { echo -e "\n${YELLOW}==>${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

assert_http() {
  local label=$1 expected=$2 method=$3 url=$4
  shift 4
  local code
  code=$(curl -s -o /tmp/e2e_assert.txt -w "%{http_code}" -X "$method" "$url" "$@")
  [ "$code" = "$expected" ] \
    && ok "$label → HTTP $code" \
    || fail "$label — expected HTTP $expected, got $code: $(cat /tmp/e2e_assert.txt)"
}

require() { command -v "$1" &>/dev/null || fail "'$1' is required but not installed."; }
require curl
require jq

NO_UP=false
[[ "${1:-}" == "--no-up" ]] && NO_UP=true

USER_URL=http://localhost:8081
CATALOG_URL=http://localhost:8082
ORDER_URL=http://localhost:8083
FILE_URL=http://localhost:8084

# ── Step 1: Start stack ───────────────────────────────────────────────────────

if [ "$NO_UP" = false ]; then
  step "Starting Docker Compose stack (building images if needed)..."
  docker compose up -d --build
fi

# ── Step 2: Wait for all 4 services to respond ───────────────────────────────

step "Waiting for all services to be healthy (up to 4 min)..."
declare -A SVC_URLS=(
  [user-service]=$USER_URL
  [catalog-service]=$CATALOG_URL
  [order-service]=$ORDER_URL
  [file-service]=$FILE_URL
)

for svc in user-service catalog-service order-service file-service; do
  url="${SVC_URLS[$svc]}/actuator/health"
  info "Waiting for $svc at $url ..."
  for i in $(seq 1 48); do
    if curl -sf "$url" | jq -e '.status == "UP"' &>/dev/null; then
      ok "$svc is UP"
      break
    fi
    [ "$i" -eq 48 ] && fail "$svc did not become healthy after 4 minutes"
    sleep 5
  done
done

# ── Step 3: Create a user ─────────────────────────────────────────────────────

RUN_ID=$(date +%s)
EMAIL="alice+${RUN_ID}@example.com"

step "Creating user ($EMAIL)..."
USER_RESP=$(curl -sf -X POST "$USER_URL/users" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"name\":\"Alice Smith\"}")
echo "$USER_RESP" | jq .
USER_ID=$(echo "$USER_RESP" | jq -r '.id')
ok "User created — id=$USER_ID"

GET_USER=$(curl -sf "$USER_URL/users/$USER_ID")
echo "$GET_USER" | jq .
[ "$(echo "$GET_USER" | jq -r '.id')" = "$USER_ID" ] \
  && ok "GET /users/$USER_ID → user retrieved" \
  || fail "GET /users/$USER_ID returned wrong id"

# ── Step 4: Duplicate email → 409 ────────────────────────────────────────────

step "Sending duplicate email — expecting 409..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$USER_URL/users" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"name\":\"Alice Again\"}")
[ "$HTTP_CODE" = "409" ] && ok "Duplicate email → 409 Conflict" || fail "Expected 409, got $HTTP_CODE"

# ── Step 5: Create catalog item ───────────────────────────────────────────────

step "Creating catalog item (Laptop Pro, stock=20)..."
ITEM_RESP=$(curl -sf -X POST "$CATALOG_URL/catalog" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Laptop Pro","category":"electronics","price":1299.99,"stock":20}')
echo "$ITEM_RESP" | jq .
ITEM_ID=$(echo "$ITEM_RESP" | jq -r '.id')
INITIAL_STOCK=$(echo "$ITEM_RESP" | jq -r '.stock')
ok "Catalog item created — id=$ITEM_ID stock=$INITIAL_STOCK"

# ── Step 6: List catalog items ────────────────────────────────────────────────

step "Listing catalog items..."
curl -sf "$CATALOG_URL/catalog" | jq .
ok "GET /catalog OK"

# ── Step 7: Place an order ────────────────────────────────────────────────────
# Flow: POST /orders → PENDING → SNS publish → SQS consume → gRPC DecrementStock → CONFIRMED

step "Placing order (userId=$USER_ID, productId=$ITEM_ID, qty=2)..."
ORDER_RESP=$(curl -sf -X POST "$ORDER_URL/orders" \
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
# SNS→SQS fanout + gRPC decrement can take a few seconds locally.

step "Polling order $ORDER_ID until CONFIRMED (max 60s)..."
for i in $(seq 1 20); do
  POLL=$(curl -sf "$ORDER_URL/orders/$ORDER_ID")
  POLL_STATUS=$(echo "$POLL" | jq -r '.status')
  info "Attempt $i/20 — status=$POLL_STATUS"
  if [ "$POLL_STATUS" = "CONFIRMED" ]; then
    ok "Order CONFIRMED"
    echo "$POLL" | jq .
    break
  elif [ "$POLL_STATUS" = "FAILED" ]; then
    echo "$POLL" | jq .
    fail "Order was FAILED — check order-service logs: docker compose logs order-service"
  fi
  [ "$i" -eq 20 ] && fail "Order still $POLL_STATUS after 60s — check order-service logs"
  sleep 3
done

# ── Step 9: Verify stock decremented ─────────────────────────────────────────

step "Verifying stock was decremented in DynamoDB (via gRPC)..."
UPDATED_ITEM=$(curl -sf "$CATALOG_URL/catalog/$ITEM_ID")
UPDATED_STOCK=$(echo "$UPDATED_ITEM" | jq -r '.stock')
EXPECTED_STOCK=$(( INITIAL_STOCK - 2 ))
info "stock: $INITIAL_STOCK → $UPDATED_STOCK (expected $EXPECTED_STOCK)"
[ "$UPDATED_STOCK" -eq "$EXPECTED_STOCK" ] \
  && ok "Stock correctly decremented by 2" \
  || fail "Stock mismatch — expected $EXPECTED_STOCK, got $UPDATED_STOCK"

# ── Step 10: List all orders ──────────────────────────────────────────────────

step "Listing all orders..."
curl -sf "$ORDER_URL/orders" | jq .
ok "GET /orders OK"

# ── Step 11: Presign upload ───────────────────────────────────────────────────

step "Requesting presigned S3 upload URL..."
PRESIGN_RESP=$(curl -sf -X POST "$FILE_URL/files/presign-upload" \
  -H 'Content-Type: application/json' \
  -d '{"filename":"test.txt","contentType":"text/plain"}')
echo "$PRESIGN_RESP" | jq .
FILE_ID=$(echo "$PRESIGN_RESP" | jq -r '.fileId')
UPLOAD_URL=$(echo "$PRESIGN_RESP" | jq -r '.uploadUrl')
ok "Got presigned upload URL — fileId=$FILE_ID"

# ── Step 12: Upload file via presigned URL ────────────────────────────────────

step "Uploading file content to LocalStack S3 via presigned URL..."
# file-service generates the URL using its internal Docker hostname (localstack:4566).
# Rewrite to localhost so the URL is reachable from the host machine.
HOST_UPLOAD_URL="${UPLOAD_URL/localstack:4566/localhost:4566}"
info "PUT $HOST_UPLOAD_URL"
HTTP_CODE=$(curl -s -o /tmp/e2e_upload.txt -w "%{http_code}" -X PUT "$HOST_UPLOAD_URL" \
  -H 'Content-Type: text/plain' \
  -d 'Hello from E2E test!')
[ "$HTTP_CODE" = "200" ] \
  && ok "File uploaded (HTTP $HTTP_CODE)" \
  || fail "Upload failed with HTTP $HTTP_CODE: $(cat /tmp/e2e_upload.txt)"

# ── Step 13: Presign download + verify content ────────────────────────────────

step "Requesting presigned download URL and verifying content..."
DOWNLOAD_RESP=$(curl -sf "$FILE_URL/files/$FILE_ID/presign-download")
echo "$DOWNLOAD_RESP" | jq .
DOWNLOAD_URL=$(echo "$DOWNLOAD_RESP" | jq -r '.downloadUrl')
HOST_DOWNLOAD_URL="${DOWNLOAD_URL/localstack:4566/localhost:4566}"
CONTENT=$(curl -sf "$HOST_DOWNLOAD_URL")
[ "$CONTENT" = "Hello from E2E test!" ] \
  && ok "File content verified: '$CONTENT'" \
  || fail "Content mismatch — got: '$CONTENT'"

# ── Step 14: 404 — resources that don't exist ────────────────────────────────

step "404 — resources that don't exist..."
assert_http "GET /users/99999"             404 GET "$USER_URL/users/99999"
assert_http "GET /catalog/<bad-uuid>"     404 GET "$CATALOG_URL/catalog/00000000-0000-0000-0000-000000000000"
assert_http "GET /orders/<bad-uuid>"      404 GET "$ORDER_URL/orders/00000000-0000-0000-0000-000000000000"

# ── Step 15: 400 — invalid request bodies ────────────────────────────────────

step "400 — invalid request bodies..."
assert_http "POST /users missing email"    400 POST "$USER_URL/users" \
  -H 'Content-Type: application/json' -d '{"name":"No Email"}'

assert_http "POST /users bad email format" 400 POST "$USER_URL/users" \
  -H 'Content-Type: application/json' -d '{"email":"not-an-email","name":"Bad"}'

assert_http "POST /catalog negative price" 400 POST "$CATALOG_URL/catalog" \
  -H 'Content-Type: application/json' -d '{"name":"X","category":"y","price":-1,"stock":10}'

assert_http "POST /orders empty items"     400 POST "$ORDER_URL/orders" \
  -H 'Content-Type: application/json' -d "{\"userId\":\"$USER_ID\",\"items\":[]}"

assert_http "POST /orders zero quantity"   400 POST "$ORDER_URL/orders" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$USER_ID\",\"items\":[{\"productId\":\"$ITEM_ID\",\"quantity\":0,\"unitPrice\":9.99}]}"

assert_http "POST /files/presign-upload missing filename"    400 POST "$FILE_URL/files/presign-upload" \
  -H 'Content-Type: application/json' -d '{"contentType":"text/plain"}'

assert_http "POST /files/presign-upload missing contentType" 400 POST "$FILE_URL/files/presign-upload" \
  -H 'Content-Type: application/json' -d '{"filename":"test.txt"}'

# ── Step 16: Insufficient stock → order FAILED ───────────────────────────────

step "Insufficient stock — order should be FAILED..."
LOW_ITEM=$(curl -sf -X POST "$CATALOG_URL/catalog" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Scarce Widget","category":"test","price":9.99,"stock":1}')
LOW_ITEM_ID=$(echo "$LOW_ITEM" | jq -r '.id')
ok "Created item with stock=1 — id=$LOW_ITEM_ID"

FAIL_ORDER=$(curl -sf -X POST "$ORDER_URL/orders" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$USER_ID\",\"items\":[{\"productId\":\"$LOW_ITEM_ID\",\"quantity\":5,\"unitPrice\":9.99}]}")
FAIL_ORDER_ID=$(echo "$FAIL_ORDER" | jq -r '.id')
ok "Order placed — id=$FAIL_ORDER_ID"

for i in $(seq 1 20); do
  POLL=$(curl -sf "$ORDER_URL/orders/$FAIL_ORDER_ID")
  POLL_STATUS=$(echo "$POLL" | jq -r '.status')
  info "Attempt $i/20 — status=$POLL_STATUS"
  if [ "$POLL_STATUS" = "FAILED" ]; then
    ok "Order correctly FAILED (insufficient stock)"
    break
  elif [ "$POLL_STATUS" = "CONFIRMED" ]; then
    fail "Order should have FAILED but was CONFIRMED (stock check not working)"
  fi
  [ "$i" -eq 20 ] && fail "Order never reached FAILED after 60s"
  sleep 3
done

# ── Step 17: Actuator metrics ─────────────────────────────────────────────────

step "Business metrics (Micrometer actuator)..."

get_metric() {
  local url=$1 name=$2
  curl -sf "$url/actuator/metrics/$name" 2>/dev/null \
    | jq -r '.measurements[0].value // "not registered yet"'
}

echo ""
printf "  %-40s %s\n" "users.created.total"              "$(get_metric "$USER_URL"    users.created.total)"
printf "  %-40s %s\n" "catalog.items.created.total"      "$(get_metric "$CATALOG_URL" catalog.items.created.total)"
printf "  %-40s %s\n" "orders.created.total"             "$(get_metric "$ORDER_URL"   orders.created.total)"
printf "  %-40s %s\n" "files.presign_upload.total"       "$(get_metric "$FILE_URL"    files.presign_upload.total)"
echo ""

step "JVM + HTTP metrics sample (user-service)..."
curl -sf "$USER_URL/actuator/metrics" \
  | jq '[.names[] | select(startswith("jvm.memory") or startswith("http.server") or startswith("process.cpu"))] | sort'

# ── Step 15: LocalStack inspection ────────────────────────────────────────────

step "LocalStack resource inspection..."
if command -v awslocal &>/dev/null; then
  info "SQS queue depths:"
  awslocal --region eu-west-1 sqs get-queue-attributes \
    --queue-url http://localhost:4566/000000000000/orders-processing \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    | jq .

  info "SQS DLQ depth:"
  awslocal --region eu-west-1 sqs get-queue-attributes \
    --queue-url http://localhost:4566/000000000000/orders-processing-dlq \
    --attribute-names ApproximateNumberOfMessages \
    | jq .

  info "DynamoDB catalog item count:"
  awslocal --region eu-west-1 dynamodb scan --table-name catalog --select COUNT | jq .

  info "S3 bucket objects:"
  awslocal --region eu-west-1 s3 ls s3://portfolio-local-files/
else
  info "awslocal not installed — skipping LocalStack CLI inspection"
  info "Install with: pip install awscli-local"
fi

# ── Done ──────────────────────────────────────────────────────────────────────

echo ""
echo -e "${GREEN}╔══════════════════════════════════════╗${NC}"
echo -e "${GREEN}║      All E2E checks passed!          ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════╝${NC}"
echo ""
echo "Useful follow-up commands:"
echo ""
echo "  # Live logs"
echo "  docker compose logs -f order-service"
echo "  docker compose logs order-service | grep -iE 'confirmed|failed|grpc|sqs|sns'"
echo ""
echo "  # Container status"
echo "  docker compose ps"
echo ""
echo "  # Specific metric"
echo "  curl -s http://localhost:8083/actuator/metrics/orders.created.total | jq"
echo ""
echo "  # All available metrics for a service"
echo "  curl -s http://localhost:8081/actuator/metrics | jq '.names[]'"
echo ""
echo "  # HTTP request histogram (p50/p95/p99)"
echo "  curl -s 'http://localhost:8081/actuator/metrics/http.server.requests' | jq '.availableTags'"
echo "  curl -s 'http://localhost:8081/actuator/metrics/http.server.requests?tag=quantile:0.95' | jq '.measurements'"
echo ""
echo "  # LocalStack — peek at SQS messages without consuming them"
echo "  awslocal --region eu-west-1 sqs receive-message \\"
echo "    --queue-url http://localhost:4566/000000000000/orders-processing \\"
echo "    --visibility-timeout 0 | jq"
echo ""
echo "  # LocalStack — DynamoDB full scan"
echo "  awslocal --region eu-west-1 dynamodb scan --table-name catalog | jq"
echo ""
echo "  # Tear down"
echo "  docker compose down -v"
