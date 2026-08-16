param(
    [int]$ItemId = 6,
    [int]$Stock = 888,
    [string]$Gateway = 'http://localhost:8080',
    [int[]]$Levels = @(10, 50, 200, 500, 1000),
    [int]$RampupSeconds = 10,
    [int]$Loops = 1,
    [switch]$SkipPrepare
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path $PSScriptRoot).Path
$jmx = Join-Path $root 'seckill-stress.jmx'
$results = Join-Path $root 'staged-results'
$gatewayUri = [uri]$Gateway
$jmeterHost = $gatewayUri.Host
$jmeterPort = if ($gatewayUri.IsDefaultPort) { if ($gatewayUri.Scheme -eq 'https') { 443 } else { 80 } } else { $gatewayUri.Port }

if (-not $SkipPrepare) {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $root 'prepare_jmeter.ps1') `
        -ItemId $ItemId -Stock $Stock -Gateway $Gateway
}

if (-not (Get-Command jmeter -ErrorAction SilentlyContinue)) {
    throw 'jmeter command is not available on PATH.'
}
if (-not (Test-Path -LiteralPath $jmx)) { throw "Missing test plan: $jmx" }

New-Item -ItemType Directory -Path $results -Force | Out-Null
$summary = [System.Collections.Generic.List[object]]::new()

foreach ($threads in $Levels) {
    $name = "t$threads"
    $jtl = Join-Path $results "result_$name.jtl"
    $report = Join-Path $results "report_$name"
    Remove-Item -LiteralPath $jtl -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $report) { Remove-Item -LiteralPath $report -Recurse -Force }

    Write-Host "Running $threads threads (ramp-up ${RampupSeconds}s)..."
    $jmeterArgs = @(
        '-n', '-t', $jmx,
        "-Jthreads=$threads", "-Jrampup=$RampupSeconds", "-Jloops=$Loops",
        "-JITEM_ID=$ItemId", "-JHOST=$jmeterHost", "-JPORT=$jmeterPort",
        '-l', $jtl, '-e', '-o', $report
    )
    & jmeter @jmeterArgs
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $jtl)) {
        throw "JMeter failed for $threads threads."
    }

    $rows = @(Import-Csv -LiteralPath $jtl)
    $execute = @($rows | Where-Object { $_.label -like '2.*' })
    $errors = @($rows | Where-Object { $_.success -ne 'true' })
    $badPath = @($rows | Where-Object { $_.URL -match '/api/seckill/execute/$' -or $_.URL -match 'PATH_ERROR' })
    if ($badPath.Count -gt 0) { throw "Dynamic path extraction failed for $threads threads." }

    $elapsed = @($execute | ForEach-Object { [int]$_.elapsed })
    $avg = if ($elapsed.Count) { [math]::Round(($elapsed | Measure-Object -Average).Average, 0) } else { 0 }
    $sorted = @($elapsed | Sort-Object)
    $p99 = if ($sorted.Count) { $sorted[[math]::Max(0, [math]::Ceiling($sorted.Count * .99) - 1)] } else { 0 }
    $summary.Add([pscustomobject]@{
        Threads = $threads; Total = $rows.Count; Execute = $execute.Count
        Errors = $errors.Count; ErrorRate = if ($rows.Count) { '{0:P1}' -f ($errors.Count / $rows.Count) } else { '0.0%' }
        AvgMs = $avg; P99Ms = $p99; Report = $report
    })
}

$summary | Format-Table -AutoSize
$summary | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $results 'summary.json') -Encoding UTF8
Write-Host "Reports written to $results"
