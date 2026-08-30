// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.Normalizer
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
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
    /** Exact verified sources for manifestless CLI compatibility; never model supplied. */
    val legacyProgramSources: Map<String, String> = emptyMap(),
)

object SkillArchive {
    const val MAX_COMPRESSED_BYTES = 50L * 1024 * 1024
    const val MAX_ENTRIES = 5000
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    const val MAX_RATIO = 100

    private const val EOCD_SIGNATURE = 0x06054B50
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014B50
    private const val LOCAL_FILE_SIGNATURE = 0x04034B50
    private const val EOCD_LENGTH = 22
    private const val MAX_ZIP_COMMENT_BYTES = 65_535
    private const val UINT32_MAX = 0xFFFF_FFFFL
    private const val MAX_LEGACY_PROGRAM_BYTES = 256 * 1024
    private const val LEGACY_ENTRYPOINT = "mobileagent_legacy_skill:run"

    private val LEGACY_FRONTMATTER_NAME = Regex("(?m)^name:\\s*([A-Za-z0-9._-]+)\\s*$")
    private val LEGACY_MAIN_FUNCTION = Regex("(?m)^\\s*def\\s+main\\s*\\(")
    private val LEGACY_MAIN_GUARD = Regex("if\\s+__name__\\s*==\\s*['\\\"]__main__['\\\"]")
    private val LEGACY_IMPORT = Regex("(?m)^\\s*import\\s+([^#\\r\\n]+)")
    private val LEGACY_FROM_IMPORT = Regex("(?m)^\\s*from\\s+([A-Za-z0-9_.]+)\\s+import\\s+")
    private val LEGACY_STDLIB_MODULES = setOf(
        "__future__", "argparse", "bisect", "collections", "contextlib", "csv", "datetime", "decimal",
        "enum", "functools", "hashlib", "heapq", "io", "itertools", "json", "math", "pathlib", "random",
        "re", "statistics", "string", "sys", "textwrap", "time", "typing", "unicodedata",
    )
    private val LEGACY_FORBIDDEN_TOKENS = mapOf(
        "dynamic-import" to Regex("\\b(?:__import__|importlib)\\b"),
        "dynamic-code" to Regex("\\b(?:eval|exec)\\s*\\("),
        "process" to Regex("\\b(?:subprocess|os\\.(?:system|exec|spawn|fork))\\b"),
        "network" to Regex("\\b(?:socket|urllib\\.request|http\\.client)\\b"),
        "native-extension" to Regex("\\b(?:ctypes|cffi)\\b"),
    )

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
            val structure = runCatching { validateZipStructure(bytes) }.getOrElse { error ->
                reasons += "Archive is truncated or damaged"
                return SkillInspection(
                    CompatibilityClass.E,
                    (reasons + (error.message ?: "zip")).distinct(),
                    null,
                    null,
                    hash,
                    emptyList(),
                    false,
                    packageBytes = bytes,
                )
            }
            val duplicatePath = structure.entries
                .map { canonicalArchivePath(it.name) }
                .groupingBy { it }
                .eachCount()
                .any { (_, count) -> count > 1 }
            if (duplicatePath) {
                reasons += "Duplicate archive paths are not allowed"
                return SkillInspection(
                    CompatibilityClass.E,
                    reasons,
                    null,
                    null,
                    hash,
                    emptyList(),
                    false,
                    packageBytes = bytes,
                )
            }
            if (structure.entries.any { it.isSymlink }) {
                reasons += "Symlink entries are not allowed"
                return SkillInspection(CompatibilityClass.E, reasons.distinct(), null, null, hash, emptyList(), false, packageBytes = bytes)
            }
            val archive = runCatching {
                readArchive(bytes, reasons, structure.entries.sortedBy { it.localHeaderOffset })
            }.getOrElse { error ->
                reasons += "Archive is truncated or damaged"
                return SkillInspection(
                    CompatibilityClass.E,
                    (reasons + (error.message ?: "zip")).distinct(),
                    null,
                    null,
                    hash,
                    emptyList(),
                    false,
                    packageBytes = bytes,
                )
            }
            if (archive.files.isEmpty() && archive.manifestJson == null && archive.markdown == null) {
                reasons += "ZIP has no readable entries"
            }
            if (isClassEReason(reasons) || archive.files.isEmpty() && archive.manifestJson == null) {
                return SkillInspection(
                    CompatibilityClass.E,
                    reasons.distinct().ifEmpty { listOf("ZIP has no readable entries") },
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
        val pythonSources: Map<String, String>,
    )

    private data class LegacyProgramInspection(
        val compatible: List<String>,
        val unsupportedModules: Map<String, Set<String>>,
    )

    private data class ZipCentralEntry(
        val name: String,
        val nameOffset: Long,
        val nameLength: Int,
        val flags: Int,
        val compressionMethod: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val externalAttributes: Long,
        val localHeaderOffset: Long,
    ) {
        val isSymlink: Boolean
            get() = ((externalAttributes ushr 16).toInt() and 0xF000) == 0xA000
    }

    private data class ZipStructure(
        val entries: List<ZipCentralEntry>,
    )

    private fun readArchive(
        bytes: ByteArray,
        reasons: MutableList<String>,
        expectedEntries: List<ZipCentralEntry>,
    ): ArchiveContent {
        val files = mutableListOf<String>()
        var manifest: String? = null
        var markdown: String? = null
        var native = false
        var pip = false
        val pythonSources = linkedMapOf<String, String>()
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
                val rawName = entry.name
                val name = normalizeArchivePath(rawName)
                if (name == null) {
                    reasons += "Zip Slip path is not allowed: ${rawName.replace('\\', '/')}"
                    continue
                }
                val payload = readBounded(zip, MAX_ENTRY_BYTES.toInt())
                if (payload == null) {
                    reasons += "Entry exceeds size limit: $name"
                    continue
                }
                val expected = expectedEntries.getOrNull(count - 1)
                if (expected == null) {
                    reasons += "ZIP local headers do not match the central directory"
                } else {
                    val crc = CRC32().also { it.update(payload) }.value
                    if (entry.name != expected.name ||
                        entry.compressedSize != expected.compressedSize ||
                        payload.size.toLong() != expected.uncompressedSize ||
                        crc != expected.crc32
                    ) {
                        reasons += "ZIP entry data does not match the central directory"
                    }
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
                if (entry.isDirectory) continue
                files += name
                val lower = name.lowercase()
                if (looksNative(lower, payload)) native = true
                if (lower.endsWith("mobile-skill.json")) manifest = String(payload, Charsets.UTF_8)
                if (lower.endsWith("skill.md")) markdown = String(payload, Charsets.UTF_8)
                if (looksRemoteDependency(lower, payload)) pip = true
                if (lower.endsWith(".py") && payload.size <= MAX_LEGACY_PROGRAM_BYTES && payload.none { it == 0.toByte() }) {
                    runCatching { payload.toString(Charsets.UTF_8) }
                        .getOrNull()
                        ?.takeIf { source -> '\uFFFD' !in source }
                        ?.let { source -> pythonSources[name] = source }
                }
            }
        } finally {
            zip.close()
        }
        if (count != expectedEntries.size) reasons += "ZIP local headers do not match the central directory"
        if (native) reasons += "Native payload (ELF/DEX/JAR/SO/wheel) is not allowed"
        if (pip) reasons += "pip / remote dependency install is not allowed"
        val parsed = manifest?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val schema = parsed?.get("schemaVersion")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val runtime = parsed?.get("runtime")?.jsonObject?.get("kind")?.jsonPrimitive?.contentOrNull
            ?: parsed?.get("runtime")?.jsonObject?.get("python")?.let { "python" }
        return ArchiveContent(files, manifest, markdown, native, pip, runtime, schema, pythonSources)
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
            // The compatibility layer is only for actual Claude Skills. A bare Python ZIP
            // must not become executable merely because it happens to contain a stdlib CLI.
            val legacy = if (archive.markdown != null) {
                inspectLegacyPrograms(archive.pythonSources)
            } else {
                LegacyProgramInspection(emptyList(), emptyMap())
            }
            legacy.unsupportedModules.toSortedMap().forEach { (path, modules) ->
                reasons += "Python program $path is not directly runnable in isolated Python; unsupported modules: ${modules.sorted().joinToString()}"
                if (path.substringAfterLast('/').equals("books_kb.py", ignoreCase = true)) {
                    reasons += "books_kb.py requires desktop ML/PDF dependencies; import the books as a knowledge base and use knowledge_search on Android"
                }
            }
            if (legacy.compatible.isNotEmpty()) {
                val programs = legacy.compatible.sorted()
                val name = legacySkillName(archive.markdown)
                val raw = legacyManifestJson(hash, name, programs).toString()
                val manifest = SkillManifest(
                    schemaVersion = 1,
                    id = "legacy.${hash.take(24)}",
                    name = name,
                    version = "compat-1",
                    license = "unknown",
                    runtimeKind = "python",
                    entrypoint = LEGACY_ENTRYPOINT,
                    permissions = emptySet(),
                    permissionSpecs = emptyList(),
                )
                reasons += "Detected ${programs.size} standard-library CLI program(s); runnable only in isolated Python with model-provided virtual files and per-call approval"
                return SkillInspection(
                    CompatibilityClass.B,
                    reasons.distinct(),
                    manifest,
                    archive.markdown,
                    hash,
                    archive.files,
                    true,
                    raw,
                    packageBytes,
                    legacyProgramSources = programs.associateWith { program -> checkNotNull(archive.pythonSources[program]) },
                )
            }
            reasons += if (archive.pythonSources.isEmpty()) {
                "No mobile-skill.json; instruction-only"
            } else {
                "No compatible standard-library CLI program was found; instructions remain available"
            }
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

    private fun inspectLegacyPrograms(sources: Map<String, String>): LegacyProgramInspection {
        val compatible = mutableListOf<String>()
        val unsupported = linkedMapOf<String, Set<String>>()
        sources.toSortedMap().forEach { (path, source) ->
            if (!LEGACY_MAIN_FUNCTION.containsMatchIn(source) || !LEGACY_MAIN_GUARD.containsMatchIn(source)) return@forEach
            val importedRoots = importedModuleRoots(source)
            val unsupportedModules = importedRoots.filter { it !in LEGACY_STDLIB_MODULES }.toSortedSet()
            val forbidden = LEGACY_FORBIDDEN_TOKENS.filter { it.value.containsMatchIn(source) }.map { it.key }.toSortedSet()
            val failures = (unsupportedModules + forbidden).toSortedSet()
            if (failures.isEmpty()) compatible += path else unsupported[path] = failures
        }
        return LegacyProgramInspection(compatible, unsupported)
    }

    private fun importedModuleRoots(source: String): Set<String> = buildSet {
        LEGACY_IMPORT.findAll(source).forEach { match ->
            match.groupValues[1].split(',').forEach { candidate ->
                candidate.trim().substringBefore(' ').substringBefore('.').takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        LEGACY_FROM_IMPORT.findAll(source).forEach { match ->
            match.groupValues[1].substringBefore('.').takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun legacySkillName(markdown: String?): String = markdown
        ?.let { LEGACY_FRONTMATTER_NAME.find(it)?.groupValues?.getOrNull(1) }
        ?.takeIf { it.isNotBlank() }
        ?.take(80)
        ?: "Imported Claude Skill"

    private fun legacyManifestJson(hash: String, name: String, programs: List<String>): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("id", "legacy.${hash.take(24)}")
        put("name", name)
        put("version", "compat-1")
        put("license", "unknown")
        putJsonObject("runtime") {
            put("kind", "python")
            put("python", "3.14")
            put("mode", "pure-python")
            put("entrypoint", LEGACY_ENTRYPOINT)
        }
        put("permissions", JsonObject(emptyMap()))
        put("legacyPrograms", buildJsonArray { programs.forEach { add(JsonPrimitive(it)) } })
        putJsonObject("inputSchema") {
            put("type", "object")
            put("additionalProperties", false)
            put("required", JsonArray(listOf(JsonPrimitive("program"), JsonPrimitive("arguments"), JsonPrimitive("files"))))
            putJsonObject("properties") {
                putJsonObject("program") {
                    put("type", "string")
                    put("enum", buildJsonArray { programs.forEach { add(JsonPrimitive(it)) } })
                }
                putJsonObject("arguments") {
                    put("type", "array")
                    put("minItems", 0)
                    put("maxItems", 64)
                    putJsonObject("items") {
                        put("type", "string")
                        put("maxLength", 256)
                    }
                }
                putJsonObject("files") {
                    put("type", "array")
                    put("minItems", 0)
                    put("maxItems", 128)
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("text"))))
                        putJsonObject("properties") {
                            putJsonObject("path") {
                                put("type", "string")
                                put("minLength", 1)
                                put("maxLength", 240)
                            }
                            putJsonObject("text") {
                                put("type", "string")
                                put("maxLength", 131_072)
                            }
                        }
                    }
                }
            }
        }
        putJsonObject("outputSchema") {
            put("type", "object")
            put("additionalProperties", false)
            put("required", JsonArray(listOf(JsonPrimitive("program"), JsonPrimitive("stdout"))))
            putJsonObject("properties") {
                putJsonObject("program") { put("type", "string") }
                putJsonObject("stdout") {
                    put("type", "string")
                    put("maxLength", 262_144)
                }
            }
        }
        putJsonObject("limits") {
            put("timeoutSeconds", 30)
            put("maxOutputKiB", 512)
            put("maxLogKiB", 128)
        }
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
                lower.contains("exceeds") || lower.contains("limit") || lower.contains("truncated") ||
                lower.contains("damaged") || lower.contains("no readable") || lower.contains("duplicate archive") ||
                lower.contains("central directory")
        }

    /**
     * Validates the single-disk ZIP central directory before ZipInputStream sees any source.
     * ZipInputStream is intentionally retained for bounded payload reads, but it accepts local
     * headers without a central directory, so the EOCD and every central record are checked here.
     */
    private fun validateZipStructure(bytes: ByteArray): ZipStructure {
        val eocd = findEocd(bytes) ?: error("ZIP end record is missing or malformed")
        val disk = u16Checked(bytes, eocd + 4)
        val centralDisk = u16Checked(bytes, eocd + 6)
        val entriesOnDisk = u16Checked(bytes, eocd + 8)
        val entriesTotal = u16Checked(bytes, eocd + 10)
        val centralSize = u32Checked(bytes, eocd + 12)
        val centralOffset = u32Checked(bytes, eocd + 16)
        if (disk != 0 || centralDisk != 0 || entriesOnDisk != entriesTotal) {
            error("Multi-disk ZIP archives are not supported")
        }
        if (entriesTotal == 0xFFFF || centralSize == UINT32_MAX || centralOffset == UINT32_MAX) {
            error("ZIP64 archives are not supported")
        }
        val centralEnd = centralOffset.checkedAdd(centralSize)
        if (centralOffset > eocd.toLong() || centralEnd != eocd.toLong()) {
            error("ZIP central directory bounds are invalid")
        }

        val entries = ArrayList<ZipCentralEntry>(entriesTotal)
        var cursor = centralOffset
        repeat(entriesTotal) {
            if (cursor.checkedAdd(46L) > centralEnd || !hasSignature(bytes, cursor, CENTRAL_DIRECTORY_SIGNATURE)) {
                error("ZIP central directory record is missing or malformed")
            }
            val nameLength = u16Checked(bytes, cursor + 28)
            val extraLength = u16Checked(bytes, cursor + 30)
            val commentLength = u16Checked(bytes, cursor + 32)
            val recordEnd = cursor.checkedAdd(46L + nameLength + extraLength + commentLength)
            if (recordEnd > centralEnd) error("ZIP central directory record exceeds its bounds")
            val flags = u16Checked(bytes, cursor + 8)
            val compressionMethod = u16Checked(bytes, cursor + 10)
            val crc32 = u32Checked(bytes, cursor + 16)
            val compressedSize = u32Checked(bytes, cursor + 20)
            val uncompressedSize = u32Checked(bytes, cursor + 24)
            if (compressedSize == UINT32_MAX || uncompressedSize == UINT32_MAX) {
                error("ZIP64 archives are not supported")
            }
            val nameStart = cursor + 46
            val nameBytes = bytes.copyOfRange(nameStart.toInt(), (nameStart + nameLength).toInt())
            entries += ZipCentralEntry(
                decodeZipName(nameBytes, flags),
                nameStart,
                nameLength,
                flags,
                compressionMethod,
                crc32,
                compressedSize,
                uncompressedSize,
                u32Checked(bytes, cursor + 38),
                u32Checked(bytes, cursor + 42),
            )
            cursor = recordEnd
        }
        if (cursor != centralEnd) error("ZIP central directory size does not match its records")

        entries.forEach { entry ->
            val localOffset = entry.localHeaderOffset
            if (localOffset.checkedAdd(30L) > centralOffset || !hasSignature(bytes, localOffset, LOCAL_FILE_SIGNATURE)) {
                error("ZIP local header does not match the central directory")
            }
            val localNameLength = u16Checked(bytes, localOffset + 26)
            val localExtraLength = u16Checked(bytes, localOffset + 28)
            val localHeaderEnd = localOffset.checkedAdd(30L + localNameLength + localExtraLength)
            if (localHeaderEnd > centralOffset) {
                error("ZIP local header exceeds the archive data area")
            }
            val localFlags = u16Checked(bytes, localOffset + 6)
            val localCompressionMethod = u16Checked(bytes, localOffset + 8)
            if (localFlags != entry.flags || localCompressionMethod != entry.compressionMethod) {
                error("ZIP local header metadata does not match the central directory")
            }
            val localNameStart = localOffset + 30
            val localNameBytes = bytes.copyOfRange(
                localNameStart.toInt(),
                (localNameStart + localNameLength).toInt(),
            )
            val localName = decodeZipName(localNameBytes, localFlags)
            if (localName != entry.name || !sameBytes(bytes, entry.nameOffset, entry.nameLength, localNameStart, localNameLength)) {
                error("ZIP local header name does not match the central directory")
            }
            if (localFlags and 0x08 == 0) {
                val localCrc32 = u32Checked(bytes, localOffset + 14)
                val localCompressedSize = u32Checked(bytes, localOffset + 18)
                val localUncompressedSize = u32Checked(bytes, localOffset + 22)
                if (localCrc32 != entry.crc32 ||
                    localCompressedSize != entry.compressedSize ||
                    localUncompressedSize != entry.uncompressedSize
                ) {
                    error("ZIP local header sizes or checksum do not match the central directory")
                }
            }
        }
        return ZipStructure(entries)
    }

    private fun findEocd(bytes: ByteArray): Int? {
        if (bytes.size < EOCD_LENGTH) return null
        val start = (bytes.size - EOCD_LENGTH - MAX_ZIP_COMMENT_BYTES).coerceAtLeast(0)
        var offset = bytes.size - EOCD_LENGTH
        while (offset >= start) {
            if (hasSignature(bytes, offset.toLong(), EOCD_SIGNATURE)) {
                val commentLength = u16Checked(bytes, offset + 20)
                val centralSize = u32Checked(bytes, offset + 12)
                val centralOffset = u32Checked(bytes, offset + 16)
                if (offset.toLong() + EOCD_LENGTH + commentLength == bytes.size.toLong() &&
                    centralOffset.checkedAdd(centralSize) == offset.toLong()
                ) return offset
            }
            offset -= 1
        }
        return null
    }

    private fun decodeZipName(bytes: ByteArray, flags: Int): String =
        runCatching {
            val charset = if (flags and 0x800 != 0) Charsets.UTF_8 else Charset.forName("IBM437")
            String(bytes, charset)
        }.getOrElse { String(bytes, Charsets.UTF_8) }

    private fun canonicalArchivePath(name: String): String {
        val segments = name.replace('\\', '/').split('/')
        val normalized = ArrayDeque<String>()
        segments.forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeLast() else normalized.addLast(segment)
                else -> normalized.addLast(segment)
            }
        }
        return Normalizer.normalize(normalized.joinToString("/"), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    /**
     * Returns a safe, NFC-normalized logical archive path, or null when the
     * name could address a path outside the import root. Dot checks are
     * segment based so ordinary filenames containing consecutive dots remain
     * valid.
     */
    private fun normalizeArchivePath(name: String): String? {
        val normalized = Normalizer.normalize(name.replace('\\', '/'), Normalizer.Form.NFC)
        if (normalized.isBlank() || normalized.any { Character.isISOControl(it) }) return null
        if (normalized.startsWith('/')) return null
        val segments = normalized.split('/')
        if (segments.any { it == "." || it == ".." }) return null
        // Keep rejecting colons anywhere in an archive name: this covers
        // drive prefixes and Windows alternate data streams on extraction.
        if (normalized.contains(':')) return null
        return normalized
    }

    private fun hasSignature(bytes: ByteArray, offset: Long, signature: Int): Boolean {
        if (offset < 0 || offset > bytes.size - 4) return false
        val i = offset.toInt()
        return (bytes[i].toInt() and 0xFF) == (signature and 0xFF) &&
            (bytes[i + 1].toInt() and 0xFF) == ((signature ushr 8) and 0xFF) &&
            (bytes[i + 2].toInt() and 0xFF) == ((signature ushr 16) and 0xFF) &&
            (bytes[i + 3].toInt() and 0xFF) == ((signature ushr 24) and 0xFF)
    }

    private fun sameBytes(bytes: ByteArray, firstOffset: Long, firstLength: Int, secondOffset: Long, secondLength: Int): Boolean {
        if (firstLength != secondLength || firstOffset < 0 || secondOffset < 0) return false
        if (firstOffset > bytes.size - firstLength || secondOffset > bytes.size - secondLength) return false
        repeat(firstLength) { index ->
            if (bytes[(firstOffset + index).toInt()] != bytes[(secondOffset + index).toInt()]) return false
        }
        return true
    }

    private fun u16Checked(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= bytes.size) error("ZIP field exceeds archive bounds")
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u16Checked(bytes: ByteArray, offset: Long): Int {
        if (offset > Int.MAX_VALUE) error("ZIP field exceeds archive bounds")
        return u16Checked(bytes, offset.toInt())
    }

    private fun u32Checked(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 3 >= bytes.size) error("ZIP field exceeds archive bounds")
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun u32Checked(bytes: ByteArray, offset: Long): Long {
        if (offset > Int.MAX_VALUE) error("ZIP field exceeds archive bounds")
        return u32Checked(bytes, offset.toInt())
    }

    private fun Long.checkedAdd(value: Long): Long =
        if (value < 0 || this > Long.MAX_VALUE - value) error("ZIP field overflows archive bounds") else this + value

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
