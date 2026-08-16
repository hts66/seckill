param(
    [switch]$Build,
    [string]$DockerRegistry = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Import-UserEnvironment([string]$name) {
    if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
        return
    }
    $value = [Environment]::GetEnvironmentVariable($name, 'User')
    if (-not [string]::IsNullOrWhiteSpace($value)) {
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Import-DotEnv([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return }
    foreach ($line in Get-Content -LiteralPath $path) {
        if ($line -match '^\s*#' -or $line -match '^\s*$') { continue }
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            $name = $matches[1]
            if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { continue }
            $value = $matches[2].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith("'") -and $value.EndsWith("'")) -or
                    ($value.StartsWith('"') -and $value.EndsWith('"')))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

function Import-LegacyMailConfiguration([string]$path) {
    if (-not (Test-Path -LiteralPath $path) -or
            (-not [string]::IsNullOrWhiteSpace($env:MAIL_USERNAME) -and
             -not [string]::IsNullOrWhiteSpace($env:MAIL_PASSWORD))) {
        return
    }

    $values = @{}
    $mailIndent = -1
    foreach ($line in Get-Content -LiteralPath $path) {
        if ($mailIndent -lt 0) {
            if ($line -match '^(\s*)mail:\s*$') { $mailIndent = $matches[1].Length }
            continue
        }
        if ($line -match '^(\s*)([^#\s][^:]*):\s*(.*)$') {
            $indent = $matches[1].Length
            if ($indent -le $mailIndent) { break }
            $key = $matches[2].Trim()
            $value = $matches[3].Trim()
            if ($value.Length -ge 2 -and (($value[0] -eq [char]39 -and $value[-1] -eq [char]39) -or
                    ($value[0] -eq [char]34 -and $value[-1] -eq [char]34))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            if ($key -in @('host', 'port', 'username', 'password')) { $values[$key] = $value }
        }
    }

    if ($values.host) { $env:MAIL_HOST = $values.host }
    if ($values.port) { $env:MAIL_PORT = $values.port }
    if ($values.username) { $env:MAIL_USERNAME = $values.username }
    if ($values.password) { $env:MAIL_PASSWORD = $values.password }
}

@(
    'DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL', 'DEEPSEEK_MODEL',
    'MAIL_HOST', 'MAIL_PORT', 'MAIL_USERNAME', 'MAIL_PASSWORD',
    'MYSQL_ROOT_PASSWORD', 'MYSQL_USERNAME', 'MYSQL_PASSWORD',
    'RABBITMQ_USERNAME', 'RABBITMQ_PASSWORD',
    'MINIO_ACCESS_KEY', 'MINIO_SECRET_KEY', 'MINIO_PUBLIC_URL',
    'INTERNAL_TOKEN', 'DOCKER_REGISTRY'
) | ForEach-Object { Import-UserEnvironment $_ }
Import-DotEnv (Join-Path $projectRoot '.env')

if ($DockerRegistry) { $env:DOCKER_REGISTRY = $DockerRegistry }
if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    throw 'DEEPSEEK_API_KEY is missing. Configure it in .env or Windows User environment variables.'
}

$privateKey = Join-Path $projectRoot 'keys\private.pem'
$publicKey = Join-Path $projectRoot 'keys\public.pem'
if (-not (Test-Path -LiteralPath $privateKey) -or -not (Test-Path -LiteralPath $publicKey)) {
    & (Join-Path $PSScriptRoot 'generate-keys.ps1')
}

Push-Location $projectRoot
try {
    if ($Build) {
        & docker compose build
        if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed.' }
    }
    & docker compose up -d --no-build --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose startup failed.' }
    & docker compose ps
} finally {
    Pop-Location
}
