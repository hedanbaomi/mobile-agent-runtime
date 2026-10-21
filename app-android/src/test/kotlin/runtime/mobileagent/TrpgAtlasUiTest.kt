// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.trpg.TrpgAtlas
import runtime.mobileagent.domain.trpg.TrpgEdge
import runtime.mobileagent.domain.trpg.TrpgLocation
import runtime.mobileagent.domain.trpg.TrpgVisibility
import runtime.mobileagent.ui.AtlasEdgeDraft
import runtime.mobileagent.ui.AtlasLocationDraft
import runtime.mobileagent.ui.atlasEditorSubmitRevision
import runtime.mobileagent.ui.atlasForRole
import runtime.mobileagent.ui.atlasFraction
import runtime.mobileagent.ui.atlasPinCenter
import runtime.mobileagent.ui.atlasPinTopLeft
import runtime.mobileagent.ui.atlasUnitAt
import runtime.mobileagent.ui.buildAtlasEdge
import runtime.mobileagent.ui.buildAtlasLocation
import runtime.mobileagent.ui.parseAtlasInt
import runtime.mobileagent.ui.reconcileAtlas
import runtime.mobileagent.ui.validateAtlasEdgeDraft
import runtime.mobileagent.ui.validateAtlasLocationDraft

class TrpgAtlasUiTest {
    private val village = TrpgLocation("village", "Village", x = 120, y = 800)
    private val keep = TrpgLocation("keep", "Keep", x = 700, y = 240)
    private val secret = TrpgLocation("secret", "Secret", x = 500, y = 500, visibility = TrpgVisibility.GAME_MASTER)
    private val atlas = TrpgAtlas(
        locations = listOf(village, keep, secret),
        edges = listOf(TrpgEdge("road", "village", "keep")),
        currentLocationId = "keep",
    )

    @Test fun unitFractionCoversTheFixedRangeIncludingBothEdges() {
        assertEquals(0f, atlasFraction(-25), 0f)
        assertEquals(0f, atlasFraction(0), 0f)
        assertEquals(0.5f, atlasFraction(500), 0f)
        assertEquals(1f, atlasFraction(1000), 0f)
        assertEquals(1f, atlasFraction(1001), 0f)
    }

    @Test fun tapsMapToUnitsAndClampOutsideTheSurface() {
        assertEquals(0 to 0, atlasUnitAt(0f, 0f, 100f, 100f))
        assertEquals(1000 to 1000, atlasUnitAt(100f, 100f, 100f, 100f))
        assertEquals(500 to 250, atlasUnitAt(50f, 25f, 100f, 100f))
        assertEquals(0 to 0, atlasUnitAt(-5f, -5f, 100f, 100f))
        assertEquals(1000 to 1000, atlasUnitAt(200f, 200f, 100f, 100f))
    }

    @Test fun anUnmeasuredSurfaceNeverProducesCoordinates() {
        assertNull(atlasUnitAt(10f, 10f, 0f, 100f))
        assertNull(atlasUnitAt(10f, 10f, 100f, 0f))
        assertNull(atlasUnitAt(10f, 10f, -1f, -1f))
    }

    @Test fun fractionalSurfacesRoundToTheNearestUnit() {
        assertEquals(500 to 500, atlasUnitAt(1.5f, 1.5f, 3f, 3f))
        assertEquals(667 to 333, atlasUnitAt(1f, 1f, 1.5f, 3f))
    }

    @Test fun pinLayoutStaysFullyInsideTheSurfaceAtEveryEdge() {
        assertEquals(0f, atlasPinTopLeft(0, 0, 1000f, 1000f, 48f).x, 0f)
        assertEquals(0f, atlasPinTopLeft(0, 0, 1000f, 1000f, 48f).y, 0f)
        assertEquals(952f, atlasPinTopLeft(1000, 1000, 1000f, 1000f, 48f).x, 0f)
        assertEquals(952f, atlasPinTopLeft(1000, 1000, 1000f, 1000f, 48f).y, 0f)
        // Centered on half the surface: 500 - 24 = 476.
        assertEquals(476f, atlasPinTopLeft(500, 500, 1000f, 1000f, 48f).x, 0f)
        // Near the far edge the pin is pushed back inside instead of being clipped.
        assertEquals(952f, atlasPinTopLeft(999, 999, 1000f, 1000f, 48f).y, 0f)
        assertEquals(0f, atlasPinTopLeft(1, 1, 1000f, 1000f, 48f).x, 0f)
    }

    @Test fun pinLayoutDegradesGracefullyWhenTheSurfaceIsSmallerThanAPin() {
        val tiny = atlasPinTopLeft(1000, 1000, 40f, 30f, 48f)
        assertEquals(0f, tiny.x, 0f)
        assertEquals(0f, tiny.y, 0f)
        // The center still follows the true fraction for edge drawing.
        assertEquals(40f, atlasPinCenter(1000, 1000, 40f, 30f).x, 0f)
        assertEquals(30f, atlasPinCenter(1000, 1000, 40f, 30f).y, 0f)
    }

    @Test fun roleFilterHidesGameMasterPlacesAndTheirEdges() {
        val player = atlasForRole(atlas, gameMasterView = false)
        assertEquals(listOf("village", "keep"), player.locations.map { it.id })
        assertEquals(listOf("road"), player.edges.map { it.id })
        assertEquals("keep", player.currentLocationId)
        assertTrue(player.locations.none { it.id == "secret" })

        val leaky = atlas.copy(edges = atlas.edges + TrpgEdge("peek", "village", "secret"))
        assertTrue(atlasForRole(leaky, gameMasterView = false).edges.none { it.id == "peek" })
        assertEquals(2, atlasForRole(leaky, gameMasterView = true).edges.size)
    }

    @Test fun draftParsingKeepsEmptyAndSignsInsteadOfCoercing() {
        assertNull(parseAtlasInt(""))
        assertNull(parseAtlasInt("   "))
        assertNull(parseAtlasInt("-"))
        assertNull(parseAtlasInt("+"))
        assertNull(parseAtlasInt("abc"))
        assertNull(parseAtlasInt("1.5"))
        assertEquals(-5, parseAtlasInt("-5"))
        assertEquals(42, parseAtlasInt(" 42 "))
        assertNull(parseAtlasInt("99999999999999999999"))
    }

    @Test fun locationDraftRejectsBadCoordinatesButNeverClampsThem() {
        val base = AtlasLocationDraft("id", "Village", "", "120", "800", TrpgVisibility.PLAYER)
        assertNull(validateAtlasLocationDraft(base, chinese = false))
        assertEquals(120, buildAtlasLocation(base)?.x)
        assertEquals(800, buildAtlasLocation(base)?.y)

        assertTrue(validateAtlasLocationDraft(base.copy(x = "-5"), chinese = false) != null)
        assertTrue(validateAtlasLocationDraft(base.copy(y = "1001"), chinese = false) != null)
        assertTrue(validateAtlasLocationDraft(base.copy(x = ""), chinese = false) != null)
        assertTrue(validateAtlasLocationDraft(base.copy(y = "+"), chinese = false) != null)
        assertTrue(validateAtlasLocationDraft(base.copy(title = "   "), chinese = false) != null)
        assertTrue(validateAtlasLocationDraft(base.copy(description = "d".repeat(2049)), chinese = false) != null)
        assertNull(buildAtlasLocation(base.copy(x = "1001")))
        // Rejection keeps the typed value intact; no silent rewrite happened.
        assertEquals("1001", base.copy(x = "1001").x)
        assertNull(validateAtlasLocationDraft(base.copy(x = "1000", y = "0"), chinese = false))
    }

    @Test fun edgeDraftRequiresTwoDistinctRealEndpoints() {
        val ids = setOf("village", "keep")
        val draft = AtlasEdgeDraft("e1", "village", "keep", " road ", TrpgVisibility.PLAYER)
        assertNull(validateAtlasEdgeDraft(draft, ids, chinese = false))
        assertEquals("road", buildAtlasEdge(draft, ids)?.label)
        assertTrue(validateAtlasEdgeDraft(draft.copy(toId = "missing"), ids, chinese = false) != null)
        assertTrue(validateAtlasEdgeDraft(draft.copy(fromId = ""), ids, chinese = false) != null)
        assertTrue(validateAtlasEdgeDraft(draft.copy(toId = "village"), ids, chinese = false) != null)
        assertTrue(validateAtlasEdgeDraft(draft.copy(label = "l".repeat(129)), ids, chinese = false) != null)
        assertNull(buildAtlasEdge(draft.copy(toId = "village"), ids))
    }

    @Test fun anEditorMayOnlySaveAgainstTheRevisionItOpenedWith() {
        assertEquals(4L, atlasEditorSubmitRevision(4L, 4L))
        assertNull(atlasEditorSubmitRevision(4L, 5L))
    }

    @Test fun reconcileAdoptsNewerAtlasesOnlyWhenNoDraftIsOpen() {
        val newer = atlas.copy(currentLocationId = "village")
        val closed = reconcileAtlas(atlas, 4, newer, 5, editorOpen = false)
        assertEquals(newer, closed.atlas)
        assertEquals(5L, closed.baseRevision)
        assertFalse(closed.conflict)

        val open = reconcileAtlas(atlas, 4, newer, 5, editorOpen = true)
        assertEquals(atlas, open.atlas)
        assertEquals(4L, open.baseRevision)
        assertTrue(open.conflict)

        val same = reconcileAtlas(atlas, 5, newer, 5, editorOpen = false)
        assertEquals(atlas, same.atlas)
        assertFalse(same.conflict)
    }

    @Test fun basemapReaderStopsAtTheByteCapInsteadOfBufferingEverything() {
        val small = ByteArray(10)
        assertEquals(10, TrpgAtlasBasemap.readCapped(ByteArrayInputStream(small), maxBytes = 16)?.size)
        assertNull(TrpgAtlasBasemap.readCapped(ByteArrayInputStream(ByteArray(20)), maxBytes = 16))
        assertTrue(TrpgAtlasBasemap.MAX_BYTES == 8 * 1024 * 1024)
        assertTrue(TrpgAtlasBasemap.MAX_EDGE == 2048)
    }
}
