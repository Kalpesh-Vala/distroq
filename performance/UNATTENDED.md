# Unattended Qualification Only

The completed qualification exposed a collector disagreement. Use [CALIBRATION.md](CALIBRATION.md)
before any P1 measurements; do not rerun qualification as a substitute for C1/C2/C3 calibration.
For the partially completed preserved calibration campaign, use [CONTINUATION.md](CONTINUATION.md)
instead of restarting the full sequence.

This workflow is prepared for a standalone Windows PowerShell 5.1 terminal. No qualification,
P1 or P2 load was executed while preparing it. Unit tests and check-only preflight are not runtime
qualification. The first unattended launch may still expose a collector/platform incompatibility;
that must produce a failed/inconclusive record, not a claimed benchmark result.

## Before Launch

1. Use branch benchmark-v1.1 and the preserved isolated project. Do not run Start-Isolated or reset
   its volumes. Existing terminal jobs and all historical evidence remain in place.
2. Manually close unnecessary VS Code windows, watchers, CPU-heavy extensions, and other workloads.
   Close dashboard clients. No script will close these for you. Do not run unrelated Node,
   PowerShell, WSL or Docker workloads during this qualification. Developer services may remain
   running only if idle. CPU attribution inside the WSL VM cannot separate unrelated Linux work.
3. Keep AC connected. Disable AC sleep/hibernate timers manually for the reserved window. The
   collector refuses enabled timers, a changed plan, missing counters or power-event uncertainty.
   It does not change power plans, clocks, fan settings, heap, worker count or connection pools.
4. Allow roughly 15-20 minutes: five-minute idle gate, two-minute excluded W1 warm-up, five-minute
   W1 qualification, plus startup/sampling/drain/checksum time. Rate is fixed at 1 job/s, worker
   count at 1, no dashboard polling. It creates 120 warm-up and 300 qualification submissions.
5. Install benchmark-only dependencies before closing the editor or beginning idle sampling:

```powershell
Set-Location D:\github\distroq
npm.cmd ci --prefix performance --ignore-scripts --no-audit --no-fund
npm.cmd test --prefix performance
```

Prerequisites: Node/npm, Docker Desktop with the existing app/PostgreSQL/Redis healthy, Git,
PowerShell 5.1, Windows performance counters, `typeperf`, `powercfg`, `wevtutil`, and the cached
k6 image recorded by the tooling. No automatic downloads occur during measured arrivals.

## No-Load Preflight

```powershell
.\performance\scripts\Start-Qualification.ps1 `
  -Project distroq-bench-20260912123428-f8051a -CheckOnly
```

Check-only reads branch/source state and container/volume identity, then prints the plan. It does
not collect idle samples, submit jobs, acquire the campaign lock, or certify host suitability.

## Launch From Standalone PowerShell

Open PowerShell from the Start menu, not the VS Code integrated terminal. After completing the
manual prerequisites, run:

```powershell
Set-Location D:\github\distroq
.\performance\scripts\Start-Qualification.ps1 `
  -Project distroq-bench-20260912123428-f8051a -ExclusiveHostConfirmed
```

For a non-interactive shell invocation from a standalone console:

```powershell
powershell.exe -NoProfile -NonInteractive -File D:\github\distroq\performance\scripts\Start-Qualification.ps1 -Project distroq-bench-20260912123428-f8051a -ExclusiveHostConfirmed
```

The path above is an example for this workspace; the scripts resolve their own filesystem paths.
No execution-policy bypass, privilege elevation, credential prompt, or secret command argument
is used. If local policy blocks scripts, resolve it manually under your security policy.

The confirmation switch attests to exclusive host use, dashboard closed, AC power, and no unrelated
work within Node/PowerShell/WSL/Docker. It is not a waiver of CPU thresholds. The workflow rejects
the VS Code terminal and still measures contention from any editor process left running.

## Gates and Outputs

- Verify preserved raw manifest hashes and scan known credentials/keys before idle collection.
- Require normalized aggregate non-benchmark CPU mean <=10%; no more than 10% of samples may
  exceed 20%. Record raw CPU and divide by the host logical processor count, not physical cores.
- The new bounded host collector uses process-ID-bearing CIM data and separate hardware counters.
  It does not reuse the historical idle collector's collapsed PID mapping. Unsupported/invalid
  counters, material gaps (>15s), power changes and reported performance limits fail closed.
- Only after a valid idle window, run W1 warm-up and settle. Invalid warm-up stops the workflow.
- Only after valid warm-up, run five-minute W1 qualification and settle within the declared drain
  deadline. Exact counts, attempts, effects, dedupe markers, transport, clock bounds, stable backlog,
  application/DB/Redis/container/generator telemetry and host windows must pass.
- Host telemetry runs concurrently with warm-up and qualification. The aggregate **campaign**
  verdict is authoritative: a child job run marked VALID does not override an invalid host window.
- Qualification is excluded from P1/P2 and is not sustainable throughput evidence. There is no
  automatic transition to P1/P2, concurrency changes, retries of invalid runs, or optimizations.

Each attempt creates a new ignored `performance/results/<UTC>-QUALIFICATION-CAMPAIGN-<UUID>/`
directory, plus independent warm-up/qualification result directories. The campaign contains the
plan, host telemetry and qualification.json linking child run IDs. Manifests and checksums are
sealed read-only at completion. Files remain mutable only while being written. Read-only flags
are accidental-edit protection, not signed or WORM storage.

The launcher returns success only for an aggregate VALID qualification; invalid/inconclusive
campaigns return nonzero. Inspect the printed campaign path after completion. Never convert
inconclusive to pass because request counts look correct. No result is uploaded or committed.

## Safety and Interrupted Runs

The launch path never calls Invoke-Fault, kills application/developer services, restarts/reconfigures
the stack, removes volumes or deletes evidence. It may stop its specifically named k6 generator on
a safety abort and terminate its own telemetry subprocesses after collection. Stopped generator
containers are retained; their environments contain the injected token, so Docker inspection is a
privileged secret-bearing operation. Do not publish full docker inspect or console debug output.

The ignored `performance/state/qualification.lock` prevents duplicate launcher instances. Abrupt
power loss/termination can leave this lock and an unsealed run. Do not clear the lock until you have
manually verified its recorded process is gone and no benchmark generator is still running. Preserve
unfinished evidence and classify it inconclusive. The manifest audit intentionally refuses unsealed
result directories; inspect and document interrupted artifacts before another launch.

## Evidence Outside Git

All results, logs, state, dependencies, private backups and temporary credentials are ignored.
The compact EVIDENCE_INDEX records local run IDs, byte counts and manifest SHA-256 hashes. It has
`externalArchiveUri: null`: no archive has been created or uploaded. Copy whole immutable run
directories to a private approved archive yourself and record its locator in a reviewed index change.
Keep manifests with raw data; a manifest alone cannot reconstruct deleted or missing evidence.

Source scripts/workloads/tests, package manifest/lockfile, plans, this documentation, reviewed
reports and the compact index are suitable Git candidates. Raw time-series files are not.