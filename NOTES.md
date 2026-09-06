# DistroQ — Engineering Notes

Running log of known limitations, failure modes observed, and design decisions.
Written for my own reference and interview prep.

## Known limitations

### 1. Dual write: job persisted but never enqueued

`JobController.submit` does two writes to two different systems, back to back:

```java
Job job = jobRepository.save(Job.create(request.type(), request.payload()));
jobQueue.enqueue(job.getId());
```

There is nothing tying these together. If the process dies between the `save` and the
`enqueue` — JVM crash, `kill -9`, container eviction, or just a Redis connection failure
on the second line — the row is committed to Postgres with status `QUEUED` and no ID ever
lands in the Redis list. No worker will ever see that job. It is not retried, because
nothing knows it needs retrying.

What makes this worse than a plain lost write is that it fails *invisibly, in a shape
that looks correct*. The client already got its `202` and a job ID. `GET /api/jobs/{id}`
returns `QUEUED`. `GET /api/jobs?status=QUEUED` lists it alongside jobs that genuinely are
waiting their turn. There is no field, no log line, and no metric that distinguishes
"queued and waiting" from "queued and permanently abandoned". The only symptom is a job
that stays `QUEUED` forever, and by the time anyone notices, the log context is long gone.

**Why `@Transactional` does not fix this.** The tempting move is to annotate `submit` and
call it done. It changes nothing. Redis is not a transactional resource participant — it
does not enlist in the JPA transaction, has no prepare phase, and cannot be rolled back by
the transaction manager. The DB transaction would commit or roll back entirely
independently of whether the Redis write happened. Worse, `@Transactional` would arguably
make the bug *harder* to see: it looks like the atomicity problem has been addressed, so
nobody looks again. If anything the ordering under a transaction gets more confusing,
because the commit now happens after the method returns, so the enqueue can fire for a row
that then fails to commit — the inverse failure, an ID in Redis pointing at a job that does
not exist. The worker handles that case (logs a warning and returns), but it is still a
symptom of the same missing atomicity.

Two standard fixes:

- **Transactional outbox.** Write the job row and an outbox row in a single database
  transaction. A separate relay process polls the outbox, publishes to Redis, and marks the
  row sent. Now the only atomic write is inside one system, which Postgres can actually
  guarantee. This is the correct answer and it is what I would do in production. The cost
  is real: another table, another background process, its own failure and monitoring story,
  and the relay itself needs at-least-once semantics.
- **Reconciliation sweep.** Periodically query for rows that have been `QUEUED` longer than
  some threshold and are not present in Redis, then re-enqueue them. Much simpler, and it
  degrades gracefully — it is eventually consistent rather than correct-by-construction.
  The trap is idempotency: a job that is legitimately sitting in the list waiting behind a
  long backlog looks identical to an abandoned one, so a naive sweep will enqueue it twice
  and it will execute twice. The threshold has to exceed the worst-case queue wait, or the
  sweep needs to actually check Redis membership rather than infer it from age.

**Status:** accepted for v0.1 and deliberately left unfixed. The window is small and the
project is a single process on one machine. I would rather have it documented and visible
than papered over with an annotation that only looks like a fix.

**Update (v0.2): the same dual write now exists in a second place.** `Worker.handleFailure`
schedules a retry with exactly the same shape:

```java
job.markRetrying(error, dueAt);
jobRepository.save(job);          // Postgres
jobQueue.scheduleAt(job.getId(), dueAt);   // Redis
```

A crash between those two lines leaves a job sitting in Postgres as `RETRYING` with a
`nextAttemptAt` that has passed, and no member in the sorted set that will ever cause it to
run. Same invisibility problem as the submit path, with an extra twist: this one *looks*
even more convincingly healthy, because `nextAttemptAt` is populated, so the row reads as
"scheduled" rather than merely "queued". Nothing sweeps for `RETRYING` rows whose due time
is in the past and whose ID is absent from Redis.

Also left unfixed, on purpose. The point of noting it is that the flaw is no longer a
one-off in a single controller method — it is a pattern that reappears every time a state
change has to be reflected in both stores, and it will reappear again at v0.3 (DLQ) and v0.6
(scheduled jobs). One occurrence is a shortcut; three is an argument for the transactional
outbox, and the reconciliation-sweep alternative gets correspondingly less attractive
because it now needs to handle two distinct stuck-state shapes rather than one.

### 2. Orphaned RUNNING jobs after a lost worker connection

This one showed up on its own during the v0.1 acceptance run, which is the only reason I
know about it this early.

The host machine suspended during a roughly nine-minute idle gap. On resume, the worker
thread — parked in a blocking `BRPOP` — surfaced this:

```
ERROR ... com.distroq.worker.Worker : Worker worker-ed66902a loop error, backing off
org.springframework.dao.QueryTimeoutException: Redis command timed out
 ... at com.distroq.queue.JobQueue.dequeue(JobQueue.java:37)
 ... at com.distroq.worker.Worker.runLoop(Worker.java:56)
Caused by: io.lettuce.core.RedisCommandTimeoutException: BRPOP. Command timed out after 1 minute(s)
```

HikariCP flagged the same event from its side, which is what confirms this was the host
suspending rather than anything wrong with Redis:

```
WARN ... com.zaxxer.hikari.pool.HikariPool : HikariPool-1 - Thread starvation or clock leap
detected (housekeeper delta=3m42s788ms900µs).
```

The error handling did its job. The loop caught the exception, logged it, backed off, and
kept going. A job submitted afterwards ran normally and completed with `durationMs 815`, so
the worker recovered fully without a restart.

**The part that matters is what did not happen.** The worker was idle when the connection
died. Nothing was in flight, so nothing was lost, and the incident looks like a clean
recovery. Had a job been executing, the outcome would have been materially different:
`markRunning()` has already committed by the time `JobExecutor.execute` is called, so the
row is sitting in Postgres as `RUNNING` with a `startedAt`. Its ID was popped off the Redis
list before that, and `RPOP` is destructive — Redis no longer holds any record of it. So
after the connection drops, the job is `RUNNING` in the database, absent from Redis, and
not actually executing anywhere. It stays that way forever.

Nothing in the system can detect this. A `RUNNING` row that is genuinely mid-execution and a
`RUNNING` row whose worker vanished are byte-for-byte identical. There is no heartbeat, no
lease, no owner column, no `startedAt` deadline being checked. Even the `workerId` I
generate is not persisted — it only appears in log lines — so I cannot even ask which worker
was supposed to be running it.

**Why the Redis list cannot fix this.** This is the structural limitation, not an oversight
in how I used it. `RPOP` and `BRPOP` are destructive reads with no acknowledgement step. The
instant an ID is popped, Redis has forgotten it ever existed. There is no server-side record
of "handed out but not confirmed done", so there is no query that answers "what work was
dispatched and never completed?" — not because I did not write that query, but because the
data structure does not retain the information. Any fix built on lists would mean
maintaining a second "in-flight" structure by hand and keeping it in sync with the first,
which is the dual-write problem from section 1 all over again, in a tighter loop.

**What actually fixes it — and why this drives v0.5.** Redis Streams with consumer groups.
`XREADGROUP` delivers an entry to a consumer and holds it in that consumer's Pending Entries
List until the consumer calls `XACK`. The PEL *is* the "handed out but not confirmed" record
that a list structurally cannot provide, maintained by Redis rather than by me. On top of
that, `XPENDING` lets me inspect entries that have been pending too long, and `XAUTOCLAIM`
reassigns entries idle beyond a threshold to a live consumer. The orphaned-`RUNNING` scenario
becomes detectable and recoverable rather than silent and permanent.

The honest consequence: this buys **at-least-once** delivery, not exactly-once. A reclaimed
job may have already partially executed before its worker died — half its side effects
applied, then reclaimed and run again from the top. That is not a flaw in the design, it is
the unavoidable price of recovering work whose outcome is unknown. Exactly-once is not
available here and pretending otherwise would be worse. This is precisely why idempotency
keys are on the roadmap for v0.7: redelivery is a feature I am choosing, and safety has to
come from making execution repeatable, not from trying to guarantee a job is never delivered
twice.

## Design decisions

**Why the retry delay lives in a Redis sorted set, not in the process.** Three options,
two of which are wrong for reasons worth writing down.

*`Thread.sleep` in the worker.* Trivially correct-looking and immediately fatal. There is
one worker thread. Sleeping it for the backoff window does not delay one job, it stops the
entire queue — a single job backing off for 60 seconds blocks every other job behind it,
including ones that would have succeeded instantly. The delay is also lost on restart. The
tell is that the worker is not the thing that needs to wait; the *job* is. Sleeping the
consumer to delay one item conflates the two.

*`ScheduledExecutorService`.* Fixes the head-of-line blocking, since the sleep moves off
the worker thread onto a timer. It does not fix durability: the scheduled task lives in a
JVM heap, so a restart, a crash, or a container eviction silently discards every pending
retry. Those jobs sit in Postgres as `RETRYING` with a `nextAttemptAt` in the past and
nothing anywhere that will ever act on it. That is worse than losing them outright, because
the database confidently describes a future that no longer has a mechanism behind it. It
also does not survive to the multi-instance case: an in-heap timer is per-process, so
whichever instance happened to catch the failure owns the retry, and if that instance is
the one that dies, the retry dies with it.

*Redis sorted set, scored by due-timestamp.* The pending work is a row in a datastore that
already outlives the process, not an object on a heap. The pending set is queryable
(`ZCARD` gives `delayedDepth` for free, `ZRANGEBYSCORE` answers "what is due in the next
minute"), shared across future instances, and recoverable by anything that can reach Redis.
A restart loses nothing: the next sweep after boot picks up everything that came due while
the process was down. This is the entire justification for the design, and acceptance check
A4 exists specifically to prove it.

**The atomicity trade-off in `promoteDueJobs`: Lua, with the duplicate-promotion window
closed.** The naive sequence — `ZRANGEBYSCORE`, then `LPUSH`, then `ZREM` — is three round
trips with two gaps, and both gaps lose:

- crash between range and push: the ID is still in the sorted set and still due, so the
  next sweep re-promotes it. Safe, just delayed.
- crash between push and remove: the ID is on the pending list *and* still in the sorted
  set. The next sweep promotes it again and the job executes twice.

I used a Lua script, so range + `ZREM` + `LPUSH` all execute in one atomic server-side
operation. Redis runs scripts single-threaded to completion, so there is no window for
another poller — or another instance later — to observe the intermediate state. The script
also checks the return of `ZREM` and only pushes when it removed the member itself, so two
concurrent sweeps racing over the same ID cannot both push it.

The remaining failure mode is the honest one: the script removes before it pushes within
the same atomic unit, so if Redis itself dies mid-script the whole script is rolled forward
or not at all — but if Redis dies *between* the script completing and the worker consuming
the ID, the job is on the pending list and Redis persistence settings decide whether it
survives. That is the same exposure the ordinary `enqueue` path has always had, not a new
one introduced here.

The `ZPOPMIN`-based alternative was the fallback: pop first, push second, so a crash loses
the job rather than duplicating it. It needs no scripting, but it trades a recoverable
failure for an unrecoverable one, and `ZPOPMIN` pops the lowest-scored member regardless of
whether it is actually due yet, so it needs a score check and a push-back for the
not-yet-due case — which reintroduces exactly the non-atomic sequence it was meant to
avoid. Lua is less code and a better failure mode.

**Jitter exists because of the thundering herd.** The pathological case is not one job
retrying, it is a hundred. If a downstream dependency goes down, every job that touches it
fails at roughly the same moment, and pure exponential backoff gives every one of them the
same delay. They all retry at T+1s together, all fail together, all retry at T+2s together.
The dependency gets hit by synchronised waves at exactly the moments it is trying to
recover, and the backoff — the mechanism meant to relieve pressure — is what keeps the
spikes aligned. Multiplying by a random factor in `[1-j, 1+j]` spreads the same load over a
window instead of concentrating it in an instant. Applied *after* the cap, so that jobs
sitting at the ceiling are still spread rather than all firing at exactly `max-delay-ms`.

**The poller uses `@Scheduled`; the worker still uses a hand-rolled thread.** These look
like the same problem and are not. The worker needs a *blocking* read — `BRPOP` parks until
work arrives, which gives near-zero dispatch latency and no idle CPU. That requires a thread
it can own and block indefinitely, which is precisely what a shared scheduling pool must not
have. The poller needs *periodic* execution — wake, sweep, sleep — which is the textbook
`@Scheduled` case and would be pure ceremony to hand-roll. Using `@Scheduled` for the worker
would mean giving up the blocking pop and going back to polling; using a manual thread for
the poller would mean writing a timing loop Spring already provides. Different concerns,
different mechanisms.

**Poller latency is the cost of polling.** A job due at T is promoted at up to
T + `poll-interval-ms`, so measured backoff gaps run consistently long. I measured this
during the v0.2 acceptance run rather than leaving it as an assertion — see *Measured
costs* below for the numbers and what they nearly hid.

**Status flips where they are observable: the poller does not touch job status.** The
promotion is a queue-level move, and the meaningful transition is `RETRYING → RUNNING`,
which the worker already performs. The alternative — have the poller write `RETRYING →
QUEUED` after a successful promotion — would add a third dual write (Redis move, then
Postgres update) for a state that would exist for a few milliseconds before the worker
overwrote it, and would introduce a genuinely confusing failure mode where a crash between
the two leaves a job `RETRYING` on the pending list. `RETRYING` is treated as equivalent to
`QUEUED` for dispatch purposes and distinct from it for reporting, which is exactly what the
two statuses are for. `nextAttemptAt` already tells anyone looking when the job becomes
eligible, so nothing is hidden by not writing the intermediate state.

**`maxAttempts` gets a DB-level default rather than a documented data wipe.** `ddl-auto:
update` cannot add a `NOT NULL` column to a populated table without one. `@ColumnDefault("3")`
makes Hibernate emit `add column max_attempts integer default 3 not null`, which Postgres
backfills. The alternative was to document `docker compose down -v` in the README, and I
rejected it: a job queue that loses its history to a schema change is not making a good
argument for itself, and "3" is a defensible retry budget to impute to rows that predate the
concept. It is also a preview of the real answer — this is a migration, and it is being
expressed as an annotation because there is no migration tool yet. It also turned out to be
only half the migration — see the `jobs_status_check` entry under Resolved issues, which is
what moved Flyway forward to v0.2.1.

**Postgres is the single source of truth; Redis holds only job ID strings.** The queue is a
coordination mechanism, not a data store. Serializing the whole job into Redis would have
been marginally faster to dequeue and would have created two copies of the truth that drift
the moment either is updated. Keeping Redis to bare IDs means the queue implementation can
be swapped wholesale — list to Streams at v0.5 — without touching the job schema, migrating
any data, or reconciling two representations. The queue becomes a detail rather than a
commitment.

**Blocking `BRPOP` rather than a sleep-poll loop.** Polling means picking a number, and every
number is wrong: short intervals burn CPU doing nothing all night, long intervals add latency
to every job. `BRPOP` gives near-zero dispatch latency with no idle CPU cost, and the code
reads as intent rather than as a timing workaround. The cost is a long-lived blocked
connection, which is exactly the thing that broke during the clock-leap incident above. I
still think it is the right trade — the failure is visible, catchable, and recoverable,
whereas polling's cost is permanent and silent.

**State transitions are methods on the `Job` entity, with no public setters for `status`,
`attemptCount`, or the timestamps.** `markRunning()`, `markSucceeded()`, `markFailed(error)`.
Nothing outside the class can put a job into an incoherent state — `SUCCEEDED` with an error
message, `FINISHED` with no `startedAt`, an `attemptCount` that does not match the number of
actual attempts. Illegal states are unrepresentable from outside rather than merely
discouraged by convention. It also means retry and backoff logic in v0.2 has one obvious
home: `markFailed` already owns the attempt bookkeeping, so incrementing and scheduling belong
right there instead of being scattered across the worker.

**API and worker share a process, but not a dependency.** For v0.1 they run in the same
Spring Boot application because two processes would be pure ceremony at this stage. But the
worker never imports anything from the `api` package and the controller never imports
anything from `worker`; they communicate only through `JobQueue` and `JobRepository`. The
coupling that would actually hurt is compile-time coupling, and there is none. Splitting them
at v0.7 should be moving a package and adding a second entry point, not untangling a
rewrite.

**`202 Accepted` on submit, not `201 Created`.** `201` claims a resource has been created and
is ready. What has actually happened is that the work has been accepted for later execution
— the result does not exist yet and may never exist if the job fails. `202` says exactly
that, and it sets the right expectation for a client that might otherwise assume the job is
done because it got a success code and an ID back.

**`ddl-auto: update` for schema management.** This is wrong for production and right for now.
The schema is changing more or less daily — v0.2 adds retry and backoff columns, v0.5 changes
how in-flight work is tracked — and hand-writing a migration for each churn would be effort
spent on a shape that is not settled. The plan *was* to switch to Flyway around v0.5, once the
schema stopped moving every few days. v0.2 proved that plan wrong by breaking under it; see
*What v0.2 changed about the plan*. Flyway now lands at v0.2.1.

## Measured costs

**Poller latency: a job due at T runs somewhere in `[T, T + 1000ms]`.** The retry poller is a
`@Scheduled` fixed delay of 1000ms. Nothing wakes it when a job becomes due — it just checks,
periodically, and finds whatever has accumulated. The latency is not a bug in the
implementation, it is the mechanism working as designed. A job whose backoff expires one
millisecond after a tick waits nearly a full interval for the next one.

This nearly cost me the interpretation of acceptance check A2. The raw inter-attempt gaps came
out at **1876ms and 2009ms** — which reads as flat. Two gaps of roughly the same size is
exactly what "backoff is not being applied at all" looks like, and that was my first reading of
it. It was wrong. Each gap is *scheduled backoff + poller latency*, and splitting them apart
gives backoffs of **879ms and 1908ms** — a clean doubling — with poller latency of **997ms and
101ms** respectively. The 997ms case is the worst case: the job came due just after a tick and
waited out almost the entire interval. The 101ms case is the lucky one. Averaging two samples
of a uniformly distributed `[0, 1000ms]` error term and reading the result as signal is how you
talk yourself out of a feature that works.

A five-attempt run settled it: **839, 2168, 3390, 8865, 17760ms**. Unambiguous.

The lesson is about the ratio, not the absolute number. At ~1s backoffs, a ~1s polling error is
the *same order of magnitude as the signal*, so the measurement is mostly noise. Once the
backoff grows past a few seconds the polling error disappears into it — by the 17760ms attempt,
a full second of jitter from the poller is a rounding error. Short backoffs are precisely where
this mechanism measures worst, and short backoffs are what the first three attempts of every
retry sequence use. Any future latency assertion about early attempts has to account for the
poll interval or it is measuring the poller, not the policy.

**The trade-off has no good setting.** Shortening the interval cuts latency and raises constant
Redis load in direct proportion — at 1s the sweep is one `EVALSHA` per second per instance, at
100ms it is ten, forever, and the overwhelming majority find nothing and return zero.
Lengthening it is cheaper and makes short backoffs meaningless: a 1s backoff behind a 5s poll
interval is not a 1s backoff. There is no interval that is good for both, because the two
requirements are in direct opposition.

**The fix is not a better interval, it is not polling.** A blocking read that returns the
instant work becomes available has neither the latency nor the idle-load problem — there is
nothing to tune because there is no timing loop. This is the same argument that chose `BRPOP`
over a sleep-poll loop in v0.1, arriving a second time from a different direction, and it is
now backed by numbers I measured on this system rather than by assertion. It is the concrete
case for the Redis Streams work at v0.5.

## What v0.2 changed about the plan

Two things came out of this version that I did not plan for and that moved the roadmap. Both
were found by running the system, not by thinking about it, which is the point of writing them
down together.

**1. `ddl-auto: update` fails silently and misleadingly, so Flyway moves from v0.5 to v0.2.1.**

Hibernate generated a `CHECK` constraint from v0.1's four-value `JobStatus` enum. `ddl-auto:
update` adds columns — it verifiably added `max_attempts` and `next_attempt_at` in the same
boot — but it never alters an existing constraint. Every `RETRYING` write was therefore
rejected at commit time. Fixed with a manual `ALTER TABLE jobs DROP CONSTRAINT`; the full
write-up is under *Resolved issues*.

What matters for planning is not the bug, it is the shape of the failure. It presented
*deceptively*. The `job_attempts` FAILURE row commits before the status update, in its own
transaction, so the evidence that the retry path had executed correctly was sitting in the API
response while the job itself sat frozen at `RUNNING`. The symptom looked like "the retry
branch never ran" when the truth was "the branch ran perfectly and its write was rejected."
I also tried `@Column(columnDefinition = ...)` to suppress constraint generation, verified
empirically that it does not work, and reverted it.

So `ddl-auto: update` is not merely *incomplete*, which I already knew and had accepted. It
performs part of a migration, declines the rest, reports nothing at startup, and defers the
failure to a runtime write path that only executes when something has already gone wrong. That
is a worse property than refusing outright. And it is not a one-off: v0.3 adds `DEAD_LETTERED`
to the same enum and would hit the identical wall, with the identical misleading symptom.
Waiting until v0.5 means walking into it again with the fix already written down. **Flyway
moves to v0.2.1** — before the next enum change, not after.

**2. Measured poller latency is a real argument for Streams at v0.5, not a stylistic one.**

Covered in full under *Measured costs*. The short version: the polling error is the same order
of magnitude as the thing being measured at short backoffs, and no choice of interval fixes
both latency and idle load. Previously "blocking reads beat polling" was a preference carried
over from the v0.1 `BRPOP` decision. It is now a number I measured on this system. v0.5 keeps
its slot, but for a better reason than it had.

Neither of these changed what v0.2 does. Both changed what I think v0.2.1 and v0.5 are *for*.
Note that the dual write under *Known limitations* did **not** move — it got worse (it now
exists in two places, submit and retry scheduling) and still did not earn a fix, because it
remains a narrow window on a single-process deployment. The difference is that the constraint
bug actually fired and the dual write still has not. Roadmaps should move on evidence, and
"this broke" is evidence in a way that "this could break" is not.

## Resolved issues

**Worker stack trace escaping on shutdown (acceptance Check 8).**

*Symptom.* Pressing Ctrl+C produced a full `RedisSystemException` stack trace at ERROR level,
originating in the worker run loop, with `Caused by: io.lettuce.core.RedisException:
Connection closed`. Functionally harmless — the app still exited — but a stack trace on every
clean shutdown is noise that trains you to ignore stack traces, and it would eventually mask
a real shutdown failure.

*Evidence.* The timestamps were the whole story:

```
13:48:54.166 ERROR [worker-d7a39b8f] Worker worker-d7a39b8f loop error, backing off
13:48:54.173 INFO  [ionShutdownHook] Worker worker-d7a39b8f shutting down
```

The loop error fired seven milliseconds *before* `@PreDestroy stop()` logged. The run loop
already had a guard meant to suppress exactly this — `if (!running.get()) return;` — but
`running` was still `true` at the moment the exception was caught, because `stop()` had not
run yet. The guard was correct; it was just reading a flag that was set too late.

*Root cause.* Spring closed the Lettuce connection factory before it destroyed the `Worker`
bean, so the blocked `BRPOP` was aborted while the worker still believed it was running.
`@PreDestroy` is structurally the wrong hook for this: by the time it fires, bean destruction
is already underway, and there is no ordering guarantee between the destruction of the worker
and the destruction of the Redis infrastructure it depends on. Adding `@DependsOn` or
reordering beans would be fighting the container for a guarantee it does not offer.

*Fix.* Clear the `running` flag on `ContextClosedEvent`, which is published before *any* bean
destruction begins:

```java
@EventListener(ContextClosedEvent.class)
public void onContextClosed() {
    running.set(false);
}
```

`stop()` still does the executor shutdown and `awaitTermination`. Now a `BRPOP` aborted by
shutdown is correctly identified as expected teardown, and the loop exits silently. Verified:
clean shutdown log, no ERROR lines, no stack frames, empty stderr.

*The broader lesson.* Telling "this failed because we are shutting down" apart from "this
failed for real" is a recurring problem, and the naive version of the check is almost always
racy. The same distinction comes back at v0.7 in a nastier form: a worker shutting down while
executing a job must requeue that job rather than mark it `FAILED`, because "the process was
asked to stop" is not a property of the job and should not be recorded as one. Getting the
shutdown signal to arrive *before* the code that has to interpret it is the general shape of
the fix. `RetryScheduler` was given the same `ContextClosedEvent` guard from the start for
exactly this reason.

**Adding a status broke every retry: `jobs_status_check` (v0.2 acceptance run A1).**

*Symptom.* The first acceptance check failed in a way that looked like the retry logic was
simply absent. The job went `RUNNING`, a `FAILURE` row appeared in `job_attempts` with the
right error message, and then nothing — the job sat at `RUNNING`, `attemptCount 1`,
`errorMessage null`, `nextAttemptAt null`, for all fifteen polling ticks. `delayedDepth`
stayed 0 and the poller logged "No due jobs to promote" once a second, forever.

*Evidence.* The worker log had the answer, under a Hibernate stack trace:

```
Caused by: org.postgresql.util.PSQLException: ERROR: new row for relation "jobs"
violates check constraint "jobs_status_check"
  Detail: Failing row contains (6e26ebe4-..., 1, ..., RETRYING, fail_n_times, ..., 5, ...).
```

and `\d jobs` confirmed it:

```
"jobs_status_check" CHECK (status::text = ANY (ARRAY['QUEUED','RUNNING','SUCCEEDED','FAILED']))
```

*Root cause.* Hibernate generates a `CHECK` constraint for `@Enumerated(EnumType.STRING)`
columns, enumerating the values that exist when the table is created. v0.1 created that
constraint with four statuses. `ddl-auto: update` adds missing columns — it verifiably added
`max_attempts` and `next_attempt_at` in the same boot — but it does not widen an existing
check constraint. The schema was frozen at v0.1's idea of what a status could be, and every
`RETRYING` write was rejected at commit.

What made this slower to spot than it should have been: the ordering of writes made a
rejected write look like missing logic. The `job_attempts` insert happens before the status
update and commits in its own transaction, so the evidence that the failure path *had* run
correctly was sitting right there in the API response. That made "the retry branch never
executed" the obvious first theory, and it was wrong — the branch executed perfectly and its
result was discarded at commit.

*Fix.* Drop the stale constraint once, documented in the README as the v0.1 → v0.2 upgrade
step:

```sql
ALTER TABLE jobs DROP CONSTRAINT IF EXISTS jobs_status_check;
```

`update` mode does not recreate check constraints on an existing table, so it stays dropped.
A fresh database is unaffected — Hibernate creates the constraint with all five statuses.

*What I tried that did not work.* I first assumed `@Column(columnDefinition = "varchar(32)")`
would suppress the generated constraint, on the theory that an explicit column definition
overrides Hibernate's inferred DDL. It does not. I checked empirically rather than trusting
it: dropped `job_attempts` entirely, restarted so Hibernate would recreate it from scratch
under the new mapping, and queried `pg_constraint` — `job_attempts_outcome_check` was still
there. The annotation bought nothing, so I reverted it rather than leave a change that
implies a guarantee it does not provide.

*Why this moves Flyway up the roadmap.* `ddl-auto: update` is not a migration tool, and this
is the sharpest demonstration of it so far: it silently did *part* of the v0.2 migration —
both new columns, including the `NOT NULL` backfill I had specifically designed for — and
silently declined the rest. A partial migration with no startup error is worse than a refused
one, because the application boots clean and then fails at runtime, on a write path that only
executes when something has already gone wrong. Every enum I add from here carries the same
trap: v0.3's DLQ status and v0.6's scheduled status will each need this same manual drop.
That is a migration file, and pretending otherwise has now cost a debugging session. Flyway
is therefore pulled forward to v0.2.1 — see *What v0.2 changed about the plan*.

