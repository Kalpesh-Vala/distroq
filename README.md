# DistroQ v0.1

A minimal, end-to-end distributed task queue.

A job submitted over HTTP is persisted to PostgreSQL, its ID pushed onto a Redis list,
picked up by an in-process background worker, executed, and its terminal status written
back to PostgreSQL — observable via a GET endpoint.

**PostgreSQL is the single source of truth.** Redis carries job ID strings only; the job
itself is never serialized into Redis. The API and the worker run in the same Spring Boot
process for v0.1 but are decoupled — the worker talks only to `JobQueue` and
`JobRepository`, and neither side references the other's package.

The worker uses a blocking `BRPOP` (`rightPop` with a 2-second timeout) rather than a
sleep-poll loop. Enqueue is `leftPush`, dequeue is `rightPop`, so ordering is FIFO.

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

Hibernate creates the `jobs` table on first boot (`ddl-auto: update`).

## Build

```powershell
.\mvnw.cmd clean package
```

## Job types

| Type          | Payload                          | Behaviour                                        |
| ------------- | -------------------------------- | ------------------------------------------------ |
| `sleep`       | milliseconds, e.g. `"3000"`      | Sleeps. Defaults to 1000ms if null/blank/unparseable. |
| `always_fail` | ignored                          | Always throws, by design.                        |

Any other type fails with an `IllegalArgumentException` naming the unknown type.

## API

| Method | Path                     | Notes                                                          |
| ------ | ------------------------ | -------------------------------------------------------------- |
| POST   | `/api/jobs`              | Returns **202 Accepted** — the work is accepted, not completed. |
| GET    | `/api/jobs/{id}`         | `404` if unknown.                                               |
| GET    | `/api/jobs?status=`      | Optional `JobStatus` filter; 50 most recent, newest first.      |
| GET    | `/api/metrics`           | `queueDepth` and `totalJobs`.                                   |

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

# 6. Unknown job type -> FAILED, message names the type
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/jobs `
  -ContentType 'application/json' -Body '{"type":"nonsense","payload":""}'

# 7. Ctrl+C shuts down cleanly, no stack trace from the worker loop
```

`com.distroq` logs at `DEBUG`, so the QUEUED → RUNNING → SUCCEEDED/FAILED flow is
visible in the console.

## Tests

```powershell
.\mvnw.cmd test
```

`JobTest` covers the `Job` state transitions and `JobExecutorTest` covers the two job
types plus the unknown-type path. Both are plain unit tests and need no infrastructure.

`DistroqApplicationTests.contextLoads` is annotated `@Disabled` because it needs a live
PostgreSQL and Redis. Run `docker compose up -d` and remove the `@Disabled` annotation to
exercise it.

## Known limitations

> `jobRepository.save()` and `jobQueue.enqueue()` in `POST /api/jobs` are not atomic.
> If the process crashes between them, the job persists as `QUEUED` but no worker will
> ever see it — the classic dual-write problem. Accepted for v0.1; the standard fixes
> are a transactional outbox or a periodic reconciliation sweep over stale `QUEUED` rows.

Similarly, a job that is `RUNNING` when the process dies stays `RUNNING` forever — there
is no lease or heartbeat in v0.1.

## Not implemented yet

Deliberately out of scope for v0.1:

- Retries and exponential backoff
- Dead-letter queue
- Priority tiers
- Delayed / scheduled jobs
- Redis Streams and consumer groups
- Idempotency keys
- WebSockets and a live dashboard
- Multiple worker processes or worker threads (v0.1 runs a single worker thread)
