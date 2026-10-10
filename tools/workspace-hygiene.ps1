# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only

# Deletes only generated caches in old project copies, never the copies or WIP.
param([switch]$Apply, [string]$ReportPath)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
$privateRoot = Join-Path $workspaceRoot '.private'
$processes = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^(java|node|python|adb|gradle|bash).*' })
$projects = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$candidates = [Collections.Generic.List[object]]::new()
$skipped = [Collections.Generic.List[string]]::new()
# Positive allowlist: only registered Git worktrees inside the old scratch
# folders. Never enumerate unknown private material to discover source copies.
$protectedNames = @('overnight', 'releases', 'manual-test', 'video', 'video-launch-20261005', 'wechat-promo-20261010', 'announcements-backups', 'info-release-key-20261005')
foreach ($line in (& 'D:\Git\cmd\git.exe' -C $workspaceRoot worktree list --porcelain)) {
    if (-not $line.StartsWith('worktree ')) { continue }
    $project = [IO.Path]::GetFullPath($line.Substring(9))
    $relativeProject = [IO.Path]::GetRelativePath($workspaceRoot, $project).Replace('\', '/')
    if (-not $relativeProject.StartsWith('.private/') -and -not $relativeProject.StartsWith('.tmp-')) { continue }
    if ($relativeProject.StartsWith('.private/') -and $relativeProject.Split('/')[1] -in $protectedNames) { continue }
    if (Test-Path -LiteralPath $project) { [void]$projects.Add($project) }
}
function Assert-GeneratedTarget([string]$target, [string]$project) {
    $absolute = [IO.Path]::GetFullPath($target).TrimEnd('\')
    $allowedProject = [IO.Path]::GetFullPath($project).TrimEnd('\')
    if (-not $absolute.StartsWith($workspaceRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not $absolute.StartsWith($allowedProject + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $absolute.StartsWith((Join-Path $privateRoot 'overnight') + '\', [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($absolute) -notin @('build', '.gradle', '.cxx', 'node_modules')) { throw "Refusing target: $absolute" }
    if (-not (Test-Path -LiteralPath (Join-Path $allowedProject 'settings.gradle.kts')) -or
        -not (Test-Path -LiteralPath (Join-Path $allowedProject 'gradlew'))) { throw "Refusing non-project: $allowedProject" }
    $cursor = $absolute
    while ($cursor.Length -gt $workspaceRoot.Length) {
        if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refusing reparse point: $cursor" }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    return $absolute
}
# A Gradle daemon's command line often omits its working checkout. Until it
# stops, do not assume that any old checkout is unused. Normalize MSYS paths
# as well for the processes which do carry a project path.
$unattributedGradle = @($processes | Where-Object { $_.CommandLine -match 'GradleDaemon' }).Count -gt 0
foreach ($project in $projects) {
    $gitRoot = & 'D:\Git\cmd\git.exe' -C $project rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($gitRoot).TrimEnd('\') -ne [IO.Path]::GetFullPath($project).TrimEnd('\')) { $skipped.Add($project); continue }
    $msysProject = '/' + $project.Substring(0, 1).ToLowerInvariant() + $project.Substring(2).Replace('\', '/')
    if ($unattributedGradle -or @($processes | Where-Object { $_.CommandLine -and ($_.CommandLine.IndexOf($project, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine.IndexOf($msysProject, [StringComparison]::OrdinalIgnoreCase) -ge 0) }).Count) { $skipped.Add($project); continue }
    $stack = [Collections.Generic.Stack[string]]::new(); $stack.Push($project)
    while ($stack.Count) {
        $dir = $stack.Pop()
        foreach ($child in (Get-ChildItem -LiteralPath $dir -Directory -Force)) {
            if ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
            if ($child.Name -in @('build', '.gradle', '.cxx', 'node_modules')) {
                $target = Assert-GeneratedTarget $child.FullName $project
                # Recent output may belong to work still in progress; retain 48h.
                if ($child.LastWriteTimeUtc -gt [DateTime]::UtcNow.AddHours(-48)) { $skipped.Add($target); continue }
                # Inspect descendants without following junctions. Directory
                # timestamps alone do not establish the age of nested outputs.
                $files = [Collections.Generic.List[object]]::new()
                $inspect = [Collections.Generic.Stack[string]]::new(); $inspect.Push($target)
                $unsafe = $false
                while ($inspect.Count -and -not $unsafe) {
                    foreach ($item in (Get-ChildItem -LiteralPath $inspect.Pop() -Force)) {
                        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { $unsafe = $true; break }
                        if (-not $item.PSIsContainer -and $item.Extension -in @('.jks', '.keystore', '.pkcs8', '.p12', '.pfx', '.pem', '.apk', '.aab')) { $unsafe = $true; break }
                        if ($item.LastWriteTimeUtc -gt [DateTime]::UtcNow.AddHours(-48)) { $unsafe = $true; break }
                        if ($item.PSIsContainer) { $inspect.Push($item.FullName) } else { $files.Add($item) }
                    }
                }
                $relative = [IO.Path]::GetRelativePath($project, $target).Replace('\', '/')
                $userFiles = & 'D:\Git\cmd\git.exe' -C $project ls-files --cached --others --exclude-standard -- $relative
                if ($LASTEXITCODE -ne 0 -or $unsafe -or $userFiles) { $skipped.Add($target); continue }
                $bytes = ($files | Measure-Object -Property Length -Sum).Sum
                $entry = [pscustomobject]@{ path=$target; project=$project; bytes=[long]$bytes; files=$files.Count; deleted=$false }
                $candidates.Add($entry)
                if ($Apply) {
                    # Check the final absolute target again immediately before mutation.
                    [void](Assert-GeneratedTarget $target $project)
                    $liveProcesses = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^(java|node|python|adb|gradle|bash).*' })
                    if (@($liveProcesses | Where-Object { $_.CommandLine -match 'GradleDaemon' -or ($_.CommandLine -and ($_.CommandLine.IndexOf($project, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine.IndexOf($msysProject, [StringComparison]::OrdinalIgnoreCase) -ge 0)) }).Count) { $skipped.Add($target); continue }
                    Remove-Item -LiteralPath $target -Recurse -Force
                    $entry.deleted = -not (Test-Path -LiteralPath $target)
                }
            } elseif ($child.Name -notin @('.git', '.private', '.codegraph', '.tmp', '.idea', '.venv', 'venv', '__pycache__')) { $stack.Push($child.FullName) }
        }
    }
    Write-Output ("Inspected old project: " + $project + "; generated targets: " + $candidates.Count)
}
$result = [pscustomobject]@{ workspace=$workspaceRoot; apply=[bool]$Apply; generated=$candidates; retainedRecentOrActive=$skipped; logicalBytes=($candidates | Measure-Object -Property bytes -Sum).Sum }
if ($ReportPath) { $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding utf8 }
$result | Select-Object workspace, apply, logicalBytes, @{n='directories';e={$candidates.Count}}, @{n='retained';e={$skipped.Count}} | Format-List
