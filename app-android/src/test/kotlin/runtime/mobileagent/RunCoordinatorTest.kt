// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.GrantPin
import runtime.mobileagent.domain.KnowledgePin
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunManifest
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.SkillPin
import runtime.mobileagent.skills.ToolSpec

/**
 * The run owner outlives any single UI page: prepare registers it, only it
 * may cancel, the frozen manifest is stamped once, and release drops it.
 * A page switch (a different owner key) must never change another run.
 */
class RunCoordinatorTest {
    private class JdbcConnection(url: String = "jdbc:sqlite::memory:") : SqlConnection, AutoCloseable {
        private val connection = DriverManager.getConnection(url).apply {
            createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        }

        override fun execute(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { stmt ->
                args.forEachIndexed { index, value -> stmt.setObject(index + 1, value) }
                stmt.execute()
            }
        }

        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            connection.prepareStatement(sql).use { stmt ->
                args.forEachIndexed { index, value -> stmt.setObject(index + 1, value) }
                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<SqlRow>()
                    while (rs.next()) {
                        val map = linkedMapOf<String, Any?>()
                        for (i in 1..rs.metaData.columnCount) map[rs.metaData.getColumnLabel(i)] = rs.getObject(i)
                        rows += SqlRow(map)
                    }
                    return rows
                }
            }
        }

        override fun <T> transaction(block: () -> T): T {
            val prev = connection.autoCommit
            connection.autoCommit = false
            return try {
                val result = block()
                connection.commit()
                result
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = prev
            }
        }

        override fun close() = connection.close()
    }

    private fun fixture(): Triple<RunCoordinator, RunRecord, JdbcConnection> {
        val db = JdbcConnection()
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(
            ProviderProfile(
                id = "provider-coord",
                name = "Coord",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "ref",
                revision = 1,
            ),
        )
        profiles.createModel(
            ModelProfile(
                id = "model-coord",
                providerId = "provider-coord",
                role = ModelRole.CHAT,
                modelId = "coord-model",
                capabilities = emptySet(),
                contextLimit = 1000,
                outputLimit = 100,
                revision = 1,
            ),
        )
        val agent = AgentRepository(db).saveWithPrompt(
            AgentProfile("agent-coord", "Coord", "initial", "model-coord", revision = 0),
            "Prompt",
        )
        val snapshot = AgentRepository(db).createSnapshot(agent.id, "snapshot-coord")
        val conversation = ConversationRepository(db).create(snapshot.id, "Coord", "conversation-coord")
        val record = RunRecord("run-coord", snapshot.id, conversation.id, createdAt = "2026-09-05T00:00:00Z")
        return Triple(RunCoordinator(RunRepository(db)), record, db)
    }

    private fun manifest(runId: String = "run-coord") = RunManifest(
        runId = runId,
        conversationId = "conversation-coord",
        snapshotId = "snapshot-coord",
        agentRevision = 0,
        promptRevisionId = "initial",
        skills = listOf(SkillPin("skill-a", "hash-a", 1)),
        knowledge = listOf(KnowledgePin("kb-a", "gen-a", "space-a")),
        grants = listOf(GrantPin("grant-a", 1)),
    )

    @Test
    fun prepareRegistersOwnerAndStampFreezesManifest() {
        val (coordinator, record, db) = fixture()
        try {
            val prepared = coordinator.prepare(record, "chat:conversation-coord")
            assertEquals("chat:conversation-coord", coordinator.ownerOf("run-coord"))
            assertEquals("{}", prepared.manifestJson)

            val stamped = coordinator.stampManifest("run-coord", manifest(), "2026-09-05T00:00:01Z")
            assertEquals(manifest(), RunManifest.fromJson(stamped.manifestJson))

            // Reload keeps the frozen facts: an old session's behavior change
            // stays explainable after process restart.
            val reloaded = RunRepository(db).get("run-coord")
            assertEquals(manifest(), reloaded?.let { RunManifest.fromJson(it.manifestJson) })
        } finally {
            db.close()
        }
    }

    @Test
    fun foreignOwnerCannotCancelOrStealTheRun() {
        val (coordinator, record, db) = fixture()
        try {
            coordinator.prepare(record, "chat:conversation-coord")
            assertFalse(coordinator.cancel("run-coord", "chat:other-conversation", "user-cancelled", "2026-09-05T00:00:01Z"))
            assertEquals(RunStatus.CREATED, RunRepository(db).get("run-coord")?.state)
            assertEquals("chat:conversation-coord", coordinator.ownerOf("run-coord"))

            assertTrue(coordinator.cancel("run-coord", "chat:conversation-coord", "user-cancelled", "2026-09-05T00:00:02Z"))
            assertEquals(RunStatus.CANCELLED, RunRepository(db).get("run-coord")?.state)

            coordinator.release("run-coord", "chat:other-conversation")
            assertEquals("chat:conversation-coord", coordinator.ownerOf("run-coord"))
            coordinator.release("run-coord", "chat:conversation-coord")
            assertNull(coordinator.ownerOf("run-coord"))
        } finally {
            db.close()
        }
    }

    @Test
    fun toolSchemaFingerprintIsStableAndSensitiveToContent() {
        val specs = listOf(
            ToolSpec("b-tool", "b", "{\"type\":\"object\"}", "cap", false),
            ToolSpec("a-tool", "a", "{\"type\":\"object\"}", "cap", false),
        )
        val reordered = listOf(specs[1], specs[0])
        assertEquals(RunCoordinator.toolSchemaFingerprint(specs), RunCoordinator.toolSchemaFingerprint(reordered))
        val changed = listOf(specs[0], specs[1].copy(parametersJson = "{\"type\":\"array\"}"))
        assertTrue(RunCoordinator.toolSchemaFingerprint(specs) != RunCoordinator.toolSchemaFingerprint(changed))
    }

    @Test
    fun modelTokenBudgetDefaultsToDisabled() {
        assertNull(RunCoordinator.modelTokenBudget("{\"maxModelRounds\":8}"))
        assertNull(RunCoordinator.modelTokenBudget("{\"maxModelTokens\":0}"))
        assertEquals(512, RunCoordinator.modelTokenBudget("{\"maxModelTokens\":512}"))
    }

    @Test
    fun manifestAssembledFromFrozenFactsKeepsExactSources() {
        // PreparedRunFacts is the single freeze point shared by the prompt
        // build and the manifest stamp: assembleManifest must propagate its
        // values verbatim instead of re-reading live state (b07 finding D).
        val facts = PreparedRunFacts(
            rootPrompt = "frozen root",
            rootPromptHash = RunCoordinator.sha256Hex("frozen root".toByteArray(Charsets.UTF_8)),
            skillPins = listOf(SkillPin("skill-a", "hash-a", 2)),
            skillInstructions = listOf("frozen instruction"),
            knowledgePins = listOf(KnowledgePin("kb-a", "gen-1", "space-a")),
            grants = listOf(GrantPin("grant-a", 4, revoked = true)),
            toolSchemaFingerprint = "fingerprint",
            retrievalScope = runtime.mobileagent.domain.RetrievalScopePin(
                requested = listOf("kb-a"),
                searched = listOf("kb-a"),
            ),
        )
        val manifest = RunCoordinator.assembleManifest(
            runId = "run-coord",
            conversationId = "conversation-coord",
            snapshotId = "snapshot-coord",
            agentRevision = 7,
            promptRevisionId = "initial",
            globalRootPromptHash = facts.rootPromptHash,
            providerId = "provider-coord",
            providerRevision = 1,
            modelId = "coord-model",
            modelRevision = 1,
            skills = facts.skillPins,
            knowledge = facts.knowledgePins,
            workspaceId = null,
            grants = facts.grants,
            policyVersion = 9,
            toolSchemaFingerprint = facts.toolSchemaFingerprint,
            budgetJson = "{\"maxModelRounds\":8}",
            retrievalPolicy = "automatic",
            modelTokenBudget = null,
            retrievalScope = facts.retrievalScope,
        )
        assertEquals(facts.rootPromptHash, manifest.globalRootPromptHash)
        assertEquals(facts.skillPins, manifest.skills)
        assertEquals(facts.knowledgePins, manifest.knowledge)
        assertEquals(facts.grants, manifest.grants)
        assertEquals(facts.toolSchemaFingerprint, manifest.toolSchemaFingerprint)
        assertEquals(facts.retrievalScope, manifest.retrievalScope)
        // The frozen prompt text itself never enters the manifest.
        assertFalse(manifest.toJson().contains("frozen root"))
        assertFalse(manifest.toJson().contains("frozen instruction"))
    }
}
