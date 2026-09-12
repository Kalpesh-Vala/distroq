param([Parameter(Mandatory)][string]$RunDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$events = @(Get-Content (Join-Path $RunDirectory 'client.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$submissions = @($events | Where-Object { $_.event -eq 'submission' })
$terminal = @($events | Where-Object { $_.event -eq 'terminal' })
function Get-Quantiles([double[]]$Values) {
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return @{ count=0 } }
    return @{ count=$sorted.Count; p50=$sorted[[Math]::Ceiling(0.50*$sorted.Count)-1];
        p95=$sorted[[Math]::Ceiling(0.95*$sorted.Count)-1]; p99=$sorted[[Math]::Ceiling(0.99*$sorted.Count)-1]; max=$sorted[-1] }
}
$queueing = @($terminal | ForEach-Object {
    $first = @($_.attempts | Sort-Object startedAt)[0]
    $eligible = if ($_.scheduledAt) { $_.scheduledAt } else { $_.createdAt }
    ([DateTimeOffset]::Parse($first.startedAt) - [DateTimeOffset]::Parse($eligible)).TotalMilliseconds
})
$execution = @($terminal | ForEach-Object { $_.attempts } | ForEach-Object {
    ([DateTimeOffset]::Parse($_.finishedAt) - [DateTimeOffset]::Parse($_.startedAt)).TotalMilliseconds
})
$serverLatency = @($terminal | ForEach-Object {
    ([DateTimeOffset]::Parse($_.finishedAt) - [DateTimeOffset]::Parse($_.createdAt)).TotalMilliseconds
})
$pending = @($events | Where-Object { $_.event -eq 'workers-probe' -and $_.view.pendingEntries.availability -eq 'AVAILABLE' -and $_.view.pendingEntries.data.Count -gt 0 })
$report = [ordered]@{
    sourceRun=(Split-Path $RunDirectory -Leaf)
    warning='Mixed cold smoke only; not capacity, latency floor, full P0 certification, or failure-recovery evidence.'
    quantileMethod='exact nearest rank; milliseconds'
    submission=Get-Quantiles @($submissions | Select-Object -ExpandProperty durationMs)
    queueing=Get-Quantiles $queueing
    execution=Get-Quantiles $execution
    serverCreatedToTerminal=Get-Quantiles $serverLatency
    endToEnd='unverified: client/server clock offset was not measured'
    nonemptyPendingApiSamples=$pending.Count
    releaseProbes=@($events | Where-Object { $_.event -eq 'release-probe' } | ForEach-Object {
        $section = if ($_.route.StartsWith('jobs')) { $_.view.jobs } else { $_.view.outbox }
        @{ route=$_.route; httpStatus=$_.status; availability=$section.availability }
    })
    packagedStaticStatus=($events | Where-Object { $_.event -eq 'packaged-static' }).status
    originalPendingFlag='Original workload used wrong property; derive API result from preserved full responses. Original files remain unchanged.'
}
$report | ConvertTo-Json -Depth 8