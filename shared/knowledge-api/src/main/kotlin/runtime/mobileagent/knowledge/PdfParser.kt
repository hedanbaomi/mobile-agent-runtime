// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

object PdfParser {
    const val FINGERPRINT = "pdf-text-v20-pdfrenderer"

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

    fun parse(bytes: ByteArray, rasterizer: PdfPageRasterizer? = null, deferImagePayloads: Boolean = false): ParsedPublication {
        if (bytes.size < 5 || String(bytes.copyOfRange(0, 5), Charsets.ISO_8859_1) != "%PDF-") {
            error("Not a PDF")
        }
        val latin = PdfByteView(bytes)
        val objects = extractIndirectObjects(bytes, latin)
        val pageNumbers = pageKids(objects).ifEmpty {
            objects.filter { (_, obj) -> isPageDict(obj.dict) }.keys.sorted()
        }
        val assets = mutableListOf<ExtractedAsset>()
        val pages = mutableListOf<ExtractedPage>()
        var imageOrdinal = 0

        // Text extraction and visual classification happen before rasterizing.
        // Rendering only the pages that need visual evidence keeps a text-only
        // PDF cheap and leaves renderer failure visible through PAGE blockers.
        val pagesNeedingRaster = if (rasterizer == null) emptyList() else pageNumbers.mapIndexedNotNull { index, objNum ->
            val pageObj = objects[objNum] ?: return@mapIndexedNotNull null
            val content = pageContent(objects, pageObj.dict)
            val decoded = content.bytes
            val pageLatin = String(decoded, Charsets.ISO_8859_1)
            val fonts = pageFonts(objects, objNum, pageObj.dict)
            val resolvedXObjects = pageXObjects(objects, objNum, pageObj.dict)
            val discovery = discoverForms(objects, decoded, resolvedXObjects,
                pageBox = pageMediaBox(objects, objNum, pageObj.dict))
            val pageText = extractPdfStrings(decoded, fonts)
            val extracted = ExtractedPdfText(pageText.texts + discovery.texts,
                pageText.complete && discovery.complete)
            val text = extracted.joined()
            val hasInline = hasInlineImage(pageLatin)
            val hasUnresolvedXObjects = !discovery.complete
            val hasImages = discovery.images.isNotEmpty() || hasUnresolvedXObjects || hasInline ||
                Regex("/Subtype\\s*/Image").containsMatchIn(pageObj.dict)
            val visibleAnnotations = hasVisibleOrUnknownAnnotations(objects, pageObj.dict)
            val hasDrawing = discovery.hasDrawing || hasVectorDrawing(pageLatin) && !(
                text.isNotBlank() && extracted.complete && content.complete && !hasImages && !visibleAnnotations &&
                    hasOnlyDecorativeGraphics(pageLatin, pageMediaBox(objects, objNum, pageObj.dict))
                )
            val blankPage = isEmptyPageWithoutVisuals(pageObj.dict, content, hasImages || visibleAnnotations)
            if (!blankPage && pageNeedsVision(text, extracted.complete, content.complete,
                    hasImages || visibleAnnotations, hasDrawing) &&
                !(discovery.usedForm.not() && canProcessIllustrationsSeparately(text, extracted.complete, content.complete, pageLatin,
                    discovery.images, objects, hasUnresolvedXObjects, hasInline, visibleAnnotations,
                    hasPageAppearanceModifiers(objects, objNum, pageObj.dict),
                    pageMediaBox(objects, objNum, pageObj.dict),
                    pageNamedResources(objects, objNum, pageObj.dict, "ExtGState")))) {
                index + 1
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
            val resolvedXObjects = pageXObjects(objects, objNum, pageObj.dict)
            val discovery = discoverForms(objects, decoded, resolvedXObjects,
                pageBox = pageMediaBox(objects, objNum, pageObj.dict))
            val pageText = extractPdfStrings(decoded, fonts)
            val extracted = ExtractedPdfText(pageText.texts + discovery.texts,
                pageText.complete && discovery.complete)
            val text = extracted.joined()
            val xobjects = discovery.images
            val hasUnresolvedXObjects = !discovery.complete
            val hasImages = xobjects.isNotEmpty() || hasUnresolvedXObjects || hasInlineImage(pageLatin) ||
                Regex("/Subtype\\s*/Image").containsMatchIn(pageObj.dict)
            val visibleAnnotations = hasVisibleOrUnknownAnnotations(objects, pageObj.dict)
            val hasDrawing = discovery.hasDrawing || hasVectorDrawing(pageLatin) && !(
                text.isNotBlank() && extracted.complete && content.complete && !hasImages && !visibleAnnotations &&
                    hasOnlyDecorativeGraphics(pageLatin, pageMediaBox(objects, objNum, pageObj.dict))
                )
            // Decorative marks around illustrations are not missing page evidence.
            val visualAssetsOnly = !discovery.usedForm && canProcessIllustrationsSeparately(text, extracted.complete, content.complete,
                pageLatin, xobjects, objects, hasUnresolvedXObjects, hasInlineImage(pageLatin), visibleAnnotations,
                hasPageAppearanceModifiers(objects, objNum, pageObj.dict),
                pageMediaBox(objects, objNum, pageObj.dict),
                pageNamedResources(objects, objNum, pageObj.dict, "ExtGState"))
            var hasUnsupportedPageVisual = (hasDrawing && !visualAssetsOnly) || !content.complete ||
                hasUnresolvedXObjects || !extracted.complete || visibleAnnotations ||
                (discovery.usedForm && discovery.images.isNotEmpty())
            xobjects.forEach { (name, imageObjNum) ->
                val image = objects[imageObjNum]
                if (image == null || !isImageDict(image.dict) || !image.hasStream) {
                    hasUnsupportedPageVisual = true
                    return@forEach
                }
                val payload = image.payload!!
                val mediaType = xObjectMediaType(image.dict, payload.prefix())
                if (mediaType == null) {
                    hasUnsupportedPageVisual = true
                    return@forEach
                }
                imageOrdinal += 1
                val usedOnPage = pageLatin.contains("/$name") || Regex("/${Regex.escape(name)}\\s+Do").containsMatchIn(pageLatin)
                val eagerBytes = if (deferImagePayloads) ByteArray(0) else payload.read() ?: error("PDF image payload unavailable")
                assets += ExtractedAsset(
                    localId = "img-$imageOrdinal",
                    kind = "IMAGE",
                    page = if (usedOnPage || xobjects.size == 1) pageIndex else pageIndex,
                    section = name,
                    bytes = eagerBytes,
                    mediaType = mediaType,
                    surroundingText = text,
                    byteLength = if (deferImagePayloads) payload.size else eagerBytes.size,
                    byteSource = if (deferImagePayloads) ({ payload.read() ?: error("PDF image payload unavailable") }) else null,
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
            val needsVision = !isEmptyPageWithoutVisuals(pageObj.dict, content, hasImages || visibleAnnotations) &&
                pageNeedsVision(text, extracted.complete, content.complete, hasImages || visibleAnnotations, hasDrawing)
            var geometryDict = pageObj.dict
            val visitedGeometry = mutableSetOf(objNum)
            while (!geometryDict.contains("/MediaBox")) {
                val parent = dictionaryOrReference(geometryDict, "Parent").reference ?: break
                if (!visitedGeometry.add(parent)) break
                geometryDict = objects[parent]?.dict ?: break
            }
            val mediaBox = Regex("/MediaBox\\s*\\[\\s*([-+.0-9]+)\\s+([-+.0-9]+)\\s+([-+.0-9]+)\\s+([-+.0-9]+)\\s*]")
                .find(geometryDict)?.groupValues?.drop(1)?.mapNotNull { it.toDoubleOrNull() }
            val pageWidth = mediaBox?.takeIf { it.size == 4 }?.let { kotlin.math.abs(it[2] - it[0]).toInt().coerceAtLeast(1) } ?: 612
            val pageHeight = mediaBox?.takeIf { it.size == 4 }?.let { kotlin.math.abs(it[3] - it[1]).toInt().coerceAtLeast(1) } ?: 792
            val complexLayout = hasDrawing && Regex("(?<![A-Za-z])(re|m|l|c|v|y)\\s").findAll(pageLatin).take(12).count() >= 12
            pages += ExtractedPage(pageIndex, text, needsVision, pageWidth, pageHeight,
                complexLayout = complexLayout, visualAssetsOnly = visualAssetsOnly)
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
                (lacksCompletePageEvidence || assets.none { it.page == pageIndex && it.kind == "IMAGE" && it.byteLength > 0 })
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
        if (pages.isEmpty() && assets.isEmpty()) {
            error("PDF has no extractable pages or text")
        }
        val orderedPages = pages.ifEmpty { listOf(ExtractedPage(1, "", needsVision = true)) }
        if (orderedPages.all { it.text.isBlank() } && assets.isEmpty()) {
            error("PDF has no extractable text or visual content")
        }
        val needsVision = orderedPages.any { it.needsVision } || assets.any { it.kind == "IMAGE" || it.kind == "PAGE" }
        return ParsedPublication(
            format = SourceFormat.PDF,
            text = orderedPages.filter { it.text.isNotBlank() }.joinToString("\n") { page ->
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

    fun writePdfWithImageXObject(
        label: String,
        jpegBytes: ByteArray = jpegStub(),
        pageDictSuffix: String = "",
        imageDictSuffix: String = "",
        imageTransform: String = "100 0 0 100 72 400",
        textPrelude: String = "",
        extraResources: String = "",
    ): ByteArray {
        val escaped = label.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val jpeg = jpegBytes
        val imageObj = buildString {
            append("<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ")
            append(jpeg.size)
            append(imageDictSuffix)
            append(" >>\nstream\n")
        }
        return assemblePages(
            pages = listOf(
                PageContent(
                    "BT /F1 12 Tf 72 700 Td $textPrelude($escaped) Tj ET\nq $imageTransform cm /Im1 Do Q\n",
                    "/Font << /F1 FONT >> /XObject << /Im1 IMAGE >> $extraResources",
                ),
            ),
            extraObjects = listOf(imageObj to jpeg),
            pageDictSuffix = pageDictSuffix,
        )
    }

    fun writeTwoPagePdfWithImageXObject(jpegBytes: ByteArray): ByteArray {
        val imageObj = "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpegBytes.size} >>\nstream\n"
        return assemblePages(
            pages = listOf("First native page", "Second native page").map { label ->
                PageContent("BT /F1 12 Tf 72 700 Td ($label) Tj ET\nq 100 0 0 100 72 400 cm /Im1 Do Q\n",
                    "/Font << /F1 FONT >> /XObject << /Im1 IMAGE >>")
            },
            extraObjects = listOf(imageObj to jpegBytes),
        )
    }

    private data class PdfObject(val dict: String, val payload: PdfStream?) {
        val hasStream: Boolean get() = payload != null
        val stream: ByteArray? get() = payload?.read()
    }
    private class PdfStream(
        private val source: ByteArray, private val start: Int, private val end: Int,
        private val cache: Boolean, private val transform: ((ByteArray) -> ByteArray?)? = null,
    ) {
        val size: Int get() = end - start
        private var loaded = false
        private var stored: ByteArray? = null
        fun read(): ByteArray? {
            if (cache && loaded) return stored
            val raw = source.copyOfRange(start, end)
            val bytes = if (transform == null) raw else transform.invoke(raw)
            if (cache) { stored = bytes; loaded = true }
            return bytes
        }
        fun prefix(): ByteArray = if (transform == null) source.copyOfRange(start, minOf(end, start + 8))
            else read()?.let { it.copyOfRange(0, minOf(8, it.size)) } ?: ByteArray(0)
        fun completeJpeg(): Boolean {
            if (transform != null) return read()?.let { bytes ->
                bytes.size > 64 && bytes.takeLast(2) == listOf(0xFF.toByte(), 0xD9.toByte()) &&
                    bytes.indices.any { it + 1 < bytes.size && bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xDA.toByte() }
            } == true
            return size > 64 && source[end - 2] == 0xFF.toByte() && source[end - 1] == 0xD9.toByte() &&
                (start until end - 1).any { source[it] == 0xFF.toByte() && source[it + 1] == 0xDA.toByte() }
        }
        fun decrypted(decrypt: (ByteArray) -> ByteArray?): PdfStream = PdfStream(source, start, end, cache, decrypt)
    }
    /** A byte-backed view for object offsets: never materializes the entire binary PDF as text. */
    private class PdfByteView(private val bytes: ByteArray) {
        val length: Int get() = bytes.size
        operator fun get(index: Int): Char = (bytes[index].toInt() and 255).toChar()
        fun substring(start: Int, end: Int = length): String = String(bytes, start, end - start, Charsets.ISO_8859_1)
        fun startsWith(value: String, start: Int): Boolean = start >= 0 && value.length <= length - start &&
            value.indices.all { this[start + it] == value[it] }
        fun indexOf(value: String, from: Int): Int {
            for (index in from.coerceAtLeast(0)..length - value.length) if (startsWith(value, index)) return index
            return -1
        }
        fun lastIndexOf(value: String): Int {
            for (index in length - value.length downTo 0) if (startsWith(value, index)) return index
            return -1
        }
    }
    private data class ObjectHeader(val number: Int, val end: Int)
    private fun objectHeader(latin: PdfByteView, from: Int): ObjectHeader? {
        var index = from
        while (index < latin.length) {
            if (latin[index] !in '0'..'9') { index++; continue }
            val start = index
            while (index < latin.length && latin[index] in '0'..'9') index++
            val digitsEnd = index
            fun skipSpace(): Boolean {
                val before = index
                while (index < latin.length && latin[index] in " \t\r\n\u000B\u000C") index++
                return before != index
            }
            if (skipSpace() && index < latin.length && latin[index++] == '0' && skipSpace() && latin.startsWith("obj", index)) {
                return ObjectHeader(latin.substring(start, digitsEnd).toInt(), index + 3)
            }
            index = digitsEnd + 1
        }
        return null
    }
    private enum class PdfCrypt { IDENTITY, RC4, AES }
    private data class PdfSecurity(val key: ByteArray, val streamCrypt: PdfCrypt, val encryptMetadata: Boolean)
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
    private data class FormDiscovery(
        val texts: List<String>,
        val images: Map<String, Int>,
        val complete: Boolean,
        val hasDrawing: Boolean,
        val usedForm: Boolean,
    )

    private fun invokedXObjects(content: ByteArray): Pair<List<String>, Boolean> {
        val lexer = PdfContentLexer(String(content, Charsets.ISO_8859_1))
        val tokens = lexer.tokenize()
        val result = mutableListOf<String>()
        var complete = lexer.complete
        var operands = mutableListOf<PdfContentToken>()
        var inText = false
        tokens.forEach { token ->
            if (token !is PdfContentToken.Operator) {
                operands += token
            } else {
                when (token.name) {
                    "BT" -> inText = true
                    "ET" -> inText = false
                    "Do" -> {
                        val name = (operands.singleOrNull() as? PdfContentToken.Name)?.value
                        if (name == null || inText) complete = false else result += name
                    }
                }
                operands.clear()
            }
        }
        return result to (complete && !inText)
    }

    /** Follow only painted forms. A failed branch still contributes any safe text, but forces page Vision. */
    private fun discoverForms(
        objects: Map<Int, PdfObject>,
        content: ByteArray,
        resources: PageXObjects,
        pageBox: List<Double>?,
        depth: Int = 0,
        active: Set<Int> = emptySet(),
        budget: IntArray = intArrayOf(0),
    ): FormDiscovery {
        val (uses, syntaxComplete) = invokedXObjects(content)
        val images = linkedMapOf<String, Int>()
        val texts = mutableListOf<String>()
        var complete = syntaxComplete && !resources.unresolved
        var drawing = false
        var usedForm = false
        uses.forEach { name ->
            val number = resources.entries[name]
            val obj = number?.let(objects::get)
            if (obj == null) { complete = false; return@forEach }
            when (namedDictionaryOrReference(obj.dict, "Subtype").name) {
                "Image" -> images[name] = number
                "Form" -> {
                    usedForm = true
                    if (depth >= 12 || number in active || obj.stream == null) {
                        complete = false
                        return@forEach
                    }
                    val decoded = decodeContentStream(obj)
                    if (!decoded.complete || decoded.bytes.size > MAX_PDF_STREAM_BYTES - budget[0]) {
                        complete = false
                        return@forEach
                    }
                    budget[0] += decoded.bytes.size
                    // A Form's own resources define its names. No guessing from
                    // the page's dictionary when they are absent or malformed.
                    val fonts = pageFonts(objects, number, obj.dict)
                    // A Form starts with the caller's graphics state. Without
                    // its own Tf we cannot know the inherited font encoding.
                    val extracted = extractPdfStrings(decoded.bytes, fonts,
                        requireExplicitFont = true)
                    var formComplete = extracted.complete &&
                        (extracted.texts.isEmpty() || fonts.encodings.isNotEmpty())
                    // Appearance operators inside the Form can hide or alter
                    // text just as they can in its calling content stream.
                    if (hasUnsafeFormPlacement(decoded.bytes)) formComplete = false
                    val formResources = pageXObjects(objects, number, obj.dict)
                    val nested = discoverForms(objects, decoded.bytes, formResources, pageBox,
                        depth + 1, active + number, budget)
                    nested.images.forEach { (nestedName, imageNumber) -> images["$name/$nestedName"] = imageNumber }
                    if (!nested.complete) formComplete = false
                    drawing = drawing || nested.hasDrawing ||
                        hasVectorDrawing(String(decoded.bytes, Charsets.ISO_8859_1))
                    // Transparency, optional visibility and clipping can make
                    // extracted strings differ from what the page displays.
                    if (listOf("Group", "OC", "SMask", "Ref", "Subtype2")
                            .any { findTopLevelValueStart(obj.dict, it) >= 0 }) formComplete = false
                    val box = arrayBody(obj.dict, "BBox").body?.trim()?.split(Regex("\\s+"))
                        ?.mapNotNull(String::toDoubleOrNull)
                    if (pageBox == null || box?.size != 4 || box.zip(pageBox).any { (a, b) ->
                            kotlin.math.abs(a - b) > AXIS_TOLERANCE }) formComplete = false
                    val matrix = arrayBody(obj.dict, "Matrix")
                    if (matrix.present) {
                        val values = matrix.body?.trim()?.split(Regex("\\s+"))?.mapNotNull(String::toDoubleOrNull)
                        if (values != listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)) formComplete = false
                    }
                    // Unproven Form strings must never enter native chunks;
                    // the whole-page Vision evidence supplies their replacement.
                    if (formComplete) texts += extracted.texts + nested.texts else complete = false
                }
                else -> complete = false
            }
        }
        if (usedForm && hasUnsafeFormPlacement(content)) {
            complete = false
            texts.clear()
        }
        return FormDiscovery(texts, images, complete, drawing, usedForm)
    }

    private fun hasUnsafeFormPlacement(content: ByteArray): Boolean {
        val lexer = PdfContentLexer(String(content, Charsets.ISO_8859_1))
        val tokens = lexer.tokenize()
        return !lexer.complete || tokens.any { token ->
            (token as? PdfContentToken.Operator)?.name in setOf("cm", "W", "W*", "gs", "Tr", "sh")
        }
    }
    private data class PageFonts(
        val encodings: Map<String, PdfFontEncoding>,
        val unresolved: Boolean,
    )
    private data class PdfFontEncoding(
        val known: Boolean,
        val differences: Map<Int, String?> = emptyMap(),
        val base: PdfBaseEncoding? = PdfBaseEncoding.STANDARD,
        val toUnicode: Map<Long, String?>? = null,
        val codeBytes: Int = 1,
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

    private fun extractIndirectObjects(bytes: ByteArray, latin: PdfByteView): Map<Int, PdfObject> {
        val scanned = linkedMapOf<Int, ScannedObject>()
        // Scan byte offsets directly: an ICU regex matcher over the binary
        // file would materialize and retain a second whole-file text buffer.
        var position = 0
        while (true) {
            val header = objectHeader(latin, position) ?: break
            val number = header.number
            val bodyStart = header.end
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
            position = next.coerceIn(0, latin.length)
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
                PdfStream(bytes, finalBounds.dataStart, finalBounds.dataEnd, cache = !isImageDict(candidate.dict))
            } else {
                // Keep the object dictionary and its endobj boundary, but
                // discard oversized bytes so later parsing fails closed.
                null
            }
            out[number] = PdfObject(candidate.dict, raw)
        }

        val security = pdfSecurity(latin, out)
        if (security != null && security.second.streamCrypt != PdfCrypt.IDENTITY) {
            out.toMap().forEach { (number, obj) ->
                if (obj.hasStream && number != security.first &&
                    !Regex("/Type\\s*/XRef\\b").containsMatchIn(obj.dict) &&
                    (security.second.encryptMetadata || !Regex("/Type\\s*/Metadata\\b").containsMatchIn(obj.dict))) {
                    out[number] = obj.copy(payload = obj.payload!!.decrypted { decryptPdfStream(it, security.second, number) })
                }
            }
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

    /** Authenticate the empty user password before any encrypted stream is interpreted as PDF syntax. */
    private fun pdfSecurity(latin: PdfByteView, objects: Map<Int, PdfObject>): Pair<Int, PdfSecurity>? {
        val trailerStart = latin.lastIndexOf("trailer")
        val trailer = if (trailerStart >= 0) dictionaryBody(latin.substring(trailerStart)) else
            objects.values.firstOrNull { Regex("/Type\\s*/XRef\\b").containsMatchIn(it.dict) }?.dict
        if (trailer == null) {
            require(objects.values.none { dictionaryOrReference(it.dict, "Encrypt").reference != null }) { "Unsupported PDF encryption trailer" }
            return null
        }
        val reference = dictionaryOrReference(trailer, "Encrypt")
        if (!reference.present) return null
        require(!reference.malformed && reference.reference != null) { "Unsupported PDF encryption dictionary" }
        val encryptNumber = reference.reference
        val dict = requireNotNull(objects[encryptNumber]?.dict) { "Missing PDF encryption dictionary" }
        require(namedDictionaryOrReference(dict, "Filter").name == "Standard" &&
            Regex("/V\\s+4\\b").containsMatchIn(dict) && Regex("/R\\s+4\\b").containsMatchIn(dict) &&
            Regex("/Length\\s+128\\b").containsMatchIn(dict)) { "Unsupported PDF encryption" }
        val idBody = arrayBody(trailer, "ID").body ?: error("Missing encrypted PDF file identifier")
        val id = pdfStringAt(idBody, 0) ?: error("Unsupported encrypted PDF file identifier")
        require(id.isNotEmpty()) { "Empty encrypted PDF file identifier" }
        val owner = pdfBytesValue(dict, "O", 32)
        val user = pdfBytesValue(dict, "U", 32)
        val permissionsStart = findTopLevelValueStart(dict, "P")
        val permissions = if (permissionsStart >= 0) Regex("[-+]?\\d+").matchAt(dict, permissionsStart)
            ?.value?.toLongOrNull()?.takeIf { it in Int.MIN_VALUE.toLong()..0xFFFFFFFFL }?.toInt() else null
        require(permissions != null) { "Invalid PDF permissions" }
        val metadataStart = findTopLevelValueStart(dict, "EncryptMetadata")
        val encryptMetadata = when {
            metadataStart < 0 -> true
            Regex("true\\b").matchAt(dict, metadataStart) != null -> true
            Regex("false\\b").matchAt(dict, metadataStart) != null -> false
            else -> error("Invalid PDF metadata encryption flag")
        }
        val digest = MessageDigest.getInstance("MD5")
        digest.update(PDF_PASSWORD_PADDING)
        digest.update(owner)
        digest.update(byteArrayOf(permissions.toByte(), (permissions ushr 8).toByte(),
            (permissions ushr 16).toByte(), (permissions ushr 24).toByte()))
        digest.update(id)
        if (!encryptMetadata) digest.update(byteArrayOf(-1, -1, -1, -1))
        var key = digest.digest()
        repeat(50) { key = MessageDigest.getInstance("MD5").digest(key) }
        val seed = MessageDigest.getInstance("MD5").digest(PDF_PASSWORD_PADDING + id)
        var check = seed
        for (round in 0..19) check = rc4(check, key.map { (it.toInt() xor round).toByte() }.toByteArray())
        require(check.copyOfRange(0, 16).contentEquals(user.copyOfRange(0, 16))) {
            "Encrypted PDF requires a non-empty user password"
        }
        val streamFilter = namedDictionaryOrReference(dict, "StmF").let { if (it.present) it.name else "Identity" }
        val stringFilter = namedDictionaryOrReference(dict, "StrF").let { if (it.present) it.name else "Identity" }
        require(streamFilter == stringFilter) { "Unsupported mixed PDF crypt filters" }
        val crypt = when (streamFilter) {
            "Identity" -> PdfCrypt.IDENTITY
            "StdCF" -> {
                val cf = dictionaryOrReference(dict, "CF").dictionary ?: error("Missing PDF crypt filter")
                val std = dictionaryOrReference(cf, "StdCF").dictionary ?: error("Missing standard PDF crypt filter")
                when (namedDictionaryOrReference(std, "CFM").name) {
                    "V2" -> PdfCrypt.RC4
                    "AESV2" -> PdfCrypt.AES
                    else -> error("Unsupported PDF crypt method")
                }
            }
            else -> error("Unsupported PDF crypt filter")
        }
        val embeddedFilter = namedDictionaryOrReference(dict, "EFF")
        require(!embeddedFilter.present || embeddedFilter.name == streamFilter) {
            "Unsupported embedded-file crypt filter"
        }
        return encryptNumber to PdfSecurity(key, crypt, encryptMetadata)
    }

    private val PDF_PASSWORD_PADDING = byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(), 0x2F,
        0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
    )

    private fun pdfStringAt(text: String, start: Int): ByteArray? {
        if (start !in 0..text.length) return null
        return when (val token = PdfContentLexer(text.substring(start)).firstToken()) {
            is PdfContentToken.Literal -> token.bytes
            is PdfContentToken.Hex -> token.bytes.takeIf { token.valid }
            else -> null
        }
    }

    private fun pdfBytesValue(dict: String, name: String, size: Int): ByteArray {
        val start = findTopLevelValueStart(dict, name)
        val value = pdfStringAt(dict, start)
        require(value?.size == size) { "Invalid encrypted PDF $name value" }
        return value
    }

    private fun rc4(input: ByteArray, key: ByteArray): ByteArray {
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
            val old = state[i]; state[i] = state[j]; state[j] = old
        }
        val result = ByteArray(input.size)
        var i = 0
        j = 0
        input.indices.forEach { index ->
            i = (i + 1) and 0xff
            j = (j + state[i]) and 0xff
            val old = state[i]; state[i] = state[j]; state[j] = old
            result[index] = (input[index].toInt() xor state[(state[i] + state[j]) and 0xff]).toByte()
        }
        return result
    }

    private fun decryptPdfStream(input: ByteArray, security: PdfSecurity, number: Int): ByteArray {
        val salt = if (security.streamCrypt == PdfCrypt.AES) "sAlT".toByteArray(Charsets.US_ASCII) else byteArrayOf()
        val material = security.key + byteArrayOf(number.toByte(), (number ushr 8).toByte(),
            (number ushr 16).toByte(), 0, 0) + salt
        val key = MessageDigest.getInstance("MD5").digest(material).copyOf(minOf(16, security.key.size + 5))
        val output = when (security.streamCrypt) {
            PdfCrypt.RC4 -> rc4(input, key)
            PdfCrypt.AES -> {
                require(input.size >= 32 && (input.size - 16) % 16 == 0) { "Invalid encrypted PDF stream" }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(input.copyOfRange(0, 16)))
                cipher.doFinal(input.copyOfRange(16, input.size))
            }
            PdfCrypt.IDENTITY -> input
        }
        require(output.size <= MAX_PDF_STREAM_BYTES) { "Decrypted PDF stream too large" }
        return output
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

    private fun findStreamKeyword(latin: PdfByteView, fromIndex: Int): Int? {
        for (candidate in fromIndex until latin.length) {
            if (latin.startsWith("endobj", candidate) &&
                (candidate == 0 || isPdfWhitespace(latin[candidate - 1]) || latin[candidate - 1] in ">])") &&
                (candidate + 6 == latin.length || isPdfWhitespace(latin[candidate + 6]))) return null
            if (!latin.startsWith("stream", candidate)) continue
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
        }
        return null
    }

    private fun streamDataStart(latin: PdfByteView, streamKeyword: Int): Int? {
        var start = streamKeyword + "stream".length
        if (start >= latin.length) return null
        return when (latin[start]) {
            '\r' -> if (start + 1 < latin.length && latin[start + 1] == '\n') start + 2 else start + 1
            '\n' -> start + 1
            else -> null
        }
    }

    private fun streamBounds(
        latin: PdfByteView,
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

    private fun findPdfKeyword(latin: PdfByteView, keyword: String, fromIndex: Int): Int {
        var index = latin.indexOf(keyword, fromIndex)
        while (index >= 0) {
            val end = index + keyword.length
            // A closing PDF dictionary, array or string delimiter also terminates
            // an object value. Producers such as Pillow legally emit >>endobj.
            // Keep stream searches whitespace-bound so binary payload markers
            // do not acquire new fallback boundaries.
            val beforeIsBoundary = index == 0 || isPdfWhitespace(latin[index - 1]) ||
                (keyword == "endobj" && latin[index - 1] in ">])")
            val afterIsWhitespace = end >= latin.length || isPdfWhitespace(latin[end])
            if (beforeIsBoundary && afterIsWhitespace) return index
            index = latin.indexOf(keyword, index + keyword.length)
        }
        return -1
    }

    private fun isPdfWhitespaceOnly(latin: PdfByteView, start: Int, end: Int): Boolean {
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
        if (subtype == "CIDFontType0" || subtype == "CIDFontType2") return PdfFontEncoding(known = false)
        // /ToUnicode takes precedence over Encoding and Differences. A code the
        // CMap does not map is unknown; it never falls back to glyph names.
        val hasToUnicode = findTopLevelValueStart(dict, "ToUnicode") >= 0
        if (subtype == "Type0") {
            // Only Identity CID encodings have a fixed two-byte code whose value
            // the ToUnicode CMap keys directly; other CMaps would need their
            // own code-space and CID tables.
            val cidEncoding = namedDictionaryOrReference(dict, "Encoding").name
            if (!hasToUnicode || (cidEncoding != "Identity-H" && cidEncoding != "Identity-V")) {
                return PdfFontEncoding(known = false)
            }
            val cmap = toUnicodeCMap(objects, dict) ?: return PdfFontEncoding(known = false)
            return PdfFontEncoding(known = true, base = null, toUnicode = cmap, codeBytes = 2)
        }
        if (hasToUnicode) {
            val cmap = toUnicodeCMap(objects, dict) ?: return PdfFontEncoding(known = false)
            return PdfFontEncoding(known = true, base = null, toUnicode = cmap, codeBytes = 1)
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
                // A non-base-14 Type1 font can still declare every glyph
                // used by a page in /Differences. Leave its undeclared base
                // unknown and validate each shown byte against that table.
                builtInBaseEncoding(dict)
            } else {
                encodingByName(baseValue.name) ?: return PdfFontEncoding(known = false)
            }
            if (base == null && subtype != "Type1") return PdfFontEncoding(known = false)
            val differences = arrayBody(encodingDict, "Differences")
            if (!differences.present) return PdfFontEncoding(known = base != null, base = base)
            if (differences.malformed) return PdfFontEncoding(known = false)
            val mapped = parseDifferences(differences.body.orEmpty()) ?: return PdfFontEncoding(known = false)
            return PdfFontEncoding(known = true, differences = mapped, base = base)
        }
        return when (val base = encodingByName(encoding.name)) {
            null -> PdfFontEncoding(known = false)
            else -> PdfFontEncoding(known = true, base = base)
        }
    }

    private const val MAX_TO_UNICODE_ENTRIES = 65_536

    private class CachedCMap(val entries: Map<Long, String?>?)

    // Pages share font objects; parse each ToUnicode stream once per loaded PDF.
    private val toUnicodeCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ByteArray, CachedCMap>())

    private fun cmapKey(length: Int, code: Long): Long = (length.toLong() shl 40) or code

    /**
     * Parses the bfchar/bfrange entries of a ToUnicode CMap (PDF 32000-1 9.10.3).
     * The whole CMap is rejected (null) when it is malformed, references another
     * CMap through `usecmap`, or is too large. A destination that is not a
     * well-formed, meaningful Unicode string is kept as an explicit unknown
     * entry so text showing that code is not certified as complete.
     */
    private fun toUnicodeCMap(objects: Map<Int, PdfObject>, fontDict: String): Map<Long, String?>? {
        val value = dictionaryOrReference(fontDict, "ToUnicode")
        val stream = value.reference?.let { objects[it] }?.takeIf { it.stream != null } ?: return null
        toUnicodeCache[stream.stream]?.let { return it.entries }
        val parsed = parseToUnicodeCMap(stream)
        toUnicodeCache[stream.stream!!] = CachedCMap(parsed)
        return parsed
    }

    private fun parseToUnicodeCMap(stream: PdfObject): Map<Long, String?>? {
        val decoded = decodeContentStream(stream)
        if (!decoded.complete) return null
        val lexer = PdfContentLexer(String(decoded.bytes, Charsets.ISO_8859_1))
        val tokens = lexer.tokenize()
        if (!lexer.complete) return null
        val entries = HashMap<Long, String?>()
        val operands = mutableListOf<PdfContentToken>()
        fun code(token: PdfContentToken): Pair<Int, Long>? {
            val bytes = (token as? PdfContentToken.Hex)?.takeIf { it.valid }?.bytes ?: return null
            if (bytes.size !in 1..4) return null
            return bytes.size to bytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
        }
        fun units(token: PdfContentToken): IntArray? {
            val bytes = (token as? PdfContentToken.Hex)?.takeIf { it.valid }?.bytes ?: return null
            if (bytes.isEmpty() || bytes.size % 2 != 0) return null
            return IntArray(bytes.size / 2) { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) }
        }
        fun put(length: Int, code: Long, text: String?): Boolean {
            entries[cmapKey(length, code)] = text?.takeIf(::isMeaningfulUnicode)
            return entries.size <= MAX_TO_UNICODE_ENTRIES
        }
        for (token in tokens) {
            val op = (token as? PdfContentToken.Operator)?.name
            if (op == null) {
                operands += token
                continue
            }
            when (op) {
                "usecmap", "begincidchar", "begincidrange", "beginnotdefchar", "beginnotdefrange" -> return null
                "endbfchar" -> {
                    if (operands.size % 2 != 0) return null
                    operands.chunked(2).forEach { (source, destination) ->
                        val (length, value) = code(source) ?: return null
                        val text = units(destination)?.let { utf16(it) }
                        if (destination !is PdfContentToken.Hex || !put(length, value, text)) return null
                    }
                }
                "endbfrange" -> {
                    if (operands.size % 3 != 0) return null
                    operands.chunked(3).forEach { (lowToken, highToken, destination) ->
                        val (length, low) = code(lowToken) ?: return null
                        val (highLength, high) = code(highToken) ?: return null
                        if (highLength != length || high < low || high - low >= MAX_TO_UNICODE_ENTRIES) return null
                        when (destination) {
                            is PdfContentToken.Array -> {
                                if (destination.items.size.toLong() != high - low + 1) return null
                                destination.items.forEachIndexed { index, item ->
                                    val text = units(item)?.let { utf16(it) }
                                    if (item !is PdfContentToken.Hex || !put(length, low + index, text)) return null
                                }
                            }
                            is PdfContentToken.Hex -> {
                                val start = units(destination) ?: return null
                                for (offset in 0..(high - low).toInt()) {
                                    val next = start.copyOf()
                                    next[next.lastIndex] += offset
                                    val text = if (next.last() > 0xFFFF) null else utf16(next)
                                    if (!put(length, low + offset, text)) return null
                                }
                            }
                            else -> return null
                        }
                    }
                }
            }
            operands.clear()
        }
        return entries
    }

    private fun utf16(units: IntArray): String = String(CharArray(units.size) { units[it].toChar() })

    private fun isMeaningfulUnicode(text: String): Boolean {
        if (text.isEmpty()) return false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return false
            } else if (Character.isLowSurrogate(char)) {
                return false
            }
            val codePoint = text.codePointAt(index)
            val allowedControl = codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0D
            if ((Character.isISOControl(codePoint) && !allowedControl) || codePoint == 0xFFFD ||
                codePoint in 0xE000..0xF8FF || codePoint >= 0xF0000 || (codePoint and 0xFFFE) == 0xFFFE) return false
            index += Character.charCount(codePoint)
        }
        return true
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

    private fun parseDifferences(body: String): Map<Int, String?>? {
        // An unrecognized glyph name is recorded as an explicit unknown code, so
        // only text that actually shows that byte loses its complete status and
        // the base encoding is never consulted for a code Differences redefined.
        val mapped = linkedMapOf<Int, String?>()
        var index = 0
        var nextCode: Int? = null
        var needsGlyph = false
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
                val glyph = pdfGlyphName(decodePdfName(body.substring(start, index)))
                val code = nextCode ?: return null
                if (code !in 0..255) return null
                mapped[code] = glyph
                nextCode = code + 1
                needsGlyph = false
                continue
            }
            val start = index
            if (body[index] == '+' || body[index] == '-') index++
            if (index >= body.length || body[index] !in '0'..'9') return null
            while (index < body.length && body[index] in '0'..'9') index++
            if (needsGlyph) return null
            nextCode = body.substring(start, index).toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            needsGlyph = true
        }
        return mapped.takeUnless { needsGlyph }
    }

    /**
     * Glyph name to Unicode following the Adobe Glyph List specification's
     * algorithm: ignore everything after the first period, split ligature
     * components on underscores, and accept `uniXXXX` / `uXXXX[XX]` forms with
     * uppercase hexadecimal scalar values. Names come from the PDF Latin
     * character set (ISO 32000-1 Annex D); an accented Latin letter is accepted
     * only when Unicode canonical composition yields a single code point.
     * Anything else is unknown and returns null.
     */
    private fun pdfGlyphName(name: String): String? {
        val base = name.substringBefore('.')
        if (base.isEmpty()) return null
        val parts = base.split('_')
        if (parts.any { it.isEmpty() }) return null
        return buildString {
            parts.forEach { part -> append(glyphComponent(part) ?: return null) }
        }
    }

    private fun glyphComponent(name: String): String? {
        if (name.length == 1) return name.takeIf { it[0] in 'A'..'Z' || it[0] in 'a'..'z' }
        LATIN_GLYPH_NAMES[name]?.let { return it }
        unicodeGlyphValue(name)?.let { return it }
        val accent = GLYPH_ACCENTS.entries.firstOrNull { (suffix, _) ->
            name.length == suffix.length + 1 && name.endsWith(suffix)
        } ?: return null
        val letter = name[0].takeIf { it in 'A'..'Z' || it in 'a'..'z' } ?: return null
        val composed = java.text.Normalizer.normalize("$letter${accent.value}", java.text.Normalizer.Form.NFC)
        return composed.takeIf { it.codePointCount(0, it.length) == 1 }
    }

    private fun unicodeGlyphValue(name: String): String? {
        val hex = when {
            name.startsWith("uni") -> name.substring(3).takeIf { it.isNotEmpty() && it.length % 4 == 0 }
            name.startsWith("u") -> name.substring(1).takeIf { it.length in 4..6 }
            else -> null
        } ?: return null
        if (hex.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        val values = if (name.startsWith("uni")) hex.chunked(4) else listOf(hex)
        return buildString {
            values.forEach { value ->
                val code = value.toInt(16)
                if (code in 0xD800..0xDFFF || code > 0x10FFFF) return null
                appendCodePoint(code)
            }
        }
    }

    private val GLYPH_ACCENTS = mapOf(
        "acute" to "́", "grave" to "̀", "circumflex" to "̂", "dieresis" to "̈",
        "tilde" to "̃", "ring" to "̊", "cedilla" to "̧", "caron" to "̌",
        "macron" to "̄", "breve" to "̆", "ogonek" to "̨", "dotaccent" to "̇",
        "hungarumlaut" to "̋",
    )

    // Non-compositional names of the PDF Latin character set (ISO 32000-1 D.2).
    private val LATIN_GLYPH_NAMES = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "space" to " ", "nbspace" to " ", "exclam" to "!", "quotedbl" to "\"", "numbersign" to "#",
        "dollar" to "$", "percent" to "%", "ampersand" to "&", "quotesingle" to "'", "parenleft" to "(",
        "parenright" to ")", "asterisk" to "*", "plus" to "+", "comma" to ",", "hyphen" to "-",
        "minus" to "-", "period" to ".", "slash" to "/", "colon" to ":", "semicolon" to ";",
        "less" to "<", "equal" to "=", "greater" to ">", "question" to "?", "at" to "@",
        "bracketleft" to "[", "backslash" to "\\", "bracketright" to "]", "asciicircum" to "^",
        "underscore" to "_", "grave" to "`", "braceleft" to "{", "bar" to "|", "braceright" to "}",
        "asciitilde" to "~", "exclamdown" to "¡", "cent" to "¢", "sterling" to "£", "currency" to "¤",
        "yen" to "¥", "brokenbar" to "¦", "section" to "§", "dieresis" to "¨", "copyright" to "©",
        "ordfeminine" to "ª", "guillemotleft" to "«", "logicalnot" to "¬", "registered" to "®",
        "macron" to "¯", "degree" to "°", "plusminus" to "±", "twosuperior" to "²",
        "threesuperior" to "³", "acute" to "´", "mu" to "µ", "paragraph" to "¶",
        "periodcentered" to "·", "cedilla" to "¸", "onesuperior" to "¹", "ordmasculine" to "º",
        "guillemotright" to "»", "onequarter" to "¼", "onehalf" to "½", "threequarters" to "¾",
        "questiondown" to "¿", "AE" to "Æ", "Eth" to "Ð", "multiply" to "×", "Oslash" to "Ø",
        "Thorn" to "Þ", "germandbls" to "ß", "ae" to "æ", "eth" to "ð", "divide" to "÷",
        "oslash" to "ø", "thorn" to "þ", "dotlessi" to "ı", "Lslash" to "Ł", "lslash" to "ł",
        "OE" to "Œ", "oe" to "œ", "florin" to "ƒ", "circumflex" to "ˆ", "caron" to "ˇ",
        "breve" to "˘", "dotaccent" to "˙", "ring" to "˚", "ogonek" to "˛", "tilde" to "˜",
        "hungarumlaut" to "˝", "endash" to "–", "emdash" to "—", "quoteleft" to "‘",
        "quoteright" to "’", "quotesinglbase" to "‚", "quotedblleft" to "“", "quotedblright" to "”",
        "quotedblbase" to "„", "dagger" to "†", "daggerdbl" to "‡", "bullet" to "•",
        "ellipsis" to "…", "perthousand" to "‰", "guilsinglleft" to "‹", "guilsinglright" to "›",
        "fraction" to "⁄", "Euro" to "€", "trademark" to "™", "fi" to "fi", "fl" to "fl",
        "ff" to "ff", "ffi" to "ffi", "ffl" to "ffl",
    )

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

    /**
     * Native text plus the embedded pictures is complete page evidence only when
     * every other mark is decorative (see [decorativeGraphics]), no text acts as
     * a clip, no visible annotation or page rotation changes the appearance, and
     * each resource XObject is drawn at least once as an unrotated, unflipped
     * picture inside the MediaBox. Each picture must be a standalone baseline
     * JPEG in DeviceRGB, DeviceGray or a one/three-component ICC space, with no
     * mask, decode array or alternate that would change what the page shows.
     */
    private fun canProcessIllustrationsSeparately(
        text: String,
        textComplete: Boolean,
        contentComplete: Boolean,
        pageLatin: String,
        xobjects: Map<String, Int>,
        objects: Map<Int, PdfObject>,
        unresolved: Boolean,
        hasInline: Boolean,
        visibleAnnotations: Boolean,
        pageAppearanceModified: Boolean,
        pageBox: List<Double>?,
        extGStates: PageXObjects,
    ): Boolean {
        if (text.isBlank() || !textComplete || !contentComplete || unresolved || hasInline || visibleAnnotations ||
            pageAppearanceModified || pageBox == null || xobjects.isEmpty()) return false
        val graphics = decorativeGraphics(pageLatin, pageBox) ?: return false
        if (graphics.textClip || graphics.draws.isEmpty()) return false
        if (graphics.graphicsStates.any { name ->
                extGStates.unresolved || !extGStateKeepsImageAppearance(
                    objects[extGStates.entries[name] ?: return false]?.dict ?: return false)
            }) return false
        if (graphics.draws.map { it.name }.toSet() != xobjects.keys) return false
        val tolerance = AXIS_TOLERANCE
        if (graphics.draws.any { (_, box) ->
                box[0] < pageBox[0] - tolerance || box[1] < pageBox[1] - tolerance ||
                    box[2] > pageBox[2] + tolerance || box[3] > pageBox[3] + tolerance
            }) return false
        return xobjects.values.all { number ->
            val image = objects[number] ?: return@all false
            val stream = image.payload ?: return@all false
            val payload = stream.prefix()
            isImageDict(image.dict) && xObjectMediaType(image.dict, payload) == "image/jpeg" &&
                illustrationColourSpace(objects, image.dict) &&
                listOf("Mask", "SMask", "Decode", "DecodeParms", "DP", "ImageMask", "Alternates", "Matte")
                    .none { findTopLevelValueStart(image.dict, it) >= 0 } &&
                stream.completeJpeg()
        }
    }

    /** Soft masks, transparency, non-normal blending and transfer functions change how a picture looks. */
    private fun extGStateKeepsImageAppearance(dict: String): Boolean {
        if (findTopLevelValueStart(dict, "SMask") >= 0 && namedDictionaryOrReference(dict, "SMask").name != "None") return false
        if (findTopLevelValueStart(dict, "BM") >= 0 &&
            namedDictionaryOrReference(dict, "BM").name !in setOf("Normal", "Compatible")) return false
        if (listOf("TR", "TR2").any { findTopLevelValueStart(dict, it) >= 0 }) return false
        return listOf("CA", "ca").all { key ->
            val start = findTopLevelValueStart(dict, key)
            start < 0 || Regex("""[-+]?(?:\d+(?:\.\d*)?|\.\d+)""").matchAt(dict, start)?.value?.toDoubleOrNull()
                ?.let { it >= 1.0 } == true
        }
    }

    /** CMYK, Lab, indexed and special colour JPEGs may decode differently from the page; keep them on page Vision. */
    private fun illustrationColourSpace(objects: Map<Int, PdfObject>, imageDict: String): Boolean {
        val named = namedDictionaryOrReference(imageDict, "ColorSpace")
        if (named.name == "DeviceRGB" || named.name == "DeviceGray") return true
        val start = findTopLevelValueStart(imageDict, "ColorSpace")
        if (start < 0) return false
        val array = when {
            imageDict.startsWith("[", start) -> imageDict.substring(start, arrayEnd(imageDict, start).takeIf { it > 0 } ?: return false)
            named.reference != null -> objects[named.reference]?.takeIf { it.stream == null }?.dict?.trim() ?: return false
            else -> return false
        }
        val match = Regex("""^\[\s*/ICCBased\s+(\d+)\s+0\s+R\s*]$""").find(array.trim()) ?: return false
        val profile = objects[match.groupValues[1].toInt()]?.takeIf { it.stream != null } ?: return false
        val components = Regex("""/N\s+(\d+)""").find(profile.dict)?.groupValues?.get(1)?.toIntOrNull()
        return components == 1 || components == 3
    }

    private fun pageMediaBox(objects: Map<Int, PdfObject>, pageNumber: Int, pageDict: String): List<Double>? {
        val visited = mutableSetOf<Int>()
        var number = pageNumber
        var dict = pageDict
        while (visited.add(number)) {
            val box = arrayBody(dict, "MediaBox")
            if (box.present) {
                val values = box.body?.trim()?.split(Regex("\\s+"))?.map { it.toDoubleOrNull() } ?: return null
                if (values.size != 4 || values.any { it == null || !it.isFinite() }) return null
                val coordinates = values.map { it!! }
                return coordinates.takeIf { it[0] < it[2] && it[1] < it[3] }
            }
            val parent = dictionaryOrReference(dict, "Parent")
            if (!parent.present) return null
            number = parent.reference ?: return null
            dict = objects[number]?.dict ?: return null
        }
        return null
    }

    private fun hasPageAppearanceModifiers(objects: Map<Int, PdfObject>, pageNumber: Int, pageDict: String): Boolean {
        val visited = mutableSetOf<Int>()
        var number = pageNumber
        var dict = pageDict
        while (visited.add(number)) {
            if (listOf("Rotate", "CropBox", "BleedBox", "TrimBox", "ArtBox")
                    .any { findTopLevelValueStart(dict, it) >= 0 }) return true
            val parent = dictionaryOrReference(dict, "Parent")
            if (!parent.present) return false
            number = parent.reference ?: return true
            dict = objects[number]?.dict ?: return true
        }
        return true
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
                if (n <= 0) {
                    if (inflater.finished()) break
                    return DecodedPageContent(raw, complete = false)
                }
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

    private fun isEmptyPageWithoutVisuals(
        dict: String,
        content: DecodedPageContent,
        hasVisuals: Boolean,
    ): Boolean {
        if (hasVisuals) return false
        if (findTopLevelValueStart(dict, "Contents") < 0) return content.bytes.isEmpty()
        if (!content.complete) return false
        val lexer = PdfContentLexer(String(content.bytes, Charsets.ISO_8859_1))
        return lexer.tokenize().isEmpty() && lexer.complete
    }

    private fun hasVisibleOrUnknownAnnotations(objects: Map<Int, PdfObject>, pageDict: String): Boolean {
        val annots = arrayBody(pageDict, "Annots")
        if (!annots.present) return false
        val body = annots.body ?: return true
        val references = Regex("(\\d+)\\s+0\\s+R\\b").findAll(body).toList()
        if (body.replace(Regex("(\\d+)\\s+0\\s+R\\b"), " ").isNotBlank()) return true
        return references.any { reference ->
            val number = reference.groupValues[1].toIntOrNull() ?: return@any true
            val annotation = objects[number]?.takeIf { it.stream == null } ?: return@any true
            if (namedDictionaryOrReference(annotation.dict, "Subtype").name != "Link") return@any true
            if (findTopLevelValueStart(annotation.dict, "AP") >= 0) return@any true
            // /BS supersedes /Border (PDF 32000-1 12.5.4); its /W defaults to 1.
            val borderStyle = dictionaryOrReference(annotation.dict, "BS")
            if (borderStyle.present) {
                val style = borderStyle.dictionary?.takeUnless { borderStyle.malformed } ?: return@any true
                val widthStart = findTopLevelValueStart(style, "W")
                if (widthStart < 0) return@any true
                val width = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)").matchAt(style, widthStart)?.value?.toDoubleOrNull()
                return@any width != 0.0
            }
            val border = arrayBody(annotation.dict, "Border").body ?: return@any true
            val values = border.trim().split(Regex("\\s+")).map { it.toDoubleOrNull() }
            values.size != 3 || values.any { it == null || it != 0.0 }
        }
    }

    private fun extractPdfStrings(
        data: ByteArray,
        fonts: PageFonts,
        requireExplicitFont: Boolean = false,
    ): ExtractedPdfText {
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
            if (name == null) return PdfFontEncoding(known = !fonts.unresolved && !requireExplicitFont)
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
                // Colour operands vary with the current colour space: one to four
                // components, and for scn/SCN an optional trailing pattern name.
                "sc", "SC", "scn", "SCN" -> {
                    val pattern = (token.name == "scn" || token.name == "SCN") &&
                        stack.lastOrNull() is PdfContentToken.Name
                    if (pattern) stack.removeAt(stack.lastIndex)
                    var components = 0
                    while (stack.lastOrNull() is PdfContentToken.Number) {
                        stack.removeAt(stack.lastIndex)
                        components++
                    }
                    if (components > 4 || (components == 0 && !pattern)) complete = false
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
        encoding.toUnicode?.let { cmap ->
            val width = encoding.codeBytes
            if (bytes.size % width != 0) complete = false
            for (start in 0 until bytes.size - bytes.size % width step width) {
                var code = 0L
                for (offset in 0 until width) code = (code shl 8) or (bytes[start + offset].toLong() and 0xff)
                val mapped = cmap[cmapKey(width, code)]
                if (mapped == null) complete = false else out.append(mapped)
            }
            return DecodedShow(out.toString(), complete)
        }
        for (byte in bytes) {
            val code = byte.toInt() and 0xff
            val mapped = if (code in encoding.differences) encoding.differences[code] else mapBaseByte(encoding.base, code)
            if (mapped == null) {
                complete = false
                out.append(String(byteArrayOf(byte), Charsets.ISO_8859_1))
            } else {
                out.append(mapped)
            }
        }
        return DecodedShow(out.toString(), complete)
    }

    private fun mapBaseByte(base: PdfBaseEncoding?, code: Int): String? {
        if (base == null) return null
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

        fun firstToken(): PdfContentToken? {
            skipWsComments()
            return readToken()
        }

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
        return (Regex("(?<![A-Za-z])(re|m|l|c|v|y)\\s").containsMatchIn(stripped) &&
            Regex("(?<![A-Za-z])(f|f\\*|F|B|b|S|s)\\s").containsMatchIn(stripped)) ||
            Regex("(?<![A-Za-z/])sh(?![A-Za-z0-9])").containsMatchIn(stripped)
    }

    private const val MAX_DECORATIVE_PAINTED_PATHS = 96
    private const val THIN_RULE_WIDTH = 2.0
    private const val AXIS_TOLERANCE = 0.5

    private class GraphicsState(val ctm: DoubleArray, val lineWidth: Double, val fillWhite: Boolean) {
        fun with(ctm: DoubleArray = this.ctm, lineWidth: Double = this.lineWidth, fillWhite: Boolean = this.fillWhite) =
            GraphicsState(ctm, lineWidth, fillWhite)
    }

    /** An XObject painted with an axis-aligned, unflipped transform; [box] is its device-space rectangle. */
    private data class XObjectDraw(val name: String, val box: List<Double>)

    /**
     * Page graphics that are decorative apart from [draws]; [textClip] marks text
     * used as a clip path and [graphicsStates] lists the ExtGState names applied.
     */
    private data class DecorativeGraphics(
        val draws: List<XObjectDraw>,
        val textClip: Boolean,
        val graphicsStates: Set<String>,
    )

    private fun hasOnlyDecorativeGraphics(latin: String, pageBox: List<Double>?): Boolean =
        decorativeGraphics(latin, pageBox)?.draws?.isEmpty() == true

    /**
     * Typeset books draw rules (section and footnote separators, underlines,
     * table and frame lines), white or page-sized background fills and clip
     * paths that carry no content beyond the text layer. Accept a page's
     * graphics only when every painted path is, in device space, a thin
     * axis-aligned straight stroke or rectangle outline, a thin filled rule, a
     * white filled rectangle, or a rectangle covering most of the page.
     * Curves, diagonal or thick lines, coloured panels, shadings, rotated or
     * flipped XObjects, unknown operators and more than a table's worth of
     * paths keep page Vision (null). XObjects placed without rotation or flip
     * are reported for the caller to validate.
     */
    private fun decorativeGraphics(latin: String, pageBox: List<Double>?): DecorativeGraphics? {
        if (pageBox == null) return null
        val pageArea = (pageBox[2] - pageBox[0]) * (pageBox[3] - pageBox[1])
        if (!pageArea.isFinite() || pageArea <= 0.0) return null
        val lexer = PdfContentLexer(latin)
        val tokens = lexer.tokenize()
        if (!lexer.complete) return null
        val draws = mutableListOf<XObjectDraw>()
        val graphicsStates = mutableSetOf<String>()
        var textClip = false
        var state = GraphicsState(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), 1.0, fillWhite = false)
        val saved = ArrayDeque<GraphicsState>()
        val operands = mutableListOf<PdfContentToken>()
        val subpaths = mutableListOf<MutableList<Pair<Double, Double>>>()
        var inText = false
        var painted = 0
        fun numbers(count: Int): DoubleArray? {
            if (operands.size != count) return null
            return DoubleArray(count) { index ->
                (operands[index] as? PdfContentToken.Number)?.value?.toDoubleOrNull()
                    ?.takeIf(Double::isFinite) ?: return null
            }
        }
        fun point(x: Double, y: Double): Pair<Double, Double> {
            val m = state.ctm
            return (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])
        }
        fun near(a: Double, b: Double) = kotlin.math.abs(a - b) <= AXIS_TOLERANCE
        fun axisAligned(a: Pair<Double, Double>, b: Pair<Double, Double>) =
            near(a.first, b.first) || near(a.second, b.second)
        fun rectangleSize(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
            val closed = points.size == 5 && near(points.first().first, points.last().first) &&
                near(points.first().second, points.last().second)
            val corners = if (closed) points.dropLast(1) else points
            if (corners.size != 4 || corners.indices.any { !axisAligned(corners[it], corners[(it + 1) % 4]) }) return null
            return (corners.maxOf { it.first } - corners.minOf { it.first }) to
                (corners.maxOf { it.second } - corners.minOf { it.second })
        }
        fun paint(stroke: Boolean, fill: Boolean): Boolean {
            val paths = subpaths.filter { it.size > 1 }
            subpaths.clear()
            painted += paths.size
            if (painted > MAX_DECORATIVE_PAINTED_PATHS) return false
            val m = state.ctm
            val deviceWidth = state.lineWidth * kotlin.math.sqrt(kotlin.math.abs(m[0] * m[3] - m[1] * m[2]))
            return paths.all { points ->
                val strokeOk = !stroke || (deviceWidth <= THIN_RULE_WIDTH &&
                    points.zipWithNext().all { (a, b) -> axisAligned(a, b) })
                val fillOk = !fill || rectangleSize(points)?.let { (width, height) ->
                    minOf(width, height) <= THIN_RULE_WIDTH || state.fillWhite || width * height >= pageArea * 0.9
                } == true
                strokeOk && fillOk
            }
        }
        // Fill colour persists across text objects, so colour set inside BT/ET
        // decides whether a later rectangle is white.
        fun colour(op: String): Boolean? = when (op) {
            "g" -> numbers(1)?.all { it == 1.0 }
            "rg" -> numbers(3)?.all { it == 1.0 }
            "k" -> numbers(4)?.all { it == 0.0 }
            "cs", "sc", "scn" -> false
            else -> null
        }
        for (token in tokens) {
            val op = (token as? PdfContentToken.Operator)?.name
            if (op == null) {
                operands += token
                continue
            }
            if (op in setOf("g", "rg", "k", "cs", "sc", "scn")) {
                state = state.with(fillWhite = colour(op) ?: return null)
                operands.clear()
                continue
            }
            if (op == "Tr") {
                textClip = textClip || (numbers(1)?.get(0) ?: return null) >= 4.0
                operands.clear()
                continue
            }
            if (op == "gs") {
                graphicsStates += decodePdfName((operands.singleOrNull() as? PdfContentToken.Name)?.value ?: return null)
                operands.clear()
                continue
            }
            if (inText) {
                when (op) {
                    "ET" -> inText = false
                    "BT", "m", "l", "c", "v", "y", "re", "h", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*",
                    "n", "W", "W*", "sh", "Do", "q", "Q", "cm", "d0", "d1" -> return null
                }
                operands.clear()
                continue
            }
            when (op) {
                "BT" -> if (subpaths.isNotEmpty()) return null else inText = true
                "q" -> saved.addLast(state)
                "Q" -> state = saved.removeLastOrNull() ?: return null
                "cm" -> {
                    val n = numbers(6) ?: return null
                    val c = state.ctm
                    state = state.with(ctm = doubleArrayOf(
                        n[0] * c[0] + n[1] * c[2], n[0] * c[1] + n[1] * c[3],
                        n[2] * c[0] + n[3] * c[2], n[2] * c[1] + n[3] * c[3],
                        n[4] * c[0] + n[5] * c[2] + c[4], n[4] * c[1] + n[5] * c[3] + c[5],
                    ))
                }
                "w" -> state = state.with(lineWidth = numbers(1)?.get(0)?.takeIf { it >= 0.0 } ?: return null)
                "m" -> {
                    val n = numbers(2) ?: return null
                    subpaths += mutableListOf(point(n[0], n[1]))
                }
                "l" -> {
                    val n = numbers(2) ?: return null
                    subpaths.lastOrNull()?.add(point(n[0], n[1])) ?: return null
                }
                "re" -> {
                    val n = numbers(4) ?: return null
                    subpaths += mutableListOf(point(n[0], n[1]), point(n[0] + n[2], n[1]),
                        point(n[0] + n[2], n[1] + n[3]), point(n[0], n[1] + n[3]), point(n[0], n[1]))
                }
                "h" -> subpaths.lastOrNull()?.let { it.add(it.first()) } ?: return null
                "S", "s" -> if (!paint(stroke = true, fill = false)) return null
                "f", "F", "f*" -> if (!paint(stroke = false, fill = true)) return null
                "B", "B*", "b", "b*" -> if (!paint(stroke = true, fill = true)) return null
                "n" -> subpaths.clear()
                "Do" -> {
                    val name = (operands.singleOrNull() as? PdfContentToken.Name)?.value ?: return null
                    val m = state.ctm
                    val scale = maxOf(kotlin.math.abs(m[0]), kotlin.math.abs(m[3]))
                    if (subpaths.isNotEmpty() || m[0] <= 0.0 || m[3] <= 0.0 ||
                        kotlin.math.abs(m[1]) > scale * 1e-6 || kotlin.math.abs(m[2]) > scale * 1e-6) return null
                    draws += XObjectDraw(decodePdfName(name), listOf(m[4], m[5], m[4] + m[0], m[5] + m[3]))
                }
                "W", "W*", "G", "RG", "K", "CS", "SC", "SCN", "d", "J", "j", "M", "i", "ri",
                "BMC", "BDC", "EMC", "MP", "DP", "BX", "EX" -> Unit
                else -> return null
            }
            operands.clear()
        }
        return DecorativeGraphics(draws, textClip, graphicsStates).takeIf { !inText && subpaths.isEmpty() }
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
        pageDictSuffix: String = "",
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
                "$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents $contentObj 0 R /Resources << $resources >> $pageDictSuffix >>\nendobj\n",
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
