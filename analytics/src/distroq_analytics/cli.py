"""Command-line entry point.

    python -m distroq_analytics.cli export  --start ... --end ... [--output ...]
    python -m distroq_analytics.cli report  --input <run-dir> [--output ...]
    python -m distroq_analytics.cli quality --input <run-dir> [--output ...]

Exit codes are distinct per failure class so a scheduler can react without parsing
text; see :mod:`distroq_analytics.errors`. A database password supplied by argument or
environment is never logged, never echoed, and is stripped from any error text that
originates in the JDBC driver.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

from .config import (
    AnalyticsConfig,
    DEFAULT_EFFECT_STALE_AFTER_MS,
    ENV_DB_PASSWORD,
    ENV_DB_URL,
    ENV_DB_USER,
    ENV_LOG_LEVEL,
    ENV_OUTPUT_DIR,
    ENV_SPARK_MASTER,
)
from .errors import EXIT_OK, EXIT_QUALITY_FATAL, EXIT_USAGE, AnalyticsError
from .pipeline import run_export, run_quality, run_report
from .quality import SAMPLE_LIMIT, SEVERITY_ERROR, SEVERITY_WARNING
from .spark import build_session
from .storage import QUALITY_DIR, REPORTS_DIR
from .window import TimeWindow

log = logging.getLogger("distroq_analytics")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m distroq_analytics.cli",
        description="Read-only historical analytics for DistroQ. Never writes to the "
                    "application database or to Redis.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    export = subparsers.add_parser(
        "export", help="Extract source tables for a UTC window and write Parquet facts."
    )
    export.add_argument("--start", required=True,
                        help="Inclusive lower bound, ISO-8601 with an offset, e.g. "
                             "2026-09-01T00:00:00Z")
    export.add_argument("--end", required=True,
                        help="Exclusive upper bound, ISO-8601 with an offset.")
    _add_output(export)
    _add_db(export)
    _add_common(export)
    export.add_argument("--effect-stale-after-ms", type=int, default=None,
                        help=f"STARTED effects older than this are stale candidates "
                             f"(default {DEFAULT_EFFECT_STALE_AFTER_MS}, matching "
                             "distroq.effects.stale-started-after-ms).")

    report = subparsers.add_parser(
        "report", help="Read fact datasets and write aggregate datasets plus a summary."
    )
    _add_input(report)
    report.add_argument("--output", default=None,
                        help="Report output directory (default: <input>/reports).")
    _add_common(report)
    _add_fail_on(report)

    quality = subparsers.add_parser(
        "quality", help="Run data-quality checks and write data_quality_summary."
    )
    _add_input(quality)
    quality.add_argument("--output", default=None,
                         help="Quality output directory (default: <input>/quality).")
    quality.add_argument("--sample-limit", type=int, default=SAMPLE_LIMIT,
                         help=f"Maximum sample IDs recorded per finding (default {SAMPLE_LIMIT}).")
    _add_common(quality)
    _add_fail_on(quality)

    return parser


def _add_input(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--input", required=True,
                        help="An export run directory, e.g. analytics/output/<run-id>.")


def _add_output(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--output", default=None,
                        help=f"Base output directory; a run directory is created inside it "
                             f"(default: ${ENV_OUTPUT_DIR} or analytics/output).")


def _add_db(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--jdbc-url", default=None,
                        help=f"PostgreSQL JDBC URL (default: ${ENV_DB_URL}).")
    parser.add_argument("--db-user", default=None,
                        help=f"Database user (default: ${ENV_DB_USER}).")
    parser.add_argument("--db-password", default=None,
                        help=f"Database password. Prefer ${ENV_DB_PASSWORD}: an argument is "
                             "visible in the process list to every user on the host.")


def _add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--overwrite", action="store_true",
                        help="Replace the target directory. Without this an existing "
                             "destination is an error, never a silent merge.")
    parser.add_argument("--spark-master", default=None,
                        help=f"Spark master URL (default: ${ENV_SPARK_MASTER} or local[*]).")
    parser.add_argument("--log-level", default=None,
                        help=f"Spark log level (default: ${ENV_LOG_LEVEL} or WARN).")
    parser.add_argument("--no-csv", action="store_true",
                        help="Skip the optional human-readable CSV copies of each dataset.")
    parser.add_argument("--json", action="store_true",
                        help="Print the stage summary as JSON instead of text.")


def _add_fail_on(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--fail-on", choices=["none", "warning", "error"], default="none",
                        help="Exit nonzero when a data-quality finding reaches this severity "
                             "(default none: findings are reported, not enforced).")


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)sZ %(levelname)-7s %(message)s",
        stream=sys.stderr,
    )
    logging.Formatter.converter = __import__("time").gmtime

    try:
        config = AnalyticsConfig.resolve(args)
    except AnalyticsError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return exc.exit_code

    spark = None
    try:
        if args.command == "export":
            window = TimeWindow.parse(args.start, args.end)
            log.info("export window %s -> run id %s", window, window.run_id)
            spark = build_session(config, jdbc=True)
            summary = run_export(spark, config, window, overwrite=args.overwrite)
            _emit(args, summary, _export_lines(summary))
            return EXIT_OK

        run_dir = Path(args.input)
        if args.command == "report":
            output = Path(args.output) if args.output else run_dir / REPORTS_DIR
            spark = build_session(config, jdbc=False)
            summary = run_report(spark, run_dir, output, overwrite=args.overwrite,
                                 csv_preview=not args.no_csv)
            _emit(args, summary, _report_lines(summary))
            return _quality_gate(args.fail_on, summary["data_quality"]["worst_severity"])

        if args.command == "quality":
            output = Path(args.output) if args.output else run_dir / QUALITY_DIR
            spark = build_session(config, jdbc=False)
            summary = run_quality(spark, run_dir, output, overwrite=args.overwrite,
                                  sample_limit=args.sample_limit, csv_preview=not args.no_csv)
            _emit(args, summary, _quality_lines(summary))
            return _quality_gate(args.fail_on, summary["worst_severity"])

        return EXIT_USAGE
    except AnalyticsError as exc:
        print(f"error [{exc.exit_name}]: {config.scrub(str(exc))}", file=sys.stderr)
        return exc.exit_code
    except KeyboardInterrupt:
        print("error: interrupted", file=sys.stderr)
        return EXIT_USAGE
    finally:
        if spark is not None:
            spark.stop()


def _quality_gate(threshold: str, worst: str | None) -> int:
    from .quality import SEVERITY_ORDER

    if threshold == "none" or worst is None:
        return EXIT_OK
    floor = SEVERITY_ERROR if threshold == "error" else SEVERITY_WARNING
    if SEVERITY_ORDER[worst] >= SEVERITY_ORDER[floor]:
        print(
            f"error [QUALITY_FATAL]: data-quality findings reached severity {worst}, which "
            f"--fail-on {threshold} treats as fatal",
            file=sys.stderr,
        )
        return EXIT_QUALITY_FATAL
    return EXIT_OK


def _emit(args, summary: dict, lines: list[str]) -> None:
    if args.json:
        print(json.dumps(summary, indent=2, sort_keys=True, default=str))
    else:
        print("\n".join(lines))


def _export_lines(summary: dict) -> list[str]:
    lines = [
        f"run id                  {summary['run_id']}",
        f"window                  [{summary['requested_lower_bound']}, "
        f"{summary['requested_upper_bound']})",
        f"source                  {summary['source_database']} "
        f"(schema V{summary['source_schema_version']})",
        f"application version     {summary['application_version']}",
        f"spark                   {summary['spark_version']} on {summary['spark_master']}",
        "",
        "source row counts",
    ]
    for name, count in sorted(summary["source_row_counts"].items()):
        lines.append(f"  {name:<24} {count:>10}  ({summary['source_inclusion_timestamps'][name]})")
    lines.append("")
    lines.append("fact row counts")
    for name, count in sorted(summary["fact_row_counts"].items()):
        lines.append(f"  {name:<24} {count:>10}")
    lines += [
        "",
        f"extraction              {summary['extraction_duration_seconds']}s",
        f"transformation          {summary['transformation_duration_seconds']}s",
        f"total                   {summary['total_duration_seconds']}s",
        f"source rows unchanged   {summary['read_only']['row_census_unchanged']}",
        f"flyway unchanged        {summary['read_only']['flyway_unchanged']}",
    ]
    for warning in summary["data_quality_warnings"]:
        lines.append(f"warning                 {warning}")
    return lines


def _report_lines(summary: dict) -> list[str]:
    lines = [f"run id                  {summary['run_id']}", "", "aggregate row counts"]
    for name, count in sorted(summary["aggregate_row_counts"].items()):
        lines.append(f"  {name:<26} {count:>8}")
    lines.append("")
    lines.append("headline")
    for key, value in summary["headline"].items():
        lines.append(f"  {key:<26} {value}")
    lines += [
        "",
        f"data-quality findings   {summary['data_quality']['total_findings']} "
        f"(worst: {summary['data_quality']['worst_severity'] or 'none'})",
        f"duration                {summary['duration_seconds']}s",
        f"output                  {summary['output']}",
    ]
    return lines


def _quality_lines(summary: dict) -> list[str]:
    lines = [
        f"run id                  {summary['run_id']}",
        f"checks run              {summary['checks_run']}",
        f"total findings          {summary['total_findings']} "
        f"(worst: {summary['worst_severity'] or 'none'})",
        f"source modified         {summary['source_modified']}",
        "",
    ]
    for finding in summary["findings"]:
        marker = " " if finding["count"] == 0 else "!"
        samples = ", ".join(finding["sample_ids"][:3])
        lines.append(
            f" {marker} {finding['check_name']:<48} {finding['severity']:<8} "
            f"{finding['count']:>6}  {samples}"
        )
    lines += ["", f"output                  {summary['output']}"]
    return lines


if __name__ == "__main__":
    sys.exit(main())
