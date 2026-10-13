// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** SAX already validates XML characters; append chunks without dropping split surrogate pairs. */
internal fun appendVisible(target: StringBuilder, chars: CharArray, start: Int, length: Int) {
    target.append(chars, start, length)
}

/** Collapses horizontal whitespace and trims; explicit paragraph breaks survive. */
internal fun normalize(value: StringBuilder, preserveBreaks: Boolean = false): String {
    val horizontal = value.toString().replace(Regex("[\\t\u00a0 ]+"), " ")
    return if (!preserveBreaks) {
        horizontal.replace(Regex("[\\r\\n]+"), " ").trim()
    } else {
        horizontal.split('\n')
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n+"), "\n")
            .trim()
    }
}

internal fun localName(localName: String?, qName: String?): String =
    localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')

internal fun decodeUtf8(bytes: ByteArray, label: String): String {
    if (bytes.size > MAX_PACKAGE_BYTES) error("XML part exceeds the supported size limit")
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        error("$label is not valid UTF-8")
    }
}

/** XHTML named character references supported by the EPUB content model. */
private val HTML_ENTITIES: Map<String, Int> = mapOf(
    "nbsp" to 160, "iexcl" to 161, "cent" to 162, "pound" to 163, "curren" to 164, "yen" to 165,
    "brvbar" to 166, "sect" to 167, "uml" to 168, "copy" to 169, "ordf" to 170, "laquo" to 171,
    "not" to 172, "shy" to 173, "reg" to 174, "macr" to 175, "deg" to 176, "plusmn" to 177,
    "sup2" to 178, "sup3" to 179, "acute" to 180, "micro" to 181, "para" to 182, "middot" to 183,
    "cedil" to 184, "sup1" to 185, "ordm" to 186, "raquo" to 187, "frac14" to 188, "frac12" to 189,
    "frac34" to 190, "iquest" to 191, "times" to 215, "divide" to 247, "OElig" to 338, "oelig" to 339,
    "Scaron" to 352, "scaron" to 353, "Yuml" to 376, "fnof" to 402, "circ" to 710, "tilde" to 732,
    "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "zwnj" to 8204, "zwj" to 8205, "lrm" to 8206,
    "rlm" to 8207, "ndash" to 8211, "mdash" to 8212, "lsquo" to 8216, "rsquo" to 8217, "sbquo" to 8218,
    "ldquo" to 8220, "rdquo" to 8221, "bdquo" to 8222, "dagger" to 8224, "Dagger" to 8225, "bull" to 8226,
    "hellip" to 8230, "permil" to 8240, "prime" to 8242, "Prime" to 8243, "lsaquo" to 8249,
    "rsaquo" to 8250, "oline" to 8254, "frasl" to 8260, "euro" to 8364,
)

/**
 * XHTML named character references are a closed, known set. Resolving them
 * to numeric references keeps a single XML entity-decode pass — no text is
 * decoded twice — and fails closed on anything undeclared. XML literal
 * regions (CDATA, comments and processing instructions) are copied verbatim.
 */
internal fun expandHtmlEntities(source: String): String {
    if (source.indexOf('&') < 0) return source
    val out = StringBuilder(source.length)
    var index = 0
    while (index < source.length) {
        val literalEnd = when {
            source.startsWith("<![CDATA[", index) -> "]]>"
            source.startsWith("<!--", index) -> "-->"
            source.startsWith("<?", index) -> "?>"
            else -> null
        }
        if (literalEnd != null) {
            val end = source.indexOf(literalEnd, index + 2)
            if (end < 0) error("XML part is malformed or exceeds supported limits")
            val next = end + literalEnd.length
            out.append(source, index, next)
            index = next
            continue
        }
        if (source[index] != '&') {
            out.append(source[index++])
            continue
        }
        // Search at most 32 characters per candidate, including malformed
        // ampersand runs, rather than repeatedly scanning the remaining input.
        val boundary = (index + 33).coerceAtMost(source.length)
        var end = index + 1
        while (end < boundary && source[end] != ';') end++
        val name = if (end < boundary) source.substring(index + 1, end) else ""
        val point = HTML_ENTITIES[name]
        if (point == null) {
            out.append('&')
            index += 1
        } else {
            out.append("&#").append(point).append(';')
            index = end + 1
        }
    }
    return out.toString()
}
