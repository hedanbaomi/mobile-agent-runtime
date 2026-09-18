// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DocumentUnitPlannerTest {
    private val planner = DocumentUnitPlanner()

    @Test fun ordinaryNativeAndScannedPagesUseOneUnitWithDifferentExecutionNeeds() {
        val native = planner.plan("hash", listOf(PlanningPage(1, "Complete text", false))).single()
        val scanned = planner.plan("hash", listOf(PlanningPage(2, "", true))).single()
        assertFalse(native.requiresVision)
        assertTrue(scanned.requiresVision)
        assertEquals(ProcessingUnitKind.PAGE, scanned.kind)
        assertEquals(UnitRegion.FULL, scanned.coverage.region)
    }

    @Test fun mixedPagePreservesNativeProvenance() {
        val unit = planner.plan("hash", listOf(PlanningPage(1, "Native caption", true))).single()
        assertEquals("Native caption", unit.nativeText)
        assertEquals("page-extraction", unit.coverage.nativeTextSource)
        assertTrue(unit.requiresVision)
    }

    @Test fun syntheticPdfFixturesFlowThroughRealParserBeforePlanning() {
        val native = planner.planPublication("native", PdfParser.parse(PdfParser.writeSimpleTextPdf("Complete native text")))
        val scanned = planner.planPublication("scan", PdfParser.parse(PdfParser.writePdfWithImageXObject("")))
        val mixed = planner.planPublication("mixed", PdfParser.parse(PdfParser.writeTextAndInlineImagePdf("Native caption")))
        val table = planner.planPublication("table", PdfParser.parse(PdfParser.writeTextAndVectorPdf("| Header | Value |")))
        assertFalse(native.single().requiresVision)
        assertTrue(scanned.all { it.requiresVision })
        assertTrue(mixed.all { it.requiresVision && it.nativeText.contains("Native caption") })
        assertTrue(table.size > 1)
        assertTrue(table.all { it.kind == ProcessingUnitKind.REGION })
    }

    @Test fun denseAndComplexPagesSplitAdaptivelyAndCoverExactlyOnce() {
        for (page in listOf(PlanningPage(1, "dense", true, dense = true),
            PlanningPage(1, "diagram", true, complexLayout = true),
            PlanningPage(1, "", true, width = 9000, height = 12000))) {
            val units = planner.plan("hash", listOf(page))
            assertTrue(units.size > 1)
            assertTrue(units.size <= DocumentUnitPlanner.MAX_UNITS_PER_PAGE)
            val area = units.sumOf { val r = it.coverage.region; (r.right - r.left).toLong() * (r.bottom - r.top) }
            assertEquals(UnitRegion.SCALE.toLong() * UnitRegion.SCALE, area)
            units.forEachIndexed { i, unit ->
                assertEquals(i, unit.readingOrder)
                units.drop(i + 1).forEach { other ->
                    val a = unit.coverage.region; val b = other.coverage.region
                    assertFalse(a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
                }
            }
        }
    }

    @Test fun crossPageTableRetainsHeaderGroupAndPageOrder() {
        val pages = (1..2).map { PlanningPage(it, "rows", true, tableHeader = "Name | Value", continuationKey = "table-1") }
        val units = planner.plan("hash", pages)
        assertEquals(4, units.size)
        assertEquals(1, units.map { it.continuationGroupId }.distinct().size)
        assertEquals(listOf(0, 0, 1, 1), units.map { it.continuationIndex })
        assertTrue(units.all { it.tableHeader == "Name | Value" })
        assertEquals(listOf(1, 1, 2, 2), units.map { it.page })
    }

    @Test fun realPublicationPathRecognizesOnlyExplicitAdjacentTableHeaders() {
        val publication = ParsedPublication(SourceFormat.PDF, "", listOf(
            ExtractedPage(1, "| Name | Value |\n| A | 10 |", true),
            ExtractedPage(2, "| Name | Value |\n| B | 20 |", true),
            ExtractedPage(3, "Unrelated ordinary paragraph", true),
        ), emptyList(), true, "fixture")
        val units = planner.planPublication("hash", publication)
        assertEquals(5, units.size)
        assertEquals(1, units.take(4).map { it.continuationGroupId }.distinct().size)
        assertNotNull(units.first().continuationGroupId)
        assertNull(units.last().continuationGroupId)
        assertEquals("| Name | Value |", units.first().tableHeader)
        assertEquals(listOf(0, 0, 1, 1), units.take(4).map { it.continuationIndex })
    }

    @Test fun nonPdfAssetsHaveDistinctStableIdentityAndOversizeRegions() {
        val assets = listOf("image-a", "image-b").map { ExtractedAsset(it, "IMAGE", 1, null, byteArrayOf(1), "image/png", "caption") }
        val publication = ParsedPublication(SourceFormat.OFFICE_ARCHIVE, "native", emptyList(), assets, true, "fixture")
        val units = planner.planPublication("hash", publication, mapOf("image-a" to (9000 to 9000)))
        assertEquals(1, units.count { !it.requiresVision })
        assertTrue(units.count { it.sourceAssetId == "image-a" } > 1)
        assertEquals(1, units.count { it.sourceAssetId == "image-b" })
        assertEquals(units.size, units.map { it.unitId }.distinct().size)
    }

    @Test fun identityStableAcrossRetriesButVersionContentGeometryAndOrderAreBound() {
        val pages = listOf(PlanningPage(1, "", true))
        val unit = planner.plan("hash", pages).single()
        assertEquals(unit, planner.plan("hash", pages).single())
        assertNotEquals(unit.unitId, DocumentUnitPlanner("v2").plan("hash", pages).single().unitId)
        assertNotEquals(unit.unitId, planner.plan("changed", pages).single().unitId)
        assertNotEquals(unit.unitId, planner.plan("hash", listOf(pages.single().copy(dense = true))).first().unitId)
    }

    @Test fun nativeExtractionAndParserChangesCannotReusePriorVisionInputIdentity() {
        val publication = ParsedPublication(SourceFormat.PDF, "", listOf(ExtractedPage(1, "old native caption", true)), emptyList(), true, "parser-v1")
        val original = planner.planPublication("same-document", publication).single()
        val changedText = planner.planPublication("same-document", publication.copy(pages = listOf(ExtractedPage(1, "corrected native caption", true)))).single()
        val changedParser = planner.planPublication("same-document", publication.copy(parserFingerprint = "parser-v2")).single()
        assertEquals(original.region, changedText.region)
        assertEquals(original.page, changedText.page)
        assertEquals(original.plannerVersion, changedText.plannerVersion)
        assertNotEquals(original.sourceInputIdentity, changedText.sourceInputIdentity)
        assertNotEquals(original.unitId, changedText.unitId)
        assertNotEquals(original.unitId, changedParser.unitId)
        assertEquals(original, planner.planPublication("same-document", publication).single())
        val table = PlanningPage(1, "same text", true, tableHeader = "Name", continuationKey = "table-a")
        val first = planner.plan("same-document", listOf(table)).first()
        assertNotEquals(first.unitId, planner.plan("same-document", listOf(table.copy(tableHeader = "Value"))).first().unitId)
        assertNotEquals(first.unitId, planner.plan("same-document", listOf(table.copy(continuationKey = "table-b"))).first().unitId)
    }

    @Test fun planningThousandsOfPagesContainsNoRenderedPayloadAndNeverInvokesRasterizer() {
        val bytes = PdfParser.writeSimpleTextPdf("Native extraction")
        val parsed = PdfParser.parse(bytes)
        assertFalse(planner.planPublication("hash", parsed).single().requiresVision)
        val units = planner.plan("hash", (1..2000).map { PlanningPage(it, "", true) })
        assertEquals(2000, units.size)
        assertTrue(units.all { it.nativeText.isEmpty() && it.region == null })
        assertFalse(ProcessingUnit::class.java.declaredFields.any { it.type == ByteArray::class.java })
    }
}
