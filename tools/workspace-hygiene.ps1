# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only

# Preview by default. Keep WIP, deliverables and verification records in place.
param([switch]$Apply, [string]$ReportPath, [string[]]$CompletedCheckout, [string]$ApprovedPreview)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
$privateRoot = Join-Path $workspaceRoot '.private'
$completedMode = @($CompletedCheckout | Where-Object { $_ }).Count -gt 0
$scopeRoot = if ($completedMode) { "$workspaceRoot-scratch" } else { $workspaceRoot }
# Only these AGP-owned intermediate kinds are disposable in a completed checkout.
# Leave reports, final APKs, original downloads and unknown directories in place.
$intermediateKinds = @('local_aar_for_lint','library_assets','merged_native_libs','stripped_native_libs',
    'assets','native_symbol_tables','compile_app_classes_jar','runtime_app_classes_jar','compressed_assets',
    'library_and_local_jars_jni','merged_jni_libs','library_jni','dex')
$processes = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^(java|node|python|adb|gradle|bash).*' })
$projects = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$candidates = [Collections.Generic.List[object]]::new()
$skipped = [Collections.Generic.List[string]]::new()
# Default to registered worktrees in old local folders; completed scratch
# checkouts must be named explicitly. Never discover copies in private material.
$protectedNames = @('overnight', 'releases', 'manual-test', 'video', 'video-launch-20261005', 'wechat-promo-20261010', 'announcements-backups', 'info-release-key-20261005')
if ($completedMode) {
    foreach ($path in $CompletedCheckout) {
        if (-not [IO.Path]::IsPathFullyQualified($path)) { throw "Expected an absolute scratch checkout: $path" }
        $project = [IO.Path]::GetFullPath($path).TrimEnd('\')
        if (-not $project.StartsWith($scopeRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Outside project scratch: $project" }
        [void]$projects.Add($project)
    }
} else {
foreach ($line in (& 'D:\Git\cmd\git.exe' -C $workspaceRoot worktree list --porcelain)) {
    if (-not $line.StartsWith('worktree ')) { continue }
    $project = [IO.Path]::GetFullPath($line.Substring(9))
    $relativeProject = [IO.Path]::GetRelativePath($workspaceRoot, $project).Replace('\', '/')
    if (-not $relativeProject.StartsWith('.private/') -and -not $relativeProject.StartsWith('.tmp-')) { continue }
    if ($relativeProject.StartsWith('.private/') -and $relativeProject.Split('/')[1] -in $protectedNames) { continue }
    if (Test-Path -LiteralPath $project) { [void]$projects.Add($project) }
}
}
function Assert-NoReparsePath([string]$path) {
    for ($part = $path; $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refusing reparse point: $part" }
    }
}
function Test-CheckoutInUse($processList, [string]$project) {
    $windowsPath = $project.Replace('\','/')
    $msysPath = '/' + $windowsPath.Substring(0,1).ToLowerInvariant() + $windowsPath.Substring(2)
    foreach ($p in $processList) {
        $argsText = ([string]$p.CommandLine).Replace('\','/')
        if ($argsText -match 'GradleDaemon|GradleWrapperMain|org\.gradle\.launcher\.GradleMain' -or
            $argsText.IndexOf($windowsPath,[StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $argsText.IndexOf($msysPath,[StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    }
    return $false
}
function Assert-GeneratedTarget([string]$target, [string]$project) {
    $absolute = [IO.Path]::GetFullPath($target).TrimEnd('\')
    $allowedProject = [IO.Path]::GetFullPath($project).TrimEnd('\')
    if (-not $absolute.StartsWith($scopeRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not $absolute.StartsWith($allowedProject + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $absolute.StartsWith((Join-Path $privateRoot 'overnight') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing target: $absolute" }
    if ($completedMode) {
        if ([IO.Path]::GetFileName($absolute) -notin $intermediateKinds -or
            [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($absolute)) -ne 'intermediates' -or
            [IO.Path]::GetFileName([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($absolute))) -ne 'build') { throw "Not an Android build intermediate: $absolute" }
    } elseif ([IO.Path]::GetFileName($absolute) -notin @('build', '.gradle', '.cxx', 'node_modules')) { throw "Refusing target: $absolute" }
    if (-not (Test-Path -LiteralPath (Join-Path $allowedProject 'settings.gradle.kts')) -or
        -not (Test-Path -LiteralPath (Join-Path $allowedProject 'gradlew'))) { throw "Refusing non-project: $allowedProject" }
    Assert-NoReparsePath $absolute
    return $absolute
}
function Test-CompletedCheckout([string]$project, [string]$expectedHead) {
    $head = & 'D:\Git\cmd\git.exe' --no-optional-locks -C $project rev-parse --verify HEAD
    if ($LASTEXITCODE -ne 0 -or $head -ne $expectedHead) { return $false }
    $status = & 'D:\Git\cmd\git.exe' --no-optional-locks -C $project status --porcelain --untracked-files=all
    return ($LASTEXITCODE -eq 0 -and -not $status)
}
function Get-CompletedParents([string]$project) {
    # Enumerate only Android intermediates of modules with tracked build scripts.
    $scripts = & 'D:\Git\cmd\git.exe' -C $project ls-files -- '*/build.gradle.kts'
    if ($LASTEXITCODE -ne 0) { throw "Cannot identify modules: $project" }
    foreach ($script in $scripts) {
        $moduleScript = Join-Path $project $script
        if (-not (Test-Path -LiteralPath $moduleScript -PathType Leaf) -or
            -not (Select-String -LiteralPath $moduleScript -Quiet -Pattern 'alias\(libs\.plugins\.android\.(application|library)\)')) { continue }
        $dir = Join-Path ([IO.Path]::GetDirectoryName($moduleScript)) 'build/intermediates'
        if (Test-Path -LiteralPath $dir -PathType Container) { Assert-NoReparsePath $dir; $dir }
    }
}
if ($Apply -and -not $ReportPath) {
    Assert-NoReparsePath $workspaceRoot
    if (-not (Test-Path -LiteralPath $privateRoot)) { [void][IO.Directory]::CreateDirectory($privateRoot) }
    $ReportPath = Join-Path $privateRoot ("hygiene-" + [guid]::NewGuid().ToString('N') + '.json')
}
if ($ReportPath) {
    $ReportPath = [IO.Path]::GetFullPath($ReportPath)
    if (-not $ReportPath.StartsWith($privateRoot + '\',[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetExtension($ReportPath) -ne '.json' -or (Test-Path -LiteralPath $ReportPath)) { throw "Expected a new JSON receipt in .private: $ReportPath" }
    Assert-NoReparsePath ([IO.Path]::GetDirectoryName($ReportPath))
}
$approved = @{}
if ($Apply -and $completedMode) {
    if (-not $ApprovedPreview) { throw 'Completed-checkout apply requires the reviewed -ApprovedPreview JSON.' }
    $previewPath = [IO.Path]::GetFullPath($ApprovedPreview)
    if (-not $previewPath.StartsWith($privateRoot + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Preview must be a private receipt.' }
    Assert-NoReparsePath $previewPath
    $preview = Get-Content -LiteralPath $previewPath -Raw | ConvertFrom-Json
    if ($preview.workspace -ne $workspaceRoot -or $preview.apply -ne $false -or $preview.complete -ne $true) { throw 'Invalid or incomplete preview.' }
    foreach ($item in $preview.generated) { $approved.Add($item.path,$item) }
}
# A Gradle daemon's command line often omits its working checkout. Until it
# stops, do not assume that any old checkout is unused. Normalize MSYS paths
# as well for the processes which do carry a project path.
$complete = $false
try {
foreach ($project in $projects) {
    Assert-NoReparsePath $project
    $gitRoot = & 'D:\Git\cmd\git.exe' -C $project rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($gitRoot).TrimEnd('\') -ne [IO.Path]::GetFullPath($project).TrimEnd('\')) { $skipped.Add($project); continue }
    if (Test-CheckoutInUse $processes $project) { $skipped.Add($project); continue }
    $stack = [Collections.Generic.Stack[string]]::new()
    if ($completedMode) {
        $head = & 'D:\Git\cmd\git.exe' -C $project rev-parse --verify HEAD
        if ($LASTEXITCODE -ne 0 -or -not (Test-CompletedCheckout $project $head)) { $skipped.Add($project); continue }
        foreach ($dir in (Get-CompletedParents $project)) { $stack.Push($dir) }
    } else { $stack.Push($project) }
    while ($stack.Count) {
        $dir = $stack.Pop()
        foreach ($child in (Get-ChildItem -LiteralPath $dir -Directory -Force)) {
            if ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) { $skipped.Add($child.FullName); continue }
            if ($completedMode -and $child.Name -notin $intermediateKinds) { continue }
            if ($completedMode -or $child.Name -in @('build', '.gradle', '.cxx', 'node_modules')) {
                $target = Assert-GeneratedTarget $child.FullName $project
                # Recent output may belong to work still in progress; retain 48h.
                if (-not $completedMode -and $child.LastWriteTimeUtc -gt [DateTime]::UtcNow.AddHours(-48)) { $skipped.Add($target); continue }
                # Inspect descendants without following junctions. Directory
                # timestamps alone do not establish the age of nested outputs.
                $files = [Collections.Generic.List[object]]::new()
                $inspect = [Collections.Generic.Stack[string]]::new(); $inspect.Push($target)
                $unsafe = $false
                $newest = 0L
                while ($inspect.Count -and -not $unsafe) {
                    foreach ($item in (Get-ChildItem -LiteralPath $inspect.Pop() -Force)) {
                        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { $unsafe = $true; break }
                        if (-not $item.PSIsContainer -and $item.Extension -in @('.jks', '.keystore', '.pkcs8', '.p12', '.pfx', '.pem', '.apk', '.aab')) { $unsafe = $true; break }
                        if (-not $completedMode -and $item.LastWriteTimeUtc -gt [DateTime]::UtcNow.AddHours(-48)) { $unsafe = $true; break }
                        if ($item.PSIsContainer) { $inspect.Push($item.FullName) } else { $files.Add($item); $newest = [Math]::Max($newest,$item.LastWriteTimeUtc.Ticks) }
                    }
                }
                $relative = [IO.Path]::GetRelativePath($project, $target).Replace('\', '/')
                $userFiles = & 'D:\Git\cmd\git.exe' -C $project ls-files --cached --others --exclude-standard -- $relative
                if ($LASTEXITCODE -ne 0 -or $unsafe -or $userFiles) { $skipped.Add($target); continue }
                if ($completedMode -and $files.Count -eq 0) { continue }
                $bytes = ($files | Measure-Object -Property Length -Sum).Sum
                $entry = [pscustomobject]@{ path=$target; project=$project; head=if ($completedMode) { $head } else { $null }; bytes=[long]$bytes; files=$files.Count; newest=$newest; deleted=$false }
                if ($Apply -and $completedMode) {
                    $v = $approved[$target]
                    if (-not $v -or $v.project -ne $project -or $v.head -ne $head -or $v.bytes -ne $bytes -or
                        $v.files -ne $files.Count -or $v.newest -ne $entry.newest) { $skipped.Add($target); continue }
                }
                $candidates.Add($entry)
                if ($Apply) {
                    # Check the final absolute target again immediately before mutation.
                    [void](Assert-GeneratedTarget $target $project)
                    if ($completedMode -and -not (Test-CompletedCheckout $project $head)) { throw "Checkout changed: $project" }
                    $liveProcesses = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^(java|node|python|adb|gradle|bash).*' })
                    if (Test-CheckoutInUse $liveProcesses $project) { $skipped.Add($target); continue }
                    Remove-Item -LiteralPath $target -Recurse -Force
                    $entry.deleted = -not (Test-Path -LiteralPath $target)
                }
            } elseif ($child.Name -notin @('.git', '.private', '.codegraph', '.tmp', '.idea', '.venv', 'venv', '__pycache__')) { $stack.Push($child.FullName) }
        }
    }
    Write-Output ("Inspected checkout: " + $project + "; generated targets so far: " + $candidates.Count)
}
$complete = $true
} finally {
$result = [pscustomobject]@{ workspace=$workspaceRoot; apply=[bool]$Apply; complete=$complete; generated=$candidates; retainedRecentOrActive=$skipped; logicalBytes=($candidates | Measure-Object -Property bytes -Sum).Sum }
if ($ReportPath) { $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding utf8 }
}
$result | Select-Object workspace, apply, logicalBytes, @{n='directories';e={$candidates.Count}}, @{n='retained';e={$skipped.Count}} | Format-List
