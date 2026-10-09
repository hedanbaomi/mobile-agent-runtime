// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

/**
 * One display reference of a reply.
 *
 * [citationId] is the canonical host id: an id the host explicitly bound for this reply,
 * never a title, excerpt, or other guess.  [number] is the display index, assigned by the
 * first appearance of that source in the reply body.
 */
data class ReplyCitationMarker(val number: Int, val citationId: String)

/**
 * A reply whose citation tokens are normalized for Markdown rendering.
 *
 * [texts] keeps the host's assistant fragment order and size; recognized tokens became
 * `[citation:<canonical id>]` and everything else is byte-for-byte unchanged.  [markers] maps
 * each canonical id to its display number.  [copyText] is the copy variant of the same
 * fragments - recognized tokens become `[number]`, code stays as written - joined by a blank
 * line and without any source tail.
 */
data class ReplyPresentation(
    val texts: List<String>,
    val markers: Map<String, ReplyCitationMarker>,
    val copyText: String,
)

/**
 * Normalizes the citation tokens of one reply.
 *
 * [texts] must hold the assistant fragments of the whole reply in order - never tool or
 * reasoning text.  Only sources bound through [citationIds] are recognized; [citations]
 * supplies the host's immutable identity (`knowledgeBaseId` / `source` / `documentVersionId` /
 * `chunkId`) but a title, excerpt, or location is never used to guess a source.
 *
 * Recognized shapes: `[citation:ID]`, `[ID]`, `【ID】`, `【citation:ID】`, and
 * `[label](citation:ID)`.  An exact bound id always wins; a bare chunk id is admitted only
 * when every binding carrying it resolves to the same immutable source.  Footnotes (`[1]`),
 * unknown, forged, or ambiguous ids, and anything inside a fenced block or inline backticks
 * are preserved as they are.
 *
 * A trailing source list that consists only of ids the body already referenced (optionally
 * below a `来源`-style heading) is dropped; inline markers already open that evidence. A tail
 * carrying prose, an unknown id, or code keeps the whole tail: this never guesses at model
 * intent and never rewrites body text.
 */
fun presentReply(
    texts: List<String>,
    citationIds: List<String>,
    citations: List<ChatCitationUi>,
): ReplyPresentation {
    val sources = BoundSources(citationIds, citations)
    val referencedEarlier = referencedCanonicals(texts.dropLast(1), sources)
    val body = texts.mapIndexed { index, text ->
        if (index == texts.lastIndex) stripTrailingSourceList(text, sources, referencedEarlier) else text
    }
    val numbers = LinkedHashMap<String, Int>()
    val rendered = body.map { renderText(it, sources, numbers) }
    val markers = numbers.entries.associate { (id, number) -> id to ReplyCitationMarker(number, id) }
    return ReplyPresentation(
        texts = rendered.map { it.normalized },
        markers = markers,
        copyText = rendered.map { it.copy }.filter { it.isNotBlank() }.joinToString("\n\n"),
    )
}

/** The immutable host identity of a source; all four parts must be present to merge bindings. */
private data class SourceIdentity(
    val knowledgeBaseId: String,
    val source: String,
    val documentVersionId: String,
    val chunkId: String,
    val assetId: String?,
)

/**
 * The host's bindings for one reply.
 *
 * `citationIds` is the admission list: an id outside it is never recognized.  Two bindings of
 * the same immutable identity share one canonical id; a binding without a complete identity
 * keeps its own id, so it is deduplicated by exact id only.
 */
private class BoundSources(citationIds: List<String>, citations: List<ChatCitationUi>) {
    private val canonicalByBoundId = LinkedHashMap<String, String>()
    private val canonicalByChunkId = HashMap<String, LinkedHashSet<String>>()
    private val incompleteChunks = HashSet<String>()

    init {
        val byId = HashMap<String, ChatCitationUi>()
        citations.forEach { if (!byId.containsKey(it.id)) byId[it.id] = it }
        val canonicalByIdentity = LinkedHashMap<SourceIdentity, String>()
        citationIds.forEach { id ->
            if (id.isBlank() || canonicalByBoundId.containsKey(id)) return@forEach
            val source = byId[id] ?: return@forEach
            val identity = source.identity()
            val canonical = if (identity == null) id else canonicalByIdentity.getOrPut(identity) { id }
            canonicalByBoundId[id] = canonical
        }
        canonicalByBoundId.forEach { (id, canonical) ->
            val source = byId[id] ?: return@forEach
            val chunkId = source.chunkId.takeIf { it.isNotBlank() } ?: return@forEach
            if (source.identity() == null) incompleteChunks.add(chunkId)
            canonicalByChunkId.getOrPut(chunkId) { LinkedHashSet() }.add(canonical)
        }
    }

    /** The canonical id for a token id, or null when the token is not an admitted source. */
    fun resolve(tokenId: String): String? {
        canonicalByBoundId[tokenId]?.let { return it }
        if (tokenId in incompleteChunks) return null
        return canonicalByChunkId[tokenId]?.singleOrNull()
    }
}

private fun ChatCitationUi.identity(): SourceIdentity? {
    if (knowledgeBaseId.isBlank() || source.isBlank()) return null
    if (documentVersionId.isBlank() || chunkId.isBlank()) return null
    return SourceIdentity(knowledgeBaseId, source, documentVersionId, chunkId, assetId)
}

/** A citation-shaped token: the candidate id and the number of characters it spans. */
private class RawToken(val id: String, val length: Int)

private const val CITATION_PREFIX = "citation:"
private const val MAX_ID_LENGTH = 512

/**
 * A token candidate at [start].  The shape decides what may be a citation; the host binding
 * decides whether it is one.  A rejected candidate is always re-emitted verbatim.
 */
private fun tokenAt(text: String, start: Int): RawToken? {
    when (text[start]) {
        '[' -> {
            markdownCitationLinkAt(text, start)?.let { return it }
            val close = closingBracket(text, start + 1, ']') ?: return null
            // `[label](target)` is a link, not a bare id token.
            if (close + 1 < text.length && text[close + 1] == '(') return null
            return idTokenAt(text, start, start + 1, close)
        }
        '【' -> {
            val close = closingBracket(text, start + 1, '】') ?: return null
            return idTokenAt(text, start, start + 1, close)
        }
        else -> return null
    }
}

/** `[label](citation:ID)` - the label is dropped and the target id is the citation. */
private fun markdownCitationLinkAt(text: String, start: Int): RawToken? {
    val labelEnd = closingBracket(text, start + 1, ']') ?: return null
    if (labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
    val targetEnd = (labelEnd + 2 until minOf(text.length, labelEnd + MAX_ID_LENGTH + CITATION_PREFIX.length + 3))
        .firstOrNull { text[it] == ')' } ?: return null
    val target = text.substring(labelEnd + 2, targetEnd).trim()
    if (!target.startsWith(CITATION_PREFIX)) return null
    val id = target.removePrefix(CITATION_PREFIX)
    if (!isPlausibleId(id)) return null
    return RawToken(id, targetEnd - start + 1)
}

private fun idTokenAt(text: String, start: Int, bodyStart: Int, close: Int): RawToken? {
    val id = text.substring(bodyStart, close).removePrefix(CITATION_PREFIX)
    if (!isPlausibleId(id)) return null
    return RawToken(id, close + 1 - start)
}

/** Tool-generated ids (uuid, base64url) may carry `-`, `_`, `+`, `/`, `=`, `:` and `.`; never spaces. */
private fun isPlausibleId(id: String): Boolean {
    if (id.isEmpty() || id.length > MAX_ID_LENGTH) return false
    return id.none { it.isWhitespace() || it in "[]【】`()" }
}

private fun closingBracket(text: String, from: Int, close: Char): Int? {
    for (index in from until minOf(text.length, from + MAX_ID_LENGTH + CITATION_PREFIX.length + 1)) {
        when (text[index]) {
            close -> return index
            '\n', '[', ']', '【', '】' -> return null
            else -> {}
        }
    }
    return null
}

private class Rendered(val normalized: String, val copy: String)

/** Renders one fragment, assigning display numbers in first-appearance order. */
private fun renderText(text: String, sources: BoundSources, numbers: MutableMap<String, Int>): Rendered {
    val normalized = StringBuilder(text.length + 16)
    val copy = StringBuilder(text.length + 16)
    val lines = text.split("\n")
    val fences = fenceFlags(lines)
    lines.forEachIndexed { index, line ->
        if (index > 0) {
            normalized.append('\n')
            copy.append('\n')
        }
        if (fences[index]) {
            normalized.append(line)
            copy.append(line)
            return@forEachIndexed
        }
        val rendered = renderProseLine(line, sources, numbers)
        normalized.append(rendered.normalized)
        copy.append(rendered.copy)
    }
    return Rendered(normalized.toString(), copy.toString())
}

private fun renderProseLine(line: String, sources: BoundSources, numbers: MutableMap<String, Int>): Rendered {
    val normalized = StringBuilder(line.length)
    val copy = StringBuilder(line.length)
    var index = 0
    while (index < line.length) {
        val char = line[index]
        if (char == '`') {
            val end = skipInlineCode(line, index)
            normalized.append(line, index, end)
            copy.append(line, index, end)
            index = end
            continue
        }
        val token = if (char == '[' || char == '【') tokenAt(line, index) else null
        if (token == null) {
            normalized.append(char)
            copy.append(char)
            index++
            continue
        }
        val canonical = sources.resolve(token.id)
        if (canonical == null) {
            normalized.append(line, index, index + token.length)
            copy.append(line, index, index + token.length)
        } else {
            val number = numbers.getOrPut(canonical) { numbers.size + 1 }
            normalized.append('[').append(CITATION_PREFIX).append(canonical).append(']')
            copy.append('[').append(number).append(']')
        }
        index += token.length
    }
    return Rendered(normalized.toString(), copy.toString())
}

/** The canonical ids cited in [texts], used only to judge whether a tail repeats the body. */
private fun referencedCanonicals(texts: List<String>, sources: BoundSources): Set<String> {
    val found = LinkedHashSet<String>()
    texts.forEach { text ->
        val lines = text.split("\n")
        val fences = fenceFlags(lines)
        lines.forEachIndexed { index, line ->
            if (fences[index]) return@forEachIndexed
            var position = 0
            while (position < line.length) {
                val char = line[position]
                if (char == '`') {
                    position = skipInlineCode(line, position)
                    continue
                }
                val token = if (char == '[' || char == '【') tokenAt(line, position) else null
                if (token == null) {
                    position++
                    continue
                }
                sources.resolve(token.id)?.let(found::add)
                position += token.length
            }
        }
    }
    return found
}

/**
 * Drops a trailing source list whose every line is blank, a `来源`-style heading, or a single
 * already-referenced citation token.  Any other tail line - prose, an unknown or ambiguous id,
 * code - keeps the whole tail.
 *
 * To stay conservative a headingless tail is dropped only when it repeats at least two ids or
 * is the whole fragment; a lone citation line after prose is left alone.
 */
private fun stripTrailingSourceList(
    text: String,
    sources: BoundSources,
    referencedEarlier: Set<String>,
): String {
    val lines = text.split("\n")
    val fences = fenceFlags(lines)
    val sourceHeading = lines.indices.lastOrNull { !fences[it] && SOURCE_HEADING.matches(lines[it].trim()) }
    // Keep a declared source list whole if it carries prose, unknown ids or code.
    if (sourceHeading != null && (sourceHeading + 1 until lines.size).any { index ->
            val candidate = footerCandidate(lines[index], fences[index])
            candidate == null || (candidate.tokenId != null && sources.resolve(candidate.tokenId) == null)
        }) return text
    var start = lines.size
    var tokenLines = 0
    var heading = false
    while (start > 0) {
        val candidate = footerCandidate(lines[start - 1], fences[start - 1]) ?: break
        if (candidate.heading) heading = true
        if (candidate.tokenId != null) tokenLines++
        start--
    }
    if (tokenLines == 0) return text
    while (start < lines.size && lines[start].isBlank()) start++
    if (start >= lines.size) return text
    val referenced = referencedEarlier +
        referencedCanonicals(listOf(lines.subList(0, start).joinToString("\n")), sources)
    val canonicals = (start until lines.size).mapNotNull { index ->
        footerCandidate(lines[index], fences[index])?.tokenId?.let(sources::resolve)
    }
    if (canonicals.size != tokenLines) return text
    if (canonicals.any { it !in referenced }) return text
    val wholeFragment = lines.subList(0, start).all { it.isBlank() }
    if (!heading && !wholeFragment && canonicals.size < 2) return text
    return lines.subList(0, start).joinToString("\n").trimEnd()
}

private class FooterCandidate(val heading: Boolean, val tokenId: String?)

/** Blank, a `来源`-style heading, or a single token line (an optional list bullet is allowed). */
private fun footerCandidate(line: String, insideFence: Boolean): FooterCandidate? {
    if (insideFence || line.contains('`')) return null
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return FooterCandidate(heading = false, tokenId = null)
    if (SOURCE_HEADING.matches(trimmed)) return FooterCandidate(heading = true, tokenId = null)
    val body = LIST_BULLET.replaceFirst(trimmed, "").trim()
    val token = if (body.startsWith("[") || body.startsWith("【")) tokenAt(body, 0) else null
    if (token == null || token.length != body.length) return null
    return FooterCandidate(heading = false, tokenId = token.id)
}

private val SOURCE_HEADING = Regex(
    "^(?:#{1,6}\\s+)?[*_]{0,2}\\s*(?:来源|参考(?:来源|资料|文献)?|引用|文献|sources?|references?|citations?)\\s*[:：]?\\s*[*_]{0,2}$",
    RegexOption.IGNORE_CASE,
)
private val LIST_BULLET = Regex("^(?:[-*•]|\\d{1,3}[.)])\\s+")

/** True for a fence delimiter line or any line inside a fenced block. */
private fun fenceFlags(lines: List<String>): BooleanArray {
    val flags = BooleanArray(lines.size)
    var fenceChar: Char? = null
    var fenceLength = 0
    lines.forEachIndexed { index, line ->
        val run = fenceRun(line)
        val open = fenceChar
        if (open == null) {
            if (run != null) {
                fenceChar = run.first
                fenceLength = run.second
                flags[index] = true
            }
            return@forEachIndexed
        }
        flags[index] = true
        if (isClosingFence(line, open, fenceLength)) fenceChar = null
    }
    return flags
}

/** A leading run of at least three backticks or tildes, with an optional info string. */
private fun fenceRun(line: String): Pair<Char, Int>? {
    val trimmed = line.trimStart()
    if (trimmed.length < 3) return null
    val char = trimmed[0]
    if (char != '`' && char != '~') return null
    var length = 0
    while (length < trimmed.length && trimmed[length] == char) length++
    return if (length >= 3) char to length else null
}

private fun isClosingFence(line: String, fenceChar: Char, fenceLength: Int): Boolean {
    val run = fenceRun(line) ?: return false
    if (run.first != fenceChar || run.second < fenceLength) return false
    return line.trim().length == run.second
}

/** Inline code is copied verbatim up to the matching backtick run, or to the end of the line. */
private fun skipInlineCode(line: String, start: Int): Int {
    val run = backtickRun(line, start)
    var index = start + run
    while (index < line.length) {
        if (line[index] == '`') {
            val candidate = backtickRun(line, index)
            if (candidate == run) return index + run
            index += candidate
        } else {
            index++
        }
    }
    return line.length
}

private fun backtickRun(line: String, start: Int): Int {
    var index = start
    while (index < line.length && line[index] == '`') index++
    return index - start
}
