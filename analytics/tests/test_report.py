from __future__ import annotations

import datetime as dt

from pyspark.sql import functions as F

from distroq_analytics import report, transform
from fixtures import all_sources, ts

EXPORT_TIME = ts("2026-09-10T00:00:00Z")
STALE_MS = 300_000


def aggregates_for(spark, **sources):
    facts = transform.build_all(all_sources(spark, **sources), EXPORT_TIME, STALE_MS)
    return facts, report.build_all(facts)


def rows(df):
    return [row.asDict() for row in df.collect()]


def job(job_id, **overrides):
    base = {
        "job_id": job_id,
        "created_at": ts("2026-09-01T10:00:00Z"),
        "started_at": ts("2026-09-01T10:00:00Z"),
        "finished_at": ts("2026-09-01T10:00:01Z"),
    }
    base.update(overrides)
    return base


class TestDailyJobSummary:
    def test_groups_by_utc_day_and_priority(self, spark):
        _, agg = aggregates_for(spark, jobs=[
            job("a", created_at=ts("2026-09-01T23:59:59Z"), priority="HIGH"),
            job("b", created_at=ts("2026-09-02T00:00:00Z"), priority="HIGH"),
            job("c", created_at=ts("2026-09-02T05:00:00Z"), priority="LOW"),
        ])
        result = [(str(r["day"]), r["priority"], r["submitted_jobs"])
                  for r in rows(agg["daily_job_summary"])]
        assert result == [
            ("2026-09-01", "HIGH", 1),
            ("2026-09-02", "HIGH", 1),
            ("2026-09-02", "LOW", 1),
        ]

    def test_counts_each_outcome_separately(self, spark):
        _, agg = aggregates_for(spark, jobs=[
            job("ok", status="SUCCEEDED"),
            job("dlq", status="DEAD_LETTERED", attempt_count=3),
            job("legacy", status="FAILED"),
            job("sched", status="SUCCEEDED", scheduled_at=ts("2026-09-01T09:00:00Z")),
        ])
        row = rows(agg["daily_job_summary"])[0]
        assert row["submitted_jobs"] == 4
        assert row["succeeded_jobs"] == 2
        assert row["dead_lettered_jobs"] == 1
        assert row["failed_jobs"] == 1
        assert row["scheduled_jobs"] == 1
        assert row["retried_jobs"] == 1

    def test_a_job_created_before_the_window_is_not_a_submission(self, spark):
        # The extract is windowed on jobs.created_at, so a job that merely finished in
        # the window never reaches the fact table at all.
        _, agg = aggregates_for(spark, jobs=[job("in-window")])
        assert rows(agg["daily_job_summary"])[0]["submitted_jobs"] == 1

    def test_retry_attempts_are_not_counted_as_submissions(self, spark):
        facts, agg = aggregates_for(
            spark,
            jobs=[job("j1", attempt_count=3, status="SUCCEEDED")],
            job_attempts=[
                {"attempt_id": f"a{n}", "job_id": "j1", "attempt_number": n,
                 "outcome": "FAILURE" if n < 3 else "SUCCESS",
                 "started_at": ts("2026-09-01T10:00:00Z"),
                 "finished_at": ts("2026-09-01T10:00:01Z")}
                for n in (1, 2, 3)
            ],
        )
        assert facts["attempt_facts"].count() == 3
        assert rows(agg["daily_job_summary"])[0]["submitted_jobs"] == 1

    def test_abandoned_attempts_are_counted_separately(self, spark):
        _, agg = aggregates_for(
            spark,
            jobs=[job("j1", attempt_count=2)],
            job_attempts=[
                {"attempt_id": "a1", "job_id": "j1", "attempt_number": 1,
                 "outcome": "ABANDONED", "started_at": ts("2026-09-01T10:00:00Z"),
                 "finished_at": ts("2026-09-01T10:00:05Z")},
                {"attempt_id": "a2", "job_id": "j1", "attempt_number": 2,
                 "outcome": "SUCCESS", "started_at": ts("2026-09-01T10:00:06Z"),
                 "finished_at": ts("2026-09-01T10:00:07Z")},
            ],
        )
        row = rows(agg["daily_job_summary"])[0]
        assert row["abandoned_attempts"] == 1
        assert row["submitted_jobs"] == 1


class TestPercentiles:
    def _durations(self, spark, millis):
        jobs = [
            job(f"j{i}",
                finished_at=ts("2026-09-01T10:00:00Z") + dt.timedelta(milliseconds=ms))
            for i, ms in enumerate(millis)
        ]
        _, agg = aggregates_for(spark, jobs=jobs)
        return rows(agg["daily_job_summary"])[0]

    def test_percentiles_are_exact_for_a_known_set(self, spark):
        row = self._durations(spark, [100, 200, 300, 400, 500])
        assert row["p50_duration_ms"] == 300
        assert row["average_duration_ms"] == 300.0

    def test_single_value_yields_that_value_at_every_quantile(self, spark):
        row = self._durations(spark, [1234])
        assert row["p50_duration_ms"] == 1234
        assert row["p95_duration_ms"] == 1234
        assert row["p99_duration_ms"] == 1234

    def test_two_values_do_not_produce_impossible_percentiles(self, spark):
        row = self._durations(spark, [100, 200])
        for key in ("p50_duration_ms", "p95_duration_ms", "p99_duration_ms"):
            assert 100 <= row[key] <= 200

    def test_percentiles_are_deterministic_across_repeats(self, spark):
        first = self._durations(spark, [10, 20, 30, 40, 50, 60, 70])
        second = self._durations(spark, [70, 60, 50, 40, 30, 20, 10])
        for key in ("p50_duration_ms", "p95_duration_ms", "p99_duration_ms"):
            assert first[key] == second[key]

    def test_no_measurable_durations_yields_null_not_zero(self, spark):
        _, agg = aggregates_for(spark, jobs=[
            job("j1", status="QUEUED", started_at=None, finished_at=None)
        ])
        row = rows(agg["daily_job_summary"])[0]
        assert row["p50_duration_ms"] is None
        assert row["average_duration_ms"] is None

    def test_negative_durations_are_not_silently_corrected(self, spark):
        row = self._durations(spark, [-500, 500])
        assert row["p50_duration_ms"] == 0
        assert row["average_duration_ms"] == 0.0


class TestSuccessRate:
    def test_success_rate_is_a_ratio(self, spark):
        _, agg = aggregates_for(spark, jobs=[
            job("a", status="SUCCEEDED"),
            job("b", status="SUCCEEDED"),
            job("c", status="DEAD_LETTERED"),
            job("d", status="DEAD_LETTERED"),
        ])
        assert rows(agg["job_type_summary"])[0]["success_rate"] == 0.5

    def test_zero_denominator_yields_null(self, spark):
        value = spark.range(1).select(
            report.safe_ratio(F.lit(3), F.lit(0)).alias("r")
        )
        assert value.collect()[0]["r"] is None

    def test_null_denominator_yields_null(self, spark):
        value = spark.range(1).select(
            report.safe_ratio(F.lit(3), F.lit(None).cast("long")).alias("r")
        )
        assert value.collect()[0]["r"] is None


class TestJobTypeSummary:
    def test_groups_by_job_type_and_carries_dedup_hits(self, spark):
        _, agg = aggregates_for(
            spark,
            jobs=[
                job("j1", job_type="idempotent_counter", attempt_count=3),
                job("j2", job_type="email"),
            ],
            job_effects=[{
                "effect_key": "j1:counter:orders", "job_id": "j1", "attempt_number": 1,
                "job_attempt_count": 3, "status": "COMPLETED",
                "created_at": ts("2026-09-01T10:00:00Z"),
                "completed_at": ts("2026-09-01T10:00:01Z"),
                "job_type": "idempotent_counter",
            }],
        )
        by_type = {r["job_type"]: r for r in rows(agg["job_type_summary"])}
        assert by_type["idempotent_counter"]["effect_deduplication_hits"] == 2
        assert by_type["email"]["effect_deduplication_hits"] == 0
        assert by_type["idempotent_counter"]["average_attempts"] == 3.0


class TestPrioritySummary:
    def test_queueing_delay_is_reported_per_priority(self, spark):
        _, agg = aggregates_for(spark, jobs=[
            job("h", priority="HIGH",
                started_at=ts("2026-09-01T10:00:00Z") + dt.timedelta(milliseconds=10)),
            job("l", priority="LOW",
                started_at=ts("2026-09-01T10:00:00Z") + dt.timedelta(milliseconds=900)),
        ])
        by_priority = {r["priority"]: r for r in rows(agg["priority_summary"])}
        assert by_priority["HIGH"]["average_queue_delay_ms"] == 10.0
        assert by_priority["LOW"]["average_queue_delay_ms"] == 900.0

    def test_schedule_delay_is_null_for_immediate_only_priorities(self, spark):
        _, agg = aggregates_for(spark, jobs=[job("h", priority="HIGH")])
        assert rows(agg["priority_summary"])[0]["average_schedule_delay_ms"] is None


class TestOutboxSummary:
    def test_states_are_distinguished(self, spark):
        base = {"aggregate_type": "Job", "event_type": "ENQUEUE_SUBMIT",
                "created_at": ts("2026-09-01T10:00:00Z"),
                "available_at": ts("2026-09-01T10:00:00Z")}
        _, agg = aggregates_for(spark, outbox_events=[
            {**base, "event_id": "p", "status": "PUBLISHED",
             "published_at": ts("2026-09-01T10:00:00.100Z")},
            {**base, "event_id": "q", "status": "PENDING"},
            {**base, "event_id": "r", "status": "PUBLISHING"},
            {**base, "event_id": "s", "status": "FAILED", "attempt_count": 100,
             "terminal_failed_at": ts("2026-09-01T10:05:00Z"), "operator_retry_count": 1},
        ])
        row = rows(agg["outbox_summary"])[0]
        assert row["total_events"] == 4
        assert row["published_events"] == 1
        assert row["pending_events"] == 2
        assert row["publishing_events"] == 1
        assert row["terminal_failed_events"] == 1
        assert row["operator_retries"] == 1
        assert row["average_publication_latency_ms"] == 100.0

    def test_oldest_unpublished_age_is_relative_to_export_time(self, spark):
        _, agg = aggregates_for(spark, outbox_events=[{
            "event_id": "q", "status": "PENDING",
            "created_at": ts("2026-09-09T12:00:00Z"),
            "available_at": ts("2026-09-09T12:00:00Z"),
        }])
        assert rows(agg["outbox_summary"])[0]["oldest_unpublished_age_ms"] == 43_200_000


class TestReliabilityAndEffectSummaries:
    def test_reliability_actions_group_by_day_and_type(self, spark):
        _, agg = aggregates_for(spark, reliability_actions=[
            {"action_id": "r1", "action_type": "OUTBOX_RETRY",
             "created_at": ts("2026-09-01T10:00:00Z")},
            {"action_id": "r2", "action_type": "OUTBOX_RETRY",
             "created_at": ts("2026-09-01T11:00:00Z")},
            {"action_id": "r3", "action_type": "LEASE_REPAIR",
             "created_at": ts("2026-09-02T11:00:00Z")},
        ])
        assert [(str(r["day"]), r["action_type"], r["action_count"])
                for r in rows(agg["reliability_summary"])] == [
            ("2026-09-01", "OUTBOX_RETRY", 2),
            ("2026-09-02", "LEASE_REPAIR", 1),
        ]

    def test_effect_summary_counts_states_and_hits(self, spark):
        _, agg = aggregates_for(spark, job_effects=[
            {"effect_key": "k1", "job_id": "j1", "status": "COMPLETED",
             "attempt_number": 1, "job_attempt_count": 2,
             "created_at": ts("2026-09-01T10:00:00Z"),
             "completed_at": ts("2026-09-01T10:00:01Z")},
            {"effect_key": "k2", "job_id": "j2", "status": "FAILED",
             "created_at": ts("2026-09-01T10:00:00Z")},
            {"effect_key": "k3", "job_id": "j3", "status": "STARTED",
             "created_at": ts("2026-09-01T10:00:00Z")},
        ])
        row = rows(agg["effect_summary"])[0]
        assert row["total_effects"] == 3
        assert row["completed_effects"] == 1
        assert row["failed_effects"] == 1
        assert row["deduplication_hits"] == 1
        assert row["stale_candidates"] == 1


class TestEmptyAggregates:
    def test_all_aggregates_are_valid_and_empty(self, spark):
        _, agg = aggregates_for(spark)
        for name, frame_ in agg.items():
            assert frame_.count() == 0, name
            assert frame_.columns, name

    def test_headline_over_empty_input_is_well_formed(self, spark):
        facts, _ = aggregates_for(spark)
        head = report.headline(facts)
        assert head["submitted_jobs"] == 0
        assert head["success_rate"] is None
