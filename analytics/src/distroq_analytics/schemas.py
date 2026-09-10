"""Canonical schemas for extracted source datasets.

The JDBC reader will infer a schema from database metadata, but inference is a moving
target: a nullable column that happens to hold no nulls, a numeric width that changes
with a future migration. Every extract is conformed to the schema declared here before
it is written, so the Parquet layout is a decision in this file rather than a side
effect of the data that happened to be in the window.

Type policy:

* UUIDs are stable lower-case strings. Parquet has no UUID logical type that every
  reader understands, and a string round-trips through pandas, DuckDB and Spark alike.
* Instants are ``TimestampType`` written as UTC microseconds.
* Enumerated values are strings, matching the application's ``@Enumerated(STRING)``
  mapping. The database has no CHECK constraint over them by design, so analytics
  validates them instead of assuming them.
* Counts are ``LongType`` even where the source column is ``integer``: aggregate sums
  overflow ``int`` long before they overflow ``bigint``.
"""

from __future__ import annotations

from pyspark.sql import DataFrame
from pyspark.sql import functions as F
from pyspark.sql.types import (
    BooleanType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
    TimestampType,
)


def _f(name: str, dtype, nullable: bool = True) -> StructField:
    return StructField(name, dtype, nullable)


JOBS_SCHEMA = StructType([
    _f("job_id", StringType(), False),
    _f("job_type", StringType(), False),
    _f("priority", StringType(), False),
    _f("status", StringType(), False),
    _f("created_at", TimestampType()),
    _f("scheduled_at", TimestampType()),
    _f("started_at", TimestampType()),
    _f("finished_at", TimestampType()),
    _f("updated_at", TimestampType()),
    _f("next_attempt_at", TimestampType()),
    _f("attempt_count", LongType(), False),
    _f("max_attempts", LongType(), False),
    _f("final_error", StringType()),
    _f("execution_owner", StringType()),
    _f("execution_lease_until", TimestampType()),
    _f("active_attempt_id", StringType()),
    _f("replay_count", LongType()),
    _f("replayed", BooleanType()),
    _f("dead_lettered_at", TimestampType()),
])

JOB_ATTEMPTS_SCHEMA = StructType([
    _f("attempt_id", StringType(), False),
    _f("job_id", StringType(), False),
    _f("attempt_number", IntegerType(), False),
    _f("worker_id", StringType()),
    _f("outcome", StringType(), False),
    _f("started_at", TimestampType()),
    _f("finished_at", TimestampType()),
    _f("error_message", StringType()),
])

OUTBOX_EVENTS_SCHEMA = StructType([
    _f("event_id", StringType(), False),
    _f("aggregate_type", StringType(), False),
    _f("aggregate_id", StringType()),
    _f("event_type", StringType(), False),
    _f("status", StringType(), False),
    _f("created_at", TimestampType(), False),
    _f("available_at", TimestampType(), False),
    _f("published_at", TimestampType()),
    _f("terminal_failed_at", TimestampType()),
    _f("locked_until", TimestampType()),
    _f("attempt_count", LongType(), False),
    _f("operator_retry_count", LongType(), False),
    _f("last_operator_retry_at", TimestampType()),
    _f("last_operator_reason", StringType()),
    _f("last_error", StringType()),
])

DEAD_LETTERS_SCHEMA = StructType([
    _f("job_id", StringType(), False),
    _f("job_type", StringType()),
    _f("priority", StringType()),
    _f("moved_at", TimestampType(), False),
    _f("replayed", BooleanType(), False),
    _f("replayed_at", TimestampType()),
    _f("replay_count", LongType(), False),
    _f("attempt_count", LongType()),
    _f("max_attempts", LongType()),
    _f("final_status", StringType()),
    _f("scheduled_at", TimestampType()),
    _f("job_created_at", TimestampType()),
])

RELIABILITY_ACTIONS_SCHEMA = StructType([
    _f("action_id", StringType(), False),
    _f("action_type", StringType(), False),
    _f("target_type", StringType(), False),
    _f("target_id", StringType()),
    _f("actor", StringType(), False),
    _f("reason", StringType()),
    _f("created_at", TimestampType(), False),
    _f("before_state", StringType()),
    _f("after_state", StringType()),
])

JOB_EFFECTS_SCHEMA = StructType([
    _f("effect_key", StringType(), False),
    _f("job_id", StringType(), False),
    _f("attempt_number", IntegerType(), False),
    _f("effect_type", StringType(), False),
    _f("status", StringType(), False),
    _f("response_hash", StringType()),
    _f("created_at", TimestampType(), False),
    _f("completed_at", TimestampType()),
    _f("error_message", StringType()),
    _f("job_type", StringType()),
    _f("priority", StringType()),
    _f("job_attempt_count", LongType()),
])

IDEMPOTENCY_KEYS_SCHEMA = StructType([
    _f("idempotency_key_hash", StringType(), False),
    _f("request_hash", StringType(), False),
    _f("job_id", StringType(), False),
    _f("created_at", TimestampType(), False),
])

SOURCE_SCHEMAS: dict[str, StructType] = {
    "jobs": JOBS_SCHEMA,
    "job_attempts": JOB_ATTEMPTS_SCHEMA,
    "outbox_events": OUTBOX_EVENTS_SCHEMA,
    "dead_letters": DEAD_LETTERS_SCHEMA,
    "reliability_actions": RELIABILITY_ACTIONS_SCHEMA,
    "job_effects": JOB_EFFECTS_SCHEMA,
    "idempotency_keys": IDEMPOTENCY_KEYS_SCHEMA,
}

DATA_QUALITY_SCHEMA = StructType([
    _f("check_name", StringType(), False),
    _f("severity", StringType(), False),
    _f("count", LongType(), False),
    _f("sample_ids", StringType()),
    _f("description", StringType(), False),
])


def conform(df: DataFrame, schema: StructType) -> DataFrame:
    """Project and cast a DataFrame onto a declared schema.

    Missing columns become typed nulls rather than an error: an empty window must
    still produce a dataset a reader can open and reason about.
    """
    projections = []
    existing = set(df.columns)
    for field in schema.fields:
        if field.name in existing:
            projections.append(F.col(field.name).cast(field.dataType).alias(field.name))
        else:
            projections.append(F.lit(None).cast(field.dataType).alias(field.name))
    return df.select(*projections)


def empty(spark, schema: StructType) -> DataFrame:
    return spark.createDataFrame([], schema)
