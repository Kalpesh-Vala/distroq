import { readFile, writeFile, readdir, stat, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const repo=path.dirname(root);
const args=process.argv.slice(2);
const digest=buffer=>createHash('sha256').update(buffer).digest('hex').toUpperCase();
const decode=buffer=>JSON.parse(buffer.toString('utf8').replace(/^\uFEFF/,''));
const git=parameters=>execFileSync('git',parameters,{cwd:repo,maxBuffer:32*1024*1024});
const relative=full=>path.relative(root,full).replaceAll('\\','/');
async function walk(directory){
  const found=[];
  for(const entry of await readdir(directory,{withFileTypes:true})){
    const full=path.join(directory,entry.name);
    if(entry.isSymbolicLink())throw new Error(`Symlink requires manual review: ${relative(full)}`);
    if(entry.isDirectory())found.push(...await walk(full));else found.push(full);
  }
  return found;
}
function category(name){
  if(/^(private|backups)\/|(^|\/)\.env(?:\.|$)|\.(dump|rdb|aof)$/.test(name))return 'private-never-commit';
  if(/^(node_modules|state|tmp)\/|\.(pid|tmp|log)$/.test(name)&&!name.startsWith('results/'))return 'dependency-generated-ignore';
  if(name.startsWith('results/'))return 'raw-evidence-external';
  if(name.endsWith('.md')||name==='reports/EVIDENCE_INDEX.json')return 'documentation-summary';
  return 'source-tooling';
}
function assertCandidate(name,size){
  const local=name.replace(/^performance\//,'');
  if(!name.startsWith('performance/')||!['source-tooling','documentation-summary'].includes(category(local)))throw new Error(`Unexpected staged path: ${name}`);
  if(size>256*1024)throw new Error(`Large staged file: ${name}`);
}
const files=await walk(root);
const inventory=[];
const groups={};
for(const full of files){
  const name=relative(full);const bytes=(await stat(full)).size;const group=category(name);
  inventory.push({path:name,category:group,bytes});
  groups[group]??={files:0,bytes:0};groups[group].files++;groups[group].bytes+=bytes;
}
const containerIds=execFileSync('docker',['ps','-q','--filter','label=com.docker.compose.project=distroq-bench-20260912123428-f8051a'],{encoding:'utf8'}).trim().split(/\s+/).filter(Boolean);
if(containerIds.length!==3)throw new Error('Preserved benchmark services required for credential scan');
const containers=JSON.parse(execFileSync('docker',['inspect',...containerIds],{encoding:'utf8'}));
const app=containers.find(item=>item.Config.Labels['com.docker.compose.service']==='app');
const postgres=containers.find(item=>item.Config.Labels['com.docker.compose.service']==='postgres');
const secrets=app.Config.Env.filter(value=>/^(DISTROQ_ADMIN_TOKEN|DISTROQ_DB_PASSWORD|DISTROQ_REDIS_PASSWORD)=/.test(value)).map(value=>value.slice(value.indexOf('=')+1));
if(secrets.length!==3)throw new Error('Cannot verify generated credentials');
const keys=JSON.parse(execFileSync('docker',['exec',postgres.Id,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','benchmark','-d','benchmark','-c',
  "BEGIN READ ONLY; SET LOCAL statement_timeout='5000ms'; SELECT coalesce(json_agg(idempotency_key),'[]') FROM idempotency_keys; COMMIT;"],{encoding:'utf8',maxBuffer:16*1024*1024}));
const hits=[];
const absolutePaths=[];
const runBindings=[];
function scan(name,text,staged=false){
  if(secrets.some(secret=>secret&&text.includes(secret)))hits.push({path:name,type:'generated-credential'});
  if(keys.some(key=>text.includes(`"${key}"`)||text.includes(`'${key}'`)))hits.push({path:name,type:'raw-idempotency-key'});
  if((name.startsWith('results/')||name.endsWith('.json'))&&!name.startsWith('node_modules/')&&/"(?:payload|idempotency_key|idempotencyKey)"\s*:/.test(text))hits.push({path:name,type:'raw-payload-or-key-field'});
  if(staged&&/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]{30,}\b|\bAKIA[0-9A-Z]{16}\b/.test(text))hits.push({path:name,type:'credential-pattern'});
}
for(const full of files){
  const name=relative(full);const text=(await readFile(full)).toString('utf8');
  scan(name,text);
  if(name.startsWith('scripts/')){
    if(/[A-Za-z]:[\\/](?:Users|github|Program Files)[\\/]/i.test(text))absolutePaths.push(name);
    if(/20260912T|distroq-bench-20260912|563ea44afd6d/.test(text))runBindings.push(name);
  }
}
const runs=[];
let verified=0;
for(const directory of await readdir(path.join(root,'results'),{withFileTypes:true})){
  if(!directory.isDirectory())continue;
  const full=path.join(root,'results',directory.name);
  let manifest;
  try{manifest=await readFile(path.join(full,'manifest.json'));}catch{throw new Error(`Unsealed evidence directory: ${directory.name}`);}
  const entries=decode(manifest);
  for(const entry of Array.isArray(entries)?entries:[entries]){
    const item=path.resolve(full,entry.file||entry.File);
    if(!item.startsWith(full+path.sep))throw new Error('Manifest path escapes run');
    if(digest(await readFile(item))!==(entry.sha256||entry.Hash).toUpperCase())throw new Error(`Evidence mismatch: ${directory.name}/${entry.file}`);
    verified++;
  }
  const runFiles=inventory.filter(item=>item.path.startsWith(`results/${directory.name}/`));
  runs.push({runId:directory.name,relativeLocation:`results/${directory.name}`,manifestSha256:digest(manifest),
    files:runFiles.length,bytes:runFiles.reduce((sum,item)=>sum+item.bytes,0)});
}
let staged=[];
if(args.includes('--staged')){
  staged=git(['diff','--cached','--name-only','-z']).toString('utf8').split('\0').filter(Boolean);
  if(!staged.length)throw new Error('Nothing staged');
  for(const name of staged){const buffer=git(['show',`:${name}`]);assertCandidate(name,buffer.length);scan(name.replace(/^performance\//,''),buffer.toString('utf8'),true);}
}
const index={schemaVersion:1,sourceCommit:'4f31ada742846859cf67d76e72b8813f90877ac2',
  evidenceRoot:'performance/results (local, ignored)',externalArchiveUri:null,
  limitation:'No external upload/archive has been performed. Set a reviewed external archive locator when copying evidence; run IDs plus manifest hashes verify identity but cannot recover missing raw data.',runs};
const report={utc:new Date().toISOString(),categories:groups,files:inventory.length,bytes:inventory.reduce((sum,item)=>sum+item.bytes,0),
  largestTen:[...inventory].sort((left,right)=>right.bytes-left.bytes).slice(0,10),secretScan:{hits,knownCredentials:secrets.length,
    rawKeyCountChecked:keys.length,scope:'All files read; exact generated credentials, quoted known submission keys, payload/key JSON fields. Staged files also checked for common credential signatures; manual review still required.'},
  machineSpecificAbsoluteScriptPaths:absolutePaths,hardcodedHistoricalRunReferences:runBindings,verifiedManifestEntries:verified,staged,
  existingIgnoreRules:(await readFile(path.join(root,'.gitignore'),'utf8')).trim().split(/\r?\n/),inventory};
if(hits.length){console.log(JSON.stringify({hits},null,2));throw new Error('Sensitive content requires review before commit');}
if(!args.includes('--verify-only')){
  const directory=path.join(root,'state',`audit-${Date.now()}`);await mkdir(directory,{recursive:true});
  await writeFile(path.join(directory,'inventory.json'),JSON.stringify(report,null,2),{flag:'wx'});
  console.log(`Full path inventory: ${directory}`);
  if(args.includes('--write-index'))await writeFile(path.join(root,'reports','EVIDENCE_INDEX.json'),JSON.stringify(index,null,2)+'\n',{flag:'wx'});
  if(args.includes('--refresh-index')){
    const filename=path.join(root,'reports','EVIDENCE_INDEX.json');
    const previous=decode(await readFile(filename));
    const oldRuns=new Map(previous.runs.map(run=>[run.runId,run]));
    for(const run of runs){
      const old=oldRuns.get(run.runId);
      if(old&&old.manifestSha256!==run.manifestSha256)throw new Error('Historical index manifest hash changed');
      if(!old)oldRuns.set(run.runId,run);
    }
    await writeFile(filename,JSON.stringify({...previous,runs:[...oldRuns.values()]},null,2)+'\n');
  }
}
console.log(JSON.stringify({...report,inventory:undefined},null,2));