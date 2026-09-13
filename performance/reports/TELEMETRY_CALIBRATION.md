# Telemetry Calibration Status

Status: initial reference and C1 executed; C1 INVALID. Canonical collector: NOT SELECTED.
P1 W1/W2: BLOCKED pending new reviewed calibration captures.
The prior qualification remains valid under its recorded checks, with a host-telemetry caveat.
No historical evidence is reclassified or overwritten by this status document.

| Capture | Total CPU | Instrumentation CPU | Unrelated CPU | Invalid counters | Verdict |
|---|---|---|---|---|---|
| C1 runner only (historical) | Mean 5.939%, p95 8.478%, max 12.496% | Unqualified | Unqualified | 21 unavailable process observations | INVALID; preserved |
| C2 independent only | TBD | TBD | TBD | TBD | Not run |
| C3 simultaneous | TBD | TBD | TBD | TBD | Not run |
| C1 repeat | TBD | TBD | TBD | TBD | Not run |
| C2 repeat | TBD | TBD | TBD | TBD | Not run |

Runner/independent/interaction overhead estimates: TBD. No exact zero-observer baseline exists;
the standalone workflow records lightweight reference captures and labels relative estimates.
Do not select the lowest reported utilization before checking attribution, timestamps, repeatability
and invalid counters. WMI and unattributed VM/backend CPU are not silently excluded.

Historical C1: `20260912T150730387Z-CAL-C1-04aa0d40`, 62 samples over 307.288 seconds, maximum gap
5.058 seconds. New parser analysis finds zero missing required counters and 21 unavailable
diagnostics (smartscreen 20, svchost one). All 31 C1 manifest entries verified. Its original INVALID
verdict is unchanged; absent tracked lifetime records prevent retrospective qualification.

The preceding qualification showed 3.767% runner competing CPU versus 8.938% independent full-window
CPU and 9.963% independent CPU aligned to arrivals. WMI contributed 7.470% during arrivals according
to the independent collector. These observations motivate calibration but do not establish which
collector is accurate or how much overhead it causes.

Preparation changes are benchmark-only and uncommitted. New calculations have unit tests; no-load
preflight must pass. The actual standalone campaign remains necessary: an active VS Code/Copilot
session was previously a measured source of contention and must not be used for these captures.

P1 repetitions/medians, effect comparison, percentile tables, polling-floor correlation and
resume-safe latency statements remain unavailable. Polling-floor hypothesis: INCONCLUSIVE until
the paired job-level analysis exists across all P1 repetitions. No P2 is authorized by this task.

See ../CALIBRATION.md for exact command, classification rules, reference assumptions, selection
criteria, evidence layout and the required post-calibration P1 experiment.