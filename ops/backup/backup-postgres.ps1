<#
.SYNOPSIS
    Takes a custom-format logical backup of the DistroQ PostgreSQL database.

.DESCRIPTION
    Read-only. Nothing in this script writes to the source database, so it is safe to run against
    a live system; pg_dump takes a consistent snapshot without blocking writers.

    Custom format (-Fc) rather than plain SQL, because it is what pg_restore needs in order to
    restore selectively, in parallel, and into a database whose owner differs from the source.

    PostgreSQL is the durable record: jobs, attempts, dead letters, outbox intent, the reliability
    audit trail, the effect ledger and the idempotency keys are all here. Redis is not backed up by
    this script and mostly does not need to be - see ops/backup/backup-redis.ps1 and OPERATIONS.md
    for what a Redis loss actually costs.

.EXAMPLE
    .\ops\backup\backup-postgres.ps1 -Host localhost -Port 5433 -Database distroq -User distroq

.NOTES
    The password is never a parameter. Set PGPASSWORD in the environment, or use a .pgpass file,
    so it does not end up in the shell history or in a process listing.
#>
[CmdletBinding()]
param(
    [string]$DbHost = "localhost",
    [int]$Port = 5433,
    [string]$Database = "distroq",
    [string]$User = "distroq",
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "."),
    [string]$Label = ""
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command pg_dump -ErrorAction SilentlyContinue)) {
    throw "pg_dump is not on PATH. Install the PostgreSQL client tools, or run the dump inside the container: docker exec distroq-postgres pg_dump ..."
}

if (-not $env:PGPASSWORD) {
    Write-Warning "PGPASSWORD is not set. pg_dump will prompt, or fail if the connection is non-interactive."
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd'T'HHmmss'Z'")
$suffix = if ($Label) { "-$Label" } else { "" }
$dumpFile = Join-Path $OutputDirectory "distroq-$Database-$stamp$suffix.dump"

Write-Host "Backing up $Database from ${DbHost}:${Port} to $dumpFile"

# --no-owner and --no-privileges: a restore into a test database should not fail because the
# production role does not exist there
& pg_dump --host=$DbHost --port=$Port --username=$User --dbname=$Database `
    --format=custom --compress=6 --no-owner --no-privileges --file=$dumpFile
if ($LASTEXITCODE -ne 0) {
    throw "pg_dump exited with $LASTEXITCODE; no usable backup was produced."
}

# A backup nobody has read is a hope, not a backup. Listing the archive proves it is readable and
# names what is in it, which is the cheapest possible verification step.
$manifest = "$dumpFile.manifest.txt"
& pg_restore --list $dumpFile | Out-File -FilePath $manifest -Encoding utf8
if ($LASTEXITCODE -ne 0) {
    throw "The dump was written but pg_restore could not read it back. Treat $dumpFile as invalid."
}

$sizeMb = [math]::Round((Get-Item $dumpFile).Length / 1MB, 2)
$tables = (Get-Content $manifest | Select-String "TABLE DATA").Count

Write-Host ""
Write-Host "Backup complete."
Write-Host "  file      : $dumpFile"
Write-Host "  size      : $sizeMb MB"
Write-Host "  manifest  : $manifest"
Write-Host "  tables    : $tables with data"
Write-Host ""
Write-Host "Verify it by restoring into a scratch database before you need it:"
Write-Host "  .\ops\restore\restore-postgres.ps1 -DumpFile `"$dumpFile`" -Database distroq_restore_test -Confirm"
