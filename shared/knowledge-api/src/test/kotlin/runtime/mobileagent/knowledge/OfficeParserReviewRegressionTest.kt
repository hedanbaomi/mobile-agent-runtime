// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Review F01 (DOCX text fidelity) and F02 (EPUB reading order) regressions.
 *
 * Every case goes through the real [OfficeParser.parse] entry point with a
 * synthesized ZIP package, so the exercised path is the production one. The
 * corpus is authored here and contains no user document.
 */
class OfficeParserReviewRegressionTest {
    // ---------- F01: DOCX text fidelity ----------

    @Test
    fun tabAndBreakNodesSeparateTextWithoutInventingTags() {
        val parsed = docx(
            """<w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t></w:r></w:p>
               <w:p><w:r><w:t>A</w:t><w:br/><w:t>B</w:t></w:r></w:p>
               <w:p><w:r><w:t>A</w:t><w:cr/><w:t>B</w:t></w:r></w:p>""",
        )
        assertEquals(3, parsed.pages.size)
        assertEquals(listOf("A B", "A\nB", "A\nB"), parsed.pages.map { it.text })
        assertEquals(parsed.pages.map { it.text }.joinToString("\n"), parsed.text)
        assertEquals(OfficeParser.DOCX_FINGERPRINT, parsed.parserFingerprint)
    }

    @Test
    fun numericCharacterReferencesDecodeOnce() {
        val parsed = docx("""<w:p><w:r><w:t>&#x4E2D;&#25991; torque</w:t></w:r></w:p>""")
        assertEquals("中文 torque", parsed.pages.single().text)
        assertFalse(parsed.text.contains("&#"), parsed.text)
    }

    @Test
    fun escapedAmpersandIsDecodedExactlyOnce() {
        val parsed = docx("""<w:p><w:r><w:t>&amp;quot;</w:t></w:r></w:p>""")
        assertEquals("&quot;", parsed.pages.single().text)
    }

    @Test
    fun unknownPrefixInTheWordNamespaceStillProvidesParagraphText() {
        val parsed = docx(
            """<x:document xmlns:x="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
               <x:body><x:p><x:r><x:t>same namespace prefix</x:t></x:r></x:p></x:body></x:document>""",
        )
        assertEquals("same namespace prefix", parsed.pages.single().text)
    }

    @Test
    fun elementsOutsideTheWordNamespaceNeverBecomeParagraphText() {
        val parsed = docx(
            """<w:p><w:r><w:t>kept</w:t></w:r>
               <m:oMath xmlns:m="urn:example:math"><m:t>fake</m:t></m:oMath></w:p>
               <w:p><w:pPr><w:t>property</w:t></w:pPr><w:r><w:t>real</w:t></w:r></w:p>""",
        )
        assertEquals(listOf("kept", "real"), parsed.pages.map { it.text })
    }

    @Test
    fun paragraphPropertiesNeverLeakIntoText() {
        val parsed = docx(
            """<w:p><w:pPr><w:tabs><w:tab w:val="left"/></w:tabs><w:jc w:val="center"/></w:pPr>
               <w:r><w:t>A</w:t><w:br/><w:t>B</w:t></w:r></w:p>""",
        )
        assertEquals("A\nB", parsed.pages.single().text)
    }

    @Test
    fun punctuationOnlyParagraphsKeepOrderAndSourcePosition() {
        val parsed = docx(
            """<w:p><w:r><w:t>Before.</w:t></w:r></w:p>
               <w:p><w:r><w:t>……</w:t></w:r></w:p>
               <w:p><w:r><w:t>After.</w:t></w:r></w:p>""",
        )
        assertEquals(listOf("Before.", "……", "After."), parsed.pages.map { it.text })
        assertEquals(listOf(1, 2, 3), parsed.pages.map { it.page })
        assertFalse(parsed.needsVision)
    }

    @Test
    fun sameNamespaceRelationshipAttributesBindImagesToTheirParagraph() {
        val png = PNG + "MARK".toByteArray()
        val parsed = OfficeParser.parse(
            "note.docx",
            zip(
                "word/document.xml" to (
                    """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
                        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
                        """xmlns:rel="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
                        """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
                        """<w:body>""" +
                        """<w:p><w:r><w:t>caption one</w:t></w:r></w:p>""" +
                        """<w:p><w:r><w:drawing><a:blip rel:embed="rId7"/></w:drawing></w:r></w:p>""" +
                        """</w:body></w:document>"""
                    ).toByteArray(),
                "word/_rels/document.xml.rels" to (
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                        """<Relationship Id="rId7" Type="http://example/image" Target="media/alt.png"/>""" +
                        """</Relationships>"""
                    ).toByteArray(),
                "word/media/alt.png" to png,
            ),
        )
        val asset = parsed.assets.single()
        assertEquals("IMAGE", asset.kind, parsed.assets.toString())
        assertEquals(2, asset.page, parsed.assets.toString())
        assertEquals("paragraph-2", asset.section)
        assertEquals("caption one", asset.surroundingText)
        assertArrayEquals(png, asset.bytes)
        assertTrue(parsed.needsVision)
    }

    @Test
    fun documentRelationshipTargetsResolveAgainstTheDocumentDirectory() {
        val png = PNG + "UP".toByteArray()
        val parsed = OfficeParser.parse(
            "note.docx",
            zip(
                "word/document.xml" to (
                    """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
                        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
                        """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
                        """<w:body><w:p><w:r><w:t>with art</w:t></w:r>""" +
                        """<w:r><w:drawing><a:blip r:embed="rId3"/></w:drawing></w:r></w:p></w:body></w:document>"""
                    ).toByteArray(),
                "word/_rels/document.xml.rels" to (
                    """<Relationships><Relationship Id="rId3" Target="../media/up.png"/></Relationships>"""
                    ).toByteArray(),
                "media/up.png" to png,
            ),
        )
        val asset = parsed.assets.single()
        assertEquals("IMAGE", asset.kind)
        assertEquals("paragraph-1", asset.section)
        assertArrayEquals(png, asset.bytes)
    }

    @Test
    fun relationshipIdsAreOnlyMatchedOnRealRelationshipElements() {
        val parsed = OfficeParser.parse(
            "note.docx",
            zip(
                "word/document.xml" to (
                    """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
                        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
                        """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
                        """<w:body><w:p><w:r><w:t>text</w:t></w:r>""" +
                        """<w:r><w:drawing><a:blip r:embed="rId5"/></w:drawing></w:r></w:p></w:body></w:document>"""
                    ).toByteArray(),
                // A comment and a decoy element must not satisfy the lookup.
                "word/_rels/document.xml.rels" to (
                    """<Relationships>""" +
                        """<!-- <Relationship Id="rId5" Target="media/decoy.png"/> -->""" +
                        """<Decoy Id="rId5" Target="media/decoy.png"/>""" +
                        """<Relationship Id="rId5" Target="media/real.png"/>""" +
                        """</Relationships>"""
                    ).toByteArray(),
                "word/media/decoy.png" to (PNG + "DECOY".toByteArray()),
                "word/media/real.png" to (PNG + "REAL".toByteArray()),
            ),
        )
        val asset = parsed.assets.single { it.kind == "IMAGE" && it.localId == "rId5" }
        assertTrue(String(asset.bytes, Charsets.ISO_8859_1).contains("REAL"))
        assertFalse(String(asset.bytes, Charsets.ISO_8859_1).contains("DECOY"))
    }

    @Test
    fun docxRejectsDoctypeAndExternalEntitiesWithoutEchoingContent() {
        listOf(
            """<!DOCTYPE w:document [<!ENTITY x SYSTEM "file:///private-sentinel">]>""" +
                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
                """<w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>""",
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
                """<w:body><w:p><w:r><w:t>private-sentinel""",
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
                """<w:body>""" + "<w:p>".repeat(300) + "<w:r><w:t>private-sentinel</w:t></w:r>" +
                "</w:p>".repeat(300) + "</w:body></w:document>",
        ).forEach { document ->
            val error = assertThrows(IllegalStateException::class.java) { docx(document) }
            assertFalse(error.message.orEmpty().contains("private-sentinel"))
        }
    }

    // A duplicate-name package cannot be synthesized with java.util.zip
    // (ZipOutputStream rejects it), so OfficeParser.readEntries still fails
    // closed on duplicates and the repository-side ZIP gate keeps its own
    // duplicate-path rejection.

    // ---------- F02: EPUB reading order ----------

    @Test
    fun spineOrderWinsOverArchivePathOrder() {
        val parsed = epub(
            opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                """<manifest>""" +
                """<item id="b" href="z-last-name.xhtml" media-type="application/xhtml+xml"/>""" +
                """<item id="a" href="a-first-name.xhtml" media-type="application/xhtml+xml"/>""" +
                """</manifest>""" +
                """<spine><itemref idref="b"/><itemref idref="a"/></spine></package>""",
            files = listOf(
                "OPS/a-first-name.xhtml" to xhtml("second by spine"),
                "OPS/z-last-name.xhtml" to xhtml("first by spine"),
            ),
        )
        assertEquals(listOf("first by spine", "second by spine"), parsed.pages.map { it.text })
        assertEquals("first by spine\nsecond by spine", parsed.text)
        assertEquals(OfficeParser.EPUB_FINGERPRINT, parsed.parserFingerprint)
    }

    @Test
    fun opfItemHrefsResolveAgainstThePackageDirectory() {
        val parsed = epub(
            opf = """<package xmlns="http://www.idpf.org/2007/opf" version="2.0">""" +
                """<manifest>""" +
                """<item id="sibling" href="ch/sibling.xhtml" media-type="application/xhtml+xml"/>""" +
                """<item id="up" href="../top.xhtml" media-type="application/xhtml+xml"/>""" +
                """</manifest><spine><itemref idref="up"/><itemref idref="sibling"/></spine></package>""",
            files = listOf(
                "OPS/ch/sibling.xhtml" to xhtml("sibling"),
                "top.xhtml" to xhtml("top level"),
            ),
        )
        assertEquals(listOf("top level", "sibling"), parsed.pages.map { it.text })
    }

    @Test
    fun declaredNavigationAndNonLinearAuxiliaryDocumentsRetainTheirContent() {
        val parsed = epub(
            opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                """<manifest>""" +
                """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" +
                """<item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>""" +
                """<item id="body" href="body.xhtml" media-type="application/xhtml+xml"/>""" +
                """<item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/>""" +
                """</manifest><spine>""" +
                """<itemref idref="nav"/><itemref idref="cover" linear="no"/>""" +
                """<itemref idref="body"/><itemref idref="notes" linear="no"/>""" +
                """</spine></package>""",
            files = listOf(
                "OPS/nav.xhtml" to xhtml("table of contents"),
                "OPS/cover.xhtml" to xhtml("cover art"),
                "OPS/body.xhtml" to xhtml("chapter body"),
                "OPS/notes.xhtml" to xhtml("endnotes"),
            ),
        )
        assertEquals(listOf("table of contents", "cover art", "chapter body", "endnotes"), parsed.pages.map { it.text })
        assertEquals(listOf(1, 2, 3, 4), parsed.pages.map { it.page })
    }

    @Test
    fun htmlOutsideTheSpineIsNeverReadAsBodyText() {
        val parsed = epub(
            opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                """<manifest><item id="only" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>""" +
                """<spine><itemref idref="only"/></spine></package>""",
            files = listOf(
                "OPS/chapter.xhtml" to xhtml("spine text"),
                "OPS/aaa-unlisted.xhtml" to xhtml("unlisted text"),
                "OPS/zzz-unlisted.html" to xhtml("also unlisted"),
            ),
        )
        assertEquals(listOf("spine text"), parsed.pages.map { it.text })
        assertFalse(parsed.text.contains("unlisted"))
    }

    @Test
    fun brokenEpubStructureFailsClosedInsteadOfFallingBackToFileOrder() {
        // No container.xml at all: previously the parser silently sorted by name.
        assertThrows(IllegalStateException::class.java) {
            OfficeParser.parse(
                "book.epub",
                zip(
                    "mimetype" to "application/epub+zip".toByteArray(),
                    "OPS/b.xhtml" to xhtml("b"),
                    "OPS/a.xhtml" to xhtml("a"),
                ),
            )
        }
        // container.xml without an OPF package.
        assertThrows(IllegalStateException::class.java) {
            epub(packagePresent = false, files = listOf("OPS/ch.xhtml" to xhtml("chapter")))
        }
        // Spine entry whose manifest item or document is missing.
        assertThrows(IllegalStateException::class.java) {
            epub(
                opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                    """<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>""" +
                    """<spine><itemref idref="missing"/></spine></package>""",
                files = listOf("OPS/a.xhtml" to xhtml("chapter")),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            epub(
                opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                    """<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>""" +
                    """<spine><itemref idref="a"/></spine></package>""",
                files = emptyList(),
            )
        }
        // Empty spine.
        assertThrows(IllegalStateException::class.java) {
            epub(
                opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                    """<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>""" +
                    """<spine/></package>""",
                files = listOf("OPS/a.xhtml" to xhtml("chapter")),
            )
        }
    }

    @Test
    fun repeatedSpineEntriesOfOneManifestItemAreRejectedAsAmbiguous() {
        assertThrows(IllegalStateException::class.java) {
            epub(
                opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                    """<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>""" +
                    """<spine><itemref idref="a"/><itemref idref="a"/></spine></package>""",
                files = listOf("OPS/a.xhtml" to xhtml("chapter")),
            )
        }
    }

    @Test
    fun spineContentOutsideThePackagePathFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            epub(
                opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                    """<manifest><item id="escape" href="../../outside.xhtml" media-type="application/xhtml+xml"/>""" +
                    """</manifest><spine><itemref idref="escape"/></spine></package>""",
                files = listOf("outside.xhtml" to xhtml("escaped")),
            )
        }
    }

    @Test
    fun epubChapterImagesBindToTheirSpineChapter() {
        val parsed = epub(
            opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                """<manifest>""" +
                """<item id="second" href="zz.xhtml" media-type="application/xhtml+xml"/>""" +
                """<item id="first" href="aa.xhtml" media-type="application/xhtml+xml"/>""" +
                """</manifest><spine><itemref idref="second"/><itemref idref="first"/></spine></package>""",
            files = listOf(
                "OPS/zz.xhtml" to """<html><body><p>second chapter</p><img src="fig.png"/></body></html>""",
                "OPS/aa.xhtml" to """<html><body><p>first chapter</p></body></html>""",
                "OPS/fig.png" to (PNG + "ONLY".toByteArray()),
            ),
        )
        assertEquals(listOf("second chapter", "first chapter"), parsed.pages.map { it.text })
        val asset = parsed.assets.single { it.kind == "IMAGE" }
        assertEquals(1, asset.page)
        assertEquals("OPS/zz.xhtml", asset.section)
        assertEquals("second chapter", asset.surroundingText)
    }

    @Test
    fun reboundRelationshipPrefixCannotInventAnImage() {
        val parsed = docx("""<w:p xmlns:r="urn:decoy"><w:r><w:t>text</w:t></w:r><a:blip xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" r:embed="missing"/></w:p>""")
        assertEquals("text", parsed.text)
        assertTrue(parsed.assets.isEmpty())
        assertFalse(parsed.needsVision)
    }

    @Test
    fun auxiliaryChapterRetainsImagesAndTheirSourcePosition() {
        val parsed = epub(
            opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest><item id="body" href="body.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="body"/><itemref idref="notes" linear="no"/></spine></package>""",
            files = listOf("OPS/body.xhtml" to xhtml("body"),
                "OPS/notes.xhtml" to """<html><body><img src="notes.png"/></body></html>""",
                "OPS/notes.png" to PNG),
        )
        val image = parsed.assets.single()
        assertEquals(2, image.page)
        assertEquals("OPS/notes.xhtml", image.section)
        assertEquals(2, parsed.pages.size)
        assertTrue(parsed.pages.last().needsVision)
    }

    @Test
    fun auxiliaryMissingIdrefsAndInvalidOrAliasedPathsFailClosed() {
        val cases = listOf(
            """<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/><itemref idref="missing" linear="no"/></spine>""",
            """<manifest><item id="a" href="https://example.invalid/a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine>""",
            """<manifest><item id="a" href="%2e%2e/%2e%2e/a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine>""",
            """<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="./a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine>""",
            """<manifest><item id="a" href="A.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine>""",
        )
        cases.forEach { structure ->
            assertThrows(IllegalStateException::class.java) {
                epub(opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">$structure</package>""",
                    files = listOf("OPS/a.xhtml" to xhtml("body")))
            }
        }
    }

    @Test
    fun xmlBoundsAlsoCoverContainerRelationshipsAndPackageDocuments() {
        val nested = "<x>".repeat(300) + "</x>".repeat(300)
        assertThrows(IllegalStateException::class.java) {
            OfficeParser.parse("note.docx", zip("word/document.xml" to docxDocument("<w:p><w:r><w:t>text</w:t></w:r></w:p>"),
                "word/_rels/document.xml.rels" to "<Relationships>$nested</Relationships>"))
        }
        assertThrows(IllegalStateException::class.java) {
            epub(opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">$nested</package>""", files = emptyList())
        }
    }

    // ---------- fixtures ----------

    private fun docx(body: String): ParsedPublication =
        OfficeParser.parse("note.docx", zip("word/document.xml" to docxDocument(body).toByteArray()))

    private fun docxDocument(body: String): String =
        """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:body>$body</w:body></w:document>"""

    private fun xhtml(text: String): String =
        """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>$text</p></body></html>"""

    /**
     * A structurally valid EPUB 2/3 package: `mimetype`, `META-INF/container.xml`,
     * a package document and its content documents.
     */
    private fun epub(
        opf: String = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
            """<manifest><item id="ch" href="ch.xhtml" media-type="application/xhtml+xml"/></manifest>""" +
            """<spine><itemref idref="ch"/></spine></package>""",
        files: List<Pair<String, Any>>,
        packagePresent: Boolean = true,
    ): ParsedPublication {
        val entries = mutableListOf<Pair<String, ByteArray>>(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to (
                """<?xml version="1.0"?><container version="1.0" """ +
                    """xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
                    """<rootfiles><rootfile full-path="OPS/content.opf" """ +
                    """media-type="application/oebps-package+xml"/></rootfiles></container>"""
                ).toByteArray(),
        )
        if (packagePresent) entries += "OPS/content.opf" to opf.toByteArray()
        files.forEach { (name, body) ->
            entries += name to (body as? ByteArray ?: (body as String).toByteArray())
        }
        return OfficeParser.parse("book.epub", zipBytes { zip ->
            entries.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        })
    }

    private fun zip(vararg entries: Pair<String, Any>): ByteArray = zipBytes { zip ->
        entries.forEach { (name, payload) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(payload as? ByteArray ?: (payload as String).toByteArray())
            zip.closeEntry()
        }
    }

    private fun zipBytes(block: (ZipOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use(block)
        return out.toByteArray()
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
