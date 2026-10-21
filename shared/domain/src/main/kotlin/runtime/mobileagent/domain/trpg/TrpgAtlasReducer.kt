// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

/**
 * Deterministic atlas transitions. Removing a location also removes every edge that touched it and
 * clears a current marker pointing at it, so the result never holds a dangling reference.
 */
object TrpgAtlasReducer {
    fun apply(atlas: TrpgAtlas, command: TrpgAtlasCommand): TrpgAtlas {
        command.validate()
        val next = when (command.operation) {
            TrpgAtlasOperation.UPSERT_LOCATION -> {
                val value = requireNotNull(command.location)
                val exists = atlas.locations.any { it.id == value.id }
                require(exists || atlas.locations.size < TrpgAtlasValidation.MAX_LOCATIONS) { "Too many locations" }
                atlas.copy(locations = upsert(atlas.locations, value) { it.id })
            }
            TrpgAtlasOperation.REMOVE_LOCATION -> {
                val id = requireNotNull(command.id)
                require(atlas.locations.any { it.id == id }) { "Location is missing" }
                atlas.copy(
                    locations = atlas.locations.filterNot { it.id == id },
                    edges = atlas.edges.filterNot { it.fromId == id || it.toId == id },
                    currentLocationId = atlas.currentLocationId?.takeIf { it != id },
                )
            }
            TrpgAtlasOperation.UPSERT_EDGE -> {
                val value = requireNotNull(command.edge)
                val locations = atlas.locations.mapTo(mutableSetOf()) { it.id }
                require(value.fromId in locations && value.toId in locations) { "Edge endpoint is missing" }
                val exists = atlas.edges.any { it.id == value.id }
                require(exists || atlas.edges.size < TrpgAtlasValidation.MAX_EDGES) { "Too many edges" }
                atlas.copy(edges = upsert(atlas.edges, value) { it.id })
            }
            TrpgAtlasOperation.REMOVE_EDGE -> {
                val id = requireNotNull(command.id)
                require(atlas.edges.any { it.id == id }) { "Edge is missing" }
                atlas.copy(edges = atlas.edges.filterNot { it.id == id })
            }
            TrpgAtlasOperation.SET_CURRENT -> {
                val id = command.locationId
                if (id == null) atlas.copy(currentLocationId = null) else {
                    require(atlas.locations.any { it.id == id }) { "Current location is missing" }
                    atlas.copy(currentLocationId = id)
                }
            }
        }
        TrpgAtlasValidation.validate(next)
        return next
    }

    private fun <T> upsert(items: List<T>, value: T, id: (T) -> String): List<T> =
        if (items.any { id(it) == id(value) }) items.map { if (id(it) == id(value)) value else it } else items + value
}
