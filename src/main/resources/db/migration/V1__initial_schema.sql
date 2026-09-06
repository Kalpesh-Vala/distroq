-- V1: baseline of the schema as ddl-auto: update left it at the end of v0.2.
--
-- This is a faithful reproduction of `pg_dump --schema-only` taken from the live database
-- before Flyway was introduced, column order included. It is not a reconstruction of how
-- the schema was built; the per-version history lives in git. Existing databases are
-- baselined at this version rather than running it.
--
-- Note what is deliberately absent: there is no CHECK constraint on jobs.status.
-- Hibernate generated one in v0.1 from the four-value JobStatus enum, and `ddl-auto: update`
-- never widened it when RETRYING was added in v0.2, so every retry write was rejected at
-- commit time. It was dropped by hand to unblock v0.2 and is not being recreated: the
-- application enum is the source of truth, and a DB-level CHECK over an enum that gains a
-- value most versions is a migration burden that has already caused one silent failure.
-- The trade-off is real - nothing at the database level now stops a bad status being
-- written by something that is not this application (a psql session, a future service).
-- That is accepted because the only writer is the app, and a startup-time schema check
-- catches a much larger class of problems than this constraint ever did.

CREATE TABLE jobs (
    id uuid NOT NULL,
    attempt_count integer NOT NULL,
    created_at timestamp(6) with time zone,
    error_message text,
    finished_at timestamp(6) with time zone,
    payload text,
    started_at timestamp(6) with time zone,
    status character varying(255) NOT NULL,
    type character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone,
    max_attempts integer DEFAULT 3 NOT NULL,
    next_attempt_at timestamp(6) with time zone,
    CONSTRAINT jobs_pkey PRIMARY KEY (id)
);

-- job_id is a plain uuid, not a mapped association: JobAttempt stores the raw id rather
-- than a @ManyToOne, so Hibernate never generated a foreign key. Reproduced as-is here;
-- adding one is a schema change, not a baseline.
CREATE TABLE job_attempts (
    id uuid NOT NULL,
    attempt_number integer NOT NULL,
    error_message text,
    finished_at timestamp(6) with time zone,
    job_id uuid NOT NULL,
    outcome character varying(255) NOT NULL,
    started_at timestamp(6) with time zone,
    worker_id character varying(255),
    CONSTRAINT job_attempts_pkey PRIMARY KEY (id),
    CONSTRAINT job_attempts_outcome_check CHECK (outcome::text = ANY (ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying]::text[]))
);
