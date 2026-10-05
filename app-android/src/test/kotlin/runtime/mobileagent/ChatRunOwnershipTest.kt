// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChatRunOwnershipTest {
    @Test fun timeoutDetachesPageUntilExecutionFinishesEvenWhenReturningToSameConversation() {
        val ownership = ChatRunOwnership()
        val a = ownership.begin("A")
        assertTrue(ownership.canProject(a, "A"))
        ownership.detach(a)
        assertEquals(a, ownership.active)
        assertFalse(ownership.canProject(a, "B"))
        assertFalse(ownership.canProject(a, "A"))
        assertThrows(IllegalStateException::class.java) { ownership.begin("B") }
        assertTrue(ownership.finish(a))
        assertNull(ownership.active)
    }

    @Test fun staleCompletionCannotReleaseAnotherRunOrItsProjection() {
        val ownership = ChatRunOwnership()
        val a = ownership.begin("same-conversation")
        ownership.finish(a)
        val b = ownership.begin("same-conversation")
        assertNotEquals(a.runId, b.runId)
        assertTrue(b.generation > a.generation)
        ownership.detach(a)
        assertFalse(ownership.finish(a))
        assertEquals(b, ownership.active)
        assertFalse(ownership.canProject(a, "same-conversation"))
        assertTrue(ownership.canProject(b, "same-conversation"))
    }

    @Test fun oldForegroundOwnerCannotStopAnotherRun() {
        val owners = ChatForegroundOwners()
        owners.register("A")
        owners.register("B")
        assertFalse(owners.release("A"))
        assertFalse(owners.release("A"))
        assertTrue(owners.release("B"))
        assertFalse(owners.release("B"))
    }
}
