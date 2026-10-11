// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

apply(from = rootProject.file("tools/debug-sbom.gradle.kts"))

// Infer the ABI exposed to the separate instrumentation APK from bytecode,
// including Runner dependencies. No test reachability rules enter Release.
abstract class InferInstrumentationKeepRules : DefaultTask() {
    @get:Classpath abstract val targetJars: ListProperty<RegularFile>
    @get:Classpath abstract val targetDirectories: ListProperty<Directory>
    @get:Classpath abstract val sourceJars: ListProperty<RegularFile>
    @get:Classpath abstract val sourceDirectories: ListProperty<Directory>
    @get:Classpath abstract val bootClasspath: ConfigurableFileCollection
    @get:Classpath abstract val r8Classpath: ConfigurableFileCollection
    @get:OutputFile abstract val outputRules: RegularFileProperty
    @get:OutputFile abstract val outputDiagnostics: RegularFileProperty
    @get:LocalState abstract val scratchDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    private fun mergeClasses(
        jars: List<RegularFile>,
        directories: List<Directory>,
        output: File,
        excluded: Set<String> = emptySet(),
    ): Set<String> {
        val names = mutableSetOf<String>()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun add(name: String, readBytes: () -> ByteArray) {
                if (!name.endsWith(".class") || name.startsWith("META-INF/") ||
                    name == "module-info.class" || name in excluded || !names.add(name)
                ) return
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(readBytes())
                zip.closeEntry()
            }
            directories.sortedBy { it.asFile.path }.forEach { directory ->
                directory.asFile.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    add(file.relativeTo(directory.asFile).invariantSeparatorsPath) { file.readBytes() }
                }
            }
            jars.sortedBy { it.asFile.path }.forEach { jar ->
                ZipFile(jar.asFile).use { input ->
                    input.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }.forEach { entry ->
                        add(entry.name) { input.getInputStream(entry).use { it.readBytes() } }
                    }
                }
            }
        }
        return names
    }

    @TaskAction
    fun infer() {
        val scratch = scratchDirectory.get().asFile.apply { mkdirs() }
        val target = File(scratch, "target.jar")
        val source = File(scratch, "source.jar")
        val targetNames = mergeClasses(targetJars.get(), targetDirectories.get(), target)
        // The ALL scopes overlap: classes supplied by the app must be targets,
        // while test-only classes and libraries are sources, never duplicates.
        val sourceNames = mergeClasses(sourceJars.get(), sourceDirectories.get(), source, targetNames)
        check(targetNames.isNotEmpty() && sourceNames.isNotEmpty()) { "Missing instrumentation inference bytecode" }
        val rules = outputRules.get().asFile.apply { parentFile.mkdirs(); delete() }
        val diagnostics = outputDiagnostics.get().asFile.apply { parentFile.mkdirs() }
        diagnostics.outputStream().buffered().use { log ->
            execOperations.javaexec {
                classpath(r8Classpath)
                mainClass.set("com.android.tools.r8.tracereferences.TraceReferences")
                args("--keep-rules", "--source", source.absolutePath, "--target", target.absolutePath,
                    "--output", rules.absolutePath,
                    "--map-diagnostics:MissingDefinitionsDiagnostic", "error", "info")
                bootClasspath.files.sortedBy { it.path }.forEach { args("--lib", it.absolutePath) }
                standardOutput = log
                errorOutput = log
            }.assertNormalExitValue()
        }
        check(rules.isFile && rules.readText().contains("-keep")) { "TraceReferences produced no instrumentation keep rules" }
        logger.lifecycle("Inferred instrumentation ABI from ${sourceNames.size} source and ${targetNames.size} target classes: $rules")
        logger.lifecycle("TraceReferences diagnostics: $diagnostics")
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("review")) { variant ->
    val instrumentation = variant.androidTest ?: return@onVariants
    val inference = tasks.register<InferInstrumentationKeepRules>("inferReviewInstrumentationKeepRules") {
        group = "verification"
        description = "Infer Review app ABI required by the separate instrumentation APK."
        bootClasspath.from(androidComponents.sdkComponents.bootClasspath)
        // AGP already supplies this pinned R8 distribution; do not resolve another tool dependency.
        r8Classpath.from(com.android.tools.r8.tracereferences.TraceReferences::class.java.protectionDomain.codeSource.location)
        outputRules.set(layout.buildDirectory.file("intermediates/instrumentation_abi/review/keep-rules.pro"))
        outputDiagnostics.set(layout.buildDirectory.file("intermediates/instrumentation_abi/review/diagnostics.txt"))
        scratchDirectory.set(layout.buildDirectory.dir("intermediates/instrumentation_abi/review/bytecode"))
    }
    variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(inference).toGet(
        ScopedArtifact.CLASSES, InferInstrumentationKeepRules::targetJars, InferInstrumentationKeepRules::targetDirectories,
    )
    instrumentation.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(inference).toGet(
        ScopedArtifact.CLASSES, InferInstrumentationKeepRules::sourceJars, InferInstrumentationKeepRules::sourceDirectories,
    )
    // Only compiled class providers are inputs: never depend on packaging or test minification.
    variant.proguardFiles.add(inference.flatMap { it.outputRules })
}

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
        versionCode = 18
        versionName = "1.1.6"
        testInstrumentationRunner = "runtime.mobileagent.PythonRuntimeDeviceTestRunner"
        // Build-type ABI filters are merged with defaultConfig, not replaced.
        // Keep the default empty so release cannot inherit debug's x86_64.
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
    // Local JVM unit tests cover pure-JVM production code (java.nio workspace
    // backends, path policy, commit primitive).  Android-framework code stays
    // in connected androidTest; these unit tests must never touch android.*
    // APIs (the android.jar stubs throw at runtime).
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
    // Build instrumentation against the same Kotlin module/variant as the installed APK.
    testBuildType = providers.gradleProperty("marTestBuildType").orElse("debug").get().also {
        require(it in setOf("debug", "review")) { "Instrumentation supports debug or local review builds only" }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // App language can change independently of the device language. Ship both resources.
    bundle { language { enableSplit = false } }
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
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFile("proguard-test-rules.pro")
            proguardFile("proguard-review-test-boundaries.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "debug"
            // Reuse only the empty test Activity, not debug resources/network policy.
            sourceSets.getByName("review").java.srcDir("src/debug/kotlin")
            buildConfigField("boolean", "HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED", "true")
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation("androidx.work:work-testing:${libs.versions.workManager.get()}")
}
