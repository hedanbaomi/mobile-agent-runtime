// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class SkillInspection(
    val classification: CompatibilityClass,
    val reasons: List<String>,
    val manifest: SkillManifest?,
    val skillMarkdown: String?,
    val packageHash: String,
    val files: List<String>,
    val installable: Boolean,
    val rawManifestJson: String? = null,
    val packageBytes: ByteArray? = null,
)

object SkillArchive {
    const val MAX_COMPRESSED_BYTES = 50L * 1024 * 1024
    const val MAX_ENTRIES = 5000
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    const val MAX_RATIO = 100

    fun inspect(bytes: ByteArray, expectedHash: String? = null): SkillInspection {
        val hash = sha256Hex(bytes)
        val reasons = mutableListOf<String>()
        if (expectedHash != null && expectedHash.lowercase() != hash) {
            reasons += "Package hash does not match the expected digest"
            return SkillInspection(CompatibilityClass.E, reasons, null, null, hash, emptyList(), installable = false)
        }
        if (bytes.size > MAX_COMPRESSED_BYTES) {
            reasons += "Compressed package exceeds 50 MiB"
            return SkillInspection(CompatibilityClass.E, reasons, null, null, hash, emptyList(), false)
        }
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            val archive = readArchive(bytes, reasons)
            if (isClassEReason(reasons)) {
                return SkillInspection(
                    CompatibilityClass.E,
                    reasons.distinct(),
                    null,
                    archive.markdown,
                    hash,
                    archive.files,
                    installable = false,
                    rawManifestJson = archive.manifestJson,
                    packageBytes = bytes,
                )
            }
            return classify(archive, hash, reasons, bytes)
        }
        val markdown = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (markdown != null && markdown.contains("# ") && !markdown.contains('\u0000')) {
            return SkillInspection(
                CompatibilityClass.A,
                listOf("No mobile-skill.json; instruction-only SKILL.md"),
                null,
                markdown,
                hash,
                listOf("SKILL.md"),
                installable = true,
                packageBytes = bytes,
            )
        }
        reasons += "Not a skill archive or SKILL.md"
        return SkillInspection(CompatibilityClass.E, reasons, null, null, hash, emptyList(), false)
    }

    private data class ArchiveContent(
        val files: List<String>,
        val manifestJson: String?,
        val markdown: String?,
        val native: Boolean,
        val pip: Boolean,
        val runtimeKind: String?,
        val schemaVersion: Int?,
    )

    private fun readArchive(bytes: ByteArray, reasons: MutableList<String>): ArchiveContent {
        val files = mutableListOf<String>()
        var manifest: String? = null
        var markdown: String? = null
        var native = false
        var pip = false
        var total = 0L
        var count = 0
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                if (count > MAX_ENTRIES) {
                    reasons += "Archive exceeds 5000 files"
                    break
                }
                val name = entry.name.replace('\\', '/')
                if (name.contains("..") || name.startsWith("/") || name.contains(":")) {
                    reasons += "Zip Slip path is not allowed: $name"
                    continue
                }
                if (entry.isDirectory) continue
                val payload = readBounded(zip, MAX_ENTRY_BYTES.toInt())
                if (payload == null) {
                    reasons += "Entry exceeds size limit: $name"
                    continue
                }
                if (entry.compressedSize > 0 && payload.size.toLong() / entry.compressedSize.coerceAtLeast(1) > MAX_RATIO) {
                    reasons += "Compression bomb ratio exceeds 100"
                }
                total += payload.size
                if (total > MAX_TOTAL_BYTES) {
                    reasons += "Uncompressed archive exceeds 200 MiB"
                    break
                }
                val zipSize = bytes.size.toLong().coerceAtLeast(1)
                if (payload.size > 256 && payload.size.toLong() / zipSize > MAX_RATIO) {
                    reasons += "Compression bomb ratio exceeds 100"
                }
                if (total > 256 && total / zipSize > MAX_RATIO) {
                    reasons += "Compression bomb ratio exceeds 100"
                }
                files += name
                val lower = name.lowercase()
                if (looksNative(lower, payload)) native = true
                if (lower.endsWith("mobile-skill.json")) manifest = String(payload, Charsets.UTF_8)
                if (lower.endsWith("skill.md")) markdown = String(payload, Charsets.UTF_8)
                if (looksRemoteDependency(lower, payload)) pip = true
            }
        } finally {
            zip.close()
        }
        if (native) reasons += "Native payload (ELF/DEX/JAR/SO/wheel) is not allowed"
        if (pip) reasons += "pip / remote dependency install is not allowed"
        val parsed = manifest?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val schema = parsed?.get("schemaVersion")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val runtime = parsed?.get("runtime")?.jsonObject?.get("kind")?.jsonPrimitive?.contentOrNull
            ?: parsed?.get("runtime")?.jsonObject?.get("python")?.let { "python" }
        return ArchiveContent(files, manifest, markdown, native, pip, runtime, schema)
    }

    private fun readBounded(zip: ZipInputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun classify(
        archive: ArchiveContent,
        hash: String,
        reasons: MutableList<String>,
        packageBytes: ByteArray,
    ): SkillInspection {
        if (archive.native || archive.pip) {
            return SkillInspection(
                CompatibilityClass.E,
                reasons.distinct(),
                null,
                archive.markdown,
                hash,
                archive.files,
                false,
                archive.manifestJson,
                packageBytes,
            )
        }
        val json = archive.manifestJson
        if (json == null) {
            reasons += "No mobile-skill.json; instruction-only"
            return SkillInspection(
                CompatibilityClass.A,
                reasons.distinct(),
                null,
                archive.markdown,
                hash,
                archive.files,
                true,
                null,
                packageBytes,
            )
        }
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
        if (obj == null) {
            reasons += "mobile-skill.json is not valid JSON"
            return SkillInspection(
                CompatibilityClass.E,
                reasons.distinct(),
                null,
                archive.markdown,
                hash,
                archive.files,
                false,
                json,
                packageBytes,
            )
        }
        val schema = obj["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (schema != 1) {
            reasons += "Unknown mobile-skill schema: ${schema ?: "missing"}"
            return SkillInspection(
                CompatibilityClass.E,
                reasons.distinct(),
                null,
                archive.markdown,
                hash,
                archive.files,
                false,
                json,
                packageBytes,
            )
        }
        val runtime = obj["runtime"]?.jsonObject ?: JsonObject(emptyMap())
        val kind = runtime["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val entry = runtime["entrypoint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (entry.contains("..") || entry.startsWith("/") || entry.contains("::")) {
            reasons += "Entrypoint is not a package module"
            return SkillInspection(
                CompatibilityClass.E,
                reasons.distinct(),
                null,
                archive.markdown,
                hash,
                archive.files,
                false,
                json,
                packageBytes,
            )
        }
        val permObj = obj["permissions"]?.jsonObject
        val specs = permObj?.map { (cap, value) ->
            val nested = value as? JsonObject
            PermissionSpec(
                capability = cap,
                knowledgeBaseIds = nested.stringSet("knowledgeBaseIds"),
                hosts = nested.stringSet("hosts"),
                methods = nested.stringSet("methods"),
            )
        }.orEmpty()
        val manifest = SkillManifest(
            schemaVersion = 1,
            id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            version = obj["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            license = obj["license"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            runtimeKind = kind.ifBlank { "instruction" },
            entrypoint = entry,
            permissions = specs.map { it.capability }.toSet(),
            permissionSpecs = specs,
        )
        val classification = when (kind) {
            "python" -> if (runtime["mode"]?.jsonPrimitive?.contentOrNull == "pure-python") CompatibilityClass.B else CompatibilityClass.C
            "unsupported-deps" -> CompatibilityClass.C
            "shell", "node", "docker" -> CompatibilityClass.D
            "", "instruction" -> CompatibilityClass.A
            else -> CompatibilityClass.D
        }
        if (classification == CompatibilityClass.C) reasons += "Unsupported dependencies; script stays disabled"
        if (classification == CompatibilityClass.D) reasons += "Runtime $kind cannot execute on this device"
        return SkillInspection(
            classification,
            reasons.distinct(),
            manifest,
            archive.markdown,
            hash,
            archive.files,
            true,
            json,
            packageBytes,
        )
    }

    private fun JsonObject?.stringSet(key: String): Set<String> {
        val element = this?.get(key) ?: return emptySet()
        return runCatching {
            element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun isClassEReason(reasons: List<String>): Boolean =
        reasons.any { reason ->
            val lower = reason.lowercase()
            lower.contains("zip slip") || lower.contains("bomb") || lower.contains("symlink") ||
                lower.contains("native") || lower.contains("pip") || lower.contains("remote") ||
                lower.contains("exceeds") || lower.contains("limit")
        }

    private fun looksRemoteDependency(name: String, payload: ByteArray): Boolean {
        val lowerName = name.lowercase()
        val looksLikeLock = lowerName.contains("requirements") ||
            lowerName.endsWith("pipfile") ||
            lowerName.endsWith("poetry.lock") ||
            lowerName.endsWith("uv.lock") ||
            lowerName.endsWith("pyproject.toml") ||
            lowerName.endsWith(".in")
        if (!looksLikeLock) return false
        val text = runCatching { String(payload, Charsets.UTF_8) }.getOrDefault("")
        val lower = text.lowercase()
        return Regex("https?://").containsMatchIn(lower) ||
            lower.contains("git+") ||
            Regex("@\\s*https?://").containsMatchIn(lower)
    }

    private fun looksNative(name: String, payload: ByteArray): Boolean {
        if (name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib") || name.endsWith(".exe")) return true
        if (name.endsWith(".dex") || name.endsWith(".jar") || name.endsWith(".class") || name.endsWith(".whl")) return true
        if (payload.size >= 4 && payload[0] == 0x7F.toByte() && payload[1] == 'E'.code.toByte() &&
            payload[2] == 'L'.code.toByte() && payload[3] == 'F'.code.toByte()
        ) {
            return true
        }
        if (payload.size >= 4 && payload[0] == 'd'.code.toByte() && payload[1] == 'e'.code.toByte() &&
            payload[2] == 'x'.code.toByte() && payload[3] == '\n'.code.toByte()
        ) {
            return true
        }
        if (payload.size >= 4 && payload[0] == 0xCA.toByte() && payload[1] == 0xFE.toByte() &&
            payload[2] == 0xBA.toByte() && payload[3] == 0xBE.toByte()
        ) {
            return true
        }
        return false
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

data class SkillInstallResult(
    val accepted: Boolean,
    val inspection: SkillInspection,
    val originalUnchanged: Boolean = true,
)

object SkillInstaller {
    fun install(bytes: ByteArray, expectedHash: String? = null): SkillInstallResult {
        val inspection = SkillArchive.inspect(bytes, expectedHash)
        if (!inspection.installable || inspection.classification == CompatibilityClass.E) {
            return SkillInstallResult(accepted = false, inspection = inspection)
        }
        return SkillInstallResult(accepted = true, inspection = inspection)
    }
}
