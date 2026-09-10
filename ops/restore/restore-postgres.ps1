<#
.SYNOPSIS
    Restores a DistroQ PostgreSQL backup into a database, and verifies the result.

.DESCRIPTION
    DESTRUCTIVE. This drops and recreates the target database, so it refuses to do anything
    without -Confirm, and refuses outright if the target name looks like a production database.
    Both guards exist because the failure mode is unrecoverable and the command that causes it
    differs from a safe one by a single word.

    After restoring it checks the three things that make a restore trustworthy rather than merely
    finished: Flyway history is present and successful, the row counts are reported so they can be
    compared against the source, and the schema is complete enough for Hibernate's startup
    validation to pass.

.EXAMPLE
    .\ops\restore\restore-postgres.ps1 -DumpFile ops\backup\distroq-20260910T120000Z.dump `
        -Database distroq_restore_test -Confirm
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DumpFile,
    [string]$DbHost = "localhost",
    [int]$Port = 5433,
    [Parameter(Mandatory = $true)][string]$Database,
    [string]$User = "distroq",
    [string]$MaintenanceDatabase = "postgres",
    [switch]$Confirm,
    [switch]$AllowProductionName
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $DumpFile)) {
    throw "No such dump file: $DumpFile"
}
foreach ($tool in @("pg_restore", "psql", "createdb", "dropdb")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool is not on PATH. Install the PostgreSQL client tools."
    }
}

# The safe default is "this is a scratch database". Restoring over the real one is possible and
# has to be spelled out, because the difference between a drill and an outage is this flag.
if ($Database -eq "distroq" -and -not $AllowProductionName) {
    throw @"
Refusing to restore into a database named 'distroq'.

That is the default production database name. If this really is a scratch instance, pass
-AllowProductionName. If it is not, restore into a new database first and compare it:

  .\ops\restore\restore-postgres.ps1 -DumpFile "$DumpFile" -Database distroq_restore_test -Confirm
"@
}

if (-not $Confirm) {
    throw @"
Refusing to run without -Confirm.

This will DROP the database '$Database' on ${DbHost}:${Port} and recreate it from
$DumpFile. Every row currently in it will be gone.

Re-run with -Confirm when that is what you intend.
"@
}

if (-not $env:PGPASSWORD) {
    Write-Warning "PGPASSWORD is not set; the client tools will prompt for each connection."
}

Write-Host "Dropping and recreating '$Database' on ${DbHost}:${Port}"
& dropdb --host=$DbHost --port=$Port --username=$User --if-exists $Database
if ($LASTEXITCODE -ne 0) { throw "dropdb failed with $LASTEXITCODE" }
& createdb --host=$DbHost --port=$Port --username=$User $Database
if ($LASTEXITCODE -ne 0) { throw "createdb failed with $LASTEXITCODE" }

Write-Host "Restoring $DumpFile"
# --exit-on-error: a restore that reports success after skipping a failed table is worse than one
# that stops, because the gap is only found later
& pg_restore --host=$DbHost --port=$Port --username=$User --dbname=$Database `
    --no-owner --no-privileges --exit-on-error $DumpFile
if ($LASTEXITCODE -ne 0) { throw "pg_restore failed with $LASTEXITCODE; the restore is not usable." }

function Invoke-Query([string]$sql) {
    $result = & psql --host=$DbHost --port=$Port --username=$User --dbname=$Database `
        --tuples-only --no-align --command=$sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed running: $sql" }
    return $result
}

Write-Host ""
Write-Host "Flyway history:"
Invoke-Query @"
SELECT version || ' | ' || description || ' | success=' || success
FROM flyway_schema_history ORDER BY installed_rank;
"@ | ForEach-Object { Write-Host "  $_" }

$failed = Invoke-Query "SELECT count(*) FROM flyway_schema_history WHERE success = false;"
if ([int]$failed -ne 0) {
    throw "The restored database has $failed failed migration(s) recorded. Do not use it."
}

Write-Host ""
Write-Host "Row counts (compare these against the source):"
$tables = @("jobs", "job_attempts", "dead_letters", "outbox_events", "idempotency_keys",
            "reliability_actions", "job_effects", "effect_counters")
foreach ($table in $tables) {
    $exists = Invoke-Query "SELECT to_regclass('public.$table') IS NOT NULL;"
    if ($exists.Trim() -eq "t") {
        $count = Invoke-Query "SELECT count(*) FROM $table;"
        Write-Host ("  {0,-22} {1}" -f $table, $count.Trim())
    } else {
        Write-Host ("  {0,-22} (absent)" -f $table)
    }
}

Write-Host ""
Write-Host "Restore complete. The remaining check is Hibernate's, and it is the one that matters:"
Write-Host "  `$env:SPRING_PROFILES_ACTIVE='production'"
Write-Host "  `$env:DISTROQ_DB_URL='jdbc:postgresql://${DbHost}:${Port}/$Database'"
Write-Host "  .\mvnw.cmd spring-boot:run"
Write-Host "A clean start means ddl-auto=validate agreed with the restored schema."
