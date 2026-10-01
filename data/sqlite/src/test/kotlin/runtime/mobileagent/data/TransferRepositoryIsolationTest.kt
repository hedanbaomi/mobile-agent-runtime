// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
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
import runtime.mobileagent.domain.AuditEvent
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.serialization.TransferArchiveLimits
import runtime.mobileagent.serialization.TransferConflictPolicy
import runtime.mobileagent.serialization.TransferOptions

class TransferRepositoryIsolationTest {
    @Test
    fun archiveWritesManifestBeforeLoadingConversationHistory() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            createAgent(source, "streamed")
            val snapshot = AgentRepository(source).createSnapshot("agent.streamed", "snapshot.streamed")
            val conversationIds = (0 until 4).map { index ->
                val id = "conversation.streamed.$index"
                ConversationRepository(source).create(snapshot.id, "History $index", id)
                ConversationRepository(source).append(
                    id, MessageRole.USER, "preserved history $index", messageId = "message.streamed.$index",
                )
                id
            }

            val tracked = HistoryQueryTrackingConnection(source)
            val output = FirstWriteCheckingOutputStream { assertEquals(0, tracked.messageHistoryQueries) }
            TransferRepository(tracked).exportArchive(
                "agent.streamed", TransferOptions(includeConversations = true), output,
            )
            assertEquals(conversationIds.size, tracked.messageHistoryQueries)

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                TransferRepository(target).importArchive(output.toByteArray())
                conversationIds.forEachIndexed { index, id ->
                    assertEquals("preserved history $index", ConversationRepository(target).messages(id).single().text)
                }
            }
        }
    }

    @Test
    fun archiveRejectsRunAndAuditChangesBetweenConversationEntries() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            createAgent(source, "fenced")
            val snapshot = AgentRepository(source).createSnapshot("agent.fenced", "snapshot.fenced")
            val conversationRepository = ConversationRepository(source) { "2026-01-01T00:00:00Z" }
            val conversationA = "conversation.fence-a"
            val conversationB = "conversation.fence-b"
            conversationRepository.create(snapshot.id, "History A", conversationA)
            conversationRepository.create(snapshot.id, "History B", conversationB)

            val runRepository = RunRepository(source)
            val runA = RunRecord(
                runId = "run.fence-a", snapshotId = snapshot.id, conversationId = conversationA,
                state = RunStatus.CREATED, createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
            )
            val runB = RunRecord(
                runId = "run.fence-b", snapshotId = snapshot.id, conversationId = conversationB,
                state = RunStatus.CREATED, createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
            )
            runRepository.create(runA)
            runRepository.create(runB)
            val auditRepository = AuditRepository(source)
            auditRepository.append(AuditEvent(
                id = "audit.fence-a-before", runId = runA.runId, createdAt = runA.createdAt,
                component = "test", action = "before", result = "OK", summary = "A before export",
            ))
            auditRepository.append(AuditEvent(
                id = "audit.fence-b-before", runId = runB.runId, createdAt = runB.createdAt,
                component = "test", action = "before", result = "OK", summary = "B before export",
            ))

            val output = MutateAfterZipEntryOutputStream("conversations/$conversationA.json") {
                runRepository.save(runA.copy(state = RunStatus.FAILED)) // Keep updatedAt unchanged deliberately.
                runRepository.save(runB.copy(state = RunStatus.FAILED)) // Keep updatedAt unchanged deliberately.
                auditRepository.append(AuditEvent(
                    id = "audit.fence-a-after", runId = runA.runId, createdAt = "2026-01-01T00:00:01Z",
                    component = "test", action = "after", result = "FAILED", summary = "A changed during export",
                ))
                auditRepository.append(AuditEvent(
                    id = "audit.fence-b-after", runId = runB.runId, createdAt = "2026-01-01T00:00:01Z",
                    component = "test", action = "after", result = "FAILED", summary = "B changed during export",
                ))
            }

            val failure = assertThrows(AppException::class.java) {
                TransferRepository(source).exportArchive(
                    "agent.fenced", TransferOptions(includeConversations = true), output,
                )
            }

            assertTrue(output.mutationRan, "the fixture must mutate only after conversation A's ZIP entry closes")
            assertEquals(ErrorCode.TRANSFER_INVALID, failure.error.code)
            assertEquals(RetryClass.USER_ACTION, failure.error.retryClass)
            assertTrue(failure.message.orEmpty().contains("backup source changed"))
            assertEquals(RunStatus.FAILED, runRepository.get(runA.runId)?.state)
            assertEquals(RunStatus.FAILED, runRepository.get(runB.runId)?.state)
            assertEquals(2, auditRepository.list(runA.runId).size)
            assertEquals(2, auditRepository.list(runB.runId).size)
            assertEquals("2026-01-01T00:00:00Z", conversationRepository.get(conversationA)?.updatedAt)
            assertEquals("2026-01-01T00:00:00Z", conversationRepository.get(conversationB)?.updatedAt)
        }
    }

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
                assertFalse(result.warnings.any { it.contains("Release builds block cleartext requests") })
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

    @Test
    fun explicitKeepExistingImportPreservesTheLocalConfigurationAndConversationHistory() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            createAgent(source, "keep-existing")
            val snapshot = AgentRepository(source).createSnapshot("agent.keep-existing", "snapshot.keep-existing")
            ConversationRepository(source).create(snapshot.id, "Backed up", "conversation.keep-existing")
            ConversationRepository(source).append("conversation.keep-existing", MessageRole.USER, "backup history")
            val archive = ByteArrayOutputStream()
            TransferRepository(source).exportArchive(
                "agent.keep-existing", TransferOptions(includeConversations = true), archive,
            )

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                TransferRepository(target).importArchive(archive.toByteArray())
                val agents = AgentRepository(target)
                target.execute("UPDATE agent_profiles SET name=? WHERE id=?", listOf("Local configuration", "agent.keep-existing"))
                ConversationRepository(target).append("conversation.keep-existing", MessageRole.USER, "local history")
                val existingSnapshot = agents.getSnapshot(snapshot.id)

                val result = TransferRepository(target).importArchive(
                    archive.toByteArray(), TransferConflictPolicy.KEEP_EXISTING,
                )

                assertEquals("Local configuration", agents.get("agent.keep-existing")!!.name)
                assertEquals(existingSnapshot, agents.getSnapshot(snapshot.id))
                assertEquals(
                    listOf("backup history", "local history"),
                    ConversationRepository(target).messages("conversation.keep-existing").map { it.text },
                )
                assertTrue(result.warnings.any { it.contains("Agent") && it.contains("kept local configuration") })
                assertTrue(result.warnings.any { it.contains("kept local history") })
            }
        }
    }

    @Test
    fun httpConversationHistoryWarnsAndKeepsItsHistoricalDestinationFrozen() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            createAgent(source, "http-history")
            val profiles = ProfileRepository(source)
            val sourceProvider = profiles.getProvider("provider.http-history")!!
            profiles.updateProvider(sourceProvider.copy(baseUrl = "http://127.0.0.1:8765/v1", revision = 2))
            val snapshot = AgentRepository(source).createSnapshot("agent.http-history", "snapshot.http-history")
            ConversationRepository(source).create(snapshot.id, "HTTP history", "conversation.http-history")
            ConversationRepository(source).append("conversation.http-history", MessageRole.USER, "kept as history")
            val archive = ByteArrayOutputStream()
            TransferRepository(source).exportArchive(
                "agent.http-history", TransferOptions(includeConversations = true), archive,
            )

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                val repository = TransferRepository(target)
                val result = repository.importArchive(archive.toByteArray())
                assertTrue(result.warnings.any { it.contains("Release builds block cleartext requests") })

                val agents = AgentRepository(target)
                val frozen = agents.getSnapshot(snapshot.id)!!
                assertEquals("http://127.0.0.1:8765/v1", agents.resolveSnapshot(snapshot.id).provider.baseUrl)
                assertEquals("kept as history", ConversationRepository(target).messages("conversation.http-history").single().text)

                val provider = ProfileRepository(target).getProvider("provider.http-history")!!
                ProfileRepository(target).updateProvider(
                    provider.copy(secretRef = "local-http-key", revision = provider.revision + 1),
                )
                assertEquals("http://127.0.0.1:8765/v1", repository.resolveRunBinding(snapshot.id).provider.baseUrl)
                val withCredentials = ProfileRepository(target).getProvider("provider.http-history")!!
                ProfileRepository(target).updateProvider(
                    withCredentials.copy(baseUrl = "https://api.example.invalid/v1", revision = withCredentials.revision + 1),
                )
                val changedDestination = assertThrows(AppException::class.java) {
                    repository.resolveRunBinding(snapshot.id)
                }
                assertTrue(changedDestination.message.orEmpty().contains("destination differs"))
                assertEquals(frozen, agents.getSnapshot(snapshot.id))

                val successor = agents.createSnapshot("agent.http-history", "snapshot.https-successor")
                assertEquals("https://api.example.invalid/v1", agents.resolveSnapshot(successor.id).provider.baseUrl)
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

    private class HistoryQueryTrackingConnection(private val delegate: SqlConnection) : SqlConnection {
        var messageHistoryQueries: Int = 0
            private set

        override fun execute(sql: String, args: List<Any?>) = delegate.execute(sql, args)

        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            if (sql.contains("FROM messages WHERE conversation_id", ignoreCase = true)) messageHistoryQueries++
            return delegate.query(sql, args)
        }

        override fun <T> transaction(block: () -> T): T = delegate.transaction(block)
    }

    private class FirstWriteCheckingOutputStream(private val beforeFirstWrite: () -> Unit) : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        private var checked = false

        override fun write(value: Int) {
            checkBeforeWrite()
            bytes.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkBeforeWrite()
            bytes.write(buffer, offset, length)
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()

        private fun checkBeforeWrite() {
            if (checked) return
            checked = true
            beforeFirstWrite()
        }
    }

    /** Inject a committed source edit only after the selected deflated ZIP entry is closed. */
    private class MutateAfterZipEntryOutputStream(
        private val targetEntryName: String,
        private val afterEntry: () -> Unit,
    ) : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        var mutationRan: Boolean = false
            private set

        override fun write(value: Int) {
            bytes.write(value)
            mutateAfterCompletedEntry()
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytes.write(buffer, offset, length)
            mutateAfterCompletedEntry()
        }

        private fun mutateAfterCompletedEntry() {
            // JDK ZIP writes may split headers and descriptors across single
            // bytes and arrays. Read the bounded fixture prefix with the ZIP
            // reader instead of assuming a particular write chunk boundary.
            if (mutationRan) return
            val targetComplete = runCatching {
                ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
                    val buffer = ByteArray(1024)
                    var entry = zip.nextEntry
                    while (entry != null) {
                        while (zip.read(buffer) >= 0) { /* Validate through the entry's descriptor/CRC. */ }
                        if (entry.name == targetEntryName) return@use true
                        entry = zip.nextEntry
                    }
                    false
                }
            }.getOrDefault(false)
            if (targetComplete) {
                mutationRan = true
                afterEntry()
            }
        }
    }
}
