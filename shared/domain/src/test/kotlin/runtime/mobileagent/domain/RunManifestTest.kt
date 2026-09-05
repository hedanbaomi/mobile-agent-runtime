// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunManifestTest {
    private fun manifest() = RunManifest(
        runId = "run-1",
        conversationId = "conversation-1",
        snapshotId = "snapshot-1",
        agentRevision = 4,
        promptRevisionId = "prompt-9",
        globalRootPromptHash = "ab".repeat(32),
        providerId = "provider-1",
        providerRevision = 2,
        modelId = "model-x",
        modelRevision = 3,
        skills = listOf(SkillPin("skill-b", "hash-b", 2), SkillPin("skill-a", "hash-a", 1)),
        knowledge = listOf(KnowledgePin("kb-1", "gen-1", "space-1"), KnowledgePin("kb-2", null, "space-2")),
        workspaceId = "workspace-1",
        grants = listOf(GrantPin("grant-2", 2, true), GrantPin("grant-1", 1, false)),
        policyVersion = 7,
        toolSchemaFingerprint = "cd".repeat(32),
        budgetJson = "{\"maxModelRounds\":8}",
        retrievalPolicy = "automatic",
        modelTokenBudget = null,
    )

    @Test
    fun roundTripPreservesEveryFrozenFact() {
        val restored = RunManifest.fromJson(manifest().toJson())
        assertEquals(manifest(), restored)
    }

    @Test
    fun manifestCarriesNoSecretsPathsOrPrivateReasoning() {
        val json = manifest().toJson()
        listOf("sk-", "content://", "/storage/", "/data/", "encrypted", "token", "secret", "serial").forEach { token ->
            assertTrue(!json.contains(token, ignoreCase = true), "manifest must not contain $token: $json")
        }
    }

    @Test
    fun blankIdentityAndBadBudgetAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(runId = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(budgetJson = "[1,2]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(modelTokenBudget = -1)
        }
    }

    @Test
    fun disabledModelBudgetIsNull() {
        assertNull(manifest().modelTokenBudget)
        assertEquals(512, manifest().copy(modelTokenBudget = 512).modelTokenBudget)
    }

    @Test
    fun emptyHelperMarksPreDispatchRuns() {
        val empty = RunManifest.empty("run-9", "conversation-9", "snapshot-9", "prompt-1")
        assertEquals("{}", empty.budgetJson)
        assertTrue(empty.skills.isEmpty() && empty.grants.isEmpty())
        assertEquals(empty, RunManifest.fromJson(empty.toJson()))
    }
}
