// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Pure relative-path validator shared by the Binder adapter and its tests. */
internal object ShizukuWorkspacePathPolicy {
    fun parse(raw: String?, allowRoot: Boolean): List<String> {
        val path = raw ?: if (allowRoot) return emptyList() else throw InvalidShizukuPath()
        if (path.isEmpty()) {
            if (allowRoot) return emptyList()
            throw InvalidShizukuPath()
        }
        val pathBytes = strictUtf8(path) ?: throw InvalidShizukuPath()
        if (pathBytes.size > ShizukuWorkspaceFileStore.MAX_PATH_BYTES || path.indexOf('\u0000') >= 0 || path.indexOf('\\') >= 0) {
            throw InvalidShizukuPath()
        }
        if (path.startsWith('/') || path.endsWith('/') || path.contains("//")) {
            throw InvalidShizukuPath()
        }
        val pieces = path.split('/')
        if (pieces.size > ShizukuWorkspaceFileStore.MAX_PATH_DEPTH) throw ShizukuPathLimit()
        pieces.forEach { piece ->
            val segmentBytes = strictUtf8(piece) ?: throw InvalidShizukuPath()
            if (piece.isBlank() || piece == "." || piece == ".." || piece.contains(':') ||
                segmentBytes.size > ShizukuWorkspaceFileStore.MAX_SEGMENT_BYTES ||
                piece.any { it.isISOControl() }
            ) {
                throw InvalidShizukuPath()
            }
        }
        return pieces
    }

    fun isValid(raw: String?, allowRoot: Boolean): Boolean = runCatching {
        parse(raw, allowRoot)
    }.isSuccess

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

internal class InvalidShizukuPath : Exception()

internal class ShizukuPathLimit : Exception()
