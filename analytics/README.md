# DistroQ analytics (v0.9)

Read-only historical analytics over the DistroQ application database.

```text
Application tables
  -> read-only JDBC extraction
  -> immutable Parquet facts
  -> PySpark transformations
  -> aggregate reports
  -> optional read-only API      (not implemented in v0.9)
```

Nothing in this package writes to `jobs`, `job_attempts`, `outbox_events`, `dead_letters`,
`reliability_actions`, `job_effects`, `effect_counters`, `idempotency_keys`,
`flyway_schema_history`, or Redis. The JDBC connection is opened with
`readOnlyMode=always`, so a write would be refused by the PostgreSQL driver even if some
future code path attempted one.

See the v0.9 section of the repository `README.md` for the full narrative and
`NOTES.md` for the design arguments. This file is the operator's reference.

---

## Environment

| Component | Pinned to | Why |
|---|---|---|
| Python | 3.12 (`>=3.10,<3.13`) | PySpark's wheel says `>=3.9`; the Python *worker* process does not start on 3.14/3.15 |
| PySpark | 4.0.1 | see `requirements.txt` |
| Java | 17 or 21 | Spark 4.0 dropped 8 and 11 and does not support 22+ |
| PostgreSQL JDBC | 42.7.5, SHA-256 pinned | needed on the JVM classpath; no Python package supplies it |

### Docker (recommended, and the only supported path on Windows)

```powershell
docker compose --profile analytics build analytics
docker compose --profile analytics run --rm analytics --help
```

The `analytics` profile keeps this service out of a plain `docker compose up`. The
container joins the compose network and reaches PostgreSQL at `postgres:5432`, not the
host's `5433`.

**Windows requires the container.** Spark writes Parquet through Hadoop's local
filesystem, which on Windows needs `winutils.exe` and `hadoop.dll` from a Hadoop binary
distribution. Without them, every write fails with:

```text
java.io.FileNotFoundException: HADOOP_HOME and hadoop.home.dir are unset
```

Installing unsigned native binaries from a third-party mirror is not a dependency this
project is willing to take, so the pipeline runs on Linux inside the image instead.

### Local virtual environment (Linux and macOS)

```bash
python3.12 -m venv analytics/.venv
analytics/.venv/bin/pip install -r analytics/requirements-dev.txt
analytics/.venv/bin/pip install -e analytics
```

The PostgreSQL driver is located automatically from `~/.m2/repository` (the Maven build
already downloads it). Override with `DISTROQ_ANALYTICS_JDBC_JAR`, or let Spark resolve
`org.postgresql:postgresql:42.7.5` from Maven Central as a fallback.

---

## Configuration

Every setting has a CLI flag and an environment variable. The flag wins.

| Variable | Flag | Default |
|---|---|---|
| `DISTROQ_ANALYTICS_DB_URL` | `--jdbc-url` | `jdbc:postgresql://localhost:5433/distroq` |
| `DISTROQ_ANALYTICS_DB_USER` | `--db-user` | unset |
| `DISTROQ_ANALYTICS_DB_PASSWORD` | `--db-password` | unset |
| `DISTROQ_ANALYTICS_OUTPUT_DIR` | `--output` | `analytics/output` |
| `DISTROQ_ANALYTICS_SPARK_MASTER` | `--spark-master` | `local[*]` |
| `DISTROQ_ANALYTICS_LOG_LEVEL` | `--log-level` | `WARN` |
| `DISTROQ_ANALYTICS_JDBC_JAR` | — | auto-detected |

Credentials are never committed, never written into the JDBC URL, never printed, and
stripped from any driver error text before it reaches a log or an exception message.
Prefer the environment variable over `--db-password`: an argument is visible in the
process list to every user on the host.

---

## Commands

### `export` — the only stage that touches the database

```powershell
docker compose --profile analytics run --rm analytics export `
  --start 2026-01-01T00:00:00Z `
  --end   2027-01-01T00:00:00Z
```

Extracts every source table for the window, writes the raw extracts and the derived fact
tables as Parquet, and records a metadata document describing exactly what it did.

### `report` — aggregates, no database access

```powershell
docker compose --profile analytics run --rm analytics report `
  --input /workspace/analytics/output/<run-id>
```

Defaults to `<run-id>/reports`.

### `quality` — data-quality checks, no database access

```powershell
docker compose --profile analytics run --rm analytics quality `
  --input /workspace/analytics/output/<run-id>
```

Defaults to `<run-id>/quality`. Add `--fail-on error` or `--fail-on warning` to make
findings fatal; the default reports without enforcing.

### Common flags

`--overwrite`, `--spark-master`, `--log-level`, `--no-csv`, `--json`.

---

## Time windows

Every command is scoped by an explicit UTC half-open interval `[start, end)`.

* An offset is **mandatory**. `2026-09-01T00:00:00` is rejected, `2026-09-01T00:00:00Z`
  and `2026-09-01T02:00:00+02:00` are accepted and are the same instant.
* `end` belongs to the next window, so consecutive windows tile the timeline exactly once.
* Grouping is UTC everywhere. On the machine this was validated on, 19 of 95 jobs would
  land on a different calendar day under the host's local zone.

Which timestamp decides inclusion:

| Dataset | Inclusion timestamp |
|---|---|
| `jobs` | `created_at` |
| `job_attempts` | `started_at` |
| `outbox_events` | `created_at` |
| `dead_letters` | `moved_at` |
| `reliability_actions` | `created_at` |
| `job_effects` | `created_at` |
| `idempotency_keys` | `created_at` |

A job created before the window but completed inside it is **not** a submission in that
window. It may still appear in `attempt_facts` if one of its attempts started in range.

---

## Output layout

```text
analytics/output/<run-id>/
  jobs/  job_attempts/  outbox_events/  dead_letters/
  reliability_actions/  job_effects/  idempotency_keys/     raw extracts
  facts/
    job_facts/  attempt_facts/  outbox_facts/  dead_letter_facts/
    reliability_action_facts/  effect_facts/  idempotency_facts/
  metadata/
    export_metadata.json
  reports/                                                   written by `report`
    daily_job_summary/  job_type_summary/  priority_summary/
    outbox_summary/  reliability_summary/  effect_summary/
    data_quality_summary/  *.csv  report_summary.json
  quality/                                                   written by `quality`
    data_quality_summary/  data_quality_summary.csv  quality_summary.json
```

The run ID is **derived from the window**, not random:

```text
20260101T000000Z__20270101T000000Z
```

That is what makes a rerun of the same window collide with its own previous output
instead of quietly producing a second copy of it.

`effect_counters` has no per-row history — it is a running total — so it is not a fact
table. It is captured in `export_metadata.json` as a point-in-time snapshot, labelled
as one.

---

## Rerun and overwrite

Running the same window twice produces the same numbers. An existing, non-empty
destination is an error:

```text
error [OUTPUT_EXISTS]: export run directory already exists at ... and is not empty.
Analytics exports are immutable snapshots, so this is refused rather than merged.
Pass --overwrite to replace this directory, or choose a different --output.
```

`--overwrite` removes exactly the one directory it was pointed at, never a sibling run.

**One metric is deliberately not reproducible**: `age_at_export_ms`, and the
`oldest_unpublished_age_ms` aggregate derived from it. An unpublished event really does
get older between two exports. Every count, latency and percentile is byte-identical
across reruns; that one column moves by the elapsed time and nothing else.

`report` and `quality` take "now" from `export_metadata.json` rather than the wall
clock, so re-reporting last month's export tomorrow produces the numbers it produced
the day it was taken.

---

## Datasets

### Facts

| Dataset | Grain |
|---|---|
| `job_facts` | one row per job |
| `attempt_facts` | one row per execution attempt |
| `outbox_facts` | one row per outbox event |
| `dead_letter_facts` | one row per DLQ record |
| `reliability_action_facts` | one row per operator or reconciliation action |
| `effect_facts` | one row per protected logical effect |
| `idempotency_facts` | one row per HTTP submission idempotency key |

Derived-value rules:

* A derived value is **null when its inputs are null**. Nothing invents a timestamp and
  nothing substitutes `now()` for a missing `finished_at`.
* A negative duration is **preserved, not clamped**. It is evidence of a clock or
  ordering problem, and the data-quality report is where it gets named.
* `queue_delay_ms` and `schedule_delay_ms` are mutually exclusive by construction. A
  scheduled job did not queue; it waited for a time a user chose.
* `publication_latency_ms` is null for anything not published. A zero would drag every
  average toward a value nothing measured.

Two caveats inherited from the application schema, not introduced here:

1. `jobs.started_at` is overwritten by every attempt (`Job.markRunning`), so
   `duration_ms` measures the **final** attempt and `queue_delay_ms` includes retry
   backoff for a retried job. `first_attempt_started_at` and `first_queue_delay_ms` are
   derived from `job_attempts` alongside them when true admission delay is what matters.
2. A deduplication hit is a Micrometer counter in the application, not a row, so it
   cannot be extracted. It is durably *implied*: the counter effect key deliberately
   excludes the attempt number, so a `COMPLETED` effect claimed on attempt N belonging to
   a job that ran M attempts means every attempt after N found the key already complete.
   `deduplication_hits = max(0, job_attempt_count - attempt_number)` is therefore a
   **lower bound**, not a total.

### Sensitive data

Job payloads, outbox payloads and raw idempotency keys are **never exported**. The raw
idempotency key is hashed to SHA-256 *inside PostgreSQL*, so the plaintext never reaches
Spark's memory. Error text and operator reasons are truncated to 500 characters at the
source.

### Aggregates

`daily_job_summary`, `job_type_summary`, `priority_summary`, `outbox_summary`,
`reliability_summary`, `effect_summary`, `data_quality_summary`.

* `failed_jobs` counts the legacy terminal `FAILED` status only. Exhausted retries are
  `dead_lettered_jobs`.
* `pending_events` counts `PENDING` **and** `PUBLISHING` — everything not published and
  not terminal — with `publishing_events` reported separately, because a stuck relay
  lease is an operational signal a plain backlog is not.
* `success_rate` is **null**, not `0.0`, when there were no submissions. A dashboard that
  renders an undefined rate as 0% is lying about a quiet day.
* Percentiles use Spark's **exact** `percentile`, not `percentile_approx`. An error bound
  on four rows is not a number to put in an operational report.
* Averages are rounded to three decimals so that a rerun is comparable: floating-point
  addition is not associative and partition order is not something this pipeline promises.

---

## Data quality

19 checks, each emitting a row **even at zero** — a report where a check is absent is
indistinguishable from one where the check did not run.

Each finding carries `check_name`, `severity`, `count`, `sample_ids`, `description`.
Sample IDs are bounded (10 by default, `--sample-limit`) and sorted, so a rerun produces
identical samples.

Severity is `ERROR`, `WARNING` or `INFO`. Checks that are sensitive to the window edge —
a job created just before `end` whose attempts fall in the next window — are `WARNING`
by construction, not because they matter less.

Three checks exist because the schema deliberately carries **no CHECK constraint** over
its enum columns (see `V1__initial_schema.sql`): `jobs_invalid_status`,
`attempts_invalid_outcome`, `outbox_invalid_status`. The application enum is the source
of truth; analytics validates rather than assumes.

**Analytics reports and never repairs.** Reconciliation already exists in the application
and is the only thing allowed to change state; an analytics job that "fixed" a row would
be a second, unaudited writer racing it.

---

## Exit codes

| Code | Name | Meaning |
|---|---|---|
| 0 | OK | |
| 1 | USAGE | bad arguments, non-PostgreSQL JDBC URL, interrupted |
| 2 | INVALID_WINDOW | naive timestamp, unparseable instant, `start >= end` |
| 3 | DB_CONNECTION | could not connect or read the catalogue |
| 4 | MISSING_TABLE | a required source table is absent |
| 5 | EXTRACTION_FAILED | a source extract failed |
| 6 | TRANSFORM_FAILED | fact or aggregate construction failed |
| 7 | OUTPUT_EXISTS | destination exists and `--overwrite` was not given |
| 8 | QUALITY_FATAL | findings reached the `--fail-on` severity |
| 9 | MISSING_INPUT | `--input` is not an export run directory |

The window is validated **before** Spark starts or any connection is attempted, so a bad
window and an unreachable database cannot be confused for one another.

---

## Tests

```powershell
docker compose --profile analytics run --rm --entrypoint python analytics `
  -m pytest /opt/distroq-analytics/tests -q
```

159 tests covering window semantics, fact transformations, aggregations, percentiles,
data-quality detection, rerun behaviour, exit codes and credential handling. The suite
takes several minutes: it exercises real Spark jobs rather than mocks, and Spark's
per-query overhead dominates at this data size.

---

## Scaling limitations

Validated against 95 jobs, 133 attempts and 30 outbox events: extraction 10.2s,
transformation 11.1s, 186 KiB of output. **That is a correctness result, not a
performance result.** Do not read production-scale conclusions from it.

Known limits:

* Extraction is single-partition JDBC. Each table is one `SELECT` over one connection,
  which is the right shape for tens of thousands of rows and the wrong shape for tens of
  millions. `spark.read.jdbc` supports partitioned reads on a numeric or timestamp
  column; adding `partitionColumn`/`lowerBound`/`upperBound`/`numPartitions` is the first
  change to make when a window stops fitting.
* Output is `coalesce(1)`. Deliberate at this size — a stable single file keeps a rerun
  comparable — and wrong above roughly a gigabyte per dataset.
* `local[*]` runs everything in one JVM. Driver memory is the ceiling.
* `--start`/`--end` are the only backpressure. A window that does not fit should be split
  into several; consecutive half-open windows tile exactly, so this is safe by design.
