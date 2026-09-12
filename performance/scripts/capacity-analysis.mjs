export function quantiles(values) {
  const sorted = values.filter(Number.isFinite).sort((left, right) => left - right);
  if (!sorted.length) return { count: 0, p50: null, p95: null, p99: null, max: null };
  const at = fraction => sorted[Math.ceil(fraction * sorted.length) - 1];
  return { count: sorted.length, p50: at(0.5), p95: at(0.95), p99: at(0.99), max: sorted.at(-1) };
}

export function slope(points) {
  if (points.length < 2) return null;
  const origin = points[0][0];
  const meanTime = points.reduce((sum, point) => sum + (point[0] - origin), 0) / points.length;
  const meanValue = points.reduce((sum, point) => sum + point[1], 0) / points.length;
  let numerator = 0;
  let denominator = 0;
  for (const [time, value] of points) {
    const delta = time - origin - meanTime;
    numerator += delta * (value - meanValue);
    denominator += delta * delta;
  }
  return denominator ? numerator / denominator : null;
}

export function reconcile(submissions, jobs, effects, counters, workload) {
  const accepted = submissions.filter(row => row.status === 202);
  const ids = new Set(accepted.map(row => row.id));
  const durable = new Map(jobs.map(row => [row.id, row]));
  const missing = [...ids].filter(id => !durable.has(id));
  const unexpected = jobs.filter(row => !ids.has(row.id)).map(row => row.id);
  const nonterminal = jobs.filter(row => row.status !== 'SUCCEEDED').map(row => row.id);
  const attemptMismatches = jobs.filter(row => row.attempt_count !== row.attempts.length).map(row => row.id);
  const unexpectedAttempts = jobs.filter(row => row.attempt_count !== (workload === 'W3' && row.type === 'fail_n_times' ? 2 : 1)
    || row.attempts.some(attempt => !['SUCCESS', 'FAILURE'].includes(attempt.outcome))).map(row => row.id);
  const expectedEffects = jobs.filter(row => row.type === 'idempotent_counter').map(row => row.id);
  const effectIds = new Set(effects.filter(row => row.status === 'COMPLETED').map(row => row.job_id));
  const badEffects = expectedEffects.filter(id => !effectIds.has(id));
  const early = jobs.filter(row => row.scheduled_at && Date.parse(row.attempts[0]?.started_at) < Date.parse(row.scheduled_at)).map(row => row.id);
  const unpublished = jobs.flatMap(row => row.outbox.filter(event => event.status !== 'PUBLISHED').map(event => event.id));
  const malformed = jobs.filter(row => !row.finished_at || !row.outbox.length || row.execution_owner || row.active_attempt_id
    || row.attempts.some(attempt => !attempt.started_at || !attempt.finished_at)).map(row => row.id);
  const expectedCounter = expectedEffects.length;
  const observedCounter = counters.reduce((sum, row) => sum + Number(row.counter_value), 0);
  const replayResponses = accepted.filter(row => row.replay).length;
  const ok = !missing.length && !unexpected.length && !nonterminal.length && !attemptMismatches.length
    && !unexpectedAttempts.length && !badEffects.length && !early.length && !unpublished.length && !malformed.length
    && effects.length === expectedCounter && effectIds.size === expectedCounter && expectedCounter === observedCounter
    && accepted.length === jobs.length + replayResponses && submissions.length === accepted.length;
  return { ok, acceptedResponses: accepted.length, replayResponses, distinctAccepted: ids.size, durableJobs: jobs.length,
    succeeded: jobs.filter(row => row.status === 'SUCCEEDED').length, attemptRows: jobs.reduce((sum, row) => sum + row.attempts.length, 0),
    expectedEffects: expectedCounter, observedCounter, effects: effects.length,
    missing: missing.slice(0, 50), unexpected: unexpected.slice(0, 50), nonterminal: nonterminal.slice(0, 50),
    attemptMismatches: attemptMismatches.slice(0, 50), unexpectedAttempts: unexpectedAttempts.slice(0, 50),
    badEffects: badEffects.slice(0, 50), early: early.slice(0, 50), unpublished: unpublished.slice(0, 50), malformed: malformed.slice(0, 50) };
}

export function analyzeLatency(submissions, jobs, durationSeconds, clockBounds) {
  const sends = new Map(submissions.filter(row => row.status === 202 && !row.replay).map(row => [row.id, row.sentMs]));
  const first = Math.min(...submissions.map(row => row.sentMs));
  const end = first + durationSeconds * 1000;
  const clockOk = clockBounds.length >= 2 && clockBounds.every(bound => Number.isFinite(bound.lowerMs) && Number.isFinite(bound.upperMs)
    && bound.upperMs - bound.lowerMs <= 100 && Math.abs((bound.lowerMs + bound.upperMs) / 2) <= 100);
  const lower = Math.min(...clockBounds.map(bound => bound.lowerMs));
  const upper = Math.max(...clockBounds.map(bound => bound.upperMs));
  const midpoint = (lower + upper) / 2;
  const successful = jobs.filter(row => row.status === 'SUCCEEDED');
  return {
    quantileMethod: 'nearest rank; milliseconds',
    submission: quantiles(submissions.map(row => row.durationMs)),
    queueing: quantiles(jobs.map(row => Date.parse(row.attempts[0]?.started_at) - Date.parse(row.scheduled_at || row.created_at))),
    execution: quantiles(jobs.flatMap(row => row.attempts.map(attempt => Date.parse(attempt.finished_at) - Date.parse(attempt.started_at)))),
    outboxPublication: quantiles(jobs.flatMap(row => row.outbox.map(event => Date.parse(event.published_at) - Date.parse(event.created_at)))),
    serverCreatedToTerminal: quantiles(successful.map(row => Date.parse(row.finished_at) - Date.parse(row.created_at))),
    endToEnd: clockOk ? quantiles(successful.map(row => Date.parse(row.finished_at) - sends.get(row.id) - midpoint)) : null,
    endToEndUpperBound: clockOk ? quantiles(successful.map(row => Date.parse(row.finished_at) - sends.get(row.id) - lower)) : null,
    clock: { valid: clockOk, lowerOffsetMs: lower, upperOffsetMs: upper, uncertaintyMs: (upper - lower) / 2 },
    acceptedRate: submissions.filter(row => row.status === 202).length / durationSeconds,
    completionRate: successful.filter(row => Date.parse(row.finished_at) >= first + midpoint && Date.parse(row.finished_at) < end + midpoint).length / durationSeconds,
    firstSubmissionMs: first, measurementEndMs: end,
    windows: Array.from({ length: Math.ceil(durationSeconds / 60) }, (_, index) => {
      const start = first + index * 60000;
      const finish = Math.min(end, start + 60000);
      const cohort = successful.filter(row => sends.get(row.id) >= start && sends.get(row.id) < finish);
      return { startMs: start, endMs: finish, jobs: cohort.length,
        latency: quantiles(cohort.map(row => Date.parse(row.finished_at) - Date.parse(row.created_at))) };
    }),
  };
}

export function stability(samples, latencies, rate) {
  const ordered = samples.filter(row => Number.isFinite(row.backlog));
  const midpoint = ordered.length ? ordered[Math.floor(ordered.length / 2)].utcMs : 0;
  const tail = ordered.filter(row => row.utcMs >= midpoint);
  const backlogSlope = slope(tail.map(row => [row.utcMs / 1000, row.backlog]));
  const windows = latencies.windows.filter(window => window.latency.count >= 20);
  const tailWindows = windows.slice(-3);
  const latencyGrowth = tailWindows.length === 3 && tailWindows[1].latency.p99 > tailWindows[0].latency.p99 * 1.1
    && tailWindows[2].latency.p99 > tailWindows[1].latency.p99 * 1.1;
  const slopeThreshold = Math.max(0.005, rate * 0.01);
  return { backlogSlopeJobsPerSecond: backlogSlope, slopeThreshold, latencyGrowth,
    stable: backlogSlope !== null && backlogSlope <= slopeThreshold && !latencyGrowth
      && latencies.completionRate >= latencies.acceptedRate * 0.98 };
}