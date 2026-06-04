/**
 * autoscale.js — Full auto-scaling lifecycle test (~23 minutes)
 *
 * Drives all 4 services through a complete scale-out + scale-in cycle:
 *
 *   Stage 1 – Idle      (2m)  Baseline: 1 task, ~5 req/s
 *   Stage 2 – Ramp      (3m)  Scale-out: ALB metric crosses 50 req/min/task → 1→2→3 tasks
 *   Stage 3 – Sustain   (5m)  ~225 req/s, 3 tasks fully registered
 *   Stage 4 – Peak ramp (2m)  Stress all 3 tasks to ceiling (~350 req/s)
 *   Stage 5 – Hold peak (3m)  Sustained overload, latency characterisation
 *   Stage 6 – Ramp down (2m)  Drop below scale-in threshold
 *   Stage 7 – Cooldown  (6m)  Wait out 300s scale-in cooldown; tasks deregister
 *
 * Usage:
 *   ./scripts/run-load-test.sh autoscale
 */

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL;
const TOKEN = __ENV.TOKEN;

export const options = {
  stages: [
    { duration: '2m', target: 2 },   // 1 – Idle
    { duration: '3m', target: 30 },  // 2 – Ramp (triggers scale-out)
    { duration: '5m', target: 30 },  // 3 – Sustain
    { duration: '2m', target: 50 },  // 4 – Peak ramp
    { duration: '3m', target: 50 },  // 5 – Hold peak
    { duration: '2m', target: 0 },   // 6 – Ramp down
    { duration: '6m', target: 0 },   // 7 – Cooldown (scale-in)
  ],
  thresholds: {
    // Up to 2% failures are acceptable during new-task spin-up (~120s grace period)
    http_req_failed: ['rate<0.02'],
    // p95 budget covers transient 503s while ALB drains deregistering tasks
    http_req_duration: ['p(95)<6000'],
  },
};

// Stage boundaries in elapsed seconds from test start
const STAGES = [
  { sec: 0,    label: '1 – Idle     (2m) | baseline: 1 task, ~5 req/s' },
  { sec: 120,  label: '2 – Ramp     (3m) | scale-out: 1 → 2 → 3 tasks' },
  { sec: 300,  label: '3 – Sustain  (5m) | ~225 req/s, 3 tasks stable' },
  { sec: 600,  label: '4 – Peak     (2m) | stress ceiling, 50 VUs' },
  { sec: 720,  label: '5 – Hold     (3m) | sustained overload' },
  { sec: 900,  label: '6 – Down     (2m) | ramp-down, scale-in begins' },
  { sec: 1020, label: '7 – Cooldown (6m) | waiting 300s scale-in cooldown' },
];

function currentStageLabel(elapsedSec) {
  let label = STAGES[0].label;
  for (const s of STAGES) {
    if (elapsedSec >= s.sec) label = s.label;
  }
  return label;
}

export function setup() {
  return { startTime: Date.now() };
}

const headers = {
  Authorization: `Bearer ${TOKEN}`,
  'Content-Type': 'application/json',
};

// Per-VU stage tracking — VU 1 prints banners so each stage is announced once
let _lastLabel = '';

export default function (data) {
  const elapsedSec = (Date.now() - data.startTime) / 1000;
  const label = currentStageLabel(elapsedSec);

  if (__VU === 1 && label !== _lastLabel) {
    _lastLabel = label;
    console.log(`\n[autoscale] ==== ${label} ====`);
  }

  const tag = { stage: label.split(' ')[0] }; // e.g. "1"

  // user-service
  const usersRes = http.get(`${BASE_URL}/users`, { headers, tags: tag });
  check(usersRes, { 'users 2xx': (r) => r.status < 300 });
  sleep(0.1);

  // catalog-service
  const catalogRes = http.get(`${BASE_URL}/catalog`, { headers, tags: tag });
  check(catalogRes, { 'catalog 2xx': (r) => r.status < 300 });
  sleep(0.1);

  // order-service
  const ordersRes = http.get(`${BASE_URL}/orders`, { headers, tags: tag });
  check(ordersRes, { 'orders 2xx': (r) => r.status < 300 });
  sleep(0.1);

  // file-service
  const filesRes = http.post(
    `${BASE_URL}/files/presign-upload`,
    JSON.stringify({ fileName: `receipt-${Date.now()}.pdf`, contentType: 'application/pdf' }),
    { headers, tags: tag },
  );
  check(filesRes, { 'files 2xx': (r) => r.status < 300 });
  sleep(0.1);
}
