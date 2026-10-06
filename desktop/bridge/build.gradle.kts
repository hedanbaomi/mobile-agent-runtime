// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

val runtimeJarLicenseDir = layout.buildDirectory.dir("generated/runtime-jar-licenses")
val collectRuntimeJarLicenses by tasks.registering {
    inputs.files(configurations.runtimeClasspath)
    outputs.dir(runtimeJarLicenseDir)
    doLast {
        val destination = runtimeJarLicenseDir.get().asFile
        val buildRoot = layout.buildDirectory.get().asFile.canonicalFile.toPath()
        val destinationPath = destination.canonicalFile.toPath()
        require(destinationPath.startsWith(buildRoot) && !Files.isSymbolicLink(destination.toPath())) {
            "runtime legal-text extraction directory must stay under this module's build directory"
        }
        destination.deleteRecursively()
        val legalName = Regex("(?i)(LICENSE|LICENCE|NOTICE|COPYING|DEPENDENCIES)([._-].*)?")
        val runtimeJars = configurations.runtimeClasspath.get()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .sortedBy { it.name }
        require(runtimeJars.any { it.name.startsWith("jna-5.19.1") }) {
            "The desktop distribution must resolve the pinned JNA 5.19.1 runtime JAR"
        }
        var copiedJnaLicense = false
        runtimeJars.forEach { jar ->
            ZipFile(jar).use { archive ->
                val entries = archive.entries().asSequence()
                    .filter { entry ->
                        !entry.isDirectory && entry.name.startsWith("META-INF/") &&
                            entry.name.substringAfterLast('/').matches(legalName)
                    }
                    .toList()
                entries.forEach { entry ->
                    val relative = entry.name.removePrefix("META-INF/")
                    val jarDirectory = destination.resolve(jar.nameWithoutExtension).toPath().normalize()
                    val targetPath = jarDirectory.resolve(relative).normalize()
                    require(targetPath.startsWith(jarDirectory)) {
                        "runtime JAR legal-text path escapes its output directory"
                    }
                    val target = targetPath.toFile()
                    target.parentFile.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (jar.name.startsWith("jna-5.19.1") && relative.substringAfterLast('/').startsWith("LICENSE", ignoreCase = true)) {
                        copiedJnaLicense = true
                    }
                }
            }
        }
        require(copiedJnaLicense) { "The desktop distribution must include JNA's bundled LICENSE text" }
    }
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("runtime.mobileagent.desktop.bridge.MainKt")
    applicationName = "mar-bridge"
}

distributions {
    main {
        distributionBaseName = "mar-bridge"
        contents {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("docs/THIRD_PARTY_NOTICES.md"))
            from(rootProject.file("LICENSES/Apache-2.0.txt")) {
                into("licenses")
            }
            from(collectRuntimeJarLicenses) {
                into("licenses/runtime-jars")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    implementation(project(":shared:bridge-protocol"))
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
}
