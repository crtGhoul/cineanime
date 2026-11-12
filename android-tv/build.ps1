param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$KeystorePath = "",
    [string]$KeyAlias = "cineanime",
    [string]$KeystorePass = "cineanime123",
    [string]$OutputApk = ""
)

$ErrorActionPreference = "Stop"

if (-not $SdkRoot -or -not (Test-Path $SdkRoot)) {
    $SdkRoot = "C:/Users/velaz/workspace/android-sdk"
}
if (-not (Test-Path $SdkRoot)) {
    throw "Android SDK not found at '$SdkRoot'. Set ANDROID_HOME or pass -SdkRoot."
}

$BuildTools = Join-Path $SdkRoot "build-tools/34.0.0"
if (-not (Test-Path $BuildTools)) {
    $FoundBt = Get-ChildItem (Join-Path $SdkRoot "build-tools") | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $FoundBt) { throw "No build-tools directory found under $SdkRoot" }
    $BuildTools = $FoundBt.FullName
}

$PlatformJar = Join-Path $SdkRoot "platforms/android-34/android.jar"
if (-not (Test-Path $PlatformJar)) {
    $FoundPlat = Get-ChildItem (Join-Path $SdkRoot "platforms") | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $FoundPlat) { throw "No platforms/android-* found under $SdkRoot" }
    $PlatformJar = Join-Path $FoundPlat.FullName "android.jar"
}

$IsPosix = ($PSVersionTable.Platform -eq 'Unix') -or $IsLinux -or $IsMacOS
$Aapt2 = Join-Path $BuildTools $(if ($IsPosix) { "aapt2" } else { "aapt2.exe" })
$D8 = Join-Path $BuildTools $(if ($IsPosix) { "d8" } else { "d8.bat" })
$ZipAlign = Join-Path $BuildTools $(if ($IsPosix) { "zipalign" } else { "zipalign.exe" })
$ApkSigner = Join-Path $BuildTools $(if ($IsPosix) { "apksigner" } else { "apksigner.bat" })

$ProjectDir = $PSScriptRoot
$MainDir = Join-Path $ProjectDir "app/src/main"
$JavaSrc = Join-Path $MainDir "java"
$ResDir = Join-Path $MainDir "res"
$Manifest = Join-Path $MainDir "AndroidManifest.xml"

$BuildDir = Join-Path $ProjectDir "build"
$DistDir = Join-Path $ProjectDir "dist"
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Force -Path $BuildDir, (Join-Path $BuildDir "res-flat"), (Join-Path $BuildDir "gen"), (Join-Path $BuildDir "classes"), (Join-Path $BuildDir "dex"), $DistDir | Out-Null

if (-not $KeystorePath) {
    $KeystorePath = Join-Path $ProjectDir "cineanime-release.keystore"
}
if (-not (Test-Path $KeystorePath)) {
    Write-Host "Generating signing keystore at $KeystorePath..." -ForegroundColor Cyan
    & keytool -genkeypair -v -keystore $KeystorePath -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10000 -storepass $KeystorePass -keypass $KeystorePass -dname "CN=CineAnime TV, OU=Velazquez Lab, O=crtGhoul, L=Local, S=NA, C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }
}

if (-not $OutputApk) {
    $OutputApk = Join-Path $DistDir "CineAnime-v1.0.0-tv.apk"
}

Write-Host "1/6 Compiling Android resources with aapt2..." -ForegroundColor Cyan
& $Aapt2 compile --dir $ResDir -o (Join-Path $BuildDir "res-flat/compiled.flata")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }

$BaseApk = Join-Path $BuildDir "base.apk"
Write-Host "2/6 Linking resources & manifest..." -ForegroundColor Cyan
& $Aapt2 link -o $BaseApk -I $PlatformJar --manifest $Manifest --java (Join-Path $BuildDir "gen") (Join-Path $BuildDir "res-flat/compiled.flata") --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

Write-Host "3/6 Compiling Java sources..." -ForegroundColor Cyan
$JavaFiles = Get-ChildItem -Path $JavaSrc, (Join-Path $BuildDir "gen") -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }
& javac --release 8 -classpath $PlatformJar -d (Join-Path $BuildDir "classes") @JavaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "4/6 Dexing bytecode with d8..." -ForegroundColor Cyan
$ClassFiles = Get-ChildItem -Path (Join-Path $BuildDir "classes") -Filter "*.class" -Recurse | ForEach-Object { $_.FullName }
& $D8 --release --min-api 26 --lib $PlatformJar --output (Join-Path $BuildDir "dex") @ClassFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }

Write-Host "5/6 Packaging & zipaligning APK..." -ForegroundColor Cyan
$UnalignedApk = Join-Path $BuildDir "unaligned.apk"
Copy-Item $BaseApk $UnalignedApk -Force
$DexDir = Join-Path $BuildDir "dex"
& jar uf $UnalignedApk -C $DexDir "classes.dex"
if ($LASTEXITCODE -ne 0) { throw "jar uf failed ($LASTEXITCODE)" }

$AlignedApk = Join-Path $BuildDir "aligned.apk"
& $ZipAlign -f 4 $UnalignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

Write-Host "6/6 Signing APK with apksigner..." -ForegroundColor Cyan
& $ApkSigner sign --ks $KeystorePath --ks-key-alias $KeyAlias --ks-pass "pass:$KeystorePass" --key-pass "pass:$KeystorePass" --out $OutputApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed ($LASTEXITCODE)" }

& $ApkSigner verify --print-certs $OutputApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed ($LASTEXITCODE)" }

Write-Host "SUCCESS! Signed APK ready at: $OutputApk" -ForegroundColor Green
