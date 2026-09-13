import { spawn, execFileSync } from 'node:child_process';
import { mkdir, writeFile, open, unlink } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { connect, runPoint, seal } from './capacity-runner.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const repo=path.dirname(root);

export async function prepareCampaign(actions) {
  await actions.audit();
  await actions.create();
}

export async function qualificationFlow(actions) {
  const idle=await actions.idle();
  if(!idle.valid)return {status:'INVALID',stoppedAt:'idle',idle};
  const warmup=await actions.warmup();
  if(!warmup.valid)return {status:'INVALID',stoppedAt:'warmup',idle,warmup};
  const measured=await actions.measured(warmup.runId);
  return {status:measured.valid?'VALID':'INVALID',stoppedAt:'qualification',idle,warmup,measured,
    limitation:'Qualification only; no P1/P2 results or sustainable capacity claim.'};
}

function hostWindow(directory,seconds) {
  const output=path.join(directory,'host-window');
  const child=spawn('powershell.exe',['-NoProfile','-NonInteractive','-File',path.join(root,'scripts','Collect-QualificationHost.ps1'),
    '-Output',output,'-Seconds',String(seconds),'-OrchestratorPid',String(process.pid)],{cwd:repo,windowsHide:true,stdio:['ignore','pipe','pipe']});
  let text='';
  child.stdout.on('data',chunk=>{text+=chunk;process.stdout.write(chunk);});
  child.stderr.on('data',chunk=>{text+=chunk;});
  return new Promise((resolve,reject)=>{
    child.on('error',reject);
    child.on('close',async code=>{
      try {await writeFile(path.join(directory,'host-console.log'),text,{flag:'wx'});resolve({valid:code===0,exitCode:code,output});}catch(error){reject(error);}
    });
  });
}

async function main() {
  const args=process.argv.slice(2);
  const checkOnly=args.includes('--check-only');
  const project=args.find(value=>/^distroq-bench-\d{14}-[a-f0-9]{6}$/.test(value));
  if(!project)throw new Error('Explicit benchmark project required');
  if(!checkOnly&&!args.includes('--exclusive-host-confirmed'))throw new Error('Exclusive host, dashboard closed, AC and Docker-only WSL attestation required');
  process.chdir(repo);
  const branch=execFileSync('git',['branch','--show-current'],{encoding:'utf8'}).trim();
  if(branch!=='benchmark-v1.1')throw new Error('Wrong branch');
  if(execFileSync('git',['diff','4f31ada742846859cf67d76e72b8813f90877ac2','--','src','dashboard','pom.xml','Dockerfile','docker-compose.production.yml'],{encoding:'utf8'}).trim())throw new Error('Application baseline changed');
  const context=await connect(project);
  if(Number(context.settings.DISTROQ_WORKER_CONCURRENCY)!==1)throw new Error('Qualification requires existing worker concurrency 1; no reconfiguration performed');
  const ownership=Object.entries(context.services).map(([service,item])=>{
    if(!item.State.Running||item.State.Paused||item.State.Health?.Status!=='healthy')throw new Error(`Service not healthy: ${service}`);
    for(const mount of item.Mounts.filter(mount=>mount.Type==='volume')){
      const volume=JSON.parse(execFileSync('docker',['volume','inspect',mount.Name],{encoding:'utf8'}))[0];
      if(volume.Labels?.['com.docker.compose.project']!==project)throw new Error('Volume belongs to another project');
    }
    return {service,id:item.Id,image:item.Image,startedAt:item.State.StartedAt,restarts:item.RestartCount,
      volumes:item.Mounts.filter(mount=>mount.Type==='volume').map(mount=>mount.Name)};
  });
  const plan={mode:checkOnly?'CHECK_ONLY':'QUALIFICATION_ONLY',project,rate:1,workload:'W1',workers:1,
    idleSeconds:300,warmupSeconds:120,qualificationSeconds:300,drainTimeoutSeconds:120,startsP1P2:false,
    applicationChanges:false,ownership,credentials:'Read existing container environment in memory; never printed or stored',
    notes:'First standalone run validates runtime integration. Check-only does not certify host telemetry or runtime correctness.'};
  console.log(JSON.stringify(plan,null,2));
  if(checkOnly)return;
  await mkdir(path.join(root,'state'),{recursive:true});
  const lockPath=path.join(root,'state','qualification.lock');
  const lock=await open(lockPath,'wx');
  const campaign=path.join(root,'results',`${new Date().toISOString().replace(/[-:.]/g,'')}-QUALIFICATION-CAMPAIGN-${randomUUID().slice(0,8)}`);
  let campaignCreated=false;
  let result={status:'INCONCLUSIVE'};
  const capture=async(name,seconds,load)=>{
    const current=await connect(project);
    for(const [service,item] of Object.entries(current.services)){
      const initial=context.services[service];
      if(item.Id!==initial.Id||item.State.StartedAt!==initial.State.StartedAt||item.RestartCount!==initial.RestartCount
        ||JSON.stringify(item.Config.Env)!==JSON.stringify(initial.Config.Env))throw new Error('Benchmark service identity or configuration changed');
    }
    const directory=path.join(campaign,name);await mkdir(directory);
    if(!load){const host=await hostWindow(directory,seconds);return {valid:host.valid};}
    const monitor=hostWindow(directory,seconds+10);
    let point;
    let host;
    try { point=await load(); } finally { host=await monitor; }
    return {valid:point.valid&&host.valid,runId:point.runId,pointValid:point.valid,hostValid:host.valid,errors:point.errors};
  };
  try {
    await lock.writeFile(JSON.stringify({pid:process.pid,project,campaign}));
    await prepareCampaign({
      audit:()=>execFileSync('node',[path.join(root,'scripts','audit-tooling.mjs'),'--verify-only'],{cwd:repo,stdio:'inherit'}),
      create:async()=>{await mkdir(campaign);campaignCreated=true;},
    });
    await writeFile(path.join(campaign,'plan.json'),JSON.stringify(plan,null,2),{flag:'wx'});
    result=await qualificationFlow({
      idle:()=>capture('idle',300),
      warmup:()=>capture('warmup',120,()=>runPoint(context,{phase:'WARMUP',workload:'W1',rate:1,seconds:120,workers:1,hostReservation:true})),
      measured:warmupRun=>capture('measured',300,()=>runPoint(context,{phase:'QUALIFICATION',workload:'W1',rate:1,seconds:300,workers:1,warmupRun,hostReservation:true})),
    });
  } catch(error){result={status:'INCONCLUSIVE',error:error.message};} finally {
    try {
      if(campaignCreated){
        await writeFile(path.join(campaign,'qualification.json'),JSON.stringify(result,null,2),{flag:'wx'});
        await seal(campaign);
      }
    } finally {await lock.close();await unlink(lockPath);}
  }
  console.log(JSON.stringify({campaign:campaignCreated?campaign:null,...result},null,2));
  if(result.status!=='VALID')process.exitCode=2;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(error=>{console.error(error.message);process.exitCode=2;});