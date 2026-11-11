# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
param([switch]$Strict)
$ErrorActionPreference = 'Stop'
function Get-NativeProbeOutput([string]$Command, [string[]]$Arguments) {
    $executable = (Get-Command $Command -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $savedPreference = $ErrorActionPreference
    try {
        # A successful native program may print its version to stderr on PS5.
        $ErrorActionPreference = 'Continue'
        $PSNativeCommandUseErrorActionPreference = $false
        $output = (& $executable @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Out-String).Trim()
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($code -ne 0) { throw "Version probe failed: $Command (exit $code)" }
    return $output
}
$requirements = @(
    @{ Name = 'Java'; Command = 'java'; Args = @('-version'); Pattern = 'version "17\.' },
    @{ Name = 'Python'; Command = 'python'; Args = @('--version'); Pattern = 'Python 3\.11\.' },
    @{ Name = 'Node'; Command = 'node'; Args = @('--version'); Pattern = '^v24\.' }
)
$mismatch = $false
foreach ($requirement in $requirements) {
    try {
        $output = Get-NativeProbeOutput $requirement.Command $requirement.Args
        $first = ($output -split "`n")[0]
        $matches = $output -match $requirement.Pattern
        Write-Output "$($requirement.Name): $first; matches CI: $matches"
        if (!$matches) { $mismatch = $true }
    } catch { Write-Output "$($requirement.Name): unavailable"; $mismatch = $true }
}
Write-Output 'CI baseline: JDK 17, Python 3.11, Node 24; use the repository Gradle wrapper and strict verification.'
Write-Output 'This command does not install tools or change global environment variables.'
if ($Strict -and $mismatch) { exit 1 }
