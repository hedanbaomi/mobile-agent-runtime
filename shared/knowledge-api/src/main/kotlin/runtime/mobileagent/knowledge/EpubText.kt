// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal data class EpubVisual(val source: String, val supportedImage: Boolean)
internal data class EpubChapter(val text: String, val visuals: List<EpubVisual>)
internal object EpubText {
    /** XHTML is parsed without DTDs, entity resolution or any external resource access. */
    fun scan(bytes: ByteArray): EpubChapter {
        if (bytes.size > MAX_PACKAGE_BYTES) error("EPUB content document exceeds the size limit")
        val raw = decodeUtf8(bytes, "EPUB content document").removePrefix("\uFEFF")
        val handler = EpubTextHandler()
        expectXml(handler, expandHtmlEntities(prepareXhtml(raw)))
        return handler.finish()
    }

    private class EpubTextHandler : DefaultHandler() {
        private val text = StringBuilder()
        private val visuals = mutableListOf<EpubVisual>()
        private var depth = 0
        private var hiddenDepth = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            depth += 1
            if (depth > MAX_XML_DEPTH) throw SAXException("depth limit")
            if (hiddenDepth != 0) return
            text.append(' ')
            when (localName(localName, qName)) {
                "script", "style" -> hiddenDepth = depth
                "img" -> visuals += EpubVisual(attributes.getValue("src").orEmpty(), true)
                "svg", "object", "embed", "canvas", "math", "video", "audio", "iframe" -> {
                    visuals += EpubVisual("", false)
                    hiddenDepth = depth
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (hiddenDepth == depth) hiddenDepth = 0
            if (hiddenDepth == 0) text.append(' ')
            depth -= 1
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            if (hiddenDepth == 0) appendVisible(text, chars, start, length)
        }

        fun finish(): EpubChapter = EpubChapter(normalize(text), visuals)
    }

}
