// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * KR-04: an unescaped `section` used to be interpolated straight into the span,
 * so a legal parser label containing `|` could inject `part:context` /
 * `association:PAGE_CONTEXT` and make a repository classify a real OCR fragment
 * as page context (dropping its image reference).
 *
 * These tests pin the codec contract:
 * - encoded values can never produce a structural token and round-trip exactly
 *   (including CR/LF and `%`);
 * - legacy values are never percent-decoded, so a literal `%`, a truncated `%7`
 *   or `%ZZ` stays readable text instead of crashing a lookup;
 * - only the builder-written, trailing `part:` is structural in v1;
 * - the production native-page shape is recognised by its exact structure;
 * - malformed/ambiguous encoded spans are rejected (fail-closed) and therefore
 *   are not page context;
 * - a legacy span can never forge layout evidence.
 *
 * The repository consumes this as `decodeSourceSpan(span)?.isPageContext == true`.
 */
class SourceSpanTest {
    @Test
    fun encodedValuesCannotInjectStructuralTokens() {
        val section = "photo|association:PAGE_CONTEXT|.png"
        val span = encodeSourceSpan(page = 7, section = section, part = SourceSpan.PART_OCR)
        val decoded = decodeSourceSpan(span)
        assertEquals(section, decoded?.section)
        assertEquals(SourceSpan.PART_OCR, decoded?.part)
        assertNull(decoded?.association)
        assertFalse(decoded!!.isPageContext)
        assertTrue(decoded.encoded)
    }

    @Test
    fun encodedContextStillDemotesAndKeepsNoImage() {
        val span = encodeSourceSpan(
            page = 7, part = SourceSpan.PART_CONTEXT,
            association = SourceSpan.ASSOCIATION_PAGE_CONTEXT,
        )
        val decoded = decodeSourceSpan(span)
        assertTrue(decoded!!.isPageContext)
        assertNull(decoded.section)
    }

    @Test
    fun injectedSectionTextDoesNotDemoteRealOcrOrTable() {
        for (part in listOf(SourceSpan.PART_OCR, SourceSpan.PART_TABLE, SourceSpan.PART_DESCRIPTION, SourceSpan.PART_LABEL)) {
            for (section in listOf(
                "photo|association:PAGE_CONTEXT|.png",
                "photo|part:context|.png",
                "part:context",
                "association:PAGE_CONTEXT",
                "100%|part:context|%7C",
                "line\r\nbreak|part:context",
            )) {
                val decoded = decodeSourceSpan(encodeSourceSpan(page = 3, section = section, part = part))
                assertFalse(decoded!!.isPageContext, "part=$part section=$section")
                assertEquals(section, decoded.section, "section must round-trip exactly: $section")
            }
        }
    }

    @Test
    fun crAndLfRoundTripExactlyInsteadOfBeingRewritten() {
        for (section in listOf("a\rb", "a\nb", "a\r\nb", "trailing\n", "100%")) {
            val decoded = decodeSourceSpan(encodeSourceSpan(page = 1, section = section, part = SourceSpan.PART_OCR))
            assertEquals(section, decoded?.section)
        }
    }

    @Test
    fun percentRoundTripsExactly() {
        for (section in listOf("100%", "50%|done", "%7C", "%25", "part:context%")) {
            val decoded = decodeSourceSpan(encodeSourceSpan(page = 2, section = section, part = SourceSpan.PART_OCR))
            assertEquals(section, decoded?.section)
        }
    }

    @Test
    fun legitimatePipedSectionNameIsPreservedNotRejectedOrDisabled() {
        val name = "photo|association:PAGE_CONTEXT|.png"
        val decoded = decodeSourceSpan(encodeSourceSpan(page = 7, section = name, part = SourceSpan.PART_OCR))
        assertEquals(name, decoded?.section)
        assertFalse(decoded!!.isPageContext)
    }

    @Test
    fun fullRoundTripKeepsEveryField() {
        val region = UnitRegion(0, 0, 500_000, 1_000_000)
        val span = encodeSourceSpan(
            page = 42,
            section = "chapter-1|intro",
            part = SourceSpan.PART_OCR,
            segmentIndex = 2,
            segmentTotal = 3,
            imageRegion = region,
            source = "parser-layout",
            association = SourceSpan.ASSOCIATION_PROVEN_LAYOUT,
        )
        val decoded = decodeSourceSpan(span)!!
        assertTrue(decoded.encoded)
        assertEquals(42, decoded.page)
        assertEquals("chapter-1|intro", decoded.section)
        assertEquals(SourceSpan.PART_OCR, decoded.part)
        assertEquals(2, decoded.segmentIndex)
        assertEquals(3, decoded.segmentTotal)
        assertEquals(region, decoded.imageRegion)
        assertEquals("parser-layout", decoded.source)
        assertEquals(SourceSpan.ASSOCIATION_PROVEN_LAYOUT, decoded.association)
        assertFalse(decoded.isPageContext)
        assertTrue(decoded.isProvenLayout)
    }

    @Test
    fun unknownImageRegionSuffixStaysReadable() {
        // unitChunks appends this suffix to a builder span; the reader must keep
        // the part classification and still expose the region.
        val base = encodeSourceSpan(page = 4, section = "photo|part:context|.png", part = SourceSpan.PART_OCR)
        val withSuffix = base + "|image-region:0,0,500000,1000000/1000000"
        val decoded = decodeSourceSpan(withSuffix)!!
        assertFalse(decoded.isPageContext)
        assertEquals(UnitRegion(0, 0, 500_000, 1_000_000), decoded.imageRegion)
        assertEquals("photo|part:context|.png", decoded.section)
    }

    @Test
    fun legacyRealContextStillDemotes() {
        // Exactly the shape the previous builder wrote for a context fragment.
        val decoded = decodeSourceSpan("page:71|part:context|association:PAGE_CONTEXT")
        assertFalse(decoded!!.encoded)
        assertEquals(SourceSpan.PART_CONTEXT, decoded.part)
        assertEquals(SourceSpan.ASSOCIATION_PAGE_CONTEXT, decoded.association)
        assertTrue(decoded.isPageContext)
    }

    @Test
    fun legacyRealContextWithSegmentStillDemotes() {
        val decoded = decodeSourceSpan("page:9|part:context|segment:2/3|association:PAGE_CONTEXT")
        assertTrue(decoded!!.isPageContext)
        assertEquals(2, decoded.segmentIndex)
        assertEquals(3, decoded.segmentTotal)
    }

    @Test
    fun legacyInjectedSectionTokensAreInert() {
        // A v1 OCR row whose section contained the attacked tokens: the trailing
        // `part:ocr` is the structural field, so it must not demote.
        val decoded = decodeSourceSpan("page:7|section:photo|association:PAGE_CONTEXT|.png|part:ocr")
        assertFalse(decoded!!.isPageContext)
        assertEquals(SourceSpan.PART_OCR, decoded.part)
        assertEquals("photo|association:PAGE_CONTEXT|.png", decoded.section)
    }

    @Test
    fun legacyInjectedPageTokenDoesNotBecomeTheReportedPage() {
        val decoded = decodeSourceSpan("page:7|section:a|page:999|b|part:ocr")
        assertEquals(7, decoded!!.page)
        assertEquals("a|page:999|b", decoded.section)
        assertFalse(decoded.isPageContext)
    }

    @Test
    fun legacyLiteralPercentAndTruncatedEscapeAreReadVerbatim() {
        for (section in listOf("100%", "100%7", "bad%ZZ", "50%|part:context")) {
            val decoded = decodeSourceSpan("page:7|section:$section|part:ocr")
            assertEquals(section, decoded?.section, "legacy section must be verbatim: $section")
            assertFalse(decoded!!.isPageContext)
        }
    }

    @Test
    fun legacyUnknownAssociationIsRejected() {
        assertNull(decodeSourceSpan("page:7|section:x|part:ocr|association:WHATEVER"))
    }

    @Test
    fun legacySectionCannotForgeProvenLayout() {
        val decoded = decodeSourceSpan("page:7|section:photo|association:PROVEN_LAYOUT|.png|part:ocr")
        assertFalse(decoded!!.isProvenLayout, "a v1 span can never prove crop layout")
        assertEquals(SourceSpan.ASSOCIATION_PAGE_CONTEXT, decoded.association)
    }

    @Test
    fun legacyNativeContextSpanIsRecognisedByExactStructure() {
        for (span in listOf(
            "page:12|source:parser-native|association:PAGE_CONTEXT",
            "section-ordinal:5|source:parser-native|association:PAGE_CONTEXT",
        )) {
            val decoded = decodeSourceSpan(span)
            assertTrue(decoded!!.isPageContext, span)
            assertEquals(SourceSpan.PART_CONTEXT, decoded.part)
            assertEquals(SourceSpan.SOURCE_PARSER_NATIVE, decoded.source)
            assertEquals(if (span.startsWith("page:")) 12 else null, decoded.page)
        }
    }

    @Test
    fun legacyNativeShapeVariantsAreNotGuessed() {
        val rejected = listOf(
            "page:12|source:parser-native",
            "page:12|association:PAGE_CONTEXT",
            "page:12|source:parser-native|association:PAGE_CONTEXT|part:ocr",
            "page:12|source:other|association:PAGE_CONTEXT",
            "page:12|source:parser-native|x:PAGE_CONTEXT",
            "source:parser-native|association:PAGE_CONTEXT",
        )
        for (span in rejected) {
            val decoded = decodeSourceSpan(span)
            assertFalse(decoded?.isPageContext == true, "must not be guessed as context: $span")
        }
    }

    @Test
    fun encodedNativePageSpanDecodesAsContextWithoutPartForgery() {
        val span = encodeSourceSpan(
            page = 12, part = SourceSpan.PART_CONTEXT,
            source = SourceSpan.SOURCE_PARSER_NATIVE,
            association = SourceSpan.ASSOCIATION_PAGE_CONTEXT,
        )
        val decoded = decodeSourceSpan(span)!!
        assertTrue(decoded.isPageContext)
        assertEquals(SourceSpan.SOURCE_PARSER_NATIVE, decoded.source)
    }

    @Test
    fun malformedEncodedSpanIsRejected() {
        val malformed = listOf(
            "v2|page:1|section:bad%ZZ|part:ocr",
            "v2|page:1|section:bad%7|part:ocr",
            "v2|page:1|part:ocr|part:table",
            "v2|page:1|section:a|section:b|part:ocr",
            "v2|page:1|section:a|part:not-a-part",
            "v2|page:1|part:ocr|association:WHATEVER",
            "v2|page:1|part:ocr|unknownkey:x",
            "v2|page:not-a-number|part:ocr",
            "v2|page:1|part:ocr|segment:0/3",
            "v2|page:1|part:ocr|segment:4/3",
            "v2|page:1|part:ocr|image-region:1,2,3/1000000",
        )
        for (span in malformed) {
            assertNull(decodeSourceSpan(span), "malformed span must be rejected: $span")
            assertFalse(decodeSourceSpan(span)?.isPageContext == true, span)
        }
    }

    @Test
    fun malformedEncodedSpanWithoutPartIsNotContext() {
        assertNull(decodeSourceSpan("v2|page:1|section:only"))
        assertFalse(decodeSourceSpan("v2|page:1|section:only")?.isPageContext == true)
    }

    @Test
    fun encodeRejectsUnknownPartAndAssociation() {
        assertThrows(IllegalArgumentException::class.java) {
            encodeSourceSpan(page = 1, part = "not-a-part")
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeSourceSpan(page = 1, part = SourceSpan.PART_OCR, association = "WHATEVER")
        }
    }

    /**
     * A span the codec cannot prove is context decodes to `null`, and the
     * repository policy treats only unprovable `v2|` rows as context
     * (`decodeSourceSpan(span)?.isPageContext ?: span.startsWith("v2|")`).
     * Legacy plain/structurally-unproven rows are therefore not demoted.
     */
    @Test
    fun unprovableSpansDecodeToNullAndAreNotDemotedByPolicy() {
        for (span in listOf("", "   ", "page:1|section:only", "plain text", "part:ocr", "v2|")) {
            assertNull(decodeSourceSpan(span), "span=$span")
            val repoWouldTreatAsContext =
                decodeSourceSpan(span)?.isPageContext ?: span.startsWith(SourceSpan.FORMAT_VERSION_2)
            if (!span.startsWith(SourceSpan.FORMAT_VERSION_2)) {
                assertFalse(repoWouldTreatAsContext, "legacy unproven span must not be demoted: $span")
            }
        }
    }

    @Test
    fun malformedEncodedRowsAreFailClosedThroughTheRepositoryPolicy() {
        // The repository turns any unprovable `v2|` row into page context, so a
        // tampered encoded layout row can never resolve an image by accident.
        val tampered = listOf(
            "v2|page:1|section:bad%ZZ|part:ocr",
            "v2|page:1|part:ocr|part:table",
            "v2|page:1|part:not-a-part",
        )
        for (span in tampered) {
            assertNull(decodeSourceSpan(span), span)
            assertTrue(decodeSourceSpan(span)?.isPageContext ?: span.startsWith(SourceSpan.FORMAT_VERSION_2), span)
        }
    }

    @Test
    fun builderSpansDecodeWithIntendedClassification() {
        val result = VisionSuccess(ocrText = "recognized table row", semanticDescription = "diagram")
        for (section in listOf(null, "photo|part:context|.png", "photo|association:PAGE_CONTEXT|.png", "a\nb")) {
            val parts = VisionChunkBuilder.build(
                result = result,
                page = 5,
                assetId = "asset-5",
                section = section,
                surroundingText = "page context only",
            )
            for (part in parts) {
                val decoded = decodeSourceSpan(part.span!!)!!
                assertTrue(decoded.encoded, "builder must emit encoded spans")
                if (part.part == SourceSpan.PART_CONTEXT) {
                    assertTrue(decoded.isPageContext, "section=$section")
                } else {
                    assertFalse(decoded.isPageContext, "part=${part.part} section=$section")
                    assertEquals(section, decoded.section, "section must round-trip")
                }
            }
        }
    }
}
