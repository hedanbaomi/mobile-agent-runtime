// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.trpg.*

class TrpgAtlasTest {
    private val village = TrpgLocation("village", "Village", "A quiet village", x = 120, y = 800)
    private val keep = TrpgLocation("keep", "Keep", "An old keep", x = 700, y = 240)
    private val cave = TrpgLocation("cave", "Cave", "Hidden behind the falls", x = 500, y = 500,
        visibility = TrpgVisibility.GAME_MASTER)
    private val road = TrpgEdge("road", "village", "keep", "King's road")
    private val tunnel = TrpgEdge("tunnel", "keep", "cave", "Secret tunnel")
    private val atlas = TrpgAtlas(
        locations = listOf(village, keep, cave),
        edges = listOf(road, tunnel),
        currentLocationId = "village",
    )
    private val strict = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }

    private fun apply(value: TrpgAtlas, command: TrpgAtlasCommand) = TrpgAtlasReducer.apply(value, command)

    @Test fun defaultAtlasIsEmptyAndValid() {
        val empty = TrpgAtlas()
        TrpgAtlasValidation.validate(empty)
        assertTrue(empty.locations.isEmpty() && empty.edges.isEmpty())
        assertNull(empty.currentLocationId)
        assertEquals("{}", Json.encodeToString(empty))
        assertEquals(empty, Json.decodeFromString<TrpgAtlas>("{}"))
    }

    @Test fun atlasRoundTripsThroughJsonAndRejectsUnknownKeys() {
        val decoded = Json.decodeFromString<TrpgAtlas>(Json.encodeToString(atlas))
        assertEquals(atlas, decoded)
        assertThrows(SerializationException::class.java) {
            strict.decodeFromString<TrpgAtlas>("{\"legacy\":true}")
        }
    }

    @Test fun duplicateIdsAreRejected() {
        val duplicateLocations = atlas.copy(locations = listOf(village, keep, village.copy(title = "Other")))
        assertThrows(IllegalArgumentException::class.java) { TrpgAtlasValidation.validate(duplicateLocations) }
        val duplicateEdges = atlas.copy(edges = listOf(road, road.copy(label = "Other")))
        assertThrows(IllegalArgumentException::class.java) { TrpgAtlasValidation.validate(duplicateEdges) }
    }

    @Test fun coordinatesOutsideTheFixedUnitRangeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrpgAtlasValidation.validate(village.copy(x = -1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgAtlasValidation.validate(village.copy(y = TrpgAtlasValidation.MAP_UNITS + 1))
        }
        // Both bounds stay legal and survive validation unchanged.
        TrpgAtlasValidation.validate(village.copy(x = 0, y = TrpgAtlasValidation.MAP_UNITS))
    }

    @Test fun danglingAndSelfReferencingEdgesAreRejected() {
        val dangling = atlas.copy(edges = listOf(road.copy(toId = "missing")))
        assertThrows(IllegalArgumentException::class.java) { TrpgAtlasValidation.validate(dangling) }
        val selfLoop = TrpgEdge("loop", "keep", "keep")
        assertThrows(IllegalArgumentException::class.java) { TrpgAtlasValidation.validate(selfLoop) }
    }

    @Test fun textLimitsMatchTheDocumentedMaximums() {
        TrpgAtlasValidation.validate(village.copy(title = "t".repeat(TrpgAtlasValidation.MAX_TITLE)))
        assertThrows(IllegalArgumentException::class.java) {
            TrpgAtlasValidation.validate(village.copy(title = "t".repeat(TrpgAtlasValidation.MAX_TITLE + 1)))
        }
        TrpgAtlasValidation.validate(village.copy(description = "d".repeat(TrpgAtlasValidation.MAX_DESCRIPTION)))
        assertThrows(IllegalArgumentException::class.java) {
            TrpgAtlasValidation.validate(village.copy(description = "d".repeat(TrpgAtlasValidation.MAX_DESCRIPTION + 1)))
        }
        TrpgAtlasValidation.validate(road.copy(label = "l".repeat(TrpgAtlasValidation.MAX_LABEL)))
        assertThrows(IllegalArgumentException::class.java) {
            TrpgAtlasValidation.validate(road.copy(label = "l".repeat(TrpgAtlasValidation.MAX_LABEL + 1)))
        }
    }

    @Test fun upsertReplacesByIdWithoutMutatingTheInput() {
        val renamed = apply(atlas, TrpgAtlasCommand(
            TrpgAtlasOperation.UPSERT_LOCATION, location = keep.copy(title = "New Keep")))
        assertEquals(listOf("village", "keep", "cave"), renamed.locations.map { it.id })
        assertEquals("New Keep", renamed.locations.single { it.id == "keep" }.title)
        assertEquals("Keep", atlas.locations.single { it.id == "keep" }.title)
        assertEquals(3, atlas.locations.size)
    }

    @Test fun removingALocationAlsoRemovesItsEdgesAndClearsCurrent() {
        val pruned = apply(atlas, TrpgAtlasCommand(TrpgAtlasOperation.REMOVE_LOCATION, id = "keep"))
        assertEquals(listOf("village", "cave"), pruned.locations.map { it.id })
        assertTrue(pruned.edges.isEmpty())
        assertEquals("village", pruned.currentLocationId)

        val currentRemoved = apply(atlas, TrpgAtlasCommand(TrpgAtlasOperation.REMOVE_LOCATION, id = "village"))
        assertNull(currentRemoved.currentLocationId)
        assertEquals(listOf("tunnel"), currentRemoved.edges.map { it.id })
        TrpgAtlasValidation.validate(pruned)
        TrpgAtlasValidation.validate(currentRemoved)
    }

    @Test fun removingAnEdgeKeepsBothLocations() {
        val pruned = apply(atlas, TrpgAtlasCommand(TrpgAtlasOperation.REMOVE_EDGE, id = "road"))
        assertEquals(listOf("tunnel"), pruned.edges.map { it.id })
        assertEquals(3, pruned.locations.size)
    }

    @Test fun currentLocationMustBeRealAndCanBeCleared() {
        val moved = apply(atlas, TrpgAtlasCommand(TrpgAtlasOperation.SET_CURRENT, locationId = "keep"))
        assertEquals("keep", moved.currentLocationId)
        val cleared = apply(atlas, TrpgAtlasCommand(TrpgAtlasOperation.SET_CURRENT, locationId = null))
        assertNull(cleared.currentLocationId)
        assertThrows(IllegalArgumentException::class.java) {
            apply(atlas, TrpgAtlasCommand(TrpgAtlasOperation.SET_CURRENT, locationId = "missing"))
        }
    }

    @Test fun missingLocationsAndEdgesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            apply(atlas, TrpgAtlasCommand(TrpgAtlasOperation.REMOVE_LOCATION, id = "missing"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            apply(atlas, TrpgAtlasCommand(TrpgAtlasOperation.REMOVE_EDGE, id = "missing"))
        }
    }

    @Test fun edgesRequireRealEndpointsAtApplyTime() {
        assertThrows(IllegalArgumentException::class.java) {
            apply(atlas, TrpgAtlasCommand(TrpgAtlasOperation.UPSERT_EDGE,
                edge = TrpgEdge("dangling", "village", "missing")))
        }
        val added = apply(TrpgAtlas(), TrpgAtlasCommand(TrpgAtlasOperation.UPSERT_LOCATION, location = village))
        assertThrows(IllegalArgumentException::class.java) {
            apply(added, TrpgAtlasCommand(TrpgAtlasOperation.UPSERT_EDGE,
                edge = TrpgEdge("dangling", "village", "missing")))
        }
    }

    @Test fun locationAndEdgeCapsAreEnforcedOnlyForNewIds() {
        val full = atlas.copy(
            locations = (1..TrpgAtlasValidation.MAX_LOCATIONS).map { TrpgLocation("l$it", "L$it", x = 0, y = 0) },
            edges = emptyList(),
            currentLocationId = null,
        )
        TrpgAtlasValidation.validate(full)
        assertThrows(IllegalArgumentException::class.java) {
            apply(full, TrpgAtlasCommand(TrpgAtlasOperation.UPSERT_LOCATION,
                location = TrpgLocation("extra", "Extra", x = 0, y = 0)))
        }
        val updated = apply(full, TrpgAtlasCommand(TrpgAtlasOperation.UPSERT_LOCATION,
            location = TrpgLocation("l1", "Renamed", x = 0, y = 0)))
        assertEquals("Renamed", updated.locations.first { it.id == "l1" }.title)

        val fullEdges = atlas.copy(
            locations = listOf(village, keep),
            edges = (1..TrpgAtlasValidation.MAX_EDGES).map { TrpgEdge("e$it", "village", "keep") },
        )
        TrpgAtlasValidation.validate(fullEdges)
        assertThrows(IllegalArgumentException::class.java) {
            apply(fullEdges, TrpgAtlasCommand(TrpgAtlasOperation.UPSERT_EDGE,
                edge = TrpgEdge("extra", "village", "keep")))
        }
    }

    @Test fun commandRejectsFieldsBelongingToAnotherOperation() {
        assertThrows(IllegalArgumentException::class.java) {
            TrpgAtlasCommand(TrpgAtlasOperation.UPSERT_LOCATION, location = village, id = "village").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgAtlasCommand(TrpgAtlasOperation.REMOVE_LOCATION, id = "village", location = village).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgAtlasCommand(TrpgAtlasOperation.SET_CURRENT, locationId = "village", edge = road).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrpgAtlasCommand(TrpgAtlasOperation.UPSERT_EDGE, edge = road, locationId = "village").validate()
        }
        TrpgAtlasCommand(TrpgAtlasOperation.SET_CURRENT).validate()
    }

    @Test fun commandJsonIsClosedAndMatchesTheTypedShape() {
        val encoded = Json.encodeToString(TrpgAtlasCommand(TrpgAtlasOperation.REMOVE_EDGE, id = "road"))
        assertEquals("""{"operation":"REMOVE_EDGE","id":"road"}""", encoded)
        assertEquals(TrpgAtlasCommand(TrpgAtlasOperation.REMOVE_EDGE, id = "road"),
            Json.decodeFromString<TrpgAtlasCommand>(encoded))
        assertThrows(SerializationException::class.java) {
            strict.decodeFromString<TrpgAtlasCommand>("{\"operation\":\"REMOVE_EDGE\",\"id\":\"x\",\"title\":\"nope\"}")
        }
    }

    @Test fun playerProjectionHidesHiddenLocationsAndTheirEdges() {
        val player = TrpgAtlasProjection.player(atlas)
        assertEquals(listOf("village", "keep"), player.locations.map { it.id })
        assertEquals(listOf("road"), player.edges.map { it.id })
        assertEquals("village", player.currentLocationId)
    }

    @Test fun publicEdgeTouchingAHiddenLocationIsHiddenToo() {
        val leaky = atlas.copy(edges = listOf(
            road,
            TrpgEdge("public-secret", "village", "cave", "Path", TrpgVisibility.PLAYER),
        ))
        val player = TrpgAtlasProjection.player(leaky)
        assertEquals(listOf("road"), player.edges.map { it.id })
        assertTrue(player.edges.none { it.id == "public-secret" })
        val encoded = Json.encodeToString(player)
        assertFalse(encoded.contains("cave"))
        assertFalse(encoded.contains("public-secret"))
    }

    @Test fun hiddenCurrentLocationIsClearedForPlayers() {
        val hiddenCurrent = atlas.copy(currentLocationId = "cave")
        assertNull(TrpgAtlasProjection.player(hiddenCurrent).currentLocationId)
    }

    @Test fun projectionIsIdempotentAndKeepsTheFullAtlasForTheHost() {
        val player = TrpgAtlasProjection.player(atlas)
        assertEquals(player, TrpgAtlasProjection.player(player))
        assertEquals(atlas, atlas)
        assertEquals(3, atlas.locations.size)
    }
}
