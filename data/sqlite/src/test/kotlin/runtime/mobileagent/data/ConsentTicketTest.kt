// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.EmbeddingUnknownOutcomeException
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.knowledge.sha256Hex

class ConsentTicketTest {
    @Test
    fun validApiConsentIsAtomicallyConsumedAndAppliedOnce() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testBinding()
        val api = TicketEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        val awaiting = repo.importBytes(
            "notes.txt",
            "text/plain",
            "ticket-protected text".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = false,
        )
        val ticket = repo.issueConsentTicket(
            "API_EMBEDDING",
            awaiting.id,
            kb,
            "GRANT\n${binding.spaceId}\n${documentsHash(db, kb)}",
        )

        val ready = repo.applyConsentTicket(ticket, visionConfigured = false)

        assertEquals(ImportStage.READY, ready?.stage)
        assertTrue(api.calls > 0)
        assertEquals(1L, db.query("SELECT consumed FROM consent_tickets WHERE id = ?", listOf(ticket)).single().long("consumed"))
        val callsAfterApply = api.calls
        assertEquals(null, repo.applyConsentTicket(ticket, visionConfigured = false))
        assertEquals(callsAfterApply, api.calls)
    }

    @Test
    fun staleDocumentFingerprintIsRejectedBeforeConsumptionOrProviderDispatch() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testBinding()
        val api = TicketEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        val awaiting = repo.importBytes(
            "notes.txt",
            "text/plain",
            "stale-ticket text".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = false,
        )
        val ticket = repo.issueConsentTicket(
            "API_EMBEDDING",
            awaiting.id,
            kb,
            "GRANT\n${binding.spaceId}\n${documentsHash(db, kb)}",
        )
        db.execute("UPDATE documents SET active_version_id = ? WHERE id = ?", listOf("changed-version", awaiting.documentId))

        assertThrows(IllegalStateException::class.java) {
            repo.applyConsentTicket(ticket, visionConfigured = false)
        }
        assertEquals(0L, db.query("SELECT consumed FROM consent_tickets WHERE id = ?", listOf(ticket)).single().long("consumed"))
        assertEquals(0, api.calls)
    }

    @Test
    fun uncertainProviderOutcomeConsumesTicketAndCannotBeAutomaticallyReplayed() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val binding = testBinding()
        val api = UnknownTicketEmbedder(binding.spaceId, binding.dimension)
        val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API library", binding)
        val awaiting = repo.importBytes(
            "unknown.txt",
            "text/plain",
            "uncertain-ticket text".toByteArray(),
            visionConfigured = false,
            knowledgeBaseId = kb,
            embeddingIsApi = true,
            embeddingConsent = false,
        )
        val ticket = repo.issueConsentTicket(
            "API_EMBEDDING",
            awaiting.id,
            kb,
            "GRANT\n${binding.spaceId}\n${documentsHash(db, kb)}",
        )

        val failed = repo.applyConsentTicket(ticket, visionConfigured = false)
        assertEquals(ImportStage.FAILED, failed?.stage)
        assertTrue(failed?.error.orEmpty().contains("UNKNOWN_OUTCOME"))
        assertEquals(1, api.calls)
        assertEquals(1L, db.query("SELECT consumed FROM consent_tickets WHERE id = ?", listOf(ticket)).single().long("consumed"))
        assertEquals(
            1L,
            db.query("SELECT COUNT(*) AS n FROM embedding_operations WHERE kb_id = ? AND state = 'UNKNOWN'", listOf(kb)).single().long("n"),
        )
        assertEquals(null, repo.applyConsentTicket(ticket, visionConfigured = false))
        assertEquals(1, api.calls)
    }

    private fun documentsHash(db: SqlConnection, kb: String): String = sha256Hex(
        db.query(
            "SELECT id,blob_hash,active_version_id FROM documents WHERE kb_id = ? AND deleted_at IS NULL ORDER BY id",
            listOf(kb),
        ).joinToString("\n") { "${it.string("id")}:${it.string("blob_hash")}:${it.string("active_version_id")}" }
            .toByteArray(Charsets.UTF_8),
    )

    private fun testBinding(): ApiEmbeddingBinding = ApiEmbeddingBinding(
        providerId = "provider-ticket",
        endpoint = "https://api.example.test/v1/embeddings",
        providerRevision = 1,
        modelId = "ticket-model",
        modelRevision = 1,
        dimension = 8,
        dataScope = "document text; retrieval purpose",
    )
}

private class TicketEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : TextEmbedder {
    var calls: Int = 0

    override fun embed(text: String): FloatArray {
        calls += 1
        return FloatArray(dimension) { index -> (text.hashCode() + index).toFloat() }
    }
}

private class UnknownTicketEmbedder(
    override val spaceId: String,
    override val dimension: Int,
) : TextEmbedder {
    var calls: Int = 0

    override fun embed(text: String): FloatArray {
        calls += 1
        throw EmbeddingUnknownOutcomeException()
    }
}
