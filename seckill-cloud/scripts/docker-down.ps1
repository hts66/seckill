$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

Push-Location $projectRoot
try {
    & docker compose down
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose shutdown failed.' }
} finally {
    Pop-Location
}

Write-Host 'Containers stopped. Database and middleware volumes were preserved.'

