// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

apply(from = rootProject.file("gradle/android-feature.gradle"))

android {
    namespace = "runtime.mobileagent.feature.worldbooks"
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    // Presentation only: no database, network or file API is touched here. The shared
    // domain project is declared the way every other feature module declares it, which
    // also keeps this module's resolved graph on the same coroutines/stdlib versions as
    // its siblings instead of dragging in an older transitive set.
    implementation(project(":shared:domain"))

    implementation(libs.androidx.activity.compose)

    // Pure-JVM tests for the presentation model and the bilingual resource contract.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
