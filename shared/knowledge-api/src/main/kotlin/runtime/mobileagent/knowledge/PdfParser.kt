// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object PdfParser {
    const val FINGERPRINT = "pdf-text-v1"

    fun parse(bytes: ByteArray): ParsedPublication {
        if (bytes.size < 5 || String(bytes.copyOfRange(0, 5), Charsets.ISO_8859_1) != "%PDF-") {
            error("Not a PDF")
        }
        val latin = String(bytes, Charsets.ISO_8859_1)
        val streams = extractStreams(bytes, latin)
        val texts = mutableListOf<String>()
        val assets = mutableListOf<ExtractedAsset>()
        var imageOrdinal = 0
        streams.forEach { stream ->
            val decoded = decodeStream(stream)
            extractPdfStrings(decoded).forEach { texts += it }
            if (stream.dict.contains("/Image") || stream.filter.contains("DCTDecode")) {
                imageOrdinal += 1
                val payload = if (stream.filter.contains("DCTDecode")) decoded else stream.raw
                assets += ExtractedAsset(
                    localId = "img-$imageOrdinal",
                    kind = "IMAGE",
                    page = null,
                    section = null,
                    bytes = payload,
                    mediaType = if (stream.filter.contains("DCTDecode")) "image/jpeg" else "application/octet-stream",
                    surroundingText = extractPdfStrings(decoded).joinToString(" "),
                )
            }
        }
        val pageCount = Regex("/Type\\s*/Page(?![s])").findAll(latin).count().coerceAtLeast(
            if (texts.isNotEmpty() || assets.isNotEmpty()) 1 else 0,
        )
        val joined = texts.joinToString("\n").trim()
        val hasImages = assets.isNotEmpty() || latin.contains("/Subtype /Image") || latin.contains("/Subtype/Image")
        val hasUncertainGraphics = hasVectorDrawing(latin) && !hasOnlyTextOperators(latin)
        val needsVision = hasImages || hasUncertainGraphics || (joined.isEmpty() && pageCount > 0)
        if (pageCount == 0 && joined.isEmpty() && assets.isEmpty()) {
            error("PDF has no extractable pages or text")
        }
        val pages = if (pageCount <= 1) {
            listOf(ExtractedPage(1, joined, needsVision))
        } else {
            val parts = splitByPageMarkers(joined, pageCount)
            parts.mapIndexed { index, text -> ExtractedPage(index + 1, text, needsVision) }
        }
        return ParsedPublication(
            format = SourceFormat.PDF,
            text = pages.joinToString("\n") { page ->
                val prefix = "Page ${page.page}: "
                if (page.text.isBlank()) prefix.trim() else prefix + page.text
            },
            pages = pages,
            assets = assets,
            needsVision = needsVision,
            parserFingerprint = FINGERPRINT,
        )
    }

    fun writeSimpleTextPdf(text: String): ByteArray {
        val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val streamBody = "BT /F1 12 Tf 72 720 Td ($escaped) Tj ET\n"
        return assemblePdf(
            content = streamBody,
            extraObjects = emptyList(),
            resources = "/Font << /F1 5 0 R >>",
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
        val content = "BT /F1 12 Tf 72 700 Td ($escaped) Tj ET\nq 100 0 0 100 72 400 cm /Im1 Do Q\n"
        return assemblePdf(
            content = content,
            extraObjects = listOf(imageObj to jpeg),
            resources = "/Font << /F1 5 0 R >> /XObject << /Im1 6 0 R >>",
        )
    }

    private data class PdfStream(val dict: String, val filter: String, val raw: ByteArray)

    private fun extractStreams(bytes: ByteArray, latin: String): List<PdfStream> {
        val out = mutableListOf<PdfStream>()
        val marker = "stream"
        var from = 0
        while (true) {
            val idx = latin.indexOf(marker, from)
            if (idx < 0) break
            val dictStart = latin.lastIndexOf("<<", idx)
            val dict = if (dictStart >= 0) latin.substring(dictStart, idx) else ""
            var dataStart = idx + marker.length
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++
            val end = latin.indexOf("endstream", dataStart)
            if (end < 0) break
            var dataEnd = end
            if (dataEnd > 0 && latin[dataEnd - 1] == '\n') dataEnd--
            if (dataEnd > 0 && latin[dataEnd - 1] == '\r') dataEnd--
            val raw = bytes.copyOfRange(dataStart.coerceAtMost(bytes.size), dataEnd.coerceAtMost(bytes.size))
            val filter = Regex("/Filter\\s*/([A-Za-z]+)").find(dict)?.groupValues?.get(1).orEmpty()
            out += PdfStream(dict, filter, raw)
            from = end + 9
        }
        return out
    }

    private fun decodeStream(stream: PdfStream): ByteArray {
        if (stream.filter != "FlateDecode") return stream.raw
        return runCatching {
            val inflater = Inflater()
            inflater.setInput(stream.raw)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toByteArray()
        }.getOrDefault(stream.raw)
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

    private fun hasOnlyTextOperators(latin: String): Boolean {
        val stripped = latin.replace(Regex("BT[\\s\\S]*?ET"), " ")
        return !stripped.contains("/Image") && !Regex("\\sDo\\s").containsMatchIn(stripped)
    }

    private fun splitByPageMarkers(text: String, pageCount: Int): List<String> {
        if (pageCount <= 1) return listOf(text)
        val chunk = (text.length / pageCount).coerceAtLeast(1)
        return (0 until pageCount).map { index ->
            val start = index * chunk
            val end = if (index == pageCount - 1) text.length else ((index + 1) * chunk).coerceAtMost(text.length)
            if (start >= text.length) "" else text.substring(start, end)
        }
    }

    private fun jpegStub(): ByteArray {
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        return header
    }

    private fun assemblePdf(
        content: String,
        extraObjects: List<Pair<String, ByteArray>>,
        resources: String,
    ): ByteArray {
        val contentBytes = content.toByteArray(Charsets.ISO_8859_1)
        val objects = mutableListOf<ByteArray>()
        fun obj(body: String) = body.toByteArray(Charsets.ISO_8859_1)
        objects += obj("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        objects += obj("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        objects += obj(
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << $resources >> >>\nendobj\n",
        )
        objects += obj("4 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n") + contentBytes + obj("\nendstream\nendobj\n")
        objects += obj("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        extraObjects.forEachIndexed { index, (dictPrefix, payload) ->
            val n = 6 + index
            objects += obj("$n 0 obj\n$dictPrefix") + payload + obj("\nendstream\nendobj\n")
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
