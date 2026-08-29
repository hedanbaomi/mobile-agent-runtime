// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import groovy.json.JsonOutput
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

// Resolve the actual debug runtime artifacts, not version-catalog declarations.
tasks.register("generateDebugSbom") {
    group = "verification"
    description = "Write a CycloneDX inventory of the freshly assembled debug APK and resolved runtime artifacts."
    dependsOn("assembleDebug")
    val destination = layout.buildDirectory.file("reports/sbom/debug.cdx.json")
    outputs.file(destination)
    outputs.upToDateWhen { false }
    doLast {
        fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    hash.update(bytes, 0, count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        fun pomLicenses(group: String, module: String, version: String): List<Map<String, Any>> {
            val directory = File(gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/$group/$module/$version")
            val pom = directory.listFiles().orEmpty().asSequence().filter { it.isDirectory }
                .flatMap { it.listFiles().orEmpty().asSequence() }.firstOrNull { it.extension == "pom" } ?: return emptyList()
            val factory = DocumentBuilderFactory.newInstance().apply {
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
        val runtime = configurations.getByName("debugRuntimeClasspath")
        // Android project dependencies expose many secondary artifacts. Resolve
        // external archives separately instead of asking Gradle to choose one
        // arbitrary project artifact (classes, resources, manifest, JNI, ...).
        val artifacts = runtime.incoming.artifactView {
            componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        }.artifacts.artifacts.groupBy { it.id.componentIdentifier }
        val rootId = runtime.incoming.resolutionResult.root.id
        val components = runtime.incoming.resolutionResult.allComponents
            .filter { it.id != rootId }.sortedBy { it.id.displayName }.map { component ->
            val id = checkNotNull(component.moduleVersion) { "Missing resolved module identity: ${component.id}" }
            val values = artifacts[component.id].orEmpty()
            val firstParty = component.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier
            val artifactVersion = if (firstParty) "0.1.0-uncommitted" else id.version
            val purl = if (firstParty) "pkg:generic/mobileAgentRuntime/${id.name}@$artifactVersion" else "pkg:maven/${id.group}/${id.name}@${id.version}"
            buildMap<String, Any> {
                put("type", "library"); put("bom-ref", purl); put("group", id.group); put("name", id.name); put("version", artifactVersion)
                put("purl", purl)
                val licenses = if (firstParty) listOf(mapOf("license" to mapOf("id" to "AGPL-3.0-only"))) else pomLicenses(id.group, id.name, id.version)
                if (licenses.isNotEmpty()) put("licenses", licenses)
                put("properties", values.sortedBy { it.file.name }.map { artifact ->
                    check(artifact.file.isFile) { "Unbuilt runtime artifact: ${artifact.id.displayName}" }
                    mapOf("name" to "mobileagent:artifact:${artifact.file.name}:sha256", "value" to sha256(artifact.file))
                } + mapOf("name" to "mobileagent:license-evidence", "value" to if (firstParty) "repository-license-policy" else if (licenses.isEmpty()) "not-present-in-cached-pom" else "cached-upstream-pom"))
            }
        }
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-android-debug.apk").get().asFile
        check(apk.isFile) { "Debug APK is missing" }
        val report = mapOf(
            "bomFormat" to "CycloneDX", "specVersion" to "1.6", "version" to 1,
            "serialNumber" to "urn:uuid:${java.util.UUID.randomUUID()}",
            "metadata" to mapOf("timestamp" to java.time.Instant.now().toString(), "component" to mapOf(
                "type" to "application", "name" to "mobileAgentRuntime-debug", "version" to "0.1.0-uncommitted",
                "licenses" to listOf(mapOf("license" to mapOf("id" to "AGPL-3.0-only"))),
                "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to sha256(apk))),
            )),
            "components" to components,
            "properties" to listOf(mapOf("name" to "mobileagent:scope", "value" to "Resolved debug Gradle runtime artifacts. Native and model-pack distributions are inventoried separately in THIRD_PARTY_NOTICES.md; this is not a vulnerability or release approval.")),
        )
        destination.get().asFile.apply { parentFile.mkdirs(); writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n") }
        logger.lifecycle("Debug SBOM: ${destination.get().asFile} (${components.size} resolved components)")
    }
}
