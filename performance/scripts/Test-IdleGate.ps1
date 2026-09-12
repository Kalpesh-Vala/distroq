param([switch]$SelfTest)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Get-ContentionVerdict([double[]]$RawTotals, [int]$LogicalProcessors) {
    if ($LogicalProcessors -le 0 -or $RawTotals.Count -eq 0) { throw 'CPU topology and samples required.' }
    $normalized = @($RawTotals | ForEach-Object { $_ / $LogicalProcessors })
    $mean = ($normalized | Measure-Object -Average).Average
    $exceeded = @($normalized | Where-Object { $_ -gt 20 }).Count
    @{ meanNormalizedPercent=$mean; peakNormalizedPercent=($normalized | Measure-Object -Maximum).Maximum;
       samplesOver20Percent=$exceeded; fractionOver20Percent=$exceeded/$normalized.Count;
       failed=($mean -gt 10 -or $exceeded/$normalized.Count -gt 0.1) }
}
if ($SelfTest) {
    if ((Get-ContentionVerdict @(80,80) 8).failed) { throw 'Exactly 10 percent must not fail.' }
    if (-not (Get-ContentionVerdict @(81,81) 8).failed) { throw 'Greater than 10 percent must fail.' }
    if (-not (Get-ContentionVerdict @(161,0,0,0,0) 8).failed) { throw 'Spike fraction must fail independently.' }
    Write-Output 'PASS: normalized mean and spike thresholds, including strict boundaries.'
    return
}
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$project = 'distroq-bench-20260912123428-f8051a'
$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-IDLE-GATE-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$output = Join-Path $root "results/$runId"
if (Test-Path $output) { throw 'Evidence already exists.' }
$null = New-Item -ItemType Directory $output
$reasons = New-Object 'System.Collections.Generic.List[string]'
$samples = New-Object 'System.Collections.Generic.List[object]'
$result = [ordered]@{runId=$runId;status='INCONCLUSIVE';project=$project;command='./performance/scripts/Test-IdleGate.ps1';workload='none; idle host only'}
function Save-Json([string]$Name, $Value) { $Value | ConvertTo-Json -Depth 14 | Set-Content (Join-Path $output $Name) -Encoding UTF8 }
function Get-Ownership {
    @(docker ps -aq | ForEach-Object {
        $item = (docker inspect $_ | ConvertFrom-Json)[0]
        $projectLabel = $item.Config.Labels.PSObject.Properties['com.docker.compose.project']
        if ($projectLabel -and $projectLabel.Value -in @($project,'distroq')) {
            @{id=$item.Id;name=$item.Name;project=$item.Config.Labels.'com.docker.compose.project';startedAt=$item.State.StartedAt;
              status=$item.State.Status;restarts=$item.RestartCount;mounts=@($item.Mounts | Select-Object Type,Name,Destination)}
        }
    })
}
try {
    $verified=0
    foreach ($manifest in Get-ChildItem (Join-Path $root 'results') -Filter manifest.json -Recurse) {
        $entries=Get-Content $manifest.FullName -Raw | ConvertFrom-Json
        foreach($entry in $entries) {
            $file=Join-Path $manifest.DirectoryName $entry.file
            $hash=if($entry.PSObject.Properties['sha256']){$entry.sha256}else{$entry.Hash}
            if((Get-FileHash $file -Algorithm SHA256).Hash -ne $hash){throw "Preserved evidence hash mismatch: $file"}
            $verified++
        }
    }
    $result.preservedFilesHashVerified=$verified
    $branch=(git branch --show-current | Out-String).Trim()
    if($branch -ne 'benchmark-v1.1'){throw 'Wrong branch.'}
    $sourceDiff=@(git diff HEAD -- src dashboard pom.xml Dockerfile)
    if($sourceDiff.Count){throw 'Application source differs from baseline.'}
    $ownership=Get-Ownership
    Save-Json 'ownership-before.json' $ownership
    $expected=@('563ea44afd6d','f96e8593504d','c1953f059622','b86374b3d4a0','af752c1886b6')
    if($ownership.Count -ne 5){throw 'Unexpected Compose membership.'}
    foreach($item in $ownership){if($item.id.Substring(0,12) -notin $expected -or $item.restarts -ne 0 -or $item.status -ne 'running'){throw 'Container identity/lifecycle changed.'}}
    $volumes=docker volume inspect "${project}_pgdata" "${project}_redisdata" | ConvertFrom-Json
    Save-Json 'volume-ownership.json' @($volumes | ForEach-Object { $_ | Select-Object Name,Labels,Driver })
    foreach($volume in $volumes){if($volume.Labels.'com.docker.compose.project' -ne $project){throw 'Volume project mismatch.'}}
    $cpu=Get-CimInstance Win32_Processor
    $logical=[int](($cpu | Measure-Object NumberOfLogicalProcessors -Sum).Sum)
    $battery=Get-CimInstance Win32_Battery | Select-Object BatteryStatus,EstimatedChargeRemaining
    if($battery.BatteryStatus -ne 2){throw 'External power not confirmed.'}
    $plan=(powercfg /getactivescheme | Out-String).Trim()
    $sleep=(powercfg /query SCHEME_CURRENT SUB_SLEEP | Out-String)
    $sleep | Set-Content (Join-Path $output 'sleep-policy.txt') -Encoding UTF8
    $start=[DateTime]::UtcNow
    Save-Json 'environment.json' @{utc=$start.ToString('o');branch=$branch;gitStatus=@(git status --porcelain);commit=(git rev-parse HEAD | Out-String).Trim();
      cpu=@($cpu | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors,MaxClockSpeed,CurrentClockSpeed);logicalProcessors=$logical;
      battery=$battery;powerPlan=$plan;intervalSeconds=5;requestedSamples=61;normalization='raw process PercentProcessorTime / logicalProcessors';
      classification='Exclude Idle/_Total, collector PID, and mixed vmmemWSL from known competition. Mixed VM is unallocated, not asserted to be benchmark-only.'}
    $paths=@('\Processor(_Total)\% Processor Time','\Process(*)\% Processor Time','\Process(*)\ID Process',
      '\Processor Information(_Total)\Processor Frequency','\Processor Information(_Total)\% Processor Performance',
      '\Processor Information(_Total)\% Performance Limit','\Processor Information(_Total)\Performance Limit Flags')
    $counterErrors=@()
    Get-Counter -Counter $paths -SampleInterval 5 -MaxSamples 61 -ErrorAction Continue -ErrorVariable +counterErrors | ForEach-Object {
        $set=$_
        $processIds=@{}
        foreach($counter in $set.CounterSamples){if($counter.Path -like '*\id process'){$processIds[$counter.InstanceName]=[int]$counter.CookedValue}}
        $processes=@(foreach($counter in $set.CounterSamples){
            if($counter.Path -notlike '*\process(*)\% processor time' -or $counter.InstanceName -in @('idle','_total')){continue}
            $processId=$processIds[$counter.InstanceName]
            $classification=if($processId -eq $PID){'collector'}elseif($counter.InstanceName -like 'vmmem*'){'unallocated-vm'}else{'non-benchmark'}
            [pscustomobject]@{name=$counter.InstanceName;pid=$processId;rawCpuPercent=$counter.CookedValue;normalizedCpuPercent=$counter.CookedValue/$logical;classification=$classification;status=$counter.Status}
        })
        $rawTotal=($processes | Where-Object {$_.classification -eq 'non-benchmark'} | Measure-Object rawCpuPercent -Sum).Sum
        $sample=@{utc=$set.Timestamp.ToUniversalTime().ToString('o');utcMs=([DateTimeOffset]$set.Timestamp).ToUnixTimeMilliseconds();
          rawNonBenchmarkCpuPercent=$rawTotal;normalizedNonBenchmarkCpuPercent=$rawTotal/$logical;processes=$processes;
          hardware=@($set.CounterSamples | Where-Object {$_.Path -notlike '*\process(*)\*'} | Select-Object Path,CookedValue,Status)}
        $samples.Add($sample)
        $sample | ConvertTo-Json -Depth 8 -Compress | Add-Content (Join-Path $output 'idle-samples.jsonl') -Encoding UTF8
        if($samples.Count % 12 -eq 0){Write-Output "Idle telemetry: $($samples.Count)/61 samples captured."}
    }
    $end=[DateTime]::UtcNow
    $result.counterErrorCount=$counterErrors.Count
    if($counterErrors.Count){$reasons.Add('Performance counter errors: incomplete telemetry; see counter-errors.json.')}
    Save-Json 'counter-errors.json' @($counterErrors | ForEach-Object {$_.Exception.Message})
    $result.samples=$samples.Count
    $result.sampleSpanSeconds=if($samples.Count -ge 2){($samples[-1].utcMs-$samples[0].utcMs)/1000}else{0}
    if($result.sampleSpanSeconds -lt 300 -or $samples.Count -ne 61){$reasons.Add('Five-minute idle window incomplete.')}
    $result.contention=Get-ContentionVerdict @($samples | ForEach-Object {$_.rawNonBenchmarkCpuPercent}) $logical
    if($result.contention.failed){$reasons.Add('Known non-benchmark aggregate CPU violates normalized host-contention rule.')}
    $result.topProcesses=@($samples | ForEach-Object {$_.processes} | Group-Object pid | ForEach-Object {
      @{pid=$_.Name;names=@($_.Group.name | Select-Object -Unique);classification=$_.Group[0].classification;
        rawMeanCpuPercent=($_.Group.rawCpuPercent | Measure-Object -Sum).Sum/$samples.Count;
        normalizedMeanCpuPercent=($_.Group.normalizedCpuPercent | Measure-Object -Sum).Sum/$samples.Count}
    } | Sort-Object normalizedMeanCpuPercent -Descending | Select-Object -First 15)
    $gaps=@(for($index=1;$index -lt $samples.Count;$index++){($samples[$index].utcMs-$samples[$index-1].utcMs)/1000})
    $result.maximumSampleGapSeconds=($gaps | Measure-Object -Maximum).Maximum
    if($result.maximumSampleGapSeconds -gt 15){$reasons.Add('Material telemetry gap or host suspension detected.')}
    $result.frequency=@($samples | ForEach-Object {$_.hardware} | Where-Object {$_.Path -like '*processor frequency'} | Measure-Object CookedValue -Minimum -Maximum -Average | Select-Object Minimum,Maximum,Average,Count)
    $result.throttling='Frequency/performance-limit counters saved. Low idle frequency alone does not prove thermal throttling; no temperature-based inference made.'
    $after=Get-Ownership
    Save-Json 'ownership-after.json' $after
    foreach($item in $after){$prior=$ownership | Where-Object {$_.id -eq $item.id};if(-not $prior -or $prior.startedAt -ne $item.startedAt -or $prior.restarts -ne $item.restarts){$reasons.Add('Container lifecycle changed during idle gate.')}}
    if((powercfg /getactivescheme | Out-String).Trim() -ne $plan){$reasons.Add('Power plan changed.')}
    $batteryAfter=Get-CimInstance Win32_Battery | Select-Object BatteryStatus,EstimatedChargeRemaining
    Save-Json 'power-after.json' $batteryAfter
    if($batteryAfter.BatteryStatus -ne 2){$reasons.Add('External power no longer confirmed.')}
    $query="*[System[(EventID=42 or EventID=107 or EventID=506 or EventID=507) and TimeCreated[@SystemTime>='$($start.ToString('o'))']]]"
    $events=(wevtutil qe System "/q:$query" /f:xml | Out-String)
    $events | Set-Content (Join-Path $output 'power-events.xml') -Encoding UTF8
    if($LASTEXITCODE -ne 0){$reasons.Add('Power-event audit unavailable.')}elseif($events.Trim()){$reasons.Add('Sleep/resume event during gate.')}
    $result.startedUtc=$start.ToString('o');$result.finishedUtc=$end.ToString('o')
    $result.status=if($result.contention.failed){'FAIL'}elseif($reasons.Count){'INCONCLUSIVE'}else{'NEEDS_REVIEW'}
} catch {$reasons.Add($_.Exception.Message)} finally {
    $result.reasons=@($reasons)
    $result.loadGeneratorQualification='Not run; idle gate precedes all load.'
    Save-Json 'gate.json' $result
    $manifest=@(Get-ChildItem $output -File | ForEach-Object {@{file=$_.Name;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash}})
    Save-Json 'manifest.json' $manifest
    $manifest | ForEach-Object {"$($_.sha256)  $($_.file)"} | Set-Content (Join-Path $output 'checksums.sha256') -Encoding ASCII
    Get-ChildItem $output -File | ForEach-Object {$_.IsReadOnly=$true}
}
Write-Output $output
Write-Output ($result | ConvertTo-Json -Depth 8)
if($result.status -ne 'NEEDS_REVIEW'){throw 'Idle environment gate did not pass; do not start qualification load.'}