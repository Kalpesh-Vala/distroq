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

**Update (v0.3): the predicted third site arrived, exactly as predicted.** `JobController.replay`:

```java
job.prepareForReplay(replayAttempts);
jobRepository.save(job);                  // Postgres
deadLetter.markReplayed(); ...save(...);  // Postgres
jobQueue.enqueue(job.getId());            // Redis
```

A crash before the `enqueue` leaves the job `QUEUED` in Postgres with its DLQ row already
marked `replayed = true`, and no ID in Redis. This is the **worst-presenting** variant of the
three so far. The submit case at least leaves a job that has never run; the retry case leaves
one that reads as scheduled. This one leaves a job that reads as *successfully replayed* —
the operator got their `202`, `replayCount` incremented, `replayedAt` is populated, and the
job is no longer listed under `/api/dlq?replayed=false`. It has fallen out of the DLQ view
without ever re-entering the queue, so neither of the two places anyone would look shows a
problem. The DLQ, which exists to make failures visible, has been used to make one invisible.

Still unfixed, and this is the version where that stops being a comfortable call. The
prediction in the v0.2 note was that a third occurrence would be the argument for the outbox.
It is. What has changed is not the probability — the window is the same handful of
milliseconds — but the *detectability*, which has got monotonically worse each time.

**And note what the new `@Transactional` does not do.** `DeadLetterWriter.deadLetter` is
`@Transactional` so that the job update and the `dead_letters` upsert commit or roll back
together. That is a genuine guarantee about two writes *to Postgres*. It says nothing about
Redis, which is not a transaction participant and has no prepare phase. It is deliberately
placed on the exhaustion path, where both writes are to the database and an annotation is
therefore the correct tool — precisely the opposite of the submit path, where the same
annotation would have been theatre. The distinction matters because the two look identical in
a diff: one is a real fix for a real atomicity problem, the other would be a fake fix for a
different one. `DeadLetterWriter` is a separate bean rather than a method on `Worker` for the
mundane reason that Spring's transaction advice is proxy-based, so a self-call from
`handleFailure` would have run with no transaction at all and looked fine.

### 2. The `dead_letters` / `jobs` invariant is enforced by application code, not the database

The rule is: a row in `dead_letters` with `replayed = false` must correspond to a job with
status `DEAD_LETTERED`. Nothing in Postgres enforces it. There is no trigger, no CHECK, no
generated column — and there could not easily be one, because the invariant spans two tables
and one direction of it is conditional.

What holds it up is that exactly two code paths write it, both of which set the pair
together: `DeadLetterWriter.deadLetter` (status `DEAD_LETTERED`, row `replayed = false`) and
`JobController.replay` (status `QUEUED`, row `replayed = true`). What could break it:

- a crash between the two writes in `replay`, which are not in one transaction (see above) —
  leaving `QUEUED` + `replayed = true`, which is *consistent*, then the missing enqueue makes
  it permanently stuck rather than inconsistent. The genuinely inconsistent variant is the
  reverse ordering, which the current code does not have.
- any second writer. A `psql` session setting a status by hand, a future service, a data fix.
  This is the same exposure accepted when the `status` CHECK constraint was dropped in v0.2.1,
  and it is the second thing that decision has now cost.
- a future bulk-replay or purge feature that updates `jobs` and `dead_letters` in separate
  statements. Noted here so it is not rediscovered.

Detecting a violation is a single query, and there is deliberately no reconciliation job
running it:

```sql
SELECT d.job_id, j.status FROM dead_letters d JOIN jobs j ON j.id = d.job_id
WHERE d.replayed = false AND j.status <> 'DEAD_LETTERED';
```

### 3. Orphaned RUNNING jobs after a lost worker connection

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

**Why the DLQ is a table plus a status, and not a status alone.** The status alone would have
been strictly less code: add `DEAD_LETTERED`, change one line in `Worker`, filter
`/api/jobs?status=DEAD_LETTERED` for the listing, done. No migration beyond the enum, no
second entity, no second write. I did not do that, and the reason has to be better than
"tables feel more real".

Two things earn the table. First, replay needs state that is *about the dead-lettering event*,
not about the job: when it was moved (`moved_at`, distinct from the job's `finished_at`),
whether it has been replayed, when, and how many times. Hanging `replayed_at` and
`replay_count` off `jobs` would put DLQ bookkeeping on every row in the system, including the
overwhelming majority that will never be dead-lettered, and would make `jobs` the place you go
to understand a subsystem it is not part of. Second, "what is in the DLQ right now" becomes a
scan of a table that stays small — it holds one row per job that has ever failed terminally —
rather than a status-filtered scan of `jobs`, which grows without bound. Today, with an index
on `status`, those perform identically. They stop being identical at the point where anyone
would care, and the migration to fix it later is more expensive than doing it now.

**What it costs, stated plainly rather than glossed:** a second write on the exhaustion path,
and therefore a third instance of the dual write. That is written up under *Known limitations*
and it is a real cost, not a rounding error. It also adds an invariant that spans two tables
and is held together by application code alone — also written up there. Choosing the table
means choosing both of those.

**Why replay continues history instead of resetting it.** The obvious implementation of replay
is "put it back like it was new": `attemptCount = 0`, fresh `maxAttempts`, clear the attempt
rows. Every part of that is wrong for this system.

The test is what `GET /api/jobs/{id}` says afterwards. With history preserved it says: failed
twice with these two errors, was dead-lettered, was replayed at this time, succeeded on
attempt 3. That is a complete account of an incident. With history reset it says: succeeded on
attempt 1. The job that caused someone to be paged is now indistinguishable from one that
worked first time. A dead-letter queue exists to make failure legible, so an operation inside
it that destroys the evidence of failure is working against the point of the feature.

`attemptCount` therefore carries over — a job that failed 3 times runs next as attempt 4, and
`job_attempts` rows append with increasing `attempt_number` rather than restarting. The
`dead_letters` row is retained rather than deleted for the same reason: "has this job ever
been dead-lettered" and "how often" stay answerable, and `replay_count` names repeat
offenders, which a delete-on-replay design cannot do at all.

**`maxAttempts` is extended, not reset, and this is load-bearing.** If `attemptCount` carries
over then `maxAttempts` must move too, or the replayed job is at 3/3 the moment it is
requeued, fails `hasAttemptsRemaining()` on its first failure, and is dead-lettered again
immediately — a replay that cannot possibly succeed more than once. So on replay
`maxAttempts = attemptCount + distroq.dlq.replay-attempts` (default 3): the replay gets its
own budget, expressed relative to where the job actually is. This is the single most
load-bearing line in the version and it has the one unit test I would keep if I could only
keep one: `hasAttemptsRemaining()` must be true immediately after `prepareForReplay`.

Resetting `maxAttempts` to its original value would work by accident when the original budget
happened to exceed `attemptCount`, and fail silently when it did not. Relative is correct;
absolute is a coin flip.

**Replay goes on the pending list, not the delayed set.** The retry path parks a job in
`distroq:jobs:delayed` with a backoff. Replay does not, and enqueues directly. Backoff exists
to protect a downstream dependency from a machine retrying in a tight loop. A replay is a
human deciding, once, that the thing is fixed — there is no herd to spread and no automatic
loop to slow down. Making an operator who just typed `redis-cli DEL` wait out a backoff
window they did not ask for is surprising behaviour in exchange for nothing. This also keeps
the two paths honestly distinct: the delayed set means "the system decided to wait", the
pending list means "run this now".

**v0.2 `FAILED` rows are not backfilled into `dead_letters`.** `FAILED` is kept in the enum
and redefined: it is a transient per-attempt outcome, and nothing writes it as a terminal job
state any more. The tempting tidy-up is a one-line `INSERT ... SELECT` in V3 giving every
existing `FAILED` job a dead-letter row so the DLQ is "complete".

That would be fabricating history. Those jobs never went through the exhaustion path that
writes a `dead_letters` row; there is no `moved_at` for them because no move happened, and any
value chosen — `finished_at`, `now()` — is an invention presented as a record. It would also
make them replayable, which retroactively grants a capability that did not exist when they
failed, against code paths that may since have changed. A DLQ whose contents are partly real
events and partly reconstructed ones is worth less than one that is smaller and entirely true.
The visible cost is a handful of legacy rows that are terminal and not replayable, which is an
accurate description of what they are.

**Adding `DEAD_LETTERED` required zero DDL, which is the whole v0.2.1 argument settled.** This
is the same change that broke v0.2: a new value in `JobStatus`. Then, Hibernate's generated
`jobs_status_check` enumerated the four v0.1 statuses, `ddl-auto: update` would not widen it,
and every write of the new status was rejected at commit with a symptom that pointed at the
application. Now, V3 does not mention `jobs` at all. The status change is a one-line enum edit
with no migration, because there is no constraint left to widen.

The contrast is worth keeping precise, because it is the only real evidence either way. v0.2:
one enum value cost a debugging session, a hand-run `ALTER TABLE`, and left the live database
and a fresh one provably different. v0.3: the identical change cost nothing and both databases
came out byte-identical (verified — see *Verified in v0.3*). The trade-off accepted in v0.2.1
was that the database no longer defends `status` against a writer that is not this
application. That cost is still real, and it showed up again this version in the
`dead_letters`/`jobs` invariant, which is likewise unenforced. Two unenforced invariants is a
different position from one, and it is the direction that eventually argues for putting
integrity back into the schema — but it has still not cost anything measurable, and the enum
has now changed twice more without incident.

**The `job_attempts` foreign key was added now, deliberately, while the table is small.** It
was never generated because `JobAttempt` stores a raw `UUID jobId` rather than a `@ManyToOne`,
so Hibernate had no association to infer one from. V1 reproduced that absence faithfully,
because a baseline describes what exists rather than improving it. V3 is the first migration
since where adding it is in scope.

The reason for doing it in the same version as an unrelated feature is that the cost only
goes up. Adding a FK validates it against every existing row, which is fast on 7 rows and a
table-lock-shaped problem on seven million. And if orphans ever do appear, the choice becomes
"delete production rows" or "never add the constraint", both of which are worse than acting
now. Zero orphans were confirmed before applying it, and the migration would have failed
rather than silently accepted bad data had there been any.

The raw-UUID mapping stays. The FK is a *database* integrity guarantee; adding `@ManyToOne`
would be an *ORM* change with its own consequences — a lazy proxy that can escape into DTO
mapping outside a session, which is the exact thing the raw UUID was chosen to avoid. Those
two are routinely conflated and they are not the same decision.

**Why the transaction is on the exhaustion path and not on submit.** Covered under *Known
limitations*, but the short version belongs here too: `@Transactional` is the right tool when
every write it covers is to the same transactional resource, and theatre when one of them is
not. Exhaustion writes `jobs` and `dead_letters` — both Postgres, both genuinely made atomic.
Submit and replay write Postgres and then Redis — no annotation can make those atomic, and
adding one would advertise a guarantee that does not exist.

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

**`ddl-auto: update` for schema management — removed in v0.2.1.** It was wrong for production
and defensible while the schema changed daily, and the plan *was* to switch to Flyway around
v0.5 once things settled. v0.2 proved that plan wrong by breaking under it; see *What v0.2
changed about the plan*. The entries below record what replaced it.

**Flyway moved from v0.5 to v0.2.1 because the failure mode was worse than "incomplete".**
The full write-up of the `jobs_status_check` bug is under *Resolved issues* and I am not
restating it. What made it a scheduling decision rather than a bug fix is the shape: the
tool did *part* of a migration, declined the rest, logged nothing at startup, and deferred
the failure to a runtime write path that only executes when something has already gone
wrong. "Incomplete" I had accepted knowingly. "Silently and misleadingly incomplete" is a
different property, and it is not one that gets better by waiting.

The decisive argument was that v0.3 adds `DEAD_LETTERED` to the same enum, which is
byte-for-byte the same trap: `ddl-auto: update` would decline to widen the constraint, every
dead-letter write would be rejected at commit, and the symptom would again point at the
application rather than the schema. Postponing to v0.5 meant walking into a known failure
twice with the fix already written down in this file. Fixing the mechanism before the next
enum change is the cheapest ordering available.

**V1 is a single baseline, not reconstructed per-version migrations.** I could have written
V1 as the v0.1 schema and V2 as the v0.2 additions, and it would have looked like a tidier
history. It would have been fiction. Those migrations were never executed in that form —
the real v0.1 → v0.2 transition was Hibernate emitting `alter table` at startup plus a
hand-run `ALTER TABLE ... DROP CONSTRAINT`, and no reconstruction reproduces that faithfully.
Worse, fabricated migrations get *tested* on fresh databases only: nobody ever runs V1-then-V2
against a real v0.1 database, so the reconstruction can be wrong indefinitely without anyone
noticing. The version history that matters is in git, where it is accurate. V1 is one honest
statement of "this is the schema as it actually exists", taken from `pg_dump` rather than
written from memory.

**A baseline migration is descriptive, not prescriptive — new work must sit above the baseline
version or it reaches only new databases.** This is the non-obvious consequence of
`baseline-on-migrate` and it changed how I split the work. V1 does not *create* anything on a
database that predates Flyway; it only asserts "this is what is already here", and Flyway
records it as applied without running a line of it. So a change placed only in V1 reaches new
databases and never reaches the existing one — which is precisely the divergence migrations
exist to prevent, reintroduced by the mechanism meant to fix it. The three query
indexes and the drop of the leftover `job_attempts_outcome_check` therefore went into V2, not
V1. V1 reproduces the dump exactly; V2 does the new work and runs on both paths. Verified by
diffing `\d jobs` and `\d job_attempts` between the baselined database and a from-scratch one
— identical, including index names.

**Why both paths had to be tested separately.** A baseline that works on the developer's
existing database and produces a broken fresh one is invisible until somebody clones the
repo, and it is invisible *to the person who wrote it* because their database is the one that
works. So the fresh path is not a nice-to-have check, it is the one that is actually likely
to be wrong. Both were run: on the existing database Flyway logged `Successfully baselined
schema with version: 1` and then applied only V2, with row counts unchanged at 32 jobs and 49
attempts; on an empty one it logged `All configured schemas are empty; baseline operation
skipped` and executed V1 and V2 in order.

**No CHECK constraint on `status`, deliberately.** The application enum is the source of
truth. A DB-level CHECK over an enum that gains a value most versions is a migration burden
that has already produced one silent failure, and it buys very little here: the only writer
is the application. What it gives up is real and worth stating plainly — nothing at the
database level now prevents a bad status being written by a `psql` session, a future second
service, or a bad manual data fix. I am accepting that in exchange for the enum being
changeable without a coordinated DDL step. The same reasoning retired
`job_attempts_outcome_check` in V2 rather than leaving one enum guarded and the other not.

**What `ddl-auto: validate` actually buys, verified rather than assumed.** It converts silent
schema drift into a startup failure. The v0.2 constraint bug was possible precisely because
nothing compared the entities to the schema — the mismatch existed from the moment `RETRYING`
was added and was only discovered by a job failing at runtime, hours later, with a symptom
that pointed somewhere else entirely.

Configuring a safety net and never testing that it catches anything is its own failure mode,
so I checked it: adding a `scratchColumn` field to `Job` with no corresponding column makes
startup fail with

```
SchemaManagementException: Schema-validation: missing column [scratch_column] in table [jobs]
```

which names the exact column and never reaches the point of serving traffic. That is the
property I actually wanted from this version — not "Flyway is installed" but "a schema
mismatch cannot survive a restart".

Two limits worth knowing. First, `validate` checks tables, columns and types; it does not
check indexes, defaults, or CHECK constraints, so it would *not* have caught the original
`jobs_status_check` bug directly — what fixes that is no longer generating the constraint.
Second, Flyway's checksum enforcement does not cover a baselined migration: on the existing
database V1 is recorded as `type=BASELINE` with a null checksum, and I confirmed that editing
V1 there changes nothing and the app starts happily. On a fresh database V1 is a real `SQL`
row with checksum `1050819957` and the same edit is rejected. So the immutability guarantee
applies to migrations Flyway actually executed, which is every migration from V2 onward on
every database, but not to the baseline itself on databases that predate it.

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

## Verified in v0.3

**Both database paths converge, checked rather than assumed.** The failure mode v0.2.1 was
built to prevent is a migration that works on the developer's database and produces a
different schema on a fresh one. V3 was applied to the pre-existing database (3 jobs, 7
attempts, unchanged afterwards; V3 recorded `success = t`), then the volume was destroyed and
the app started against an empty database, where V1, V2 and V3 all executed in order. The
`\d` output for `jobs`, `job_attempts` and `dead_letters` from the two databases diffs to
**zero lines**, index names and column order included.

**`GET /api/dlq` issues 2 SQL statements regardless of page size.** Measured with
`logging.level.org.hibernate.SQL=DEBUG` against a 6-entry DLQ: one `select ... from
dead_letters order by moved_at desc fetch first ? rows only`, then one `select ... from jobs
where id in (?,?,?,?,?,?)`. The naive version — calling `jobRepository.findById` inside the
mapping loop — would have been 7, growing with the page. The fix is `findAllById` plus a
`Map<UUID, Job>` built once, which is the whole of it; the point of measuring was that N+1 is
invisible in code review precisely because the per-item lookup reads perfectly naturally.

**Re-dead-lettering does not violate the primary key.** `dead_letters` is keyed by `job_id`,
so the second exhaustion of a replayed job has to update rather than insert. Verified end to
end: a job dead-lettered, replayed, and dead-lettered again came back with `replayCount: 1`
preserved, `replayed` back to `false`, a refreshed `movedAt`, and all six attempt rows intact.
No constraint-violation line anywhere in the log — the only ERROR was the intentional
`DEAD_LETTERED after 6 attempt(s)` one. This is the case that a status-only DLQ would never
have had, and it is the one that would have shipped broken if only the happy path had been
exercised.

**`server.error.include-message: always` was needed and is not free.** The 409 on replaying a
non-dead-lettered job is required to name the status the job is actually in. It did not:
Spring Boot omits `message` from error bodies by default, so the `reason` on every
`ResponseStatusException` in this application — including the v0.2 `maxAttempts must be at
least 1` 400, which has apparently been invisible the entire time — was being discarded. The
setting fixes all of them at once. It also exposes messages from *unhandled* exceptions, which
is a genuine information leak on anything internet-facing. Accepted for a local
unauthenticated development service and flagged in the README, rather than reached for
silently.

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

*Fix.* Drop the stale constraint once. In v0.2 this was a manual step documented in the README
as the v0.1 → v0.2 upgrade:

```sql
ALTER TABLE jobs DROP CONSTRAINT IF EXISTS jobs_status_check;
```

`update` mode does not recreate check constraints on an existing table, so it stayed dropped.
A fresh v0.2 database was unaffected — Hibernate created the constraint with all five statuses.

v0.2.1 removed the manual step entirely: the constraint is simply not part of the Flyway
baseline, so no database has it and none regains it. That a schema fix had to be applied by
hand — leaving the live database and a freshly created one provably different — is what
motivated moving Flyway forward a version.

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

