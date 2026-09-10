"""Read-only JDBC extraction from the DistroQ application database.

Every statement issued here is a ``SELECT`` against a connection the driver has been
told is read-only (``readOnlyMode=always``), so a write is refused by PostgreSQL and
not merely absent from the SQL. Nothing in this module opens a transaction that could
take a lock a worker cares about.

Window predicates are rendered from parsed ``datetime`` objects, never from raw
operator input, so no user-supplied text reaches a SQL string.

Two extracts carry a ``LEFT JOIN`` into ``jobs``. That is deliberate. ``dead_letters``
has no job type or priority of its own, and ``job_effects`` has no job type; joining at
extraction time keeps the fact grain correct for a DLQ row whose job was created before
the window, which a post-hoc join against the windowed ``jobs`` extract could not do.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from pathlib import Path

from pyspark.sql import DataFrame, SparkSession

from .config import AnalyticsConfig
from .errors import DatabaseConnectionError, ExtractionError, MissingSourceTableError
from .schemas import SOURCE_SCHEMAS, conform
from .window import TimeWindow

log = logging.getLogger(__name__)

#: Text kept for diagnostics is truncated at the source so that no unbounded blob and
#: no job payload is ever written to an analytics file.
TEXT_LIMIT = 500

REQUIRED_TABLES = (
    "jobs",
    "job_attempts",
    "outbox_events",
    "dead_letters",
    "reliability_actions",
    "job_effects",
    "effect_counters",
    "idempotency_keys",
    "flyway_schema_history",
)


@dataclass(frozen=True)
class ExtractSpec:
    name: str
    inclusion_column: str
    sql: str


def _specs(window: TimeWindow) -> list[ExtractSpec]:
    start, end = window.start_sql, window.end_sql
    return [
        ExtractSpec(
            "jobs",
            "jobs.created_at",
            f"""
            SELECT j.id::text                        AS job_id,
                   j.type                            AS job_type,
                   j.priority                        AS priority,
                   j.status                          AS status,
                   j.created_at                      AS created_at,
                   j.scheduled_at                    AS scheduled_at,
                   j.started_at                      AS started_at,
                   j.finished_at                     AS finished_at,
                   j.updated_at                      AS updated_at,
                   j.next_attempt_at                 AS next_attempt_at,
                   j.attempt_count                   AS attempt_count,
                   j.max_attempts                    AS max_attempts,
                   left(j.error_message, {TEXT_LIMIT}) AS final_error,
                   j.execution_owner                 AS execution_owner,
                   j.execution_lease_until           AS execution_lease_until,
                   j.active_attempt_id::text         AS active_attempt_id,
                   dl.replay_count                   AS replay_count,
                   dl.replayed                       AS replayed,
                   dl.moved_at                       AS dead_lettered_at
            FROM jobs j
            LEFT JOIN dead_letters dl ON dl.job_id = j.id
            WHERE j.created_at >= TIMESTAMPTZ '{start}'
              AND j.created_at <  TIMESTAMPTZ '{end}'
            """,
        ),
        ExtractSpec(
            "job_attempts",
            "job_attempts.started_at",
            f"""
            SELECT a.id::text                        AS attempt_id,
                   a.job_id::text                    AS job_id,
                   a.attempt_number                  AS attempt_number,
                   a.worker_id                       AS worker_id,
                   a.outcome                         AS outcome,
                   a.started_at                      AS started_at,
                   a.finished_at                     AS finished_at,
                   left(a.error_message, {TEXT_LIMIT}) AS error_message
            FROM job_attempts a
            WHERE a.started_at >= TIMESTAMPTZ '{start}'
              AND a.started_at <  TIMESTAMPTZ '{end}'
            """,
        ),
        ExtractSpec(
            "outbox_events",
            "outbox_events.created_at",
            f"""
            SELECT o.id::text                        AS event_id,
                   o.aggregate_type                  AS aggregate_type,
                   o.aggregate_id::text              AS aggregate_id,
                   o.event_type                      AS event_type,
                   o.status                          AS status,
                   o.created_at                      AS created_at,
                   o.available_at                    AS available_at,
                   o.published_at                    AS published_at,
                   o.terminal_failed_at              AS terminal_failed_at,
                   o.locked_until                    AS locked_until,
                   o.attempt_count                   AS attempt_count,
                   o.operator_retry_count            AS operator_retry_count,
                   o.last_operator_retry_at          AS last_operator_retry_at,
                   left(o.last_operator_reason, {TEXT_LIMIT}) AS last_operator_reason,
                   left(o.last_error, {TEXT_LIMIT})  AS last_error
            FROM outbox_events o
            WHERE o.created_at >= TIMESTAMPTZ '{start}'
              AND o.created_at <  TIMESTAMPTZ '{end}'
            """,
        ),
        ExtractSpec(
            "dead_letters",
            "dead_letters.moved_at",
            f"""
            SELECT d.job_id::text                    AS job_id,
                   j.type                            AS job_type,
                   j.priority                        AS priority,
                   d.moved_at                        AS moved_at,
                   d.replayed                        AS replayed,
                   d.replayed_at                     AS replayed_at,
                   d.replay_count                    AS replay_count,
                   j.attempt_count                   AS attempt_count,
                   j.max_attempts                    AS max_attempts,
                   j.status                          AS final_status,
                   j.scheduled_at                    AS scheduled_at,
                   j.created_at                      AS job_created_at
            FROM dead_letters d
            LEFT JOIN jobs j ON j.id = d.job_id
            WHERE d.moved_at >= TIMESTAMPTZ '{start}'
              AND d.moved_at <  TIMESTAMPTZ '{end}'
            """,
        ),
        ExtractSpec(
            "reliability_actions",
            "reliability_actions.created_at",
            f"""
            SELECT r.id::text                        AS action_id,
                   r.action_type                     AS action_type,
                   r.target_type                     AS target_type,
                   r.target_id::text                 AS target_id,
                   r.actor                           AS actor,
                   left(r.reason, {TEXT_LIMIT})      AS reason,
                   r.created_at                      AS created_at,
                   left(r.before_state, {TEXT_LIMIT}) AS before_state,
                   left(r.after_state, {TEXT_LIMIT}) AS after_state
            FROM reliability_actions r
            WHERE r.created_at >= TIMESTAMPTZ '{start}'
              AND r.created_at <  TIMESTAMPTZ '{end}'
            """,
        ),
        ExtractSpec(
            "job_effects",
            "job_effects.created_at",
            f"""
            SELECT e.effect_key                      AS effect_key,
                   e.job_id::text                    AS job_id,
                   e.attempt_number                  AS attempt_number,
                   e.effect_type                     AS effect_type,
                   e.status                          AS status,
                   e.response_hash                   AS response_hash,
                   e.created_at                      AS created_at,
                   e.completed_at                    AS completed_at,
                   left(e.error_message, {TEXT_LIMIT}) AS error_message,
                   j.type                            AS job_type,
                   j.priority                        AS priority,
                   j.attempt_count                   AS job_attempt_count
            FROM job_effects e
            LEFT JOIN jobs j ON j.id = e.job_id
            WHERE e.created_at >= TIMESTAMPTZ '{start}'
              AND e.created_at <  TIMESTAMPTZ '{end}'
            """,
        ),
        ExtractSpec(
            "idempotency_keys",
            "idempotency_keys.created_at",
            # The raw key is a client-supplied token and never leaves the database.
            # Hashing happens in SQL so the plaintext is not even in Spark's memory.
            f"""
            SELECT encode(sha256(convert_to(k.idempotency_key, 'UTF8')), 'hex')
                                                     AS idempotency_key_hash,
                   k.request_hash                    AS request_hash,
                   k.job_id::text                    AS job_id,
                   k.created_at                      AS created_at
            FROM idempotency_keys k
            WHERE k.created_at >= TIMESTAMPTZ '{start}'
              AND k.created_at <  TIMESTAMPTZ '{end}'
            """,
        ),
    ]


INCLUSION_TIMESTAMPS = {
    "jobs": "jobs.created_at",
    "job_attempts": "job_attempts.started_at",
    "outbox_events": "outbox_events.created_at",
    "dead_letters": "dead_letters.moved_at",
    "reliability_actions": "reliability_actions.created_at",
    "job_effects": "job_effects.created_at",
    "idempotency_keys": "idempotency_keys.created_at",
}


class SourceDatabase:
    """A read-only handle on the application database."""

    def __init__(self, spark: SparkSession, config: AnalyticsConfig) -> None:
        self.spark = spark
        self.config = config
        self._properties = config.jdbc_properties()

    def query(self, sql: str) -> DataFrame:
        return self.spark.read.jdbc(
            url=self.config.jdbc_url,
            table=f"({sql}) AS analytics_source",
            properties=self._properties,
        )

    def check_connection(self) -> dict[str, str]:
        try:
            row = self.query(
                "SELECT version() AS server_version, "
                "current_database() AS database_name, "
                "current_setting('TimeZone') AS server_timezone"
            ).collect()[0]
        except Exception as exc:  # py4j surfaces the driver's failure as a generic error
            raise DatabaseConnectionError(
                "Could not connect to the analytics source database at "
                f"{self.config.source_identifier}: {self.config.scrub(str(exc).splitlines()[0])}"
            ) from None
        return {
            "server_version": row["server_version"],
            "database_name": row["database_name"],
            "server_timezone": row["server_timezone"],
        }

    def require_tables(self, tables=REQUIRED_TABLES) -> None:
        names = ", ".join(f"'{name}'" for name in tables)
        try:
            present = {
                row["table_name"]
                for row in self.query(
                    "SELECT table_name FROM information_schema.tables "
                    f"WHERE table_schema = 'public' AND table_name IN ({names})"
                ).collect()
            }
        except Exception as exc:
            raise DatabaseConnectionError(
                "Could not read the table catalogue from "
                f"{self.config.source_identifier}: {self.config.scrub(str(exc).splitlines()[0])}"
            ) from None

        missing = [name for name in tables if name not in present]
        if missing:
            raise MissingSourceTableError(
                f"Required source table(s) missing from {self.config.source_identifier}: "
                f"{', '.join(missing)}. This database does not look like a DistroQ v0.8+ "
                "schema; check the JDBC URL and that Flyway has been run."
            )

    def schema_version(self) -> str | None:
        try:
            rows = self.query(
                "SELECT version FROM flyway_schema_history "
                "WHERE success ORDER BY installed_rank DESC LIMIT 1"
            ).collect()
        except Exception:
            return None
        return rows[0]["version"] if rows else None

    def flyway_fingerprint(self) -> dict[str, object]:
        """Migration state, captured so a rerun can prove it did not change it."""
        try:
            row = self.query(
                "SELECT count(*) AS migrations, "
                "coalesce(sum(coalesce(checksum, 0))::bigint, 0) AS checksum_total, "
                "max(installed_rank) AS max_rank "
                "FROM flyway_schema_history"
            ).collect()[0]
        except Exception:
            return {}
        return {
            "migrations": int(row["migrations"]),
            "checksum_total": int(row["checksum_total"]),
            "max_rank": int(row["max_rank"]) if row["max_rank"] is not None else None,
        }

    def effect_counter_snapshot(self) -> list[dict[str, object]]:
        """Point-in-time only.

        ``effect_counters`` is a running total with no history, so it cannot be
        windowed and is not a fact table. It is recorded in metadata, labelled as a
        snapshot, so a reader is never tempted to treat it as a time series.
        """
        try:
            rows = self.query(
                "SELECT counter_name, counter_value, updated_at FROM effect_counters "
                "ORDER BY counter_name"
            ).collect()
        except Exception:
            return []
        return [
            {
                "counter_name": row["counter_name"],
                "counter_value": int(row["counter_value"]),
                "updated_at": row["updated_at"].isoformat() if row["updated_at"] else None,
            }
            for row in rows
        ]

    def unwindowable_attempts(self) -> int:
        """Attempt rows with no ``started_at``, which no window can ever select."""
        try:
            return int(
                self.query(
                    "SELECT count(*) AS c FROM job_attempts WHERE started_at IS NULL"
                ).collect()[0]["c"]
            )
        except Exception:
            return 0

    def row_census(self) -> dict[str, int]:
        """Whole-table counts, used to prove the export changed nothing."""
        parts = " UNION ALL ".join(
            f"SELECT '{table}' AS t, count(*) AS c FROM {table}"
            for table in REQUIRED_TABLES
        )
        try:
            return {
                row["t"]: int(row["c"])
                for row in self.query(f"SELECT t, c FROM ({parts}) census ORDER BY t").collect()
            }
        except Exception as exc:
            raise DatabaseConnectionError(
                f"Could not take a row census: {self.config.scrub(str(exc).splitlines()[0])}"
            ) from None


def extract_all(source: SourceDatabase, window: TimeWindow, run_dir: Path) -> dict[str, dict]:
    """Extract every source dataset for the window and write it under ``run_dir``."""
    from .storage import write_dataset

    results: dict[str, dict] = {}
    for spec in _specs(window):
        started = time.perf_counter()
        try:
            frame = conform(source.query(spec.sql), SOURCE_SCHEMAS[spec.name])
            rows = write_dataset(frame, run_dir, spec.name)
        except Exception as exc:
            raise ExtractionError(
                f"Extraction of {spec.name} failed: "
                f"{source.config.scrub(str(exc).splitlines()[0])}"
            ) from None
        elapsed = time.perf_counter() - started
        results[spec.name] = {
            "rows": rows,
            "inclusion_timestamp": spec.inclusion_column,
            "duration_seconds": round(elapsed, 3),
        }
        log.info("extracted %-20s %8d rows in %.2fs", spec.name, rows, elapsed)
    return results
