$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = Join-Path $projectRoot 'keys'
New-Item -ItemType Directory -Force -Path $target | Out-Null

$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCommand) {
    throw 'Java was not found. Install JDK 17 and add java to PATH.'
}

& java (Join-Path $PSScriptRoot 'GenerateRsaKeys.java') $target
if ($LASTEXITCODE -ne 0) {
    throw 'RSA key generation failed.'
}

Write-Host "RSA keys generated in $target"
Write-Host 'The start script loads both PEM files into JWT environment variables.'
