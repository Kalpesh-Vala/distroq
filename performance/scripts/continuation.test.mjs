import test from 'node:test';
import assert from 'node:assert/strict';
import { continueMissingRepeats, assertRepeatAvailable, assertPreconditions, assertManifestHash, EXIT } from './continuation-policy.mjs';
import { atomicJson } from './continue-calibration.mjs';
import { mkdtemp, readFile, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

test('atomic progress updates leave one complete state document', async () => {
  const directory=await mkdtemp(path.join(tmpdir(),'distroq-continuation-test-'));
  try {
    const filename=path.join(directory,'state.json');
    await atomicJson(filename,{phase:'C1-repeat'});
    await atomicJson(filename,{phase:'C2-repeat',completed:'C1-repeat'});
    assert.deepEqual(JSON.parse(await readFile(filename,'utf8')),{phase:'C2-repeat',completed:'C1-repeat'});
    assert.deepEqual(await readdir(directory),['state.json']);
  } finally {await rm(directory,{recursive:true,force:true});}
});

const state = () => ({ repeats: {}, references: [], failedReferences: { 'C1-repeat': 0, 'C2-repeat': 0 },
  maximumConsecutiveFailedReferences: 3, cooldownSeconds: 60, cooldownUntilMs: null, inFlight: null });
function mocks(log, overrides = {}) {
  return { persist: async () => { log.push('persist'); }, checkInterrupted() {}, now: () => 100,
    cooldown: async () => { log.push('cooldown'); },
    reference: async phase => { log.push(`reference:${phase}`); return { runId: `reference-${phase}`, status: 'VALID' }; },
    capture: async phase => { log.push(`capture:${phase}`); return { runId: phase, status: 'VALID' }; }, ...overrides };
}
test('continuation executes only missing repeats in order', async () => {
  const current = state(), log = [];
  await continueMissingRepeats(current, mocks(log));
  assert.deepEqual(log.filter(entry => entry.startsWith('capture:')), ['capture:C1-repeat', 'capture:C2-repeat']);
  assert.equal(current.nextRequiredPhase, 'ANALYSIS');
});
test('resume after C1 completion never repeats C1', async () => {
  const current = state(), log = []; current.repeats['C1-repeat'] = { runId: 'old-repeat', status: 'VALID' };
  await continueMissingRepeats(current, mocks(log));
  assert.deepEqual(log.filter(entry => entry.startsWith('capture:')), ['capture:C2-repeat']);
});
test('explicit duplicate phases and original captures are refused', () => {
  const current = state(); current.repeats['C1-repeat'] = { status: 'VALID' }; current.repeats['C2-repeat'] = { status: 'VALID' };
  for (const phase of ['C1-repeat', 'C2-repeat', 'C1', 'C2', 'C3']) assert.throws(() => assertRepeatAvailable(current, phase), { code: EXIT.ALREADY_RUN });
});
test('three failed references preserve all attempts and only two cooldowns', async () => {
  const current = state(), log = [];
  await assert.rejects(continueMissingRepeats(current, mocks(log, { reference: async () => ({ status: 'INVALID', runId: String(current.references.length) }) })), { code: EXIT.C1_REFERENCES });
  assert.equal(current.references.length, 3);
  assert.equal(log.filter(entry => entry === 'cooldown').length, 2);
  assert.equal(log.some(entry => entry.startsWith('capture:')), false);
});
test('failed repeat and interrupted reservation refuse automatic retries', async () => {
  const current = state(), log = [];
  await assert.rejects(continueMissingRepeats(current, mocks(log, { capture: async () => ({ status: 'INVALID', runId: 'bad-repeat' }) })), { code: EXIT.C1_INVALID });
  await assert.rejects(continueMissingRepeats(current, mocks(log)), { code: EXIT.ALREADY_RUN });
  const interrupted = state(); interrupted.inFlight = { runId: 'reserved' };
  await assert.rejects(continueMissingRepeats(interrupted, mocks(log)), { code: EXIT.INTERRUPTION });
});
test('interruption at a phase boundary preserves completed phase and starts nothing else', async () => {
  const current = state(), log = [];
  const actions = mocks(log, { checkInterrupted() { if (current.repeats['C1-repeat']) throw Object.assign(new Error('interrupted'), { code: EXIT.INTERRUPTION }); } });
  await assert.rejects(continueMissingRepeats(current, actions), { code: EXIT.INTERRUPTION });
  assert.equal(current.repeats['C1-repeat'].status, 'VALID');
  assert.equal(current.repeats['C2-repeat'], undefined);
});
test('changed manifests, stale lock, dirty app and wrong commit fail closed', () => {
  assert.throws(() => assertManifestHash('changed', 'expected'), { code: EXIT.EVIDENCE });
  const good = { expectedCommit: 'same', currentCommit: 'same', applicationDirty: false, toolingDirty: false, lockExists: false };
  assert.doesNotThrow(() => assertPreconditions(good));
  assert.throws(() => assertPreconditions({ ...good, lockExists: true }), { code: EXIT.LOCK });
  assert.throws(() => assertPreconditions({ ...good, applicationDirty: true }), { code: EXIT.APPLICATION });
  assert.throws(() => assertPreconditions({ ...good, currentCommit: 'other' }), { code: EXIT.TOOLING });
});
test('three failures before C2 do not invalidate or rerun completed C1', async () => {
  const current=state(),log=[];current.repeats['C1-repeat']={runId:'done',status:'VALID'};
  await assert.rejects(continueMissingRepeats(current,mocks(log,{reference:async()=>({status:'INVALID',runId:String(current.references.length)})})),{code:EXIT.C2_REFERENCES});
  assert.equal(current.repeats['C1-repeat'].runId,'done');
  assert.equal(current.references.length,3);
  assert.equal(log.some(entry=>entry.startsWith('capture:')),false);
});