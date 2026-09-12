-- v1.1 adds indexes for the read-only operations dashboard. No table, no column, no data.
--
-- The dashboard polls. Every five seconds, from every open tab, it asks for recent activity, for
-- throughput inside a window, and for publication latency. Each of those orders or filters by a
-- timestamp that nothing before v1.1 ever ordered or filtered by, so on a large jobs table each
-- one is a sequential scan and a sort. A monitoring page that degrades the system it monitors is
-- worse than no monitoring page, and adding six indexes is a smaller change than teaching the
-- dashboard to lie about freshness instead.
--
-- These are ordinary CREATE INDEX statements, matching V2 through V7. On PostgreSQL that takes a
-- SHARE lock and blocks writes to the table for the duration. On a table large enough for the
-- lock to matter, build them with CREATE INDEX CONCURRENTLY before deploying and let Flyway find
-- them already present -- IF NOT EXISTS makes that safe. See UPGRADE.md.
--
-- Nothing in the application reads or writes differently because of this file. Removing it would
-- leave every query correct and some of them slow.

-- The activity feed reads the most recently changed jobs, newest first.
CREATE INDEX IF NOT EXISTS idx_jobs_updated_at ON jobs (updated_at DESC);

-- Throughput counts jobs that started or finished inside the dashboard's recent window, and the
-- job list offers both as sort columns.
CREATE INDEX IF NOT EXISTS idx_jobs_started_at ON jobs (started_at DESC);
CREATE INDEX IF NOT EXISTS idx_jobs_finished_at ON jobs (finished_at DESC);

-- Reclaimed work: the newest ABANDONED attempts, and the count of them on the overview.
CREATE INDEX IF NOT EXISTS idx_job_attempts_outcome_finished
    ON job_attempts (outcome, finished_at DESC);

-- Publication latency percentiles sample the most recent publications; the outbox age buckets and
-- the activity feed walk outbox_events newest first.
CREATE INDEX IF NOT EXISTS idx_outbox_published_at ON outbox_events (published_at DESC);
CREATE INDEX IF NOT EXISTS idx_outbox_created_at ON outbox_events (created_at DESC);
