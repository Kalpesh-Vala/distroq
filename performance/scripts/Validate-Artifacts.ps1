param([Parameter(Mandatory)][string]$Project)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Project -notmatch '^distroq-bench-[0-9]{14}-[a-f0-9]{6}$') { throw 'Not a benchmark project.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$checkedFiles = 0
foreach ($script in Get-ChildItem (Join-Path $root 'scripts') -Filter '*.ps1') {
    $tokens = $null
    $parseErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw "PowerShell parse errors in $($script.Name): $parseErrors" }
}
foreach ($manifest in Get-ChildItem (Join-Path $root 'results') -Filter manifest.json -Recurse) {
    $entries = Get-Content $manifest.FullName -Raw | ConvertFrom-Json
    foreach ($entry in $entries) {
        $file = Join-Path $manifest.DirectoryName $entry.file
        $hash = if ($entry.PSObject.Properties['sha256']) { $entry.sha256 } else { $entry.Hash }
        if ((Get-FileHash $file -Algorithm SHA256).Hash -cne $hash) { throw "Evidence checksum mismatch: $file" }
        if (-not (Get-Item $file).IsReadOnly) { throw "Evidence is not marked read-only: $file" }
        $checkedFiles++
    }
}
$appId = docker ps -q --filter "label=com.docker.compose.project=$Project" --filter 'label=com.docker.compose.service=app'
$app = (docker inspect $appId | ConvertFrom-Json)[0]
$secrets = @($app.Config.Env | Where-Object { $_ -match '^(DISTROQ_ADMIN_TOKEN|DISTROQ_DB_PASSWORD|DISTROQ_REDIS_PASSWORD)=' } | ForEach-Object { $_.Split('=',2)[1] })
if ($secrets.Count -ne 3) { throw 'Cannot verify generated-secret exclusion.' }
$scanned = 0
foreach ($file in Get-ChildItem $root -File -Recurse | Where-Object { $_.FullName -notmatch '[\\/]private[\\/]|[\\/]node_modules[\\/]' }) {
    $text = [IO.File]::ReadAllText($file.FullName)
    foreach ($secret in $secrets) {
        if ($secret -and $text.Contains($secret)) { throw "Generated secret found in $($file.FullName); do not commit." }
    }
    if ($file.FullName -match '[\\/]results[\\/]' -and $text -match '"(?:payload|idempotency_key|idempotencyKey)"\s*:') { throw "Sensitive field found in $($file.FullName); review before committing." }
    $scanned++
}
$output = Join-Path $root ('results/' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-validation-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
if (Test-Path $output) { throw 'Evidence exists.' }
$null = New-Item -ItemType Directory $output
& (Join-Path $PSScriptRoot 'Summarize-P0.ps1') -RunDirectory (Join-Path $root 'results/20260912T124253607Z-P0-974571ea') | Set-Content (Join-Path $output 'derived-smoke-summary.json') -Encoding UTF8
$safety = & (Join-Path $PSScriptRoot 'Test-Safety.ps1')
$result = @{ utc=[DateTime]::UtcNow.ToString('o'); status='PASS'; command="./performance/scripts/Validate-Artifacts.ps1 -Project $Project";
    evidenceFilesHashVerified=$checkedFiles; filesSecretScanned=$scanned; safety=$safety;
    limits='Syntax, recorded manifest integrity, generated-secret and field-name checks only. Not a full security audit or performance validation.' }
$result | ConvertTo-Json | Set-Content (Join-Path $output 'validation.json') -Encoding UTF8
@(Get-ChildItem $output -File | ForEach-Object { @{file=$_.Name;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash} }) | ConvertTo-Json | Set-Content (Join-Path $output 'manifest.json') -Encoding UTF8
Get-ChildItem $output -File | ForEach-Object { $_.IsReadOnly=$true }
Write-Output $output
Write-Output ($result | ConvertTo-Json)