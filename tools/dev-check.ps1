# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
param(
    [string[]]$Tasks = @('check'),
    [string]$Java17Home = $env:MAR_JAVA17_HOME,
    [switch]$Offline,
    [ValidateRange(1, 8)][int]$MaxWorkers = 2
)
$ErrorActionPreference = 'Stop'
function Get-NativeProbeOutput([string]$Command, [string[]]$Arguments) {
    $executable = (Get-Command $Command -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $savedPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5 turns native stderr into ErrorRecord objects.
        # Version banners on stderr are valid; the process exit code decides success.
        $ErrorActionPreference = 'Continue'
        $PSNativeCommandUseErrorActionPreference = $false
        $output = (& $executable @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Out-String).Trim()
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($code -ne 0) { throw "Version probe failed: $Command (exit $code)" }
    return $output
}
$root = Split-Path -Parent $PSScriptRoot
if (!$Java17Home) {
    $candidates = @($env:JAVA_HOME) + @(Get-ChildItem "$env:USERPROFILE/.gradle/jdks" -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName)
    $Java17Home = $candidates | Where-Object {
        $_ -and (Test-Path "$_/release") -and (Select-String -Path "$_/release" -Pattern '^JAVA_VERSION=.*17\.' -Quiet)
    } | Select-Object -First 1
}
if (!$Java17Home -or !(Test-Path "$Java17Home/bin/java.exe")) { throw 'Set MAR_JAVA17_HOME to an installed JDK 17.' }
$javaVersion = Get-NativeProbeOutput "$Java17Home/bin/java.exe" @('-version')
if ($javaVersion -notmatch 'version .*17\.') { throw 'The selected Java installation must be JDK 17.' }
$pythonPath = Get-NativeProbeOutput 'py' @('-3.11', '-c', 'import sys; print(sys.executable)')
if (!(Test-Path $pythonPath)) { throw 'Install Python 3.11 and register it with the Windows Python launcher.' }
if ((Get-NativeProbeOutput 'node' @('--version')) -notmatch '^v24\.') { throw 'Node 24 is required to match CI.' }
$savedJava = $env:JAVA_HOME
$savedPath = $env:PATH
$status = 1
Push-Location $root
try {
    $env:JAVA_HOME = $Java17Home
    $env:PATH = "$Java17Home/bin;$(Split-Path -Parent $pythonPath);$savedPath"
    $options = @('--dependency-verification=strict', '--console=plain', '--warning-mode=all', "--max-workers=$MaxWorkers", '-Dorg.gradle.java.installations.auto-download=false')
    if ($Offline) { $options += '--offline' }
    & ./gradlew.bat @Tasks @options
    $status = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $savedJava
    $env:PATH = $savedPath
    Pop-Location
}
exit $status
