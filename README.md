# DistroQ v0.3

A minimal, end-to-end distributed task queue with automatic retries, exponential backoff and a
dead-letter queue with explicit replay.

A job submitted over HTTP is persisted to PostgreSQL, its ID pushed onto a Redis list,
picked up by an in-process background worker, executed, and its terminal status written
back to PostgreSQL — observable via a GET endpoint. A job that fails is retried
automatically, with an exponentially increasing delay. A job that exhausts its retries is
**moved to a dead-letter queue** rather than merely marked failed, and can be replayed on
demand with its failure history intact.

**PostgreSQL is the single source of truth.** Redis carries job ID strings only; the job
itself is never serialized into Redis. The API and the worker run in the same Spring Boot
process for v0.3 but are decoupled — the worker talks only to `JobQueue` and
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
- **No attempts left.** The job goes to `DEAD_LETTERED` and a row is written to
  `dead_letters`. Both writes happen in one transaction. See *Dead-letter queue* below.

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
| `job_attempts` rows | **Preserved.** New attempts append with increasing `attempt_number`. |
| the `dead_letters` row | **Retained**, marked `replayed = true` with `replay_count` incremented. Never deleted. |

The point is that `GET /api/jobs/{id}` still tells a coherent story afterwards — "failed
twice, dead-lettered, replayed, succeeded on attempt 3". A reset would erase the evidence of
why the job was ever a problem, which is the entire reason for having a DLQ.

Extending `maxAttempts` is not cosmetic: without it a replayed job is already out of budget
and dies on its first attempt back.

Replay goes onto the **pending list**, not the delayed set — it is an explicit operator
action, so making them wait out a backoff window they did not ask for would be surprising.

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
  queue-key: distroq:jobs:pending
  delayed-key: distroq:jobs:delayed
  retry:
    default-max-attempts: 3      # used when the request omits maxAttempts
    base-delay-ms: 1000          # attempt 1 -> ~1s, 2 -> ~2s, 3 -> ~4s
    max-delay-ms: 60000          # cap, applied before jitter
    jitter-factor: 0.2           # +/-20% randomisation, to break up retry waves
    poll-interval-ms: 1000       # how often the delayed set is swept
    promote-batch-size: 100      # max jobs promoted per sweep
  dlq:
    replay-attempts: 3           # replay budget: maxAttempts becomes attemptCount + this
```

Also set, outside the `distroq` namespace:

```yaml
server:
  error:
    include-message: always      # otherwise the 409 body would not name the job's actual status
```

Spring Boot omits the `message` field from error bodies by default, which silently discards
the `reason` on every `ResponseStatusException` the app throws. The cost is that unhandled
exception messages are also exposed; acceptable here because this is a local, unauthenticated
development service, and worth revisiting before anything is deployed.

## Job statuses

| Status      | Meaning                                                         |
| ----------- | --------------------------------------------------------------- |
| `QUEUED`    | Ready to run, waiting for a free worker.                          |
| `RUNNING`   | Currently executing.                                              |
| `RETRYING`  | Failed, attempts remaining, waiting out its backoff window.        |
| `SUCCEEDED` | Terminal.                                                         |
| `FAILED`    | **Legacy.** See below.                                            |
| `DEAD_LETTERED` | Terminal — attempts exhausted, moved to the DLQ, replayable.  |

`RETRYING` is deliberately distinct from `QUEUED`: conflating them would make queue depth
meaningless and hide backoff entirely.

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
| POST   | `/api/jobs`              | Returns **202 Accepted** — the work is accepted, not completed. |
| GET    | `/api/jobs/{id}`         | Includes the full `attempts` history. `404` if unknown.         |
| GET    | `/api/jobs?status=`      | Optional `JobStatus` filter; 50 most recent, newest first. No attempts (avoids N+1). |
| POST   | `/api/jobs/{id}/retry`   | Replay a dead-lettered job. **202**; `409` if not `DEAD_LETTERED`, `404` if unknown. |
| GET    | `/api/dlq`               | 50 most recent dead-letters, newest `moved_at` first. Optional `?replayed=true\|false`. |
| GET    | `/api/dlq/{jobId}`       | Single entry with the full attempt history. `404` if not dead-lettered. |
| GET    | `/api/metrics`           | `queueDepth`, `delayedDepth`, `totalJobs`, `deadLetterCount`, `replayedCount`. |

`deadLetterCount` counts rows with `replayed = false` (currently sitting in the DLQ);
`replayedCount` counts rows with `replayed = true` (replayed and not since re-failed).

`GET /api/dlq` joins to `jobs` for each entry's `type`, `status`, `attemptCount` and
`maxAttempts` — a listing of bare IDs would tell an operator nothing. The join is one
`WHERE id IN (...)` query for the whole page, not one lookup per dead-letter.

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
```

`com.distroq` logs at `DEBUG`, so the QUEUED → RUNNING → RETRYING → SUCCEEDED/DEAD_LETTERED
flow is visible in the console. The poller logs at DEBUG when it promotes nothing and INFO
when it does, so an idle system does not spam the console once a second.

## Tests

```powershell
.\mvnw.cmd test
```

`JobTest` covers the `Job` state transitions including `markRetrying`, `markDeadLettered`,
`prepareForReplay` and the `hasAttemptsRemaining` boundary, `DeadLetterTest` covers the
`DeadLetter` transitions including re-dead-lettering after a replay, `DeadLetterWriterTest`
covers the upsert on the exhaustion path, `BackoffPolicyTest` covers the exponential
progression, the cap, overflow at high attempt counts and jitter variance, and
`JobExecutorTest` covers the four job types plus the unknown-type path. All are plain unit
tests and need no infrastructure — no Testcontainers, and the dependency set is unchanged
from v0.1 apart from the two Flyway artifacts added in v0.2.1.

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

> v0.3 adds a third: the replay endpoint saves the job and the `dead_letters` row, then
> calls `jobQueue.enqueue()`. A crash between them leaves a job `QUEUED` with its DLQ row
> already marked `replayed` and no ID in Redis — and it is no longer listed under
> `?replayed=false`, so it is invisible from both directions. The `@Transactional` on the
> exhaustion path makes the two *database* writes atomic with each other; it does **not**
> touch this, because Redis cannot enlist in a JPA transaction.

Similarly, a job that is `RUNNING` when the process dies stays `RUNNING` forever — there
is no lease or heartbeat yet.

A job due at time T is picked up at up to T + `poll-interval-ms`. Backoff delays are
therefore a floor, not an exact schedule.

## Not implemented yet

Deliberately out of scope for v0.3:

- Priority tiers (v0.4)
- Redis Streams and consumer groups (v0.5)
- User-scheduled future jobs via `scheduled_at` (v0.6)
- Idempotency keys and multiple worker processes (v0.7)
- WebSockets and a live dashboard (v0.8)

Also out of scope within the DLQ itself: automatic replay, bulk replay, and any retention or
purge policy for `dead_letters`. Replay is explicit and per-job.

The delayed sorted set built here is the mechanism v0.6 needs; scheduling an arbitrary
future job is a small addition to `scheduleAt`, but that capability is not exposed now.
