import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 1, iterations: 1,
  thresholds: { checks: ['rate==1'] },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max', 'count'],
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};

const base = __ENV.BASE_URL || 'http://app:8080';
const run = __ENV.RUN_ID;
const auth = { headers: { Authorization: `Bearer ${__ENV.BENCH_ADMIN_TOKEN}` }, timeout: '10s' };

export default function () {
  if (!run) throw new Error('RUN_ID required');
  const health = http.get(`${base}/actuator/health/readiness`);
  if (!check(health, { ready: response => response.status === 200 })) return;
  const definitions = [
    { type: 'sleep', payload: '10', priority: 'NORMAL', expected: 'SUCCEEDED', attempts: 1 },
    { type: 'fail_n_times', payload: '2', priority: 'HIGH', expected: 'SUCCEEDED', attempts: 3 },
    { type: 'always_fail', payload: '', priority: 'LOW', expected: 'DEAD_LETTERED', attempts: 3 },
    { type: 'sleep', payload: '10', priority: 'HIGH', scheduledAt: new Date(Date.now() + 15000).toISOString(), expected: 'SUCCEEDED', attempts: 1 },
    { type: 'idempotent_counter', payload: run, priority: 'NORMAL', expected: 'SUCCEEDED', attempts: 1 },
    { type: 'sleep', payload: '4000', priority: 'LOW', expected: 'SUCCEEDED', attempts: 1 },
  ];
  const jobs = [];
  definitions.forEach((definition, index) => {
    const body = JSON.stringify({ type: definition.type, payload: definition.payload,
      priority: definition.priority, maxAttempts: 3, scheduledAt: definition.scheduledAt || null });
    const params = { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `${run}-${index}` },
      tags: { name: 'submit' }, timeout: '15s' };
    const sent = Date.now();
    const response = http.post(`${base}/api/jobs`, body, params);
    check(response, { accepted: result => result.status === 202 });
    const id = response.status === 202 ? response.json('id') : null;
    console.log(JSON.stringify({ event: 'submission', index, id, status: response.status,
      sentMs: sent, receivedMs: Date.now(), durationMs: response.timings.duration, replay: false }));
    if (id) jobs.push({ id, ...definition });
    if (index === 4 && id) {
      const duplicateSent = Date.now();
      const duplicate = http.post(`${base}/api/jobs`, body, params);
      check(duplicate, { 'idempotent identity': result => result.status === 202 && result.json('id') === id
        && result.headers['Idempotent-Replay'] === 'true' });
      console.log(JSON.stringify({ event: 'submission', index, id: duplicate.status === 202 ? duplicate.json('id') : null,
        status: duplicate.status, sentMs: duplicateSent, receivedMs: Date.now(), durationMs: duplicate.timings.duration, replay: true }));
    }
  });
  let pendingObserved = false;
  const deadline = Date.now() + 120000;
  let remaining = [...jobs];
  while (remaining.length && Date.now() < deadline) {
    const workers = http.get(`${base}/api/dashboard/workers`, auth);
    if (workers.status === 200) {
      const view = workers.json();
      console.log(JSON.stringify({ event: 'workers-probe', atMs: Date.now(), view }));
      if (view.pendingEntries && view.pendingEntries.availability === 'AVAILABLE'
          && view.pendingEntries.data && view.pendingEntries.data.length > 0) pendingObserved = true;
    }
    remaining = remaining.filter(job => {
      const response = http.get(`${base}/api/jobs/${job.id}`, { tags: { name: 'job-detail' } });
      if (response.status !== 200) return true;
      const detail = response.json();
      if (!['SUCCEEDED', 'DEAD_LETTERED'].includes(detail.status)) return true;
      check(detail, {
        'expected terminal outcome': value => value.status === job.expected,
        'attempt history preserved': value => value.attemptCount === job.attempts && value.attempts.length === job.attempts,
        'not started early': value => !job.scheduledAt || value.attempts.every(attempt => Date.parse(attempt.startedAt) >= Date.parse(job.scheduledAt)),
        'effect recorded': value => job.type !== 'idempotent_counter' || value.effects.length === 1,
      });
      console.log(JSON.stringify({ event: 'terminal', id: job.id, type: job.type, status: detail.status,
        attemptCount: detail.attemptCount, createdAt: detail.createdAt, scheduledAt: detail.scheduledAt,
        startedAt: detail.startedAt, finishedAt: detail.finishedAt,
        attempts: detail.attempts.map(attempt => ({ startedAt: attempt.startedAt, finishedAt: attempt.finishedAt, outcome: attempt.outcome })) }));
      return false;
    });
    if (remaining.length) sleep(0.5);
  }
  check(remaining, { 'all jobs terminal': value => value.length === 0 });
  console.log(JSON.stringify({ event: 'pending-dashboard-validation', pendingObserved }));
  for (const route of ['jobs', 'jobs?sort=createdAt', 'outbox', 'outbox?sort=createdAt']) {
    const response = http.get(`${base}/api/dashboard/${route}`, auth);
    const view = response.status === 200 ? response.json() : null;
    console.log(JSON.stringify({ event: 'release-probe', route, status: response.status, view }));
  }
  const unauthorized = http.get(`${base}/api/dashboard/jobs`);
  check(unauthorized, { 'dashboard requires authentication': response => response.status === 401 });
  const mutation = http.post(`${base}/api/dashboard/jobs`, '{}', auth);
  check(mutation, { 'dashboard rejects mutation': response => response.status === 405 });
  const page = http.get(`${base}/dashboard/`);
  console.log(JSON.stringify({ event: 'packaged-static', status: page.status }));
}

export function handleSummary(data) {
  return { '/results/k6-summary.json': JSON.stringify(data, null, 2) };
}