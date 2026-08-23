$ErrorActionPreference = 'Stop'
$sdk = $env:ANDROID_HOME
$bt = Join-Path $sdk 'build-tools\37.0.0'
$plat = Join-Path $sdk 'platforms\android-34\android.jar'
$root = $PSScriptRoot
$out = Join-Path $root 'build'

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path (Join-Path $out 'gen') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $out 'obj') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $out 'dex') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $out 'apk') | Out-Null

Write-Host '[1/6] aapt2 compile res'
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $root 'res') -o (Join-Path $out 'res.zip')
if ($LASTEXITCODE) { throw 'aapt2 compile' }

Write-Host '[2/6] aapt2 link'
$baseApk = Join-Path $out 'apk\base.apk'
& (Join-Path $bt 'aapt2.exe') link -o $baseApk -I $plat --manifest (Join-Path $root 'AndroidManifest.xml') --java (Join-Path $out 'gen') --min-sdk-version 26 --target-sdk-version 34 --auto-add-overlay (Join-Path $out 'res.zip')
if ($LASTEXITCODE) { throw 'aapt2 link' }

Write-Host '[3/6] javac'
$sources = @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object FullName)
$genJava = Get-ChildItem (Join-Path $out 'gen') -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName
if ($genJava) { $sources += $genJava }
$bootCp = "$plat;$(Join-Path $bt 'core-lambda-stubs.jar')"
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath $bootCp -d (Join-Path $out 'obj') @sources
if ($LASTEXITCODE) { throw 'javac' }

Write-Host '[4/6] d8 dex'
& jar cf (Join-Path $out 'classes.jar') -C (Join-Path $out 'obj') .
if ($LASTEXITCODE) { throw 'jar' }
& (Join-Path $bt 'd8.bat') --release --lib $plat --min-api 26 --output (Join-Path $out 'dex') (Join-Path $out 'classes.jar')
if ($LASTEXITCODE) { throw 'd8' }

Push-Location (Join-Path $out 'dex')
try {
    & (Join-Path $bt 'aapt.exe') add $baseApk classes.dex | Out-Null
    if ($LASTEXITCODE) { throw 'aapt add' }
} finally { Pop-Location }

Write-Host '[5/6] zipalign'
$aligned = Join-Path $out 'apk\aligned.apk'
& (Join-Path $bt 'zipalign.exe') -f 4 $baseApk $aligned
if ($LASTEXITCODE) { throw 'zipalign' }

Write-Host '[6/6] apksigner'
$ks = Join-Path $root 'debug.keystore'
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE) { throw 'keytool' }
}
$final = Join-Path $root '..\RCN1C_Bridge.apk'
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $final $aligned
if ($LASTEXITCODE) { throw 'apksigner sign' }
& (Join-Path $bt 'apksigner.bat') verify $final
if ($LASTEXITCODE) { throw 'apksigner verify' }

Write-Host "OK -> $final"
