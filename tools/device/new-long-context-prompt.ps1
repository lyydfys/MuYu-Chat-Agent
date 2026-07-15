param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [ValidateRange(4096, 500000)]
    [int]$TargetCharacters = 64000,
    [string]$Secret = '7391'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Secret -notmatch '^\d{4,12}$') {
    throw 'Secret must contain 4..12 decimal digits.'
}

$builder = [Text.StringBuilder]::new()
[void]$builder.Append("At the very beginning, remember this private passcode: $Secret.`n")
$index = 0
while ($builder.Length -lt ($TargetCharacters - 256)) {
    $index++
    [void]$builder.Append(
        "Filler record $index describes a quiet library shelf, a wooden chair, a green notebook, and a small desk lamp. " +
        "This record contains no passcode and does not change the instruction at the beginning.`n"
    )
}
[void]$builder.Append(
    "Now answer with only the private passcode stated at the very beginning. Do not add words or punctuation."
)

$destination = [IO.Path]::GetFullPath($OutputPath)
$parent = Split-Path -Parent $destination
if (-not [string]::IsNullOrWhiteSpace($parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
[IO.File]::WriteAllText($destination, $builder.ToString(), [Text.UTF8Encoding]::new($false))

[pscustomobject]@{
    path = $destination
    characters = $builder.Length
    bytes = (Get-Item -LiteralPath $destination).Length
    secret = $Secret
    fillerRecords = $index
}
