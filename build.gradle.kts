// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.jetbrains.kotlin.gradle.tasks.CompileUsingKotlinDaemon

plugins {
    id("runtime.mobileagent.license-guard")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// A narrow APK is for local iteration. Evidence and release invocations always
// use their own standard ABI sets, even if a developer left a property enabled.
val supportedAndroidAbis = listOf("arm64-v8a", "x86_64")
val developerAbi = providers.gradleProperty("marDevAbi").orNull
if (developerAbi != null) {
    require(developerAbi in supportedAndroidAbis) { "marDevAbi must be arm64-v8a or x86_64" }
    require(gradle.startParameter.taskNames == listOf(":app-android:assembleDebug") &&
        providers.environmentVariable("CI").orNull.isNullOrBlank()) {
        "marDevAbi is only allowed for a local :app-android:assembleDebug invocation"
    }
}
extra["debugNativeAbis"] = developerAbi?.let(::listOf) ?: supportedAndroidAbis
extra["pinnedArtifactCacheRoot"] = providers.gradleProperty("pinnedArtifactCache").orNull
    ?.takeIf { it.isNotBlank() }?.let { file(it) } ?: gradle.gradleUserHomeDir.resolve("mobileagent-pinned")

abstract class AppKotlinCompilerSlot : BuildService<BuildServiceParameters.None>

// Keep the Kotlin compiler separate from the 4 GiB Gradle/R8 JVM on workstations and CI.
if (!providers.gradleProperty("kotlin.daemon.jvmargs").isPresent &&
    !providers.systemProperty("kotlin.daemon.jvm.options").isPresent &&
    !providers.gradleProperty("kotlin.compiler.execution.strategy").isPresent
) {
    // Debug, release and review share the daemon. Compiling these large variants together exhausts its heap.
    val appCompilerSlot = gradle.sharedServices.registerIfAbsent("appKotlinCompilerSlot", AppKotlinCompilerSlot::class.java) {
        maxParallelUsages.set(1)
    }
    gradle.projectsEvaluated {
        subprojects {
            tasks.withType<CompileUsingKotlinDaemon>().configureEach {
                if (kotlinDaemonJvmArguments.getOrElse(emptyList()).isEmpty()) {
                    kotlinDaemonJvmArguments.convention(listOf("-Xmx2g"))
                    if (project.path == ":app-android") usesService(appCompilerSlot)
                }
            }
        }
    }
}

// Every resolvable configuration participates in dependency locking.  The
// checked-in lockfiles are generated with `--write-locks` and are verified by
// the release gate before any artifact can be considered reproducible.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("check") {
    group = "verification"
    description = "Run licenseGuard and all subproject checks."
    dependsOn("licenseGuard", "licenseGuardReverse", "verifyWorkflowYaml", "verifyWorkspace", "verifyTestSelection", "verifyModuleBoundaries", "verifyComplexity", "verifyLintWarnings", "verifyI18n")
    gradle.includedBuilds.forEach { included ->
        dependsOn(included.task(":license-guard:test"))
    }
    subprojects.forEach { sub ->
        dependsOn(sub.tasks.matching { it.name == "check" })
    }
}

tasks.register<Exec>("verifyTestSelection") {
    group = "verification"
    description = "Require real instrumentation classes and complete explicit suite or manual-only mappings."
    commandLine("python", "-B", rootProject.file("tools/verify_test_selection.py").absolutePath)
}

tasks.register<Exec>("verifyI18n") {
    group = "verification"
    description = "Reject new feature string literals and mismatched bilingual resources without modifying the baseline."
    commandLine("python", "-B", rootProject.file("tools/verify_i18n.py").absolutePath)
}

tasks.register<Exec>("verifyModuleBoundaries") {
    group = "verification"
    description = "Keep shared/data JVM layers independent of Android and feature layers."
    commandLine("python", "-B", rootProject.file("tools/verify_module_boundaries.py").absolutePath)
}

tasks.register<Exec>("verifyComplexity") {
    group = "verification"
    description = "Ratchet existing Kotlin conditional-token budgets without introducing analyzer dependencies."
    commandLine("python", "-B", rootProject.file("tools/verify_complexity.py").absolutePath)
}

tasks.register<Exec>("verifyLintWarnings") {
    group = "verification"
    description = "Reject new app lint warnings; Error/Fatal findings are never baseline exemptions."
    dependsOn(":app-android:lintDebug")
    commandLine("python", "-B", rootProject.file("tools/verify_lint_warnings.py").absolutePath)
}

/**
 * Minimal GitHub Actions syntax gate (b07 follow-up finding A): an invalid
 * workflow file fails the whole CI run with zero jobs.  Requires PyYAML
 * (`python -m pip install "pyyaml==6.0.2"`); a missing dependency fails,
 * never skips.
 */
tasks.register<Exec>("verifyWorkflowYaml") {
    group = "verification"
    description = "Fail when a GitHub Actions workflow is not parseable YAML with the required job shape."
    commandLine("python", "-B", rootProject.file("tools/verify-workflows.py").absolutePath)
}

tasks.register<Exec>("verifyWorkspace") {
    group = "verification"
    description = "Reject release rollback and stale current-document links."
    commandLine("python", "-B", rootProject.file("tools/verify_workspace.py").absolutePath)
    if (System.getenv("CI") == "true") args("--publication")
}

apply(from = rootProject.file("tools/release-gate.gradle.kts"))
