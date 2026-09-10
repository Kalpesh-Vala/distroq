"""Fixture builders shaped exactly like the extracted source datasets.

Rows are written as dicts and filled against the canonical schemas, so a test states
only the fields it cares about and a schema change surfaces as a failure here rather
than as a silently wrong column order.
"""

from __future__ import annotations

from datetime import datetime, timezone

from pyspark.sql.types import StructType

from distroq_analytics import schemas


def ts(text: str) -> datetime:
    """ISO-8601 with an offset to an aware UTC datetime."""
    normalized = text[:-1] + "+00:00" if text.endswith("Z") else text
    return datetime.fromisoformat(normalized).astimezone(timezone.utc)


def as_read(text: str) -> datetime:
    """The same instant as Spark hands it back.

    Spark returns ``TimestampType`` as a naive ``datetime`` already converted to the
    session timezone, which is pinned to UTC. Comparing that to an aware value fails
    on tzinfo alone, so assertions about read-back timestamps use this instead.
    """
    return ts(text).replace(tzinfo=None)


DEFAULTS: dict[str, dict] = {
    "jobs": {
        "job_type": "email", "priority": "NORMAL", "status": "SUCCEEDED",
        "attempt_count": 1, "max_attempts": 3, "replay_count": None, "replayed": None,
    },
    "job_attempts": {
        "attempt_number": 1, "outcome": "SUCCESS", "worker_id": "worker-1",
    },
    "outbox_events": {
        "aggregate_type": "Job", "event_type": "ENQUEUE_SUBMIT", "status": "PUBLISHED",
        "attempt_count": 1, "operator_retry_count": 0,
    },
    "dead_letters": {
        "replayed": False, "replay_count": 0, "job_type": "email", "priority": "NORMAL",
        "final_status": "DEAD_LETTERED", "attempt_count": 3, "max_attempts": 3,
    },
    "reliability_actions": {
        "action_type": "OUTBOX_RETRY", "target_type": "OutboxEvent", "actor": "operator",
        "reason": "manual retry",
    },
    "job_effects": {
        "attempt_number": 1, "effect_type": "counter", "status": "COMPLETED",
        "job_type": "idempotent_counter", "priority": "NORMAL", "job_attempt_count": 1,
    },
    "idempotency_keys": {"request_hash": "hash-a"},
}


def frame(spark, name: str, rows: list[dict]):
    schema: StructType = schemas.SOURCE_SCHEMAS[name]
    defaults = DEFAULTS.get(name, {})
    tuples = []
    for row in rows:
        merged = {**defaults, **row}
        tuples.append(tuple(merged.get(field.name) for field in schema.fields))
    return spark.createDataFrame(tuples, schema)


def empty(spark, name: str):
    return frame(spark, name, [])


def all_sources(spark, **overrides):
    """Every source dataset, empty unless a test supplies rows for it."""
    return {
        name: frame(spark, name, overrides.get(name, []))
        for name in schemas.SOURCE_SCHEMAS
    }
