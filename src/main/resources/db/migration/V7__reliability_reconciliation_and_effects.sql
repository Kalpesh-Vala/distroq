-- v0.8 makes the outbox lifecycle explicit, adds an audit log for every operator or
-- reconciliation mutation, and adds a ledger for side effects that opt into the effect protocol.

-- ---------------------------------------------------------------------------------------------
-- Outbox lifecycle
--
-- v0.6 inferred state from nullable columns: published_at, attempt_count, locked_until. That is
-- enough for a relay loop and not enough for an operator, because "failed" and "not tried yet"
-- look the same until you also know max-attempts. The status column is now the source of truth
-- and the nullable columns stay for audit.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE outbox_events
    ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'PENDING';

ALTER TABLE outbox_events
    ADD COLUMN operator_retry_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE outbox_events
    ADD COLUMN terminal_failed_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE outbox_events
    ADD COLUMN last_operator_retry_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE outbox_events
    ADD COLUMN last_operator_reason TEXT;

-- Backfill: every pre-v0.8 row that already published is PUBLISHED, and everything else starts
-- PENDING, which is what the DEFAULT already gave it. A row that was terminal under v0.7 rules
-- is left PENDING on purpose — the relay will re-derive terminality from the current
-- max-attempts setting rather than this migration guessing at the setting in force when it failed.
UPDATE outbox_events SET status = 'PUBLISHED' WHERE published_at IS NOT NULL;

CREATE INDEX idx_outbox_status_available
    ON outbox_events (status, available_at, created_at);
CREATE INDEX idx_outbox_status_terminal
    ON outbox_events (status, terminal_failed_at);
CREATE INDEX idx_outbox_last_operator_retry
    ON outbox_events (last_operator_retry_at);

-- ---------------------------------------------------------------------------------------------
-- Reliability audit log
--
-- One row per mutation performed by an operator or by reconciliation, written in the same
-- transaction as the state change it describes. Payloads are never copied here.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reliability_actions (
    id UUID PRIMARY KEY,
    action_type VARCHAR(100) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id UUID,
    reason TEXT NOT NULL,
    actor VARCHAR(255) NOT NULL,
    before_state TEXT,
    after_state TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_reliability_actions_type_created
    ON reliability_actions (action_type, created_at);
CREATE INDEX idx_reliability_actions_target
    ON reliability_actions (target_type, target_id);
CREATE INDEX idx_reliability_actions_created
    ON reliability_actions (created_at);

-- ---------------------------------------------------------------------------------------------
-- Side-effect ledger
--
-- effect_key is the primary key and it is the whole mechanism: claiming an effect is an INSERT
-- that either succeeds or collides, so two workers racing the same logical effect are resolved
-- by PostgreSQL rather than by application timing. response_hash holds a digest, never a response.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE job_effects (
    effect_key VARCHAR(255) PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id),
    attempt_number INTEGER NOT NULL,
    effect_type VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    response_hash VARCHAR(64),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    error_message TEXT
);

CREATE INDEX idx_job_effects_job_created ON job_effects (job_id, created_at);
CREATE INDEX idx_job_effects_status_created ON job_effects (status, created_at);

-- ---------------------------------------------------------------------------------------------
-- Durable counter behind the built-in idempotent_counter job type
--
-- Incremented with INSERT ... ON CONFLICT DO UPDATE inside the same transaction that completes
-- the ledger row, so the counter and the ledger can never disagree.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE effect_counters (
    counter_name VARCHAR(255) PRIMARY KEY,
    counter_value BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
