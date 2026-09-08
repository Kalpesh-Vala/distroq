# DistroQ — Engineering Notes

Running log of known limitations, failure modes observed, and design decisions.
Written for my own reference and interview prep.

## What v0.7 changed about the plan

### 1. Why the dual-write problem required an outbox

PostgreSQL and Redis cannot participate in one local transaction. Annotating a method that writes
both systems never made the pair atomic. v0.7 writes the job transition and its publication intent
to PostgreSQL together, then lets a relay perform the Redis half later.

### 2. What the outbox guarantees

A committed submission, retry schedule, user schedule, or DLQ replay has a durable
`outbox_events` row. A process crash before Redis publication no longer silently loses that intent.
Multiple relays claim rows with `FOR UPDATE SKIP LOCKED` and a short lease.

### 3. What the outbox does not guarantee

The relay remains at-least-once. It does not by itself guarantee exactly-once Redis publication,
and it says nothing about exactly-once external job side effects. Redis publication is separately
deduplicated by event ID.

### 4. Relay crash windows

```text
publish Redis
crash before marking published
relay retries
Redis deduplicates by event ID
```

The Stream or sorted-set write and `distroq:outbox:published:<event-id>` marker happen in one Lua
script. PostgreSQL `published_at` is written only after Redis confirms the script. The
`fail-after-publish` acceptance hook reproduces this window without killing a transaction early.

### 5. Outbox retention

Unpublished events are never deleted automatically. Failed rows retain attempt count and the last
error for investigation. Published rows have a 30-day retention target but no automatic cleanup
in v0.7. Redis markers expire after seven days to bound memory; that period must exceed expected
relay recovery time. An unpublished row older than the marker lifetime requires investigation
before retry, because its prior marker may have expired.

The retry ceiling is an explicit terminal operational state. Once `attempt_count` reaches
`outbox.max-attempts`, the row is no longer claimable, the relay emits a terminal error, and
`outboxTerminalFailed` counts it separately from retryable `outboxFailed` rows. The row is retained
for inspection; v0.7 intentionally has no automatic reset or destructive dead-letter cleanup.

### 6. Idempotency-Key semantics

The key is optional, trimmed, globally scoped, opaque, and 1-128 characters. Same key plus the
same canonical request returns the original job. Same key plus a different request returns 409.
The hash uses resolved priority and attempts plus `scheduledAt` normalized to `Instant`, so
equivalent offsets hash equally. Authentication and tenant-scoped keys remain deferred.

### 7. Why idempotent submission is not idempotent execution

Submission idempotency prevents a client retry from creating a second job. A worker crash can
still cause the same job's user code to run again. External effects need their own idempotency key
or a transaction supplied by the external system.

### 8. Database execution leases

Consumer-group ownership tracks a Stream delivery, not authority over the PostgreSQL job. v0.7
therefore conditionally updates the job to set an execution owner, lease deadline, and active
attempt ID before running. Only a matching unexpired owner/attempt can renew or finalize. Expired
`RUNNING` work can be claimed by another worker; terminal work cannot.

### 9. Lease renewal limitations

Heartbeats extend long-running ownership. Losing ownership prevents final DistroQ persistence but
cannot safely stop arbitrary Java code or undo an external effect already performed. A worker can
lose its lease after external work and before persistence. The system remains at-least-once.

### 10. Multiple consumers

Each configured loop gets a unique `<prefix>-<8 random hex>` consumer name, uses the shared
`distroq-workers` group, and writes that name to attempt history. Concurrency is per process and
defaults to one. A process-local semaphore keeps active user-code executions within the configured
limit, including reclaimed deliveries.

The recovery sweep also gets a dedicated generated consumer name. It uses that exact identity for
both `XAUTOCLAIM` and the conditional PostgreSQL execution claim, so it never shares worker loop
1's Redis or database owner identity.

### 11. Verification count

Maven Surefire report totals are authoritative for release verification because they count
executed leaf test cases. VS Code's Test view can include discovered class/container nodes in its
displayed total. The final run had 193 Maven tests with one skipped, leaving 192 passing leaf
tests; VS Code reported 216 passed items because it also counted 24 passing test-class containers.
This is a reporting-model difference, not evidence that Maven omitted a test source set.

### 12. Reconciliation

Operational reconciliation is still useful. Stuck `SCHEDULED` rows should be compared with
unpublished outbox rows and Redis membership; unpublished rows need age/error alerts; orphaned
legacy jobs predating V6 may need repair; stale Redis dedupe markers disappear by TTL. The outbox
removes new database-to-Redis loss windows but does not prove old data was healthy.

### 13. Remaining exact-once limitation

> v0.7 prevents duplicate submission and coordinates database ownership, but it does
> not make arbitrary external side effects exactly once. That requires idempotency keys
> or a transactional boundary at the external system.

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

**Update (v0.4): still three sites, still unfixed, and all three now carry a tier.** No fourth
occurrence appeared — `JobController.submit`, `Worker.handleFailure` and `JobController.replay`
are the same three, each now passing a `Priority` alongside the ID. What changed is only that the
Redis half of each write got one field wider. That is worth stating plainly rather than letting
the diff imply progress: routing a stranded write to the correct list does not make it less
stranded. A HIGH job lost between `save` and `enqueue` is lost from the HIGH list specifically,
which if anything is the one you would most want to notice, and there is still nothing that does.

### 2. Priority is immutable after submission, and no version on the roadmap fixes that

There is no endpoint to re-prioritise a queued job, and `Job` has no setter for `priority`. This
is not an omission for want of time; it is a consequence of the design that would have to be
undone deliberately.

The routing decision is committed at enqueue time. The instant `submit` returns, the job's ID is
sitting in one specific list, and Redis lists have no "move this member to another list" that is
also atomic with removing it from the first. Re-prioritising would mean `LREM` from the old tier
then `LPUSH` to the new one, and a crash between those two loses the job outright — a strictly
worse failure than the dual write, because here both writes are to Redis and there is no database
row to reconcile against. `LMOVE`/`RPOPLPUSH` is atomic but moves the *tail* element, not a
nominated one, so it cannot be aimed at a particular job.

**Streams do not fix this either**, which is the part worth being precise about, because it is
tempting to file it under "v0.5 will sort it out." A consumer group reads from one stream. Tiers
under Streams are still separate streams, so the entry is still committed to a specific one at
`XADD` time, and moving it is still a non-atomic delete-and-re-add — worse, because `XDEL` leaves
a tombstone and the re-added entry gets a new ID, so its position in the stream and any
consumer's PEL reference to it both change. The property that makes re-prioritisation hard is not
the list; it is that routing happens at write time in every log- or list-shaped queue.

What would actually fix it is not committing the routing at enqueue time at all: keep one queue
and let the *consumer* choose, which means a structure the consumer can order by priority — a
sorted set scored by `(tier, submitted_at)`. That is the design rejected in Decision 1, and it
costs the blocking read. So "priority is mutable" and "dispatch is a blocking pop" are close to
mutually exclusive, and this version picked the pop.

The mitigation available today is cancel-and-resubmit at the application level, which is a
different job with a different ID and a different position in the queue. That is honest about
what is happening rather than pretending an update occurred.

### 3. The starvation counter is per-worker, and will not hold globally at v0.7

`PriorityStrategy` holds an `AtomicInteger`. Atomic makes the class safe to *share between
threads*; it does not make the guarantee global. With one worker those are the same thing, which
is exactly why this is easy to get wrong later.

At v0.7 there are N workers. If each holds its own `PriorityStrategy`, each counts only its own
dequeues, so the guard fires at most once per `threshold` dequeues *per worker* — with N workers
and a threshold of 10, the lowest tier is served roughly N times per 10N dequeues, which happens
to come out the same in aggregate. The failure is not the rate, it is the correlation: all N
workers can trip their guards at once and all poll in reversed order simultaneously, so a burst
of LOW jobs is served while HIGH work waits, then none for a long stretch. The intended "one in
ten" becomes "N in a row, then 10N of nothing," which is a worse latency distribution for both
tiers than either strict priority or a genuine global counter.

Sharing one bean across workers is not the fix on its own either, because then N threads race on
one counter and the guard order is handed to whichever thread happens to call `nextPollOrder`
next — the poll that trips the guard and the poll that consumes it need not be the same thread.
A real fix needs the counter in Redis (`INCR` on a shared key, checked and reset atomically), at
the cost of a round trip per dequeue on the hot path. Noted here so v0.7 does not discover it by
watching latency graphs.

### 4. The `dead_letters` / `jobs` invariant is enforced by application code, not the database

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

### 5. Orphaned RUNNING jobs after a lost worker connection

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

**Resolved in v0.5, with two caveats.** Streams and `XAUTOCLAIM` were built and the scenario above
is now detectable and recoverable — see *Verified in v0.5* for a worker killed mid-job and its
work picked up by another instance. The caveats:

- Recovery needs *another* live instance. A single-instance deployment recovers only when it is
  restarted, because the sweep runs inside the same process that died.
- Redis measures idle time, not liveness, so the same mechanism reclaims work from a healthy
  worker whose job outlasts `claim-min-idle-ms`. Detection is now possible; distinguishing "slow"
  from "dead" still is not.

### 6. User scheduling adds a fourth instance of limitation 1, and does not fix the first three

`JobController.submit` with a future `scheduledAt` does the same two writes to the same two
systems as §1, with a different Redis command on the far side:

```java
Job job = jobRepository.save(Job.create(type, payload, maxAttempts, priority, scheduledAt));
scheduledJobQueue.schedule(job.getId(), job.getPriority(), scheduledAt);
```

A crash between them leaves a `SCHEDULED` row with no sorted-set member. Nothing will ever promote
it, and — exactly as in §1 — it fails in a shape that looks correct: the client has its `202`,
`GET /api/jobs/{id}` says `SCHEDULED`, and it sits alongside jobs that genuinely are waiting for a
time that will arrive. The only thing distinguishing the two is that one of them has a member in
`distroq:jobs:scheduled` and the other does not, and nothing checks.

It is arguably *worse* to notice than §1, because a `QUEUED` job that never runs is anomalous
within seconds, whereas a `SCHEDULED` job that never runs looks entirely normal until its
requested time passes. That gives reconciliation a much better signal to work with, though:
"`SCHEDULED` rows whose `scheduled_at` is comfortably in the past" is a cheap indexed query —
which is part of why V5 indexes `scheduled_at` at all — and it is a stronger predicate than
anything available for the other three windows.

What scheduling explicitly does **not** do is narrow §1, §2 or the DLQ replay window by any
amount. The promotion Lua script is atomic within Redis, and only within Redis; the job's row was
committed by a different write at a different time, and either side can fail without the other.
The three known windows remain three known windows, and there are now four.

## Design decisions

**Why v0.4 was built on a mechanism v0.5 will delete, on purpose.** v0.5 replaces the Redis list
with Streams. That rewrites how dequeue works, which means most of the code in this version —
`JobQueue.dequeue`, the per-tier keys, the promotion script's destination logic — is being
written in the knowledge that it will be thrown away in one version's time. That deserves an
argument rather than a shrug.

The argument is that the *policy* and the *mechanism* have very different lifespans, and only one
of them is being discarded. The questions v0.4 actually settles are: how many tiers are there and
why that number; what does the API accept and reject; what is the default; is priority mutable;
what happens to the lowest tier under sustained load, and what is the guarantee called. Every one
of those answers is unchanged by Streams. `PriorityStrategy` contains all of the scheduling
policy, touches no Redis, and compiles against nothing that v0.5 replaces — it and its twelve
tests survive the rewrite untouched. The API contract survives. The schema survives. What dies is
the plumbing between them, and the plumbing is the cheapest part.

The alternative was to reorder the roadmap: do Streams first, then priority on top. I rejected it
for two reasons. The first is that it would have meant designing priority *while* also designing
consumer groups, PEL handling and reclaim, and the policy questions above would have been settled
as a side effect of the mechanism work rather than on their own merits — which is precisely how
you end up with three tiers because three streams were convenient. The second is that Streams is
motivated by the orphaned-`RUNNING` problem, which is a *reliability* change; bundling a
*scheduling* feature into it makes one version that changes both what runs and whether it is
recoverable, and if the acceptance run goes wrong there is no way to tell which half broke it.

The honest cost is real: some of the diff in this version is dead code walking, and it will be
deleted having run in production for exactly one version. The trade is that the decisions it
encodes get made once, in isolation, with a test suite that outlives them.

**Discrete tiers, not an integer priority score.** `Priority` is a three-value enum. The obvious
alternative — `int priority`, higher wins — was rejected because a Redis list cannot honour it.

A list has exactly one ordering primitive: insertion position. There is no "insert this member
ahead of everything with a lower score," and `LINSERT` needs a pivot value you would have to find
by scanning. So an arbitrary integer needs one of two things. A **sorted set** scored by priority
gives real ordering, and costs the blocking pop: `BZPOPMIN` exists, but it pops the *lowest score*
across one key, so with a score encoding priority you lose the ability to also order by arrival
time within a tier without packing both into one float and hoping the precision holds. Or
**client-side scanning** — read a window, pick the best, remove it — which is a check-then-act
race and a lost job every time two consumers pick the same member.

Discrete tiers dodge both because "one list per tier" turns priority into key *selection* rather
than in-list ordering, and `BRPOP` already takes multiple keys. Atomicity and blocking both
survive intact, which is the entire reason for the shape of this version.

**What it cannot express, stated plainly:** there is no "priority 47." There is no way to say
this job is slightly more urgent than that one within a tier — inside a tier it is strictly FIFO,
and that is not configurable. There is no dynamic priority ageing, where a job's rank rises with
time waited, because rank is a key name and key names do not change. If any of those were
genuine requirements the design would have to change to the sorted set and give up the blocking
read, and I would want to see the requirement before paying that.

Three tiers specifically, rather than two or five, because two cannot demonstrate the interesting
case — starvation only exists when something can be squeezed from both sides, and with two tiers
"the lowest tier" and "not the highest tier" are the same set, so the guard's definition would be
degenerate and would not generalise. Five would be five key names and no additional idea.

**Strict priority plus a starvation guard, not weighted round-robin.** The naive implementation
drains HIGH before touching NORMAL and NORMAL before LOW. Under a steady stream of HIGH that is
starvation in the literal sense: a LOW job that has waited an hour is no closer to running than
one submitted this second, and nothing in the system will ever change that.

The rejected alternative was **weighted round-robin** — serve HIGH:NORMAL:LOW in a fixed 5:3:1
rotation. It gives smoother throughput sharing and a much easier guarantee to state. What it
costs is the thing priorities are for: in a 5:3:1 rotation a HIGH job submitted at the wrong
point in the cycle waits behind a LOW job even when the queue is otherwise nearly empty, because
the rotation does not care that the system has capacity. Making HIGH sometimes slower than
NORMAL, on a system whose entire purpose is to make HIGH fast, is a bad trade for a fairness
property nobody asked for.

So: strict order every time, except that after `distroq.priority.starvation-threshold`
consecutive dequeues served above the lowest tier, exactly one poll goes out in reversed order.

**What the guard guarantees, and what it does not.** It bounds starvation **in dequeues, not in
time**. With a threshold of 10, a LOW job cannot have more than roughly 10 higher-tier jobs
served ahead of it per cycle. It says nothing at all about how long that takes: ten HIGH jobs that
each run for a minute means the LOW job waits ten minutes, and the guard is working perfectly the
whole time. There is no deadline, no maximum age, no promotion by waiting.

**It is therefore not fairness, and calling it fair would be wrong twice over** — once because
the shares are deliberately unequal, and once because the bound is in the wrong unit to be a
latency guarantee. It is a *weighted* strategy with a floor on how badly the lowest tier can be
squeezed. A real time-based bound would need a deadline scheduler: track each job's age and
promote anything past a threshold, which means reading ages, which means the sorted set again.

**The counter counts bypasses, and over-counts on purpose.** The definition that would be exactly
right is "consecutive dequeues that skipped a lower tier *that had work waiting*." Serving HIGH
a hundred times with LOW empty is not starving anybody, and tripping the guard for it is noise.
Getting that right requires knowing tier depths at the moment of each dequeue, which means either
`LLEN` before every pop — an extra round trip on the hot path, and a stale answer by the time the
pop happens — or letting `PriorityStrategy` read Redis, which destroys the one property that
makes it survive v0.5.

The trade taken instead is to count every dequeue served above the lowest tier and make a
spurious guard **self-cancelling**: when the guard fires and the lowest tier is empty, the same
`BRPOP` falls through to the next non-empty tier in the reversed list, and `recordServed` resets
the counter regardless of which tier answered. So an unnecessary guard costs exactly one poll in
a non-preferred order, once every `threshold` dequeues, and cannot latch. Latching is the failure
that would matter: a guard stuck on would mean NORMAL permanently outranks HIGH, which is worse
than the starvation it was added to prevent. There is a unit test named after precisely that.

Observed in the acceptance run, both branches fired: once during A3 where LOW was empty and the
guard fell through to NORMAL and reset, and once during A4 where it served LOW.

**Multi-key `BRPOP` is the whole trick.** `BRPOP key1 key2 key3 timeout` blocks until any of the
keys has an element and returns from the first non-empty key *in the order given*. That single
primitive supplies strict priority, blocking semantics, and atomicity together:

- Priority ordering is the argument order — no comparison, no scoring, no sorting.
- It blocks, so dispatch latency stays near zero and there is no interval to tune. This is the
  v0.1 `BRPOP`-over-polling argument surviving intact through a feature that looked like it would
  need polling.
- There is **no check-then-pop race**. The obvious implementation is "`LLEN` each tier, find the
  best non-empty one, pop from it" — three round trips and a window in which another consumer (or
  the same consumer next iteration) drains the tier that was non-empty a microsecond ago, so the
  pop either blocks on an empty key or has to be retried. Redis evaluates all the keys itself,
  atomically. The race does not need handling because it does not exist.

The guard uses the same call with the key list reversed, which is why "serve the lowest
**non-empty** tier" needs no depth query: `BRPOP low normal high` *is* that query, fused with the
pop.

**The exact binding, because the obvious one does not exist.** The brief suggested
`redis.opsForList().rightPop(List.of(k1, k2, k3), timeout)`. That overload is not in Spring Data
Redis 3.5.13 — `ListOperations` has `rightPop(K)`, `rightPop(K, long)`,
`rightPop(K, long, TimeUnit)` and `rightPop(K, Duration)`, all single-key. Verified with `javap`
against the resolved jar rather than assumed.

The multi-key form lives one layer down, on the connection API:

```java
List<byte[]> popped = redis.execute((RedisCallback<List<byte[]>>) connection ->
        connection.listCommands().bRPop(timeoutSeconds, keys));
```

`RedisListCommands.bRPop(int timeout, byte[]... keys)`. This is not a workaround or a degraded
fallback — it is the same method `DefaultListOperations.rightPop(K, Duration)` delegates to, with
more than one key. `ListOperations` is a serializer-aware convenience layer, and the only thing
given up is that convenience: keys and the result are handled as raw UTF-8 bytes here. Nothing
about blocking, atomicity or connection handling changes, and Lettuce still routes the blocking
command to a dedicated connection exactly as it did in v0.3.

Two details that matter. `BRPOP`'s timeout is **whole seconds**, and `0` means block forever —
which would never release the loop to notice a shutdown — so the 2-second pop timeout is floored
at 1 rather than allowed to round to 0. And `BRPOP` replies with a two-element array of `[key,
value]`, not a bare value, which is what makes the tier knowable at all; `dequeue` returns a
`Dequeued(UUID, Priority)` record rather than a `UUID` for that reason, because the guard cannot
count what it cannot see.

**The delayed set carries the tier in the member, not in a second lookup.** This was the most
interesting problem in the version. The retry path parks a job in `distroq:jobs:delayed`, one
sorted set with no tier of its own, and when the poller promotes it, it must know which of three
lists to push to. Three options:

*A database read per promoted job.* Correct and obvious, and it breaks the property v0.2 was
built around. `promoteDueJobs` is a Lua script doing range + `ZREM` + `LPUSH` atomically, so no ID
can be promoted twice. A DB lookup cannot happen inside a Lua script, so this means: range in
Redis, read N rows from Postgres, then push — and the atomic unit is gone, replaced by exactly
the three-round-trip sequence with two crash windows that the Lua script was written to
eliminate. It also puts a Postgres query on a path that runs every second forever, at batch size
up to 100.

*A sorted set per tier.* Keeps atomicity, since each key would have its own fixed destination.
Costs three `EVALSHA` calls per poll tick instead of one, forever, mostly finding nothing —
tripling the idle cost already measured as this design's weakest point. And it splits "what is
due next" across three keys, so any future question about the delayed set has to merge them
client-side.

*Encode the tier in the member.* Chosen. The member becomes `HIGH:<uuid>` instead of `<uuid>`,
and the script parses the prefix and picks its destination from the key list it was handed. Range
+ `ZREM` + `LPUSH` stays in one atomic server-side operation, there is no extra read anywhere,
and the tier keys are still passed in as `KEYS` rather than built inside Lua.

**What it costs: denormalisation.** The tier now exists in two places — `jobs.priority` in
Postgres and the member prefix in Redis — and nothing enforces that they agree. That is normally
a bad trade, and the reason it is acceptable here is specific and load-bearing: **priority is
immutable after submission**. A value that never changes after it is written cannot drift. Those
two decisions hold each other up, and if a future version adds re-prioritisation it must revisit
this one in the same change, because at that moment the Redis copy becomes capable of being
stale. That is written down here precisely because the connection between them is not visible
from either piece of code.

A v0.3 leftover — a bare UUID with no prefix, from a job parked in the delayed set before the
upgrade — is handled rather than dropped: no separator means no tier, which routes to the default
tier. The same reading as everywhere else, that a job submitted before priority existed is a
NORMAL job.

**Job IDs left in the v0.3 pending key are drained on startup, not documented away.** The
alternative offered was to require the queue be empty before upgrading. I rejected it: a job in
that list is a committed row in Postgres reading `QUEUED`, and after the upgrade no worker would
ever look at that key again, so it would sit there permanently — the exact invisible-forever
shape described under *Known limitations*, except caused by the release process rather than a
crash. Making that a line in the README puts the cost on whoever skips the line.

`JobQueue.drainLegacyPendingKey` runs `RPOPLPUSH` from the old key onto the NORMAL tier until it
returns nil. Each move is atomic, and FIFO order survives: `RPOPLPUSH` takes the *oldest* entry
(the tail, which is where `BRPOP` reads) and pushes it to the head of the target, so draining N
entries leaves the oldest nearest the tail. It runs in `@PostConstruct` rather than on
`ApplicationReadyEvent` because `Worker` depends on this bean, so its own `@PostConstruct` cannot
start the first `BRPOP` until the drain has returned — no interleaving to reason about. Failures
are logged rather than thrown, because an unreachable Redis has never prevented this application
from starting and quietly changing that would be a surprise unrelated to priority.

Verified rather than assumed: v0.3 was run against the database, seven job IDs were left in
`distroq:jobs:pending` by stopping the app mid-backlog, and v0.4 logged
`Drained 7 job ID(s) left in the v0.3 key distroq:jobs:pending onto distroq:jobs:pending:normal`
and then executed all seven.

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

## What v0.4 changed about the plan

v0.4 did not move the roadmap. It did sharpen what v0.5 is for, and it added one item to v0.5's
scope that was not there before. The six things below are the carry-forward list; most are
written up in full elsewhere in this file and are collected here so the Streams work has one
place to start from.

**1. `BRPOP` is genuinely good at blocking priority dispatch — the routing is what has to go.**
This is the finding I did not expect. Multi-key `BRPOP` gives strict priority, blocking
semantics and atomicity in one call, with no polling and no check-then-pop race. As a *dispatch*
primitive it is not the weak part of this design and Streams will have to work to match it —
`XREADGROUP` reads one stream per call, so multi-stream priority means either N calls (losing the
single atomic decision) or an ordering compromise. What is weak is that priority is committed at
**enqueue** time: the ID is in one specific list the moment `submit` returns, and every
consequence below follows from that one property rather than from the list itself. v0.5 should
be explicit that it is replacing lists for *acknowledgement*, not for *dispatch quality*, and
should measure whether its priority dispatch regresses rather than assuming Streams strictly
dominates.

**2. The tier is now denormalised into Redis, and something has to own that.** Delayed-set
members are `HIGH:<uuid>`, so the promotion script knows the destination without a database read.
The value is duplicated between `jobs.priority` and the Redis member, and nothing enforces
agreement. It is safe today only because priority is immutable — a value that never changes
cannot drift. v0.5 inherits this: if tiers become separate streams, the same encoding decision
recurs at `XADD`, and if v0.5 introduces any path that rewrites a queued entry, the duplication
becomes a live consistency problem rather than a dormant one.

**3. Re-prioritisation is unsafe by construction and Streams does not fix it.** `LREM` +
`LPUSH` is not atomic and a crash between them loses the job outright — worse than the dual
write, because both writes are to Redis and there is no database row to reconcile against.
`LMOVE` is atomic but moves the tail, not a nominated member. Under Streams the entry is still
committed to a specific stream at `XADD`, and moving it is `XDEL` plus re-add, which leaves a
tombstone, changes the entry ID, and invalidates any PEL reference to it. So this is **not** a
v0.5 deliverable and should not be written into the v0.5 brief as one. It needs a design where
routing is not committed at write time at all, which means a consumer-ordered structure, which
costs the blocking read. Full argument under *Known limitations* §2.

**4. The starvation counter is per-worker and silently stops meaning anything at v0.7.** With
one worker, "atomic" and "global" are the same thing, which is exactly why this will be easy to
miss. With N workers the aggregate rate happens to come out right but the *correlation* is
wrong: all N can trip simultaneously and poll reversed at once, turning "one in ten" into "N in a
row, then 10N of nothing". A shared bean does not fix it either, because the poll that trips the
guard and the poll that consumes it need not be the same thread. A real fix puts the counter in
Redis at the cost of a round trip per dequeue. Full argument under *Known limitations* §3.

**5. Three dual-write sites, still unfixed, and now three versions old.** v0.4 added no fourth
site — submit, retry scheduling and replay are the same three — and made each one field wider by
passing a tier. That is not progress and the diff should not be allowed to imply otherwise:
routing a stranded write to the correct list does not make it less stranded. The prediction in
the v0.2 note was that the third occurrence would be the argument for the outbox. It was, and it
still has not been acted on. What has changed since is only detectability, which has got
monotonically worse each version.

**6. Retry promotion and DLQ replay both hand work off with no acknowledgement — new to this
list.** The orphaned-`RUNNING` write-up under §5 frames the problem as a *dispatch* one: `BRPOP`
is a destructive read, so a worker that dies mid-job leaves a row that nothing can detect. What
v0.4 makes visible is that the same gap exists on the two paths that put work *back* into the
queue. `promoteDueJobs` does `ZREM` then `LPUSH` atomically and is finished with the job the
instant the script returns — there is no record that the promotion was ever consumed, so a
worker that dies between the `LPUSH` and the `markRunning` commit loses the retry with no trace,
and it looks identical to a retry that simply has not come up yet. Replay is worse in the same
way it was worse in v0.3: the DLQ row already reads `replayed = true`, so the job has left the
one view built to make failures visible.

Streams close this properly rather than incidentally, and that is the point worth carrying: the
PEL is a record of "handed out and not confirmed" that applies to *every* entry regardless of how
it got into the stream, so promotion and replay stop being special cases that each need their own
reconciliation story. Three delivery paths converge on one recovery mechanism. That is a better
argument for v0.5 than the orphaned-`RUNNING` case alone, which is the one the roadmap has been
carrying since v0.1.

The honest counterweight, unchanged: this buys **at-least-once**, not exactly-once. A reclaimed
retry may have already partially executed. Idempotency keys at v0.7 are what make that safe, and
nothing in v0.5 should be described as if redelivery were free.

## What v0.5 changed about the plan

### 1. Why Lists were replaced

A Redis list gives destructive pop and nothing else. `BRPOP` is atomic, ordered and blocking —
everything v0.4 needed to *dispatch* work, and nothing it needed to *account* for it. The moment
an ID leaves the list there is no record anywhere in Redis that it was ever handed to anybody:
no owner, no timestamp, no delivery count, no way to ask "what did I give out that has not come
back". That is one bit of missing state, and it is the bit every recovery story depends on.

The consequence had been sitting in §5 of *Known limitations* since v0.1 and had grown, by v0.4,
into three separate versions of the same hole: a worker dying mid-job (nothing to detect), a
promoted retry lost between `LPUSH` and `markRunning` (indistinguishable from a retry that has
not come up yet), and a replayed job lost between the DLQ update and the enqueue (invisible from
both directions, because the DLQ row already reads `replayed = true`).

None of those are fixable on top of a list. Every proposed workaround — a separate "in flight"
set written after the pop, a lease key with a TTL, a heartbeat table in PostgreSQL — is a second
write that is not atomic with the pop, which means it has its own crash window and needs its own
reconciliation. The missing state has to live where the pop happens, or it does not help.

### 2. What Streams provide

Streams supply exactly the missing bit, and a few useful things around it:

- **Entry IDs.** Every entry has a durable, monotonic, server-assigned identity, so a delivery can
  be referred to after the fact — in a log line, in an acknowledgement, in a reclaim.
- **Consumer groups.** Redis tracks which consumer was given which entry. Ownership is a property
  of the data, not of a convention the client is trusted to maintain.
- **Pending Entries Lists.** One per stream per group: everything delivered and not acknowledged,
  with its owner, its idle time and its delivery count. This is the state a list cannot hold.
- **`XACK`.** Completion becomes an explicit act with a place to put it, rather than something
  implied by the absence of a record.
- **Inspection.** `XPENDING`, `XINFO GROUPS` and `XINFO CONSUMERS` answer "who is holding what,
  and for how long" without any application-level bookkeeping.
- **`XAUTOCLAIM`.** Transferring ownership of stale entries is one atomic command, not a scan and
  a race.

The important consequence is the one predicted in §6 above: because the PEL applies to every entry
regardless of how it got into the stream, submit, retry promotion and DLQ replay stop being three
delivery paths that each need their own reconciliation story. They converge on one recovery
mechanism, which is a better argument for Streams than the orphaned-`RUNNING` case alone.

### 3. At-least-once semantics

The ordering is the guarantee. Every path writes the outcome to PostgreSQL and calls `XACK`
afterwards:

```
execute -> close the attempt row -> set the terminal (or RETRYING) job state -> XACK
```

Reversing those two would be faster to write and would produce at-most-once delivery with silent
loss, which is what v0.4 had. In the chosen order the failure window is: the job has run, the
database says so, and the process dies before the acknowledgement. Redis still holds the entry as
pending against a consumer that no longer exists, and another instance reclaims it after
`claim-min-idle-ms`.

That redelivery is not a defect being tolerated — it is the mechanism working. What it costs is
that the worker now has to answer a question v0.4 never had to: *what does this entry mean, given
what the database already says about the job?* The decision table in the README is that answer,
and it exists because at-least-once delivery makes duplicate deliveries a normal event rather than
an anomaly.

There is a second source of duplicates that is easy to overlook and worth stating plainly: Redis
measures idle time, not liveness. An entry held by a completely healthy worker running a job for
longer than `claim-min-idle-ms` is indistinguishable from one held by a corpse, and will be
reclaimed and executed a second time. The threshold is therefore not just a recovery knob; it is
an assertion about maximum job duration, and getting it wrong manufactures duplicates on a system
where nothing has failed. The one case that *can* be distinguished is a process reclaiming from
itself, and the worker tracks its own in-flight entries to prevent that.

### 4. Exactly-once is not achieved, and cannot be here

`XACK` is a statement about a message. It says: this consumer group has accounted for this entry,
stop offering it. It says nothing whatsoever about what the consumer *did*.

Consider a job that charges a card. The worker charges the card, commits `SUCCEEDED`, and is
killed before the `XACK`. The entry is still pending. Another worker reclaims it. It reads the job
from PostgreSQL, sees `SUCCEEDED`, and acknowledges without re-executing — so in *this* case the
duplicate is caught, because the database happens to record the outcome.

Now move the crash one line earlier: the worker charges the card and dies before committing
anything. The database still says `RUNNING`. The reclaiming worker follows the documented rule,
marks the abandoned attempt, and **charges the card again**. Nothing in Redis could have prevented
this. The broker cannot know that the side effect happened, because the side effect did not happen
in the broker.

The general form: exactly-once execution across a broker and an external effect requires the
effect and the record of the effect to commit atomically. That is a property of the *job*, not of
the queue — a deduplication key checked and written in the same transaction as the work. Which is
why v0.7 is idempotency keys and not, say, a cleverer acknowledgement scheme.

So: **at-least-once delivery** (v0.5, provided), **idempotent processing** (v0.7, a property of
the job), and only their combination approximates **exactly-once execution** as far as it can be
approximated at all. Three distinct terms, and the repository keeps them distinct on purpose.

### 5. The priority trade-off

v0.4 had one property v0.5 cannot reproduce: `BRPOP key1 key2 key3` chooses the highest-priority
non-empty key *inside Redis*, atomically, while blocking. One command, no window between deciding
which tier to read and reading it.

Streams have no equivalent. `XREADGROUP` accepts several streams, but it returns whatever is
available across all of them — it has no notion that one is preferable — and it wakes on the first
arrival anywhere. There is no "read these, preferring this one" command, and no `BLPOP`-style
first-non-empty semantics.

Two designs were available:

**One stream for everything.** Acknowledgements would work; priority would not. A stream is
append-ordered and a consumer group hands out entries in that order, so a HIGH job submitted after
a thousand LOW ones is the thousand-and-first entry. Restoring priority would mean a second
scheduling structure in front of the stream, at which point the stream is not the queue any more.

**One stream per tier**, which is what was built. It preserves the v0.4 policy model exactly — the
tiers are separate keys, `PriorityStrategy` is untouched, the starvation guard still works — at
the cost of moving the ordering decision out of Redis and into the worker's read strategy.

The strategy is: non-blocking `XREADGROUP` per tier in the order `PriorityStrategy` asks for,
stopping at the first that answers; only if all three are empty, one bounded blocking read across
all three. That costs up to four round trips on an idle queue where v0.4 needed one, and it buys
back strict order whenever anything is queued, because a tier is skipped only after Redis has said
it is empty.

**What is genuinely lost.** During the final blocking read — and only then — the first entry to
arrive wakes the call regardless of tier. If a LOW job and a HIGH job arrive within the same
millisecond on a completely idle queue, the LOW one may be served first. v0.4's `BRPOP` would have
preferred HIGH. The window is one delivery, immediately after an idle period, and it closes as
soon as there is any backlog at all. Measured with eleven NORMAL jobs already queued, a
subsequently submitted HIGH job ran second overall, delayed only by the NORMAL job that was
already executing — which is non-preemption, not a queue-ordering failure, and was true in v0.4
too.

The alternative worth naming and rejecting: a depth check followed by a read. `XLEN` on each
stream, then read the deepest non-empty one. That reintroduces exactly the race the design brief
forbids — the depth is stale the instant it is returned — and costs more round trips than the
sequential reads do. A read either returns an entry or it does not; that is the only reliable
signal, so it is the only one used.

One more constraint that shaped the code: an entry returned by `XREADGROUP` is already in the
consumer's PEL. Reading three streams and processing one would strand the other two until the idle
timeout. So the consumer returns *everything* a read produced, sorted into tier order, and the
worker processes all of it. "Read one, ignore the rest" is not available once delivery is tracked.

### 6. Reclaiming, and what `ABANDONED` does and does not mean

v0.4 wrote a `job_attempts` row when an attempt *finished*. A worker that vanished mid-execution
therefore left no attempt row at all — the job sat at `RUNNING` with `attemptCount` incremented
and nothing recording who had been running it or when. The history was silent about precisely the
case it would be most useful for.

v0.5 opens the row first, as `IN_PROGRESS`, and closes it as `SUCCESS`, `FAILURE` or `ABANDONED`.
`ABANDONED` is written by whichever worker reclaims the delivery, and it names both the entry ID
and the reclaiming consumer:

```
Delivery 1788795125064-0 reclaimed by worker-two-b1230ceb;
worker-one-12191346 never reported an outcome
```

The wording is deliberate. `ABANDONED` means: **the worker did not report a success or a failure
before the delivery was reclaimed.** It does not mean the job did nothing. It does not mean the
job did something. Nobody knows, and the row is careful not to imply otherwise — that unknowability
is the whole reason idempotency keys are a separate version rather than a paragraph in this one.

The reclaim rule itself: whoever holds the entry owns the current attempt. Any open attempt is
closed as `ABANDONED`, a new attempt is opened, and `attemptCount` **advances**. It would have been
tempting to leave `attemptCount` alone on the grounds that the abandoned attempt "did not really
happen" — but it did happen, possibly all the way to a side effect, and pretending otherwise would
give a job that repeatedly kills its workers an unlimited retry budget. Advancing means the
attempt sequence in `job_attempts` stays one coherent ordered history, and a job that cannot be
completed still eventually dead-letters.

No DDL was required for either new outcome value. That is the third time V2's decision to drop the
Hibernate-generated CHECK constraint has paid for itself — `jobs.status` in v0.3, `jobs.priority`
in v0.4, `job_attempts.outcome` now — and the reasoning was checked against the live database
rather than assumed, because "the migration may be unnecessary" is exactly the kind of claim that
is embarrassing to get wrong.

### 7. Delayed retry promotion

The promotion script kept its shape and changed its verb: `ZRANGEBYSCORE` + `ZREM` + `LPUSH`
became `ZRANGEBYSCORE` + `ZREM` + `XADD`, with the four standard entry fields and `source=RETRY`.
The delayed-set member format is unchanged — still `<TIER>:<uuid>` — because it is still the only
thing that lets the script pick a destination without a PostgreSQL lookup per promoted job.

Two details that had to be checked rather than assumed:

- **`XADD *` inside a Lua script.** Non-deterministic commands were a genuine problem under the
  old command-replication model. Redis 5 onwards replicates scripts by their *effects*, so the
  replica receives the ID the primary generated. Safe on Redis 7, which is what this runs on.
- **Dynamic key names.** A Lua script may not construct key names it was not given. All three
  stream keys are therefore passed in `KEYS`, with their tier names in `ARGV`, and the script maps
  tier to key from that — it never builds `distroq:jobs:stream:` + anything.

The atomicity claim, stated precisely: **within Redis**, a due retry moves from the sorted set into
exactly one stream, exactly once, or not at all. There is no window in which it exists in both or
neither.

That is not a system-level guarantee. The job's `RETRYING` row was committed to PostgreSQL by a
different write at a different time, and the two can still disagree — a `RETRYING` row whose
delayed member never made it, or a stream entry for a job the database never marked. Redis being
atomic with itself does not make Redis atomic with PostgreSQL, and §10 below is the same point in
its general form.

One behaviour this forced, and it was found by testing rather than by reading: the promoted entry
arrives while the job is still `RETRYING`. A naive "a `RETRYING` job's delivery is stale, just
acknowledge it" rule — which is what the design brief's duplicate-delivery table suggests in
isolation — acknowledges the retry without running it, and the job never completes. The
discriminator is the entry itself: `source=RETRY`, stamped at or after `nextAttemptAt`, is the
scheduled retry and must run; anything else on a `RETRYING` job is a superseded delivery and must
not. Two different entries, the same job status, opposite correct actions.

### 8. Consumer identity

Ownership in a consumer group is keyed by consumer *name*, and Redis will happily let two
processes use the same one. If they did, each would find the other's in-flight entries in its own
PEL, `XPENDING` would attribute work to a consumer that is not doing it, and `XAUTOCLAIM` would
reclaim live work from a healthy peer while believing it was recovering from a crash. The recovery
model would not fail loudly; it would produce confident, wrong answers.

So the name is generated per process — `<prefix>-<8 random hex>` — and the prefix is configurable
only for legibility when running two instances side by side. A fixed `worker-1` is not offered as
an option anywhere.

The same string is used as the `workerId` in `job_attempts`. One identity, two systems: a pending
entry in Redis and an attempt row in PostgreSQL can be tied together by string equality, which is
what makes the crash-recovery evidence readable at all.

### 9. Legacy migration, and its remaining crash window

Nothing reads the v0.4 lists any more, so an ID left on one is a job sitting in PostgreSQL as
`QUEUED` that no worker will ever pick up. The alternative to migrating was to document "drain the
queue before upgrading", which converts silently stranded work into a documentation problem — the
same argument v0.4 made when it drained the v0.3 key, and it holds here too.

The migration is **not transactional and is not claimed to be**. A pop and an `XADD` are two
commands; the process can die between them. What the implementation does is choose which side of
that window to fail on:

```
LMOVE  <legacy list>  <parking list>  RIGHT LEFT
XADD   <tier stream>  ...  source=LEGACY_MIGRATION
LREM   <parking list> 1 <id>
```

The ID is on exactly one list at every instant. A crash after `LMOVE` and before `XADD` leaves it
parked, and the next startup republishes it. A crash after `XADD` and before `LREM` produces a
**duplicate stream entry** on the next startup — which the duplicate-delivery rules already handle,
because handling duplicates is now a normal part of the system rather than a special case. A naive
`RPOP` then `XADD` would have failed on the other side, losing the job outright. Duplicates are
recoverable; losses are not.

Parking lists are per tier, so an interrupted migration does not lose the tier of what it was
carrying.

### 10. The dual write is untouched

All three occurrences are still there, unchanged:

1. `POST /api/jobs` — `jobRepository.save()` then `XADD`.
2. Retry scheduling — `jobRepository.save()` then `ZADD`.
3. DLQ replay — job and `dead_letters` rows saved, then `XADD`.

It is worth being explicit about *why* Streams do not help, because "we added acknowledgements" is
close enough to "we added reliability" to be mistaken for it. Acknowledgements make delivery
reliable **once an entry is in the stream**. Every one of the three windows above is the gap
between committing to PostgreSQL and getting the entry into the stream at all. There is no entry
yet, so there is no pending entry, so there is nothing for `XAUTOCLAIM` to find. The PEL cannot
recover a message that was never written.

What v0.5 did change is the *shape* of the argument. The v0.4 note predicted the third occurrence
would be the argument for an outbox; it was, and it still has not been acted on. What has changed
is that recovery infrastructure now exists — a periodic sweep, running per instance, that already
reconciles Redis state against PostgreSQL for one class of problem. A reconciliation pass over
stale `QUEUED` and `RETRYING` rows would sit naturally alongside `PendingEntryRecovery` rather than
being a new subsystem. That is an argument for doing it, not evidence that it has been done.

Left for v0.7 or a dedicated reliability version: a transactional outbox, or reconciliation, or
both. Nothing in v0.5 should be read as narrowing these three windows by so much as a millisecond.

### 11. The self-reclaim bug, found by running it

Worth recording because it is not obvious from the design and only appeared under test. The first
version of `PendingEntryRecovery` reclaimed every entry idle beyond the threshold, including its
own. A ten-second job on a ten-second threshold therefore had its entry reclaimed by the same
process that was executing it, which would have re-run the job in place — a duplicate manufactured
entirely by the recovery mechanism, with no failure anywhere.

The fix is small: the worker tracks the entries it is currently executing, and the sweep skips
those. It is the one liveness question that *can* be answered locally, and answering it costs a
set lookup. Across processes the question remains unanswerable, which is what §3 says about idle
time not being death.

## What v0.6 changed about the plan

The v0.5 README predicted that user scheduling would be "a small addition to `scheduleAt`". The
*mechanism* was indeed already there — the promotion Lua script is reused verbatim, with a
different key and a different label — but almost none of the actual work turned out to be the
mechanism. It was the status, the timestamp, and what a worker should do with an entry that
arrives before its time.

### 1. Why `SCHEDULED` is separate from `RETRYING`

They are both "waiting", and that is the only thing they have in common.

`RETRYING` means *an attempt failed and another is pending*. It carries an `errorMessage`, an
`attemptCount` above zero, a `nextAttemptAt` computed by the backoff policy, and at least one row
in `job_attempts`. It is a failure signal, and it is read as one: every "how much is failing right
now" question in this system goes through it.

`SCHEDULED` means *the submitter asked for a time that has not arrived*. It carries no error, zero
attempts, no attempt rows, and a timestamp that came from the request rather than from a policy.
Nothing has gone wrong.

Reusing `RETRYING` would have made "jobs currently failing" include jobs that have never run.
Reusing `QUEUED` would have been the other mistake: `queueDepth` is read as *backlog*, and a job
due next Tuesday is not backlog — no worker could run it if every worker in the fleet were idle.
An autoscaler watching `queueDepth` would start capacity for work that is not due.

The transition is also one-way, which is what makes the status honest. A job leaves `SCHEDULED`
exactly once, on its first promotion, and cannot return: `markQueuedFromSchedule()` throws for any
other status, so no duplicate delivery, no retry and no replay can walk a job that has already run
back into the scheduling path.

### 2. Why user scheduling uses a separate Sorted Set

`distroq:jobs:delayed` already existed, already held `<TIER>:<uuid>` members scored by epoch
millis, and was already swept by an atomic promotion script. Putting scheduled jobs in it was one
line. It was still the wrong answer.

- **The two mean different things.** One holds automatic retry state produced by the backoff
  policy; the other holds a user's stated intent. A key that holds both can answer neither
  question.
- **The metrics would have merged.** `delayedDepth` is "how many retries are backing off" — a
  health signal. Folding in user-scheduled work would make it fire because someone scheduled a
  report for midnight.
- **The write semantics differ, and they conflict.** The retry set wants plain `ZADD`: a newly
  computed backoff *should* overwrite the old score, and that idempotency is what stops a
  redelivered entry producing a second competing retry. The scheduled set wants `ZADD NX`:
  rescheduling is out of scope, so the only thing that can produce a second `ZADD` is a repeat of
  an already-accepted request, and silently moving that job's execution time is worse than doing
  nothing. One key cannot have both.
- **They should evolve independently.** Retry promotion and scheduled promotion happen to be
  identical today. Retry promotion will change when leases arrive; scheduled promotion will change
  if recurring jobs ever do. Coupling them now means one cannot move without the other.

What *is* shared is the code: `DueSetPromoter` holds the Lua once and both callers pass their own
key and `source`. Sharing the implementation while separating the data is the right split — the
alternative was thirty lines of duplicated Lua, and the worst outcome for duplicated Lua is a copy
that has quietly drifted.

### 3. Why timestamps are `Instant`

There were four candidate readings of "run this at 15:30" and three of them are ambiguous:
`LocalDateTime` (a calendar position with no idea which calendar), the JVM default zone (a
property of whichever machine started the process), and the PostgreSQL server zone (a fourth
answer again). None of the three is visible in the response, so a system that picked one would
give different answers on different hosts and look identical from outside.

Requiring an explicit offset moves the ambiguity to the request, where it can be *rejected*.
`DateTimeFormatter.ISO_OFFSET_DATE_TIME` is the entire validation rule: it accepts `Z` and any
numeric offset and fails on a value carrying neither, which is why `2026-09-07T15:30:00` is a 400
rather than a silent reading in the server's zone. The offset is then applied and discarded —
`17:30:00+02:00` and `15:30:00Z` become the same `Instant`, the same `timestamptz`, the same
sorted-set score and the same response.

This is checked rather than assumed. The verification run below has a JVM default of `India
Standard Time` against a PostgreSQL server set to `UTC` — two different wrong answers available —
and all three spellings of the same instant produce byte-identical stored values.

The DTO field is a `String` for the same reason `priority` is, and a stronger one. Bound as an
`Instant`, Jackson accepts `2026-09-07T15:30:00Z` and *rejects* `2026-09-07T17:30:00+02:00` — a
perfectly valid ISO-8601 instant — with a deserialization error the controller never sees and the
caller cannot act on.

Blank is rejected rather than treated as absent. Omitting the field already means "now"; there is
no second thing an empty string could mean, and accepting it would create two spellings of one
request for no benefit.

### 4. Why past timestamps execute immediately

`scheduledAt <= now` is not an error. It is a request that is already due — a client computing a
target time, a retried HTTP call, a queue of submissions that took longer to drain than expected.
Rejecting it would make every caller do clock arithmetic before submitting.

The alternative implementation — put it in the sorted set anyway and let the next sweep promote it
— is simpler by one branch and costs up to a full poll interval of latency for nothing. So a past
or equal timestamp persists as `QUEUED` and goes straight to its stream with `source=SUBMIT`,
never touching the scheduled set.

The requested value is still stored. It is what the submitter asked for, and a job whose
`scheduledAt` is an hour before its `createdAt` is genuinely useful information about a client
that is behind.

### 5. Scheduling precision

The requested time is a floor, not a guarantee. Actual start time is pushed out by:

- **the poll interval** — the dominant term, and the only one configurable here. A job is promoted
  on the first tick at or after its due time, so the mean contribution is half of
  `distroq.scheduling.poll-interval-ms` and the worst case is all of it
- Redis latency for `ZRANGEBYSCORE` + `ZREM` + `XADD`
- stream delivery — the worker's next `XREADGROUP` has to come round, and on an idle queue that is
  a blocking read with its own timeout
- worker availability — one worker thread per process
- higher-priority work — a HIGH backlog is served before a promoted LOW job, by design

Measured with the default 1s interval: 518 ms from requested time to `startedAt` on an idle queue,
624 ms for a job promoted right after a restart. Both well inside one poll interval, and neither
is a number to build a real-time system on.

Lowering the interval is cheap — one `ZRANGEBYSCORE` per tick against a set that is usually empty
— but it only shrinks the first term. The rest are properties of the delivery path.

### 6. Restart durability

The whole reason this is a Redis sorted set and a poller rather than a `ScheduledExecutorService`.
An in-memory schedule dies with the process; a sorted-set member does not. There is no
`Thread.sleep` anywhere on this path and nothing about a job's due time is held in the JVM, so
"restart the app" and "the app crashed" are the same event as far as a scheduled job is concerned.

Verified with a hard kill rather than a graceful stop, which matters: a graceful stop would let a
shutdown hook do something, and the claim is that nothing needs to.

What this does not survive is Redis losing the key. The durability is exactly Redis' durability,
and the `docker-compose.yml` here configures none.

### 7. Promotion atomicity

The Lua script removes the member and writes the stream entry in one server-side round trip, so no
member can be promoted twice even with several instances sweeping concurrently. The `ZREM` return
value is what decides ownership: only the caller whose `ZREM` returned 1 writes the `XADD`. That
is also why the promoter needs no leader election — running it in every instance is safe, it just
costs each of them a `ZRANGEBYSCORE` per tick.

Two constraints on the script, both deliberate:

- **Every Redis key arrives through `KEYS`.** The script never concatenates a key name. That keeps
  it correct under Redis Cluster and, more usefully here, means the tier-to-key mapping has
  exactly one definition (`StreamKeys`) instead of one in Java and one in Lua that can drift.
- **It never touches PostgreSQL, and cannot.** So the job's row still says `SCHEDULED` at the
  moment its entry is already on a stream. That intermediate state is normal, not a failure, and
  the worker resolves it.

`XADD *` inside a script is safe on Redis 5+: scripts replicate by effects, so a replica receives
the ID the primary generated.

### 8. The remaining dual-write problem

Scheduling does not fix the PostgreSQL-to-Redis gap. It adds a fourth instance of it:

```text
save job as SCHEDULED
then ZADD scheduled member
```

A crash between those leaves a stuck `SCHEDULED` row that nothing will ever promote. See *Known
limitations* §6. The three windows from v0.1–v0.3 are untouched, and nothing in v0.6 should be
read as narrowing them.

### 9. The scheduled retry distinction

`scheduledAt` and `nextAttemptAt` are two fields because they answer two questions, and
overloading one would destroy the other's answer the first time a scheduled job failed.

`scheduledAt` is historical metadata: written once at submission, never cleared, never modified by
promotion, retry, dead-lettering or replay. `nextAttemptAt` is working state: written by the
backoff policy, cleared by `markRunning()`, meaningless outside `RETRYING`.

A scheduled job that has failed once carries both, and both are correct. Merging them would mean
that after the first failure there is no longer any record of when the job was asked to run — and
"why did this run at 03:12 when I asked for 15:30" is exactly the question you want answerable
after an incident.

### 10. Replay semantics

Replay is immediate, even for a job whose original `scheduledAt` was in the future.

`scheduledAt` describes the original execution request. Replaying a dead-lettered job is a new
operator action, taken now, by someone looking at a failure — and re-honouring the original
timestamp would do one of two useless things: run immediately anyway, because the time has passed,
or strand a deliberate human intervention until a time nobody asked about. The original value
stays on the row as history and is visible in both the job detail and the DLQ responses, so the
operator can see that this job was meant to run at a particular time and will not this time.

Replay therefore creates no scheduled sorted-set member. It writes a `source=REPLAY` entry onto
the job's own priority stream, unchanged from v0.5.

### 11. No rescheduling

`scheduledAt` and `priority` are both immutable after submission, for the same reason and with the
same honesty about it: changing either would mean updating a PostgreSQL row and a Redis structure
that are not written atomically together, in a system that already documents four unrepaired
instances of exactly that failure.

Rescheduling is not `ZADD XX` plus an `UPDATE`. It needs an answer to what happens when the row
moves and the score does not, when a job is rescheduled while its promotion script is mid-flight,
and when a job is rescheduled after it has already been promoted but before a worker has picked it
up. Those are a consistency design, not a feature, and adding them casually would put a fifth
window into a version whose main claim is that it added no new categories of problem.

## Planned for v0.5.1 — reliability

Five things v0.5 either left untouched or newly exposed. None is a v0.6 feature; all of them are
about making what already exists trustworthy, which is why they belong in a point release rather
than being folded into scheduled jobs. Nothing here has been implemented — this is the shape of
the work and the decisions that have to be made first.

### 1. Reconciliation for the three dual writes

Unchanged since v0.1, v0.2 and v0.3 respectively: submit, retry scheduling and DLQ replay each
commit to PostgreSQL and then write to Redis, and a crash between the two strands the job. Streams
did not touch this — every one of those windows is the gap *before* an entry reaches a stream, and
the Pending Entries List cannot recover a message that was never written.

What v0.5 did change is that a home for the fix now exists. `PendingEntryRecovery` already runs a
periodic sweep that reconciles Redis state against PostgreSQL for one class of problem, so a
second sweep over stale rows is an addition rather than a new subsystem.

The shape: find `QUEUED` rows older than some threshold with no corresponding stream entry, and
`RETRYING` rows whose `nextAttemptAt` is comfortably past with no delayed-set member, and
re-enqueue them. Two questions to settle before writing any of it:

- **How is "no corresponding stream entry" answered cheaply?** Scanning three streams per sweep
  does not scale, and a job ID is not indexed by Redis. The likely answer is that reconciliation
  does not check Redis at all — it re-enqueues on age alone and accepts producing a duplicate,
  because a duplicate is something the redelivery rules already handle and a stranded job is not.
  That is the same trade the legacy migration made, and it should be made explicitly rather than
  discovered.
- **Is an outbox the better answer?** A transactional outbox removes the window instead of
  cleaning up after it, at the cost of a table, a relay, and its own ordering questions.
  Reconciliation is cheaper to build and leaves the window open. This is the decision, and it has
  been deferred three versions running.

### 2. Safe stream retention

v0.5 never trims, because a trimmed entry that is still pending cannot be inspected or reclaimed.
`XLEN` therefore rises forever on a perfectly healthy system, which is a memory leak with a slow
fuse.

The safe bound is the **minimum pending entry ID across all consumers in the group** — everything
strictly older than that has been acknowledged by everybody and can go. `XTRIM MINID` takes
exactly that argument. The work is computing it correctly and conservatively:

- `XPENDING <stream> <group>` gives the group's minimum pending ID, but only for one group. With a
  single group this is the whole answer; it stops being so the moment a second group is added.
- A margin behind that ID is worth keeping deliberately, so `XRANGE` during an incident still
  shows recently completed work rather than a stream that ends at "now".
- When nothing is pending at all, the minimum is undefined and the naive reading is "trim
  everything". Getting that case wrong destroys the history on an idle system, which is precisely
  when nobody is watching.

Open question: should trimming run on a schedule, or on a threshold, or only on an explicit
operator action? A background trim that is subtly wrong is worse than manual pruning.

### 3. Heartbeats or lease extension

The reclaim rule is time-based and therefore blind: Redis knows when an entry was last delivered,
not whether the process holding it is alive. §3 and §11 of the v0.5 notes cover the consequences.

Two candidate mechanisms:

- **Lease extension.** A worker executing a long job periodically re-claims its own entry
  (`XCLAIM` with a zero minimum idle time, or a no-op `XAUTOCLAIM` against itself) to reset the
  idle clock. Cheap, no new state, and it fails in the right direction — a dead worker stops
  extending and the entry ages out normally. The catch is that a *hung* worker may keep extending
  forever, so the lease has to be renewed from the same thread that is doing the work, not from a
  timer that survives it.
- **Heartbeats in PostgreSQL.** A `last_seen` column on the in-progress attempt row, written
  periodically. More expressive — it makes "which workers are alive" a query — but it is another
  write on the hot path and another thing that can disagree with Redis.

Lease extension looks correct and small; heartbeats look useful for the dashboard. They are not
mutually exclusive, and the decision is whether the dashboard's need justifies the second one.

### 4. Configuration for legitimately long jobs

Today `claim-min-idle-ms` is one global number that has to exceed the longest job in the system.
That forces the whole deployment to tolerate the worst case: a fleet where one job type takes ten
minutes cannot detect a dead worker in under ten minutes.

Options, in increasing order of cost:

- **Per-tier thresholds.** Cheap, since the streams are already separate — but priority is not a
  proxy for duration, so this solves the wrong axis.
- **Per-job-type thresholds.** Correct axis, but the reclaim sweep works from stream entries and
  would need the job type in the entry to apply it. That is a fifth field, and it is the first
  piece of *job data* to be duplicated into Redis rather than routing metadata. Worth doing
  deliberately or not at all.
- **Lease extension (item 3) instead.** If a running worker keeps its own entry fresh, the global
  threshold only has to exceed the *heartbeat interval*, not the job duration — which removes the
  need for this configuration almost entirely.

That last point is the important one: item 3 may make item 4 unnecessary, and item 4 should not be
built first.

### 5. What the dashboard should show (v0.8 input, decided here)

v0.5 produced three counts that are easy to conflate, and a dashboard is exactly where conflating
them causes a bad decision at 3am. The position taken in the metrics endpoint should carry through
to the UI:

- **Stream lag** — "work waiting". This is the number that belongs on a queue-depth chart, and the
  only one of the three that should ever drive an alert on backlog.
- **Pending entries** — "handed out, not confirmed". A steady small number is healthy. A number
  that does not fall when the queue drains is the signal that something died, and it deserves its
  own panel rather than being summed into anything.
- **Abandoned attempts** — a PostgreSQL count, not a Redis one, and a *rate* rather than a level.
  It answers "how often is work being reclaimed", which is the closest thing the system has to a
  worker-mortality metric.

`XLEN` should appear on the dashboard only as diagnostic detail, clearly labelled as total entries
ever written, or not at all. Presenting it next to lag under a shared heading is how it gets read
as a backlog.

One thing that does not exist yet and probably should: **duplicate executions**, counted where the
worker detects a stale or reclaimed delivery. It is the number that says how much at-least-once is
actually costing, and it is the number that will justify — or fail to justify — the idempotency
work in v0.7.

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

## Verified in v0.4

**Both database paths converge again, V4 included.** V4 was applied to a database built by
running v0.3 (21 jobs, schema at V3), then the volume was destroyed and v0.4 started against an
empty one where V1–V4 all executed in order. `\d jobs` from the two databases diffs to **zero
lines**, column order, the `'NORMAL'::character varying` default and index names included. Row
count was 21 before the migration and 21 after, with all 21 rows reading `NORMAL` — the DB-level
default backfilled them in one statement without a rewrite.

**Adding a third enum-backed column cost zero constraint DDL, for the third time.** V4 is one
`ALTER TABLE ... ADD COLUMN` and one `CREATE INDEX`. There is no CHECK on `priority`, which means
adding a fourth tier later is a one-line enum edit and no migration at all. This is now the third
consecutive version where the v0.2.1 decision has paid rather than cost, and the exposure it
bought — nothing at the database level stops a bad value being written by something that is not
this application — is unchanged and still accepted.

**Strict priority was measured, not asserted.** With twelve `sleep`/800ms NORMAL jobs already
queued, a HIGH job submitted 500ms *later* started 235ms after submission and finished while six
NORMAL jobs were still `QUEUED`. Its `started_at` (`17:16:18.943`) precedes the `started_at` of
eleven of the twelve NORMAL jobs created before it. The twelfth is the interesting one: it
started at `17:16:18.109`, before the HIGH job was submitted at all. **There is no preemption** —
priority decides what runs next, never what stops running — and that is visible in the data
rather than only in the code.

**The guard fires, and both of its branches were observed.** In A3 it tripped after ten NORMAL
dequeues, polled `[LOW, NORMAL, HIGH]`, found LOW empty, was served by NORMAL and reset — the
self-cancelling path. In A4 it tripped and served LOW. Under sustained load (25 HIGH, 3 LOW, LOW
enqueued first) the LOW jobs ran at positions 11, 22 and 28 of 28: two of the three ran *before*
the HIGH backlog was exhausted, at exactly the threshold spacing, and the third ran last only
because HIGH had run out by then.

**The tier survives retry and replay, proved at the Redis level rather than the DB level.** A
`priority` field that still reads HIGH after a retry proves the database column was not
overwritten; it does not prove the job was routed correctly, because a job pushed to `:normal`
would execute and finish with `priority` still reading HIGH. `redis-cli MONITOR` was run across
the retry window instead:

```
[0 172.18.0.1] "ZADD" "distroq:jobs:delayed" "1.788715215167E12" "HIGH:f3f86de1-..."
[0 lua]       "ZREM"  "distroq:jobs:delayed" "HIGH:f3f86de1-..."
[0 lua]       "LPUSH" "distroq:jobs:pending:high" "f3f86de1-..."
```

The `[0 lua]` marker is the promotion script's own calls, so this is the `ZREM`+`LPUSH` pair
inside the atomic unit, landing on `:high`. The replay path was checked the same way and shows
`LPUSH distroq:jobs:pending:low` for a dead-lettered LOW job. All three enqueue sites were
confirmed against Redis, not against the API response.

**The shutdown WARN is pre-existing, not a v0.4 regression.** A clean Ctrl+C on v0.4 produces one
`WARN io.lettuce.core.RedisChannelHandler : Connection is already closed` from the worker thread
— the blocked `BRPOP` being aborted — followed by `Worker ... shutting down` and a clean teardown.
No ERROR, no stack frame, no `loop error, backing off`, so the v0.1 `ContextClosedEvent` fix is
intact. Since v0.4 changed how the worker acquires its connection (connection-callback rather
than `ListOperations`), the possibility that the WARN was newly introduced was worth eliminating
rather than reasoning about: the v0.3 jar was built and given the identical treatment, and it
emits the same line at the same point in the sequence. Pre-existing, third-party, harmless — but
now known to be so.

**Ctrl+C had to be sent as a real console event to test this at all.** `Stop-Process` and
`taskkill` without `/F` do not produce a `CTRL_C_EVENT`, so neither exercises the shutdown hook —
the first is a hard kill and the second is refused. Both produce a "clean" log by virtue of the
process never getting to log anything, which is the kind of test that passes for the wrong
reason. The shutdown evidence above came from `GenerateConsoleCtrlEvent` against the app's
console from a helper process, and from letting Logback own the log file: with output piped
through the shell, the shell dies on the same Ctrl+C and truncates the buffer before the
interesting lines are flushed. Two ways to accidentally not test the thing being tested.

## Verified in v0.5

**A worker was killed mid-job and another instance finished the work.** Two instances against the
same Redis and PostgreSQL, prefixes `worker-one` and `worker-two`. A 30-second HIGH job was
submitted to instance 1 and confirmed `RUNNING` with entry `1788795125064-0` pending and owned by
`worker-one-12191346`. Instance 1 was then terminated forcefully — process gone, port 8080
refusing connections, both checked rather than assumed, because closing a terminal that never
owned the process is the easy way to fake this test.

`XPENDING` through the sequence, which is the whole story in three lines:

```
1788795125064-0  worker-one-12191346   3418ms  1     # running normally
1788795125064-0  worker-one-12191346   9217ms  2     # owner is dead; entry still attributed to it
1788795125064-0  worker-two-b1230ceb  12564ms  3     # reclaimed
```

Instance 2 logged the reclaim with both owners and the idle time (10820ms against a 10000ms
threshold), closed attempt 1 as `ABANDONED`, opened attempt 2 under its own name, ran the job to
completion and only then acknowledged. Final state `SUCCEEDED`, `attemptCount` 2, pending count 0.
Nothing was lost — and the job ran twice, which is at-least-once behaving exactly as documented.

**The `RETRYING` reclaim case was reconstructed rather than raced for.** The interesting window —
a worker that commits `RETRYING`, schedules the delayed member, and dies before `XACK` — is a few
milliseconds wide and cannot be hit reliably by killing a process. Rather than claim a lucky
timing, the state a crashed worker *would* have left was built directly: a `RETRYING` row with a
seeded failed attempt, a delayed-set member due 60 seconds out, and the original `source=SUBMIT`
entry delivered to a consumer named `worker-ghost` that does not exist. Then the app was started.

The sweep reclaimed the orphan from `worker-ghost` after 20s idle, recognised it as superseded —
`source=SUBMIT` against a job whose retry is already scheduled — and acknowledged it without
executing. `ZCARD` on the delayed set stayed at exactly 1 throughout, so no duplicate schedule was
created. At the due time the promotion produced a `source=RETRY` entry, the job ran as attempt 2
and succeeded. Pending count 0, delayed set empty.

Constructing the state is worth flagging as what it is: it proves the *handling* is correct, not
that the window was observed occurring naturally.

**A slow job is not mistaken for a dead one, within a process.** A 10-second job against a
10-second reclaim threshold. `XAUTOCLAIM` did take the entry back — it is idle by Redis'
definition — and the worker recognised it as its own in-flight work and declined to treat it as
abandoned:

```
XAUTOCLAIM on distroq:jobs:stream:high took 1 idle entr(ies) for worker-f57bbf64
Entry 1788794367297-0 on distroq:jobs:stream:high is still running here; not treating it as abandoned
```

The job completed normally as a single attempt. Across processes this remains undecidable — see
*What v0.5 changed about the plan* §11 for the bug this was found by.

**No V5 migration was needed, checked against the live database.** `pg_constraint` for
`job_attempts` holds only the primary key and the v0.3 foreign key. `outcome` is `varchar(255)`
with no CHECK, so `IN_PROGRESS` and `ABANDONED` are writable with zero DDL, and `finished_at` was
already nullable for the open row. Flyway reports `v4` as current on both the pre-existing and a
freshly created database, with no checksum change.

**Metrics distinguish the three stream counts, demonstrated on a live backlog.** Six LOW jobs
queued behind a HIGH job in flight:

```json
"queueDepth": 5,
"queueDepthByPriority":     { "HIGH": 0, "NORMAL": 0, "LOW": 5 },
"streamDepthByPriority":    { "HIGH": 4, "NORMAL": 1, "LOW": 11 },
"pendingEntriesByPriority": { "HIGH": 1, "NORMAL": 0, "LOW": 0 },
"activeConsumers": 1
```

`XLEN` on the LOW stream was 11 and `XINFO GROUPS` reported `lag` 5 — the same numbers, from
Redis, that the endpoint reports. Eleven entries, five jobs waiting. Reporting `XLEN` as queue
depth would have been wrong by more than a factor of two on a system that drained cleanly to zero
sixteen seconds later.

The lag field is why `queueDepth` is exact rather than estimated. It is a Redis 7 addition, it is
not exposed by Spring Data Redis 3.5.13's `XInfoGroup`, and it goes null once entries are deleted
or trimmed — which is survivable here only because v0.5 does not trim. A null lag is reported as
0 and logged, rather than silently substituted.

**The Spring Data Redis surface was checked against the resolved jars, not assumed.**
`javap` against `spring-data-redis-3.5.13.jar` confirmed `StreamOperations` has `claim(..)` but no
`autoClaim`, and that `StreamInfo.XInfoGroup` exposes `pendingCount()` and `lastDeliveredId()` but
not `lag`. Both gaps are filled by binding directly to `lettuce-core-6.6.0.RELEASE`
(`xautoclaim`, `xinfoGroups`), isolated in one class. `XPENDING` plus `XCLAIM` was available as a
fallback and was not used: it is two round trips with a race between them, and the client supports
the atomic command.

Also checked rather than assumed: whether a blocking `XREADGROUP` monopolises the shared
connection. It does not — `LettuceStreamCommands.xReadGroup` branches on
`StreamReadOptions.isBlocking()` and takes a dedicated connection, the same way the v0.4 `BRPOP`
path did. Had it not, a 1-second block would have stalled the retry sweep and every metrics call.

**Graceful shutdown is still clean with Streams, a blocking read and two sweeps running.** Ctrl+C
sent as a real `CTRL_C_EVENT`, with a 20-second job in flight and a retrying job pending. The
shutdown hook ran, Tomcat drained, the worker logged its own shutdown, the executor timed out its
10 seconds and stopped, and Hikari closed. 255 log lines, zero stack frames, zero `ERROR` lines,
no `loop error, backing off` from the worker and nothing from the retry or recovery sweeps — the
`ContextClosedEvent` pattern from v0.1 extends to the new component unchanged.

**Legacy list migration, all four keys.** Real `QUEUED` job rows were seeded into
`distroq:jobs:pending:high`, `:normal` and the unqualified v0.3 key, then the app was started
against a flushed Redis. Each was logged at WARN, routed to the correct stream (the unqualified
key to NORMAL), written with `source=LEGACY_MIGRATION`, and executed to `SUCCEEDED`. All four list
keys were empty afterwards and `KEYS distroq:jobs:*` returned only the three streams. A restart
immediately after created no groups (`BUSYGROUP` treated as success), migrated nothing, and
started normally.

**99 tests, one skipped.** The skip is `DistroqApplicationTests.contextLoads`, `@Disabled` since
v0.1 because it needs live infrastructure. `PriorityStrategyTest` passes unchanged, which is the
concrete return on having kept the scheduling policy free of any queue mechanism: the entire
delivery layer underneath it was replaced and its twelve tests did not move.

## Verified in v0.6

**V5 applied to a populated database with no data loss and no checksum error.** Fourteen rows in
`jobs` before, fourteen after. Flyway logged `Migrating schema "public" to version "5 - scheduled
jobs"` then `Successfully applied 1 migration ... now at version v5 (execution time 00:00.079s)`,
and `flyway_schema_history` lists V1–V5 all `success = t`. `scheduled_at` came out as
`timestamp with time zone`, nullable, with `idx_jobs_scheduled_at` present. Hibernate's
`ddl-auto: validate` then accepted the entity, which is the actual check: a plain `timestamp`
would have failed startup here rather than silently storing local time.

**A future job waited, then ran once, on time.** Submitted 10 seconds out at HIGH:

```
status            SCHEDULED
scheduledAt       2026-09-07T17:57:52Z
ZRANGE            HIGH:89baf9bc-...  1788803872000
XLEN high         4 (unchanged)
scheduledDepth    1
```

Nine polls of `SCHEDULED`, then `RUNNING`, then `SUCCEEDED`. `startedAt` 2026-09-07T17:57:52.517Z
against a requested 17:57:52.000Z — **518 ms**, inside one poll interval. `attemptCount` 1, one
`job_attempts` row, `scheduledAt` still present after success, the sorted-set member gone, `XLEN`
4 → 5, `XPENDING` back to 0. The promoted entry:

```
jobId       89baf9bc-3a13-4cb6-95b6-fa4d62b1fdd4
priority    HIGH
enqueuedAt  1788803872497
source      SCHEDULED
```

**Three spellings of one instant are indistinguishable afterwards.** `2027-03-01T15:30:00Z`,
`2027-03-01T17:30:00+02:00` and `2027-03-01T10:30:00-05:00` submitted as three separate jobs:

```
API           2027-03-01T15:30:00Z          (all three)
PostgreSQL    2027-03-01 15:30:00+00        (all three)
epoch_ms      1803915000000                 (all three)
ZSCORE        1803915000000                 (all three)
```

With `[System.TimeZoneInfo]::Local.Id` = `India Standard Time` and PostgreSQL `SHOW timezone` =
`UTC`. Two different wrong answers were available and neither appeared.

**Every invalid timestamp is a 400 that names the field, and creates nothing.** `""`,
`not-a-date`, `2026-09-07 15:30:00`, `2026-09-07T15:30:00`, `2026-13-01T00:00:00Z` and
`1788715215167` all rejected. `totalJobs` and `ZCARD` on the scheduled set identical before and
after. The offsetless case is the one that matters:

```json
{"status":400,"message":"scheduledAt must be an ISO-8601 timestamp with an explicit UTC offset, e.g. 2026-09-07T15:30:00Z or 2026-09-07T17:30:00+02:00, got '2026-09-07T15:30:00'"}
```

`curl.exe` rather than `Invoke-RestMethod` for these: Windows PowerShell 5.1 throws away the
response body of a non-2xx, so the message under test is exactly the thing the obvious tooling
hides. Same class of mistake as the shutdown-log truncation in v0.5.

**A past timestamp went straight to the stream.** Submitted five minutes in the past: `QUEUED` in
the POST response, `SUCCEEDED` two seconds later, `ZRANGE` on the scheduled set never contained
it, and the entry carried `source=SUBMIT` — not `SCHEDULED`. `scheduledAt` still on the row at
`2026-09-07T17:53:37Z`, five minutes before its own `createdAt`.

**Priority routing held through promotion.** Future HIGH, NORMAL and LOW with one common target
time. All nine stream/tier combinations checked, not just the three expected ones:

```
HIGH   on high True   normal False  low False
NORMAL on high False  normal True   low False
LOW    on high False  normal False  low True
```

Every promoted entry carried `source=SCHEDULED`, and PostgreSQL agreed with the stream on all
three.

**Metrics separated scheduled work from everything else.** During the waiting window, with six
jobs scheduled and nothing else happening:

```json
"delayedDepth": 0,
"scheduledDepth": 6,
"scheduledDepthByPriority": { "HIGH": 1, "NORMAL": 1, "LOW": 4 },
"queueDepth": 0,
"pendingEntriesByPriority": { "HIGH": 0, "NORMAL": 0, "LOW": 0 }
```

At the target time `scheduledDepth` fell 6 → 3, the three stream depths each rose by one, and
`pendingEntriesByPriority` was caught mid-promotion at `{HIGH:0, NORMAL:1, LOW:1}` before
returning to all zeros. `queueDepth` stayed 0 throughout — which is the point: none of that work
was ever backlog.

**A scheduled job survived a hard kill.** Submitted 35 seconds out at HIGH, then
`Stop-Process -Force` on the application only — process confirmed gone, port 8080 refusing
connections, Redis `PONG` and PostgreSQL `accepting connections` both confirmed still up. With the
application dead, `ZSCORE distroq:jobs:scheduled HIGH:3f8dea6a-...` still returned `1788804453000`
and the row still read `SCHEDULED`. Restarted at 18:07:19, promoted at 18:07:33.624 against a
requested 18:07:33 — **624 ms**. One attempt row, exactly one occurrence on the high stream, no
duplicate member, and the attempt is attributed to `worker-867545fa` where the pre-kill process
was `worker-c6f81df4`, so it demonstrably ran in the new process.

**A scheduled job retried like any other.** `fail_n_times` payload 2, `maxAttempts` 5, scheduled 6
seconds out. Observed sequence `SCHEDULED -> RETRYING -> SUCCEEDED`, `attemptCount` 3, attempt
history FAILURE / FAILURE / SUCCESS. The three stream entries:

```
enqueuedAt 1788804526648  source SCHEDULED
enqueuedAt 1788804528679  source RETRY
enqueuedAt 1788804531702  source RETRY
```

All on `distroq:jobs:stream:high`. The retry members were in `distroq:jobs:delayed`, never in the
scheduled set. `scheduledAt` stayed `2026-09-07T18:08:46Z` throughout while `nextAttemptAt` moved
18:08:47.854 → 18:08:50.758 → null. The job never returned to `SCHEDULED`.

**A scheduled job reached the DLQ and replayed immediately.** `always_fail`, LOW,
`maxAttempts` 2, scheduled 6 seconds out. `SCHEDULED -> RETRYING -> DEAD_LETTERED`, two FAILURE
attempts, no scheduled member and no delayed member left. Both DLQ responses carried the original
`scheduledAt` alongside `priority: LOW`.

The replay then went out at 18:10:16.887 with `replayedAt` 18:10:16.882 and attempt 3 starting at
18:10:16.894 — **12 ms** from operator action to execution, with `source=REPLAY` on the LOW stream
and `ZSCORE` on the scheduled set empty immediately afterwards and again five seconds later. Four
entries on the low stream for that job, in order: `SCHEDULED`, `RETRY`, `REPLAY`, `RETRY`. Nothing
on the other two streams.

**Filtering works on the new status.** With seven jobs waiting, `?status=SCHEDULED` returned 7,
`&priority=HIGH` returned 2, `NORMAL` 2 and `LOW` 3. Every row carried a non-null `scheduledAt`.
`?status=SUCCEEDED` returned 24, of which the pre-v0.6 rows show `scheduledAt` null — the column
was not backfilled, which is the correct reading of a job submitted before scheduling existed.

**v0.1–v0.5 behaviour is unchanged, checked against the running system.** Immediate `sleep`
succeeded in 825 ms; `fail_n_times` recovered on attempt 3 with FAILURE/FAILURE/SUCCESS;
`always_fail` reached the DLQ and replayed with `replayCount` 0 → 1; replaying a `SUCCEEDED` job
was still a 409 naming the status; immediate HIGH/NORMAL/LOW each landed on exactly one stream;
all four legacy list keys were `type=none`; and three invalid timestamps left `totalJobs` at 42.

**`XAUTOCLAIM` recovery still works, reconstructed rather than raced for.** With the application
stopped, two entries were handed to a consumer named `ghost-worker` that does not exist. On
restart the sweep took them after 10999 ms idle, logged both owners, recognised the job as already
`SUCCEEDED` and acknowledged without executing. Pending went 2 → 0 and `attemptCount` stayed 1:

```
XAUTOCLAIM on distroq:jobs:stream:normal took 2 idle entr(ies) for worker-94773c47
Reclaimed entry 1788804770403-0 ... previous owner ghost-worker, new owner worker-94773c47, idle 10999ms
Stale delivery 1788804770403-0 ... already SUCCEEDED, acknowledging without executing
```

**The legacy list migration still runs.** A real job ID seeded into `distroq:jobs:pending:normal`
while the app was down was migrated on startup with `source=LEGACY_MIGRATION`, the list drained to
`llen=0`, and the entry was delivered and dismissed as stale.

**Graceful shutdown is still clean with a third sweep running.** Ctrl+C sent as a real
`CTRL_C_EVENT` — again, because `Stop-Process` and `taskkill` without `/F` do not produce one and
would pass for the wrong reason — with a 4-second HIGH job in flight. Tomcat drained, the worker
logged `Worker worker-867545fa shutting down`, the in-flight job completed and was persisted
*after* the shutdown hook started, Hikari closed. Zero stack frames and zero ERROR lines in the
last 120 log lines, and nothing from the retry sweep, the scheduled-job sweep or the recovery
sweep. The `ContextClosedEvent` pattern from v0.1 extended to `ScheduledJobPromoter` unchanged.

**169 tests, one skipped**, up from 99 in v0.5. Same skip, same reason. Every v0.5 test passes
untouched except `WorkerDeliveryTest`, which gained cases rather than changing any.

**One test-only discovery worth recording.** Mockito's `any()` in a varargs position matches
exactly one argument, not "any number of arguments" — so verifying
`redis.execute(script, keys, args...)` with `any()` silently failed to match a seven-argument
call and reported an argument mismatch that looked like a production bug. `any(Object[].class)`
matches the whole varargs array and captures it as one. Five red tests that were entirely the
test's fault.

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

