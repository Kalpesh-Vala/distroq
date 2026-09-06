-- V3: the dead-letter queue.
--
-- Note what is NOT here: nothing touches jobs.status. Adding DEAD_LETTERED to JobStatus is a
-- zero-DDL change because v0.2.1 dropped jobs_status_check and declined to recreate it. Under
-- `ddl-auto: update` this exact change is what broke v0.2 - the constraint enumerated the four
-- v0.1 statuses, `update` would not widen it, and every write of the new status was rejected at
-- commit. That failure mode is now structurally impossible rather than merely fixed.

-- Column types mirror the conventions in V1 exactly: snake_case, `timestamp(6) with time zone`
-- for instants. Hibernate runs with ddl-auto: validate, so any drift here fails startup.
CREATE TABLE dead_letters (
    job_id uuid NOT NULL,
    final_error text,
    moved_at timestamp(6) with time zone NOT NULL,
    replayed boolean DEFAULT false NOT NULL,
    replayed_at timestamp(6) with time zone,
    replay_count integer DEFAULT 0 NOT NULL,
    CONSTRAINT dead_letters_pkey PRIMARY KEY (job_id),
    CONSTRAINT fk_dead_letters_job FOREIGN KEY (job_id) REFERENCES jobs (id)
);

-- job_id is the primary key, not a surrogate: one DLQ row per job. A job that is replayed and
-- dead-lettered again updates its row rather than inserting a second one, which is what keeps
-- replay_count meaningful across repeat offences.

-- Deliberately no CHECK constraint on any enum-backed column, consistent with V1 and V2.

-- DeadLetterRepository.findTop50ByOrderByMovedAtDesc
CREATE INDEX idx_dead_letters_moved_at ON dead_letters (moved_at DESC);

-- DeadLetterRepository.findTop50ByReplayedOrderByMovedAtDesc and countByReplayed
CREATE INDEX idx_dead_letters_replayed ON dead_letters (replayed);

-- The foreign key job_attempts never had. JobAttempt stores a raw UUID rather than a
-- @ManyToOne, so Hibernate had no association to generate a constraint from - V1 reproduced
-- that absence faithfully because a baseline describes, it does not improve. Adding it is a
-- schema change and therefore belongs here. Verified 0 orphan rows before applying; the
-- constraint is validated against existing data on creation, so a non-zero count would fail
-- this migration rather than silently accept bad data.
--
-- The raw-UUID mapping stays. This is a database-level integrity guarantee, not an ORM
-- relationship, and the reason for the raw UUID (no lazy proxy escaping into DTO mapping)
-- is unchanged.
ALTER TABLE job_attempts
    ADD CONSTRAINT fk_job_attempts_job FOREIGN KEY (job_id) REFERENCES jobs (id);
