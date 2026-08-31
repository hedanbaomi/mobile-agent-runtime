// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * One path parser is shared by both backends.  A backend must never resolve a raw path before
 * this parser has accepted every segment.  NFC normalization makes canonically equivalent names
 * address the same entry; provider entries that are not already NFC are rejected as ambiguous.
 */
internal object WorkspacePathPolicy {
    fun parse(raw: String?, allowRoot: Boolean, limits: InternalWorkspaceLimits): List<String> {
        val value = raw ?: InternalWorkspaceErrorCode.INVALID_PATH.error()
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        if (value != normalized) {
            // Callers can use the normalized representation consistently, but controls and path
            // separators must be judged both before and after normalization.
            validateGlobal(value, limits)
        }
        validateGlobal(normalized, limits)
        if (normalized.isEmpty()) {
            if (allowRoot) return emptyList()
            InternalWorkspaceErrorCode.INVALID_PATH.error()
        }
        if (normalized.startsWith('/') || normalized.endsWith('/') || normalized.contains("//")) {
            InternalWorkspaceErrorCode.INVALID_PATH.error()
        }
        val pieces = normalized.split('/')
        if (pieces.size > limits.maxPathDepth) InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED.error()
        pieces.forEach { segment -> validateSegment(segment, limits) }
        return pieces
    }

    /** A provider child name is stricter: returning a non-NFC name would make aliases possible. */
    fun validateProviderName(name: String, limits: InternalWorkspaceLimits): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        if (name != normalized) InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS.error()
        val segments = parse(name, allowRoot = false, limits)
        if (segments.size != 1 || segments[0] != name) InternalWorkspaceErrorCode.INVALID_PATH.error()
        return name
    }

    private fun validateGlobal(value: String, limits: InternalWorkspaceLimits) {
        val bytes = strictUtf8(value) ?: InternalWorkspaceErrorCode.INVALID_PATH.error()
        if (bytes.size > limits.maxPathBytes || value.indexOf('\u0000') >= 0 || value.indexOf('\\') >= 0) {
            InternalWorkspaceErrorCode.INVALID_PATH.error()
        }
        // This catches Unix absolute paths, UNC paths, and drive-qualified paths after slash
        // normalization without treating a URI or host path as a workspace path.
        if (value.startsWith('/') || value.startsWith("//") ||
            value.matches(Regex("^[A-Za-z]:($|/).*"))
        ) {
            InternalWorkspaceErrorCode.INVALID_PATH.error()
        }
        if (value.any { it.isISOControl() }) InternalWorkspaceErrorCode.INVALID_PATH.error()
    }

    private fun validateSegment(segment: String, limits: InternalWorkspaceLimits) {
        val bytes = strictUtf8(segment) ?: InternalWorkspaceErrorCode.INVALID_PATH.error()
        if (segment.isBlank() || segment == "." || segment == ".." || segment.contains(':') ||
            segment.indexOf('\u0000') >= 0 || segment.indexOf('\\') >= 0 ||
            segment.any { it.isISOControl() } || bytes.size > limits.maxSegmentBytes
        ) {
            InternalWorkspaceErrorCode.INVALID_PATH.error()
        }
    }

    private fun strictUtf8(value: String): ByteArray? = try {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) {
        null
    }
}

internal fun expectVersion(actual: String?, expected: String?) {
    if (expected == null) return
    val matches = when {
        expected == InternalWorkspaceVersions.MISSING -> actual == null
        else -> expected == actual
    }
    if (!matches) InternalWorkspaceErrorCode.CONFLICT.error()
}
