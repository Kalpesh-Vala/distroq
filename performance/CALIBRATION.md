# Host Telemetry Calibration Before P1

For the approved continuation-only implementation, use [CONTINUATION.md](CONTINUATION.md).
It supersedes the earlier "not yet implemented" preparation notes below. Do not use the
full-sequence command in this file to resume the preserved campaign.

## Repeat Continuation Review Gate

The preserved campaign is `20260912T162818671Z-CALIBRATION-REVIEW-f50d2f1e`. Its C1, C2 and C3
captures remain valid; reference `20260912T162713228Z-CAL-REFERENCE-bb86699b` remains invalid.
Do not rerun the original captures or use the full-sequence command below to resume missing repeats.
No repeat was executed during this review: the previously documented screen omitted a p95 limit,
and the requested procedure requires defining and testing it before proceeding.

Prospective comparison policy `prospective-mean-p95-v1`, defined before any new repeat:

| Criterion | Limit |
|---|---:|
| Absolute host CPU mean difference | <=2.0000 percentage points |
| Absolute host CPU p95 difference | <=2.0000 percentage points |
| Maximum sample gap in either capture | <=15 seconds |

The p95 limit deliberately uses the same absolute tolerance as the established mean limit; it is
a conservative engineering screen, not a statistical confidence interval or a limit fitted to
repeat results. Thresholds use full-precision observations without rounding. Both captures must
already pass host-total coverage, tracked attribution, lifecycle, power and evidence gates.
Background activity and reference drift still require review: a passing numerical screen alone
does not select a collector. Two-observation sample CV is descriptive and weak evidence, not proof
of a stable population. A zero denominator produces null, not an invented percentage.

The analysis reports absolute/relative mean differences, p95/p99 differences, maximum-gap and
tracked-instrumentation differences, diagnostic unavailable counts by family, and sample CV for
host mean, host p95 and instrumentation mean. Diagnostic degradation never changes the host total.

After this prospective policy is reviewed, continuation must execute only C1-repeat then C2-repeat,
each following a new valid reference. Allow at most three consecutive reference failures, preserving
each attempt, with a prospectively configured 60-second cooldown between failed references. The
existing 2.0000-point half-window settling threshold remains unchanged. The continuation runner
is not yet implemented; the full-sequence launcher is NOT an acceptable substitute. Do not start
P1 automatically. New reference/capture estimates must be paired by IDs, include UTC time gaps and
mean/p95 differences, and must not silently pool temporally unrelated references.

Future methodology proposal only: evaluate half-window mean difference together with linear CPU
trend, window p95 or standard deviation, and two consecutive stable windows. Design/test that policy
in a separate future campaign; do not implement it here or use it to reclassify existing references.

## Revised Windows Attribution Policy

This section supersedes the earlier subtractive accounting and zero-diagnostic-error gate below.
Old C1 `20260912T150730387Z-CAL-C1-04aa0d40` remains INVALID and unchanged. Reanalysis explains
21 unavailable diagnostic observations (20 smartscreen, one svchost) with complete required host
counters. It does not retroactively qualify that capture; tracked lifetime evidence was not saved.

- `hostTotalCpu` comes only from the authoritative Processor(_Total) counter.
- `trackedBenchmarkCpu` reports PIDs owned by the orchestrator/ChildProcess handles. Saved start/end
  lifetimes bound when a PID must be present. The exact PID and expected executable family must
  match; a familiar process name never grants ownership. Missing or duplicate tracked PID rows fail.
- `diagnosticProcessCpu` is best effort. Negative/missing CPU or PID values remain unavailable,
  counted by family with bounded examples. They are not filled with zero. A reused instance suffix
  does not establish PID identity. An invalid tracked observation is fatal even if diagnostics degrade.
- Exact `unattributedCpu` is null (unknown), not zero: process and host-total time bases have not
  been demonstrated subtractable. The host total is its conservative upper bound. Docker/container
  CPU remains separate and is never subtracted from Windows host total.

For idle calibration, **contention equals host total CPU**, including idle infrastructure and
instrumentation. The unchanged thresholds are mean <=10% and no more than 10% of samples >20%.
Required host/timing/frequency counters have zero missing-sample allowance; maximum gap remains
15 seconds. Diagnostic unavailability alone produces a warning, not failure. Missing owned-process
attribution, host-total failure, limiting flags, power/lifecycle changes or actual contention fail.
Console and saved validity reasons distinguish HOST_TELEMETRY_FAILURE,
TRACKED_PROCESS_ATTRIBUTION_FAILURE, ACTUAL_CONTENTION_FAILURE and the nonfatal warning
DIAGNOSTIC_ATTRIBUTION_DEGRADATION. Failed captures stop before any subsequent capture or P1.

The independent collector now receives `-CalibrationIdle` only in this workflow so its local gate
also uses authoritative host total. Its qualification behavior is unchanged. C3 starts the
independent process before typeperf enumerates process columns; finished child lifetimes are not
required in later samples of the longer collector. Both collectors still report all diagnostic
WMI/VS Code observations. No OS protection or service is disabled.

Process V2 discovery on this Windows 11 host (2026-09-12): available, with `% Processor Time` and
`Process ID`. Three short simultaneous discovery samples of legacy/V2 had 548 counter observations
per family per sample and zero status errors. V2 instance paths included `name:PID` (for example
acpowernotification:11628), whereas legacy paths use names and instance suffixes. This establishes
availability and short-probe completeness only, not long-running PID stability or overhead. The
probe was run in the editor and is NOT a calibration capture. Relative overhead is unmeasured;
Windows-version portability is unverified and this counter set cannot be assumed on other builds.
No automatic switch: retain legacy Process with diagnostic degradation until V2 is separately
benchmarked and reviewed. No canonical collector is selected by this change.

Status: prepared, unit-tested and subject to no-load preflight. No C1/C2/C3 capture or P1 baseline
has been executed by this change. The existing 300-job qualification remains qualification only.
Do not run this workflow while an active editor or unrelated work materially consumes CPU.

## Standalone Launch

Close VS Code and unnecessary applications manually. Keep Docker Desktop, the existing isolated
stack and AC power running. Disable automatic AC sleep/hibernate manually. Reserve approximately
35-40 minutes. Do not run another benchmark, development load or unrelated WSL task during capture.
The original application settings, worker concurrency and data are not changed.

```powershell
Set-Location D:\github\distroq; .\performance\scripts\Start-TelemetryCalibration.ps1 -Project distroq-bench-20260912123428-f8051a -ExclusiveHostConfirmed
```

Use `-CheckOnly` instead of `-ExclusiveHostConfirmed` to inspect the plan and connect to the stack
without any capture. Full mode refuses a VS Code integrated terminal and requires the explicit
exclusive-host attestation. It does not terminate editor, remote-session, Docker/WSL or application
processes. Only Docker stats subprocesses owned by this runner are stopped at the end of a capture;
bounded typeperf and independent collector processes terminate themselves.

## Experimental Sequence

| Capture | Runner typeperf | Independent WMI collector | Job submissions |
|---|---|---|---|
| C1 | Enabled | Disabled | None |
| C2 | Disabled | Enabled | None |
| C3 | Enabled | Enabled | None |
| C1-repeat | Enabled | Disabled | None |
| C2-repeat | Disabled | Enabled | None |

Each capture lasts at least five minutes. Repeats are isolated, not simultaneous. C3 is the only
simultaneous collector run. Every capture has its own timestamped immutable directory. Separate
60-second total-host-only typeperf references precede captures, after the previous collectors exit.
Reference windows must have mean total CPU <=10% and no >2 percentage-point change between halves.
Otherwise the campaign stops before the next capture. A fixed reference interval is not proof of
settlement; the trend/mean checks are the declared discriminators. Reference failures are preserved.

C1 retains the runner's typeperf method but adds PID and frequency/performance counters to the same
query. This is explicitly an augmented candidate collector: it is NOT an exact byte-for-byte replay
of the older qualification instrumentation. PID counters are needed to avoid the historical
same-name attribution problem. Its measured overhead applies only to this recorded counter set.
C2 uses Collect-QualificationHost.ps1 unchanged, including its own reported validity verdict. Both
raw stdout and normalized samples are retained. C3 compares nearest samples within three seconds;
unmatched samples are not invented or interpolated. Both C3 total-CPU estimates are reported.

All captures share identical Docker stats collection for app/PostgreSQL/Redis. Read-only host,
application, PostgreSQL and Redis snapshots occur before and after each capture, outside its
five-minute window. Their counters/state and the collector source hashes are retained. Application
Prometheus values are boundary samples in calibration, not a claim of continuous application
telemetry; calibration's experimental load is host instrumentation, not job execution.

## CPU Accounting

Normalize raw Windows process percentages by logical processor count. Keep Docker/container CPU
in its native per-logical-core units as a separate diagnostic; do NOT subtract a Docker sample
directly from Windows total CPU or count both container CPU and vmmemWSL CPU as independent work.

```text
unrelated host contention (conservative residual)
  = Windows total non-idle host CPU
  - explicitly owned host-PID application/infrastructure CPU
  - explicitly owned host-PID load-generator CPU
  - explicitly owned and measured instrumentation CPU
```

During idle calibration no generator exists. The owned Node orchestrator, its typeperf/PowerShell
collectors and its docker-stats CLI are instrumentation. They are individually identified by PID
and subtracted only when their counters are present. Missing CPU is not subtracted. WMI provider
CPU is shown separately but NOT assigned to instrumentation merely because queries use WMI.
WSL/VM/backend CPU remains unattributed in the host residual. This is conservative and may reject
an environment that would pass with better attribution; it never silently subtracts unknown work.
PID reuse, missing/duplicate PID counters, invalid data or subtraction exceeding total CPU blocks
confidence in attribution. Exact process lifetime attribution beyond the captured owned PIDs
remains a review obligation; no process-family blanket exclusion is used in the new policy.

Mean unrelated CPU must remain <=10%; at most 10% of samples may exceed 20%. Instrumentation is
always part of total host consumption. Repetition comparisons retain total, instrumentation,
unrelated, WMI, VS Code and VM/container observations, not only the gate result. The historical
hostContention policy remains in the older workload runner; P1 must not use that path until a
canonical collector is selected and integrated. Calibration uses attributedHostCpu instead.

## Observer Overhead

The common reference is the mean of the total-only reference captures. It contains a smaller
sampler and the running idle stack; it is NOT an exact zero-instrumentation baseline.

```text
runner relative overhead = C1 mean total CPU - common reference mean
independent relative overhead = C2 mean total CPU - common reference mean
combined interaction = C3 mean total CPU - (C1 mean + C2 mean - common reference mean)
```

Report estimates as percentage-point differences, preserve negative values, and show both C3
collector results when they disagree. Direct owned-instrumentation CPU is also reported separately
from relative overhead. Differences may include background changes or frequency effects; no exact
causal cost is asserted from subtraction alone. The fixed sequence is not randomized, so order
effects remain a threat even with repeats.

## Gate and Selection

Capture gates require at least 59 samples spanning >=300 seconds, monotonically increasing UTC
timestamps, maximum gap <=15 seconds, zero invalid counters, valid attribution and contention,
unchanged durable/transport state, unchanged service identities/configuration/restarts, stable
power plan and no sleep/resume event. Any performance-limit flag or limit below 100% stops for
review; low idle frequency alone is not labeled thermal throttling. Counter timestamps from
typeperf use the local OS timestamp converted to UTC, with parsing verified in unit tests.

Two isolated valid captures are required for a candidate. The repeatability screen requires total
and unrelated mean CPU differences <=2 percentage points. That tolerance is declared in advance;
it does not change the existing 10%/20% contention thresholds. Lowest total CPU alone never selects
a collector. Coverage, attribution, overhead repeatability, frequency and cross-collector agreement
must also be reviewed. The output is BLOCKED on any failed capture or NEEDS_REVIEW after all captures;
canonicalCollector remains null until evidence is reviewed. Neither status authorizes automatic P1.

## P1 After Calibration

P1 W1 and W2 are NOT run by this launcher. After selecting a canonical collector, integrate only
that collector into the P1 runner, align samples to the exact arrival window, and preserve raw
total CPU/instrumentation/unrelated values. Rerun instrumentation regression tests before launch.
For each workload: an excluded two-minute warm-up, then three independent >=500-job measurements
at 1 job/s with worker concurrency 1. Reconcile all identities, attempts, outbox, leases and transport;
for W2 also completed effects, counter changes and unexpected deduplication hits. No relay change.

The P1 report must pair publication and first-start timestamps by job to test the polling-floor
hypothesis. Report correlation, end-to-end minus publication distribution, proximity to the 500 ms
relay interval, per-run clock uncertainty, minute-window p95/p99, min/median/max and coefficient
of variation across three valid repetitions. Drain is an observation bound, reported at the
polling/detection granularity, not millisecond precision. No P1 baseline if fewer than three runs
are valid. No P2, maximum/sustainable throughput or resume claim follows from calibration.

## Evidence and Failure Handling

results/ remains ignored. Each capture contains environment/configuration, raw and normalized host
telemetry, Docker stats, initial/final datastore and application snapshots, state checks, validity,
manifest and checksums. A CALIBRATION-REVIEW directory links every executed reference/capture and
contains overhead/repeatability estimates. No raw job payload or idempotency key is exported.
The existing audit runs BEFORE creating new result directories; unsealed prior runs stop execution.
The qualification lock also prevents simultaneous qualification/calibration. Abrupt interruption
may leave a lock or unsealed evidence; inspect manually, never delete evidence to make the audit pass.
All evidence is local unless an external archive locator is explicitly recorded.