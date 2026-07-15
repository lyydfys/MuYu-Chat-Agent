[CmdletBinding()]
param(
    [string]$AdbPath = "D:\model\android-sdk\platform-tools\adb.exe",
    [string]$Serial = "",
    [int]$Port = 11435,
    [string]$ApiKey = "",
    [int]$TimeoutSeconds = 180,
    [string]$OutputPath = "host-summary.json"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "benchmark-validation.psm1") -Force

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    throw "Pass -ApiKey with the local API key shown inside MCA Settings -> Local API."
}

$adbArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $adbArgs += @("-s", $Serial)
}

& $AdbPath @adbArgs shell am start -n com.muyuchat.mca/.MainActivity | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to start MCA MainActivity." }
Start-Sleep -Seconds 2
& $AdbPath @adbArgs forward "tcp:$Port" "tcp:$Port" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to forward local API port $Port." }

$baseUrl = "http://127.0.0.1:$Port"
$headers = @{
    "Authorization" = "Bearer $ApiKey"
    "Content-Type" = "application/json"
}

$health = Invoke-RestMethod -Method Get -Uri "$baseUrl/health" -TimeoutSec 10
$models = Invoke-RestMethod -Method Get -Uri "$baseUrl/v1/models" -Headers $headers -TimeoutSec 10
$benchmark = Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/v1/mca/benchmark" `
    -Headers $headers `
    -Body "{}" `
    -TimeoutSec $TimeoutSeconds

Assert-JsonDataShape -Value $health -Path '$.health'
Assert-JsonDataShape -Value $models -Path '$.models'
Assert-JsonDataShape -Value $benchmark -Path '$.benchmark'

$summary = [pscustomobject][ordered]@{
    schemaVersion = 1
    artifactType = "mca_local_benchmark_host_summary"
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    endpoint = $baseUrl
    deviceSerial = if ([string]::IsNullOrWhiteSpace($Serial)) { $null } else { $Serial }
    timeoutSeconds = $TimeoutSeconds
    health = $health
    models = $models
    benchmark = $benchmark
}
$required = @("generatedAt", "endpoint", "deviceSerial", "timeoutSeconds", "health", "models", "benchmark")
$allowed = @("schemaVersion", "artifactType") + $required
Write-ValidatedBenchmarkJson `
    -Path $OutputPath `
    -Value $summary `
    -ExpectedArtifactType "mca_local_benchmark_host_summary" `
    -RequiredProperties $required `
    -AllowedProperties $allowed `
    -Depth 30

Write-Host "Host summary: $([IO.Path]::GetFullPath($OutputPath))"
