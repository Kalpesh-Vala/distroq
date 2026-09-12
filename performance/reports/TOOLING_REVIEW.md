# Tooling Preservation Review

Scope: preserve the existing benchmark harness and prepare standalone qualification, not execute
P1/P2. Baseline application commit remains 4f31ada742846859cf67d76e72b8813f90877ac2.

## Findings and Disposition

| Finding | Disposition |
|---|---|
| Original ignore rules covered only private/ and node_modules/ | Added results/, state/, tmp/, backups/, environment files, logs, PID/temp files, dumps/RDB/AOF. Nothing deleted. |
| Capacity runner used obsolete per-process raw CPU threshold | Replaced with tested aggregate whole-machine mean/spike rules; excludes explicitly attested harness/VM categories. |
| Historical idle gate had collapsed PID mapping and counter/sorting errors | Preserved as historical tooling; do not use for unattended launches. New collector uses PID-bearing CIM data. |
| Historical derived report contains superseded per-PID analysis | Immutable reports retained. Authoritative later review uses family-level valid counters and VS Code-only failure proof. |
| No unattended sequence or gate coupling | Added qualification-only idle -> warm-up -> measured workflow with mocked sequencing tests and independent host verdicts. |
| Drain timing included post-drain analysis time | Corrected future runner summaries; old files remain unchanged. |
| Existing manifests contain relative paths/hashes but no external archive locator | Compact EVIDENCE_INDEX added; externalArchiveUri remains null. No backup/upload is claimed. |
| Fault scripts are only refusal-tested | Retained as explicitly unvalidated scaffolding, never imported or invoked by qualification. |

## Inventory and Sensitive Content

Before this task's additions, 25 tooling files occupied 114,544 bytes; five Markdown documents
39,838 bytes; 221 raw evidence files 9,753,381 bytes; 31 dependency files 1,438,489 bytes.
No private/sensitive directory was present. These are historical inventory counts, not final staged
counts. The commit audit prints current totals and writes a per-path classification under ignored
state/audit-*/inventory.json. It classifies every path, including dependencies and previous audits.

The largest raw files are the five-minute idle telemetry (2,385,819 bytes) and the invalid warm-up's
application metrics (2,291,313 bytes). Both remain ignored. The full largest-ten list is in the audit.

The audit reads all files and checks three generated credentials, 187 currently known submission
keys, JSON payload/key fields, and common staged credential patterns. Initial scan found no hits;
the staged scan must pass immediately before committing. These checks are scoped, not a claim of
perfect secret detection. Raw job payloads are never staged; workload source necessarily contains
synthetic payload construction. Dependency strings and synthetic fixture IDs are not credentials.

No script has a machine-specific absolute filesystem path. Documentation shows the user's local
workspace path as an example. Several historical scripts contain fixed run IDs/project IDs:
Test-IdleGate, Report-IdleGate, Validate-Artifacts and Test-Safety. The audit intentionally scans
the preserved project's credentials and evidence. These bindings are explicit limitations, not
portable stack discovery. Start-Qualification takes the project as an argument; the current audit
requires the preserved DistroQ project to remain available, even if another project is supplied.

## Validation Boundary

Unit tests cover quantiles, missing accepted identities, retry/effect mismatch, host thresholds and
qualification sequencing. PowerShell/Node syntax and a check-only live preflight are required.
Preparation validation passed: nine unit tests, all benchmark PowerShell and Node syntax checks,
and the check-only launcher against the unchanged healthy stack. No editor diagnostics remained.
No new host capture, warm-up, qualification, P1 or P2 workload is run during preparation. End-to-end
runtime behavior of the new standalone collector remains unverified until the user's first launch.
Failing/inconclusive first launches must be retained and investigated without relaxing thresholds.

No throughput/latency/recovery claim or optimization recommendation follows from this tooling
review. Existing P0 is still a correctness smoke only; all performance metrics remain unsupported.

## Git Policy

Commit reusable source, pinned tooling manifest/lockfile, workload/safety/analysis tests, plans,
documentation, small reviewed reports and the selected compact evidence index. Do not stage any
results/, node_modules/, state/, private/ or backup path. Verify staged blob content, sizes and
allowlisted categories, not only the working-tree files. No staged file may exceed 256 KiB without
separate review. Show the exact staged list before committing. No merge, tag or publication.