// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class MarkdownBlock(val kind: String, val text: String, val level: Int = 0)

/** Local presentation only: no HTML, external resource loading or changes to stored/model text. */
internal fun markdownBlocks(source: String): List<MarkdownBlock> {
    val lines = source.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        when {
            line.trimStart().matches(Regex("(`{3,}|~{3,}).*")) -> {
                val fence = line.trimStart().takeWhile { it == line.trimStart().first() }
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].trim().matches(
                        Regex("${Regex.escape(fence.first().toString())}{${fence.length},}\\s*"))) code += lines[index++]
                if (index < lines.size) index++
                blocks += MarkdownBlock("code", code.joinToString("\n"))
            }
            index + 1 < lines.size && line.contains('|') &&
                lines[index + 1].trim().matches(Regex("\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?")) -> {
                val rows = mutableListOf(line)
                index += 2
                while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) rows += lines[index++]
                blocks += MarkdownBlock("table", rows.joinToString("\n"))
            }
            else -> {
                val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
                blocks += when {
                    heading != null -> MarkdownBlock("heading", heading.groupValues[2], heading.groupValues[1].length)
                    line.trimStart().startsWith("> ") -> MarkdownBlock("quote", line.trimStart().removePrefix("> "))
                    line.matches(Regex("^\\s*[-*+]\\s+.*")) -> MarkdownBlock("text", line.replaceFirst(Regex("^(\\s*)[-*+]\\s+"), "$1• "))
                    else -> MarkdownBlock("text", line)
                }
                index++
            }
        }
    }
    // A long plain answer remains one text layout instead of one composable per source line.
    val grouped = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            grouped += MarkdownBlock("text", paragraph.joinToString("\n"))
            paragraph.clear()
        }
    }
    blocks.forEach { block ->
        if (block.kind == "text") paragraph += block.text
        else { flushParagraph(); grouped += block }
    }
    flushParagraph()
    return grouped
}

private val inlinePattern = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|\\*([^*]+)\\*")

internal fun markdownInline(
    text: String,
    markers: Map<String, ReplyCitationMarker> = emptyMap(),
    linkColor: Color = Color.Unspecified,
    onCitation: (String) -> Unit = {},
) = buildAnnotatedString {
    fun appendCitations(value: String) {
        var start = 0
        Regex("\\[citation:([^\\]\\n]+)]").findAll(value).forEach { match ->
            append(value.substring(start, match.range.first))
            val marker = markers[match.groupValues[1]]
            if (marker == null) append(match.value) else {
                pushLink(LinkAnnotation.Clickable(
                    tag = "citation:${marker.citationId}",
                    styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)),
                    linkInteractionListener = { onCitation(marker.citationId) },
                ))
                withStyle(SpanStyle(fontSize = 12.sp, baselineShift = BaselineShift.Superscript)) {
                    append("[${marker.number}]")
                }
                pop()
            }
            start = match.range.last + 1
        }
        append(value.substring(start))
    }
    var cursor = 0
    inlinePattern.findAll(text).forEach { match ->
        appendCitations(text.substring(cursor, match.range.first))
        val code = match.groups[1]?.value
        val bold = match.groups[2]?.value ?: match.groups[3]?.value
        val italic = match.groups[4]?.value
        withStyle(when {
            code != null -> SpanStyle(fontFamily = FontFamily.Monospace)
            bold != null -> SpanStyle(fontWeight = FontWeight.Bold)
            else -> SpanStyle(fontStyle = FontStyle.Italic)
        }) {
            if (code != null) append(code) else appendCitations(bold ?: italic.orEmpty())
        }
        cursor = match.range.last + 1
    }
    appendCitations(text.substring(cursor))
}

@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    markers: Map<String, ReplyCitationMarker> = emptyMap(),
    onCitation: (String) -> Unit = {},
) {
    val blocks = remember(text) { markdownBlocks(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier) {
        blocks.forEach { block ->
            when (block.kind) {
                "code" -> Text(block.text, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        .horizontalScroll(rememberScrollState()).padding(8.dp))
                "table" -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                    val rows = remember(block.text, markers, linkColor, onCitation) {
                        block.text.lines().map { row ->
                            row.trim().removePrefix("|").removeSuffix("|").split('|').map { markdownInline(it.trim(), markers, linkColor, onCitation) }
                        }
                    }
                    rows.forEachIndexed { rowIndex, cells ->
                        Row {
                            cells.forEach { cell ->
                                Text(cell, fontWeight = if (rowIndex == 0) FontWeight.Bold else null,
                                    modifier = Modifier.width(160.dp).padding(6.dp))
                            }
                        }
                    }
                }
                "heading" -> Text(remember(block.text, markers, linkColor, onCitation) { markdownInline(block.text, markers, linkColor, onCitation) },
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                "quote" -> Text(remember(block.text, markers, linkColor, onCitation) { markdownInline(block.text, markers, linkColor, onCitation) }, modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
                else -> Text(remember(block.text, markers, linkColor, onCitation) { markdownInline(block.text, markers, linkColor, onCitation) })
            }
        }
    }
}
