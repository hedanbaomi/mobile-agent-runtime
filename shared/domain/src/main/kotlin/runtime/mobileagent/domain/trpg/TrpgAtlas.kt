// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

import kotlinx.serialization.Serializable

/**
 * A named place on the atlas. [x]/[y] are abstract map units in `0..1000` (never pixels), so the
 * same atlas renders at any screen size. Both coordinates are required: an upsert replaces the
 * whole location, and a missing pair would silently move an existing pin to the origin.
 * Visibility is presentation only; it never grants tool authority and is filtered again by
 * [TrpgAtlasProjection].
 */
@Serializable
data class TrpgLocation(
    val id: String,
    val title: String,
    val description: String = "",
    val x: Int,
    val y: Int,
    val visibility: TrpgVisibility = TrpgVisibility.PLAYER,
)

/**
 * A labelled link between two real places. Endpoints must exist in the same atlas and must differ;
 * removing an endpoint removes the link instead of leaving it dangling.
 */
@Serializable
data class TrpgEdge(
    val id: String,
    val fromId: String,
    val toId: String,
    val label: String = "",
    val visibility: TrpgVisibility = TrpgVisibility.PLAYER,
)

/** Default-empty, command-owned atlas state. [currentLocationId] is null or a real location id. */
@Serializable
data class TrpgAtlas(
    val locations: List<TrpgLocation> = emptyList(),
    val edges: List<TrpgEdge> = emptyList(),
    val currentLocationId: String? = null,
)

object TrpgAtlasValidation {
    const val MAX_ID = 128
    const val MAX_TITLE = 128
    const val MAX_DESCRIPTION = 2048
    const val MAX_LABEL = 128
    const val MAX_LOCATIONS = 32
    const val MAX_EDGES = 64

    /** Fixed coordinate space for every atlas; UI code converts to/from this range only. */
    const val MAP_UNITS = 1000

    fun id(value: String) {
        require(value.isNotBlank() && value.length <= MAX_ID) { "Invalid atlas entity id" }
    }

    fun validate(location: TrpgLocation) {
        id(location.id)
        require(location.title.isNotBlank() && location.title.length <= MAX_TITLE) { "Invalid location title" }
        require(location.description.length <= MAX_DESCRIPTION) { "Location description is too long" }
        require(location.x in 0..MAP_UNITS && location.y in 0..MAP_UNITS) { "Location must stay within 0..$MAP_UNITS" }
    }

    fun validate(edge: TrpgEdge) {
        id(edge.id)
        id(edge.fromId)
        id(edge.toId)
        require(edge.label.length <= MAX_LABEL) { "Edge label is too long" }
        require(edge.fromId != edge.toId) { "An edge needs two different locations" }
    }

    fun validate(atlas: TrpgAtlas) {
        require(atlas.locations.size <= MAX_LOCATIONS) { "Too many locations" }
        require(atlas.edges.size <= MAX_EDGES) { "Too many edges" }
        require(atlas.locations.map { it.id }.distinct().size == atlas.locations.size) { "Duplicate location id" }
        require(atlas.edges.map { it.id }.distinct().size == atlas.edges.size) { "Duplicate edge id" }
        atlas.locations.forEach(::validate)
        atlas.edges.forEach(::validate)
        val locations = atlas.locations.mapTo(mutableSetOf()) { it.id }
        atlas.edges.forEach { require(it.fromId in locations && it.toId in locations) { "Edge endpoint is missing" } }
        atlas.currentLocationId?.let { require(it in locations) { "Current location is missing" } }
    }
}
