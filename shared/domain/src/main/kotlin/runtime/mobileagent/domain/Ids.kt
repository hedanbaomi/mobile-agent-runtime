// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.util.UUID

@JvmInline
value class EntityId(val value: String) {
    init {
        require(value.isNotBlank()) { "id must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun random(): EntityId = EntityId(UUID.randomUUID().toString())
    }
}

object Utc {
    fun nowIso(): String = java.time.Instant.now().toString()
}
