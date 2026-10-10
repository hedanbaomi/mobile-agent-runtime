// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfParserDeferredPayloadTest {
    @Test fun largeBinaryImagesStayDeferredAndEmbeddedObjectMarkersAreNotPages() {
        val input = imagesPdf(6, 512 * 1024)
        val parsed = PdfParser.parse(input, deferImagePayloads = true)
        assertEquals(1, parsed.pages.size)
        assertTrue(parsed.pages.single().text.contains("native source"))
        val images = parsed.assets.filter { it.kind == "IMAGE" }
        assertEquals(6, images.size)
        assertTrue(images.all { it.bytes.isEmpty() && it.byteSource != null && it.byteLength == 512 * 1024 })
        val first = images.first().readBytes()
        assertEquals(512 * 1024, first.size)
        assertEquals(0xff.toByte(), first[0])
        assertTrue(images.all { it.bytes.isEmpty() }) // Loading a unit does not retain every image copy.
    }

    @Test fun deferredAndDefaultParsingPreserveTextPageAndPayloadIdentity() {
        val input = imagesPdf(2, 1024)
        val eager = PdfParser.parse(input)
        val lazy = PdfParser.parse(input, deferImagePayloads = true)
        assertEquals(eager.text, lazy.text)
        assertEquals(eager.pages, lazy.pages)
        assertEquals(eager.assets.map { it.localId to it.page }, lazy.assets.map { it.localId to it.page })
        eager.assets.zip(lazy.assets).forEach { (a, b) -> assertArrayEquals(a.bytes, b.readBytes()) }
    }

    private fun imagesPdf(count: Int, size: Int): ByteArray {
        val output = ByteArrayOutputStream()
        fun text(value: String) { output.write(value.toByteArray(Charsets.ISO_8859_1)) }
        text("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
        text("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n")
        val refs = (0 until count).joinToString(" ") { "/Im$it ${6 + it} 0 R" }
        text("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> /XObject << $refs >> >> /Contents 5 0 R >> endobj\n")
        text("4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n")
        val content = "BT /F1 12 Tf (native source) Tj ET\n" + (0 until count).joinToString("\n") { "q 10 0 0 10 20 20 cm /Im$it Do Q" }
        text("5 0 obj << /Length ${content.length} >> stream\n$content\nendstream\nendobj\n")
        repeat(count) { index ->
            val payload = ByteArray(size) { (index + 65).toByte() }
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()).copyInto(payload)
            "\n999 0 obj << /Type /Page >> endobj\nendstream\nendobj\n".toByteArray().copyInto(payload, 64)
            text("${6 + index} 0 obj << /Type /XObject /Subtype /Image /Width 10 /Height 10 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length $size >> stream\n")
            output.write(payload)
            text("\nendstream\nendobj\n")
        }
        text("trailer << /Root 1 0 R >>\n%%EOF")
        return output.toByteArray()
    }
}
