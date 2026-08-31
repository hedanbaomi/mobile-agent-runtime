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

// Release signing is intentionally opt-in. A release task must never silently
// fall back to the debug keystore or create a new signing identity.
val releaseKeystorePath = providers.gradleProperty("android.release.keystore")
    .orElse(providers.environmentVariable("ANDROID_RELEASE_KEYSTORE"))
val releaseStorePassword = providers.gradleProperty("android.release.storePassword")
    .orElse(providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("android.release.keyAlias")
    .orElse(providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("android.release.keyPassword")
    .orElse(providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD"))

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fail closed unless all explicitly configured release signing inputs are present."
    doLast {
        val values = linkedMapOf(
            "android.release.keystore or ANDROID_RELEASE_KEYSTORE" to releaseKeystorePath.orNull,
            "android.release.storePassword or ANDROID_RELEASE_STORE_PASSWORD" to releaseStorePassword.orNull,
            "android.release.keyAlias or ANDROID_RELEASE_KEY_ALIAS" to releaseKeyAlias.orNull,
            "android.release.keyPassword or ANDROID_RELEASE_KEY_PASSWORD" to releaseKeyPassword.orNull,
        )
        val missing = values.filterValues { it.isNullOrBlank() }.keys
        check(missing.isEmpty()) {
            "Release signing is fail-closed. Missing explicit input(s): ${missing.joinToString()}. " +
                "No debug key or generated identity is permitted."
        }
        check(file(releaseKeystorePath.get()).isFile) {
            "Configured release keystore does not exist; refusing to sign"
        }
    }
}

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
        aidl = true
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
    signingConfigs {
        create("release") {
            // Keep the AGP model configured when values are supplied, while the
            // verification task above remains the authoritative fail-closed
            // guard for every release-producing task.
            releaseKeystorePath.orNull?.takeIf { it.isNotBlank() }?.let { storeFile = file(it) }
            storePassword = releaseStorePassword.orNull
            keyAlias = releaseKeyAlias.orNull
            keyPassword = releaseKeyPassword.orNull
        }
    }
    buildTypes {
        getByName("debug") {
            // A debuggable APK is intentionally excluded from the persistent
            // elevated-control plane: `run-as` can access its app-private
            // state.  Debug remains available for ordinary UI/runtime tests.
            buildConfigField("boolean", "HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED", "false")
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        create("review") {
            // Internal security-acceptance artifact.  It deliberately uses the
            // local debug signing identity while remaining non-debuggable, so
            // it cannot be mistaken for or publish a formally signed release.
            initWith(getByName("debug"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "debug"
            buildConfigField("boolean", "HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED", "true")
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        getByName("release") {
            buildConfigField("boolean", "HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED", "true")
            signingConfig = signingConfigs.getByName("release")
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
    }
}

tasks.configureEach {
    // `preReleaseBuild` is also used by read-only release lint/check tasks. Keep those usable
    // without private signing material; every artifact-producing release task below, and the
    // root releaseGate, still fails closed through verifyReleaseSigning.
    if (name in setOf("validateSigningRelease", "packageRelease", "signReleaseBundle", "assembleRelease", "bundleRelease")) {
        dependsOn(verifyReleaseSigning)
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
    implementation(project(":shared:bridge-protocol"))
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
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation("androidx.work:work-testing:2.10.0")
}
