import http from "k6/http";
import { check } from "k6";

// End-to-end happy-path: create catalog item → place order (with payment) → get presign URL.
// Verifies the full service mesh is functional under load.
//
// Phase 8: POST /orders now calls payment-service synchronously via gRPC.
// Success (all stubs succeed) → 201 CONFIRMED.
// Failure path (payment refused) is tested in PaymentGrpcServiceIT and
// OrderControllerIT (mock returns FAILED → 402), not via k6, since the
// stub strategies have no external trigger to force a failure.
//
// Usage:
//   BASE_URL=$(tofu -chdir=infra/envs/dev output -raw api_gateway_endpoint)
//   TOKEN=$(./scripts/get-token.sh)
//   k6 run -e BASE_URL="${BASE_URL%/}" -e TOKEN="$TOKEN" tests/load/order-flow.js

export const options = {
  vus: 3,
  duration: "1m",
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<5000"],
  },
};

const BASE = __ENV.BASE_URL;
const HEADERS = {
  headers: {
    Authorization: `Bearer ${__ENV.TOKEN}`,
    "Content-Type": "application/json",
  },
};

export default function () {
  // 1. Create a catalog item
  const catalogRes = http.post(
    `${BASE}/catalog`,
    JSON.stringify({ name: `k6-item-${Date.now()}`, price: 9.99, stock: 100 }),
    HEADERS
  );
  check(catalogRes, { "catalog created": (r) => r.status === 201 });

  const itemId = catalogRes.json("id");
  if (!itemId) return;

  // 2. Place an order — includes paymentMethod (CREDIT_CARD is the default)
  const orderRes = http.post(
    `${BASE}/orders`,
    JSON.stringify({
      userId: "k6-user",
      paymentMethod: "CREDIT_CARD",
      items: [{ productId: itemId, quantity: 1, unitPrice: 9.99 }],
    }),
    HEADERS
  );
  check(orderRes, {
    "order accepted": (r) => r.status === 201,
    "order confirmed": (r) => r.json("status") === "CONFIRMED",
  });

  // 3. Request a presigned upload URL
  const presignRes = http.post(
    `${BASE}/files/presign-upload`,
    JSON.stringify({ fileName: `receipt-${Date.now()}.pdf`, contentType: "application/pdf" }),
    HEADERS
  );
  check(presignRes, { "presign 200": (r) => r.status === 200 });
}
