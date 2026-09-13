param(
    [Parameter(Mandatory)][ValidatePattern('^distroq-bench-[0-9]{14}-[a-f0-9]{6}$')][string]$Project,
    [switch]$CheckOnly,
    [switch]$ExclusiveHostConfirmed
)
$ErrorActionPreference='Stop'
if(-not $CheckOnly -and -not $ExclusiveHostConfirmed){throw 'Explicit host reservation required.'}
if(-not $CheckOnly -and $env:TERM_PROGRAM -eq 'vscode'){throw 'Use standalone PowerShell with VS Code closed.'}
$arguments=@((Join-Path $PSScriptRoot 'calibrate-telemetry.mjs'),$Project)
if($CheckOnly){$arguments+='--check-only'}
if($ExclusiveHostConfirmed){$arguments+='--exclusive-host-confirmed'}
& node @arguments
if($LASTEXITCODE -ne 0){throw "Calibration stopped (exit $LASTEXITCODE); preserve evidence and do not run P1."}