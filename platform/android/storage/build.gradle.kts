// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "runtime.mobileagent.storage"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }}

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:knowledge-api"))
    implementation(project(":data:sqlite"))
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.androidx.sqlite.bundled)

}
