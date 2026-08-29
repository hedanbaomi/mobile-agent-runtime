// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

apply(from = rootProject.file("tools/debug-sbom.gradle.kts"))

fun gitCapture(vararg args: String): String = try {
    val process = ProcessBuilder(listOf("git") + args.toList())
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
    if (process.waitFor() == 0) text else "unknown"
} catch (_: Exception) {
    "unknown"
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val gitHead = gitCapture("rev-parse", "HEAD")
val gitDirty = gitCapture("status", "--porcelain").isNotEmpty()
val gitRevision = when {
    gitHead == "unknown" -> "unknown"
    gitDirty -> "$gitHead-dirty"
    else -> gitHead
}
val dbSchemaVersion = Regex("""const val VERSION = (\d+)""")
    .find(rootProject.file("data/sqlite/src/main/kotlin/runtime/mobileagent/data/Migrations.kt").readText())
    ?.groupValues?.get(1) ?: "0"
val buildTimeUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())

android {
    namespace = "runtime.mobileagent"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "runtime.mobileagent"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "runtime.mobileagent.PythonRuntimeDeviceTestRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/hedanbaomi/mobile-agent-runtime\"")
        buildConfigField("String", "GIT_REVISION", buildConfigString(gitRevision))
        buildConfigField("boolean", "GIT_DIRTY", gitDirty.toString())
        buildConfigField("int", "DB_SCHEMA_VERSION", dbSchemaVersion)
        buildConfigField("String", "BUILD_TIME_UTC", buildConfigString(buildTimeUtc))
        buildConfigField("String", "ANNOUNCEMENTS_BASE_URL", "\"https://announcements.luotianyi.fun\"")
        buildConfigField("String", "ANNOUNCEMENTS_KEY_ID", "\"mar-prod-20260829-1\"")
        buildConfigField("String", "ANNOUNCEMENTS_PUBLIC_KEY_HEX", "\"e89c5b55f45a303f5c721a568493edfb9f268b39967ac597b2e105725a552df8\"")
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
    implementation(libs.androidx.documentfile)
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bcprov)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.work:work-testing:2.10.0")
}
