// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.DiffPart
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageErrorCode
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.PromptRevision
import runtime.mobileagent.domain.ReasoningPart

class TransferCodecTest {
    private val packageHash = "0".repeat(64)

    @Test
    fun conversationTransferRoundTripsNewMessageParts() {
        val conversation = Conversation("conversation.one", "snapshot.one", "Conversation", "now", "now")
        val transfer = ConversationTransfer(
            conversation = conversation,
            snapshot = AgentSnapshot(
                id = "snapshot.one",
                schemaVersion = SchemaVersion.CURRENT,
                agentId = "agent.one",
                promptRevisionId = "prompt.one",
                chatModelId = "model.one",
                providerRevision = 1,
                knowledgeBaseIds = emptyList(),
                skillIds = emptyList(),
                createdAt = "now",
                providerId = "provider.one",
            ),
            messages = listOf(
                Message(
                    id = "message.one",
                    conversationId = conversation.id,
                    role = MessageRole.ASSISTANT,
                    status = "COMPLETE",
                    createdAt = "now",
                    parts = listOf(
                        ReasoningPart("verified provider reasoning"),
                        DiffPart("Updated one file", "diff --git a/src/Main.kt b/src/Main.kt"),
                        ErrorPart(MessageErrorCode.TIMEOUT, "The request timed out", retryable = true),
                    ),
                ),
            ),
        )

        val decoded = TransferCodec.decodeConversation(TransferCodec.encodeConversation(transfer))

        assertEquals(transfer, decoded)
    }

    @Test
    fun conversationTransferRejectsUnboundedOrAbsoluteDiffParts() {
        assertThrows(IllegalArgumentException::class.java) {
            DiffPart("changed", patchPreview = "C:\\private\\Main.kt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReasoningPart("x".repeat(64 * 1024 + 1))
        }
    }

    @Test
    fun roundTripKeepsSecretFreeSkillMetadata() {
        val bundle = TransferBundle(
            schemaVersion = SchemaVersion.CURRENT,
            exportedAt = "2026-08-29T00:00:00Z",
            skills = listOf(
                SkillTransfer(
                    packageHash = packageHash,
                    id = "skill.one",
                    name = "One",
                    version = "1.0.0",
                    licenseId = "AGPL-3.0-only",
                    classification = "safe",
                ),
            ),
        )
        val decoded = TransferCodec.decode(TransferCodec.encode(bundle))
        assertEquals(bundle, decoded)
    }

    @Test
    fun unknownFieldsAndSecretsAreRejected() {
        val unknown = """
            {"schemaVersion":1,"exportedAt":"now","skills":[{"packageHash":"$packageHash","id":"skill.one","name":"One","version":"1","licenseId":"AGPL-3.0-only","classification":"safe","unexpected":true}]}
        """.trimIndent()
        val unknownError = assertThrows(AppException::class.java) { TransferCodec.decode(unknown) }
        assertEquals(ErrorCode.TRANSFER_INVALID, unknownError.error.code)

        val secret = """
            {"schemaVersion":1,"exportedAt":"now","skills":[],"apiKey":"should-not-be-here"}
        """.trimIndent()
        val secretError = assertThrows(AppException::class.java) { TransferCodec.decode(secret) }
        assertEquals(ErrorCode.TRANSFER_INVALID, secretError.error.code)
    }

    @Test
    fun publicProviderHeadersAreAllowedButSensitiveHeadersAreRejected() {
        val provider = ProviderTransfer(
            id = "provider.one",
            name = "One",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE.name,
            baseUrl = "https://example.invalid/v1",
            nonSecretHeaders = mapOf("Content-Type" to "application/json", "X-Client" to "mobile-agent"),
            revision = 1,
        )
        val model = ModelProfile(
            id = "model.one",
            providerId = provider.id,
            role = ModelRole.CHAT,
            modelId = "chat-one",
            capabilities = emptySet(),
            contextLimit = 1_000,
            outputLimit = 100,
            revision = 1,
        )
        val bundle = TransferBundle(
            schemaVersion = SchemaVersion.CURRENT,
            exportedAt = "now",
            agent = AgentTransfer(
                profile = AgentProfile("agent.one", "One", "prompt.one", model.id, revision = 1),
                promptRevisions = listOf(PromptRevision("prompt.one", "agent.one", template = "hello", createdAt = "now")),
                providers = listOf(provider),
                models = listOf(ModelTransfer(model)),
            ),
        )
        assertDoesNotThrow { TransferCodec.encode(bundle) }

        val sensitive = bundle.copy(
            agent = bundle.agent!!.copy(
                providers = listOf(provider.copy(nonSecretHeaders = mapOf("Authorization" to "Bearer should-not-export"))),
            ),
        )
        val error = assertThrows(AppException::class.java) { TransferCodec.validate(sensitive) }
        assertEquals(ErrorCode.TRANSFER_INVALID, error.error.code)

        val malformed = bundle.copy(
            agent = bundle.agent!!.copy(
                providers = listOf(provider.copy(nonSecretHeaders = mapOf("X-Client" to "line1\nline2"))),
            ),
        )
        assertThrows(AppException::class.java) { TransferCodec.validate(malformed) }
    }

    @Test
    fun unsafeRelativePathIsRejectedBeforeImport() {
        val bundle = TransferBundle(
            schemaVersion = SchemaVersion.CURRENT,
            exportedAt = "now",
            knowledgeBases = listOf(
                KnowledgeTransfer(
                    id = "kb.one",
                    name = "One",
                    blobs = listOf(BlobTransfer(packageHash, 1, "text/plain", "../outside.txt")),
                ),
            ),
        )
        val error = assertThrows(AppException::class.java) { TransferCodec.validate(bundle) }
        assertEquals(ErrorCode.TRANSFER_INVALID, error.error.code)
    }
}
