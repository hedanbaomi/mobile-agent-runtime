// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentParserTest {
    @Test
    fun compactDictionaryObjectBoundariesPreserveThePageTree() {
        // Pillow uses obj<< ... >>endobj. Dictionary delimiters separate the tokens;
        // neither boundary requires an extra whitespace byte. The helper emits correct xref offsets.
        val parsed = PdfParser.parse(nestedPageTreePdf(compactObjects = true))
        assertEquals(listOf("first leaf", "second leaf"), parsed.pages.map { it.text })
        assertEquals(2, parsed.pages.size)
        assertTrue(parsed.pages.first().needsVision)
    }

    @Test
    fun nestedPdfPageTreeUsesCatalogRootAndPreservesEveryLeafInOrder() {
        val parsed = PdfParser.parse(nestedPageTreePdf())
        assertEquals(listOf(1, 2), parsed.pages.map { it.page })
        assertEquals(listOf("first leaf", "second leaf"), parsed.pages.map { it.text })
        assertTrue(parsed.pages.first().needsVision)
        assertFalse(parsed.pages.last().needsVision)
        assertEquals(listOf(1), parsed.assets.filter { it.kind == "PAGE" }.map { it.page })
    }

    @Test
    fun cyclicOrMissingPdfPageTreeFailsWithoutSilentlyDroppingPages() {
        assertThrows(IllegalArgumentException::class.java) { PdfParser.parse(nestedPageTreePdf("2 0 R")) }
        assertThrows(IllegalArgumentException::class.java) { PdfParser.parse(nestedPageTreePdf("99 0 R")) }
    }

    @Test
    fun compressedObjectStreamKeepsCatalogAndNestedPageLeaves() {
        val parsed = PdfParser.parse(objectStreamPdf())
        assertEquals(listOf("first leaf", "second leaf"), parsed.pages.map { it.text })
        assertEquals(2, parsed.pages.size)
    }

    @Test
    fun malformedObjectStreamHeaderFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) { PdfParser.parse(objectStreamPdf(firstOverride = 999999)) }
    }

    @Test
    fun streamLengthPreservesTrailingCarriageReturnInCompressedPayload() {
        val parsed = PdfParser.parse(objectStreamPdf(trailingCarriageReturn = true))
        assertEquals(listOf("first leaf", "second leaf"), parsed.pages.map { it.text })
    }

    private fun objectStreamPdf(firstOverride: Int? = null, trailingCarriageReturn: Boolean = false): ByteArray {
        val compressedObjects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [4 0 R 3 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R >>",
            "<< /Type /Pages /Parent 2 0 R /Kids [5 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 4 0 R /MediaBox [0 0 612 792] /Contents 7 0 R >>",
        )
        var offset = 0
        val header = compressedObjects.mapIndexed { index, body ->
            "${index + 1} $offset ".also { offset += body.length + 1 }
        }.joinToString("")
        val plain = header + compressedObjects.joinToString("\n") + "\n"
        val compressed = if (trailingCarriageReturn) {
            (0..65521).asSequence().map { deflate((plain + " ".repeat(it)).toByteArray()) }
                .first { it.last() == '\r'.code.toByte() }
        } else deflate(plain.toByteArray())
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.5\n".toByteArray())
        val offsets = mutableMapOf<Int, Int>()
        fun writeStream(number: Int, dict: String, bytes: ByteArray) {
            offsets[number] = out.size()
            out.write("$number 0 obj\n<< $dict /Length ${bytes.size} >>\nstream\n".toByteArray())
            out.write(bytes)
            out.write("\nendstream\nendobj\n".toByteArray())
        }
        writeStream(6, "", "BT (second leaf) Tj ET".toByteArray())
        writeStream(7, "", "BT (first leaf) Tj ET".toByteArray())
        writeStream(8, "/Type /ObjStm /N 5 /First ${firstOverride ?: header.length} /Filter /FlateDecode", compressed)
        offsets[9] = out.size()
        val xref = java.nio.ByteBuffer.allocate(10 * 7)
        xref.put(0.toByte()).putInt(0).putShort(65535.toShort())
        (1..5).forEach { number -> xref.put(2.toByte()).putInt(8).putShort((number - 1).toShort()) }
        (6..9).forEach { number -> xref.put(1.toByte()).putInt(offsets.getValue(number)).putShort(0.toShort()) }
        writeStream(9, "/Type /XRef /Root 1 0 R /Size 10 /W [1 4 2]", xref.array())
        out.write("startxref\n${offsets.getValue(9)}\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    private fun nestedPageTreePdf(branchKids: String = "5 0 R", compactObjects: Boolean = false): ByteArray {
        fun stream(text: String) = "<< /Length ${text.length} >>\nstream\n$text\nendstream"
        // The intermediate /Pages object precedes the actual catalog root in
        // file order, as it does in ordinary PDFs using a nested page tree.
        val bodies = linkedMapOf(
            1 to "<< /Type /Catalog /Pages 2 0 R >>",
            4 to "<< /Type /Pages /Parent 2 0 R /Kids [$branchKids] /Count 1 >>",
            5 to "<< /Type /Page /Parent 4 0 R /MediaBox [0 0 612 792] /Contents 7 0 R >>",
            2 to "<< /Type /Pages /Kids [4 0 R 3 0 R] /Count 2 >>",
            3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R >>",
            6 to stream("BT (second leaf) Tj ET"),
            7 to stream("BT (first leaf) Tj ET\n0 0 100 100 re f\n"),
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray())
        val offsets = bodies.mapValues { (number, body) ->
            out.size().also {
                val startSeparator = if (compactObjects) "" else "\n"
                val endSeparator = if (compactObjects && body.endsWith(">>")) "" else "\n"
                out.write("$number 0 obj$startSeparator$body${endSeparator}endobj\n".toByteArray())
            }
        }
        val xref = out.size()
        out.write("xref\n0 8\n0000000000 65535 f \n".toByteArray())
        (1..7).forEach { number -> out.write("%010d 00000 n \n".format(offsets.getValue(number)).toByteArray()) }
        out.write("trailer\n<< /Size 8 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun declaredStreamLengthWinsOverEmbeddedMarkersAndKeepsVectorEvidence() {
        val content = """
            BT /F1 12 Tf 72 720 Td (before) Tj ET
            BT /F1 12 Tf 72 700 Td (endobj) Tj ET
            BT /F1 12 Tf 72 680 Td (endstream) Tj ET
            0 0 100 100 re f
            BT /F1 12 Tf 72 660 Td (after) Tj ET
        """.trimIndent().toByteArray(Charsets.ISO_8859_1)

        val parsed = PdfParser.parse(singlePageContentPdf(content))
        val page = parsed.pages.single()

        assertTrue(page.text.contains("before"), page.text)
        assertTrue(page.text.contains("endstream"), page.text)
        assertTrue(page.text.contains("after"), page.text)
        assertTrue(page.needsVision)
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 && it.bytes.isEmpty() })
    }

    @Test
    fun inheritedOrIndirectResourcesWithUnresolvedDoKeepPageBlocked() {
        listOf(false, true).forEach { indirectResources ->
            val parsed = PdfParser.parse(inheritedRawImagePdf(indirectResources))
            val page = parsed.pages.single()

            assertTrue(page.needsVision, "indirectResources=$indirectResources")
            assertTrue(
                parsed.assets.any { it.kind == "PAGE" && it.page == 1 && it.bytes.isEmpty() },
                "indirectResources=$indirectResources assets=${parsed.assets.map { it.kind }}",
            )
        }
    }

    @Test
    fun oversizedUncompressedContentFailsClosedAsVisualGap() {
        val prefix = "BT /F1 12 Tf 72 720 Td (oversized stream) Tj ET\n"
            .toByteArray(Charsets.ISO_8859_1)
        val content = ByteArrayOutputStream().apply {
            write(prefix)
            write(ByteArray(32 * 1024 * 1024 + 1) { ' '.code.toByte() })
        }.toByteArray()

        val parsed = PdfParser.parse(singlePageContentPdf(content))
        val page = parsed.pages.single()

        assertTrue(page.needsVision)
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 && it.bytes.isEmpty() })
    }

    @Test
    fun declaredLengthAcceptsAdjacentBinaryEndstreamWithoutSkippingFollowingPage() {
        val parsed = PdfParser.parse(adjacentBinaryStreamPdf())

        assertEquals(1, parsed.pages.size)
        assertTrue(parsed.pages.single().needsVision)
    }

    private fun adjacentBinaryStreamPdf(): ByteArray {
        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [5 0 R] /Count 1 >>\nendobj\n",
            // The payload ends directly before endstream. Object 5 must still
            // be discovered after this stream; object 6 supplies a later,
            // delimiter-prefixed marker for the regression.
            "4 0 obj\n<< /Length 3 >>\nstream\nabcendstream\nendobj\n",
            "5 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Contents 4 0 R >>\nendobj\n",
            "6 0 obj\n<< /Length 1 >>\nstream\nx\nendstream\nendobj\n",
        )
        return ByteArrayOutputStream().apply {
            write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
            objects.forEach { write(it.toByteArray(Charsets.ISO_8859_1)) }
            write("%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
    }

    private fun singlePageContentPdf(
        content: ByteArray,
        fontDict: String = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        extraObjects: Map<Int, ByteArray> = emptyMap(),
    ): ByteArray {
        val objects = linkedMapOf(
            1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
            2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            3 to plainPdfObject(
                3,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            ),
            4 to streamPdfObject(4, "", content),
            5 to plainPdfObject(5, fontDict),
        )
        objects.putAll(extraObjects)
        return finishPdf(objects)
    }

    private fun inheritedRawImagePdf(indirectResources: Boolean): ByteArray {
        val resourceDict = "<< /Font << /F1 5 0 R >> /XObject << /Im1 6 0 R >> >>"
        val pagesResources = if (indirectResources) "/Resources 7 0 R" else "/Resources $resourceDict"
        val rawImage = deflate(byteArrayOf(0x7F, 0x20, 0x10))
        val objects = linkedMapOf(
            1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
            2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 $pagesResources >>"),
            3 to plainPdfObject(
                3,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>",
            ),
            4 to streamPdfObject(
                4,
                "",
                (
                    "BT /F1 12 Tf 72 720 Td (inherited raw image) Tj ET\n" +
                        "q 100 0 0 100 72 400 cm /Im1 Do Q\n"
                ).toByteArray(Charsets.ISO_8859_1),
            ),
            5 to plainPdfObject(5, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            6 to streamPdfObject(
                6,
                "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB " +
                    "/BitsPerComponent 8 /Filter /FlateDecode",
                rawImage,
            ),
        )
        if (indirectResources) {
            objects[7] = plainPdfObject(7, resourceDict)
        }
        return finishPdf(objects)
    }

    private fun plainPdfObject(number: Int, body: String): ByteArray =
        "$number 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1)

    private fun streamPdfObject(number: Int, dict: String, payload: ByteArray): ByteArray =
        "$number 0 obj\n<< $dict /Length ${payload.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) +
            payload + "\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1)

    private fun finishPdf(objects: Map<Int, ByteArray>): ByteArray {
        val maxNumber = objects.keys.maxOrNull() ?: error("PDF requires at least one object")
        require(objects.keys == (1..maxNumber).toSet()) { "PDF test objects must be contiguous" }
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = IntArray(maxNumber + 1)
        objects.toSortedMap().forEach { (number, body) ->
            offsets[number] = out.size()
            out.write(body)
        }
        val xrefAt = out.size()
        val count = maxNumber + 1
        out.write("xref\n0 $count\n0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        (1..maxNumber).forEach { number ->
            out.write("%010d 00000 n \n".format(offsets[number]).toByteArray(Charsets.ISO_8859_1))
        }
        out.write(
            "trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n"
                .toByteArray(Charsets.ISO_8859_1),
        )
        return out.toByteArray()
    }

    @Test
    fun textPdfExtractsPageTextWithoutVision() {
        val pdf = PdfParser.writeSimpleTextPdf("Alpha widget torque spec is 12Nm.")
        val parsed = PdfParser.parse(pdf)
        assertTrue(parsed.text.contains("12Nm"))
        assertEquals(1, parsed.pages.size)
        assertFalse(parsed.needsVision)
        assertEquals(PdfParser.FINGERPRINT, parsed.parserFingerprint)
    }

    @Test
    fun tjGlyphArrayDoesNotInsertSpacesInsideWordsOrDuplicateText() {
        val pdf = String(PdfParser.writeSimpleTextPdf("placeholder"), Charsets.ISO_8859_1)
            .replace(
                "(placeholder) Tj",
                "[(L)(o)(n)(e) -120 (s)(t)(u)(d)(y)] TJ",
            )
            .toByteArray(Charsets.ISO_8859_1)

        val text = PdfParser.parse(pdf).pages.single().text

        assertTrue(text.contains("Lone study"), text)
        assertFalse(text.contains("L o n e"), text)
        assertEquals(1, text.windowed("Lone study".length).count { it == "Lone study" }, text)
    }

    @Test
    fun tjPositiveKerningDoesNotInsertSpacesAndExplicitWhitespaceIsPreserved() {
        val pdf = String(PdfParser.writeSimpleTextPdf("placeholder"), Charsets.ISO_8859_1)
            .replace(
                "(placeholder) Tj",
                "[(L)120(o)80(n)(e)( )(s)(t)(u)(d)(y)] TJ",
            )
            .toByteArray(Charsets.ISO_8859_1)

        val text = PdfParser.parse(pdf).pages.single().text

        assertTrue(text.contains("Lone study"), text)
        assertFalse(text.contains("L o"), text)
    }

    @Test
    fun mixedLiteralAndHexStringsAreIndexedAsCompleteText() {
        val parsed = PdfParser.parse(
            PdfParser.writeLiteralAndHexTextPdf("TITLE", "BODY: KEEP THIS SENTENCE."),
        )
        assertEquals("TITLE BODY: KEEP THIS SENTENCE.", parsed.pages.single().text)
        assertFalse(parsed.pages.single().needsVision)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun hexStringsInsideTjArrayAreExtracted() {
        val parsed = PdfParser.parse(
            PdfParser.writeLiteralAndHexArrayPdf("TITLE", "BODY: KEEP THIS SENTENCE."),
        )
        assertTrue(parsed.pages.single().text.contains("TITLE"), parsed.pages.single().text)
        assertTrue(parsed.pages.single().text.contains("BODY: KEEP THIS SENTENCE."), parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun undecodedHexShowOperatorDoesNotCountAsCompleteText() {
        val parsed = PdfParser.parse(PdfParser.writeLiteralAndUndecodedHexShowPdf("TITLE"))
        assertEquals("TITLE", parsed.pages.single().text)
        assertTrue(parsed.pages.single().needsVision)
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.bytes.isEmpty() })
    }

    @Test
    fun quotedCommentBeforeShowOperatorIsExtracted() {
        val parsed = PdfParser.parse(
            PdfParser.writeQuotedCommentShowPdf("TITLE", "BODY: KEEP THIS SENTENCE."),
        )
        assertEquals("TITLE BODY: KEEP THIS SENTENCE.", parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun escapedBackslashBeforeNStaysAPathNotANewline() {
        val parsed = PdfParser.parse(PdfParser.writeTwoLiteralTextPdf("TITLE", "C:\\notes"))
        assertTrue(parsed.pages.single().text.contains("C:\\notes"), parsed.pages.single().text)
        assertFalse(parsed.pages.single().text.contains("C:\\\n"), parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun fontDifferencesMapHexBytesToGlyphNames() {
        val parsed = PdfParser.parse(PdfParser.writeHexWithFontDifferencesPdf())
        assertEquals("XYZ", parsed.pages.single().text)
        assertFalse(parsed.needsVision)
        val labeled = PdfParser.parse(PdfParser.writeHexWithFontDifferencesPdf("KEEPTOKEN"))
        assertTrue(labeled.pages.single().text.contains("KEEPTOKEN"), labeled.pages.single().text)
        assertTrue(labeled.pages.single().text.contains("XYZ"), labeled.pages.single().text)
        assertFalse(labeled.pages.single().text.contains("ABC"), labeled.pages.single().text)
    }

    @Test
    fun winAnsiEncodingMapsEuroAndDoesNotClaimLatin1() {
        val parsed = PdfParser.parse(PdfParser.writeWinAnsiEuroPdf())
        assertTrue(parsed.pages.single().text.contains("KEEPTOKEN"), parsed.pages.single().text)
        assertTrue(parsed.pages.single().text.contains("Price: €10"), parsed.pages.single().text)
        assertFalse(parsed.pages.single().text.contains("\u0080"), parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun macRomanEncodingMapsEAcute() {
        val parsed = PdfParser.parse(PdfParser.writeMacRomanCafePdf())
        assertTrue(parsed.pages.single().text.contains("KEEPTOKEN"), parsed.pages.single().text)
        assertTrue(parsed.pages.single().text.contains("café"), parsed.pages.single().text)
        assertFalse(parsed.pages.single().text.contains("\u008E"), parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun symbolBuiltInEncodingMapsGreekInsteadOfLatinBytes() {
        val parsed = PdfParser.parse(PdfParser.writeSymbolBuiltinPdf("KEEPTOKEN"))
        val text = parsed.pages.single().text
        assertTrue(text.contains("KEEPTOKEN"), text)
        assertTrue(text.contains("\u03B1\u03B2\u03B3"), text)
        assertFalse(text.contains("abg"), text)
        assertFalse(parsed.needsVision, text)
        assertTrue(parsed.assets.none { it.kind == "PAGE" }, parsed.assets.toString())
    }

    /**
     * Dictionary lexicon shapes that a flat `/Name` scan mis-reads: a comment
     * between the key and its value, a `]` or `>>` inside a comment or string, a
     * same-named key in a nested dictionary, and `/Differences` used as a name
     * value before the real key. Each one previously dropped the declared mapping
     * and published the undeclared bytes as complete text.
     */
    @Test
    fun differencesIsFoundAcrossLexiconShapesOtherwiseDiscardedByAFlatScan() {
        val base = "/Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /BaseEncoding /WinAnsiEncoding"
        val tail = "/Differences [65 /X 66 /Y 67 /Z] >> >>"
        val cases = mapOf(
            "comment after key" to "$base /Differences % harmless comment\n [65 /X 66 /Y 67 /Z] >> >>",
            "comment containing array closer" to "$base /Differences [ % ] harmless comment\n 65 /X 66 /Y 67 /Z] >> >>",
            "comment containing dict closer" to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /BaseEncoding /WinAnsiEncoding % >> harmless comment\n $tail",
            "string containing dict closer" to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /BaseEncoding /WinAnsiEncoding /Note (>>) $tail",
            "same name in nested dictionary" to "$base /Private << /Differences [] >> $tail",
            "name value before the real key" to "$base /Custom /Differences $tail",
        )
        cases.forEach { (label, fontDict) ->
            val parsed = PdfParser.parse(PdfParser.writeVerbatimFontDictPdf(fontDict, literal = "KEEPTOKEN"))
            val text = parsed.pages.single().text
            assertTrue(text.contains("KEEPTOKEN"), "$label: $text")
            assertTrue(text.contains("XYZ"), "$label: $text")
            assertFalse(text.contains("ABC"), "$label: $text")
            assertFalse(parsed.needsVision, "$label: $text")
        }
    }

    @Test
    fun malformedDifferencesValueFailsClosedInsteadOfPublishingUnmappedText() {
        val fontDict = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding " +
            "<< /BaseEncoding /WinAnsiEncoding /Differences 5 >> >>"
        val parsed = PdfParser.parse(PdfParser.writeVerbatimFontDictPdf(fontDict, literal = "KEEPTOKEN"))
        assertTrue(parsed.pages.single().text.contains("KEEPTOKEN"), parsed.pages.single().text)
        assertTrue(parsed.pages.single().needsVision, parsed.pages.single().text)
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 }, parsed.assets.toString())
    }

    @Test
    fun pageWithoutContentOrAnnotationsDoesNotNeedVision() {
        val pdf = finishPdf(
            mapOf(
                1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
                2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
                3 to plainPdfObject(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Contents 5 0 R /Resources << /Font << /F1 6 0 R >> >> >>"),
                4 to plainPdfObject(4, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"),
                5 to streamPdfObject(5, "", "BT /F1 12 Tf 72 720 Td (REALTEXT) Tj ET".toByteArray()),
                6 to plainPdfObject(6, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            ),
        )
        val parsed = PdfParser.parse(pdf)
        assertEquals(2, parsed.pages.size)
        assertFalse(parsed.needsVision)
        assertFalse(parsed.pages[1].needsVision)
        assertTrue(parsed.text.contains("REALTEXT"))
        assertFalse(parsed.text.contains("Page 2"))
        assertTrue(parsed.assets.isEmpty())
        val compressedBlank = finishPdf(
            mapOf(
                1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
                2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
                3 to plainPdfObject(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Contents 5 0 R /Resources << /Font << /F1 6 0 R >> >> >>"),
                4 to plainPdfObject(4, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R >>"),
                5 to streamPdfObject(5, "", "BT /F1 12 Tf 72 720 Td (REALTEXT) Tj ET".toByteArray()),
                6 to plainPdfObject(6, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
                7 to streamPdfObject(7, "/Filter /FlateDecode", deflate(ByteArray(0))),
            ),
        )
        val reparsed = PdfParser.parse(compressedBlank)
        assertFalse(reparsed.pages[1].needsVision)
        assertEquals(parsed.text, reparsed.text)
    }

    @Test
    fun allBlankPdfIsRejectedButVisibleAnnotationKeepsVisualCheck() {
        val noContent = finishPdf(
            mapOf(
                1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
                2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to plainPdfObject(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"),
            ),
        )
        assertThrows(IllegalStateException::class.java) { PdfParser.parse(noContent) }
        assertThrows(IllegalStateException::class.java) {
            PdfParser.parse(singlePageContentPdf("\n % blank\n".toByteArray()))
        }
        val compressedBlank =
            finishPdf(
                mapOf(
                    1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
                    2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                    3 to plainPdfObject(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"),
                    4 to streamPdfObject(4, "/Filter /FlateDecode", deflate(ByteArray(0))),
                ),
            )
        assertThrows(IllegalStateException::class.java) { PdfParser.parse(compressedBlank) }
        val annotated = finishPdf(
            mapOf(
                1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
                2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to plainPdfObject(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Annots [4 0 R] >>"),
                4 to plainPdfObject(4, "<< /Type /Annot /Subtype /FreeText /Rect [0 0 50 20] >>"),
            ),
        )
        assertTrue(PdfParser.parse(annotated).needsVision)
    }

    @Test
    fun invalidFlateContentStillKeepsPageBlocker() {
        val pdf = finishPdf(
            mapOf(
                1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
                2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to plainPdfObject(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"),
                4 to streamPdfObject(4, "/Filter /FlateDecode", byteArrayOf(1, 2, 3)),
            ),
        )
        val parsed = PdfParser.parse(pdf)
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 })
    }

    @Test
    fun visibleTextPageAnnotationsNeedVisionWhileBorderlessLinksDoNot() {
        fun pdfWithAnnotation(annotation: String): ByteArray = finishPdf(
            mapOf(
                1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
                2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to plainPdfObject(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> /Annots [6 0 R] >>"),
                4 to streamPdfObject(4, "", "BT /F1 12 Tf 72 720 Td (READABLE) Tj ET".toByteArray()),
                5 to plainPdfObject(5, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
                6 to plainPdfObject(6, "<< /Type /Annot $annotation /Rect [0 0 40 20] >>"),
            ),
        )
        val invisibleLink = PdfParser.parse(pdfWithAnnotation("/Subtype /Link /Border [0 0 0]"))
        assertFalse(invisibleLink.needsVision)
        val visibleText = PdfParser.parse(pdfWithAnnotation("/Subtype /FreeText /Border [0 0 0]"))
        assertTrue(visibleText.needsVision)
        assertTrue(visibleText.assets.any { it.kind == "PAGE" })
        val borderedLink = PdfParser.parse(pdfWithAnnotation("/Subtype /Link /Border [0 0 1]"))
        assertTrue(borderedLink.needsVision)
        assertFalse(PdfParser.parse(pdfWithAnnotation("/Subtype /Link /BS << /W 0 /S /S >>")).needsVision)
        assertFalse(PdfParser.parse(pdfWithAnnotation("/Subtype /Link /BS << /W 0.0 >> /Border [0 0 0]")).needsVision)
        listOf(
            "/Subtype /Link /BS << /S /S >>",
            "/Subtype /Link /BS << /W 1 >> /Border [0 0 0]",
            "/Subtype /Link /BS 9 0 R",
            "/Subtype /Link",
            "/Subtype /Link /BS << /W 0 >> /AP << /N 9 0 R >>",
        ).forEach { annotation ->
            assertTrue(PdfParser.parse(pdfWithAnnotation(annotation)).needsVision, annotation)
        }
    }

    @Test
    fun customEmbeddedFontWithCompleteDifferencesUsesTextLayer() {
        val fontDict = "<< /Type /Font /Subtype /Type1 /BaseFont /Subset+PrivateFace " +
            "/Encoding << /Differences [65 /A /B /C] >> >>"
        val parsed = PdfParser.parse(PdfParser.writeVerbatimFontDictPdf(fontDict, literal = "ABC"))
        assertEquals("ABC ABC", parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun commonSubsetGlyphNamesRetainSearchableText() {
        val fontDict = "<< /Type /Font /Subtype /Type1 /BaseFont /Subset+PrivateFace " +
            "/Encoding << /Differences [65 /a.sc /one.taboldstyle /ffi] >> >>"
        val parsed = PdfParser.parse(PdfParser.writeVerbatimFontDictPdf(fontDict, literal = "ABC"))
        assertEquals("a1ffi a1ffi", parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun accentedLigatureAndUnicodeGlyphNamesFollowGlyphListRules() {
        val fontDict = "<< /Type /Font /Subtype /Type1 /BaseFont /Subset+PrivateFace " +
            "/Encoding << /Differences [65 /acircumflex /Scedilla /uni00E9 /f_f_i /one.oldstyle /dotlessi " +
            "/odieresis /numbersign /dollar /quotesinglbase /u1F600] >> >>"
        val parsed = PdfParser.parse(singlePageContentPdf(
            "BT /F1 12 Tf 72 720 Td (ABCDEFGHIJK) Tj ET".toByteArray(Charsets.ISO_8859_1), fontDict))
        assertEquals("âŞéffi1ıö#$‚\uD83D\uDE00", parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun colourSpaceOperatorsWithVariableOperandsKeepTextComplete() {
        val content = "BT /F1 12 Tf 0 0 0 sc 1 SC /CS0 cs 0.1 0.2 0.3 0.4 scn /P1 scn 0.5 /P2 SCN " +
            "72 720 Td (Colour text) Tj ET"
        val parsed = PdfParser.parse(singlePageContentPdf(content.toByteArray(Charsets.ISO_8859_1)))
        assertEquals("Colour text", parsed.pages.single().text)
        assertFalse(parsed.needsVision)
        listOf("sc", "1 2 3 4 5 scn").forEach { colour ->
            val broken = PdfParser.parse(singlePageContentPdf(
                "BT /F1 12 Tf $colour 72 720 Td (Colour text) Tj ET".toByteArray(Charsets.ISO_8859_1)))
            assertTrue(broken.needsVision, colour)
        }
    }

    @Test
    fun unknownGlyphNameBlocksOnlyBytesThatShowIt() {
        val fontDict = "<< /Type /Font /Subtype /Type1 /BaseFont /PrivateFace " +
            "/Encoding << /BaseEncoding /WinAnsiEncoding /Differences [65 /A /B /arrowdown.private] >> >>"
        fun parse(shown: String) = PdfParser.parse(singlePageContentPdf(
            "BT /F1 12 Tf 72 720 Td ($shown) Tj ET".toByteArray(Charsets.ISO_8859_1), fontDict))
        val known = parse("ABxyz")
        assertEquals("ABxyz", known.pages.single().text)
        assertFalse(known.needsVision)
        val unknown = parse("AC")
        assertTrue(unknown.pages.single().needsVision)
        assertTrue(unknown.assets.any { it.kind == "PAGE" })
        listOf("uniD800", "uni00e9", "u110000", "uni00E").forEach { name ->
            val odd = PdfParser.parse(singlePageContentPdf("BT /F1 12 Tf 72 720 Td (A) Tj ET".toByteArray(),
                "<< /Type /Font /Subtype /Type1 /BaseFont /PrivateFace /Encoding << /Differences [65 /$name] >> >>"))
            assertTrue(odd.needsVision, name)
        }
    }

    @Test
    fun customEmbeddedFontWithIncompleteDifferencesKeepsPageVision() {
        val fontDict = "<< /Type /Font /Subtype /Type1 /BaseFont /Subset+PrivateFace " +
            "/Encoding << /Differences [65 /A] >> >>"
        val parsed = PdfParser.parse(PdfParser.writeVerbatimFontDictPdf(fontDict, literal = "ABC"))
        assertTrue(parsed.pages.single().needsVision)
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 })
    }

    private fun toUnicodePdf(fontDict: String, cmapBody: String, content: String, flate: Boolean = false): ByteArray {
        val cmap = ("/CIDInit /ProcSet findresource begin 12 dict begin begincmap " +
            "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def " +
            "/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n$cmapBody\nendcmap " +
            "CMapName currentdict /CMap defineresource pop end end").toByteArray(Charsets.ISO_8859_1)
        return finishPdf(
            mapOf(
                1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
                2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to plainPdfObject(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"),
                4 to streamPdfObject(4, "", content.toByteArray(Charsets.ISO_8859_1)),
                5 to plainPdfObject(5, fontDict),
                6 to if (flate) streamPdfObject(6, "/Filter /FlateDecode", deflate(cmap)) else streamPdfObject(6, "", cmap),
            ),
        )
    }

    @Test
    fun toUnicodeCMapOverridesGlyphNamesWithoutFallingBackToThem() {
        val font = "<< /Type /Font /Subtype /Type1 /BaseFont /PrivateFace " +
            "/Encoding << /Differences [65 /A /B] >> /ToUnicode 6 0 R >>"
        val body = "1 begincodespacerange <00> <FF> endcodespacerange\n1 beginbfchar <41> <0042> endbfchar"
        val mapped = PdfParser.parse(toUnicodePdf(font, body, "BT /F1 12 Tf 72 720 Td (A) Tj ET"))
        assertEquals("B", mapped.pages.single().text)
        assertFalse(mapped.needsVision)
        val missing = PdfParser.parse(toUnicodePdf(font, body, "BT /F1 12 Tf 72 720 Td (AB) Tj ET"))
        assertTrue(missing.needsVision)
        assertTrue(missing.assets.any { it.kind == "PAGE" })
    }

    @Test
    fun type0IdentityFontWithToUnicodeUsesTextLayer() {
        val font = "<< /Type /Font /Subtype /Type0 /BaseFont /ABCDEF+Body /Encoding /Identity-H " +
            "/DescendantFonts [7 0 R] /ToUnicode 6 0 R >>"
        val body = "1 begincodespacerange <0000> <FFFF> endcodespacerange\n" +
            "2 beginbfchar <0001> <0048> <0010> <0020> endbfchar\n" +
            "2 beginbfrange <0002> <0003> <0069> <0004> <0005> [<00660069> <D83DDE00>] endbfrange"
        val content = "BT /F1 12 Tf 72 720 Td <0001000200030010> Tj [<0004> -250 <0005>] TJ ET"
        listOf(false, true).forEach { flate ->
            val parsed = PdfParser.parse(toUnicodePdf(font, body, content, flate))
            assertEquals("Hijfi\uD83D\uDE00", parsed.pages.single().text.filterNot { it.isWhitespace() })
            assertTrue(parsed.pages.single().text.startsWith("Hij "))
            assertFalse(parsed.needsVision, "flate=$flate")
        }
    }

    @Test
    fun unverifiableToUnicodeMappingsKeepPageVision() {
        val identity = "<< /Type /Font /Subtype /Type0 /BaseFont /Body /Encoding /Identity-H " +
            "/DescendantFonts [7 0 R] /ToUnicode 6 0 R >>"
        val body = "1 begincodespacerange <0000> <FFFF> endcodespacerange\n" +
            "4 beginbfchar <0001> <0048> <0002> <E000> <0003> <FFFD> <0004> <D800> endbfchar"
        listOf("<0009>", "<0002>", "<0003>", "<0004>", "<000100>").forEach { shown ->
            val parsed = PdfParser.parse(toUnicodePdf(identity, body, "BT /F1 12 Tf 72 720 Td $shown Tj ET"))
            assertTrue(parsed.needsVision, shown)
        }
        val ok = PdfParser.parse(toUnicodePdf(identity, body, "BT /F1 12 Tf 72 720 Td <0001> Tj ET"))
        assertFalse(ok.needsVision)
        listOf(
            identity.replace("/Identity-H", "/UniGB-UCS2-H") to body,
            identity.replace("/ToUnicode 6 0 R", "/ToUnicode /Identity-H") to body,
            identity to "/Adobe-Base usecmap\n$body",
            identity to "1 beginbfrange <0001> <0003> [<0048>] endbfrange",
            identity to "1 beginbfrange <0001> <FFFFFF> <0041> endbfrange",
            identity to "1 beginbfchar <0001> endbfchar",
        ).forEach { (font, cmap) ->
            val parsed = PdfParser.parse(toUnicodePdf(font, cmap, "BT /F1 12 Tf 72 720 Td <0001> Tj ET"))
            assertTrue(parsed.needsVision, "$font / $cmap")
        }
    }

    @Test
    fun malformedDifferencesCodesDoNotCertifyUnknownFontText() {
        listOf(
            "[999 /A 65 /B]" to "A",
            "[65 66 /A]" to "B",
            "[255 /A /B]" to "\u00FF",
            "[65]" to "A",
        ).forEach { (differences, shown) ->
            val fontDict = "<< /Type /Font /Subtype /Type1 /BaseFont /PrivateFace " +
                "/Encoding << /Differences $differences >> >>"
            val pdf = finishPdf(
                mapOf(
                    1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
                    2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                    3 to plainPdfObject(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                        "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"),
                    4 to streamPdfObject(4, "", "BT /F1 12 Tf 72 720 Td ($shown) Tj ET".toByteArray(Charsets.ISO_8859_1)),
                    5 to plainPdfObject(5, fontDict),
                ),
            )
            val parsed = PdfParser.parse(pdf)
            assertTrue(parsed.needsVision, differences)
        }
    }
    @Test
    fun escapedEncodingAndDifferencesKeysDecodeLikeTheirLiteralSpelling() {
        val literal = PdfParser.parse(PdfParser.writeDifferencesWithKeySpellingsPdf(literal = "KEEPTOKEN"))
        val escapedEncodingOnly = PdfParser.parse(
            PdfParser.writeDifferencesWithKeySpellingsPdf(encodingKey = "/Enc#6Fding", literal = "KEEPTOKEN"),
        )
        val escapedDifferencesOnly = PdfParser.parse(
            PdfParser.writeDifferencesWithKeySpellingsPdf(differencesKey = "/Diff#65rences", literal = "KEEPTOKEN"),
        )
        val escapedBoth = PdfParser.parse(
            PdfParser.writeDifferencesWithKeySpellingsPdf(
                encodingKey = "/Enc#6Fding",
                differencesKey = "/Diff#65rences",
                literal = "KEEPTOKEN",
            ),
        )
        val decoded = listOf(literal, escapedEncodingOnly, escapedDifferencesOnly, escapedBoth)
        decoded.forEach { parsed ->
            val text = parsed.pages.single().text
            assertTrue(text.contains("KEEPTOKEN"), text)
            assertTrue(text.contains("XYZ"), text)
            assertFalse(text.contains("ABC"), text)
            assertFalse(parsed.needsVision, text)
            assertEquals(literal.pages.single().text, text)
        }
    }

    @Test
    fun escapedFilterKeyStillAppliesFlateDecode() {
        val literal = PdfParser.parse(PdfParser.writeFlateTextPdf("Alpha torque 12Nm"))
        val escaped = PdfParser.parse(PdfParser.writeFlateTextPdf("Alpha torque 12Nm", filterKey = "/Fil#74er"))
        assertEquals("Alpha torque 12Nm", literal.pages.single().text)
        assertEquals(literal.pages.single().text, escaped.pages.single().text)
        assertFalse(escaped.needsVision, escaped.pages.single().text)
    }
    @Test
    fun escapedBaseFontNameDecodesLikeItsLiteralSpelling() {
        val parsed = PdfParser.parse(PdfParser.writeBuiltInFontPdf("KEEPTOKEN", "Sym#62ol"))
        val text = parsed.pages.single().text
        assertTrue(text.contains("KEEPTOKEN"), text)
        assertTrue(text.contains("\u03B1\u03B2\u03B3"), text)
        assertFalse(text.contains("abg"), text)
        assertFalse(parsed.needsVision, text)
    }

    @Test
    fun escapedResourceFontNameMatchesContentStreamName() {
        val parsed = PdfParser.parse(PdfParser.writeEscapedResourceNamePdf("KEEPTOKEN"))
        val text = parsed.pages.single().text
        assertTrue(text.contains("KEEPTOKEN"), text)
        assertTrue(text.contains("\u03B1\u03B2\u03B3"), text)
        assertFalse(text.contains("abg"), text)
        assertFalse(parsed.needsVision, text)
    }

    @Test
    fun unknownBuiltInFontFailsClosedInsteadOfAssumingStandardEncoding() {
        val parsed = PdfParser.parse(PdfParser.writeBuiltInFontPdf("KEEPTOKEN", "ReviewUnknownFont"))
        assertTrue(parsed.pages.single().text.contains("KEEPTOKEN"), parsed.pages.single().text)
        assertTrue(parsed.pages.single().needsVision, parsed.pages.single().text)
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 }, parsed.assets.toString())
    }

    @Test
    fun base14LatinFontWithoutEncodingStillPublishesCompleteText() {
        val parsed = PdfParser.parse(PdfParser.writeBuiltInFontPdf("KEEPTOKEN", "Helvetica"))
        assertTrue(parsed.pages.single().text.contains("KEEPTOKEN"), parsed.pages.single().text)
        assertFalse(parsed.pages.single().needsVision, parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun zapfDingbatsBuiltInEncodingFailsClosedInsteadOfPublishingLatin() {
        val parsed = PdfParser.parse(PdfParser.writeZapfDingbatsBuiltinPdf("KEEPTOKEN"))
        assertTrue(parsed.pages.single().text.contains("KEEPTOKEN"), parsed.pages.single().text)
        assertTrue(parsed.pages.single().needsVision, parsed.pages.single().text)
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 }, parsed.assets.toString())
    }

    @Test
    fun graphicsStateRestoresFontAfterQ() {
        val parsed = PdfParser.parse(PdfParser.writeFontRestorePdf())
        assertTrue(parsed.pages.single().text.contains("KEEPTOKEN"), parsed.pages.single().text)
        assertTrue(parsed.pages.single().text.contains("XYZ"), parsed.pages.single().text)
        assertTrue(parsed.pages.single().text.contains("ABC"), parsed.pages.single().text)
        assertFalse(parsed.pages.single().text.matches(Regex(".*XYZ\\s+XYZ.*")), parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun mixedIncompleteAndImagePagesKeepPageBlockerOnlyOnIncompletePage() {
        val parsed = PdfParser.parse(PdfParser.writeIncompleteThenImagePagesPdf())
        assertEquals(listOf(1, 2), parsed.pages.map { it.page })
        assertEquals("TITLE", parsed.pages[0].text)
        assertEquals("SECONDPAGEJPEG", parsed.pages[1].text)
        assertTrue(parsed.pages[0].needsVision)
        assertTrue(parsed.pages[1].needsVision)
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 && it.bytes.isEmpty() })
        assertFalse(parsed.assets.any { it.kind == "PAGE" && it.page == 2 })
        assertTrue(parsed.assets.any { it.kind == "IMAGE" && it.page == 2 && it.bytes.isNotEmpty() })
    }

    @Test
    fun nestedLiteralWithImageKeepsInnerText() {
        val parsed = PdfParser.parse(PdfParser.writeNestedLiteralWithImagePdf())
        assertTrue(parsed.pages.single().text.contains("TITLE"), parsed.pages.single().text)
        assertTrue(
            parsed.pages.single().text.contains("BODY: (nested) KEEP THIS SENTENCE."),
            parsed.pages.single().text,
        )
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.any { it.kind == "IMAGE" && it.bytes.isNotEmpty() })
    }

    @Test
    fun incompleteTextWithImageStillCreatesPageBlocker() {
        val parsed = PdfParser.parse(PdfParser.writeUndecodedHexWithImagePdf("TITLE"))
        assertEquals("TITLE", parsed.pages.single().text)
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.any { it.kind == "IMAGE" && it.bytes.isNotEmpty() })
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 && it.bytes.isEmpty() })
    }

    @Test
    fun imagePdfRequiresVisionAndKeepsLabel() {
        val pdf = PdfParser.writePdfWithImageXObject("flowchart page")
        val parsed = PdfParser.parse(pdf)
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.isNotEmpty())
        assertTrue(parsed.text.contains("flowchart"))
    }

    @Test
    fun brokenPdfIsNotReadyText() {
        val error = assertThrows(IllegalStateException::class.java) {
            PdfParser.parse("%PDF-1.4 leftover".toByteArray())
        }
        assertTrue(error.message.orEmpty().contains("PDF") || error.message.orEmpty().contains("extractable"))
    }

    @Test
    fun docxExtractsParagraphAndLinkedImage() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
        val zip = zip(
            "word/document.xml" to """
                <w:document><w:body>
                <w:p><w:r><w:t>DOCX torque table 12Nm</w:t></w:r></w:p>
                <w:p><w:r><w:drawing><a:blip r:embed="rId4"/></w:drawing></w:r></w:p>
                </w:body></w:document>
            """.trimIndent().toByteArray(),
            "word/_rels/document.xml.rels" to """
                <Relationships>
                <Relationship Id="rId4" Type="http://example/image" Target="media/image1.png"/>
                </Relationships>
            """.trimIndent().toByteArray(),
            "word/media/image1.png" to png,
        )
        val parsed = OfficeParser.parse("note.docx", zip)
        assertTrue(parsed.text.contains("12Nm"))
        assertTrue(parsed.needsVision)
        assertEquals(1, parsed.assets.size)
        assertEquals("paragraph-2", parsed.assets.single().section)
    }

    @Test
    fun epubExtractsXhtmlAndImage() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
        val zip = zip(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OPS/content.opf\"/></rootfiles></container>".toByteArray(),
            "OPS/ch1.xhtml" to "<html><body><p>EPUB chapter mentions USearch</p><img src=\"images/fig.png\"/></body></html>".toByteArray(),
            "OPS/images/fig.png" to png,
        )
        val parsed = OfficeParser.parse("book.epub", zip)
        assertTrue(parsed.text.contains("USearch"))
        assertTrue(parsed.needsVision)
        assertEquals(1, parsed.assets.size)
        assertEquals(1, parsed.assets.single().page)
    }

    @Test
    fun textPlusVectorPdfNeedsVision() {
        val pdf = PdfParser.writeTextAndVectorPdf("vector label")
        val parsed = PdfParser.parse(pdf)
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.bytes.isEmpty() })
    }

    @Test
    fun completeTextWithThinHorizontalSeparatorsUsesTextLayer() {
        val content = ("BT /F1 12 Tf 72 720 Td (Searchable paragraph) Tj ET\n" +
            "q 1 0 0 1 86.4 700.084 cm [] 0 d 0 J 0.398 w 0 0 m 454.876 0 l S Q\n" +
            "q 1 0 0 1 86.4 639.312 cm [] 0 d 0 J 0.398 w 0 0 m 454.876 0 l S Q\n")
            .toByteArray(Charsets.ISO_8859_1)
        val parsed = PdfParser.parse(singlePageContentPdf(
            content,
            fontDict = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FontDescriptor 6 0 R >>",
            extraObjects = mapOf(6 to plainPdfObject(6,
                "<< /Type /FontDescriptor /FontName /Helvetica /FontBBox [-500 -250 1200 1000] >>")),
        ))
        assertEquals("Searchable paragraph", parsed.pages.single().text)
        assertFalse(parsed.needsVision)
    }

    @Test
    fun decorativeRulesFramesAndBackgroundsUseTextLayer() {
        listOf(
            "q 1 0 0 1 86.4 76.7 cm [] 0 d 0 J 0.398 w 0 0 m 88.981 0 l S Q",
            "q 1 0 0 1 86.4 724 cm [] 0 d 0 J 0.398 w 0 0 m 454 0 l S Q",
            "0.5 g 0.5 G q 1 0 0 1 108 465.8 cm []0 d 0 J 0.398 w 0 0 m 379.3 0 l S Q 0.98 0.13 0 0.43 k",
            "q .3985 w 0 -10.162 m 316.205 -10.162 l S Q",
            "0.5 w 300 100 m 300 600 l S 72 90 m 540 90 l 540 700 l S",
            "0.4 w 72 600 200 40 re S 72 620 m 272 620 l S",
            "q 0 0 612 792 re W n /Cs1 cs 1 sc 0 792 m 612 792 l 612 0 l 0 0 l h f Q",
            "q 1 0 0 -1 0 792 cm 0 0 612 792 re f Q",
            "0 0 0 0 k 162 664.6 m 450 664.6 l 450 645.6 l 162 645.6 l f",
            "1 g 72 500 300 100 re f 0 g 72 480 300 0.8 re f",
            "q 0.000008871 0 595.32 841.92 re W* n Q /Artifact << /Type /Pagination >> BDC 0 0 1 RG 72 70 m 500 70 l S EMC",
        ).forEach { drawing ->
            val content = "BT /F1 12 Tf 72 720 Td (Readable text) Tj ET\n$drawing\n"
            val parsed = PdfParser.parse(singlePageContentPdf(content.toByteArray(Charsets.ISO_8859_1)))
            assertEquals("Readable text", parsed.pages.single().text, drawing)
            assertFalse(parsed.needsVision, drawing)
            assertTrue(parsed.assets.isEmpty(), drawing)
        }
    }

    private val testJpeg: ByteArray by lazy {
        ByteArrayOutputStream().also { output ->
            val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 16) for (y in 0 until 16) image.setRGB(x, y, (x * 16) shl 8 or (y * 16))
            assertTrue(javax.imageio.ImageIO.write(image, "jpeg", output))
        }.toByteArray()
    }

    private fun illustrationPdf(
        content: String,
        imageDict: String = "/Type /XObject /Subtype /Image /Width 16 /Height 16 /ColorSpace /DeviceRGB " +
            "/BitsPerComponent 8 /Filter /DCTDecode",
        pageExtras: String = "",
        extraObjects: Map<Int, ByteArray> = emptyMap(),
        extraResources: String = "",
    ): ParsedPublication = PdfParser.parse(finishPdf(
        mapOf(
            1 to plainPdfObject(1, "<< /Type /Catalog /Pages 2 0 R >>"),
            2 to plainPdfObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            3 to plainPdfObject(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] $pageExtras" +
                "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> /XObject << /Im1 6 0 R >> $extraResources>> >>"),
            4 to streamPdfObject(4, "", content.toByteArray(Charsets.ISO_8859_1)),
            5 to plainPdfObject(5, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            6 to streamPdfObject(6, imageDict, testJpeg),
        ) + extraObjects,
    ))

    @Test
    fun illustrationAmidDecorationsAndNestedTransformsIsSentWithoutThePage() {
        val text = "BT 0 g /F1 11 Tf 72 200 Td (Lesson text) Tj 0.98 0.13 0 0.43 k ET"
        listOf(
            "0 g 0 G q 366.72 0 0 451.68 114.278 318.234 cm /Im1 Do Q 0.5 g 0.5 G " +
                "q 1 0 0 1 108 258.397 cm []0 d 0 J 0.398 w 0 0 m 379.276 0 l S Q $text",
            "1 0 0 1 227.075 625.89 cm q 1.00005 0 0 1.00005 0 0 cm q 141.12 0 0 144 0 0 cm /Im1 Do Q Q " +
                "1 0 0 1 -227.075 -625.89 cm $text",
            "q 0 0 595 842 re W n /Artifact BMC /P << /MCID 0 >> BDC q 200 0 0 100 50 600 cm /Im1 Do Q EMC EMC Q $text",
        ).forEach { content ->
            val parsed = illustrationPdf(content)
            val page = parsed.pages.single()
            assertTrue(page.visualAssetsOnly, content)
            assertEquals("Lesson text", page.text)
            assertTrue(parsed.assets.none { it.kind == "PAGE" }, content)
            assertEquals(1, parsed.assets.count { it.kind == "IMAGE" && it.page == 1 }, content)
        }
        assertTrue(illustrationPdf("q 100 0 0 100 50 600 cm /Im1 Do Q $text",
            imageDict = "/Type /XObject /Subtype /Image /Width 16 /Height 16 /ColorSpace [/ICCBased 7 0 R] " +
                "/BitsPerComponent 8 /Filter /DCTDecode /Interpolate true",
            extraObjects = mapOf(7 to streamPdfObject(7, "/N 3", ByteArray(8))),
        ).pages.single().visualAssetsOnly)
    }

    @Test
    fun illustrationWhoseAppearanceDiffersFromTheRawImageKeepsPageVision() {
        val text = "BT /F1 11 Tf 72 200 Td (Lesson text) Tj ET"
        val placed = "q 100 0 0 100 50 600 cm /Im1 Do Q"
        val base = "/Type /XObject /Subtype /Image /Width 16 /Height 16 /BitsPerComponent 8 /Filter /DCTDecode"
        listOf(
            illustrationPdf("q 100 0 0 -100 50 700 cm /Im1 Do Q $text"),
            illustrationPdf("q 0 100 -100 0 150 600 cm /Im1 Do Q $text"),
            illustrationPdf("q 100 0 0 100 540 600 cm /Im1 Do Q $text"),
            illustrationPdf("$placed 0.5 g 72 100 200 50 re f $text"),
            illustrationPdf("$placed 72 100 m 100 150 150 150 180 100 c S $text"),
            illustrationPdf("BT 7 Tr /F1 11 Tf 72 200 Td (Lesson text) Tj ET $placed"),
            illustrationPdf("$placed $text", imageDict = "$base /ColorSpace /DeviceCMYK"),
            illustrationPdf("$placed $text", imageDict = "$base /ColorSpace /DeviceRGB /SMask 7 0 R",
                extraObjects = mapOf(7 to plainPdfObject(7, "<< /Type /XObject /Subtype /Image >>"))),
            illustrationPdf("$placed $text", imageDict = "$base /ColorSpace [/ICCBased 7 0 R]",
                extraObjects = mapOf(7 to streamPdfObject(7, "/N 4", ByteArray(8)))),
            illustrationPdf("$placed $text", pageExtras = "/Rotate 90 "),
            illustrationPdf("$placed $text", pageExtras = "/Annots [7 0 R] ",
                extraObjects = mapOf(7 to plainPdfObject(7, "<< /Type /Annot /Subtype /FreeText /Rect [0 0 50 20] >>"))),
        ).forEachIndexed { index, parsed ->
            assertFalse(parsed.pages.single().visualAssetsOnly, "case $index")
            assertTrue(parsed.pages.single().needsVision, "case $index")
        }
        listOf("<< /Type /ExtGState /ca 0.2 >>", "<< /Type /ExtGState /SMask 8 0 R >>",
            "<< /Type /ExtGState /BM /Multiply >>", "<< /Type /ExtGState /TR /Identity >>").forEach { state ->
            val parsed = illustrationPdf("/GS1 gs $placed $text", extraResources = "/ExtGState << /GS1 7 0 R >> ",
                extraObjects = mapOf(7 to plainPdfObject(7, state), 8 to plainPdfObject(8, "<< >>")))
            assertFalse(parsed.pages.single().visualAssetsOnly, state)
        }
        assertFalse(illustrationPdf("/GS9 gs $placed $text").pages.single().visualAssetsOnly)
        assertTrue(illustrationPdf("BT /GS1 gs ET $placed $text", extraResources = "/ExtGState << /GS1 7 0 R >> ",
            extraObjects = mapOf(7 to plainPdfObject(7, "<< /Type /ExtGState /LW 1 /CA 1 /ca 1.0 /SMask /None /BM /Normal >>")),
        ).pages.single().visualAssetsOnly)
        val invisibleLink = illustrationPdf("$placed $text", pageExtras = "/Annots [7 0 R] ",
            extraObjects = mapOf(7 to plainPdfObject(7, "<< /Type /Annot /Subtype /Link /Border [0 0 0] /Rect [0 0 50 20] >>")))
        assertTrue(invisibleLink.pages.single().visualAssetsOnly)
    }

    @Test
    fun meaningfulVectorGraphicsKeepPageVision() {
        listOf(
            "q 1 0 0 1 86.4 700 cm [] 0 d 0 J 4 w 0 0 m 454 0 l S Q",
            "q 5 0 0 5 0 0 cm 0.5 w 10 10 m 100 10 l S Q",
            "q 1 0 0 1 86.4 700 cm [] 0 d 0 J 0.398 w 0 0 m 454 20 l S Q",
            "72 400 m 100 450 150 450 180 400 c S",
            "0.5 g 72 400 100 100 re f",
            "72 400 m 172 400 l 122 480 l h f",
            "1 g BT 0 g ET 72 400 100 100 re f",
            "q 72 400 100 100 re W n /Sh1 sh Q",
            "0.4 w 72 400 50 50 re B",
            (1..97).joinToString(" ") { "72 ${100 + it * 5} m 300 ${100 + it * 5} l S" },
        ).forEach { drawing ->
            val content = "BT /F1 12 Tf 72 720 Td (Text) Tj ET\n$drawing\n"
            val parsed = PdfParser.parse(singlePageContentPdf(content.toByteArray(Charsets.ISO_8859_1)))
            assertTrue(parsed.pages.single().needsVision, drawing.take(80))
            assertTrue(parsed.assets.any { it.kind == "PAGE" }, drawing.take(80))
        }
    }

    @Test
    fun drawingOnlyPdfNeedsVisionWithoutRasterAssets() {
        val parsed = PdfParser.parse(PdfParser.writeDrawingOnlyPdf())
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.none { it.kind == "IMAGE" && it.bytes.isNotEmpty() })
    }

    @Test
    fun rendererTurnsVectorPageIntoLocatableImageAsset() {
        val rendered = byteArrayOf(1, 2, 3, 4)
        val parsed = PdfParser.parse(PdfParser.writeDrawingOnlyPdf(), PdfPageRasterizer { _, pages ->
            pages.map { page -> RenderedPdfPage(page, rendered, "image/png", 612, 792) }
        })
        val asset = parsed.assets.single { it.localId == "page-rendered-1" }
        assertEquals("IMAGE", asset.kind)
        assertEquals(1, asset.page)
        assertEquals("pdf-page-1", asset.section)
        assertEquals(rendered.toList(), asset.bytes.toList())
        assertTrue(asset.bytes.isNotEmpty())
        assertTrue(parsed.assets.none { it.kind == "PAGE" })
    }

    @Test
    fun rendererFailureKeepsExplicitPageBlocker() {
        val parsed = PdfParser.parse(PdfParser.writeDrawingOnlyPdf(), PdfPageRasterizer { _, _ -> emptyList() })
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 && it.bytes.isEmpty() })
    }

    @Test
    fun twoPageTextKeepsPageBoundaries() {
        val parsed = PdfParser.parse(PdfParser.writeTwoPageTextPdf("FIRSTPAGEONLYTOKEN", "SECONDPAGEONLYTOKEN"))
        assertEquals(2, parsed.pages.size)
        assertTrue(parsed.pages[0].text.contains("FIRSTPAGEONLYTOKEN"))
        assertTrue(parsed.pages[1].text.contains("SECONDPAGEONLYTOKEN"))
        assertFalse(parsed.pages[0].text.contains("SECONDPAGEONLYTOKEN"))
    }

    @Test
    fun imagePdfAssignsPageToAsset() {
        val parsed = PdfParser.parse(PdfParser.writePdfWithImageXObject("flowchart page"))
        assertEquals(1, parsed.assets.single().page)
    }

    @Test
    fun rawFlateImageXObjectStaysAnExplicitPageBlocker() {
        val compressedRgb = deflate(byteArrayOf(0x7F, 0x20, 0x10))
        val pdf = replaceImageObject(
            PdfParser.writePdfWithImageXObject("raw RGB page"),
            filterSyntax = "/Filter /FlateDecode",
            payload = compressedRgb,
        )
        val parsed = PdfParser.parse(pdf)
        assertTrue(parsed.needsVision)
        assertTrue(parsed.assets.none { it.kind == "IMAGE" && it.bytes.isNotEmpty() })
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 && it.bytes.isEmpty() })
    }

    @Test
    fun dctArrayFilterKeepsOnlySignatureValidatedJpeg() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val pdf = replaceImageObject(
            PdfParser.writePdfWithImageXObject("array DCT page"),
            filterSyntax = "/Filter [/DCTDecode]",
            payload = jpeg,
        )
        val parsed = PdfParser.parse(pdf)
        val asset = parsed.assets.single { it.kind == "IMAGE" }
        assertEquals("image/jpeg", asset.mediaType)
        assertEquals(jpeg.toList(), asset.bytes.toList())
        assertTrue(parsed.assets.none { it.mediaType == "application/octet-stream" })
    }

    @Test
    fun validJpegDoesNotHideUnsupportedImageOnTheSamePage() {
        val parsed = PdfParser.parse(addRawFlateImageToPage(PdfParser.writePdfWithImageXObject("mixed image page")))

        assertEquals(1, parsed.assets.count { it.kind == "IMAGE" && it.mediaType == "image/jpeg" })
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 && it.bytes.isEmpty() })
    }

    @Test
    fun independentlyDecodedContentStreamsKeepLaterVisualBlocker() {
        val parsed = PdfParser.parse(contentArrayPdfWithFlateTextAndVectorJpeg())

        assertTrue(parsed.text.contains("content array label"))
        assertEquals(1, parsed.assets.count { it.kind == "IMAGE" && it.mediaType == "image/jpeg" })
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 && it.bytes.isEmpty() })
    }

    @Test
    fun danglingContentsCannotBeHiddenByValidJpeg() {
        val pdf = String(PdfParser.writePdfWithImageXObject("dangling content"), Charsets.ISO_8859_1)
            .replace("/Contents 4 0 R", "/Contents 999 0 R")
            .toByteArray(Charsets.ISO_8859_1)

        val parsed = PdfParser.parse(pdf)

        assertEquals(1, parsed.assets.count { it.kind == "IMAGE" && it.mediaType == "image/jpeg" })
        assertTrue(parsed.assets.any { it.kind == "PAGE" && it.page == 1 && it.bytes.isEmpty() })
    }

    @Test
    fun textPlusInlineImageNeedsVision() {
        val parsed = PdfParser.parse(PdfParser.writeTextAndInlineImagePdf("inline caption token"))
        assertTrue(parsed.needsVision)
        assertTrue(parsed.text.contains("inline caption token"))
        assertTrue(
            parsed.assets.any { it.kind == "IMAGE" && it.bytes.isNotEmpty() } ||
                parsed.assets.any { it.kind == "PAGE" && it.bytes.isEmpty() },
        )
    }

    @Test
    fun docxExternalImageIsRecordedAndNotFetched() {
        val zip = zip(
            "word/document.xml" to """
                <w:document><w:body>
                <w:p><w:r><w:t>caption text</w:t></w:r></w:p>
                <w:p><w:r><w:drawing><a:blip r:link="rId9"/></w:drawing></w:r></w:p>
                </w:body></w:document>
            """.trimIndent().toByteArray(),
            "word/_rels/document.xml.rels" to """
                <Relationships>
                <Relationship Id="rId9" Type="http://example/image" Target="https://example.invalid/image.png" TargetMode="External"/>
                </Relationships>
            """.trimIndent().toByteArray(),
        )
        val parsed = OfficeParser.parse("note.docx", zip)
        assertTrue(parsed.needsVision)
        assertEquals("EXTERNAL", parsed.assets.single().kind)
        assertEquals(0, parsed.assets.single().bytes.size)
    }

    @Test
    fun epubExternalImageIsRecorded() {
        val zip = zip(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OPS/content.opf\"/></rootfiles></container>".toByteArray(),
            "OPS/ch1.xhtml" to "<html><body><p>chapter</p><img src=\"https://example.invalid/fig.png\"/></body></html>".toByteArray(),
        )
        val parsed = OfficeParser.parse("book.epub", zip)
        assertTrue(parsed.needsVision)
        assertEquals("EXTERNAL", parsed.assets.single().kind)
    }

    @Test
    fun epubSameBasenameUsesChapterDirectory() {
        val pngA = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "MARK-A".toByteArray()
        val pngB = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "MARK-B".toByteArray()
        val zip = zip(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OPS/content.opf\"/></rootfiles></container>".toByteArray(),
            "OPS/ch1/chapter.xhtml" to "<html><body><p>one</p><img src=\"images/fig.png\"/></body></html>".toByteArray(),
            "OPS/ch2/chapter.xhtml" to "<html><body><p>two</p><img src=\"images/fig.png\"/></body></html>".toByteArray(),
            "OPS/ch1/images/fig.png" to pngA,
            "OPS/ch2/images/fig.png" to pngB,
        )
        val parsed = OfficeParser.parse("book.epub", zip)
        val page1 = parsed.assets.single { it.page == 1 && it.kind == "IMAGE" }
        val page2 = parsed.assets.single { it.page == 2 && it.kind == "IMAGE" }
        assertTrue(String(page1.bytes, Charsets.ISO_8859_1).contains("MARK-A"))
        assertTrue(String(page2.bytes, Charsets.ISO_8859_1).contains("MARK-B"))
        assertEquals("OPS/ch1/chapter.xhtml", page1.section)
        assertEquals("OPS/ch2/chapter.xhtml", page2.section)
    }

    @Test
    fun zipSlipStillRejectedBeforeParse() {
        val error = assertThrows(IllegalStateException::class.java) {
            OfficeParser.parse("evil.docx", zip("../outside.txt" to "nope".toByteArray()))
        }
        assertTrue(error.message.orEmpty().contains("path") || error.message.orEmpty().contains("not allowed"))
    }

    @Test
    fun strictModeRejectsVisualHitsOnTextChatUntilDegraded() {
        val reject = StrictVisualPolicy.allow(true, chatSupportsImages = false, textDegradationEnabled = false)
        assertTrue(reject is StrictVisualDecision.Reject)
        // The rejection is a typed POLICY decision, not an "invalid parameters"
        // style error: call sites narrow on the code instead of parsing reason.
        assertEquals(
            StrictVisualRejectCode.VISUAL_EVIDENCE_REQUIRES_IMAGE_MODEL,
            (reject as StrictVisualDecision.Reject).code,
        )
        assertTrue(reject.reason.isNotBlank())
        val degraded = StrictVisualPolicy.allow(true, chatSupportsImages = false, textDegradationEnabled = true)
        assertEquals(
            "Original images were not sent. Visual evidence may be incomplete.",
            (degraded as StrictVisualDecision.Allow).warning,
        )
    }

    @Test
    fun strictModeAllowsWithoutVisualEvidenceOrOnImageCapableChat() {
        val noEvidence = StrictVisualPolicy.allow(
            hasVisualEvidence = false,
            chatSupportsImages = false,
            textDegradationEnabled = false,
        )
        assertEquals(StrictVisualDecision.Allow(warning = null), noEvidence)
        val imageCapable = StrictVisualPolicy.allow(
            hasVisualEvidence = true,
            chatSupportsImages = true,
            textDegradationEnabled = false,
        )
        assertEquals(StrictVisualDecision.Allow(warning = null), imageCapable)
    }

    @Test
    fun visualAttachmentRejectsPartialOrOversizedSets() {
        val tiny = "image/png" to ByteArray(8)
        val huge = "image/png" to ByteArray(VisualAttachmentPolicy.MAX_BYTES + 1)
        val mixed = VisualAttachmentPolicy.plan(listOf("ok", "big")) { id ->
            if (id == "ok") tiny else huge
        }
        assertTrue(mixed is VisualAttachmentPlan.Incomplete)
        val missing = VisualAttachmentPolicy.plan(listOf("gone")) { null }
        assertTrue(missing is VisualAttachmentPlan.Incomplete)
        val five = VisualAttachmentPolicy.plan((1..5).map { "a$it" }) { tiny }
        assertTrue(five is VisualAttachmentPlan.Incomplete)
        val ok = VisualAttachmentPolicy.plan(listOf("a", "b")) { tiny }
        assertTrue(ok is VisualAttachmentPlan.Complete)
        assertEquals(2, (ok as VisualAttachmentPlan.Complete).images.size)
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun replaceImageObject(pdf: ByteArray, filterSyntax: String, payload: ByteArray): ByteArray {
        val marker = "/Filter /DCTDecode /Length 4 >>\nstream\n"
        val markerStart = String(pdf, Charsets.ISO_8859_1).indexOf(marker)
        require(markerStart >= 0) { "Image object marker not found" }
        val oldPayloadStart = markerStart + marker.length
        val oldPayloadEnd = oldPayloadStart + 4
        return ByteArrayOutputStream().apply {
            write(pdf, 0, markerStart)
            write("$filterSyntax /Length ${payload.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
            write(payload)
            write(pdf, oldPayloadEnd, pdf.size - oldPayloadEnd)
        }.toByteArray()
    }

    private fun addRawFlateImageToPage(pdf: ByteArray): ByteArray {
        val compressed = deflate(byteArrayOf(0x7F, 0x20, 0x10))
        val latin = String(pdf, Charsets.ISO_8859_1)
            .replace(
                "/XObject << /Im1 6 0 R >>",
                "/XObject << /Im1 6 0 R /Im2 7 0 R >>",
            )
            .replace(
                "/Im1 Do Q\n",
                "/Im1 Do Q\nq 100 0 0 100 200 400 cm /Im2 Do Q\n",
            )
        val xref = latin.indexOf("xref\n")
        require(xref >= 0) { "xref marker not found" }
        val imageObject = buildString {
            append("7 0 obj\n")
            append("<< /Type /XObject /Subtype /Image /Width 1 /Height 1 ")
            append("/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode ")
            append("/Length ${compressed.size} >>\nstream\n")
        }.toByteArray(Charsets.ISO_8859_1)
        return ByteArrayOutputStream().apply {
            write(latin.substring(0, xref).toByteArray(Charsets.ISO_8859_1))
            write(imageObject)
            write(compressed)
            write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            write(latin.substring(xref).toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
    }

    private fun contentArrayPdfWithFlateTextAndVectorJpeg(): ByteArray {
        val text = deflate("BT /F1 12 Tf 72 700 Td (content array label) Tj ET\n".toByteArray())
        val visual = "0 0 100 100 re f\nq 100 0 0 100 72 400 cm /Im1 Do Q\n".toByteArray()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        fun plain(number: Int, body: String): ByteArray =
            "$number 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1)
        fun stream(number: Int, dict: String, payload: ByteArray): ByteArray =
            "$number 0 obj\n<< $dict /Length ${payload.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) +
                payload + "\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1)
        val objects = listOf(
            plain(1, "<< /Type /Catalog /Pages 2 0 R >>"),
            plain(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            plain(
                3,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents [4 0 R 5 0 R] " +
                    "/Resources << /Font << /F1 6 0 R >> /XObject << /Im1 7 0 R >> >> >>",
            ),
            stream(4, "/Filter /FlateDecode", text),
            stream(5, "", visual),
            plain(6, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            stream(
                7,
                "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB " +
                    "/BitsPerComponent 8 /Filter /DCTDecode",
                jpeg,
            ),
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = objects.map { body -> out.size().also { out.write(body) } }
        val xref = out.size()
        out.write("xref\n0 8\n0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        offsets.forEach { offset ->
            out.write("%010d 00000 n \n".format(offset).toByteArray(Charsets.ISO_8859_1))
        }
        out.write("trailer\n<< /Size 8 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }
}
