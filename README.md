# DistroQ v0.2

A minimal, end-to-end distributed task queue with automatic retries and exponential backoff.

A job submitted over HTTP is persisted to PostgreSQL, its ID pushed onto a Redis list,
picked up by an in-process background worker, executed, and its terminal status written
back to PostgreSQL — observable via a GET endpoint. A job that fails is retried
automatically, with an exponentially increasing delay, until its attempts are exhausted.

**PostgreSQL is the single source of truth.** Redis carries job ID strings only; the job
itself is never serialized into Redis. The API and the worker run in the same Spring Boot
process for v0.2 but are decoupled — the worker talks only to `JobQueue` and
`JobRepository`, and neither side references the other's package.

The worker uses a blocking `BRPOP` (`rightPop` with a 2-second timeout) rather than a
sleep-poll loop. Enqueue is `leftPush`, dequeue is `rightPop`, so ordering is FIFO.

## How retries work

When `JobExecutor.execute` throws, the worker checks `job.hasAttemptsRemaining()`
(`attemptCount < maxAttempts`).

- **Attempts left.** `BackoffPolicy.delayFor(attemptCount)` produces a delay —
  `base * 2^(attempt-1)`, capped, then jittered by ±20%. The job goes to `RETRYING` with
  `nextAttemptAt = now + delay`, and its ID is added to a Redis **sorted set**
  (`distroq:jobs:delayed`) scored by that due instant.
- **No attempts left.** The job goes to `FAILED`, which now means *permanently* failed.

`RetryScheduler` runs on a Spring `@Scheduled` fixed delay (1s by default). Each tick it
calls `JobQueue.promoteDueJobs`, a Lua script that atomically ranges the sorted set for
members due at or before now, removes them, and pushes them onto the pending list. The
worker then picks them up through the same `BRPOP` path as any other job.

Because the delay lives in Redis rather than in a `Thread.sleep` or a
`ScheduledExecutorService`, a retry scheduled for 8 seconds from now still fires if the
application restarts in the meantime. See `NOTES.md` for the full reasoning and the
atomicity trade-off.

The poller moves IDs only; it does not touch job status. A job stays `RETRYING` until the
worker picks it up and sets `RUNNING`, so there is no transient `QUEUED` flicker.

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

Hibernate creates the `jobs` and `job_attempts` tables on first boot (`ddl-auto: update`).

### Upgrading from v0.1

Two schema changes, only one of which `ddl-auto: update` can perform on its own.

**`maxAttempts` — handled automatically.** It is a new `NOT NULL` column on a populated
table, so it carries `@ColumnDefault("3")`. Hibernate emits
`alter table jobs add column max_attempts integer default 3 not null` and Postgres
backfills existing rows with 3. The DB default was chosen over documenting
`docker compose down -v` because losing job history to a schema change is exactly the kind
of thing a job queue should not do, and 3 is a defensible retry budget to impute to rows
submitted before the concept existed.

**`RETRYING` — needs one manual statement.** v0.1 created a `CHECK` constraint pinning
`status` to the four statuses that existed at the time. `ddl-auto: update` adds columns; it
never widens an existing check constraint. Without this step the first retry fails with
`new row for relation "jobs" violates check constraint "jobs_status_check"`:

```powershell
docker exec distroq-postgres psql -U distroq -d distroq `
  -c "ALTER TABLE jobs DROP CONSTRAINT IF EXISTS jobs_status_check;"
```

A fresh database is unaffected — Hibernate creates the constraint with all five statuses.
Wiping the volume (`docker compose down -v`) also works if you do not care about the
existing rows.

## Build

```powershell
.\mvnw.cmd clean package
```

## Configuration

Bound as a single `@ConfigurationProperties` record (`DistroqProperties`):

```yaml
distroq:
  queue-key: distroq:jobs:pending
  delayed-key: distroq:jobs:delayed
  retry:
    default-max-attempts: 3      # used when the request omits maxAttempts
    base-delay-ms: 1000          # attempt 1 -> ~1s, 2 -> ~2s, 3 -> ~4s
    max-delay-ms: 60000          # cap, applied before jitter
    jitter-factor: 0.2           # +/-20% randomisation, to break up retry waves
    poll-interval-ms: 1000       # how often the delayed set is swept
    promote-batch-size: 100      # max jobs promoted per sweep
```

## Job statuses

| Status      | Meaning                                                         |
| ----------- | --------------------------------------------------------------- |
| `QUEUED`    | Ready to run, waiting for a free worker.                          |
| `RUNNING`   | Currently executing.                                              |
| `RETRYING`  | Failed, attempts remaining, waiting out its backoff window.        |
| `SUCCEEDED` | Terminal.                                                         |
| `FAILED`    | Terminal — **permanently** failed, attempts exhausted.             |

`RETRYING` is deliberately distinct from `QUEUED`: conflating them would make queue depth
meaningless and hide backoff entirely.

## Job types

| Type           | Payload                          | Behaviour                                        |
| -------------- | -------------------------------- | ------------------------------------------------ |
| `sleep`        | milliseconds, e.g. `"3000"`      | Sleeps. Defaults to 1000ms if null/blank/unparseable. |
| `always_fail`  | ignored                          | Always throws, by design.                        |
| `fail_n_times` | N as an integer, e.g. `"2"`      | Throws on attempts 1..N, succeeds afterwards. Defaults to 2 if null/blank/unparseable. |

`fail_n_times` is stateless — it derives its behaviour from `job.getAttemptCount()`, not
from any counter held in the executor. It is the job type that demonstrates retry
*recovery* rather than retry *exhaustion*.

Any other type fails with an `IllegalArgumentException` naming the unknown type — and,
since that is an ordinary failure, it is retried before it becomes terminally `FAILED`.

## API

| Method | Path                     | Notes                                                          |
| ------ | ------------------------ | -------------------------------------------------------------- |
| POST   | `/api/jobs`              | Returns **202 Accepted** — the work is accepted, not completed. |
| GET    | `/api/jobs/{id}`         | Includes the full `attempts` history. `404` if unknown.         |
| GET    | `/api/jobs?status=`      | Optional `JobStatus` filter; 50 most recent, newest first. No attempts (avoids N+1). |
| GET    | `/api/metrics`           | `queueDepth`, `delayedDepth` and `totalJobs`.                   |

### Submit body

```json
{ "type": "fail_n_times", "payload": "2", "maxAttempts": 5 }
```

`maxAttempts` is optional. Omitted or `null` → the configured default (3). Any value below
1 is rejected with **400** and no job is created.

### Job detail response

Adds `maxAttempts`, `nextAttemptAt` (non-null only while `RETRYING`) and `attempts`:

```json
{
  "status": "SUCCEEDED",
  "attemptCount": 3,
  "maxAttempts": 5,
  "nextAttemptAt": null,
  "attempts": [
    { "attemptNumber": 1, "workerId": "worker-1a2b3c4d", "outcome": "FAILURE", "errorMessage": "...", "durationMs": 2 },
    { "attemptNumber": 2, "workerId": "worker-1a2b3c4d", "outcome": "FAILURE", "errorMessage": "...", "durationMs": 1 },
    { "attemptNumber": 3, "workerId": "worker-1a2b3c4d", "outcome": "SUCCESS", "errorMessage": null, "durationMs": 0 }
  ]
}
```

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

# 4. Failure path -> FAILED with a non-null errorMessage; app stays alive
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"always_fail","payload":""}'

# 5. Burst of 10 drains sequentially, ~500ms apart; queueDepth rises then falls to 0
1..10 | ForEach-Object {
  Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
    -ContentType 'application/json' -Body '{"type":"sleep","payload":"500"}'
} | Out-Null
Invoke-RestMethod -Uri http://localhost:8080/api/metrics

# 6. Unknown job type -> retried, then FAILED, message names the type
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"nonsense","payload":""}'

# 7. Retry recovery -> fails twice, SUCCEEDED on attempt 3, three attempt records
$r = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"fail_n_times","payload":"2","maxAttempts":5}'
Start-Sleep -Seconds 8
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($r.id)" | ConvertTo-Json -Depth 4

# 8. Retry exhaustion -> FAILED after 3 attempts, nextAttemptAt null
$f = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"always_fail","payload":"","maxAttempts":3}'
Start-Sleep -Seconds 12
Invoke-RestMethod -Uri "http://localhost:8080/api/jobs/$($f.id)" | ConvertTo-Json -Depth 4

# 9. Durability -> a pending retry survives a restart
$d = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"fail_n_times","payload":"1","maxAttempts":5}'
# Ctrl+C the app while it is RETRYING, restart, then poll -> SUCCEEDED

# 10. Both depths are visible
Invoke-RestMethod -Uri http://localhost:8080/api/metrics   # queueDepth, delayedDepth, totalJobs

# 11. Ctrl+C shuts down cleanly, no stack trace from the worker loop or the scheduler
```

`com.distroq` logs at `DEBUG`, so the QUEUED → RUNNING → RETRYING → SUCCEEDED/FAILED flow is
visible in the console. The poller logs at DEBUG when it promotes nothing and INFO when it
does, so an idle system does not spam the console once a second.

## Tests

```powershell
.\mvnw.cmd test
```

`JobTest` covers the `Job` state transitions including `markRetrying` and the
`hasAttemptsRemaining` boundary, `BackoffPolicyTest` covers the exponential progression,
the cap, overflow at high attempt counts and jitter variance, and `JobExecutorTest` covers
the three job types plus the unknown-type path. All are plain unit tests and need no
infrastructure — no Testcontainers, and the dependency set is unchanged from v0.1.

`DistroqApplicationTests.contextLoads` is annotated `@Disabled` because it needs a live
PostgreSQL and Redis. Run `docker compose up -d` and remove the `@Disabled` annotation to
exercise it.

## Known limitations

> `jobRepository.save()` and `jobQueue.enqueue()` in `POST /api/jobs` are not atomic.
> If the process crashes between them, the job persists as `QUEUED` but no worker will
> ever see it — the classic dual-write problem. Accepted for v0.1; the standard fixes
> are a transactional outbox or a periodic reconciliation sweep over stale `QUEUED` rows.

> v0.2 reintroduces the same dual write in a second place: `jobRepository.save()` then
> `jobQueue.scheduleAt()` when a retry is scheduled. A crash between them leaves a job
> `RETRYING` with a `nextAttemptAt` that will never arrive. Also deliberately unfixed —
> two occurrences is a stronger argument for the outbox than one.

Similarly, a job that is `RUNNING` when the process dies stays `RUNNING` forever — there
is no lease or heartbeat yet.

A job due at time T is picked up at up to T + `poll-interval-ms`. Backoff delays are
therefore a floor, not an exact schedule.

## Not implemented yet

Deliberately out of scope for v0.2:

- Dead-letter queue and replay endpoint (v0.3)
- Priority tiers (v0.4)
- Redis Streams and consumer groups (v0.5)
- User-scheduled future jobs via `scheduled_at` (v0.6)
- Idempotency keys and multiple worker processes (v0.7)
- WebSockets and a live dashboard (v0.8)

The delayed sorted set built here is the mechanism v0.6 needs; scheduling an arbitrary
future job is a small addition to `scheduleAt`, but that capability is not exposed now.
