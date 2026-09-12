param([Parameter(Mandatory)][string]$Project)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Project -notmatch '^distroq-bench-[0-9]{14}-[a-f0-9]{6}$') { throw 'Not a benchmark project.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repo = (Resolve-Path (Join-Path $root '..')).Path
$output = Join-Path $root ('results/' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-runtime-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
if (Test-Path $output) { throw 'Evidence exists.' }
$null = New-Item -ItemType Directory $output
$ids = @(docker ps -q --filter "label=com.docker.compose.project=$Project")
if ($ids.Count -ne 3) { throw 'Expected three running isolated services.' }
$containers = @($ids | ForEach-Object { (docker inspect $_ | ConvertFrom-Json)[0] })
$app = $containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'app' }
$postgres = $containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'postgres' }
$redis = $containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'redis' }
$allowlist = @('SPRING_PROFILES_ACTIVE','JAVA_OPTS','DISTROQ_WORKER_CONCURRENCY','DISTROQ_CLAIM_MIN_IDLE_MS','DISTROQ_DB_POOL_MAX','DISTROQ_REDIS_POOL_MAX')
$settings = [ordered]@{}
foreach ($entry in $app.Config.Env) {
    $parts = $entry.Split('=',2)
    if ($parts[0] -in $allowlist) { $settings[$parts[0]] = $parts[1] }
    if ($parts[0] -in @('DISTROQ_ADMIN_TOKEN','DISTROQ_DB_PASSWORD','DISTROQ_REDIS_PASSWORD')) { $settings[$parts[0]] = if ($parts[1]) { 'configured' } else { 'unset' } }
}
$record = [ordered]@{
    utc=[DateTime]::UtcNow.ToString('o'); project=$Project; scope='post-smoke runtime inventory, not a pre-capacity environment record';
    settings=$settings; unspecifiedSettings='Use unchanged base and production profile defaults; source hashes below. No effective-config endpoint was enabled.';
    java=@(); postgres=(docker exec $postgres.Id postgres --version | Out-String).Trim();
    redis=(docker exec $redis.Id redis-server --version | Out-String).Trim();
    images=@($containers | ForEach-Object { @{service=$_.Config.Labels.'com.docker.compose.service'; imageId=$_.Image; digests=@(docker image inspect $_.Image --format '{{json .RepoDigests}}' | ConvertFrom-Json)} });
    limits=@($containers | ForEach-Object { @{service=$_.Config.Labels.'com.docker.compose.service';memoryBytes=$_.HostConfig.Memory;nanoCpus=$_.HostConfig.NanoCpus} });
    dashboardClients='P0 API probes, one browser visit to packaged 404; no capacity workload';
    externalWorkloads='Developer PostgreSQL and Redis remained running; no exclusive host reservation';
    loadGenerator='Docker k6 on the same Docker Desktop VM; no dedicated CPU/memory limit';
    sourceHashes=@('src/main/resources/application.yml','src/main/resources/application-production.yml','pom.xml','Dockerfile' | ForEach-Object { @{file=$_;sha256=(Get-FileHash (Join-Path $repo $_) -Algorithm SHA256).Hash} });
    toolingHashes=@(Get-ChildItem (Join-Path $root 'scripts'),(Join-Path $root 'workloads'),(Join-Path $root 'environments') -File -Recurse | ForEach-Object { @{file=$_.FullName.Substring($root.Length+1);sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash} });
    sourceDiff=@(git -C $repo diff HEAD -- src Dockerfile pom.xml dashboard)
}
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try { $record.java = @(docker exec $app.Id java -version 2>&1 | ForEach-Object { $_.ToString() }) } finally { $ErrorActionPreference=$previous }
docker exec $redis.Id redis-cli --json CONFIG GET appendonly appendfsync maxmemory maxmemory-policy save | Set-Content (Join-Path $output 'redis-persistence.json') -Encoding UTF8
$record | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $output 'runtime.json') -Encoding UTF8
@(Get-ChildItem $output -File | ForEach-Object { @{file=$_.Name;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash} }) | ConvertTo-Json | Set-Content (Join-Path $output 'manifest.json') -Encoding UTF8
Get-ChildItem $output -File | ForEach-Object { $_.IsReadOnly=$true }
Write-Output $output