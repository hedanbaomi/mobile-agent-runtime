// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.AppSettings
import runtime.mobileagent.domain.LocalePreference
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ThemePreference
import runtime.mobileagent.domain.TextPart
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.sha256Hex
import runtime.mobileagent.serialization.TransferConflictPolicy
import runtime.mobileagent.serialization.TransferBundle
import runtime.mobileagent.serialization.TransferCodec
import runtime.mobileagent.serialization.KnowledgeTransfer
import runtime.mobileagent.serialization.BlobTransfer
import runtime.mobileagent.serialization.SchemaVersion
import runtime.mobileagent.serialization.TransferOptions

class ProductDataRepositoryTest {
    @Test
    fun agentPromptSnapshotAndConversationUseFrozenTypedData() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            profiles.createProvider(
                ProviderProfile(
                    id = "provider.one",
                    name = "Local",
                    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                    baseUrl = "https://example.invalid/v1",
                    secretRef = "host-secret-ref",
                    revision = 1,
                ),
            )
            profiles.createModel(
                ModelProfile(
                    id = "model.chat",
                    providerId = "provider.one",
                    role = ModelRole.CHAT,
                    modelId = "chat-v1",
                    capabilities = setOf("stream"),
                    parameterSchemaJson = "{\"temperature\":{\"type\":\"number\"}}",
                    contextLimit = 8_000,
                    outputLimit = 1_024,
                    revision = 1,
                    parametersJson = "{\"temperature\":0.2}",
                ),
            )
            val agents = AgentRepository(db)
            val saved = agents.saveWithPrompt(
                profile = runtime.mobileagent.domain.AgentProfile(
                    id = "agent.one",
                    name = "Agent One",
                    promptRevisionId = "initial",
                    chatProfileId = "model.chat",
                    revision = 0,
                    parameterOverridesJson = "{\"temperature\":0.4}",
                ),
                template = "You are {{agent_name}}.",
            )
            assertEquals(1, saved.revision)
            assertEquals("You are {{agent_name}}.", agents.promptRevision(saved.promptRevisionId)?.template)

            val snapshot = agents.createSnapshot(saved.id, "snapshot.one", "2026-08-29T00:00:00Z")
            profiles.updateModel(profiles.getModel("model.chat")!!.copy(modelId = "chat-v2", revision = 2))
            val binding = agents.resolveSnapshot(snapshot.id)
            assertEquals("chat-v1", binding.chatModel.modelId)
            assertEquals("{\"temperature\":0.2}", binding.chatModel.parametersJson)
            assertEquals("You are {{agent_name}}.", binding.prompt.template)

            val conversations = ConversationRepository(db)
            val conversation = conversations.create(snapshot.id, "Conversation one", "conversation.one")
            conversations.appendMessage(
                Message(
                    id = "message.one",
                    conversationId = conversation.id,
                    role = MessageRole.USER,
                    text = "hello",
                    status = "COMPLETE",
                    createdAt = "2026-08-29T00:00:01Z",
                    parts = listOf(TextPart("hello"), ImagePart("asset.one", "image/png")),
                ),
            )
            val stored = conversations.messages(conversation.id).single()
            assertEquals(2, stored.parts.size)
            assertEquals("asset.one", (stored.parts[1] as ImagePart).assetId)
            val assistant = conversations.append(
                conversation.id,
                MessageRole.ASSISTANT,
                text = "",
                status = "STREAMING",
                messageId = "assistant.one",
                createdAt = "2026-08-29T00:00:01Z",
            )
            assertEquals("partial", conversations.checkpointAssistant(assistant.id, "partial", listOf(TextPart("partial"))).text)
            val completed = conversations.checkpointAssistant(assistant.id, "done", listOf(TextPart("done")), status = "COMPLETE")
            assertEquals("done", completed.text)
            assertEquals("done", conversations.checkpointAssistant(assistant.id, "late", emptyList(), status = "STREAMING").text)
            conversations.append(
                conversation.id,
                MessageRole.ASSISTANT,
                text = "",
                status = "STREAMING",
                messageId = "assistant.pending",
                createdAt = "2026-08-29T00:00:02Z",
            )

            val runs = RunRepository(db)
            runs.create(
                RunRecord(
                    runId = "run.one",
                    snapshotId = snapshot.id,
                    conversationId = conversation.id,
                    state = RunStatus.MODEL_STREAMING,
                    createdAt = "2026-08-29T00:00:02Z",
                ),
            )
            assertEquals(listOf("run.one"), runs.markInFlightUnknown("2026-08-29T00:00:03Z"))
            assertEquals(RunStatus.UNKNOWN_OUTCOME, runs.get("run.one")!!.state)
            assertEquals("UNKNOWN_OUTCOME", conversations.message("assistant.pending")!!.status)
            assertThrows(AppException::class.java) {
                runs.save(runs.get("run.one")!!.copy(state = RunStatus.FAILED))
            }
            assertThrows(AppException::class.java) { runs.acknowledgeUnknown("run.one", false) }
            assertTrue(runs.acknowledgeUnknown("run.one", true).stopReason!!.contains("Retry acknowledged"))
            assertEquals("2026-08-29T00:00:04Z", runs.acknowledgeUnknown("run.one", true, "2026-08-29T00:00:04Z").retryAcknowledgedAt)
        }
    }

    @Test
    fun settingsAndReferenceProtectionAreDurable() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val settings = SettingsRepository(db)
            assertEquals(AppSettings(), settings.get())
            settings.set(AppSettings(ThemePreference.DARK, LocalePreference.EN_US))
            assertEquals(ThemePreference.DARK, settings.theme())
            assertEquals(LocalePreference.EN_US, settings.locale())

            val profiles = ProfileRepository(db)
            profiles.createProvider(
                ProviderProfile("provider.one", "Local", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid", secretRef = "ref", revision = 1),
            )
            profiles.createModel(
                ModelProfile("model.chat", "provider.one", ModelRole.CHAT, "chat", emptySet(), contextLimit = 1_000, outputLimit = 100, revision = 1),
            )
            val agents = AgentRepository(db)
            val agent = agents.saveWithPrompt(
                runtime.mobileagent.domain.AgentProfile("agent.one", "Agent", "initial", "model.chat", revision = 0),
                "Prompt",
            )
            val snapshot = agents.createSnapshot(agent.id, "snapshot.one")
            assertFalse(profiles.deleteModel("model.chat"))
            assertFalse(profiles.deleteProvider("provider.one"))
            assertTrue(settings.get().theme == ThemePreference.DARK)
            assertEquals(snapshot.id, agents.getSnapshot(snapshot.id)?.id)
        }
    }

    @Test
    fun agentTransferIsSecretFreeAndTransactional() {
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            val profiles = ProfileRepository(source)
            profiles.createProvider(
                ProviderProfile("provider.transfer", "Transfer", ApiFormat.OPENAI_COMPATIBLE,
                    "https://example.invalid/v1", secretRef = "secret-local-marker", revision = 1),
            )
            profiles.createModel(
                ModelProfile("model.transfer", "provider.transfer", ModelRole.CHAT, "chat-transfer", emptySet(),
                    contextLimit = 1_000, outputLimit = 100, revision = 1, parametersJson = "{\"temperature\":0.3}"),
            )
            val agent = AgentRepository(source).saveWithPrompt(
                runtime.mobileagent.domain.AgentProfile("agent.transfer", "Transfer Agent", "unused", "model.transfer", revision = 0),
                "A local prompt",
            )
            val raw = TransferRepository(source).exportAgent(agent.id)
            assertFalse(raw.contains("secret-local-marker"))

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                val result = TransferRepository(target).importBundle(raw, TransferConflictPolicy.REJECT)
                assertEquals(agent.id, result.agentId)
                assertEquals("", ProfileRepository(target).getProvider("provider.transfer")!!.secretRef)
                assertEquals("{\"temperature\":0.3}", ProfileRepository(target).getModel("model.transfer")!!.parametersJson)

                // A conflicting second import must not leave a partially written agent/provider.
                val conflicting = raw.replace("Transfer Agent", "Different Agent")
                assertThrows(AppException::class.java) { TransferRepository(target).importBundle(conflicting) }
                assertEquals("Transfer Agent", AgentRepository(target).get(agent.id)!!.name)
            }
        }
    }

    @Test
    fun fullArchiveStreamsContentAndRestoresPortableConversation() {
        val bytes = "portable document".toByteArray()
        val hash = sha256Hex(bytes)
        val sourceSink = MemoryBlobSink().also { it.put(bytes, "text/plain") }
        JdbcSqlConnection().use { source ->
            Migrations.apply(source)
            source.execute(
                "INSERT INTO knowledge_bases(id,name,active_generation_id,embedding_space_id,created_at,deleted_at) VALUES(?,?,?,?,?,NULL)",
                listOf("kb.archive", "Archive", null, null, "2026-08-29T00:00:00Z"),
            )
            source.execute(
                "INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,?)",
                listOf(hash, bytes.size, "text/plain", "memory:$hash", 1),
            )
            source.execute(
                "INSERT INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES(?,?,?,?,?,?,NULL)",
                listOf("doc.archive", "kb.archive", hash, "Document", "text/plain", null),
            )
            val profiles = ProfileRepository(source)
            profiles.createProvider(ProviderProfile("provider.archive", "Archive", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid", secretRef = "host-only", revision = 1))
            profiles.createModel(ModelProfile("model.archive", "provider.archive", ModelRole.CHAT, "chat", emptySet(), contextLimit = 1_000, outputLimit = 100, revision = 1))
            val agent = AgentRepository(source).saveWithPrompt(
                runtime.mobileagent.domain.AgentProfile("agent.archive", "Archive Agent", "unused", "model.archive", knowledgeBaseIds = listOf("kb.archive"), revision = 0),
                "Archive prompt",
            )
            val snapshot = AgentRepository(source).createSnapshot(agent.id, "snapshot.archive", "2026-08-29T00:00:00Z")
            val conversation = ConversationRepository(source).create(snapshot.id, "Archived conversation", "conversation.archive", "2026-08-29T00:00:01Z")
            ConversationRepository(source).append(conversation.id, MessageRole.USER, "hello", messageId = "message.archive", createdAt = "2026-08-29T00:00:02Z")

            val output = ByteArrayOutputStream()
            TransferRepository(source, blobSink = sourceSink).exportArchive(
                agent.id,
                TransferOptions(includeKnowledgeContent = true, includeConversations = true),
                output,
            )
            assertTrue(output.size() > bytes.size)

            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                val targetSink = MemoryBlobSink()
                val imported = TransferRepository(target, blobSink = targetSink).importArchive(output.toByteArray())
                assertEquals(agent.id, imported.agentId)
                assertEquals(bytes.toList(), targetSink.get(hash)!!.toList())
                assertEquals("", ProfileRepository(target).getProvider("provider.archive")!!.secretRef)
                assertEquals("hello", ConversationRepository(target).messages(conversation.id).single().text)
                assertTrue(AgentRepository(target).getSnapshot(snapshot.id)!!.bindingManifestJson.contains("LOCAL_CREDENTIALS_REQUIRED"))
            }
        }
    }

    @Test
    fun fullArchiveRequiresExplicitBlobSink() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            assertThrows(AppException::class.java) {
                TransferRepository(db).exportArchive(
                    "missing-agent",
                    TransferOptions(includeKnowledgeContent = true),
                    ByteArrayOutputStream(),
                )
            }
        }
    }

    @Test
    fun archiveCompressionRatioIsBoundedWhenZipSizeUsesDataDescriptor() {
        val payload = ByteArray(1024 * 1024)
        val hash = sha256Hex(payload)
        val bundle = TransferBundle(
            schemaVersion = SchemaVersion.CURRENT,
            exportedAt = "2026-08-29T00:00:00Z",
            knowledgeBases = listOf(
                KnowledgeTransfer(
                    id = "kb.bomb",
                    name = "Bomb",
                    blobs = listOf(BlobTransfer(hash, payload.size.toLong(), "application/octet-stream")),
                    contentIncluded = true,
                ),
            ),
        )
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(TransferCodec.encode(bundle).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("blobs/$hash"))
            zip.write(payload)
            zip.closeEntry()
        }
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            assertThrows(AppException::class.java) {
                TransferRepository(db, blobSink = MemoryBlobSink()).importArchive(archive.toByteArray())
            }
            assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM knowledge_bases").single().long("n"))
        }
    }
}
