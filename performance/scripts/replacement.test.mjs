import test from 'node:test';
import assert from 'node:assert/strict';
import {authorizeReplacement, assertRepeatAvailable, continueMissingRepeats, classificationCorrection, failureAuthorization, EXIT} from './continuation-policy.mjs';
import {evaluatedRepeat} from './continue-calibration.mjs';

test('valid capture outside repeatability limits is not a valid replacement',()=>{
  const summary={valid:true,hostCpu:{mean:4,p95:7,p99:9},maximumGapSeconds:5};
  const result=evaluatedRepeat({runId:'new',status:'VALID',reasons:[],runner:{...summary,hostCpu:{mean:4,p95:9.00001,p99:10}}},'C1-repeat',{C1:{runner:summary}});
  assert.equal(result.captureStatus,'VALID');
  assert.equal(result.status,'INVALID');
  assert.equal(result.repeatability.eligible,false);
  assert.equal(failureAuthorization(result),'P1_BLOCKED_NO_REPEATABLE_COLLECTOR');
});

const authorization={invalidC1RepeatRunId:'invalid-C1',previousToolingCommit:'old'};
const original=()=>({toolingCommit:'old',repeats:{'C1-repeat':{runId:'invalid-C1',status:'INVALID'}},inFlight:null,
  maximumConsecutiveFailedReferences:3,cooldownSeconds:60,references:[]});
test('explicit replacement preserves invalid history and cannot be authorized twice',()=>{
  const old=original(),next=authorizeReplacement(old,authorization);
  assert.equal(old.repeats['C1-repeat'].status,'INVALID');
  assert.equal(next.priorC1Attempts[0].runId,'invalid-C1');
  assert.equal(next.repeats['C1-repeat'],undefined);
  assert.throws(()=>authorizeReplacement(next,authorization),{code:EXIT.ALREADY_RUN});
  assert.throws(()=>authorizeReplacement(old,null),{code:EXIT.ALREADY_RUN});
  next.replacementAttempted=true;
  assert.throws(()=>assertRepeatAvailable(next,'C1-repeat'),{code:EXIT.ALREADY_RUN});
});
test('invalid authorized replacement prevents C2 and classifies actual contention',async()=>{
  const state=authorizeReplacement(original(),authorization);const captures=[];
  await assert.rejects(continueMissingRepeats(state,{persist:async()=>{},checkInterrupted(){},now:()=>0,cooldown:async()=>{},
    reference:async()=>({runId:'fresh',status:'VALID'}),capture:async phase=>{
      captures.push(phase);return {runId:'replacement',status:'INVALID',reasons:['ACTUAL_CONTENTION_FAILURE']};
    }}),{code:EXIT.C1_INVALID});
  assert.deepEqual(captures,['C1-repeat']);
  assert.equal(state.replacementAttempted,true);
  assert.equal(state.finalAuthorization,'P1_BLOCKED_UNSTABLE_HOST');
  await assert.rejects(continueMissingRepeats(state,{}),{code:EXIT.ALREADY_RUN});
});
test('repeatability failure is separate from telemetry failure',()=>{
  assert.equal(failureAuthorization({repeatability:{eligible:false},reasons:[]}), 'P1_BLOCKED_NO_REPEATABLE_COLLECTOR');
  assert.equal(failureAuthorization({reasons:['HOST_TELEMETRY_FAILURE']}),'P1_BLOCKED_INCOMPLETE_TELEMETRY');
});
test('superseding correction requires complete telemetry and authoritative contention evidence',()=>{
  const failed={runId:'bad',status:'INVALID',runner:{invalidRequiredCounterCount:0,hostCpu:{mean:11.295,p95:28.614},samples:62},reasons:['ACTUAL_CONTENTION_FAILURE']};
  const old={p1Authorization:'P1_BLOCKED_INCOMPLETE_TELEMETRY'};
  const correction=classificationCorrection('review',failed,old,'utc','commit');
  assert.equal(correction.correctedAuthorization,'P1_BLOCKED_UNSTABLE_HOST');
  assert.equal(correction.historicalEvidenceRewritten,false);
  assert.equal(old.p1Authorization,'P1_BLOCKED_INCOMPLETE_TELEMETRY');
  assert.throws(()=>classificationCorrection('review',{...failed,runner:{...failed.runner,invalidRequiredCounterCount:1}},old,'utc','commit'),{code:EXIT.EVIDENCE});
});
test('successful replacement reaches exactly one C2 and does not modify invalid history',async()=>{
  const state=authorizeReplacement(original(),authorization),calls=[];
  await continueMissingRepeats(state,{persist:async()=>{},checkInterrupted(){},now:()=>0,cooldown:async()=>{},
    reference:async phase=>({status:'VALID',runId:'ref-'+phase}),
    capture:async phase=>{calls.push(phase);return {status:'VALID',runId:'new-'+phase};}});
  assert.deepEqual(calls,['C1-repeat','C2-repeat']);
  assert.equal(state.replacementAttempted,true);
  assert.equal(state.c2RepeatAttempted,true);
  assert.equal(state.priorC1Attempts[0].status,'INVALID');
  assert.throws(()=>assertRepeatAvailable(state,'C1-repeat'),{code:EXIT.ALREADY_RUN});
  assert.throws(()=>assertRepeatAvailable(state,'C2-repeat'),{code:EXIT.ALREADY_RUN});
});