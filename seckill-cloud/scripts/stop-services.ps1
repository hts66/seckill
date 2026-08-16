$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pidDirectory = Join-Path $projectRoot 'run'
if (-not (Test-Path -LiteralPath $pidDirectory)) {
    Write-Host 'No service PID files were found.'
    exit 0
}

Get-ChildItem -LiteralPath $pidDirectory -Filter '*.pid' | ForEach-Object {
    $service = $_.BaseName
    $processId = Get-Content -LiteralPath $_.FullName -ErrorAction SilentlyContinue
    $process = if ($processId) { Get-Process -Id $processId -ErrorAction SilentlyContinue } else { $null }
    if ($process) {
        Stop-Process -Id $processId
        Write-Host "$service stopped. PID=$processId"
    }
    Remove-Item -LiteralPath $_.FullName -Force
}
