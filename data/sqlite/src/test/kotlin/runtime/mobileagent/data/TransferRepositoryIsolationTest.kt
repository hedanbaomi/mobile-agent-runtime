// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.serialization.TransferArchiveLimits
import runtime.mobileagent.serialization.TransferOptions

class TransferRepositoryIsolationTest {
    @Test
    fun legacyAgentImportPreservesOtherAgentsSnapshotsAndConversations() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            createAgent(source, "old-backup")
            val legacyJson = TransferRepository(source).exportAgent("agent.old-backup")

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                createAgent(target, "unrelated")
                val agents = AgentRepository(target)
                val oldSnapshot = agents.createSnapshot("agent.unrelated", "snapshot.unrelated")
                val conversations = ConversationRepository(target)
                conversations.create(oldSnapshot.id, "Keep this session", "conversation.unrelated")
                conversations.append("conversation.unrelated", MessageRole.USER, "Keep this message")

                assertEquals("agent.old-backup", TransferRepository(target).importBundle(legacyJson).agentId)
                assertNotNull(agents.get("agent.unrelated"))
                assertEquals(oldSnapshot, agents.getSnapshot(oldSnapshot.id))
                assertEquals("Keep this message", conversations.messages("conversation.unrelated").single().text)
                assertEquals(1, target.query("SELECT COUNT(*) AS n FROM conversations").single().long("n"))

                assertThrows(AppException::class.java) {
                    TransferRepository(target).importBundle(legacyJson)
                }
                assertEquals(oldSnapshot, agents.getSnapshot(oldSnapshot.id))
                assertEquals("Keep this message", conversations.messages("conversation.unrelated").single().text)
            }
        }
    }

    @Test
    fun importRollsBackIfAnUnexpectedDatabaseSideEffectRemovesOtherHistory() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            createAgent(source, "old-backup")
            val legacyJson = TransferRepository(source).exportAgent("agent.old-backup")

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                createAgent(target, "unrelated")
                val snapshot = AgentRepository(target).createSnapshot("agent.unrelated", "snapshot.unrelated")
                ConversationRepository(target).create(snapshot.id, "Must survive", "conversation.unrelated")
                target.execute(
                    """
                    CREATE TRIGGER simulated_import_history_loss AFTER INSERT ON agent_profiles
                    WHEN NEW.id = 'agent.old-backup'
                    BEGIN
                        DELETE FROM conversations WHERE id = 'conversation.unrelated';
                        DELETE FROM agent_snapshots WHERE id = 'snapshot.unrelated';
                    END
                    """.trimIndent(),
                )

                val failure = assertThrows(AppException::class.java) {
                    TransferRepository(target).importBundle(legacyJson)
                }
                assertTrue(failure.message.orEmpty().contains("remove existing Agent history"))
                assertNotNull(AgentRepository(target).getSnapshot("snapshot.unrelated"))
                assertNotNull(ConversationRepository(target).get("conversation.unrelated"))
                assertEquals(null, AgentRepository(target).get("agent.old-backup"))
            }
        }
    }

    @Test
    fun importRollsBackIfOnlyAnUnrelatedTranscriptIsDeleted() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            createAgent(source, "old-backup")
            val legacyJson = TransferRepository(source).exportAgent("agent.old-backup")

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                createAgent(target, "unrelated")
                val snapshot = AgentRepository(target).createSnapshot("agent.unrelated", "snapshot.unrelated")
                ConversationRepository(target).create(snapshot.id, "Must survive", "conversation.unrelated")
                ConversationRepository(target).append("conversation.unrelated", MessageRole.USER, "Keep transcript")
                target.execute(
                    """
                    CREATE TRIGGER simulated_transcript_loss AFTER INSERT ON agent_profiles
                    WHEN NEW.id = 'agent.old-backup'
                    BEGIN
                        DELETE FROM message_parts WHERE message_id IN
                            (SELECT id FROM messages WHERE conversation_id = 'conversation.unrelated');
                        DELETE FROM messages WHERE conversation_id = 'conversation.unrelated';
                    END
                    """.trimIndent(),
                )

                val failure = assertThrows(AppException::class.java) { TransferRepository(target).importBundle(legacyJson) }
                assertTrue(failure.message.orEmpty().contains("remove existing Agent history"))
                assertEquals("Keep transcript", ConversationRepository(target).messages("conversation.unrelated").single().text)
                assertEquals(null, AgentRepository(target).get("agent.old-backup"))
            }
        }
    }

    @Test
    fun importRollsBackIfAnotherConversationIsReplacedAtTheSameCount() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            createAgent(source, "old-backup")
            val legacyJson = TransferRepository(source).exportAgent("agent.old-backup")

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                createAgent(target, "unrelated")
                val snapshot = AgentRepository(target).createSnapshot("agent.unrelated", "snapshot.unrelated")
                ConversationRepository(target).create(snapshot.id, "Must survive", "conversation.unrelated")
                target.execute(
                    """
                    CREATE TRIGGER simulated_same_count_history_loss AFTER INSERT ON agent_profiles
                    WHEN NEW.id = 'agent.old-backup'
                    BEGIN
                        DELETE FROM conversations WHERE id = 'conversation.unrelated';
                        INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at)
                        VALUES('conversation.replacement','snapshot.unrelated','snapshot.unrelated','Replacement','fixture','fixture');
                    END
                    """.trimIndent(),
                )

                val failure = assertThrows(AppException::class.java) { TransferRepository(target).importBundle(legacyJson) }
                assertTrue(failure.message.orEmpty().contains("remove existing Agent history"))
                assertNotNull(ConversationRepository(target).get("conversation.unrelated"))
                assertEquals(null, ConversationRepository(target).get("conversation.replacement"))
            }
        }
    }

    @Test
    fun archiveHistoryCanUseMatchingLocalCredentialsWithoutChangingFrozenSnapshot() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            createAgent(source, "portable")
            val sourceSnapshot = AgentRepository(source).createSnapshot("agent.portable", "snapshot.portable")
            ConversationRepository(source).create(sourceSnapshot.id, "Portable history", "conversation.portable")
            ConversationRepository(source).append("conversation.portable", MessageRole.USER, "Synthetic history")
            val output = ByteArrayOutputStream()
            TransferRepository(source).exportArchive(
                "agent.portable", TransferOptions(includeConversations = true), output,
            )
            val bytes = output.toByteArray()
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (zip.nextEntry != null) {
                    assertFalse(zip.readBytes().toString(Charsets.UTF_8).contains("source-only-ref"))
                    zip.closeEntry()
                }
            }

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                createAgent(target, "unrelated")
                val unrelatedSnapshot = AgentRepository(target).createSnapshot("agent.unrelated", "snapshot.unrelated")
                ConversationRepository(target).create(unrelatedSnapshot.id, "Other history", "conversation.unrelated")
                assertEquals("source-only-ref", TransferRepository(target).resolveRunBinding(unrelatedSnapshot.id).provider.secretRef)

                val result = TransferRepository(target).importArchive(bytes)
                assertTrue(result.warnings.any { it.contains("history") && it.contains("local credentials") })
                assertEquals("Synthetic history", ConversationRepository(target).messages("conversation.portable").single().text)
                assertNotNull(ConversationRepository(target).get("conversation.unrelated"))
                assertEquals(unrelatedSnapshot, AgentRepository(target).getSnapshot(unrelatedSnapshot.id))

                val agents = AgentRepository(target)
                val profiles = ProfileRepository(target)
                assertEquals("", profiles.getProvider("provider.portable")!!.secretRef)
                assertEquals("", agents.resolveSnapshot(sourceSnapshot.id).provider.secretRef)
                assertThrows(AppException::class.java) {
                    TransferRepository(target).resolveRunBinding(sourceSnapshot.id)
                }
                profiles.updateProvider(
                    profiles.getProvider("provider.portable")!!.copy(secretRef = "local-only-ref", revision = 2),
                )
                val rebound = TransferRepository(target).resolveRunBinding(sourceSnapshot.id)
                assertEquals("local-only-ref", rebound.provider.secretRef)
                assertEquals(sourceSnapshot.chatModelId, rebound.chatModel.id)
                assertEquals(sourceSnapshot.promptRevisionId, rebound.prompt.id)
                val newSnapshot = agents.createSnapshot("agent.portable", "snapshot.local")
                assertEquals("local-only-ref", agents.resolveSnapshot(newSnapshot.id).provider.secretRef)
                assertEquals("", agents.resolveSnapshot(sourceSnapshot.id).provider.secretRef)

                profiles.updateProvider(
                    profiles.getProvider("provider.portable")!!.copy(baseUrl = "https://other.invalid/v1", revision = 3),
                )
                assertThrows(AppException::class.java) {
                    TransferRepository(target).resolveRunBinding(sourceSnapshot.id)
                }

                assertThrows(AppException::class.java) { TransferRepository(target).importArchive(bytes) }
                assertNotNull(ConversationRepository(target).get("conversation.unrelated"))
                assertNotNull(ConversationRepository(target).get("conversation.portable"))
            }
        }
    }

    @Test
    fun conversationEntryAboveManifestLimitRoundTripsWithinContentEntryLimit() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            createAgent(source, "large")
            val snapshot = AgentRepository(source).createSnapshot("agent.large", "snapshot.large")
            ConversationRepository(source).create(snapshot.id, "Large synthetic history", "conversation.large")
            val random = Random(41L)
            repeat(300) { index ->
                val generated = ByteArray(22_500).also(random::nextBytes)
                ConversationRepository(source).append(
                    "conversation.large", MessageRole.USER, Base64.getEncoder().encodeToString(generated),
                    messageId = "message.large.$index",
                )
            }
            val archive = ByteArrayOutputStream()
            TransferRepository(source).exportArchive(
                "agent.large", TransferOptions(includeConversations = true), archive,
            )
            var contentBytes = 0
            ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.startsWith("conversations/")) contentBytes = zip.readBytes().size
                    zip.closeEntry()
                }
            }
            assertTrue(contentBytes > TransferArchiveLimits.MAX_METADATA_BYTES)
            assertTrue(contentBytes <= TransferArchiveLimits.MAX_ENTRY_BYTES)
            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                TransferRepository(target).importArchive(archive.toByteArray())
                assertEquals(300, ConversationRepository(target).messages("conversation.large").size)
            }
        }
    }

    private fun createAgent(db: SqlConnection, suffix: String) {
        ProfileRepository(db).createProvider(
            ProviderProfile(
                id = "provider.$suffix", name = "Provider $suffix", apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1", secretRef = "source-only-ref", revision = 1,
            ),
        )
        ProfileRepository(db).createModel(
            ModelProfile(
                id = "model.$suffix", providerId = "provider.$suffix", role = ModelRole.CHAT,
                modelId = "synthetic", capabilities = emptySet(), contextLimit = 4096,
                outputLimit = 512, revision = 1,
            ),
        )
        AgentRepository(db).saveWithPrompt(
            AgentProfile(
                id = "agent.$suffix", name = "Agent $suffix", promptRevisionId = "pending",
                chatProfileId = "model.$suffix", revision = 0,
            ),
            "Synthetic prompt",
        )
    }
}
