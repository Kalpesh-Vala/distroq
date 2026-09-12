$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$cases = @(
    @{ Project='distroq'; ConfirmProject='distroq'; Service='redis'; Fault='Pause'; BackupManifest='missing.json' },
    @{ Project='distroq-bench-20260912000000-abcdef'; ConfirmProject='wrong'; Service='app'; Fault='Kill'; BackupManifest='missing.json' },
    @{ Project='distroq-bench-20260912000000-abcdef'; ConfirmProject='distroq-bench-20260912000000-abcdef'; Service='postgres'; Fault='Kill'; BackupManifest='missing.json' },
    @{ Project='distroq-bench-20260912000000-abcdef'; ConfirmProject='distroq-bench-20260912000000-abcdef'; Service='redis'; Fault='Pause'; BackupManifest='missing.json' }
)
$refused = 0
foreach ($parameters in $cases) {
    try { & (Join-Path $PSScriptRoot 'Invoke-Fault.ps1') @parameters -WhatIf }
    catch { $refused++ }
}
if ($refused -ne $cases.Count) { throw 'An unsafe fault request was not rejected.' }
Write-Output "$refused/$($cases.Count) unsafe requests rejected before Docker mutation. Actual injection remains untested."