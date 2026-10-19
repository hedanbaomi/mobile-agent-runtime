// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "runtime.mobileagent.feature.worldbooks"
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
    }
    buildFeatures {
        compose = true
    }
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    // Pure-JVM tests for the presentation model and the bilingual resource contract.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.engine)
}
