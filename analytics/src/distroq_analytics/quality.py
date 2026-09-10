"""Data-quality checks over the fact tables.

This module reports and never repairs. Reconciliation already exists in the
application and is the only thing allowed to change application state; an analytics
job that "fixed" a row would be a second, unaudited writer competing with it. Every
check here is a ``SELECT``-shaped predicate over Parquet that was already extracted.

Two structural notes:

* Every check emits a row even when it finds nothing. A report where a check is absent
  is indistinguishable from a report where the check did not run, and "zero findings"
  is a result worth recording.
* Sample IDs are bounded and sorted. Bounded so a systemic failure does not produce a
  megabyte of IDs; sorted so that a rerun over the same window produces byte-identical
  samples rather than whichever rows a partition happened to yield first.

Some checks are marked ``WARNING`` purely because they are sensitive to the window
edge: a job created just before ``end`` may have its attempts, or its retry event, in
the *next* window. That is a property of half-open windows, not a defect in the data,
so those checks do not escalate to ``ERROR``.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Callable

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from .schemas import DATA_QUALITY_SCHEMA
from .transform import TERMINAL_STATUSES

SAMPLE_LIMIT = 10

SEVERITY_ERROR = "ERROR"
SEVERITY_WARNING = "WARNING"
SEVERITY_INFO = "INFO"
SEVERITY_ORDER = {SEVERITY_INFO: 0, SEVERITY_WARNING: 1, SEVERITY_ERROR: 2}


@dataclass(frozen=True)
class Check:
    name: str
    severity: str
    description: str
    offenders: Callable[[dict[str, DataFrame], datetime], DataFrame]


@dataclass(frozen=True)
class Finding:
    check_name: str
    severity: str
    count: int
    sample_ids: list[str]
    description: str

    def as_row(self) -> tuple:
        return (self.check_name, self.severity, self.count, ",".join(self.sample_ids),
                self.description)

    def as_dict(self) -> dict:
        return {
            "check_name": self.check_name,
            "severity": self.severity,
            "count": self.count,
            "sample_ids": self.sample_ids,
            "description": self.description,
        }


def _ids(df: DataFrame, column: str) -> DataFrame:
    return df.select(F.col(column).cast("string").alias("id"))


def _job_negative_duration(facts, _now):
    return _ids(facts["job_facts"].filter(F.col("duration_ms") < 0), "job_id")


def _attempt_negative_duration(facts, _now):
    return _ids(facts["attempt_facts"].filter(F.col("duration_ms") < 0), "attempt_id")


def _finished_without_finished_at(facts, _now):
    return _ids(
        facts["job_facts"].filter(
            F.col("status").isin(*TERMINAL_STATUSES) & F.col("finished_at").isNull()
        ),
        "job_id",
    )


def _succeeded_without_success_attempt(facts, _now):
    return _ids(
        facts["job_facts"].filter(
            F.col("is_succeeded") & (F.col("observed_success_attempts") == 0)
        ),
        "job_id",
    )


def _dead_lettered_without_dlq_row(facts, _now):
    return _ids(
        facts["job_facts"].filter(F.col("is_dead_lettered") & F.col("dead_lettered_at").isNull()),
        "job_id",
    )


def _dlq_without_job(facts, _now):
    return _ids(facts["dead_letter_facts"].filter(~F.col("has_job")), "job_id")


def _attempt_count_mismatch(facts, _now):
    return _ids(
        facts["job_facts"].filter(F.col("attempt_count") != F.col("observed_attempt_rows")),
        "job_id",
    )


def _multiple_in_progress_attempts(facts, _now):
    return _ids(facts["job_facts"].filter(F.col("observed_in_progress_attempts") > 1), "job_id")


def _expired_execution_lease(facts, now):
    return _ids(
        facts["job_facts"].filter(
            F.col("execution_lease_until").isNotNull()
            & (F.col("execution_lease_until") < F.lit(now).cast("timestamp"))
        ),
        "job_id",
    )


def _missing_event(facts, job_predicate, event_type: str) -> DataFrame:
    events = (
        facts["outbox_facts"]
        .filter(F.col("event_type") == event_type)
        .select(F.col("aggregate_id").alias("job_id"))
        .distinct()
    )
    return _ids(
        facts["job_facts"]
        .filter(job_predicate)
        .join(events, on="job_id", how="left_anti"),
        "job_id",
    )


def _scheduled_missing_schedule_event(facts, _now):
    return _missing_event(facts, F.col("is_scheduled"), "SCHEDULE_USER_JOB")


def _retrying_missing_retry_event(facts, _now):
    return _missing_event(facts, F.col("status") == "RETRYING", "SCHEDULE_RETRY")


def _published_missing_published_at(facts, _now):
    return _ids(
        facts["outbox_facts"].filter(F.col("is_published") & F.col("published_at").isNull()),
        "event_id",
    )


def _published_impossible_timestamps(facts, _now):
    return _ids(
        facts["outbox_facts"].filter(
            F.col("published_at").isNotNull()
            & (F.col("published_at") < F.col("created_at"))
        ),
        "event_id",
    )


def _effect_invalid_status(facts, _now):
    return _ids(facts["effect_facts"].filter(~F.col("has_valid_status")), "effect_key")


def _duplicate_effect_keys(facts, _now):
    return (
        facts["effect_facts"]
        .groupBy("effect_key")
        .count()
        .filter(F.col("count") > 1)
        .select(F.col("effect_key").cast("string").alias("id"))
    )


def _duplicate_idempotency_hashes(facts, _now):
    return (
        facts["idempotency_facts"]
        .groupBy("request_hash")
        .agg(F.countDistinct("job_id").alias("jobs"))
        .filter(F.col("jobs") > 1)
        .select(F.col("request_hash").cast("string").alias("id"))
    )


def _job_invalid_status(facts, _now):
    return _ids(facts["job_facts"].filter(~F.col("has_valid_status")), "job_id")


def _attempt_invalid_outcome(facts, _now):
    return _ids(facts["attempt_facts"].filter(~F.col("has_valid_outcome")), "attempt_id")


def _outbox_invalid_status(facts, _now):
    return _ids(facts["outbox_facts"].filter(~F.col("has_valid_status")), "event_id")


CHECKS: tuple[Check, ...] = (
    Check("jobs_negative_duration", SEVERITY_ERROR,
          "Job finished_at is earlier than started_at. Reported, never corrected: a negative "
          "duration is evidence of a clock or ordering problem and clamping it to zero would "
          "hide it.",
          _job_negative_duration),
    Check("attempts_negative_duration", SEVERITY_ERROR,
          "Attempt finished_at is earlier than started_at.",
          _attempt_negative_duration),
    Check("finished_jobs_without_finished_at", SEVERITY_ERROR,
          "Job is in a terminal status (SUCCEEDED, FAILED, DEAD_LETTERED) but has no finished_at.",
          _finished_without_finished_at),
    Check("succeeded_jobs_without_successful_attempt", SEVERITY_WARNING,
          "Job is SUCCEEDED but no attempt row in this window has outcome SUCCESS. Sensitive to "
          "the window edge: the successful attempt may fall in the next window.",
          _succeeded_without_success_attempt),
    Check("dead_lettered_jobs_without_dlq_row", SEVERITY_ERROR,
          "Job status is DEAD_LETTERED but no dead_letters row exists for it.",
          _dead_lettered_without_dlq_row),
    Check("dlq_rows_without_job", SEVERITY_ERROR,
          "A dead_letters row references a job that does not exist.",
          _dlq_without_job),
    Check("job_attempt_count_mismatch", SEVERITY_WARNING,
          "jobs.attempt_count disagrees with the number of job_attempts rows observed in this "
          "window. Sensitive to the window edge for jobs created near its end.",
          _attempt_count_mismatch),
    Check("multiple_in_progress_attempts", SEVERITY_ERROR,
          "More than one IN_PROGRESS attempt for the same job: two workers believe they own it.",
          _multiple_in_progress_attempts),
    Check("jobs_with_expired_execution_lease", SEVERITY_WARNING,
          "Job still carries an execution lease whose deadline passed before the export ran.",
          _expired_execution_lease),
    Check("scheduled_jobs_missing_schedule_event", SEVERITY_WARNING,
          "A job with scheduled_at set has no SCHEDULE_USER_JOB outbox event in this window. "
          "This is the durable-intent gap reconciliation exists to find.",
          _scheduled_missing_schedule_event),
    Check("retry_jobs_missing_retry_event", SEVERITY_WARNING,
          "A RETRYING job has no SCHEDULE_RETRY outbox event in this window.",
          _retrying_missing_retry_event),
    Check("published_outbox_missing_published_at", SEVERITY_ERROR,
          "Outbox event status is PUBLISHED but published_at is null.",
          _published_missing_published_at),
    Check("published_outbox_impossible_timestamps", SEVERITY_ERROR,
          "Outbox event published_at precedes created_at.",
          _published_impossible_timestamps),
    Check("effects_invalid_status", SEVERITY_ERROR,
          "job_effects.status is not one of STARTED, COMPLETED, FAILED. The schema carries no "
          "CHECK constraint over this enum by design, so analytics validates it instead.",
          _effect_invalid_status),
    Check("duplicate_effect_keys", SEVERITY_ERROR,
          "The same effect_key appears more than once. effect_key is the primary key, so a "
          "duplicate here means the extract itself is wrong.",
          _duplicate_effect_keys),
    Check("duplicate_idempotency_hashes_for_different_jobs", SEVERITY_WARNING,
          "The same request_hash maps to more than one job. Expected only when a client reused "
          "an identical body under different idempotency keys.",
          _duplicate_idempotency_hashes),
    Check("jobs_invalid_status", SEVERITY_ERROR,
          "jobs.status is not a member of JobStatus.",
          _job_invalid_status),
    Check("attempts_invalid_outcome", SEVERITY_ERROR,
          "job_attempts.outcome is not a member of AttemptOutcome.",
          _attempt_invalid_outcome),
    Check("outbox_invalid_status", SEVERITY_ERROR,
          "outbox_events.status is not a member of OutboxStatus.",
          _outbox_invalid_status),
)


def run_checks(facts: dict[str, DataFrame], export_time: datetime,
               sample_limit: int = SAMPLE_LIMIT) -> list[Finding]:
    findings: list[Finding] = []
    for check in CHECKS:
        offenders = check.offenders(facts, export_time).filter(F.col("id").isNotNull())
        count = offenders.count()
        samples = (
            [row["id"] for row in offenders.orderBy("id").limit(sample_limit).collect()]
            if count
            else []
        )
        findings.append(Finding(check.name, check.severity, count, samples, check.description))
    return findings


def findings_frame(spark: SparkSession, findings: list[Finding]) -> DataFrame:
    rows = [finding.as_row() for finding in findings]
    return spark.createDataFrame(rows, DATA_QUALITY_SCHEMA).orderBy("check_name")


def worst_severity(findings: list[Finding]) -> str | None:
    hits = [f.severity for f in findings if f.count > 0]
    if not hits:
        return None
    return max(hits, key=lambda severity: SEVERITY_ORDER[severity])


def total_findings(findings: list[Finding]) -> int:
    return sum(finding.count for finding in findings)


def is_fatal(findings: list[Finding], threshold: str) -> bool:
    """Whether any finding reaches the severity the operator declared fatal."""
    if threshold in (None, "none"):
        return False
    floor = SEVERITY_ORDER[threshold.upper()]
    return any(
        finding.count > 0 and SEVERITY_ORDER[finding.severity] >= floor for finding in findings
    )
