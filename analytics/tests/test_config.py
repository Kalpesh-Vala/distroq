from __future__ import annotations

import argparse

import pytest

from distroq_analytics import config as config_module
from distroq_analytics.cli import build_parser, main
from distroq_analytics.config import AnalyticsConfig, redact
from distroq_analytics.errors import EXIT_INVALID_WINDOW, EXIT_USAGE, AnalyticsError

SECRET = "s3cr3t-not-in-logs"


def args(**overrides) -> argparse.Namespace:
    base = {
        "jdbc_url": None, "db_user": None, "db_password": None, "output": None,
        "spark_master": None, "log_level": None,
    }
    base.update(overrides)
    return argparse.Namespace(**base)


class TestCredentialHandling:
    def test_password_is_not_in_repr(self):
        config = AnalyticsConfig.resolve(args(db_password=SECRET))
        assert SECRET not in repr(config)
        assert "***" in repr(config)

    def test_password_is_not_in_describe(self):
        config = AnalyticsConfig.resolve(args(db_password=SECRET))
        assert SECRET not in str(config.describe())

    def test_scrub_removes_the_password_from_driver_text(self):
        config = AnalyticsConfig.resolve(args(db_password=SECRET))
        message = f"FATAL: password authentication failed, tried {SECRET}"
        assert SECRET not in config.scrub(message)

    def test_redact_is_a_no_op_without_a_secret(self):
        assert redact("nothing to hide") == "nothing to hide"

    def test_source_identifier_strips_embedded_credentials(self):
        config = AnalyticsConfig.resolve(
            args(jdbc_url="jdbc:postgresql://user:pw@db.example:5432/distroq")
        )
        assert "pw" not in config.source_identifier
        assert config.source_identifier == "postgresql://db.example:5432/distroq"

    def test_password_travels_as_a_connection_property_not_in_the_url(self):
        config = AnalyticsConfig.resolve(args(db_password=SECRET, db_user="distroq"))
        assert SECRET not in config.jdbc_url
        assert config.jdbc_properties()["password"] == SECRET


class TestReadOnlyProperties:
    def test_driver_is_pinned_read_only(self):
        properties = AnalyticsConfig.resolve(args()).jdbc_properties()
        assert properties["readOnly"] == "true"
        assert properties["readOnlyMode"] == "always"

    def test_application_name_identifies_analytics_in_pg_stat_activity(self):
        assert AnalyticsConfig.resolve(args()).jdbc_properties()["ApplicationName"] == (
            "distroq-analytics"
        )


class TestEnvironmentResolution:
    def test_environment_supplies_defaults(self, monkeypatch):
        monkeypatch.setenv(config_module.ENV_DB_URL, "jdbc:postgresql://envhost:5432/db")
        monkeypatch.setenv(config_module.ENV_DB_USER, "env-user")
        monkeypatch.setenv(config_module.ENV_OUTPUT_DIR, "/tmp/env-output")
        monkeypatch.setenv(config_module.ENV_SPARK_MASTER, "local[3]")
        config = AnalyticsConfig.resolve(args())
        assert config.jdbc_url == "jdbc:postgresql://envhost:5432/db"
        assert config.db_user == "env-user"
        assert str(config.output_dir) in ("/tmp/env-output", "\\tmp\\env-output")
        assert config.spark_master == "local[3]"

    def test_arguments_win_over_environment(self, monkeypatch):
        monkeypatch.setenv(config_module.ENV_DB_USER, "env-user")
        assert AnalyticsConfig.resolve(args(db_user="arg-user")).db_user == "arg-user"

    def test_non_postgres_url_is_rejected(self):
        with pytest.raises(AnalyticsError) as excinfo:
            AnalyticsConfig.resolve(args(jdbc_url="jdbc:mysql://host/db"))
        assert excinfo.value.exit_code == EXIT_USAGE


class TestCliContract:
    def test_commands_are_registered(self):
        parser = build_parser()
        for command in ("export", "report", "quality"):
            assert parser.parse_args([command, *_minimal(command)]).command == command

    def test_export_requires_a_window(self):
        with pytest.raises(SystemExit):
            build_parser().parse_args(["export"])

    def test_report_requires_an_input(self):
        with pytest.raises(SystemExit):
            build_parser().parse_args(["report"])

    def test_overwrite_defaults_to_false(self):
        parsed = build_parser().parse_args(["export", *_minimal("export")])
        assert parsed.overwrite is False


def _minimal(command: str) -> list[str]:
    if command == "export":
        return ["--start", "2026-09-01T00:00:00Z", "--end", "2026-10-01T00:00:00Z"]
    return ["--input", "analytics/output/run"]


class TestExitCodes:
    def test_naive_start_exits_with_the_window_code(self, capsys):
        code = main(["export", "--start", "2026-09-01T00:00:00",
                     "--end", "2026-10-01T00:00:00Z"])
        assert code == EXIT_INVALID_WINDOW
        assert "offset" in capsys.readouterr().err

    def test_start_equal_to_end_exits_with_the_window_code(self, capsys):
        code = main(["export", "--start", "2026-09-01T00:00:00Z",
                     "--end", "2026-09-01T00:00:00Z"])
        assert code == EXIT_INVALID_WINDOW
        assert "strictly before" in capsys.readouterr().err

    def test_start_after_end_exits_with_the_window_code(self):
        assert main(["export", "--start", "2026-10-01T00:00:00Z",
                     "--end", "2026-09-01T00:00:00Z"]) == EXIT_INVALID_WINDOW

    def test_bad_jdbc_scheme_exits_with_the_usage_code(self):
        assert main(["export", "--start", "2026-09-01T00:00:00Z",
                     "--end", "2026-10-01T00:00:00Z",
                     "--jdbc-url", "postgres://nope"]) == EXIT_USAGE

    def test_window_validation_happens_before_any_connection_attempt(self, capsys):
        # An unreachable database must not change the exit code for a bad window: the
        # window is rejected before Spark or JDBC is touched at all.
        code = main(["export", "--start", "2026-09-01T00:00:00",
                     "--end", "2026-10-01T00:00:00Z",
                     "--jdbc-url", "jdbc:postgresql://unreachable.invalid:1/none"])
        assert code == EXIT_INVALID_WINDOW

    def test_password_never_appears_in_error_output(self, capsys):
        main(["export", "--start", "bad", "--end", "2026-10-01T00:00:00Z",
              "--db-password", SECRET])
        captured = capsys.readouterr()
        assert SECRET not in captured.err + captured.out
