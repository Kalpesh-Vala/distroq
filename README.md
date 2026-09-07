# DistroQ v0.5

A minimal, end-to-end distributed task queue with priority scheduling, automatic retries,
exponential backoff, a dead-letter queue with explicit replay, and **at-least-once delivery over
Redis Streams with acknowledgements and crash recovery**.

A job submitted over HTTP is persisted to PostgreSQL, its ID appended to **the Redis Stream for
its priority tier**, delivered to a worker through a **consumer group**, executed, and its terminal
status written back to PostgreSQL — observable via a GET endpoint. The stream entry stays in the
group's **Pending Entries List** until the worker acknowledges it, so work abandoned by a worker
that died can be found and re-run instead of vanishing. A job that fails is retried automatically,
with an exponentially increasing delay, **at its original priority**. A job that exhausts its
retries is **moved to a dead-letter queue** rather than merely marked failed, and can be replayed
on demand with its failure history and its priority intact.

**PostgreSQL is the single source of truth.** Redis carries job ID strings and a little routing
metadata; the job itself is never serialized into Redis. The API and the worker run in the same
Spring Boot process, but are decoupled — the worker talks only to the `queue` package and the
repositories, and neither side references the other's internals.

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
  source      SUBMIT | RETRY | REPLAY | LEGACY_MIGRATION
```

`priority` is duplicated from PostgreSQL deliberately: the retry-promotion script needs it to pick
a destination stream without a per-job database lookup, and it makes `XRANGE` readable during an
incident. If it disagrees with the database, **PostgreSQL wins** — the worker logs an integrity
warning naming both values and executes at the database's tier.

`source` exists because the worker's decision for a `RETRYING` job depends on where the entry came
from. See *Duplicate deliveries* below.

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
```

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
      pool-size: 2               # the retry sweep and the recovery sweep must not block each other
server:
  error:
    include-message: always      # otherwise the 409 body would not name the job's actual status
```

The scheduler pool is 2 because the recovery sweep executes reclaimed jobs on the scheduler
thread. With Spring Boot's default pool size of 1, a reclaimed thirty-second job would stall the
retry sweep for its whole duration.

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
| POST   | `/api/jobs`              | Returns **202 Accepted** — the work is accepted, not completed. `400` on an unknown `priority`. |
| GET    | `/api/jobs/{id}`         | Includes the full `attempts` history. `404` if unknown.         |
| GET    | `/api/jobs?status=&priority=` | Optional `JobStatus` and `Priority` filters, combinable; 50 most recent, newest first. Both are pushed into SQL, never filtered in memory. No attempts (avoids N+1). |
| POST   | `/api/jobs/{id}/retry`   | Replay a dead-lettered job at its original priority. **202**; `409` if not `DEAD_LETTERED`, `404` if unknown. |
| GET    | `/api/dlq`               | 50 most recent dead-letters, newest `moved_at` first. Optional `?replayed=true\|false`. Includes `priority`. |
| GET    | `/api/dlq/{jobId}`       | Single entry with the full attempt history and `priority`. `404` if not dead-lettered. |
| GET    | `/api/metrics`           | Queue, stream, delayed-set and DLQ counters — see below. |

`deadLetterCount` counts rows with `replayed = false` (currently sitting in the DLQ);
`replayedCount` counts rows with `replayed = true` (replayed and not since re-failed).

```json
{
  "queueDepth": 5,
  "queueDepthByPriority": { "HIGH": 0, "NORMAL": 0, "LOW": 5 },
  "delayedDepth": 0,
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
| `queueDepth`, `queueDepthByPriority` | How much work is waiting to be delivered? | the consumer group's `lag` from `XINFO GROUPS` | Exact while nothing is trimmed or deleted — and nothing in v0.5 trims or deletes. Redis reports `lag` as null once entries have been removed, in which case this reports 0 and logs at DEBUG. |
| `streamDepthByPriority` | How many entries does the stream hold? | `XLEN` | Exact, and **not a backlog**. It counts every entry the stream has ever been given, acknowledged or not, and only ever grows. |
| `pendingEntriesByPriority` | How much work has been handed out and not confirmed? | `XPENDING` summary | Exact. In-flight work plus anything abandoned and not yet reclaimed. |
| `delayedDepth` | How many retries are waiting out a backoff? | `ZCARD` | Exact. |
| `activeConsumers` | How many consumers are holding work? | distinct consumers in the `XPENDING` summaries | Exact for that question, which is **not** "how many workers are registered". An idle worker holds nothing and does not appear; a dead worker still holding an unreclaimed entry does. |

> `XLEN` is not queue depth. `streamDepthByPriority` is the count that would grow forever on a
> healthy, fully drained system, and treating it as a backlog would page you at 3am about a queue
> that is empty.

`queueDepth` and `queueDepthByPriority` keep their v0.4 names and meaning — "waiting to run" — so
anything already watching them keeps working. Their *source* changed from `LLEN` to consumer-group
lag.

`GET /api/dlq` joins to `jobs` for each entry's `type`, `status`, `attemptCount` and
`maxAttempts` — a listing of bare IDs would tell an operator nothing. The join is one
`WHERE id IN (...)` query for the whole page, not one lookup per dead-letter.

### Submit body

```json
{ "type": "fail_n_times", "payload": "2", "maxAttempts": 5, "priority": "HIGH" }
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

### Job detail response

Adds `priority`, `maxAttempts`, `nextAttemptAt` (non-null only while `RETRYING`) and `attempts`:

```json
{
  "status": "SUCCEEDED",
  "priority": "HIGH",
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

# 24. Ctrl+C with work in flight -> no stack trace from the worker loop, the retry sweep or the
#     recovery sweep; the shutdown hook logs "Worker ... shutting down" and exits cleanly
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

All are plain unit tests and need no infrastructure — no Testcontainers, and the dependency set is
unchanged from v0.1 apart from the two Flyway artifacts added in v0.2.1. Everything Redis-specific
is verified end to end against the Docker containers instead; see *Verification*.

`DistroqApplicationTests.contextLoads` is annotated `@Disabled` because it needs a live
PostgreSQL and Redis. Run `docker compose up -d` and remove the `@Disabled` annotation to
exercise it.

## Known limitations

> **The three dual writes are unchanged.** Streams did not fix any of them, and nothing in v0.5
> claims to.
>
> 1. `POST /api/jobs`: `jobRepository.save()` then `jobQueue.enqueue()` (now an `XADD`). A crash
>    between them leaves a job `QUEUED` that no stream entry refers to.
> 2. Retry scheduling: `jobRepository.save()` then `jobQueue.scheduleAt()`. A crash between them
>    leaves a job `RETRYING` with a `nextAttemptAt` that will never arrive.
> 3. DLQ replay: the job and `dead_letters` rows are saved, then `XADD`. A crash between them
>    leaves a job `QUEUED` with its DLQ row already marked `replayed` — invisible from both
>    directions.
>
> The `@Transactional` on the exhaustion path makes the two *database* writes atomic with each
> other; it does **not** touch this, because Redis cannot enlist in a JPA transaction. The
> promotion Lua script is atomic *within Redis* for the same reason and with the same limit. The
> standard fixes are a transactional outbox or a periodic reconciliation sweep over stale rows,
> and both are out of scope until v0.7 or a dedicated reliability version.

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

A job due at time T is picked up at up to T + `poll-interval-ms`. Backoff delays are
therefore a floor, not an exact schedule.

There is still one worker thread per process. Two instances give two concurrent jobs, not two
threads in one.

## Not implemented yet

Deliberately out of scope for v0.5:

- Reconciliation for the three dual writes, stream retention, and worker leases (**v0.5.1** — see
  *Planned for v0.5.1* in `NOTES.md`)
- User-scheduled future jobs via `scheduled_at` (v0.6)
- Idempotency keys, exactly-once side-effect protection, and a separate multi-worker service (v0.7)
- WebSockets and a live dashboard (v0.8)
- Bulk DLQ replay, automatic replay, and any retention policy for `dead_letters`
- Re-prioritising a submitted job
- Job cancellation
- Kubernetes deployment and horizontal autoscaling

The delayed sorted set is the mechanism v0.6 needs; scheduling an arbitrary future job is a small
addition to `scheduleAt`, but that capability is not exposed now.

