// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

/**
 * Player/export projection: hides GM-only places, and hides any edge whose own visibility is hidden
 * or whose endpoint is hidden — even a public edge never leaks a hidden endpoint id. A current
 * marker that points at a hidden place is cleared, so no hidden id, count or label crosses here.
 */
object TrpgAtlasProjection {
    fun player(atlas: TrpgAtlas): TrpgAtlas {
        val locations = atlas.locations.filter { it.visibility == TrpgVisibility.PLAYER }
        val visible = locations.mapTo(mutableSetOf()) { it.id }
        val edges = atlas.edges.filter {
            it.visibility == TrpgVisibility.PLAYER && it.fromId in visible && it.toId in visible
        }
        return atlas.copy(
            locations = locations,
            edges = edges,
            currentLocationId = atlas.currentLocationId?.takeIf { it in visible },
        )
    }
}
