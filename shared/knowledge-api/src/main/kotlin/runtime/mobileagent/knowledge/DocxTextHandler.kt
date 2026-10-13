// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal data class DocxExtraction(val pages: List<ExtractedPage>, val assets: List<ExtractedAsset>)
/**
 * Walks `word/document.xml`. Paragraph text is the concatenation of
 * WordprocessingML `t` runs, with `tab` mapped to a separator and `br`/`cr`
 * to a line break. Only the WordprocessingML namespace produces text, so
 * `pPr` properties, foreign-namespace elements or a decoy `t` can never be
 * folded into a paragraph.
 */
internal class DocxTextHandler(
    private val onRelationship: (relId: String, page: Int, text: String) -> ExtractedAsset,
) : DefaultHandler() {
    private val pages = mutableListOf<ExtractedPage>()
    private val assets = mutableListOf<ExtractedAsset>()
    private val paragraph = StringBuilder()
    private val relationshipIds = mutableListOf<String>()
    private var lastText = ""
    private var index = 0
    private var depth = 0
    private var hiddenDepth = 0
    private var textDepth = 0

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        depth += 1
        if (depth > MAX_XML_DEPTH) throw SAXException("depth limit")
        if (hiddenDepth != 0) return
        val name = localName(localName, qName)
        val word = uri == WORD_NS
        when {
            word && name == "t" -> textDepth += 1
            word && name == "tab" -> paragraph.append(' ')
            word && (name == "br" || name == "cr") -> paragraph.append('\n')
            word && (name == "pPr" || name == "rPr") -> hiddenDepth = depth
        }
        val image = (uri == "http://schemas.openxmlformats.org/drawingml/2006/main" && name == "blip") ||
            (uri == "urn:schemas-microsoft-com:vml" && name == "imagedata")
        if (!image) return
        repeat(attributes.length) { position ->
            val attribute = localName(attributes.getLocalName(position), attributes.getQName(position))
            if (attribute != "embed" && attribute != "link" && attribute != "id") return@repeat
            if (attributes.getURI(position) != OFFICE_REL_NS) return@repeat
            relationshipIds += attributes.getValue(position)
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        if (hiddenDepth == depth) hiddenDepth = 0
        if (hiddenDepth == 0) {
            val name = localName(localName, qName)
            if (uri == WORD_NS && name == "t") textDepth = (textDepth - 1).coerceAtLeast(0)
            if (uri == WORD_NS && name == "p") flushParagraph()
        }
        depth -= 1
    }

    override fun characters(chars: CharArray, start: Int, length: Int) {
        if (hiddenDepth == 0 && textDepth > 0) appendVisible(paragraph, chars, start, length)
    }

    private fun flushParagraph() {
        val text = normalize(paragraph, preserveBreaks = true)
        paragraph.setLength(0)
        if (text.isEmpty() && relationshipIds.isEmpty()) { index += 1; return }
        index += 1
        pages += ExtractedPage(index, text, needsVision = relationshipIds.isNotEmpty())
        relationshipIds.forEach { relId -> assets += onRelationship(relId, index, text.ifEmpty { lastText }) }
        if (text.isNotEmpty()) lastText = text
        relationshipIds.clear()
    }

    fun finish(): DocxExtraction {
        if (paragraph.isNotEmpty() || relationshipIds.isNotEmpty()) flushParagraph()
        return DocxExtraction(pages, assets)
    }
}
