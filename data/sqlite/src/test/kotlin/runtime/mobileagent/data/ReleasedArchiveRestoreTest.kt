// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.data

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.knowledge.Citation
import runtime.mobileagent.knowledge.FileBlobSink

class ReleasedArchiveRestoreTest {
    private fun archive(): ByteArray {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/release-fixtures/v1.4.0.zip")).use { it.readBytes() }
        val hash = checkNotNull(javaClass.getResourceAsStream("/release-fixtures/v1.4.0.zip.sha256"))
            .bufferedReader().use { it.readText().trim() }
        assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return bytes
    }

    @Test fun releasedArchiveRestoresOriginalEvidenceButNeverLocalTrustOrReplay() {
        val root = Files.createTempDirectory("released-restore").toFile()
        val blobs = FileBlobSink(root.resolve("cas"))
        val path = root.resolve("restore.db")
        try {
            JdbcSqlConnection("jdbc:sqlite:$path").use { db ->
                Migrations.apply(db)
                assertEquals("archive-agent", TransferRepository(db, blobSink = blobs).importArchive(archive()).agentId)
            }
            JdbcSqlConnection("jdbc:sqlite:$path").use { db ->
                Migrations.apply(db)
                val conversations = ConversationRepository(db)
                assertEquals("保留旧版归档消息", conversations.message("archive-message")?.text)
                assertEquals("archive-snapshot", conversations.get("archive-conversation")?.snapshotId)
                val run = requireNotNull(RunRepository(db).get("archive-run"))
                assertEquals(RunStatus.UNKNOWN_OUTCOME, run.state)
                assertNull(run.retryAcknowledgedAt)
                assertEquals(37, run.inputTokens)
                assertEquals("UNKNOWN_OUTCOME", db.query("SELECT state FROM tool_invocations WHERE invocation_id='archive-invocation'").single().string("state"))
                assertTrue(CapabilityGrantRepository(db).forAgent("archive-agent", includeRevoked = true).isEmpty())
                assertTrue(requireNotNull(ProfileRepository(db).getProvider("archive-provider")).secretRef.isNullOrBlank())
                val metadata = Json.parseToJsonElement(requireNotNull(conversations.message("archive-answer")).metadataJson).jsonObject
                val citations = metadata.getValue("citations").jsonArray
                assertTrue(citations.isNotEmpty())
                val knowledge = KnowledgeRepository(db, blobs)
                try {
                    citations.forEach { value ->
                        val fields = value.jsonObject
                        fun text(key: String) = fields[key]?.jsonPrimitive?.contentOrNull
                        val citation = Citation(citationId = text("citationId")!!, runId = "archive-run",
                            knowledgeBaseId = text("knowledgeBaseId")!!, documentId = text("documentId")!!,
                            chunkId = text("chunkId")!!, documentVersionId = requireNotNull(text("documentVersionId")),
                            page = fields["page"]?.jsonPrimitive?.intOrNull, assetId = null, sourceSpan = text("sourceSpan"))
                        assertFalse(knowledge.locateCitation(citation).removed)
                        assertArrayEquals("旧版归档证据 archive source evidence".toByteArray(), knowledge.evidenceBytes(citation)?.second)
                    }
                } finally { knowledge.closeVectorIndexes() }
                assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedArchiveCommitDoesNotPublishPartialHistoryAndCanRetry() {
        val root = Files.createTempDirectory("released-restore-failure").toFile()
        try {
            JdbcSqlConnection().use { db ->
                Migrations.apply(db)
                val blobs = FileBlobSink(root.resolve("cas"))
                var messages = 0
                val failing = object : SqlConnection by db {
                    override fun execute(sql: String, args: List<Any?>) {
                        if (sql.startsWith("INSERT INTO messages") && ++messages == 2) throw java.io.IOException("synthetic full disk")
                        db.execute(sql, args)
                    }
                }
                assertThrows(Exception::class.java) { TransferRepository(failing, blobSink = blobs).importArchive(archive()) }
                assertNull(AgentRepository(db).get("archive-agent"))
                assertNull(ConversationRepository(db).get("archive-conversation"))
                assertNull(RunRepository(db).get("archive-run"))
                assertEquals("archive-agent", TransferRepository(db, blobSink = blobs).importArchive(archive()).agentId)
            }
        } finally { root.deleteRecursively() }
    }
}
