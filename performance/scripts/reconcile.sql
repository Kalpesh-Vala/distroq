\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '15s';
SELECT json_build_object(
  'utc', clock_timestamp(),
  'jobs', (SELECT count(*) FROM jobs),
  'statuses', (SELECT json_object_agg(status, total) FROM (SELECT status, count(*) total FROM jobs GROUP BY status) counts),
  'attempts', (SELECT count(*) FROM job_attempts),
  'attemptCountSum', (SELECT coalesce(sum(attempt_count),0) FROM jobs),
  'attemptMismatches', (SELECT count(*) FROM jobs job WHERE job.attempt_count <> (SELECT count(*) FROM job_attempts attempt WHERE attempt.job_id = job.id)),
  'nonterminal', (SELECT count(*) FROM jobs WHERE status NOT IN ('SUCCEEDED','DEAD_LETTERED')),
  'earlyStarts', (SELECT count(*) FROM jobs job JOIN job_attempts attempt ON attempt.job_id = job.id WHERE attempt.started_at < job.scheduled_at),
  'dlq', (SELECT count(*) FROM dead_letters WHERE NOT replayed),
  'dlqMismatches', (SELECT count(*) FROM jobs job FULL JOIN dead_letters dead ON dead.job_id = job.id WHERE (job.status = 'DEAD_LETTERED') IS DISTINCT FROM (dead.job_id IS NOT NULL AND NOT dead.replayed)),
  'outbox', (SELECT json_object_agg(status, total) FROM (SELECT status, count(*) total FROM outbox_events GROUP BY status) counts),
  'unpublished', (SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED'),
  'effects', (SELECT count(*) FROM job_effects),
  'effectStatuses', (SELECT json_object_agg(status, total) FROM (SELECT status, count(*) total FROM job_effects GROUP BY status) counts),
  'counterTotal', (SELECT coalesce(sum(counter_value),0) FROM effect_counters),
  'auditActions', (SELECT count(*) FROM reliability_actions),
  'flyway', (SELECT json_agg(row_to_json(migration)) FROM (SELECT version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank) migration),
  'jobEvidence', (SELECT json_agg(row_to_json(evidence)) FROM (SELECT id, type, status, priority, attempt_count, created_at, scheduled_at, finished_at, execution_lease_until FROM jobs ORDER BY created_at) evidence)
);
COMMIT;