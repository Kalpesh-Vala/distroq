import { readFile, readdir, stat, mkdir } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { seal } from './capacity-runner.mjs';
import { writeFile } from 'node:fs/promises';

const root=path.resolve('performance');
const source='20260912T141143347Z-IDLE-GATE-be0e0485';
const decode=text=>JSON.parse(text.replace(/^\uFEFF/,''));
const rows=(await readFile(path.join(root,'results',source,'idle-samples.jsonl'),'utf8')).replace(/^\uFEFF/,'').trim().split(/\r?\n/).map(JSON.parse);
const totals=new Map();
const known=rows.map(row=>{
  let sum=0;
  for(const proc of row.processes){
    if(proc.status!==0 || !Number.isFinite(proc.rawCpuPercent))continue;
    if(proc.classification==='non-benchmark')sum+=proc.normalizedCpuPercent;
    const key=proc.name;
    const value=totals.get(key)||{name:proc.name,classification:proc.classification,raw:0,normalized:0,validSamples:0};
    value.raw+=proc.rawCpuPercent;value.normalized+=proc.normalizedCpuPercent;value.validSamples++;totals.set(key,value);
  }
  return sum;
});
async function files(directory){
  const output=[];
  for(const entry of await readdir(directory,{withFileTypes:true})){
    if(['private','node_modules'].includes(entry.name))continue;
    const full=path.join(directory,entry.name);
    if(entry.isDirectory())output.push(...await files(full));else output.push(full);
  }
  return output;
}
const inventory=await files(root);
let verified=0;
for(const manifest of inventory.filter(file=>path.basename(file)==='manifest.json')){
  const decoded=decode(await readFile(manifest,'utf8'));
  for(const entry of Array.isArray(decoded)?decoded:[decoded]){
    const actual=createHash('sha256').update(await readFile(path.join(path.dirname(manifest),entry.file||entry.File))).digest('hex');
    if(actual.toUpperCase()!==(entry.sha256||entry.Hash).toUpperCase())throw new Error(`Checksum mismatch: ${manifest}`);
    verified++;
  }
}
const app=JSON.parse(execFileSync('docker',['inspect','563ea44afd6d'],{encoding:'utf8'}))[0];
const secrets=app.Config.Env.filter(value=>/^(DISTROQ_ADMIN_TOKEN|DISTROQ_DB_PASSWORD|DISTROQ_REDIS_PASSWORD)=/.test(value)).map(value=>value.slice(value.indexOf('=')+1));
if(secrets.length!==3)throw new Error('Generated-secret scan cannot be verified');
const secretHits=[];
const payloadHits=[];
const sizes=[];
for(const file of inventory){
  const text=await readFile(file,'utf8');
  if(secrets.some(secret=>secret && text.includes(secret)))secretHits.push(path.relative(root,file));
  if(file.includes(`${path.sep}results${path.sep}`)&&/"(?:payload|idempotency_key|idempotencyKey)"\s*:/.test(text))payloadHits.push(path.relative(root,file));
  sizes.push({file:path.relative(root,file).replaceAll('\\','/'),bytes:(await stat(file)).size});
}
const hardware=rows.flatMap(row=>row.hardware).filter(counter=>counter.Status===0);
const limits=hardware.filter(counter=>counter.Path.endsWith('\\% performance limit')).map(counter=>counter.CookedValue);
const flags=hardware.filter(counter=>counter.Path.endsWith('\\performance limit flags')).map(counter=>counter.CookedValue);
const editorOnly=rows.map(row=>row.processes.filter(proc=>proc.status===0 && /^code(?:#\d+)?$/i.test(proc.name)).reduce((sum,proc)=>sum+proc.normalizedCpuPercent,0));
const report={
  sourceRun:source,status:'FAIL',qualification:'NOT RUN; environment gate failed',p1:'NOT RUN',p2:'NOT RUN',
  samples:rows.length,sampleSpanSeconds:(rows.at(-1).utcMs-rows[0].utcMs)/1000,
  knownCompetition:{meanNormalizedPercent:known.reduce((sum,value)=>sum+value,0)/rows.length,
    samplesOver20Percent:known.filter(value=>value>20).length,fractionOver20Percent:known.filter(value=>value>20).length/rows.length,
    interpretation:'Only status-valid counters included. Missing process counters and mixed VM CPU are unallocated; this is a known-competition lower bound, not a complete host accounting.'},
  invalidProcessCounters:rows.flatMap(row=>row.processes).filter(proc=>proc.status!==0).length,
  editorOnlyLowerBound:{meanNormalizedPercent:editorOnly.reduce((sum,value)=>sum+value,0)/rows.length,
    samplesOver20Percent:editorOnly.filter(value=>value>20).length,
    interpretation:'All status-valid Code process counters combined; excludes every other process including mixed VM, Docker and collector. Sufficient independent evidence for mean contention rule.'},
  topProcessFamilies:[...totals.values()].map(value=>({name:value.name,classification:value.classification,validCounterObservations:value.validSamples,
    rawMeanCpuPercent:value.raw/rows.length,normalizedMeanCpuPercent:value.normalized/rows.length})).sort((left,right)=>right.normalizedMeanCpuPercent-left.normalizedMeanCpuPercent).slice(0,10),
  performanceLimit:{minimum:Math.min(...limits),maximum:Math.max(...limits),nonzeroFlags:flags.filter(value=>value!==0).length,
    caveat:'Idle counters do not establish loaded thermal behavior. No sustained thermal-throttling claim.'},
  reportCorrections:['Original gate topProcesses sorting used hashtables under PowerShell; use this independently sorted list.',
    'Original aggregate included status-invalid counters; use valid-only values. Original evidence remains unchanged.',
    'Counter InstanceName collapses same-name process instances for PID mapping. Original and first derived per-PID attribution is invalid; this report groups by process family only.',
    'Aggregate classification includes ambiguous infrastructure/collector CPU. Use editorOnlyLowerBound as the independent fail proof, not the full aggregate as an exact competition total.'],
  hashesVerified:verified,secretScan:{files:inventory.length,generatedSecretHits:secretHits,payloadFieldHits:payloadHits,
    scope:'Known generated credentials and explicit payload/key field names; not a general proof of absence of sensitive content.'},
  inventoryBeforeThisReport:{files:sizes.length,bytes:sizes.reduce((sum,item)=>sum+item.bytes,0),largestTen:sizes.sort((left,right)=>right.bytes-left.bytes).slice(0,10)},
  gitStatus:execFileSync('git',['status','--short','--branch'],{encoding:'utf8'}),
  trackedPerformancePaths:execFileSync('git',['ls-files','performance'],{encoding:'utf8'}).trim(),
  policy:'No commit. Keep raw telemetry outside Git unless approved; scripts, pinned tooling lockfile and reviewed reports are candidates for commit.'
};
if(report.knownCompetition.meanNormalizedPercent<=10 && report.knownCompetition.fractionOver20Percent<=0.1)report.status='INCONCLUSIVE';
const output=path.join(root,'results',`${new Date().toISOString().replace(/[-:.]/g,'')}-IDLE-REVIEW-${randomUUID().slice(0,8)}`);
await mkdir(output);
await writeFile(path.join(output,'review.json'),JSON.stringify(report,null,2),{flag:'wx'});
await seal(output);
console.log(output);
console.log(JSON.stringify(report,null,2));