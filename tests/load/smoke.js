import http from "k6/http";
import { check } from "k6";

// Usage:
//   BASE_URL=$(tofu -chdir=infra/envs/dev output -raw api_gateway_endpoint)
//   TOKEN=$(./scripts/get-token.sh)
//   k6 run -e BASE_URL="${BASE_URL%/}" -e TOKEN="$TOKEN" tests/load/smoke.js

export const options = {
  vus: 1,
  duration: "30s",
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<3000"],
  },
};

const BASE = __ENV.BASE_URL;
const AUTH = { headers: { Authorization: `Bearer ${__ENV.TOKEN}` } };

export default function () {
  check(http.get(`${BASE}/catalog`, AUTH), { "catalog 200": (r) => r.status === 200 });
  const email = `smoke-${__VU}-${__ITER}@example.com`;
  const res = http.post(
    `${BASE}/users`,
    JSON.stringify({ name: "Smoke Test", email }),
    Object.assign({}, AUTH, { headers: Object.assign({}, AUTH.headers, { "Content-Type": "application/json" }) }),
  );
  check(res, { "users 201": (r) => r.status === 201 });
}
