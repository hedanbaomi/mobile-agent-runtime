// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

/**
 * Self-authored, public golden corpus for the knowledge pipeline.
 *
 * Every byte here is produced by this file: no user data, no private source,
 * no downloaded document and no third-party fixture is embedded. The corpus is
 * deliberately deterministic so a rerun measures the pipeline, not the fixture.
 *
 * The corpus separates two very different claims:
 *
 *  - parser/chunk/index/citation plumbing (this corpus covers it precisely), and
 *  - recognition or embedding *semantics* (NOT covered here).
 *
 * A vision marker below is text the local stub returns; it proves the bytes
 * travelled Vision -> chunk -> index -> citation, and proves nothing about what
 * a real provider would recognize on the same page.
 */
enum class GoldenKind {
    TEXT,
    IMAGE_PDF,
    MIXED_PDF,
    TWO_COLUMN_PDF,
    WIDE_TABLE_PDF,
    CROSS_PAGE_TABLE_PDF,
    VECTOR_PDF,
    UNICODE_TEXT,
    LONG_TEXT,
    CORRUPTED_PDF,
    UNSUPPORTED_BINARY,
}

/** One corpus member and the exact evidence the pipeline must preserve. */
data class GoldenCase(
    val id: String,
    val displayName: String,
    val mediaType: String,
    val kind: GoldenKind,
    val bytes: ByteArray,
    val needsVision: Boolean,
    val expectedPages: Int,
    /** Markers that must survive native extraction/reading. */
    val nativeMarkers: List<String> = emptyList(),
    /** Marker the local Vision stub injects; null when the document is text-only. */
    val visionMarker: String? = null,
    /** Row-aware Markdown table the local Vision stub returns, if any. */
    val visionTableMarkdown: String = "",
    /** Pages the local Vision stub must be asked for exactly once each. */
    val visionPages: List<Int> = emptyList(),
    /** Extra markers that live inside [visionTableMarkdown]. */
    val extraMarkers: List<String> = emptyList(),
    /** True when the import must fail closed instead of publishing. */
    val failClosed: Boolean = false,
    /** Token searched after a fail-closed import; the result must stay empty. */
    val failSearchToken: String = "",
) {
    val requiresVision: Boolean get() = visionMarker != null

    val searchMarkers: List<String>
        get() = (nativeMarkers + listOfNotNull(visionMarker).filter { it.isNotBlank() } + extraMarkers).distinct()
}

/** Minimal deterministic PDF writer. Only the constructs the shared parser understands. */
object GoldenPdf {
    private val JPEG_STUB = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    data class Page(
        val runs: List<String> = emptyList(),
        val image: Boolean = false,
        val vector: Boolean = false,
        val width: Int = 612,
        val height: Int = 792,
    )

    fun build(pages: List<Page>): ByteArray {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        val n = pages.size
        val fontObject = 3 + 2 * n
        val imageObject = fontObject + 1
        val objects = mutableListOf<ByteArray>()
        fun text(value: String) = value.toByteArray(Charsets.ISO_8859_1)
        objects += text("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val kids = (0 until n).joinToString(" ") { "${3 + it} 0 R" }
        objects += text("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count $n >>\nendobj\n")
        pages.forEachIndexed { index, page ->
            val resources = if (page.image) {
                "/Font << /F1 $fontObject 0 R >> /XObject << /Im1 $imageObject 0 R >>"
            } else {
                "/Font << /F1 $fontObject 0 R >>"
            }
            objects += text(
                "${3 + index} 0 obj\n<< /Type /Page /Parent 2 0 R " +
                    "/MediaBox [0 0 ${page.width} ${page.height}] /Contents ${3 + n + index} 0 R " +
                    "/Resources << $resources >> >>\nendobj\n",
            )
        }
        pages.forEachIndexed { index, page ->
            val body = content(page)
            objects += text("${3 + n + index} 0 obj\n<< /Length ${body.size} >>\nstream\n") + body +
                text("\nendstream\nendobj\n")
        }
        objects += text("$fontObject 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        if (pages.any { it.image }) {
            objects += text(
                "$imageObject 0 obj\n<< /Type /XObject /Subtype /Image /Width 1 /Height 1 " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${JPEG_STUB.size} >>\nstream\n",
            ) + JPEG_STUB + text("\nendstream\nendobj\n")
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(text("%PDF-1.4\n"))
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
            offsets.forEach { offset -> append("%010d 00000 n \n".format(offset)) }
            append("trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        }
        out.write(text(xref))
        return out.toByteArray()
    }

    private fun content(page: Page): ByteArray {
        val builder = StringBuilder()
        if (page.runs.isNotEmpty()) {
            builder.append("BT /F1 12 Tf 72 720 Td ")
            page.runs.forEachIndexed { index, run ->
                if (index > 0) builder.append(" 0 -14 Td ")
                builder.append('(').append(escape(run)).append(") Tj ")
            }
            builder.append("ET\n")
        }
        if (page.image) builder.append("q 100 0 0 100 72 400 cm /Im1 Do Q\n")
        if (page.vector) builder.append("0 0 100 100 re f\n")
        return builder.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '(' -> out.append("\\(")
                ')' -> out.append("\\)")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}

object KnowledgeGoldenCorpus {
    /**
     * The default repository fingerprint is intentionally the default
     * "vision-unconfigured" value: a directly consented single-document import
     * binds the local backend without pretending it is a real provider.
     */
    const val VISION_FINGERPRINT = "vision-unconfigured"

    fun cases(): List<GoldenCase> = listOf(
        plainText(),
        scannedMultiPage(),
        scannedPdf(),
        mixedPdf(),
        twoColumnPdf(),
        wideTablePdf(),
        crossPageTablePdf(),
        chartsAndFormulasPdf(),
        unicodeText(),
        longPageText(),
        corruptedPdf(),
        unsupportedBinary(),
    )

    private fun plainText(): GoldenCase {
        val marker = "GOLDENPLAINMARKER"
        val body = buildString {
            appendLine("Golden corpus plain text document.")
            appendLine("$marker: torque spec is 12 Nm and the bolt pattern is M8.")
            appendLine("Second paragraph stays readable after chunking.")
        }
        return GoldenCase(
            id = "plain-text",
            displayName = "golden-plain.txt",
            mediaType = "text/plain",
            kind = GoldenKind.TEXT,
            bytes = body.toByteArray(Charsets.UTF_8),
            needsVision = false,
            expectedPages = 1,
            nativeMarkers = listOf(marker),
        )
    }

    private fun scannedPdf(): GoldenCase {
        val marker = "GOLDENSCANNEDOCR"
        return GoldenCase(
            id = "scanned-pdf",
            displayName = "golden-scan.pdf",
            mediaType = "application/pdf",
            kind = GoldenKind.IMAGE_PDF,
            bytes = GoldenPdf.build(listOf(GoldenPdf.Page(image = true))),
            needsVision = true,
            expectedPages = 1,
            visionMarker = marker,
            visionPages = listOf(1),
        )
    }

    private fun scannedMultiPage(): GoldenCase {
        val marker = "GOLDENSCANMULTI"
        return GoldenCase(
            id = "scanned-multipage",
            displayName = "golden-scan-multi.pdf",
            mediaType = "application/pdf",
            kind = GoldenKind.IMAGE_PDF,
            bytes = GoldenPdf.build(List(3) { GoldenPdf.Page(image = true) }),
            needsVision = true,
            expectedPages = 3,
            visionMarker = marker,
            visionPages = listOf(1, 2, 3),
        )
    }
    private fun mixedPdf(): GoldenCase {
        val nativeMarker = "GOLDENMIXEDNATIVE"
        val visionMarker = "GOLDENMIXEDVISION"
        return GoldenCase(
            id = "mixed-layout",
            displayName = "golden-mixed.pdf",
            mediaType = "application/pdf",
            kind = GoldenKind.MIXED_PDF,
            bytes = GoldenPdf.build(
                listOf(GoldenPdf.Page(runs = listOf("$nativeMarker native caption remains page evidence"), image = true)),
            ),
            needsVision = true,
            expectedPages = 1,
            nativeMarkers = listOf(nativeMarker),
            visionMarker = visionMarker,
            visionPages = listOf(1),
        )
    }

    private fun twoColumnPdf(): GoldenCase {
        val left = "GOLDENCOLUMNLEFT"
        val right = "GOLDENCOLUMNRIGHT"
        return GoldenCase(
            id = "two-column",
            displayName = "golden-two-column.pdf",
            mediaType = "application/pdf",
            kind = GoldenKind.TWO_COLUMN_PDF,
            bytes = GoldenPdf.build(
                listOf(GoldenPdf.Page(runs = listOf("$left left column body", "$right right column body"))),
            ),
            needsVision = false,
            expectedPages = 1,
            nativeMarkers = listOf(left, right),
        )
    }

    private fun wideTablePdf(): GoldenCase {
        val visionMarker = "GOLDENWIDEDESC"
        val rows = (1..90).joinToString("\n") { index ->
            val label = "GOLDENWIDEROW" + index.toString().padStart(3, '0')
            "|$label|$index|note-${index.toString().padStart(6, '0')}|"
        }
        val table = "|Item|Qty|Note|\n|---|---|---|\n$rows"
        return GoldenCase(
            id = "wide-table",
            displayName = "golden-wide-table.pdf",
            mediaType = "application/pdf",
            kind = GoldenKind.WIDE_TABLE_PDF,
            bytes = GoldenPdf.build(listOf(GoldenPdf.Page(image = true))),
            needsVision = true,
            expectedPages = 1,
            visionMarker = visionMarker,
            visionTableMarkdown = table,
            visionPages = listOf(1),
            extraMarkers = listOf("GOLDENWIDEROW001", "GOLDENWIDEROW045", "GOLDENWIDEROW090"),
        )
    }

    private fun crossPageTablePdf(): GoldenCase {
        val p1a = "GOLDENCROSSP1A"
        val p1b = "GOLDENCROSSP1B"
        val p2 = "GOLDENCROSSP2A"
        val header = "|Item|Qty|\n|---|---|"
        return GoldenCase(
            id = "cross-page-table",
            displayName = "golden-cross-page-table.pdf",
            mediaType = "application/pdf",
            kind = GoldenKind.CROSS_PAGE_TABLE_PDF,
            bytes = GoldenPdf.build(
                listOf(
                    GoldenPdf.Page(runs = listOf("$header\n|$p1a|1|\n|$p1b|2|")),
                    GoldenPdf.Page(runs = listOf("$header\n|$p2|3|")),
                ),
            ),
            needsVision = true,
            expectedPages = 2,
            nativeMarkers = listOf(p1a, p1b, p2),
            visionMarker = "GOLDENCROSSTABLEVISION",
            visionPages = listOf(1, 1, 2, 2),
        )
    }

    private fun chartsAndFormulasPdf(): GoldenCase {
        val nativeMarker = "GOLDENFORMULA"
        val visionMarker = "GOLDENCHARTVISION"
        return GoldenCase(
            id = "charts-formulas",
            displayName = "golden-charts.pdf",
            mediaType = "application/pdf",
            kind = GoldenKind.VECTOR_PDF,
            bytes = GoldenPdf.build(
                listOf(GoldenPdf.Page(runs = listOf("$nativeMarker E=mc^2 and torque 12Nm caption"), vector = true)),
            ),
            needsVision = true,
            expectedPages = 1,
            nativeMarkers = listOf(nativeMarker),
            visionMarker = visionMarker,
            visionPages = listOf(1),
        )
    }

    private fun unicodeText(): GoldenCase {
        val marker = "GOLDENUNICODE"
        val body = buildString {
            appendLine("$marker CJK 知识库与图谱检索完整性检查。")
            appendLine("combining e\u0301 and a\u0308 stay attached to the base character.")
            appendLine("emoji \uD83D\uDE80 rocket and \uD83D\uDCC4 document and \uD83E\uDDEA test tube.")
            appendLine("RTL \u0645\u0631\u062d\u0628\u0627 alongside mixed script.")
            appendLine("$marker closing marker.")
        }
        return GoldenCase(
            id = "unicode-text",
            displayName = "golden-unicode.txt",
            mediaType = "text/plain",
            kind = GoldenKind.UNICODE_TEXT,
            bytes = body.toByteArray(Charsets.UTF_8),
            needsVision = false,
            expectedPages = 1,
            nativeMarkers = listOf(marker),
        )
    }

    private fun longPageText(): GoldenCase {
        val markers = listOf("GOLDENLONGFIRST", "GOLDENLONGMIDDLE", "GOLDENLONGLAST")
        val builder = StringBuilder()
        repeat(24) { index ->
            val marker = when (index) {
                0 -> markers[0]
                11 -> markers[1]
                23 -> markers[2]
                else -> "GOLDENLONGP" + (index + 1).toString().padStart(2, '0')
            }
            builder.append(marker).append(' ')
            repeat(60) { builder.append("lorem ipsum dolor sit amet ") }
            builder.append("\n\n")
        }
        return GoldenCase(
            id = "long-page",
            displayName = "golden-long.txt",
            mediaType = "text/plain",
            kind = GoldenKind.LONG_TEXT,
            bytes = builder.toString().toByteArray(Charsets.UTF_8),
            needsVision = false,
            expectedPages = 1,
            nativeMarkers = markers,
        )
    }

    private fun corruptedPdf(): GoldenCase {
        val body = "%PDF-1.4\nGOLDENCORRUPTED this body has no page tree or objects\n"
        return GoldenCase(
            id = "corrupted-pdf",
            displayName = "golden-corrupted.pdf",
            mediaType = "application/pdf",
            kind = GoldenKind.CORRUPTED_PDF,
            bytes = body.toByteArray(Charsets.ISO_8859_1),
            needsVision = false,
            expectedPages = 0,
            failClosed = true,
            failSearchToken = "GOLDENCORRUPTED",
        )
    }

    private fun unsupportedBinary(): GoldenCase {
        val marker = "GOLDENUNSUPPORTED"
        val bytes = marker.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0) +
            "trailing-binary-payload".toByteArray(Charsets.ISO_8859_1)
        return GoldenCase(
            id = "unsupported-binary",
            displayName = "golden-unknown.bin",
            mediaType = "application/octet-stream",
            kind = GoldenKind.UNSUPPORTED_BINARY,
            bytes = bytes,
            needsVision = false,
            expectedPages = 0,
            failClosed = true,
            failSearchToken = marker,
        )
    }
}

/** Published chunk texts of the active version, in ordinal order. */
fun publishedChunkTexts(db: SqlConnection, documentId: String): List<String> =
    db.query(
        "SELECT chunks.text AS text FROM chunks " +
            "JOIN documents ON documents.active_version_id = chunks.document_version_id " +
            "WHERE documents.id = ? ORDER BY chunks.ordinal",
        listOf(documentId),
    ).map { it.string("text") }

/** Published chunk count of the active version. */
fun publishedChunkCount(db: SqlConnection, documentId: String): Long =
    db.query(
        "SELECT COUNT(*) AS n FROM chunks " +
            "JOIN documents ON documents.active_version_id = chunks.document_version_id " +
            "WHERE documents.id = ?",
        listOf(documentId),
    ).single().long("n")
