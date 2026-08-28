// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "runtime.mobileagent"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "runtime.mobileagent"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/hedanbaomi/mobile-agent-runtime\"")
        buildConfigField("String", "GIT_REVISION", "\"uncommitted\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:serialization"))
    implementation(project(":shared:provider-api"))
    implementation(project(":shared:agent-runtime"))
    implementation(project(":shared:knowledge-api"))
    implementation(project(":shared:skills-api"))
    implementation(project(":shared:announcements"))
    implementation(project(":data:sqlite"))
    implementation(project(":runtime:embedding-onnx"))
    implementation(project(":runtime:vector-usearch"))
    implementation(project(":runtime:python-android"))
    implementation(project(":platform:android:storage"))
    implementation(project(":platform:android:security"))
    implementation(project(":platform:android:background"))
    implementation(project(":platform:android:ipc"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:agents"))
    implementation(project(":feature:providers"))
    implementation(project(":feature:knowledge"))
    implementation(project(":feature:skills"))
    implementation(project(":feature:announcements"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bcprov)
}
