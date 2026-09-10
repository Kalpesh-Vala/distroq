<#
.SYNOPSIS
    Captures a point-in-time snapshot of the DistroQ Redis instance.

.DESCRIPTION
    Read-only with respect to the data. BGSAVE forks and writes an RDB file; it does not modify
    any key.

    Read OPERATIONS.md before relying on this. Redis holds transport and scheduling state -
    streams, consumer groups, Pending Entries Lists, the scheduled and delayed sorted sets, and the
    outbox deduplication markers. It does not hold business history. A restored RDB is therefore a
    convenience, not a source of truth: the authoritative statement of what was supposed to happen
    is the outbox table in PostgreSQL, and reconciliation is what turns that back into Redis state.

    The one thing an RDB restore genuinely recovers that reconciliation cannot is the deduplication
    markers. Without them, a re-published event can produce a second stream entry - which
    at-least-once already tolerates, but which is worth avoiding when it is this cheap.

.EXAMPLE
    .\ops\backup\backup-redis.ps1 -Container distroq-redis
#>
[CmdletBinding()]
param(
    [string]$Container = "distroq-redis",
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "."),
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker is not on PATH."
}

function Invoke-Redis {
    param([string[]]$RedisArgs)
    $auth = @()
    if ($env:DISTROQ_REDIS_PASSWORD) { $auth = @("-a", $env:DISTROQ_REDIS_PASSWORD, "--no-auth-warning") }
    $output = & docker exec $Container redis-cli @auth @RedisArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "redis-cli $RedisArgs failed: $output" }
    return ($output | Out-String).Trim()
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd'T'HHmmss'Z'")
$target = Join-Path $OutputDirectory "distroq-redis-$stamp.rdb"

# LASTSAVE before and after: BGSAVE returns immediately, so the only way to know the snapshot
# finished - rather than that the fork started - is to watch the timestamp move
$before = [long](Invoke-Redis @("LASTSAVE"))
Write-Host "Requesting BGSAVE on $Container (last save was at unix $before)"
Invoke-Redis @("BGSAVE") | Out-Null

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    if ([long](Invoke-Redis @("LASTSAVE")) -gt $before) { break }
}
$after = [long](Invoke-Redis @("LASTSAVE"))
if ($after -le $before) {
    throw "BGSAVE did not complete within $TimeoutSeconds seconds. No snapshot was copied."
}

$rdbName = Invoke-Redis @("CONFIG", "GET", "dbfilename") | Select-Object -Last 1
$rdbDir = Invoke-Redis @("CONFIG", "GET", "dir") | Select-Object -Last 1
& docker cp "${Container}:$rdbDir/$rdbName" $target
if ($LASTEXITCODE -ne 0) { throw "docker cp failed; the snapshot exists in the container but was not copied out." }

$keys = Invoke-Redis @("DBSIZE")
$sizeMb = [math]::Round((Get-Item $target).Length / 1MB, 3)

Write-Host ""
Write-Host "Redis snapshot complete."
Write-Host "  file  : $target"
Write-Host "  size  : $sizeMb MB"
Write-Host "  keys  : $keys"
Write-Host ""
Write-Host "This snapshot is transport state, not business history. PostgreSQL remains the record"
Write-Host "of what was supposed to happen; see OPERATIONS.md for what a Redis loss costs."
