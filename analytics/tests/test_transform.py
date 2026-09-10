from __future__ import annotations

import pytest

from distroq_analytics import transform
from fixtures import all_sources, as_read, frame, ts

EXPORT_TIME = ts("2026-09-10T00:00:00Z")
STALE_MS = 300_000


def facts_for(spark, **sources):
    return transform.build_all(all_sources(spark, **sources), EXPORT_TIME, STALE_MS)


def one(df):
    rows = df.collect()
    assert len(rows) == 1, f"expected exactly one row, got {len(rows)}"
    return rows[0]


class TestJobDurations:
    def test_duration_is_finished_minus_started(self, spark):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:01Z"),
            "finished_at": ts("2026-09-01T10:00:03.500Z"),
        }])["job_facts"])
        assert row["duration_ms"] == 2500

    def test_duration_is_null_when_finished_at_is_missing(self, spark):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "status": "RUNNING", "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:01Z"), "finished_at": None,
        }])["job_facts"])
        assert row["duration_ms"] is None

    def test_duration_is_null_when_started_at_is_missing(self, spark):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "status": "QUEUED", "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": None, "finished_at": None,
        }])["job_facts"])
        assert row["duration_ms"] is None

    def test_negative_duration_is_preserved_not_clamped(self, spark):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:05Z"),
            "finished_at": ts("2026-09-01T10:00:03Z"),
        }])["job_facts"])
        assert row["duration_ms"] == -2000


class TestDelays:
    def test_queue_delay_applies_to_immediate_jobs(self, spark):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "created_at": ts("2026-09-01T10:00:00Z"),
            "scheduled_at": None,
            "started_at": ts("2026-09-01T10:00:00.250Z"),
            "finished_at": ts("2026-09-01T10:00:01Z"),
        }])["job_facts"])
        assert row["queue_delay_ms"] == 250
        assert row["schedule_delay_ms"] is None
        assert row["is_scheduled"] is False

    def test_schedule_delay_applies_only_to_scheduled_jobs(self, spark):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "created_at": ts("2026-09-01T10:00:00Z"),
            "scheduled_at": ts("2026-09-01T12:00:00Z"),
            "started_at": ts("2026-09-01T12:00:00.400Z"),
            "finished_at": ts("2026-09-01T12:00:01Z"),
        }])["job_facts"])
        assert row["schedule_delay_ms"] == 400
        assert row["queue_delay_ms"] is None
        assert row["is_scheduled"] is True

    def test_delays_are_null_when_the_job_never_started(self, spark):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "status": "SCHEDULED",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "scheduled_at": ts("2026-09-05T10:00:00Z"),
            "started_at": None, "finished_at": None,
        }])["job_facts"])
        assert row["schedule_delay_ms"] is None
        assert row["queue_delay_ms"] is None

    def test_first_queue_delay_uses_the_first_attempt_not_the_last(self, spark):
        facts = facts_for(
            spark,
            jobs=[{
                "job_id": "j1", "attempt_count": 2,
                "created_at": ts("2026-09-01T10:00:00Z"),
                "started_at": ts("2026-09-01T10:00:30Z"),
                "finished_at": ts("2026-09-01T10:00:31Z"),
            }],
            job_attempts=[
                {"attempt_id": "a1", "job_id": "j1", "attempt_number": 1,
                 "outcome": "FAILURE", "started_at": ts("2026-09-01T10:00:01Z"),
                 "finished_at": ts("2026-09-01T10:00:02Z")},
                {"attempt_id": "a2", "job_id": "j1", "attempt_number": 2,
                 "outcome": "SUCCESS", "started_at": ts("2026-09-01T10:00:30Z"),
                 "finished_at": ts("2026-09-01T10:00:31Z")},
            ],
        )
        row = one(facts["job_facts"])
        assert row["queue_delay_ms"] == 30_000  # jobs.started_at is the last attempt
        assert row["first_queue_delay_ms"] == 1_000


class TestJobFlags:
    @pytest.mark.parametrize(
        "status,expected",
        [
            ("SUCCEEDED", {"is_succeeded": True, "is_dead_lettered": False,
                           "is_legacy_failed": False}),
            ("DEAD_LETTERED", {"is_succeeded": False, "is_dead_lettered": True,
                               "is_legacy_failed": False}),
            ("FAILED", {"is_succeeded": False, "is_dead_lettered": False,
                        "is_legacy_failed": True}),
            ("RETRYING", {"is_succeeded": False, "is_dead_lettered": False,
                          "is_legacy_failed": False}),
        ],
    )
    def test_status_flags(self, spark, status, expected):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "status": status, "created_at": ts("2026-09-01T10:00:00Z"),
        }])["job_facts"])
        for key, value in expected.items():
            assert row[key] is value

    def test_replay_count_defaults_to_zero_without_a_dlq_row(self, spark):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "created_at": ts("2026-09-01T10:00:00Z"),
        }])["job_facts"])
        assert row["replay_count"] == 0
        assert row["is_replayed"] is False

    def test_replay_metadata_comes_from_the_dlq_row(self, spark):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "status": "SUCCEEDED", "created_at": ts("2026-09-01T10:00:00Z"),
            "scheduled_at": ts("2026-09-01T11:00:00Z"),
            "replay_count": 2, "replayed": True,
            "dead_lettered_at": ts("2026-09-01T10:30:00Z"),
        }])["job_facts"])
        assert row["replay_count"] == 2
        assert row["is_replayed"] is True
        # A replayed job keeps the scheduling metadata it was submitted with.
        assert row["scheduled_at"] == as_read("2026-09-01T11:00:00Z")
        assert row["is_scheduled"] is True

    def test_unknown_status_is_flagged_rather_than_dropped(self, spark):
        row = one(facts_for(spark, jobs=[{
            "job_id": "j1", "status": "BANANA", "created_at": ts("2026-09-01T10:00:00Z"),
        }])["job_facts"])
        assert row["has_valid_status"] is False


class TestAttemptFacts:
    @pytest.mark.parametrize(
        "outcome,flags",
        [
            ("SUCCESS", (True, False, False, False)),
            ("FAILURE", (False, True, False, False)),
            ("ABANDONED", (False, False, True, False)),
            ("IN_PROGRESS", (False, False, False, True)),
        ],
    )
    def test_outcome_flags(self, spark, outcome, flags):
        row = one(facts_for(spark, job_attempts=[{
            "attempt_id": "a1", "job_id": "j1", "outcome": outcome,
            "started_at": ts("2026-09-01T10:00:00Z"),
            "finished_at": ts("2026-09-01T10:00:01Z"),
        }])["attempt_facts"])
        assert (row["is_success"], row["is_failure"], row["is_abandoned"],
                row["is_in_progress"]) == flags

    def test_in_progress_attempt_has_no_duration(self, spark):
        row = one(facts_for(spark, job_attempts=[{
            "attempt_id": "a1", "job_id": "j1", "outcome": "IN_PROGRESS",
            "started_at": ts("2026-09-01T10:00:00Z"), "finished_at": None,
        }])["attempt_facts"])
        assert row["duration_ms"] is None

    def test_duration_is_milliseconds(self, spark):
        row = one(facts_for(spark, job_attempts=[{
            "attempt_id": "a1", "job_id": "j1",
            "started_at": ts("2026-09-01T10:00:00Z"),
            "finished_at": ts("2026-09-01T10:00:00.750Z"),
        }])["attempt_facts"])
        assert row["duration_ms"] == 750

    def test_payload_is_not_exposed(self, spark):
        columns = facts_for(spark)["attempt_facts"].columns
        assert "payload" not in columns


class TestOutboxFacts:
    def test_publication_latency_for_a_published_event(self, spark):
        row = one(facts_for(spark, outbox_events=[{
            "event_id": "e1", "status": "PUBLISHED",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "available_at": ts("2026-09-01T10:00:00Z"),
            "published_at": ts("2026-09-01T10:00:00.120Z"),
        }])["outbox_facts"])
        assert row["publication_latency_ms"] == 120
        assert row["is_published"] is True
        assert row["age_at_export_ms"] is None

    def test_unpublished_event_has_null_latency_and_an_age(self, spark):
        row = one(facts_for(spark, outbox_events=[{
            "event_id": "e1", "status": "PENDING",
            "created_at": ts("2026-09-09T23:00:00Z"),
            "available_at": ts("2026-09-09T23:00:00Z"),
            "published_at": None,
        }])["outbox_facts"])
        assert row["publication_latency_ms"] is None
        assert row["age_at_export_ms"] == 3_600_000
        assert row["is_published"] is False

    def test_terminal_failure_is_not_counted_as_published(self, spark):
        row = one(facts_for(spark, outbox_events=[{
            "event_id": "e1", "status": "FAILED",
            "created_at": ts("2026-09-09T22:00:00Z"),
            "available_at": ts("2026-09-09T22:00:00Z"),
            "published_at": None,
            "terminal_failed_at": ts("2026-09-09T22:05:00Z"),
            "attempt_count": 100,
        }])["outbox_facts"])
        assert row["is_terminal_failed"] is True
        assert row["is_published"] is False
        assert row["publication_latency_ms"] is None

    def test_operator_retry_is_visible(self, spark):
        row = one(facts_for(spark, outbox_events=[{
            "event_id": "e1", "status": "PENDING",
            "created_at": ts("2026-09-09T22:00:00Z"),
            "available_at": ts("2026-09-09T22:00:00Z"),
            "operator_retry_count": 2,
            "last_operator_retry_at": ts("2026-09-09T23:30:00Z"),
        }])["outbox_facts"])
        assert row["operator_retry_count"] == 2
        assert row["has_operator_retry"] is True


class TestEffectFacts:
    def test_completed_effect_duration(self, spark):
        row = one(facts_for(spark, job_effects=[{
            "effect_key": "j1:counter:orders", "job_id": "j1",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "completed_at": ts("2026-09-01T10:00:00.050Z"),
        }])["effect_facts"])
        assert row["duration_ms"] == 50
        assert row["is_completed"] is True

    def test_started_effect_older_than_threshold_is_a_stale_candidate(self, spark):
        row = one(facts_for(spark, job_effects=[{
            "effect_key": "j1:counter:orders", "job_id": "j1", "status": "STARTED",
            "created_at": ts("2026-09-09T23:50:00Z"), "completed_at": None,
        }])["effect_facts"])
        assert row["age_at_export_ms"] == 600_000
        assert row["is_stale_candidate"] is True

    def test_recent_started_effect_is_not_stale(self, spark):
        row = one(facts_for(spark, job_effects=[{
            "effect_key": "j1:counter:orders", "job_id": "j1", "status": "STARTED",
            "created_at": ts("2026-09-09T23:59:00Z"), "completed_at": None,
        }])["effect_facts"])
        assert row["age_at_export_ms"] == 60_000
        assert row["is_stale_candidate"] is False

    def test_completed_effect_is_never_a_stale_candidate(self, spark):
        row = one(facts_for(spark, job_effects=[{
            "effect_key": "j1:counter:orders", "job_id": "j1", "status": "COMPLETED",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "completed_at": ts("2026-09-01T10:00:01Z"),
        }])["effect_facts"])
        assert row["is_stale_candidate"] is False

    def test_deduplication_hits_are_attempts_after_the_claiming_one(self, spark):
        row = one(facts_for(spark, job_effects=[{
            "effect_key": "j1:counter:orders", "job_id": "j1", "attempt_number": 1,
            "job_attempt_count": 3, "status": "COMPLETED",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "completed_at": ts("2026-09-01T10:00:01Z"),
        }])["effect_facts"])
        assert row["deduplication_hits"] == 2

    def test_single_attempt_effect_has_no_deduplication_hits(self, spark):
        row = one(facts_for(spark, job_effects=[{
            "effect_key": "j1:counter:orders", "job_id": "j1", "attempt_number": 1,
            "job_attempt_count": 1, "status": "COMPLETED",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "completed_at": ts("2026-09-01T10:00:01Z"),
        }])["effect_facts"])
        assert row["deduplication_hits"] == 0

    def test_failed_effect_reports_no_deduplication_hits(self, spark):
        row = one(facts_for(spark, job_effects=[{
            "effect_key": "j1:counter:orders", "job_id": "j1", "attempt_number": 1,
            "job_attempt_count": 5, "status": "FAILED",
            "created_at": ts("2026-09-01T10:00:00Z"), "completed_at": None,
        }])["effect_facts"])
        assert row["deduplication_hits"] == 0
        assert row["is_failed"] is True


class TestIdempotencyFacts:
    def test_raw_key_is_not_a_column(self, spark):
        columns = facts_for(spark)["idempotency_facts"].columns
        assert "idempotency_key" not in columns
        assert "idempotency_key_hash" in columns


class TestEmptyInput:
    def test_every_fact_table_has_a_valid_empty_schema(self, spark):
        facts = facts_for(spark)
        for name, frame_ in facts.items():
            assert frame_.count() == 0, name
            assert frame_.columns, name

    def test_required_columns_are_present(self, spark):
        facts = facts_for(spark)
        required = {
            "job_facts": ["job_id", "job_type", "priority", "created_at", "scheduled_at",
                          "started_at", "finished_at", "status", "attempt_count",
                          "max_attempts", "duration_ms", "queue_delay_ms",
                          "schedule_delay_ms", "final_error", "is_scheduled",
                          "is_succeeded", "is_dead_lettered", "is_legacy_failed",
                          "replay_count"],
            "attempt_facts": ["attempt_id", "job_id", "attempt_number", "worker_id",
                              "outcome", "started_at", "finished_at", "duration_ms",
                              "error_message", "is_abandoned", "is_success", "is_failure",
                              "is_in_progress"],
            "outbox_facts": ["event_id", "aggregate_type", "aggregate_id", "event_type",
                             "status", "created_at", "available_at", "published_at",
                             "terminal_failed_at", "attempt_count", "operator_retry_count",
                             "last_operator_retry_at", "publication_latency_ms",
                             "age_at_export_ms", "is_published", "is_terminal_failed"],
            "dead_letter_facts": ["job_id", "job_type", "priority", "moved_at", "replayed",
                                  "replayed_at", "replay_count", "attempt_count",
                                  "final_status", "scheduled_at"],
            "reliability_action_facts": ["action_id", "action_type", "target_type",
                                         "target_id", "actor", "reason", "created_at",
                                         "before_state", "after_state"],
            "effect_facts": ["effect_key", "job_id", "attempt_number", "effect_type",
                             "status", "response_hash", "created_at", "completed_at",
                             "duration_ms", "is_completed", "is_failed",
                             "is_stale_candidate"],
            "idempotency_facts": ["idempotency_key_hash", "request_hash", "job_id",
                                  "created_at"],
        }
        for dataset, columns in required.items():
            missing = [c for c in columns if c not in facts[dataset].columns]
            assert not missing, f"{dataset} missing {missing}"


class TestUtcGrouping:
    def test_day_is_assigned_in_utc_not_local_time(self, spark):
        facts = facts_for(spark, jobs=[
            {"job_id": "late", "created_at": ts("2026-09-01T23:30:00Z")},
            {"job_id": "early", "created_at": ts("2026-09-02T00:30:00Z")},
        ])
        days = {row["job_id"]: str(row["day"]) for row in facts["job_facts"].collect()}
        assert days == {"late": "2026-09-01", "early": "2026-09-02"}

    def test_equivalent_instants_land_on_the_same_day(self, spark):
        facts = facts_for(spark, jobs=[
            {"job_id": "utc", "created_at": ts("2026-09-01T22:00:00Z")},
            {"job_id": "offset", "created_at": ts("2026-09-02T00:00:00+02:00")},
        ])
        days = {str(row["day"]) for row in facts["job_facts"].collect()}
        assert days == {"2026-09-01"}
