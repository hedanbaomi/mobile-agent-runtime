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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

internal data class MarkdownBlock(val kind: String, val text: String, val level: Int = 0)

/** Local presentation only: no HTML, external resource loading or changes to stored/model text. */
internal fun markdownBlocks(source: String): List<MarkdownBlock> {
    val lines = source.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        when {
            line.trimStart().startsWith("```") -> {
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) code += lines[index++]
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

internal fun markdownInline(text: String) = buildAnnotatedString {
    val pattern = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|\\*([^*]+)\\*")
    var cursor = 0
    pattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val code = match.groups[1]?.value
        val bold = match.groups[2]?.value ?: match.groups[3]?.value
        val italic = match.groups[4]?.value
        withStyle(when {
            code != null -> SpanStyle(fontFamily = FontFamily.Monospace)
            bold != null -> SpanStyle(fontWeight = FontWeight.Bold)
            else -> SpanStyle(fontStyle = FontStyle.Italic)
        }) { append(code ?: bold ?: italic.orEmpty()) }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { markdownBlocks(text) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block.kind) {
                "code" -> Text(block.text, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        .horizontalScroll(rememberScrollState()).padding(8.dp))
                "table" -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                    block.text.lines().forEachIndexed { rowIndex, row ->
                        Row {
                            row.trim().removePrefix("|").removeSuffix("|").split('|').forEach { cell ->
                                Text(markdownInline(cell.trim()), fontWeight = if (rowIndex == 0) FontWeight.Bold else null,
                                    modifier = Modifier.width(160.dp).padding(6.dp))
                            }
                        }
                    }
                }
                "heading" -> Text(markdownInline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                "quote" -> Text(markdownInline(block.text), modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
                else -> Text(markdownInline(block.text))
            }
        }
    }
}
