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
    fun textPdfExtractsPageTextWithoutVision() {
        val pdf = PdfParser.writeSimpleTextPdf("Alpha widget torque spec is 12Nm.")
        val parsed = PdfParser.parse(pdf)
        assertTrue(parsed.text.contains("12Nm"))
        assertEquals(1, parsed.pages.size)
        assertFalse(parsed.needsVision)
        assertEquals(PdfParser.FINGERPRINT, parsed.parserFingerprint)
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
        val degraded = StrictVisualPolicy.allow(true, chatSupportsImages = false, textDegradationEnabled = true)
        assertEquals(
            "Original images were not sent. Visual evidence may be incomplete.",
            (degraded as StrictVisualDecision.Allow).warning,
        )
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
