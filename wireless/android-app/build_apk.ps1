$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$outApk = Join-Path $root '..\RCN1C_Bridge.apk'

$gradle = Get-Command gradle -ErrorAction SilentlyContinue
if (-not $gradle) {
    throw @'
Gradle non trovato nel PATH.
Il branch Android Gamepad usa ora il build Gradle per Shizuku + uinput nativo.
Installa Gradle 8.7+ oppure apri wireless/android-app in Android Studio.
'@
}

Push-Location $root
try {
    Write-Host '[1/3] Gradle clean'
    & $gradle.Source --no-daemon clean
    if ($LASTEXITCODE) { throw 'gradle clean' }

    Write-Host '[2/3] Assemble release APK'
    & $gradle.Source --no-daemon assembleRelease
    if ($LASTEXITCODE) { throw 'gradle assembleRelease' }

    $apk = Get-ChildItem (Join-Path $root 'build\outputs\apk\release') -Filter *.apk |
        Sort-Object Length -Descending |
        Select-Object -First 1
    if (-not $apk) { throw 'APK Gradle non trovata' }

    Write-Host '[3/3] Copy unified Flight Bridge APK'
    Copy-Item -Force $apk.FullName $outApk
    Write-Host "OK -> $outApk"
} finally {
    Pop-Location
}
