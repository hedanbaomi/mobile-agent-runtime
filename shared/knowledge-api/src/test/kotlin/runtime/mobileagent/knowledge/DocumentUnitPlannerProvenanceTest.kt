// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DocumentUnitPlannerProvenanceTest {
    @Test fun unrenderableCropPlanFailsBeforeAnyPartialPlanCanBeDispatched() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            DocumentUnitPlanner().plan("huge", listOf(PlanningPage(1, "", true, Int.MAX_VALUE, Int.MAX_VALUE)))
        }
        assertTrue(failure.message.orEmpty().contains("PIPELINE_REGION_LIMIT_EXCEEDED"))
    }

    @Test fun evidenceCannotBisectUnicodeAndDisjointFragmentsKeepASeparator() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentUnitPlanner().plan("unicode", listOf(PlanningPage(1, "A😀B", true,
                textRegions = listOf(LayoutTextEvidence(UnitRegion.FULL, 0, 2)))))
        }
        val unit = DocumentUnitPlanner().plan("fragments", listOf(PlanningPage(1, "first GAP second", true,
            textRegions = listOf(LayoutTextEvidence(UnitRegion.FULL, 0, 5), LayoutTextEvidence(UnitRegion.FULL, 10, 16))))).single()
        assertEquals("first\nsecond", unit.effectiveRequestText())
    }

    private val half = UnitRegion.SCALE / 2
    private val top = UnitRegion(0, 0, UnitRegion.SCALE, half)
    private val bottom = UnitRegion(0, half, UnitRegion.SCALE, UnitRegion.SCALE)

    @Test fun provenContainedEvidenceMapsExactOffsetsPerCrop() {
        val source = "AAAA\nBBBB"
        val units = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(
            1, source, true, dense = true,
            textRegions = listOf(
                LayoutTextEvidence(top, 0, 4, "pdf-lines"),
                LayoutTextEvidence(bottom, 5, 9, "pdf-lines"),
            ),
        )))
        assertEquals(2, units.size)
        val topUnit = units[0]
        val bottomUnit = units[1]
        assertEquals(TextImageAssociation.PROVEN_LAYOUT, topUnit.textImageAssociation)
        assertEquals("AAAA", topUnit.effectiveRequestText())
        assertEquals(0, topUnit.coverage.textStart)
        assertEquals(4, topUnit.coverage.textEnd)
        assertNull(topUnit.layoutDegradation)
        assertEquals(1, topUnit.textLayoutEvidence.size)
        assertEquals("pdf-lines", topUnit.textLayoutEvidence.single().evidenceSource)
        assertEquals(TextImageAssociation.PROVEN_LAYOUT, bottomUnit.textImageAssociation)
        assertEquals("BBBB", bottomUnit.effectiveRequestText())
        assertEquals(5, bottomUnit.coverage.textStart)
        assertEquals(9, bottomUnit.coverage.textEnd)
        assertNull(bottomUnit.layoutDegradation)
        // Full native text is provenance on every unit and is never truncated.
        assertTrue(units.all { it.nativeText == source })
        assertTrue(units.all { it.requiresVision })
    }

    @Test fun straddlingEvidenceFallsBackToWholePageContextOnce() {
        val source = "AAAA\nBBBB"
        val units = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(
            1, source, true, dense = true,
            textRegions = listOf(LayoutTextEvidence(UnitRegion.FULL, 0, source.length, "pdf-page")),
        )))
        assertEquals(2, units.size)
        assertTrue(units.none { it.textImageAssociation == TextImageAssociation.PROVEN_LAYOUT })
        assertEquals(1, units.count { it.effectiveRequestText().isNotEmpty() })
        assertEquals(source, units[0].effectiveRequestText())
        assertEquals(TextImageAssociation.PAGE_CONTEXT, units[0].textImageAssociation)
        assertEquals("", units[1].effectiveRequestText())
        assertTrue(units.all { it.layoutDegradation == LayoutDegradation.TEXT_NOT_LOCATED_IN_CROP })
    }

    @Test fun partialEvidenceKeepsUnmappedCropExplicitlyDegraded() {
        val source = "AAAA\nBBBB"
        val units = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(
            1, source, true, dense = true,
            textRegions = listOf(LayoutTextEvidence(top, 0, 4, "pdf-lines")),
        )))
        assertEquals(2, units.size)
        assertEquals(TextImageAssociation.PROVEN_LAYOUT, units[0].textImageAssociation)
        assertEquals("AAAA", units[0].effectiveRequestText())
        assertEquals(TextImageAssociation.PAGE_CONTEXT, units[1].textImageAssociation)
        assertEquals("", units[1].effectiveRequestText())
        assertEquals(LayoutDegradation.TEXT_NOT_LOCATED_IN_CROP, units[1].layoutDegradation)
    }

    @Test fun multiCropPageCarriesWholePageContextExactlyOnce() {
        val source = "captions and notes"
        val units = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(1, source, true, dense = true)))
        assertEquals(2, units.size)
        assertEquals(1, units.count { it.effectiveRequestText().isNotEmpty() })
        assertEquals(source, units.first { it.effectiveRequestText().isNotEmpty() }.effectiveRequestText())
        assertTrue(units.all { it.textImageAssociation == TextImageAssociation.PAGE_CONTEXT })
        assertTrue(units.all { it.nativeText == source })
    }

    @Test fun oversizedPageTextStaysLocalOnlyWithExplicitDegradation() {
        val source = "x".repeat(DocumentUnitPlanner.MAX_REQUEST_TEXT_CHARS + 1)
        val units = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(1, source, true, dense = true)))
        assertTrue(units.all { it.effectiveRequestText().isEmpty() })
        assertTrue(units.all { it.layoutDegradation == LayoutDegradation.PAGE_TEXT_LOCAL_ONLY })
        assertTrue(units.all { it.nativeText == source })
    }

    @Test fun headerAndContinuationHintsAreCountedInAdmission() {
        val budget = VisionRequestBudget(contextWindowTokens = 12_000, outputReserveTokens = 4_096)
        assertEquals(7_904L, budget.effectiveInputUnits)
        val text = "y".repeat(2_000)
        val withHeader = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(
            1, text, true, tableHeader = "h".repeat(1_000),
        )), budget)
        assertTrue(withHeader.all { it.effectiveRequestText().isEmpty() })
        assertTrue(withHeader.all { it.layoutDegradation == LayoutDegradation.PAGE_TEXT_LOCAL_ONLY })

        val withoutHeader = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(1, text, true)), budget)
        assertEquals(1, withoutHeader.count { it.effectiveRequestText() == text })
        assertNull(withoutHeader.single().layoutDegradation)
    }

    @Test fun imageBaselineThatCannotFitFailsLocallyButNeverBlocksNativePages() {
        val budget = VisionRequestBudget(contextWindowTokens = 6_000, outputReserveTokens = 4_096)
        assertEquals(1_904L, budget.effectiveInputUnits)
        val error = assertThrows(IllegalArgumentException::class.java) {
            DocumentUnitPlanner().plan("hash", listOf(PlanningPage(1, "text", true)), budget)
        }
        assertTrue(error.message.orEmpty().startsWith("PIPELINE_VISION_BUDGET_EXCEEDED"))
        val native = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(1, "text", false)), budget).single()
        assertEquals("text", native.effectiveRequestText())
    }

    @Test fun knownWindowSuppressesTextThatWouldOverflowInputCapacity() {
        val text = "z".repeat(1_000)
        val tight = VisionRequestBudget(contextWindowTokens = 10_000, outputReserveTokens = 4_096)
        val suppressed = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(1, text, true)), tight)
        assertTrue(suppressed.all { it.effectiveRequestText().isEmpty() })
        assertEquals(LayoutDegradation.PAGE_TEXT_LOCAL_ONLY, suppressed.single().layoutDegradation)
        val roomy = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(1, text, true)),
            VisionRequestBudget(contextWindowTokens = 131_072))
        assertEquals(text, roomy.single().effectiveRequestText())
    }

    @Test fun provenEvidenceSurvivesSerialization() {
        val source = "AAAA\nBBBB"
        val unit = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(
            1, source, true, dense = true,
            textRegions = listOf(LayoutTextEvidence(top, 0, 4, "pdf-lines")),
        ))).first()
        assertEquals(TextImageAssociation.PROVEN_LAYOUT, unit.textImageAssociation)
        val restored = Json.decodeFromString<ProcessingUnit>(Json.encodeToString(unit))
        assertEquals(unit, restored)
        assertEquals(TextImageAssociation.PROVEN_LAYOUT, restored.textImageAssociation)
        assertEquals(top, restored.textLayoutEvidence.single().region)
        assertEquals(0, restored.textLayoutEvidence.single().textStart)
        assertEquals("AAAA", restored.effectiveRequestText())
    }

    @Test fun planPublicationThreadsEvidenceAndBudget() {
        val publication = ParsedPublication(SourceFormat.PDF, "", listOf(
            ExtractedPage(1, "AAAA\nBBBB", true,
                textRegions = listOf(LayoutTextEvidence(top, 0, 4, "pdf-lines"))),
        ), emptyList(), true, "fixture")
        val unit = DocumentUnitPlanner().planPublication("hash", publication).single()
        assertEquals(TextImageAssociation.PROVEN_LAYOUT, unit.textImageAssociation)
        assertEquals("AAAA", unit.effectiveRequestText())
        val error = assertThrows(IllegalArgumentException::class.java) {
            DocumentUnitPlanner().planPublication(
                "hash", publication,
                budget = VisionRequestBudget(contextWindowTokens = 6_000, outputReserveTokens = 4_096),
            )
        }
        assertTrue(error.message.orEmpty().startsWith("PIPELINE_VISION_BUDGET_EXCEEDED"))
    }

    @Test fun complexLayoutWithoutEvidenceDegradesExplicitly() {
        val source = "left column\nright column"
        val units = DocumentUnitPlanner().plan("hash", listOf(PlanningPage(1, source, true, complexLayout = true)))
        assertEquals(2, units.size)
        assertEquals(1, units.count { it.effectiveRequestText() == source })
        assertTrue(units.all { it.layoutDegradation == LayoutDegradation.COMPLEX_LAYOUT_TEXT_ORDER_UNVERIFIED })
    }
}
