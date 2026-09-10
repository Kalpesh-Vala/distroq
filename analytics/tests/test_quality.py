from __future__ import annotations

import pytest

from distroq_analytics import quality, transform
from fixtures import all_sources, ts

EXPORT_TIME = ts("2026-09-10T00:00:00Z")
STALE_MS = 300_000


def facts_for(spark, **sources):
    return transform.build_all(all_sources(spark, **sources), EXPORT_TIME, STALE_MS)


def findings_by_name(spark, **sources):
    findings = quality.run_checks(facts_for(spark, **sources), EXPORT_TIME)
    return {finding.check_name: finding for finding in findings}


def clean_sources():
    """A small internally consistent dataset that must produce zero findings."""
    return {
        "jobs": [{
            "job_id": "j-ok", "job_type": "email", "priority": "NORMAL",
            "status": "SUCCEEDED", "attempt_count": 1, "max_attempts": 3,
            "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:01Z"),
            "finished_at": ts("2026-09-01T10:00:02Z"),
        }],
        "job_attempts": [{
            "attempt_id": "a-ok", "job_id": "j-ok", "attempt_number": 1,
            "outcome": "SUCCESS",
            "started_at": ts("2026-09-01T10:00:01Z"),
            "finished_at": ts("2026-09-01T10:00:02Z"),
        }],
        "outbox_events": [{
            "event_id": "e-ok", "aggregate_type": "Job", "aggregate_id": "j-ok",
            "event_type": "ENQUEUE_SUBMIT", "status": "PUBLISHED", "attempt_count": 1,
            "operator_retry_count": 0,
            "created_at": ts("2026-09-01T10:00:00Z"),
            "available_at": ts("2026-09-01T10:00:00Z"),
            "published_at": ts("2026-09-01T10:00:00.100Z"),
        }],
        "job_effects": [{
            "effect_key": "j-ok:counter:orders", "job_id": "j-ok", "attempt_number": 1,
            "job_attempt_count": 1, "effect_type": "counter", "status": "COMPLETED",
            "created_at": ts("2026-09-01T10:00:01Z"),
            "completed_at": ts("2026-09-01T10:00:01.500Z"),
        }],
        "idempotency_keys": [{
            "idempotency_key_hash": "h1", "request_hash": "r1", "job_id": "j-ok",
            "created_at": ts("2026-09-01T10:00:00Z"),
        }],
    }


class TestCleanFixtures:
    def test_clean_input_produces_zero_findings(self, spark):
        findings = quality.run_checks(facts_for(spark, **clean_sources()), EXPORT_TIME)
        offenders = {f.check_name: f.count for f in findings if f.count}
        assert offenders == {}
        assert quality.total_findings(findings) == 0
        assert quality.worst_severity(findings) is None

    def test_empty_input_produces_zero_findings(self, spark):
        findings = quality.run_checks(facts_for(spark), EXPORT_TIME)
        assert quality.total_findings(findings) == 0

    def test_every_check_is_reported_even_at_zero(self, spark):
        findings = quality.run_checks(facts_for(spark, **clean_sources()), EXPORT_TIME)
        assert len(findings) == len(quality.CHECKS)
        assert {f.check_name for f in findings} == {c.name for c in quality.CHECKS}


class TestChecksDetectBadFixtures:
    def test_jobs_negative_duration(self, spark):
        found = findings_by_name(spark, jobs=[{
            "job_id": "bad", "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:05Z"),
            "finished_at": ts("2026-09-01T10:00:01Z"),
        }])["jobs_negative_duration"]
        assert found.count == 1 and found.sample_ids == ["bad"]

    def test_attempts_negative_duration(self, spark):
        found = findings_by_name(spark, job_attempts=[{
            "attempt_id": "bad", "job_id": "j1",
            "started_at": ts("2026-09-01T10:00:05Z"),
            "finished_at": ts("2026-09-01T10:00:01Z"),
        }])["attempts_negative_duration"]
        assert found.count == 1

    def test_finished_jobs_without_finished_at(self, spark):
        found = findings_by_name(spark, jobs=[{
            "job_id": "bad", "status": "SUCCEEDED",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:01Z"), "finished_at": None,
        }])["finished_jobs_without_finished_at"]
        assert found.count == 1

    def test_succeeded_jobs_without_successful_attempt(self, spark):
        found = findings_by_name(spark, jobs=[{
            "job_id": "bad", "status": "SUCCEEDED",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:01Z"),
            "finished_at": ts("2026-09-01T10:00:02Z"),
        }])["succeeded_jobs_without_successful_attempt"]
        assert found.count == 1
        assert found.severity == quality.SEVERITY_WARNING

    def test_dead_lettered_jobs_without_dlq_row(self, spark):
        found = findings_by_name(spark, jobs=[{
            "job_id": "bad", "status": "DEAD_LETTERED", "attempt_count": 3,
            "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:01Z"),
            "finished_at": ts("2026-09-01T10:00:02Z"),
            "dead_lettered_at": None,
        }])["dead_lettered_jobs_without_dlq_row"]
        assert found.count == 1

    def test_dlq_rows_without_job(self, spark):
        found = findings_by_name(spark, dead_letters=[{
            "job_id": "orphan", "moved_at": ts("2026-09-01T10:00:00Z"),
            "job_type": None, "priority": None, "final_status": None,
            "attempt_count": None, "max_attempts": None,
        }])["dlq_rows_without_job"]
        assert found.count == 1

    def test_job_attempt_count_mismatch(self, spark):
        found = findings_by_name(spark, jobs=[{
            "job_id": "bad", "attempt_count": 3,
            "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:01Z"),
            "finished_at": ts("2026-09-01T10:00:02Z"),
        }])["job_attempt_count_mismatch"]
        assert found.count == 1
        assert found.severity == quality.SEVERITY_WARNING

    def test_multiple_in_progress_attempts(self, spark):
        found = findings_by_name(
            spark,
            jobs=[{"job_id": "j1", "status": "RUNNING", "attempt_count": 2,
                   "created_at": ts("2026-09-01T10:00:00Z"),
                   "started_at": ts("2026-09-01T10:00:01Z"), "finished_at": None}],
            job_attempts=[
                {"attempt_id": "a1", "job_id": "j1", "attempt_number": 1,
                 "outcome": "IN_PROGRESS", "started_at": ts("2026-09-01T10:00:01Z")},
                {"attempt_id": "a2", "job_id": "j1", "attempt_number": 2,
                 "outcome": "IN_PROGRESS", "started_at": ts("2026-09-01T10:00:02Z")},
            ],
        )["multiple_in_progress_attempts"]
        assert found.count == 1
        assert found.severity == quality.SEVERITY_ERROR

    def test_jobs_with_expired_execution_lease(self, spark):
        found = findings_by_name(spark, jobs=[{
            "job_id": "bad", "status": "RUNNING",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:01Z"), "finished_at": None,
            "execution_owner": "worker-1",
            "execution_lease_until": ts("2026-09-01T10:00:31Z"),
        }])["jobs_with_expired_execution_lease"]
        assert found.count == 1

    def test_scheduled_jobs_missing_schedule_event(self, spark):
        found = findings_by_name(spark, jobs=[{
            "job_id": "bad", "status": "SCHEDULED",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "scheduled_at": ts("2026-09-05T10:00:00Z"),
            "started_at": None, "finished_at": None,
        }])["scheduled_jobs_missing_schedule_event"]
        assert found.count == 1

    def test_scheduled_job_with_its_event_is_clean(self, spark):
        found = findings_by_name(
            spark,
            jobs=[{"job_id": "ok", "status": "SCHEDULED",
                   "created_at": ts("2026-09-01T10:00:00Z"),
                   "scheduled_at": ts("2026-09-05T10:00:00Z"),
                   "started_at": None, "finished_at": None}],
            outbox_events=[{
                "event_id": "e1", "aggregate_id": "ok",
                "event_type": "SCHEDULE_USER_JOB", "status": "PUBLISHED",
                "created_at": ts("2026-09-01T10:00:00Z"),
                "available_at": ts("2026-09-05T10:00:00Z"),
                "published_at": ts("2026-09-01T10:00:00.100Z"),
            }],
        )["scheduled_jobs_missing_schedule_event"]
        assert found.count == 0

    def test_retry_jobs_missing_retry_event(self, spark):
        found = findings_by_name(spark, jobs=[{
            "job_id": "bad", "status": "RETRYING", "attempt_count": 1,
            "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:01Z"), "finished_at": None,
        }])["retry_jobs_missing_retry_event"]
        assert found.count == 1

    def test_published_outbox_missing_published_at(self, spark):
        found = findings_by_name(spark, outbox_events=[{
            "event_id": "bad", "status": "PUBLISHED",
            "created_at": ts("2026-09-01T10:00:00Z"),
            "available_at": ts("2026-09-01T10:00:00Z"),
            "published_at": None,
        }])["published_outbox_missing_published_at"]
        assert found.count == 1

    def test_published_outbox_impossible_timestamps(self, spark):
        found = findings_by_name(spark, outbox_events=[{
            "event_id": "bad", "status": "PUBLISHED",
            "created_at": ts("2026-09-01T10:00:05Z"),
            "available_at": ts("2026-09-01T10:00:05Z"),
            "published_at": ts("2026-09-01T10:00:01Z"),
        }])["published_outbox_impossible_timestamps"]
        assert found.count == 1

    def test_effects_invalid_status(self, spark):
        found = findings_by_name(spark, job_effects=[{
            "effect_key": "bad", "job_id": "j1", "status": "MAYBE",
            "created_at": ts("2026-09-01T10:00:00Z"),
        }])["effects_invalid_status"]
        assert found.count == 1

    def test_duplicate_effect_keys(self, spark):
        found = findings_by_name(spark, job_effects=[
            {"effect_key": "dup", "job_id": "j1",
             "created_at": ts("2026-09-01T10:00:00Z"),
             "completed_at": ts("2026-09-01T10:00:01Z")},
            {"effect_key": "dup", "job_id": "j2",
             "created_at": ts("2026-09-01T10:00:02Z"),
             "completed_at": ts("2026-09-01T10:00:03Z")},
        ])["duplicate_effect_keys"]
        assert found.count == 1 and found.sample_ids == ["dup"]

    def test_duplicate_idempotency_hashes_for_different_jobs(self, spark):
        found = findings_by_name(spark, idempotency_keys=[
            {"idempotency_key_hash": "h1", "request_hash": "same", "job_id": "j1",
             "created_at": ts("2026-09-01T10:00:00Z")},
            {"idempotency_key_hash": "h2", "request_hash": "same", "job_id": "j2",
             "created_at": ts("2026-09-01T10:00:01Z")},
        ])["duplicate_idempotency_hashes_for_different_jobs"]
        assert found.count == 1

    def test_same_hash_for_the_same_job_is_not_a_finding(self, spark):
        found = findings_by_name(spark, idempotency_keys=[
            {"idempotency_key_hash": "h1", "request_hash": "same", "job_id": "j1",
             "created_at": ts("2026-09-01T10:00:00Z")},
            {"idempotency_key_hash": "h2", "request_hash": "same", "job_id": "j1",
             "created_at": ts("2026-09-01T10:00:01Z")},
        ])["duplicate_idempotency_hashes_for_different_jobs"]
        assert found.count == 0

    def test_invalid_enum_values_are_caught_without_a_db_constraint(self, spark):
        found = findings_by_name(
            spark,
            jobs=[{"job_id": "j", "status": "NOPE",
                   "created_at": ts("2026-09-01T10:00:00Z"),
                   "started_at": None, "finished_at": None}],
            job_attempts=[{"attempt_id": "a", "job_id": "j", "outcome": "PROBABLY",
                           "started_at": ts("2026-09-01T10:00:00Z")}],
            outbox_events=[{"event_id": "e", "status": "SOMETIMES",
                            "created_at": ts("2026-09-01T10:00:00Z"),
                            "available_at": ts("2026-09-01T10:00:00Z")}],
        )
        assert found["jobs_invalid_status"].count == 1
        assert found["attempts_invalid_outcome"].count == 1
        assert found["outbox_invalid_status"].count == 1


class TestFindingShape:
    def test_sample_ids_are_bounded(self, spark):
        jobs = [
            {"job_id": f"j{n:03d}", "created_at": ts("2026-09-01T10:00:00Z"),
             "started_at": ts("2026-09-01T10:00:05Z"),
             "finished_at": ts("2026-09-01T10:00:01Z")}
            for n in range(50)
        ]
        found = quality.run_checks(facts_for(spark, jobs=jobs), EXPORT_TIME, sample_limit=10)
        negative = next(f for f in found if f.check_name == "jobs_negative_duration")
        assert negative.count == 50
        assert len(negative.sample_ids) == 10

    def test_sample_ids_are_deterministic(self, spark):
        jobs = [
            {"job_id": f"j{n:03d}", "created_at": ts("2026-09-01T10:00:00Z"),
             "started_at": ts("2026-09-01T10:00:05Z"),
             "finished_at": ts("2026-09-01T10:00:01Z")}
            for n in range(30)
        ]
        first = findings_by_name(spark, jobs=jobs)["jobs_negative_duration"]
        second = findings_by_name(spark, jobs=jobs)["jobs_negative_duration"]
        assert first.sample_ids == second.sample_ids == [f"j{n:03d}" for n in range(10)]

    def test_severity_is_assigned_consistently(self, spark):
        findings = quality.run_checks(facts_for(spark), EXPORT_TIME)
        assert {f.severity for f in findings} <= {
            quality.SEVERITY_ERROR, quality.SEVERITY_WARNING, quality.SEVERITY_INFO
        }
        by_name = {f.check_name: f.severity for f in findings}
        for _ in range(3):
            again = quality.run_checks(facts_for(spark), EXPORT_TIME)
            assert {f.check_name: f.severity for f in again} == by_name

    def test_every_finding_carries_a_description(self, spark):
        for finding in quality.run_checks(facts_for(spark), EXPORT_TIME):
            assert finding.description.strip()

    def test_findings_frame_matches_the_declared_schema(self, spark):
        findings = quality.run_checks(facts_for(spark, **clean_sources()), EXPORT_TIME)
        frame = quality.findings_frame(spark, findings)
        assert frame.columns == ["check_name", "severity", "count", "sample_ids",
                                 "description"]
        assert frame.count() == len(quality.CHECKS)


class TestFatalGate:
    def _findings(self, spark):
        return quality.run_checks(facts_for(spark, jobs=[{
            "job_id": "bad", "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:05Z"),
            "finished_at": ts("2026-09-01T10:00:01Z"),
        }]), EXPORT_TIME)

    def test_none_threshold_never_fails(self, spark):
        assert quality.is_fatal(self._findings(spark), "none") is False

    def test_error_threshold_fails_on_an_error_finding(self, spark):
        assert quality.is_fatal(self._findings(spark), "error") is True

    def test_error_threshold_ignores_warning_only_findings(self, spark):
        warnings_only = quality.run_checks(facts_for(spark, jobs=[{
            "job_id": "w", "attempt_count": 5,
            "created_at": ts("2026-09-01T10:00:00Z"),
            "started_at": ts("2026-09-01T10:00:01Z"),
            "finished_at": ts("2026-09-01T10:00:02Z"),
        }]), EXPORT_TIME)
        assert quality.worst_severity(warnings_only) == quality.SEVERITY_WARNING
        assert quality.is_fatal(warnings_only, "error") is False
        assert quality.is_fatal(warnings_only, "warning") is True


class TestReadOnly:
    def test_running_checks_does_not_mutate_the_facts(self, spark):
        facts = facts_for(spark, **clean_sources())
        before = {name: frame.collect() for name, frame in facts.items()}
        quality.run_checks(facts, EXPORT_TIME)
        after = {name: frame.collect() for name, frame in facts.items()}
        assert before == after
