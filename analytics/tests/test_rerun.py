from __future__ import annotations

import json
from pathlib import Path

import pytest

from distroq_analytics import pipeline, quality, report, transform
from distroq_analytics.errors import EXIT_OUTPUT_EXISTS, MissingInputError, OutputExistsError
from distroq_analytics.storage import (
    EXPORT_METADATA_FILE,
    FACTS_DIR,
    METADATA_DIR,
    prepare_directory,
    write_dataset,
    write_json,
)
from distroq_analytics.window import TimeWindow, format_instant
from fixtures import all_sources, ts

EXPORT_TIME = ts("2026-09-10T00:00:00Z")
STALE_MS = 300_000

SOURCES = {
    "jobs": [
        {"job_id": "j1", "priority": "HIGH", "status": "SUCCEEDED",
         "created_at": ts("2026-09-01T10:00:00Z"),
         "started_at": ts("2026-09-01T10:00:01Z"),
         "finished_at": ts("2026-09-01T10:00:02Z")},
        {"job_id": "j2", "priority": "LOW", "status": "DEAD_LETTERED", "attempt_count": 3,
         "created_at": ts("2026-09-02T10:00:00Z"),
         "started_at": ts("2026-09-02T10:00:01Z"),
         "finished_at": ts("2026-09-02T10:00:09Z"),
         "dead_lettered_at": ts("2026-09-02T10:00:09Z"), "replay_count": 0,
         "replayed": False},
    ],
    "job_attempts": [
        {"attempt_id": "a1", "job_id": "j1", "attempt_number": 1, "outcome": "SUCCESS",
         "started_at": ts("2026-09-01T10:00:01Z"),
         "finished_at": ts("2026-09-01T10:00:02Z")},
    ],
    "outbox_events": [
        {"event_id": "e1", "aggregate_id": "j1", "status": "PUBLISHED",
         "created_at": ts("2026-09-01T10:00:00Z"),
         "available_at": ts("2026-09-01T10:00:00Z"),
         "published_at": ts("2026-09-01T10:00:00.100Z")},
    ],
}


def _write_run(spark, tmp_path: Path, run_id: str = "20260901T000000Z__20261001T000000Z") -> Path:
    """Materialise a run directory the way `export` would, without a database."""
    run_dir = tmp_path / run_id
    facts_dir = run_dir / FACTS_DIR
    facts_dir.mkdir(parents=True, exist_ok=True)
    facts = transform.build_all(all_sources(spark, **SOURCES), EXPORT_TIME, STALE_MS)
    for name, frame in facts.items():
        write_dataset(frame, facts_dir, name)
    write_json(
        {"run_id": run_id, "created_at": format_instant(EXPORT_TIME)},
        run_dir / METADATA_DIR / EXPORT_METADATA_FILE,
    )
    return run_dir


def _normalise(frame):
    return sorted(json.dumps(row.asDict(), sort_keys=True, default=str)
                  for row in frame.collect())


def _file_order(frame):
    """Rows as written, unsorted.

    Stronger than `_normalise`: it catches a reordering that set comparison would miss,
    which is what makes the CSV copies byte-comparable.
    """
    return [json.dumps(row.asDict(), sort_keys=True, default=str) for row in frame.collect()]


class TestOverwriteRules:
    def test_existing_non_empty_directory_is_refused(self, tmp_path):
        target = tmp_path / "run"
        target.mkdir()
        (target / "marker.txt").write_text("x", encoding="utf-8")
        with pytest.raises(OutputExistsError) as excinfo:
            prepare_directory(target, overwrite=False, label="export run directory")
        assert excinfo.value.exit_code == EXIT_OUTPUT_EXISTS
        assert "--overwrite" in str(excinfo.value)

    def test_existing_empty_directory_is_accepted(self, tmp_path):
        target = tmp_path / "run"
        target.mkdir()
        assert prepare_directory(target, overwrite=False, label="run") == target

    def test_overwrite_replaces_the_target(self, tmp_path):
        target = tmp_path / "run"
        target.mkdir()
        (target / "old.txt").write_text("x", encoding="utf-8")
        prepare_directory(target, overwrite=True, label="run")
        assert list(target.iterdir()) == []

    def test_overwrite_touches_only_the_requested_run(self, tmp_path):
        sibling = tmp_path / "other-run"
        sibling.mkdir()
        (sibling / "keep.txt").write_text("keep me", encoding="utf-8")
        target = tmp_path / "run"
        target.mkdir()
        (target / "old.txt").write_text("x", encoding="utf-8")

        prepare_directory(target, overwrite=True, label="run")

        assert (sibling / "keep.txt").read_text(encoding="utf-8") == "keep me"
        assert list(target.iterdir()) == []


class TestRunIdentity:
    def test_the_same_window_targets_the_same_run_directory(self):
        first = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
        second = TimeWindow.parse("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
        assert first.run_id == second.run_id, (
            "a rerun must collide with its own output, otherwise --overwrite is meaningless"
        )


class TestReportRerun:
    def test_repeated_reports_are_identical(self, spark, tmp_path):
        run_dir = _write_run(spark, tmp_path)

        first = pipeline.run_report(spark, run_dir, tmp_path / "reports-1", csv_preview=False)
        second = pipeline.run_report(spark, run_dir, tmp_path / "reports-2", csv_preview=False)

        assert first["aggregate_row_counts"] == second["aggregate_row_counts"]
        assert first["headline"] == second["headline"]
        assert first["data_quality"]["total_findings"] == second["data_quality"]["total_findings"]

        for name in report.AGGREGATE_DATASETS:
            a = spark.read.parquet(str((tmp_path / "reports-1" / name).as_posix()))
            b = spark.read.parquet(str((tmp_path / "reports-2" / name).as_posix()))
            assert a.schema == b.schema
            assert _normalise(a) == _normalise(b)
            # Row order too, not just row content: an aggregate written in a different
            # order is still a different file to anything that diffs or hashes it.
            assert _file_order(a) == _file_order(b), name

    def test_csv_copies_are_byte_identical_across_report_runs(self, spark, tmp_path):
        run_dir = _write_run(spark, tmp_path)
        pipeline.run_report(spark, run_dir, tmp_path / "csv-1", csv_preview=True)
        pipeline.run_report(spark, run_dir, tmp_path / "csv-2", csv_preview=True)

        for name in (*report.AGGREGATE_DATASETS, "data_quality_summary"):
            a = (tmp_path / "csv-1" / f"{name}.csv").read_bytes()
            b = (tmp_path / "csv-2" / f"{name}.csv").read_bytes()
            assert a == b, name

    def test_report_refuses_an_existing_output_directory(self, spark, tmp_path):
        run_dir = _write_run(spark, tmp_path)
        output = tmp_path / "reports"
        pipeline.run_report(spark, run_dir, output, csv_preview=False)
        with pytest.raises(OutputExistsError):
            pipeline.run_report(spark, run_dir, output, csv_preview=False)

    def test_report_overwrite_is_deterministic(self, spark, tmp_path):
        run_dir = _write_run(spark, tmp_path)
        output = tmp_path / "reports"
        first = pipeline.run_report(spark, run_dir, output, csv_preview=False)
        before = _normalise(
            spark.read.parquet(str((output / "daily_job_summary").as_posix()))
        )
        second = pipeline.run_report(spark, run_dir, output, overwrite=True, csv_preview=False)
        after = _normalise(spark.read.parquet(str((output / "daily_job_summary").as_posix())))
        assert before == after
        assert first["headline"] == second["headline"]

    def test_no_duplicate_rows_are_introduced_by_a_rerun(self, spark, tmp_path):
        run_dir = _write_run(spark, tmp_path)
        output = tmp_path / "reports"
        pipeline.run_report(spark, run_dir, output, csv_preview=False)
        pipeline.run_report(spark, run_dir, output, overwrite=True, csv_preview=False)
        daily = spark.read.parquet(str((output / "daily_job_summary").as_posix()))
        assert daily.count() == daily.dropDuplicates(["day", "priority"]).count()

    def test_facts_are_unchanged_by_reporting(self, spark, tmp_path):
        run_dir = _write_run(spark, tmp_path)
        before = {
            name: _normalise(frame)
            for name, frame in pipeline.load_facts(spark, run_dir).items()
        }
        pipeline.run_report(spark, run_dir, tmp_path / "reports", csv_preview=False)
        after = {
            name: _normalise(frame)
            for name, frame in pipeline.load_facts(spark, run_dir).items()
        }
        assert before == after


class TestQualityRerun:
    def test_repeated_quality_runs_agree(self, spark, tmp_path):
        run_dir = _write_run(spark, tmp_path)
        first = pipeline.run_quality(spark, run_dir, tmp_path / "q1", csv_preview=False)
        second = pipeline.run_quality(spark, run_dir, tmp_path / "q2", csv_preview=False)
        assert first["findings"] == second["findings"]
        assert first["total_findings"] == second["total_findings"]
        assert first["worst_severity"] == second["worst_severity"]

    def test_quality_refuses_an_existing_output_directory(self, spark, tmp_path):
        run_dir = _write_run(spark, tmp_path)
        output = tmp_path / "quality"
        pipeline.run_quality(spark, run_dir, output, csv_preview=False)
        with pytest.raises(OutputExistsError):
            pipeline.run_quality(spark, run_dir, output, csv_preview=False)

    def test_quality_reports_every_check(self, spark, tmp_path):
        run_dir = _write_run(spark, tmp_path)
        summary = pipeline.run_quality(spark, run_dir, tmp_path / "quality", csv_preview=False)
        assert summary["checks_run"] == len(quality.CHECKS)
        assert summary["checks_reported"] == len(quality.CHECKS)
        assert summary["source_modified"] is False


class TestMissingInput:
    def test_missing_run_directory_is_a_clear_error(self, spark, tmp_path):
        with pytest.raises(MissingInputError):
            pipeline.run_report(spark, tmp_path / "nope", tmp_path / "out", csv_preview=False)

    def test_missing_fact_dataset_is_a_clear_error(self, spark, tmp_path):
        run_dir = _write_run(spark, tmp_path)
        import shutil

        shutil.rmtree(run_dir / FACTS_DIR / "job_facts")
        with pytest.raises(MissingInputError):
            pipeline.run_report(spark, run_dir, tmp_path / "out", csv_preview=False)
