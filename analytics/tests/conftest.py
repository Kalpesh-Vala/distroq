"""Shared pytest fixtures.

One SparkSession is shared by the whole test session. Starting a JVM per test is slow
enough to discourage running the suite, and a suite nobody runs is not a suite.
"""

from __future__ import annotations

import pytest

from distroq_analytics.config import AnalyticsConfig
from distroq_analytics.spark import build_session


@pytest.fixture(scope="session")
def spark():
    config = AnalyticsConfig(
        jdbc_url="jdbc:postgresql://unused.invalid:5432/none",
        db_user=None,
        db_password=None,
        spark_master="local[2]",
        log_level="ERROR",
    )
    session = build_session(config, app_name="distroq-analytics-tests", jdbc=False)
    yield session
    session.stop()
