# DistroQ Performance Workbench

## Standalone Qualification

Use [UNATTENDED.md](UNATTENDED.md) for the current qualification-only launcher. It does not run
P1/P2 or fix release defects. Close competing applications and launch outside VS Code. The scripts
below include historical tools; Test-IdleGate and Report-IdleGate are not the unattended gate.
All generated results are now ignored. [reports/EVIDENCE_INDEX.json](reports/EVIDENCE_INDEX.json)
locates preserved local runs by run ID and manifest hash; it does not imply an external backup exists.

**Status: preflight and a small P0 correctness smoke executed; the requested performance campaign
is not complete. No sustainable-capacity or failure-recovery resume metric is available.**

Start with [TEST_PLAN.md](TEST_PLAN.md), [reports/BASELINE_STATUS.md](reports/BASELINE_STATUS.md),
and [reports/RESUME_METRICS.md](reports/RESUME_METRICS.md). Application source is unchanged.

## Reproduce the Completed Smoke

Run from the repository root in Windows PowerShell 5.1 with Docker Desktop running. These commands
create fresh benchmark resources; they do not reuse or reset developer data. Keep the same terminal
for Compose operations because generated credentials exist only in its environment.

```powershell
./performance/scripts/Capture-Environment.ps1
./performance/scripts/Start-Isolated.ps1 -Workers 1 -Port 18081
./performance/scripts/Run-P0.ps1 -Project $env:BENCH_PROJECT
```

The runner prints a UTC/UUID output directory. Use its leaf name as SourceRun:

```powershell
./performance/scripts/Audit-P0.ps1 -Project $env:BENCH_PROJECT -SourceRun '<printed-P0-run-ID>'
./performance/scripts/Capture-Runtime.ps1 -Project $env:BENCH_PROJECT
./performance/scripts/Summarize-P0.ps1 -RunDirectory './performance/results/<printed-P0-run-ID>'
```

The original completed stack is `distroq-bench-20260912123428-f8051a`, on loopback port 18080.
Its volumes are preserved. Run-P0 refuses this nonempty dataset; use a new project for another smoke.
No script removes volumes. Normal shutdown is not a fault-recovery measurement. Stop only explicitly
identified benchmark containers, never wildcard all DistroQ containers.

## Tooling Maturity

| Artifact | State |
|---|---|
| Capture-Environment / Start-Isolated | Executed successfully; environment records are append-only |
| Run-P0 / Collect-Snapshot / reconcile.sql | Executed successfully for six jobs; snapshots are point-in-time, not continuous resource monitoring |
| Audit-P0 | Executed; all outbox markers/delivery identities and captured durable read-only state matched |
| Summarize-P0 | Executed against preserved observations; nearest-rank smoke quantiles only |
| Validate-Artifacts | Executed; syntax, hashes, read-only flags, known generated-secret and sensitive-field checks; currently references the original smoke for derived analysis |
| arrivals.js | k6 inspect passed; NOT executed; open-loop synthetic sleep/counter, telemetry and Overview polling |
| fault-observer.js | k6 inspect passed; NOT executed against an outage |
| Invoke-Fault / Test-Safety | 4 refusal-path tests passed; actual injection/restoration and backup workflow NOT validated |

## Capacity Workload Contract

[workloads/arrivals.js](workloads/arrivals.js) takes `RUN_ID`, `RATE`, `SECONDS`, `VUS`, `JOB_TYPE`,
`PHASE`, `DASHBOARD_CLIENTS`, `BASE_URL`, and runtime `BENCH_ADMIN_TOKEN`. Defaults: 1 job/s,
900 seconds, 50 fixed preallocated VUs, idempotent_counter, no dashboard clients, internal app URL.
Never log the token. It must be passed using Docker `-e BENCH_ADMIN_TOKEN`, not a literal value.
Mount workloads read-only at `/workloads` and a newly allocated result directory at `/results`;
join only the explicit benchmark project's default network. Use the recorded k6 digest from P0.
Use `--console-output /results/client.jsonl --out json=/results/k6-samples.json`; the workload writes
its summary to `/results/k6-summary.json`. Default phases are deliberately UNCLASSIFIED.

This file alone is NOT a certified capacity runner. Still required before any performance claim:
automated warm-up/exclusion and drain gates, cohort-based large-run reconciliation, clock-bound
analysis at both ends, continuous Docker/PostgreSQL/Redis collection including generator saturation,
live abort criteria, three repetitions, completed-run sealing, and telemetry-gap validation.
The current collector performs full cohort exports only for the small isolated smoke and must not
be run every five seconds on an unbounded dataset. Add bounded summaries for ongoing sampling.
Overview polls one real endpoint per active page, matching the current frontend; it is not a claim
that all 11 dashboard endpoints are polled simultaneously. Multi-page mixes remain to implement.

P3-P6 and P12 have protocols in TEST_PLAN but no complete executable orchestration yet. P7-P9 have
guarded injection scaffolding, not finished experiments. P11 has a protocol, not a completed soak.
No optimization branch or application tuning is authorized by these files.

## Fault Preconditions

Invoke-Fault refuses anything except the exact benchmark project and named service. It requires
private PostgreSQL and Redis backups, an operator-created manifest and successful scratch-restore
evidence. No backup or restore has been performed in this campaign. Do not create a PASS attestation
without actually restoring and comparing the data.

Expected private manifest schema (paths relative to the manifest directory):

```json
{
  "project": "exact-benchmark-project",
  "createdUtc": "ISO-8601-UTC",
  "postgres": {"file": "postgres.dump", "sha256": "UPPERCASE-SHA256", "containerId": "full-ID"},
  "redis": {"file": "redis-backup", "sha256": "UPPERCASE-SHA256", "containerId": "full-ID"},
  "restoreEvidence": {"file": "restore.json", "sha256": "UPPERCASE-SHA256"}
}
```

The referenced restore evidence must name the same project, `status: PASS`, and boolean
`postgresVerified`/`redisVerified`, supported by retained scratch-restore outputs. This is an
operator attestation, not automatic proof; automatic backup and restore verification remain to build.
Backups stay in ignored `performance/private`, never in public results. First use `-WhatIf`.
Actual injection additionally requests PowerShell confirmation. Redis/PostgreSQL support pause,
not reset. Observed duration includes observer startup/timeouts and is recorded separately from
the requested duration. Recovery-to-useful-work is deliberately left inconclusive by this helper.

## Evidence Handling

Each results directory is uniquely named, checksummed and marked read-only when complete. The
read-only attribute is accidental-edit protection, not WORM storage or a signature. Do not overwrite
it to fix a report; append a new derived analysis. Manifests are not independently signed.
No raw payloads, submission keys, bearer tokens or private backups belong in reports. The first
smoke's raw pendingObserved flag is wrong because it read `status` rather than `availability`;
the preserved response bodies support the corrected result in the separate derived summary.
The corrected workload has not been rerun against a fresh P0 cohort.

Keep tooling commits separate from later optimization. Raw evidence is intentionally excluded
from the tooling commit and remains local; do not merge, tag or publish results without review.