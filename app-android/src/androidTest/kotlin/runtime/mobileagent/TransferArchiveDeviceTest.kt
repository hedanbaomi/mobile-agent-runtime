// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.TransferRepository
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.serialization.TransferOptions
import runtime.mobileagent.storage.AndroidContextSqlite

/**
 * Regression for the device-only `Files.readString(Path)` crash. The archive is exported from one
 * on-device SQLite database and restored into a second, empty one, so the target really is a fresh
 * installation state instead of the same rows the export came from.
 */
class TransferArchiveDeviceTest {
    @Test
    fun conversationArchiveRestoresIntoAFreshInstallDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val sourceName = "r2-transfer-source-$suffix.db"
        val targetName = "r2-transfer-target-$suffix.db"
        val source = AndroidContextSqlite(context, sourceName)
        val target = AndroidContextSqlite(context, targetName)
        try {
            Migrations.apply(source)
            Migrations.apply(target)
            val providerId = "provider.transfer.$suffix"
            val modelId = "model.transfer.$suffix"
            val agentId = "agent.transfer.$suffix"
            val conversationId = "conversation.transfer.$suffix"
            val profiles = ProfileRepository(source)
            profiles.createProvider(
                ProviderProfile(
                    id = providerId,
                    name = "Transfer fixture",
                    apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                    baseUrl = "https://example.invalid/v1",
                    secretRef = "host-only-$suffix",
                    revision = 1,
                ),
            )
            profiles.createModel(
                ModelProfile(
                    id = modelId,
                    providerId = providerId,
                    role = ModelRole.CHAT,
                    modelId = "fixture-transfer",
                    capabilities = setOf("stream"),
                    contextLimit = 4096,
                    outputLimit = 512,
                    revision = 1,
                ),
            )
            AgentRepository(source).saveWithPrompt(
                AgentProfile(
                    id = agentId,
                    name = "Transfer fixture",
                    promptRevisionId = "pending",
                    chatProfileId = modelId,
                    revision = 0,
                ),
                "Transfer fixture prompt.",
            )
            val snapshot = AgentRepository(source).createSnapshot(agentId)
            ConversationRepository(source).create(snapshot.id, "Transfer conversation", conversationId)
            ConversationRepository(source).append(conversationId, MessageRole.USER, "portable device message")

            val archive = ByteArrayOutputStream()
            TransferRepository(source).exportArchive(agentId, TransferOptions(includeConversations = true), archive)
            assertTrue("the archive must carry the conversation entry", archive.size() > 0)

            val imported = TransferRepository(target).importArchive(archive.toByteArray())

            assertEquals(agentId, imported.agentId)
            assertEquals(
                "portable device message",
                ConversationRepository(target).messages(conversationId).single().text,
            )
        } finally {
            context.deleteDatabase(sourceName)
            context.deleteDatabase(targetName)
        }
    }
}
