param(
    [Parameter(Mandatory)][ValidatePattern('^distroq-bench-[0-9]{14}-[a-f0-9]{6}$')][string]$Project,
    [switch]$ExclusiveHostConfirmed,
    [switch]$CheckOnly,
    [switch]$AuthorizeReplacementC1
)
$ErrorActionPreference='Stop'
if(-not $CheckOnly -and (-not $ExclusiveHostConfirmed -or $env:TERM_PROGRAM -eq 'vscode')) {
    Write-Error 'Use standalone PowerShell with VS Code/dashboard closed and explicit exclusive-host confirmation.'
    exit 42
}
$arguments=@((Join-Path $PSScriptRoot 'continue-calibration.mjs'),$Project)
if($CheckOnly){$arguments+='--check-only'}
if($ExclusiveHostConfirmed){$arguments+='--exclusive-host-confirmed'}
if($AuthorizeReplacementC1){$arguments+='--authorize-replacement-c1'}
& node @arguments
exit $LASTEXITCODE