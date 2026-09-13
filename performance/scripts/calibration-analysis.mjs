import { quantiles } from './capacity-analysis.mjs';
import { idleHostAccounting } from './host-policy.mjs';

export function distribution(values) {
  return { ...quantiles(values), mean: values.length ? values.reduce((sum,value)=>sum+value,0)/values.length : null };
}

export function summarizeCollector(samples, logicalProcessors, owners, hostInvalidCount = 0) {
  const result = { samples: samples.length, invalidRequiredCounterCount: hostInvalidCount, reasons: [], warnings: [] };
  const finite = samples.every(row=>Number.isFinite(row.utcMs));
  const gaps = samples.slice(1).map((row,index)=>(row.utcMs-samples[index].utcMs)/1000);
  result.spanSeconds = samples.length > 1 ? (samples.at(-1).utcMs-samples[0].utcMs)/1000 : 0;
  result.maximumGapSeconds = gaps.length ? Math.max(...gaps) : null;
  result.accounting = idleHostAccounting(samples,logicalProcessors,owners.tracked||[]);
  for(const owner of owners.tracked||[]){
    if(!samples.some(sample=>sample.utcMs>=owner.startedMs&&sample.utcMs<=owner.endedMs))result.reasons.push(`TRACKED_PROCESS_ATTRIBUTION_FAILURE: no sampled lifetime for PID ${owner.pid}`);
  }
  result.hostCpu = distribution(samples.map(row=>row.totalCpuPercent).filter(Number.isFinite));
  if(samples.some(row=>!row.frequencyMHz?.length || row.frequencyMHz.some(value=>!Number.isFinite(value)||value<=0))) result.reasons.push('HOST_TELEMETRY_FAILURE: frequency telemetry missing (allowance zero)');
  if (!finite || gaps.some(gap=>gap<=0) || result.maximumGapSeconds>15 || result.spanSeconds<300 || samples.length<59) result.reasons.push('HOST_TELEMETRY_FAILURE: incomplete five-minute coverage or invalid timing');
  if (hostInvalidCount) result.reasons.push('HOST_TELEMETRY_FAILURE: invalid required counters');
  result.reasons.push(...result.accounting.reasons);
  result.diagnostics=result.accounting.diagnostics;
  if(result.diagnostics.unavailableCount)result.warnings.push('DIAGNOSTIC_ATTRIBUTION_DEGRADATION: unavailable observations retained; no zero filling or subtraction');
  const observations=result.accounting.observations || [];
  for(const field of ['instrumentationCpuPercent','hostTotalCpu','trackedBenchmarkCpu','diagnosticProcessCpu','idleContention','wmiCpuPercent','vscodeCpuPercent']) {
    result[field]=distribution(observations.map(row=>row[field]));
  }
  result.frequencyMHz=distribution(samples.flatMap(row=>row.frequencyMHz || []).filter(Number.isFinite));
  result.limitedSamples=samples.filter(row=>row.performanceLimited).length;
  if(result.limitedSamples) result.reasons.push('Performance limiting observed; cannot attribute cause automatically');
  result.valid=result.reasons.length===0;
  return result;
}

export function validReferenceBaseline(references, captures) {
  const linkedIds=new Set(captures.filter(capture=>capture.status==='VALID').map(capture=>capture.referenceRun));
  const included=references.filter(reference=>reference.status==='VALID'&&linkedIds.has(reference.runId)&&Number.isFinite(reference.hostCpu?.mean));
  return {mean:included.length?included.reduce((sum,reference)=>sum+reference.hostCpu.mean,0)/included.length:null,
    includedRunIds:included.map(reference=>reference.runId),
    excludedRunIds:references.filter(reference=>!included.includes(reference)).map(reference=>reference.runId)};
}

export function overheadEstimates(runner, independent, combined, reference) {
  if([runner,independent,combined,reference].some(value=>!Number.isFinite(value)))return {status:'UNMEASURED'};
  return {status:'RELATIVE_ESTIMATE',referenceHostCpuPercent:reference,
    runnerPercentagePoints:runner-reference,independentPercentagePoints:independent-reference,
    interactionPercentagePoints:combined-(runner+independent-reference),
    caveat:'Reference contains a low-detail sampler plus idle infrastructure. Not an exact uninstrumented baseline; differences include temporal noise, power/frequency and background work. Negative estimates are preserved.'};
}

export const REPEATABILITY_LIMITS = Object.freeze({
  version: 'prospective-mean-p95-v1',
  meanDifferencePercentagePoints: 2,
  p95DifferencePercentagePoints: 2,
  maximumSampleGapSeconds: 15,
});

export function candidateRepeatability(first, second) {
  const limits=REPEATABILITY_LIMITS;
  if(!first?.valid || !second?.valid)return {eligible:false,limits,reason:'Two valid isolated captures required'};
  const required=sample=>[sample.hostCpu?.mean,sample.hostCpu?.p95,sample.hostCpu?.p99,sample.maximumGapSeconds];
  if([...required(first),...required(second)].some(value=>!Number.isFinite(value)||value<0))
    return {eligible:false,limits,reason:'Required comparison measurements missing or invalid'};
  const totalDelta=Math.abs(first.hostCpu.mean-second.hostCpu.mean);
  const p95Delta=Math.abs(first.hostCpu.p95-second.hostCpu.p95);
  const pairCv=(left,right)=>{
    if(!Number.isFinite(left)||!Number.isFinite(right))return null;
    const mean=(left+right)/2;
    return mean===0?null:Math.sqrt(((left-mean)**2+(right-mean)**2)/(2-1))/mean;
  };
  const diagnosticCounts=sample=>sample.diagnostics?.unavailableByFamily||{};
  const families=new Set([...Object.keys(diagnosticCounts(first)),...Object.keys(diagnosticCounts(second))]);
  return {eligible:totalDelta<=limits.meanDifferencePercentagePoints && p95Delta<=limits.p95DifferencePercentagePoints
      && first.maximumGapSeconds<=limits.maximumSampleGapSeconds && second.maximumGapSeconds<=limits.maximumSampleGapSeconds,
    limits,totalCpuDifferencePercentagePoints:totalDelta,
    relativeMeanDifferencePercent:first.hostCpu.mean===0?null:100*totalDelta/first.hostCpu.mean,
    p95DifferencePercentagePoints:p95Delta,p99DifferencePercentagePoints:Math.abs(first.hostCpu.p99-second.hostCpu.p99),
    maximumGapDifferenceSeconds:Math.abs(first.maximumGapSeconds-second.maximumGapSeconds),
    instrumentationMeanDifferencePercentagePoints:Number.isFinite(first.instrumentationCpuPercent?.mean)&&Number.isFinite(second.instrumentationCpuPercent?.mean)
      ?Math.abs(first.instrumentationCpuPercent.mean-second.instrumentationCpuPercent.mean):null,
    diagnosticUnavailableDifferenceByFamily:Object.fromEntries([...families].map(family=>[family,
      (diagnosticCounts(second)[family]||0)-(diagnosticCounts(first)[family]||0)])),
    sampleCoefficientOfVariation:{hostMean:pairCv(first.hostCpu.mean,second.hostCpu.mean),hostP95:pairCv(first.hostCpu.p95,second.hostCpu.p95),
      instrumentationMean:pairCv(first.instrumentationCpuPercent?.mean,second.instrumentationCpuPercent?.mean),observations:2},
    reason:'Prospective mean/p95 screen only. Compare validated captures; two observations do not establish causal overhead, background stability or authorize canonical selection.'};
}