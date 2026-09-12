import test from 'node:test';
import assert from 'node:assert/strict';
import { quantiles, slope, reconcile, stability } from './capacity-analysis.mjs';
import { hostContention } from './host-policy.mjs';
import { qualificationFlow } from './qualification.mjs';

test('qualification never starts load after failed idle gate', async () => {
  const result = await qualificationFlow({ idle: async () => ({ valid: false }),
    warmup: async () => { throw new Error('must not start'); } });
  assert.equal(result.stoppedAt, 'idle');
});
test('qualification stops at invalid warm-up and never enters P1/P2', async () => {
  const result = await qualificationFlow({ idle: async () => ({ valid: true }),
    warmup: async () => ({ valid: false }), measured: async () => { throw new Error('must not start'); } });
  assert.equal(result.stoppedAt, 'warmup');
});
test('qualification references its excluded warm-up', async () => {
  const result = await qualificationFlow({ idle: async () => ({ valid: true }),
    warmup: async () => ({ valid: true, runId: 'warmup' }),
    measured: async id => { assert.equal(id, 'warmup'); return { valid: true }; } });
  assert.equal(result.status, 'VALID');
});

test('host CPU uses whole-machine mean and spike rules', () => {
  const sample = cpuPercent => ({ processes: [{ name: 'Process(Code)', cpuPercent }] });
  assert.equal(hostContention([sample(80), sample(80)], 8).valid, true);
  assert.equal(hostContention([sample(81), sample(81)], 8).valid, false);
  assert.equal(hostContention([sample(161), sample(0), sample(0), sample(0), sample(0)], 8).valid, false);
  assert.equal(hostContention([sample(NaN)], 8).valid, false);
});

test('nearest rank retains exact samples and empty distributions', () => {
  assert.deepEqual(quantiles([3, 1, 2]), { count: 3, p50: 2, p95: 3, p99: 3, max: 3 });
  assert.equal(quantiles([]).p95, null);
});
test('positive backlog slope and growing tails fail sustainability', () => {
  assert.equal(slope([[0, 1], [5, 11], [10, 21]]), 2);
  assert.equal(stability([{ utcMs: 0, backlog: 0 }, { utcMs: 5000, backlog: 10 }, { utcMs: 10000, backlog: 20 }],
    { windows: [], acceptedRate: 2, completionRate: 2 }, 2).stable, false);
});
test('missing accepted identity cannot reconcile', () => {
  const result = reconcile([{ id: 'missing', status: 202, replay: false }], [], [], [], 'W1');
  assert.equal(result.ok, false);
  assert.deepEqual(result.missing, ['missing']);
});
test('expected mixed retry is distinct from unexpected W1 retry', () => {
  const job = { id: 'job', type: 'fail_n_times', status: 'SUCCEEDED', attempt_count: 2, finished_at: '2026-09-12T00:00:02Z',
    attempts: [{ outcome: 'FAILURE', started_at: '2026-09-12T00:00:00Z', finished_at: '2026-09-12T00:00:01Z' },
      { outcome: 'SUCCESS', started_at: '2026-09-12T00:00:01Z', finished_at: '2026-09-12T00:00:02Z' }],
    outbox: [{ id: 'event', status: 'PUBLISHED' }] };
  assert.equal(reconcile([{ id: 'job', status: 202 }], [job], [], [], 'W3').ok, true);
  assert.equal(reconcile([{ id: 'job', status: 202 }], [job], [], [], 'W1').ok, false);
});
test('counter excess invalidates an otherwise successful effect job', () => {
  const job = { id: 'job', type: 'idempotent_counter', status: 'SUCCEEDED', attempt_count: 1, finished_at: '2026-09-12T00:00:01Z',
    attempts: [{ outcome: 'SUCCESS', started_at: '2026-09-12T00:00:00Z', finished_at: '2026-09-12T00:00:01Z' }], outbox: [{ status: 'PUBLISHED' }] };
  assert.equal(reconcile([{ id: 'job', status: 202 }], [job], [{ job_id: 'job', status: 'COMPLETED' }], [{ counter_value: 2 }], 'W2').ok, false);
});