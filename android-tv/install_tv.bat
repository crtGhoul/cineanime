@echo off
setlocal enabledelayedexpansion

set "APK_PATH=%~dp0dist\CineAnime-v1.0.0-tv.apk"
set "ADB_BIN=adb"
if exist "C:\Users\velaz\Documents\antigravity\happy-hypatia\platform-tools\adb.exe" (
    set "ADB_BIN=C:\Users\velaz\Documents\antigravity\happy-hypatia\platform-tools\adb.exe"
)

if not exist "%APK_PATH%" (
    echo [ERROR] APK not found at %APK_PATH%
    echo Run android-tv\build.ps1 first!
    pause
    exit /b 1
)

echo ==================================================
echo   CineAnime Android TV 1-Click ADB Installer
echo ==================================================
echo.
echo [1] Chromecast with Google TV (192.168.68.105 - Wireless Debugging)
echo [2] Onn Google TV (192.168.68.116:5555 - Network ADB)
echo [3] Custom IP:Port or already-connected device
echo.
set /p choice="Choose device [1/2/3]: "

if "%choice%"=="1" (
    echo.
    echo On Chromecast: Settings ^> System ^> About ^> tap Android TV OS build 7x
    echo Then Settings ^> System ^> Developer options ^> Wireless debugging
    set /p needpair="Need to pair first? (y/n): "
    if /i "!needpair!"=="y" (
        set /p pairport="Enter 5-digit Pairing Port on TV: "
        set /p paircode="Enter 6-digit Pairing Code on TV: "
        "%ADB_BIN%" pair 192.168.68.105:!pairport! !paircode!
    )
    set /p connport="Enter Wireless Debugging Connection Port shown on TV: "
    "%ADB_BIN%" connect 192.168.68.105:!connport!
    set "TARGET=192.168.68.105:!connport!"
) else if "%choice%"=="2" (
    "%ADB_BIN%" connect 192.168.68.116:5555
    set "TARGET=192.168.68.116:5555"
) else (
    "%ADB_BIN%" devices
    set /p custom="Enter IP:Port (or leave blank to install to single connected device): "
    set "TARGET=!custom!"
)

echo.
if "%TARGET%"=="" (
    echo Installing %APK_PATH%...
    "%ADB_BIN%" install -r "%APK_PATH%"
) else (
    echo Installing %APK_PATH% to %TARGET%...
    "%ADB_BIN%" -s %TARGET% install -r "%APK_PATH%"
)

if %errorlevel% equ 0 (
    echo.
    echo [SUCCESS] CineAnime installed cleanly!
    echo Launching CineAnime on TV...
    if "%TARGET%"=="" (
        "%ADB_BIN%" shell monkey -p com.cineanime.tv -c android.intent.category.LEANBACK_LAUNCHER 1
    ) else (
        "%ADB_BIN%" -s %TARGET% shell monkey -p com.cineanime.tv -c android.intent.category.LEANBACK_LAUNCHER 1
    )
) else (
    echo [FAILED] Check TV screen to confirm "Always allow from this computer".
)
pause
