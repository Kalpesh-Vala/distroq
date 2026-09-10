# Operations

Running DistroQ v1.0: configuration, health, observability, shutdown, dependency failures, backup
and restore.

This is the runbook. `SECURITY.md` covers the security posture, `UPGRADE.md` covers getting here
from v0.9, and `README.md` covers what the system does and why it does it that way.

---

## Contents

1. [Profiles](#profiles)
2. [Environment variables](#environment-variables)
3. [Starting it](#starting-it)
4. [Health and readiness](#health-and-readiness)
5. [Structured logging](#structured-logging)
6. [Metrics](#metrics)
7. [Graceful shutdown](#graceful-shutdown)
8. [Dependency failure behaviour](#dependency-failure-behaviour)
9. [PostgreSQL backup and restore](#postgresql-backup-and-restore)
10. [Redis persistence and restore](#redis-persistence-and-restore)
11. [Common incidents](#common-incidents)
12. [What this is not](#what-this-is-not)

---

## Profiles

| Profile | Purpose | Logs | Credentials | Admin token |
|---|---|---|---|---|
| `local` | A developer's laptop against `docker compose up -d`. **The default.** | Human-readable, `com.distroq` at DEBUG | Hardcoded throwaway values for localhost containers | Optional; unset means the guard is inactive |
| `test` | Automated tests | Quiet | Environment with local fallbacks | Optional |
| `production` | Anything else | One JSON object per line | Environment only, no defaults | **Required** |

Production is opted into, never arrived at:

```
SPRING_PROFILES_ACTIVE=production
```

A deployment that forgets this starts with the local profile — safe, but pointed at
`localhost:5433` and logging in a format nothing can parse.

---

## Environment variables

Everything the production profile reads. Anything marked **required** has no default anywhere; a
missing one fails startup with the property named. `.env.example` is the copy-and-fill version.

### Required

| Variable | Example | Notes |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `production` | |
| `DISTROQ_DB_URL` | `jdbc:postgresql://postgres:5432/distroq` | Append `?ssl=true&sslmode=verify-full` for TLS |
| `DISTROQ_DB_USER` | `distroq` | |
| `DISTROQ_DB_PASSWORD` | — | `openssl rand -base64 24` |
| `DISTROQ_REDIS_HOST` | `redis` | |
| `DISTROQ_ADMIN_TOKEN` | — | `openssl rand -hex 32`. Required unless `DISTROQ_ADMIN_ENABLED=false` |

### Connections

| Variable | Default | Notes |
|---|---|---|
| `DISTROQ_DB_POOL_MAX` | `10` | Must exceed worker concurrency plus the sweeps, or workers queue behind the relay |
| `DISTROQ_DB_POOL_MIN` | `2` | |
| `DISTROQ_DB_CONNECTION_TIMEOUT_MS` | `5000` | Bounded so a request fails fast with 503 rather than holding a servlet thread |
| `DISTROQ_REDIS_PORT` | `6379` | |
| `DISTROQ_REDIS_PASSWORD` | empty | Leave empty only on a network nothing else can reach |
| `DISTROQ_REDIS_SSL` | `false` | |
| `DISTROQ_REDIS_TIMEOUT_MS` | `5000` | |
| `DISTROQ_REDIS_POOL_MAX` | `16` | Lettuce pool; requires `commons-pool2`, which is on the classpath |

### Workers

| Variable | Default | Notes |
|---|---|---|
| `DISTROQ_WORKER_CONCURRENCY` | `4` | One Redis consumer name per worker loop. Must be at least 1 |
| `DISTROQ_WORKER_LEASE_MS` | `30000` | The database execution lease |
| `DISTROQ_WORKER_HEARTBEAT_MS` | `5000` | **Must be strictly shorter than the lease** or every long job loses ownership of itself |
| `DISTROQ_CLAIM_MIN_IDLE_MS` | `60000` | **Must exceed the longest expected job duration.** Below it, a healthy slow worker looks abandoned and its job is executed a second time |

### Reliability

| Variable | Default | Notes |
|---|---|---|
| `DISTROQ_OUTBOX_RELAY_ENABLED` | `true` | With this false nothing is ever enqueued. Refused in production unless a separate relay deployment exists |
| `DISTROQ_RECONCILIATION_ENABLED` | `true` | |
| `DISTROQ_RECONCILIATION_AUTO_REPAIR` | `false` | The upper bound on repairs. A request may ask for less, never more |

### Shutdown

| Variable | Default | Notes |
|---|---|---|
| `DISTROQ_SHUTDOWN_WORKER_TIMEOUT_MS` | `30000` | How long in-flight jobs may finish after shutdown begins |
| `DISTROQ_SHUTDOWN_RELAY_TIMEOUT_MS` | `10000` | |
| `DISTROQ_SHUTDOWN_SCHEDULER_TIMEOUT_MS` | `10000` | |

The container's stop grace period must exceed `spring.lifecycle.timeout-per-shutdown-phase` (30s)
plus the worker timeout. The production compose file uses 90s.

### Observability

| Variable | Default | Notes |
|---|---|---|
| `DISTROQ_LOG_LEVEL` | `INFO` | `com.distroq` only. DEBUG logs delivery detail per entry; volume is proportional to throughput |
| `DISTROQ_LOG_LEVEL_ROOT` | `INFO` | |
| `DISTROQ_INSTANCE_ID` | `<hostname>-<random>` | Set for a stable identity. The random suffix is what makes two instances on one host distinguishable |

### HTTP

| Variable | Default |
|---|---|
| `DISTROQ_HTTP_MAX_THREADS` | `200` |

---

## Starting it

### Production-like, in containers

```powershell
cp .env.example .env    # then fill in every CHANGE_ME
docker compose -f docker-compose.production.yml up -d --build
```

> **Compose reads shell environment variables in preference to `.env`.** If your shell already
> exports `DISTROQ_DB_PASSWORD` from some earlier session, that value wins and `.env` is ignored —
> which presents as `password authentication failed` against a database whose password you can see
> is correct. Clear the shell first, or run compose from a fresh one. This cost an hour during
> v1.0 acceptance testing.

The two compose files declare separate project names (`distroq` and `distroq-prod`) so they can
run side by side. Without that, Compose derives the project from the directory, both files land in
one project, and bringing either up removes the other's containers as orphans.

### On the host, against the development containers

```powershell
docker compose up -d
.\mvnw.cmd clean package
java -jar target\distroq-1.0.0.jar          # local profile, no configuration needed
```

### Confirming it came up

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/readiness | ConvertTo-Json -Depth 6
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 8
```

---

## Health and readiness

Three endpoints, answering three different questions. Pointing a probe at the wrong one is the
most expensive configuration mistake available here.

### `GET /actuator/health/liveness` — is the process alive?

Contains `livenessState` and **nothing else**. Deliberately independent of PostgreSQL and Redis.

An orchestrator kills a container that fails liveness. If liveness depended on the database, a
thirty-second database blip would restart every instance simultaneously — turning a recoverable
outage into a restart storm, during which nothing drains gracefully and every in-flight job is
abandoned at once. A JVM with an unreachable database is not broken; it is waiting.

Use this for a Kubernetes `livenessProbe`.

### `GET /actuator/health/readiness` — should this instance get work?

| Component | DOWN when |
|---|---|
| `readinessState` | Shutdown has begun |
| `db` | PostgreSQL is unreachable |
| `redis` | Redis is unreachable |
| `flyway` | Migrations are pending, or migration state is unreadable |
| `outboxRelay` | Enabled, and no successful cycle within 10 × `outbox.poll-interval-ms` (min 30s) |
| `workerSubsystem` | No successful poll within 10 × `streams.block-timeout-ms` (min 30s) |
| `schedulerSubsystem` | No successful sweep within 10 × the slowest sweep interval (min 30s) |

A subsystem disabled by configuration is UP with `enabled: false` — switching the relay off does
not make the instance unfit to serve traffic. A subsystem that is enabled but has never completed
a cycle is DOWN.

Use this for a Kubernetes `readinessProbe` and for load-balancer membership.

Example during a Redis outage — note that liveness is untouched:

```
overall=DOWN db=UP redis=DOWN relay=UP worker=UP
{"status":"UP","components":{"livenessState":{"status":"UP"}}}
```

### `GET /actuator/health` — everything

The union. Useful to a human, wrong for a probe.

### Redaction

Health details name PostgreSQL, its version, the Redis version, the applied schema version and, on
failure, the *class name* of the last exception. Never a message, a connection string or a
credential — an exception message routinely quotes the JDBC URL, and the JDBC URL routinely
contains a password.

Actuator has no authentication. See `SECURITY.md` for the network boundary you have to provide.

---

## Structured logging

Under the production profile, one JSON object per line. Every line carries:

```
timestamp  level  logger  thread  service  version  instanceId  message
```

and, where relevant:

```
event  correlationId  workerId  consumerName  jobId  attemptId  stream  streamEntryId
outboxEventId  eventType  priority  status  durationMs  errorType
```

Example:

```json
{"timestamp":"2026-09-10T15:45:49.016Z","level":"INFO","logger":"com.distroq.worker.Worker",
 "thread":"distroq-worker","service":"distroq","version":"1.0.0","instanceId":"acceptance-1",
 "message":"Job 7e2cedaa succeeded","event":"job.succeeded","workerId":"worker-dde7f7a2",
 "consumerName":"worker-dde7f7a2","jobId":"7e2cedaa-6900-46f2-ba06-9604cc11a369",
 "attemptId":"9aefe112-adbf-4997-b579-fba670980ede","stream":"distroq:jobs:stream:normal",
 "streamEntryId":"1789055147965-0","priority":"NORMAL","status":"SUCCEEDED","durationMs":"1031"}
```

### Event names

The `event` field is a contract. Wording may change between releases; these values may not.

| Event | Meaning |
|---|---|
| `job.submitted` | Accepted and durably recorded |
| `job.scheduled` | Accepted for a future execution time |
| `job.execution_claimed` | A worker won the database lease |
| `job.started` | Execution began |
| `job.succeeded` | Finalised as SUCCEEDED |
| `job.retry_scheduled` | Failed, budget remaining, backoff applied |
| `job.dead_lettered` | Failed with no budget remaining |
| `job.replayed` | An operator returned a dead-lettered job to the queue |
| `job.reclaimed` | `XAUTOCLAIM` took an idle entry from another consumer |
| `job.execution_lease_lost` | A heartbeat failed while the job was still running |
| `outbox.published` | An event reached Redis |
| `outbox.failed` | A publication attempt failed; `status` distinguishes PENDING from FAILED |
| `outbox.operator_retry` | A terminal event was re-armed by hand |
| `reconciliation.finding` | An inconsistency was reported |
| `reconciliation.repair` | An inconsistency was repaired |
| `application.readiness_changed` | |
| `application.shutdown_started` | |
| `application.shutdown_completed` | |
| `admin.authentication_failed` | A request to a protected endpoint was rejected |

### Alerting suggestions

| Condition | Why |
|---|---|
| `event=job.dead_lettered` rate above baseline | Jobs are exhausting their budget |
| `event=outbox.failed` with `status=FAILED` | Terminal; needs an operator retry |
| `event=job.execution_lease_lost` | A worker lost ownership mid-job. Usually a database outage or a heartbeat/lease misconfiguration |
| `event=admin.authentication_failed` in volume | Someone is probing the admin surface |
| `event=application.readiness_changed status=REFUSING_TRAFFIC` without a deploy | An instance took itself out of rotation |

### What is never logged

Job payloads, the admin token, database or Redis passwords, raw idempotency keys, effect
responses, and exception *messages* on `errorType` (class name only). Full stack traces are
logged — a log file is internal; an HTTP response body is not.

---

## Metrics

`/actuator/metrics`, `/actuator/prometheus`, and the unchanged `/api/metrics`.

Micrometer names are dotted; Prometheus renders them with underscores and adds `_total` to
counters. The table gives the Prometheus name, because that is what a dashboard uses.

### Counters — process-local, reset on restart

| Metric | Labels | Notes |
|---|---|---|
| `distroq_jobs_submitted_total` | priority, jobType | |
| `distroq_jobs_started_total` | priority, jobType | |
| `distroq_jobs_succeeded_total` | priority, jobType | |
| `distroq_jobs_failed_total` | priority, jobType | One per failed attempt, not per job |
| `distroq_jobs_dead_lettered_total` | priority, jobType | |
| `distroq_jobs_replayed_total` | priority, jobType | |
| `distroq_jobs_reclaimed_total` | priority | Entries taken by `XAUTOCLAIM` |
| `distroq_job_attempts_total` | priority, jobType, outcome | `outcome` is SUCCEEDED or FAILED |

### Counters — database-derived, survive a restart

These are Micrometer `FunctionCounter`s over a `COUNT(*)`. They only increase, so Prometheus is
right to treat them as counters, but the number is the same on every instance rather than a
per-process tally. Cached for `distroq.metrics.database-gauge-cache-ms` (default 5s).

| Metric | Source |
|---|---|
| `distroq_reconciliation_repairs_total` | `reliability_actions` excluding cleanup rows |
| `distroq_effect_applications_total` | COMPLETED rows in `job_effects` |
| `distroq_effect_deduplication_hits_total` | **Process-local**, not database-derived — a deduplication hit leaves no row behind, precisely because its job is to leave nothing behind |

### Histograms (timers)

| Metric | Labels | Measures |
|---|---|---|
| `distroq_job_execution_duration_seconds` | priority, jobType, outcome | Wall-clock time in the job handler |
| `distroq_job_queue_delay_seconds` | priority | Stream enqueue → worker claim |
| `distroq_job_schedule_delay_seconds` | priority | Requested execution time → actual start |
| `distroq_outbox_publish_duration_seconds` | eventType, outcome | Time to publish one event |

A negative duration means the clocks disagree; it is clamped to zero rather than dragging the
histogram sum below zero.

### Gauges — approximate, database-derived, cached

| Metric | Meaning |
|---|---|
| `distroq_outbox_pending` | Durably recorded, not yet published |
| `distroq_outbox_retryable_failed` | Failed at least once, still inside its budget |
| `distroq_outbox_terminal_failed` | Given up on until an operator retries |
| `distroq_outbox_oldest_age_seconds` | Age of the oldest unpublished event. **The single most useful alert here** |
| `distroq_reconciliation_findings` | What reconciliation would report if it ran now |
| `distroq_execution_leases_active` | Held and unexpired |
| `distroq_execution_leases_expired` | Holder stopped renewing |

### Gauges — process-local, exact

| Metric | Meaning |
|---|---|
| `distroq_worker_active` | Executions running in this process |
| `distroq_worker_concurrency` | Configured concurrency for this process |

### Label cardinality

Labels are bounded enumerations — priority (3), status, outcome, event type — plus `jobType`,
which callers control. The first `distroq.metrics.max-job-type-tags` (default 20) distinct types
keep their own series; everything after becomes `other`. The cap is a ceiling, not an eviction
policy: an admitted type keeps its series, because a series that disappeared and came back would
have a gap in it and make every rate over it wrong.

Set `distroq.metrics.job-type-tag: false` to drop the label entirely. Do that if your submitters
put identifiers in the type field.

**No job ID, attempt ID, outbox event ID, idempotency key, payload or token is ever a label.**

### Suggested alerts

```promql
# unpublished outbox events are aging - the relay is stuck or Redis is unreachable
distroq_outbox_oldest_age_seconds > 300

# terminal failures need a human
distroq_outbox_terminal_failed > 0

# leases expiring means workers are dying or the database is slow
rate(distroq_execution_leases_expired[5m]) > 0

# no instance is fit for work
min(up{job="distroq"}) == 0
```

---

## Graceful shutdown

SIGTERM starts the sequence. Verified against a container stop with a 20-second job in flight:

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

Zero errors, zero stack traces.

### Why that order

| Step | Mechanism |
|---|---|
| Readiness DOWN, shutdown flag set | `ShutdownCoordinator`, a `ContextClosedEvent` listener at highest precedence. Spring publishes that event at the top of `doClose()`, before it stops a single `Lifecycle` bean — so this is the earliest hook that exists |
| Producers stop | Every sweep checks the shared flag on its next tick |
| HTTP drains | Boot's graceful web shutdown, `SmartLifecycle` phase `MAX_VALUE - 1024` |
| Connector stops | phase `MAX_VALUE - 2048` |
| In-flight jobs finish | `WorkDrainLifecycle`, phase `MAX_VALUE - 4096` — after HTTP, while the pools are still open |
| Pools close | Bean destruction |

### If a job outruns the budget

Nothing is forced. The job is **not** failed, **not** marked succeeded, and its stream entry is
**not** acknowledged. The process simply stops holding it:

```
WARN 1 job execution(s) were still running after 30000ms; leaving their stream entries
     unacknowledged and their leases to expire so another consumer can reclaim them
```

The lease then expires on its own clock and `XAUTOCLAIM` hands the entry to another consumer after
`claim-min-idle-ms` — the same path a hard kill takes. Recording an outcome nobody observed is the
one genuinely unrecoverable thing available at that moment, so it is not done.

---

## Dependency failure behaviour

All verified during v1.0 acceptance testing.

| Scenario | Behaviour |
|---|---|
| **PostgreSQL unavailable at startup** | Flyway cannot connect; startup fails with a clear error. The container restarts under its restart policy. The production compose gates the app on `service_healthy` so this is rare |
| **Redis unavailable at startup** | The application starts. Readiness is DOWN (`redis`), liveness UP. Submissions still commit to PostgreSQL; publication waits |
| **PostgreSQL lost during submission** | 503 `DEPENDENCY_UNAVAILABLE`. Nothing is half-written — job and outbox event are one transaction |
| **Redis lost during outbox publication** | The job and its outbox event are already committed. The publish attempt fails, the event returns to PENDING with `available_at` pushed forward, and it publishes once when Redis returns. **Verified**: `attempt_count = 1`, `published = true`, job SUCCEEDED |
| **Redis lost during worker consumption** | The poll loop logs one error and backs off a full second before retrying. Readiness goes DOWN. No busy loop |
| **PostgreSQL lost during finalisation** | The worker does **not** log success. The stream entry stays unacknowledged, the lease is not renewed, and when PostgreSQL returns another consumer reclaims the entry. **Verified**: attempt 1 became ABANDONED, attempt 2 SUCCEEDED, `attempt_count` advanced to 2 rather than resetting |
| **Redis reconnects** | Readiness returns UP within one health-check interval. The deduplication marker prevents a double effective publication |
| **PostgreSQL reconnects** | Hikari reconnects; the sweeps resume on their next tick |

### Retry bounds

Nothing spins. Measured over a 25-second PostgreSQL outage:

| Loop | Interval during an outage |
|---|---|
| Lease heartbeat | `heartbeat-interval-ms` (5s) — 3 failures in 25s |
| Retry / scheduled-job / recovery sweeps | Their poll interval (1s), one error line each |
| Outbox relay | Poll interval, but a failed event's `available_at` is pushed forward, so a *single* event is not re-attempted every 500ms — the whole point of the v0.8 backoff column |
| Worker poll loop | 1s fixed backoff after an error |

No unbounded thread creation: the worker pool is fixed-size, the heartbeat pool is fixed-size, and
the sweeps share the six-thread scheduler pool.

### Data safety

No silent loss. Outbox events remain recoverable in every case above, because the intent is a row
in PostgreSQL committed in the same transaction as the job. A job is never reported successful
when its final persistence failed — the finalisation is a conditional update that returns zero
rows if the lease has moved, and only a return of one leads to an acknowledgement.

---

## PostgreSQL backup and restore

PostgreSQL is the durable record: jobs, attempts, dead letters, outbox intent, the reliability
audit trail, the effect ledger and the idempotency keys.

### Backup

```powershell
$env:PGPASSWORD = "..."
.\ops\backup\backup-postgres.ps1 -DbHost localhost -Port 5433 -Database distroq -User distroq
```

POSIX equivalent: `PGPASSWORD=... ops/backup/backup-postgres.sh`

Or straight from a container, which needs no client tools on the host:

```powershell
docker exec distroq-postgres sh -c `
  "pg_dump -U distroq -d distroq -Fc --no-owner --no-privileges -f /tmp/distroq.dump"
docker cp distroq-postgres:/tmp/distroq.dump ops/backup/distroq.dump
```

Custom format (`-Fc`), because it is what `pg_restore` needs in order to restore selectively, in
parallel, and into a database whose owner differs. The script also runs `pg_restore --list` over
the result — reading the archive back is the cheapest possible proof it is not a zero-byte file.

`pg_dump` is read-only and does not block writers, so it is safe against a live system.

### Restore, and verifying it

```powershell
.\ops\restore\restore-postgres.ps1 `
  -DumpFile ops\backup\distroq-20260910T120000Z.dump `
  -Database distroq_restore_test -Confirm
```

The script refuses to run without `-Confirm`, and refuses a target named `distroq` unless
`-AllowProductionName` is also given. Both guards exist because the failure mode is unrecoverable
and the dangerous command differs from the safe one by a single word.

It then checks the three things that make a restore trustworthy rather than merely finished:

1. **Flyway history is present and every migration succeeded.** A restore with a failed migration
   row is refused outright.
2. **Row counts are printed** for `jobs`, `job_attempts`, `dead_letters`, `outbox_events`,
   `idempotency_keys`, `reliability_actions`, `job_effects` and `effect_counters`, to be compared
   against the source.
3. **Hibernate validation** — the one that actually matters — by starting the application against
   the restored database. `ddl-auto: validate` fails startup on any mismatch.

Verified during v1.0 release testing: 104 jobs, 151 attempts, 44 outbox events, 8 dead letters, 41
audit rows, 8 effects, 25 idempotency keys and 7 Flyway rows, identical on both sides, with a
clean application start against the restored database.

### What to schedule

This repository ships scripts, not a scheduler. Wire `backup-postgres.sh` into cron, a systemd
timer, a Kubernetes `CronJob` or your platform's managed backups. Two rules:

- **Restore-test on a schedule, not on demand.** A backup nobody has restored is a hope.
- **Store backups somewhere the database's failure cannot reach.** `ops/backup/` on the same host
  is fine for a drill and useless for a disk failure.

---

## Redis persistence and restore

> **PostgreSQL is the durable source of business history and outbox intent. Redis contains
> transport state and scheduling state. Redis loss may require reconciliation and can affect
> pending delivery recovery.**

### What Redis holds

| Structure | Key | Loss means |
|---|---|---|
| Priority streams | `distroq:jobs:stream:{high,normal,low}` | Undelivered entries are gone. The outbox rows that created them are not, so reconciliation can find them |
| Consumer groups | group `distroq-workers` per stream | Delivery state is gone; every surviving entry looks new |
| Pending Entries Lists | inside each group | In-flight work at the moment of loss is no longer tracked. Leases still expire, so PostgreSQL still shows the job as RUNNING with an expired lease |
| Scheduled sorted set | `distroq:jobs:scheduled` | User-requested times are gone. `jobs.scheduled_at` is not, and reconciliation reports the gap |
| Delayed sorted set | `distroq:jobs:delayed` | Retry backoffs are gone. `jobs.next_attempt_at` is not |
| Deduplication markers | `distroq:outbox:published:<eventId>` | **The only thing here PostgreSQL cannot reconstruct.** Without a marker, republishing an event creates a second stream entry |

### Configuration

The production compose runs Redis with AOF and per-second fsync:

```
redis-server --appendonly yes --appendfsync everysec --save 900 1 --save 300 10
             --dir /data --maxmemory-policy noeviction
```

`noeviction` is not a tuning choice. Any eviction policy would let Redis silently delete a stream
entry or a scheduled member under memory pressure, which is a lost job.

`appendfsync everysec` means up to one second of writes can be lost on an unclean shutdown. That
is acceptable *here specifically* because the lost writes are transport state whose intent is
already committed in PostgreSQL — and because at-least-once already tolerates a replay. It would
not be acceptable for business history, which is why business history is not in Redis.

### Snapshot

```powershell
.\ops\backup\backup-redis.ps1 -Container distroq-redis-prod
```

`BGSAVE` returns immediately, so the script watches `LASTSAVE` move before copying the file out —
otherwise it would copy the *previous* snapshot and report success.

### Restore, into isolation

```powershell
.\ops\restore\restore-redis.ps1 `
  -RdbFile ops\backup\distroq-redis-20260910T120000Z.rdb -Confirm
```

This starts a **new** container on port 6380 and will not touch a running DistroQ Redis. Restoring
into the live instance replaces streams underneath consumers that still hold pending entries
against them; if you must, stop every application instance first.

The report shows stream lengths, whether consumer groups survived, pending entry counts, both
sorted sets and the deduplication marker count — the six things whose absence changes what happens
next.

### What a Redis restore cannot recover

- **If persistence was disabled: everything.** An RDB-less, AOF-less Redis that restarts comes back
  empty. Every scheduled job, delayed retry and undelivered entry is gone from Redis.
- **Anything written since the snapshot**, up to one second with `everysec`.
- **Deduplication markers that had already expired.** Their TTL is
  `distroq.outbox.dedupe-retention-ms` (7 days by default), which is why retention refuses to
  delete an outbox row while its marker may still be needed.

### Recovering from a Redis loss

1. Bring Redis back. Empty is fine.
2. Start the application. `JobStreamInitializer` recreates the consumer groups.
3. Run reconciliation and read it before acting:
   ```powershell
   curl.exe -H "Authorization: Bearer $env:DISTROQ_ADMIN_TOKEN" `
     http://localhost:8080/api/admin/reconciliation
   ```
   Expect `STALE_PENDING_OUTBOX`, `STALE_SCHEDULED_JOB`, `STALE_RETRY_JOB` and
   `EXPIRED_EXECUTION_LEASE` findings.
4. Repair deliberately, with a reason:
   ```powershell
   curl.exe -X POST -H "Authorization: Bearer $env:DISTROQ_ADMIN_TOKEN" `
     -H "X-Admin-Reason: Redis data loss on 2026-09-10, re-arming scheduled jobs" `
     -H "Content-Type: application/json" `
     -d '{"reason":"Redis data loss recovery","autoRepairRequested":true}' `
     http://localhost:8080/api/admin/reconciliation/run
   ```
   This requires `DISTROQ_RECONCILIATION_AUTO_REPAIR=true`; configuration is the upper bound and a
   request can only ask for less.
5. **Expect duplicate execution.** Entries republished without their deduplication markers can be
   delivered a second time. That is at-least-once working as designed. Jobs using the effect
   ledger absorb it; jobs performing arbitrary external side effects do not.

---

## Common incidents

### `distroq_outbox_oldest_age_seconds` is climbing

The relay is not publishing. In order:

1. `GET /actuator/health/readiness` — is `redis` DOWN, or `outboxRelay`?
2. `event=outbox.failed` in the logs — read `errorType`.
3. `GET /api/admin/outbox?status=FAILED` — terminal events need an operator retry.
4. `POST /api/admin/outbox/{id}/retry` with `X-Admin-Reason`, once you know why it failed.

### Jobs are executing twice

Almost always `claim-min-idle-ms` below the longest real job duration. Redis measures time since
delivery, not liveness, so a healthy worker running a job for longer than the threshold looks
identical to a dead one and its entry is reclaimed. Raise `DISTROQ_CLAIM_MIN_IDLE_MS` above the
p99.9 of `distroq_job_execution_duration_seconds`.

### `job.execution_lease_lost` is firing

The heartbeat could not renew. Either the database was unreachable (check `db` health for the same
period) or `heartbeat-interval-ms` is too close to `execution-lease-ms` — v1.0 refuses the
degenerate case at startup, but a 29s heartbeat against a 30s lease passes validation and will
still lose races under load. Keep the heartbeat at a third of the lease or less.

### An instance is UP but taking no work

`GET /actuator/health/readiness` and look at `workerSubsystem`. `lifecycle: NOT_STARTED` means the
worker never started; a large `sinceLastSuccessMs` means the poll loop is wedged, usually on Redis.

### Startup fails with a configuration error

Read the message; it names the property. The list of rules is in `UPGRADE.md`.

---

## What this is not

`docker-compose.production.yml` is a **production-like demonstration**, not a production
orchestrator. It shows pinned images, no baked secrets, non-root execution, health-gated startup,
named volumes, resource limits and a restart policy. It does not provide:

- More than one application replica behind a load balancer
- Rolling deployment or zero-downtime release
- Node failure handling or rescheduling
- Secret management with rotation
- Backup scheduling
- Log shipping or metric collection
- TLS termination

Those belong to a platform. DistroQ v1.0 is built to run correctly *underneath* one — it drains on
SIGTERM, answers separate liveness and readiness probes, logs JSON to stdout, exposes Prometheus
metrics, and refuses to start on a configuration that would fail later. What it does with those
signals is the platform's job.
