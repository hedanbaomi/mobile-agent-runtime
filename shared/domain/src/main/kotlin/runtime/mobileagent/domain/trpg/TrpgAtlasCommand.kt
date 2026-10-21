// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

import kotlinx.serialization.Serializable

@Serializable
enum class TrpgAtlasOperation { UPSERT_LOCATION, REMOVE_LOCATION, UPSERT_EDGE, REMOVE_EDGE, SET_CURRENT }

/**
 * One explicit atlas operation. Only the fields belonging to the chosen operation are accepted;
 * any other non-null field is rejected instead of being silently ignored.
 *
 * `SET_CURRENT` accepts a null [locationId] to clear the current marker.
 */
@Serializable
data class TrpgAtlasCommand(
    val operation: TrpgAtlasOperation,
    val location: TrpgLocation? = null,
    val edge: TrpgEdge? = null,
    val id: String? = null,
    val locationId: String? = null,
) {
    fun validate() {
        val present = buildSet {
            if (location != null) add("location")
            if (edge != null) add("edge")
            if (id != null) add("id")
            if (locationId != null) add("locationId")
        }
        val (allowed, required) = when (operation) {
            TrpgAtlasOperation.UPSERT_LOCATION -> setOf("location") to setOf("location")
            TrpgAtlasOperation.REMOVE_LOCATION -> setOf("id") to setOf("id")
            TrpgAtlasOperation.UPSERT_EDGE -> setOf("edge") to setOf("edge")
            TrpgAtlasOperation.REMOVE_EDGE -> setOf("id") to setOf("id")
            TrpgAtlasOperation.SET_CURRENT -> setOf("locationId") to emptySet()
        }
        require(present.all { it in allowed } && present.containsAll(required)) {
            "Unexpected or missing atlas operation fields"
        }
        when (operation) {
            TrpgAtlasOperation.UPSERT_LOCATION -> TrpgAtlasValidation.validate(requireNotNull(location))
            TrpgAtlasOperation.UPSERT_EDGE -> TrpgAtlasValidation.validate(requireNotNull(edge))
            TrpgAtlasOperation.REMOVE_LOCATION, TrpgAtlasOperation.REMOVE_EDGE ->
                TrpgAtlasValidation.id(requireNotNull(id))
            TrpgAtlasOperation.SET_CURRENT -> locationId?.let(TrpgAtlasValidation::id)
        }
    }
}
