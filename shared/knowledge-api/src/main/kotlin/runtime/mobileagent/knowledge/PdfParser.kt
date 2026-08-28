// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object PdfParser {
    const val FINGERPRINT = "pdf-text-v3"

    fun parse(bytes: ByteArray): ParsedPublication {
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
        pageNumbers.forEachIndexed { index, objNum ->
            val pageObj = objects[objNum] ?: return@forEachIndexed
            val pageIndex = index + 1
            val content = pageContent(objects, pageObj.dict)
            val decoded = content?.let { decodeStream(it) } ?: ByteArray(0)
            val pageLatin = String(decoded, Charsets.ISO_8859_1)
            val text = extractPdfStrings(decoded).joinToString(" ").trim()
            val xobjects = pageXObjects(pageObj.dict)
            xobjects.forEach { (name, imageObjNum) ->
                val image = objects[imageObjNum] ?: return@forEach
                if (!isImageDict(image.dict) || image.stream == null) return@forEach
                assignedImages += imageObjNum
                imageOrdinal += 1
                val filter = streamFilter(image.dict)
                val payload = if (filter.contains("DCTDecode")) decodeStream(image) else image.stream
                val usedOnPage = pageLatin.contains("/$name") || Regex("/${Regex.escape(name)}\\s+Do").containsMatchIn(pageLatin)
                assets += ExtractedAsset(
                    localId = "img-$imageOrdinal",
                    kind = "IMAGE",
                    page = if (usedOnPage || xobjects.size == 1) pageIndex else pageIndex,
                    section = name,
                    bytes = payload,
                    mediaType = if (filter.contains("DCTDecode")) "image/jpeg" else "application/octet-stream",
                    surroundingText = text,
                )
            }
            extractInlineImages(decoded).forEach { payload ->
                imageOrdinal += 1
                assets += ExtractedAsset(
                    localId = "inline-$imageOrdinal",
                    kind = "IMAGE",
                    page = pageIndex,
                    section = "inline",
                    bytes = payload,
                    mediaType = "application/octet-stream",
                    surroundingText = text,
                )
            }
            val hasInline = hasInlineImage(pageLatin)
            val hasImages = xobjects.isNotEmpty() || hasInline || Regex("/Subtype\\s*/Image").containsMatchIn(pageObj.dict)
            val hasDrawing = hasVectorDrawing(pageLatin)
            val needsVision = hasImages || hasDrawing || text.isEmpty()
            pages += ExtractedPage(pageIndex, text, needsVision)
            if (needsVision && assets.none { it.page == pageIndex && it.kind == "IMAGE" && it.bytes.isNotEmpty() }) {
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
            imageOrdinal += 1
            val filter = streamFilter(image.dict)
            val payload = if (filter.contains("DCTDecode")) decodeStream(image) else image.stream
            assets += ExtractedAsset(
                localId = "img-$imageOrdinal",
                kind = "IMAGE",
                page = null,
                section = null,
                bytes = payload,
                mediaType = if (filter.contains("DCTDecode")) "image/jpeg" else "application/octet-stream",
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
            streamFilter(dict).contains("DCTDecode")

    private fun pageContent(objects: Map<Int, PdfObject>, dict: String): PdfObject? {
        val single = Regex("/Contents\\s+(\\d+)\\s+0\\s+R").find(dict)?.groupValues?.get(1)?.toIntOrNull()
        if (single != null) return objects[single]
        val array = Regex("/Contents\\s*\\[([^]]*)]").find(dict)?.groupValues?.get(1)
        if (array != null) {
            val nums = Regex("(\\d+)\\s+0\\s+R").findAll(array).map { it.groupValues[1].toInt() }.toList()
            val combined = nums.mapNotNull { objects[it]?.stream }.fold(ByteArray(0)) { acc, next -> acc + next }
            val first = nums.firstOrNull()?.let { objects[it] }
            return PdfObject(first?.dict.orEmpty(), combined)
        }
        return null
    }

    private fun pageXObjects(dict: String): Map<String, Int> {
        val section = Regex("/XObject\\s*<<([^>]*)>>").find(dict)?.groupValues?.get(1) ?: return emptyMap()
        return Regex("/([A-Za-z0-9._]+)\\s+(\\d+)\\s+0\\s+R").findAll(section)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    private fun streamFilter(dict: String): String =
        Regex("/Filter\\s*/([A-Za-z]+)").find(dict)?.groupValues?.get(1).orEmpty()

    private fun decodeStream(obj: PdfObject): ByteArray {
        val raw = obj.stream ?: return ByteArray(0)
        if (streamFilter(obj.dict) != "FlateDecode") return raw
        return runCatching {
            val inflater = Inflater()
            inflater.setInput(raw)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toByteArray()
        }.getOrDefault(raw)
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
