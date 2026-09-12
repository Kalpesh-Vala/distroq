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