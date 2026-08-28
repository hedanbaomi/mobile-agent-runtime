// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
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
        assertEquals(1, parsed.assets.single { it.kind == "IMAGE" }.page)
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
}
