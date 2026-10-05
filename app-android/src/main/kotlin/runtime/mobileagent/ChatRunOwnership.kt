// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import runtime.mobileagent.agent.RuntimeEvent
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.skills.ToolCall

/** Execution ownership survives a timeout; permission to update a page does not. */
internal data class ChatRunOwner(val runId: String, val conversationId: String, val generation: Long)

internal class ChatRunOwnership {
    private var generation = 0L
    var active: ChatRunOwner? = null
        private set
    private var projection: ChatRunOwner? = null

    fun begin(conversationId: String): ChatRunOwner {
        check(active == null) { "Previous run is still finishing" }
        return ChatRunOwner(EntityId.random().value, conversationId, ++generation).also {
            active = it
            projection = it
        }
    }

    fun canProject(owner: ChatRunOwner, selectedConversationId: String?): Boolean =
        projection == owner && active == owner && selectedConversationId == owner.conversationId

    fun detach(owner: ChatRunOwner) {
        if (projection == owner) projection = null
    }

    fun finish(owner: ChatRunOwner): Boolean {
        if (active != owner) return false
        detach(owner)
        active = null
        return true
    }
}

/** Internal runtime seam for deterministic deadline/late-event integration tests. */
internal class ChatRunExecution(
    val awaitWatchdog: suspend (Long) -> Unit = { delay(it) },
    val approvalReady: (suspend (ToolCall) -> Boolean) -> Unit = {},
    val collectEvents: suspend (Flow<RuntimeEvent>, suspend (RuntimeEvent) -> Unit) -> Unit =
        { events, accept -> events.collect { accept(it) } },
)

/** A finishing run cannot stop the foreground service used by another run. */
internal class ChatForegroundOwners {
    private val owners = mutableSetOf<String>()

    @Synchronized
    fun register(owner: String) { owners += owner }

    @Synchronized
    fun release(owner: String): Boolean = owners.remove(owner) && owners.isEmpty()
}
