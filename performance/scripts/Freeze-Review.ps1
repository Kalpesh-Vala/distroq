$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = Join-Path $root ('results/' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-review-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
if (Test-Path $output) { throw 'Refusing to overwrite a review.' }
$null = New-Item -ItemType Directory $output
foreach ($name in @('README.md','TEST_PLAN.md','reports','scripts','workloads','environments')) {
    Copy-Item (Join-Path $root $name) -Destination $output -Recurse
}
@{ utc=[DateTime]::UtcNow.ToString('o'); classification='PREFLIGHT_AND_P0_ONLY_NOT_PERFORMANCE_BASELINE';
    sourceCommit=(git rev-parse HEAD | Out-String).Trim(); sourceDirty=@(git status --porcelain);
    originalEvidenceLocation='performance/results'; command='./performance/scripts/Freeze-Review.ps1';
    browserObservation=@{utc='2026-09-12T12:51:07.773547545Z';url='http://127.0.0.1:18080/dashboard/';status=404;code='NOT_FOUND';tool='integrated Playwright page.goto'}
} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'review.json') -Encoding UTF8
@(Get-ChildItem $output -File -Recurse | ForEach-Object { @{file=$_.FullName.Substring($output.Length+1);sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash} }) | ConvertTo-Json | Set-Content (Join-Path $output 'manifest.json') -Encoding UTF8
Get-ChildItem $output -File -Recurse | ForEach-Object { $_.IsReadOnly=$true }
Write-Output $output