$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$privateKey = Join-Path $projectRoot 'keys\private.pem'
$publicKey = Join-Path $projectRoot 'keys\public.pem'

if (-not (Test-Path -LiteralPath $privateKey) -or -not (Test-Path -LiteralPath $publicKey)) {
    & (Join-Path $PSScriptRoot 'generate-keys.ps1')
}

function Set-DefaultEnvironment([string]$name, [string]$value) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
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
    if (-not (Test-Path -LiteralPath $path)) { return }

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
            if ($value.Length -ge 2 -and (($value.StartsWith("'") -and $value.EndsWith("'")) -or
                    ($value.StartsWith('"') -and $value.EndsWith('"')))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            if ($key -in @('host', 'port', 'username', 'password')) { $values[$key] = $value }
        }
    }

    if ($values.username -and $values.password) {
        Set-DefaultEnvironment 'MAIL_HOST' $(if ($values.host) { $values.host } else { 'smtp.qq.com' })
        Set-DefaultEnvironment 'MAIL_PORT' $(if ($values.port) { $values.port } else { '587' })
        Set-DefaultEnvironment 'MAIL_USERNAME' $values.username
        Set-DefaultEnvironment 'MAIL_PASSWORD' $values.password
        Write-Host 'Mail configuration loaded from the monolith application.yml.'
    }
}

Import-DotEnv (Join-Path $projectRoot '.env')

Set-DefaultEnvironment 'MYSQL_HOST' 'localhost'
Set-DefaultEnvironment 'MYSQL_PORT' '3307'
Set-DefaultEnvironment 'REDIS_HOST' 'localhost'
Set-DefaultEnvironment 'REDIS_PORT' '6380'
Set-DefaultEnvironment 'RABBITMQ_HOST' 'localhost'
Set-DefaultEnvironment 'RABBITMQ_PORT' '5673'
Set-DefaultEnvironment 'MINIO_ENDPOINT' 'http://localhost:9010'
Set-DefaultEnvironment 'MINIO_PUBLIC_URL' 'http://localhost:9010'
Set-DefaultEnvironment 'NACOS_ADDR' 'localhost:8848'

foreach ($required in @('MYSQL_USERNAME', 'MYSQL_PASSWORD', 'RABBITMQ_USERNAME',
        'RABBITMQ_PASSWORD', 'MINIO_ACCESS_KEY', 'MINIO_SECRET_KEY', 'INTERNAL_TOKEN')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($required, 'Process'))) {
        throw "$required is missing. Configure it in seckill-cloud\.env or the process environment."
    }
}

$env:JWT_PRIVATE_KEY = Get-Content -LiteralPath $privateKey -Raw
$env:JWT_PUBLIC_KEY = Get-Content -LiteralPath $publicKey -Raw

$logs = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
# Sentinel gateway flow-control defaults to writing logs under ~/logs/csp. On Windows it
# can throw ExceptionInInitializerError (DateFileLogHandler NPE) and hang every gateway
# request, so point it at a writable project-local directory.
$sentinelLogs = Join-Path $logs 'sentinel'
New-Item -ItemType Directory -Force -Path $sentinelLogs | Out-Null
$pidDirectory = Join-Path $projectRoot 'run'
New-Item -ItemType Directory -Force -Path $pidDirectory | Out-Null

$services = @(
    'seckill-auth-service',
    'seckill-product-service',
    'seckill-activity-service',
    'seckill-gateway'
)

# Single-instance services.
foreach ($service in $services) {
    $pidFile = Join-Path $pidDirectory "$service.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $oldPid = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue
        if ($oldPid -and (Get-Process -Id $oldPid -ErrorAction SilentlyContinue)) {
            $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId=$oldPid" -ErrorAction SilentlyContinue).CommandLine
            if ($commandLine -and $commandLine.Contains("$service-1.0.0.jar")) {
                Write-Host "$service is already running. PID=$oldPid"
                continue
            }
        }
    }
    $jar = Join-Path $projectRoot "$service\target\$service-1.0.0.jar"
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "Missing $jar. Run mvn -DskipTests package in seckill-cloud first."
    }
    $stdout = Join-Path $logs "$service.out.log"
    $stderr = Join-Path $logs "$service.err.log"
    $process = Start-Process -FilePath 'java' -ArgumentList @("-Dcsp.sentinel.log.dir=$sentinelLogs", '-jar', $jar) `
        -WorkingDirectory $projectRoot -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    $process.Id | Set-Content -LiteralPath $pidFile
    Write-Host "$service started. PID=$($process.Id)"
}

# order-service runs two instances for the L2 chat fanout horizontal-scaling setup.
# Each instance needs a unique HTTP port, chat instance-id and snowflake worker-id.
$orderService = 'seckill-order-service'
$orderJar = Join-Path $projectRoot "$orderService\target\$orderService-1.0.0.jar"
if (-not (Test-Path -LiteralPath $orderJar)) {
    throw "Missing $orderJar. Run mvn -DskipTests package in seckill-cloud first."
}
$orderInstances = @(
    @{ Name = $orderService;       Port = '8104'; ChatInstance = 'node8104'; WorkerId = '0' }
    @{ Name = "$orderService-2";   Port = '8114'; ChatInstance = 'node8114'; WorkerId = '1' }
)
foreach ($inst in $orderInstances) {
    $name = $inst.Name
    $pidFile = Join-Path $pidDirectory "$name.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $oldPid = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue
        if ($oldPid -and (Get-Process -Id $oldPid -ErrorAction SilentlyContinue)) {
            $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId=$oldPid" -ErrorAction SilentlyContinue).CommandLine
            if ($commandLine -and $commandLine.Contains("$orderService-1.0.0.jar") -and
                    $commandLine.Contains("--server.port=$($inst.Port)")) {
                Write-Host "$name is already running on port $($inst.Port). PID=$oldPid"
                continue
            }
        }
    }
    $stdout = Join-Path $logs "$name.out.log"
    $stderr = Join-Path $logs "$name.err.log"
    $jvmArgs = @(
        "-Dcsp.sentinel.log.dir=$sentinelLogs",
        '-jar', $orderJar,
        "--server.port=$($inst.Port)",
        "--app.chat.instance-id=$($inst.ChatInstance)",
        "--app.chat.worker-id=$($inst.WorkerId)"
    )
    $process = Start-Process -FilePath 'java' -ArgumentList $jvmArgs `
        -WorkingDirectory $projectRoot -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    $process.Id | Set-Content -LiteralPath $pidFile
    Write-Host "$name started on port $($inst.Port). PID=$($process.Id)"
}

if ([string]::IsNullOrWhiteSpace($env:MAIL_USERNAME) -or [string]::IsNullOrWhiteSpace($env:MAIL_PASSWORD)) {
    Write-Warning 'MAIL_USERNAME or MAIL_PASSWORD is empty. Sending email codes will fail.'
}
Write-Host 'Gateway: http://localhost:8080'
Write-Host 'order-service instances: http://localhost:8104 and http://localhost:8114'
Write-Host 'Logs: seckill-cloud\logs'

# Chat fanout self-check. The chat.fanout bindings were once lost wholesale when a
# queue argument drifted: RabbitAdmin aborted its declaration pass and every
# cross-instance message was then dropped silently, with nothing in the logs.
# See jmeter/ws/README.md section 7. Runs after instances register so the failure
# surfaces at startup instead of in production.
$fanoutCheck = Join-Path $projectRoot '..\jmeter\ws\check_fanout.py'
if (Test-Path -LiteralPath $fanoutCheck) {
    Start-Sleep -Seconds 5
    & python $fanoutCheck --expected-instances $orderInstances.Count
    if ($LASTEXITCODE -ne 0) {
        Write-Warning 'Chat fanout bindings are broken: users and agents on different instances will not see each other.'
        Write-Warning 'See jmeter\ws\README.md section 7. Re-test with: python jmeter\ws\diag_relay.py'
    }
}
