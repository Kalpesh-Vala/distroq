export function idleHostAccounting(samples, logicalProcessors, tracked = []) {
  const reasons=[];
  const observations=[];
  const unavailableByFamily={};
  const unavailableSamples=[];
  const recordUnavailable=(sample,proc,reason)=>{
    const family=(proc.name||'unknown').replace(/#\d+$/,'');
    unavailableByFamily[family]=(unavailableByFamily[family]||0)+1;
    if(unavailableSamples.length<50)unavailableSamples.push({utcMs:sample.utcMs,name:proc.name,pid:proc.pid??null,reason});
  };
  const validCounter=proc=>Number.isInteger(proc.pid)&&proc.pid>0&&Number.isFinite(proc.rawCpuPercent)&&proc.rawCpuPercent>=0
    &&(proc.status===undefined||proc.status===0);
  if(!Number.isInteger(logicalProcessors)||logicalProcessors<1||!samples.length)reasons.push('HOST_TELEMETRY_FAILURE: missing topology or samples');
  const ownerPids=new Set();
  for(const owner of tracked){
    if(ownerPids.has(owner.pid))reasons.push('TRACKED_PROCESS_ATTRIBUTION_FAILURE: duplicate ownership PID');
    ownerPids.add(owner.pid);
    if(!owner.identityVerified||!Number.isInteger(owner.pid)||owner.pid<=0||!owner.name
      ||!Number.isFinite(owner.startedMs)||!Number.isFinite(owner.endedMs)||owner.endedMs<owner.startedMs)
      reasons.push('TRACKED_PROCESS_ATTRIBUTION_FAILURE: missing verified lifetime');
  }
  for(const sample of samples){
    if(!Number.isFinite(sample.totalCpuPercent)||sample.totalCpuPercent<0||sample.totalCpuPercent>100||!Number.isFinite(sample.utcMs)){
      reasons.push('HOST_TELEMETRY_FAILURE: unavailable authoritative total or timestamp');continue;
    }
    const processes=sample.processes||[];
    for(const proc of sample.unavailableProcesses||[])recordUnavailable(sample,proc,proc.reason||'counter unavailable');
    for(const proc of processes)if(!validCounter(proc))recordUnavailable(sample,proc,'counter unavailable');
    const active=tracked.filter(owner=>sample.utcMs>=owner.startedMs&&sample.utcMs<=owner.endedMs);
    let trackedCpu=0;
    for(const owner of active){
      const matches=processes.filter(proc=>proc.pid===owner.pid&&validCounter(proc));
      if(matches.length!==1||matches[0].name.replace(/#\d+$/,'').toLowerCase()!==owner.name.toLowerCase()){
        reasons.push(`TRACKED_PROCESS_ATTRIBUTION_FAILURE: PID ${owner.pid} unavailable or identity mismatch`);continue;
      }
      trackedCpu+=matches[0].rawCpuPercent/logicalProcessors;
    }
    const diagnostic=processes.filter(validCounter).filter(proc=>!active.some(owner=>owner.pid===proc.pid));
    observations.push({utcMs:sample.utcMs,hostTotalCpu:sample.totalCpuPercent,totalCpuPercent:sample.totalCpuPercent,
      trackedBenchmarkCpu:trackedCpu,instrumentationCpuPercent:trackedCpu,
      diagnosticProcessCpu:diagnostic.reduce((sum,proc)=>sum+proc.rawCpuPercent/logicalProcessors,0),
      wmiCpuPercent:diagnostic.filter(proc=>/^WmiPrvSE(?:#\d+)?$/i.test(proc.name)).reduce((sum,proc)=>sum+proc.rawCpuPercent/logicalProcessors,0),
      vscodeCpuPercent:diagnostic.filter(proc=>/^Code(?:#\d+)?$/i.test(proc.name)).reduce((sum,proc)=>sum+proc.rawCpuPercent/logicalProcessors,0),
      unattributedCpu:null,unattributedCpuUpperBound:sample.totalCpuPercent,idleContention:sample.totalCpuPercent});
  }
  const mean=key=>observations.length?observations.reduce((sum,row)=>sum+row[key],0)/observations.length:null;
  const fractionOver20=observations.length?observations.filter(row=>row.hostTotalCpu>20).length/observations.length:null;
  if(mean('hostTotalCpu')>10||fractionOver20>0.1)reasons.push('ACTUAL_CONTENTION_FAILURE: idle host total exceeds mean/spike thresholds');
  return {valid:reasons.length===0,reasons:[...new Set(reasons)],observations,
    means:{hostTotalCpu:mean('hostTotalCpu'),trackedBenchmarkCpu:mean('trackedBenchmarkCpu')},fractionOver20,
    diagnostics:{status:Object.keys(unavailableByFamily).length?'DEGRADED':'AVAILABLE',
      unavailableCount:Object.values(unavailableByFamily).reduce((sum,value)=>sum+value,0),unavailableByFamily,unavailableSamples},
    policy:'Idle contention equals authoritative host total. Tracked CPU is reported, not subtracted. Diagnostics never reconstruct total. Exact unattributed CPU is unknown because process/total intervals are not demonstrated compatible; all total CPU remains inside the idle gate.'};
}

export function attributedHostCpu(samples, logicalProcessors, owners = {}) {
  const invalid = reason => ({ valid: false, reason });
  if (!Number.isInteger(logicalProcessors) || logicalProcessors < 1 || !samples.length) return invalid('Missing topology or samples');
  const categories = ['infrastructure', 'generator', 'instrumentation'];
  const assigned = new Map();
  for (const category of categories) {
    for (const pid of owners[category] || []) {
      if (!Number.isInteger(pid) || pid <= 0 || assigned.has(pid)) return invalid('Invalid or multiply attributed PID');
      assigned.set(pid, category);
    }
  }
  const observations = [];
  for (const sample of samples) {
    if (!Number.isFinite(sample.totalCpuPercent) || sample.totalCpuPercent < 0 || sample.totalCpuPercent > 100) return invalid('Invalid host total');
    const row = { utcMs: sample.utcMs, totalCpuPercent: sample.totalCpuPercent,
      infrastructureCpuPercent: 0, generatorCpuPercent: 0, instrumentationCpuPercent: 0, wmiCpuPercent: 0, vscodeCpuPercent: 0 };
    const seen = new Set();
    for (const proc of sample.processes) {
      if (!Number.isInteger(proc.pid) || proc.pid < 0 || !Number.isFinite(proc.rawCpuPercent) || proc.rawCpuPercent < 0
          || (proc.status !== undefined && proc.status !== 0)) return invalid('Invalid process counter');
      if (proc.pid === 0) continue;
      if (seen.has(proc.pid)) return invalid('Duplicate PID counter in sample');
      seen.add(proc.pid);
      const normalized = proc.rawCpuPercent / logicalProcessors;
      const category = assigned.get(proc.pid);
      if (category) row[`${category}CpuPercent`] += normalized;
      if (/^WmiPrvSE(?:#\d+)?$/i.test(proc.name)) row.wmiCpuPercent += normalized;
      if (/^Code(?:#\d+)?$/i.test(proc.name)) row.vscodeCpuPercent += normalized;
    }
    const attributed = row.infrastructureCpuPercent + row.generatorCpuPercent + row.instrumentationCpuPercent;
    if (attributed > row.totalCpuPercent) return invalid('Attributed CPU exceeds total; counters are not safely aligned');
    row.unrelatedCpuPercent = row.totalCpuPercent - attributed;
    observations.push(row);
  }
  const mean = key => observations.reduce((sum, row) => sum + row[key], 0) / observations.length;
  const fractionOver20 = observations.filter(row => row.unrelatedCpuPercent > 20).length / observations.length;
  return { valid: mean('unrelatedCpuPercent') <= 10 && fractionOver20 <= 0.1, observations,
    means: Object.fromEntries(['totalCpuPercent','infrastructureCpuPercent','generatorCpuPercent','instrumentationCpuPercent',
      'unrelatedCpuPercent','wmiCpuPercent','vscodeCpuPercent'].map(key => [key, mean(key)])), fractionOver20,
    policy: 'Unrelated = host total minus verified host-PID infrastructure, generator and instrumentation. Unattributed CPU, including WMI/VM work, remains in the residual. Container CPU is reported separately, never subtracted from host total.' };
}

export function hostContention(samples, logicalProcessors) {
  if (!Number.isInteger(logicalProcessors) || logicalProcessors < 1 || !samples.length) {
    return { valid: false, reason: 'CPU topology or host samples missing' };
  }
  const totals = samples.map(sample => sample.processes.reduce((sum, entry) => {
    if (/Process\((Idle|_Total|vmmem.*|node(?:#\d+)?|typeperf(?:#\d+)?)\)/i.test(entry.name)) return sum;
    return sum + entry.cpuPercent;
  }, 0) / logicalProcessors);
  if (totals.some(value => !Number.isFinite(value) || value < 0)) return { valid: false, reason: 'Invalid host CPU sample' };
  const mean = totals.reduce((sum, value) => sum + value, 0) / totals.length;
  const over20 = totals.filter(value => value > 20).length;
  return { valid: mean <= 10 && over20 / totals.length <= 0.1, logicalProcessors,
    meanNormalizedPercent: mean, samplesOver20Percent: over20, samples: totals.length,
    fractionOver20Percent: over20 / totals.length,
    classification: 'Idle/total excluded. Node/typeperf treated as harness only under exclusive-host attestation; mixed VM excluded under Docker-only attestation. All other host processes included.' };
}