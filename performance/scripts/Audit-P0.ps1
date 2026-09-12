param([Parameter(Mandatory)][string]$Project, [Parameter(Mandatory)][string]$SourceRun)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Project -notmatch '^distroq-bench-[0-9]{14}-[a-f0-9]{6}$') { throw 'Not a benchmark project.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = Join-Path $root ('results/' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-P0-audit-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
if (Test-Path $output) { throw 'Evidence exists.' }
$null = New-Item -ItemType Directory $output
$record = [ordered]@{ status='INCONCLUSIVE'; sourceRun=$SourceRun; project=$Project; utc=[DateTime]::UtcNow.ToString('o'); command="./performance/scripts/Audit-P0.ps1 -Project $Project -SourceRun $SourceRun" }
try {
    & (Join-Path $PSScriptRoot 'Collect-Snapshot.ps1') -Project $Project -Output (Join-Path $output 'before')
    $before = Get-Content (Join-Path $output 'before/durable.json') -Raw | ConvertFrom-Json
    if ($before.jobs -ne 6 -or $before.nonterminal -ne 0 -or $before.unpublished -ne 0) { throw 'Audit is only for the settled six-job smoke cohort.' }
    $source = Get-Content (Join-Path $root "results/$SourceRun/after/durable.json") -Raw | ConvertFrom-Json
    if (@(Compare-Object @($source.jobEvidence.id | Sort-Object) @($before.jobEvidence.id | Sort-Object)).Count -ne 0) { throw 'Source cohort does not match.' }
    $postgres = docker ps -q --filter "label=com.docker.compose.project=$Project" --filter 'label=com.docker.compose.service=postgres'
    $redis = docker ps -q --filter "label=com.docker.compose.project=$Project" --filter 'label=com.docker.compose.service=redis'
    $appId = docker ps -q --filter "label=com.docker.compose.project=$Project" --filter 'label=com.docker.compose.service=app'
    $events = docker exec $postgres psql -X -qAt -U benchmark -d benchmark -c 'SELECT json_agg(event) FROM (SELECT id,aggregate_id,event_type,status FROM outbox_events ORDER BY id LIMIT 100) event;' | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Outbox identity query failed.' }
    $markers = @($events | ForEach-Object {
        $exists = [int](docker exec $redis redis-cli EXISTS "distroq:outbox:published:$($_.id)")
        @{ eventId=$_.id; jobId=$_.aggregate_id; type=$_.event_type; markerExists=($exists -eq 1) }
    })
    $lua = "local result = {}; for _, entry in ipairs(redis.call('XRANGE', KEYS[1], '-', '+', 'COUNT', 100)) do local item = {entryId=entry[1]}; for offset=1,#entry[2],2 do local field=entry[2][offset]; if field=='jobId' or field=='outboxEventId' or field=='source' then item[field]=entry[2][offset+1] end end; table.insert(result,item) end; return cjson.encode(result)"
    $deliveries = @()
    foreach ($tier in @('high','normal','low')) {
        $length = [long](docker exec $redis redis-cli XLEN "distroq:jobs:stream:$tier")
        if ($length -gt 100) { throw 'Stream exceeds bounded P0 audit limit.' }
        $entries = docker exec $redis redis-cli --raw EVAL_RO $lua 1 "distroq:jobs:stream:$tier" | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0) { throw 'Stream identity audit failed.' }
        $deliveries += @($entries)
    }
    $record['outboxMarkers'] = $markers
    $record['deliveries'] = $deliveries
    $record['unknownDeliveryJobs'] = @($deliveries | Where-Object { $_.jobId -notin $before.jobEvidence.id }).Count
    $record['missingEventDeliveries'] = @($events | Where-Object { $_.id -notin $deliveries.outboxEventId }).Count
    $app = (docker inspect $appId | ConvertFrom-Json)[0]
    $token = ($app.Config.Env | Where-Object { $_.StartsWith('DISTROQ_ADMIN_TOKEN=') }).Substring('DISTROQ_ADMIN_TOKEN='.Length)
    $port = $app.NetworkSettings.Ports.'8080/tcp'[0].HostPort
    $probes = @()
    foreach ($route in @('overview','queues','workers','outbox','reconciliation','jobs','dlq','analytics','system','activity',"jobs/$($before.jobEvidence[0].id)")) {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/api/dashboard/$route" -Headers @{ Authorization="Bearer $token" } -TimeoutSec 30
        $probes += @{ route=$route; status=[int]$response.StatusCode }
    }
    $record['dashboardReadProbes'] = $probes
    & (Join-Path $PSScriptRoot 'Collect-Snapshot.ps1') -Project $Project -Output (Join-Path $output 'after')
    $after = Get-Content (Join-Path $output 'after/durable.json') -Raw | ConvertFrom-Json
    $before.PSObject.Properties.Remove('utc')
    $after.PSObject.Properties.Remove('utc')
    $record['durableSnapshotUnchanged'] = ($before | ConvertTo-Json -Depth 15 -Compress) -ceq ($after | ConvertTo-Json -Depth 15 -Compress)
    $record['missingMarkers'] = @($markers | Where-Object { -not $_.markerExists }).Count
    if ($record.unknownDeliveryJobs -eq 0 -and $record.missingEventDeliveries -eq 0 -and $record.missingMarkers -eq 0 -and $record.durableSnapshotUnchanged) { $record.status='PASS' } else { $record.status='FAIL' }
} catch { $record['error']=$_.Exception.Message } finally {
    $record | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $output 'audit.json') -Encoding UTF8
    @(Get-ChildItem $output -File -Recurse | ForEach-Object { @{file=$_.FullName.Substring($output.Length+1);sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash} }) | ConvertTo-Json | Set-Content (Join-Path $output 'manifest.json') -Encoding UTF8
    Get-ChildItem $output -File -Recurse | ForEach-Object { $_.IsReadOnly=$true }
}
Write-Output $output
Write-Output ($record | ConvertTo-Json -Depth 12)
if ($record.status -ne 'PASS') { throw 'Extended P0 audit did not pass.' }