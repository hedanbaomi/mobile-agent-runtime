// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import groovy.json.JsonOutput
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

/*
 * This script is applied by the Android application project. It deliberately
 * does not use a placeholder file or a version-catalog-only inventory: every
 * component is taken from the resolved Gradle configuration and every
 * packaged-artifact hash is calculated from the bytes on disk.
 */

fun sha256(file: File): String {
    check(file.isFile) { "Cannot hash a missing file: ${file.absolutePath}" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun gitOutput(vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git") + arguments.toList())
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
    check(process.waitFor() == 0) {
        "git ${arguments.joinToString(" ")} failed: $output"
    }
    return output
}

fun gitState(requireClean: Boolean): Pair<String, Boolean> {
    val status = gitOutput("status", "--porcelain=v1", "--untracked-files=all")
    if (requireClean) {
        check(status.isBlank()) {
            "Release artifacts require a clean Git worktree; uncommitted or untracked paths are present"
        }
    }
    val head = gitOutput("rev-parse", "--verify", "HEAD")
    check(Regex("[0-9a-fA-F]{40}").matches(head)) { "Git HEAD is not a full commit SHA" }
    return head.lowercase() to status.isNotBlank()
}

fun sourceArchiveSha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val process = ProcessBuilder("git", "archive", "--format=tar", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(false)
        .start()
    process.inputStream.use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    val errors = process.errorStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
    check(process.waitFor() == 0) { "git archive failed: $errors" }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun pomLicenses(group: String, module: String, version: String): List<Map<String, Any>> {
    val directory = File(gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/$group/$module/$version")
    val pom = directory.listFiles().orEmpty().asSequence()
        .filter { it.isDirectory }
        .flatMap { it.listFiles().orEmpty().asSequence() }
        .firstOrNull { it.extension == "pom" }
        ?: return emptyList()
    val factory = DocumentBuilderFactory.newInstance().apply {
        // Cached POMs are data, not trusted XML programs.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val licenses = factory.newDocumentBuilder().parse(pom).getElementsByTagName("license")
    return (0 until licenses.length).mapNotNull { index ->
        val node = licenses.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
        val name = node.getElementsByTagName("name").item(0)?.textContent?.trim().orEmpty()
        val url = node.getElementsByTagName("url").item(0)?.textContent?.trim().orEmpty()
        if (name.isBlank()) return@mapNotNull null
        val license = linkedMapOf<String, String>("name" to name)
        if (url.startsWith("https://") || url.startsWith("http://")) license["url"] = url
        mapOf("license" to license)
    }
}

fun resolvedComponents(configurationName: String): List<Map<String, Any>> {
    val runtime = configurations.getByName(configurationName)
    val artifacts = runtime.incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier }
    }.artifacts.artifacts.groupBy { it.id.componentIdentifier }
    val rootId = runtime.incoming.resolutionResult.root.id

    return runtime.incoming.resolutionResult.allComponents
        .filter { it.id != rootId }
        .sortedBy { it.id.displayName }
        .map { component ->
            val projectId = component.id as? ProjectComponentIdentifier
            val moduleId = component.moduleVersion
            val firstParty = projectId != null
            val group = if (firstParty) "runtime.mobileagent" else checkNotNull(moduleId).group
            val name = if (firstParty) {
                projectId!!.projectPath.removePrefix(":").replace(":", "-")
            } else {
                checkNotNull(moduleId).name
            }
            val version = if (firstParty) "source" else checkNotNull(moduleId).version
            val purl = if (firstParty) {
                "pkg:generic/mobileAgentRuntime/$name@$version"
            } else {
                "pkg:maven/$group/$name@$version"
            }
            val properties = mutableListOf<Map<String, Any>>()
            artifacts[component.id].orEmpty().sortedBy { it.file.name }.forEach { artifact ->
                check(artifact.file.isFile) { "Resolved artifact is missing: ${artifact.id.displayName}" }
                properties += mapOf(
                    "name" to "mobileagent:artifact:${artifact.file.name}:sha256",
                    "value" to sha256(artifact.file),
                )
            }
            val licenses = if (firstParty) {
                listOf(mapOf("license" to mapOf("id" to "AGPL-3.0-only")))
            } else {
                pomLicenses(group, name, version)
            }
            properties += mapOf(
                "name" to "mobileagent:license-evidence",
                "value" to if (firstParty) "repository-license-policy"
                else if (licenses.isEmpty()) "cached-pom-has-no-license" else "cached-upstream-pom",
            )
            val result = linkedMapOf<String, Any>(
                "type" to "library",
                "bom-ref" to purl,
                "group" to group,
                "name" to name,
                "version" to version,
                "purl" to purl,
                "properties" to properties,
            )
            if (licenses.isNotEmpty()) result["licenses"] = licenses
            result
        }
}

fun releaseBundleFile(): File {
    val directory = layout.buildDirectory.dir("outputs/bundle/release").get().asFile
    val bundles = directory.listFiles { file -> file.isFile && file.extension == "aab" }.orEmpty()
    check(bundles.size == 1) {
        "Expected exactly one release AAB in ${directory.absolutePath}, found ${bundles.size}"
    }
    return bundles.single()
}

fun writeSbom(
    variant: String,
    configurationName: String,
    artifact: File,
    destination: File,
    requireClean: Boolean,
) {
    check(artifact.isFile) { "${variant.uppercase()} artifact is missing: ${artifact.absolutePath}" }
    val (head, dirty) = gitState(requireClean)
    val sourceHash = sourceArchiveSha256()
    val artifactHash = sha256(artifact)
    val components = resolvedComponents(configurationName)
    check(components.isNotEmpty()) { "Resolved $configurationName has no components; refusing an empty SBOM" }
    val serial = UUID.nameUUIDFromBytes(
        "mobileAgentRuntime|$variant|$head|$sourceHash|$artifactHash".toByteArray(StandardCharsets.UTF_8),
    )
    val artifactPath = artifact.relativeTo(rootProject.projectDir).invariantSeparatorsPath
    val report = linkedMapOf<String, Any>(
        "bomFormat" to "CycloneDX",
        "specVersion" to "1.6",
        "serialNumber" to "urn:uuid:$serial",
        "version" to 1,
        "metadata" to mapOf(
            "timestamp" to java.time.Instant.now().toString(),
            "component" to mapOf(
                "type" to "application",
                "bom-ref" to "pkg:generic/mobileAgentRuntime/mobileAgentRuntime-$variant@$head",
                "group" to "mobileAgentRuntime",
                "name" to "mobileAgentRuntime-$variant",
                "version" to head,
                "licenses" to listOf(mapOf("license" to mapOf("id" to "AGPL-3.0-only"))),
                "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to artifactHash)),
            ),
        ),
        "components" to components,
        "properties" to listOf(
            mapOf("name" to "mobileagent:git-sha", "value" to head),
            mapOf("name" to "mobileagent:git-dirty", "value" to dirty.toString()),
            mapOf("name" to "mobileagent:source-archive-sha256", "value" to sourceHash),
            mapOf("name" to "mobileagent:artifact-sha256", "value" to artifactHash),
            mapOf("name" to "mobileagent:artifact-path", "value" to artifactPath),
            mapOf("name" to "mobileagent:configuration", "value" to configurationName),
        ),
    )
    destination.parentFile.mkdirs()
    destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n", StandardCharsets.UTF_8)
    check(destination.isFile && destination.length() > 0) { "SBOM was not written" }
    logger.lifecycle("CycloneDX SBOM: ${destination.absolutePath} (${components.size} components)")
}

val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-android-debug.apk")
tasks.register("generateDebugSbom") {
    group = "verification"
    description = "Write a CycloneDX inventory of the assembled debug APK and resolved runtime artifacts."
    dependsOn("assembleDebug")
    val destination = layout.buildDirectory.file("reports/sbom/debug.cdx.json")
    outputs.file(destination)
    outputs.upToDateWhen { false }
    doLast {
        writeSbom("debug", "debugRuntimeClasspath", debugApk.get().asFile, destination.get().asFile, requireClean = false)
    }
}

tasks.register("generateReleaseSbom") {
    group = "verification"
    description = "Write a clean-source CycloneDX SBOM for the signed arm64-v8a release AAB."
    dependsOn("verifyReleaseSigning", "bundleRelease")
    val destination = layout.buildDirectory.file("reports/sbom/release.cdx.json")
    outputs.file(destination)
    outputs.upToDateWhen { false }
    doLast {
        writeSbom("release", "releaseRuntimeClasspath", releaseBundleFile(), destination.get().asFile, requireClean = true)
    }
}

tasks.register("verifyReleaseArtifact") {
    group = "verification"
    description = "Verify the release AAB contains arm64-v8a only and no x86_64 native payload."
    dependsOn("verifyReleaseSigning", "bundleRelease")
    doLast {
        val bundle = releaseBundleFile()
        var arm64Entries = 0
        val x86Entries = mutableListOf<String>()
        ZipFile(bundle).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val name = entry.name
                if (name.matches(Regex("(^|/)lib/arm64-v8a(/|$).*"))) arm64Entries++
                if (name.matches(Regex("(^|/)lib/x86_64(/|$).*"))) x86Entries += name
            }
        }
        check(arm64Entries > 0) { "Release AAB has no arm64-v8a native payload" }
        check(x86Entries.isEmpty()) { "Release AAB contains forbidden x86_64 entries: $x86Entries" }
        logger.lifecycle("Release AAB ABI verified: arm64-v8a only (${bundle.length()} bytes)")
    }
}

tasks.register("generateReleaseProvenance") {
    group = "verification"
    description = "Bind the clean Git SHA, release AAB, SBOM and source archive hash in a provenance manifest."
    dependsOn("verifyReleaseSigning", "bundleRelease", "generateReleaseSbom", "verifyReleaseArtifact")
    val manifest = layout.buildDirectory.file("reports/provenance/release.provenance.json")
    outputs.file(manifest)
    outputs.upToDateWhen { false }
    doLast {
        val (head, dirty) = gitState(requireClean = true)
        check(!dirty) { "Release provenance cannot be generated from a dirty worktree" }
        val bundle = releaseBundleFile()
        val sbom = layout.buildDirectory.file("reports/sbom/release.cdx.json").get().asFile
        check(sbom.isFile) { "Release SBOM is missing: ${sbom.absolutePath}" }
        val sourceHash = sourceArchiveSha256()
        val artifactHash = sha256(bundle)
        val sbomHash = sha256(sbom)
        val report = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "gitDirty" to false,
            "git" to mapOf(
                "sha" to head,
                "sourceUrl" to "https://github.com/hedanbaomi/mobile-agent-runtime",
            ),
            "sourceArchiveSha256" to sourceHash,
            "artifact" to mapOf(
                "type" to "android-app-bundle",
                "path" to bundle.relativeTo(rootProject.projectDir).invariantSeparatorsPath,
                "sha256" to artifactHash,
                "abi" to listOf("arm64-v8a"),
            ),
            "sbom" to mapOf(
                "format" to "CycloneDX-1.6",
                "path" to sbom.relativeTo(rootProject.projectDir).invariantSeparatorsPath,
                "sha256" to sbomHash,
            ),
        )
        val file = manifest.get().asFile
        file.parentFile.mkdirs()
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n", StandardCharsets.UTF_8)
        check(file.isFile && file.length() > 0) { "Provenance manifest was not written" }
        logger.lifecycle("Release provenance: ${file.absolutePath}")
    }
}
