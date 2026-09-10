// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object PdfParser {
    const val FINGERPRINT = "pdf-text-v8-pdfrenderer"

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
            val extracted = extractPdfStrings(decoded)
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
            val extracted = extractPdfStrings(decoded)
            val text = extracted.joined()
            val resolvedXObjects = pageXObjects(objects, objNum, pageObj.dict)
            val xobjects = resolvedXObjects.entries
            val hasUnresolvedXObjects = resolvedXObjects.unresolved ||
                hasUnresolvedXObjectDo(pageLatin, xobjects)
            val hasDrawing = hasVectorDrawing(pageLatin)
            var hasUnsupportedPageVisual = hasDrawing || !content.complete || hasUnresolvedXObjects
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
    private data class PdfDictionaryValue(
        val present: Boolean,
        val dictionary: String? = null,
        val reference: Int? = null,
        val malformed: Boolean = false,
    )
    private data class ExtractedPdfText(val texts: List<String>, val complete: Boolean) {
        fun joined(): String = texts.joinToString(" ").trim()
    }
    private data class DecodedTjArray(val text: String, val complete: Boolean)

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
                return xObjectsFromResources(objects, resourceDict)
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

    private fun xObjectsFromResources(
        objects: Map<Int, PdfObject>,
        resources: String,
    ): PageXObjects {
        val xObjectValue = dictionaryOrReference(resources, "XObject")
        if (!xObjectValue.present) return PageXObjects(emptyMap(), unresolved = false)
        if (xObjectValue.malformed) return PageXObjects(emptyMap(), unresolved = true)
        val xObjectDict = xObjectValue.dictionary ?: xObjectValue.reference
            ?.let { reference ->
                objects[reference]
                    ?.takeIf { it.stream == null }
                    ?.let { dictionaryBody(it.dict) }
            }
            ?: return PageXObjects(emptyMap(), unresolved = true)
        val entries = linkedMapOf<String, Int>()
        Regex("/([^\\s<>\\[\\]()/%]+)\\s+(\\d+)\\s+0\\s+R\\b")
            .findAll(xObjectDict)
            .forEach { match ->
                entries[match.groupValues[1]] = match.groupValues[2].toInt()
            }
        return PageXObjects(entries, unresolved = false)
    }

    private fun hasUnresolvedXObjectDo(latin: String, entries: Map<String, Int>): Boolean {
        val outsideText = latin.replace(Regex("BT[\\s\\S]*?ET"), " ")
        return Regex("/([^\\s<>\\[\\]()/%]+)\\s+Do\\b")
            .findAll(outsideText)
            .any { match -> match.groupValues[1] !in entries }
    }

    private fun dictionaryOrReference(dict: String, name: String): PdfDictionaryValue {
        val key = Regex("/" + Regex.escape(name) + "(?![A-Za-z0-9])").find(dict)
            ?: return PdfDictionaryValue(present = false)
        var valueStart = key.range.last + 1
        while (valueStart < dict.length && isPdfWhitespace(dict[valueStart])) valueStart++
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

    private fun dictionaryBody(body: String): String? {
        val start = body.indexOf("<<")
        if (start < 0) return null
        val end = dictionaryEnd(body, start)
        return end.takeIf { it >= 0 }?.let { body.substring(start, it) }
    }

    private fun dictionaryEnd(text: String, start: Int): Int {
        if (start < 0 || start + 1 >= text.length || !text.startsWith("<<", start)) return -1
        var depth = 0
        var index = start
        while (index + 1 < text.length) {
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
                else -> index++
            }
        }
        return -1
    }

    private fun streamFilters(dict: String): List<String> {
        val array = Regex("/Filter\\s*\\[([^]]*)]").find(dict)?.groupValues?.get(1)
        if (array != null) {
            return Regex("/([A-Za-z0-9]+)").findAll(array).map { it.groupValues[1] }.toList()
        }
        return Regex("/Filter\\s*/([A-Za-z0-9]+)").find(dict)
            ?.groupValues
            ?.get(1)
            ?.let(::listOf)
            .orEmpty()
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

    private fun extractPdfStrings(data: ByteArray): ExtractedPdfText {
        val latin = String(data, Charsets.ISO_8859_1)
        val consumed = BooleanArray(latin.length)
        val texts = mutableListOf<String>()
        var complete = true

        fun mark(range: IntRange) {
            for (index in range) {
                if (index in consumed.indices) consumed[index] = true
            }
        }

        val operators = buildList {
            Regex("\\[((?:\\\\.|[^]])*)]\\s*TJ\\b").findAll(latin).forEach { match ->
                add(0 to match)
            }
            Regex("\\((?:\\\\.|[^\\\\)])*\\)\\s*(?:Tj\\b|'|\")").findAll(latin).forEach { match ->
                add(1 to match)
            }
            Regex("<[0-9A-Fa-f \\t\\r\\n]*>\\s*(?:Tj\\b|'|\")").findAll(latin).forEach { match ->
                add(2 to match)
            }
        }.sortedBy { it.second.range.first }

        operators.forEach { (kind, match) ->
            if (match.range.any { it in consumed.indices && consumed[it] }) return@forEach
            mark(match.range)
            when (kind) {
                0 -> {
                    val decoded = decodeTjArray(match.value.substringBeforeLast("]").removePrefix("["))
                    if (!decoded.complete) complete = false
                    if (decoded.text.isNotBlank()) texts += decoded.text
                }
                1 -> {
                    val decoded = decodePdfLiteral(match.value.substringBeforeLast(")").substringAfter("(", ""))
                    if (decoded.isNotBlank()) texts += decoded
                }
                else -> {
                    val hex = match.value.substringAfter("<").substringBeforeLast(">")
                    val decoded = decodePdfHex(hex)
                    if (decoded == null) {
                        complete = false
                    } else if (decoded.isNotBlank()) {
                        texts += decoded
                    }
                }
            }
        }
        if (hasUnconsumedTextShowOperator(latin, consumed)) complete = false
        return ExtractedPdfText(texts, complete)
    }

    private fun hasUnconsumedTextShowOperator(latin: String, consumed: BooleanArray): Boolean {
        return Regex("(?<![A-Za-z])(Tj|TJ)(?![A-Za-z0-9])").findAll(latin).any { match ->
            match.range.any { it in consumed.indices && !consumed[it] }
        }
    }

    private fun decodeTjArray(body: String): DecodedTjArray {
        val out = StringBuilder()
        var insertSpace = false
        var complete = true
        val consumed = BooleanArray(body.length)
        val token = Regex("\\((?:\\\\.|[^\\\\)])*\\)|<[0-9A-Fa-f \\t\\r\\n]*>|[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")
        token.findAll(body).forEach { match ->
            for (index in match.range) {
                if (index in consumed.indices) consumed[index] = true
            }
            when {
                match.value.startsWith("(") -> {
                    val decoded = decodePdfLiteral(match.value.removePrefix("(").removeSuffix(")"))
                    if (decoded.isEmpty()) return@forEach
                    if (insertSpace && out.isNotEmpty() && !out.last().isWhitespace() && !decoded.first().isWhitespace()) {
                        out.append(' ')
                    }
                    out.append(decoded)
                    insertSpace = false
                }
                match.value.startsWith("<") -> {
                    val decoded = decodePdfHex(match.value.removePrefix("<").removeSuffix(">"))
                    if (decoded == null) {
                        complete = false
                        return@forEach
                    }
                    if (decoded.isEmpty()) return@forEach
                    if (insertSpace && out.isNotEmpty() && !out.last().isWhitespace() && !decoded.first().isWhitespace()) {
                        out.append(' ')
                    }
                    out.append(decoded)
                    insertSpace = false
                }
                else -> {
                    // In PDF TJ, positive adjustments pull the next glyph left;
                    // negative adjustments create a word gap. Small kerning
                    // values, including positive ones, do not become spaces.
                    insertSpace = (match.value.toDoubleOrNull() ?: 0.0) <= -100.0
                }
            }
        }
        val leftover = buildString {
            body.forEachIndexed { index, char ->
                if (index !in consumed.indices || !consumed[index]) append(char)
            }
        }
        if (Regex("<[^>]*>|\\(").containsMatchIn(leftover)) complete = false
        return DecodedTjArray(out.toString(), complete)
    }

    private fun decodePdfLiteral(inner: String): String = inner
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\(", "(")
        .replace("\\)", ")")
        .replace("\\\\", "\\")

    private fun decodePdfHex(inner: String): String? {
        val hex = inner.filter { !it.isWhitespace() }
        if (hex.any { it !in "0123456789abcdefABCDEF" }) return null
        val padded = if (hex.length % 2 == 1) hex + "0" else hex
        val bytes = ByteArray(padded.length / 2)
        for (index in bytes.indices) {
            bytes[index] = padded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

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

    private fun jpegStub(): ByteArray {
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        return header
    }

    private fun assemblePages(
        pages: List<PageContent>,
        extraObjects: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val n = pages.size
        val objects = mutableListOf<ByteArray>()
        fun obj(body: String) = body.toByteArray(Charsets.ISO_8859_1)
        val fontObj = 3 + (2 * n)
        val firstExtra = fontObj + 1
        objects += obj("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val kids = (0 until n).joinToString(" ") { "${3 + it} 0 R" }
        objects += obj("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count $n >>\nendobj\n")
        pages.forEachIndexed { index, page ->
            val pageObj = 3 + index
            val contentObj = 3 + n + index
            val resources = page.resources
                .replace("FONT", "$fontObj 0 R")
                .replace("IMAGE", "$firstExtra 0 R")
            objects += obj(
                "$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents $contentObj 0 R /Resources << $resources >> >>\nendobj\n",
            )
        }
        pages.forEachIndexed { index, page ->
            val contentObj = 3 + n + index
            val contentBytes = page.content.toByteArray(Charsets.ISO_8859_1)
            objects += obj("$contentObj 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n") +
                contentBytes + obj("\nendstream\nendobj\n")
        }
        objects += obj("$fontObj 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
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
