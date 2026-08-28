// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object OfficeParser {
    const val DOCX_FINGERPRINT = "docx-xml-v1"
    const val EPUB_FINGERPRINT = "epub-xml-v1"

    fun parse(fileName: String, bytes: ByteArray): ParsedPublication {
        val inspection = ZipSafety.inspect(bytes)
        if (!inspection.ok) error(inspection.reason)
        val entries = readEntries(bytes)
        val names = entries.keys.map { it.lowercase() }
        return when {
            names.any { it == "word/document.xml" || it.endsWith("/word/document.xml") } -> parseDocx(entries)
            names.any { it == "meta-inf/container.xml" || it == "mimetype" } -> parseEpub(entries)
            fileName.lowercase().endsWith(".docx") -> parseDocx(entries)
            fileName.lowercase().endsWith(".epub") -> parseEpub(entries)
            else -> error("Archive is not a DOCX or EPUB package")
        }
    }

    private fun parseDocx(entries: Map<String, ByteArray>): ParsedPublication {
        val document = entries.entries.firstOrNull { it.key.lowercase() == "word/document.xml" }?.value
            ?: error("DOCX is missing word/document.xml")
        val xml = String(document, Charsets.UTF_8)
        if (xml.contains("<w:instrText") && xml.contains("MACRO") ) {
            error("DOCX macros are not executed")
        }
        val paragraphs = Regex("<w:p[\\s\\S]*?</w:p>").findAll(xml).toList()
        val pages = mutableListOf<ExtractedPage>()
        val assets = mutableListOf<ExtractedAsset>()
        val textParts = mutableListOf<String>()
        paragraphs.forEachIndexed { index, match ->
            val paraXml = match.value
            val text = Regex("<w:t[^>]*>([\\s\\S]*?)</w:t>").findAll(paraXml)
                .joinToString("") { unescapeXml(it.groupValues[1]) }
                .trim()
            if (text.isNotEmpty()) {
                textParts += text
                pages += ExtractedPage(index + 1, text, needsVision = false)
            }
            Regex("r:embed=\"([^\"]+)\"").findAll(paraXml).forEach { rel ->
                val media = findMedia(entries, rel.groupValues[1])
                if (media != null) {
                    assets += ExtractedAsset(
                        localId = rel.groupValues[1],
                        kind = "IMAGE",
                        page = index + 1,
                        section = "paragraph-${index + 1}",
                        bytes = media.second,
                        mediaType = guessImageType(media.first),
                        surroundingText = text,
                    )
                }
            }
        }
        if (textParts.isEmpty() && assets.isEmpty()) error("DOCX has no extractable text or images")
        val mediaFiles = entries.filter { it.key.lowercase().startsWith("word/media/") }
        mediaFiles.forEach { (name, payload) ->
            if (assets.none { it.bytes.contentEquals(payload) }) {
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
        return ParsedPublication(
            format = SourceFormat.OFFICE_ARCHIVE,
            text = textParts.joinToString("\n"),
            pages = pages.ifEmpty { listOf(ExtractedPage(1, textParts.joinToString("\n"), assets.isNotEmpty())) },
            assets = assets,
            needsVision = assets.isNotEmpty(),
            parserFingerprint = DOCX_FINGERPRINT,
        )
    }

    private fun parseEpub(entries: Map<String, ByteArray>): ParsedPublication {
        val xhtml = entries.filter { (name, _) ->
            val lower = name.lowercase()
            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
        }
        if (xhtml.isEmpty()) error("EPUB has no HTML documents")
        val pages = mutableListOf<ExtractedPage>()
        val assets = mutableListOf<ExtractedAsset>()
        val texts = mutableListOf<String>()
        xhtml.entries.sortedBy { it.key }.forEachIndexed { index, (name, bytes) ->
            val html = String(bytes, Charsets.UTF_8)
            val stripped = html
                .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
                .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                .replace(Regex("(?is)<[^>]+>"), " ")
                .replace(Regex("&nbsp;"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (stripped.isNotEmpty()) {
                texts += stripped
                pages += ExtractedPage(index + 1, stripped, needsVision = false)
            }
            Regex("(?is)<img[^>]+src\\s*=\\s*\"([^\"]+)\"").findAll(html).forEach { img ->
                val src = img.groupValues[1].substringAfterLast('/').lowercase()
                val media = entries.entries.firstOrNull { it.key.lowercase().endsWith(src) }
                if (media != null) {
                    assets += ExtractedAsset(
                        localId = media.key.substringAfterLast('/'),
                        kind = "IMAGE",
                        page = index + 1,
                        section = name,
                        bytes = media.value,
                        mediaType = guessImageType(media.key),
                        surroundingText = stripped,
                    )
                }
            }
        }
        entries.filter { it.key.lowercase().contains("/images/") || imageName(it.key) }.forEach { (name, payload) ->
            if (assets.none { it.bytes.contentEquals(payload) }) {
                assets += ExtractedAsset(
                    localId = name.substringAfterLast('/'),
                    kind = "IMAGE",
                    page = null,
                    section = name,
                    bytes = payload,
                    mediaType = guessImageType(name),
                    surroundingText = texts.lastOrNull().orEmpty(),
                )
            }
        }
        if (texts.isEmpty() && assets.isEmpty()) error("EPUB has no extractable text or images")
        return ParsedPublication(
            format = SourceFormat.OFFICE_ARCHIVE,
            text = texts.joinToString("\n"),
            pages = pages.ifEmpty { listOf(ExtractedPage(1, texts.joinToString("\n"), assets.isNotEmpty())) },
            assets = assets,
            needsVision = assets.isNotEmpty(),
            parserFingerprint = EPUB_FINGERPRINT,
        )
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                out[name] = zip.readBytes()
            }
        }
        return out
    }

    private fun findMedia(entries: Map<String, ByteArray>, embed: String): Pair<String, ByteArray>? {
        val rels = entries.entries.firstOrNull { it.key.lowercase() == "word/_rels/document.xml.rels" }?.value
        if (rels != null) {
            val xml = String(rels, Charsets.UTF_8)
            val target = Regex("Id=\"${Regex.escape(embed)}\"[^>]*Target=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
                ?: Regex("Target=\"([^\"]+)\"[^>]*Id=\"${Regex.escape(embed)}\"").find(xml)?.groupValues?.get(1)
            if (target != null) {
                val path = if (target.startsWith("/")) target.drop(1) else "word/" + target.removePrefix("../")
                val hit = entries.entries.firstOrNull { it.key.replace('\\', '/').equals(path, ignoreCase = true) }
                    ?: entries.entries.firstOrNull { it.key.endsWith(target.substringAfterLast('/')) }
                if (hit != null) return hit.toPair()
            }
        }
        return entries.entries.firstOrNull { it.key.contains(embed, ignoreCase = true) }?.toPair()
    }

    private fun guessImageType(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun imageName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".webp")
    }

    private fun unescapeXml(value: String): String =
        value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&apos;", "'")
}
