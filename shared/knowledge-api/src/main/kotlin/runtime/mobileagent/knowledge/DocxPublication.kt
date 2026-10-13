// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

internal object DocxPublication {
    private data class Relationship(val target: String, val external: Boolean)

    fun parse(entries: Map<String, ByteArray>): ParsedPublication {
        val document = entries.entries.firstOrNull { it.key.lowercase() == "word/document.xml" }?.value
            ?: error("DOCX is missing word/document.xml")
        val raw = decodeUtf8(document, "DOCX document.xml")
        val relationships = documentRelationships(entries)
        val handler = DocxTextHandler { relId, page, text -> docxAsset(entries, relationships, relId, page, text) }
        expectXml(handler, raw)
        val extracted = handler.finish()
        val pages = extracted.pages
        val assets = extracted.assets.toMutableList()
        val textParts = pages.map { it.text }
        if (textParts.isEmpty() && assets.isEmpty()) error("DOCX has no extractable text or images")
        entries.filter { it.key.lowercase().startsWith("word/media/") }.forEach { (name, payload) ->
            if (assets.none { it.bytes.isNotEmpty() && it.bytes.contentEquals(payload) }) {
                assets += ExtractedAsset(
                    localId = name.substringAfterLast('/'),
                    kind = "IMAGE",
                    page = null,
                    section = name,
                    bytes = payload,
                    mediaType = guessImageType(name),
                    surroundingText = textParts.lastOrNull().orEmpty(),
                )
            }
        }
        val needsVision = assets.any { it.kind == "IMAGE" || it.kind == "EXTERNAL" || it.kind == "MISSING" }
        return ParsedPublication(
            format = SourceFormat.OFFICE_ARCHIVE,
            text = textParts.joinToString("\n"),
            pages = pages.ifEmpty { listOf(ExtractedPage(1, textParts.joinToString("\n"), needsVision)) },
            assets = assets,
            needsVision = needsVision,
            parserFingerprint = OfficeParser.DOCX_FINGERPRINT,
        )
    }

    /** Reads `word/_rels/document.xml.rels`; relationship ids come from real elements only. */
    private fun documentRelationships(entries: Map<String, ByteArray>): Map<String, Relationship> {
        val payload = entries.entries.firstOrNull { it.key.lowercase() == "word/_rels/document.xml.rels" }?.value
            ?: return emptyMap()
        val handler = object : DefaultHandler() {
            val relationships = linkedMapOf<String, Relationship>()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (localName(localName, qName) != "Relationship") return
                if (uri != null && uri.isNotEmpty() && uri != OPC_REL_NS) return
                val id = attributes.getValue("Id") ?: return
                require(relationships.size < MAX_PACKAGE_ITEMS && !relationships.containsKey(id)) { "Invalid DOCX relationship identities" }
                relationships[id] = Relationship(
                    target = attributes.getValue("Target").orEmpty(),
                    external = attributes.getValue("TargetMode").equals("External", ignoreCase = true),
                )
            }
        }
        expectXml(handler, decodeUtf8(payload, "DOCX document relationships"))
        return handler.relationships
    }

    /**
     * A relationship whose id is absent stays `MISSING` rather than being
     * matched by a path fragment: a relationship id is an identity, not a
     * filename hint.
     */
    /**
     * A relationship id is an identity, not a filename hint: an unknown id
     * stays `MISSING` instead of being matched by a path fragment. When the
     * relationship resolves, its bytes are always read, even if the paragraph
     * also carries text, so a drawing can never become a metadata-only record.
     */
    private fun docxAsset(
        entries: Map<String, ByteArray>,
        relationships: Map<String, Relationship>,
        relId: String,
        page: Int,
        surroundingText: String,
    ): ExtractedAsset {
        val relationship = relationships[relId]
            ?: return missingDocxAsset(relId, page, surroundingText)
        val target = relationship.target.trim()
        val external = relationship.external || target.startsWith("//") ||
            Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(target)
        if (external) {
            return ExtractedAsset(relId, "EXTERNAL", page, "paragraph-$page", ByteArray(0), "image/*", surroundingText)
        }
        val resolved = resolvePackagePath("word/document.xml", target)
            ?: return missingDocxAsset(relId, page, surroundingText)
        val hit = entries.entries.firstOrNull { it.key == resolved }
            ?: return missingDocxAsset(relId, page, surroundingText)
        return ExtractedAsset(
            localId = relId,
            kind = "IMAGE",
            page = page,
            section = "paragraph-$page",
            bytes = hit.value,
            mediaType = guessImageType(hit.key),
            surroundingText = surroundingText,
        )
    }

    private fun missingDocxAsset(relId: String, page: Int, surroundingText: String): ExtractedAsset =
        ExtractedAsset(relId, "MISSING", page, "paragraph-$page", ByteArray(0), "image/*", surroundingText)

}
