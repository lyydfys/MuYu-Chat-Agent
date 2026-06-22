param(
    [string]$Repo = "https://github.com/ggml-org/llama.cpp.git",
    [string]$Destination = "third_party/llama.cpp"
)

$ErrorActionPreference = "Stop"

if (Test-Path $Destination) {
    Write-Host "llama.cpp already exists at $Destination"
    exit 0
}

git clone --depth 1 $Repo $Destination
Write-Host "Fetched llama.cpp into $Destination"
