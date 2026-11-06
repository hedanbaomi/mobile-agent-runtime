// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

apply(from = rootProject.file("gradle/android-feature.gradle"))

android {
    namespace = "runtime.mobileagent.feature.chat"
}

dependencies {
    implementation(project(":shared:domain"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.activity.compose)
}
