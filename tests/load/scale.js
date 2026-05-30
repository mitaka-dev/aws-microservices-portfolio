import http from "k6/http";
import { check, sleep } from "k6";

// Ramps to 20 VUs to push ALBRequestCountPerTarget above the 50 req/min/task
// threshold and trigger ECS scale-out. Watch ECS in CloudWatch while this runs.
//
// Usage:
//   BASE_URL=$(tofu -chdir=infra/envs/dev output -raw api_gateway_endpoint)
//   TOKEN=$(./scripts/get-token.sh)
//   k6 run -e BASE_URL="${BASE_URL%/}" -e TOKEN="$TOKEN" tests/load/scale.js

export const options = {
  stages: [
    { duration: "2m", target: 20 }, // ramp up
    { duration: "3m", target: 20 }, // hold — enough for CloudWatch alarm to fire
    { duration: "1m", target: 0 },  // ramp down
  ],
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<5000"],
  },
};

const BASE = __ENV.BASE_URL;
const AUTH = { headers: { Authorization: `Bearer ${__ENV.TOKEN}` } };

export default function () {
  // Spread load across services so all four scale independently
  check(http.get(`${BASE}/catalog`, AUTH), { "catalog 2xx": (r) => r.status < 300 });
  sleep(0.1);
  check(http.get(`${BASE}/users`, AUTH), { "users 2xx": (r) => r.status < 300 });
  sleep(0.1);
  check(http.get(`${BASE}/orders`, AUTH), { "orders 2xx": (r) => r.status < 300 });
  sleep(0.1);
}
