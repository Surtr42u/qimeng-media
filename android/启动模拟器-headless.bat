@echo off
REM ============================================================
REM qimeng-media headless emulator launcher (dev aid, M4 batches)
REM
REM Comments are ASCII-only on purpose: Windows cmd may run under
REM a legacy codepage (GBK/cp936) and would mangle UTF-8 Chinese.
REM
REM What it does:
REM   1. Print "adb devices" as a HINT so you can check whether an
REM      emulator (e.g. emulator-5554) is already running. This is
REM      informational only - it does NOT stop or block the start.
REM   2. Start the headless emulator "qimeng_api35":
REM      -no-window              no GUI (CI / background dev)
REM      -no-audio               always muted (user decision 2026-09-06)
REM      -gpu swiftshader_indirect   software GPU (no host GPU needed)
REM      -no-snapshot            cold boot, do not load/save snapshots
REM
REM SDK path is fixed to the default user-local install location.
REM ============================================================

setlocal
set "ANDROID_SDK=%LOCALAPPDATA%\Android\Sdk"
set "EMULATOR_EXE=%ANDROID_SDK%\emulator\emulator.exe"
set "ADB_EXE=%ANDROID_SDK%\platform-tools\adb.exe"

echo [1/2] adb devices (hint only: check emulator-5554 is NOT already running)
if exist "%ADB_EXE%" (
    "%ADB_EXE%" devices
) else (
    echo adb not found at %ADB_EXE% - skipping device check
)

echo.
echo [2/2] Starting headless emulator: qimeng_api35
if not exist "%EMULATOR_EXE%" (
    echo ERROR: emulator.exe not found at %EMULATOR_EXE%
    pause
    exit /b 1
)

"%EMULATOR_EXE%" -avd qimeng_api35 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot

endlocal
