# DistroQ v1.1

v1.1 adds a read-only operations dashboard over the queue, worker, outbox, reconciliation, job,
DLQ, analytics, and runtime state that DistroQ already owns. The UI is served at `/dashboard/`;
its backend-for-frontend is under `/api/dashboard/**` and requires the same administrative bearer
token as `/api/admin/**`.

The dashboard is deliberately not an administrative console. Its API has GET routes only, a
servlet filter rejects every other HTTP method with `405 Method Not Allowed`, and its browser
bundle contains no retry, replay, repair, cleanup, or other mutation call. Those actions remain on
the audited administrative API, where `X-Admin-Reason` and `reliability_actions` still apply.

## Dashboard

The available views are Overview, Queues, Workers, Outbox, Reconciliation, Jobs, Job Detail, DLQ,
Analytics, and System. Each independently loaded section reports one of `AVAILABLE`,
`UNAVAILABLE`, or `NOT_CONFIGURED`; a PostgreSQL, Redis, health, or analytics failure therefore
does not turn unknown data into zero or blank the rest of the page. Previously loaded data remains
visible and marked stale while polling backs off.

The browser prompts for the administrative token at runtime, keeps it in `sessionStorage`, and
sends it only as `Authorization: Bearer ...` to `/api/dashboard/**`. The static bundle is public
and contains no token or environment-derived credential. `sessionStorage` limits persistence, but
it does not protect a token from script executing in the same origin. Production deployments
should put the dashboard behind an identity-aware proxy or SSO and restrict who can reach it; see
`SECURITY.md`.

### Build and run

The frontend and backend are intentionally separate builds. Maven does not download Node or run
npm, and Vite does not package the Java application.

```powershell
cd dashboard
npm ci
npm test
npm run build
cd ..

.\mvnw.cmd clean package
java -jar target\distroq-1.1.0.jar
```

By default Spring serves `dashboard/dist` at `/dashboard/`, including SPA fallbacks such as
`/dashboard/jobs`. For a deployment that keeps the bundle elsewhere, set
`DISTROQ_DASHBOARD_STATIC_PATH` to the directory containing `index.html`. To ship the UI inside the
fat jar, copy the built files to `src/main/resources/static/dashboard/` before running Maven.

The dashboard API routes are:

```text
GET /api/dashboard/overview       GET /api/dashboard/queues
GET /api/dashboard/workers        GET /api/dashboard/outbox
GET /api/dashboard/reconciliation GET /api/dashboard/jobs
GET /api/dashboard/jobs/{jobId}   GET /api/dashboard/dlq
GET /api/dashboard/analytics      GET /api/dashboard/system
GET /api/dashboard/activity
```

Analytics is not recomputed by the application. The Analytics view reads existing v0.9 export
reports beneath `distroq.dashboard.analytics-directory` (default `analytics/output`) and identifies
the selected run and UTC window. With no export, it reports `NOT_CONFIGURED`; it never invents an
empty report and never starts Spark or writes analytics output.

The polling, page-size, cache, recent-window, analytics-directory, and static-path budgets are
under `distroq.dashboard` in `application.yml`. Requested table pages are capped server-side at
`max-page-size`.

## Historical: v0.9 analytics

v0.9 is the analytics release. Everything through v0.8 was about making the *present* correct and
legible: what is queued, what is running, what failed, what an operator did about it. v0.9 asks a
different question — what happened *last month* — and answers it without touching a single row of
the system that has to keep working.

```text
Application tables
    |
    | read-only JDBC extraction        readOnlyMode=always
    v
Immutable Parquet facts                analytics/output/<run-id>/
    |
    v
PySpark transformations
    |
    +-- fact tables                    one row per job, attempt, event, action, effect, key
    +-- aggregate reports              daily, per job type, per priority, per event type
    +-- data-quality report            20 checks that report and never repair
    |
    v
Optional read-only API                 NOT IMPLEMENTED in v0.9 - batch analytics only
```

PostgreSQL is the analytics source of truth. Redis is not, and the reason is not a preference:
Streams retain acknowledged entries, stream length is not executable backlog, and deduplication
markers expire. Redis knows what is being delivered right now. It does not know what happened.

> The pipeline never writes to `jobs`, `job_attempts`, `outbox_events`, `dead_letters`,
> `reliability_actions`, `job_effects`, `effect_counters`, `idempotency_keys` or
> `flyway_schema_history`. It never writes to Redis. It never starts a scheduler or a worker.
> Every export records a row census and a Flyway fingerprint taken before and after itself, so
> the guarantee is a measurement in the output rather than a claim in this file.

## What changed in v0.9

### Analytics is a separate program, not a feature of the application

The pipeline lives in `analytics/` as a Python project with its own pinned dependencies. No Spark
dependency was added to the Maven build, no analytics code runs in the application JVM, and no
scheduled task was added to the worker path. The two systems share exactly one thing: a database
that one of them is only allowed to read.

That separation is the whole design. An analytics query that goes wrong should cost a report, not
a queue.

### Exports are immutable snapshots with a deterministic identity

A run directory is named after its window:

```text
analytics/output/20260101T000000Z__20270101T000000Z/
```

Not a random ID and not a timestamp. Rerunning the same window has to *collide* with its own
previous output, otherwise "don't overwrite silently" is unenforceable — you would simply get a
second copy under a new name and never know which one anyone was reading.

```text
error [OUTPUT_EXISTS]: export run directory already exists at ... and is not empty.
Analytics exports are immutable snapshots, so this is refused rather than merged.
Pass --overwrite to replace this directory, or choose a different --output.
```

`--overwrite` removes exactly the directory it was pointed at. Sibling runs are untouched.

### Every window is a UTC half-open interval

```text
2026-09-01T00:00:00Z <= timestamp < 2026-10-01T00:00:00Z
```

An offset is mandatory. A naive timestamp is *rejected*, not assumed:

```powershell
python -m distroq_analytics.cli export --start 2026-09-01T00:00:00 --end 2026-10-01T00:00:00Z
# error [INVALID_WINDOW]: start '2026-09-01T00:00:00' has no UTC offset. Naive timestamps are
# rejected because the result would depend on the timezone of whichever machine ran the export.
```

This is the same argument v0.6 made about `scheduledAt` and it is load-bearing for the same
reason. On the machine v0.9 was validated on, 19 of 95 jobs fall on a different calendar day under
the host's local zone than under UTC. A daily report that silently used local time would be wrong
for a fifth of the data and would look completely plausible.

Which timestamp decides inclusion is documented per dataset, because it is not obvious and it is
not the same one everywhere:

| Dataset | Inclusion timestamp |
|---|---|
| Jobs | `created_at` |
| Attempts | `started_at` |
| Outbox events | `created_at` |
| Dead letters | `moved_at` |
| Reliability actions | `created_at` |
| Effects | `created_at` |
| Idempotency keys | `created_at` |

A job created in August and completed in September is **not** a September submission. It may still
appear in September's `attempt_facts`, because that dataset is keyed on when an attempt started.

### Seven fact tables, each with one grain

`job_facts`, `attempt_facts`, `outbox_facts`, `dead_letter_facts`, `reliability_action_facts`,
`effect_facts`, `idempotency_facts` — one row per job, attempt, event, action, effect, and
submission key respectively.

Three rules decide every derived column:

1. **Null in, null out.** Nothing invents a timestamp. A `RUNNING` job has no `duration_ms`,
   and it does not get one by substituting `now()`.
2. **Negative durations survive.** A `finished_at` before `started_at` is evidence of a clock or
   ordering problem. Clamping it to zero would delete the evidence and leave a plausible number in
   its place; instead it flows through to the aggregates *and* gets named by a data-quality check.
3. **Queue delay and schedule delay are mutually exclusive.** A scheduled job did not wait in a
   queue, it waited for a time a user picked. Averaging the two together would make a user's own
   choice look like system latency.

Two things the schema forces the analytics to be honest about:

- `jobs.started_at` is overwritten by every attempt (`Job.markRunning`), so `duration_ms` measures
  the **final** attempt and `queue_delay_ms` includes retry backoff for a retried job. The
  spec-required columns are computed exactly as specified; `first_attempt_started_at` and
  `first_queue_delay_ms` are derived from `job_attempts` alongside them for the cases where true
  admission delay is what is wanted.
- An effect deduplication hit is a Micrometer counter, not a row, so it cannot be extracted. It is
  durably *implied*: the counter effect key deliberately excludes the attempt number, so a
  `COMPLETED` effect claimed on attempt N belonging to a job that ran M attempts means every
  attempt after N found the key already complete. `deduplication_hits = max(0, M - N)` is a lower
  bound, and is documented as one.

### Payloads never leave the database

Job payloads and outbox payloads are not selected. Error text and operator reasons are truncated
to 500 characters in SQL. The raw `Idempotency-Key` is hashed to SHA-256 *inside PostgreSQL*:

```sql
encode(sha256(convert_to(k.idempotency_key, 'UTF8')), 'hex') AS idempotency_key_hash
```

so the client-supplied token is never in Spark's memory, never in a Parquet file, and never in a
report.

### Data quality reports and never repairs

20 checks run over the extracted facts. Every check emits a row **even when it finds nothing** — a
report where a check is absent is indistinguishable from one where the check did not run.

```text
   jobs_negative_duration                                       ERROR     0
   dead_lettered_jobs_without_dlq_row                           ERROR     0
   multiple_in_progress_attempts                                ERROR     0
   scheduled_jobs_missing_schedule_event_despite_other_events   ERROR     0
 ! scheduled_jobs_missing_schedule_event                        WARNING  18
 ! duplicate_idempotency_hashes_for_different_jobs              WARNING   2
```

The two checks about schedule events are the same question asked at two confidence levels. A job
scheduled into the future with no `SCHEDULE_USER_JOB` event may simply predate the outbox, or its
event may have been deleted by retention — the broad `WARNING`. A job that has *other* outbox rows
but no schedule event cannot be explained that way, because the outbox was demonstrably writing
events for it — the narrow `ERROR`. Reporting only the broad number would have buried the second
case inside the first.

Reconciliation already exists and is the only thing allowed to change application state. An
analytics job that "fixed" a row would be a second, unaudited writer racing it.

Three of the checks exist specifically because the schema carries **no CHECK constraint** over its
enum columns, a decision `V1__initial_schema.sql` argues for at length. The application enum is the
source of truth; v0.9 validates rather than assumes.

### Percentiles are exact, not approximate

`percentile`, not `percentile_approx`. The approximate form is the right choice at scale and is
defined by an error bound — and an error bound over four rows is not a number to put in an
operational report. Exact percentiles are deterministic at any input size, which is what makes two
runs of the same window comparable.

A group with no measurable durations reports a **null** percentile, and a success rate over zero
submissions is **null**, not `0.0`. A dashboard that renders an undefined rate as 0% is lying about
a quiet day.

### The pipeline runs in a container

`analytics/Dockerfile` pins Python 3.12, PySpark 4.0.1, a JRE, and a SHA-256-verified PostgreSQL
JDBC driver. `docker compose` gains an `analytics` service behind a profile, so a plain
`docker compose up` still starts only Redis and PostgreSQL.

On Windows the container is not a convenience, it is the only supported path: Spark writes Parquet
through Hadoop's local filesystem, which needs `winutils.exe` and `hadoop.dll` from a Hadoop binary
distribution, and installing unsigned native binaries from a third-party mirror is not a dependency
this project is willing to take.

## v0.9 analytics

### Setup

```powershell
docker compose --profile analytics build analytics
docker compose --profile analytics run --rm analytics --help
```

The service joins the compose network and reaches PostgreSQL at `postgres:5432` rather than the
host's `5433`. Output lands in `analytics/output/` on the host.

### Configuration

| Variable | Flag | Default |
|---|---|---|
| `DISTROQ_ANALYTICS_DB_URL` | `--jdbc-url` | `jdbc:postgresql://localhost:5433/distroq` |
| `DISTROQ_ANALYTICS_DB_USER` | `--db-user` | unset |
| `DISTROQ_ANALYTICS_DB_PASSWORD` | `--db-password` | unset |
| `DISTROQ_ANALYTICS_OUTPUT_DIR` | `--output` | `analytics/output` |
| `DISTROQ_ANALYTICS_SPARK_MASTER` | `--spark-master` | `local[*]` |
| `DISTROQ_ANALYTICS_LOG_LEVEL` | `--log-level` | `WARN` |

No credential is committed. The password is never written into the JDBC URL, never printed, and
stripped from any driver error text before it reaches a log. Prefer the environment variable over
`--db-password`, which is visible in the process list to every user on the host.

### Commands

```powershell
# extract - the only stage that touches the database
docker compose --profile analytics run --rm analytics export `
  --start 2026-01-01T00:00:00Z `
  --end   2027-01-01T00:00:00Z

# aggregate - reads the Parquet the export wrote, never the database
docker compose --profile analytics run --rm analytics report `
  --input /workspace/analytics/output/20260101T000000Z__20270101T000000Z

# data quality - same input, same rule
docker compose --profile analytics run --rm analytics quality `
  --input /workspace/analytics/output/20260101T000000Z__20270101T000000Z
```

Running locally instead of in the container, the CLI is the same program:

```powershell
python -m distroq_analytics.cli export `
  --start 2026-01-01T00:00:00Z `
  --end 2027-01-01T00:00:00Z `
  --output analytics/output `
  --jdbc-url "jdbc:postgresql://localhost:5433/distroq" `
  --db-user distroq
```

### Output directory

```text
analytics/output/<run-id>/
  jobs/  job_attempts/  outbox_events/  dead_letters/
  reliability_actions/  job_effects/  idempotency_keys/    raw extracts
  facts/                                                   seven fact tables
  metadata/export_metadata.json
  reports/                                                 six aggregates + data quality + CSV
  quality/                                                 data quality on its own
```

### Exit codes

| Code | Name | Meaning |
|---|---|---|
| 0 | OK | |
| 1 | USAGE | bad arguments, non-PostgreSQL JDBC URL |
| 2 | INVALID_WINDOW | naive timestamp, unparseable instant, `start >= end` |
| 3 | DB_CONNECTION | could not connect or read the catalogue |
| 4 | MISSING_TABLE | a required source table is absent |
| 5 | EXTRACTION_FAILED | a source extract failed |
| 6 | TRANSFORM_FAILED | fact or aggregate construction failed |
| 7 | OUTPUT_EXISTS | destination exists and `--overwrite` was not given |
| 8 | QUALITY_FATAL | findings reached the `--fail-on` severity |
| 9 | MISSING_INPUT | `--input` is not an export run directory |

The window is validated before Spark starts and before any connection is attempted, so a bad
window and an unreachable database can never be confused for one another.

### Example reports

`daily_job_summary` — UTC day and priority:

```text
day,priority,submitted_jobs,started_jobs,succeeded_jobs,failed_jobs,dead_lettered_jobs,scheduled_jobs,retried_jobs,abandoned_attempts,average_duration_ms,p50_duration_ms,p95_duration_ms,p99_duration_ms,average_queue_delay_ms,p95_queue_delay_ms,average_schedule_delay_ms
2026-09-07,HIGH,11,11,11,0,0,7,2,0,871.182,122,4014,4016,1369.25,3571,1351.0
2026-09-08,HIGH,18,18,18,0,0,0,3,1,5238.333,1524,21517,28315,23424.611,138313,
2026-09-08,LOW,3,2,1,0,1,2,1,0,155.0,155,274,284,8338.0,8338,86246.0
2026-09-09,NORMAL,8,5,4,0,1,1,1,0,53.6,55,81,84,11143.0,36865,905.0
```

`job_type_summary` — note `success_rate` and the deduplication hit:

```text
job_type,submitted_jobs,succeeded_jobs,dead_lettered_jobs,success_rate,average_attempts,average_duration_ms,p95_duration_ms,effect_deduplication_hits
always_fail,6,0,6,0.0,4.667,19.5,26,0
fail_n_times,8,7,1,0.875,3.625,18.625,28,0
idempotent_counter,8,5,0,0.625,0.75,45.2,73,1
sleep,73,67,0,0.917808,0.959,3018.985,18542,0
```

`priority_summary` — LOW pays for the tiers, exactly as v0.4 said it would:

```text
priority,submitted_jobs,succeeded_jobs,dead_lettered_jobs,success_rate,average_queue_delay_ms,p95_queue_delay_ms,average_schedule_delay_ms,p95_schedule_delay_ms
HIGH,32,31,0,0.96875,17830.083,104233,1351.0,4248
LOW,21,13,4,0.619048,528165.154,2722456,28150.25,76921
NORMAL,42,35,3,0.833333,261659.0,185524,2487809.818,8920215
```

`outbox_summary` — an empty latency column is a null, not a zero:

```text
day,event_type,total_events,published_events,pending_events,publishing_events,terminal_failed_events,operator_retries,average_publication_latency_ms,p95_publication_latency_ms,oldest_unpublished_age_ms
2026-09-08,SCHEDULE_USER_JOB,1,1,0,0,0,0,81.0,81,
2026-09-09,ENQUEUE_SUBMIT,10,6,3,0,1,1,372.833,648,591177
```

The terminal event is counted in `terminal_failed_events` and **not** in `published_events`; its
`publication_latency_ms` is null because it never published; the operator retry that re-armed a
different event shows in `operator_retries`; and `oldest_unpublished_age_ms` is measured from the
export, not from the window.

### Rerun and overwrite

Two exports of the same window, four minutes apart, produced byte-identical CSVs for
`daily_job_summary`, `job_type_summary`, `priority_summary`, `reliability_summary`,
`effect_summary` and `data_quality_summary`. `outbox_summary` differed in exactly one cell:

```text
run 1:  2026-09-09,ENQUEUE_SUBMIT,10,6,3,0,1,1,372.833,648,591177
run 2:  2026-09-09,ENQUEUE_SUBMIT,10,6,3,0,1,1,372.833,648,831245
```

`oldest_unpublished_age_ms` moved by 240,068 ms — the elapsed time between the two exports. An
unpublished event genuinely does get older. That is the one column in the whole pipeline that is
defined relative to export time, and it is documented rather than frozen, because freezing it
would make it useless for the question it exists to answer.

Note where the non-determinism lives: it is in the **export**, not in the aggregation. `report` and
`quality` read `age_at_export_ms` already frozen in the export's Parquet, so re-running them over
one export is fully deterministic — two report runs over the same run directory produce all seven
CSVs byte-identical, `outbox_summary.csv` included, with rows in the same order. Both properties
are regression-tested.

The Parquet files themselves are not byte-stable even when their contents are: parquet-mr emits
Thrift footer metadata fields in a non-fixed order, so two writes of identical data differ by
around twenty bytes in the footer. Compare rows or CSVs, not container bytes.

### Read-only guarantee

Every export writes its own proof into `export_metadata.json`:

```json
"read_only": {
  "jdbc_read_only_mode": "always",
  "row_census_before": { "jobs": 95, "job_attempts": 133, "outbox_events": 30, "...": 0 },
  "row_census_after":  { "jobs": 95, "job_attempts": 133, "outbox_events": 30, "...": 0 },
  "row_census_unchanged": true,
  "flyway_before": { "migrations": 7, "checksum_total": 4346192880, "max_rank": 7 },
  "flyway_after":  { "migrations": 7, "checksum_total": 4346192880, "max_rank": 7 },
  "flyway_unchanged": true
}
```

The census covers all eight application tables plus `flyway_schema_history`, and is taken by the
same connection that did the extraction, before and after it.

### Performance and its limits

Measured on 95 jobs, 133 attempts, 30 outbox events, 41 reliability actions:

| | |
|---|---|
| Extraction | 10.2s (7 tables, single-partition JDBC) |
| Transformation | 11.1s |
| Total export | 25.2s |
| Report | 31.1s |
| Quality | 22s |
| Output | 186 KiB across 99 files |

**This is a correctness result, not a performance result.** Nothing here justifies a claim about
production scale. The known limits are single-partition JDBC extraction, `coalesce(1)` output, and
a single-JVM `local[*]` master; the mitigation available today is a smaller window, which is safe
because consecutive half-open windows tile the timeline exactly once.

### Analytics HTTP API

```text
Batch analytics only; no analytics HTTP API implemented in v0.9.
```

It was scoped as optional and declined. Serving `GET /api/analytics/summary` from the application
would mean the application process reading Parquet from a directory the batch job owns, which
introduces a coupling — a deploy that moves the output directory breaks an HTTP endpoint — for no
capability the files do not already provide. The aggregates are on disk and readable by anything
that reads Parquet.

## What changed in v0.8

v0.8 is the operations release. v0.7 made publication durable; v0.8 made it *legible*. The outbox
has an explicit lifecycle rather than one inferred from nullable columns, a terminal failure
survives for a human to look at instead of retrying forever in silence, a reconciliation pass
compares PostgreSQL intent against what the rest of the system actually did, every operator or
automatic repair writes an audit row, and a side-effect ledger gives cooperating integrations an
identity that survives redelivery.

```text
PostgreSQL transaction
    |
    +-- business state
    +-- outbox event                    PENDING
    |
    v
Outbox relay                            PENDING -> PUBLISHING -> PUBLISHED
    |                                              |
    +-- Redis publication                          +-> FAILED (terminal, waits for an operator)
    +-- deduplication marker
    |
    v
Reconciliation
    |
    +-- detect missing publication
    +-- detect terminal relay failure
    +-- detect stale scheduling intent
    +-- detect lease anomalies
    +-- detect stale effects
    |
    v
Operator action, or a repair that the database already proves is correct
    |
    +-- reliability_actions (same transaction as the state change)
```

PostgreSQL remains the source of truth. Redis remains an at-least-once delivery mechanism.

> `Idempotency-Key` prevents duplicate job creation.
> Outbox event IDs prevent duplicate Redis publication.
> Execution leases prevent stale workers from finalizing database state.
> Effect keys protect only integrations that participate in the effect protocol.
> None of these alone makes arbitrary external side effects exactly once.

### The outbox lifecycle is explicit

v0.7 inferred state from `published_at`, `attempt_count` and `locked_until`. That is enough for a
relay loop and not enough for an operator: "failed" and "not tried yet" look identical unless you
also know what `max-attempts` was set to at the time. `outbox_events.status` is now authoritative.

| Status | Meaning | Claimed by the relay? | Deleted by cleanup? |
| --- | --- | --- | --- |
| `PENDING` | Eligible. The initial state, and the state an operator retry restores. | Yes | Never |
| `PUBLISHING` | Claimed under a relay lease. | Only once `locked_until` has passed | Never |
| `PUBLISHED` | Redis publication succeeded. | No | Only past retention |
| `FAILED` | Budget exhausted. Waiting for a human. | **No** | **Never** |

The nullable columns are all still there and still written — they are the audit trail now, not the
state machine.

A `PUBLISHING` row whose lease expired is picked up by the *relay*, not by reconciliation:

```sql
WHERE (status = 'PENDING' OR (status = 'PUBLISHING' AND locked_until < :now))
  AND available_at <= :now
```

That matters more than it looks. It means the queue drains after a crash whether or not
reconciliation is enabled, and reconciliation is a diagnostic rather than a load-bearing part of
delivery. Reconciliation reports the same rows so the crash is visible.

### Terminal failure

When an event exhausts its budget it becomes `FAILED`, `terminal_failed_at` is stamped, and the
relay stops claiming it. Nothing deletes it. Nothing quietly retries it. The log says exactly what
to do:

```text
Outbox event 12ad91eb-... is now FAILED after 2 attempts against a ceiling of 2; the relay will
not claim it again until an operator retries it through POST /api/admin/outbox/12ad91eb-.../retry
```

The ceiling is `max-attempts × (operator_retry_count + 1)`, not a lifetime cap. `attempt_count` is
cumulative and is never reset, so each operator retry moves the ceiling instead of erasing the
history: attempt 214 really is the 214th attempt, and `max-attempts` keeps meaning "tries before a
human is asked", which is the only reading an alert can act on.

### Operator retry

```powershell
$headers = @{ "X-Admin-Reason" = "Redis was restored after maintenance" }
$body = @{ reason = "Retry terminal event after Redis recovery" } | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/admin/outbox/$eventId/retry" `
  -Headers $headers -ContentType "application/json" -Body $body
```

`202 Accepted` — the event is back in the queue, and the publication has not happened yet and will
not happen on that thread. Three things the endpoint deliberately does **not** do:

- It does not create a second event. A new event ID would defeat the Redis deduplication marker,
  which is the only thing standing between a repaired event and a double publication.
- It does not publish to Redis. A controller that writes to Redis is exactly the dual write the
  outbox exists to remove. The normal relay picks the event up on its next tick.
- It does not reset `attempt_count`.

`409` if the event is not `FAILED`, `404` if it does not exist, `400` if either reason is missing,
blank, or over `distroq.admin.max-reason-length`. An `OUTBOX_RETRY` audit row records the before
state, the after state, both reasons and the actor.

### Reconciliation

A scheduled sweep, plus `POST /api/admin/reconciliation/run`, that compares durable intent against
observable state. It is bounded by `batch-size`, idempotent, safe on several instances at once
(each run holds a PostgreSQL advisory lock for its transaction), and **non-destructive by
default**.

| Category | Findings |
| --- | --- |
| `OUTBOX` | `STALE_PENDING_OUTBOX`, `EXPIRED_OUTBOX_LOCK`, `TERMINAL_OUTBOX_FAILURE`, `INCONSISTENT_PUBLISHED_OUTBOX`, `ORPHANED_OUTBOX_EVENT`, `MALFORMED_OUTBOX_PAYLOAD` |
| `SCHEDULED_JOBS` | `STALE_SCHEDULED_JOB`, `SCHEDULED_JOB_WITHOUT_EVENT`, `SCHEDULED_JOB_EVENT_FAILED`, `DUPLICATE_SCHEDULE_EVENT`, `UNPROMOTED_SCHEDULED_MEMBER` |
| `RETRIES` | `STALE_RETRY_JOB`, `RETRY_JOB_WITHOUT_EVENT`, `RETRY_EVENT_FAILED`, `DUPLICATE_RETRY_EVENT` |
| `EXECUTION_LEASES` | `EXPIRED_EXECUTION_LEASE`, `MISSING_ACTIVE_ATTEMPT`, `MULTIPLE_IN_PROGRESS_ATTEMPTS`, `ATTEMPT_OWNER_MISMATCH`, `TERMINAL_JOB_HOLDING_LEASE` |
| `EFFECTS` | `STALE_STARTED_EFFECT` |

The source of truth is the outbox and job tables, never the Streams. A stream keeps acknowledged
entries, so "the entry is there" does not mean the work is outstanding; and trimming or an expired
deduplication marker means "no entry" does not mean the work never published. Only rows DistroQ
wrote inside a transaction can be reasoned about after the fact.

Redis is consulted in exactly one place: the scheduled sorted set, where a member that is *due*
and still *present* is positive evidence that promotion has stopped — something no PostgreSQL
table can show. A Redis outage there is logged and skipped rather than turned into a finding,
because "I could not look" is not evidence of a problem.

### Safe versus unsafe repairs

A finding is repaired automatically only when the database state **proves** the repair is correct,
not when the repair merely seems likely to help. That is why most of the list is "operator".

| Finding | Automatic? | Why |
| --- | --- | --- |
| `EXPIRED_OUTBOX_LOCK` | Yes — `OUTBOX_UNLOCK` | An expired lease is proof that nobody owns the row. |
| `INCONSISTENT_PUBLISHED_OUTBOX` | Yes — `OUTBOX_REPUBLISH` | Only when `published_at` is set, which is proof it published. A `PUBLISHED` row with no timestamp is skipped. |
| `SCHEDULED_JOB_WITHOUT_EVENT` | Yes — `SCHEDULED_JOB_REPAIR` | No event exists at all, so no publication can exist. |
| `RETRY_JOB_WITHOUT_EVENT` | Yes — `RETRY_JOB_REPAIR` | Same argument. |
| `TERMINAL_JOB_HOLDING_LEASE` | Yes — `LEASE_REPAIR` | A finished job has no live owner: every finalizing statement requires a live lease it no longer has. |
| `STALE_STARTED_EFFECT` | Only with `effects.auto-fail-stale` — `STALE_EFFECT_REPAIR` | Closed as `FAILED`, never as `COMPLETED`. |
| `TERMINAL_OUTBOX_FAILURE` | No, unless `reconciliation.requeue-failed-outbox` | Re-arming a terminal event is the operator decision v0.8 refuses to make on its own. |
| `EXPIRED_EXECUTION_LEASE` | **Never** | A lease expiring proves the worker stopped renewing, not that it stopped working. Recovery belongs to `XAUTOCLAIM` plus a fresh database claim; a second claim made here would compete with the worker about to make one. |
| `STALE_PENDING_OUTBOX` | No | The relay owns it. Repairing it here would race. |
| Everything else | No | Ambiguous. Reported and left alone. |

**Configuration is the ceiling.** A request may ask for less than `auto-repair` allows and never
for more, so enabling repairs stays a deployment decision rather than something an HTTP body can
do:

```json
{ "autoRepairRequested": true, "autoRepairAllowedByConfiguration": false, "autoRepairApplied": false }
```

Each finding is reported with a resolution — `REPORTED`, `REPAIRED`, `SKIPPED` or `FAILED` — and
the response separates `inspected` (bounded by `batch-size`, so `inspected == batchSize` means
there is probably more) from the partition of the findings themselves.

### Audit records

`reliability_actions` gets one row per mutation, **written in the same transaction as the state
change it describes**, so there is no window in which the database has been repaired and nothing
says who repaired it. `ReliabilityAuditService.record` is `@Transactional(MANDATORY)`: it refuses
to run outside a transaction, which makes the rule a runtime failure rather than a convention.

Action types: `OUTBOX_RETRY`, `OUTBOX_UNLOCK`, `OUTBOX_REPUBLISH`, `SCHEDULED_JOB_REPAIR`,
`RETRY_JOB_REPAIR`, `LEASE_REPAIR`, `OUTBOX_CLEANUP`, `STALE_EFFECT_REPAIR`.

`before_state` and `after_state` are short structural summaries — statuses, counts, timestamps —
never payloads and never user data, so an audit table an operator reads casually does not become
something that has to be redacted later.

### Outbox retention

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/admin/outbox/cleanup" `
  -Headers @{ "X-Admin-Reason" = "Retention sweep after audit" }
# -> { "deleted": 34, "batchSize": 500, "batchFull": false, "cutoff": "...", "retentionWindowDays": 30 }
```

Only `PUBLISHED` rows are ever deleted, and only when all of the following hold:

1. The row is older than the retention window.
2. No sibling event for the same job is `FAILED` or mid-publication.
3. The job itself is not currently in a state reconciliation reports as an unresolved finding.

(2) and (3) stop cleanup deleting the evidence an operator is in the middle of reading. All three
are expressed in the SQL rather than in Java, which is where the rule cannot be forgotten, and
`OutboxRetentionPolicyTest` asserts against the query text for exactly that reason.

The retention window is **the longer of** `published-retention-days` and `dedupe-retention-ms`.
While the deduplication marker is alive, the row and the marker are redundant with each other and
losing either is survivable. Once the marker has expired the row is the last remaining evidence
that the publication happened, so deleting it inside the window in which something might republish
is how a duplicate gets made. Configuring a short retention does not shorten this — it only means
the marker is the binding constraint.

One `OUTBOX_CLEANUP` audit row is written per deleted event, bounded by `cleanup-batch-size`.
`batchFull: true` means run it again.

### Effect idempotency

Four different mechanisms solve four different duplicate problems, and v0.8 exists partly to stop
them being confused with one another:

| Mechanism | Prevents | Scope |
| --- | --- | --- |
| `Idempotency-Key` | two jobs from one submission | HTTP request |
| Outbox event ID + Redis marker | two publications of one intent | Redis publication |
| Execution lease | a stale worker finalizing database state | database ownership |
| **Effect key** | a protected side effect happening twice | one logical operation |

The ledger claims a key, does the work and records the result **in one transaction**, so there is
no instant at which the effect has happened and the ledger does not know:

```sql
INSERT INTO job_effects (effect_key, ...) VALUES (...) ON CONFLICT (effect_key) DO NOTHING
```

That is the whole mechanism. Two workers racing the same key are separated by the primary key
rather than by timing: the loser blocks on the winner's uncommitted insert, and once the winner
commits it reads `COMPLETED` and does nothing.

Three identities that get confused with each other, kept apart by name:

- **Physical delivery attempt** — a stream entry ID plus the consumer holding it. New on every
  redelivery, including redelivery of work that already ran.
- **Job attempt** — `attemptCount` and the `job_attempts` row. New on every execution, including
  one that reruns work a crashed worker had already done.
- **Logical effect key** — stable for the operation being protected, across both of the above.

### `idempotent_counter`

```powershell
$body = @{ type = "idempotent_counter"; payload = "orders:daily"; priority = "NORMAL" } | ConvertTo-Json
$j = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType "application/json" -Body $body
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($j.id)" | ConvertTo-Json -Depth 8
```

```json
"effects": [{
  "effectKey": "c90accdb-...:counter:orders:daily",
  "effectType": "counter",
  "status": "COMPLETED",
  "attemptNumber": 1,
  "responseHash": "f3761a3292ab90c5ecc49d947389ef48997534d4ce6cd91a25551fa6a5aa22f9"
}]
```

The key is `<job-id>:counter:<normalized-payload>` and deliberately **excludes the attempt
number**. Keying on the attempt would make every redelivery a new effect, which is precisely the
duplicate the ledger exists to prevent. Run the job twice — by redelivery, by reclaim, or by
resetting it in the database and re-adding a stream entry — and you get two `job_attempts` rows,
one `job_effects` row, one increment, and `effectDeduplicationHits` going up by one.

`response_hash` is a SHA-256 of the effect identity and the observed result. It is enough to prove
two observations of the same effect agree, and it cannot leak a response body, a token or a
customer record into a table that outlives the job.

Ledger states and what a second claimer does with them:

| State | Second claimer |
| --- | --- |
| `COMPLETED` | Reports a deduplication hit and returns the recorded `responseHash`. |
| `FAILED` | Reclaims the same row under the same key. `FAILED` means a worker *observed* the effect not happening. |
| `STARTED` | Throws `EffectInProgressException` and lets the job retry. |

`STARTED` is the one state carrying no information: the holder may be mid-flight, or may have died
a millisecond after the external system accepted the call. Failing the job hands the decision to
the retry machinery and — if the row is still `STARTED` past `stale-started-after-ms` — to
reconciliation and then to a human.

**None of this makes an arbitrary external effect exactly once.** An HTTP POST to a payment
provider commits somewhere DistroQ has no transaction over and cannot join the one above; no
amount of bookkeeping here changes that. The ledger protects effects that opt into the protocol.

### Administrative API

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/admin/outbox` | Filters: `status`, `eventType`, `aggregateId`, `createdAfter`, `createdBefore`, `page`, `size`. **No payload.** |
| GET | `/api/admin/outbox/{eventId}` | Full metadata plus a payload *summary* — size and SHA-256, never the bytes. |
| POST | `/api/admin/outbox/{eventId}/retry` | `202`; `409` if not `FAILED`; `404` if unknown. |
| POST | `/api/admin/outbox/cleanup` | Retention sweep on demand, same rules as the scheduled one. |
| GET | `/api/admin/reconciliation` | Read-only preview. No header required — nothing changes and nothing is audited. |
| POST | `/api/admin/reconciliation/run` | `{ "autoRepair": false, "reason": "..." }`. Configuration is the ceiling. |
| GET | `/api/admin/reliability-actions` | Audit history. Filters: `actionType`, `targetId`, `page`, `size`. |

Every **mutating** endpoint requires a non-blank `X-Admin-Reason` of at most
`distroq.admin.max-reason-length` characters; it is stored in `reliability_actions`. An optional
`X-Admin-Actor` names the operator and defaults to `operator`.

> `X-Admin-Reason` is **not authorization and must not be read as any**. v0.8 has no
> authentication at all: anything that can reach the port can call these endpoints. The header only
> guarantees that whoever did left a sentence explaining themselves. It is a forcing function for
> the audit trail. Authentication and authorization are deferred.

### V7 migration

`V7__reliability_reconciliation_and_effects.sql` adds `status`, `operator_retry_count`,
`terminal_failed_at`, `last_operator_retry_at` and `last_operator_reason` to `outbox_events` with
three supporting indexes; creates `reliability_actions`, `job_effects` and `effect_counters`. Rows
that had already published are backfilled to `PUBLISHED`; everything else takes the `PENDING`
default.

A row that was terminal under v0.7's rules is left `PENDING` on purpose. The migration has no way
to know what `max-attempts` was set to when it failed, so it lets the relay re-derive terminality
from the setting actually in force. Hibernate still runs with `ddl-auto: validate`.

### Metrics

`GET /api/metrics` adds a reliability block. `outboxFailed` was renamed `outboxRetryableFailed`,
because "failed" had been doing the work of two different words.

| Field | Precision |
| --- | --- |
| `outboxPending` | exact DB count of eligible, unlocked `PENDING` rows |
| `outboxRetryableFailed` | exact DB count of `PENDING` rows that have already failed at least once |
| `outboxTerminalFailed` | exact DB count of `FAILED` rows — **this is the alert** |
| `outboxOldestAgeMs` | age of the oldest unpublished row at query time |
| `outboxPublishedTotal` | exact *retained* row count; retention makes it historical, not lifetime |
| `outboxCleanupDeleted` | exact cumulative DB count of `OUTBOX_CLEANUP` audit rows |
| `reconciliationFindings` | exact, recounted live from the same predicates reconciliation uses |
| `reconciliationRepairs` | exact cumulative DB count of non-cleanup audit rows |
| `staleScheduledJobs` | exact DB count |
| `staleRetryJobs` | exact DB count |
| `expiredExecutionLeases` | exact DB count |
| `staleEffects` | exact DB count of `STARTED` rows past the threshold |
| `effectDeduplicationHits` | **process-local** cumulative counter; resets on restart |
| `effectApplications` | exact DB count of `COMPLETED` ledger rows |

Everything except `effectDeduplicationHits` is a live database count rather than a remembered
number. That costs a handful of indexed aggregates per call and buys two things: the values do not
drift after a restart, and they read the same on every instance. A cached gauge from the last
reconciliation run would have been cheaper and would have reported an outage that was already
fixed, or missed one that started thirty seconds ago.

`effectDeduplicationHits` cannot be derived from a table after the fact, because a deduplication
hit leaves no row behind — that is its whole job.

`reconciliationFindings` counts only the findings expressible as a single indexed predicate. The
per-job event cross-checks are too expensive for a metrics scrape and appear only in the
reconciliation report.

### Operational troubleshooting

**"`outboxTerminalFailed` is above zero."** Something is unpublished and no longer being retried.

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/admin/outbox?status=FAILED" |
  Select-Object -ExpandProperty events | Format-Table eventId, eventType, attemptCount, ageMs
Invoke-RestMethod -Uri "http://localhost:8080/api/admin/outbox/$eventId"   # lastError, ceiling
```

Fix the cause, then retry the event. Check afterwards that it reached `PUBLISHED` — a second
failure returns it to `FAILED` with `operator_retry_count` intact.

**"A job is `SCHEDULED` and its time has passed."** Run the preview. If it reports
`SCHEDULED_JOB_EVENT_FAILED`, the schedule event is terminal and the fix is an operator retry of
that event. If it reports `SCHEDULED_JOB_WITHOUT_EVENT`, no publication can exist and a repair run
will create one. If it reports `UNPROMOTED_SCHEDULED_MEMBER`, the member is in Redis and due, and
the promoter has stopped — that is an application problem, not a data problem.

**"A job has been `RUNNING` for hours."** `EXPIRED_EXECUTION_LEASE` says the lease lapsed;
reconciliation will not touch it. Recovery is `XAUTOCLAIM` plus a fresh database claim, which needs
`claim-min-idle-ms` to pass and *another live worker*. Check `XPENDING` for the entry, and check
that a second instance is running.

**"`staleEffects` is above zero."** A worker claimed an effect and never came back. The external
effect may or may not have happened, and nothing in the database can settle it. Decide from the
external system, then either let `effects.auto-fail-stale` release the key for a retry or close it
by hand.

**"Reconciliation reports the same findings every run."** Expected, if they are the operator kinds.
`unresolved` is the number to watch; `repaired` going to zero on a second run is the *idempotence*
working, not a failure.

## What changed in v0.7

### Transactional outbox

The four former database-to-Redis windows now commit an `outbox_events` row in the same database
transaction as their business state:

| Business transition | Event type | Redis destination |
| --- | --- | --- |
| immediate submission | `ENQUEUE_SUBMIT` | priority Stream |
| retryable failure | `SCHEDULE_RETRY` | `distroq:jobs:delayed` |
| future submission | `SCHEDULE_USER_JOB` | `distroq:jobs:scheduled` |
| DLQ replay | `ENQUEUE_REPLAY` | priority Stream |

Each immutable event payload contains `eventId`, `jobId`, validated `priority`, `source`, and the
relevant `dueAt`/`scheduledAt`. Only relay bookkeeping (`published_at`, `attempt_count`,
`last_error`, and `locked_until`) changes after creation.

Relay instances claim batches with PostgreSQL `FOR UPDATE SKIP LOCKED`, commit a 30-second lease,
publish each event independently, and mark it published only after Redis returns success. A bad
event increments its attempt count, records its error, releases its lock, and does not block later
events. Unpublished events are never automatically deleted. Published-event cleanup is not
automatic in v0.7; `retention-days` is the operational retention target.

When an event reaches `outbox.max-attempts`, the relay logs that it is terminal and stops claiming
it. The row remains unpublished with its final `attempt_count` and `last_error` for operator
inspection; v0.7 does not automatically discard or reset terminal events. `outboxFailed` counts
retryable failures, while `outboxTerminalFailed` separately counts rows at or above the ceiling.

Redis publication and its marker are one Lua operation. The marker key is:

```text
distroq:outbox:published:<event-id>
```

For Streams the script atomically performs `XADD` and stores the generated Stream ID in the marker.
For sorted sets it atomically performs `ZADD` and stores the member in the marker. A repeated relay
attempt returns the marker value without writing again. Stream fields now include
`outboxEventId`. New sorted-set members are `<TIER>:<job-id>:<event-id>`; the parser and promotion
script still accept v0.6 `<TIER>:<job-id>` members.

Markers expire after seven days. This must exceed the maximum expected relay retry window.
Expiring too early can let an old unpublished row publish again; retaining markers forever would
grow Redis without bound. Unpublished rows approaching that window must be investigated.

The relay has two acceptance controls:

```yaml
distroq:
  outbox:
    relay-enabled: false       # commit rows without publishing
    fail-after-publish: true   # publish, then fail before published_at is set
```

Both default to the safe production values shown in the complete configuration below.

### Idempotent submission

`POST /api/jobs` accepts an optional global `Idempotency-Key` until authentication provides a
tenant/client scope:

```http
Idempotency-Key: customer-request-123
```

Keys are trimmed, must contain 1-128 characters, and are otherwise opaque. A PostgreSQL advisory
transaction lock serializes concurrent first use of the same key. The canonical SHA-256 request
hash contains `type`, `payload`, resolved `maxAttempts`, resolved `priority`, and `scheduledAt` as
a normalized UTC `Instant` or null. It is serialized from a fixed-order record, not raw request
JSON, so JSON whitespace/property order and equivalent timestamp offsets do not create conflicts.

```text
first request:
  creates job 7f..., idempotency row, and one outbox event
retry with same key and same body:
  returns job 7f... with HTTP 202 and Idempotent-Replay: true
same key with a different body:
  returns HTTP 409 and creates nothing
```

`GET /api/idempotency/{key}` returns the key, hash, original job ID, creation time, and current job
status, or `404`. It does not return the job payload.

### Multiple workers and execution leases

`distroq.worker.concurrency` creates that many worker loops in one process. Each loop receives a
different `<prefix>-<8 random hex>` consumer name, and that exact name is written as the attempt's
`workerId`. Every loop processes at most one active job at a time.

Before user code runs, `ExecutionClaimService` executes one conditional `UPDATE jobs`. It accepts
due `QUEUED`, `SCHEDULED`, or `RETRYING` work, or a `RUNNING` job whose lease expired. The same
statement sets `RUNNING`, owner, lease deadline, active attempt UUID, timestamps, and increments
`attempt_count` and `version`. Terminal states and live leases cannot match. Only a matching,
unexpired owner plus `active_attempt_id` may renew or finalize.

While user code runs, a heartbeat renews the lease. If renewal or owner-guarded finalization
fails, that worker logs ownership loss, does not persist success/retry/DLQ state, and leaves the
Stream entry pending. Java execution is cooperative: a sleeping or externally blocked thread
cannot be forcibly stopped safely. An old worker may therefore complete an external side effect
after losing its lease, but it cannot finalize the DistroQ row.

`XAUTOCLAIM` remains the transport recovery mechanism. A reclaiming consumer still must win the
database claim. When it wins an expired lease, previous `IN_PROGRESS` attempts become
`ABANDONED`, a new attempt opens, and the attempt count advances. When it loses, it does not run,
does not abandon the active attempt, does not acknowledge, and leaves the delivery for a later
reclaim. This preserves the invariant that at most one current database lease owner may finalize.
Each process gives its recovery sweep a dedicated generated consumer name rather than sharing the
identity of worker loop 1; that recovery name is also used as the database execution owner.

### Configuration

```yaml
distroq:
  outbox:
    poll-interval-ms: 500
    batch-size: 100
    lock-duration-ms: 30000
    max-attempts: 100
    dedupe-retention-ms: 604800000
    retention-days: 30
    relay-enabled: true
    fail-after-publish: false
  worker:
    concurrency: 1
    execution-lease-ms: 30000
    heartbeat-interval-ms: 5000
```

The default concurrency remains one. The heartbeat interval should be comfortably shorter than
the execution lease. Redis `claim-min-idle-ms` determines when transport ownership can move;
database lease expiry independently determines when execution ownership can move.

### Metrics

v0.7 added the following; v0.8 renames `outboxFailed` to `outboxRetryableFailed` and redefines the
first three in terms of the explicit lifecycle rather than attempt arithmetic.

| Field | Meaning and precision |
| --- | --- |
| `outboxPending` | exact DB count of eligible, unlocked unpublished rows below max attempts |
| `outboxFailed` | exact DB count of retryable unpublished rows with at least one failed attempt |
| `outboxTerminalFailed` | exact DB count of unpublished rows at or above max attempts; operator action is required |
| `outboxOldestAgeMs` | age of the oldest unpublished DB row at query time |
| `outboxPublishedTotal` | exact retained DB row count; retention can make it historical rather than lifetime total |
| `workerConcurrency` | configured loops in this process |
| `activeWorkers` | process-local gauge of loops currently executing user code |
| `activeLeases` | exact DB count of unexpired execution leases |
| `reclaimedEntries` | process-local cumulative count returned by `XAUTOCLAIM` |
| `abandonedAttempts` | exact DB count of `ABANDONED` attempt rows |

`activeConsumers` retains its older meaning: consumers currently holding pending entries, not
active execution count.

### V6 migration

`V6__outbox_idempotency_and_execution_leases.sql` creates `outbox_events` and
`idempotency_keys`, and adds `execution_owner`, `execution_lease_until`, `active_attempt_id`, and
`version` to `jobs`. Hibernate continues to run with `ddl-auto: validate`.

### Acceptance and two instances

Build and inspect the migration:

```powershell
.\mvnw.cmd clean package
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "SELECT version, description, type, success FROM flyway_schema_history ORDER BY installed_rank;"
Invoke-RestMethod http://localhost:8080/api/metrics | ConvertTo-Json -Depth 8
```

Start two instances with one worker each and a short lease:

```powershell
java -jar target/distroq-0.0.1-SNAPSHOT.jar `
  --server.port=8080 --distroq.streams.consumer-name-prefix=worker-one `
  --distroq.worker.concurrency=1 --distroq.worker.execution-lease-ms=3000 `
  --distroq.worker.heartbeat-interval-ms=500

java -jar target/distroq-0.0.1-SNAPSHOT.jar `
  --server.port=8081 --distroq.streams.consumer-name-prefix=worker-two `
  --distroq.worker.concurrency=1 --distroq.worker.execution-lease-ms=3000 `
  --distroq.worker.heartbeat-interval-ms=500
```

To inspect three in-process consumers, start with `--distroq.worker.concurrency=3`, submit work,
then run `XINFO CONSUMERS` for each priority Stream and group `distroq-workers`.

## Historical v0.6 behavior

A minimal, end-to-end distributed task queue with priority scheduling, automatic retries,
exponential backoff, a dead-letter queue with explicit replay, at-least-once delivery over
Redis Streams with acknowledgements and crash recovery, and **durable user-scheduled execution**.

A job submitted over HTTP is persisted to PostgreSQL, its ID appended to **the Redis Stream for
its priority tier**, delivered to a worker through a **consumer group**, executed, and its terminal
status written back to PostgreSQL — observable via a GET endpoint. The stream entry stays in the
group's **Pending Entries List** until the worker acknowledges it, so work abandoned by a worker
that died can be found and re-run instead of vanishing. A job that fails is retried automatically,
with an exponentially increasing delay, **at its original priority**. A job that exhausts its
retries is **moved to a dead-letter queue** rather than merely marked failed, and can be replayed
on demand with its failure history and its priority intact.

A job may also carry a **`scheduledAt`** timestamp. It is then persisted as `SCHEDULED`, parked in
a Redis sorted set scored by its execution time, and promoted onto its own priority stream when
that time arrives — surviving application restarts, because nothing about the schedule lives in
the JVM.

**PostgreSQL is the single source of truth.** Redis carries job ID strings and a little routing
metadata; the job itself is never serialized into Redis. The API and the worker run in the same
Spring Boot process, but are decoupled — the worker talks only to the `queue` package and the
repositories, and neither side references the other's internals.

## What changed in v0.6

One feature: a submission may ask for a future execution time.

```text
Future POST
  -> PostgreSQL SCHEDULED
  -> Redis scheduled Sorted Set
  -> due-time promotion
  -> priority Redis Stream
  -> XREADGROUP
  -> worker
  -> success/retry/DLQ
```

| | v0.5 | v0.6 |
| --- | --- | --- |
| Submission | runs as soon as a worker is free | optional `scheduledAt`; a future time waits |
| Statuses | `QUEUED`, `RUNNING`, `RETRYING`, `SUCCEEDED`, `FAILED`, `DEAD_LETTERED` | plus **`SCHEDULED`** |
| Sorted sets | `distroq:jobs:delayed` (retry backoff) | plus **`distroq:jobs:scheduled`** (user-requested times) |
| Entry sources | `SUBMIT`, `RETRY`, `REPLAY`, `LEGACY_MIGRATION` | plus **`SCHEDULED`** |
| Pollers | retry sweep, recovery sweep | plus the **scheduled-job promoter** |
| Metrics | `delayedDepth` | plus `scheduledDepth`, `scheduledDepthByPriority` |
| Migrations | V1–V4 | plus **V5** (`jobs.scheduled_at`) |

What did **not** change: the priority model, the retry and backoff policy, the delayed sorted set,
the DLQ and replay endpoints, the consumer-group delivery path, and the three known dual-write
windows. Scheduling adds a fourth instance of the same dual write rather than fixing any of them.
See *Known limitations*.

## What changed in v0.5

v0.4 delivered jobs with a blocking multi-key `BRPOP` over three Redis Lists. A list pop is
destructive: the moment an ID leaves the list, Redis has no record that anyone was holding it. A
worker that died mid-job therefore took the job with it, leaving a row stuck at `RUNNING` forever
and nothing to recover from.

Redis Streams keep a delivered entry in the consumer group's Pending Entries List until the
consumer calls `XACK`. That single difference is the whole of v0.5:

| | v0.4 (Lists) | v0.5 (Streams) |
| --- | --- | --- |
| Delivery | `BRPOP` across three keys | `XREADGROUP` per tier, `>` |
| After delivery | the ID is gone | the entry is pending, owned, with an idle timer |
| Completion | implicit | explicit `XACK`, after the database write |
| Crash mid-job | job lost, row stuck `RUNNING` | entry stays pending, reclaimed by `XAUTOCLAIM` |
| Inspection | `LLEN` only | `XPENDING`, `XINFO GROUPS`, `XINFO CONSUMERS`, `XRANGE` |
| Guarantee | at-most-once *delivery* in a crash | **at-least-once delivery** |

What did **not** change: the priority model, the retry and backoff policy, the delayed sorted set,
the DLQ and replay endpoints, and the three known dual-write windows. See *Known limitations*.

## Redis Streams

### Keys

```
distroq:jobs:stream:high      # one stream per tier, derived from distroq.stream-key
distroq:jobs:stream:normal
distroq:jobs:stream:low
distroq:jobs:delayed          # unchanged: one sorted set for all tiers, members "<TIER>:<uuid>"
distroq:jobs:scheduled        # v0.6: user-requested execution times, same member format
```

Three streams rather than one. A single stream would still give acknowledgements, but a HIGH job
submitted after a backlog of LOW ones would sit behind them — a stream is append-ordered and has
no notion of priority, so overtaking would need another scheduling layer on top. One stream per
tier keeps the v0.4 policy model intact at the cost of three consumer-group registrations and a
read strategy that has to visit them in order. See `NOTES.md`.

### Consumer group

One group name, created separately on each stream:

```
XGROUP CREATE distroq:jobs:stream:high   distroq-workers 0 MKSTREAM
XGROUP CREATE distroq:jobs:stream:normal distroq-workers 0 MKSTREAM
XGROUP CREATE distroq:jobs:stream:low    distroq-workers 0 MKSTREAM
```

The name is shared but Redis keeps a **separate group and Pending Entries List per stream** — one
name is not one queue. `JobStreamInitializer` runs this at startup and treats a `BUSYGROUP` reply
as success, so restarting is a no-op rather than a startup failure. `MKSTREAM` is what makes it
work against a clean Redis, where the stream key does not exist yet.

### Consumer names

Each application instance takes one name, `<prefix>-<8 random hex>`, e.g. `worker-9dc4b5b0`. The
prefix is `distroq.streams.consumer-name-prefix`; the suffix is generated per process and is not
configurable.

That name is also the `workerId` written to `job_attempts`, so a pending entry in Redis and an
attempt row in PostgreSQL can be tied together by string equality.

Two instances must **never** share a consumer name. Ownership in a consumer group is by name: two
processes answering to `worker-1` would each see the other's in-flight entries as their own, and
neither reclaim nor `XPENDING` would mean anything.

### Stream entries

Redis-generated IDs (`*`), four fields, no payload:

```
1788795125064-0
  jobId       57bf8cfc-d4aa-4dea-bc31-028e6f8e88b7
  priority    HIGH
  enqueuedAt  1788795125062
  source      SUBMIT | RETRY | SCHEDULED | REPLAY | LEGACY_MIGRATION
```

`priority` is duplicated from PostgreSQL deliberately: the retry-promotion script needs it to pick
a destination stream without a per-job database lookup, and it makes `XRANGE` readable during an
incident. If it disagrees with the database, **PostgreSQL wins** — the worker logs an integrity
warning naming both values and executes at the database's tier.

`source` exists because the worker's decision for a `RETRYING` job depends on where the entry came
from, and from v0.6 because a `SCHEDULED` entry means "this job's requested time has arrived"
rather than "run it now". See *Duplicate deliveries* and *Scheduled jobs*.

There is **no `MAXLEN` trimming**. A trimmed entry that is still pending cannot be inspected or
reclaimed, and inspection is the point. The streams therefore grow without bound in v0.5 — a real
operational limitation, listed under *Known limitations*.

### Reading

`XREADGROUP GROUP distroq-workers <consumer> COUNT 1 STREAMS <stream> >` — never plain `XREAD`,
which creates no pending entry and so cannot be acknowledged, inspected or reclaimed.

`PriorityStrategy` is unchanged from v0.4 and still answers the only question it ever answered:
in what order should the tiers be attempted. `JobStreamConsumer` then does non-blocking reads in
that order — HIGH, then NORMAL, then LOW — stopping at the first tier that answers. Only when all
three are empty does it issue one bounded blocking `XREADGROUP BLOCK` across all three.

The cost is up to four round trips on an idle queue where v0.4 needed one. What it buys is that a
tier is skipped only after Redis has said it is empty, which is as close to v0.4's guarantee as
three independent streams allow. Nothing is ever discarded: an entry returned by `XREADGROUP` is
already in this consumer's PEL, so every entry a read returns is processed, in tier order.

### Acknowledgement

`XACK` is always **last**, after the database already describes the outcome:

| Path | Order |
| --- | --- |
| Success | read → execute → close the attempt row `SUCCESS` → job `SUCCEEDED` → **`XACK`** |
| Retryable failure | read → execute → close the attempt row `FAILURE` → job `RETRYING` → schedule the delayed retry → **`XACK`** |
| Exhausted | read → execute → close the attempt row `FAILURE` → job `DEAD_LETTERED` + `dead_letters` row (one transaction) → **`XACK`** |

A crash anywhere before the `XACK` costs a redelivery, never a lost job.

### At-least-once delivery is not exactly-once execution

> `XACK` stops Redis re-delivering an entry that has been acknowledged. It does nothing about the
> job's external side effects. A worker can charge a card, send an email, or write to a third-party
> API and then die before acknowledging; the entry is still pending, another worker reclaims it,
> and the side effect happens twice.
>
> Making that safe requires the *job* to be idempotent — a deduplication key checked and recorded
> in the same transaction as the effect. That is **v0.7**. v0.5 gives you at-least-once delivery
> and honest visibility into duplicates; it does not give you exactly-once execution, and no
> configuration of it will.

Three terms, kept distinct throughout this repository:

- **At-least-once delivery** — every entry is delivered to some consumer one or more times until
  acknowledged. This is what v0.5 provides.
- **Exactly-once execution** — the job body runs precisely once. Not provided, and not achievable
  by a message broker alone.
- **Idempotent processing** — running the job body twice has the same observable effect as running
  it once. A property of the job, not of the queue. v0.7.

### Duplicate deliveries

A redelivered entry may refer to a job that has already moved on. Before executing anything, the
worker loads the job and decides from its status:

| Job status | Decision |
| --- | --- |
| `SUCCEEDED` | Stale. `XACK`, do not execute. |
| `DEAD_LETTERED` | Stale. `XACK`, do not execute. |
| `FAILED` (legacy) | Terminal. `XACK`, do not execute. |
| `RETRYING`, entry `source=RETRY` and stamped at or after `nextAttemptAt` | This *is* the scheduled retry. Execute. |
| `RETRYING`, anything else | Superseded — most often the original delivery of a worker that scheduled the retry and then died. `XACK`, do not execute; the delayed set will produce the real entry on time. |
| `RUNNING` | Reclaim. See below. |
| `QUEUED` | Normal first delivery. Execute. |
| no such job | `XACK`. Nothing will ever make it runnable, and leaving it pending would have every future recovery sweep pick it up again. |

**The rule for a redelivered `RUNNING` job:** whoever holds the entry owns the current attempt.
Any attempt row still `IN_PROGRESS` is closed as `ABANDONED` with the reclaiming consumer named in
its error message, a new attempt is started, and `attemptCount` **advances rather than resetting**
— so the history stays one ordered sequence and the retry budget is not silently refilled. This
can execute the job twice; that is at-least-once delivery working as designed.

### Attempt outcomes

`job_attempts` rows are now written **before** the attempt runs, not after it finishes:

| Outcome | Meaning |
| --- | --- |
| `IN_PROGRESS` | Opened at the start of an attempt. A row stuck here means the worker never came back. |
| `SUCCESS` | Closed normally. |
| `FAILURE` | Closed with an error. |
| `ABANDONED` | The delivery was reclaimed by another consumer while this attempt was still open. |

> `ABANDONED` means the worker did not report a success or a failure before the delivery was
> reclaimed. It does **not** prove the job performed no external side effects — that is precisely
> what nobody can know, and precisely why v0.7 exists.

Neither new value needed DDL: v0.2 dropped the CHECK constraint Hibernate had generated over this
enum, for exactly this reason.

### Recovering abandoned work

`PendingEntryRecovery` runs once a second and, per tier, issues:

```
XAUTOCLAIM <stream> distroq-workers <consumer> <claim-min-idle-ms> <cursor> COUNT <claim-batch-size>
```

Entries idle longer than the threshold are transferred to this consumer and pushed through the
**same** `Worker.handle` path as a fresh delivery — there is no second execution path, so the
duplicate-delivery rules above apply to reclaimed entries automatically. The scan resumes from the
cursor `XAUTOCLAIM` returns and stops when it comes back as `0-0`.

Reclaims are logged at WARN with the stream, entry ID, job ID, previous owner, new owner, idle
time and delivery count:

```
Reclaimed entry 1788795125064-0 on distroq:jobs:stream:high for job 57bf8cfc-...:
previous owner worker-one-12191346, new owner worker-two-b1230ceb, idle 10820ms, delivery count 2
```

> **Idle is not death.** Redis measures time since delivery, not liveness. An entry held by a
> perfectly healthy worker running a job longer than `claim-min-idle-ms` looks identical to one
> held by a corpse, and will be reclaimed and executed a second time. **`claim-min-idle-ms` must
> exceed your longest expected job duration.** The default of 10s suits the demo job types and
> nothing else.

The one case that *can* be told apart is a process reclaiming from itself: the worker tracks the
entries it is currently executing, and the recovery sweep skips those.

## Priority

Every job has one of three tiers. `NORMAL` is the default, and a request that omits `priority`
behaves exactly as it did in v0.3.

| Tier | Meaning |
| ---- | ------- |
| `HIGH` | Runs ahead of everything else waiting. |
| `NORMAL` | The default. |
| `LOW` | Runs last, but is guaranteed not to wait forever — see the guard below. |

Tiers are discrete rather than an integer score because neither a Redis list nor a Redis stream
has an ordering primitive beyond insertion position: an arbitrary integer would need a sorted set
(losing blocking delivery) or client-side scanning (losing atomicity). One key per tier keeps
both. The consequence is that there is no "priority 47" and no ordering *within* a tier beyond
arrival order. See `NOTES.md`.

### Submitting with a priority

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' `
  -Body '{"type":"sleep","payload":"800","priority":"HIGH"}'
```

Accepted case-insensitively (`high`, `HIGH`, `High`). Omitted, `null` or blank → `NORMAL`. Any
other value is a **400** whose body names the valid options; no job is created.

### Scheduling policy

After `distroq.priority.starvation-threshold` consecutive deliveries served above `LOW`
(default 10), one poll goes out with the tier order **reversed**, serving the lowest tier that has
work and resetting the counter.

This bounds starvation **in delivery count, not in wall-clock time** — ten slow HIGH jobs still
mean a long wait for a LOW one — and it is deliberately **not** a fairness guarantee. Priority is
also **not preemptive**: it decides what runs next, never what stops running, so a job already
executing always finishes first.

**Where v0.5 is weaker than v0.4.** In v0.4 a single `BRPOP` chose the highest non-empty tier
atomically. v0.5 has no equivalent single call, so the order is enforced by the worker's read
strategy instead. When every tier is empty the worker blocks on all three at once, and the first
entry to arrive after that wakes the call regardless of tier — so the very first job after an idle
period is served in *arrival* order rather than *tier* order. Once anything is queued, the
sequential non-blocking reads restore strict order. Measured in practice, a HIGH job submitted
behind eleven waiting NORMAL jobs ran second overall, delayed only by the NORMAL job already
executing:

```
NORMAL   2026-09-07T15:20:16.746840Z   (already RUNNING when HIGH arrived)
HIGH     2026-09-07T15:20:17.583242Z
NORMAL   2026-09-07T15:20:17.725171Z
...ten more NORMALs
```

Priority is **immutable after submission**. There is no re-prioritise endpoint: the ID is already
committed to a tier-specific stream, and moving it would be a non-atomic write-then-delete that
Streams do not help with any more than Lists did. Cancel and resubmit instead.

### Upgrading from v0.4: legacy list migration

Nothing reads the v0.4 pending lists any more, so an ID left on one is a job sitting in PostgreSQL
as `QUEUED` that no worker will ever pick up. On startup, `JobStreamInitializer` drains all four
legacy keys into the streams with `source=LEGACY_MIGRATION`:

| Legacy key | Destination |
| --- | --- |
| `distroq:jobs:pending:high` | `distroq:jobs:stream:high` |
| `distroq:jobs:pending:normal` | `distroq:jobs:stream:normal` |
| `distroq:jobs:pending:low` | `distroq:jobs:stream:low` |
| `distroq:jobs:pending` (the v0.3 key, no tier) | `distroq:jobs:stream:normal` |

The count per key is logged at WARN, and the lists are re-checked afterwards.

**This is not transactional, and is not claimed to be.** A pop and an `XADD` are two commands and
the process can die between them. The migration narrows the window to the safe side by using
`LMOVE` onto a per-tier parking list (`distroq:jobs:migration:pending:<tier>`) first:

```
LMOVE  <legacy list>  <parking list>  RIGHT LEFT     # the ID is on exactly one list at all times
XADD   <tier stream>  ...  source=LEGACY_MIGRATION
LREM   <parking list> 1 <id>
```

A crash after the `XADD` and before the `LREM` produces a **duplicate stream entry** on the next
startup — which the duplicate-delivery rules already handle — rather than a lost job. Anything
found parked at startup is republished before the drain begins, so an interrupted migration
finishes itself.

## How retries work

When `JobExecutor.execute` throws, the worker checks `job.hasAttemptsRemaining()`
(`attemptCount < maxAttempts`).

- **Attempts left.** `BackoffPolicy.delayFor(attemptCount)` produces a delay —
  `base * 2^(attempt-1)`, capped, then jittered by ±20%. The job goes to `RETRYING` with
  `nextAttemptAt = now + delay`, and its ID is added to a Redis **sorted set**
  (`distroq:jobs:delayed`) scored by that due instant. The current stream entry is then
  acknowledged: it has done its job, and the delayed set owns what happens next.
- **No attempts left.** The job goes to `DEAD_LETTERED` and a row is written to
  `dead_letters`. Both writes happen in one transaction. See *Dead-letter queue* below.

`RetryScheduler` runs on a Spring `@Scheduled` fixed delay (1s by default). Each tick it calls
`JobQueue.promoteDueJobs`, a Lua script that atomically ranges the sorted set for members due at
or before now, removes them, and **`XADD`s** each one onto the stream for its own tier with
`source=RETRY`. The delayed set is a single key with no tier of its own, so each member carries
its tier as a `HIGH:<uuid>` prefix — that keeps range + `ZREM` + `XADD` inside one atomic script
where a per-job database lookup would not.

`XADD *` inside a script is safe on Redis 5+: scripts replicate by their effects, so a replica
receives the ID the primary generated rather than generating its own.

> The script is atomic **within Redis**. It is not atomic across systems: the job's `RETRYING` row
> was committed to PostgreSQL by a different write, at a different time, and either can fail
> without the other. Streams did not fix the dual-write problem — see *Known limitations*.

Because the delay lives in Redis rather than in a `Thread.sleep` or a `ScheduledExecutorService`,
a retry scheduled for 8 seconds from now still fires if the application restarts in the meantime.

The poller moves IDs only; it does not touch job status. A job stays `RETRYING` until the worker
picks it up and sets `RUNNING`, so there is no transient `QUEUED` flicker.


## Scheduled jobs

A submission may name the time it should run:

```json
{
  "type": "sleep",
  "payload": "1000",
  "priority": "HIGH",
  "scheduledAt": "2026-09-07T15:30:00Z"
}
```

```text
POST /api/jobs with scheduledAt
        |
        v
PostgreSQL job row: SCHEDULED
        |
        v
Redis Sorted Set: distroq:jobs:scheduled
        |
        | when due
        v
Redis priority Stream          (source=SCHEDULED)
        |
        v
XREADGROUP
        |
        v
Worker execution
```

Everything after the stream is the ordinary v0.5 path. A scheduled job is not a special kind of
job once it is running: it retries, dead-letters, is replayed and is recovered exactly like any
other.

### `SCHEDULED` is not `RETRYING`, and not `QUEUED`

Three different reasons a job can be waiting, and they are three different statuses because they
mean three different things to whoever is looking at the queue.

| Status | Why it is waiting | Which Redis key holds it | Has it ever run? |
| --- | --- | --- | --- |
| `SCHEDULED` | the submitter asked for a future time | `distroq:jobs:scheduled` | no |
| `QUEUED` | ready now, waiting for a free worker | a priority stream | maybe (a replay is `QUEUED`) |
| `RETRYING` | an attempt failed and the backoff has not elapsed | `distroq:jobs:delayed` | yes |

Reusing `QUEUED` for a job due next Tuesday would make queue depth a lie — it would report work
that no worker could pick up even if every worker were idle, and an autoscaler reading it would
start capacity for nothing. Reusing `RETRYING` would be worse: it would report a failure that
never happened, and every "how many jobs are failing right now" panel would be wrong.

### The scheduled sorted set

```
ZADD distroq:jobs:scheduled NX <epochMillis> <TIER>:<uuid>
```

A **separate key** from `distroq:jobs:delayed`, not a second use of it. The mechanism is identical
— that part is shared code — but the meaning is not, and merging them would tie two independently
tunable behaviours together forever and make `delayedDepth` unanswerable. See `NOTES.md`.

The member carries its tier for the same reason the delayed set's members do: the sorted set is a
single key with no tier of its own, so promotion has to learn the destination from the member
itself if range + `ZREM` + `XADD` are to stay inside one atomic script.

`ZADD` is issued with **`NX`**. Rescheduling is out of scope in v0.6, so the only thing that can
produce a second `ZADD` for a job already in the set is a repeat of a request that has already
been accepted — and silently moving that job's execution time is the worse of the two failures. A
duplicate schedule is therefore an **idempotent no-op**, logged at WARN, that leaves the original
score untouched.

### Promotion

`ScheduledJobPromoter` runs on a Spring `@Scheduled` fixed delay (1s by default) and calls the
same Lua script the retry sweep uses, against a different key and with a different `source` label.
The script ranges the set for members scored at or before now, removes each one, and `XADD`s it
onto the stream for its own tier:

```lua
local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
local tiers = #KEYS - 1
local dest = {}
for i = 1, tiers do
  dest[ARGV[i + 2]] = KEYS[i + 1]
end
local fallbackTier = ARGV[tiers + 3]
local source = ARGV[tiers + 4]
local moved = 0
for i = 1, #due do
  local member = due[i]
  if redis.call('ZREM', KEYS[1], member) == 1 then
    local sep = string.find(member, ':', 1, true)
    local id = member
    local tier = fallbackTier
    if sep then
      local parsed = string.sub(member, 1, sep - 1)
      if dest[parsed] then
        tier = parsed
        id = string.sub(member, sep + 1)
      end
    end
    redis.call('XADD', dest[tier], '*',
      'jobId', id,
      'priority', tier,
      'enqueuedAt', ARGV[1],
      'source', source)
    moved = moved + 1
  end
end
return moved
```

`KEYS[1]` is the scheduled set and `KEYS[2..]` the three tier streams in `Priority` declaration
order; the tier names arrive as `ARGV` in the matching order. **The script never builds a Redis
key name or an enum name itself**, and it never touches PostgreSQL. A member it cannot recognise
goes to the default tier rather than stopping the sweep, and the worker corrects the tier against
the database on arrival.

The `ZREM` return value is what decides ownership: only the caller whose `ZREM` returned 1 writes
the `XADD`, so no member can be promoted twice even with several processes running the script.

The promoter never executes a job, never opens an attempt row and never writes a job status. All
of that belongs to the worker that receives the entry.

### The worker's side

An entry with `source=SCHEDULED` for a job that is still `SCHEDULED` means its time has come. The
worker re-reads `scheduledAt` from PostgreSQL rather than trusting the sorted-set score that
caused the promotion, moves the job `SCHEDULED -> QUEUED`, and then runs the ordinary attempt
lifecycle. Promotion is **not** an attempt: no attempt row, no `attemptCount` change, and
`scheduledAt` is kept.

If the database says the job is **not yet due** — only reachable through clock skew or a
hand-written `ZADD` — the worker does nothing at all: it does not execute, does not create an
attempt, does not change any status, and above all does not write a second sorted-set member. The
entry is simply left **pending**, so `XAUTOCLAIM` redelivers it after `claim-min-idle-ms` and it
is re-evaluated then. That costs one reclaim cycle of latency and a WARN per attempt, and it is
the only option that neither runs early, nor loses the job, nor duplicates its schedule.

### Timestamps

Accepted: ISO-8601 with an **explicit** offset or `Z`.

```
2026-09-07T15:30:00Z
2026-09-07T17:30:00+02:00
2026-09-07T10:30:00-05:00
```

All three are the same instant, and after parsing they are indistinguishable — the same
`java.time.Instant`, the same `timestamptz` value, the same sorted-set score, the same API
response. The offset describes how the request was written, not when the job should run.

| Input | Result |
| --- | --- |
| omitted | immediate |
| JSON `null` | immediate |
| `""` or whitespace | **400** — omitting the field already means "now", so blank cannot also mean it |
| `2026-09-07T15:30:00` | **400** — no offset; reading it would require guessing a timezone |
| `2026-09-07 15:30:00` | **400** — not ISO-8601 |
| `1788715215167` | **400** — epoch millis is a second wire format for one field |
| `not-a-date` | **400** |

```json
{
  "status": 400,
  "message": "scheduledAt must be an ISO-8601 timestamp with an explicit UTC offset, e.g. 2026-09-07T15:30:00Z or 2026-09-07T17:30:00+02:00, got '2026-09-07T15:30:00'"
}
```

Every check runs **before the first write**, so a rejected submission leaves no PostgreSQL row and
no Redis member behind.

`LocalDateTime`, the JVM default zone and the PostgreSQL server zone are all deliberately unused.
Any of them would make "run this at 15:30" mean different things on different hosts, and none of
it would be visible in the response. Verified rather than assumed: the application in the
transcript below runs with a JVM default of `India Standard Time` against a PostgreSQL server set
to `UTC`, and the stored value round-trips unchanged.

### A past timestamp runs immediately

`scheduledAt <= now` is not an error — it is a request that is already due. The job is persisted
as `QUEUED`, `XADD`ed straight onto its tier's stream with `source=SUBMIT`, and **never touches
the scheduled sorted set**. Parking it there only to promote it on the next tick would add up to a
poll interval of latency for nothing.

The original timestamp is still recorded on the row. It is what was asked for, and it stays
visible.

### Scheduling is best-effort

The requested time is a **floor, not a guarantee**. A job starts at some point at or after it,
delayed by:

- **the poll interval** — the dominant term, and the only one this configuration controls. Mean
  contribution is half of `distroq.scheduling.poll-interval-ms`, worst case is all of it
- Redis latency for the `ZRANGEBYSCORE` + `ZREM` + `XADD` script
- stream delivery: the worker's next `XREADGROUP` has to come round
- worker availability: there is one worker thread per process
- higher-priority work: a HIGH backlog is served before a promoted LOW job

Measured on the transcript below with the default 1s poll interval: **518 ms** from requested time
to `startedAt` for an idle HIGH job, and **624 ms** for one promoted immediately after an
application restart.

### `scheduledAt` is history, not working state

Once set, it is never changed. Not by promotion, not by a retry, not by dead-lettering, not by
replay. It is immutable in v0.6 — there is no setter on the entity and no reschedule endpoint.

`nextAttemptAt` remains exclusively retry timing. A scheduled job that has failed once carries
both, and they answer different questions:

```json
{
  "status": "RETRYING",
  "scheduledAt": "2026-09-07T18:08:46Z",     // what was asked for
  "nextAttemptAt": "2026-09-07T18:08:50.7Z"  // when attempt 3 is due
}
```

### Retry, DLQ and replay for a scheduled job

Once a scheduled job starts, everything downstream is the v0.5 behaviour unchanged:

```text
SCHEDULED
  -> source=SCHEDULED stream entry -> RUNNING
  -> RETRYING -> distroq:jobs:delayed -> source=RETRY stream entry -> RUNNING
  -> SUCCEEDED | DEAD_LETTERED
```

- Retries use the **retry** sorted set and `source=RETRY`. A job never returns to `SCHEDULED`.
- The priority is unchanged at every hop.
- Attempt history contains only real execution attempts — the promotion is not one of them.
- A scheduled job that exhausts its retries enters the DLQ normally, and `scheduledAt` stays
  visible in both `GET /api/jobs/{id}` and the DLQ responses.

**Replay is immediate.** `POST /api/jobs/{id}/retry` goes straight onto the job's priority stream
with `source=REPLAY` and creates **no** scheduled sorted-set member, even when the original job
had a future `scheduledAt`.

> `scheduledAt` describes the original execution request. Replaying a dead-lettered job
> is a new operator action and is intentionally immediate.

Re-honouring a timestamp that has almost always already passed would either run the job
immediately anyway or, for a genuinely future one, strand an operator's deliberate intervention
until a time they were not asked about.

### Restart durability

The schedule lives in Redis, so it survives the application dying. The transcript below kills the
process outright (not a graceful stop) while a job is `SCHEDULED`, confirms the sorted-set member
is still there with the application gone, restarts before the target time, and the job runs once,
on time, with a single attempt row.

What this does **not** survive is Redis losing the key. The scheduled set is subject to whatever
persistence the Redis deployment is configured for, and the `docker-compose.yml` here configures
none.


## Dead-letter queue

A job whose retries are exhausted becomes `DEAD_LETTERED` and gets a row in the
`dead_letters` table. The table exists rather than the status alone because replay needs
metadata that does not belong on `jobs` — `moved_at`, `replayed`, `replayed_at`,
`replay_count` — and because "what is currently in the DLQ" should be a small dedicated
table rather than a filtered scan of an ever-growing `jobs` table.

The row is keyed by `job_id`, so a job that is replayed and dead-lettered again **updates**
its row instead of inserting a second one. `replay_count` therefore survives and identifies
repeat offenders.

### Replay semantics

`POST /api/jobs/{id}/retry` puts a dead-lettered job back on the pending list. Replay
**continues** the job's history rather than resetting it:

| | Behaviour on replay |
| --- | --- |
| `attemptCount` | **Not reset.** A job that failed 3 times runs next as attempt 4. |
| `maxAttempts` | **Extended**, not reset: `attemptCount + distroq.dlq.replay-attempts`. A job at 3/3 replayed with a budget of 3 becomes 3/6. |
| `priority` | **Preserved.** A dead-lettered LOW job is re-enqueued to the low tier, not the default one. |
| `job_attempts` rows | **Preserved.** New attempts append with increasing `attempt_number`. |
| the `dead_letters` row | **Retained**, marked `replayed = true` with `replay_count` incremented. Never deleted. |

The point is that `GET /api/jobs/{id}` still tells a coherent story afterwards — "failed
twice, dead-lettered, replayed, succeeded on attempt 3". A reset would erase the evidence of
why the job was ever a problem, which is the entire reason for having a DLQ.

Extending `maxAttempts` is not cosmetic: without it a replayed job is already out of budget
and dies on its first attempt back.

Replay goes straight onto the job's **priority stream** with `source=REPLAY`, not into the delayed
set — it is an explicit operator action, so making them wait out a backoff window they did not ask
for would be surprising.

Replaying a job that is not `DEAD_LETTERED` is a **409**, naming the status the job is
actually in. An unknown job ID is a **404**. Neither is treated as a no-op.

There is deliberately **no automatic or bulk replay** in this version. Replay is explicit and
per-job.

## Prerequisites

- Java 21
- Docker (for Redis and PostgreSQL)

## Start the infrastructure

```powershell
docker compose up -d
```

This starts:

| Service    | Container         | Host port |
| ---------- | ----------------- | --------- |
| Redis 7    | `distroq-redis`   | 6379      |
| Postgres16 | `distroq-postgres`| **5433**  |

Postgres is mapped to 5433 deliberately, to avoid colliding with a native install.
Data lives in the named volume `distroq-pgdata`, so completed jobs survive restarts.

## Run the app

```powershell
.\mvnw.cmd spring-boot:run
```

Flyway creates the `jobs` and `job_attempts` tables on first boot. No manual SQL, and no
`docker compose down -v`, is needed on any path — including upgrading a database that
predates Flyway.

## Database migrations

The schema is defined by versioned SQL in `src/main/resources/db/migration`, and Hibernate
runs with `ddl-auto: validate` — it checks that the entities match the schema at startup and
**fails fast** if they do not, but it never modifies anything. Flyway is the only thing that
writes DDL.

| Migration | Contents |
| --------- | -------- |
| `V1__initial_schema.sql` | `jobs` and `job_attempts` as `ddl-auto: update` left them at the end of v0.2. A single honest baseline, not a reconstruction — the per-version history is in git. |
| `V2__add_query_indexes_and_drop_enum_check.sql` | Indexes for the three existing queries, and dropping the last Hibernate-generated enum CHECK. |
| `V3__dead_letters.sql` | The `dead_letters` table with its two indexes, plus the foreign key `job_attempts` never had. Contains **no** DDL for `DEAD_LETTERED` — see below. |
| `V4__job_priority.sql` | `jobs.priority` as `varchar(255) NOT NULL DEFAULT 'NORMAL'`, plus `idx_jobs_priority`. The DB-level default backfills every pre-v0.4 row, which is the correct reading of a job submitted before priority existed. |
| `V5__scheduled_jobs.sql` | `jobs.scheduled_at` as a **nullable** `timestamp(6) with time zone`, plus `idx_jobs_scheduled_at`. Nullable because an immediate job was never scheduled at all, and NULL is the only value that says so. |
| `V6__outbox_idempotency_and_execution_leases.sql` | `outbox_events` and `idempotency_keys`, plus `execution_owner`, `execution_lease_until`, `active_attempt_id` and `version` on `jobs`. |
| `V7__reliability_reconciliation_and_effects.sql` | The outbox lifecycle columns (`status`, `operator_retry_count`, `terminal_failed_at`, `last_operator_retry_at`, `last_operator_reason`) and three indexes; `reliability_actions`; `job_effects`; `effect_counters`. Backfills `PUBLISHED` for rows that already published. |

**V7 adds no enum CHECK constraints,** consistent with V1–V6 and for the reason V1 documents.
`outbox_events.status`, `job_effects.status` and `reliability_actions.action_type` are all plain
`varchar`, so adding a lifecycle state later is a code change rather than a migration plus an
outage.

The backfill leaves a row that was terminal under v0.7's rules as `PENDING` on purpose. Terminality
under v0.7 was `attempt_count >= max-attempts`, and the migration has no way to know what
`max-attempts` was set to when the row failed. Letting the relay re-derive it from the setting
actually in force is both correct and self-healing: an event that is genuinely terminal goes back
to `FAILED` on its next tick, and one that is not simply publishes.

**V5 adds no constraint tying `status` to `scheduled_at`,** consistent with V1–V4. The invariant
"`SCHEDULED` implies a non-null `scheduled_at` in the future" is real but *time-dependent*: it
stops being true the instant the job is promoted, and a constraint that only holds at INSERT is
not a constraint. Enforcing it would also mean the database knowing about a seventh status value
— exactly the coupling V1 documents as having already caused one silent failure. The application
maintains the invariant instead; the worker treats a `SCHEDULED` job with a null `scheduled_at` as
due now, so the failure mode is "runs immediately", not "stuck forever".

The column type is `timestamp(6) with time zone`, matching `created_at`, `started_at`,
`finished_at` and `next_attempt_at` exactly. A plain `timestamp` — what PostgreSQL gives you if
you forget the qualifier — fails startup under `ddl-auto: validate` rather than silently storing a
local-time value, which is the last place a timezone could have crept back in.

Applied against the live database with fourteen existing rows: **`Successfully applied 1 migration
to schema "public", now at version v5`**, `SELECT count(*) FROM jobs` unchanged at 14 before and
after, no checksum errors.

**v0.5 added no migration.** It adds two `AttemptOutcome` values, `IN_PROGRESS` and `ABANDONED`,
and needs zero DDL to do it: `job_attempts.outcome` is `varchar(255)` with no CHECK constraint,
because V2 dropped the one Hibernate had generated. Verified rather than assumed:

```powershell
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'job_attempts'::regclass;"
#  job_attempts_pkey   | PRIMARY KEY (id)
#  fk_job_attempts_job | FOREIGN KEY (job_id) REFERENCES jobs(id)
```

This is the third time that decision has paid for itself — `jobs.status` gaining `DEAD_LETTERED`
in v0.3, `jobs.priority` in v0.4, and now `job_attempts.outcome` — and the same trade-off still
applies: nothing at the database level stops a bad value being written by something that is not
this application.

The new `IN_PROGRESS` row is written before the attempt runs, so `finished_at` is null for a
while. That column was already nullable in V1; no change was needed there either.

**Naming:** `V<n>__<snake_case_description>.sql`, two underscores before the description.
Flyway applies them in version order and records each in `flyway_schema_history`.

**Applied migrations are immutable.** Flyway stores a checksum of each file it ran and
refuses to start if the file changes afterwards. To alter the schema, add a new migration —
never edit an existing one. This is enforced, not a convention: editing `V2` after it has
run produces `Migration checksum mismatch for migration version 2` and the app will not boot.

**Adding one for a future version:**

1. Create `V3__whatever_you_are_doing.sql`.
2. Change the entities to match.
3. Start the app. Flyway applies V3, then Hibernate validates the entities against the
   result. A mismatch either way is a startup failure with the offending column named.

### Existing databases (created before Flyway)

Handled automatically by `baseline-on-migrate`. A database that already has the tables but
no `flyway_schema_history` is recorded as being at V1 rather than having V1 run against it,
so `CREATE TABLE` never executes over live data. Migrations above the baseline — V2 onward —
then apply normally.

This is why anything that must reach *both* new and existing databases has to live above the
baseline version: V1 is skipped entirely on a pre-existing database, so a change placed only
in V1 would silently never reach it.

### Why there is no CHECK constraint on `status`

v0.1's Hibernate-generated `jobs_status_check` pinned `status` to the four statuses that
existed at the time, and `ddl-auto: update` never widened it when `RETRYING` was added, so
every retry write was rejected at commit time. It is deliberately not recreated: the
application enum is the source of truth. The trade-off is that nothing at the database level
stops a bad status being written by something that is not this application. See `NOTES.md`.

v0.3 is where that pays off. Adding `DEAD_LETTERED` — byte-for-byte the change that broke
v0.2 — required **zero DDL**. V3 creates a table and adds a foreign key; it does not touch
`jobs` at all.

V4 adds a third enum-backed column, `priority`, and likewise gives it no CHECK constraint. Adding
a fourth tier later is therefore a one-line change to the `Priority` enum with **no migration at
all** — no `ALTER`, no coordinated deploy.

### The `job_attempts` foreign key

`job_attempts` had no FK to `jobs`, because `JobAttempt` stores a raw `UUID jobId` rather than
a `@ManyToOne` and Hibernate had no association to generate one from. V3 adds it explicitly,
after confirming zero orphan rows. The raw-UUID mapping is unchanged — this is a
database-level integrity guarantee, not an ORM relationship.

## Build

```powershell
.\mvnw.cmd clean package
```

## Configuration

Bound as a single `@ConfigurationProperties` record (`DistroqProperties`):

```yaml
distroq:
  queue-key: distroq:jobs:pending    # v0.4 lists; only the startup migration reads these now
  delayed-key: distroq:jobs:delayed
  scheduled-key: distroq:jobs:scheduled  # v0.6: user-requested execution times
  stream-key: distroq:jobs:stream    # base for :high / :normal / :low
  retry:
    default-max-attempts: 3      # used when the request omits maxAttempts
    base-delay-ms: 1000          # attempt 1 -> ~1s, 2 -> ~2s, 3 -> ~4s
    max-delay-ms: 60000          # cap, applied before jitter
    jitter-factor: 0.2           # +/-20% randomisation, to break up retry waves
    poll-interval-ms: 1000       # how often the delayed set is swept
    promote-batch-size: 100      # max jobs promoted per sweep
  dlq:
    replay-attempts: 3           # replay budget: maxAttempts becomes attemptCount + this
  priority:
    starvation-threshold: 10     # deliveries served above LOW before one poll is reversed
  streams:
    group-name: distroq-workers  # one name, one group per stream
    consumer-name-prefix: worker # a random per-process suffix is appended
    claim-min-idle-ms: 10000     # reclaim threshold - must exceed the longest job duration
    claim-batch-size: 100        # entries per XAUTOCLAIM call
    read-count: 1                # COUNT on XREADGROUP
    block-timeout-ms: 1000       # BLOCK on the fallback read, so shutdown is noticed promptly
    group-start-id: "0"          # groups start at the beginning of the stream, not its tail
  scheduling:
    poll-interval-ms: 1000       # how often the scheduled set is swept
    promote-batch-size: 100      # max scheduled jobs promoted per sweep
  outbox:
    poll-interval-ms: 500        # relay tick
    batch-size: 100              # events claimed per tick
    lock-duration-ms: 30000      # relay lease; a PUBLISHING row past this is reclaimable
    max-attempts: 100            # budget per operator generation, NOT a lifetime cap
    dedupe-retention-ms: 604800000   # TTL of the Redis "already published" marker
    published-retention-days: 30 # cleanup floor; the dedupe TTL can raise it
    failed-retention-days: 90    # documentation only - FAILED is never deleted automatically
    cleanup-interval-ms: 3600000
    cleanup-batch-size: 500
    relay-enabled: true
    fail-after-publish: false    # acceptance hook: publish, then fail before marking published
  reconciliation:
    enabled: true
    poll-interval-ms: 30000
    batch-size: 100              # bounds every query in a run; inspected == this means "look again"
    stale-scheduled-after-ms: 60000
    stale-outbox-after-ms: 60000
    stale-lease-after-ms: 60000
    auto-repair: false           # the CEILING on repairs; a request can ask for less, never more
    requeue-failed-outbox: false # off even when auto-repair is on
  effects:
    enabled: true
    stale-started-after-ms: 300000
    auto-fail-stale: false       # a STARTED effect is ambiguous, so closing one is opt-in
  admin:
    max-reason-length: 500       # X-Admin-Reason and the body reason
```

`outbox.max-attempts` is the relay budget for **one operator generation** of an event. The terminal
ceiling is `max-attempts × (operator_retry_count + 1)`, so an operator retry hands the event a
fresh budget without erasing the attempts it already made.

`outbox.dedupe-retention-ms` is compared against `published-retention-days` before a row is
deleted; see *Outbox retention*. Setting the marker TTL shorter than retention is the
configuration that risks a duplicate, which is why cleanup takes the longer of the two rather than
the configured one.

`reconciliation.auto-repair` is an upper bound, not a default. `POST /api/admin/reconciliation/run`
with `"autoRepair": true` against a deployment configured `false` runs a preview and says so in the
response.

`effects.auto-fail-stale` closes a stale `STARTED` row as `FAILED`, never as `COMPLETED`. Marking
it completed would assert an effect happened that nobody observed, and would suppress the retry
that is the only remaining way to make it happen.

`scheduling` mirrors the `retry` block rather than reusing it. The two pollers sweep different
sorted sets for different reasons, and tying retry-backoff resolution to user-scheduling
resolution would mean neither could be tuned without moving the other.

`scheduling.poll-interval-ms` is the **dominant term in scheduling latency**: a job is promoted on
the first tick at or after its due time, so the mean delay it contributes is half the interval and
the worst case is the whole of it. Lowering it costs one `ZRANGEBYSCORE` per tick against a set
that is usually empty. It does not make the requested time a hard guarantee — stream delivery and
worker availability are still in front of the job.

`starvation-threshold` is a **count of deliveries, not a duration**. See *Scheduling policy*.

`claim-min-idle-ms` is not only a recovery knob — it is also the point at which a healthy but slow
worker's entry starts looking abandoned to everyone else. Raise it above your longest job.

The three stream keys are derived from `stream-key` rather than configured separately, so they
cannot drift out of sync with the `Priority` enum. The same holds for the legacy list keys and
`queue-key`.

Also set, outside the `distroq` namespace:

```yaml
spring:
  task:
    scheduling:
      pool-size: 6               # one thread per @Scheduled sweep, so none can starve another
server:
  error:
    include-message: always      # otherwise the 409 body would not name the job's actual status
```

The scheduler pool was 3 in v0.6, because the recovery sweep executes reclaimed jobs on the
scheduler thread and a reclaimed thirty-second job would otherwise stall the retry and
scheduled-job sweeps for its whole duration. v0.8 raises it to 6 for the outbox relay,
reconciliation and retention cleanup.

That number is load-bearing during a Redis outage. Four of the six sweeps issue Redis commands, so
a disconnected Lettuce client can leave several threads blocked on command timeouts at once — and
the one sweep that must keep running in that situation is the relay, because it is the thing whose
job is to notice the outage and record it. If you shorten `spring.data.redis.timeout` to make an
outage fail fast, raise this to match.

Spring Boot omits the `message` field from error bodies by default, which silently discards
the `reason` on every `ResponseStatusException` the app throws. The cost is that unhandled
exception messages are also exposed; acceptable here because this is a local, unauthenticated
development service, and worth revisiting before anything is deployed.

## Running two instances

v0.5 does not add a separate worker service — that is v0.7 — but two copies of the same
application can be pointed at the same Redis and PostgreSQL, which is what the crash-recovery
demonstration needs. Only two settings have to differ:

```powershell
# terminal 1
java -jar target\distroq-0.0.1-SNAPSHOT.jar `
  --server.port=8080 --distroq.streams.consumer-name-prefix=worker-one

# terminal 2
java -jar target\distroq-0.0.1-SNAPSHOT.jar `
  --server.port=8081 --distroq.streams.consumer-name-prefix=worker-two
```

Both join `distroq-workers` on all three streams and compete for entries; Redis hands each entry
to exactly one consumer. Both also run their own retry sweep and recovery sweep, which is safe:
the promotion script is atomic, and `XAUTOCLAIM` transfers ownership atomically.

The prefixes are only for legibility — the random suffix already guarantees distinct names.

### Worker crash recovery, end to end

```
worker-one-12191346 is delivered a HIGH job; entry 1788795125064-0 becomes pending, owned by it
worker-one-12191346 is killed mid-execution, before XACK
the entry stays pending, still recorded as owned by worker-one-12191346, and its idle time grows
after claim-min-idle-ms, worker-two-b1230ceb reclaims it with XAUTOCLAIM
worker-two-b1230ceb closes attempt 1 as ABANDONED and starts attempt 2
worker-two-b1230ceb finishes the job, marks it SUCCEEDED, and only then calls XACK
```

Observed, with a 30-second job killed three seconds in:

```powershell
# owned by instance 1 while it runs
docker exec distroq-redis redis-cli XPENDING distroq:jobs:stream:high distroq-workers - + 10
# 1788795125064-0  worker-one-12191346  3418  1

# instance 1 killed; the entry is still pending and still attributed to the dead consumer
# 1788795125064-0  worker-one-12191346  9217  2

# after the idle threshold, ownership has moved
# 1788795125064-0  worker-two-b1230ceb  12564  3
```

and the resulting history:

```json
"attempts": [
  { "attemptNumber": 1, "workerId": "worker-one-12191346", "outcome": "ABANDONED",
    "errorMessage": "Delivery 1788795125064-0 reclaimed by worker-two-b1230ceb; worker-one-12191346 never reported an outcome" },
  { "attemptNumber": 2, "workerId": "worker-two-b1230ceb", "outcome": "SUCCESS" }
]
```

The job reached `SUCCEEDED`, the pending count returned to zero, and nothing was lost. It also ran
twice — see *At-least-once delivery is not exactly-once execution*.


## Job statuses

| Status      | Meaning                                                         |
| ----------- | --------------------------------------------------------------- |
| `SCHEDULED` | Waiting for a user-requested future execution time. **Has never run.** |
| `QUEUED`    | Ready to run, waiting for a free worker.                          |
| `RUNNING`   | Currently executing.                                              |
| `RETRYING`  | Failed, attempts remaining, waiting out its backoff window.        |
| `SUCCEEDED` | Terminal.                                                         |
| `FAILED`    | **Legacy.** See below.                                            |
| `DEAD_LETTERED` | Terminal — attempts exhausted, moved to the DLQ, replayable.  |

`RETRYING` is deliberately distinct from `QUEUED`: conflating them would make queue depth
meaningless and hide backoff entirely.

`SCHEDULED` is deliberately distinct from **both**. It is not `QUEUED`, because a job due next
Tuesday is not ready for execution and counting it as backlog would be misleading. It is not
`RETRYING`, because `RETRYING` means an attempt failed and another is pending, while a `SCHEDULED`
job has not had a first attempt at all. A job leaves `SCHEDULED` exactly once and never returns to
it — not through a retry, not through the DLQ, not through a replay. See *Scheduled jobs*.

### `FAILED` vs `DEAD_LETTERED`

Up to v0.2, `FAILED` was a job's resting state once its attempts ran out. From v0.3 it is
not: exhaustion goes to `DEAD_LETTERED`, which additionally means "there is a `dead_letters`
row for this job and it can be replayed". `FAILED` is now a *transient per-attempt outcome*
and nothing writes it as a terminal job state any more.

The value stays in the enum because v0.2 rows still carry it. Those rows are **not**
migrated: backfilling them would fabricate `dead_letters` entries for events that never went
through the DLQ path, inventing a `moved_at` that never happened. A `FAILED` job is not
replayable — `POST /api/jobs/{id}/retry` returns 409.

## Job types

| Type           | Payload                          | Behaviour                                        |
| -------------- | -------------------------------- | ------------------------------------------------ |
| `sleep`        | milliseconds, e.g. `"3000"`      | Sleeps. Defaults to 1000ms if null/blank/unparseable. |
| `always_fail`  | ignored                          | Always throws, by design.                        |
| `fail_n_times` | N as an integer, e.g. `"2"`      | Throws on attempts 1..N, succeeds afterwards. Defaults to 2 if null/blank/unparseable. |
| `fail_until_flagged` | ignored                    | Throws while a Redis flag key is set; succeeds once it is cleared. |
| `idempotent_counter` | counter name, e.g. `"orders:daily"` | v0.8. Increments a durable counter exactly once per `<job-id>:counter:<normalized-payload>`, however many times the job is delivered. Defaults to `default` if null/blank. |

`fail_n_times` is stateless — it derives its behaviour from `job.getAttemptCount()`, not
from any counter held in the executor. It is the job type that demonstrates retry
*recovery* rather than retry *exhaustion*.

### `fail_until_flagged` — demonstrating replay end to end

The executor sets `distroq:test:flag:<jobId>` on the job's first attempt and then throws for
as long as that key exists. It stands in for a broken downstream dependency. Clearing the
key is "the dependency got fixed", which is what makes the replay success path demonstrable:
without it the only replayable job is one that fails forever, which proves nothing.

```powershell
# 1. submit; it exhausts its attempts and lands in the DLQ
$g = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"fail_until_flagged","payload":"","maxAttempts":2}'

# 2. "fix the dependency" - clear the flag
docker exec distroq-redis redis-cli DEL "distroq:test:flag:$($g.id)"

# 3. replay -> succeeds on attempt 3, with attempts 1 and 2 still on record
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/jobs/$($g.id)/retry"
```

The flag is only ever set on attempt 1, so a replay does not re-break the job.

Any other type fails with an `IllegalArgumentException` naming the unknown type — and,
since that is an ordinary failure, it is retried before it becomes terminally
`DEAD_LETTERED`.

## API

| Method | Path                     | Notes                                                          |
| ------ | ------------------------ | -------------------------------------------------------------- |
| POST   | `/api/jobs`              | Returns **202 Accepted** — the work is accepted, not completed. `400` on an unknown `priority` or an unparseable `scheduledAt`. |
| GET    | `/api/jobs/{id}`         | Includes the full `attempts` history, `scheduledAt`, and (v0.8) the `effects` ledger for the job. `404` if unknown.         |
| GET    | `/api/jobs?status=&priority=` | Optional `JobStatus` (including `SCHEDULED`) and `Priority` filters, combinable; 50 most recent, newest first. Both are pushed into SQL, never filtered in memory. No attempts (avoids N+1). |
| POST   | `/api/jobs/{id}/retry`   | Replay a dead-lettered job at its original priority, **immediately** — never on its original schedule. **202**; `409` if not `DEAD_LETTERED`, `404` if unknown. |
| GET    | `/api/dlq`               | 50 most recent dead-letters, newest `moved_at` first. Optional `?replayed=true\|false`. Includes `priority` and `scheduledAt`. |
| GET    | `/api/dlq/{jobId}`       | Single entry with the full attempt history, `priority` and `scheduledAt`. `404` if not dead-lettered. |
| GET    | `/api/metrics`           | Queue, stream, delayed-set, scheduled-set, DLQ, outbox, reconciliation and effect counters — see below. |
| GET    | `/api/admin/outbox`      | v0.8. Outbox events with `status`/`eventType`/`aggregateId`/`createdAfter`/`createdBefore` filters and pagination. Payload is not returned. |
| GET    | `/api/admin/outbox/{eventId}` | v0.8. Full metadata plus a payload size and digest. `404` if unknown. |
| POST   | `/api/admin/outbox/{eventId}/retry` | v0.8. Re-arm a `FAILED` event. **202**; `409` if not `FAILED`, `404` if unknown, `400` without a valid reason. Requires `X-Admin-Reason`. |
| POST   | `/api/admin/outbox/cleanup` | v0.8. Retention sweep on demand. Requires `X-Admin-Reason`. |
| GET    | `/api/admin/reconciliation` | v0.8. Read-only findings preview, grouped by category. |
| POST   | `/api/admin/reconciliation/run` | v0.8. `{ "autoRepair": bool, "reason": "..." }`. Configuration is the ceiling. Requires `X-Admin-Reason`. |
| GET    | `/api/admin/reliability-actions` | v0.8. Audit history with `actionType`/`targetId` filters and pagination. |

`deadLetterCount` counts rows with `replayed = false` (currently sitting in the DLQ);
`replayedCount` counts rows with `replayed = true` (replayed and not since re-failed).

```json
{
  "queueDepth": 5,
  "queueDepthByPriority": { "HIGH": 0, "NORMAL": 0, "LOW": 5 },
  "delayedDepth": 0,
  "scheduledDepth": 1,
  "scheduledDepthByPriority": { "HIGH": 1, "NORMAL": 0, "LOW": 0 },
  "totalJobs": 10,
  "deadLetterCount": 1,
  "replayedCount": 0,
  "streamDepthByPriority": { "HIGH": 4, "NORMAL": 1, "LOW": 11 },
  "pendingEntriesByPriority": { "HIGH": 1, "NORMAL": 0, "LOW": 0 },
  "activeConsumers": 1
}
```

**Three different counts of "entries in a stream", because they answer three different questions.**
Conflating them is the easiest mistake to make with Streams, and the numbers above are a real
sample that shows why: the LOW stream had eleven entries and five jobs waiting.

| Metric | Question | Source | Exact? |
| --- | --- | --- | --- |
| `queueDepth`, `queueDepthByPriority` | How much work is waiting to be delivered? | the consumer group's `lag` from `XINFO GROUPS` | Exact while nothing is trimmed or deleted — and nothing in v0.6 trims or deletes. Redis reports `lag` as null once entries have been removed, in which case this reports 0 and logs at DEBUG. |
| `streamDepthByPriority` | How many entries does the stream hold? | `XLEN` | Exact, and **not a backlog**. It counts every entry the stream has ever been given, acknowledged or not, and only ever grows. |
| `pendingEntriesByPriority` | How much work has been handed out and not confirmed? | `XPENDING` summary | Exact. In-flight work plus anything abandoned and not yet reclaimed. |
| `delayedDepth` | How many **retries** are waiting out a backoff? | `ZCARD distroq:jobs:delayed` | Exact. Retry depth only — it does not include user-scheduled jobs. |
| `scheduledDepth`, `scheduledDepthByPriority` | How many jobs are waiting for a **user-requested time**? | `ZCARD` / `ZRANGE` on `distroq:jobs:scheduled` | Exact. Not backlog, not retries, not stream entries, not pending deliveries. |
| `activeConsumers` | How many consumers are holding work? | distinct consumers in the `XPENDING` summaries | Exact for that question, which is **not** "how many workers are registered". An idle worker holds nothing and does not appear; a dead worker still holding an unreclaimed entry does. |

> `XLEN` is not queue depth. `streamDepthByPriority` is the count that would grow forever on a
> healthy, fully drained system, and treating it as a backlog would page you at 3am about a queue
> that is empty.

**Four kinds of waiting, kept apart on purpose.** A scheduled job is not backlog: no worker could
run it even if every worker were idle. A retrying job is a *failure* signal; a scheduled one is
not. A stream entry is history. A pending delivery is work in flight. Adding scheduled jobs to
`queueDepth` would make an autoscaler start capacity for work that is not due; adding them to
`delayedDepth` would fire a retry-rate alarm because someone scheduled a report for midnight.

`scheduledDepthByPriority` groups by the tier encoded in the member. That is a `ZRANGE 0 -1` and a
count — O(N) in the number of jobs currently waiting on a time. There is no Redis command that
groups a sorted set by a member prefix, and the alternatives (three sets, or a companion hash)
would each add a write that is not atomic with the `ZADD` that matters. It is bounded by
outstanding scheduled work rather than by history, but it is a real cost at scale.

`queueDepth` and `queueDepthByPriority` keep their v0.4 names and meaning — "waiting to run" — so
anything already watching them keeps working. Their *source* changed from `LLEN` to consumer-group
lag.

`GET /api/dlq` joins to `jobs` for each entry's `type`, `status`, `attemptCount` and
`maxAttempts` — a listing of bare IDs would tell an operator nothing. The join is one
`WHERE id IN (...)` query for the whole page, not one lookup per dead-letter.

### Submit body

```json
{
  "type": "fail_n_times",
  "payload": "2",
  "maxAttempts": 5,
  "priority": "HIGH",
  "scheduledAt": "2026-09-07T15:30:00Z"
}
```

`maxAttempts` is optional. Omitted or `null` → the configured default (3). Any value below
1 is rejected with **400** and no job is created.

`priority` is optional and case-insensitive. Omitted, `null` or blank → `NORMAL`. Anything that is
not a tier name is rejected with **400** naming the valid values, and no job is created:

```json
{ "status": 400, "message": "priority must be one of HIGH, NORMAL, LOW (case-insensitive), got 'urgent'" }
```

It is bound as a `String` rather than the enum precisely so that this message is the one the
caller sees, instead of Jackson's deserialization error about a type they have never heard of.

`scheduledAt` is optional. Omitted or `null` → immediate. A time in the past or equal to now →
immediate, with the requested value still recorded. A future time → `SCHEDULED`. Blank or
unparseable → **400**, and no job is created. It is bound as a `String` for the same reason
`priority` is, and a stronger one: bound as an `Instant`, Jackson accepts `2026-09-07T15:30:00Z`
but rejects `2026-09-07T17:30:00+02:00` — a perfectly valid ISO-8601 instant — before the
controller ever sees it. See *Scheduled jobs*.

### Job detail response

Adds `priority`, `maxAttempts`, `nextAttemptAt` (non-null only while `RETRYING`), `scheduledAt`
(non-null only for a job that was submitted with one) and `attempts`:

```json
{
  "status": "SUCCEEDED",
  "priority": "HIGH",
  "attemptCount": 3,
  "maxAttempts": 5,
  "nextAttemptAt": null,
  "scheduledAt": "2026-09-07T18:08:46Z",
  "attempts": [
    { "attemptNumber": 1, "workerId": "worker-1a2b3c4d", "outcome": "FAILURE", "errorMessage": "...", "durationMs": 2 },
    { "attemptNumber": 2, "workerId": "worker-1a2b3c4d", "outcome": "FAILURE", "errorMessage": "...", "durationMs": 1 },
    { "attemptNumber": 3, "workerId": "worker-1a2b3c4d", "outcome": "SUCCESS", "errorMessage": null, "durationMs": 0 }
  ]
}
```

`scheduledAt` and `nextAttemptAt` are never the same field. The first is what was asked for and
never changes; the second is when the next automatic retry is due and is cleared as soon as that
attempt starts.

The `jobs` row holds only the *most recent* error; `job_attempts` holds the history, one
row per execution attempt, recorded on both success and failure.

## Verification (PowerShell)

```powershell
# 1. Submit a 3-second job -> 202, status QUEUED
$job = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"sleep","payload":"3000"}'

# 2. Within ~1s it is QUEUED or RUNNING
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($job.id)"

# 3. After ~4s -> SUCCEEDED, attemptCount 1, durationMs roughly 3000
Start-Sleep -Seconds 4
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($job.id)"

# 4. Failure path -> DEAD_LETTERED with a non-null errorMessage; app stays alive
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"always_fail","payload":""}'

# 5. Burst of 10 drains sequentially, ~500ms apart; queueDepth rises then falls to 0
1..10 | ForEach-Object {
  Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
    -ContentType 'application/json' -Body '{"type":"sleep","payload":"500"}'
} | Out-Null
Invoke-RestMethod -Uri http://localhost:8080/api/metrics

# 6. Unknown job type -> retried, then DEAD_LETTERED, message names the type
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"nonsense","payload":""}'

# 7. Retry recovery -> fails twice, SUCCEEDED on attempt 3, three attempt records
$r = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"fail_n_times","payload":"2","maxAttempts":5}'
Start-Sleep -Seconds 8
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($r.id)" | ConvertTo-Json -Depth 4

# 8. Retry exhaustion -> DEAD_LETTERED after 3 attempts, nextAttemptAt null, DLQ entry present
$f = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"always_fail","payload":"","maxAttempts":3}'
Start-Sleep -Seconds 15
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($f.id)" | ConvertTo-Json -Depth 4
Invoke-RestMethod -Uri "http://localhost:8080/api/dlq" | ConvertTo-Json -Depth 4

# 8a. Replay to success -> SUCCEEDED on attempt 3, first two FAILUREs preserved, maxAttempts 5
$g = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"fail_until_flagged","payload":"","maxAttempts":2}'
Start-Sleep -Seconds 10
docker exec distroq-redis redis-cli DEL "distroq:test:flag:$($g.id)"
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/jobs/$($g.id)/retry"
Start-Sleep -Seconds 5
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($g.id)" | ConvertTo-Json -Depth 5

# 8b. Replaying a non-dead-lettered job -> 409 naming the actual status
try { Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/jobs/$($g.id)/retry" }
catch { "HTTP $($_.Exception.Response.StatusCode.value__)" }

# 9. Durability -> a pending retry survives a restart
$d = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"fail_n_times","payload":"1","maxAttempts":5}'
# Ctrl+C the app while it is RETRYING, restart, then poll -> SUCCEEDED

# 10. All five metrics are visible
Invoke-RestMethod -Uri http://localhost:8080/api/metrics

# 11. Ctrl+C shuts down cleanly, no stack trace from the worker loop or the scheduler

# 12. HIGH jumps a NORMAL backlog -> the HIGH job SUCCEEDS while NORMALs are still QUEUED
1..12 | ForEach-Object {
  Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
    -ContentType 'application/json' `
    -Body '{"type":"sleep","payload":"800","priority":"NORMAL"}'
} | Out-Null
Start-Sleep -Milliseconds 500
$hi = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"sleep","payload":"100","priority":"HIGH"}'
Start-Sleep -Seconds 4
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($hi.id)" |
  Select-Object status, priority, startedAt
(Invoke-RestMethod -Uri "http://localhost:8080/api/jobs?priority=NORMAL&status=QUEUED").Count

# 13. The guard fires -> a LOW job runs before a long HIGH backlog is exhausted.
#     Watch for "Starvation guard fired ..." at INFO in the console.

# 14. Priority survives a retry, verified against Redis rather than the API.
#     While the job is RETRYING the delayed-set member carries its tier:
docker exec distroq-redis redis-cli ZRANGE distroq:jobs:delayed 0 -1

# 15. Per-tier depth, and the three different stream counts
Invoke-RestMethod -Uri http://localhost:8080/api/metrics | ConvertTo-Json

# 16. Nothing stranded in any legacy list key
'distroq:jobs:pending','distroq:jobs:pending:high','distroq:jobs:pending:normal','distroq:jobs:pending:low' |
  ForEach-Object { "$_ = " + (docker exec distroq-redis redis-cli LLEN $_) }

# ---- v0.5 ----

# 17. The streams and the group exist, and a restart does not fail on BUSYGROUP
docker exec distroq-redis redis-cli XINFO STREAM distroq:jobs:stream:high
docker exec distroq-redis redis-cli XINFO GROUPS distroq:jobs:stream:high

# 18. A submitted job produces a four-field entry with source=SUBMIT, acknowledged on completion
$s = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"sleep","payload":"500","priority":"NORMAL"}'
Start-Sleep -Seconds 3
docker exec distroq-redis redis-cli XRANGE distroq:jobs:stream:normal - +
docker exec distroq-redis redis-cli XPENDING distroq:jobs:stream:normal distroq-workers   # -> 0

# 19. Acknowledgement timing -> pending WHILE running, zero after
$long = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"sleep","payload":"10000","priority":"HIGH"}'
Start-Sleep -Seconds 2
docker exec distroq-redis redis-cli XPENDING distroq:jobs:stream:high distroq-workers - + 10
Start-Sleep -Seconds 10
docker exec distroq-redis redis-cli XPENDING distroq:jobs:stream:high distroq-workers      # -> 0

# 20. Retries are promoted into the job's OWN tier stream with source=RETRY
$rr = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' `
  -Body '{"type":"fail_n_times","payload":"2","priority":"HIGH","maxAttempts":5}'
Start-Sleep -Milliseconds 700
docker exec distroq-redis redis-cli ZRANGE distroq:jobs:delayed 0 -1 WITHSCORES
Start-Sleep -Seconds 12
docker exec distroq-redis redis-cli XRANGE distroq:jobs:stream:high - +
docker exec distroq-redis redis-cli XRANGE distroq:jobs:stream:normal - +   # no RETRY entries

# 21. Replay uses the job's own tier with source=REPLAY (see 8a for the setup)
docker exec distroq-redis redis-cli XRANGE distroq:jobs:stream:low - +

# 22. Legacy list migration. Stop the app, seed the old keys with real QUEUED job IDs, restart.
docker exec distroq-redis redis-cli LPUSH distroq:jobs:pending:normal <known-job-id>
docker exec distroq-redis redis-cli LPUSH distroq:jobs:pending <known-job-id>
# on startup: "Migrated 1 job ID(s) from the v0.4 list ... onto the NORMAL stream", lists empty,
# entries carry source=LEGACY_MIGRATION

# 23. Worker crash recovery. Start two instances (see "Running two instances"), submit a long
#     HIGH job to instance 1, wait until it is RUNNING and pending, then kill instance 1 outright.
Stop-Process -Id <instance-1-pid> -Force
Start-Sleep -Seconds 14
docker exec distroq-redis redis-cli XPENDING distroq:jobs:stream:high distroq-workers - + 10
# the owner column changes from worker-one-... to worker-two-...; instance 2 logs the reclaim,
# marks attempt 1 ABANDONED, runs attempt 2, and acknowledges

# 24. Ctrl+C with work in flight -> no stack trace from the worker loop, the retry sweep, the
#     scheduled-job sweep or the recovery sweep; the shutdown hook logs "Worker ... shutting down"
#     and exits cleanly

# ---- v0.6 ----

# 25. A future job is SCHEDULED, not QUEUED, and sits in the scheduled sorted set
$scheduled = (Get-Date).ToUniversalTime().AddSeconds(10).ToString("yyyy-MM-ddTHH:mm:ssZ")
$j = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' `
  -Body (@{ type='sleep'; payload='500'; priority='HIGH'; scheduledAt=$scheduled } | ConvertTo-Json)
$j | ConvertTo-Json -Depth 5
docker exec distroq-redis redis-cli ZRANGE distroq:jobs:scheduled 0 -1 WITHSCORES
docker exec distroq-redis redis-cli XLEN distroq:jobs:stream:high
# status SCHEDULED, member "HIGH:<uuid>" scored at the target epoch millis, XLEN unchanged

# 26. It runs after its target time, once, with scheduledAt still on the row
1..15 | ForEach-Object {
  $s = Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($j.id)"
  "{0,2}s status={1,-10} attempts={2} scheduledAt={3}" -f $_, $s.status, $s.attemptCount, $s.scheduledAt
  Start-Sleep -Seconds 1
}
# SCHEDULED ... SCHEDULED, RUNNING, SUCCEEDED; attemptCount 1; scheduledAt unchanged

# 27. The promoted entry carries source=SCHEDULED on the job's OWN tier stream
docker exec distroq-redis redis-cli XRANGE distroq:jobs:stream:high - + COUNT 100 |
  Select-String -Pattern $j.id -Context 0,8

# 28. A past timestamp runs immediately and never touches the scheduled set
$past = (Get-Date).ToUniversalTime().AddMinutes(-5).ToString("yyyy-MM-ddTHH:mm:ssZ")
$p = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' `
  -Body (@{ type='sleep'; payload='300'; priority='NORMAL'; scheduledAt=$past } | ConvertTo-Json)
$p.status                                                   # QUEUED, not SCHEDULED
docker exec distroq-redis redis-cli ZSCORE distroq:jobs:scheduled "NORMAL:$($p.id)"   # empty

# 29. Timestamp validation. Valid values are accepted and normalised to the same instant
@("2026-09-07T15:30:00Z", "2026-09-07T17:30:00+02:00", "2026-09-07T10:30:00-05:00") |
  ForEach-Object {
    (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
      -ContentType 'application/json' `
      -Body (@{ type='sleep'; payload='100'; scheduledAt=$_ } | ConvertTo-Json)).scheduledAt
  }
# all three print 2026-09-07T15:30:00Z

# 30. Invalid values are 400s that name the field, and create nothing.
#     curl.exe rather than Invoke-RestMethod: Windows PowerShell 5.1 discards a non-2xx body.
$before = (Invoke-RestMethod -Uri http://localhost:8080/api/metrics).totalJobs
@("", "not-a-date", "2026-09-07 15:30:00", "2026-09-07T15:30:00") | ForEach-Object {
  (@{ type='sleep'; payload='100'; scheduledAt=$_ } | ConvertTo-Json -Compress) |
    curl.exe -s -o - -w "`nHTTP %{http_code}`n" -X POST http://localhost:8080/api/jobs `
      -H "Content-Type: application/json" --data-binary "@-"
}
(Invoke-RestMethod -Uri http://localhost:8080/api/metrics).totalJobs - $before   # 0

# 31. Two spellings of the same FUTURE instant get the same Redis score and the same DB value
$ids = @("2027-03-01T15:30:00Z", "2027-03-01T17:30:00+02:00") | ForEach-Object {
  (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs -ContentType 'application/json' `
    -Body (@{ type='sleep'; payload='100'; priority='LOW'; scheduledAt=$_ } | ConvertTo-Json)).id
}
$ids | ForEach-Object { docker exec distroq-redis redis-cli ZSCORE distroq:jobs:scheduled "LOW:$_" }
# identical scores; and the JVM's default zone is irrelevant:
[System.TimeZoneInfo]::Local.Id
docker exec distroq-postgres psql -U distroq -d distroq -c "SHOW timezone;"

# 32. Scheduled priority routing. Submit future HIGH/NORMAL/LOW with one target time, then:
docker exec distroq-redis redis-cli XRANGE distroq:jobs:stream:high - +
docker exec distroq-redis redis-cli XRANGE distroq:jobs:stream:normal - +
docker exec distroq-redis redis-cli XRANGE distroq:jobs:stream:low - +
# each ID appears on exactly its own stream, every entry with source=SCHEDULED

# 33. Restart durability. Submit ~20s out, wait 3s, kill the app (NOT Redis or PostgreSQL),
#     confirm the member survives, restart before the target time.
docker exec distroq-redis redis-cli ZRANGE distroq:jobs:scheduled 0 -1 WITHSCORES
# the member is still there with the process gone; after restart the job runs once, on time

# 34. Scheduled metrics, during the waiting window and after promotion
Invoke-RestMethod -Uri http://localhost:8080/api/metrics | ConvertTo-Json -Depth 6
# scheduledDepth > 0 and scheduledDepthByPriority.HIGH = 1 while waiting;
# after promotion scheduledDepth falls, the tier's streamDepth rises, pendingEntries goes 1 -> 0

# 35. Scheduled retry: the first entry is source=SCHEDULED, later ones source=RETRY,
#     and the job never goes back to SCHEDULED
$future = (Get-Date).ToUniversalTime().AddSeconds(5).ToString("yyyy-MM-ddTHH:mm:ssZ")
$r = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' `
  -Body (@{ type='fail_n_times'; payload='2'; priority='HIGH'; maxAttempts=5; scheduledAt=$future } | ConvertTo-Json)
docker exec distroq-redis redis-cli XRANGE distroq:jobs:stream:high - + COUNT 500 |
  Select-String -Pattern $r.id -Context 0,8

# 36. Replay is immediate and creates no scheduled member
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/jobs/<dead-lettered-id>/retry"
docker exec distroq-redis redis-cli ZSCORE distroq:jobs:scheduled "LOW:<dead-lettered-id>"   # empty
# the new entry carries source=REPLAY on the job's own tier; scheduledAt is still on the row

# 37. Filters
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs?status=SCHEDULED"
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs?priority=HIGH&status=SCHEDULED"

# ---- v0.8 ----

# 38. Terminal outbox failure. Start with a small ceiling and a fast-failing Redis client, then
#     take Redis away and submit a job. The event exhausts its budget and stops being claimed.
java -jar target\distroq-0.0.1-SNAPSHOT.jar `
  --distroq.outbox.max-attempts=2 --spring.data.redis.timeout=2s `
  --spring.task.scheduling.pool-size=12
docker stop distroq-redis
$t = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"sleep","payload":"100","priority":"HIGH"}'
Start-Sleep -Seconds 40
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "SELECT id, status, attempt_count, published_at, terminal_failed_at, left(last_error,40)
   FROM outbox_events WHERE aggregate_id = '$($t.id)';"
# FAILED, attempt_count 2, published_at null, terminal_failed_at set, and it stays that way
docker start distroq-redis

# 39. Operator retry validation -> 400, 400, 400, 404. Read the BODIES, not just the codes.
$eventId = "<the id from step 38>"
$b = @{ reason = "Retry terminal event after Redis recovery" } | ConvertTo-Json
curl.exe -s -o - -w "`nHTTP %{http_code}`n" -X POST `
  "http://localhost:8080/api/admin/outbox/$eventId/retry" `
  -H "Content-Type: application/json" --data-binary $b                       # no header  -> 400
curl.exe -s -o - -w "`nHTTP %{http_code}`n" -X POST `
  "http://localhost:8080/api/admin/outbox/$eventId/retry" `
  -H "X-Admin-Reason:  " -H "Content-Type: application/json" --data-binary $b   # blank   -> 400
curl.exe -s -o - -w "`nHTTP %{http_code}`n" -X POST `
  "http://localhost:8080/api/admin/outbox/$eventId/retry" `
  -H "X-Admin-Reason: $('x' * 501)" -H "Content-Type: application/json" --data-binary $b  # -> 400
curl.exe -s -o - -w "`nHTTP %{http_code}`n" -X POST `
  "http://localhost:8080/api/admin/outbox/00000000-0000-0000-0000-000000000000/retry" `
  -H "X-Admin-Reason: valid" -H "Content-Type: application/json" --data-binary $b         # -> 404

# 40. Operator retry succeeds -> 202, same event ID, operatorRetryCount 1, then PUBLISHED
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/admin/outbox/$eventId/retry" `
  -Headers @{ "X-Admin-Reason" = "Redis was restored after maintenance"; "X-Admin-Actor" = "alice" } `
  -ContentType "application/json" -Body $b
Start-Sleep -Seconds 4
Invoke-RestMethod -Uri "http://localhost:8080/api/admin/outbox/$eventId" | ConvertTo-Json -Depth 5
# and exactly one Redis publication, guarded by the surviving dedupe marker:
docker exec distroq-redis redis-cli KEYS "distroq:outbox:published:$eventId"

# 41. Retrying it again now that it is PUBLISHED -> 409 naming the status
curl.exe -s -o - -w "`nHTTP %{http_code}`n" -X POST `
  "http://localhost:8080/api/admin/outbox/$eventId/retry" `
  -H "X-Admin-Reason: valid" -H "Content-Type: application/json" --data-binary $b

# 42. Reconciliation preview changes nothing. Seed an inconsistency the application cannot make -
#     here, a RUNNING job whose lease lapsed - then look, and check the audit table is untouched.
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "INSERT INTO jobs (id, type, payload, status, priority, attempt_count, max_attempts, created_at,
     updated_at, started_at, execution_owner, execution_lease_until, version)
   VALUES ('00000000-0000-0000-0000-0000000000d1','sleep','60000','RUNNING','NORMAL',1,3,
     now()-interval '20 minutes', now()-interval '20 minutes', now()-interval '20 minutes',
     'worker-ghost', now()-interval '10 minutes', 0);"
$before = docker exec distroq-postgres psql -U distroq -d distroq -tAc `
  "SELECT count(*) FROM reliability_actions;"
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/admin/reconciliation/run" `
  -Headers @{ "X-Admin-Reason" = "Manual reconciliation preview" } -ContentType "application/json" `
  -Body (@{ autoRepair = $false; reason = "Inspect reliability findings" } | ConvertTo-Json) |
  ConvertTo-Json -Depth 8
docker exec distroq-postgres psql -U distroq -d distroq -tAc `
  "SELECT count(*) FROM reliability_actions;"     # unchanged from $before

# 43. A request cannot enable repairs that configuration forbids
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/admin/reconciliation/run" `
  -Headers @{ "X-Admin-Reason" = "Confirm configuration is the ceiling" } `
  -ContentType "application/json" `
  -Body (@{ autoRepair = $true; reason = "Ask for more than configuration allows" } | ConvertTo-Json) |
  Select-Object autoRepairRequested, autoRepairAllowedByConfiguration, autoRepairApplied
# True / False / False, and a WARN in the log

# 44. Repairs, with configuration permitting them. Run twice: the second run repairs nothing.
java -jar target\distroq-0.0.1-SNAPSHOT.jar --distroq.reconciliation.auto-repair=true
$hdr = @{ "X-Admin-Reason" = "Repair post-maintenance reliability findings"; "X-Admin-Actor" = "alice" }
$rb = @{ autoRepair = $true; reason = "Repair post-maintenance reliability findings" } | ConvertTo-Json
1..2 | ForEach-Object {
  Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/admin/reconciliation/run" `
    -Headers $hdr -ContentType "application/json" -Body $rb |
    Select-Object inspected, findings, repaired, skipped, failed
}
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "SELECT action_type, target_id, actor, before_state, after_state FROM reliability_actions
   ORDER BY created_at;"
# EXPIRED_EXECUTION_LEASE stays SKIPPED in both runs. It is never repaired automatically.

# 45. Retention. Age the published rows, then sweep - PENDING and FAILED survive, and so does a
#     published row whose job still has a FAILED sibling event or an unresolved finding.
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "UPDATE outbox_events SET published_at = now() - interval '400 days' WHERE status = 'PUBLISHED';"
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/admin/outbox/cleanup" `
  -Headers @{ "X-Admin-Reason" = "Retention sweep after audit" }
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "SELECT status, count(*) FROM outbox_events GROUP BY status;"
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "SELECT count(*) FROM reliability_actions WHERE action_type = 'OUTBOX_CLEANUP';"

# 46. Idempotent effect execution -> one ledger row, one increment
$c = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' `
  -Body '{"type":"idempotent_counter","payload":"orders:daily","priority":"NORMAL"}'
Start-Sleep -Seconds 5
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($c.id)" | ConvertTo-Json -Depth 8
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "SELECT effect_key, attempt_number, status, response_hash FROM job_effects;"
docker exec distroq-postgres psql -U distroq -d distroq -c "SELECT * FROM effect_counters;"

# 47. Duplicate DELIVERY of the same logical effect. Reset the job and hand the worker a second
#     entry: two attempts, still one effect row, still one increment.
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "UPDATE jobs SET status='QUEUED', finished_at=NULL, execution_owner=NULL,
     execution_lease_until=NULL, active_attempt_id=NULL, version=version+1
   WHERE id='$($c.id)';"
docker exec distroq-redis redis-cli XADD distroq:jobs:stream:normal '*' `
  jobId $c.id priority NORMAL enqueuedAt 1 source SUBMIT outboxEventId manual-duplicate
Start-Sleep -Seconds 4
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($c.id)" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Uri http://localhost:8080/api/metrics |
  Select-Object effectApplications, effectDeduplicationHits
# attemptCount 2, one effects entry, counter unchanged, effectDeduplicationHits +1

# 48. Concurrent effect claims and advisory-lock contention, against the real database
.\mvnw.cmd test "-Dtest=JobEffectConcurrencyLiveTest,ReconciliationLockLiveTest" `
  "-Ddistroq.live=true" "-DfailIfNoSpecifiedTests=false"

# 49. Admin filters, redaction and audit history
Invoke-RestMethod -Uri "http://localhost:8080/api/admin/outbox?status=FAILED"
Invoke-RestMethod -Uri "http://localhost:8080/api/admin/outbox?eventType=SCHEDULE_RETRY"
Invoke-RestMethod -Uri "http://localhost:8080/api/admin/outbox?status=PENDING&size=1&page=0"
Invoke-RestMethod -Uri "http://localhost:8080/api/admin/reliability-actions?size=3"
Invoke-RestMethod -Uri "http://localhost:8080/api/admin/reconciliation" | ConvertTo-Json -Depth 8
# no `payload` field appears anywhere in the outbox list response

# 50. The v0.8 metrics block
Invoke-RestMethod -Uri http://localhost:8080/api/metrics | ConvertTo-Json -Depth 10

# ---- v0.9 ----
# The analytics pipeline runs in its own container and never needs the application running.

# 51. Build the image and confirm the environment is the pinned one
docker compose --profile analytics build analytics
docker compose --profile analytics run --rm --entrypoint python analytics `
  -c "import sys, pyspark; print(sys.version); print(pyspark.__version__)"
# 3.12.x and 4.0.1

# 52. A naive timestamp is rejected before Spark or JDBC is touched
docker compose --profile analytics run --rm analytics export `
  --start 2026-01-01T00:00:00 --end 2027-01-01T00:00:00Z
echo $LASTEXITCODE   # 2, and the message says why an offset is required

# 53. start >= end is rejected
docker compose --profile analytics run --rm analytics export `
  --start 2027-01-01T00:00:00Z --end 2026-01-01T00:00:00Z
echo $LASTEXITCODE   # 2

# 54. Export a window, then inspect the metadata the export wrote about itself
docker compose --profile analytics run --rm analytics export `
  --start 2026-01-01T00:00:00Z --end 2027-01-01T00:00:00Z
$run = "analytics/output/20260101T000000Z__20270101T000000Z"
Get-Content "$run/metadata/export_metadata.json" | ConvertFrom-Json |
  Select-Object -ExpandProperty read_only | ConvertTo-Json -Depth 5
# row_census_unchanged: true, flyway_unchanged: true, jdbc_read_only_mode: always

# 55. The same window again is refused, not merged
docker compose --profile analytics run --rm analytics export `
  --start 2026-01-01T00:00:00Z --end 2027-01-01T00:00:00Z
echo $LASTEXITCODE   # 7, OUTPUT_EXISTS

# 56. Aggregates and data quality, neither of which opens a database connection
docker compose --profile analytics run --rm analytics report --input "/workspace/$run"
docker compose --profile analytics run --rm analytics quality --input "/workspace/$run"
Get-Content "$run/reports/priority_summary.csv"
Get-Content "$run/quality/data_quality_summary.csv"

# 57. UTC grouping is real, not incidental. Compare the report against the database's own
#     UTC buckets, and against what the host's local zone would have produced.
Get-Content "$run/reports/daily_job_summary.csv"
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "select (created_at at time zone 'UTC')::date day, priority, count(*) from jobs group by 1,2 order by 1,2;"
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "select count(*) from jobs where (created_at at time zone 'UTC')::date <> (created_at at time zone 'Asia/Kolkata')::date;"
# the report matches the UTC grouping exactly; the second query shows how many rows a
# local-time report would have moved to a different day

# 58. Rerun determinism. Only the export-time-relative column may move.
Copy-Item -Recurse -Force "$run/reports" "$env:TEMP/reports-run1"
docker compose --profile analytics run --rm analytics export `
  --start 2026-01-01T00:00:00Z --end 2027-01-01T00:00:00Z --overwrite
docker compose --profile analytics run --rm analytics report --input "/workspace/$run" --overwrite
Get-ChildItem "$env:TEMP/reports-run1/*.csv" | ForEach-Object {
  $a = (Get-FileHash $_.FullName).Hash
  $b = (Get-FileHash "$run/reports/$($_.Name)").Hash
  "{0,-28} {1}" -f $_.Name, $(if ($a -eq $b) { "IDENTICAL" } else { "DIFFERENT" })
}
# every file IDENTICAL except outbox_summary.csv, which differs only in
# oldest_unpublished_age_ms, by the elapsed time between the two exports

# 59. Credentials never reach the output
Select-String -Path "$run/metadata/export_metadata.json" -Pattern 'password'
# no matches; source_database is host/port/database only

# 60. The Python suite
docker compose --profile analytics run --rm --entrypoint python analytics `
  -m pytest /opt/distroq-analytics/tests -q
```

`com.distroq` logs at `DEBUG`, so the QUEUED → RUNNING → RETRYING → SUCCEEDED/DEAD_LETTERED
flow is visible in the console. The poller logs at DEBUG when it promotes nothing and INFO
when it does, so an idle system does not spam the console once a second.

## v1.0 — release readiness

v1.0 adds no queue mechanism and no job semantics. It makes the behaviour that already existed
safe to operate: to configure, authenticate, probe, observe, shut down, upgrade and recover.

Companion documents: [`OPERATIONS.md`](OPERATIONS.md) is the runbook,
[`SECURITY.md`](SECURITY.md) is the security posture, [`UPGRADE.md`](UPGRADE.md) is the path from
v0.9, and [`CHANGELOG.md`](CHANGELOG.md) is what changed.

### 1. Production configuration

Three profiles. `local` is the default, so nothing about running this on a laptop changed.

| Profile | Logs | Credentials | Admin token |
|---|---|---|---|
| `local` (default) | Human-readable, `com.distroq` at DEBUG | Throwaway values for localhost containers | Optional |
| `test` | Quiet | Environment with local fallbacks | Optional |
| `production` | One JSON object per line | Environment only, **no defaults** | **Required** |

```powershell
$env:SPRING_PROFILES_ACTIVE = "production"
```

The production profile contains no default password, no hardcoded secret, no localhost assumption,
no debug logging and no schema generation. `spring.jpa.hibernate.ddl-auto` is `validate`; Flyway
remains the only schema migration mechanism, and anything else is refused at startup.

Configuration is validated *before the first bean is created*, so a bad value produces one line
naming the property rather than a five-screen `UnsatisfiedDependencyException` whose root cause is
three levels down:

```
DistroQ refused to start: 1 configuration problem(s) must be fixed first.
  1. distroq.worker.heartbeat-interval-ms must be shorter than
     distroq.worker.execution-lease-ms, otherwise the lease expires before it is ever renewed
     and every long job loses ownership of itself
```

### 2. Environment variables

The full reference is in [`OPERATIONS.md`](OPERATIONS.md) and the fill-in-the-blanks version is
[`.env.example`](.env.example). Required, with no default anywhere:

```
SPRING_PROFILES_ACTIVE=production
DISTROQ_DB_URL=jdbc:postgresql://postgres:5432/distroq
DISTROQ_DB_USER=distroq
DISTROQ_DB_PASSWORD=...
DISTROQ_REDIS_HOST=redis
DISTROQ_ADMIN_TOKEN=...          # openssl rand -hex 32
```

### 3. Administrative authentication

v0.8 had `X-Admin-Reason` as an audit device and no authorization at all. v1.0 puts a configurable
shared bearer token in front of every administrative endpoint and of the idempotency lookup:

```powershell
curl.exe -i http://localhost:8080/api/admin/outbox
# 401 {"code":"UNAUTHORIZED","message":"A valid administrative bearer token is required", ...}

curl.exe -i -H "Authorization: Bearer invalid-token" http://localhost:8080/api/admin/outbox
# 401

curl.exe -i -H "Authorization: Bearer $env:DISTROQ_ADMIN_TOKEN" `
  -H "X-Admin-Reason: v1.0 security verification" http://localhost:8080/api/admin/outbox
# 200

curl.exe -i -X POST -H "Authorization: Bearer $env:DISTROQ_ADMIN_TOKEN" `
  http://localhost:8080/api/admin/outbox/cleanup
# 400 {"code":"MISSING_ADMIN_REASON", ...} - the reason is still required on every mutation
```

`/api/idempotency/{key}` is protected too. The key is chosen by the submitter and is very often a
customer or order identifier, so an open lookup was both an enumeration oracle and a way to read
back someone else's job.

**This is a release-level guard, not an identity system.** One token means one role, not one
person; `X-Admin-Actor` remains a self-declared label. The token is compared in constant time,
never logged, never persisted, and never reachable through actuator. Rotation is a restart with a
new value. See [`SECURITY.md`](SECURITY.md).

### 4. Health, liveness and readiness

```
GET /actuator/health            everything
GET /actuator/health/liveness   is the JVM alive?
GET /actuator/health/readiness  should this instance get work?
```

Liveness contains `livenessState` and nothing else — deliberately independent of PostgreSQL and
Redis. An orchestrator *kills* a container that fails liveness, so a liveness probe that depended
on the database would turn a thirty-second blip into a fleet-wide restart storm.

Readiness aggregates `readinessState`, `db`, `redis`, `flyway`, `outboxRelay`, `workerSubsystem`
and `schedulerSubsystem`. During a Redis outage:

```
overall=DOWN  db=UP  redis=DOWN  relay=UP  worker=UP
{"status":"UP","components":{"livenessState":{"status":"UP"}}}     <- liveness untouched
```

Health details name PostgreSQL, its version, the Redis version and the applied schema version.
Never a message, a connection string or a credential — an exception message routinely quotes the
JDBC URL, and the JDBC URL routinely contains a password. Actuator itself is unauthenticated; the
network boundary is yours to provide, and [`SECURITY.md`](SECURITY.md) says how.

### 5. Structured logging

One JSON object per line under the production profile; the local profile keeps the readable
pattern, because a developer reading a stack trace in a terminal is not helped by escaped
newlines.

```json
{"timestamp":"2026-09-10T15:45:49.016Z","level":"INFO","logger":"com.distroq.worker.Worker",
 "thread":"distroq-worker","service":"distroq","version":"1.0.0","instanceId":"acceptance-1",
 "message":"Job 7e2cedaa succeeded","event":"job.succeeded","workerId":"worker-dde7f7a2",
 "consumerName":"worker-dde7f7a2","jobId":"7e2cedaa-6900-46f2-ba06-9604cc11a369",
 "attemptId":"9aefe112-adbf-4997-b579-fba670980ede","stream":"distroq:jobs:stream:normal",
 "streamEntryId":"1789055147965-0","priority":"NORMAL","status":"SUCCEEDED","durationMs":"1031"}
```

The `event` field is a contract; the wording of `message` is not. The names are
`job.submitted`, `job.scheduled`, `job.started`, `job.succeeded`, `job.retry_scheduled`,
`job.dead_lettered`, `job.replayed`, `job.reclaimed`, `job.execution_claimed`,
`job.execution_lease_lost`, `outbox.published`, `outbox.failed`, `outbox.operator_retry`,
`reconciliation.finding`, `reconciliation.repair`, `application.readiness_changed`,
`application.shutdown_started`, `application.shutdown_completed` and
`admin.authentication_failed`.

Never logged: job payloads, the admin token, database or Redis passwords, raw idempotency keys,
effect responses, and exception *messages* on `errorType` — the class name only.

Every HTTP request carries a `correlationId` through its log lines and into its error body.
`X-Correlation-Id` is honoured when it is short and alphanumeric, and replaced otherwise: the value
ends up in a log file, and a newline in it could forge a whole log record.

### 6. Metrics

Micrometer through `/actuator/metrics` and `/actuator/prometheus`. `/api/metrics` is unchanged.

```powershell
curl.exe -s http://localhost:8080/actuator/prometheus | Select-String "^distroq_"
```

Counters are process-local and reset on restart: `distroq_jobs_submitted_total`,
`_started_`, `_succeeded_`, `_failed_`, `_dead_lettered_`, `_replayed_`, `_reclaimed_`,
`distroq_job_attempts_total`. Histograms:
`distroq_job_execution_duration_seconds`, `distroq_job_queue_delay_seconds`,
`distroq_job_schedule_delay_seconds`, `distroq_outbox_publish_duration_seconds`. Gauges are
database-derived, approximate and cached: `distroq_outbox_pending`, `_retryable_failed`,
`_terminal_failed`, `distroq_outbox_oldest_age_seconds`, `distroq_reconciliation_findings`,
`distroq_execution_leases_active`, `_expired`. `distroq_worker_active` and
`distroq_worker_concurrency` are process-local and exact.
[`OPERATIONS.md`](OPERATIONS.md) states the type and provenance of every one, because reading a
database-derived total as a process counter is how someone concludes a restart lost data.

Labels are bounded by construction: priority, status, outcome and event type are enums, and job
type — the one value a caller controls — is capped at 20 distinct values with the rest collapsed
into `other`. **No job ID, attempt ID, outbox event ID, idempotency key, payload or token is ever
a label.** A per-request label is an unbounded time series, which is the standard way a
well-behaved service becomes an out-of-memory incident three weeks after release.

### 7. Graceful shutdown

SIGTERM, with a twenty-second job in flight:

```
16:23:17.606  application.readiness_changed   Readiness is now REFUSING_TRAFFIC
16:23:17.608  application.shutdown_started    no new work will be claimed
16:23:17.615  Commencing graceful shutdown. Waiting for active requests to complete
16:23:17.619  Graceful shutdown complete
16:23:33.058  job.succeeded                   the in-flight job was allowed to finish
16:23:33.060  application.shutdown_completed  Redis and database connections close next
16:23:33.090  Worker worker-c86819e8 shutting down
16:23:33.098  Closing JPA EntityManagerFactory
16:23:33.107  HikariPool-1 - Shutdown completed
```

Zero errors, zero stack traces, and no job claimed after the sequence began.

If a job outruns `distroq.shutdown.worker-timeout-ms`, nothing is forced: it is not failed, not
marked succeeded, and its stream entry is not acknowledged. The lease expires on its own clock and
`XAUTOCLAIM` hands the entry to another consumer — the same path a hard kill takes. Recording an
outcome nobody observed is the one genuinely unrecoverable thing available at that moment.

### 8. Dependency failure behaviour

| Scenario | Behaviour |
|---|---|
| Redis unavailable at startup | Starts. Readiness DOWN, liveness UP. Submissions still commit |
| Redis lost during outbox publication | Job and event already committed; the event returns to PENDING and publishes **once** when Redis returns |
| Redis lost during worker consumption | One error, then a one-second backoff. No busy loop |
| PostgreSQL lost during submission | 503 `DEPENDENCY_UNAVAILABLE`. Nothing half-written |
| PostgreSQL lost during finalisation | **No false success.** The entry stays unacknowledged; a replacement worker reclaims it and the previous attempt becomes `ABANDONED` |
| Either reconnects | Readiness returns UP; sweeps resume on their next tick |

Retries are bounded everywhere. Over a measured 25-second PostgreSQL outage: three heartbeat
failures (one per 5s interval), one error per sweep tick, and a *single* relay attempt for the
affected event — because a failed outbox event's `available_at` is pushed forward rather than
re-attempted every 500ms. No unbounded thread creation: the worker and heartbeat pools are
fixed-size and the six sweeps share one scheduler pool.

### 9. Docker, production-like

```powershell
cp .env.example .env    # then fill in every CHANGE_ME
docker compose -f docker-compose.production.yml up -d --build
```

Pinned images, health-gated startup order, named volumes, resource limits, a restart policy, a
90-second stop grace period, and an application container that runs as uid 10001 with a read-only
root filesystem, `cap_drop: ALL` and `no-new-privileges`. No secret is baked into any layer, and
`.dockerignore` keeps `.env` and key material out of the build context entirely.

> Compose reads **shell** environment variables in preference to `.env`. A stale
> `DISTROQ_DB_PASSWORD` exported in your shell will win and present as an authentication failure
> against a password you can see is correct.

The two compose files declare separate project names so the development and production-like stacks
can run side by side. Without that, Compose derives the project from the directory and bringing
either up removes the other's containers.

**This is a demonstration, not an orchestrator.** No replicas, no rolling deployment, no node
failure handling, no secret rotation, no TLS. Those belong to a platform; v1.0 is built to run
correctly underneath one.

### 10. PostgreSQL backup and restore

```powershell
$env:PGPASSWORD = "..."
.\ops\backup\backup-postgres.ps1 -DbHost localhost -Port 5433 -Database distroq -User distroq

.\ops\restore\restore-postgres.ps1 -DumpFile ops\backup\distroq-....dump `
  -Database distroq_restore_test -Confirm
```

The restore script refuses to run without `-Confirm`, and refuses a target named `distroq` unless
`-AllowProductionName` is also given — the failure mode is unrecoverable and the dangerous command
differs from the safe one by a single word. It then verifies Flyway history, prints row counts for
comparison, and tells you to finish with the check that actually matters: starting the application
against the restored database, where `ddl-auto: validate` fails on any mismatch.

Verified during release testing: 104 jobs, 151 attempts, 44 outbox events, 8 dead letters, 41 audit
rows, 8 effects, 25 idempotency keys, 7 Flyway rows — identical on both sides, clean start.

### 11. Redis persistence and restore

> **PostgreSQL is the durable source of business history and outbox intent. Redis contains
> transport state and scheduling state. Redis loss may require reconciliation and can affect
> pending delivery recovery.**

The production compose runs Redis with `--appendonly yes --appendfsync everysec` and
`--maxmemory-policy noeviction`. The eviction policy is not a tuning choice: any other value lets
Redis silently delete a stream entry or a scheduled member under memory pressure, which is a lost
job.

`ops/backup/backup-redis.ps1` takes an RDB snapshot; `ops/restore/restore-redis.ps1` restores it
into an **isolated** container on another port and reports what survived — streams, consumer
groups, pending entries, both sorted sets and the deduplication markers. It will not overwrite a
running DistroQ Redis, because doing so replaces streams underneath consumers that still hold
pending entries against them.

The one thing a Redis restore recovers that reconciliation cannot is the deduplication markers.
Everything else is reconstructable from the outbox table. What a restore cannot recover: everything
written since the snapshot, and — if persistence was disabled — everything.

### 12. Upgrading from v0.9

No migration; the schema stays at `V7`. The v1.0 binary starts against a v0.9 database with
nothing to apply. The one change that will stop a deployment is the required
`DISTROQ_ADMIN_TOKEN`. Full detail, including why no `V8` was written, is in
[`UPGRADE.md`](UPGRADE.md).

### 13. Rollback limitations

Rolling back to v0.9 is a binary rollback and needs no database restore, because v1.0 adds no
migration. What you lose immediately is administrative authentication — v0.9 has none, so every
admin endpoint becomes open the moment the older binary runs. If the rollback is a response to an
incident, block the port at the network *before* rolling back.

The general rule, stated now because the instinct to check out an older tag is strongest during an
incident: **a forward migration is not undone by checking out an older binary.** Once a release
adds one, rolling back across it means restoring the database from the backup taken before the
upgrade, and accepting the loss of everything written since.

### 14. Security limitations

- The bearer token authenticates a role, not a person. No per-user scoping, no expiry, no
  revocation list, no rate limiting.
- `X-Admin-Actor` is self-declared. The audit trail records a claim by a token holder, not a
  verified subject.
- Actuator is unauthenticated. Provide the network boundary.
- No TLS. Terminate it in front.
- PostgreSQL and Redis are trusted. Anyone who can reach them directly has everything.

### 15. At-least-once, still

Unchanged since v0.5 and not softened by anything in v1.0. A job can execute more than once: a
worker that dies between finishing the work and acknowledging the entry will have its entry
redelivered, and a healthy worker slower than `claim-min-idle-ms` is indistinguishable from a dead
one. `XACK` makes *delivery* at-least-once; it does not make *execution* exactly-once.

### 16. External effects are not exactly-once

The effect ledger (v0.8) makes **cooperative** effects idempotent: a handler that claims an effect
key, performs the work and completes the ledger row will not repeat it. That is a contract the
handler opts into.

**An arbitrary external API call is not exactly-once and v1.0 does not claim it is.** If the
handler charges a card through an endpoint with no idempotency key of its own, a duplicate
delivery charges the card twice. Nothing in this release changes that, and no configuration makes
it otherwise.

### 17. Release verification

```powershell
git status --short
git diff --check
.\mvnw.cmd clean package

# health, version, admin auth
Invoke-RestMethod http://localhost:8080/actuator/health/liveness  | ConvertTo-Json -Depth 8
Invoke-RestMethod http://localhost:8080/actuator/health/readiness | ConvertTo-Json -Depth 8
Invoke-RestMethod http://localhost:8080/actuator/info             | ConvertTo-Json -Depth 8
curl.exe -i http://localhost:8080/api/admin/outbox                       # 401
curl.exe -i -H "Authorization: Bearer $env:DISTROQ_ADMIN_TOKEN" `
  http://localhost:8080/api/admin/outbox                                 # 200

# error contract
curl.exe -s -X POST -H "Content-Type: application/json" `
  -d '{\"type\":\"x\",\"payload\":\"{}\",\"priority\":\"URGENT\"}' http://localhost:8080/api/jobs
# {"status":400,"code":"INVALID_PRIORITY","correlationId":"...", ...}

# metrics
curl.exe -s http://localhost:8080/actuator/prometheus | Select-String "^distroq_"

# migrations
docker exec distroq-postgres psql -U distroq -d distroq -c `
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

### 18. Version metadata

One source of truth: the `<version>` in `pom.xml`. `build-info.properties` and `git.properties` are
generated at build time, `application.yml` is filtered from the same value, and every log line
carries it.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 8
```

```json
{
  "git":   { "branch": "v1.0-release-hardening",
             "commit": { "id": { "abbrev": "13e6a32", "full": "13e6a32c935b..." },
                         "time": "2026-09-10T13:07:42Z" },
             "dirty": "true", "tags": "v0.9" },
  "build": { "artifact": "distroq", "name": "distroq", "group": "com.distroq",
             "version": "1.0.0", "time": "2026-09-10T15:41:36.870Z" }
}
```

## Tests

```powershell
.\mvnw.cmd test
```

Release verification uses Maven Surefire's aggregate leaf-test count from
`target/surefire-reports/TEST-*.xml`. VS Code may display a larger number because its Test view
also reports discovered containers/tree items; that UI number is not compared with Surefire's
executed-test total. The final v0.7 run was 193 Maven tests with one skipped: 192 passing leaf
tests plus 24 passing class containers account exactly for VS Code's 216 passed items.

`JobTest` covers the `Job` state transitions including `markRetrying`, `markDeadLettered`,
`prepareForReplay` and the `hasAttemptsRemaining` boundary, `DeadLetterTest` covers the
`DeadLetter` transitions including re-dead-lettering after a replay, `DeadLetterWriterTest`
covers the upsert on the exhaustion path, `BackoffPolicyTest` covers the exponential
progression, the cap, overflow at high attempt counts and jitter variance, and
`JobExecutorTest` covers the four job types plus the unknown-type path.

v0.5 adds:

- `JobStreamEntryTest` — both directions of the stream body: what this application writes, and
  what it accepts back, including a missing or unparseable `jobId`, an unknown tier, an unknown
  `source` and an unreadable timestamp. A stream entry is data from outside this process, so a bad
  one must not be able to kill the poll loop.
- `StreamKeysTest` — the tier-to-key mapping in both directions, and that a key this application
  does not own resolves to nothing rather than a silent default.
- `JobAttemptTest` — `IN_PROGRESS` to `SUCCESS`, `FAILURE` and `ABANDONED`, and that abandoning a
  row does not rewrite who ran it.
- `WorkerDeliveryTest` — the duplicate-delivery decision table, the reclaim rule for a redelivered
  `RUNNING` job (previous attempt `ABANDONED`, attempt number advances, `attemptCount` not reset),
  that the attempt row is opened before execution and closed after it, and that **every** branch
  acknowledges.

`PriorityStrategyTest` is unchanged from v0.4 and still passes, which was the point of keeping the
scheduling policy free of any Redis knowledge.

v0.6 adds:

- `ScheduledAtParserTest` — the whole timezone argument, as assertions: `Z`, a positive offset and
  a negative offset all producing the same `Instant`; blank, offsetless, space-separated, bare-date
  and epoch-millis input all rejected with a 400 that names the field; a null value meaning
  immediate rather than invalid.
- `SortedSetMemberTest` — the `<TIER>:<uuid>` format in both directions for all three tiers, and
  that a bare UUID, an unknown tier, a malformed UUID or an empty member is rejected without
  throwing. This is data that can be written by hand during an incident; it must not be able to
  break a sweep or a metrics call.
- `ScheduledJobQueueTest` — that scheduling issues `ZADD NX` at the right key with the right score,
  that a duplicate is an idempotent no-op that writes nothing else, that promotion delegates with
  the scheduled key and the `SCHEDULED` source and never the retry ones, and that the depth
  breakdown reports every tier, survives an absent key and excludes unreadable members.
- `DueSetPromoterTest` — the positional contract between Java and the Lua: the sorted set first,
  then the tier streams in strict priority order, with the tier names lining up. Reordering
  `Priority` would otherwise route every HIGH job to the LOW stream with nothing throwing.
- `ScheduledJobPromoterTest` — that the poller promotes at the configured batch size, swallows
  exceptions so Spring cannot cancel its own schedule, stops touching Redis after
  `ContextClosedEvent`, and does **nothing** to a job beyond moving it.
- `JobTest` and `WorkerDeliveryTest` additions — the `SCHEDULED`/`QUEUED` decision at creation,
  `markQueuedFromSchedule` and its guard against every other status, `scheduledAt` surviving
  promotion, retry, dead-lettering and replay, and the worker refusing to run — or acknowledge —
  an entry that arrives before its time.

All are plain unit tests and need no infrastructure — no Testcontainers, and the dependency set is
unchanged from v0.1 apart from the two Flyway artifacts added in v0.2.1. Everything Redis-specific
is verified end to end against the Docker containers instead; see *Verification*.

What the unit tests deliberately do **not** cover is what the promotion Lua script does to a live
sorted set — that a future score is left alone, that the limit bounds the batch, that a promoted
member is removed. A mock would only echo the test's own assumptions back at it, so those are
verified against real Redis in checks 25–34 above.

`DistroqApplicationTests.contextLoads` is annotated `@Disabled` because it needs a live
PostgreSQL and Redis. Run `docker compose up -d` and remove the `@Disabled` annotation to
exercise it.

v0.8 adds:

- `OutboxEventTest` — the whole lifecycle as assertions: a new event is `PENDING`, claiming makes
  it `PUBLISHING`, publication makes it `PUBLISHED`, the ceiling makes it `FAILED` and stamps
  `terminal_failed_at`; an operator retry returns it to `PENDING`, preserves the event ID and the
  cumulative attempt count, increments `operator_retry_count`, raises the ceiling, and is refused
  on anything that is not `FAILED`.
- `OutboxRelayStoreTest` — that the terminal verdict comes from configuration rather than a
  constant, that publication is idempotent, and that an operator retry buys a fresh budget without
  erasing history.
- `OutboxOperatorServiceTest` — 404, 409 on `PENDING`, 409 on `PUBLISHED`, that a refused retry
  writes no audit row, that both reasons and the actor reach the audit record, and that the audit
  states do not contain the payload.
- `OutboxRetentionPolicyTest` — assertions against the SQL itself. "`PENDING` is never deleted"
  cannot be proved with a mocked repository, because the mock is the thing deciding what comes
  back; the rule lives in the query, so the test reads the query.
- `OutboxCleanupServiceTest` — batch limiting, one audit row per deleted event, payload-free audit
  states, `batchFull` reporting, and that the retention window is the longer of the configured
  window and the deduplication marker TTL in both directions.
- `ReconciliationServiceTest` — each finding type detected; grouping into every category including
  empty ones; preview mutating nothing and auditing nothing; configuration bounding the request in
  both directions; an allowed repair being performed and audited; an expired execution lease and a
  stale effect being skipped even when repairs are on; a second run repairing nothing; and a run
  that cannot take the advisory lock reporting that instead of scanning. Also the two negative
  cases that keep the duplicate-event rule honest: a healthy retrying job with published retry
  events behind it is **not** a duplicate, while a second live schedule event for one job is.
- `JobEffectServiceTest` — one ledger row and one increment; a repeated key not incrementing again;
  the recorded hash coming back on a deduplicated call; payload normalisation being part of the
  identity; a `FAILED` key being reclaimable on the same row; a `STARTED` key refusing to apply;
  hash stability; and that nothing but a digest is stored.
- `EffectKeysTest` — that the counter key excludes the attempt number, that normalisation folds
  case and whitespace, and that a long payload still fits the 255-character key column.
- `AdminReasonTest` — missing, blank and over-length reasons for both the header and the body, the
  boundary value, trimming, and that the maximum comes from configuration.

`JobEffectConcurrencyLiveTest` is the exception to "no infrastructure". The concurrent-claim
guarantee comes from two connections colliding on a primary key — the loser blocks on the winner's
uncommitted insert and then reads a committed `COMPLETED` row — and a stubbed repository decides
that outcome by itself rather than demonstrating it. `ReconciliationLockLiveTest` is the same
argument for the advisory lock: a mock can prove that a *denied* lock produces a skipped report,
but not that the lock is genuinely contended, nor that the scheduled sweep and an operator run make
opposite choices about waiting for it. Both are gated on a system property rather than `@Disabled`,
so they can actually be run:

```powershell
.\mvnw.cmd test "-Dtest=JobEffectConcurrencyLiveTest,ReconciliationLockLiveTest" `
  "-Ddistroq.live=true" "-DfailIfNoSpecifiedTests=false"
```

Both redirect the queue keys so their workers poll their own empty streams instead of competing
with a running instance for real work.

v1.0 adds 106 tests, all of which run without infrastructure. The count is 395 Maven tests, four
skipped — the two live tests above, which need a system property.

- `ConfigurationValidatorTest` — every rule that stops a deployment starting: zero and negative
  worker concurrency, a heartbeat not shorter than the lease it renews (including the exactly-equal
  case), a maximum retry delay below the base, a jitter factor outside `[0, 1)`, blank and
  duplicated Redis keys, a zero block timeout, a deduplication window shorter than the
  reconciliation staleness window, a cleanup interval shorter than the same, failed retention below
  published retention, a repair flag that has no effect without the broader one, a scheduler pool
  too small for the sweeps that share it, a missing datasource URL or Redis host, and the four
  production-only rules. Each asserts on the **property name in the message** rather than on a
  count of problems, because the entire value of failing at startup is that the message says which
  line to change.
- `UnresolvedPlaceholderTest` — the defect this release found. Spring's binder passes an
  unresolvable `${VAR}` through as literal text, so a production deployment that forgot to export
  `DISTROQ_ADMIN_TOKEN` would have started with an administrative token whose value is printed in
  this repository. Asserts that a placeholder-shaped value authenticates nobody and fails startup,
  for the token, the datasource URL and the Redis host.
- `AdminAuthenticationFilterTest` — which paths are protected and which are not (including that
  `/api/administrators` is not a sub-path of `/api/admin`), missing, wrong, prefix and
  case-different tokens, a case-insensitive scheme with a case-sensitive token, the disabled-surface
  403, and the inactive guard when no token is configured. Every assertion about a rejection body
  also asserts that it **does not contain the token or any prefix of it** — the one way this class
  can fail catastrophically is by echoing the value it compares against.
- `ErrorContractTest` — that every `ErrorCode` agrees with its own HTTP status, that the
  correlation ID reaches the body, that an uncoded `ResponseStatusException` still gets a code from
  its status, and that an unexpected failure leaks neither its message, its type, the SQL nor a
  password. Also that `ApiError` has exactly the seven documented fields and no `trace`.
- `CorrelationIdFilterTest` — the sanitiser, which is the security boundary: a value containing a
  newline could forge an entire log record, and one containing JSON punctuation could break the
  line it is embedded in. Over-long, control-character and structural values are replaced rather
  than rejected, and the MDC is cleared even when the request throws.
- `SubsystemHealthIndicatorTest` — the two cases that look like failures and are not: a subsystem
  an operator switched off, and one whose queue is empty. Plus never-started versus disabled (three
  states, not a boolean), consecutive-failure counting, and that only the exception's class name is
  published.
- `HealthGroupsTest` — reads the shipped `application.yml` rather than starting a context, and
  asserts that liveness contains `livenessState` and nothing else, that readiness names all seven
  indicators, that the readiness group's names match the health-indicator bean names, and that the
  actuator exposure list excludes `env`, `configprops`, `beans`, `heapdump` and `threaddump`.
  Adding `db` to liveness looks like an improvement and turns a thirty-second database blip into a
  fleet-wide restart storm, so it fails if someone edits the file.
- `ShutdownSequenceTest` — that the coordinator hooks `ContextClosedEvent` rather than a lifecycle
  phase (Spring publishes that event before it stops a single `Lifecycle` bean, which is the defect
  described in NOTES.md §13), that the drain runs after HTTP has stopped, that the shutdown is
  announced once, and that work outrunning the budget is **abandoned rather than forced** — no
  outcome recorded, so the lease expires and another consumer reclaims it.
- `DistroqMetricsTest` and `BoundedTagValuesTest` — that the metric names are the documented ones,
  that everything is namespaced, that **no job ID is ever a label**, that the only label keys are
  `priority`, `jobType`, `outcome` and `eventType`, and that 500 distinct caller-chosen job types
  produce at most four series. The cap is a ceiling rather than an eviction policy, because an
  evicted series would reappear with a gap and make every rate over it wrong.
- `StructuredLoggingTest` — that every required event name exists, that a scoped context restores
  the fields that were there before rather than clearing them, that a null field is omitted rather
  than written as `"null"`, that an exception contributes its type and not its message, and that a
  formatted line is one parseable JSON object even when the message contains quotes, newlines and
  tabs.
- `WorkerDeliveryTest` gains one: no lease is claimed, no attempt row opened, no execution started
  and **no acknowledgement issued** once shutdown has begun — the entry stays in the Pending
  Entries List for whichever instance is still reading.

`TestProperties` was collapsed from seven near-identical factory methods into a builder. Every
release so far has added a component to `DistroqProperties` and forced an edit to all seven; the
next one now costs one field.

## Known limitations

> **v1.0 update.** The first entry below described v0.8. Administrative endpoints now sit behind a
> configurable bearer token, and `/api/idempotency/{key}` sits behind the same one. That closes the
> open-port hole; it does not make this an identity system. Read the entry after it, and
> [`SECURITY.md`](SECURITY.md).

> **The administrative bearer token authenticates a role, not a person.** One token means one
> operator role. `X-Admin-Actor` is self-declared, so `reliability_actions.actor` records a claim
> made by a token holder rather than a verified subject. There is no expiry, no per-endpoint
> scoping, no revocation list and no rate limiting; rotation is a restart with a new value.
> Actuator is unauthenticated and needs a network boundary you provide. There is no TLS —
> terminate it in front, or the token crosses the network in clear text.

> **v0.8 and earlier had no authentication on the administrative endpoints at all.**
> `X-Admin-Reason` is an audit device, not authorization. Anything that could reach port 8080 could
> retry a terminal outbox event, run a repair or read the audit log. This is fixed in v1.0 and is
> the single most important reason not to run v0.9 or earlier on a reachable network.

> **Arbitrary external side effects are still not exactly once, and v1.0 does not claim otherwise.**
> The effect ledger commits the claim and the effect in one transaction, which is why it works for
> a counter in the same database. An HTTP call to a third party commits somewhere DistroQ has no
> transaction over. `Idempotency-Key`, outbox event IDs, execution leases and effect keys each
> close a different duplicate window; none of them, and not all of them together, close that one.

> **A `STARTED` effect is genuinely ambiguous.** If a worker dies between calling an external
> system and recording the result, no amount of inspection here can determine whether the call
> landed. v0.8 refuses to guess: the row is reported, and the configured policy can only close it
> as `FAILED` — releasing the key for another attempt — never as `COMPLETED`.

> **Redis deduplication markers expire.** Once `dedupe-retention-ms` has passed, republishing an
> old event would produce a second stream entry. Retention takes the longer of the marker TTL and
> `published-retention-days` for exactly this reason, but an event that stays terminal for longer
> than the marker's lifetime and is then retried by an operator has no Redis-side protection left.
> Reconciliation cannot prove a historical publication once both the marker and the row are gone.

> **Terminal outbox failures need a human.** By design — that is the difference between v0.8 and a
> retry loop that hides the problem — but it does mean `outboxTerminalFailed` must be alerted on.
> Nothing else will notice.

> **Reconciliation is bounded and partial.** Every query is limited to `batch-size`, so a large
> backlog needs several runs; `inspected == batchSize` in the response is the signal. The metrics
> endpoint recounts only the findings expressible as one indexed predicate, so the per-job event
> cross-checks appear in the report and not in `reconciliationFindings`.

> **Multiple instances coordinate by advisory lock, not leader election.** Every instance runs the
> sweep; a run that cannot take `pg_try_advisory_xact_lock` skips its tick. That prevents two
> instances repairing the same row at once, and it is not the same as electing one of them.

> **A Redis outage can starve the scheduler pool.** Four of the six `@Scheduled` sweeps issue Redis
> commands, and a disconnected Lettuce client blocks each one for its command timeout. With the
> pool sized exactly to the number of sweeps, the relay can wait a long time for a thread — which
> is the worst possible sweep to delay during an outage. See *Configuration*.

> **The three dual writes are unchanged.** Streams did not fix any of them, scheduling does not
> fix any of them, and nothing in v0.6 claims to.
>
> 1. `POST /api/jobs`: `jobRepository.save()` then `jobQueue.enqueue()` (an `XADD`). A crash
>    between them leaves a job `QUEUED` that no stream entry refers to.
> 2. Retry scheduling: `jobRepository.save()` then `jobQueue.scheduleAt()`. A crash between them
>    leaves a job `RETRYING` with a `nextAttemptAt` that will never arrive.
> 3. DLQ replay: the job and `dead_letters` rows are saved, then `XADD`. A crash between them
>    leaves a job `QUEUED` with its DLQ row already marked `replayed` — invisible from both
>    directions.
>
> v0.6 adds a **fourth instance of the same shape**, not a fourth problem: `POST /api/jobs` with a
> future `scheduledAt` saves the row as `SCHEDULED` and then issues the `ZADD`. A crash between
> them leaves a stuck `SCHEDULED` row with no sorted-set member, and nothing will ever promote it.
> It is the same window as case 1, with a different Redis command on the far side.
>
> The `@Transactional` on the exhaustion path makes the two *database* writes atomic with each
> other; it does **not** touch this, because Redis cannot enlist in a JPA transaction. Both
> promotion Lua scripts are atomic *within Redis* for the same reason and with the same limit. The
> standard fixes are a transactional outbox or a periodic reconciliation sweep over stale rows,
> and both are out of scope until v0.7 or a dedicated reliability version.

> **Scheduled times are best-effort.** A job starts at or after its requested time, never before,
> but the delay is bounded only by the poll interval plus stream delivery plus worker
> availability. This is not a real-time scheduler and does not try to be.

> **No rescheduling, and no cancellation.** `scheduledAt` and `priority` are both immutable after
> submission. Moving a job's execution time would mean changing a sorted-set score and a database
> row that are not written atomically together, in a system that already has four unrepaired
> instances of exactly that problem — it needs its own consistency design, not a `ZADD XX`.

> **`scheduledDepthByPriority` is O(N).** It reads every member of the scheduled set and counts
> prefixes, because Redis cannot group a sorted set by a member prefix. It is bounded by
> outstanding scheduled work rather than by history, but a very large backlog of scheduled jobs
> makes `/api/metrics` proportionally slower.

> **The schedule is only as durable as Redis.** The sorted set survives an application restart
> because it is not in the JVM — but it does not survive Redis losing the key, and the
> `docker-compose.yml` here configures no persistence at all.

> **At-least-once, not exactly-once.** A job can execute more than once: after a genuine crash, and
> also whenever an execution outlasts `claim-min-idle-ms`, because Redis cannot distinguish a slow
> worker from a dead one. Duplicate side effects are possible by design. Idempotency keys are v0.7.

> **The streams grow without bound.** There is no `MAXLEN` trimming, because a trimmed entry that
> is still pending cannot be inspected or reclaimed. `XLEN` therefore rises forever on a healthy
> system. A retention policy — trimming well behind the minimum pending ID — is needed before this
> runs anywhere for long.

> **The legacy list migration is not transactional.** It parks each ID with `LMOVE` before the
> `XADD`, so the failure mode is a duplicate stream entry rather than a lost job, but it is a
> best-effort one-time upgrade path and is described as such.

A job that is `RUNNING` when its process dies no longer stays `RUNNING` forever — that was the
v0.4 orphan problem, and pending-entry recovery is exactly the fix. But it is bounded by
`claim-min-idle-ms` plus the sweep interval, and it needs *another* instance to be running: a
single-instance deployment recovers only when it is restarted.

A job due at time T is picked up at up to T + `poll-interval-ms` — for retries and for
user-scheduled jobs alike. Backoff delays and requested execution times are both a floor, not an
exact schedule.

There is still one worker thread per process. Two instances give two concurrent jobs, not two
threads in one.

The scheduled-job promoter runs in **every** instance. That is safe — the Lua script's `ZREM`
decides ownership, so a member cannot be promoted twice — but it is not leader-elected, and every
instance therefore pays for a `ZRANGEBYSCORE` per tick.

### v0.9 analytics limitations

> **Local validation is not a performance result.** The pipeline was exercised against 95 jobs and
> 133 attempts. It says nothing about a million. Extraction is single-partition JDBC, output is
> `coalesce(1)`, and the master is `local[*]`; all three are correct at this size and wrong at
> scale. The available mitigation today is a smaller window.

> **A missing schedule event has three possible causes and analytics can only rule out one.**
> A job scheduled into the future should have a `SCHEDULE_USER_JOB` outbox event. If it does not,
> the cause is either that the job predates the outbox, or that its event was deleted by
> `published-retention-days` cleanup, or that the durable intent was genuinely never written. The
> first two are indistinguishable from each other in the data. What *is* distinguishable is
> whether the job has any other outbox row at all: if it does, the outbox was demonstrably
> writing events for that job and neither age nor retention explains the gap. That case is a
> separate `ERROR` check; the broad one stays a `WARNING`. On the validation database the broad
> check reports 18 jobs, all created before the earliest surviving outbox event, and the narrow
> check reports zero.

> **Window edges cut across relationships.** A job created just before `end` may have its attempts,
> or its retry event, in the *next* window. Checks that span two datasets are therefore `WARNING`
> rather than `ERROR`. This is a property of half-open windows, not a defect, and it is why
> consecutive windows tile exactly.

> **Deduplication hits are a lower bound.** They are inferred from `job.attempt_count` minus the
> effect's claiming attempt, because the application counts them in Micrometer and not in a row.
> An effect completed on a job's final attempt reports zero hits even though the ledger is exactly
> what made a second increment impossible.

> **Parquet files are not byte-stable; their contents are.** Two report runs over the same export
> produce identical rows in identical order and byte-identical CSV copies, but the Parquet files
> differ by around twenty bytes in the footer, because parquet-mr does not emit Thrift metadata
> fields in a fixed order. Compare the data or the CSVs, not the container bytes.

> **`age_at_export_ms` is not reproducible, on purpose.** Every other number in the pipeline is
> byte-identical across reruns of the same window. This one moves with the clock, because the age
> of an unpublished event is a live fact and freezing it would answer the wrong question. It is
> frozen *within* an export, so re-reporting one export is fully deterministic.

> **Analytics reruns do not change execution semantics.** The pipeline is repeatable; the
> application is still at-least-once, and a job may still execute more than once. Nothing in v0.9
> makes an arbitrary external side effect exactly once, and nothing in v0.9 tries to.

## Not implemented yet

Deliberately out of scope for v0.9:

- An analytics HTTP API (`GET /api/analytics/*`); v0.9 is batch analytics only
- WebSockets and live streaming analytics; v1.1 reads completed v0.9 exports
- Kafka or another event broker
- Incremental or append-only analytics; every run re-extracts its whole window
- Automatic scheduling of exports
- Machine-learning predictions and anomaly detection
- Analytics-driven repair of any kind

Still out of scope, from v0.8:

- Exactly-once execution of arbitrary external side effects
- Automatic retry of unknown external API calls
- Automatic deletion of terminal outbox events
- WebSockets
- Cron and recurring jobs
- Job cancellation, rescheduling, and priority mutation after submission
- Bulk DLQ replay and automatic replay
- Stream retention and `MAXLEN` trimming
- Distributed leader election for the sweeps
- Multi-region processing, Kafka or another broker
- Kubernetes deployment and horizontal autoscaling
- New priority tiers
- A complete event-sourcing architecture

Earlier lists, kept for the record:

- Cancelling a scheduled job, rescheduling it, or editing `scheduledAt` after submission
- Calendar-aware or timezone-aware scheduling (v0.6 stores normalised UTC instants only)
- Bulk scheduling, and scheduled DLQ replay
- Any retention policy for `dead_letters`

v0.5 predicted that user scheduling would be "a small addition to `scheduleAt`". It was not. The
mechanism was indeed already there — the same Lua promotion, reused verbatim — but reusing the
*key* would have conflated retry state with user intent, and the real work turned out to be the
status, the timestamp parsing and the worker's behaviour on an entry that arrives early. See
*What v0.6 changed about the plan* in `NOTES.md`.

v0.7 predicted that the outbox would close the reliability story. It closed the *write* side and
opened an operational one: a durable intent nobody can see the state of is only half a fix. See
*What v0.8 changed about the plan* in `NOTES.md`.

v0.8 assumed that the operational state it had made legible was also the historical record. It is
not. Redis Streams retain acknowledged entries, deduplication markers expire, and outbox retention
deletes the published rows that prove a scheduling intent was ever honoured — so the system that
answers "what is happening now" cannot answer "what happened last month". See *What v0.9 changed
about the plan* in `NOTES.md`.

