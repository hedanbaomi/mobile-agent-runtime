// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EpubVisualCompletenessTest {
    @Test fun singleAndDoubleQuotedReferencesKeepChapterContext() {
        for (quote in listOf("'", "\"")) {
            val parsed = parse("<html><body><p>context</p><img src=${quote}../media/a.png${quote}/></body></html>",
                "media/a.png" to byteArrayOf(1, 2, 3))
            val asset = parsed.assets.single()
            assertEquals("IMAGE", asset.kind)
            assertEquals(1, asset.page)
            assertEquals("OPS/ch.xhtml", asset.section)
            assertEquals("context", asset.surroundingText)
            assertArrayEquals(byteArrayOf(1, 2, 3), asset.bytes)
            assertTrue(parsed.needsVision)
        }
    }

    @Test fun externalSingleQuotedImageCannotBecomeCompleteText() {
        val parsed = parse("<html><body>text<img src='https://example.invalid/image.png'/></body></html>")
        assertTrue(parsed.needsVision)
        assertEquals("EXTERNAL", parsed.assets.single().kind)
        assertTrue(parsed.assets.single().bytes.isEmpty())
    }

    @Test fun inlineAndReferencedVectorAndObjectVisualsStayBlocked() {
        for (visual in listOf("<svg xmlns='http://www.w3.org/2000/svg'><rect/></svg>",
            "<v:svg xmlns:v='http://www.w3.org/2000/svg'><v:rect/></v:svg>",
            "<object data='diagram.svg'/>", "<img src='../media/diagram.svg'/>")) {
            val parsed = parse("<html><body>text$visual</body></html>",
                "media/diagram.svg" to "<svg/>".toByteArray())
            assertTrue(parsed.needsVision, visual)
            assertTrue(parsed.assets.any { it.kind == "UNSUPPORTED" }, visual)
            assertTrue(parsed.assets.none { it.kind == "IMAGE" }, visual)
        }
    }

    @Test fun commentsAndScriptsDoNotInventImagesOrText() {
        val parsed = parse("<html><body><!-- <img src='https://example.invalid/no'/> -->" +
            "<script>hidden</script><style>hidden</style><p>A &amp; B&nbsp;C</p></body></html>")
        assertEquals("A & B C", parsed.text)
        assertFalse(parsed.needsVision)
        assertTrue(parsed.assets.isEmpty())
    }

    @Test fun fixedHtmlDoctypeKeepsTextAndImageCompatibilityWithoutDtdProcessing() {
        val text = parse("<!DOCTYPE html><html><body>text</body></html>")
        assertEquals("text", text.text)
        assertFalse(text.needsVision)
        val declared = parse("\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html><html><body>text</body></html>")
        assertEquals("text", declared.text)
        val image = parse("<!DOCTYPE html><html><body>text<img src='https://example.invalid/a'/></body></html>")
        assertTrue(image.needsVision)
        assertEquals("EXTERNAL", image.assets.single().kind)
    }

    @Test fun unsafeMalformedAndExcessivelyDeepXmlFailWithoutEchoingSource() {
        for (html in listOf("<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///private-sentinel'>]><html>&x;</html>",
            "<!DOCTYPE html SYSTEM 'https://example.invalid/private-sentinel'><html>text</html>",
            "<!DOCTYPE html PUBLIC 'private-sentinel' 'https://example.invalid/a'><html>text</html>",
            "<!DOCTYPE html [<!ENTITY x 'private-sentinel'>]><html>&x;</html>",
            "<html><body>text<!DOCTYPE html></body></html>",
            "<!DOCTYPE html><!DOCTYPE html><html>text</html>",
            "<html><body><![CDATA[alpha<!DOCTYPE html>private-sentinel]]></body></html>",
            "<html><body>private-sentinel", "<html>" + "<div>".repeat(260) +
                "private-sentinel" + "</div>".repeat(260) + "</html>")) {
            val error = assertThrows(IllegalStateException::class.java) { parse(html) }
            assertFalse(error.message.orEmpty().contains("private-sentinel"))
            assertNull(error.cause)
        }
    }

    private fun parse(html: String, vararg assets: Pair<String, ByteArray>): ParsedPublication {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in listOf("mimetype" to "application/epub+zip".toByteArray(),
                "OPS/ch.xhtml" to html.toByteArray()) + assets) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return OfficeParser.parse("book.epub", out.toByteArray())
    }
}
