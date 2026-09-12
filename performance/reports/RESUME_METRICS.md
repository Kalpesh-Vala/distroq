# Resume Metrics: Performance Evidence Pending

No performance value is filled from the cold six-job correctness smoke. Baseline and optimized
columns remain TBD until the associated experiment is executed and reviewed. The smoke's configured
worker count was one, not a demonstrated capacity configuration.

| Metric | Baseline | After optimization | Evidence |
|---|---:|---:|---|
| Sustainable accepted jobs/s | TBD | TBD | No >=15-minute run |
| Sustainable completed jobs/s | TBD | TBD | No >=15-minute run |
| Peak accepted jobs/s | TBD | TBD | No capacity/burst experiment |
| Submission latency p95 | TBD | TBD | Cold smoke is not P1/P2 |
| End-to-end latency p95 | TBD | TBD | No measured clock bound |
| End-to-end latency p99 | TBD | TBD | No measured clock bound |
| Worker concurrency | TBD | TBD | Capacity config pending; P0 used 1 |
| Scaling efficiency | TBD | TBD | No sustainable worker matrix |
| Worker-crash recovery time | TBD | TBD | P7 unexecuted |
| Redis-outage recovery time | TBD | TBD | P8 unexecuted |
| PostgreSQL-outage recovery time | TBD | TBD | P9 unexecuted |
| Scheduled lateness p95 | TBD | TBD | Only one P0 scheduled sample |
| Dashboard overhead | TBD | TBD | P10 blocked by release defects |
| Soak-test duration | TBD | TBD | P11 unexecuted |
| Lost jobs | TBD | TBD | 0 unreconciled identities in six-job smoke only |
| Duplicate effects | TBD | TBD | 1 expected/observed counter increment; no forced-redelivery test |

## Full Engineering Claim Available Now

On an Intel i5-10300H Windows 11 host using isolated Docker Desktop services and one configured
worker, executed a small mixed correctness smoke of DistroQ's at-least-once queue: seven HTTP 202
responses represented six durable jobs after accounting for one idempotent replay. Five succeeded,
one intentionally dead-lettered, and all ten attempts and ten outbox/Stream delivery identities
reconciled. This was not a sustained-throughput, failure-recovery, or production-traffic experiment.

## Concise Resume Wording Available Now

> Built an at-least-once distributed job queue with transactional outbox and idempotent submission;
> validated retry, scheduling and durable-state reconciliation in isolated Docker-based correctness tests.

No numerical performance bullet is justified yet. Do not insert the smoke's latency percentiles into
the user's proposed sustained-throughput bullet. Add that bullet only after the measured campaign.

## Interview Explanation

- Environment: four physical/eight logical host cores, Docker Desktop sharing resources with its
  load generator and existing developer containers; one app/worker and real PostgreSQL/Redis.
- Workload: synthetic sleeps, controlled failures, a schedule and a cooperative transactional counter.
  The counter is I/O/database work, not evidence of CPU scalability or arbitrary external effects.
- Accepted versus completed: 202 acknowledges durable submission, not execution; repeated submission
  keys can produce additional accepted responses without creating additional jobs.
- Sustainable throughput requires >=15 minutes, stable backlog/latency and resources, correct
  reconciliation, excluded warm-up, three measured repetitions and median-run reporting.
- p95/p99 expose slow-job behavior hidden by averages, but seven POSTs cannot establish a stable tail.
- What failed first: release validation found packaged dashboard 404 and omitted-sort unavailability.
  These are functional defects, not measured resource saturation.
- What improved: only benchmark-tooling mistakes were corrected. No application optimization occurred.
- Still unproven: capacity, scaling, recovery, duplicate-effect protection under crash, scheduler tails,
  dashboard polling cost and soak stability.
- Production-like means real infrastructure and production configuration locally, not production
  traffic, real users, a cloud cluster, or production-proven reliability.