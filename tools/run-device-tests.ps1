# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][string]$Classes,
    [Parameter(Mandatory)][string]$Evidence,
    [switch]$Install,
    [ValidateSet('debug', 'review')][string]$Variant = 'debug'
)
$ErrorActionPreference = 'Stop'
function Invoke-Adb([string[]]$Arguments) {
    $savedPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5 turns native stderr lines (such as adb daemon startup banners)
        # into ErrorRecord objects; process exit code and instrumentation output decide success.
        $ErrorActionPreference = 'Continue'
        $PSNativeCommandUseErrorActionPreference = $false
        $lines = @(& adb @Arguments 2>&1 | ForEach-Object { $_.ToString().TrimEnd("`r") })
        return @{ Lines = $lines; ExitCode = $LASTEXITCODE }
    } finally {
        $ErrorActionPreference = $savedPreference
    }
}
if ($Classes -notmatch '^runtime\.mobileagent\.[A-Za-z0-9_.#]+(,runtime\.mobileagent\.[A-Za-z0-9_.#]+)*$') { throw 'Explicit project test classes required.' }
New-Item -ItemType Directory -Force $Evidence | Out-Null
if (Test-Path "$Evidence/instrumentation.txt") { throw 'Use a new evidence directory to retain earlier failures.' }
$root = Split-Path -Parent $PSScriptRoot
if ($Install) {
    foreach ($apk in @("app-android/build/outputs/apk/$Variant/app-android-$Variant.apk", "app-android/build/outputs/apk/androidTest/$Variant/app-android-$Variant-androidTest.apk")) {
        $installResult = Invoke-Adb @('-s', $Serial, 'install', '-r', (Join-Path $root $apk))
        $installResult.Lines | Write-Host
        if ($installResult.ExitCode -ne 0) { throw "APK installation failed: $apk" }
    }
}
$instrumentResult = Invoke-Adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $Classes, 'runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner')
$output = $instrumentResult.Lines
$exitCode = $instrumentResult.ExitCode
$text = $output -join "`n"
$text | Set-Content "$Evidence/instrumentation.txt" -Encoding utf8
# Custom metric bundles can also send status 0; only a completed named test counts.
$passedTests = 0
$skippedTests = 0
$hasTest = $false
foreach ($line in $output) {
    if ($line -match '^INSTRUMENTATION_STATUS: test=\S') { $hasTest = $true }
    if ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$') {
        $statusCode = [int]$Matches[1]
        if ($hasTest -and $statusCode -eq 0) { $passedTests++ }
        if ($hasTest -and $statusCode -in @(-3, -4)) { $skippedTests++ }
        $hasTest = $false
    }
}
$success = $exitCode -eq 0 -and $passedTests -gt 0 -and $text -match 'OK \([1-9]\d* tests?\)' -and $text -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -(1|2)\b'
@{ classes = $Classes; variant = $Variant; shellExitCode = $exitCode; passed = $success; passedTests = $passedTests; skippedTests = $skippedTests } | ConvertTo-Json | Set-Content "$Evidence/invocation.json" -Encoding utf8
$output | Select-Object -Last 18
if (!$success) { exit 1 }
