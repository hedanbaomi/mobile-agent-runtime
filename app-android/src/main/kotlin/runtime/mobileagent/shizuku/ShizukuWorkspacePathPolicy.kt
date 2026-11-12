// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.Normalizer
import java.util.HashSet
import java.util.LinkedHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure relative-path validator and filesystem NFC alias guard shared by the
 * Shizuku workspace store, Binder adapters, and unit tests.
 */
internal object ShizukuWorkspacePathPolicy {
    private val DRIVE_PREFIX = Regex("^[A-Za-z]:($|/).*")

    fun parse(raw: String?, allowRoot: Boolean): List<String> {
        val rawPath = raw ?: if (allowRoot) return emptyList() else throw InvalidShizukuPath()
        if (rawPath.isEmpty()) {
            if (allowRoot) return emptyList()
            throw InvalidShizukuPath()
        }
        val path = Normalizer.normalize(rawPath, Normalizer.Form.NFC)
        if (rawPath != path) {
            validateGlobal(rawPath)
        }
        validateGlobal(path)
        val pieces = path.split('/')
        if (pieces.size > ShizukuWorkspaceFileStore.MAX_PATH_DEPTH) throw ShizukuPathLimit()
        pieces.forEach(::validateSegment)
        return pieces
    }

    /** Rejects non-NFC provider entry names before callers can observe or alias them. */
    fun validateProviderName(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        if (name != normalized) {
            throw ShizukuWorkspaceFileStore.WorkspaceFailure(ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS)
        }
        val segments = parse(name, allowRoot = false)
        if (segments.size != 1 || segments[0] != name) throw InvalidShizukuPath()
        return name
    }

    /**
     * Fails closed with [ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS] if any
     * immediate entry in [entries] is not in Unicode NFC form or collides with a
     * sibling after NFC normalization.
     */
    fun validateDirectoryEntries(entries: Iterable<Path>) {
        val seenNormalized = HashSet<String>()
        entries.forEach { entry ->
            val rawName = entry.fileName?.toString()
                ?: throw ShizukuWorkspaceFileStore.WorkspaceFailure(ShizukuWorkspaceFileStore.INVALID_PATH)
            strictUtf8(rawName)
                ?: throw ShizukuWorkspaceFileStore.WorkspaceFailure(ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS)
            val normalized = Normalizer.normalize(rawName, Normalizer.Form.NFC)
            if (rawName != normalized || !seenNormalized.add(normalized)) {
                throw ShizukuWorkspaceFileStore.WorkspaceFailure(ShizukuWorkspaceFileStore.PROVIDER_ALIAS_AMBIGUOUS)
            }
        }
    }

    /** Re-verifies [directory] immediately before a filesystem mutation commits. */
    fun verifyDirectoryClean(directory: Path) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
        val entries = Files.newDirectoryStream(directory).use { stream -> stream.toList() }
        validateDirectoryEntries(entries)
    }

    /**
     * Resolves [segments] beneath [rootPath], rejecting symbolic links, out-of-root
     * escapes, and any ancestor/parent directory containing non-NFC or NFC-colliding
     * sibling entries before the caller reads or mutates the target.
     */
    fun verifyResolvedPath(
        rootPath: Path,
        segments: List<String>,
        rejectSymbolicLink: (Path) -> Unit,
    ): Path {
        val candidate = segments.fold(rootPath) { parent, segment -> parent.resolve(segment) }.normalize()
        if (candidate != rootPath && !candidate.startsWith(rootPath)) {
            throw ShizukuWorkspaceFileStore.WorkspaceFailure(ShizukuWorkspaceFileStore.OUTSIDE_ROOT)
        }
        var current = rootPath
        rejectSymbolicLink(current)
        segments.forEach { segment ->
            verifyStepDirectory(current, segment)
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) rejectSymbolicLink(current)
        }
        val canonicalRoot = rootPath.toFile().canonicalFile.toPath()
        val canonicalCandidate = candidate.toFile().canonicalFile.toPath()
        if (canonicalCandidate != canonicalRoot && !canonicalCandidate.startsWith(canonicalRoot)) {
            throw ShizukuWorkspaceFileStore.WorkspaceFailure(ShizukuWorkspaceFileStore.OUTSIDE_ROOT)
        }
        return candidate
    }

    fun isValid(raw: String?, allowRoot: Boolean): Boolean = runCatching {
        parse(raw, allowRoot)
    }.isSuccess

    private fun verifyStepDirectory(directory: Path, nextSegment: String) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
        val entries = try {
            Files.newDirectoryStream(directory).use { stream -> stream.toList() }
        } catch (denied: AccessDeniedException) {
            if (canHaveCanonicalAlias(nextSegment)) throw denied
            return
        } catch (denied: SecurityException) {
            if (canHaveCanonicalAlias(nextSegment)) throw denied
            return
        }
        validateDirectoryEntries(entries)
    }

    /**
     * Only segments free of non-ASCII code points and of the three ASCII targets
     * reachable by Unicode NFC singletons (`U+037E -> ';'`, `U+1FEF -> '`'`,
     * `U+212A -> 'K'`) are provably free of canonical aliases across a search-only
     * (`--x`) ancestor directory such as `/storage/emulated`.
     */
    private fun canHaveCanonicalAlias(segment: String): Boolean =
        segment.any { ch -> ch.code >= 0x80 || ch == ';' || ch == '`' || ch == 'K' }

    private fun validateGlobal(value: String) {
        val bytes = strictUtf8(value) ?: throw InvalidShizukuPath()
        if (bytes.size > ShizukuWorkspaceFileStore.MAX_PATH_BYTES ||
            value.indexOf('\u0000') >= 0 ||
            value.indexOf('\\') >= 0
        ) {
            throw InvalidShizukuPath()
        }
        if (value.startsWith('/') || value.endsWith('/') || value.contains("//") ||
            DRIVE_PREFIX.matches(value) || value.any(Char::isISOControl)
        ) {
            throw InvalidShizukuPath()
        }
    }

    private fun validateSegment(piece: String) {
        val segmentBytes = strictUtf8(piece) ?: throw InvalidShizukuPath()
        if (piece.isBlank() || piece == "." || piece == ".." || piece.contains(':') ||
            segmentBytes.size > ShizukuWorkspaceFileStore.MAX_SEGMENT_BYTES ||
            piece.any(Char::isISOControl)
        ) {
            throw InvalidShizukuPath()
        }
    }

    private fun strictUtf8(value: String): ByteArray? = try {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * Pure-JVM compact JSON builder for [ShizukuWorkspaceFileStore] responses so unit
 * tests exercise real filesystem operations without `android.jar` `org.json` stubs.
 */
internal class ShizukuJson {
    private val fields = LinkedHashMap<String, JsonElement>()

    fun put(key: String, value: Boolean): ShizukuJson = apply { fields[key] = JsonPrimitive(value) }
    fun put(key: String, value: Int): ShizukuJson = apply { fields[key] = JsonPrimitive(value) }
    fun put(key: String, value: Long): ShizukuJson = apply { fields[key] = JsonPrimitive(value) }
    fun put(key: String, value: String): ShizukuJson = apply { fields[key] = JsonPrimitive(value) }

    fun putNullable(key: String, value: String?): ShizukuJson = apply {
        fields[key] = if (value == null) JsonNull else JsonPrimitive(value)
    }

    fun putEntries(key: String, values: List<ShizukuJson>): ShizukuJson = apply {
        fields[key] = JsonArray(values.map(ShizukuJson::toJsonObject))
    }

    fun optString(key: String): String = (fields[key] as? JsonPrimitive)?.content.orEmpty()

    fun toJsonObject(): JsonObject = JsonObject(fields)

    override fun toString(): String = JsonObject(fields).toString()
}

internal class InvalidShizukuPath : Exception()

internal class ShizukuPathLimit : Exception()
