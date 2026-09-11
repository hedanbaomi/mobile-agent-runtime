// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

object PdfParser {
    const val FINGERPRINT = "pdf-text-v14-pdfrenderer"

    private const val MAX_PDF_STREAM_BYTES = 32 * 1024 * 1024

    /**
     * Render one already-classified page without retaining a list of rendered
     * pages in the parser. Repository imports use this after consent and
     * process the returned image immediately.
     */
    fun renderPage(bytes: ByteArray, rasterizer: PdfPageRasterizer, page: Int): RenderedPdfPage? {
        require(page > 0) { "PDF page numbers are one-based" }
        if (bytes.size < 5 || String(bytes.copyOfRange(0, 5), Charsets.ISO_8859_1) != "%PDF-") {
            error("Not a PDF")
        }
        return runCatching {
            rasterizer.render(bytes, listOf(page)).firstOrNull {
                it.page == page && it.bytes.isNotEmpty()
            }
        }.getOrNull()
    }

    fun parse(bytes: ByteArray, rasterizer: PdfPageRasterizer? = null): ParsedPublication {
        if (bytes.size < 5 || String(bytes.copyOfRange(0, 5), Charsets.ISO_8859_1) != "%PDF-") {
            error("Not a PDF")
        }
        val latin = String(bytes, Charsets.ISO_8859_1)
        val objects = extractIndirectObjects(bytes, latin)
        val pageNumbers = pageKids(objects).ifEmpty {
            objects.filter { (_, obj) -> isPageDict(obj.dict) }.keys.sorted()
        }
        val imageObjects = objects.filter { (_, obj) -> isImageDict(obj.dict) }
        val assets = mutableListOf<ExtractedAsset>()
        val pages = mutableListOf<ExtractedPage>()
        var imageOrdinal = 0
        val assignedImages = mutableSetOf<Int>()

        // Text extraction and visual classification happen before rasterizing.
        // Rendering only the pages that need visual evidence keeps a text-only
        // PDF cheap and leaves renderer failure visible through PAGE blockers.
        val pagesNeedingRaster = pageNumbers.mapNotNull { objNum ->
            val pageObj = objects[objNum] ?: return@mapNotNull null
            val content = pageContent(objects, pageObj.dict)
            val decoded = content.bytes
            val pageLatin = String(decoded, Charsets.ISO_8859_1)
            val fonts = pageFonts(objects, objNum, pageObj.dict)
            val extracted = extractPdfStrings(decoded, fonts)
            val text = extracted.joined()
            val hasInline = hasInlineImage(pageLatin)
            val resolvedXObjects = pageXObjects(objects, objNum, pageObj.dict)
            val hasUnresolvedXObjects = resolvedXObjects.unresolved ||
                hasUnresolvedXObjectDo(pageLatin, resolvedXObjects.entries)
            val hasImages = resolvedXObjects.entries.isNotEmpty() || hasUnresolvedXObjects || hasInline ||
                Regex("/Subtype\\s*/Image").containsMatchIn(pageObj.dict)
            val hasDrawing = hasVectorDrawing(pageLatin)
            if (pageNeedsVision(text, extracted.complete, content.complete, hasImages, hasDrawing)) {
                pageNumbers.indexOf(objNum) + 1
            } else {
                null
            }
        }
        val renderedPages = rasterizer?.let { renderer ->
            runCatching { renderer.render(bytes, pagesNeedingRaster.distinct()) }
                .getOrDefault(emptyList())
                .associateBy { it.page }
        }.orEmpty()
        pageNumbers.forEachIndexed { index, objNum ->
            val pageObj = objects[objNum] ?: return@forEachIndexed
            val pageIndex = index + 1
            val content = pageContent(objects, pageObj.dict)
            val decoded = content.bytes
            val pageLatin = String(decoded, Charsets.ISO_8859_1)
            val fonts = pageFonts(objects, objNum, pageObj.dict)
            val extracted = extractPdfStrings(decoded, fonts)
            val text = extracted.joined()
            val resolvedXObjects = pageXObjects(objects, objNum, pageObj.dict)
            val xobjects = resolvedXObjects.entries
            val hasUnresolvedXObjects = resolvedXObjects.unresolved ||
                hasUnresolvedXObjectDo(pageLatin, xobjects)
            val hasDrawing = hasVectorDrawing(pageLatin)
            var hasUnsupportedPageVisual = hasDrawing || !content.complete || hasUnresolvedXObjects ||
                !extracted.complete
            xobjects.forEach { (name, imageObjNum) ->
                val image = objects[imageObjNum]
                if (image == null || !isImageDict(image.dict) || image.stream == null) {
                    hasUnsupportedPageVisual = true
                    return@forEach
                }
                assignedImages += imageObjNum
                val payload = image.stream
                val mediaType = xObjectMediaType(image.dict, payload)
                if (mediaType == null) {
                    hasUnsupportedPageVisual = true
                    return@forEach
                }
                imageOrdinal += 1
                val usedOnPage = pageLatin.contains("/$name") || Regex("/${Regex.escape(name)}\\s+Do").containsMatchIn(pageLatin)
                assets += ExtractedAsset(
                    localId = "img-$imageOrdinal",
                    kind = "IMAGE",
                    page = if (usedOnPage || xobjects.size == 1) pageIndex else pageIndex,
                    section = name,
                    bytes = payload,
                    mediaType = mediaType,
                    surroundingText = text,
                )
            }
            // Inline image payloads are not necessarily standalone image files
            // (for example, raw RGB samples).  When a renderer is available the
            // complete page PNG is the authoritative visual attachment.  Keep
            // a source payload only when it is already a standalone encoded
            // image and no complete page image was supplied.  Raw RGB samples
            // are deliberately left behind as a PAGE blocker instead of being
            // sent to a Vision backend with a false image MIME type.
            val inlinePayloads = extractInlineImages(decoded)
            if (rasterizer == null || renderedPages[pageIndex] == null) {
                if (hasInlineImage(pageLatin) && inlinePayloads.isEmpty()) hasUnsupportedPageVisual = true
                inlinePayloads.forEach { payload ->
                    val mediaType = encodedImageMediaType(payload)
                    if (mediaType == null) {
                        hasUnsupportedPageVisual = true
                        return@forEach
                    }
                    imageOrdinal += 1
                    assets += ExtractedAsset(
                        localId = "inline-$imageOrdinal",
                        kind = "IMAGE",
                        page = pageIndex,
                        section = "inline",
                        bytes = payload,
                        mediaType = mediaType,
                        surroundingText = text,
                    )
                }
            }
            val hasInline = hasInlineImage(pageLatin)
            val hasImages = xobjects.isNotEmpty() || hasUnresolvedXObjects || hasInline ||
                Regex("/Subtype\\s*/Image").containsMatchIn(pageObj.dict)
            val needsVision = pageNeedsVision(text, extracted.complete, content.complete, hasImages, hasDrawing)
            pages += ExtractedPage(pageIndex, text, needsVision)
            val rendered = renderedPages[pageIndex]?.takeIf { it.bytes.isNotEmpty() }
            if (rendered != null) {
                assets += ExtractedAsset(
                    localId = "page-rendered-$pageIndex",
                    kind = "IMAGE",
                    page = pageIndex,
                    section = "pdf-page-$pageIndex",
                    bytes = rendered.bytes,
                    mediaType = rendered.mediaType.ifBlank { "image/png" },
                    surroundingText = text,
                )
            }
            val lacksCompletePageEvidence = hasUnsupportedPageVisual && rendered == null
            if (needsVision &&
                (lacksCompletePageEvidence || assets.none { it.page == pageIndex && it.kind == "IMAGE" && it.bytes.isNotEmpty() })
            ) {
                assets += ExtractedAsset(
                    localId = "page-$pageIndex",
                    kind = "PAGE",
                    page = pageIndex,
                    section = null,
                    bytes = ByteArray(0),
                    mediaType = "application/pdf-page",
                    surroundingText = text,
                )
            }
        }
        imageObjects.forEach { (num, image) ->
            if (num in assignedImages || image.stream == null) return@forEach
            val payload = image.stream
            val mediaType = xObjectMediaType(image.dict, payload) ?: return@forEach
            imageOrdinal += 1
            assets += ExtractedAsset(
                localId = "img-$imageOrdinal",
                kind = "IMAGE",
                page = null,
                section = null,
                bytes = payload,
                mediaType = mediaType,
                surroundingText = "",
            )
        }
        if (pages.isEmpty() && assets.isEmpty()) {
            error("PDF has no extractable pages or text")
        }
        val orderedPages = pages.ifEmpty { listOf(ExtractedPage(1, "", needsVision = true)) }
        val needsVision = orderedPages.any { it.needsVision } || assets.any { it.kind == "IMAGE" || it.kind == "PAGE" }
        return ParsedPublication(
            format = SourceFormat.PDF,
            text = orderedPages.joinToString("\n") { page ->
                val prefix = "Page ${page.page}: "
                if (page.text.isBlank()) prefix.trim() else prefix + page.text
            },
            pages = orderedPages,
            assets = assets,
            needsVision = needsVision,
            parserFingerprint = FINGERPRINT,
        )
    }

    fun writeSimpleTextPdf(text: String): ByteArray {
        val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        return assemblePages(
            listOf(PageContent("BT /F1 12 Tf 72 720 Td ($escaped) Tj ET\n", "/Font << /F1 FONT >>")),
        )
    }

    fun writeLiteralAndHexTextPdf(literal: String, hexText: String): ByteArray {
        val escaped = literal.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val hex = hexText.toByteArray(Charsets.ISO_8859_1).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return assemblePages(
            listOf(
                PageContent(
                    "BT /F1 12 Tf 72 720 Td ($escaped) Tj 0 -24 Td <$hex> Tj ET\n",
                    "/Font << /F1 FONT >>",
                ),
            ),
        )
    }

    fun writeLiteralAndHexArrayPdf(literal: String, hexText: String): ByteArray {
        val escaped = literal.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val hex = hexText.toByteArray(Charsets.ISO_8859_1).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return assemblePages(
            listOf(
                PageContent(
                    "BT /F1 12 Tf 72 720 Td [($escaped) -200 <$hex>] TJ ET\n",
                    "/Font << /F1 FONT >>",
                ),
            ),
        )
    }

    fun writeLiteralAndUndecodedHexShowPdf(literal: String): ByteArray {
        val escaped = literal.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        return assemblePages(
            listOf(
                PageContent(
                    "BT /F1 12 Tf 72 720 Td ($escaped) Tj 0 -24 Td <zzzz> Tj ET\n",
                    "/Font << /F1 FONT >>",
                ),
            ),
        )
    }

    fun writeQuotedCommentShowPdf(literal: String, quoted: String): ByteArray {
        val escapedLiteral = literal.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val escapedQuoted = quoted.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        return assemblePages(
            listOf(
                PageContent(
                    "BT /F1 18 Tf 24 TL 72 720 Td ($escapedLiteral) Tj\n($escapedQuoted) % operand and operator may be separated by comments\n'\nET\n",
                    "/Font << /F1 FONT >>",
                ),
            ),
        )
    }

    fun writeTwoLiteralTextPdf(first: String, second: String): ByteArray {
        val escapedFirst = first.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val escapedSecond = second.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        return assemblePages(
            listOf(
                PageContent(
                    "BT /F1 18 Tf 72 720 Td ($escapedFirst) Tj 0 -30 Td ($escapedSecond) Tj ET\n",
                    "/Font << /F1 FONT >>",
                ),
            ),
        )
    }

    fun writeHexWithFontDifferencesPdf(literal: String? = null): ByteArray {
        val content = if (literal.isNullOrEmpty()) {
            "BT /F1 18 Tf 72 720 Td <414243> Tj ET\n"
        } else {
            val escaped = literal.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            "BT /F1 18 Tf 72 720 Td ($escaped) Tj 0 -30 Td <414243> Tj ET\n"
        }
        return assemblePages(
            pages = listOf(
                PageContent(content, "/Font << /F1 FONT >>"),
            ),
            fontDicts = listOf(
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Type /Encoding /BaseEncoding /WinAnsiEncoding /Differences [65 /X 66 /Y 67 /Z] >> >>",
            ),
        )
    }

    fun writeNestedLiteralWithImagePdf(): ByteArray {
        val jpeg = jpegStub()
        val imageObj = buildString {
            append("<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ")
            append(jpeg.size)
            append(" >>\nstream\n")
        }
        return assemblePages(
            pages = listOf(
                PageContent(
                    "BT /F1 18 Tf 72 720 Td (TITLE) Tj 0 -30 Td (BODY: (nested) KEEP THIS SENTENCE.) Tj ET\nq 40 0 0 40 72 600 cm /Im1 Do Q\n",
                    "/Font << /F1 FONT >> /XObject << /Im1 IMAGE >>",
                ),
            ),
            extraObjects = listOf(imageObj to jpeg),
        )
    }

    fun writeWinAnsiEuroPdf(): ByteArray = assemblePages(
        pages = listOf(
            PageContent(
                "BT /F1 18 Tf 72 720 Td (KEEPTOKEN) Tj 0 -24 Td (Price: \\20010) Tj ET\n",
                "/Font << /F1 FONT >>",
            ),
        ),
        fontDicts = listOf(
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        ),
    )

    fun writeMacRomanCafePdf(): ByteArray = assemblePages(
        pages = listOf(
            PageContent(
                "BT /F1 18 Tf 72 720 Td (KEEPTOKEN) Tj 0 -24 Td (caf\\216) Tj ET\n",
                "/Font << /F1 FONT >>",
            ),
        ),
        fontDicts = listOf(
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /MacRomanEncoding >>",
        ),
    )

    fun writeSymbolBuiltinPdf(label: String, symbolBytes: String = "abg"): ByteArray =
        writeBuiltInFontTextPdf(label, "Symbol", symbolBytes)

    fun writeZapfDingbatsBuiltinPdf(label: String, dingbatBytes: String = "ab"): ByteArray =
        writeBuiltInFontTextPdf(label, "ZapfDingbats", dingbatBytes)

    /**
     * Font fixture with a verbatim font dictionary, so dictionary-lexicon shapes
     * (comments inside the encoding dictionary, `>>` inside a value string, a
     * same-named key in a nested dictionary, `/Differences` used as a name value)
     * can be exercised without hand-building each PDF.
     */
    fun writeVerbatimFontDictPdf(fontDict: String, literal: String? = null): ByteArray {
        val content = if (literal.isNullOrEmpty()) {
            "BT /F1 18 Tf 72 720 Td <414243> Tj ET\n"
        } else {
            val escaped = literal.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            "BT /F1 18 Tf 72 720 Td ($escaped) Tj 0 -30 Td <414243> Tj ET\n"
        }
        return assemblePages(
            pages = listOf(PageContent(content, "/Font << /F1 FONT >>")),
            fontDicts = listOf(fontDict),
        )
    }
    /**
     * Differences mapping fixture whose `/Encoding` and `/Differences` keys are
     * written with the given spellings, so escaped forms such as `/Enc#6Fding` can
     * be compared against the literal key.
     */
    fun writeDifferencesWithKeySpellingsPdf(
        encodingKey: String = "/Encoding",
        differencesKey: String = "/Differences",
        literal: String? = null,
    ): ByteArray {
        val content = if (literal.isNullOrEmpty()) {
            "BT /F1 18 Tf 72 720 Td <414243> Tj ET\n"
        } else {
            val escaped = literal.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            "BT /F1 18 Tf 72 720 Td ($escaped) Tj 0 -30 Td <414243> Tj ET\n"
        }
        return assemblePages(
            pages = listOf(PageContent(content, "/Font << /F1 FONT >>")),
            fontDicts = listOf(
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica $encodingKey " +
                    "<< /Type /Encoding /BaseEncoding /WinAnsiEncoding $differencesKey [65 /X 66 /Y 67 /Z] >> >>",
            ),
        )
    }

    /**
     * Flate-compressed text page whose stream dictionary spells the filter key as
     * [filterKey]. An undecoded key would leave the compressed bytes looking
     * unfiltered and publish them as complete text.
     */
    fun writeFlateTextPdf(text: String, filterKey: String = "/Filter"): ByteArray {
        val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        return assemblePages(
            pages = listOf(PageContent("BT /F1 12 Tf 72 720 Td ($escaped) Tj ET\n", "/Font << /F1 FONT >>")),
            contentDictSuffix = " $filterKey /FlateDecode",
            deflateContent = true,
        )
    }
    /**
     * Single-page PDF whose font dictionary has no `/Encoding`, so the built-in
     * encoding is taken from [baseFontName]. The spelling is written verbatim so
     * escaped name forms such as `Sym#62ol` can be exercised.
     */
    fun writeBuiltInFontPdf(label: String, baseFontName: String, glyphBytes: String = "abg"): ByteArray =
        writeBuiltInFontTextPdf(label, baseFontName, glyphBytes)

    /**
     * Same layout, but the resource dictionary spells the symbol font as `/F#32`
     * while the content stream selects `/F2`. Equivalent name spellings must still
     * resolve to the same font.
     */
    fun writeEscapedResourceNamePdf(label: String, glyphBytes: String = "abg"): ByteArray {
        val escaped = label.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        return assemblePages(
            pages = listOf(
                PageContent(
                    "BT /F1 18 Tf 72 720 Td ($escaped) Tj 0 -30 Td /F2 24 Tf ($glyphBytes) Tj ET\n",
                    "/Font << /F1 FONT /F#32 FONT2 >>",
                ),
            ),
            fontDicts = listOf(
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Symbol >>",
            ),
        )
    }

    private fun writeBuiltInFontTextPdf(label: String, baseFont: String, glyphBytes: String): ByteArray {
        val escaped = label.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        return assemblePages(
            pages = listOf(
                PageContent(
                    "BT /F1 18 Tf 72 720 Td ($escaped) Tj 0 -30 Td /F2 24 Tf ($glyphBytes) Tj ET\n",
                    "/Font << /F1 FONT /F2 FONT2 >>",
                ),
            ),
            fontDicts = listOf(
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /$baseFont >>",
            ),
        )
    }

    fun writeFontRestorePdf(): ByteArray = assemblePages(
        pages = listOf(
            PageContent(
                "BT /F1 18 Tf 72 720 Td (KEEPTOKEN) Tj q /F2 18 Tf (ABC) Tj Q 0 -30 Td (ABC) Tj ET\n",
                "/Font << /F1 FONT /F2 FONT2 >>",
            ),
        ),
        fontDicts = listOf(
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Type /Encoding /BaseEncoding /WinAnsiEncoding /Differences [65 /X 66 /Y 67 /Z] >> >>",
        ),
    )

    fun writeIncompleteThenImagePagesPdf(): ByteArray {
        val jpeg = jpegStub()
        val imageObj = buildString {
            append("<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ")
            append(jpeg.size)
            append(" >>\nstream\n")
        }
        return assemblePages(
            pages = listOf(
                PageContent(
                    "BT /F1 12 Tf 72 720 Td (TITLE) Tj 0 -24 Td <zzzz> Tj ET\n",
                    "/Font << /F1 FONT >>",
                ),
                PageContent(
                    "BT /F1 12 Tf 72 700 Td (SECONDPAGEJPEG) Tj ET\nq 100 0 0 100 72 400 cm /Im1 Do Q\n",
                    "/Font << /F1 FONT >> /XObject << /Im1 IMAGE >>",
                ),
            ),
            extraObjects = listOf(imageObj to jpeg),
        )
    }

    fun writeIncompleteThenEmptySecondPagePdf(): ByteArray = assemblePages(
        pages = listOf(
            PageContent(
                "BT /F1 12 Tf 72 720 Td (TITLE) Tj 0 -24 Td <zzzz> Tj ET\n",
                "/Font << /F1 FONT >>",
            ),
            PageContent(
                "BT /F1 12 Tf 72 720 Td ET\n",
                "/Font << /F1 FONT >>",
            ),
        ),
    )

    fun writeUndecodedHexWithImagePdf(literal: String): ByteArray {
        val escaped = literal.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val jpeg = jpegStub()
        val imageObj = buildString {
            append("<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ")
            append(jpeg.size)
            append(" >>\nstream\n")
        }
        return assemblePages(
            pages = listOf(
                PageContent(
                    "BT /F1 12 Tf 72 700 Td ($escaped) Tj 0 -24 Td <zzzz> Tj ET\nq 100 0 0 100 72 400 cm /Im1 Do Q\n",
                    "/Font << /F1 FONT >> /XObject << /Im1 IMAGE >>",
                ),
            ),
            extraObjects = listOf(imageObj to jpeg),
        )
    }

    fun writeTextAndVectorPdf(text: String): ByteArray {
        val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        return assemblePages(
            listOf(
                PageContent(
                    "BT /F1 12 Tf 72 720 Td ($escaped) Tj ET\n0 0 100 100 re f\n",
                    "/Font << /F1 FONT >>",
                ),
            ),
        )
    }

    fun writeDrawingOnlyPdf(): ByteArray =
        assemblePages(listOf(PageContent("0 0 100 100 re f\n", "")))

    fun writeTextAndInlineImagePdf(text: String): ByteArray {
        val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val rgb = byteArrayOf(0xFF.toByte(), 0x00, 0x00)
        val content = "BT /F1 12 Tf 72 720 Td ($escaped) Tj ET\nBI /W 1 /H 1 /CS /RGB /BPC 8 ID " +
            String(rgb, Charsets.ISO_8859_1) + " EI\n"
        return assemblePages(listOf(PageContent(content, "/Font << /F1 FONT >>")))
    }

    fun writeTwoPageTextPdf(page1: String, page2: String): ByteArray {
        fun body(text: String): String {
            val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            return "BT /F1 12 Tf 72 720 Td ($escaped) Tj ET\n"
        }
        return assemblePages(
            listOf(
                PageContent(body(page1), "/Font << /F1 FONT >>"),
                PageContent(body(page2), "/Font << /F1 FONT >>"),
            ),
        )
    }

    fun writePdfWithImageXObject(label: String): ByteArray {
        val escaped = label.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val jpeg = jpegStub()
        val imageObj = buildString {
            append("<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ")
            append(jpeg.size)
            append(" >>\nstream\n")
        }
        return assemblePages(
            pages = listOf(
                PageContent(
                    "BT /F1 12 Tf 72 700 Td ($escaped) Tj ET\nq 100 0 0 100 72 400 cm /Im1 Do Q\n",
                    "/Font << /F1 FONT >> /XObject << /Im1 IMAGE >>",
                ),
            ),
            extraObjects = listOf(imageObj to jpeg),
        )
    }

    private data class PdfObject(val dict: String, val stream: ByteArray?)
    private data class DecodedPageContent(val bytes: ByteArray, val complete: Boolean)
    private data class PageContent(val content: String, val resources: String)
    private data class StreamBounds(val dataStart: Int, val dataEnd: Int, val objectEnd: Int)
    private data class DeclaredLength(
        val present: Boolean,
        val value: Int?,
        val reference: Int? = null,
    )
    private data class ScannedObject(val dict: String, val streamBounds: StreamBounds?)
    private data class PageXObjects(val entries: Map<String, Int>, val unresolved: Boolean)
    private data class PageFonts(val encodings: Map<String, PdfFontEncoding>, val unresolved: Boolean)
    private data class PdfFontEncoding(
        val known: Boolean,
        val differences: Map<Int, String> = emptyMap(),
        val base: PdfBaseEncoding = PdfBaseEncoding.STANDARD,
    )
    private enum class PdfBaseEncoding { STANDARD, WIN_ANSI, MAC_ROMAN, SYMBOL }
    private data class PdfDictionaryValue(
        val present: Boolean,
        val dictionary: String? = null,
        val reference: Int? = null,
        val malformed: Boolean = false,
    )
    private data class PdfArrayValue(
        val present: Boolean,
        val body: String? = null,
        val malformed: Boolean = false,
    )
    private data class PdfNamedValue(
        val present: Boolean,
        val name: String? = null,
        val dictionary: String? = null,
        val reference: Int? = null,
        val malformed: Boolean = false,
    )
    private data class ExtractedPdfText(val texts: List<String>, val complete: Boolean) {
        fun joined(): String = texts.joinToString(" ").trim()
    }
    private sealed class PdfContentToken {
        data class Name(val value: String) : PdfContentToken()
        data class Number(val value: String) : PdfContentToken()
        data class Literal(val bytes: ByteArray) : PdfContentToken()
        data class Hex(val bytes: ByteArray?, val valid: Boolean) : PdfContentToken()
        data class Array(val items: List<PdfContentToken>) : PdfContentToken()
        data class Dict(val ignored: Int = 0) : PdfContentToken()
        data class Operator(val name: String) : PdfContentToken()
    }

    private fun extractIndirectObjects(bytes: ByteArray, latin: String): Map<Int, PdfObject> {
        val scanned = linkedMapOf<Int, ScannedObject>()
        // Kotlin MatchResult.next() creates a new Matcher for each match.
        // Android's ICU matcher retains its own native input representation;
        // recreating it over an entire binary PDF can exhaust native memory.
        // Reuse one matcher for this whole-file scan.
        val header = Regex("(\\d+)\\s+0\\s+obj").toPattern().matcher(latin)
        header.region(0, latin.length)
        while (header.find()) {
            val number = header.group(1)!!.toInt()
            val bodyStart = header.end()
            val streamStart = findStreamKeyword(latin, bodyStart)
            val next: Int
            if (streamStart == null) {
                val endObjStart = findPdfKeyword(latin, "endobj", bodyStart)
                if (endObjStart < 0) break
                scanned[number] = ScannedObject(
                    dict = latin.substring(bodyStart, endObjStart),
                    streamBounds = null,
                )
                next = endObjStart + "endobj".length
            } else {
                val dataStart = streamDataStart(latin, streamStart)
                if (dataStart == null) {
                    val endObjStart = findPdfKeyword(latin, "endobj", bodyStart)
                    if (endObjStart < 0) break
                    next = endObjStart + "endobj".length
                } else {
                    val dict = latin.substring(bodyStart, streamStart)
                    val declared = declaredStreamLength(dict)
                    val directLength = declared.value.takeIf { declared.reference == null }
                    val bounds = streamBounds(latin, dataStart, directLength)
                    if (bounds == null) {
                        // A malformed stream cannot safely delimit the rest of
                        // the file. Skip to the next endobj token when one is
                        // available, while never treating the stream bytes as
                        // a valid object.
                        val endObjStart = findPdfKeyword(latin, "endobj", bodyStart)
                        if (endObjStart < 0) break
                        next = endObjStart + "endobj".length
                    } else {
                        scanned[number] = ScannedObject(dict, bounds)
                        next = bounds.objectEnd
                    }
                }
            }
            // Do not let object headers embedded in a binary stream become
            // separate objects. The resolved endobj is the only safe restart
            // point for the whole-file matcher.
            header.region(next.coerceIn(0, latin.length), latin.length)
        }

        val out = linkedMapOf<Int, PdfObject>()
        scanned.forEach { (number, candidate) ->
            val bounds = candidate.streamBounds
            if (bounds == null) {
                out[number] = PdfObject(candidate.dict, null)
                return@forEach
            }
            // Indirect /Length values can point to a scalar object that was
            // encountered later in the file. Revalidate the same endobj
            // against that length before replacing the fallback bounds.
            val declared = declaredStreamLength(candidate.dict)
            val resolvedLength = resolveIndirectLength(declared, scanned)
            val preciseBounds = resolvedLength?.let { length ->
                streamBounds(latin, bounds.dataStart, length, bounds.objectEnd)
            }
            val finalBounds = preciseBounds ?: bounds
            val streamSize = finalBounds.dataEnd - finalBounds.dataStart
            val raw = if (
                streamSize in 0..MAX_PDF_STREAM_BYTES &&
                finalBounds.dataStart >= 0 &&
                finalBounds.dataEnd <= bytes.size
            ) {
                bytes.copyOfRange(finalBounds.dataStart, finalBounds.dataEnd)
            } else {
                // Keep the object dictionary and its endobj boundary, but
                // discard oversized bytes so later parsing fails closed.
                null
            }
            out[number] = PdfObject(candidate.dict, raw)
        }

        // PDF 1.5 object streams contain object values, without obj/endobj
        // delimiters. Expand them before traversing the catalog's page tree.
        out.values.toList().filter { Regex("/Type\\s*/ObjStm\\b").containsMatchIn(it.dict) }.forEach { container ->
            val decoded = decodeContentStream(container)
            require(decoded.complete) { "Unsupported or incomplete PDF object stream" }
            fun integer(name: String): Int = requireNotNull(
                Regex("/$name\\s+(\\d+)\\b").find(container.dict)?.groupValues?.get(1)?.toIntOrNull(),
            ) { "Invalid PDF object stream header" }
            val count = integer("N")
            val first = integer("First")
            require(count in 1..100_000 && first in 1 until decoded.bytes.size) { "Invalid PDF object stream bounds" }
            val headerText = String(decoded.bytes, 0, first, Charsets.ISO_8859_1).trim()
            val fields = headerText.split(Regex("\\s+"))
            require(fields.size == count * 2) { "Invalid PDF object stream index" }
            val numbers = IntArray(count)
            val offsets = IntArray(count)
            val seen = mutableSetOf<Int>()
            for (i in 0 until count) {
                numbers[i] = requireNotNull(fields[i * 2].toIntOrNull()) { "Invalid PDF object number" }
                offsets[i] = requireNotNull(fields[i * 2 + 1].toIntOrNull()) { "Invalid PDF object offset" }
                require(numbers[i] > 0 && seen.add(numbers[i])) { "Duplicate PDF object stream number" }
                require(offsets[i] in 0 until (decoded.bytes.size - first) && (i == 0 || offsets[i] > offsets[i - 1])) {
                    "Invalid PDF object stream offset"
                }
            }
            for (i in 0 until count) {
                val start = first + offsets[i]
                val end = if (i + 1 < count) first + offsets[i + 1] else decoded.bytes.size
                val value = String(decoded.bytes, start, end - start, Charsets.ISO_8859_1)
                // Keep explicit objects when a document also contains revisions.
                out.putIfAbsent(numbers[i], PdfObject(value, null))
            }
        }
        return out
    }

    private fun declaredStreamLength(dict: String): DeclaredLength {
        val match = Regex("/Length(?![A-Za-z0-9])\\s+(-?\\d+)(\\s+0\\s+R\\b)?")
            .find(dict)
        if (match != null) {
            val number = match.groupValues[1].toLongOrNull()
            val indirect = match.groupValues[2].isNotEmpty()
            return if (indirect) {
                DeclaredLength(present = true, value = null, reference = number?.toInt())
            } else {
                DeclaredLength(
                    present = true,
                    value = number?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
                )
            }
        }
        return DeclaredLength(
            present = Regex("/Length(?![A-Za-z0-9])").containsMatchIn(dict),
            value = null,
        )
    }

    private fun resolveIndirectLength(
        declared: DeclaredLength,
        objects: Map<Int, ScannedObject>,
    ): Int? {
        val reference = declared.reference ?: return declared.value
        val target = objects[reference] ?: return null
        if (target.streamBounds != null) return null
        return target.dict.trim().toLongOrNull()
            ?.takeIf { it in 0..Int.MAX_VALUE }
            ?.toInt()
    }

    private fun findStreamKeyword(latin: String, fromIndex: Int): Int? {
        val firstEndObj = findPdfKeyword(latin, "endobj", fromIndex)
        var candidate = latin.indexOf("stream", fromIndex)
        while (candidate >= 0 && (firstEndObj < 0 || candidate < firstEndObj)) {
            val keywordEnd = candidate + "stream".length
            val afterIsWhitespace = keywordEnd < latin.length && isPdfWhitespace(latin[keywordEnd])
            if (afterIsWhitespace) {
                var dictionaryEnd = candidate
                while (dictionaryEnd > fromIndex && isPdfWhitespace(latin[dictionaryEnd - 1])) {
                    dictionaryEnd--
                }
                if (
                    dictionaryEnd >= fromIndex + 2 &&
                    latin.substring(dictionaryEnd - 2, dictionaryEnd) == ">>"
                ) {
                    return candidate
                }
            }
            candidate = latin.indexOf("stream", candidate + "stream".length)
        }
        return null
    }

    private fun streamDataStart(latin: String, streamKeyword: Int): Int? {
        var start = streamKeyword + "stream".length
        if (start >= latin.length) return null
        return when (latin[start]) {
            '\r' -> if (start + 1 < latin.length && latin[start + 1] == '\n') start + 2 else start + 1
            '\n' -> start + 1
            else -> null
        }
    }

    private fun streamBounds(
        latin: String,
        dataStart: Int,
        declaredLength: Int?,
        expectedObjectEnd: Int? = null,
    ): StreamBounds? {
        if (dataStart !in 0..latin.length) return null

        if (declaredLength != null && declaredLength >= 0 && declaredLength <= latin.length - dataStart) {
            val declaredEnd = dataStart + declaredLength
            // Some valid producers place endstream immediately after the
            // declared binary payload, without a delimiter byte. The
            // declaration makes that exact boundary unambiguous; preserve
            // token-aware whitespace checks for every later candidate.
            val endStreamStart = if (
                latin.startsWith("endstream", declaredEnd) &&
                declaredEnd + "endstream".length <= latin.length &&
                (
                    declaredEnd + "endstream".length >= latin.length ||
                        isPdfWhitespace(latin[declaredEnd + "endstream".length])
                    )
            ) {
                declaredEnd
            } else {
                findPdfKeyword(latin, "endstream", declaredEnd)
            }
            if (
                endStreamStart >= 0 &&
                isPdfWhitespaceOnly(latin, declaredEnd, endStreamStart)
            ) {
                val endStreamEnd = endStreamStart + "endstream".length
                val endObjStart = findPdfKeyword(latin, "endobj", endStreamEnd)
                if (
                    endObjStart >= 0 &&
                    isPdfWhitespaceOnly(latin, endStreamEnd, endObjStart)
                ) {
                    val objectEnd = endObjStart + "endobj".length
                    if (expectedObjectEnd == null || expectedObjectEnd == objectEnd) {
                        return StreamBounds(dataStart, declaredEnd, objectEnd)
                    }
                }
            }
        }

        var search = dataStart
        while (true) {
            val endStreamStart = findPdfKeyword(latin, "endstream", search)
            if (endStreamStart < 0) return null
            val endStreamEnd = endStreamStart + "endstream".length
            val endObjStart = findPdfKeyword(latin, "endobj", endStreamEnd)
            if (
                endObjStart >= 0 &&
                isPdfWhitespaceOnly(latin, endStreamEnd, endObjStart)
            ) {
                val objectEnd = endObjStart + "endobj".length
                if (expectedObjectEnd == null || expectedObjectEnd == objectEnd) {
                    var dataEnd = endStreamStart
                    if (dataEnd > dataStart && latin[dataEnd - 1] == '\n') dataEnd--
                    if (dataEnd > dataStart && latin[dataEnd - 1] == '\r') dataEnd--
                    return StreamBounds(dataStart, dataEnd, objectEnd)
                }
            }
            search = endStreamEnd
        }
    }

    private fun findPdfKeyword(latin: String, keyword: String, fromIndex: Int): Int {
        var index = latin.indexOf(keyword, fromIndex)
        while (index >= 0) {
            val end = index + keyword.length
            val beforeIsWhitespace = index == 0 || isPdfWhitespace(latin[index - 1])
            val afterIsWhitespace = end >= latin.length || isPdfWhitespace(latin[end])
            if (beforeIsWhitespace && afterIsWhitespace) return index
            index = latin.indexOf(keyword, index + keyword.length)
        }
        return -1
    }

    private fun isPdfWhitespaceOnly(latin: String, start: Int, end: Int): Boolean {
        if (start < 0 || end < start || end > latin.length) return false
        for (index in start until end) {
            if (!isPdfWhitespace(latin[index])) return false
        }
        return true
    }

    private fun isPdfWhitespace(value: Char): Boolean =
        value == '\u0000' || value == '\t' || value == '\n' || value == '\u000C' ||
            value == '\r' || value == ' '

    private fun pageKids(objects: Map<Int, PdfObject>): List<Int> {
        val catalog = objects.values.firstOrNull { Regex("/Type\\s*/Catalog\\b").containsMatchIn(it.dict) }
        val root = if (catalog != null) {
            requireNotNull(Regex("/Pages\\s+(\\d+)\\s+0\\s+R").find(catalog.dict)?.groupValues?.get(1)?.toIntOrNull()) {
                "Invalid PDF page tree root"
            }
        } else {
            val roots = objects.filterValues {
                Regex("/Type\\s*/Pages\\b").containsMatchIn(it.dict) && !Regex("/Parent\\b").containsMatchIn(it.dict)
            }.keys
            require(roots.size <= 1) { "Ambiguous PDF page tree root" }
            roots.singleOrNull() ?: return emptyList()
        }
        val pending = ArrayDeque<Int>()
        val visited = mutableSetOf<Int>()
        val leaves = mutableListOf<Int>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val number = pending.removeLast()
            require(visited.add(number)) { "Cyclic or duplicate PDF page tree reference" }
            val node = requireNotNull(objects[number]) { "Missing PDF page tree object" }
            if (isPageDict(node.dict)) {
                leaves += number
            } else {
                require(Regex("/Type\\s*/Pages\\b").containsMatchIn(node.dict)) { "Invalid PDF page tree node" }
                val kids = requireNotNull(Regex("/Kids\\s*\\[([^]]*)]").find(node.dict)?.groupValues?.get(1)) {
                    "Missing PDF page tree children"
                }
                val children = Regex("(\\d+)\\s+0\\s+R").findAll(kids).map { it.groupValues[1].toInt() }.toList()
                require(children.isNotEmpty()) { "Empty PDF page tree children" }
                // A stack keeps even unusually deep trees bounded by the
                // parsed object set; reverse insertion preserves /Kids order.
                children.asReversed().forEach(pending::addLast)
            }
        }
        return leaves
    }

    private fun isPageDict(dict: String): Boolean =
        Regex("/Type\\s*/Page(?![sA-Za-z])").containsMatchIn(dict)

    private fun isImageDict(dict: String): Boolean =
        dict.contains("/Image") || Regex("/Subtype\\s*/Image").containsMatchIn(dict) ||
            streamFilters(dict).contains("DCTDecode")

    private fun pageContent(objects: Map<Int, PdfObject>, dict: String): DecodedPageContent {
        val single = Regex("/Contents\\s+(\\d+)\\s+0\\s+R").find(dict)?.groupValues?.get(1)?.toIntOrNull()
        if (single != null) {
            return objects[single]?.let(::decodeContentStream)
                ?: DecodedPageContent(ByteArray(0), complete = false)
        }
        val array = Regex("/Contents\\s*\\[([^]]*)]").find(dict)?.groupValues?.get(1)
        if (array != null) {
            val nums = Regex("(\\d+)\\s+0\\s+R").findAll(array).map { it.groupValues[1].toInt() }.toList()
            val out = ByteArrayOutputStream()
            var complete = nums.isNotEmpty()
            var first = true
            for (number in nums) {
                val content = objects[number]?.let(::decodeContentStream)
                if (content == null) {
                    complete = false
                    continue
                }
                if (!content.complete) complete = false
                if (!first) {
                    if (out.size() >= MAX_PDF_STREAM_BYTES) {
                        return DecodedPageContent(ByteArray(0), complete = false)
                    }
                    out.write('\n'.code)
                }
                if (content.bytes.size > MAX_PDF_STREAM_BYTES - out.size()) {
                    return DecodedPageContent(ByteArray(0), complete = false)
                }
                out.write(content.bytes)
                first = false
            }
            return DecodedPageContent(
                bytes = out.toByteArray(),
                complete = complete,
            )
        }
        return DecodedPageContent(ByteArray(0), complete = false)
    }

    private fun pageXObjects(
        objects: Map<Int, PdfObject>,
        pageNumber: Int,
        dict: String,
    ): PageXObjects = pageNamedResources(objects, pageNumber, dict, "XObject")

    private fun pageFonts(
        objects: Map<Int, PdfObject>,
        pageNumber: Int,
        dict: String,
    ): PageFonts {
        val named = pageNamedResources(objects, pageNumber, dict, "Font")
        if (named.unresolved) return PageFonts(emptyMap(), unresolved = true)
        val encodings = linkedMapOf<String, PdfFontEncoding>()
        named.entries.forEach { (name, objNum) ->
            val font = objects[objNum] ?: return PageFonts(encodings, unresolved = true)
            encodings[name] = encodingFromFont(objects, font)
        }
        return PageFonts(encodings, unresolved = false)
    }

    private fun pageNamedResources(
        objects: Map<Int, PdfObject>,
        pageNumber: Int,
        dict: String,
        key: String,
    ): PageXObjects {
        val visited = mutableSetOf<Int>()
        var currentNumber = pageNumber
        var currentDict = dict
        while (true) {
            if (!visited.add(currentNumber)) return PageXObjects(emptyMap(), unresolved = true)

            val resources = dictionaryOrReference(currentDict, "Resources")
            if (resources.present) {
                if (resources.malformed) return PageXObjects(emptyMap(), unresolved = true)
                val resourceDict = resources.dictionary ?: resources.reference
                    ?.let { reference ->
                        objects[reference]
                            ?.takeIf { it.stream == null }
                            ?.let { dictionaryBody(it.dict) }
                    }
                    ?: return PageXObjects(emptyMap(), unresolved = true)
                return namedResourcesFrom(objects, resourceDict, key)
            }

            val parent = dictionaryOrReference(currentDict, "Parent")
            if (!parent.present) return PageXObjects(emptyMap(), unresolved = false)
            if (parent.malformed || parent.reference == null) {
                return PageXObjects(emptyMap(), unresolved = true)
            }
            val parentObject = objects[parent.reference]
                ?.takeIf { it.stream == null }
                ?: return PageXObjects(emptyMap(), unresolved = true)
            currentNumber = parent.reference
            currentDict = parentObject.dict
        }
    }

    private fun namedResourcesFrom(
        objects: Map<Int, PdfObject>,
        resources: String,
        key: String,
    ): PageXObjects {
        val value = dictionaryOrReference(resources, key)
        if (!value.present) return PageXObjects(emptyMap(), unresolved = false)
        if (value.malformed) return PageXObjects(emptyMap(), unresolved = true)
        val resourceDict = value.dictionary ?: value.reference
            ?.let { reference ->
                objects[reference]
                    ?.takeIf { it.stream == null }
                    ?.let { dictionaryBody(it.dict) }
            }
            ?: return PageXObjects(emptyMap(), unresolved = true)
        val entries = linkedMapOf<String, Int>()
        Regex("/([^\\s<>\\[\\]()/%]+)\\s+(\\d+)\\s+0\\s+R\\b")
            .findAll(resourceDict)
            .forEach { match ->
                entries[decodePdfName(match.groupValues[1])] = match.groupValues[2].toInt()
            }
        return PageXObjects(entries, unresolved = false)
    }

    private fun encodingFromFont(objects: Map<Int, PdfObject>, font: PdfObject): PdfFontEncoding {
        val dict = font.dict
        val subtype = Regex("/Subtype\\s*/([A-Za-z0-9]+)").find(dict)?.groupValues?.get(1)
        if (subtype == "Type0" || subtype == "CIDFontType0" || subtype == "CIDFontType2") {
            return PdfFontEncoding(known = false)
        }
        val encoding = namedDictionaryOrReference(dict, "Encoding")
        if (!encoding.present) return builtInFontEncoding(dict)
        if (encoding.malformed) return PdfFontEncoding(known = false)
        val encodingDict = encoding.dictionary ?: encoding.reference
            ?.let { reference -> objects[reference]?.let { dictionaryBody(it.dict) } }
        if (encodingDict != null) {
            val baseValue = namedDictionaryOrReference(encodingDict, "BaseEncoding")
            if (baseValue.malformed) return PdfFontEncoding(known = false)
            val base = if (!baseValue.present) {
                builtInBaseEncoding(dict) ?: return PdfFontEncoding(known = false)
            } else {
                encodingByName(baseValue.name) ?: return PdfFontEncoding(known = false)
            }
            val differences = arrayBody(encodingDict, "Differences")
            if (!differences.present) return PdfFontEncoding(known = true, base = base)
            if (differences.malformed) return PdfFontEncoding(known = false)
            val mapped = parseDifferences(differences.body.orEmpty()) ?: return PdfFontEncoding(known = false)
            return PdfFontEncoding(known = true, differences = mapped, base = base)
        }
        return when (val base = encodingByName(encoding.name)) {
            null -> PdfFontEncoding(known = false)
            else -> PdfFontEncoding(known = true, base = base)
        }
    }

    /**
     * PDF 32000-1 9.6.6.1: a simple font with no explicit (Base)Encoding uses its
     * own built-in encoding. Symbol has a non-Latin built-in set; ZapfDingbats
     * dingbats have no dependable Unicode text mapping here, so both are resolved
     * through [builtInBaseEncoding] and unknown built-ins fail closed. A page that
     * cannot be decoded must fall back to Vision instead of publishing wrong Latin
     * text as complete.
     */
    private fun builtInFontEncoding(dict: String): PdfFontEncoding {
        val base = builtInBaseEncoding(dict) ?: return PdfFontEncoding(known = false)
        return PdfFontEncoding(known = true, base = base)
    }

    private fun builtInBaseEncoding(dict: String): PdfBaseEncoding? {
        val baseFont = namedDictionaryOrReference(dict, "BaseFont").name?.substringAfterLast('+') ?: return null
        return when {
            baseFont == "Symbol" -> PdfBaseEncoding.SYMBOL
            baseFont == "ZapfDingbats" -> null
            baseFont in BASE_14_TEXT_FONTS -> PdfBaseEncoding.STANDARD
            else -> null
        }
    }

    // Adobe base-14 Latin text faces (PDF 32000-1 Table 111); the twelve text faces
    // have StandardEncoding as their built-in encoding.
    private val BASE_14_TEXT_FONTS = setOf(
        "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique",
        "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
        "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic",
    )

    private fun encodingByName(name: String?): PdfBaseEncoding? = when (name) {
        "WinAnsiEncoding" -> PdfBaseEncoding.WIN_ANSI
        "MacRomanEncoding" -> PdfBaseEncoding.MAC_ROMAN
        "StandardEncoding" -> PdfBaseEncoding.STANDARD
        null -> null
        else -> null
    }

    /**
     * Locate a top-level array value. Absent, malformed and legitimately empty are
     * three different answers: collapsing them would let a malformed `/Differences`
     * silently degrade to "no differences", which republishes the undeclared bytes
     * as complete text.
     */
    private fun arrayBody(dict: String, name: String): PdfArrayValue {
        val valueStart = findTopLevelValueStart(dict, name)
        if (valueStart < 0) return PdfArrayValue(present = false)
        if (valueStart >= dict.length || dict[valueStart] != '[') return PdfArrayValue(present = true, malformed = true)
        val end = arrayEnd(dict, valueStart)
        if (end < 0) return PdfArrayValue(present = true, malformed = true)
        return PdfArrayValue(present = true, body = dict.substring(valueStart + 1, end - 1))
    }

    /**
     * Index just past the `]` closing the array that starts at [start], skipping
     * comments, literal strings, hex strings and nested structures. A `]` inside
     * any of those does not close the array.
     */
    private fun arrayEnd(text: String, start: Int): Int {
        if (start >= text.length || text[start] != '[') return -1
        var index = start
        var depth = 0
        while (index < text.length) {
            val char = text[index]
            val next = when {
                char == '%' -> endOfPdfComment(text, index)
                char == '(' -> endOfLiteralString(text, index)
                char == '<' && text.startsWith("<<", index) -> dictionaryEnd(text, index)
                char == '<' -> endOfHexString(text, index)
                char == '[' -> {
                    depth++
                    index + 1
                }
                char == ']' -> {
                    depth--
                    index + 1
                }
                else -> index + 1
            }
            if (next < 0) return -1
            if (char == ']' && depth == 0) return next
            index = next
        }
        return -1
    }

    private fun parseDifferences(body: String): Map<Int, String>? {
        val mapped = linkedMapOf<Int, String>()
        var index = 0
        var nextCode: Int? = null
        while (index < body.length) {
            while (index < body.length && (isPdfWhitespace(body[index]) || body[index] == '%')) {
                if (body[index] == '%') {
                    while (index < body.length && body[index] != '\n' && body[index] != '\r') index++
                } else {
                    index++
                }
            }
            if (index >= body.length) break
            if (body[index] == '/') {
                val start = index + 1
                index++
                while (index < body.length && !isPdfWhitespace(body[index]) && !isPdfDelimiter(body[index])) index++
                val glyph = pdfGlyphName(decodePdfName(body.substring(start, index))) ?: return null
                val code = nextCode ?: return null
                mapped[code] = glyph
                nextCode = code + 1
                continue
            }
            val start = index
            if (body[index] == '+' || body[index] == '-') index++
            if (index >= body.length || body[index] !in '0'..'9') return null
            while (index < body.length && body[index] in '0'..'9') index++
            nextCode = body.substring(start, index).toIntOrNull() ?: return null
        }
        return mapped
    }

    private fun pdfGlyphName(name: String): String? = when {
        name.length == 1 -> name
        name == "space" -> " "
        name == "period" -> "."
        name == "comma" -> ","
        name == "colon" -> ":"
        name == "semicolon" -> ";"
        name == "hyphen" || name == "minus" -> "-"
        name == "slash" -> "/"
        name == "backslash" -> "\\"
        name == "parenleft" -> "("
        name == "parenright" -> ")"
        name == "quotesingle" -> "'"
        name == "quotedbl" -> "\""
        name == "underscore" -> "_"
        name == "Euro" -> "€"
        name == "eacute" -> "é"
        else -> null
    }

    private fun isPdfDelimiter(value: Char): Boolean =
        value == '(' || value == ')' || value == '<' || value == '>' ||
            value == '[' || value == ']' || value == '{' || value == '}' ||
            value == '/' || value == '%'

    /**
     * Index of the value token for the *top-level* [name] key of a dictionary body,
     * or -1 when that key is absent.
     *
     * PDF 32000-1 7.3.5 allows any byte of a name to be escaped as `#` plus two
     * hex digits, so `/Enc#6Fding` and `/Encoding` are the same key; keys must be
     * decoded before they are compared.
     *
     * Key lookup walks the body as a token stream instead of scanning for `/Name`
     * substrings and instead of matching wherever a name happens to appear. A flat
     * scan cannot tell three different things apart — a name used as a *value*
     * (`/Custom /Differences [...]`), a same-named key inside a nested dictionary
     * (`/Private << /Differences [] >>`), and a `/Name` that only occurs inside a
     * comment or string. Any of those makes a declared key look absent, which
     * silently selects a weaker default (for example dropping the `/Differences`
     * map) and publishes the wrong bytes as complete text.
     */
    private fun findTopLevelValueStart(dict: String, name: String): Int {
        var index = skipPdfSpaceAndComments(dict, 0)
        if (dict.startsWith("<<", index)) index = skipPdfSpaceAndComments(dict, index + 2)
        var expectKey = true
        while (index < dict.length) {
            index = skipPdfSpaceAndComments(dict, index)
            if (index >= dict.length || dict.startsWith(">>", index)) return -1
            if (expectKey) {
                if (dict[index] != '/') return -1
                val nameEnd = endOfPdfName(dict, index)
                val matched = decodePdfName(dict.substring(index + 1, nameEnd)) == name
                index = nameEnd
                if (matched) return skipPdfSpaceAndComments(dict, index)
                expectKey = false
            } else {
                val valueEnd = skipPdfValue(dict, index)
                if (valueEnd < 0) return -1
                index = valueEnd
                expectKey = true
            }
        }
        return -1
    }

    /** Index just past one value token, or -1 when it is malformed. */
    private fun skipPdfValue(text: String, start: Int): Int {
        if (start >= text.length) return -1
        return when {
            text.startsWith("<<", start) -> dictionaryEnd(text, start)
            text[start] == '[' -> arrayEnd(text, start)
            text[start] == '(' -> endOfLiteralString(text, start)
            text[start] == '<' -> endOfHexString(text, start)
            text[start] == '/' -> endOfPdfName(text, start)
            else -> endOfPdfSimpleValue(text, start)
        }
    }

    /**
     * A number, boolean, null, or an indirect reference `n 0 R`. The reference form
     * spans three tokens, so a caller that stops after the first integer would
     * mistake the object number for a key and abandon the rest of the dictionary.
     */
    private fun endOfPdfSimpleValue(text: String, start: Int): Int {
        val first = endOfRegularToken(text, start)
        if (first < 0) return -1
        if (text.substring(start, first).toLongOrNull() == null) return first
        val second = skipPdfSpaceAndComments(text, first)
        if (second >= text.length || !text[second].isDigit()) return first
        val secondEnd = endOfRegularToken(text, second)
        if (secondEnd < 0) return first
        val marker = skipPdfSpaceAndComments(text, secondEnd)
        if (marker < text.length && text[marker] == 'R' && isPdfTokenEnd(text, marker + 1)) return marker + 1
        return first
    }

    private fun skipPdfSpaceAndComments(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            when {
                isPdfWhitespace(text[index]) -> index++
                text[index] == '%' -> index = endOfPdfComment(text, index)
                else -> return index
            }
        }
        return index
    }

    private fun endOfPdfComment(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index] != '\n' && text[index] != '\r') index++
        return index
    }

    /** Index just past the `)` closing the literal string at [start], or -1. */
    private fun endOfLiteralString(text: String, start: Int): Int {
        if (start >= text.length || text[start] != '(') return -1
        var index = start + 1
        var depth = 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += 2
                '(' -> {
                    depth++
                    index++
                }
                ')' -> {
                    depth--
                    index++
                    if (depth == 0) return index
                }
                else -> index++
            }
        }
        return -1
    }

    /** Index just past the `>` closing the hex string at [start], or -1. */
    private fun endOfHexString(text: String, start: Int): Int {
        if (start >= text.length || text[start] != '<') return -1
        var index = start + 1
        while (index < text.length) {
            if (text[index] == '>') return index + 1
            index++
        }
        return -1
    }

    /**
     * Index just past the name token that starts with `/` at [start]. `/` is itself
     * a PDF delimiter, so the scan must begin one character after it.
     */
    private fun endOfPdfName(text: String, start: Int): Int {
        if (start >= text.length || text[start] != '/') return -1
        var index = start + 1
        while (index < text.length && !isPdfWhitespace(text[index]) && !isPdfDelimiter(text[index])) index++
        return index
    }

    private fun endOfRegularToken(text: String, start: Int): Int {
        var index = start
        while (index < text.length && !isPdfWhitespace(text[index]) && !isPdfDelimiter(text[index])) index++
        return if (index > start) index else -1
    }

    private fun isPdfTokenEnd(text: String, index: Int): Boolean =
        index >= text.length || isPdfWhitespace(text[index]) || isPdfDelimiter(text[index])

    /**
     * PDF 32000-1 7.3.5: a name may spell any byte as `#` plus two hex digits, so
     * `/Sym#62ol` and `/Symbol` name the same font. Names must be decoded before
     * they are compared or looked up; otherwise an equivalent spelling silently
     * misses its table and is treated as an unknown font.
     */
    private fun decodePdfName(raw: String): String {
        if (raw.indexOf('#') < 0) return raw
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char == '#' && index + 2 < raw.length &&
                raw[index + 1].isPdfHexDigit() && raw[index + 2].isPdfHexDigit()
            ) {
                out.append(((raw[index + 1].pdfHexValue() shl 4) or raw[index + 2].pdfHexValue()).toChar())
                index += 3
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }

    private fun Char.isPdfHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.pdfHexValue(): Int = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        else -> this - 'A' + 10
    }

    private fun hasUnresolvedXObjectDo(latin: String, entries: Map<String, Int>): Boolean {
        val outsideText = latin.replace(Regex("BT[\\s\\S]*?ET"), " ")
        return Regex("/([^\\s<>\\[\\]()/%]+)\\s+Do\\b")
            .findAll(outsideText)
            .any { match -> decodePdfName(match.groupValues[1]) !in entries }
    }

    private fun dictionaryOrReference(dict: String, name: String): PdfDictionaryValue {
        val valueStart = findTopLevelValueStart(dict, name)
        if (valueStart < 0) return PdfDictionaryValue(present = false)
        if (valueStart >= dict.length) return PdfDictionaryValue(present = true, malformed = true)
        if (dict.startsWith("<<", valueStart)) {
            val end = dictionaryEnd(dict, valueStart)
            return if (end < 0) {
                PdfDictionaryValue(present = true, malformed = true)
            } else {
                PdfDictionaryValue(
                    present = true,
                    dictionary = dict.substring(valueStart, end),
                )
            }
        }
        val matcher = Regex("(\\d+)\\s+0\\s+R\\b").toPattern().matcher(dict)
        matcher.region(valueStart, dict.length)
        return if (matcher.lookingAt()) {
            PdfDictionaryValue(
                present = true,
                reference = matcher.group(1).toIntOrNull(),
            )
        } else {
            PdfDictionaryValue(present = true, malformed = true)
        }
    }

    private fun namedDictionaryOrReference(dict: String, name: String): PdfNamedValue {
        val valueStart = findTopLevelValueStart(dict, name)
        if (valueStart < 0) return PdfNamedValue(present = false)
        if (valueStart >= dict.length) return PdfNamedValue(present = true, malformed = true)
        if (dict.startsWith("<<", valueStart)) {
            val end = dictionaryEnd(dict, valueStart)
            return if (end < 0) {
                PdfNamedValue(present = true, malformed = true)
            } else {
                PdfNamedValue(present = true, dictionary = dict.substring(valueStart, end))
            }
        }
        if (dict[valueStart] == '/') {
            val start = valueStart + 1
            var index = start
            while (index < dict.length && !isPdfWhitespace(dict[index]) && !isPdfDelimiter(dict[index])) index++
            return PdfNamedValue(present = true, name = decodePdfName(dict.substring(start, index)))
        }
        val matcher = Regex("(\\d+)\\s+0\\s+R\\b").toPattern().matcher(dict)
        matcher.region(valueStart, dict.length)
        return if (matcher.lookingAt()) {
            PdfNamedValue(present = true, reference = matcher.group(1).toIntOrNull())
        } else {
            PdfNamedValue(present = true, malformed = true)
        }
    }

    private fun dictionaryBody(body: String): String? {
        val start = body.indexOf("<<")
        if (start < 0) return null
        val end = dictionaryEnd(body, start)
        return end.takeIf { it >= 0 }?.let { body.substring(start, it) }
    }

    /**
     * Index just past the `>>` closing the dictionary that starts at [start], or -1.
     * Comments, literal strings and hex strings are skipped: a `>>` inside any of
     * them does not close the dictionary, and counting raw `>>` occurrences would
     * truncate the dictionary and hide the keys declared after it.
     */
    private fun dictionaryEnd(text: String, start: Int): Int {
        if (start < 0 || !text.startsWith("<<", start)) return -1
        var depth = 0
        var index = start
        while (index < text.length) {
            when {
                text.startsWith("<<", index) -> {
                    depth++
                    index += 2
                }
                text.startsWith(">>", index) -> {
                    depth--
                    index += 2
                    if (depth == 0) return index
                }
                text[index] == '%' -> {
                    index = endOfPdfComment(text, index)
                }
                text[index] == '(' -> {
                    index = endOfLiteralString(text, index)
                    if (index < 0) return -1
                }
                text[index] == '<' -> {
                    index = endOfHexString(text, index)
                    if (index < 0) return -1
                }
                else -> index++
            }
        }
        return -1
    }

    private fun streamFilters(dict: String): List<String> {
        val valueStart = findTopLevelValueStart(dict, "Filter")
        if (valueStart < 0 || valueStart >= dict.length) return emptyList()
        if (dict[valueStart] == '[') {
            val body = arrayBody(dict, "Filter")
            if (!body.present || body.malformed) return emptyList()
            return Regex("/([^\\s<>\\[\\]()/%]+)").findAll(body.body.orEmpty())
                .map { decodePdfName(it.groupValues[1]) }
                .toList()
        }
        if (dict[valueStart] != '/') return emptyList()
        val end = endOfPdfName(dict, valueStart)
        if (end < 0) return emptyList()
        return listOf(decodePdfName(dict.substring(valueStart + 1, end)))
    }

    private fun decodeContentStream(obj: PdfObject): DecodedPageContent {
        val raw = obj.stream ?: return DecodedPageContent(ByteArray(0), complete = false)
        if (raw.size > MAX_PDF_STREAM_BYTES) {
            return DecodedPageContent(ByteArray(0), complete = false)
        }
        val filters = streamFilters(obj.dict)
        if (filters.isEmpty()) return DecodedPageContent(raw, complete = true)
        if (filters != listOf("FlateDecode")) return DecodedPageContent(raw, complete = false)
        val inflater = Inflater()
        return try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) return DecodedPageContent(raw, complete = false)
                if (n > MAX_PDF_STREAM_BYTES - out.size()) {
                    return DecodedPageContent(ByteArray(0), complete = false)
                }
                out.write(buf, 0, n)
            }
            DecodedPageContent(out.toByteArray(), complete = true)
        } catch (_: Exception) {
            DecodedPageContent(raw, complete = false)
        } finally {
            inflater.end()
        }
    }

    private fun pageNeedsVision(
        text: String,
        textComplete: Boolean,
        contentComplete: Boolean,
        hasImages: Boolean,
        hasDrawing: Boolean,
    ): Boolean = hasImages || hasDrawing || text.isEmpty() || !contentComplete || !textComplete

    private fun extractPdfStrings(data: ByteArray, fonts: PageFonts): ExtractedPdfText {
        val latin = String(data, Charsets.ISO_8859_1)
        val lexer = PdfContentLexer(latin)
        val tokens = lexer.tokenize()
        var complete = lexer.complete && !fonts.unresolved
        val texts = mutableListOf<String>()
        val stack = mutableListOf<PdfContentToken>()
        var fontName: String? = null
        val graphicsFonts = mutableListOf<String?>()

        fun encoding(): PdfFontEncoding {
            val name = fontName
            if (name == null) return PdfFontEncoding(known = !fonts.unresolved)
            return fonts.encodings[name] ?: PdfFontEncoding(known = false)
        }

        fun pop(count: Int): List<PdfContentToken>? {
            if (count == 0) return emptyList()
            if (stack.size < count) return null
            val start = stack.size - count
            val taken = stack.subList(start, stack.size).toList()
            repeat(count) { stack.removeAt(stack.lastIndex) }
            return taken
        }

        tokens.forEach { token ->
            if (token !is PdfContentToken.Operator) {
                stack += token
                return@forEach
            }
            when (token.name) {
                "q" -> {
                    if (pop(0) == null) complete = false
                    graphicsFonts += fontName
                }
                "Q" -> {
                    if (pop(0) == null) complete = false
                    if (graphicsFonts.isEmpty()) {
                        complete = false
                    } else {
                        fontName = graphicsFonts.removeAt(graphicsFonts.lastIndex)
                    }
                }
                "Tf" -> {
                    val ops = pop(2)
                    if (ops == null) {
                        complete = false
                    } else {
                        fontName = (ops[0] as? PdfContentToken.Name)?.value
                        if (fontName == null) complete = false
                    }
                }
                "Tj", "'" -> {
                    val ops = pop(1)
                    val shown = ops?.singleOrNull()?.let { decodeShowToken(it, encoding()) }
                    if (shown == null) {
                        complete = false
                    } else {
                        if (!shown.complete) complete = false
                        if (shown.text.isNotBlank()) texts += shown.text
                    }
                }
                "\"" -> {
                    val ops = pop(3)
                    val shown = ops?.getOrNull(2)?.let { decodeShowToken(it, encoding()) }
                    if (shown == null) {
                        complete = false
                    } else {
                        if (!shown.complete) complete = false
                        if (shown.text.isNotBlank()) texts += shown.text
                    }
                }
                "TJ" -> {
                    val ops = pop(1)
                    val array = ops?.singleOrNull() as? PdfContentToken.Array
                    if (array == null) {
                        complete = false
                    } else {
                        val decoded = decodeTjArrayTokens(array.items, encoding())
                        if (!decoded.complete) complete = false
                        if (decoded.text.isNotBlank()) texts += decoded.text
                    }
                }
                else -> {
                    val count = CONTENT_OPERATOR_OPERANDS[token.name]
                    if (count == null) {
                        complete = false
                    } else if (pop(count) == null) {
                        complete = false
                    }
                }
            }
        }
        if (stack.any {
                it is PdfContentToken.Literal || it is PdfContentToken.Hex || it is PdfContentToken.Array
            }
        ) {
            complete = false
        }
        return ExtractedPdfText(texts, complete)
    }

    private data class DecodedShow(val text: String, val complete: Boolean)

    private fun decodeShowToken(token: PdfContentToken, encoding: PdfFontEncoding): DecodedShow? {
        return when (token) {
            is PdfContentToken.Literal -> applyEncoding(token.bytes, encoding)
            is PdfContentToken.Hex -> {
                if (!token.valid || token.bytes == null) DecodedShow("", complete = false)
                else applyEncoding(token.bytes, encoding)
            }
            else -> null
        }
    }

    private fun applyEncoding(bytes: ByteArray, encoding: PdfFontEncoding): DecodedShow {
        if (!encoding.known) {
            return DecodedShow(String(bytes, Charsets.ISO_8859_1), complete = false)
        }
        val out = StringBuilder()
        var complete = true
        for (byte in bytes) {
            val code = byte.toInt() and 0xff
            val mapped = encoding.differences[code] ?: mapBaseByte(encoding.base, code)
            if (mapped == null) {
                complete = false
                out.append(String(byteArrayOf(byte), Charsets.ISO_8859_1))
            } else {
                out.append(mapped)
            }
        }
        return DecodedShow(out.toString(), complete)
    }

    private fun mapBaseByte(base: PdfBaseEncoding, code: Int): String? {
        if (base == PdfBaseEncoding.SYMBOL) return symbolChar(code)
        if (code in 0x20..0x7E) return code.toChar().toString()
        return when (base) {
            PdfBaseEncoding.STANDARD -> null
            PdfBaseEncoding.WIN_ANSI -> winAnsiChar(code)
            PdfBaseEncoding.MAC_ROMAN -> macRomanChar(code)
            PdfBaseEncoding.SYMBOL -> null
        }
    }

    private fun winAnsiChar(code: Int): String? {
        if (code in 0xA0..0xFF) return String(byteArrayOf(code.toByte()), Charsets.ISO_8859_1)
        return when (code) {
            0x80 -> "€"
            0x82 -> "‚"
            0x83 -> "ƒ"
            0x84 -> "„"
            0x85 -> "…"
            0x86 -> "†"
            0x87 -> "‡"
            0x88 -> "ˆ"
            0x89 -> "‰"
            0x8A -> "Š"
            0x8B -> "‹"
            0x8C -> "Œ"
            0x8E -> "Ž"
            0x91 -> "‘"
            0x92 -> "’"
            0x93 -> "“"
            0x94 -> "”"
            0x95 -> "•"
            0x96 -> "–"
            0x97 -> "—"
            0x98 -> "˜"
            0x99 -> "™"
            0x9A -> "š"
            0x9B -> "›"
            0x9C -> "œ"
            0x9E -> "ž"
            0x9F -> "Ÿ"
            else -> null
        }
    }

    private fun macRomanChar(code: Int): String? {
        val mapped = MAC_ROMAN_80.getOrNull(code - 0x80) ?: return null
        return mapped.takeIf { it.isNotEmpty() }
    }

    // PDF MacRomanEncoding 0x80–0xFF. Empty slots are undefined and fail closed.
    private val MAC_ROMAN_80 = arrayOf(
        "Ä", "Å", "Ç", "É", "Ñ", "Ö", "Ü", "á",
        "à", "â", "ä", "ã", "å", "ç", "é", "è",
        "ê", "ë", "í", "ì", "î", "ï", "ñ", "ó",
        "ò", "ô", "ö", "õ", "ú", "ù", "û", "ü",
        "†", "°", "¢", "£", "§", "•", "¶", "ß",
        "®", "©", "™", "´", "¨", "≠", "Æ", "Ø",
        "∞", "±", "≤", "≥", "¥", "µ", "∂", "∑",
        "∏", "π", "∫", "ª", "º", "Ω", "æ", "ø",
        "¿", "¡", "¬", "√", "ƒ", "≈", "∆", "«",
        "»", "…", "\u00A0", "À", "Ã", "Õ", "Œ", "œ",
        "–", "—", "“", "”", "‘", "’", "÷", "◊",
        "ÿ", "Ÿ", "⁄", "€", "‹", "›", "ﬁ", "ﬂ",
        "‡", "·", "‚", "„", "‰", "Â", "Ê", "Á",
        "Ë", "È", "Í", "Î", "Ï", "Ì", "Ó", "Ô",
        "", "Ò", "Ú", "Û", "Ù", "ı", "ˆ", "˜",
        "¯", "˘", "˙", "˚", "¸", "˝", "˛", "ˇ",
    )

    // Adobe Symbol built-in encoding (PDF 32000-1 D.5). Codes absent from these
    // tables are undefined in the set or have no dependable Unicode text mapping
    // (for example the bounding-box glyph fragments) and fail closed.
    private fun symbolChar(code: Int): String? {
        SYMBOL_ASCII_OVERRIDES[code]?.let { return it }
        if (code in 0x20..0x7E) return code.toChar().toString()
        val mapped = SYMBOL_HIGH_80.getOrNull(code - 0x80) ?: return null
        return mapped.takeIf { it.isNotEmpty() }
    }

    // Symbol reassigns part of 0x20-0x7E to Greek letters and math operators.
    private val SYMBOL_ASCII_OVERRIDES: Map<Int, String> = mapOf(
        0x22 to "\u2200", // universal
        0x24 to "\u2203", // existential
        0x27 to "\u220B", // suchthat
        0x2A to "\u2217", // asteriskmath
        0x2D to "\u2212", // minus
        0x40 to "\u2245", // congruent
        0x41 to "\u0391", 0x42 to "\u0392", 0x43 to "\u03A7", 0x44 to "\u0394",
        0x45 to "\u0395", 0x46 to "\u03A6", 0x47 to "\u0393", 0x48 to "\u0397",
        0x49 to "\u0399", 0x4A to "\u03D1", 0x4B to "\u039A", 0x4C to "\u039B",
        0x4D to "\u039C", 0x4E to "\u039D", 0x4F to "\u039F", 0x50 to "\u03A0",
        0x51 to "\u0398", 0x52 to "\u03A1", 0x53 to "\u03A3", 0x54 to "\u03A4",
        0x55 to "\u03A5", 0x56 to "\u03C2", 0x57 to "\u03A9", 0x58 to "\u039E",
        0x59 to "\u03A8", 0x5A to "\u0396",
        0x5C to "\u2234", // therefore
        0x5E to "\u22A5", // perpendicular
        0x60 to "\u203E", // radicalex (overline)
        0x61 to "\u03B1", 0x62 to "\u03B2", 0x63 to "\u03C7", 0x64 to "\u03B4",
        0x65 to "\u03B5", 0x66 to "\u03C6", 0x67 to "\u03B3", 0x68 to "\u03B7",
        0x69 to "\u03B9", 0x6A to "\u03D5", 0x6B to "\u03BA", 0x6C to "\u03BB",
        0x6D to "\u03BC", 0x6E to "\u03BD", 0x6F to "\u03BF", 0x70 to "\u03C0",
        0x71 to "\u03B8", 0x72 to "\u03C1", 0x73 to "\u03C3", 0x74 to "\u03C4",
        0x75 to "\u03C5", 0x76 to "\u03D6", 0x77 to "\u03C9", 0x78 to "\u03BE",
        0x79 to "\u03C8", 0x7A to "\u03B6",
        0x7E to "\u223C", // similar
    )

    // Symbol 0x80-0xFF. Empty slots fail closed, matching the MacRoman table style.
    private val SYMBOL_HIGH_80 = arrayOf(
        "", "", "", "", "", "", "", "",
        "", "", "", "", "", "", "", "",
        "", "", "", "", "", "", "", "",
        "", "", "", "", "", "", "", "",
        "\u20AC", "\u03D2", "\u2032", "\u2264", "\u2044", "\u221E", "\u0192", "\u2663",
        "\u2666", "\u2665", "\u2660", "\u2194", "\u2190", "\u2191", "\u2192", "\u2193",
        "\u00B0", "\u00B1", "\u2033", "\u2265", "\u00D7", "\u221D", "\u2202", "\u2022",
        "\u00F7", "\u2260", "\u2261", "\u2248", "\u2026", "", "", "\u21B5",
        "\u2135", "\u2111", "\u211C", "\u2118", "\u2297", "\u2295", "\u2205", "\u2229",
        "\u222A", "\u2283", "\u2287", "\u2284", "\u2282", "\u2286", "\u2208", "\u2209",
        "\u2220", "\u2207", "\u00AE", "\u00A9", "\u2122", "\u220F", "\u221A", "\u22C5",
        "\u00AC", "\u2227", "\u2228", "\u21D4", "\u21D0", "\u21D1", "\u21D2", "\u21D3",
        "\u25CA", "\u2329", "\u00AE", "\u00A9", "\u2122", "\u2211", "\u239B", "\u239C",
        "\u239D", "\u23A1", "\u23A2", "\u23A3", "\u23A7", "\u23A8", "\u23A9", "\u23AA",
        "", "\u232A", "\u222B", "\u2320", "\u23AE", "\u2321", "\u239E", "\u239F",
        "\u23A0", "\u23A4", "\u23A5", "\u23A6", "\u23AB", "\u23AC", "\u23AD", "",
    )

    private fun decodeTjArrayTokens(
        items: List<PdfContentToken>,
        encoding: PdfFontEncoding,
    ): DecodedShow {
        val out = StringBuilder()
        var insertSpace = false
        var complete = true
        items.forEach { item ->
            when (item) {
                is PdfContentToken.Literal, is PdfContentToken.Hex -> {
                    val decoded = decodeShowToken(item, encoding)
                    if (decoded == null) {
                        complete = false
                        return@forEach
                    }
                    if (!decoded.complete) complete = false
                    if (decoded.text.isEmpty()) return@forEach
                    if (insertSpace && out.isNotEmpty() && !out.last().isWhitespace() &&
                        !decoded.text.first().isWhitespace()
                    ) {
                        out.append(' ')
                    }
                    out.append(decoded.text)
                    insertSpace = false
                }
                is PdfContentToken.Number -> {
                    insertSpace = (item.value.toDoubleOrNull() ?: 0.0) <= -100.0
                }
                else -> complete = false
            }
        }
        return DecodedShow(out.toString(), complete)
    }

    private class PdfContentLexer(private val latin: String) {
        var i = 0
        var complete = true

        fun tokenize(): List<PdfContentToken> {
            val tokens = mutableListOf<PdfContentToken>()
            while (true) {
                skipWsComments()
                if (i >= latin.length) break
                if (isBareOperator("BI")) {
                    i += 2
                    if (!skipInlineImage()) complete = false
                    continue
                }
                val token = readToken()
                if (token == null) {
                    complete = false
                    break
                }
                tokens += token
            }
            return tokens
        }

        private fun skipWsComments() {
            while (i < latin.length) {
                val char = latin[i]
                if (isPdfWhitespace(char)) {
                    i++
                    continue
                }
                if (char == '%') {
                    while (i < latin.length && latin[i] != '\n' && latin[i] != '\r') i++
                    continue
                }
                break
            }
        }

        private fun isBareOperator(name: String): Boolean {
            if (!latin.startsWith(name, i)) return false
            val end = i + name.length
            if (end < latin.length) {
                val next = latin[end]
                if (!isPdfWhitespace(next) && !isPdfDelimiter(next) && next != '\'' && next != '"') {
                    return false
                }
            }
            return i == 0 || isPdfWhitespace(latin[i - 1]) || isPdfDelimiter(latin[i - 1])
        }

        private fun skipInlineImage(): Boolean {
            val idMatch = Regex("(?<![A-Za-z])ID(?![A-Za-z0-9])").find(latin, i) ?: return false
            val eiMatch = Regex("(?<![A-Za-z])EI(?![A-Za-z0-9])").find(latin, idMatch.range.last + 1)
                ?: return false
            i = eiMatch.range.last + 1
            return true
        }

        private fun readToken(): PdfContentToken? {
            if (i >= latin.length) return null
            return when (latin[i]) {
                '(' -> readLiteral()?.let { PdfContentToken.Literal(it) }
                '<' -> if (i + 1 < latin.length && latin[i + 1] == '<') {
                    if (!skipDict()) return null
                    PdfContentToken.Dict()
                } else {
                    readHex()
                }
                '[' -> readArray()?.let { PdfContentToken.Array(it) }
                '/' -> PdfContentToken.Name(readName())
                '\'', '"' -> {
                    val name = latin[i].toString()
                    i++
                    PdfContentToken.Operator(name)
                }
                ']', ')', '>' -> null
                else -> {
                    val word = readRegular()
                    if (word.isEmpty()) {
                        i++
                        return null
                    }
                    if (isPdfNumber(word)) PdfContentToken.Number(word) else PdfContentToken.Operator(word)
                }
            }
        }

        private fun readName(): String {
            i++
            val start = i
            while (i < latin.length && !isPdfWhitespace(latin[i]) && !isPdfDelimiter(latin[i])) i++
            return decodePdfName(latin.substring(start, i))
        }

        private fun readRegular(): String {
            val start = i
            while (i < latin.length && !isPdfWhitespace(latin[i]) && !isPdfDelimiter(latin[i]) &&
                latin[i] != '\'' && latin[i] != '"'
            ) {
                i++
            }
            return latin.substring(start, i)
        }

        private fun readLiteral(): ByteArray? {
            if (latin[i] != '(') return null
            i++
            val out = ByteArrayOutputStream()
            var depth = 1
            while (i < latin.length && depth > 0) {
                val char = latin[i]
                if (char == '\\') {
                    val decoded = readEscape() ?: return null
                    if (decoded >= 0) out.write(decoded)
                    continue
                }
                if (char == '(') {
                    depth++
                    out.write('('.code)
                    i++
                    continue
                }
                if (char == ')') {
                    depth--
                    i++
                    if (depth == 0) break
                    out.write(')'.code)
                    continue
                }
                out.write(char.code)
                i++
            }
            return if (depth == 0) out.toByteArray() else null
        }

        private fun readEscape(): Int? {
            if (i + 1 >= latin.length) {
                i++
                return null
            }
            i++
            return when (val next = latin[i]) {
                'n' -> {
                    i++
                    '\n'.code
                }
                'r' -> {
                    i++
                    '\r'.code
                }
                't' -> {
                    i++
                    '\t'.code
                }
                'b' -> {
                    i++
                    8
                }
                'f' -> {
                    i++
                    12
                }
                '(' -> {
                    i++
                    '('.code
                }
                ')' -> {
                    i++
                    ')'.code
                }
                '\\' -> {
                    i++
                    '\\'.code
                }
                '\n' -> {
                    i++
                    if (i < latin.length && latin[i] == '\r') i++
                    -1
                }
                '\r' -> {
                    i++
                    if (i < latin.length && latin[i] == '\n') i++
                    -1
                }
                in '0'..'7' -> {
                    var value = 0
                    var count = 0
                    while (count < 3 && i < latin.length && latin[i] in '0'..'7') {
                        value = value * 8 + (latin[i] - '0')
                        i++
                        count++
                    }
                    value and 0xff
                }
                else -> {
                    i++
                    next.code
                }
            }
        }

        private fun readHex(): PdfContentToken.Hex {
            i++
            val hex = StringBuilder()
            var valid = true
            while (i < latin.length && latin[i] != '>') {
                val char = latin[i]
                if (isPdfWhitespace(char)) {
                    i++
                    continue
                }
                if (char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F') {
                    hex.append(char)
                    i++
                } else {
                    valid = false
                    i++
                }
            }
            if (i >= latin.length || latin[i] != '>') {
                return PdfContentToken.Hex(null, valid = false)
            }
            i++
            if (!valid) return PdfContentToken.Hex(null, valid = false)
            val padded = if (hex.length % 2 == 1) hex.toString() + "0" else hex.toString()
            val bytes = ByteArray(padded.length / 2)
            for (index in bytes.indices) {
                bytes[index] = padded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            return PdfContentToken.Hex(bytes, valid = true)
        }

        private fun readArray(): List<PdfContentToken>? {
            i++
            val items = mutableListOf<PdfContentToken>()
            while (true) {
                skipWsComments()
                if (i >= latin.length) return null
                if (latin[i] == ']') {
                    i++
                    return items
                }
                val token = readToken() ?: return null
                if (token is PdfContentToken.Operator) return null
                items += token
            }
        }

        private fun skipDict(): Boolean {
            if (!latin.startsWith("<<", i)) return false
            var depth = 0
            while (i + 1 < latin.length) {
                when {
                    latin.startsWith("<<", i) -> {
                        depth++
                        i += 2
                    }
                    latin.startsWith(">>", i) -> {
                        depth--
                        i += 2
                        if (depth == 0) return true
                    }
                    latin[i] == '(' -> {
                        if (readLiteral() == null) return false
                    }
                    latin[i] == '<' && (i + 1 >= latin.length || latin[i + 1] != '<') -> {
                        readHex()
                    }
                    else -> i++
                }
            }
            return false
        }
    }

    private fun isPdfNumber(value: String): Boolean =
        Regex("^[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)$").matches(value)

    private val CONTENT_OPERATOR_OPERANDS = mapOf(
        "BT" to 0, "ET" to 0,
        "Td" to 2, "TD" to 2, "Tm" to 6, "T*" to 0,
        "Tc" to 1, "Tw" to 1, "Tz" to 1, "TL" to 1, "Ts" to 1, "Tr" to 1,
        "q" to 0, "Q" to 0, "n" to 0, "h" to 0,
        "S" to 0, "s" to 0, "f" to 0, "F" to 0, "f*" to 0,
        "B" to 0, "B*" to 0, "b" to 0, "b*" to 0, "W" to 0, "W*" to 0,
        "cm" to 6, "Do" to 1, "gs" to 1,
        "re" to 4, "m" to 2, "l" to 2, "c" to 6, "v" to 4, "y" to 4,
        "w" to 1, "J" to 1, "j" to 1, "M" to 1, "i" to 1, "d" to 2,
        "G" to 1, "g" to 1, "RG" to 3, "rg" to 3, "K" to 4, "k" to 4,
        "CS" to 1, "cs" to 1, "ri" to 1, "sh" to 1,
        "BMC" to 1, "EMC" to 0, "MP" to 1, "BDC" to 2, "DP" to 2,
        "BX" to 0, "EX" to 0,
    )

    private fun hasVectorDrawing(latin: String): Boolean {
        val stripped = latin.replace(Regex("BT[\\s\\S]*?ET"), " ")
        return Regex("(?<![A-Za-z])(re|m|l|c|v|y)\\s").containsMatchIn(stripped) &&
            Regex("(?<![A-Za-z])(f|f\\*|F|B|b|S|s)\\s").containsMatchIn(stripped)
    }

    private fun hasInlineImage(latin: String): Boolean {
        val stripped = latin.replace(Regex("BT[\\s\\S]*?ET"), " ")
        return Regex("(?<![A-Za-z])BI\\b[\\s\\S]*?\\bID\\b[\\s\\S]*?\\bEI\\b").containsMatchIn(stripped)
    }

    private fun extractInlineImages(decoded: ByteArray): List<ByteArray> {
        val latin = String(decoded, Charsets.ISO_8859_1)
        val matches = Regex("(?<![A-Za-z])BI\\b([\\s\\S]*?)\\bID\\b([\\s\\S]*?)\\bEI\\b").findAll(latin)
        return matches.mapNotNull { match ->
            val payload = match.groupValues[2].trimStart { it == ' ' || it == '\n' || it == '\r' || it == '\t' }
            payload.toByteArray(Charsets.ISO_8859_1).takeIf { it.isNotEmpty() }
        }.toList()
    }

    private fun encodedImageMediaType(payload: ByteArray): String? = when {
        payload.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "image/jpeg"
        payload.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        payload.startsWith(byteArrayOf(0x47, 0x49, 0x46, 0x38)) -> "image/gif"
        else -> null
    }

    private fun xObjectMediaType(dict: String, payload: ByteArray): String? =
        encodedImageMediaType(payload)
            ?.takeIf { it == "image/jpeg" && streamFilters(dict) == listOf("DCTDecode") }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun deflateBytes(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun jpegStub(): ByteArray {
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        return header
    }

    private fun assemblePages(
        pages: List<PageContent>,
        extraObjects: List<Pair<String, ByteArray>> = emptyList(),
        fontDicts: List<String> = listOf("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
        contentDictSuffix: String = "",
        deflateContent: Boolean = false,
    ): ByteArray {
        val n = pages.size
        val objects = mutableListOf<ByteArray>()
        fun obj(body: String) = body.toByteArray(Charsets.ISO_8859_1)
        val fontObj = 3 + (2 * n)
        val firstExtra = fontObj + fontDicts.size
        objects += obj("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val kids = (0 until n).joinToString(" ") { "${3 + it} 0 R" }
        objects += obj("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count $n >>\nendobj\n")
        pages.forEachIndexed { index, page ->
            val pageObj = 3 + index
            val contentObj = 3 + n + index
            val resources = page.resources
                .replace("FONT2", "${fontObj + 1} 0 R")
                .replace("FONT", "$fontObj 0 R")
                .replace("IMAGE", "$firstExtra 0 R")
            objects += obj(
                "$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents $contentObj 0 R /Resources << $resources >> >>\nendobj\n",
            )
        }
        pages.forEachIndexed { index, page ->
            val contentObj = 3 + n + index
            val plain = page.content.toByteArray(Charsets.ISO_8859_1)
            val contentBytes = if (deflateContent) deflateBytes(plain) else plain
            objects += obj("$contentObj 0 obj\n<< /Length ${contentBytes.size}$contentDictSuffix >>\nstream\n") +
                contentBytes + obj("\nendstream\nendobj\n")
        }
        fontDicts.forEachIndexed { index, fontDict ->
            objects += obj("${fontObj + index} 0 obj\n$fontDict\nendobj\n")
        }
        extraObjects.forEachIndexed { index, (dictPrefix, payload) ->
            val num = firstExtra + index
            objects += obj("$num 0 obj\n$dictPrefix") + payload + obj("\nendstream\nendobj\n")
        }
        val header = "%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        out.write(header)
        val offsets = mutableListOf<Int>()
        objects.forEach { body ->
            offsets += out.size()
            out.write(body)
        }
        val xrefAt = out.size()
        val count = objects.size + 1
        val xref = buildString {
            append("xref\n0 $count\n")
            append("0000000000 65535 f \n")
            offsets.forEach { off -> append("%010d 00000 n \n".format(off)) }
            append("trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        }
        out.write(xref.toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }
}
