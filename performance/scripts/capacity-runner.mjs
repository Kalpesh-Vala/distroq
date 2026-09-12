import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile, appendFile, readdir, chmod, copyFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { stripVTControlCharacters } from 'node:util';
import { parse } from 'csv-parse/sync';
import { cpus } from 'node:os';
import { hostContention } from './host-policy.mjs';
import { reconcile, analyzeLatency, stability } from './capacity-analysis.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = path.dirname(root);
const k6Image = 'grafana/k6@sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d';
const projectPattern = /^distroq-bench-\d{14}-[a-f0-9]{6}$/;
const safeId = /^[a-f0-9-]{36}$/;
const json = value => JSON.stringify(value, null, 2);
const stamp = () => new Date().toISOString().replace(/[-:.]/g, '');

async function command(program, args, { input, env, timeout = 30000, allowFailure = false } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(program, args, { cwd: repo, env: env || process.env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.on('error', reject);
    const timer = setTimeout(() => child.kill(), timeout);
    child.on('close', code => {
      clearTimeout(timer);
      if (code !== 0 && !allowFailure) reject(new Error(`${program} ${args[0]} failed (exit ${code}); no raw stderr retained to avoid secret disclosure`));
      else resolve({ code, stdout, stderr });
    });
    child.stdin.end(input);
  });
}
const docker = async (args, options) => (await command('docker', args, options)).stdout.trim();
async function save(directory, filename, value) {
  const destination = path.join(directory, filename);
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, typeof value === 'string' ? value : json(value), { flag: 'wx' });
}
async function line(directory, filename, value) {
  await appendFile(path.join(directory, filename), JSON.stringify(value) + '\n');
}
async function allFiles(directory) {
  const output = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) output.push(...await allFiles(full));
    else output.push(full);
  }
  return output;
}
export async function seal(directory) {
  const entries = [];
  for (const full of await allFiles(directory)) {
    entries.push({ file: path.relative(directory, full).replaceAll('\\', '/'), sha256: createHash('sha256').update(await readFile(full)).digest('hex').toUpperCase() });
  }
  await save(directory, 'manifest.json', entries);
  await save(directory, 'checksums.sha256', entries.map(entry => `${entry.sha256}  ${entry.file}`).join('\n') + '\n');
  for (const full of await allFiles(directory)) await chmod(full, 0o444);
}

export async function connect(project) {
  if (!projectPattern.test(project)) throw new Error('Explicit isolated benchmark project required');
  const ids = (await docker(['ps', '-q', '--filter', `label=com.docker.compose.project=${project}`])).split(/\s+/).filter(Boolean);
  if (ids.length !== 3) throw new Error('Exactly three running benchmark services required');
  const raw = JSON.parse(await docker(['inspect', ...ids]));
  const services = Object.fromEntries(raw.map(item => [item.Config.Labels['com.docker.compose.service'], item]));
  for (const name of ['app', 'postgres', 'redis']) if (!services[name]) throw new Error(`Missing ${name}`);
  const settings = Object.fromEntries(services.app.Config.Env.map(entry => {
    const offset = entry.indexOf('='); return [entry.slice(0, offset), entry.slice(offset + 1)];
  }));
  const port = services.app.NetworkSettings.Ports['8080/tcp'][0].HostPort;
  const context = { project, services, settings, base: `http://127.0.0.1:${port}` };
  context.sql = async query => {
    const text = await docker(['exec', '-i', services.postgres.Id, 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'benchmark', '-d', 'benchmark'],
      { input: `BEGIN READ ONLY; SET LOCAL statement_timeout='4000ms'; ${query}\nCOMMIT;`, timeout: 10000 });
    return JSON.parse(text);
  };
  context.redis = async args => {
    const output = await docker(['exec', services.redis.Id, 'redis-cli', '--json', ...args], { timeout: 8000 });
    if (output.startsWith('error:') || output.startsWith('ERR')) throw new Error('Redis diagnostic failed');
    return JSON.parse(output);
  };
  return context;
}

const databaseQuery = since => `SELECT json_build_object('utc',clock_timestamp(),
 'jobs',(SELECT count(*) FROM jobs),
 'cohort',(SELECT json_build_object('created',count(*),'succeeded',count(*) FILTER(WHERE status='SUCCEEDED'),'dead',count(*) FILTER(WHERE status='DEAD_LETTERED'),
 'backlog',count(*) FILTER(WHERE status IN ('QUEUED','RUNNING','RETRYING') OR (status='SCHEDULED' AND scheduled_at<=clock_timestamp())),
 'running',count(*) FILTER(WHERE status='RUNNING'),'scheduled',count(*) FILTER(WHERE status='SCHEDULED'),
 'retrying',count(*) FILTER(WHERE status='RETRYING'),'attempts',coalesce(sum(attempt_count),0),
 'expiredLeases',count(*) FILTER(WHERE execution_lease_until<clock_timestamp()),
 'oldestUnfinishedSeconds',coalesce(extract(epoch FROM clock_timestamp()-min(created_at) FILTER(WHERE status NOT IN ('SUCCEEDED','DEAD_LETTERED'))),0)) FROM jobs WHERE created_at>='${since}'),
 'unsettled',(SELECT count(*) FROM jobs WHERE status NOT IN ('SUCCEEDED','DEAD_LETTERED')),
 'outbox',(SELECT json_object_agg(status,total) FROM (SELECT status,count(*) total FROM outbox_events WHERE created_at>='${since}' GROUP BY status) counts),
 'unpublished',(SELECT count(*) FROM outbox_events WHERE status<>'PUBLISHED'),
 'auditActions',(SELECT count(*) FROM reliability_actions),
 'connections',(SELECT json_agg(row_to_json(activity)) FROM (SELECT state,wait_event_type,count(*) FROM pg_stat_activity WHERE datname=current_database() GROUP BY state,wait_event_type) activity),
 'database',(SELECT row_to_json(stats) FROM (SELECT numbackends,xact_commit,xact_rollback,blks_read,blks_hit,tup_inserted,tup_updated,deadlocks,temp_bytes,stats_reset FROM pg_stat_database WHERE datname=current_database()) stats));`;

const redisSampleLua = `local result={}; for _,tier in ipairs({'high','normal','low'}) do local key='distroq:jobs:stream:'..tier; local groups=redis.call('XINFO','GROUPS',key); local group={}; for _,row in ipairs(groups) do for index=1,#row,2 do group[row[index]]=row[index+1] end end; result[tier]={length=redis.call('XLEN',key),lag=group.lag,pending=group.pending}; end; result.delayed=redis.call('ZCARD','distroq:jobs:delayed'); result.scheduled=redis.call('ZCARD','distroq:jobs:scheduled'); return cjson.encode(result)`;
function parseInfo(text) {
  return Object.fromEntries(text.split(/\r?\n/).filter(row => row && !row.startsWith('#') && row.includes(':')).map(row => {
    const index = row.indexOf(':'); return [row.slice(0, index), row.slice(index + 1)];
  }));
}
async function redisState(context) {
  const [transport, info] = await Promise.all([context.redis(['EVAL_RO', redisSampleLua, '0']),
    docker(['exec',context.services.redis.Id,'redis-cli','--raw','INFO','ALL'],{timeout:8000})]);
  return { transport: JSON.parse(transport), info: parseInfo(info) };
}
async function health(context) {
  const response = await fetch(`${context.base}/actuator/health/readiness`, { signal: AbortSignal.timeout(5000) });
  return { status: response.status, body: await response.json() };
}
function settled(database, redis) {
  return database.unsettled === 0 && database.unpublished === 0 && redis.transport.delayed === 0 && redis.transport.scheduled === 0
    && ['high','normal','low'].every(tier => redis.transport[tier].lag === 0 && redis.transport[tier].pending === 0);
}
async function clockSample(context) {
  const sent = Date.now();
  const response = await fetch(`${context.base}/api/dashboard/system`, { headers: { Authorization: `Bearer ${context.settings.DISTROQ_ADMIN_TOKEN}` }, signal: AbortSignal.timeout(5000) });
  const body = await response.json();
  const received = Date.now();
  if (response.status !== 200 || !body.timestamp) throw new Error('Clock probe unavailable');
  const remote = Date.parse(body.timestamp);
  return { sentMs: sent, receivedMs: received, lowerMs: remote - received, upperMs: remote - sent, source: 'one non-polling system probe outside measured arrivals' };
}
async function cohort(context, since, runId) {
  const jobs = [];
  let lastId = '00000000-0000-0000-0000-000000000000';
  for (;;) {
    const batch = await context.sql(`SELECT coalesce(json_agg(row_to_json(evidence)),'[]') FROM (
      SELECT job.id,job.type,job.status,job.priority,job.attempt_count,job.created_at,job.scheduled_at,job.finished_at,
      job.execution_owner,job.active_attempt_id,
      (SELECT coalesce(json_agg(row_to_json(attempt) ORDER BY attempt.attempt_number),'[]') FROM
        (SELECT attempt_number,outcome,started_at,finished_at FROM job_attempts WHERE job_id=job.id) attempt) attempts,
      (SELECT coalesce(json_agg(row_to_json(event)),'[]') FROM
        (SELECT id,event_type,status,created_at,published_at,attempt_count FROM outbox_events WHERE aggregate_id=job.id) event) outbox
      FROM jobs job WHERE job.created_at>='${since}' AND job.id>'${lastId}' ORDER BY job.id LIMIT 500) evidence;`);
    jobs.push(...batch);
    if (batch.length < 500) break;
    lastId = batch.at(-1).id;
  }
  const effects = await context.sql(`SELECT coalesce(json_agg(row_to_json(effect)),'[]') FROM (SELECT job_id,status,attempt_number FROM job_effects WHERE job_id IN (SELECT id FROM jobs WHERE created_at>='${since}')) effect;`);
  const counters = await context.sql(`SELECT coalesce(json_agg(row_to_json(counter)),'[]') FROM (SELECT counter_value FROM effect_counters WHERE counter_name='${runId.toLowerCase()}') counter;`);
  return { jobs, effects, counters };
}

function captureStream(child, directory, filename, onRow) {
  let pending = '';
  let writes = Promise.resolve();
  child.stdout.on('data', chunk => {
    pending += chunk.toString();
    let end;
    while ((end = pending.indexOf('\n')) >= 0) {
      const row = stripVTControlCharacters(pending.slice(0, end)).trim(); pending = pending.slice(end + 1);
      if (!row) continue;
      writes = writes.then(async () => {
        const value = onRow ? onRow(row) : { utcMs: Date.now(), raw: row };
        if (value) await line(directory, filename, value);
      });
    }
  });
  return async () => { child.kill(); await writes; };
}

export async function runPoint(context, { phase, workload, rate, seconds, workers, repetition = 0, drainSeconds = 120, warmupRun = null, hostReservation = false }) {
  if (!['SHAKEDOWN','WARMUP','QUALIFICATION','P1','EXPLORE','P2','BOUNDARY'].includes(phase) || !['W1','W2','W3'].includes(workload)) throw new Error('Invalid point');
  if (![rate, seconds, workers].every(value => Number.isInteger(value) && value > 0)) throw new Error('Positive integer rate, duration and worker count required');
  if (!hostReservation) throw new Error('Host reservation must be confirmed');
  if (Number(context.settings.DISTROQ_WORKER_CONCURRENCY) !== workers) throw new Error('Worker setting differs from requested point');
  const runId = `${stamp()}-${phase}-${workload}-n${workers}-r${rate}-${randomUUID().slice(0,8)}`;
  const directory = path.join(root, 'results', runId);
  await mkdir(directory);
  for (const name of ['client-raw','application-metrics','postgres-metrics','redis-metrics','container-metrics','initial-state','final-state']) await mkdir(path.join(directory, name));
  const errors = [];
  const samples = [];
  const containerSamples = [];
  const hostSamples = [];
  let hostHeaders = null;
  const clockBounds = [];
  const monitoring = [];
  const since = await context.sql('SELECT to_json(clock_timestamp());');
  const started = new Date().toISOString();
  const configuration = { project: context.project, commit: (await command('git',['rev-parse','HEAD'])).stdout.trim(), workers,
    appImage: context.services.app.Image, settings: {}, limits: {}, dashboardClients: 0, hostReservation,
    loadGeneratorColocated: true, initialContainerIds: {}, initialContainerStarted: {} };
  for (const [name, value] of Object.entries(context.settings)) {
    if (['JAVA_OPTS','SPRING_PROFILES_ACTIVE','DISTROQ_WORKER_CONCURRENCY'].includes(name)) configuration.settings[name] = value;
    if (/TOKEN|PASSWORD/.test(name)) configuration.settings[name] = value ? 'configured' : 'unset';
  }
  for (const [name, item] of Object.entries(context.services)) {
    configuration.limits[name] = { memory: item.HostConfig.Memory, nanoCpus: item.HostConfig.NanoCpus };
    configuration.initialContainerIds[name] = item.Id;
    configuration.initialContainerStarted[name] = item.State.StartedAt;
  }
  const workloadConfig = { runId, phase, workload, rate, seconds, workers, repetition, drainSeconds, warmupRun,
    W1: 'sleep 0 ms; dispatch overhead only', W2: 'idempotent_counter; one shared cohort counter creates database contention',
    W3: 'deterministic blocks of 20: 14 sleep0, 3 counters, 2 fail_n_times(1), 1 sleep0 scheduled +2000ms',
    collectorIntervalSeconds: 5, cutoffPostgresUtc: since, command: `node performance/scripts/capacity-runner.mjs point ${context.project} ${phase} ${workload} ${rate} ${seconds} ${workers}` };
  await save(directory,'configuration-redacted.json',configuration);
  await save(directory,'workload.json',workloadConfig);
  await copyFile(path.join(root,'workloads','arrivals.js'),path.join(directory,'client-raw','workload.js'));
  let clientExit = null;
  let stopStats = null;
  let stopHost = null;
  let client = null;
  let monitorTimer = null;
  let generatorName = `${context.project}-load-${randomUUID().slice(0,8)}`;
  let lastTick = performance.now();
  let finishedAt = null;
  let drainTime = null;
  let finalState = null;
  let activeSample = null;
  let baseline = null;
  let summary = null;
  let correctness = null;
  let power = '';
  let startClock = Date.now();
  const abort = reason => { if (!errors.includes(reason)) errors.push(reason); if (client && clientExit === null) void docker(['stop','--time','2',generatorName],{allowFailure:true}).catch(()=>{}); };
  try {
    power = (await command('powercfg',['/getactivescheme'])).stdout;
    const [initialDb, initialRedis, initialHealth] = await Promise.all([context.sql(databaseQuery(since)), redisState(context), health(context)]);
    baseline = { database: initialDb, redis: initialRedis, health: initialHealth };
    await save(directory,'initial-state/state.json',baseline);
    if (!settled(initialDb, initialRedis) || initialHealth.status !== 200) throw new Error('Initial state is not ready and settled; historical terminal rows are retained, never reset');
    const environment = { utc: started, hostname: process.env.COMPUTERNAME, node: process.version, platform: process.platform,
      powerScheme: power.trim(), gitStatus: (await command('git',['status','--porcelain'])).stdout.trim(),
      docker: JSON.parse(await docker(['info','--format','{{json .}}'])), toolchain: { k6Image },
      priorTerminalJobCount: initialDb.jobs, limitations: ['Cumulative terminal history retained and recorded; not a reset database.',
      'Host power events and scheme sampled; operator attests dashboard closed and no heavy unrelated work.'] };
    const dockerInfo = environment.docker;
    environment.docker = Object.fromEntries(['ServerVersion','OperatingSystem','NCPU','MemTotal','Architecture'].map(name=>[name,dockerInfo[name]]));
    await save(directory,'environment.json',environment);
    clockBounds.push(await clockSample(context));
    const tokenEnv = { ...process.env, BENCH_ADMIN_TOKEN: context.settings.DISTROQ_ADMIN_TOKEN };
    await docker(['create','--name',generatorName,'--label',`distroq.benchmark.project=${context.project}`,'--network',`${context.project}_default`,
      '-e','BENCH_ADMIN_TOKEN','-e',`RUN_ID=${runId}`,'-e',`WORKLOAD=${workload}`,'-e',`RATE=${rate}`,'-e',`SECONDS=${seconds}`,
      '-e',`PHASE=${phase}`,'-e','VUS=100','-e','EXTERNAL_COLLECTOR=true','-e','DASHBOARD_CLIENTS=0',
      '-v',`${path.join(root,'workloads')}:/workloads:ro`,'-v',`${directory}:/results`,k6Image,
      'run','--log-format','raw','--console-output','/results/client-raw/requests.jsonl','--out','json=/results/client-raw/k6-samples.json','/workloads/arrivals.js'],{env:tokenEnv});
    const stats = spawn('docker',['stats','--no-trunc','--format','{{json .}}',...Object.values(context.services).map(item=>item.Id),generatorName],{windowsHide:true});
    stats.stderr.on('data',()=>{});
    stopStats = captureStream(stats,directory,'container-metrics/samples.jsonl',row=>{
      try { const data = {utcMs:Date.now(),...JSON.parse(row)}; containerSamples.push(data); return data; } catch { errors.push('Malformed Docker stats sample'); return {utcMs:Date.now(),unparsed:row}; }
    });
    const host = spawn('typeperf',['\\Processor(_Total)\\% Processor Time','\\Memory\\Available MBytes','\\Process(*)\\% Processor Time','-si','5'],{windowsHide:true});
    host.stderr.on('data',()=>{});
    stopHost = captureStream(host,directory,'container-metrics/host.jsonl',row=>{
      try {
        const cells=parse(row,{relax_column_count:true})[0];
        if(!hostHeaders){hostHeaders=cells;return {utcMs:Date.now(),columns:cells};}
        if(cells.length!==hostHeaders.length)return {utcMs:Date.now(),diagnostic:row};
        const processes=cells.slice(3).map((value,index)=>({name:hostHeaders[index+3],cpuPercent:Number(value)}));
        const value={utcMs:Date.parse(cells[0]),receivedUtcMs:Date.now(),hostLocalTimestamp:cells[0],cpuPercent:Number(cells[1]),availableMB:Number(cells[2]),processes};
        hostSamples.push(value);return value;
      }catch{return {utcMs:Date.now(),diagnostic:row};}
    });
    host.on('error',()=>errors.push('Host collector unavailable'));
    const collect = async () => {
      const monotonic = performance.now();
      const gap = monotonic-lastTick;
      lastTick=monotonic;
      if (gap > 15000) abort('Material collector/host-sleep gap greater than 15 seconds');
      const tick = Date.now();
      const [db, redis, response] = await Promise.all([context.sql(databaseQuery(since)),redisState(context),fetch(`${context.base}/actuator/prometheus`,{signal:AbortSignal.timeout(5000)})]);
      if (response.status!==200) throw new Error('Prometheus unavailable');
      const prometheus=await response.text();
      const findings=prometheus.split('\n').find(row=>row.startsWith('distroq_reconciliation_findings{'));
      if(findings&&Number(findings.slice(findings.lastIndexOf(' ')+1))>0)abort('Reconciliation findings reported by application');
      await Promise.all([line(directory,'postgres-metrics/samples.jsonl',{utcMs:tick,...db}),line(directory,'redis-metrics/samples.jsonl',{utcMs:tick,...redis}),
        line(directory,'application-metrics/samples.jsonl',{utcMs:tick,prometheus})]);
      const sample={utcMs:tick,backlog:db.cohort.backlog,succeeded:db.cohort.succeeded,running:db.cohort.running,unpublished:db.unpublished};
      samples.push(sample);
      if (db.cohort.dead>0 || db.cohort.expiredLeases>0 || db.auditActions!==baseline.database.auditActions) abort('Unexpected dead letter, expired lease, or reliability mutation');
      if (db.cohort.oldestUnfinishedSeconds>60 || db.cohort.backlog>Math.max(100,rate*30)) abort('Safety stop: backlog exceeds declared bounded exploration budget');
      if (redis.info.evicted_keys!==baseline.redis.info.evicted_keys) abort('Redis eviction counter increased');
    };
    await collect();
    monitorTimer=setInterval(()=>{
      if (activeSample) return;
      activeSample=collect().catch(error=>abort(error.message)).finally(()=>{activeSample=null;});
    },5000);
    startClock=Date.now();
    client=spawn('docker',['start','-a',generatorName],{windowsHide:true});
    let clientText='';
    client.stdout.on('data',chunk=>{clientText+=chunk;});
    client.stderr.on('data',chunk=>{clientText+=chunk;});
    clientExit=await new Promise((resolve,reject)=>{client.on('error',reject);client.on('close',resolve);});
    finishedAt=Date.now();
    await save(directory,'client-raw/console.txt',clientText);
    const container=JSON.parse(await docker(['inspect',generatorName]))[0];
    clientExit=container.State.ExitCode;
    await save(directory,'client-raw/container-exit.json',{exitCode:clientExit,startedAt:container.State.StartedAt,finishedAt:container.State.FinishedAt,oomKilled:container.State.OOMKilled});
    const drainStarted=performance.now();
    finalState=await new Promise((resolve,reject)=>{
      let checking=false;
      const timer=setInterval(async()=>{
        if(checking)return;
        checking=true;
        try {
          const [db,redis]=await Promise.all([context.sql(databaseQuery(since)),redisState(context)]);
          if(settled(db,redis)||performance.now()-drainStarted>drainSeconds*1000){clearInterval(timer);resolve({database:db,redis});}
        } catch(error){clearInterval(timer);reject(error);} finally {checking=false;}
      },1000);
    });
    drainTime=(performance.now()-drainStarted)/1000;
    clearInterval(monitorTimer);monitorTimer=null;
    if(activeSample)await activeSample;
    if(!settled(finalState.database,finalState.redis))errors.push('Drain timeout; transport or durable state still executable');
    clockBounds.push(await clockSample(context));
    const finalHealth=await health(context);
    finalState.health=finalHealth;
    await save(directory,'final-state/state.json',finalState);
    const lifecycle=JSON.parse(await docker(['inspect',...Object.values(context.services).map(item=>item.Id)]));
    for(const item of lifecycle){const service=item.Config.Labels['com.docker.compose.service']; if(item.State.StartedAt!==configuration.initialContainerStarted[service]||!item.State.Running||item.RestartCount!==context.services[service].RestartCount)errors.push(`Unexpected lifecycle change: ${service}`);}
    const data=await cohort(context,since,runId);
    await save(directory,'final-state/cohort.json',data);
    const raw=(await readFile(path.join(directory,'client-raw/requests.jsonl'),'utf8')).split(/\r?\n/).filter(Boolean).map(row=>JSON.parse(row));
    const submissions=raw.filter(row=>row.event==='submission');
    correctness=reconcile(submissions,data.jobs,data.effects,data.counters,workload);
    correctness.transportSettled=settled(finalState.database,finalState.redis);
    correctness.auditActionsUnchanged=finalState.database.auditActions===initialDb.auditActions;
    correctness.missingDedupeMarkers=[];
    const events=data.jobs.flatMap(job=>job.outbox.map(event=>event.id));
    for(let offset=0;offset<events.length;offset+=500){
      const batch=events.slice(offset,offset+500);
      if(batch.some(id=>!safeId.test(id)))throw new Error('Malformed event identity');
      const lua="local missing={}; for _,key in ipairs(KEYS) do if redis.call('EXISTS',key)==0 then table.insert(missing,key) end end; return cjson.encode(missing)";
      const missing=JSON.parse(await context.redis(['EVAL_RO',lua,String(batch.length),...batch.map(id=>`distroq:outbox:published:${id}`)]));
      if(Array.isArray(missing))correctness.missingDedupeMarkers.push(...missing);
    }
    correctness.ok=correctness.ok&&correctness.transportSettled&&correctness.auditActionsUnchanged&&!correctness.missingDedupeMarkers.length;
    await save(directory,'reconciliation.json',correctness);
    const k6=JSON.parse(await readFile(path.join(directory,'k6-summary.json'),'utf8'));
    const dropped=k6.metrics.dropped_iterations?.values?.count||0;
    if(clientExit!==0)errors.push(`k6 exited ${clientExit}`);
    if(dropped>0)errors.push(`${dropped} dropped arrivals`);
    if(submissions.length!==rate*seconds)errors.push(`Actual arrivals ${submissions.length} differ from configured ${rate*seconds}`);
    if(!correctness.ok)errors.push('Correctness reconciliation failed');
    if(finalHealth.status!==200)errors.push('Final readiness not UP');
    summary=analyzeLatency(submissions,data.jobs,seconds,clockBounds);
    const measuredSamples=samples.filter(sample=>sample.utcMs>=summary.firstSubmissionMs&&sample.utcMs<=summary.measurementEndMs);
    summary.stability=stability(measuredSamples,summary,rate);
    summary.drainSeconds=(finishedAt+drainTime*1000-Math.max(...submissions.map(row=>row.sentMs)))/1000;
    summary.postGeneratorDrainObservationSeconds=drainTime;
    summary.httpErrorRate=submissions.filter(row=>row.status!==202).length/Math.max(1,submissions.length);
    summary.droppedIterations=dropped;
    summary.resources={};
    for(const name of [...Object.values(context.services).map(item=>item.Name.slice(1)),generatorName]){
      const rows=containerSamples.filter(row=>row.Name===name&&row.utcMs>=startClock&&row.utcMs<=finishedAt);
      summary.resources[name]={samples:rows.length,peakCpuPercent:Math.max(0,...rows.map(row=>parseFloat(row.CPUPerc))),peakMemoryPercent:Math.max(0,...rows.map(row=>parseFloat(row.MemPerc)))};
      if(seconds>=20&&rows.length<Math.floor(seconds/5))errors.push(`Insufficient container samples: ${name}`);
      const ceiling=name===generatorName?85:name.endsWith('-app-1')?180:720;
      let pinnedSince=null;
      for(const row of rows){pinnedSince=parseFloat(row.CPUPerc)>=ceiling?(pinnedSince??row.utcMs):null;if(pinnedSince!==null&&row.utcMs-pinnedSince>=60_000){errors.push(name===generatorName?'Load generator CPU saturated':'Application/datastore CPU persistently saturated');break;}}
    }
    const measuredHost=hostSamples.filter(row=>row.utcMs>=startClock&&row.utcMs<=finishedAt);
    if(seconds>=20&&measuredHost.length<Math.floor(seconds/5)-1)errors.push('Insufficient host process samples');
    const totals=new Map();
    for(const sample of measuredHost)for(const entry of sample.processes){
      if(!Number.isFinite(entry.cpuPercent)||/Process\((Idle|_Total)\)/.test(entry.name))continue;
      const value=totals.get(entry.name)||{name:entry.name,sum:0,peak:0};value.sum+=entry.cpuPercent;value.peak=Math.max(value.peak,entry.cpuPercent);totals.set(entry.name,value);
    }
    summary.host={samples:measuredHost.length,peakCpuPercent:Math.max(0,...measuredHost.map(row=>row.cpuPercent)),
      processes:[...totals.values()].map(row=>({name:row.name,meanCpuPercent:row.sum/Math.max(1,measuredHost.length),peakCpuPercent:row.peak})).sort((left,right)=>right.meanCpuPercent-left.meanCpuPercent).slice(0,20)};
    summary.host.contention=hostContention(measuredHost,cpus().length);
    if(!summary.host.contention.valid)errors.push('Normalized aggregate host CPU contention rule failed');
    if(!summary.clock.valid)errors.push('Clock bounds missing or wider than 100 ms');
    if(seconds>=20&&measuredSamples.length<Math.floor(seconds/5)-1)errors.push('Insufficient five-second samples');
    if(['QUALIFICATION','P1','P2'].includes(phase)&&!summary.stability.stable)errors.push('Backlog/latency/completion convergence criteria failed');
    if(phase==='P1'&&correctness.succeeded<500)errors.push('P1 has fewer than 500 successful jobs');
    if(phase==='P2'&&seconds<900)errors.push('P2 measured duration shorter than 900 seconds');
    if(['QUALIFICATION','P1','P2'].includes(phase)&&!warmupRun)errors.push('Excluded warm-up reference missing');
    if(phase==='QUALIFICATION'&&seconds<300)errors.push('Qualification must span at least five minutes');
    if((await command('powercfg',['/getactivescheme'])).stdout!==power)errors.push('Power scheme changed');
    const powerEvents=await command('wevtutil',['qe','System',`/q:*[System[(EventID=42 or EventID=107 or EventID=506 or EventID=507) and TimeCreated[@SystemTime>='${started}']]]`,'/f:xml'],{allowFailure:true});
    await save(directory,'container-metrics/power-events.xml',powerEvents.stdout);
    if(powerEvents.code!==0)errors.push('Power event audit unavailable');
    else if(powerEvents.stdout.trim())errors.push('Host sleep/resume or standby event during run');
    await save(directory,'client-summary.json',summary);
  } catch(error){errors.push(error.message);} finally {
    if(monitorTimer)clearInterval(monitorTimer);
    if(activeSample)await activeSample.catch(()=>{});
    if(stopStats)await stopStats();
    if(stopHost)await stopHost();
    await save(directory,'application-metrics/clock-bounds.json',clockBounds);
    const validity={runId,phase,workload,workers,rate,seconds,status:errors.length?'INVALID':'VALID',reasons:[...new Set(errors)],
      startedUtc:started,finishedUtc:new Date().toISOString(),clientExitCode:clientExit,
      limitation:'Historical terminal state is retained; dashboard closure/heavy-work exclusion is an operator attestation. Host process telemetry requires review.'};
    await save(directory,'validity.json',validity);
    await seal(directory);
    console.log(JSON.stringify({runId,status:validity.status,reasons:validity.reasons,completed:correctness?.succeeded,summary:summary?{acceptedRate:summary.acceptedRate,completionRate:summary.completionRate,p95:summary.endToEnd?.p95}:null}));
  }
  return {runId,directory,valid:errors.length===0,errors,summary,correctness};
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const [mode,project,phase,workload,rate,seconds,workers]=process.argv.slice(2);
  if(mode!=='point')throw new Error('Usage: point PROJECT PHASE WORKLOAD RATE SECONDS WORKERS');
  const result=await runPoint(await connect(project),{phase,workload,rate:Number(rate),seconds:Number(seconds),workers:Number(workers),hostReservation:true});
  if(!result.valid)process.exitCode=1;
}