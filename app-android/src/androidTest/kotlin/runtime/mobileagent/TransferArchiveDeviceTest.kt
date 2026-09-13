// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.serialization.TransferConflictPolicy
import runtime.mobileagent.serialization.TransferOptions

/**
 * Regression for the device-only `Files.readString(Path)` crash.
 *
 * The host JVM implements that method, so `:data:sqlite:test` stayed green while importing a
 * backup that contained conversations crashed on a real Android runtime with
 * `NoSuchMethodError`. The archive must be readable through the bounded stream path on device.
 */
class TransferArchiveDeviceTest {
    @Test
    fun conversationArchiveImportsOnDeviceWithoutHostOnlyFileApis() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val providerId = "provider.transfer.$suffix"
        val modelId = "model.transfer.$suffix"
        val agentId = "agent.transfer.$suffix"
        val conversationId = "conversation.transfer.$suffix"

        container.profiles.createProvider(
            ProviderProfile(
                id = providerId,
                name = "Transfer fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-transfer-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
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
        container.agents.saveWithPrompt(
            AgentProfile(
                id = agentId,
                name = "Transfer fixture",
                promptRevisionId = "pending",
                chatProfileId = modelId,
                revision = 0,
            ),
            "Transfer fixture prompt.",
        )
        val snapshot = container.agents.createSnapshot(agentId)
        val conversation = container.conversations.create(snapshot.id, "Transfer conversation", conversationId)
        container.conversations.append(conversation.id, MessageRole.USER, "portable device message")

        val output = ByteArrayOutputStream()
        container.transfer.exportArchive(agentId, TransferOptions(includeConversations = true), output)
        assertTrue("the archive must contain a conversation entry", output.size() > 0)

        // Importing into the same installation reaches the staged conversation read before the
        // existing-row handling, which is exactly where the device crash happened.
        val imported = container.transfer.importArchive(
            output.toByteArray(),
            TransferConflictPolicy.KEEP_EXISTING,
        )

        assertEquals(agentId, imported.agentId)
        assertTrue(imported.warnings.isNotEmpty())
        assertEquals(
            "portable device message",
            container.conversations.messages(conversation.id).single().text,
        )
    }
}