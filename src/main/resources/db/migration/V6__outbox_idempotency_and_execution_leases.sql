CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    available_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    locked_until TIMESTAMP(6) WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX idx_outbox_publication
    ON outbox_events (published_at, available_at, created_at);
CREATE INDEX idx_outbox_locked_until ON outbox_events (locked_until);
CREATE INDEX idx_outbox_aggregate_id ON outbox_events (aggregate_id);

CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    job_id UUID NOT NULL REFERENCES jobs(id),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_idempotency_job_id ON idempotency_keys (job_id);

ALTER TABLE jobs ADD COLUMN execution_owner VARCHAR(255);
ALTER TABLE jobs ADD COLUMN execution_lease_until TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE jobs ADD COLUMN active_attempt_id UUID;
ALTER TABLE jobs ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_jobs_execution_lease_until ON jobs (execution_lease_until);