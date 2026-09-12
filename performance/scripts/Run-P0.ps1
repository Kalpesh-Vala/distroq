param([Parameter(Mandatory)][string]$Project)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Project -notmatch '^distroq-bench-[0-9]{14}-[a-f0-9]{6}$') { throw 'Not a benchmark project.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-P0-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$output = Join-Path $root "results/$runId"
if (Test-Path $output) { throw 'Refusing to overwrite evidence.' }
$null = New-Item -ItemType Directory -Path $output
$verdict = [ordered]@{ runId=$runId; project=$Project; workload='P0'; status='INCONCLUSIVE'; reasons=@();
    commit=(git rev-parse HEAD | Out-String).Trim(); startedUtc=[DateTime]::UtcNow.ToString('o');
    command="./performance/scripts/Run-P0.ps1 -Project $Project" }
try {
    & (Join-Path $PSScriptRoot 'Collect-Snapshot.ps1') -Project $Project -Output (Join-Path $output 'before')
    $before = Get-Content (Join-Path $output 'before/durable.json') -Raw | ConvertFrom-Json
    $transport = Get-Content (Join-Path $output 'before/transport.json') -Raw | ConvertFrom-Json
    if ($before.jobs -ne 0 -or $before.attempts -ne 0 -or $before.effects -ne 0 -or $before.dlq -ne 0 -or $before.outbox) { throw 'Initial durable dataset is not empty.' }
    if ($transport.delayed -ne 0 -or $transport.scheduled -ne 0 -or $transport.high.length -ne 0 -or $transport.normal.length -ne 0 -or $transport.low.length -ne 0) { throw 'Initial transport dataset is not empty.' }
    $appId = docker ps -q --filter "label=com.docker.compose.project=$Project" --filter 'label=com.docker.compose.service=app'
    $app = (docker inspect $appId | ConvertFrom-Json)[0]
    $tokenEntry = $app.Config.Env | Where-Object { $_.StartsWith('DISTROQ_ADMIN_TOKEN=') }
    $env:BENCH_ADMIN_TOKEN = $tokenEntry.Substring('DISTROQ_ADMIN_TOKEN='.Length)
    $verdict['k6Image'] = docker image inspect grafana/k6:1.3.0 --format '{{json .RepoDigests}}' | ConvertFrom-Json
    $verdict['k6Version'] = (docker run --rm grafana/k6:1.3.0 version | Out-String).Trim()
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        docker run --rm --name "$Project-k6" --network "${Project}_default" -e BENCH_ADMIN_TOKEN -e "RUN_ID=$runId" -v "${root}/workloads:/workloads:ro" -v "${output}:/results" grafana/k6:1.3.0 run --log-format raw --console-output /results/client.jsonl --out json=/results/k6-samples.json /workloads/p0.js 2>&1 | ForEach-Object { $_.ToString() } | Set-Content (Join-Path $output 'k6-console.txt') -Encoding UTF8
        $k6Exit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    $verdict['k6ExitCode'] = $k6Exit
    & (Join-Path $PSScriptRoot 'Collect-Snapshot.ps1') -Project $Project -Output (Join-Path $output 'after')
    $after = Get-Content (Join-Path $output 'after/durable.json') -Raw | ConvertFrom-Json
    $client = @(Get-Content (Join-Path $output 'client.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
    $accepted = @($client | Where-Object { $_.event -eq 'submission' -and $_.status -eq 202 })
    $identities = @($accepted | Select-Object -ExpandProperty id -Unique | Sort-Object)
    $durableIds = @($after.jobEvidence | Select-Object -ExpandProperty id | Sort-Object)
    $identityDifference = @(Compare-Object $identities $durableIds)
    $verdict['acceptedResponses'] = $accepted.Count
    $verdict['uniqueAcceptedJobs'] = $identities.Count
    $verdict['durableJobs'] = $after.jobs
    $verdict['identityDifferenceCount'] = $identityDifference.Count
    $finalTransport = Get-Content (Join-Path $output 'after/transport.json') -Raw | ConvertFrom-Json
    $settled = $finalTransport.delayed -eq 0 -and $finalTransport.scheduled -eq 0
    foreach ($tier in @('high','normal','low')) {
        $pending = $finalTransport.$tier.pending[0] | ConvertFrom-Json
        $groups = $finalTransport.$tier.groups[0] | ConvertFrom-Json
        if ($pending[0] -ne 0) { $settled = $false }
        foreach ($group in $groups) { if ($group.lag -ne 0) { $settled = $false } }
    }
    $verdict['transportSettled'] = $settled
    if ($k6Exit -eq 0 -and $identities.Count -eq 6 -and $identityDifference.Count -eq 0 -and $after.nonterminal -eq 0 -and $after.attemptMismatches -eq 0 -and $after.dlqMismatches -eq 0 -and $after.earlyStarts -eq 0 -and $after.effects -eq 1 -and $after.counterTotal -eq 1 -and $after.unpublished -eq 0 -and $after.auditActions -eq $before.auditActions -and $settled) {
        $verdict.status = 'VALID'
    } else {
        $verdict.status = 'CORRECTNESS_FAIL'
        $verdict.reasons = @('P0 assertions, exact-identity reconciliation, effects, audit, or settled transport failed; inspect raw evidence. Capacity gate closed.')
    }
    $logs = docker logs $appId 2>&1
    $logs | ForEach-Object {
        try { $_.ToString() | ConvertFrom-Json -ErrorAction Stop | Select-Object timestamp, level, logger, event, jobId, attemptNumber } catch { }
    } | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'app-log-metadata.json') -Encoding UTF8
} catch {
    $verdict.reasons += $_.Exception.Message
} finally {
    $verdict['finishedUtc'] = [DateTime]::UtcNow.ToString('o')
    $verdict | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $output 'verdict.json') -Encoding UTF8
    $manifest = @(Get-ChildItem $output -File -Recurse | ForEach-Object {
        @{ file=$_.FullName.Substring($output.Length+1); sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash }
    })
    $manifest | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $output 'manifest.json') -Encoding UTF8
    Get-ChildItem $output -File -Recurse | ForEach-Object { $_.IsReadOnly = $true }
}
Write-Output $output
Write-Output ($verdict | ConvertTo-Json -Depth 8)
if ($verdict.status -ne 'VALID') { throw 'P0 did not pass; do not proceed to capacity testing.' }