param([Parameter(Mandatory)][string]$Project, [Parameter(Mandatory)][string]$Output)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Project -notmatch '^distroq-bench-[0-9]{14}-[a-f0-9]{6}$') { throw 'Not a benchmark project.' }
if (Test-Path $Output) { throw 'Snapshot already exists.' }
$null = New-Item -ItemType Directory -Path $Output
$ids = @(docker ps -aq --filter "label=com.docker.compose.project=$Project")
if ($ids.Count -ne 3) { throw 'Expected exactly three isolated services.' }
$containers = @($ids | ForEach-Object { (docker inspect $_ | ConvertFrom-Json)[0] })
$safe = @($containers | ForEach-Object {
    [ordered]@{ id=$_.Id; image=$_.Image; service=$_.Config.Labels.'com.docker.compose.service';
      startedAt=$_.State.StartedAt; restarts=$_.RestartCount; memoryLimit=$_.HostConfig.Memory;
      nanoCpus=$_.HostConfig.NanoCpus; state=$_.State.Status }
})
$safe | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $Output 'containers.json') -Encoding UTF8
docker stats --no-stream --format '{{json .}}' @ids | Set-Content (Join-Path $Output 'docker-stats.jsonl') -Encoding UTF8
if ($LASTEXITCODE -ne 0) { throw 'Docker stats failed.' }
$postgres = ($containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'postgres' }).Id
$redis = ($containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'redis' }).Id
$app = ($containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'app' }).Id
$sql = Get-Content (Join-Path $PSScriptRoot 'reconcile.sql') -Raw
$sql | docker exec -i $postgres psql -X -qAt -U benchmark -d benchmark | Set-Content (Join-Path $Output 'durable.json') -Encoding UTF8
if ($LASTEXITCODE -ne 0) { throw 'Durable snapshot failed.' }
docker exec $postgres psql -X -qAt -U benchmark -d benchmark -c "SELECT json_build_object('utc',clock_timestamp(),'database',(SELECT row_to_json(stats) FROM (SELECT numbackends,xact_commit,xact_rollback,blks_read,blks_hit,tup_returned,tup_fetched,tup_inserted,tup_updated,deadlocks,temp_bytes,stats_reset FROM pg_stat_database WHERE datname=current_database()) stats),'connections',(SELECT json_agg(row_to_json(activity)) FROM (SELECT state,wait_event_type,count(*) FROM pg_stat_activity WHERE datname=current_database() GROUP BY state,wait_event_type) activity),'sizeBytes',pg_database_size(current_database()));" | Set-Content (Join-Path $Output 'postgres-stats.json') -Encoding UTF8
if ($LASTEXITCODE -ne 0) { throw 'Postgres stats failed.' }
docker exec $redis redis-cli INFO | Set-Content (Join-Path $Output 'redis-info.txt') -Encoding UTF8
if ($LASTEXITCODE -ne 0) { throw 'Redis INFO failed.' }
$transport = [ordered]@{}
foreach ($tier in @('high','normal','low')) {
    $key = "distroq:jobs:stream:$tier"
    $transport[$tier] = @{
      length = [long](docker exec $redis redis-cli XLEN $key)
      groups = @(docker exec $redis redis-cli --json XINFO GROUPS $key)
      consumers = @(docker exec $redis redis-cli --json XINFO CONSUMERS $key distroq-workers)
      pending = @(docker exec $redis redis-cli --json XPENDING $key distroq-workers)
    }
    if ($LASTEXITCODE -ne 0) { throw 'Redis transport snapshot failed.' }
}
$transport['delayed'] = [long](docker exec $redis redis-cli ZCARD distroq:jobs:delayed)
$transport['scheduled'] = [long](docker exec $redis redis-cli ZCARD distroq:jobs:scheduled)
$transport | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $Output 'transport.json') -Encoding UTF8
foreach ($endpoint in @('prometheus','health/readiness','health/liveness','info')) {
    $name = $endpoint.Replace('/','-')
    $content = docker exec $app wget -qO- "http://127.0.0.1:8080/actuator/$endpoint"
    if ($LASTEXITCODE -ne 0) { throw "Missing actuator sample: $endpoint" }
    $content | Set-Content (Join-Path $Output "$name.txt") -Encoding UTF8
}