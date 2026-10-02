// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile

class AgentSnapshotDisplayProjectionTest {
    @Test
    fun emptyConversationListDoesNotQuerySnapshots() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val counted = CountingConnection(db)
            assertEquals(emptyMap<String, String>(), AgentRepository(counted).snapshotAgentIds(emptyList()))
            assertTrue(counted.queries.isEmpty())
        }
    }

    @Test
    fun duplicateMissingAndSqlLikeIdsRemainBoundData() {
        JdbcSqlConnection().use { db ->
            val agents = fixture(db)
            agents.createSnapshot("agent.one", "snapshot.one")
            agents.createSnapshot("agent.two", "snapshot.two")
            val counted = CountingConnection(db)
            val sqlLikeId = "snapshot.one') OR 1=1 --"
            val actual = AgentRepository(counted).snapshotAgentIds(
                listOf("snapshot.one", "snapshot.two", "snapshot.one", "missing", sqlLikeId),
            )
            assertEquals(mapOf("snapshot.one" to "agent.one", "snapshot.two" to "agent.two"), actual)
            assertEquals(1, counted.queries.size)
            assertEquals(listOf("snapshot.one", "snapshot.two", "missing", sqlLikeId), counted.queries.single().second)
            assertTrue(!counted.queries.single().first.contains(sqlLikeId))
            assertEquals(2, db.query("SELECT id FROM agent_snapshots").size)
        }
    }

    @Test
    fun largeConversationListsMatchFullSnapshotsWithBoundedQueries() {
        JdbcSqlConnection().use { db ->
            val agents = fixture(db)
            val ids = (0 until 1_201).map { "snapshot.batch.$it" }
            db.transaction {
                ids.forEachIndexed { index, id ->
                    agents.createSnapshot(if (index % 2 == 0) "agent.one" else "agent.two", id)
                }
            }
            val counted = CountingConnection(db)
            val measuredAgents = AgentRepository(counted)
            val fullSnapshotIds = ids.associateWith { measuredAgents.getSnapshot(it)!!.agentId }
            assertEquals(1_201, counted.queries.size)
            counted.queries.clear()
            counted.returnedColumnSets.clear()

            val projection = measuredAgents.snapshotAgentIds(ids + ids.take(25))
            assertEquals(fullSnapshotIds, projection)
            assertEquals(3, counted.queries.size)
            assertTrue(counted.queries.all { (_, args) -> args.size <= 500 })
            assertTrue(counted.returnedColumnSets.all { it == setOf("id", "agent_id") })
        }
    }

    @Test
    fun displayProjectionDoesNotDecodeUnrelatedMetadataOrChangeExecutionValidation() {
        JdbcSqlConnection().use { db ->
            val agents = fixture(db)
            agents.createSnapshot("agent.one", "snapshot.corrupt")
            db.execute(
                "UPDATE agent_snapshots SET knowledge_base_ids=? WHERE id=?",
                listOf("invalid-json", "snapshot.corrupt"),
            )
            assertEquals(
                mapOf("snapshot.corrupt" to "agent.one"),
                agents.snapshotAgentIds(listOf("snapshot.corrupt")),
            )
            assertThrows(AppException::class.java) { agents.getSnapshot("snapshot.corrupt") }
            assertThrows(AppException::class.java) { agents.resolveSnapshot("snapshot.corrupt") }
        }
    }

    private fun fixture(db: SqlConnection): AgentRepository {
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(
            ProviderProfile(
                id = "provider.projection",
                name = "Projection fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "host-only-fixture",
                revision = 1,
            ),
        )
        profiles.createModel(
            ModelProfile(
                id = "model.projection",
                providerId = "provider.projection",
                role = ModelRole.CHAT,
                modelId = "fixture",
                capabilities = emptySet(),
                contextLimit = 1_000,
                outputLimit = 100,
                revision = 1,
            ),
        )
        return AgentRepository(db).also { agents ->
            listOf("agent.one", "agent.two").forEach { id ->
                agents.saveWithPrompt(
                    AgentProfile(
                        id = id,
                        name = id,
                        promptRevisionId = "initial",
                        chatProfileId = "model.projection",
                        revision = 0,
                    ),
                    "Fixture prompt",
                )
            }
        }
    }

    private class CountingConnection(private val delegate: SqlConnection) : SqlConnection by delegate {
        val queries = mutableListOf<Pair<String, List<Any?>>>()
        val returnedColumnSets = mutableListOf<Set<String>>()

        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            queries += sql to args.toList()
            return delegate.query(sql, args).also { rows ->
                returnedColumnSets += rows.map { it.columns.keys }
            }
        }
    }
}
