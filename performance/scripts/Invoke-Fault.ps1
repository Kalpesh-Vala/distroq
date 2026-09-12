[CmdletBinding(SupportsShouldProcess=$true, ConfirmImpact='High')]
param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][ValidateSet('app','redis','postgres')][string]$Service,
    [Parameter(Mandatory)][ValidateSet('Pause','Kill','GracefulStop')][string]$Fault,
    [ValidateSet(10,30,60)][int]$Seconds = 10,
    [Parameter(Mandatory)][string]$BackupManifest,
    [Parameter(Mandatory)][string]$ConfirmProject
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Project -notmatch '^distroq-bench-[0-9]{14}-[a-f0-9]{6}$' -or $ConfirmProject -cne $Project) { throw 'Exact isolated benchmark project confirmation required.' }
if ($Service -ne 'app' -and $Fault -ne 'Pause') { throw 'Datastore tests support pause only; never reset or remove data.' }
$private = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../private')) + [IO.Path]::DirectorySeparatorChar
$manifestPath = (Resolve-Path $BackupManifest).Path
if (-not $manifestPath.StartsWith($private, [StringComparison]::OrdinalIgnoreCase)) { throw 'Backups must be under ignored performance/private.' }
$proof = Get-Content $manifestPath -Raw | ConvertFrom-Json
if ($proof.project -cne $Project) { throw 'Backup belongs to another project.' }
if ([DateTimeOffset]::UtcNow - [DateTimeOffset]::Parse($proof.createdUtc) -gt [TimeSpan]::FromHours(1)) { throw 'Backup is older than one hour.' }
$restorePath = Join-Path (Split-Path $manifestPath) $proof.restoreEvidence.file
if ((Get-FileHash $restorePath -Algorithm SHA256).Hash -cne $proof.restoreEvidence.sha256) { throw 'Restore evidence checksum mismatch.' }
$restore = Get-Content $restorePath -Raw | ConvertFrom-Json
if ($restore.project -cne $Project -or $restore.status -cne 'PASS' -or -not $restore.postgresVerified -or -not $restore.redisVerified) { throw 'Both scratch restores must be verified.' }
foreach ($store in @('postgres','redis')) {
    $backup = $proof.$store
    $backupPath = [IO.Path]::GetFullPath((Join-Path (Split-Path $manifestPath) $backup.file))
    if (-not $backupPath.StartsWith($private, [StringComparison]::OrdinalIgnoreCase)) { throw 'Backup escapes private directory.' }
    if ((Get-Item $backupPath).Length -eq 0 -or (Get-FileHash $backupPath -Algorithm SHA256).Hash -cne $backup.sha256) { throw 'Backup is empty or checksum differs.' }
    $container = @(docker ps -q --filter "label=com.docker.compose.project=$Project" --filter "label=com.docker.compose.service=$store")
    if ($container.Count -ne 1) { throw 'Exactly one running benchmark store required.' }
    $fullId = docker inspect $container[0] --format '{{.Id}}'
    if ($fullId -cne $backup.containerId) { throw 'Backup does not match the current store container.' }
}
$target = @(docker ps -q --filter "label=com.docker.compose.project=$Project" --filter "label=com.docker.compose.service=$Service")
if ($target.Count -ne 1) { throw 'Expected one running target.' }
if (-not $PSCmdlet.ShouldProcess("$Project/$Service/$($target[0])", "$Fault for approximately $Seconds seconds; observer startup and request timeouts add overhead")) { return }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = Join-Path $root ('results/' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-fault-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
if (Test-Path $output) { throw 'Evidence already exists.' }
$null = New-Item -ItemType Directory $output
$record = [ordered]@{ project=$Project; service=$Service; fault=$Fault; requestedSeconds=$Seconds;
    status='INCONCLUSIVE'; restoreManifestSha256=(Get-FileHash $manifestPath -Algorithm SHA256).Hash;
    limitation='Health observer only. Recovery requires separately correlated useful-work and reconciliation evidence.' }
$restoreNeeded = $false
try {
    $record['injectionUtc'] = [DateTime]::UtcNow.ToString('o')
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $restoreNeeded = $true
    if ($Fault -eq 'Pause') { docker pause $target[0] }
    elseif ($Fault -eq 'Kill') { docker kill --signal KILL $target[0] }
    else { docker stop --time 90 $target[0] }
    $faultExit = $LASTEXITCODE
    if ($faultExit -ne 0) { throw 'Fault injection command failed; attempting restoration.' }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        docker run --rm --network "${Project}_default" -e "SECONDS=$Seconds" -v "${root}/workloads:/workloads:ro" -v "${output}:/results" grafana/k6:1.3.0 run --log-format raw --console-output /results/health.jsonl /workloads/fault-observer.js 2>&1 | ForEach-Object { $_.ToString() } | Set-Content (Join-Path $output 'observer.txt') -Encoding UTF8
        $record['observerExitCode'] = $LASTEXITCODE
    } finally { $ErrorActionPreference=$previous }
} catch { $record['error']=$_.Exception.Message } finally {
    if ($restoreNeeded) {
        if ($Fault -eq 'Pause') { docker unpause $target[0] } else { docker start $target[0] }
        $record['restoreExitCode']=$LASTEXITCODE
        $record['restoreCommandUtc']=[DateTime]::UtcNow.ToString('o')
        $record['observedInjectionToRestoreCommandSeconds']=$clock.Elapsed.TotalSeconds
    }
    $record | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'fault.json') -Encoding UTF8
    @(Get-ChildItem $output -File | ForEach-Object { @{file=$_.Name;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash} }) | ConvertTo-Json | Set-Content (Join-Path $output 'manifest.json') -Encoding UTF8
    Get-ChildItem $output -File | ForEach-Object { $_.IsReadOnly=$true }
}
Write-Output $output
if ($restoreNeeded -and $record.restoreExitCode -ne 0) { throw 'RESTORATION FAILED: restore the named benchmark service before any other test.' }