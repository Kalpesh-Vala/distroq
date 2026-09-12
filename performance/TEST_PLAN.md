# Controlled Local Benchmark Protocol

## Scope and Gates

This is production-like local Docker evidence, not production traffic. Delivery and execution
are at least once. Application source, packaging, and defaults remain unchanged in the baseline.
Revision: 4f31ada742846859cf67d76e72b8813f90877ac2. Benchmark tooling is separate, uncommitted work
on benchmark-v1.1, created after a clean-tree check. Do not merge, tag, publish, or optimize
without review. All results, including failed and inconclusive runs, must be retained.

Gate sequence: release preflight -> isolated startup -> P0 -> P1 -> capacity search ->
burst/priority/retry/schedule -> backed-up failure tests -> dashboard overhead -> soak.
A core correctness failure blocks all performance runs. Dashboard failures block P10 and final
v1.1 release claims, but do not alone block a correctly reconciled core experiment.

## Environment and Isolation

Use Start-Isolated.ps1 once per fresh dataset. Never use the developer Compose files for these
tests. Project names begin distroq-bench-, volumes are project-scoped, HTTP binds loopback, and
database/Redis ports are not published. Credentials are generated cryptographically into process
environment variables; never print Compose's resolved environment or full docker inspect.
Never run down -v, FLUSHALL, FLUSHDB, TRUNCATE, or developer backup/restore scripts here.
The current PowerShell session must retain the generated credentials for Compose operations.

Capture host/WSL/Docker versions, exact image IDs/digests, commit/tags/dirty status, build tool
versions, Flyway checksums, app profile, JVM version/arguments, resource limits, and settings before
each campaign. Archive application.yml and application-production.yml by hash, with an explicit
allowlist of non-secret overrides. Credentials are recorded only as configured/unset.
Record dashboard clients, host power mode, other containers/workloads, machine sleep/restarts,
clock agreement (client/app/PostgreSQL), and load-generator colocation. Missing fields remain
unknown, never inferred. Record clock offsets before comparing client and server timestamps.

Configured starting point: 1 app, 1 worker, app 2 CPUs/1 GiB, PostgreSQL 2 GiB, Redis 1 GiB;
the existing production JVM RAM percentage is 75. JDBC pool max/min 10/2, connection timeout
5000 ms; Redis pool max 16, min-idle 1, max-idle 8, command timeout 5000 ms. Stream block 1000 ms,
claim idle 60000 ms, read count 1, claim batch 100. Lease 30000 ms, heartbeat 5000 ms. Retry
base/max 1000/60000 ms, jitter 0.2, promoter poll/batch 1000 ms/100. Scheduler poll/batch
1000 ms/100; outbox poll/batch 500 ms/100, lock 30000 ms, max attempts 100. Reconciliation
poll 30000 ms, batch 100, auto-repair false. Validate effective settings against each container.
Redis AOF everysec, noeviction. Retained Stream length is not executable backlog.

Native k6 was absent at preflight. Use Docker grafana/k6:1.3.0, recording its resolved digest
and version (including the upstream +dirty build suffix). No ad hoc throughput loop is allowed.
Framework tests may inform correctness but cannot substitute for real PostgreSQL/Redis/runtime
tests. Existing Java tests and dashboard Vitest tests are reusable regression assets, not new
performance evidence. Browser journeys require Playwright; no mock browser result proves packaged
serving. No embedded-database fallback is valid for this benchmark.

## Run Lifecycle

1. Allocate a new UTC-and-UUID result directory; refuse overwrites. Record exact command/config.
2. Verify readiness UP, zero durable jobs/attempts/outbox/effects/DLQ, zero transport lag/pending,
   zero retry/schedule membership, and known empty Stream state. Preserve initial snapshots.
3. Warm JVM/pools with the same workload, excluding warm-up from results. Settle it completely.
   Use distinct cohort IDs and initial/final state deltas; never mix warm-up samples with measured
   samples. A fresh application restart after warm-up would invalidate the warm-up.
4. Collect telemetry every 5 seconds, health every second during faults, monotonic elapsed time
   plus UTC. Record collector overhead. Write explicit missing-sample markers rather than zeroes.
5. Execute controlled open-loop arrivals. Preallocate VUs for observed latency, record dropped
   iterations and generator CPU. Timeouts are unknown acceptance until reconciled, not lost jobs.
6. Stop arrivals; allow a declared drain deadline (120 seconds for ordinary runs; 300 seconds
   for retries/reclaim). A longer deadline must be declared before the run. Keep drain outside
   the measured capacity interval, but include it in full-cohort latency and correctness.
7. Save redacted logs, telemetry, client records, durable snapshots and reconciliation. Settle
   means no ready lag, pending deliveries, running jobs, due schedules/retries, or unpublished
   outbox events; intentionally future work is separately enumerated.
8. Assign VALID, INVALID, INCONCLUSIVE, or CORRECTNESS_FAIL with reasons. Hash every artifact
   and mark files read-only. Corrections belong in a new report, never in an old run directory.

## Definitions and Statistics

Accepted throughput is 202 responses per measured second. Completion throughput is jobs reaching
SUCCEEDED or DEAD_LETTERED per measured second; always split successful and dead-letter throughput.
For the ordinary success workload, any unexpected retry or dead letter fails the point.
Submission latency is client POST duration. Original submission time is the client send time;
created_at is a separate server-ingress proxy and MUST NOT be mislabeled full end-to-end latency.
End-to-end is terminal finished_at minus client submission time with measured clock uncertainty.
Queueing is first job_attempts.started_at minus eligibility (created_at for immediate jobs,
requested scheduled_at otherwise). Execution is per-attempt finish minus start. Do not use the
jobs.started_at field for first-start calculations; it changes on retry. Scheduled lateness is
first start minus scheduled_at. Separate promoter delay using transport/publication evidence
from post-promotion worker waiting; where timestamps are absent, report decomposition unmeasured.

Report p50/p95/p99/max/sample count; retain full distributions and censor unfinished jobs explicitly.
Use exact nearest-rank percentiles for stored observations, naming k6's own interpolation separately.
No averages as headlines. Median of three repetitions is the headline, ordered by successful
completion throughput; report that repetition's associated latencies, not independently cherry-picked
latencies. Report min/median/max and sample-standard-deviation/mean coefficient of variation.
Use unrounded evidence in machine files and truncate, never round upward, in resume claims.

Sustainable means >=900 measured seconds with no correctness failure, resource exhaustion, or
continuously growing executable backlog/latency. Normal-load HTTP error threshold is <=0.1%,
but any unreconciled acceptance fails regardless of rate. Dropped k6 iterations, sustained
generator CPU >=85%, missing telemetry, host sleep, or unexpected restarts invalidate a capacity
run. Stop escalation on growing backlog in all three final 60-second windows, final five-minute
p99 >1.2 times preceding five-minute p99, or CPU >=90% for 60 seconds. Inspect raw trends before
accepting stability: these thresholds do not override visibly unbounded growth. Memory limit
pressure, pool exhaustion, evictions, or unknown counts also stop escalation. Externally caused
rejections during fault tests are expected and reported separately, not scored as normal load.

## Workload Matrix

| ID | Controlled experiment | Evidence and acceptance |
|---|---|---|
| P0 | Small mixed success, fail_n_times, always_fail, all priorities, future schedule, repeated submission keys, idempotent_counter | Same identity on replay, attempt history, correct DLQ, no early start, exact effect/counter agreement, read-only dashboard, transport/durable reconciliation. Must pass before P1. |
| P1 | One worker, 1 arrival/s initially, deterministic sleep 10 ms, dashboard closed | Three 15-minute measured repetitions after warm-up; latency floor only. Label sleep synthetic. |
| P2 | Separate sleep 10 ms and idempotent_counter campaigns; workers 1,2,4,8 | Open-loop 1,2,5,10,20,50 jobs/s then bounded steps near saturation; preliminary probes are not sustainable results. Three >=15-minute repetitions at each claimed capacity point, one excluded warm-up each. Keep app CPU/pools constant; 16 workers deferred unless pool/headroom valid. |
| P3 | Idle -> 2x,5x,10x established sustainable rate for 10 seconds | Peak accepted/backlog, 120-second drain, p95/p99, outbox delay, readiness, false lease loss/retries/DLQ. Abort safely on thresholds. |
| P4 | Queue NORMAL/LOW then HIGH, one worker first | First-start order, older jobs bypassed, starvation guard at 10 deliveries, continued LOW progress; no preemption claim. Repeat with continuous HIGH. |
| P5 | fail_until_flagged at 10%,50%,100% within conservative capacity | Controlled Redis flags only; clear flags at recorded point, never external services. Retry intervals/jitter, delayed members, waves, outbox attempts, recovery/DLQ. Flag mutation confined to known job IDs in isolated Redis. |
| P6 | Spread schedules over 30 seconds plus same-time 100-job batch | Zero early starts, lateness quantiles, promoter batches, drain; separately restart application with future work and verify persistence. |
| P7 | Forced SIGKILL during in-flight work, separate graceful SIGTERM control | Backups required. Record injection/detection/claim/useful-work timestamps, attempts/ABANDONED/leases/pending, final state, duplicate effects. Current counter handler has no precise external crash hook: do not claim a specific crash point unless observed. |
| P8 | Redis pause intervals 10,30,60 seconds | Backups first; preserve volume/AOF. API outcomes, health transitions, outbox growth/recovery surge, duplicate delivery, reconciliation, browser partial failure. |
| P9 | PostgreSQL pause intervals 10,30,60 seconds | Backups first; submission rejection, lease renewals/finalization, no false success, reclaim/recovery, browser partial failure. |
| P10 | 0,1,5,10 dashboard clients at actual configured 5-second polling | Blocked by release defects until reviewed. Model real active-page endpoint mix from frontend, not all 11 endpoints every cycle by assumption. Report mix; additional all-page stress is a separate workload. Three repetitions with same established load. |
| P11 | >=60 minutes at 70% of established saturation-bound sustainable point | Prefer 2-4h with approval/environment availability; compare first/last 10-minute windows and trends in memory/heap, backlog, pending, stream length, pool, GC, throughput and latency. Monotonic unrecovered growth fails; report intentional Stream retention separately. |
| P12 | Optional two instances, shared benchmark stores | Owner/consumer distribution, simultaneous recovery, kill versus graceful shutdown, priority, attempts/effects; no horizontal scalability claim without this test. |

Worker efficiency: E_n = T_n / (n * T_1), using sustainable successful-completion throughput for
the same workload/limits. These I/O-bound synthetic handlers do not establish CPU scalability.
Dashboard overhead: 100 * (baseline throughput - polling throughput) / baseline throughput;
latency cost: 100 * (polling latency - baseline latency) / baseline latency. Preserve signs.

## Telemetry and Reconciliation

Client: send/receive timestamps, status, replay flag, job UUID, duration, timeout/error and iteration
counts. Never persist raw payloads, bearer tokens, keys, or HTTP debug dumps. k6 tags must be bounded.
Application: Prometheus counters/timers, heap, GC, threads, active workers, leases/reclaims/effects,
outbox, readiness/liveness. Save restart count and metrics resets. Dashboard throughput is sampled
and approximate: do not use it as the authoritative completion count.
PostgreSQL: bounded read-only pg_stat_database/activity/locks, connection states, table/index sizes,
Flyway checksums, all cohort job IDs/statuses/timestamps and attempt metadata. Never SELECT payload,
idempotency_key, last_error, reason, or raw query text. pg_stat_statements is optional and cannot be
silently enabled as a baseline change. Record unavailable query-rate/slow-query instrumentation.
Redis: INFO, XINFO GROUPS/CONSUMERS, XPENDING summaries, XLEN, ZCARD, commandstats/latencystats,
persistence/noeviction settings. Avoid payload-bearing XRANGE exports and general key dumps.
Docker: stats including app/stores/generator CPU/memory/network/block I/O plus resource limits.

Reconcile accepted responses minus idempotent replays by UUID against durable jobs; unknown network
outcomes need separate idempotency resolution held privately during execution. Sum statuses:
SUCCEEDED + DEAD_LETTERED + intentionally SCHEDULED/RETRYING/QUEUED + legitimately RUNNING.
Counts alone do not prove recoverability: queued/retrying/scheduled jobs need matching transport
or progressing outbox state; pending entries need active/expired lease mapping; expired owners need
reclaim/reconciliation evidence. Unresolved mappings make loss inconclusive. Compare attempt_count
to attempt rows; active attempts may not yet be historical rows. Compare dead_letters to terminal
jobs, effect ledger to actual counters, published outbox to dedupe markers/transport where observable.
Never infer lost=0 merely from accepted=completed aggregate counts.

## Fault Safety

Before injection verify exact benchmark Compose labels, known container IDs, healthy stores,
restorable PostgreSQL custom dump and Redis AOF/RDB backup, checksums, and free disk. Private backups
contain payloads/keys and MUST stay under performance/private (ignored), never raw public results.
Run a restore verification in separate scratch containers before declaring backup verified.
Require explicit operator confirmation naming the project and fault target. Never inject against
developer container names or a target selected by port alone. Bound duration and use try/finally
to restore paused services. Record intended versus observed duration. A paused Redis test measures
unresponsiveness, not a TCP refusal; label it accurately. No volume deletion is part of an outage.

## Reporting and Optimization

Freeze a baseline only after valid measurements and review. A blocked-campaign report is not a
performance baseline. Save all attempts, including failures. No optimization is currently authorized.
For each later candidate: measured symptom -> limiting resource -> falsifiable hypothesis -> one
approved change on a separate branch -> predicted metric/regression -> identical repetitions ->
accept/reject, retaining negative results. Tooling commits must be separate from optimization commits.

Threats: single laptop/4 physical cores, shared WSL CPU/RAM, active developer containers, load generator
colocation, thermal/power management, clock uncertainty, cold build/startup, telemetry overhead,
synthetic handlers and durable-counter hot-key contention, finite sample tails, preserved stream
growth, mutable base-image tags (record digests), no real external effects, absent crash-point hooks,
and missing browser/package validation. No cloud extrapolation, exactly-once execution, production
users/traffic, zero loss, recovery SLA, horizontal scaling, or capacity claims without their evidence.