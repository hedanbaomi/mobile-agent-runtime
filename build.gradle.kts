// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    id("runtime.mobileagent.license-guard")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
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
    dependsOn("licenseGuard", "licenseGuardReverse")
    gradle.includedBuilds.forEach { included ->
        dependsOn(included.task(":license-guard:test"))
    }
    subprojects.forEach { sub ->
        dependsOn(sub.tasks.matching { it.name == "check" })
    }
}

apply(from = rootProject.file("tools/release-gate.gradle.kts"))
