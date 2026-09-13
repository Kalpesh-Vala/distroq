import test from 'node:test';
import assert from 'node:assert/strict';
import { quantiles, slope, reconcile, stability } from './capacity-analysis.mjs';
import { attributedHostCpu, hostContention, idleHostAccounting } from './host-policy.mjs';

test('idle totals survive diagnostic churn without zero filling or subtraction', () => {
  for(const proc of [{pid:20,name:'smartscreen',rawCpuPercent:-1},{pid:-1,name:'smartscreen',rawCpuPercent:3}]){
    const result=idleHostAccounting([{utcMs:100,totalCpuPercent:6,processes:[proc]}],8);
    assert.equal(result.valid,true);
    assert.equal(result.diagnostics.unavailableCount,1);
    assert.equal(result.observations[0].idleContention,6);
    assert.equal(result.observations[0].unattributedCpu,null);
  }
  assert.equal(idleHostAccounting([{utcMs:100,totalCpuPercent:11,processes:[]}],8).valid,false);
  assert.equal(idleHostAccounting([{utcMs:100,totalCpuPercent:null,processes:[]}],8).valid,false);
});
test('tracked identity follows PID lifetime rather than reused instance suffix', () => {
  const owner={pid:123,name:'node',identityVerified:true,startedMs:0,endedMs:200};
  const row={utcMs:100,totalCpuPercent:5,processes:[{pid:123,name:'node#2',rawCpuPercent:8}]};
  assert.equal(idleHostAccounting([row],8,[owner]).valid,true);
  assert.equal(idleHostAccounting([{...row,processes:[]}],8,[owner]).valid,false);
  assert.equal(idleHostAccounting([{...row,processes:[{pid:456,name:'node#2',rawCpuPercent:8}]}],8,[owner]).valid,false);
  assert.equal(idleHostAccounting([{...row,processes:[{pid:123,name:'node#2',rawCpuPercent:-1}]}],8,[owner]).valid,false);
  assert.equal(idleHostAccounting([row],8,[{...owner,identityVerified:false}]).valid,false);
  assert.equal(idleHostAccounting([row],8,[owner,owner]).valid,false);
});

test('calibrated CPU accounting subtracts only owned instrumentation and keeps WMI', () => {
  const result = attributedHostCpu([{ utcMs: 0, totalCpuPercent: 15, processes: [
    { pid: 10, name: 'node', rawCpuPercent: 16 }, { pid: 20, name: 'WmiPrvSE', rawCpuPercent: 24 },
  ] }], 8, { instrumentation: [10] });
  assert.equal(result.means.instrumentationCpuPercent, 2);
  assert.equal(result.means.unrelatedCpuPercent, 13);
  assert.equal(result.means.wmiCpuPercent, 3);
  assert.equal(result.valid, false);
});
test('calibrated CPU attribution rejects duplicates and impossible subtraction', () => {
  const sample = { totalCpuPercent: 1, processes: [{ pid: 10, name: 'typeperf', rawCpuPercent: 16 }] };
  assert.equal(attributedHostCpu([sample], 8, { instrumentation: [10] }).valid, false);
  assert.equal(attributedHostCpu([sample], 8, { instrumentation: [10], generator: [10] }).valid, false);
});
test('unattributed VM CPU remains in calibrated contention even with familiar process names', () => {
  const sample = { totalCpuPercent: 12, processes: [{ pid: 20, name: 'vmmemWSL', rawCpuPercent: 96 }] };
  assert.equal(attributedHostCpu([sample], 8).means.unrelatedCpuPercent, 12);
  assert.equal(attributedHostCpu([sample], 8).valid, false);
});
import { prepareCampaign, qualificationFlow } from './qualification.mjs';
import { overheadEstimates, candidateRepeatability, summarizeCollector, validReferenceBaseline } from './calibration-analysis.mjs';

test('overhead reference excludes failed and unused settling windows without dropping evidence', () => {
  const references=[{runId:'good',status:'VALID',hostCpu:{mean:6}},
    {runId:'failed',status:'INVALID',hostCpu:{mean:2}},
    {runId:'unused',status:'VALID',hostCpu:{mean:1}}];
  const result=validReferenceBaseline(references,[{status:'VALID',referenceRun:'good'},{status:'INVALID',referenceRun:'failed'}]);
  assert.equal(result.mean,6);
  assert.deepEqual(result.includedRunIds,['good']);
  assert.deepEqual(result.excludedRunIds,['failed','unused']);
  assert.equal(references.length,3);
  assert.equal(validReferenceBaseline(references,[]).mean,null);
});
import { parseRunnerCsv, transportIdentity } from './calibrate-telemetry.mjs';

test('calibration CSV matches exact process instances to their PID counter', () => {
  const headers=['(PDH-CSV 4.0)','\\\\host\\Processor(_Total)\\% Processor Time',
    '\\\\host\\Process(node)\\% Processor Time','\\\\host\\Process(node#1)\\% Processor Time',
    '\\\\host\\Process(node)\\ID Process','\\\\host\\Process(node#1)\\ID Process'];
  const csv=[headers,['09/12/2026 14:00:00.000','5','8','16','100','200']].map(row=>row.map(value=>`"${value}"`).join(',')).join('\n');
  const result=parseRunnerCsv(csv);
  assert.equal(result.invalidCount,0);
  assert.deepEqual(result.samples[0].processes.map(proc=>[proc.pid,proc.rawCpuPercent]),[[100,8],[200,16]]);
  assert.equal(parseRunnerCsv(csv.replace('"200"','""')).invalidCount,1);
  const negative=parseRunnerCsv(csv.replace('"16"','"-1"'));
  assert.equal(negative.invalidCount,1);
  assert.equal(negative.invalidDetails[0].cpuRaw,'-1');
  assert.equal(negative.samples[0].processes.some(proc=>proc.rawCpuPercent<0),false);
  const missingPair=parseRunnerCsv(csv.replace('"16"','"-1"').replace('"200"','"-1"'));
  assert.equal(missingPair.invalidCount,1);
  assert.equal(missingPair.invalidDetails[0].pidRaw,'-1');
  assert.equal(missingPair.samples[0].unavailableProcesses[0].rawCpuPercent,null);
  assert.equal(result.hostInvalidCount,1);
  assert.equal(parseRunnerCsv(csv.replace('"5"','""')).samples[0].totalCpuPercent.toString(),'NaN');
});
test('idle transport audit ignores consumer idle clocks but catches Stream growth', () => {
  const stream={length:2,groups:['[{"name":"workers","lag":0,"pending":0,"last-delivered-id":"1-0"}]'],pending:['[0,null,null,null]'],consumers:['idle=100']};
  const before={delayed:0,scheduled:0,high:stream,normal:stream,low:stream};
  const after={...before,normal:{...stream,consumers:['idle=200']}};
  assert.deepEqual(transportIdentity(before),transportIdentity(after));
  after.normal.length=3;
  assert.notDeepEqual(transportIdentity(before),transportIdentity(after));
});

test('observer overhead preserves negative estimates and separates interaction', () => {
  const result=overheadEstimates(4,6,9,2);
  assert.equal(result.runnerPercentagePoints,2);
  assert.equal(result.independentPercentagePoints,4);
  assert.equal(result.interactionPercentagePoints,1);
  assert.equal(overheadEstimates(1,6,9,2).runnerPercentagePoints,-1);
  assert.equal(overheadEstimates(null,6,9,2).status,'UNMEASURED');
});
test('calibration rejects missing repeats and counter errors', () => {
  assert.equal(candidateRepeatability({valid:true},null).eligible,false);
  const samples=Array.from({length:61},(_,index)=>({utcMs:index*5000,totalCpuPercent:2,
    processes:[{pid:123,name:'example',rawCpuPercent:8}],frequencyMHz:[2500]}));
  assert.equal(summarizeCollector(samples,8,{},0).valid,true);
  assert.equal(summarizeCollector(samples,8,{},1).valid,false);
  assert.equal(summarizeCollector(samples.slice(1),8,{},0).valid,false);
  assert.equal(summarizeCollector(samples.map(row=>({...row,frequencyMHz:[]})),8,{},0).valid,false);
  assert.equal(summarizeCollector(samples.map(row=>({...row,processes:[]})),8,{},0).valid,true);
});

test('campaign directory is created only after preserved evidence audit completes', async () => {
  const calls = [];
  await prepareCampaign({
    audit: async () => { calls.push('audit-start'); await Promise.resolve(); calls.push('audit-end'); },
    create: async () => { calls.push('create'); },
  });
  assert.deepEqual(calls, ['audit-start', 'audit-end', 'create']);
});

test('unsealed prior evidence blocks creation of another campaign', async () => {
  let created = false;
  await assert.rejects(prepareCampaign({
    audit: async () => { throw new Error('Unsealed evidence directory: prior-run'); },
    create: async () => { created = true; },
  }), /Unsealed evidence directory/);
  assert.equal(created, false);
});

test('qualification never starts load after failed idle gate', async () => {
  const result = await qualificationFlow({ idle: async () => ({ valid: false }),
    warmup: async () => { throw new Error('must not start'); } });
  assert.equal(result.stoppedAt, 'idle');
});
test('qualification stops at invalid warm-up and never enters P1/P2', async () => {
  const result = await qualificationFlow({ idle: async () => ({ valid: true }),
    warmup: async () => ({ valid: false }), measured: async () => { throw new Error('must not start'); } });
  assert.equal(result.stoppedAt, 'warmup');
});
test('qualification references its excluded warm-up', async () => {
  const result = await qualificationFlow({ idle: async () => ({ valid: true }),
    warmup: async () => ({ valid: true, runId: 'warmup' }),
    measured: async id => { assert.equal(id, 'warmup'); return { valid: true }; } });
  assert.equal(result.status, 'VALID');
});

test('host CPU uses whole-machine mean and spike rules', () => {
  const sample = cpuPercent => ({ processes: [{ name: 'Process(Code)', cpuPercent }] });
  assert.equal(hostContention([sample(80), sample(80)], 8).valid, true);
  assert.equal(hostContention([sample(81), sample(81)], 8).valid, false);
  assert.equal(hostContention([sample(161), sample(0), sample(0), sample(0), sample(0)], 8).valid, false);
  assert.equal(hostContention([sample(NaN)], 8).valid, false);
});

test('nearest rank retains exact samples and empty distributions', () => {
  assert.deepEqual(quantiles([3, 1, 2]), { count: 3, p50: 2, p95: 3, p99: 3, max: 3 });
  assert.equal(quantiles([]).p95, null);
});
test('positive backlog slope and growing tails fail sustainability', () => {
  assert.equal(slope([[0, 1], [5, 11], [10, 21]]), 2);
  assert.equal(stability([{ utcMs: 0, backlog: 0 }, { utcMs: 5000, backlog: 10 }, { utcMs: 10000, backlog: 20 }],
    { windows: [], acceptedRate: 2, completionRate: 2 }, 2).stable, false);
});
test('missing accepted identity cannot reconcile', () => {
  const result = reconcile([{ id: 'missing', status: 202, replay: false }], [], [], [], 'W1');
  assert.equal(result.ok, false);
  assert.deepEqual(result.missing, ['missing']);
});
test('expected mixed retry is distinct from unexpected W1 retry', () => {
  const job = { id: 'job', type: 'fail_n_times', status: 'SUCCEEDED', attempt_count: 2, finished_at: '2026-09-12T00:00:02Z',
    attempts: [{ outcome: 'FAILURE', started_at: '2026-09-12T00:00:00Z', finished_at: '2026-09-12T00:00:01Z' },
      { outcome: 'SUCCESS', started_at: '2026-09-12T00:00:01Z', finished_at: '2026-09-12T00:00:02Z' }],
    outbox: [{ id: 'event', status: 'PUBLISHED' }] };
  assert.equal(reconcile([{ id: 'job', status: 202 }], [job], [], [], 'W3').ok, true);
  assert.equal(reconcile([{ id: 'job', status: 202 }], [job], [], [], 'W1').ok, false);
});
test('counter excess invalidates an otherwise successful effect job', () => {
  const job = { id: 'job', type: 'idempotent_counter', status: 'SUCCEEDED', attempt_count: 1, finished_at: '2026-09-12T00:00:01Z',
    attempts: [{ outcome: 'SUCCESS', started_at: '2026-09-12T00:00:00Z', finished_at: '2026-09-12T00:00:01Z' }], outbox: [{ status: 'PUBLISHED' }] };
  assert.equal(reconcile([{ id: 'job', status: 202 }], [job], [{ job_id: 'job', status: 'COMPLETED' }], [{ counter_value: 2 }], 'W2').ok, false);
});
test('repeatability mean and p95 limits are inclusive and applied without rounding', () => {
  const first={valid:true,hostCpu:{mean:4,p95:7,p99:9},maximumGapSeconds:5,
    instrumentationCpuPercent:{mean:0.1},diagnostics:{unavailableByFamily:{example:3}}};
  const boundary={...first,hostCpu:{mean:6,p95:9,p99:10},maximumGapSeconds:15,
    instrumentationCpuPercent:{mean:0.2},diagnostics:{unavailableByFamily:{example:1}}};
  const result=candidateRepeatability(first,boundary);
  assert.equal(result.eligible,true);
  assert.equal(result.relativeMeanDifferencePercent,50);
  assert.equal(result.diagnosticUnavailableDifferenceByFamily.example,-2);
  assert.equal(result.sampleCoefficientOfVariation.observations,2);
  assert.equal(candidateRepeatability(first,{...boundary,hostCpu:{...boundary.hostCpu,mean:6.000001}}).eligible,false);
  assert.equal(candidateRepeatability(first,{...boundary,hostCpu:{...boundary.hostCpu,p95:9.000001}}).eligible,false);
  assert.equal(candidateRepeatability(first,{...boundary,maximumGapSeconds:15.000001}).eligible,false);
  assert.equal(candidateRepeatability(first,{...boundary,hostCpu:{mean:4}}).eligible,false);
  assert.equal(candidateRepeatability({...first,hostCpu:{mean:0,p95:0,p99:0}}, {...first,hostCpu:{mean:0,p95:0,p99:0}}).sampleCoefficientOfVariation.hostMean,null);
});