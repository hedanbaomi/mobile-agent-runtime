// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

internal object EpubPublication {
    fun parse(entries: Map<String, ByteArray>): ParsedPublication {
        val spine = EpubPackage.read(entries)
        val pages = mutableListOf<ExtractedPage>()
        val assets = mutableListOf<ExtractedAsset>()
        val texts = mutableListOf<String>()
        spine.forEachIndexed { position, document ->
            val chapter = EpubText.scan(document.bytes)
            val stripped = chapter.text
            if (stripped.isNotEmpty()) texts += stripped
            pages += ExtractedPage(position + 1, stripped, needsVision = chapter.visuals.isNotEmpty())
            chapter.visuals.forEachIndexed { visualIndex, visual ->
                assets += if (visual.supportedImage) {
                    epubImageAsset(entries, visual.source, position + 1, document.path, stripped)
                } else {
                    ExtractedAsset("${document.path}#visual-$visualIndex", "UNSUPPORTED", position + 1, document.path,
                        ByteArray(0), "application/octet-stream", stripped)
                }
            }
        }
        entries.filter { it.key.lowercase().contains("/images/") || imageName(it.key) || it.key.lowercase().endsWith(".svg") }
            .forEach { (name, payload) ->
                if (assets.none { it.bytes.contentEquals(payload) }) {
                    assets += ExtractedAsset(
                        localId = name.substringAfterLast('/'),
                        kind = if (imageName(name)) "IMAGE" else "UNSUPPORTED",
                        page = null,
                        section = name,
                        bytes = if (imageName(name)) payload else ByteArray(0),
                        mediaType = guessImageType(name),
                        surroundingText = texts.lastOrNull().orEmpty(),
                    )
                }
            }
        if (texts.isEmpty() && assets.isEmpty()) error("EPUB has no extractable text or images")
        val needsVision = assets.any { it.kind == "IMAGE" || it.kind == "EXTERNAL" || it.kind == "MISSING" || it.kind == "UNSUPPORTED" }
        return ParsedPublication(
            format = SourceFormat.OFFICE_ARCHIVE,
            text = texts.joinToString("\n"),
            pages = pages.ifEmpty { listOf(ExtractedPage(1, texts.joinToString("\n"), needsVision)) },
            assets = assets,
            needsVision = needsVision,
            parserFingerprint = OfficeParser.EPUB_FINGERPRINT,
        )
    }

    private fun epubImageAsset(
        entries: Map<String, ByteArray>,
        src: String,
        page: Int,
        section: String,
        surroundingText: String,
    ): ExtractedAsset {
        val trimmed = src.trim()
        if (trimmed.startsWith("//") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(trimmed)) {
            return ExtractedAsset(trimmed, "EXTERNAL", page, section, ByteArray(0), "image/*", surroundingText)
        }
        val resolved = resolvePackagePath(section, trimmed) ?: return ExtractedAsset(
            trimmed, "MISSING", page, section, ByteArray(0), "image/*", surroundingText,
        )
        val media = entries.entries.firstOrNull { it.key == resolved }
            ?: return ExtractedAsset(resolved, "MISSING", page, section, ByteArray(0), "image/*", surroundingText)
        return if (imageName(media.key)) {
            ExtractedAsset(media.key, "IMAGE", page, section, media.value, guessImageType(media.key), surroundingText)
        } else {
            ExtractedAsset(media.key, "UNSUPPORTED", page, section, ByteArray(0), guessImageType(media.key), surroundingText)
        }
    }

}
