-- V2: the work V1 cannot do.
--
-- V1 is baselined away on any database that predates Flyway - Flyway records it as applied
-- without executing it. So anything placed only in V1 reaches new databases and never
-- reaches the existing one, which is the precise divergence migrations are supposed to
-- prevent. Changes that must land everywhere therefore go in a migration above the
-- baseline version. On a fresh database V1 runs and then this runs; on the existing
-- database V1 is skipped and this runs. Both converge.

-- Drop the last Hibernate-generated enum CHECK. Same reasoning as jobs.status in V1:
-- AttemptOutcome is an application enum, and pinning its values in DDL means every new
-- value needs a migration that `ddl-auto: update` would silently decline to write.
-- Only present on databases created before Flyway; IF EXISTS covers the fresh path.
ALTER TABLE job_attempts DROP CONSTRAINT IF EXISTS job_attempts_outcome_check;

-- Indexes for the three queries that exist today. Only the primary keys were indexed
-- before this: `ddl-auto: update` does not infer indexes from Spring Data method names,
-- and with no mapped association it did not create one for job_id either.

-- JobRepository.findTop50ByStatusOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs (status);

-- JobRepository.findTop50ByOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON jobs (created_at DESC);

-- JobAttemptRepository.findByJobIdOrderByAttemptNumberAsc, and every job-detail response
CREATE INDEX IF NOT EXISTS idx_job_attempts_job_id ON job_attempts (job_id);
