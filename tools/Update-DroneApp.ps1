[CmdletBinding()]
param(
    [string]$Repository,
    [switch]$InstallApk
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $root 'config\update.json'
$apkPath = Join-Path $root 'wireless\RCN1C_Bridge.apk'

if (-not (Test-Path -LiteralPath $configPath)) {
    throw "Configurazione non trovata: $configPath"
}
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($Repository)) { $Repository = [string]$config.repository }
if ([string]::IsNullOrWhiteSpace($Repository)) {
    Write-Host '[INFO] repository GitHub non configurato.'
    Write-Host '       Inserisci owner/repository in config/update.json oppure usa -Repository.'
    exit 2
}
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') {
    throw 'Repository non valido: usare il formato owner/repository'
}

$assetName = [string]$config.asset
$current = [version][string]$config.currentVersion
$api = "https://api.github.com/repos/$Repository/releases/latest"
$headers = @{
    Accept = 'application/vnd.github+json'
    'User-Agent' = 'RCN1C-Flight-Deck-Updater'
}

try {
    $release = Invoke-RestMethod -Uri $api -Headers $headers -Method Get
} catch {
    throw "Impossibile leggere l'ultima release GitHub: $($_.Exception.Message)"
}

$tag = ([string]$release.tag_name).TrimStart('v')
try { $remote = [version]$tag } catch { throw "Tag release non numerico: $($release.tag_name)" }
Write-Host "Versione locale: $current"
Write-Host "Ultima release:  $remote ($($release.tag_name))"
if ($remote -le $current) {
    Write-Host '[OK] nessun aggiornamento disponibile.'
    exit 0
}

$asset = @($release.assets) | Where-Object { $_.name -eq $assetName } | Select-Object -First 1
if (-not $asset) { throw "La release non contiene l'asset $assetName" }
Write-Host "[UPDATE] disponibile: $($asset.browser_download_url)"
if (-not $InstallApk) {
    Write-Host 'Per scaricarla e sostituire l''APK locale aggiungere -InstallApk.'
    exit 10
}

$tmp = [System.IO.Path]::GetTempFileName()
$backup = "$apkPath.bak"
try {
    Invoke-WebRequest -Uri $asset.browser_download_url -Headers $headers -OutFile $tmp
    if ((Get-Item -LiteralPath $tmp).Length -lt 10000) { throw 'APK scaricato troppo piccolo' }
    $digest = [string]$asset.digest
    if ($digest -match '^sha256:([0-9a-fA-F]{64})$') {
        $actual = (Get-FileHash -LiteralPath $tmp -Algorithm SHA256).Hash
        if ($actual -ne $Matches[1]) { throw 'Hash SHA-256 dell''APK non corrispondente' }
        Write-Host '[OK] hash SHA-256 verificato.'
    }
    if (Test-Path -LiteralPath $apkPath) {
        Copy-Item -LiteralPath $apkPath -Destination $backup -Force
        Write-Host "[OK] backup creato: $backup"
    }
    Move-Item -LiteralPath $tmp -Destination $apkPath -Force
    Write-Host "[OK] APK aggiornato: $apkPath"
} finally {
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
}
