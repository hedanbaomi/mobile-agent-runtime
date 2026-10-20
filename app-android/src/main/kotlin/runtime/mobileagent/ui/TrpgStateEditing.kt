// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import runtime.mobileagent.domain.trpg.TrpgResource
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.domain.trpg.TrpgStateValidation

/** A display maximum is not a hard cap (temporary HP and custom rules may exceed it). */
internal fun adjustTrpgResource(resource: TrpgResource, delta: Int): TrpgResource =
    resource.copy(current = (resource.current.toLong() + delta).coerceIn(-1_000_000L, 1_000_000L).toInt())

internal fun addTrpgResource(state: TrpgState, name: String, current: String, maximum: String): TrpgState {
    val value = requireNotNull(current.trim().toIntOrNull()) { "Current value must be a valid integer" }
    val max = maximum.trim().takeIf { it.isNotEmpty() }?.let {
        requireNotNull(it.toIntOrNull()) { "Maximum must be blank or a valid integer" }
    }
    return state.copy(resources = state.resources + TrpgResource(name.trim(), value, max))
        .also(TrpgStateValidation::validate)
}

internal fun appendTrpgNote(state: TrpgState, text: String): TrpgState =
    state.copy(notes = if (state.notes.isBlank()) text else "${state.notes}\n$text")
        .also(TrpgStateValidation::validate)
