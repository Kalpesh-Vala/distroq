import { spawn, execFileSync } from 'node:child_process';
import { mkdir, open, unlink, readFile, writeFile, appendFile, readdir } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { cpus } from 'node:os';
import { fileURLToPath } from 'node:url';
import { stripVTControlCharacters } from 'node:util';
import path from 'node:path';
import { parse } from 'csv-parse/sync';
import { connect, seal } from './capacity-runner.mjs';
import { summarizeCollector, distribution, overheadEstimates, candidateRepeatability, validReferenceBaseline } from './calibration-analysis.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const repo=path.dirname(root);
const runId=label=>`${new Date().toISOString().replace(/[-:.]/g,'')}-${label}-${randomUUID().slice(0,8)}`;
const decode=text=>JSON.parse(text.replace(/^\uFEFF/,''));
const readJson=async file=>decode(await readFile(file,'utf8'));
const rows=async file=>(await readFile(file,'utf8')).replace(/^\uFEFF/,'').trim().split(/\r?\n/).filter(Boolean).map(JSON.parse);
const save=async(directory,name,data)=>writeFile(path.join(directory,name),typeof data==='string'?data:JSON.stringify(data,null,2),{flag:'wx'});
const ownedChildren=new Set();
let interrupted=false;
export function interruptCalibration() {
  interrupted=true;
  for(const child of ownedChildren)child.kill();
}
export function calibrationInterrupted() { return interrupted; }

function start(program,args){
  if(interrupted)throw new Error('Calibration interrupted');
  const startedMs=Date.now();
  const child=spawn(program,args,{cwd:repo,windowsHide:true,stdio:['ignore','pipe','pipe']});
  ownedChildren.add(child);
  const identity={pid:child.pid,name:path.basename(program).replace(/\.exe$/i,''),startedMs,endedMs:null,identityVerified:Number.isInteger(child.pid)};
  let stdout='';let stderr='';
  child.stdout.on('data',chunk=>{stdout+=chunk;});child.stderr.on('data',chunk=>{stderr+=chunk;});
  const done=new Promise(resolve=>{
    child.once('error',error=>{ownedChildren.delete(child);resolve({code:null,stdout,stderr,error:error.message});});
    child.once('close',code=>{ownedChildren.delete(child);identity.endedMs=Date.now();resolve({code,stdout,stderr});});
  });
  return {child,done,identity};
}
async function powershell(script,args){
  const task=start('powershell.exe',['-NoProfile','-NonInteractive','-File',path.join(root,'scripts',script),...args]);
  const result=await task.done;
  if(result.code!==0)throw new Error(`${script} failed; no raw exception output exposed`);
  return result;
}
const processName=header=>/\\process\((.+)\)\\/i.exec(header)?.[1];
export function parseRunnerCsv(text){
  const records=parse(stripVTControlCharacters(text).replace(/^\uFEFF/,''),{relax_column_count:true,skip_empty_lines:true});
  const headerIndex=records.findIndex(row=>row[0]?.includes('PDH-CSV'));
  if(headerIndex<0)throw new Error('typeperf CSV header missing');
  const headers=records[headerIndex];
  let invalidCount=0;let hostInvalidCount=0;const samples=[];const invalidDetails=[];
  const pidIndexes=new Map(headers.map((header,index)=>[header.replace(/\\id process$/i,''),index]).filter(([header,index])=>/\\id process$/i.test(headers[index])));
  const totalIndex=headers.findIndex(header=>/\\processor\(_total\)\\% processor time$/i.test(header));
  for(const cells of records.slice(headerIndex+1)){
    if(!/^\d/.test(cells[0]||''))continue;
    if(cells.length!==headers.length){hostInvalidCount++;continue;}
    const processes=[];const unavailableProcesses=[];
    for(let index=1;index<headers.length;index++){
      const name=processName(headers[index]);
      if(!name||!/\\% processor time$/i.test(headers[index])||/^(idle|_total)$/i.test(name))continue;
      const pidIndex=pidIndexes.get(headers[index].replace(/\\% processor time$/i,''));
      const pid=pidIndex===undefined?NaN:Number(cells[pidIndex]);
      const rawCpuPercent=Number(cells[index]);
      if(!Number.isInteger(pid)||pid<=0||!Number.isFinite(rawCpuPercent)||rawCpuPercent<0||cells[index]===''){
        invalidCount++;
        unavailableProcesses.push({name,pid:Number.isInteger(pid)&&pid>0?pid:null,rawCpuPercent:null,pidRaw:pidIndex===undefined?null:cells[pidIndex],cpuRaw:cells[index],reason:'Unavailable CPU/PID diagnostic'});
        if(invalidDetails.length<50)invalidDetails.push({timestamp:cells[0],counter:headers[index],pidRaw:pidIndex===undefined?null:cells[pidIndex],cpuRaw:cells[index],reason:'Unavailable or invalid process CPU/PID counter; not zero-filled'});
        continue;
      }
      processes.push({pid,name,rawCpuPercent,status:0});
    }
    const hardware=headers.map((name,index)=>({name,value:cells[index]===''?NaN:Number(cells[index])})).filter(row=>/processor information/i.test(row.name));
    if(hardware.length<3||hardware.some(row=>!Number.isFinite(row.value)||row.value<0))hostInvalidCount++;
    const utcMs=Date.parse(cells[0]);
    const totalCpuPercent=totalIndex<0||cells[totalIndex]===''?NaN:Number(cells[totalIndex]);
    if(!Number.isFinite(utcMs)||!Number.isFinite(totalCpuPercent)||totalCpuPercent<0||totalCpuPercent>100)hostInvalidCount++;
    samples.push({utcMs,totalCpuPercent,processes,unavailableProcesses,
      frequencyMHz:hardware.filter(row=>/\\processor frequency$/i.test(row.name)).map(row=>row.value),
      performanceLimited:hardware.some(row=>(/\\% performance limit$/i.test(row.name)&&row.value<100)||(/\\performance limit flags$/i.test(row.name)&&row.value!==0))});
  }
  return {samples,invalidCount,hostInvalidCount,invalidDetails};
}
function parseIndependent(input){
  let invalidCount=0;let hostInvalidCount=0;
  const samples=input.map(row=>{
    const total=row.hardware.find(counter=>/\\processor\(_total\)\\% processor time$/i.test(counter.Path));
    hostInvalidCount+=row.hardware.filter(counter=>counter.Status!==0||!Number.isFinite(counter.CookedValue)||counter.CookedValue<0).length;
    if(!total||row.hardware.length<4)hostInvalidCount++;
    const good=proc=>Number.isInteger(proc.pid)&&proc.pid>0&&Number.isFinite(proc.rawCpuPercent)&&proc.rawCpuPercent>=0;
    const unavailableProcesses=row.processes.filter(proc=>!good(proc)).map(proc=>({...proc,rawCpuPercent:null,reason:'Unavailable independent process diagnostic'}));
    invalidCount+=unavailableProcesses.length;
    return {utcMs:row.utcMs,totalCpuPercent:total?.CookedValue,processes:row.processes.filter(good).map(proc=>({pid:proc.pid,name:proc.name,rawCpuPercent:proc.rawCpuPercent})),unavailableProcesses,
      frequencyMHz:row.hardware.filter(counter=>/\\processor frequency$/i.test(counter.Path)).map(counter=>counter.CookedValue),
      performanceLimited:row.hardware.some(counter=>(/\\% performance limit$/i.test(counter.Path)&&counter.CookedValue<100)||(/\\performance limit flags$/i.test(counter.Path)&&counter.CookedValue!==0))};
  });
  return {samples,invalidCount,hostInvalidCount};
}
async function verifyManifest(directory){
  const manifest=await readJson(path.join(directory,'manifest.json'));
  for(const item of manifest){
    const hash=createHash('sha256').update(await readFile(path.join(directory,item.file))).digest('hex').toUpperCase();
    if(hash!==item.sha256)throw new Error('New evidence checksum mismatch');
  }
}
async function snapshot(context,directory,name){
  const current=await connect(context.project);
  for(const [service,item] of Object.entries(current.services)){
    const initial=context.services[service];
    if(item.Id!==initial.Id||item.Image!==initial.Image||item.State.StartedAt!==initial.State.StartedAt
      ||item.RestartCount!==initial.RestartCount||JSON.stringify(item.Config.Env)!==JSON.stringify(initial.Config.Env))throw new Error('Service identity or configuration changed');
  }
  const output=path.join(directory,name);await mkdir(output);
  await powershell('Capture-CalibrationBoundary.ps1',['-Output',path.join(output,'host.json'),'-Project',context.project]);
  await powershell('Collect-Snapshot.ps1',['-Output',path.join(output,'stack'),'-Project',context.project]);
  const state=await readJson(path.join(output,'stack','durable.json'));
  const transport=await readJson(path.join(output,'stack','transport.json'));
  if(state.nonterminal!==0||state.unpublished!==0||transport.delayed!==0||transport.scheduled!==0)throw new Error('Stack is not settled');
  for(const tier of ['high','normal','low']){
    const groups=JSON.parse(transport[tier].groups[0]);const pending=JSON.parse(transport[tier].pending[0]);
    if(pending[0]!==0||groups.some(group=>group.lag!==0))throw new Error('Transport is not settled');
  }
  return {host:await readJson(path.join(output,'host.json')),state,transport};
}
export function transportIdentity(transport){
  return {delayed:transport.delayed,scheduled:transport.scheduled,streams:['high','normal','low'].map(tier=>{
    const stream=transport[tier];
    return {tier,length:stream.length,groups:JSON.parse(stream.groups[0]).map(group=>({name:group.name,lag:group.lag,pending:group.pending,lastDeliveredId:group['last-delivered-id']})),
      pending:JSON.parse(stream.pending[0])};
  })};
}
export async function reference(context, options={}){
  const id=runId('CAL-REFERENCE');const directory=path.join(root,'results',id);
  if(options.allocated)await options.allocated(id);
  await mkdir(directory);
  let result={runId:id,status:'INCONCLUSIVE'};
  try{
    const before=await snapshot(context,directory,'initial-state');
    const began=new Date().toISOString();
    const wall=Date.now();const monotonic=process.hrtime.bigint();
    await save(directory,'environment.json',{toolingCommit:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),project:context.project,startedUtc:began,noJobs:true});
    const task=start('typeperf',['\\Processor(_Total)\\% Processor Time','-si','5','-sc','13']);
    const measured=await task.done;
    await save(directory,'host.csv',measured.stdout);await save(directory,'collector.log',measured.stderr);
    if(measured.code!==0)throw new Error('Reference sampler failed');
    const parsed=parse(measured.stdout,{relax_column_count:true,skip_empty_lines:true});
    const values=parsed.filter(row=>/^\d/.test(row[0]||'')).map(row=>({utcMs:Date.parse(row[0]),cpu:Number(row[1])}));
    if(values.length!==13||values.some(row=>!Number.isFinite(row.utcMs)||!Number.isFinite(row.cpu)||row.cpu<0||row.cpu>100))throw new Error('Reference coverage incomplete');
    const gaps=values.slice(1).map((row,index)=>(row.utcMs-values[index].utcMs)/1000);
    if(gaps.some(gap=>gap<=0||gap>15))throw new Error('Reference timing gap');
    const mean=slice=>slice.reduce((sum,row)=>sum+row.cpu,0)/slice.length;
    const midpoint=Math.floor(values.length/2);
    result={runId:id,status:'VALID',hostCpu:distribution(values.map(row=>row.cpu)),
      settlingChangePercentagePoints:Math.abs(mean(values.slice(midpoint))-mean(values.slice(0,midpoint))),
      samples:values.length,spanSeconds:(values.at(-1).utcMs-values[0].utcMs)/1000,maximumGapSeconds:Math.max(...gaps),
      startedUtc:new Date(values[0].utcMs).toISOString(),finishedUtc:new Date(values.at(-1).utcMs).toISOString(),
      limitation:'Total-only typeperf reference. Contains its own observer overhead and running benchmark infrastructure; not zero-instrumentation CPU.'};
    result.reasons=[];
    if(result.spanSeconds<60)throw new Error('Reference duration incomplete');
    if(result.hostCpu.mean>10||values.filter(row=>row.cpu>20).length/values.length>0.1)result.reasons.push('Host total contention threshold exceeded');
    if(result.settlingChangePercentagePoints>2)result.reasons.push('Half-window difference exceeds 2.0000 percentage points');
    const after=await snapshot(context,directory,'final-state');
    const stable=value=>{const copy={...value};delete copy.utc;return JSON.stringify(copy);};
    if(before.host.powerPlan!==after.host.powerPlan||JSON.stringify(before.host.containers)!==JSON.stringify(after.host.containers))throw new Error('Reference power/container lifecycle changed');
    if(stable(before.state)!==stable(after.state)||JSON.stringify(transportIdentity(before.transport))!==JSON.stringify(transportIdentity(after.transport)))throw new Error('Reference durable/transport state changed');
    if(Math.abs(Date.now()-wall-Number(process.hrtime.bigint()-monotonic)/1e6)>1000)throw new Error('System clock stepped materially');
    const events=await start('wevtutil',['qe','System',`/q:*[System[(EventID=42 or EventID=107 or EventID=506 or EventID=507) and TimeCreated[@SystemTime>='${began}']]]`,'/f:xml']).done;
    await save(directory,'power-events.xml',events.stdout);
    if(events.code!==0||events.stdout.trim())throw new Error('Reference power event audit failed');
    if(result.reasons.length)result.status='INVALID';
  }catch(error){result.status='INVALID';result.error=error.message;result.reasons=[...(result.reasons||[]),error.message];result.fatal=true;}finally{
    if(interrupted){result.status='INCOMPLETE';result.fatal=true;result.reasons=['Interrupted; evidence retained'];}
    await save(directory,'validity.json',result);await seal(directory);await verifyManifest(directory);
  }
  console.log(JSON.stringify(result));return result;
}
export async function capture(context,label,referenceRun, options={}){
  if(options.continuationOnly&&!['C1-repeat','C2-repeat'].includes(label))throw new Error('Continuation refuses original capture labels');
  const id=runId(`CAL-${label}`);const directory=path.join(root,'results',id);
  if(options.allocated)await options.allocated(id);
  await mkdir(directory);
  const mode=label.startsWith('C1')?'runner':label.startsWith('C2')?'independent':'both';
  const result={runId:id,label,mode,status:'INCONCLUSIVE',reasons:[],referenceRun:referenceRun.runId};
  const captureWall=Date.now();const captureMonotonic=process.hrtime.bigint();
  let stats=null;let statsWrites=Promise.resolve();let statsBuffer='';
  try{
    const before=await snapshot(context,directory,'initial-state');
    const logical=before.host.cpu.reduce((sum,item)=>sum+item.NumberOfLogicalProcessors,0);
    await save(directory,'environment.json',{utc:new Date().toISOString(),sourceCommit:execFileSync('git',['rev-parse','HEAD'],{cwd:repo,encoding:'utf8'}).trim(),
      logicalProcessors:logical,node:process.version,host:before.host,applicationProfile:'unchanged production profile; see initial-state stack info/metrics',
      workload:'none',mode,referenceRun:referenceRun.runId,
      collectorVariant:'C1 typeperf runner method supplemented with same-query PID and hardware counters; overhead conclusions apply to this explicit variant, not retrospectively to original qualification'});
    const tools=['calibrate-telemetry.mjs','calibration-analysis.mjs','host-policy.mjs','Collect-QualificationHost.ps1'];
    await save(directory,'configuration-redacted.json',{project:context.project,workers:context.settings.DISTROQ_WORKER_CONCURRENCY,
      settingsChanged:false,toolHashes:await Promise.all(tools.map(async name=>({name,sha256:createHash('sha256').update(await readFile(path.join(root,'scripts',name))).digest('hex')})))});
    const startedUtc=new Date().toISOString();
    result.startedUtc=startedUtc;
    stats=start('docker',['stats','--no-trunc','--format','{{json .}}',...Object.values(context.services).map(item=>item.Id)]);
    stats.child.stdout.on('data',chunk=>{
      statsBuffer+=chunk.toString();let end;
      while((end=statsBuffer.indexOf('\n'))>=0){const row=stripVTControlCharacters(statsBuffer.slice(0,end)).trim();statsBuffer=statsBuffer.slice(end+1);
        if(row)statsWrites=statsWrites.then(()=>appendFile(path.join(directory,'container-metrics.jsonl'),JSON.stringify({utcMs:Date.now(),...JSON.parse(row)})+'\n'));
      }
    });
    const collectors=[];const instrumentation=[process.pid,stats.child.pid];
    const orchestrator={pid:process.pid,name:'node',identityVerified:true,startedMs:Date.now(),endedMs:null};
    const tracked=[orchestrator,stats.identity];
    if(mode!=='runner'){
      const task=start('powershell.exe',['-NoProfile','-NonInteractive','-File',path.join(root,'scripts','Collect-QualificationHost.ps1'),
        '-Output',path.join(directory,'independent'),'-Seconds','300','-OrchestratorPid',String(process.pid),'-CalibrationIdle']);
      instrumentation.push(task.child.pid);tracked.push(task.identity);collectors.push({kind:'independent',task});
    }
    if(mode!=='independent'){
      const task=start('typeperf',['\\Processor(_Total)\\% Processor Time','\\Memory\\Available MBytes','\\Process(*)\\% Processor Time','\\Process(*)\\ID Process',
        '\\Processor Information(_Total)\\Processor Frequency','\\Processor Information(_Total)\\% Performance Limit',
        '\\Processor Information(_Total)\\Performance Limit Flags','-si','5','-sc','62']);
      instrumentation.push(task.child.pid);tracked.push(task.identity);collectors.push({kind:'runner',task});
    }
    await save(directory,'collector-ownership.json',{instrumentation,benchmarkHostPids:[],generatorPids:[],
      wmi:'Provider CPU is measured separately but not causally attributed or subtracted',
      vm:'Unattributed VM and Docker backend CPU stays in host residual; Docker stats reported separately'});
    const completed=await Promise.all(collectors.map(async collector=>({...collector,result:await collector.task.done})));
    result.finishedUtc=new Date().toISOString();
    orchestrator.endedMs=Date.now();
    stats.child.kill();await stats.done;await statsWrites;stats=null;
    await save(directory,'tracked-process-lifetimes.json',{tracked,verification:'Owned ChildProcess PID from spawn; lifetime ends on child close. Parent PID is process.pid. Sample PID must match and lifetime must overlap; names only cross-check identity, never assign ownership.'});
    const containerRows=await rows(path.join(directory,'container-metrics.jsonl'));
    result.containerCpu={};
    for(const item of Object.values(context.services)){
      const measured=containerRows.filter(row=>row.Name===item.Name.slice(1));
      result.containerCpu[item.Name.slice(1)]={samples:measured.length,cpuRawPerLogicalCorePercent:distribution(measured.map(row=>parseFloat(row.CPUPerc))),
        note:'Docker CPU percentage is per logical CPU; no conversion to Windows host accounting or subtraction'};
      if(measured.length<59)result.reasons.push('Container telemetry coverage incomplete');
    }
    for(const collector of completed){
      await save(directory,`${collector.kind}-stdout.log`,collector.result.stdout);await save(directory,`${collector.kind}-stderr.log`,collector.result.stderr);
      if(collector.result.code!==0)result.reasons.push(`${collector.kind} collector exited ${collector.result.code}`);
      const parsed=collector.kind==='runner'?parseRunnerCsv(collector.result.stdout):parseIndependent(await rows(path.join(directory,'independent','samples.jsonl')));
      result[collector.kind]=summarizeCollector(parsed.samples,logical,{tracked},parsed.hostInvalidCount);
      result[collector.kind].invalidDiagnosticCounterCount=parsed.invalidCount;
      result[collector.kind].invalidCounterDetails=parsed.invalidDetails||[];
      await save(directory,`${collector.kind}-normalized.json`,result[collector.kind]);
      if(!result[collector.kind].valid)result.reasons.push(...result[collector.kind].reasons.map(reason=>`${collector.kind}: ${reason}`));
    }
    if(mode==='both'){
      const first=result.runner.accounting.observations||[];const second=result.independent.accounting.observations||[];
      const pairs=first.map(row=>({first:row,second:second.reduce((best,item)=>!best||Math.abs(item.utcMs-row.utcMs)<Math.abs(best.utcMs-row.utcMs)?item:best,null)}))
        .filter(pair=>pair.second&&Math.abs(pair.first.utcMs-pair.second.utcMs)<=3000);
      result.pairedCollectorComparison={pairs:pairs.length,maximumAlignmentMs:3000,
        totalCpuDifferencePercentagePoints:distribution(pairs.map(pair=>pair.second.totalCpuPercent-pair.first.totalCpuPercent)),
        wmiCpuDifferencePercentagePoints:distribution(pairs.map(pair=>pair.second.wmiCpuPercent-pair.first.wmiCpuPercent))};
    }
    const after=await snapshot(context,directory,'final-state');
    if(before.host.powerPlan!==after.host.powerPlan)result.reasons.push('Power plan changed');
    if(JSON.stringify(before.host.containers)!==JSON.stringify(after.host.containers))result.reasons.push('Running container identity/lifecycle/mounts changed');
    const omitUtc=value=>{const copy={...value};delete copy.utc;return JSON.stringify(copy);};
    if(omitUtc(before.state)!==omitUtc(after.state)||JSON.stringify(transportIdentity(before.transport))!==JSON.stringify(transportIdentity(after.transport)))result.reasons.push('Durable/transport state changed during idle capture');
    const query=`*[System[(EventID=42 or EventID=107 or EventID=506 or EventID=507) and TimeCreated[@SystemTime>='${startedUtc}']]]`;
    const events=await start('wevtutil',['qe','System',`/q:${query}`,'/f:xml']).done;
    await save(directory,'power-events.xml',events.stdout);
    if(events.code!==0||events.stdout.trim())result.reasons.push('Sleep/resume or unavailable power event audit');
    if(Math.abs(Date.now()-captureWall-Number(process.hrtime.bigint()-captureMonotonic)/1e6)>1000)result.reasons.push('System clock stepped materially');
    result.status=result.reasons.length?'INVALID':'VALID';
  }catch(error){result.reasons.push(error.message);}finally{
    if(stats){stats.child.kill();await stats.done;await statsWrites.catch(()=>{});}
    if(interrupted){result.status='INCOMPLETE';result.reasons.push('Interrupted; evidence retained');}
    await save(directory,'validity.json',result);await seal(directory);await verifyManifest(directory);
  }
  console.log(JSON.stringify({runId:id,status:result.status,reasons:result.reasons,
    diagnosticWarnings:{runner:result.runner?.warnings,independent:result.independent?.warnings}}));return result;
}

async function main(){
  process.chdir(repo);
  const args=process.argv.slice(2);const project=args.find(arg=>/^distroq-bench-\d{14}-[a-f0-9]{6}$/.test(arg));
  if(!project)throw new Error('Explicit benchmark project required');
  const checkOnly=args.includes('--check-only');
  if(!checkOnly&&(!args.includes('--exclusive-host-confirmed')||process.env.TERM_PROGRAM==='vscode'))throw new Error('Standalone reserved host required; do not run from VS Code');
  if(execFileSync('git',['branch','--show-current'],{encoding:'utf8'}).trim()!=='benchmark-v1.1')throw new Error('Wrong branch');
  if(execFileSync('git',['diff','4f31ada742846859cf67d76e72b8813f90877ac2','--','src','dashboard','pom.xml','Dockerfile'],{encoding:'utf8'}).trim())throw new Error('Application baseline changed');
  const context=await connect(project);
  const plan={project,mode:'CALIBRATION_ONLY',captures:['C1','C2','C3','C1-repeat','C2-repeat'],durationEachSeconds:300,
    referenceBeforeEachSeconds:60,referenceSettlingTolerancePercentagePoints:2,repeatabilityTolerancePercentagePoints:2,
    noJobs:true,startsP1:false,startsP2:false,canonicalCollector:null,
    note:'Idle gate uses authoritative host total, without subtraction. Diagnostic degradation is reported; tracked-PID and required-counter failures are fatal. No automatic Process V2 switch or P1 launch.'};
  console.log(JSON.stringify(plan,null,2));if(checkOnly)return;
  await mkdir(path.join(root,'state'),{recursive:true});const lockPath=path.join(root,'state','qualification.lock');const lock=await open(lockPath,'wx');
  const captures=[];const references=[];let error=null;
  try{
    await lock.writeFile(JSON.stringify({pid:process.pid,project,mode:'calibration'}));
    execFileSync('node',[path.join(root,'scripts','audit-tooling.mjs'),'--verify-only'],{stdio:'inherit'});
    for(const label of plan.captures){
      const referenceRun=await reference(context);references.push(referenceRun);
      if(referenceRun.status!=='VALID'){error='Reference did not settle; stop before further captures';break;}
      const measured=await capture(context,label,referenceRun);captures.push(measured);
      if(measured.status!=='VALID'){error='Calibration capture invalid; P1 gate closed';break;}
    }
  }catch(caught){error=caught.message;}finally{await lock.close();await unlink(lockPath);}
  const referenceBaseline=validReferenceBaseline(references,captures);
  const referenceCpu=referenceBaseline.mean;
  const find=label=>captures.find(row=>row.label===label);
  const comparison={plan,referenceBaseline,references:references.map(row=>({runId:row.runId,status:row.status,hostCpu:row.hostCpu,
    settlingChangePercentagePoints:row.settlingChangePercentagePoints})),
    captures:captures.map(row=>({runId:row.runId,label:row.label,status:row.status,reasons:row.reasons})),
    observerOverheadUsingC3Runner:overheadEstimates(find('C1')?.runner?.hostCpu.mean,find('C2')?.independent?.hostCpu.mean,find('C3')?.runner?.hostCpu.mean,referenceCpu),
    observerOverheadUsingC3Independent:overheadEstimates(find('C1')?.runner?.hostCpu.mean,find('C2')?.independent?.hostCpu.mean,find('C3')?.independent?.hostCpu.mean,referenceCpu),
    repeatability:{runner:candidateRepeatability(find('C1')?.runner,find('C1-repeat')?.runner),independent:candidateRepeatability(find('C2')?.independent,find('C2-repeat')?.independent)},
    status:error?'BLOCKED':'NEEDS_REVIEW',error,canonicalCollector:null,p1:'NOT RUN',p2:'NOT RUN',
    gate:'Never starts P1 automatically. Quantified overhead, independent repeatability and attribution must be reviewed before selecting canonical instrumentation.'};
  const report=path.join(root,'results',runId('CALIBRATION-REVIEW'));await mkdir(report);await save(report,'comparison.json',comparison);await seal(report);await verifyManifest(report);
  console.log(report);console.log(JSON.stringify(comparison,null,2));
  if(error)process.exitCode=2;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(error=>{console.error(error.message);process.exitCode=2;});