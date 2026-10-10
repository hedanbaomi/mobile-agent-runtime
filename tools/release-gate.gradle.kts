// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import groovy.json.JsonSlurper
import java.io.File
import java.util.function.Function

/*
 * Shared release-gate tasks.  This is deliberately implemented with Gradle's
 * own runtime rather than a downloaded SBOM plugin: the report is generated
 * from the resolved configuration and the actual packaged artifact.  The
 * release path is fail-closed when signing or a clean Git source snapshot is
 * unavailable.
 */

@Suppress("UNCHECKED_CAST")
fun sha256(file: File): String =
    (rootProject.extensions.extraProperties["mobileagentEvidenceSha256"] as Function<File, String>).apply(file)

fun gitOutput(vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git") + arguments.toList())
        .directory(rootProject.projectDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
    check(process.waitFor() == 0) { "git command failed: $output" }
    return output
}

@Suppress("UNCHECKED_CAST")
fun sourceArchiveSha256(): String =
    (rootProject.extensions.extraProperties["mobileagentEvidenceSourceArchiveSha256"] as Function<File, String>).apply(rootProject.projectDir)

val verifyCiPins = tasks.register("verifyCiPins") {
    group = "verification"
    description = "Require full commit-SHA pins and version comments for every GitHub Action."
    doLast {
        val workflows = rootProject.file(".github/workflows").listFiles().orEmpty()
            .filter { it.isFile && it.extension in setOf("yml", "yaml") }
        check(workflows.isNotEmpty()) { "No GitHub Actions workflows found" }
        val usesPattern = Regex("^\\s*(?:-\\s*)?uses:\\s*([^@\\s]+)@([^\\s#]+)\\s*(?:#\\s*(.*))?$")
        val shaPattern = Regex("[0-9a-fA-F]{40}")
        val violations = mutableListOf<String>()
        workflows.forEach { workflow ->
            workflow.readLines().forEachIndexed { index, line ->
                if (!line.contains("uses:")) return@forEachIndexed
                val match = usesPattern.matchEntire(line)
                if (match == null || !shaPattern.matches(match.groupValues[2]) || !match.groupValues[3].trim().startsWith("v")) {
                    violations += "${workflow.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1}"
                }
            }
        }
        check(violations.isEmpty()) { "Every Actions uses line needs a 40-character commit SHA and # v... comment: $violations" }
        logger.lifecycle("GitHub Actions pins verified: ${workflows.size} workflow(s)")
    }
}

private val ignoredBuildTreeDirectories = setOf(
    ".git",
    ".gradle",
    ".codegraph",
    ".private",
    "build",
    "node_modules",
)

fun dependencyLockFilesForIncludedBuild(buildRoot: File): List<File> {
    val projectLockFiles = buildRoot.walkTopDown()
        .onEnter { directory -> directory.name !in ignoredBuildTreeDirectories }
        .filter { it.isFile && it.name in setOf("build.gradle", "build.gradle.kts") }
        .map { it.parentFile.resolve("gradle.lockfile") }
        .toList()
    return (listOf(buildRoot.resolve("settings-gradle.lockfile")) + projectLockFiles)
        .distinctBy { it.absoluteFile.normalize().path }
}

fun gatePath(file: File): String = try {
    file.relativeTo(rootProject.projectDir).invariantSeparatorsPath
} catch (_: IllegalArgumentException) {
    file.absolutePath
}

fun verifyDependencyVerificationMetadata(label: String, metadata: File) {
    check(metadata.isFile) {
        "Missing $label dependency verification metadata: ${gatePath(metadata)}; " +
            "generate it with --write-verification-metadata sha256"
    }
    val text = metadata.readText(Charsets.UTF_8)
    check("<verification-metadata" in text && "<configuration>" in text) {
        "$label dependency verification metadata is not a Gradle metadata document"
    }
    check("<verify-metadata>true</verify-metadata>" in text) {
        "$label dependency verification metadata must keep verify-metadata=true"
    }
    val checksums = Regex("""<sha256\s+value="([0-9a-fA-F]{64})"""").findAll(text).toList()
    check(checksums.isNotEmpty()) {
        "$label dependency verification metadata has no SHA-256 component entries"
    }
    check("<!--" !in text.substringAfter("<verification-metadata", "")) {
        "$label dependency verification metadata must not be a placeholder"
    }
    val wildcardTrust = Regex("""<trusted-(?:key|artifact)\b[^>]*\*[^>]*/?>""")
        .containsMatchIn(text)
    check(!wildcardTrust) {
        "$label dependency verification metadata must not contain wildcard trust"
    }
}

val verifyDependencyLock = tasks.register("verifyDependencyLock") {
    group = "verification"
    description = "Require native Gradle dependency lockfiles for every root and included build project."
    dependsOn(gradle.includedBuilds.map { it.task(":license-guard:verifyDependencyLock") })
    doLast {
        // The root settings lock protects plugin/version-catalog resolution;
        // each root project and included project owns its native gradle.lockfile.
        val required = mutableListOf(rootProject.file("settings-gradle.lockfile"))
        // Settings-only grouping projects (:data, :feature, ... ) have no
        // build file or resolvable configuration, so there is no dependency
        // state to lock for them. Every leaf build project is required.
        required += subprojects.filter { it.path != ":" && it.buildFile.isFile }
            .map { it.file("gradle.lockfile") }
        gradle.includedBuilds.forEach { included ->
            required += dependencyLockFilesForIncludedBuild(included.projectDir)
        }
        val distinctRequired = required.distinctBy { it.absoluteFile.normalize().path }
        val missing = distinctRequired.filterNot(File::isFile)
        check(missing.isEmpty()) { "Missing dependency lockfiles: ${missing.joinToString { gatePath(it) }}" }
        distinctRequired.forEach { lock ->
            val text = lock.readText(Charsets.UTF_8)
            check("This is a Gradle generated file for dependency locking." in text) { "Invalid Gradle lockfile: ${lock.name}" }
        }
        logger.lifecycle("Dependency lockfiles verified: ${distinctRequired.size}")
    }
}

val verifyDependencyVerification = tasks.register("verifyDependencyVerification") {
    group = "verification"
    description = "Require Gradle dependency verification metadata with SHA-256 checksums."
    dependsOn(gradle.includedBuilds.map { it.task(":license-guard:verifyDependencyVerification") })
    doLast {
        val metadata = buildList {
            add("root build" to rootProject.file("gradle/verification-metadata.xml"))
            gradle.includedBuilds.forEach { included ->
                add("included build ${included.name}" to included.projectDir.resolve("gradle/verification-metadata.xml"))
            }
        }
        metadata.forEach { (label, file) -> verifyDependencyVerificationMetadata(label, file) }
        logger.lifecycle(
            "Dependency verification metadata verified: ${metadata.joinToString { (label, file) -> "$label=${file.length()} bytes" }}",
        )
    }
}

val verifyReleaseProvenance = tasks.register("verifyReleaseProvenance") {
    group = "verification"
    description = "Verify the release provenance manifest binds clean Git, artifact, SBOM and source hashes."
    val appProject = project(":app-android")
    val manifest = appProject.layout.buildDirectory.file("reports/provenance/release.provenance.json")
    inputs.file(manifest)
    dependsOn(":app-android:generateReleaseProvenance")
    doLast {
        val file = manifest.get().asFile
        check(file.isFile) { "Missing release provenance manifest: ${file.absolutePath}" }
        val parsed = JsonSlurper().parse(file) as? Map<*, *> ?: error("Provenance is not a JSON object")
        check(parsed["schemaVersion"] == 1) { "Unsupported provenance schema" }
        check(parsed["gitDirty"] == false) { "Release provenance must state gitDirty=false" }
        val git = parsed["git"] as? Map<*, *> ?: error("Provenance git object missing")
        val artifact = parsed["artifact"] as? Map<*, *> ?: error("Provenance artifact object missing")
        val sbom = parsed["sbom"] as? Map<*, *> ?: error("Provenance SBOM object missing")
        check(artifact["type"] == "android-apk" && artifact["abi"] == listOf("arm64-v8a")) {
            "Release provenance must describe an arm64-only APK"
        }
        check(artifact["path"] == "app-android/build/outputs/apk/release/app-android-release.apk") {
            "Release provenance must bind the canonical release APK"
        }
        check(artifact["debuggable"] == false) { "Release APK must be non-debuggable" }
        val security = parsed["security"] as? Map<*, *> ?: error("Provenance security object missing")
        check(security["highPrivilegeControlPlaneEnabled"] == true) { "Release control plane evidence is invalid" }
        check(sbom["format"] == "CycloneDX-1.6") { "Release SBOM format is invalid" }
        val sourceHash = parsed["sourceArchiveSha256"] as? String ?: error("Provenance source hash missing")
        check(Regex("[0-9a-f]{40}").matches(git["sha"] as? String ?: "")) { "Provenance Git SHA is not complete" }
        listOf(sourceHash, artifact["sha256"], sbom["sha256"]).forEach {
            check(Regex("[0-9a-f]{64}").matches(it as? String ?: "")) { "Provenance contains an invalid SHA-256" }
        }
        val artifactFile = rootProject.file(artifact["path"] as? String ?: "")
        val sbomFile = rootProject.file(sbom["path"] as? String ?: "")
        check(artifactFile.isFile && sha256(artifactFile) == artifact["sha256"]) { "APK hash does not match provenance" }
        check(sbomFile.isFile && sha256(sbomFile) == sbom["sha256"]) { "SBOM hash does not match provenance" }
        check(sourceHash == sourceArchiveSha256()) { "Source archive hash does not match provenance" }
        check(git["sha"] == gitOutput("rev-parse", "--verify", "HEAD").lowercase()) { "Provenance Git SHA does not match HEAD" }
        logger.lifecycle("Release provenance verified: ${file.absolutePath}")
    }
}

tasks.named("check") {
    dependsOn(verifyCiPins, verifyDependencyLock, verifyDependencyVerification)
}

tasks.register("debugEvidenceGate") {
    group = "verification"
    description = "Run checks and verify a freshly assembled, hash-bound debug APK evidence set."
    dependsOn("check", ":app-android:debugEvidenceGate")
}

tasks.register("reviewGate") {
    group = "verification"
    description = "Run checks and verify a freshly assembled non-debuggable review APK without signing or publishing."
    dependsOn("check", ":app-android:reviewGate")
}

tasks.register("releaseGate") {
    group = "verification"
    description = "Run the complete local release gate without publishing or deploying anything."
    dependsOn("check")
    dependsOn(verifyCiPins, verifyDependencyLock, verifyDependencyVerification)
    dependsOn(":app-android:verifyReleaseSigning")
    dependsOn(":app-android:assembleRelease", ":app-android:generateReleaseSbom", ":app-android:verifyReleaseArtifact", ":app-android:generateReleaseProvenance")
    dependsOn(verifyReleaseProvenance)
}
