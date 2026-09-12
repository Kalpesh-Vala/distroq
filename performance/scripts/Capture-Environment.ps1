param([string]$ResultsRoot = (Join-Path $PSScriptRoot '../results'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-preflight-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$output = Join-Path $ResultsRoot $runId
if (Test-Path $output) { throw 'Refusing to overwrite evidence.' }
$null = New-Item -ItemType Directory -Path $output
function Get-Version([string]$Name, [string[]]$Arguments) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) { return @{ status = 'unavailable' } }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $text = (& $Name @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Out-String).Trim().Replace("`0", '')
        return @{ status = 'attempted'; exitCode = $LASTEXITCODE; output = $text }
    } finally { $ErrorActionPreference = $previous }
}
$cpu = Get-CimInstance Win32_Processor | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors
$os = Get-CimInstance Win32_OperatingSystem | Select-Object Caption, Version, BuildNumber, TotalVisibleMemorySize, FreePhysicalMemory
$docker = docker info --format '{{json .}}' | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Docker daemon unavailable.' }
$record = [ordered]@{
    runId = $runId
    utc = [DateTime]::UtcNow.ToString('o')
    scope = 'preflight only; no performance workload executed'
    commit = (git -C $repo rev-parse HEAD | Out-String).Trim()
    branch = (git -C $repo branch --show-current | Out-String).Trim()
    tags = @(git -C $repo tag --points-at HEAD)
    workingTree = @(git -C $repo status --porcelain)
    cpu = $cpu
    os = $os
    disks = @(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | Select-Object DeviceID, Size, FreeSpace)
    docker = $docker | Select-Object ServerVersion, OSType, OperatingSystem, Architecture, NCPU, MemTotal, Driver
    tools = @{
        java = Get-Version 'java' @('-version')
        maven = Get-Version (Join-Path $repo 'mvnw.cmd') @('--version')
        node = Get-Version 'node' @('--version')
        npm = Get-Version 'npm.cmd' @('--version')
        k6 = Get-Version 'k6' @('version')
        compose = Get-Version 'docker' @('compose', 'version')
        wsl = Get-Version 'wsl' @('--version')
    }
    containers = @(docker ps -a --format '{{json .}}' | ForEach-Object {
        $item = $_ | ConvertFrom-Json
        $item | Select-Object ID, Image, Names, Status, Ports
    })
    applicationSettings = 'not captured until isolated benchmark application exists'
    dashboardClients = 'not controlled during preflight'
    loadGeneratorColocated = $true
}
$record | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $output 'environment.json') -Encoding UTF8
Get-FileHash (Join-Path $output 'environment.json') -Algorithm SHA256 | Select-Object Hash, @{Name='File';Expression={'environment.json'}} | ConvertTo-Json | Set-Content (Join-Path $output 'manifest.json') -Encoding UTF8
Get-ChildItem $output -File | ForEach-Object { $_.IsReadOnly = $true }
Write-Output $output