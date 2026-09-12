param([ValidateRange(1,16)][int]$Workers = 1, [ValidateRange(1024,65535)][int]$Port = 18080)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw 'Selected HTTP port is already in use.' }
$env:BENCH_PROJECT = 'distroq-bench-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,6)
$env:BENCH_PORT = [string]$Port
$env:BENCH_WORKERS = [string]$Workers
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    foreach ($name in @('BENCH_DB_PASSWORD','BENCH_REDIS_PASSWORD','BENCH_ADMIN_TOKEN')) {
        $bytes = New-Object byte[] 32
        $rng.GetBytes($bytes)
        [Environment]::SetEnvironmentVariable($name, [Convert]::ToBase64String($bytes), 'Process')
    }
} finally { $rng.Dispose() }
$compose = Join-Path $PSScriptRoot '../environments/compose.yml'
docker compose -p $env:BENCH_PROJECT -f $compose up -d --build --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) { throw 'Isolated startup failed; preserve containers for diagnosis. Do not run workloads.' }
Write-Output "Isolated project: $env:BENCH_PROJECT; URL: http://127.0.0.1:$Port"