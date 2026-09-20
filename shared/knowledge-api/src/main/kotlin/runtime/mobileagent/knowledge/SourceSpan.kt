// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

/**
 * Versioned, explicitly escaped codec for `chunks.source_span` and for
 * per-part provenance in general.
 *
 * ## Why this exists
 * Version 1 spans were built by joining raw field values with `|`:
 *
 * ```
 * page:7|section:photo|association:PAGE_CONTEXT|.png|part:ocr
 * ```
 *
 * A parser-supplied section (for example a ZIP entry, a PDF XObject name or a
 * DOCX paragraph label) may legally contain `|`, so a legitimate label can
 * inject structural-looking tokens. A classifier that searched for the
 * substrings `part:context` / `association:PAGE_CONTEXT` anywhere in the span
 * would then reclassify a genuine OCR fragment as page context and drop its
 * image reference. Escaping removes that ambiguity in both directions: because
 * [encodeSourceSpan] escapes `|` and `%` inside values, a v2 span can never
 * contain an unescaped `|` inside a value, so a `part:`/`association:` token in
 * a v2 span is always structural.
 *
 * ## Reading rules
 * - A v2 span (`v2|` prefix) is parsed by a strict grammar: every value is
 *   percent-decoded, duplicate structural fields and unknown parts are
 *   rejected, and anything malformed yields `null` (fail-closed) instead of a
 *   guess.
 * - A legacy v1 span is **never** percent-decoded and its section is preserved
 *   verbatim (a literal `%`, a truncated `%7` or a `%ZZ` is just text). Only the
 *   final `part:` token is structural, because the builder always appends it
 *   after the section. Real legacy context
 *   (`page:N|part:context|association:PAGE_CONTEXT`) therefore still
 *   downgrades, while injected tokens inside a section are inert.
 * - The production native-page span has no `part:` at all. It is recognised by
 *   its exact complete shape
 *   (`page:N|source:parser-native|association:PAGE_CONTEXT` or
 *   `section-ordinal:N|source:parser-native|association:PAGE_CONTEXT`), never by
 *   scanning for arbitrary tokens.
 * - Legacy rows always report association `PAGE_CONTEXT` because v1 never
 *   encoded layout evidence.
 * - [DecodedSourceSpan.isPageContext] is the single classification entry point
 *   callers should use. A malformed v2 span returns null; readers must withhold
 *   image attribution for those rows. Unstructured legacy spans retain their
 *   existing attribution instead of guessing fields from arbitrary substrings.
 *
 * ## Versioning and compatibility
 * [encodeSourceSpan] always emits [SourceSpan.FORMAT_VERSION_2] so new rows are
 * unambiguous. Legacy v1 rows stay readable (verbatim) and are only rewritten
 * only new publications use v2, so existing citations keep resolving.
 */
object SourceSpan {
    /** Marker that the span uses explicit escaping; never a valid v1 page field. */
    const val FORMAT_VERSION_2 = "v2|"

    const val PART_DESCRIPTION = "description"
    const val PART_OCR = "ocr"
    const val PART_TABLE = "table"
    const val PART_CONTEXT = "context"
    const val PART_LABEL = "label"

    /**
     * Closed vocabulary the builder may publish and [encodeSourceSpan] accepts.
     * An unknown part is rejected instead of being silently trusted.
     */
    val PARTS: Set<String> = setOf(PART_DESCRIPTION, PART_OCR, PART_TABLE, PART_CONTEXT, PART_LABEL)

    /**
     * Relation marker: the wrapped text is page/section context and makes no
     * claim that it was extracted from a specific crop.
     */
    const val ASSOCIATION_PAGE_CONTEXT = "PAGE_CONTEXT"

    /**
     * Relation marker: a parser proved the text range lies inside the crop. The
     * authoritative carrier is `ProcessingUnit.textLayoutEvidence`; the span only
     * mirrors the marker so encoded and legacy rows classify consistently.
     */
    const val ASSOCIATION_PROVEN_LAYOUT = "PROVEN_LAYOUT"

    /** Association values [decodeSourceSpan] accepts (and [encodeSourceSpan] emits). */
    val ASSOCIATIONS: Set<String> = setOf(ASSOCIATION_PAGE_CONTEXT, ASSOCIATION_PROVEN_LAYOUT)

    /** Exact `source:` value of the production native-page span. */
    const val SOURCE_PARSER_NATIVE = "parser-native"
}

/**
 * Decoded source location for one published fragment.
 *
 * [encoded] is `true` when the row carried the [SourceSpan.FORMAT_VERSION_2]
 * marker; `false` means an unescaped legacy v1 span. [association] is never null
 * for legacy rows: v1 never encoded layout evidence, so the honest default is
 * [SourceSpan.ASSOCIATION_PAGE_CONTEXT].
 */
data class DecodedSourceSpan(
    val encoded: Boolean,
    val page: Int?,
    val section: String?,
    val part: String,
    val segmentIndex: Int?,
    val segmentTotal: Int?,
    val imageRegion: UnitRegion?,
    val source: String?,
    val association: String?,
    val raw: String,
) {
    /**
     * True when this fragment must not resolve an image asset as its evidence.
     *
     * Encoded rows are decided by the structural [part] alone (unknown or
     * duplicated parts never reach here). Legacy rows require the trailing
     * association *and* the final `part:` token to be `part:context`. The native
     * page shape is recognised by its exact complete structure, so injected
     * tokens inside a section are inert.
     */
    val isPageContext: Boolean
        get() = if (encoded) {
            part == SourceSpan.PART_CONTEXT
        } else {
            association == SourceSpan.ASSOCIATION_PAGE_CONTEXT && part == SourceSpan.PART_CONTEXT
        }

    /**
     * True when the fragment can back a layout-evidence claim.
     *
     * Only an encoded [SourceSpan.ASSOCIATION_PROVEN_LAYOUT] on a non-context
     * part qualifies. Legacy v1 rows are always page context, so a migration can
     * never silently upgrade an unlocated legacy fragment.
     */
    val isProvenLayout: Boolean
        get() = encoded &&
            association == SourceSpan.ASSOCIATION_PROVEN_LAYOUT &&
            part != SourceSpan.PART_CONTEXT
}

/**
 * Encode a provenance span. [section] keeps its exact content: `%`, `|`, CR and
 * LF are escaped (`%25`, `%7C`, `%0D`, `%0A`) and decoded back verbatim, so a
 * legitimate display name is never disabled or silently rewritten.
 *
 * @param imageRegion normalized millionths rectangle for the attached crop.
 * @param source producing step label, e.g. `parser-layout`.
 * @param association one of [SourceSpan.ASSOCIATIONS]; null omits the field.
 */
fun encodeSourceSpan(
    page: Int?,
    section: String? = null,
    part: String,
    segmentIndex: Int? = null,
    segmentTotal: Int? = null,
    imageRegion: UnitRegion? = null,
    source: String? = null,
    association: String? = null,
): String {
    require(part in SourceSpan.PARTS) { "PIPELINE_INVALID_SPAN: unknown part" }
    require(segmentIndex == null || segmentIndex >= 1) { "PIPELINE_INVALID_SPAN: segmentIndex must be >= 1" }
    require(segmentTotal == null || segmentTotal >= 1) { "PIPELINE_INVALID_SPAN: segmentTotal must be >= 1" }
    require(segmentIndex == null || segmentTotal == null || segmentIndex in 1..segmentTotal) {
        "PIPELINE_INVALID_SPAN: segmentIndex must lie inside segmentTotal"
    }
    require(association == null || association in SourceSpan.ASSOCIATIONS) {
        "PIPELINE_INVALID_SPAN: unknown association"
    }
    val fields = mutableListOf("page:${page ?: "?"}")
    section?.takeIf { it.isNotBlank() }?.let { fields += "section:${escapeSourceSpanValue(it)}" }
    fields += "part:${escapeSourceSpanValue(part)}"
    if (segmentTotal != null && segmentTotal > 1) {
        fields += "segment:${segmentIndex ?: 1}/$segmentTotal"
    }
    imageRegion?.let { fields += "image-region:${it.left},${it.top},${it.right},${it.bottom}/1000000" }
    source?.takeIf { it.isNotBlank() }?.let { fields += "source:${escapeSourceSpanValue(it)}" }
    association?.let { fields += "association:${escapeSourceSpanValue(it)}" }
    return SourceSpan.FORMAT_VERSION_2 + fields.joinToString("|")
}

/**
 * Parse a span produced by [encodeSourceSpan] or a legacy v1 span.
 *
 * Fail-closed: a malformed encoded span, an unknown/duplicate structural field,
 * or any shape the codec cannot prove returns `null`. The caller distinguishes
 * malformed v2 (withhold image attribution) from unstructured legacy spans.
 *
 * @return null when the span is blank, malformed, ambiguous, or has no part.
 */
fun decodeSourceSpan(span: String): DecodedSourceSpan? {
    if (span.isBlank()) return null
    return if (span.startsWith(SourceSpan.FORMAT_VERSION_2)) {
        decodeEncodedSourceSpan(span.substring(SourceSpan.FORMAT_VERSION_2.length), span)
    } else {
        decodeLegacySourceSpan(span)
    }
}

/** Strict v2 grammar: known keys only, no duplicates, percent-decoded values. */
private fun decodeEncodedSourceSpan(body: String, raw: String): DecodedSourceSpan? {
    if (body.isBlank()) return null
    val tokens = body.split('|')
    if (tokens.isEmpty()) return null
    var page: Int? = null
    var section: String? = null
    var part: String? = null
    var segmentIndex: Int? = null
    var segmentTotal: Int? = null
    var region: UnitRegion? = null
    var source: String? = null
    var association: String? = null
    val seen = mutableSetOf<String>()
    for (token in tokens) {
        if (':' !in token) return null
        val key = token.substringBefore(':')
        // An unknown prefix or a repeated structural field is ambiguous: reject.
        if (key !in ENCODED_KEYS || !seen.add(key)) return null
        when (key) {
            "page" -> {
                val value = token.removePrefix("page:")
                if (value != "?") page = value.toIntOrNull() ?: return null
            }
            "section" -> section = decodeSourceSpanValue(token.removePrefix("section:")) ?: return null
            "part" -> {
                val value = decodeSourceSpanValue(token.removePrefix("part:"))
                if (value !in SourceSpan.PARTS) return null
                part = value
            }
            "segment" -> {
                val segment = parseSourceSpanSegment(token.removePrefix("segment:")) ?: return null
                segmentIndex = segment.first
                segmentTotal = segment.second
            }
            "image-region" -> region = parseSourceSpanRegion(token.removePrefix("image-region:")) ?: return null
            "source" -> source = decodeSourceSpanValue(token.removePrefix("source:")) ?: return null
            "association" -> {
                val value = decodeSourceSpanValue(token.removePrefix("association:"))
                if (value !in SourceSpan.ASSOCIATIONS) return null
                association = value
            }
        }
    }
    if ("page" !in seen) return null
    val decodedPart = part ?: return null
    return DecodedSourceSpan(
        encoded = true,
        page = page,
        section = section,
        part = decodedPart,
        segmentIndex = segmentIndex,
        segmentTotal = segmentTotal,
        imageRegion = region,
        source = source,
        association = association,
        raw = raw,
    )
}

/**
 * Legacy v1 readable rules. Values are kept **verbatim** (never percent-decoded,
 * so a literal `%`, a truncated `%7` or `%ZZ` is just text) and only the final
 * `part:` token is structural, because the builder always appends it after the
 * section.
 *
 * The section keeps every character up to the final `part:` token, including
 * `|`, so a label such as `photo|part:context|.png` stays one section value and
 * can never be read as a structural field. `page:` and `source:` are read only
 * from their real positions, never from tokens injected inside the section.
 */
private fun decodeLegacySourceSpan(raw: String): DecodedSourceSpan? {
    val partIndex = raw.lastIndexOf("|part:")
    if (partIndex < 0) {
        return decodeLegacyNativeContextSpan(raw.split('|'), raw)
    }
    val suffix = raw.substring(partIndex + 1).split('|')
    val partText = suffix.first().removePrefix("part:")
    if (partText !in SourceSpan.PARTS) return null
    val prefix = raw.substring(0, partIndex)
    // Only the first field and suffix after the final part are structural.
    // Everything after the leading section marker is the original raw label.
    val leading = prefix.substringBefore('|')
    if (!leading.startsWith("page:")) return null
    val pageValue = leading.removePrefix("page:")
    val page = if (pageValue == "?") null else pageValue.toIntOrNull() ?: return null
    val remainder = prefix.removePrefix(leading)
    val section = when {
        remainder.isEmpty() -> null
        remainder.startsWith("|section:") -> remainder.removePrefix("|section:")
        else -> return null
    }
    val tail = suffix.drop(1)
    if (tail.any { it.substringBefore(':') !in setOf("segment", "image-region", "association") }) return null
    if (tail.map { it.substringBefore(':') }.distinct().size != tail.size) return null
    if (tail.any { it.startsWith("association:") && it !in LegacyTokens.KNOWN_ASSOCIATIONS }) return null
    val segment = tail.lastOrNull { it.startsWith("segment:") }
        ?.let { parseSourceSpanSegment(it.removePrefix("segment:")) }
    return DecodedSourceSpan(
        encoded = false,
        page = page,
        section = section,
        part = partText,
        segmentIndex = segment?.first,
        segmentTotal = segment?.second,
        imageRegion = tail.lastOrNull { it.startsWith("image-region:") }
            ?.let { parseSourceSpanRegion(it.removePrefix("image-region:")) },
        source = null,
        association = SourceSpan.ASSOCIATION_PAGE_CONTEXT,
        raw = raw,
    )
}


/**
 * The production native-page span, which has no `part:` field:
 *
 * ```
 * page:N|source:parser-native|association:PAGE_CONTEXT
 * section-ordinal:N|source:parser-native|association:PAGE_CONTEXT
 * ```
 *
 * Only that exact complete structure is page context; any extra, missing or
 * different token returns `null` instead of a token-scanned guess.
 */
private fun decodeLegacyNativeContextSpan(tokens: List<String>, raw: String): DecodedSourceSpan? {
    if (tokens.size != 3) return null
    val location = tokens[0]
    val locationKind = when {
        location.startsWith("page:") -> "page"
        location.startsWith("section-ordinal:") -> "section-ordinal"
        else -> return null
    }
    val locationValue = location.substringAfter(':')
    val locationNumber = locationValue.toIntOrNull() ?: return null
    if (tokens[1] != "source:${SourceSpan.SOURCE_PARSER_NATIVE}") return null
    if (tokens[2] != "association:${SourceSpan.ASSOCIATION_PAGE_CONTEXT}") return null
    return DecodedSourceSpan(
        encoded = false,
        page = locationNumber.takeIf { locationKind == "page" },
        section = locationValue.takeIf { locationKind == "section-ordinal" },
        part = SourceSpan.PART_CONTEXT,
        segmentIndex = null,
        segmentTotal = null,
        imageRegion = null,
        source = SourceSpan.SOURCE_PARSER_NATIVE,
        association = SourceSpan.ASSOCIATION_PAGE_CONTEXT,
        raw = raw,
    )
}

private object LegacyTokens {
    /** Every association marker the legacy writer could legitimately have emitted. */
    val KNOWN_ASSOCIATIONS: Set<String> = setOf(
        "association:${SourceSpan.ASSOCIATION_PAGE_CONTEXT}",
        "association:${SourceSpan.ASSOCIATION_PROVEN_LAYOUT}",
    )
}
private val ENCODED_KEYS = setOf("page", "section", "part", "segment", "image-region", "source", "association")

private fun escapeSourceSpanValue(value: String): String {
    val out = StringBuilder(value.length + 8)
    for (c in value) {
        when (c) {
            '%' -> out.append("%25")
            '|' -> out.append("%7C")
            '\n' -> out.append("%0A")
            '\r' -> out.append("%0D")
            else -> out.append(c)
        }
    }
    return out.toString()
}

private fun decodeSourceSpanValue(raw: String): String? {
    if (raw.indexOf('%') < 0) return raw
    val out = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val c = raw[index]
        if (c != '%') {
            out.append(c)
            index++
            continue
        }
        val hex = raw.substring(index + 1, minOf(index + 3, raw.length))
        val code = if (hex.length == 2) hex.toIntOrNull(16) else null
        if (code == null) return null
        out.append(code.toChar())
        index += 3
    }
    return out.toString()
}

private fun parseSourceSpanSegment(raw: String): Pair<Int, Int>? {
    val slash = raw.indexOf('/')
    if (slash <= 0 || slash == raw.length - 1) return null
    val index = raw.substring(0, slash).toIntOrNull() ?: return null
    val total = raw.substring(slash + 1).toIntOrNull() ?: return null
    if (index < 1 || total < 1 || index > total) return null
    return index to total
}

private fun parseSourceSpanRegion(raw: String): UnitRegion? {
    val scaleSplit = raw.split('/')
    if (scaleSplit.size != 2 || scaleSplit[1] != "1000000") return null
    val numbers = scaleSplit[0].split(',').map { it.toIntOrNull() ?: return null }
    if (numbers.size != 4) return null
    return runCatching { UnitRegion(numbers[0], numbers[1], numbers[2], numbers[3]) }.getOrNull()
}
