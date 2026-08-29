// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object PdfParser {
    const val FINGERPRINT = "pdf-text-v5-pdfrenderer"

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
            val text = extractPdfStrings(decoded).joinToString(" ").trim()
            val hasInline = hasInlineImage(pageLatin)
            val hasImages = pageXObjects(pageObj.dict).isNotEmpty() || hasInline ||
                Regex("/Subtype\\s*/Image").containsMatchIn(pageObj.dict)
            val hasDrawing = hasVectorDrawing(pageLatin)
            if (hasImages || hasDrawing || text.isEmpty() || !content.complete) {
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
            val text = extractPdfStrings(decoded).joinToString(" ").trim()
            val xobjects = pageXObjects(pageObj.dict)
            val hasDrawing = hasVectorDrawing(pageLatin)
            var hasUnsupportedPageVisual = hasDrawing || !content.complete
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
            val hasImages = xobjects.isNotEmpty() || hasInline || Regex("/Subtype\\s*/Image").containsMatchIn(pageObj.dict)
            val needsVision = hasImages || hasDrawing || text.isEmpty() || !content.complete
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

    private fun extractIndirectObjects(bytes: ByteArray, latin: String): Map<Int, PdfObject> {
        val out = linkedMapOf<Int, PdfObject>()
        val header = Regex("(\\d+)\\s+0\\s+obj")
        header.findAll(latin).forEach { match ->
            val number = match.groupValues[1].toInt()
            val start = match.range.last + 1
            val end = latin.indexOf("endobj", start)
            if (end < 0) return@forEach
            val body = latin.substring(start, end)
            val streamIdx = body.indexOf("stream")
            if (streamIdx < 0) {
                out[number] = PdfObject(body, null)
                return@forEach
            }
            val dict = body.substring(0, streamIdx)
            var dataStart = match.range.last + 1 + streamIdx + "stream".length
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++
            val endStreamAbs = latin.indexOf("endstream", dataStart)
            if (endStreamAbs < 0) {
                out[number] = PdfObject(dict, null)
                return@forEach
            }
            var dataEnd = endStreamAbs
            if (dataEnd > 0 && latin[dataEnd - 1] == '\n') dataEnd--
            if (dataEnd > 0 && latin[dataEnd - 1] == '\r') dataEnd--
            val raw = bytes.copyOfRange(dataStart.coerceAtMost(bytes.size), dataEnd.coerceAtMost(bytes.size))
            out[number] = PdfObject(dict, raw)
        }
        return out
    }

    private fun pageKids(objects: Map<Int, PdfObject>): List<Int> {
        val pages = objects.values.firstOrNull { obj ->
            Regex("/Type\\s*/Pages").containsMatchIn(obj.dict) && !isPageDict(obj.dict)
        } ?: return emptyList()
        val kids = Regex("/Kids\\s*\\[([^]]*)]").find(pages.dict)?.groupValues?.get(1) ?: return emptyList()
        return Regex("(\\d+)\\s+0\\s+R").findAll(kids).map { it.groupValues[1].toInt() }.toList()
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
            val decoded = nums.mapNotNull { objects[it]?.let(::decodeContentStream) }
            val out = ByteArrayOutputStream()
            decoded.forEach { content ->
                out.write(content.bytes)
                out.write('\n'.code)
            }
            return DecodedPageContent(
                bytes = out.toByteArray(),
                complete = decoded.size == nums.size && decoded.all { it.complete },
            )
        }
        return DecodedPageContent(ByteArray(0), complete = false)
    }

    private fun pageXObjects(dict: String): Map<String, Int> {
        val section = Regex("/XObject\\s*<<([^>]*)>>").find(dict)?.groupValues?.get(1) ?: return emptyMap()
        return Regex("/([A-Za-z0-9._]+)\\s+(\\d+)\\s+0\\s+R").findAll(section)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
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
                out.write(buf, 0, n)
            }
            DecodedPageContent(out.toByteArray(), complete = true)
        } catch (_: Exception) {
            DecodedPageContent(raw, complete = false)
        } finally {
            inflater.end()
        }
    }

    private fun extractPdfStrings(data: ByteArray): List<String> {
        val latin = String(data, Charsets.ISO_8859_1)
        val out = mutableListOf<String>()
        val matches = Regex("\\((?:\\\\.|[^\\\\)])*\\)").findAll(latin)
        matches.forEach { match ->
            val inner = match.value.removePrefix("(").removeSuffix(")")
            val decoded = inner
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\(", "(")
                .replace("\\)", ")")
                .replace("\\\\", "\\")
            if (decoded.isNotBlank()) out += decoded
        }
        Regex("\\[([^]]*)]\\s*TJ").findAll(latin).forEach { match ->
            Regex("\\((?:\\\\.|[^\\\\)])*\\)").findAll(match.groupValues[1]).forEach { piece ->
                val inner = piece.value.removePrefix("(").removeSuffix(")")
                if (inner.isNotBlank()) out += inner
            }
        }
        return out
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
