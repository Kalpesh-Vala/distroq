"""Aggregate reports over the fact tables.

Percentiles use Spark's exact ``percentile`` aggregate rather than
``percentile_approx``. The approximate form is cheaper and is the right choice at
scale, but it is defined by an error bound, and an error bound on a dataset of four
rows is not a number anyone should put in an operational report. Exact percentiles are
deterministic for any input size, which is what makes a rerun comparable.

Averages are rounded to three decimal places for the same reason: floating-point
addition is not associative, so an unrounded mean is only reproducible if the partition
order is, and partition order is not something this pipeline promises.

Nulls are not zeros. A group with no measurable durations reports a null percentile,
because "we did not observe this" and "we observed zero milliseconds" are different
facts and only one of them is true.
"""

from __future__ import annotations

from pyspark.sql import DataFrame
from pyspark.sql import functions as F


def percentile(column: str, quantile: float, alias: str):
    return F.expr(
        f"CAST(ROUND(percentile(`{column}`, {quantile})) AS BIGINT)"
    ).alias(alias)


def average(column: str, alias: str):
    return F.round(F.avg(F.col(column)), 3).alias(alias)


def count_true(column: str, alias: str):
    return F.sum(F.when(F.col(column), 1).otherwise(0)).cast("bigint").alias(alias)


def safe_ratio(numerator, denominator, scale: int = 6):
    """Ratio with an explicit null for a zero denominator.

    Spark already yields null for division by zero, but relying on that makes the
    intent invisible. A success rate over no submissions is undefined, not 0.0, and a
    dashboard that renders it as 0% is lying about a quiet day.
    """
    return F.when(
        (denominator.isNotNull()) & (denominator > 0),
        F.round(numerator / denominator, scale),
    ).otherwise(F.lit(None).cast("double"))


def daily_job_summary(job_facts: DataFrame) -> DataFrame:
    return (
        job_facts.groupBy("day", "priority")
        .agg(
            F.count(F.lit(1)).cast("bigint").alias("submitted_jobs"),
            F.sum(F.when(F.col("started_at").isNotNull(), 1).otherwise(0))
            .cast("bigint").alias("started_jobs"),
            count_true("is_succeeded", "succeeded_jobs"),
            count_true("is_legacy_failed", "failed_jobs"),
            count_true("is_dead_lettered", "dead_lettered_jobs"),
            count_true("is_scheduled", "scheduled_jobs"),
            count_true("is_retried", "retried_jobs"),
            F.sum("observed_abandoned_attempts").cast("bigint").alias("abandoned_attempts"),
            average("duration_ms", "average_duration_ms"),
            percentile("duration_ms", 0.50, "p50_duration_ms"),
            percentile("duration_ms", 0.95, "p95_duration_ms"),
            percentile("duration_ms", 0.99, "p99_duration_ms"),
            average("queue_delay_ms", "average_queue_delay_ms"),
            percentile("queue_delay_ms", 0.95, "p95_queue_delay_ms"),
            average("schedule_delay_ms", "average_schedule_delay_ms"),
        )
        .orderBy("day", "priority")
    )


def job_type_summary(job_facts: DataFrame, effect_facts: DataFrame) -> DataFrame:
    base = job_facts.groupBy("job_type").agg(
        F.count(F.lit(1)).cast("bigint").alias("submitted_jobs"),
        count_true("is_succeeded", "succeeded_jobs"),
        count_true("is_dead_lettered", "dead_lettered_jobs"),
        average("attempt_count", "average_attempts"),
        average("duration_ms", "average_duration_ms"),
        percentile("duration_ms", 0.95, "p95_duration_ms"),
    )

    dedup = (
        effect_facts.filter(F.col("job_type").isNotNull())
        .groupBy("job_type")
        .agg(F.sum("deduplication_hits").cast("bigint").alias("effect_deduplication_hits"))
    )

    return (
        base.join(dedup, on="job_type", how="left")
        .select(
            "job_type",
            "submitted_jobs",
            "succeeded_jobs",
            "dead_lettered_jobs",
            safe_ratio(F.col("succeeded_jobs"), F.col("submitted_jobs")).alias("success_rate"),
            "average_attempts",
            "average_duration_ms",
            "p95_duration_ms",
            F.coalesce(F.col("effect_deduplication_hits"), F.lit(0)).cast("bigint")
            .alias("effect_deduplication_hits"),
        )
        .orderBy("job_type")
    )


def priority_summary(job_facts: DataFrame) -> DataFrame:
    return (
        job_facts.groupBy("priority")
        .agg(
            F.count(F.lit(1)).cast("bigint").alias("submitted_jobs"),
            count_true("is_succeeded", "succeeded_jobs"),
            count_true("is_dead_lettered", "dead_lettered_jobs"),
            average("queue_delay_ms", "average_queue_delay_ms"),
            percentile("queue_delay_ms", 0.95, "p95_queue_delay_ms"),
            average("schedule_delay_ms", "average_schedule_delay_ms"),
            percentile("schedule_delay_ms", 0.95, "p95_schedule_delay_ms"),
        )
        .select(
            "priority",
            "submitted_jobs",
            "succeeded_jobs",
            "dead_lettered_jobs",
            safe_ratio(F.col("succeeded_jobs"), F.col("submitted_jobs")).alias("success_rate"),
            "average_queue_delay_ms",
            "p95_queue_delay_ms",
            "average_schedule_delay_ms",
            "p95_schedule_delay_ms",
        )
        .orderBy("priority")
    )


def outbox_summary(outbox_facts: DataFrame) -> DataFrame:
    return (
        outbox_facts.groupBy("day", "event_type")
        .agg(
            F.count(F.lit(1)).cast("bigint").alias("total_events"),
            count_true("is_published", "published_events"),
            # PENDING and PUBLISHING are both "not published and not terminal"; the
            # split is reported separately because a stuck PUBLISHING lease is an
            # operational signal that a PENDING backlog is not.
            F.sum(F.when(F.col("is_pending") | F.col("is_publishing"), 1).otherwise(0))
            .cast("bigint").alias("pending_events"),
            count_true("is_publishing", "publishing_events"),
            count_true("is_terminal_failed", "terminal_failed_events"),
            F.sum("operator_retry_count").cast("bigint").alias("operator_retries"),
            average("publication_latency_ms", "average_publication_latency_ms"),
            percentile("publication_latency_ms", 0.95, "p95_publication_latency_ms"),
            F.max("age_at_export_ms").cast("bigint").alias("oldest_unpublished_age_ms"),
        )
        .orderBy("day", "event_type")
    )


def reliability_summary(action_facts: DataFrame) -> DataFrame:
    return (
        action_facts.groupBy("day", "action_type")
        .agg(F.count(F.lit(1)).cast("bigint").alias("action_count"))
        .orderBy("day", "action_type")
    )


def effect_summary(effect_facts: DataFrame) -> DataFrame:
    return (
        effect_facts.groupBy("day", "effect_type")
        .agg(
            F.count(F.lit(1)).cast("bigint").alias("total_effects"),
            count_true("is_completed", "completed_effects"),
            count_true("is_failed", "failed_effects"),
            F.sum("deduplication_hits").cast("bigint").alias("deduplication_hits"),
            count_true("is_stale_candidate", "stale_candidates"),
        )
        .orderBy("day", "effect_type")
    )


AGGREGATE_DATASETS = (
    "daily_job_summary",
    "job_type_summary",
    "priority_summary",
    "outbox_summary",
    "reliability_summary",
    "effect_summary",
)


def build_all(facts: dict[str, DataFrame]) -> dict[str, DataFrame]:
    return {
        "daily_job_summary": daily_job_summary(facts["job_facts"]),
        "job_type_summary": job_type_summary(facts["job_facts"], facts["effect_facts"]),
        "priority_summary": priority_summary(facts["job_facts"]),
        "outbox_summary": outbox_summary(facts["outbox_facts"]),
        "reliability_summary": reliability_summary(facts["reliability_action_facts"]),
        "effect_summary": effect_summary(facts["effect_facts"]),
    }


def headline(facts: dict[str, DataFrame]) -> dict[str, object]:
    """A small, human-readable roll-up for the console and the summary JSON."""
    jobs = facts["job_facts"]
    attempts = facts["attempt_facts"]
    outbox = facts["outbox_facts"]
    effects = facts["effect_facts"]

    job_row = jobs.agg(
        F.count(F.lit(1)).alias("submitted"),
        F.sum(F.when(F.col("is_succeeded"), 1).otherwise(0)).alias("succeeded"),
        F.sum(F.when(F.col("is_dead_lettered"), 1).otherwise(0)).alias("dead_lettered"),
        F.sum(F.when(F.col("is_retried"), 1).otherwise(0)).alias("retried"),
        F.sum(F.when(F.col("is_scheduled"), 1).otherwise(0)).alias("scheduled"),
        F.round(F.avg("duration_ms"), 3).alias("avg_duration_ms"),
        F.expr("CAST(ROUND(percentile(duration_ms, 0.95)) AS BIGINT)").alias("p95_duration_ms"),
    ).collect()[0]

    attempt_row = attempts.agg(
        F.count(F.lit(1)).alias("attempts"),
        F.sum(F.when(F.col("is_abandoned"), 1).otherwise(0)).alias("abandoned"),
    ).collect()[0]

    outbox_row = outbox.agg(
        F.count(F.lit(1)).alias("events"),
        F.sum(F.when(F.col("is_published"), 1).otherwise(0)).alias("published"),
        F.sum(F.when(F.col("is_terminal_failed"), 1).otherwise(0)).alias("terminal_failed"),
        F.sum("operator_retry_count").alias("operator_retries"),
        F.max("age_at_export_ms").alias("oldest_unpublished_age_ms"),
    ).collect()[0]

    effect_row = effects.agg(
        F.count(F.lit(1)).alias("effects"),
        F.sum("deduplication_hits").alias("deduplication_hits"),
        F.sum(F.when(F.col("is_stale_candidate"), 1).otherwise(0)).alias("stale_candidates"),
    ).collect()[0]

    submitted = int(job_row["submitted"] or 0)
    succeeded = int(job_row["succeeded"] or 0)
    return {
        "submitted_jobs": submitted,
        "succeeded_jobs": succeeded,
        "dead_lettered_jobs": int(job_row["dead_lettered"] or 0),
        "retried_jobs": int(job_row["retried"] or 0),
        "scheduled_jobs": int(job_row["scheduled"] or 0),
        "success_rate": round(succeeded / submitted, 6) if submitted else None,
        "average_duration_ms": job_row["avg_duration_ms"],
        "p95_duration_ms": job_row["p95_duration_ms"],
        "attempts": int(attempt_row["attempts"] or 0),
        "abandoned_attempts": int(attempt_row["abandoned"] or 0),
        "outbox_events": int(outbox_row["events"] or 0),
        "outbox_published": int(outbox_row["published"] or 0),
        "outbox_terminal_failed": int(outbox_row["terminal_failed"] or 0),
        "outbox_operator_retries": int(outbox_row["operator_retries"] or 0),
        "oldest_unpublished_age_ms": outbox_row["oldest_unpublished_age_ms"],
        "effects": int(effect_row["effects"] or 0),
        "effect_deduplication_hits": int(effect_row["deduplication_hits"] or 0),
        "effect_stale_candidates": int(effect_row["stale_candidates"] or 0),
    }
