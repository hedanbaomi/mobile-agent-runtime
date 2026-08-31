// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Shared release-gate tasks.  This is deliberately implemented with Gradle's
 * own runtime rather than a downloaded SBOM plugin: the report is generated
 * from the resolved configuration and the actual packaged artifact.  The
 * release path is fail-closed when signing or a clean Git source snapshot is
 * unavailable.
 */

fun digestBytes(algorithm: String, update: (MessageDigest) -> Unit): String {
    val digest = MessageDigest.getInstance(algorithm)
    update(digest)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun sha256(file: File): String = digestBytes("SHA-256") { digest ->
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
}

fun gitOutput(vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git") + arguments.toList())
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
    check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    return output
}

fun cleanGitState(): String {
    val status = gitOutput("status", "--porcelain=v1", "--untracked-files=all")
    check(status.isBlank()) {
        "Release artifacts require a clean Git worktree; uncommitted or untracked paths are present"
    }
    val head = gitOutput("rev-parse", "--verify", "HEAD")
    check(Regex("[0-9a-fA-F]{40}").matches(head)) { "Git HEAD is not a full commit SHA" }
    return head.lowercase()
}

fun sourceArchiveSha256(): String = digestBytes("SHA-256") { digest ->
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
    val errors = process.errorStream.bufferedReader(Charsets.UTF_8).readText().trim()
    check(process.waitFor() == 0) { "git archive failed: $errors" }
}

fun pomLicenses(group: String, module: String, version: String): List<Map<String, Any>> {
    val directory = File(gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/$group/$module/$version")
    val pom = directory.listFiles().orEmpty().asSequence()
        .filter { it.isDirectory }
        .flatMap { it.listFiles().orEmpty().asSequence() }
        .firstOrNull { it.extension == "pom" }
        ?: return emptyList()
    val factory = DocumentBuilderFactory.newInstance().apply {
        // POMs are local Gradle-cache inputs.  Never resolve an external
        // entity while creating a release report.
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
        mapOf("license" to buildMap<String, String> {
            put("name", name)
            if (url.startsWith("https://") || url.startsWith("http://")) put("url", url)
        })
    }
}

fun componentPurl(group: String, name: String, version: String): String =
    "pkg:maven/${group.replace(".", "/")}/$name@$version"

fun resolvedComponents(configurationName: String): List<Map<String, Any>> {
    val configuration = project(":app-android").configurations.getByName(configurationName)
    val artifacts = configuration.incoming.artifactView {
        componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
    }.artifacts.artifacts.groupBy { it.id.componentIdentifier }
    val rootId = configuration.incoming.resolutionResult.root.id
    return configuration.incoming.resolutionResult.allComponents
        .filter { it.id != rootId }
        .sortedBy { it.id.displayName }
        .map { component ->
            val projectId = component.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier
            val moduleId = component.moduleVersion
            val firstParty = projectId != null
            val group = if (firstParty) "runtime.mobileagent" else checkNotNull(moduleId).group
            val name = if (firstParty) projectId!!.projectPath.removePrefix(":").replace(":", "-")
            else checkNotNull(moduleId).name
            val version = if (firstParty) "source" else checkNotNull(moduleId).version
            val purl = if (firstParty) {
                "pkg:generic/mobileAgentRuntime/$name@$version"
            } else {
                componentPurl(group, name, version)
            }
            val files = artifacts[component.id].orEmpty().sortedBy { it.file.name }.map { artifact ->
                check(artifact.file.isFile) { "Resolved artifact is missing: ${artifact.id.displayName}" }
                mapOf(
                    "name" to "mobileagent:artifact:${artifact.file.name}:sha256",
                    "value" to sha256(artifact.file),
                )
            }
            val licenses = if (firstParty) {
                listOf(mapOf("license" to mapOf("id" to "AGPL-3.0-only")))
            } else {
                pomLicenses(group, name, version)
            }
            buildMap<String, Any> {
                put("type", "library")
                put("bom-ref", purl)
                put("group", group)
                put("name", name)
                put("version", version)
                put("purl", purl)
                if (licenses.isNotEmpty()) put("licenses", licenses)
                put("properties", files + mapOf(
                    "name" to "mobileagent:license-evidence",
                    "value" to if (firstParty) "repository-license-policy" else if (licenses.isEmpty()) "cached-pom-has-no-license" else "cached-upstream-pom",
                ))
            }
        }
}

fun writeSbom(variant: String, configurationName: String, apkOrBundle: File, destination: File, requireClean: Boolean) {
    check(apkOrBundle.isFile) { "${variant.uppercase()} artifact is missing: ${apkOrBundle.absolutePath}" }
    val head = if (requireClean) cleanGitState() else gitOutput("rev-parse", "--verify", "HEAD").lowercase()
    val sourceHash = sourceArchiveSha256()
    val artifactHash = sha256(apkOrBundle)
    val components = resolvedComponents(configurationName)
    check(components.isNotEmpty()) { "Resolved $configurationName has no components; refusing an empty SBOM" }
    val serial = UUID.nameUUIDFromBytes("mobileAgentRuntime:$variant:$head:$artifactHash".toByteArray(StandardCharsets.UTF_8))
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
                "name" to "mobileAgentRuntime-$variant",
                "version" to head,
                "group" to "mobileAgentRuntime",
                "licenses" to listOf(mapOf("license" to mapOf("id" to "AGPL-3.0-only"))),
                "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to artifactHash)),
            ),
        ),
        "components" to components,
        "properties" to listOf(
            mapOf("name" to "mobileagent:git-sha", "value" to head),
            mapOf("name" to "mobileagent:source-archive-sha256", "value" to sourceHash),
            mapOf("name" to "mobileagent:artifact-sha256", "value" to artifactHash),
            mapOf("name" to "mobileagent:artifact-path", "value" to apkOrBundle.relativeTo(rootProject.projectDir).invariantSeparatorsPath),
            mapOf("name" to "mobileagent:configuration", "value" to configurationName),
            mapOf("name" to "mobileagent:clean-git", "value" to requireClean.toString()),
        ),
    )
    destination.parentFile.mkdirs()
    destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n", Charsets.UTF_8)
    check(destination.isFile && destination.length() > 0) { "SBOM was not written" }
    logger.lifecycle("CycloneDX SBOM: ${destination.absolutePath} (${components.size} components)")
}

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
        val sourceHash = parsed["sourceArchiveSha256"] as? String ?: error("Provenance source hash missing")
        check(Regex("[0-9a-f]{40}").matches(git["sha"] as? String ?: "")) { "Provenance Git SHA is not complete" }
        listOf(sourceHash, artifact["sha256"], sbom["sha256"]).forEach {
            check(Regex("[0-9a-f]{64}").matches(it as? String ?: "")) { "Provenance contains an invalid SHA-256" }
        }
        val artifactFile = rootProject.file(artifact["path"] as? String ?: "")
        val sbomFile = rootProject.file(sbom["path"] as? String ?: "")
        check(artifactFile.isFile && sha256(artifactFile) == artifact["sha256"]) { "AAB hash does not match provenance" }
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
    dependsOn(":app-android:bundleRelease", ":app-android:generateReleaseSbom", ":app-android:verifyReleaseArtifact", ":app-android:generateReleaseProvenance")
    dependsOn(verifyReleaseProvenance)
}
