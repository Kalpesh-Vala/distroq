export const EXIT = Object.freeze({
  OK: 0, EVIDENCE: 10, APPLICATION: 11, CONTAINERS: 12, C1_REFERENCES: 20,
  C1_INVALID: 21, C2_REFERENCES: 22, C2_INVALID: 23, NO_COLLECTOR: 24,
  ARTIFACT: 30, INTERRUPTION: 40, LOCK: 41, TOOLING: 42, ALREADY_RUN: 43,
});

export class ContinuationError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

export function assertPreconditions({ expectedCommit, currentCommit, applicationDirty, toolingDirty, lockExists }) {
  if (lockExists) throw new ContinuationError(EXIT.LOCK, 'Calibration lock exists; manual inspection required');
  if (applicationDirty) throw new ContinuationError(EXIT.APPLICATION, 'Application tree differs from baseline');
  if (toolingDirty || expectedCommit !== currentCommit) throw new ContinuationError(EXIT.TOOLING, 'Tooling commit or working tree differs from pinned version');
}

export function assertManifestHash(actual, expected) {
  if (actual !== expected) throw new ContinuationError(EXIT.EVIDENCE, 'Preserved manifest missing or changed');
}

export function assertRepeatAvailable(state, phase) {
  if (!['C1-repeat', 'C2-repeat'].includes(phase)) throw new ContinuationError(EXIT.ALREADY_RUN, 'Only the two missing repeats may execute');
  if (state.repeats[phase]) throw new ContinuationError(EXIT.ALREADY_RUN, `${phase} already has a reserved or completed run; refusing to rerun`);
  if (state.replacementAuthorized && phase === 'C1-repeat' && state.replacementAttempted)
    throw new ContinuationError(EXIT.ALREADY_RUN, 'Authorized replacement C1 has already been attempted');
  if (state.replacementAuthorized && phase === 'C2-repeat' && state.c2RepeatAttempted)
    throw new ContinuationError(EXIT.ALREADY_RUN, 'C2 repeat has already been attempted');
}

export function authorizeReplacement(state, authorization) {
  if (!authorization || state.replacementAuthorized || state.replacementAttempted || state.inFlight || state.repeats['C2-repeat'])
    throw new ContinuationError(EXIT.ALREADY_RUN, 'Replacement authorization cannot be reused or applied to this state');
  const previous = state.repeats['C1-repeat'];
  if (previous?.runId !== authorization.invalidC1RepeatRunId || previous.status !== 'INVALID'
      || state.toolingCommit !== authorization.previousToolingCommit)
    throw new ContinuationError(EXIT.EVIDENCE, 'Replacement does not match the explicitly authorized invalid attempt');
  const next = structuredClone(state);
  next.priorC1Attempts = [...(next.priorC1Attempts || []), previous];
  delete next.repeats['C1-repeat'];
  Object.assign(next, {invalidC1RepeatRunId: previous.runId, replacementAuthorized: true,
    replacementAttempted: false, replacementRunId: null, replacementVerdict: 'PENDING',
    c2RepeatAttempted: false, c2RepeatRunId: null, finalAuthorization: 'P1_BLOCKED_UNSTABLE_HOST',
    nextRequiredPhase: 'C1-repeat', cooldownUntilMs: null,
    failedReferences: {'C1-repeat': 0, 'C2-repeat': 0}, finalCalibrationVerdict: 'PENDING'});
  return next;
}

export function failureAuthorization(result) {
  const reasons = (result?.reasons || []).join(' ');
  if (/ACTUAL_CONTENTION_FAILURE|Host total contention/i.test(reasons)) return 'P1_BLOCKED_UNSTABLE_HOST';
  if (result?.repeatability && !result.repeatability.eligible) return 'P1_BLOCKED_NO_REPEATABLE_COLLECTOR';
  return 'P1_BLOCKED_INCOMPLETE_TELEMETRY';
}

export function classificationCorrection(reviewId, invalidRun, oldReview, timestamp, toolingCommit) {
  if (invalidRun.status !== 'INVALID' || invalidRun.runner?.invalidRequiredCounterCount !== 0
      || invalidRun.runner.hostCpu.mean <= 10 || !invalidRun.reasons.some(reason => reason.includes('ACTUAL_CONTENTION_FAILURE')))
    throw new ContinuationError(EXIT.EVIDENCE, 'Evidence does not support unstable-host correction');
  return {originalContinuationReviewId: reviewId, originalInvalidC1RepeatId: invalidRun.runId,
    originalAuthorization: oldReview.p1Authorization, correctedAuthorization: 'P1_BLOCKED_UNSTABLE_HOST',
    correctionReason: 'Required telemetry was complete. The run failed the authoritative host-total mean CPU gate. Therefore the correct category is unstable host, not incomplete telemetry.',
    evidence: {requiredCounterFailures: invalidRun.runner.invalidRequiredCounterCount, hostMeanPercent: invalidRun.runner.hostCpu.mean,
      hostP95Percent: invalidRun.runner.hostCpu.p95, samples: invalidRun.runner.samples, reasons: invalidRun.reasons},
    timestamp, toolingCommit, historicalEvidenceRewritten: false};
}

export async function continueMissingRepeats(state, actions) {
  if (state.inFlight) throw new ContinuationError(EXIT.INTERRUPTION, 'Interrupted phase is reserved; reconcile preserved artifacts before resuming');
  for (const phase of ['C1-repeat', 'C2-repeat']) {
    const existing = state.repeats[phase];
    if (existing) {
      if (existing.status !== 'VALID') throw new ContinuationError(EXIT.ALREADY_RUN, `${phase} already attempted and requires review`);
      continue;
    }
    assertRepeatAvailable(state, phase);
    state.nextRequiredPhase = phase;
    await actions.persist(state);
    let reference = null;
    while (state.failedReferences[phase] < state.maximumConsecutiveFailedReferences) {
      actions.checkInterrupted();
      if (state.cooldownUntilMs) {
        await actions.cooldown(state.cooldownUntilMs);
        state.cooldownUntilMs = null;
        await actions.persist(state);
      }
      actions.checkInterrupted();
      const result = await actions.reference(phase);
      state.references.push({ ...result, phase });
      state.inFlight = null;
      if (result.status === 'VALID') {
        reference = result;
        state.failedReferences[phase] = 0;
        await actions.persist(state);
        break;
      }
      state.failedReferences[phase]++;
      state.cooldownUntilMs = actions.now() + state.cooldownSeconds * 1000;
      await actions.persist(state);
      if (result.fatal) throw new ContinuationError(result.exitCode || EXIT.ARTIFACT, result.reason || 'Fatal reference validation failure');
    }
    if (!reference) throw new ContinuationError(phase === 'C1-repeat' ? EXIT.C1_REFERENCES : EXIT.C2_REFERENCES,
      `Three consecutive failed settling references before ${phase}`);
    actions.checkInterrupted();
    assertRepeatAvailable(state, phase);
    const repeat = await actions.capture(phase, reference);
    state.repeats[phase] = repeat;
    if (state.replacementAuthorized) {
      if (phase === 'C1-repeat') { state.replacementAttempted = true; state.replacementRunId = repeat.runId; state.replacementVerdict = repeat.status; }
      else { state.c2RepeatAttempted = true; state.c2RepeatRunId = repeat.runId; }
      if (repeat.status !== 'VALID') state.finalAuthorization = failureAuthorization(repeat);
    }
    state.inFlight = null;
    await actions.persist(state);
    if (repeat.status !== 'VALID') throw new ContinuationError(phase === 'C1-repeat' ? EXIT.C1_INVALID : EXIT.C2_INVALID,
      `${phase} did not pass; do not rerun it automatically`);
  }
  state.nextRequiredPhase = 'ANALYSIS';
  await actions.persist(state);
}

export function authorizationForExit(code) {
  if (code === EXIT.OK) return 'P1_AUTHORIZED';
  if ([EXIT.C1_REFERENCES, EXIT.C2_REFERENCES].includes(code)) return 'P1_BLOCKED_UNSTABLE_HOST';
  if (code === EXIT.NO_COLLECTOR) return 'P1_BLOCKED_NO_REPEATABLE_COLLECTOR';
  if ([EXIT.C1_INVALID, EXIT.C2_INVALID, EXIT.INTERRUPTION].includes(code)) return 'P1_BLOCKED_INCOMPLETE_TELEMETRY';
  return 'P1_BLOCKED_EVIDENCE_FAILURE';
}