@echo off
rem SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
rem SPDX-License-Identifier: AGPL-3.0-only
setlocal
title mobileAgentRuntime Resident ADB Activation
pushd "%~dp0"

echo mobileAgentRuntime Windows resident ADB activation
echo Requirements: Windows 10/11, Java 17 or newer, official Google Android platform-tools, and a USB data cable.
echo This launcher does not install or download Java, platform-tools, or other dependencies.

if not exist "%~dp0bin\mar-bridge.bat" (
    echo ERROR: The mar-bridge distribution is incomplete. Re-extract the full distribution ZIP.
    set "MAR_BRIDGE_EXIT=2"
    goto finish
)

if defined JAVA_HOME (
    if not exist "%JAVA_HOME%\bin\java.exe" (
        echo ERROR: JAVA_HOME does not contain bin\java.exe. Install Java 17 or newer or correct JAVA_HOME.
        set "MAR_BRIDGE_EXIT=2"
        goto finish
    )
    "%JAVA_HOME%\bin\java.exe" -version
) else (
    where java >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Java was not found. Install Java 17 or newer and set JAVA_HOME or PATH.
        set "MAR_BRIDGE_EXIT=2"
        goto finish
    )
    java -version
)

echo.
call "%~dp0bin\mar-bridge.bat" activate
set "MAR_BRIDGE_EXIT=%ERRORLEVEL%"
if not "%MAR_BRIDGE_EXIT%"=="0" echo Activation stopped with exit code %MAR_BRIDGE_EXIT%. Review the guidance above and try again.

:finish
echo.
echo After Android reports READY, you may unplug USB and close this window. The service runs on the phone.
pause
popd
exit /b %MAR_BRIDGE_EXIT%
