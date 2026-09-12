import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { check, sleep } from 'k6';

const rate = Number(__ENV.RATE || 1);
const seconds = Number(__ENV.SECONDS || 900);
const vus = Number(__ENV.VUS || 50);
const dashboardClients = Number(__ENV.DASHBOARD_CLIENTS || 0);
const base = __ENV.BASE_URL || 'http://app:8080';
const run = __ENV.RUN_ID;
const type = __ENV.JOB_TYPE || 'idempotent_counter';
const workload = __ENV.WORKLOAD || 'W2';
const accepted = new Counter('accepted_submissions');
const submission = new Trend('submission_latency_ms', true);
const rejected = new Counter('rejected_submissions');
const scenarios = {
  submissions: { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${seconds}s`,
    preAllocatedVUs: vus, maxVUs: vus, exec: 'submit', gracefulStop: '30s' },
};
if (__ENV.EXTERNAL_COLLECTOR !== 'true') {
  scenarios.telemetry = { executor: 'constant-vus', vus: 1, duration: `${seconds}s`, exec: 'sample', gracefulStop: '5s' };
}
if (dashboardClients > 0) {
  scenarios.dashboard = { executor: 'constant-vus', vus: dashboardClients, duration: `${seconds}s`,
    exec: 'dashboard', gracefulStop: '15s' };
}
export const options = {
  scenarios,
  thresholds: { dropped_iterations: ['count==0'], 'http_req_failed{scenario:submissions}': ['rate<=0.001'], checks: ['rate==1'] },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max', 'count'],
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};

export function setup() {
  if (!run || !['W1', 'W2', 'W3'].includes(workload) || !['sleep', 'idempotent_counter'].includes(type) || rate < 1 || seconds < 1) {
    throw new Error('RUN_ID, positive RATE/SECONDS and a supported synthetic handler are required');
  }
  const ready = http.get(`${base}/actuator/health/readiness`);
  if (ready.status !== 200) throw new Error('Application is not ready');
  const sentMs = Date.now();
  const clock = http.get(`${base}/api/dashboard/system`, {
    headers: { Authorization: `Bearer ${__ENV.BENCH_ADMIN_TOKEN}` }, timeout: '10s',
  });
  const receivedMs = Date.now();
  console.log(JSON.stringify({ event: 'clock-bound', sentMs, receivedMs,
    serverTimestamp: clock.status === 200 ? clock.json('timestamp') : null }));
  console.log(JSON.stringify({ event: 'config', run, rate, seconds, vus, type, workload, dashboardClients,
    phase: __ENV.PHASE || 'UNCLASSIFIED', dashboardRoute: 'overview', dashboardIntervalMs: 5000 }));
}

export function submit() {
  const iteration = exec.scenario.iterationInTest;
  if (iteration >= rate * seconds) return;
  const params = { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `${run}-${iteration}` },
    tags: { name: 'submit' }, timeout: '15s' };
  const slot = iteration % 20;
  const selectedType = workload === 'W1' ? 'sleep' : workload === 'W2' ? 'idempotent_counter'
    : slot < 14 ? 'sleep' : slot < 17 ? 'idempotent_counter' : slot < 19 ? 'fail_n_times' : 'sleep';
  const payload = selectedType === 'idempotent_counter' ? run : selectedType === 'fail_n_times' ? '1' : '0';
  const scheduledAt = workload === 'W3' && slot === 19 ? new Date(Date.now() + 2000).toISOString() : null;
  const sentMs = Date.now();
  const response = http.post(`${base}/api/jobs`, JSON.stringify({ type: selectedType, payload, priority: 'NORMAL', maxAttempts: 3, scheduledAt }), params);
  submission.add(response.timings.duration);
  const ok = response.status === 202;
  if (ok) accepted.add(1); else rejected.add(1);
  check(response, { 'submission returns 202': result => result.status === 202 });
  console.log(JSON.stringify({ event: 'submission', iteration, id: ok ? response.json('id') : null,
    status: response.status, errorCode: response.error_code || null, sentMs, receivedMs: Date.now(), jobType: selectedType, scheduledAt,
    durationMs: response.timings.duration, replay: response.headers['Idempotent-Replay'] === 'true' }));
}

export function sample() {
  const started = Date.now();
  for (const endpoint of ['prometheus', 'health/readiness', 'health/liveness']) {
    const response = http.get(`${base}/actuator/${endpoint}`, { timeout: '3s', tags: { name: `telemetry-${endpoint}` } });
    console.log(JSON.stringify({ event: 'telemetry', endpoint, sentMs: started, receivedMs: Date.now(),
      status: response.status, body: response.status === 200 ? response.body : null }));
  }
  sleep(Math.max(0, 5 - (Date.now() - started) / 1000));
}

export function dashboard() {
  const started = Date.now();
  const response = http.get(`${base}/api/dashboard/overview`, {
    headers: { Authorization: `Bearer ${__ENV.BENCH_ADMIN_TOKEN}` }, timeout: '10s', tags: { name: 'dashboard-overview' },
  });
  console.log(JSON.stringify({ event: 'dashboard-poll', atMs: Date.now(), status: response.status, durationMs: response.timings.duration }));
  sleep(Math.max(0, 5 - (Date.now() - started) / 1000));
}

export function handleSummary(data) {
  return { '/results/k6-summary.json': JSON.stringify(data, null, 2) };
}