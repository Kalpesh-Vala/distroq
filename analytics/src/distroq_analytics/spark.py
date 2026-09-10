"""SparkSession construction for the analytics pipeline.

Two settings here are load-bearing rather than tuning:

* ``spark.sql.session.timeZone`` is pinned to UTC. Every ``to_date`` and every day
  bucket in the aggregate reports derives from it, so leaving it at the JVM default
  would make daily boundaries depend on where the export ran.
* ``spark.sql.parquet.outputTimestampType`` is pinned to microsecond timestamps so
  the written schema is stable across runs and readers.
"""

from __future__ import annotations

import logging
import os
import sys
from pathlib import Path

from .config import AnalyticsConfig

log = logging.getLogger(__name__)

#: Fallback coordinate if no jar is found locally. Requires network access on first use.
JDBC_PACKAGE = "org.postgresql:postgresql:42.7.5"

_MAVEN_JDBC_DIR = Path.home() / ".m2" / "repository" / "org" / "postgresql" / "postgresql"


def find_jdbc_jar(explicit: str | None = None) -> str | None:
    """Locate the PostgreSQL JDBC driver without downloading it if we can avoid it."""
    if explicit:
        candidate = Path(explicit)
        if not candidate.is_file():
            raise FileNotFoundError(f"JDBC jar not found at {candidate}")
        return str(candidate.resolve())

    if not _MAVEN_JDBC_DIR.is_dir():
        return None

    jars = sorted(
        (jar for jar in _MAVEN_JDBC_DIR.rglob("postgresql-*.jar") if "sources" not in jar.name),
        key=lambda jar: _version_key(jar.parent.name),
    )
    return str(jars[-1].resolve()) if jars else None


def _version_key(version: str) -> tuple[int, ...]:
    parts = []
    for piece in version.split("."):
        digits = "".join(ch for ch in piece if ch.isdigit())
        parts.append(int(digits) if digits else 0)
    return tuple(parts)


def build_session(config: AnalyticsConfig, app_name: str = "distroq-analytics", *, jdbc: bool = True):
    from pyspark.sql import SparkSession

    # The driver and the workers must be the same interpreter; on Windows the launcher
    # otherwise picks whatever `python` is first on PATH.
    os.environ.setdefault("PYSPARK_PYTHON", sys.executable)
    os.environ.setdefault("PYSPARK_DRIVER_PYTHON", sys.executable)

    builder = (
        SparkSession.builder.master(config.spark_master)
        .appName(app_name)
        .config("spark.sql.session.timeZone", "UTC")
        .config("spark.sql.parquet.outputTimestampType", "TIMESTAMP_MICROS")
        .config("spark.sql.parquet.compression.codec", "snappy")
        .config("spark.sql.shuffle.partitions", "8")
        .config("spark.sql.adaptive.enabled", "true")
        .config("spark.ui.showConsoleProgress", "false")
        .config("spark.ui.enabled", "false")
    )

    if jdbc:
        jar = find_jdbc_jar(config.jdbc_jar)
        if jar:
            log.debug("Using local JDBC driver %s", jar)
            builder = builder.config("spark.jars", jar).config("spark.driver.extraClassPath", jar)
        else:
            log.warning(
                "No local PostgreSQL JDBC jar found; resolving %s from Maven Central",
                JDBC_PACKAGE,
            )
            builder = builder.config("spark.jars.packages", JDBC_PACKAGE)

    session = builder.getOrCreate()
    session.sparkContext.setLogLevel(config.log_level)
    return session
