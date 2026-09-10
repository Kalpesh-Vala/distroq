"""Orchestration for the three analytics stages.

``export`` is the only stage that touches the database. ``report`` and ``quality``
read the Parquet an export already wrote, which is what makes them cheap to rerun and
what keeps a reporting bug from turning into database load.

Determinism across reruns is deliberate, not incidental:

* The run ID is derived from the window, so a rerun of the same window collides with
  its own previous output and has to be told to overwrite it.
* ``report`` and ``quality`` take "now" from the export metadata rather than the wall
  clock, so re-reporting an old export tomorrow produces the same numbers it produced
  today.
"""

from __future__ import annotations

import logging
import re
import time
from datetime import datetime
from pathlib import Path

from pyspark.sql import SparkSession

from .config import AnalyticsConfig
from .errors import TransformationError
from .extract import INCLUSION_TIMESTAMPS, SourceDatabase, extract_all
from .schemas import SOURCE_SCHEMAS
from . import quality as quality_module
from . import report as report_module
from . import transform as transform_module
from .storage import (
    EXPORT_METADATA_FILE,
    FACTS_DIR,
    METADATA_DIR,
    prepare_directory,
    read_dataset,
    read_json,
    run_directory,
    utc_now,
    write_csv_preview,
    write_dataset,
    write_json,
)
from .window import TimeWindow, format_instant, parse_instant

log = logging.getLogger(__name__)

FACT_DATASETS = (
    "job_facts",
    "attempt_facts",
    "outbox_facts",
    "dead_letter_facts",
    "reliability_action_facts",
    "effect_facts",
    "idempotency_facts",
)


def detect_application_version(start: Path | None = None) -> str | None:
    """Read the DistroQ version out of pom.xml, if the export runs inside the repo."""
    here = (start or Path.cwd()).resolve()
    for directory in (here, *here.parents):
        pom = directory / "pom.xml"
        if pom.is_file():
            match = re.search(
                r"<artifactId>distroq</artifactId>\s*<version>([^<]+)</version>",
                pom.read_text(encoding="utf-8"),
            )
            if match:
                return match.group(1).strip()
    return None


def run_export(spark: SparkSession, config: AnalyticsConfig, window: TimeWindow, *,
               overwrite: bool = False) -> dict:
    started_at = utc_now()
    wall = time.perf_counter()

    source = SourceDatabase(spark, config)
    server = source.check_connection()
    source.require_tables()
    census_before = source.row_census()
    flyway_before = source.flyway_fingerprint()

    run_dir = prepare_directory(
        run_directory(config.output_dir, window.run_id), overwrite, label="export run directory"
    )

    extraction_start = time.perf_counter()
    extracts_meta = extract_all(source, window, run_dir)
    extraction_seconds = time.perf_counter() - extraction_start

    transform_start = time.perf_counter()
    try:
        extracts = {
            name: read_dataset(spark, run_dir, name, SOURCE_SCHEMAS[name])
            for name in SOURCE_SCHEMAS
        }
        facts = transform_module.build_all(extracts, started_at, config.effect_stale_after_ms)
        facts_dir = run_dir / FACTS_DIR
        facts_dir.mkdir(parents=True, exist_ok=True)
        fact_counts = {name: write_dataset(facts[name], facts_dir, name) for name in FACT_DATASETS}
    except Exception as exc:
        raise TransformationError(
            f"Building fact tables failed: {config.scrub(str(exc).splitlines()[0])}"
        ) from None
    transform_seconds = time.perf_counter() - transform_start

    census_after = source.row_census()
    flyway_after = source.flyway_fingerprint()
    drift = {
        table: {"before": census_before[table], "after": census_after[table]}
        for table in census_before
        if census_before[table] != census_after.get(table)
    }

    warnings = _extraction_warnings(source, extracts_meta)
    finished_at = utc_now()

    metadata = {
        "run_id": window.run_id,
        "created_at": format_instant(started_at),
        "run_started_at": format_instant(started_at),
        "run_finished_at": format_instant(finished_at),
        "requested_lower_bound": format_instant(window.start),
        "requested_upper_bound": format_instant(window.end),
        "window": window.as_dict(),
        "source_database": config.source_identifier,
        "source_database_name": server["database_name"],
        "source_server_version": server["server_version"],
        "source_server_timezone": server["server_timezone"],
        "source_schema_version": source.schema_version(),
        "application_version": detect_application_version(),
        "analytics_version": _analytics_version(),
        "spark_version": spark.version,
        "spark_master": config.spark_master,
        "source_row_counts": {name: meta["rows"] for name, meta in extracts_meta.items()},
        "source_inclusion_timestamps": INCLUSION_TIMESTAMPS,
        "fact_row_counts": fact_counts,
        "per_table_extraction_seconds": {
            name: meta["duration_seconds"] for name, meta in extracts_meta.items()
        },
        "extraction_duration_seconds": round(extraction_seconds, 3),
        "transformation_duration_seconds": round(transform_seconds, 3),
        "total_duration_seconds": round(time.perf_counter() - wall, 3),
        "extraction_succeeded": True,
        "data_quality_warnings": warnings,
        "read_only": {
            "jdbc_read_only_mode": "always",
            "row_census_before": census_before,
            "row_census_after": census_after,
            "row_census_unchanged": not drift,
            "row_census_drift": drift,
            "flyway_before": flyway_before,
            "flyway_after": flyway_after,
            "flyway_unchanged": flyway_before == flyway_after,
        },
        "effect_counters_snapshot": {
            "note": "Point-in-time running totals. effect_counters has no per-row history, so "
                    "it is not a windowed fact table.",
            "captured_at": format_instant(finished_at),
            "counters": source.effect_counter_snapshot(),
        },
    }
    write_json(metadata, run_dir / METADATA_DIR / EXPORT_METADATA_FILE)
    return metadata


def _analytics_version() -> str:
    from . import __version__

    return __version__


def _extraction_warnings(source: SourceDatabase, extracts_meta: dict) -> list[str]:
    warnings: list[str] = []
    orphan_attempts = source.unwindowable_attempts()
    if orphan_attempts:
        warnings.append(
            f"{orphan_attempts} job_attempts row(s) have a null started_at and can never be "
            "selected by a window keyed on started_at; they are absent from every export."
        )
    if extracts_meta["jobs"]["rows"] == 0:
        warnings.append("No jobs fall in this window; every job-derived aggregate will be empty.")
    return warnings


def load_facts(spark: SparkSession, run_dir: Path) -> dict:
    facts_dir = Path(run_dir) / FACTS_DIR
    return {name: read_dataset(spark, facts_dir, name) for name in FACT_DATASETS}


def export_time_of(run_dir: Path) -> datetime:
    """The instant the export ran, taken from metadata so reports stay reproducible."""
    metadata = read_json(Path(run_dir) / METADATA_DIR / EXPORT_METADATA_FILE)
    return parse_instant(metadata["created_at"], "export created_at")


def run_report(spark: SparkSession, run_dir: Path, output_dir: Path, *,
               overwrite: bool = False, sample_limit: int = quality_module.SAMPLE_LIMIT,
               csv_preview: bool = True) -> dict:
    run_dir = Path(run_dir)
    started = time.perf_counter()
    export_time = export_time_of(run_dir)
    facts = load_facts(spark, run_dir)

    target = prepare_directory(Path(output_dir), overwrite, label="report output directory")

    try:
        aggregates = report_module.build_all(facts)
        counts = {name: write_dataset(frame, target, name) for name, frame in aggregates.items()}
    except Exception as exc:
        raise TransformationError(f"Building aggregate reports failed: {exc}") from None

    findings = quality_module.run_checks(facts, export_time, sample_limit)
    quality_frame = quality_module.findings_frame(spark, findings)
    counts["data_quality_summary"] = write_dataset(quality_frame, target, "data_quality_summary")

    if csv_preview:
        for name, frame in aggregates.items():
            write_csv_preview(frame, target, name)
        write_csv_preview(quality_frame, target, "data_quality_summary")

    summary = {
        "run_id": run_dir.name,
        "generated_at": format_instant(utc_now()),
        "export_time": format_instant(export_time),
        "input": str(run_dir),
        "output": str(target),
        "fact_row_counts": {name: frame.count() for name, frame in facts.items()},
        "aggregate_row_counts": counts,
        "headline": report_module.headline(facts),
        "data_quality": {
            "total_findings": quality_module.total_findings(findings),
            "worst_severity": quality_module.worst_severity(findings),
            "findings": [finding.as_dict() for finding in findings if finding.count],
        },
        "duration_seconds": round(time.perf_counter() - started, 3),
    }
    write_json(summary, target / "report_summary.json")
    return summary


def run_quality(spark: SparkSession, run_dir: Path, output_dir: Path, *,
                overwrite: bool = False, sample_limit: int = quality_module.SAMPLE_LIMIT,
                csv_preview: bool = True) -> dict:
    run_dir = Path(run_dir)
    started = time.perf_counter()
    export_time = export_time_of(run_dir)
    facts = load_facts(spark, run_dir)

    target = prepare_directory(Path(output_dir), overwrite, label="quality output directory")
    findings = quality_module.run_checks(facts, export_time, sample_limit)
    frame = quality_module.findings_frame(spark, findings)
    rows = write_dataset(frame, target, "data_quality_summary")
    if csv_preview:
        write_csv_preview(frame, target, "data_quality_summary")

    summary = {
        "run_id": run_dir.name,
        "generated_at": format_instant(utc_now()),
        "export_time": format_instant(export_time),
        "input": str(run_dir),
        "output": str(target),
        "checks_run": len(quality_module.CHECKS),
        "checks_reported": rows,
        "sample_limit": sample_limit,
        "total_findings": quality_module.total_findings(findings),
        "worst_severity": quality_module.worst_severity(findings),
        "source_modified": False,
        "findings": [finding.as_dict() for finding in findings],
        "duration_seconds": round(time.perf_counter() - started, 3),
    }
    write_json(summary, target / "quality_summary.json")
    return summary
