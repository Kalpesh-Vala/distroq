param(
    [Parameter(Mandatory)][string]$Output,
    [ValidateRange(10,1800)][int]$Seconds = 300,
    [int]$OrchestratorPid = 0
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (Test-Path $Output) { throw 'Host evidence directory already exists.' }
$null = New-Item -ItemType Directory $Output
$failures = New-Object 'System.Collections.Generic.List[string]'
$rows = New-Object 'System.Collections.Generic.List[object]'
$expected = [int][Math]::Ceiling($Seconds / 5) + 1
$logical = [int]((Get-CimInstance Win32_Processor | Measure-Object NumberOfLogicalProcessors -Sum).Sum)
$start = [DateTime]::UtcNow
$plan = (powercfg /getactivescheme | Out-String).Trim()
function Write-Json([string]$Name, $Value) {
    $Value | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $Output $Name) -Encoding UTF8
}
function Check-Power {
    $battery = @(Get-CimInstance Win32_Battery)
    if ($battery.Count -and @($battery | Where-Object { $_.BatteryStatus -ne 2 }).Count) { throw 'AC power is not confirmed.' }
    foreach ($setting in @('STANDBYIDLE','HIBERNATEIDLE')) {
        $text = powercfg /query SCHEME_CURRENT SUB_SLEEP $setting | Out-String
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect sleep policy.' }
        $indexes = [regex]::Matches($text, '0x[0-9a-fA-F]{8}')
        if ($indexes.Count -lt 2 -or $indexes[$indexes.Count-2].Value -ne '0x00000000') { throw 'AC sleep/hibernate timeout must be disabled manually.' }
    }
    if ((powercfg /getactivescheme | Out-String).Trim() -ne $plan) { throw 'Power plan changed.' }
}
try {
    Check-Power
    Write-Json 'configuration.json' @{utc=$start.ToString('o');logicalProcessors=$logical;powerPlan=$plan;durationSeconds=$Seconds;
      normalization='Sum non-benchmark raw process CPU / host logical CPU count';collectorPid=$PID;orchestratorPid=$OrchestratorPid;
      assumptions='Only benchmark work inside Docker/WSL; no unrelated Node or PowerShell work. VM CPU is unallocated, not reported as zero.'}
    $paths = @('\Processor(_Total)\% Processor Time','\Processor Information(_Total)\Processor Frequency',
      '\Processor Information(_Total)\% Performance Limit','\Processor Information(_Total)\Performance Limit Flags')
    Get-Counter -Counter $paths -SampleInterval 5 -MaxSamples $expected -ErrorAction Stop | ForEach-Object {
        $hardware = $_
        $processes = @(Get-CimInstance Win32_PerfFormattedData_PerfProc_Process | Where-Object { $_.Name -notin @('Idle','_Total') } | ForEach-Object {
            $classification = if ($_.IDProcess -in @($PID,$OrchestratorPid)) { 'collector' } elseif ($_.Name -like 'vmmem*') { 'unallocated-vm' } else { 'non-benchmark' }
            [pscustomobject]@{name=$_.Name;pid=$_.IDProcess;rawCpuPercent=[double]$_.PercentProcessorTime;
              normalizedCpuPercent=[double]$_.PercentProcessorTime/$logical;classification=$classification}
        })
        $known = @($processes | Where-Object { $_.classification -eq 'non-benchmark' })
        $raw = [double](($known | Measure-Object rawCpuPercent -Sum).Sum)
        $row = [pscustomobject]@{utc=[DateTime]::UtcNow.ToString('o');utcMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();
          normalizedNonBenchmarkCpuPercent=$raw/$logical;rawNonBenchmarkCpuPercent=$raw;processes=$processes;
          hardware=@($hardware.CounterSamples | Select-Object Path,CookedValue,Status)}
        $rows.Add($row)
        $row | ConvertTo-Json -Depth 8 -Compress | Add-Content (Join-Path $Output 'samples.jsonl') -Encoding UTF8
        if (@($hardware.CounterSamples | Where-Object { $_.Status -ne 0 }).Count) { $failures.Add('Invalid hardware counter sample.') }
        if ($rows.Count % 12 -eq 0) { Check-Power; Write-Output "Host window: $($rows.Count)/$expected samples" }
    }
    Check-Power
    $mean = ($rows | Measure-Object normalizedNonBenchmarkCpuPercent -Average).Average
    $spikes = @($rows | Where-Object { $_.normalizedNonBenchmarkCpuPercent -gt 20 }).Count
    if ($mean -gt 10 -or $spikes/$rows.Count -gt 0.1) { $failures.Add('Normalized host contention threshold exceeded.') }
    $span = ($rows[-1].utcMs-$rows[0].utcMs)/1000
    if ($span -lt $Seconds -or $rows.Count -ne $expected) { $failures.Add('Required host sample window incomplete.') }
    $gap = 0
    for ($index=1;$index -lt $rows.Count;$index++) { $gap=[Math]::Max($gap,($rows[$index].utcMs-$rows[$index-1].utcMs)/1000) }
    if ($gap -gt 15) { $failures.Add('Material sample gap.') }
    $limited = @($rows | Where-Object { @($_.hardware | Where-Object { ($_.Path -like '*\% performance limit' -and $_.CookedValue -lt 100) -or ($_.Path -like '*\performance limit flags' -and $_.CookedValue -ne 0) }).Count -gt 0 })
    if ($limited.Count -gt 0) { $failures.Add('Performance limiting reported; requires manual review, not an inferred thermal diagnosis.') }
    $query="*[System[(EventID=42 or EventID=107 or EventID=506 or EventID=507) and TimeCreated[@SystemTime>='$($start.ToString('o'))']]]"
    $events=wevtutil qe System "/q:$query" /f:xml | Out-String
    $events | Set-Content (Join-Path $Output 'power-events.xml') -Encoding UTF8
    if ($LASTEXITCODE -ne 0 -or $events.Trim()) { $failures.Add('Power event audit failed or sleep/resume occurred.') }
    Write-Json 'summary.json' @{samples=$rows.Count;spanSeconds=$span;maximumGapSeconds=$gap;meanNormalizedCpuPercent=$mean;
      samplesOver20Percent=$spikes;fractionOver20Percent=$spikes/$rows.Count;limitedSamples=$limited.Count;
      caveat='Formatted CIM process counters have integer precision; frequency counters do not prove absence of thermal throttling. Mixed VM attribution relies on operator attestation.'}
} catch { $failures.Add($_.Exception.Message) } finally {
    Write-Json 'validity.json' @{status=$(if($failures.Count){'INVALID'}else{'VALID'});reasons=@($failures);samples=$rows.Count;
      startUtc=$start.ToString('o');finishUtc=[DateTime]::UtcNow.ToString('o')}
}
if ($failures.Count) { exit 2 }
exit 0