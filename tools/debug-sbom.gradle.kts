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

fun variantApkFile(variant: String): File {
    val directory = layout.buildDirectory.dir("outputs/apk/$variant").get().asFile
    val candidates = directory.listFiles { file ->
        file.isFile && file.extension.equals("apk", ignoreCase = true)
    }.orEmpty()
    check(candidates.size == 1) {
        "Expected exactly one " + variant + " APK in " + directory.absolutePath +
            ", found " + candidates.size
    }
    return candidates.single()
}

fun sdkRootCandidates(): List<File> {
    val roots = mutableListOf<File>()
    listOf("ANDROID_SDK_ROOT", "ANDROID_HOME").forEach { variable ->
        System.getenv(variable)?.takeIf { it.isNotBlank() }?.let { roots += File(it) }
    }
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) {
        localProperties.readLines(Charsets.UTF_8)
            .firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("sdk.dir=")
            ?.replace("\\\\", "\\")
            ?.replace("\\:", ":")
            ?.takeIf { it.isNotBlank() }
            ?.let { roots += File(it) }
    }
    return roots.distinctBy { it.absoluteFile.normalize().path }
}

fun apkAnalyzer(): File {
    val names = listOf("apkanalyzer.bat", "apkanalyzer")
    val candidates = buildList {
        sdkRootCandidates().forEach { sdk ->
            names.forEach { name ->
                add(sdk.resolve("cmdline-tools/latest/bin/$name"))
                add(sdk.resolve("tools/bin/$name"))
            }
        }
        System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .filter(String::isNotBlank)
            .forEach { directory -> names.forEach { name -> add(File(directory, name)) } }
    }
    return candidates.firstOrNull { it.isFile } ?: error(
        "apkanalyzer is required for APK security evidence. Checked ANDROID_SDK_ROOT, " +
            "ANDROID_HOME, local.properties sdk.dir and PATH",
    )
}

fun runExternalCommand(executable: File, arguments: List<String>): String {
    val command = if (executable.extension.equals("bat", ignoreCase = true)) {
        listOf(System.getenv("ComSpec") ?: "cmd.exe", "/d", "/c", executable.absolutePath) + arguments
    } else {
        listOf(executable.absolutePath) + arguments
    }
    val process = ProcessBuilder(command)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) {
        executable.name + " " + arguments.joinToString(" ") +
            " failed with exit code " + exitCode + ": " + output
    }
    return output
}

fun verifyApkDebuggable(artifact: File, expected: Boolean) {
    val output = runExternalCommand(
        apkAnalyzer(),
        listOf("manifest", "debuggable", artifact.absolutePath),
    )
    val actual = output.lineSequence()
        .map { it.trim().lowercase() }
        .lastOrNull { it == "true" || it == "false" }
    check(actual != null) {
        "apkanalyzer did not return a boolean debuggable value for " +
            artifact.absolutePath + ": " + output
    }
    check(actual == expected.toString()) {
        "APK debuggable=" + actual + ", expected debuggable=" + expected +
            ": " + artifact.absolutePath
    }
}

fun verifyBuildConfigControlPlane(variant: String, expected: Boolean) {
    val generated = layout.buildDirectory.dir("generated/source/buildConfig/$variant").get().asFile
        .walkTopDown()
        .firstOrNull { it.isFile && it.name == "BuildConfig.java" }
        ?: error("Generated BuildConfig.java for " + variant + " is missing")
    val actual = Regex("""HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED\s*=\s*(true|false)""")
        .find(generated.readText(StandardCharsets.UTF_8))
        ?.groupValues
        ?.get(1)
    check(actual == expected.toString()) {
        "BuildConfig HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED=" + actual +
            ", expected " + expected + ": " + generated.absolutePath
    }
}

fun verifyReviewBuildConfiguration() {
    val buildFile = rootProject.file("app-android/build.gradle.kts")
    val text = buildFile.readText(StandardCharsets.UTF_8)
    val reviewStart = text.indexOf("""create("review")""")
    val releaseStart = text.indexOf("""getByName("release")""", reviewStart)
    check(reviewStart >= 0 && releaseStart > reviewStart) {
        "app-android/build.gradle.kts has no bounded review build type block"
    }
    val reviewBlock = text.substring(reviewStart, releaseStart)
    check("isDebuggable = false" in reviewBlock) {
        "Review build type must remain non-debuggable"
    }
    check("""signingConfig = signingConfigs.getByName("debug")""" in reviewBlock) {
        "Review gate must use the local debug identity only; release signing is forbidden"
    }
}

fun verifyVariantSecurity(
    variant: String,
    artifact: File,
    expectedDebuggable: Boolean,
    expectedControlPlaneEnabled: Boolean,
) {
    verifyApkDebuggable(artifact, expectedDebuggable)
    verifyBuildConfigControlPlane(variant, expectedControlPlaneEnabled)
    if (variant == "review") {
        verifyReviewBuildConfiguration()
        val forbiddenReleaseTasks = gradle.taskGraph.allTasks
            .filter { it.name in setOf("verifyReleaseSigning", "assembleRelease", "bundleRelease", "signReleaseBundle") }
        check(forbiddenReleaseTasks.isEmpty()) {
            "Review gate must not invoke release/signing tasks: " +
                forbiddenReleaseTasks.map { it.path }
        }
    }
}

fun writeSbom(
    variant: String,
    configurationName: String,
    artifact: File,
    destination: File,
    requireClean: Boolean,
    expectedDebuggable: Boolean? = null,
    expectedControlPlaneEnabled: Boolean? = null,
) {
    check(artifact.isFile) {
        variant.uppercase() + " artifact is missing: " + artifact.absolutePath
    }
    if (expectedDebuggable != null) {
        check(expectedControlPlaneEnabled != null) {
            "A security build SBOM must declare its control-plane expectation"
        }
        verifyVariantSecurity(
            variant,
            artifact,
            expectedDebuggable,
            expectedControlPlaneEnabled,
        )
    }
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
        "properties" to buildList {
            add(mapOf("name" to "mobileagent:git-sha", "value" to head))
            add(mapOf("name" to "mobileagent:git-dirty", "value" to dirty.toString()))
            add(mapOf("name" to "mobileagent:source-archive-sha256", "value" to sourceHash))
            add(mapOf("name" to "mobileagent:artifact-sha256", "value" to artifactHash))
            add(mapOf("name" to "mobileagent:artifact-path", "value" to artifactPath))
            add(mapOf("name" to "mobileagent:configuration", "value" to configurationName))
            if (expectedDebuggable != null && expectedControlPlaneEnabled != null) {
                add(mapOf("name" to "mobileagent:artifact-debuggable", "value" to expectedDebuggable.toString()))
                add(
                    mapOf(
                        "name" to "mobileagent:security-build",
                        "value" to if (!expectedDebuggable && expectedControlPlaneEnabled) {
                            "review-like-non-debuggable"
                        } else {
                            "ordinary-debug"
                        },
                    ),
                )
                add(
                    mapOf(
                        "name" to "mobileagent:high-privilege-control-plane-enabled",
                        "value" to expectedControlPlaneEnabled.toString(),
                    ),
                )
            }
        },
    )
    destination.parentFile.mkdirs()
    destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n", StandardCharsets.UTF_8)
    check(destination.isFile && destination.length() > 0) { "SBOM was not written" }
    logger.lifecycle("CycloneDX SBOM: ${destination.absolutePath} (${components.size} components)")
}

fun sbomProperty(report: Map<*, *>, name: String): String {
    val properties = report["properties"] as? List<*>
        ?: error("SBOM properties object is missing")
    return properties.asSequence()
        .mapNotNull { it as? Map<*, *> }
        .firstOrNull { it["name"] == name }
        ?.get("value")
        ?.toString()
        ?: error("SBOM property is missing: " + name)
}

fun verifySbom(
    variant: String,
    configurationName: String,
    artifact: File,
    sbom: File,
    expectedDebuggable: Boolean? = null,
    expectedControlPlaneEnabled: Boolean? = null,
) {
    check(sbom.isFile) { "Missing SBOM: " + sbom.absolutePath }
    val parsed = JsonSlurper().parse(sbom) as? Map<*, *>
        ?: error("SBOM is not a JSON object: " + sbom.absolutePath)
    check(parsed["bomFormat"] == "CycloneDX") { "SBOM bomFormat is not CycloneDX" }
    check(parsed["specVersion"] == "1.6") { "SBOM specVersion is not 1.6" }
    check((parsed["components"] as? List<*>)?.isNotEmpty() == true) {
        "SBOM has no components"
    }
    val (head, dirty) = gitState(requireClean = false)
    val sourceHash = sourceArchiveSha256()
    val artifactHash = sha256(artifact)
    val artifactPath = artifact.relativeTo(rootProject.projectDir).invariantSeparatorsPath
    check(sbomProperty(parsed, "mobileagent:git-sha") == head) {
        "SBOM Git SHA is stale"
    }
    check(sbomProperty(parsed, "mobileagent:git-dirty") == dirty.toString()) {
        "SBOM dirty-state evidence is stale"
    }
    check(sbomProperty(parsed, "mobileagent:source-archive-sha256") == sourceHash) {
        "SBOM source archive hash is stale"
    }
    check(sbomProperty(parsed, "mobileagent:artifact-sha256") == artifactHash) {
        "SBOM artifact hash does not match the current artifact"
    }
    check(sbomProperty(parsed, "mobileagent:artifact-path") == artifactPath) {
        "SBOM artifact path does not match the current artifact"
    }
    check(sbomProperty(parsed, "mobileagent:configuration") == configurationName) {
        "SBOM configuration evidence does not match the requested variant"
    }
    val metadata = parsed["metadata"] as? Map<*, *> ?: error("SBOM metadata is missing")
    val application = metadata["component"] as? Map<*, *>
        ?: error("SBOM application component is missing")
    val hashes = application["hashes"] as? List<*> ?: error("SBOM application hashes are missing")
    check(hashes.asSequence().mapNotNull { it as? Map<*, *> }.any {
        it["alg"] == "SHA-256" && it["content"] == artifactHash
    }) {
        "SBOM application hash does not match the current artifact"
    }
    val expectedSerial = UUID.nameUUIDFromBytes(
        "mobileAgentRuntime|$variant|$head|$sourceHash|$artifactHash".toByteArray(StandardCharsets.UTF_8),
    )
    check(parsed["serialNumber"] == "urn:uuid:$expectedSerial") {
        "SBOM serial is not bound to the current Git/source/artifact evidence"
    }
    if (expectedDebuggable != null) {
        check(expectedControlPlaneEnabled != null) {
            "A security SBOM must declare its control-plane expectation"
        }
        check(sbomProperty(parsed, "mobileagent:artifact-debuggable") == expectedDebuggable.toString()) {
            "SBOM debuggable evidence does not match the requested variant"
        }
        check(
            sbomProperty(parsed, "mobileagent:high-privilege-control-plane-enabled") ==
                expectedControlPlaneEnabled.toString(),
        ) {
            "SBOM control-plane evidence does not match the requested variant"
        }
        verifyVariantSecurity(variant, artifact, expectedDebuggable, expectedControlPlaneEnabled)
    }
}

fun writeVariantProvenance(
    variant: String,
    configurationName: String,
    artifact: File,
    sbom: File,
    destination: File,
    expectedDebuggable: Boolean,
    expectedControlPlaneEnabled: Boolean,
) {
    verifySbom(
        variant,
        configurationName,
        artifact,
        sbom,
        expectedDebuggable,
        expectedControlPlaneEnabled,
    )
    val (head, dirty) = gitState(requireClean = false)
    val sourceHash = sourceArchiveSha256()
    val artifactHash = sha256(artifact)
    val sbomHash = sha256(sbom)
    val artifactPath = artifact.relativeTo(rootProject.projectDir).invariantSeparatorsPath
    val sbomPath = sbom.relativeTo(rootProject.projectDir).invariantSeparatorsPath
    val report = linkedMapOf<String, Any>(
        "schemaVersion" to 1,
        "variant" to variant,
        "gitDirty" to dirty,
        "git" to mapOf(
            "sha" to head,
            "sourceUrl" to "https://github.com/hedanbaomi/mobile-agent-runtime",
        ),
        "sourceArchiveSha256" to sourceHash,
        "artifact" to mapOf(
            "type" to "android-apk",
            "path" to artifactPath,
            "sha256" to artifactHash,
            "debuggable" to expectedDebuggable,
        ),
        "security" to mapOf(
            "reviewLike" to (variant == "review"),
            "highPrivilegeControlPlaneEnabled" to expectedControlPlaneEnabled,
        ),
        "sbom" to mapOf(
            "format" to "CycloneDX-1.6",
            "path" to sbomPath,
            "sha256" to sbomHash,
        ),
    )
    destination.parentFile.mkdirs()
    destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n", StandardCharsets.UTF_8)
    check(destination.isFile && destination.length() > 0) {
        "Provenance manifest was not written"
    }
    logger.lifecycle("Variant provenance: " + destination.absolutePath)
}

fun verifyVariantProvenance(
    variant: String,
    configurationName: String,
    artifact: File,
    sbom: File,
    manifest: File,
    expectedDebuggable: Boolean,
    expectedControlPlaneEnabled: Boolean,
) {
    check(manifest.isFile) { "Missing provenance manifest: " + manifest.absolutePath }
    val parsed = JsonSlurper().parse(manifest) as? Map<*, *>
        ?: error("Provenance is not a JSON object: " + manifest.absolutePath)
    check(parsed["schemaVersion"] == 1) { "Unsupported provenance schema" }
    check(parsed["variant"] == variant) { "Provenance variant does not match the requested gate" }
    val (head, dirty) = gitState(requireClean = false)
    val sourceHash = sourceArchiveSha256()
    val artifactHash = sha256(artifact)
    val sbomHash = sha256(sbom)
    val artifactPath = artifact.relativeTo(rootProject.projectDir).invariantSeparatorsPath
    val sbomPath = sbom.relativeTo(rootProject.projectDir).invariantSeparatorsPath
    check(parsed["gitDirty"] == dirty) { "Provenance dirty-state evidence is stale" }
    val git = parsed["git"] as? Map<*, *> ?: error("Provenance git object is missing")
    check(git["sha"] == head) { "Provenance Git SHA is stale" }
    check(parsed["sourceArchiveSha256"] == sourceHash) {
        "Provenance source archive hash is stale"
    }
    val artifactRecord = parsed["artifact"] as? Map<*, *>
        ?: error("Provenance artifact object is missing")
    check(artifactRecord["path"] == artifactPath) { "Provenance artifact path is stale" }
    check(artifactRecord["sha256"] == artifactHash) { "Provenance artifact hash is stale" }
    check(artifactRecord["debuggable"] == expectedDebuggable) {
        "Provenance debuggable evidence does not match the requested variant"
    }
    val security = parsed["security"] as? Map<*, *>
        ?: error("Provenance security object is missing")
    check(security["reviewLike"] == (variant == "review")) {
        "Provenance review-like marker is stale"
    }
    check(security["highPrivilegeControlPlaneEnabled"] == expectedControlPlaneEnabled) {
        "Provenance control-plane evidence does not match the requested variant"
    }
    val sbomRecord = parsed["sbom"] as? Map<*, *> ?: error("Provenance SBOM object is missing")
    check(sbomRecord["format"] == "CycloneDX-1.6") { "Provenance SBOM format is invalid" }
    check(sbomRecord["path"] == sbomPath) { "Provenance SBOM path is stale" }
    check(sbomRecord["sha256"] == sbomHash) { "Provenance SBOM hash is stale" }
    verifySbom(
        variant,
        configurationName,
        artifact,
        sbom,
        expectedDebuggable,
        expectedControlPlaneEnabled,
    )
    verifyVariantSecurity(variant, artifact, expectedDebuggable, expectedControlPlaneEnabled)
}

val debugSbom = layout.buildDirectory.file("reports/sbom/debug.cdx.json")
val debugProvenance = layout.buildDirectory.file("reports/provenance/debug.provenance.json")

val verifyDebugArtifact = tasks.register("verifyDebugArtifact") {
    group = "verification"
    description = "Verify the debug APK is debuggable and excluded from the elevated control plane."
    dependsOn("assembleDebug")
    doLast {
        verifyVariantSecurity("debug", variantApkFile("debug"), expectedDebuggable = true, expectedControlPlaneEnabled = false)
    }
}

val verifyDebugSecurity = tasks.register("verifyDebugSecurity") {
    group = "verification"
    description = "Verify the assembled debug APK security boundary."
    dependsOn(verifyDebugArtifact)
    doLast {
        verifyVariantSecurity("debug", variantApkFile("debug"), expectedDebuggable = true, expectedControlPlaneEnabled = false)
    }
}

val generateDebugSbom = tasks.register("generateDebugSbom") {
    group = "verification"
    description = "Write a CycloneDX inventory of the assembled debug APK and resolved runtime artifacts."
    dependsOn(verifyDebugSecurity)
    outputs.file(debugSbom)
    outputs.upToDateWhen { false }
    doLast {
        writeSbom(
            "debug",
            "debugRuntimeClasspath",
            variantApkFile("debug"),
            debugSbom.get().asFile,
            requireClean = false,
            expectedDebuggable = true,
            expectedControlPlaneEnabled = false,
        )
    }
}

val verifyDebugSbom = tasks.register("verifyDebugSbom") {
    group = "verification"
    description = "Reject stale or unbound debug APK SBOM evidence."
    dependsOn(generateDebugSbom)
    inputs.file(debugSbom)
    doLast {
        verifySbom(
            "debug",
            "debugRuntimeClasspath",
            variantApkFile("debug"),
            debugSbom.get().asFile,
            expectedDebuggable = true,
            expectedControlPlaneEnabled = false,
        )
    }
}

val generateDebugProvenance = tasks.register("generateDebugProvenance") {
    group = "verification"
    description = "Bind the debug APK, SBOM, Git SHA and source archive in a local evidence manifest."
    dependsOn(verifyDebugSbom)
    outputs.file(debugProvenance)
    outputs.upToDateWhen { false }
    doLast {
        writeVariantProvenance(
            "debug",
            "debugRuntimeClasspath",
            variantApkFile("debug"),
            debugSbom.get().asFile,
            debugProvenance.get().asFile,
            expectedDebuggable = true,
            expectedControlPlaneEnabled = false,
        )
    }
}

val verifyDebugProvenance = tasks.register("verifyDebugProvenance") {
    group = "verification"
    description = "Reject stale or mismatched debug APK provenance evidence."
    dependsOn(generateDebugProvenance)
    inputs.file(debugProvenance)
    doLast {
        verifyVariantProvenance(
            "debug",
            "debugRuntimeClasspath",
            variantApkFile("debug"),
            debugSbom.get().asFile,
            debugProvenance.get().asFile,
            expectedDebuggable = true,
            expectedControlPlaneEnabled = false,
        )
    }
}

tasks.register("debugEvidenceGate") {
    group = "verification"
    description = "Assemble debug and verify a fresh, SHA-bound artifact/SBOM/provenance evidence set."
    dependsOn(verifyDebugProvenance)
}

val reviewSbom = layout.buildDirectory.file("reports/sbom/review.cdx.json")
val reviewProvenance = layout.buildDirectory.file("reports/provenance/review.provenance.json")

val verifyReviewArtifact = tasks.register("verifyReviewArtifact") {
    group = "verification"
    description = "Verify the review APK is non-debuggable before security evidence is generated."
    dependsOn("assembleReview")
    doLast {
        verifyVariantSecurity("review", variantApkFile("review"), expectedDebuggable = false, expectedControlPlaneEnabled = true)
    }
}

val verifyReviewSecurity = tasks.register("verifyReviewSecurity") {
    group = "verification"
    description = "Verify the review-like security artifact and prohibit release/signing task use."
    dependsOn(verifyReviewArtifact)
    doLast {
        verifyVariantSecurity("review", variantApkFile("review"), expectedDebuggable = false, expectedControlPlaneEnabled = true)
    }
}

val generateReviewSbom = tasks.register("generateReviewSbom") {
    group = "verification"
    description = "Write a CycloneDX inventory for the non-debuggable review APK."
    dependsOn(verifyReviewSecurity)
    outputs.file(reviewSbom)
    outputs.upToDateWhen { false }
    doLast {
        writeSbom(
            "review",
            "reviewRuntimeClasspath",
            variantApkFile("review"),
            reviewSbom.get().asFile,
            requireClean = false,
            expectedDebuggable = false,
            expectedControlPlaneEnabled = true,
        )
    }
}

val verifyReviewSbom = tasks.register("verifyReviewSbom") {
    group = "verification"
    description = "Reject stale or unbound review-like APK SBOM evidence."
    dependsOn(generateReviewSbom)
    inputs.file(reviewSbom)
    doLast {
        verifySbom(
            "review",
            "reviewRuntimeClasspath",
            variantApkFile("review"),
            reviewSbom.get().asFile,
            expectedDebuggable = false,
            expectedControlPlaneEnabled = true,
        )
    }
}

val generateReviewProvenance = tasks.register("generateReviewProvenance") {
    group = "verification"
    description = "Bind the review APK, SBOM, Git SHA and source archive in a local security evidence manifest."
    dependsOn(verifyReviewSbom)
    outputs.file(reviewProvenance)
    outputs.upToDateWhen { false }
    doLast {
        writeVariantProvenance(
            "review",
            "reviewRuntimeClasspath",
            variantApkFile("review"),
            reviewSbom.get().asFile,
            reviewProvenance.get().asFile,
            expectedDebuggable = false,
            expectedControlPlaneEnabled = true,
        )
    }
}

val verifyReviewProvenance = tasks.register("verifyReviewProvenance") {
    group = "verification"
    description = "Reject stale or mismatched review-like APK provenance evidence."
    dependsOn(generateReviewProvenance)
    inputs.file(reviewProvenance)
    doLast {
        verifyVariantProvenance(
            "review",
            "reviewRuntimeClasspath",
            variantApkFile("review"),
            reviewSbom.get().asFile,
            reviewProvenance.get().asFile,
            expectedDebuggable = false,
            expectedControlPlaneEnabled = true,
        )
    }
}

tasks.register("reviewGate") {
    group = "verification"
    description = "Assemble and verify a non-debuggable review-like APK with fresh SBOM/provenance, without release signing."
    dependsOn(verifyReviewProvenance)
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
