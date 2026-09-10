"""Source extracts to fact tables.

The rules that matter here are about what is *not* done:

* A derived value is null when its inputs are null. Nothing invents a timestamp, and
  nothing substitutes ``now()`` for a missing ``finished_at``.
* A negative duration is preserved, not clamped. It is a real inconsistency and the
  data-quality report is where it gets named; silently correcting it here would delete
  the evidence.
* ``queue_delay_ms`` and ``schedule_delay_ms`` are mutually exclusive by construction.
  A scheduled job did not queue, it waited for a time the user chose, and averaging the
  two together would make scheduling look like latency.

One caveat is inherent in the source schema rather than in this code: ``jobs.started_at``
is overwritten by every attempt (``Job.markRunning``), so ``duration_ms`` measures the
final attempt and ``queue_delay_ms`` includes retry backoff for a job that was retried.
``first_attempt_started_at``/``first_queue_delay_ms`` are derived from ``job_attempts``
alongside them for the case where the true admission delay is what is wanted.
"""

from __future__ import annotations

from datetime import datetime

from pyspark.sql import DataFrame
from pyspark.sql import functions as F

TERMINAL_STATUSES = ("SUCCEEDED", "FAILED", "DEAD_LETTERED")
VALID_JOB_STATUSES = ("SCHEDULED", "QUEUED", "RUNNING", "RETRYING", "SUCCEEDED", "FAILED",
                      "DEAD_LETTERED")
VALID_ATTEMPT_OUTCOMES = ("IN_PROGRESS", "SUCCESS", "FAILURE", "ABANDONED")
VALID_EFFECT_STATUSES = ("STARTED", "COMPLETED", "FAILED")
VALID_OUTBOX_STATUSES = ("PENDING", "PUBLISHING", "PUBLISHED", "FAILED")
SCHEDULE_EVENT_TYPES = ("SCHEDULE_USER_JOB",)
RETRY_EVENT_TYPES = ("SCHEDULE_RETRY",)


def millis_between(start: str, end: str):
    """``end - start`` in whole milliseconds, or null if either side is missing."""
    return F.when(
        F.col(start).isNotNull() & F.col(end).isNotNull(),
        F.expr(
            f"CAST(ROUND((unix_micros(`{end}`) - unix_micros(`{start}`)) / 1000.0) AS BIGINT)"
        ),
    ).otherwise(F.lit(None).cast("bigint"))


def millis_since(start: str, moment: datetime):
    """``moment - start`` in whole milliseconds, or null if ``start`` is missing.

    ``moment`` is resolved to epoch millis in Python rather than embedded as a SQL
    literal, so the reference instant cannot be reinterpreted by a session timezone.
    """
    epoch_millis = round(moment.timestamp() * 1000)
    return F.when(
        F.col(start).isNotNull(),
        F.lit(epoch_millis).cast("bigint")
        - F.expr(f"CAST(ROUND(unix_micros(`{start}`) / 1000.0) AS BIGINT)"),
    ).otherwise(F.lit(None).cast("bigint"))


def utc_day(column: str):
    """Calendar day in UTC.

    Correct only because the session timezone is pinned to UTC in ``spark.build_session``.
    """
    return F.to_date(F.col(column))


def job_facts(jobs: DataFrame, attempts: DataFrame) -> DataFrame:
    first_starts = (
        attempts.groupBy("job_id")
        .agg(
            F.min("started_at").alias("first_attempt_started_at"),
            F.count(F.lit(1)).cast("bigint").alias("observed_attempt_rows"),
            F.sum(F.when(F.col("outcome") == "SUCCESS", 1).otherwise(0))
            .cast("bigint").alias("observed_success_attempts"),
            F.sum(F.when(F.col("outcome") == "IN_PROGRESS", 1).otherwise(0))
            .cast("bigint").alias("observed_in_progress_attempts"),
            F.sum(F.when(F.col("outcome") == "ABANDONED", 1).otherwise(0))
            .cast("bigint").alias("observed_abandoned_attempts"),
        )
    )

    joined = jobs.join(first_starts, on="job_id", how="left")
    is_scheduled = F.col("scheduled_at").isNotNull()

    return joined.select(
        F.col("job_id"),
        F.col("job_type"),
        F.col("priority"),
        F.col("created_at"),
        F.col("scheduled_at"),
        F.col("started_at"),
        F.col("finished_at"),
        F.col("status"),
        F.col("attempt_count"),
        F.col("max_attempts"),
        millis_between("started_at", "finished_at").alias("duration_ms"),
        F.when(~is_scheduled, millis_between("created_at", "started_at"))
        .otherwise(F.lit(None).cast("bigint")).alias("queue_delay_ms"),
        F.when(is_scheduled, millis_between("scheduled_at", "started_at"))
        .otherwise(F.lit(None).cast("bigint")).alias("schedule_delay_ms"),
        F.col("final_error"),
        is_scheduled.alias("is_scheduled"),
        (F.col("status") == "SUCCEEDED").alias("is_succeeded"),
        (F.col("status") == "DEAD_LETTERED").alias("is_dead_lettered"),
        (F.col("status") == "FAILED").alias("is_legacy_failed"),
        F.coalesce(F.col("replay_count"), F.lit(0)).cast("bigint").alias("replay_count"),
        # Supplementary, beyond the required set.
        F.col("status").isin(*TERMINAL_STATUSES).alias("is_terminal"),
        (F.col("attempt_count") > 1).alias("is_retried"),
        F.coalesce(F.col("replayed"), F.lit(False)).alias("is_replayed"),
        F.col("dead_lettered_at"),
        F.col("first_attempt_started_at"),
        F.when(~is_scheduled, millis_between("created_at", "first_attempt_started_at"))
        .otherwise(F.lit(None).cast("bigint")).alias("first_queue_delay_ms"),
        F.coalesce(F.col("observed_attempt_rows"), F.lit(0)).cast("bigint")
        .alias("observed_attempt_rows"),
        F.coalesce(F.col("observed_success_attempts"), F.lit(0)).cast("bigint")
        .alias("observed_success_attempts"),
        F.coalesce(F.col("observed_in_progress_attempts"), F.lit(0)).cast("bigint")
        .alias("observed_in_progress_attempts"),
        F.coalesce(F.col("observed_abandoned_attempts"), F.lit(0)).cast("bigint")
        .alias("observed_abandoned_attempts"),
        F.col("execution_owner"),
        F.col("execution_lease_until"),
        F.col("status").isin(*VALID_JOB_STATUSES).alias("has_valid_status"),
        utc_day("created_at").alias("day"),
    )


def attempt_facts(attempts: DataFrame) -> DataFrame:
    return attempts.select(
        F.col("attempt_id"),
        F.col("job_id"),
        F.col("attempt_number"),
        F.col("worker_id"),
        F.col("outcome"),
        F.col("started_at"),
        F.col("finished_at"),
        millis_between("started_at", "finished_at").alias("duration_ms"),
        F.col("error_message"),
        (F.col("outcome") == "ABANDONED").alias("is_abandoned"),
        (F.col("outcome") == "SUCCESS").alias("is_success"),
        (F.col("outcome") == "FAILURE").alias("is_failure"),
        (F.col("outcome") == "IN_PROGRESS").alias("is_in_progress"),
        F.col("outcome").isin(*VALID_ATTEMPT_OUTCOMES).alias("has_valid_outcome"),
        utc_day("started_at").alias("day"),
    )


def outbox_facts(events: DataFrame, export_time: datetime) -> DataFrame:
    is_published = F.col("status") == "PUBLISHED"
    return events.select(
        F.col("event_id"),
        F.col("aggregate_type"),
        F.col("aggregate_id"),
        F.col("event_type"),
        F.col("status"),
        F.col("created_at"),
        F.col("available_at"),
        F.col("published_at"),
        F.col("terminal_failed_at"),
        F.col("attempt_count"),
        F.col("operator_retry_count"),
        F.col("last_operator_retry_at"),
        # Null for anything not published: an unpublished event has no latency, and a
        # zero here would drag every average toward a value nothing ever measured.
        F.when(is_published, millis_between("created_at", "published_at"))
        .otherwise(F.lit(None).cast("bigint")).alias("publication_latency_ms"),
        F.when(~is_published, millis_since("created_at", export_time))
        .otherwise(F.lit(None).cast("bigint")).alias("age_at_export_ms"),
        is_published.alias("is_published"),
        (F.col("status") == "FAILED").alias("is_terminal_failed"),
        # Supplementary, beyond the required set.
        (F.col("status") == "PENDING").alias("is_pending"),
        (F.col("status") == "PUBLISHING").alias("is_publishing"),
        (F.col("operator_retry_count") > 0).alias("has_operator_retry"),
        millis_between("created_at", "available_at").alias("availability_delay_ms"),
        F.col("last_operator_reason"),
        F.col("status").isin(*VALID_OUTBOX_STATUSES).alias("has_valid_status"),
        utc_day("created_at").alias("day"),
    )


def dead_letter_facts(dead_letters: DataFrame) -> DataFrame:
    return dead_letters.select(
        F.col("job_id"),
        F.col("job_type"),
        F.col("priority"),
        F.col("moved_at"),
        F.col("replayed"),
        F.col("replayed_at"),
        F.col("replay_count"),
        F.col("attempt_count"),
        F.col("final_status"),
        F.col("scheduled_at"),
        # Supplementary, beyond the required set.
        F.col("max_attempts"),
        F.col("job_created_at"),
        F.col("final_status").isNotNull().alias("has_job"),
        millis_between("moved_at", "replayed_at").alias("replay_delay_ms"),
        utc_day("moved_at").alias("day"),
    )


def reliability_action_facts(actions: DataFrame) -> DataFrame:
    return actions.select(
        F.col("action_id"),
        F.col("action_type"),
        F.col("target_type"),
        F.col("target_id"),
        F.col("actor"),
        F.col("reason"),
        F.col("created_at"),
        F.col("before_state"),
        F.col("after_state"),
        utc_day("created_at").alias("day"),
    )


def effect_facts(effects: DataFrame, export_time: datetime, stale_after_ms: int) -> DataFrame:
    is_completed = F.col("status") == "COMPLETED"
    age_ms = millis_since("created_at", export_time)
    return effects.select(
        F.col("effect_key"),
        F.col("job_id"),
        F.col("attempt_number"),
        F.col("effect_type"),
        F.col("status"),
        F.col("response_hash"),
        F.col("created_at"),
        F.col("completed_at"),
        millis_between("created_at", "completed_at").alias("duration_ms"),
        is_completed.alias("is_completed"),
        (F.col("status") == "FAILED").alias("is_failed"),
        ((F.col("status") == "STARTED") & (age_ms > F.lit(stale_after_ms)))
        .alias("is_stale_candidate"),
        # Supplementary, beyond the required set.
        #
        # A deduplication hit is a Micrometer counter in the application, not a row, so
        # it cannot be extracted directly. It is durably *implied*, though: the effect
        # key for the counter job type deliberately excludes the attempt number, so a
        # completed effect claimed on attempt N while the job went on to run M attempts
        # means every attempt after N found the key already COMPLETED and skipped the
        # work. That difference is the hit count. It is a lower bound, not a total: an
        # effect completed on the job's last attempt shows zero even though the ledger
        # is exactly what stopped a second increment from being possible.
        F.when(
            is_completed & F.col("job_attempt_count").isNotNull(),
            F.greatest(F.col("job_attempt_count") - F.col("attempt_number"), F.lit(0)),
        ).otherwise(F.lit(0)).cast("bigint").alias("deduplication_hits"),
        (F.col("status") == "STARTED").alias("is_started"),
        F.col("job_type"),
        F.col("priority"),
        F.col("job_attempt_count"),
        age_ms.alias("age_at_export_ms"),
        F.col("status").isin(*VALID_EFFECT_STATUSES).alias("has_valid_status"),
        utc_day("created_at").alias("day"),
    )


def idempotency_facts(keys: DataFrame) -> DataFrame:
    return keys.select(
        F.col("idempotency_key_hash"),
        F.col("request_hash"),
        F.col("job_id"),
        F.col("created_at"),
        utc_day("created_at").alias("day"),
    )


FACT_BUILDERS = (
    "job_facts",
    "attempt_facts",
    "outbox_facts",
    "dead_letter_facts",
    "reliability_action_facts",
    "effect_facts",
    "idempotency_facts",
)


def build_all(extracts: dict[str, DataFrame], export_time: datetime,
              stale_after_ms: int) -> dict[str, DataFrame]:
    return {
        "job_facts": job_facts(extracts["jobs"], extracts["job_attempts"]),
        "attempt_facts": attempt_facts(extracts["job_attempts"]),
        "outbox_facts": outbox_facts(extracts["outbox_events"], export_time),
        "dead_letter_facts": dead_letter_facts(extracts["dead_letters"]),
        "reliability_action_facts": reliability_action_facts(extracts["reliability_actions"]),
        "effect_facts": effect_facts(extracts["job_effects"], export_time, stale_after_ms),
        "idempotency_facts": idempotency_facts(extracts["idempotency_keys"]),
    }
