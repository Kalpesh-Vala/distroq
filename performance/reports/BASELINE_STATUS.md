# Preflight and P0 Review: Baseline Not Yet Measured

Date: 2026-09-12. Source commit: `4f31ada742846859cf67d76e72b8813f90877ac2`.
Scope: controlled local Docker environment, not a production deployment.
**Campaign incomplete. This is not a final v1.1 release benchmark or a frozen performance baseline.**

## 1. Release Precondition

| Concern | Observed status |
|---|---|
| Packaged `/dashboard/` | UNRESOLVED: k6 and integrated Playwright browser both returned HTTP 404 |
| Jobs/Outbox omitted sort | UNRESOLVED: HTTP 200 with section UNAVAILABLE / NullPointerException; explicit sort=createdAt returned AVAILABLE |
| Nonempty Redis pending dashboard | API validated: seven samples had AVAILABLE nonempty pendingEntries, consumer ownership and active lease evidence; browser rendering unverified |
| Browser partial failure | NOT VERIFIED: packaged bundle is absent; no outage or React rendering test executed |
| v1.1 tag/changelog | UNRESOLVED: HEAD has no local tag; changelog still says 1.1.0 unreleased; no remote ref refresh was performed |

Core queue: the small P0 correctness gate and extended identity audit passed; eligible for a separate
controlled core experiment once performance instrumentation is finished. Dashboard benchmark: not ready.
Overall v1.1 release: not ready. Dashboard defects alone do not invalidate the P0 core observations.
No application code, packaging, release metadata or configuration defaults were fixed/tuned.

Browser observation at 2026-09-12T12:51:07.773547545Z: GET /dashboard/ -> 404, code NOT_FOUND,
message "No endpoint GET /dashboard/". Browser tool capability was available; the blocker was the
served artifact, not a missing browser. No browser screenshot or partial-outage fixture was saved.

## 2. Environment

Intel Core i5-10300H, 4 physical / 8 logical cores. Windows 11 Home Single Language build 26200;
OS-reported visible RAM 16,610,188 KiB. Docker Desktop engine 29.7.2, Compose 5.3.1; Linux x86_64 VM,
8 CPUs and 8,241,414,144 bytes memory. Initial free disk: C 320,798,715,904 bytes; D 987,629,129,728 bytes.
WSL 2.7.11.0. Host Java on PATH is Microsoft 21.0.2; Maven wrapper 3.9.16 runs a different Oracle
JDK 21. Node 26.7.0, npm 11.19.0. Runtime container uses Temurin 21.0.12+8-LTS.
PostgreSQL 16.4; Redis 7.4.11. k6 1.3.0+dirty, upstream commit 5870e99ae8-dirty, Go 1.25.1.
Native k6 absent; official Docker image used, digest recorded in raw verdict.

One production-profile app, 1 worker, app CPU limit 2 and RAM 1 GiB. PostgreSQL RAM 2 GiB and Redis
RAM 1 GiB, no explicit CPU limit on either. JVM MaxRAMPercentage=75.0, ExitOnOutOfMemoryError;
actual heap maximum is available in saved Prometheus samples, not equated blindly to container RAM.
JDBC max/min 10/2, Redis max 16; other timing settings are documented in TEST_PLAN and source hashes
are saved. Redis AOF everysec and noeviction. No independent Redis maxmemory cap configured beyond
the container limit. Flyway V1-V8 checksums captured. k6 shares the VM; developer DB/Redis remained
running. No power-mode, thermal, host-sleep, generator saturation or clock-drift monitoring was active.

Initial working tree was clean; benchmark-v1.1 was then created. Only performance files were added.
Packaged build info reports git.dirty=true because tooling was uncommitted; source diff is empty.
Image identities and subsequent runtime/tooling hashes are in the runtime evidence. Original
pre-run tooling hashes and full container-build stdout were not archived; this limits reproducibility
of the initial harness revision. Later immutable records do not retroactively fill that gap.

## 3. Workloads Executed

P0 only: sleep 10 ms, fail_n_times (two failures then success), always_fail (three attempts),
one future scheduled sleep, one idempotent_counter with repeated submission key, and sleep 4000 ms
to observe in-flight pending entries. All three priorities used. One k6 VU, cold mixed workload,
approximately 16.4 seconds of k6 execution. No warm-up; no performance repetitions.
Follow-up: read-only transport/outbox audit and GET checks for all 11 dashboard endpoint shapes.
No P1-P12 performance, outage, scaling or soak workload executed. `k6 inspect` is not execution.

## 4. Results

These are cold mixed-smoke distributions, deliberately NOT headline performance or resume metrics.
Nearest-rank percentiles, milliseconds, original precision retained in derived JSON.

| Measurement | p50 | p95 | p99 | Maximum | Samples |
|---|---:|---:|---:|---:|---:|
| POST submission | 21.385571 | 164.661813 | 164.661813 | 164.661813 | 7 |
| First-start queueing | 553.081 | 839.63 | 839.63 | 839.63 | 6 |
| Attempt execution | 15.052 | 4011.085 | 4011.085 | 4011.085 | 10 |
| Server creation to terminal | 4564.166 | 15645.159 | 15645.159 | 15645.159 | 6 |

Server creation-to-terminal is NOT full client-submission-to-terminal latency. Client/server clock
offset was not measured. The 4-second handler, scheduled delay and retries dominate this mixed
distribution. The single scheduled job started 839.63 ms late; sample count one cannot establish
scheduler p95 performance. No maximum observed or sustainable throughput estimate was computed.

## 5. Correctness Reconciliation

7 accepted responses minus 1 idempotent HTTP replay = 6 distinct UUIDs = 6 durable jobs.
5 SUCCEEDED + 1 expected DEAD_LETTERED; zero scheduled/retrying/queued/running jobs at settlement.
Exact accepted-versus-durable UUID difference: zero. Attempt rows 10 = sum of attempt_count 10;
zero mismatches. One unreplayed DLQ row matches the expected terminal job. Zero early starts.
10 published outbox events, zero unpublished; all 10 deduplication markers present, all 10 retained
Stream entries linked to the expected job and outbox identities. Remaining lag, pending, delayed
and scheduled membership: zero. No unmapped accepted jobs in this six-job cohort.
One completed effect ledger entry and one observed counter increment. This tests repeated submission
identity, not crash-window duplicate-effect prevention. No forced redelivery or reclaim occurred.
Captured durable snapshots were unchanged across all dashboard GET probes; rejected dashboard POST
and unauthorized GET were also checked. This is scoped snapshot evidence, not a universal proof that
every future dashboard route is read-only.

## 6. Failure Recovery

Not executed. No backups or scratch restores were performed; Invoke-Fault refuses without checked
private backup/restore evidence and confirmation. Four unsafe-input refusal tests passed. Kill,
graceful shutdown, XAUTOCLAIM recovery, Redis outage, PostgreSQL outage, and useful-work recovery
timings remain unmeasured. Retry success in P0 is not worker-crash recovery.

## 7. Resource Bottleneck

No capacity bottleneck established. Initial/final Docker statistics, JVM/Actuator, pg_stat_database,
connection-state and Redis INFO/transport snapshots exist, but continuous host/store/generator
resource sampling does not. Neither snapshots nor the 500 ms relay default establish a measured
limiting resource. Do not recommend pool/concurrency/index tuning from this smoke alone.

## 8. Baseline Versus Optimized

No performance baseline frozen; no optimization branch, candidate, tuning or comparison exists.
See [OPTIMIZATION_COMPARISON.md](OPTIMIZATION_COMPARISON.md). Branch remains benchmark-v1.1.
No commit, merge, tag or publication was performed.

## 9. Evidence and Inconclusive Work

All paths below are relative to performance/results; original directories were not overwritten.

| Run ID | Classification |
|---|---|
| 20260912T123208895Z-preflight-9300c35b | Initial environment; Java stderr formatting and WSL NUL decoding noisy, not a workload |
| 20260912T124253607Z-P0-974571ea | VALID for implemented core smoke checks, k6 exit 0; not valid as a capacity/latency-floor experiment |
| 20260912T124549318Z-P0-audit-b0c0f150 | PASS extended delivery/outbox/read-only audit, exit 0 |
| 20260912T124950999Z-runtime-b41ae3c3 | Post-smoke runtime/image/default-source inventory |
| 20260912T124954705Z-preflight-9b77b0e3 | Repeated environment capture after output-decoding fix |
| 20260912T125101586Z-validation-36c8b1fe | PASS: 51 evidence-file checksums verified, 73 files secret-scanned, 4/4 safety refusals; derived smoke summary saved |

The initial pendingObserved flag was false because the harness used the wrong JSON property.
Full original responses prove seven AVAILABLE nonempty samples. The source check was corrected;
derived analysis was saved separately, but the corrected workload has not been rerun. Do not
silently treat its original boolean as a valid assertion. An unsupported gracefulStop option was
found during fault-observer inspect and fixed before any fault execution. The first artifact-validator
execution failed due to PowerShell JSON array enumeration; fixed and rerun successfully. Those
tooling-validation failures were not performance runs; their terminal output was not fully archived.
Docker build skipped tests by its unchanged Dockerfile; the full Java/Vitest suites were not rerun.

## 10. Resume-Safe Wording

No throughput/latency/recovery/soak resume claim is supported yet. Safe limited engineering statement:

> In an isolated Docker Desktop correctness smoke of DistroQ's at-least-once queue, reconciled seven
> accepted HTTP responses to six durable jobs, including one idempotent replay, five successes and
> one expected dead letter; matched ten attempts and ten outbox/Stream delivery identities.

For a resume, prefer the non-capacity wording in [RESUME_METRICS.md](RESUME_METRICS.md) until the
performance campaign is complete. Do not promote a six-job smoke into a reliability SLA.

## 11. Threats and Unproven Claims

Single laptop, synthetic work, one repetition, cold JVM, small tails, colocated generator, other
containers, no thermal/power controls, no continuous telemetry, no clock-offset validation, uncommitted
tooling, incomplete original harness hashing, and a 404 packaged frontend limit conclusions.
Read-only attributes/checksums prevent accidental modification, not malicious tampering or external
evidence loss. Logs were reduced to allowlisted metadata; full diagnostic messages were not retained.
No CPU scaling, horizontal scaling, maximum throughput, 15-minute sustainability, long-run stability,
arbitrary external-effect deduplication, recovery SLA or final-v1.1 acceptance claim is established.
Never claim exactly-once execution, real production users/traffic, cloud extrapolation, or general
zero data loss from these observations.

## 12. Next Experiment

Finish capacity-grade orchestration and independent telemetry first; validate it with a short
explicitly non-capacity shakedown and reconcile every identity. Then P1: one worker, dashboard
closed, sleep 10 ms at 1 arrival/s, excluded warm-up and three 15-minute measured repetitions.
Follow with a separately labeled durable-counter workload, P2 search and >=60-minute soak.
The release defects do not require abandoning the core benchmark, but a final v1.1 claim must wait
for separately authorized release fixes and packaged/browser revalidation. No optimization is
recommended until a measured bottleneck and frozen baseline exist.

## Deliverable Coverage

Completed: test protocol, isolated environment setup, environment/runtime capture, P0 workload,
point-in-time collection, small-cohort reconciliation/audit, raw smoke evidence, refusal-tested
fault scaffolding, this status report, comparison placeholder, resume table, threats/unproven list.
Incomplete: full P1-P12 runners, continuous performance instrumentation, large-cohort finalizer,
validated backup/restore/fault execution, three-repeat capacity measurements, scaling, burst/priority/
retry-storm/scheduler campaigns, browser outage rendering, P10, soak, and an actual immutable
performance baseline. This handoff must not be described as completion of the original campaign.