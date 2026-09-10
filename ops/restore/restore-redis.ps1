<#
.SYNOPSIS
    Restores a Redis RDB snapshot into an isolated Redis container and reports what survived.

.DESCRIPTION
    DESTRUCTIVE, and deliberately unable to touch a running DistroQ Redis. It starts a *new*
    container on a separate port from the snapshot, so the restore can be inspected before anyone
    decides whether to use it. Overwriting the live instance is not something this script will do,
    because doing it while an application is connected replaces the streams underneath consumers
    that hold pending entries against them.

    What the report shows, and why each line matters:

      streams          - the job entries themselves. Present means undelivered work survived.
      consumer groups  - without these a restored stream has no delivery state at all, and every
                         entry looks new to every worker.
      pending entries  - in-flight work at snapshot time. These are the entries that will be
                         redelivered, and the reason a restore is at-least-once rather than
                         exactly-once.
      scheduled set    - user-requested execution times that had not come due.
      delayed set      - retry backoffs that had not expired.
      dedupe markers   - the only thing here that PostgreSQL cannot reconstruct. Losing them means
                         a republished outbox event can create a second stream entry.

.EXAMPLE
    .\ops\restore\restore-redis.ps1 -RdbFile ops\backup\distroq-redis-20260910T120000Z.rdb -Confirm
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RdbFile,
    [string]$Container = "distroq-redis-restore",
    [int]$Port = 6380,
    [string]$Image = "redis:7.4-alpine",
    [string]$KeyPrefix = "distroq:jobs",
    [switch]$Confirm
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $RdbFile)) { throw "No such RDB file: $RdbFile" }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "docker is not on PATH." }

if (-not $Confirm) {
    throw @"
Refusing to run without -Confirm.

This will remove any existing container named '$Container' and start a new one on port $Port
from $RdbFile.

It will NOT touch a running DistroQ Redis. Restoring into the live instance is a manual,
deliberate procedure - see OPERATIONS.md - because it replaces streams underneath consumers that
still hold pending entries against them.
"@
}

$existing = & docker ps -aq --filter "name=^/$Container$"
if ($existing) {
    Write-Host "Removing the previous restore container"
    & docker rm -f $Container | Out-Null
}

$scratch = New-Item -ItemType Directory -Force -Path (Join-Path $env:TEMP "distroq-redis-restore-$([guid]::NewGuid())")
Copy-Item $RdbFile (Join-Path $scratch "dump.rdb")

Write-Host "Starting $Container on port $Port from $RdbFile"
# appendonly no: the snapshot is an RDB, and enabling AOF would have Redis build an empty append
# log and load that instead, which restores nothing
& docker run -d --name $Container -p "${Port}:6379" `
    -v "$($scratch.FullName):/data" $Image `
    redis-server --dir /data --dbfilename dump.rdb --appendonly no | Out-Null
if ($LASTEXITCODE -ne 0) { throw "docker run failed." }

$deadline = (Get-Date).AddSeconds(30)
do {
    Start-Sleep -Milliseconds 500
    $ping = & docker exec $Container redis-cli ping 2>&1
} while ($ping -notmatch "PONG" -and (Get-Date) -lt $deadline)
if ($ping -notmatch "PONG") { throw "The restore container did not become ready." }

function Redis([string[]]$RedisArgs) {
    return (& docker exec $Container redis-cli @RedisArgs 2>&1 | Out-String).Trim()
}

Write-Host ""
Write-Host "Restored into $Container (port $Port). Contents:"
Write-Host "  total keys        : $(Redis @('DBSIZE'))"

foreach ($tier in @("high", "normal", "low")) {
    $stream = "${KeyPrefix}:stream:$tier"
    $length = Redis @("XLEN", $stream)
    $groups = Redis @("XINFO", "GROUPS", $stream)
    Write-Host ""
    Write-Host "  $stream"
    Write-Host "    entries         : $length"
    if ($groups -match "ERR|WRONGTYPE|^$") {
        Write-Host "    consumer groups : none (workers would treat every entry as new)"
    } else {
        $pending = Redis @("XPENDING", $stream, "distroq-workers")
        Write-Host "    consumer groups : present"
        Write-Host "    pending entries : $($pending -replace '\s+', ' ')"
    }
}

Write-Host ""
Write-Host "  scheduled set     : $(Redis @('ZCARD', "${KeyPrefix}:scheduled")) member(s)"
Write-Host "  delayed set       : $(Redis @('ZCARD', "${KeyPrefix}:delayed")) member(s)"
$dedupe = Redis @("EVAL", "return #redis.call('keys', ARGV[1])", "0", "distroq:outbox:published:*")
Write-Host "  dedupe markers    : $dedupe"

Write-Host ""
Write-Host "Inspect it with:  docker exec -it $Container redis-cli"
Write-Host "Discard it with:  docker rm -f $Container"
Write-Host ""
Write-Host "A missing dedupe marker is the one loss PostgreSQL cannot make good: a republished"
Write-Host "outbox event will create a second stream entry, which at-least-once tolerates and"
Write-Host "which the job's own idempotency has to absorb. Everything else here is reconstructable"
Write-Host "from the outbox table by running reconciliation."
