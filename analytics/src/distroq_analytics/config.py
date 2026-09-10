"""Analytics configuration, resolved from CLI arguments then environment.

Credentials are never defaulted into source, never written into the JDBC URL, and
never rendered by :meth:`AnalyticsConfig.describe`. :func:`redact` is applied to
anything that is about to be logged or put in an exception message.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from .errors import AnalyticsError, EXIT_USAGE

ENV_DB_URL = "DISTROQ_ANALYTICS_DB_URL"
ENV_DB_USER = "DISTROQ_ANALYTICS_DB_USER"
ENV_DB_PASSWORD = "DISTROQ_ANALYTICS_DB_PASSWORD"
ENV_OUTPUT_DIR = "DISTROQ_ANALYTICS_OUTPUT_DIR"
ENV_SPARK_MASTER = "DISTROQ_ANALYTICS_SPARK_MASTER"
ENV_LOG_LEVEL = "DISTROQ_ANALYTICS_LOG_LEVEL"
ENV_JDBC_JAR = "DISTROQ_ANALYTICS_JDBC_JAR"

DEFAULT_DB_URL = "jdbc:postgresql://localhost:5433/distroq"
DEFAULT_OUTPUT_DIR = "analytics/output"
DEFAULT_SPARK_MASTER = "local[*]"
DEFAULT_LOG_LEVEL = "WARN"

#: Matches ``distroq.effects.stale-started-after-ms`` in application.yml.
DEFAULT_EFFECT_STALE_AFTER_MS = 300_000


def redact(text: str, *secrets: str | None) -> str:
    """Blank out any occurrence of a secret in text that is about to escape."""
    cleaned = text
    for secret in secrets:
        if secret:
            cleaned = cleaned.replace(secret, "***")
    return cleaned


@dataclass(frozen=True)
class AnalyticsConfig:
    jdbc_url: str
    db_user: str | None
    db_password: str | None = field(repr=False, default=None)
    output_dir: Path = Path(DEFAULT_OUTPUT_DIR)
    spark_master: str = DEFAULT_SPARK_MASTER
    log_level: str = DEFAULT_LOG_LEVEL
    jdbc_jar: str | None = None
    fetch_size: int = 5_000
    effect_stale_after_ms: int = DEFAULT_EFFECT_STALE_AFTER_MS

    def __repr__(self) -> str:  # never let a password reach a traceback
        return (
            f"AnalyticsConfig(jdbc_url={self.jdbc_url!r}, db_user={self.db_user!r}, "
            f"db_password=***, output_dir={str(self.output_dir)!r}, "
            f"spark_master={self.spark_master!r})"
        )

    @classmethod
    def resolve(cls, args) -> "AnalyticsConfig":
        jdbc_url = getattr(args, "jdbc_url", None) or os.environ.get(ENV_DB_URL) or DEFAULT_DB_URL
        db_user = getattr(args, "db_user", None) or os.environ.get(ENV_DB_USER)
        db_password = getattr(args, "db_password", None) or os.environ.get(ENV_DB_PASSWORD)
        output_dir = getattr(args, "output", None) or os.environ.get(ENV_OUTPUT_DIR) or DEFAULT_OUTPUT_DIR
        spark_master = (
            getattr(args, "spark_master", None)
            or os.environ.get(ENV_SPARK_MASTER)
            or DEFAULT_SPARK_MASTER
        )
        log_level = (
            getattr(args, "log_level", None) or os.environ.get(ENV_LOG_LEVEL) or DEFAULT_LOG_LEVEL
        ).upper()

        if not jdbc_url.startswith("jdbc:postgresql://"):
            raise AnalyticsError(
                f"jdbc-url must be a PostgreSQL JDBC URL, got {jdbc_url!r}", EXIT_USAGE
            )

        return cls(
            jdbc_url=jdbc_url,
            db_user=db_user,
            db_password=db_password,
            output_dir=Path(output_dir),
            spark_master=spark_master,
            log_level=log_level,
            jdbc_jar=os.environ.get(ENV_JDBC_JAR),
            effect_stale_after_ms=getattr(
                args, "effect_stale_after_ms", None
            ) or DEFAULT_EFFECT_STALE_AFTER_MS,
        )

    @property
    def source_identifier(self) -> str:
        """Host/port/database, with any embedded credentials stripped."""
        stripped = self.jdbc_url.removeprefix("jdbc:")
        if "?" in stripped:
            stripped = stripped.split("?", 1)[0]
        if "@" in stripped:
            scheme, _, rest = stripped.partition("//")
            stripped = f"{scheme}//{rest.rpartition('@')[2]}"
        return stripped

    def jdbc_properties(self) -> dict[str, str]:
        """Connection properties, pinned read-only at the driver level.

        ``readOnlyMode=always`` makes the PostgreSQL driver refuse a write on this
        connection even if some future code path tried one. The read-only guarantee
        is therefore enforced by the driver, not only by the shape of the SQL.
        """
        properties = {
            "driver": "org.postgresql.Driver",
            "readOnly": "true",
            "readOnlyMode": "always",
            "ApplicationName": "distroq-analytics",
            "fetchsize": str(self.fetch_size),
        }
        if self.db_user:
            properties["user"] = self.db_user
        if self.db_password:
            properties["password"] = self.db_password
        return properties

    def describe(self) -> dict[str, object]:
        return {
            "source": self.source_identifier,
            "db_user": self.db_user,
            "output_dir": str(self.output_dir),
            "spark_master": self.spark_master,
            "log_level": self.log_level,
            "read_only": True,
        }

    def scrub(self, text: str) -> str:
        return redact(text, self.db_password)
