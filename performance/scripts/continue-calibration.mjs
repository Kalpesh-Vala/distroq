import { execFileSync } from 'node:child_process';
import { readFile, writeFile, mkdir, open, rename, unlink, readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { parse } from 'csv-parse/sync';
import { connect, seal } from './capacity-runner.mjs';
import { reference, capture, interruptCalibration, calibrationInterrupted, transportIdentity } from './calibrate-telemetry.mjs';
import { candidateRepeatability, REPEATABILITY_LIMITS } from './calibration-analysis.mjs';
import { EXIT, ContinuationError, assertPreconditions, assertManifestHash, continueMissingRepeats, authorizationForExit,
  authorizeReplacement, classificationCorrection, failureAuthorization } from './continuation-policy.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = path.dirname(root);
const descriptorPath = path.join(root, 'environments', 'continuation.json');
const statePath = path.join(root, 'state', 'calibration-continuation.json');
const lockPath = path.join(root, 'state', 'qualification.lock');
const git = args => execFileSync('git', args, { cwd: repo, encoding: 'utf8' }).trim();
const json = buffer => JSON.parse(buffer.toString().replace(/^\uFEFF/, ''));
const readJson = async filename => json(await readFile(filename));
const digest = buffer => createHash('sha256').update(buffer).digest('hex').toUpperCase();
const newId = label => `${new Date().toISOString().replace(/[-:.]/g, '')}-${label}-${randomUUID().slice(0,8)}`;
const fail = (code, reason) => { throw new ContinuationError(code, reason); };
const checkInterrupted = () => { if (calibrationInterrupted()) fail(EXIT.INTERRUPTION, 'Interrupted; owned collector output preserved'); };
const location = id => {
  if (!/^[A-Za-z0-9-]+$/.test(id)) fail(EXIT.EVIDENCE, 'Unsafe run ID');
  return path.join(root, 'results', id);
};

export async function atomicJson(filename, value) {
  const temp = `${filename}.${randomUUID()}.tmp`;
  const handle = await open(temp, 'wx');
  try { await handle.writeFile(JSON.stringify(value, null, 2) + '\n'); await handle.sync(); }
  finally { await handle.close(); }
  await rename(temp, filename);
}

async function verifyRun(id, expectedHash) {
  try {
    const directory = location(id), manifestBuffer = await readFile(path.join(directory, 'manifest.json'));
    const hash = digest(manifestBuffer);
    if (expectedHash) assertManifestHash(hash, expectedHash);
    const entries = json(manifestBuffer);
    for (const item of entries) {
      const filename = path.resolve(directory, item.file);
      if (!filename.startsWith(directory + path.sep)) fail(EXIT.EVIDENCE, 'Manifest escapes evidence directory');
      assertManifestHash(digest(await readFile(filename)), item.sha256);
    }
    const checksums = path.join(directory, 'checksums.sha256');
    if (existsSync(checksums)) {
      const text = (await readFile(checksums, 'utf8')).replace(/^\uFEFF/, '').trim().split(/\r?\n/);
      for (const line of text) {
        const match = /^([a-fA-F0-9]{64})\s+(.+)$/.exec(line);
        if (!match) fail(EXIT.EVIDENCE, 'Malformed checksum line');
        const filename = path.resolve(directory, match[2]);
        if (!filename.startsWith(directory + path.sep)) fail(EXIT.EVIDENCE, 'Checksum escapes evidence directory');
        assertManifestHash(digest(await readFile(filename)), match[1].toUpperCase());
      }
    }
    return hash;
  } catch (error) { fail(EXIT.EVIDENCE, `Evidence verification failed for ${id}: ${error.message}`); }
}

export async function verifyOriginals(descriptor) {
  const originals = {};
  for (const [name, expected] of Object.entries(descriptor.originals)) {
    await verifyRun(expected.runId, expected.manifestSha256);
    const verdict = await readJson(path.join(location(expected.runId), expected.verdictFile));
    if (verdict.status !== expected.expectedStatus) fail(EXIT.EVIDENCE, `Original ${name} verdict differs`);
    originals[name] = verdict;
  }
  return originals;
}

export async function writeSupersedingCorrection(descriptor, toolingCommit, previousState = null) {
  const policy=descriptor.replacement;
  await verifyRun(policy.invalidC1RepeatRunId,policy.invalidC1ManifestSha256);
  await verifyRun(policy.originalContinuationReviewId,policy.originalReviewManifestSha256);
  const failed=await readJson(path.join(location(policy.invalidC1RepeatRunId),'validity.json'));
  const prior=await readJson(path.join(location(policy.originalContinuationReviewId),'verdict.json'));
  const correction=classificationCorrection(policy.originalContinuationReviewId,failed,prior,new Date().toISOString(),toolingCommit);
  const directory=location(newId('CLASSIFICATION-CORRECTION'));
  await mkdir(directory);
  await writeFile(path.join(directory,'correction.json'),JSON.stringify(correction,null,2),{flag:'wx'});
  if(previousState)await writeFile(path.join(directory,'previous-continuation-state.json'),JSON.stringify(previousState,null,2),{flag:'wx'});
  await seal(directory);await verifyRun(path.basename(directory));
  return {runId:path.basename(directory),...correction};
}

export function evaluatedRepeat(result, phase, originals) {
  const collector=phase==='C1-repeat'?'runner':'independent';
  const original=phase==='C1-repeat'?originals.C1:originals.C2;
  const repeatability=candidateRepeatability(original[collector],result[collector]);
  return {runId:result.runId,status:result.status==='VALID'&&repeatability.eligible?'VALID':result.status==='INCOMPLETE'?'INCOMPLETE':'INVALID',
    captureStatus:result.status,repeatability,reasons:[...(result.reasons||[]),
      ...(result.status==='VALID'&&!repeatability.eligible?['Approved original-versus-repeat mean/p95/gap limits failed']:[])]};
}

export async function verifyEnvironment(descriptor, state) {
  let context;
  try { context = await connect(descriptor.project); } catch { fail(EXIT.CONTAINERS, 'Cannot connect to exact benchmark project'); }
  const originalDirectory = location(descriptor.originals.C3.runId);
  const previous = await readJson(path.join(originalDirectory, 'final-state', 'host.json'));
  const expected = previous.containers;
  let current;
  try { current = JSON.parse(execFileSync('docker', ['inspect', ...expected.map(item => item.id)], { encoding: 'utf8' })); }
  catch { fail(EXIT.CONTAINERS, 'Expected benchmark/developer container missing'); }
  for (const item of current) {
    const prior = expected.find(value => value.id === item.Id);
    if (!prior || prior.startedAt !== item.State.StartedAt || prior.restarts !== item.RestartCount || item.Image !== prior.image
      || !item.State.Running || item.State.Paused || item.State.Health?.Status !== 'healthy'
      || item.Config.Labels['com.docker.compose.project'] !== prior.labels['com.docker.compose.project'])
      fail(EXIT.CONTAINERS, 'Benchmark/developer ownership or lifecycle changed');
    for (const mount of item.Mounts.filter(mount => mount.Type === 'volume')) {
      const volume = JSON.parse(execFileSync('docker', ['volume', 'inspect', mount.Name], { encoding: 'utf8' }))[0];
      if (volume.Labels?.['com.docker.compose.project'] !== item.Config.Labels['com.docker.compose.project']) fail(EXIT.CONTAINERS, 'Volume project mismatch');
    }
  }
  const running = execFileSync('docker', ['ps', '-q'], { encoding: 'utf8' }).trim().split(/\s+/).filter(Boolean);
  if (running.length !== current.length || running.some(id => !current.some(item => item.Id.startsWith(id))))
    fail(EXIT.CONTAINERS, 'Unexpected running container or load generator');
  if (context.settings.DISTROQ_WORKER_CONCURRENCY !== '1') fail(EXIT.CONTAINERS, 'Worker setting differs from original');
  const fingerprints = Object.fromEntries(Object.entries(context.services).map(([name,item]) => [name,
    digest(Buffer.from(JSON.stringify({ image:item.Image, env:item.Config.Env, limits:[item.HostConfig.Memory,item.HostConfig.NanoCpus],mounts:item.Mounts }))) ]));
  if (state?.containerFingerprints && JSON.stringify(fingerprints) !== JSON.stringify(state.containerFingerprints)) fail(EXIT.CONTAINERS, 'Pinned application configuration changed');
  const oldDurable = await readJson(path.join(originalDirectory, 'final-state', 'stack', 'durable.json'));
  const sql = await readFile(path.join(root, 'scripts', 'reconcile.sql'), 'utf8');
  const durable = json(execFileSync('docker', ['exec','-i',context.services.postgres.Id,'psql','-X','-qAt','-U','benchmark','-d','benchmark'], {input:sql,encoding:'utf8'}));
  const withoutUtc = value => { const copy = {...value}; delete copy.utc; return JSON.stringify(copy); };
  if (withoutUtc(oldDurable) !== withoutUtc(durable)) fail(EXIT.CONTAINERS, 'Durable state differs from preserved C3; no-job invariant failed');
  const oldTransport = await readJson(path.join(originalDirectory, 'final-state','stack','transport.json'));
  const transport = { delayed:await context.redis(['ZCARD','distroq:jobs:delayed']), scheduled:await context.redis(['ZCARD','distroq:jobs:scheduled']) };
  for (const tier of ['high','normal','low']) {
    const key = `distroq:jobs:stream:${tier}`;
    transport[tier] = {length:await context.redis(['XLEN',key]), groups:[JSON.stringify(await context.redis(['XINFO','GROUPS',key]))],
      pending:[JSON.stringify(await context.redis(['XPENDING',key,'distroq-workers']))]};
  }
  if (JSON.stringify(transportIdentity(transport)) !== JSON.stringify(transportIdentity(oldTransport))) fail(EXIT.CONTAINERS, 'Transport differs from preserved C3');
  return { context, fingerprints };
}

async function relativeObservation(captureResult) {
  const referenceId = captureResult.referenceRun;
  await verifyRun(referenceId);
  const ref = await readJson(path.join(location(referenceId), 'validity.json'));
  if (ref.status !== 'VALID') fail(EXIT.EVIDENCE, 'Invalid reference cannot be used in overhead observations');
  const referenceCsv = parse(await readFile(path.join(location(referenceId), 'host.csv'), 'utf8'), {relax_column_count:true,skip_empty_lines:true});
  const timestamps = referenceCsv.filter(row => /^\d/.test(row[0]||'')).map(row => Date.parse(row[0]));
  const collector = captureResult.runner || captureResult.independent;
  const observations = collector.accounting.observations;
  const first = observations[0].utcMs, last = observations.at(-1).utcMs;
  return {referenceRunId:referenceId,captureRunId:captureResult.runId,
    referenceHostCpuMean:ref.hostCpu.mean,referenceHostCpuP95:ref.hostCpu.p95,
    referenceFinishedUtc:new Date(timestamps.at(-1)).toISOString(),captureStartedUtc:new Date(first).toISOString(),captureFinishedUtc:new Date(last).toISOString(),
    referenceToCaptureGapSeconds:(first-timestamps.at(-1))/1000,
    meanDifferencePercentagePoints:collector.hostCpu.mean-ref.hostCpu.mean,
    p95DifferencePercentagePoints:collector.hostCpu.p95-ref.hostCpu.p95,
    interpretation:collector.hostCpu.mean<ref.hostCpu.mean?'Capture lower than reference: background variation exceeds resolvable overhead signal.':'Positive relative observed difference; not exact causal overhead.',
    distinguishableFromIdleVariation:'Not established from one temporal pair; no universal reference averaging'};
}

async function analyze(state, originals) {
  const repeats = {};
  for (const phase of ['C1-repeat','C2-repeat']) {
    await verifyRun(state.repeats[phase].runId,state.repeats[phase].manifestSha256);
    repeats[phase] = await readJson(path.join(location(state.repeats[phase].runId),'validity.json'));
  }
  const runner = candidateRepeatability(originals.C1.runner,repeats['C1-repeat'].runner);
  const independent = candidateRepeatability(originals.C2.independent,repeats['C2-repeat'].independent);
  const comparisons = {runner,independent};
  const checks = (original,repeat,comparison) => ({originalValid:original.valid,repeatValid:repeat.valid,
    mean:comparison.totalCpuDifferencePercentagePoints<=2,p95:comparison.p95DifferencePercentagePoints<=2,
    timing:original.maximumGapSeconds<=15&&repeat.maximumGapSeconds<=15,
    trackedAttribution:original.accounting.valid&&repeat.accounting.valid});
  const overhead=await Promise.all([originals.C1,originals.C2,repeats['C1-repeat'],repeats['C2-repeat']].map(relativeObservation));
  const referenceStability={runner:Math.abs(overhead[0].referenceHostCpuMean-overhead[2].referenceHostCpuMean)<=2,
    independent:Math.abs(overhead[1].referenceHostCpuMean-overhead[3].referenceHostCpuMean)<=2};
  const qualified = [runner.eligible&&referenceStability.runner?'runner':null,independent.eligible&&referenceStability.independent?'independent':null].filter(Boolean);
  const selected = qualified.includes('runner')?'runner':qualified[0]||null;
  const report = {status:selected?'COMPLETE':'NO_REPEATABLE_COLLECTOR',originalCampaignReviewId:state.originals.review.runId,
    toolingCommit:state.toolingCommit,originals:state.originals,repeats:state.repeats,references:state.references,
    comparisons,individualGates:{runner:checks(originals.C1.runner,repeats['C1-repeat'].runner,runner),
      independent:checks(originals.C2.independent,repeats['C2-repeat'].independent,independent)},
    distributions:{runner:{original:originals.C1.runner.hostCpu,repeat:repeats['C1-repeat'].runner.hostCpu},
      independent:{original:originals.C2.independent.hostCpu,repeat:repeats['C2-repeat'].independent.hostCpu}},
    relativeOverhead:overhead,referenceStability,
    diagnostics:{runner:repeats['C1-repeat'].runner.diagnostics,independent:repeats['C2-repeat'].independent.diagnostics},
    independentCpu:{tracked:repeats['C2-repeat'].independent.instrumentationCpuPercent,wmi:repeats['C2-repeat'].independent.wmiCpuPercent,
      diagnostic:repeats['C2-repeat'].independent.diagnosticProcessCpu,unattributed:'Unknown, never subtracted',
      classification:'WmiPrvSE is diagnostic/provider CPU; harness ownership does not prove its causality. Harness process CPU is not complete observer overhead.'},
    canonicalCollector:selected,technicallyQualified:qualified,
    rationale:qualified.length===2?'Both pass declared screens; runner recommended operationally for timestamped CSV and no independent WMI polling. No lower causal overhead claim.':
      selected?'Only this collector passes all original/repeat numeric and validity screens; no claim of exact observer cost.':'Neither passes the approved repeatability gates.',
    p1Authorization:authorizationForExit(selected?EXIT.OK:EXIT.NO_COLLECTOR),p1Executed:false,p2Executed:false,
    limitations:['Two observations are descriptive only; changing unrelated background activity remains a threat.',
      'Workload-window alignment uses retained UTC sample timestamps with measured sampling resolution, not sub-sample CPU precision.',
      'C3 remains preserved interaction evidence, not an algebraic decomposition of individual overhead.',
      'Collector implementation still requires separate P1 integration/review; authorization never launches P1.',
      'Future multi-signal settling rule is a proposal only; current 2.0000-point gate is unchanged.']};
  return {report,code:selected?EXIT.OK:EXIT.NO_COLLECTOR};
}

async function main() {
  process.chdir(repo);
  const args=process.argv.slice(2),checkOnly=args.includes('--check-only');
  const descriptor=await readJson(descriptorPath);
  const replacementFlag=args.includes('--authorize-replacement-c1');
  const project=args.find(value=>/^distroq-bench-\d{14}-[a-f0-9]{6}$/.test(value));
  if(project!==descriptor.project)fail(EXIT.CONTAINERS,'Project differs from approved descriptor');
  if(!checkOnly&&(!args.includes('--exclusive-host-confirmed')||process.env.TERM_PROGRAM==='vscode'))fail(EXIT.TOOLING,'Use reserved standalone PowerShell with dashboard/editor closed');
  const head=git(['rev-parse','HEAD']);
  const pinned=git(['log','-1','--format=%H','--','performance/environments/continuation.json']);
  const applicationPaths=['src','dashboard','pom.xml','Dockerfile','docker-compose.yml','docker-compose.production.yml'];
  assertPreconditions({expectedCommit:pinned,currentCommit:head,
    applicationDirty:!!git(['diff',descriptor.applicationCommit,'--',...applicationPaths])||!!git(['status','--porcelain','--',...applicationPaths]),
    toolingDirty:!!git(['status','--porcelain','--','performance']),lockExists:existsSync(lockPath)});
  if(git(['branch','--show-current'])!=='benchmark-v1.1')fail(EXIT.TOOLING,'Wrong branch');
  const originals=await verifyOriginals(descriptor);
  let state=existsSync(statePath)?await readJson(statePath):null;
  const descriptorHash=digest(await readFile(descriptorPath));
  const migration=!!(replacementFlag&&state&&!state.replacementAuthorized);
  if(descriptor.replacement&&!replacementFlag)fail(EXIT.ALREADY_RUN,'Explicit -AuthorizeReplacementC1 is required; prior command may not repeat C1');
  if(!state&&descriptor.replacement)fail(EXIT.EVIDENCE,'Prior continuation state required for the one authorized replacement');
  if(migration){
    if(state.descriptorSha256!==descriptor.replacement.previousDescriptorSha256)fail(EXIT.TOOLING,'Prior state descriptor pin differs');
    authorizeReplacement(state,descriptor.replacement);
    await verifyRun(descriptor.replacement.invalidC1RepeatRunId,descriptor.replacement.invalidC1ManifestSha256);
    await verifyRun(descriptor.replacement.originalContinuationReviewId,descriptor.replacement.originalReviewManifestSha256);
  } else if(state&&(state.toolingCommit!==head||state.descriptorSha256!==descriptorHash))fail(EXIT.TOOLING,'Continuation state pin differs from committed tooling');
  if(state?.replacementAttempted&&state.replacementVerdict!=='VALID')fail(EXIT.ALREADY_RUN,'Replacement C1 already attempted; no further local replacement authorized');
  if(state?.nextRequiredPhase==='DONE')fail(EXIT.ALREADY_RUN,'Calibration already completed; inspect immutable review');
  const {context,fingerprints}=await verifyEnvironment(descriptor,state);
  const expectedLimits=REPEATABILITY_LIMITS;
  if(descriptor.limits.meanDifferencePercentagePoints!==expectedLimits.meanDifferencePercentagePoints||descriptor.limits.p95DifferencePercentagePoints!==expectedLimits.p95DifferencePercentagePoints
    ||descriptor.limits.maximumSampleGapSeconds!==expectedLimits.maximumSampleGapSeconds)fail(EXIT.TOOLING,'Approved limits differ');
  const plan={project,toolingCommit:head,mode:replacementFlag?'ONE_AUTHORIZED_REPLACEMENT':'CONTINUATION_ONLY',nextRequiredPhase:state?.nextRequiredPhase||descriptor.nextRequiredPhase,
    replacementAuthorized:replacementFlag,invalidC1RepeatRunId:descriptor.replacement?.invalidC1RepeatRunId,
    operatorAttestation:'AC connected; sleep/hibernation disabled; VS Code, browsers and Task Manager closed; no dashboard/unrelated generator; ordinary Defender/indexing activity allowed to settle naturally. No protection disabled.',
    phases:['C1-repeat','C2-repeat'],originalsRerun:false,p1:false,p2:false,limits:descriptor.limits,cooldownSeconds:descriptor.cooldownSeconds,
    maximumConsecutiveFailedReferences:descriptor.maximumConsecutiveFailedReferences,statePath};
  console.log(JSON.stringify(plan,null,2));
  if(checkOnly){console.log('CHECK_ONLY_OK: no collectors started and no state written');return;}
  await mkdir(path.dirname(statePath),{recursive:true});
  const lock=await open(lockPath,'wx').catch(()=>fail(EXIT.LOCK,'Calibration lock exists'));
  let code=EXIT.OK,reason='Calibration completed',report=null,reviewDirectory=null,verdictWritten=false;
  const persist=async value=>{
    value.c1RepeatCompleted=value.repeats['C1-repeat']?.status==='VALID';
    value.c2RepeatCompleted=value.repeats['C2-repeat']?.status==='VALID';
    if(value.replacementAuthorized){
      const c1=value.repeats['C1-repeat'];
      if(c1){value.replacementAttempted=true;value.replacementRunId=c1.runId;value.replacementVerdict=c1.status;}
      const c2=value.repeats['C2-repeat'];
      if(c2){value.c2RepeatAttempted=true;value.c2RepeatRunId=c2.runId;}
    }
    await atomicJson(statePath,value);
  };
  const signal=()=>interruptCalibration();
  process.on('SIGINT',signal);process.on('SIGTERM',signal);process.on('SIGHUP',signal);
  try {
    await lock.writeFile(JSON.stringify({pid:process.pid,project,toolingCommit:head,statePath}));await lock.sync();
    execFileSync('node',[path.join(root,'scripts','audit-tooling.mjs'),'--verify-only'],{cwd:repo,stdio:'inherit'});
    if(migration){
      const correction=await writeSupersedingCorrection(descriptor,head,state);
      state=authorizeReplacement(state,descriptor.replacement);
      state.previousToolingCommit=state.toolingCommit;state.toolingCommit=head;state.descriptorSha256=descriptorHash;
      state.classificationCorrectionRunId=correction.runId;
      await persist(state);
    }
    state ||= {...descriptor,toolingCommit:head,descriptorSha256:digest(await readFile(descriptorPath)),containerFingerprints:fingerprints,
      repeats:{},references:[],failedReferences:{'C1-repeat':0,'C2-repeat':0},inFlight:null,cooldownUntilMs:null,finalCalibrationVerdict:'PENDING'};
    if(state.inFlight){
      const reserved=state.inFlight;
      await verifyRun(reserved.runId);
      const result=await readJson(path.join(location(reserved.runId),'validity.json'));
      const entry={...(reserved.kind==='capture'&&state.replacementAuthorized?evaluatedRepeat(result,reserved.phase,originals):{runId:result.runId,status:result.status}),manifestSha256:await verifyRun(result.runId)};
      if(reserved.kind==='capture')state.repeats[reserved.phase]=entry;
      else {state.references.push({...entry,phase:reserved.phase,recovered:true});if(result.status!=='VALID')state.failedReferences[reserved.phase]++;}
      state.inFlight=null;await persist(state);
      if(entry.status!=='VALID')fail(EXIT.INTERRUPTION,'Prior interrupted attempt preserved; manual review required');
    }
    for(const phase of ['C1-repeat','C2-repeat']){
      if(state.repeats[phase])await verifyRun(state.repeats[phase].runId,state.repeats[phase].manifestSha256);
      else for(const directory of await readdir(path.join(root,'results'))){
        const label=state.replacementAuthorized&&phase==='C1-repeat'?'C1-replacement':phase;
        if(directory.includes(`-CAL-${label}-`))fail(EXIT.ALREADY_RUN,`Unrecorded ${label} evidence exists; do not duplicate it`);
      }
    }
    await persist(state);
    const allocate=async(kind,phase,id)=>{
      checkInterrupted();state.inFlight={kind,phase,runId:id};
      if(kind==='capture')state.repeats[phase]={runId:id,status:'RESERVED'};
      await persist(state);
    };
    await continueMissingRepeats(state,{
      persist,checkInterrupted,now:()=>Date.now(),
      cooldown:until=>new Promise((resolve,reject)=>{
        const remaining=Math.max(0,until-Date.now());
        console.log(`Cooldown before new reference: ${Math.ceil(remaining/1000)} seconds`);
        const timer=setTimeout(()=>{clearInterval(interruption);resolve();},remaining);
        const interruption=setInterval(()=>{if(calibrationInterrupted()){clearTimeout(timer);clearInterval(interruption);reject(new ContinuationError(EXIT.INTERRUPTION,'Interrupted during cooldown'));}},250);
      }),
      reference:async phase=>{
        await verifyEnvironment(descriptor,state);checkInterrupted();
        const result=await reference(context,{allocated:id=>allocate('reference',phase,id)});
        checkInterrupted();
        return {...result,manifestSha256:await verifyRun(result.runId),exitCode:result.fatal?EXIT.ARTIFACT:undefined};
      },
      capture:async(phase,ref)=>{
        await verifyEnvironment(descriptor,state);checkInterrupted();
        const label=state.replacementAuthorized&&phase==='C1-repeat'?'C1-replacement':phase;
        const result=await capture(context,label,ref,{continuationOnly:true,allocated:id=>allocate('capture',phase,id)});
        checkInterrupted();
        return {...(state.replacementAuthorized?evaluatedRepeat(result,phase,originals):{runId:result.runId,status:result.status,reasons:result.reasons}),manifestSha256:await verifyRun(result.runId)};
      },
    });
    await verifyOriginals(descriptor);await verifyEnvironment(descriptor,state);
    ({report,code}=await analyze(state,originals));reason=report.rationale;
    execFileSync('node',[path.join(root,'scripts','audit-tooling.mjs'),'--verify-only'],{cwd:repo,stdio:'inherit'});
    state.nextRequiredPhase='DONE';
  } catch(error){code=calibrationInterrupted()?EXIT.INTERRUPTION:Number.isInteger(error.code)?error.code:EXIT.ARTIFACT;reason=error.message;report=null;}
  finally {
    try {
      const invalidRepeat=[state?.repeats?.['C1-repeat'],state?.repeats?.['C2-repeat']].find(entry=>entry?.status==='INVALID');
      const finalAuthorization=[EXIT.C1_INVALID,EXIT.C2_INVALID].includes(code)&&invalidRepeat?failureAuthorization(invalidRepeat):authorizationForExit(code);
      const verdict={...(report||{}),status:code===EXIT.INTERRUPTION?'INCOMPLETE':report?.status||'BLOCKED',exitCode:code,reason,
        toolingCommit:head,p1Authorization:finalAuthorization,p1Executed:false,p2Executed:false,
        recommendation:state?.replacementAttempted&&finalAuthorization==='P1_BLOCKED_UNSTABLE_HOST'?'Local replacement budget exhausted. Move performance campaign to a dedicated VM or separate machine; no additional local replacement authorized.':null,
        originals:descriptor.originals,continuationProgress:state||null};
      reviewDirectory=location(newId('CONTINUATION-REVIEW'));await mkdir(reviewDirectory);
      await writeFile(path.join(reviewDirectory,'verdict.json'),JSON.stringify(verdict,null,2),{flag:'wx'});
      const index=await readJson(path.join(root,'reports','EVIDENCE_INDEX.json'));
      const additions=[...(state?.references||[]),...(state?.priorC1Attempts||[]),...Object.values(state?.repeats||{})].filter(entry=>entry.manifestSha256);
      const indexed=new Map(index.runs.map(entry=>[entry.runId,entry]));
      for(const entry of additions)indexed.set(entry.runId,{...indexed.get(entry.runId),runId:entry.runId,relativeLocation:`results/${entry.runId}`,manifestSha256:entry.manifestSha256});
      await writeFile(path.join(reviewDirectory,'evidence-index.json'),JSON.stringify({...index,runs:[...indexed.values()],
        note:'Continuation index candidate; promote to tracked reports/EVIDENCE_INDEX.json only after review.'},null,2),{flag:'wx'});
      await seal(reviewDirectory);await verifyRun(path.basename(reviewDirectory));verdictWritten=true;
      if(state){state.finalCalibrationVerdict=verdict.p1Authorization;state.finalAuthorization=verdict.p1Authorization;state.lastReviewRunId=path.basename(reviewDirectory);await persist(state);}
      console.log(JSON.stringify({reviewDirectory,...verdict},null,2));
    } finally {
      process.removeListener('SIGINT',signal);process.removeListener('SIGTERM',signal);process.removeListener('SIGHUP',signal);
      await lock.close();if(verdictWritten)await unlink(lockPath);
    }
  }
  process.exitCode=code;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(error=>{
  const code=Number.isInteger(error.code)?error.code:EXIT.ARTIFACT;console.error(JSON.stringify({status:'BLOCKED',exitCode:code,reason:error.message,p1Authorization:authorizationForExit(code)}));process.exitCode=code;
});