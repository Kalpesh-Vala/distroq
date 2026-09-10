# Reproducible analytics runtime.
#
# The pipeline is containerised for two reasons, not one:
#
#  1. It pins the interpreter and the Spark version. PySpark's wheel metadata claims
#     Python >=3.9, but the range that actually works is narrower, and "whatever python
#     was on PATH" is not a reproducible environment.
#  2. Spark writes Parquet through Hadoop's local filesystem, which on Windows requires
#     winutils.exe and hadoop.dll from a Hadoop binary distribution. Rather than ask
#     every developer to install unsigned native binaries from a third party, the
#     pipeline runs on Linux where Hadoop needs nothing extra.
#
# The DistroQ application itself is unchanged: no Spark dependency was added to the
# Maven build, and this image never runs application code.

FROM python:3.12-slim-bookworm

# Spark 4.0 requires Java 17 or 21 and dropped support for 8 and 11. The application
# targets Java 21; 17 is used here because it is what Debian bookworm packages, and
# Spark supports both.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless \
        curl \
        ca-certificates \
        procps \
    && rm -rf /var/lib/apt/lists/*

# The PostgreSQL JDBC driver, pinned by version and verified by digest. No Python
# package provides it and Spark needs it on the JVM classpath.
ARG JDBC_VERSION=42.7.5
ARG JDBC_SHA256=69020b3bd20984543e817393f2e6c01a890ef2e37a77dd11d6d8508181d079ab
RUN curl -fsSL -o /opt/postgresql.jar \
        "https://repo1.maven.org/maven2/org/postgresql/postgresql/${JDBC_VERSION}/postgresql-${JDBC_VERSION}.jar" \
    && echo "${JDBC_SHA256}  /opt/postgresql.jar" | sha256sum -c -

ENV DISTROQ_ANALYTICS_JDBC_JAR=/opt/postgresql.jar \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /opt/distroq-analytics

# Dependencies first so a source edit does not re-download 400MB of Spark.
COPY requirements.txt requirements-dev.txt ./
RUN pip install --no-cache-dir -r requirements-dev.txt

COPY pyproject.toml ./
COPY src ./src
COPY tests ./tests
RUN pip install --no-cache-dir -e .

# /workspace is the repository root when mounted by docker compose, which is what puts
# output under analytics/output on the host and lets pom.xml supply the app version.
WORKDIR /workspace
ENTRYPOINT ["python", "-m", "distroq_analytics.cli"]
CMD ["--help"]
