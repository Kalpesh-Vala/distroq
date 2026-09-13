# Repeat Continuation: Prospective Policy Review Required

Continuation-only tooling is now implemented in ../CONTINUATION.md with descriptor pins, atomic
progress, retry budgets, stable exits and mocked tests. This report retains the prior methodology
checkpoint below as history; new reference/repeat results remain unmeasured until standalone launch.

No new calibration or workload execution occurred. Review discovered that the existing
repeatability screen lacked a p95 limit. Per the requested stop condition, mean/p95 limits were
defined and unit-tested before running repeats. This is a methodology checkpoint, not completion
of the missing repeatability campaign.

Preserved valid originals:
- C1: 20260912T160755606Z-CAL-C1-46013ec0
- C2: 20260912T161428506Z-CAL-C2-146fcbbc
- C3: 20260912T162124009Z-CAL-C3-e45ff3d7

Preserved invalid reference: 20260912T162713228Z-CAL-REFERENCE-bb86699b. Its half-window absolute
change was 2.026833571428572 percentage points, exceeding the unchanged 2.0000 limit. It remains
excluded from corrected overhead estimates. Historical reports are not rewritten by the correction.

| Requested result | Status |
|---|---|
| New reference attempts | Not run |
| C1 repeat | Not run |
| C2 repeat | Not run |
| Repeatability statistics | Implementation tested; no measured comparison yet |
| Observer overhead | Still confounded by reference/background variation |
| Canonical collector | None selected |
| P1 / P2 | Not run; P1 gate remains closed |

Prospective screen: both mean and p95 absolute differences <=2.0000 percentage points, maximum
sample gap <=15 seconds, no rounding at threshold comparison, plus both captures' existing validity
requirements. Rationale and limitations are in ../CALIBRATION.md. Do not fit new tolerances after
observing repeats. Relative differences and two-observation CV are descriptive, not selection alone.

Existing C1 diagnostic degradation remains RuntimeBroker 61, WmiPrvSE 29, smartscreen 22, svchost 1.
Original C2/C3 had no unavailable diagnostics. These are original observations, not repeat results.
WmiPrvSE remains diagnostic/provider CPU, not presumed harness-owned instrumentation. Its actual
causal contribution cannot be inferred from the independent collector's own process CPU alone.

The corrected original-reference mean is 5.87490282051282%, using only the three valid references
associated with original valid captures. The resulting negative individual relative differences
(-1.0580054495450772 and -0.4292745695915894 points) do not establish negative overhead. Reference
drift makes causal cost inconclusive. Future continuation reporting must show each temporal pairing
and its time gap, not treat this historical pooled estimate as a new measurement.

Next step: review the prospective limits, then prepare a continuation-only standalone runner with
bounded reference retries and cooldown, preserving original C1/C2/C3. Do not launch the current full
calibration sequence, which would repeat already-completed captures. Nothing is staged or committed.