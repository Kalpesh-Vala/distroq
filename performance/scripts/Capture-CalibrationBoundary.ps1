param([Parameter(Mandatory)][string]$Output, [Parameter(Mandatory)][string]$Project)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($Project -notmatch '^distroq-bench-[0-9]{14}-[a-f0-9]{6}$'){throw 'Explicit benchmark project required.'}
if(Test-Path $Output){throw 'Boundary evidence already exists.'}
$battery=@(Get-CimInstance Win32_Battery | Select-Object BatteryStatus,EstimatedChargeRemaining)
if($battery.Count -and @($battery | Where-Object {$_.BatteryStatus -ne 2}).Count){throw 'AC power not confirmed.'}
$sleep=@{}
foreach($setting in @('STANDBYIDLE','HIBERNATEIDLE')){
    $text=powercfg /query SCHEME_CURRENT SUB_SLEEP $setting | Out-String
    if($LASTEXITCODE -ne 0){throw 'Sleep policy unavailable.'}
    $matches=[regex]::Matches($text,'0x[0-9a-fA-F]{8}')
    if($matches.Count -lt 2 -or $matches[$matches.Count-2].Value -ne '0x00000000'){throw 'Disable AC sleep/hibernate manually before capture.'}
    $sleep[$setting]=$text
}
$containers=@(docker ps -q | ForEach-Object {
    $item=(docker inspect $_ | ConvertFrom-Json)[0]
    $labels=@{}
    foreach($name in @('com.docker.compose.project','com.docker.compose.service')){
      $property=$item.Config.Labels.PSObject.Properties[$name]
      if($property){$labels[$name]=$property.Value}
    }
    [pscustomobject]@{id=$item.Id;name=$item.Name;image=$item.Image;labels=$labels;startedAt=$item.State.StartedAt;
      restarts=$item.RestartCount;running=$item.State.Running;paused=$item.State.Paused;
      volumes=@($item.Mounts | Where-Object {$_.Type -eq 'volume'} | Select-Object Name,Destination)}
})
$value=@{utc=[DateTime]::UtcNow.ToString('o');powerPlan=(powercfg /getactivescheme | Out-String).Trim();battery=$battery;sleep=$sleep;
  cpu=@(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors,MaxClockSpeed,CurrentClockSpeed);
  containers=$containers;note='No container environments, command lines or credentials collected. Boundary-only CIM overhead is outside capture windows.'}
$value | ConvertTo-Json -Depth 9 | Set-Content $Output -Encoding UTF8