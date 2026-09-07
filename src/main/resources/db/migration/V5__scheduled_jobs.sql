-- V5: user-requested execution time on jobs.
--
-- timestamp(6) with time zone, matching created_at, started_at, finished_at and next_attempt_at
-- exactly. Hibernate maps java.time.Instant to timestamptz and runs with ddl-auto: validate, so
-- a plain `timestamp` here — the type PostgreSQL gives you if you forget the qualifier — fails
-- startup rather than silently storing a local-time value. That is the point: the whole v0.6
-- timestamp argument is that nothing in this system may depend on a server's local zone, and
-- the column type is the last place that could reintroduce one.
--
-- Nullable, unlike every other v0.6 field. A job submitted without scheduledAt is not "scheduled
-- for now"; it was never scheduled at all, and NULL is the only value that says so. Backfilling
-- existing rows with created_at would be a lie that GET /api/jobs?status=SCHEDULED would then
-- have to reason around.
ALTER TABLE jobs
    ADD COLUMN scheduled_at TIMESTAMP(6) WITH TIME ZONE;

-- Deliberately no CHECK constraint and no constraint tying status to scheduled_at, consistent
-- with V1, V2, V3 and V4. The invariant "status = SCHEDULED implies scheduled_at is not null and
-- in the future at submission time" is real, but it is time-dependent: it stops being true the
-- moment the job is promoted, and a constraint that is only valid at INSERT is not a constraint.
-- Enforcing it would also mean the DB knowing about a sixth status value, which is exactly the
-- coupling V1 documents as having already caused one silent failure.
--
-- The trade-off: nothing at the database level stops a SCHEDULED row with a null scheduled_at
-- being written by something that is not this application. The worker treats a null scheduledAt
-- on a SCHEDULED job as due now, so the failure mode is "runs immediately", not "stuck forever".

-- Serves the SCHEDULED listing and any future reconciliation sweep that has to ask "which rows
-- claim to be scheduled and should by now have been promoted". Named per the idx_<table>_<column>
-- convention established in V2.
--
-- Single-column rather than a partial index on status = 'SCHEDULED'. A partial index would be
-- smaller and is the textbook answer, but it hardcodes a status name into DDL — the coupling
-- this schema has refused four times — and the column is null for every non-scheduled job, so
-- PostgreSQL already leaves those rows out of a plain btree.
CREATE INDEX idx_jobs_scheduled_at
    ON jobs (scheduled_at);
