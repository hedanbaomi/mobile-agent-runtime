// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DocumentUnitPlannerReviewRegressionTest {
    private fun plan(text: String, budget: VisionRequestBudget = VisionRequestBudget()) =
        DocumentUnitPlanner().plan("review-synthetic", listOf(PlanningPage(1, text, true)), budget)

    /**
     * The whole page text must survive on `nativeText`; at most one Vision
     * request may carry it, and only as explicit whole-page PAGE_CONTEXT. No
     * text slice may be paired with an image stripe by list position.
     */
    private fun assertLosslessNoFabrication(source: String, units: List<ProcessingUnit>) {
        assertTrue(units.isNotEmpty())
        assertTrue(units.all { it.nativeText == source }, "native text must be retained in full")
        val carrying = units.filter { it.effectiveRequestText().isNotEmpty() }
        assertTrue(carrying.size <= 1, "at most one request may carry page text; no cartesian repetition")
        carrying.forEach {
            assertEquals(source, it.effectiveRequestText())
            assertEquals(TextImageAssociation.PAGE_CONTEXT, it.textImageAssociation)
            assertTrue(it.textLayoutEvidence.isEmpty())
        }
        assertTrue(units.filter { it.requiresVision }
            .all { it.effectiveRequestText().length <= DocumentUnitPlanner.MAX_REQUEST_TEXT_CHARS })
    }

    @Test fun blankSlicesDoNotExpandToPageText() {
        val source = "A" + " ".repeat(30_000) + "B"
        val units = plan(source)
        assertTrue(units.any { it.effectiveRequestText().isBlank() })
        assertTrue(units.all { it.effectiveRequestText().isEmpty() })
        assertTrue(units.all { it.layoutDegradation == LayoutDegradation.PAGE_TEXT_LOCAL_ONLY })
        assertLosslessNoFabrication(source, units)
    }

    @Test fun explicitEmptyAndWhitespaceSurviveSerialization() {
        val unit = plan("page text").single()
        for (slice in listOf("", " ", "\t\n")) {
            val encoded = Json.encodeToString(unit.copy(requestText = slice))
            val restored = Json.decodeFromString<ProcessingUnit>(encoded)
            assertEquals(slice, restored.effectiveRequestText())
        }
    }

    @Test fun legacyMissingSliceUsesPageOrPersistedOffsetsWithoutInventingText() {
        val unit = plan("page text").single()
        val page = Json.parseToJsonElement(Json.encodeToString(unit)).jsonObject.toMutableMap()
        page.remove("requestText")
        page["coverage"] = JsonObject(page.getValue("coverage").jsonObject - "textStart" - "textEnd")
        assertEquals(unit.nativeText, Json.decodeFromString<ProcessingUnit>(JsonObject(page).toString()).effectiveRequestText())

        val trailing = unit.copy(coverage = unit.coverage.copy(textStart = 9, textEnd = 9), requestText = "")
        val old = Json.parseToJsonElement(Json.encodeToString(trailing)).jsonObject - "requestText"
        assertEquals("", Json.decodeFromString<ProcessingUnit>(JsonObject(old).toString()).effectiveRequestText())
        assertThrows(IllegalArgumentException::class.java) {
            trailing.copy(requestText = null, coverage = trailing.coverage.copy(textEnd = 10)).effectiveRequestText()
        }
    }

    @Test fun overCapacityKeepsOriginalLocalOnlyInsteadOfTruncatingOrSlicing() {
        val source = "x".repeat(600_000)
        val units = plan(source)
        assertLosslessNoFabrication(source, units)
        assertTrue(units.all { it.effectiveRequestText().isEmpty() })
        assertTrue(units.all { it.layoutDegradation == LayoutDegradation.PAGE_TEXT_LOCAL_ONLY })
        // The 8500 UTF-16 bound still guards any slice that is actually attached.
        assertThrows(IllegalArgumentException::class.java) {
            DocumentUnitPlanner.splitTextSlices("x".repeat(8_501), 1)
        }
    }

    @Test fun capacitySizedTextIsPreservedLocallyWithoutFabricatedSlices() {
        val source = "x".repeat(DocumentUnitPlanner.MAX_UNITS_PER_PAGE * DocumentUnitPlanner.MAX_REQUEST_TEXT_CHARS)
        val units = plan(source)
        assertLosslessNoFabrication(source, units)
        assertTrue(units.all { it.effectiveRequestText().isEmpty() })
    }

    @Test fun individualRequestsPreserveUnicodeAcrossEncodingAndJsonRoundTrips() {
        val source = "甲".repeat(4_000) + "😀" + "乙".repeat(4_000)
        val units = plan(source, VisionRequestBudget(contextWindowTokens = 131_072))
        assertLosslessNoFabrication(source, units)
        val carrying = units.filter { it.effectiveRequestText().isNotEmpty() }
        assertEquals(1, carrying.size)
        assertEquals(source, Json.decodeFromString<String>(Json.encodeToString(carrying.single().effectiveRequestText())))
        units.forEach { assertEquals(source, Json.decodeFromString<String>(Json.encodeToString(it.nativeText))) }
    }

    @Test fun tinyUnicodeTextAllowsEmptySlicesForAdditionalImageRegions() {
        val source = "😀"
        val units = DocumentUnitPlanner().plan("tiny", listOf(PlanningPage(1, source, true, 4_096, 4_096)))
        assertTrue(units.size > 1)
        assertLosslessNoFabrication(source, units)
        assertEquals(1, units.count { it.effectiveRequestText().isNotEmpty() })
    }

    @Test fun malformedUnicodeIsRejectedBeforeDispatch() {
        val error = assertThrows(IllegalArgumentException::class.java) { plan("prefix\uD83Dsuffix") }
        assertTrue(error.message.orEmpty().startsWith("PIPELINE_INVALID_TEXT"))
    }

    @Test fun wideTablesSplitTheOverLimitDimensionWithoutDegenerateRegions() {
        val units = DocumentUnitPlanner().plan("wide", listOf(PlanningPage(
            1, "a|b|c\n1|2|3", true, width = 4_096, height = 2_000, tableHeader = "a|b|c",
        )))
        assertTrue(units.all {
            val r = it.coverage.region
            r.left < r.right && r.top < r.bottom &&
                (r.right - r.left).toLong() * 4_096 <= 2_048L * UnitRegion.SCALE
        })
        assertEquals(UnitRegion.SCALE.toLong() * UnitRegion.SCALE, units.sumOf {
            val r = it.coverage.region
            (r.right - r.left).toLong() * (r.bottom - r.top)
        })
        for (i in units.indices) for (j in 0 until i) {
            val a = units[i].coverage.region
            val b = units[j].coverage.region
            assertTrue(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
        }
        assertLosslessNoFabrication("a|b|c\n1|2|3", units)
    }

    @Test fun nativeOnlyLongTextDoesNotAcquireVisionLimitsOrRequests() {
        val text = "x".repeat(600_000)
        val unit = DocumentUnitPlanner().plan("native", listOf(PlanningPage(1, text, false))).single()
        assertFalse(unit.requiresVision)
        assertEquals(text, unit.nativeText)
    }

    @Test fun deterministicMixedUnicodeCorpusHasLosslessBoundedRequests() {
        val random = java.util.Random(7329)
        val alphabet = listOf("A", "甲", "\n", " ", "。", "😀", "𠀀", "\t")
        repeat(64) {
            val source = buildString {
                repeat(random.nextInt(20_000) + 1) { append(alphabet[random.nextInt(alphabet.size)]) }
            }
            assertLosslessNoFabrication(source, plan(source))
        }
    }
}
