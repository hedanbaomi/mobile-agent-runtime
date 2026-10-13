// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.helpers.DefaultHandler

internal const val MAX_XML_DEPTH = 256
internal const val MAX_PACKAGE_BYTES = 4 * 1024 * 1024
internal const val MAX_PACKAGE_ITEMS = 4096
internal const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
internal const val OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val OPC_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
internal const val OPF_NS = "http://www.idpf.org/2007/opf"

/** Independent input/DTD/depth gates also apply on Android SAX implementations. */
internal fun expectXml(handler: DefaultHandler, source: String) {
    if (source.length > MAX_PACKAGE_BYTES || source.contains("<!DOCTYPE") || source.contains("<!ENTITY")) {
        error("XML part is malformed or exceeds supported limits")
    }
    try {
        val reader = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
        }.newSAXParser().xmlReader
        // Android does not implement Xerces-specific feature URIs. The lexical
        // DTD rejection above and rejecting resolver remain unconditional.
        listOf("http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd").forEach { feature ->
            try { reader.setFeature(feature, false) }
            catch (_: SAXNotRecognizedException) { }
            catch (_: SAXNotSupportedException) { }
        }
        reader.contentHandler = object : DefaultHandler() {
            private var depth = 0
            override fun startElement(uri: String?, local: String?, qName: String?, attrs: Attributes) {
                depth += 1
                if (depth > MAX_XML_DEPTH) throw SAXException("depth limit")
                handler.startElement(uri, local, qName, attrs)
            }
            override fun endElement(uri: String?, local: String?, qName: String?) {
                handler.endElement(uri, local, qName)
                depth -= 1
            }
            override fun characters(chars: CharArray, start: Int, length: Int) = handler.characters(chars, start, length)
        }
        reader.errorHandler = object : DefaultHandler() {
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        }
        reader.entityResolver = EntityResolver { _, _ -> throw SAXException("external entities disabled") }
        reader.parse(InputSource(StringReader(source)))
    } catch (_: Exception) {
        error("XML part is malformed or exceeds supported limits")
    }
}

/** Strip only the inert HTML5 doctype in the prolog; never enable DTD parsing. */
internal fun prepareXhtml(source: String): String {
    val prolog = Regex("\\A(\\s*(?:<\\?xml[^?]*\\?>\\s*)?)<!DOCTYPE html>\\s*")
    val match = prolog.find(source)
    val cleaned = if (match == null) source else source.replaceRange(match.range, match.groupValues[1])
    if (cleaned.contains("<!DOCTYPE") || cleaned.contains("<!ENTITY")) error("XML part is malformed or exceeds supported limits")
    return cleaned
}
