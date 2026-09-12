param(
    [Parameter(Mandatory)][ValidatePattern('^distroq-bench-[0-9]{14}-[a-f0-9]{6}$')][string]$Project,
    [switch]$CheckOnly,
    [switch]$ExclusiveHostConfirmed
)
$ErrorActionPreference = 'Stop'
if (-not $CheckOnly -and -not $ExclusiveHostConfirmed) { throw 'Confirm exclusive host reservation before launching.' }
if ($env:TERM_PROGRAM -eq 'vscode' -and -not $CheckOnly) { throw 'Launch from standalone PowerShell, not a VS Code terminal.' }
$arguments = @((Join-Path $PSScriptRoot 'qualification.mjs'),$Project)
if ($CheckOnly) { $arguments += '--check-only' }
if ($ExclusiveHostConfirmed) { $arguments += '--exclusive-host-confirmed' }
& node @arguments
if ($LASTEXITCODE -ne 0) { throw "Qualification stopped with exit code $LASTEXITCODE. Inspect the newly printed evidence directory." }