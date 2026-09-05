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
spent on a shape that is not settled. The plan is to switch to Flyway around v0.5, baselining
whatever the schema looks like at that point, once it stops moving every few days. Leaving
`update` in place past that point would be the actual mistake.

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
the fix.
