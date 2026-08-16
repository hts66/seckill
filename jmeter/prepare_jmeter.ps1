param(
    [int]$ItemId = 6,
    [int]$Stock = 888,
    [string]$Gateway = 'http://localhost:8080',
    [int]$TokenTtlSeconds = 7200
)

$ErrorActionPreference = 'Stop'

$scriptRoot = (Resolve-Path $PSScriptRoot).Path
$tokenSource = Join-Path $scriptRoot 'tokens.csv'
$tokenOutput = Join-Path $scriptRoot 'tokens-current.csv'
$generator = Join-Path $scriptRoot 'generate_tokens.py'
$privateKey = Join-Path $scriptRoot '..\seckill-cloud\keys\private.pem'

Write-Host "Checking gateway: $Gateway"
$activeResponse = Invoke-RestMethod -Uri "$Gateway/api/seckill/items" -Method Get
$activeItem = @($activeResponse.data) | Where-Object { [int]$_.id -eq $ItemId } | Select-Object -First 1
if (-not $activeItem) {
    throw "Item $ItemId is not currently active. Active item IDs: $((@($activeResponse.data) | ForEach-Object id) -join ', ')"
}

if (-not (Test-Path -LiteralPath $tokenSource)) { throw "Missing $tokenSource" }
if (-not (Test-Path -LiteralPath $privateKey)) { throw "Missing development private key: $privateKey" }

Write-Host "Generating fresh load-test tokens from $tokenSource"
python $generator --source $tokenSource --output $tokenOutput --private-key $privateKey --ttl $TokenTtlSeconds
$tokenLines = (Get-Content -LiteralPath $tokenOutput | Measure-Object -Line).Lines - 1
if ($tokenLines -lt 10) { throw "Only $tokenLines fresh tokens generated; need at least 10" }

$firstToken = (Get-Content -LiteralPath $tokenOutput | Select-Object -Skip 1 -First 1).Trim()
$headers = @{ Authorization = "Bearer $firstToken" }
$pathResponse = Invoke-RestMethod -Uri "$Gateway/api/seckill/path/$ItemId" -Headers $headers -Method Get
if (-not $pathResponse.data.path) { throw 'Gateway rejected generated token or returned no seckill path' }

Write-Host "Active item: $($activeItem.productName) (itemId=$ItemId)"
Write-Host "Database stock: $($activeItem.stock); requested Redis test stock: $Stock"
Write-Host "Fresh tokens: $tokenLines"
Write-Host "Path endpoint check: PASS"
Write-Host "Preparation complete. JMeter files should use tokens-current.csv and ITEM_ID=$ItemId."
