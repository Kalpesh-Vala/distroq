-- V4: priority tiers on jobs.
--
-- Column type is varchar(255), matching jobs.status and job_attempts.outcome exactly. Hibernate
-- maps @Enumerated(EnumType.STRING) to varchar(255) by default and runs with ddl-auto: validate,
-- so anything narrower here — varchar(6), an enum type, text — fails startup rather than working.
--
-- Deliberately no CHECK constraint, consistent with V1, V2 and V3. This is the third enum-backed
-- column in the schema and the third time the constraint has been declined for the same reason:
-- the application enum is the source of truth, and a DB-level CHECK over an enum that gains values
-- is a migration burden that already caused one silent failure in v0.2. The concrete consequence
-- is that adding a fourth tier — URGENT, BULK, whatever — is a one-line change to the Priority
-- enum and requires ZERO DDL. No ALTER, no migration, no coordinated deploy. That property is
-- the whole v0.2.1 argument, verified again here.
--
-- The trade-off is unchanged and still real: nothing at the database level stops a bad priority
-- being written by a psql session or a future second service.

-- DEFAULT 'NORMAL' does two jobs. It backfills every pre-v0.4 row, which is the correct reading
-- of those jobs — they were submitted before priority existed, i.e. without one, and "without a
-- priority" resolves to NORMAL everywhere else in the system. And it makes the column addable as
-- NOT NULL against a populated table in a single statement. Postgres 11+ stores the default in
-- the catalogue rather than rewriting every row, so this does not table-scan.
--
-- The default is retained after the backfill rather than dropped. It is a second place the
-- default lives (Priority.DEFAULT is the first), which is a duplication worth naming: they must
-- not disagree. Keeping it means a direct INSERT that omits priority behaves the same as the
-- application, which is the safer of the two failure modes now that there is no CHECK constraint.
ALTER TABLE jobs ADD COLUMN priority VARCHAR(255) NOT NULL DEFAULT 'NORMAL';

-- JobRepository.findTop50ByPriorityOrderByCreatedAtDesc and
-- findTop50ByStatusAndPriorityOrderByCreatedAtDesc, behind GET /api/jobs?priority=&status=.
-- Named per the idx_<table>_<column> convention established in V2.
--
-- Single-column rather than a composite (status, priority): the combined filter is served by
-- idx_jobs_status, which is the more selective of the two on any realistic distribution — three
-- priority values across the whole table versus six statuses skewed heavily toward terminal ones.
-- A composite index would be the right answer if the combined query were hot; it is not, and
-- guessing at the leading column of an index nobody has measured is how you end up with two
-- indexes where one would do.
CREATE INDEX idx_jobs_priority ON jobs (priority);
