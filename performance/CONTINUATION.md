# Resume Only the Missing Calibration Repeats

This launcher reuses the immutable campaign named in environments/continuation.json. It cannot
execute original C1, C2 or C3 labels and never starts P1/P2. It is prepared and unit/static-tested;
the first standalone execution remains runtime validation, not guaranteed success.

Preparation checks: 31 tooling tests passed, all PowerShell/Node syntax checks passed, descriptor
hashes/statuses verified, and the read-only live environment check confirmed unchanged no-job state
and expected benchmark/developer ownership and lifecycles. No capture was executed during preparation.

## Launch After Tooling Review and Commit

Keep the existing app/PostgreSQL/Redis and developer containers running. Connect AC; manually
confirm AC sleep and hibernation are disabled. Close dashboard/browser clients and unnecessary
applications, including VS Code. Avoid interacting with the machine during capture. Verify no
Windows update, scheduled job or unrelated load generator is visibly busy. No script kills these
processes or suppresses OS protections. Idle/reference total-CPU gates still apply.

From standalone PowerShell:

```powershell
Set-Location D:\github\distroq; .\performance\scripts\Continue-TelemetryCalibration.ps1 -Project distroq-bench-20260912123428-f8051a -ExclusiveHostConfirmed
```

To inspect pins and state without captures, use `-CheckOnly` instead of the confirmation switch.
Invoke the PS1 with `-File` in a separate shell if you need to inspect its process exit code from
another script; it deliberately uses `exit` to preserve stable codes. No execution-policy bypass
or elevation is requested. Dependencies are the already-pinned Node package, Docker, Windows
PowerShell 5.1/typeperf/performance counters, powercfg, wevtutil and Git.

Expect roughly 13-20 minutes for two repeats and their references, longer if references require
cooldowns. The operator must reopen VS Code only after the command exits. Full-sequence
Start-TelemetryCalibration is NOT invoked and should not be used to resume this campaign.

## Pins and Progress

The committed descriptor contains original run IDs, expected manifest SHA-256 hashes/statuses,
approved mean/p95/gap limits, three-attempt reference budget and 60-second cooldown. To avoid an
impossible self-referential commit hash, the tooling version is the commit introducing the descriptor,
resolved from Git. HEAD must match it, and all benchmark/application source must be clean. Runtime
state records that full hash plus the descriptor hash. No automatically accepted later commit.

The ignored state/calibration-continuation.json is written via flushed temporary file plus atomic
rename. It records next phase, reserved in-flight run, every reference, failed-reference counts,
completed repeats, configuration fingerprints and final verdict. The ignored qualification.lock
is acquired exclusively. A stale lock is never stolen or automatically removed on startup.

Each new reference/capture is reserved in state BEFORE its directory is created. After completion
its manifest is verified and its outcome committed to state. C1 completion resumes at C2 without
rerunning C1. An interrupted reference resumes with a fresh ID after review; an attempted invalid
or incomplete collector repeat is NOT silently replaced. Unexpected repeat directories not linked
to state cause refusal. A sealed completed in-flight run may be recovered into state after its
checksums verify. A final DONE state refuses duplicate execution.

If a crash leaves an unsealed run or stale lock, inspect it manually first. Do not delete evidence
or the descriptor to bypass refusal. State is an operator-owned progress file, not tamper-proof
storage; prior/current manifests and original pins detect content changes but are not signatures.

## Reference Rules

Every repeat gets its own immediately preceding valid reference. References use the existing
13 total-host samples at five-second intervals. Required coverage is complete, span >=60 seconds,
maximum gap <=15 seconds, host mean <=10%, at most 10% of samples >20%, and absolute first-half
versus second-half mean difference <=2.0000 points. Full precision is used, falling trends count,
and no threshold is relaxed. Power/lifecycle/state and clock-step checks run outside the sample
window; collector configuration is unchanged from the original C1/C2.

Only ordinary settling/contention failures may retry after 60 seconds. Three consecutive failures
stop that phase. Telemetry, power/lifecycle, state or evidence failures are fatal immediately. A
reference's failed outcome is always retained. No retries occur without a new immutable ID.

## Repeatability and Selection

Compare only original C1 vs C1-repeat and original C2 vs C2-repeat. Limits: absolute mean difference
<=2.0000 points; p95 difference <=2.0000 points; both maximum gaps <=15 seconds; both validity
verdicts and required PID/total coverage must pass. Report p50/p95/p99/max, absolute/relative means,
instrumentation/gap differences, family diagnostic deltas and two-observation descriptive CV.
As a conservative background screen, the two paired reference means must also differ by <=2 points
before automatic recommendation; failure never changes either capture's own validity verdict.

Each overhead observation reports its exact reference/capture IDs, mean and p95 differences and
UTC gap. Negative differences mean background variation exceeds the resolvable signal, not negative
observer cost. No pooling of remote references. C3 is retained context, never rerun or used to
algebraically isolate individual costs. WmiPrvSE stays diagnostic/provider work, not presumed owned
instrumentation. Exact unattributed CPU stays unknown; Docker CPU is not subtracted from host total.

If both collectors meet all screens, runner is recommended operationally for timestamped CSV and
avoiding separate CIM polling, not because its harness CPU necessarily equals its full overhead.
If neither meets the screens, no winner is forced. A selected collector yields P1_AUTHORIZED as a
calibration recommendation only; STOP for review and separate P1 integration/launch. No P1 run follows.

## Exit Codes

| Code | Meaning |
|---:|---|
| 0 | Calibration completed with a recommended collector; review before P1 |
| 10 | Preserved evidence hash/status mismatch |
| 11 | Dirty or changed application baseline |
| 12 | Container ownership/lifecycle/configuration/no-job state mismatch |
| 20 | Three failed references before C1-repeat |
| 21 | Invalid C1-repeat |
| 22 | Three failed references before C2-repeat |
| 23 | Invalid C2-repeat |
| 24 | Neither collector qualifies as repeatable under the screens |
| 30 | Artifact/telemetry validation or unclassified operational failure |
| 40 | Interrupted/incomplete execution requiring review |
| 41 | Existing/stale lock |
| 42 | Tooling commit/dirty tree/standalone precondition mismatch |
| 43 | Duplicate, previously attempted, or completed phase refused |

The same code/reason appears in console and final JSON. P1 statuses distinguish authorized,
no-repeatable-collector, unstable-host, incomplete-telemetry and evidence-failure outcomes. Early
preflight refusal writes no new capture; errors during execution produce immutable review evidence.

## Interruption and Evidence

Catchable Ctrl+C/SIGTERM stops ONLY subprocesses launched by this calibration runner. It never stops
the application, datastore containers, Docker Desktop, OS services, editor or unrelated processes.
Incomplete capture output is sealed with INCOMPLETE and a final review is written before the lock
is removed. If sealing itself fails, the lock is retained for manual inspection. Power loss or a
forced OS termination cannot be caught: the reservation and remaining files are the evidence,
and manual incomplete-run recovery is required. Do not claim automatic sealing after power loss.

New ignored results include reference/capture manifests, snapshots, exact tooling commit, state
links and a CONTINUATION-REVIEW directory. It contains verdict.json and a compact evidence-index
candidate. Historical reports/index entries are never overwritten automatically. Promote the new
index into reports/EVIDENCE_INDEX.json only after review, so the pinned tooling tree stays clean
for resumptions. No raw evidence or progress state is committed, merged, uploaded or published.

Future-only proposal: combine half-window difference with CPU slope, p95/standard deviation and
two consecutive stable windows. That methodology is not implemented or applied to this campaign.