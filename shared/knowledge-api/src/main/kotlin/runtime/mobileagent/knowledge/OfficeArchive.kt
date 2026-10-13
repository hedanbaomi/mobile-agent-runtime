// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

internal fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
    val out = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            // OfficeParser has already applied ZipSafety.inspect to these bytes.
            // Canonicalize the same accepted names for duplicate/path identity.
            val name = java.text.Normalizer.normalize(entry.name.replace('\\', '/'), java.text.Normalizer.Form.NFC)
            if (out.containsKey(name)) error("Archive has duplicate entries")
            out[name] = zip.readBytes()
        }
    }
    return out
}

/** Resolves a package-relative reference and refuses to leave the package root. */
internal fun resolvePackagePath(section: String, src: String): String? {
    val reference = src.substringBefore('#').substringBefore('?').trim()
    if (reference.isEmpty() || reference.startsWith('/') || reference.contains('\\') ||
        Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(reference)) return null
    val raw = try { java.net.URLDecoder.decode(reference.replace("+", "%2B"), "UTF-8") }
        catch (_: IllegalArgumentException) { return null }
    if (raw.startsWith('/') || raw.contains('\\') || raw.contains(':') || raw.any { Character.isISOControl(it) }) return null
    val base = section.substringBeforeLast('/', missingDelimiterValue = "")
    val combined = if (base.isEmpty()) raw else "$base/$raw"
    val parts = mutableListOf<String>()
    combined.split('/').forEach { piece ->
        when (piece) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else return null
            else -> parts += piece
        }
    }
    return parts.joinToString("/").ifEmpty { null }
}

internal fun guessImageType(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".webp") -> "image/webp"
        else -> "application/octet-stream"
    }
}

internal fun imageName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
        lower.endsWith(".gif") || lower.endsWith(".webp")
}
