// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.mobileagent.domain.*
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.serialization.TransferOptions

class ConversationImageRepositoryTest {
    @Test fun concurrentImageReadsAndGcUseDatabaseBeforeCasLock() {
        JdbcSqlConnection().use { connection ->
            val databaseLock = Any()
            val db = object : SqlConnection by connection {
                override fun <T> transaction(block: () -> T): T = synchronized(databaseLock) { connection.transaction(block) }
            }
            fixture(db)
            val sink = object : BlobSink by MemoryBlobSink() {
                private val storageLock = Any()
                override fun <T> withStorageLock(block: () -> T): T {
                    check(Thread.holdsLock(databaseLock)) { "CAS lock must follow the database transaction" }
                    return synchronized(storageLock) { block() }
                }
            }
            val repo = ConversationImageRepository(db, ConversationRepository(db), sink)
            val message = repo.appendUser("conversation", "", listOf(upload()))
            val image = repo.references("conversation", message.parts.filterIsInstance<ImagePart>()).single()
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val readers = executor.submit { repeat(20) { repo.load("conversation", image); repo.verify("conversation", image) } }
                val collector = executor.submit { repeat(20) { StorageMaintenance(db, sink, null).collect() } }
                readers.get(10, java.util.concurrent.TimeUnit.SECONDS)
                collector.get(10, java.util.concurrent.TimeUnit.SECONDS)
                assertArrayEquals(PNG, Base64.getDecoder().decode(repo.load("conversation", image).base64))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun conversationArchiveRejectsMissingOrTamperedOriginalWithoutPublishingRows() {
        JdbcSqlConnection().use { source ->
            fixture(source)
            val sink = MemoryBlobSink()
            ConversationImageRepository(source, ConversationRepository(source), sink).appendUser("conversation", "", listOf(upload()))
            val options = TransferOptions(includeConversations = true)
            assertThrows(AppException::class.java) { TransferRepository(source).exportArchive("agent", options, ByteArrayOutputStream()) }
            val archive = ByteArrayOutputStream()
            TransferRepository(source, blobSink = sink).exportArchive("agent", options, archive)
            listOf(false, true).forEach { omit ->
                val broken = ByteArrayOutputStream()
                java.util.zip.ZipInputStream(archive.toByteArray().inputStream()).use { input ->
                    java.util.zip.ZipOutputStream(broken).use { output ->
                        while (true) {
                            val entry = input.nextEntry ?: break
                            val bytes = input.readBytes()
                            if (!entry.name.startsWith("blobs/") || !omit) {
                                output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                                output.write(if (entry.name.startsWith("blobs/")) bytes.copyOf().apply { this[lastIndex] = 1 } else bytes)
                                output.closeEntry()
                            }
                            input.closeEntry()
                        }
                    }
                }
                JdbcSqlConnection().use { target ->
                    Migrations.apply(target)
                    assertThrows(AppException::class.java) { TransferRepository(target, blobSink = MemoryBlobSink()).importArchive(broken.toByteArray()) }
                    assertTrue(target.query("SELECT id FROM conversations").isEmpty())
                    assertTrue(target.query("SELECT hash FROM blobs").isEmpty())
                }
            }
        }
    }

    @Test fun historyReceiptsSuppressOnlyOwnedSuccessfullyAnalyzedOriginals() {
        JdbcSqlConnection().use { db ->
            fixture(db)
            val conversations = ConversationRepository(db)
            val repo = ConversationImageRepository(db, conversations, MemoryBlobSink())
            val original = repo.appendUser("conversation", "", listOf(upload())).parts.filterIsInstance<ImagePart>().single()
            val receiptPart = original.copy(blobHash = null)
            val pending = repo.referencesForHistory("conversation", listOf(receiptPart), emptyMap(), true)
            assertEquals(original.blobHash, pending.single().sha256)
            assertTrue(repo.referencesForHistory("conversation", listOf(original, receiptPart),
                mapOf(original.assetId to original.blobHash!!), true).isEmpty())
            assertTrue(repo.referencesForHistory("conversation", listOf(original),
                mapOf(original.assetId to original.blobHash!!), false).isEmpty())
            assertEquals(1, repo.referencesForHistory("conversation", listOf(original),
                mapOf(original.assetId to "0".repeat(64)), true).size)
            assertThrows(IllegalArgumentException::class.java) {
                repo.referencesForHistory("conversation", listOf(original), emptyMap(), false)
            }
            conversations.create("snapshot", "Other", "other")
            assertThrows(IllegalArgumentException::class.java) {
                repo.referencesForHistory("other", listOf(receiptPart), mapOf(original.assetId to original.blobHash!!), true)
            }
        }
    }

    @Test fun imageOnlyPersistsAcrossReopenAndCannotCrossConversations(@TempDir path: Path) {
        val url = "jdbc:sqlite:${path.resolve("chat.db")}" // Synthetic local test DB only.
        val sink = FileBlobSink(path.resolve("cas").toFile())
        val message = JdbcSqlConnection(url).use { db ->
            fixture(db)
            ConversationImageRepository(db, ConversationRepository(db), sink).appendUser("conversation", "", listOf(upload()))
        }
        JdbcSqlConnection(url).use { db ->
            val conversations = ConversationRepository(db)
            assertEquals(message, conversations.messages("conversation").single())
            val repo = ConversationImageRepository(db, conversations, sink)
            val image = repo.references("conversation", message.parts.filterIsInstance<ImagePart>()).single()
            assertArrayEquals(PNG, Base64.getDecoder().decode(repo.load("conversation", image).base64))
            conversations.create("snapshot", "Other", "other")
            assertThrows(IllegalArgumentException::class.java) { repo.load("other", image) }
            assertThrows(IllegalArgumentException::class.java) { repo.load("conversation", image.copy(sha256 = "0".repeat(64))) }
        }
    }

    @Test fun gcRetainsConversationOriginalsAndReclaimsAfterDeletion() {
        JdbcSqlConnection().use { db ->
            fixture(db)
            val sink = MemoryBlobSink()
            val conversations = ConversationRepository(db)
            val repo = ConversationImageRepository(db, conversations, sink)
            val message = repo.appendUser("conversation", "Explain", listOf(upload(), upload()))
            val hash = message.parts.filterIsInstance<ImagePart>().first().blobHash!!
            assertEquals(1, sink.storedHashes().size)
            StorageMaintenance(db, sink, null).collect()
            assertArrayEquals(PNG, sink.get(hash))
            assertTrue(conversations.delete("conversation"))
            StorageMaintenance(db, sink, null).collect()
            assertNull(sink.get(hash))
            assertTrue(db.query("SELECT hash FROM blobs").isEmpty())
        }
    }

    @Test fun missingTamperedAndChangedBytesFailClosed() {
        JdbcSqlConnection().use { db ->
            fixture(db)
            val sink = MemoryBlobSink()
            val repo = ConversationImageRepository(db, ConversationRepository(db), sink)
            val message = repo.appendUser("conversation", "Explain", listOf(upload()))
            val image = repo.references("conversation", message.parts.filterIsInstance<ImagePart>()).single()
            sink.blobs[image.sha256!!] = PNG.copyOf().apply { this[lastIndex] = 1 }
            assertThrows(IllegalArgumentException::class.java) { repo.verify("conversation", image.copy(mediaType = "image/jpeg")) }
            assertThrows(IllegalArgumentException::class.java) { repo.verify("conversation", image.copy(byteLength = 1)) }
            // MemoryBlobSink intentionally does not hash-check reads; repository must do so itself.
            assertThrows(IllegalArgumentException::class.java) { repo.verify("conversation", image) }
            sink.blobs.clear()
            assertThrows(IllegalArgumentException::class.java) { repo.verify("conversation", image) }
        }
    }

    @Test fun countArchiveAndQuotaGatesNeverPublishPartialMessages(@TempDir path: Path) {
        JdbcSqlConnection().use { db ->
            fixture(db)
            val sink = MemoryBlobSink()
            val conversations = ConversationRepository(db)
            val repo = ConversationImageRepository(db, conversations, sink, path.toFile())
            assertThrows(IllegalArgumentException::class.java) { repo.appendUser("conversation", "", List(9) { upload() }) }
            assertTrue(sink.storedHashes().isEmpty())
            conversations.setArchived("conversation", true)
            assertThrows(AppException::class.java) { repo.appendUser("conversation", "", listOf(upload())) }
            assertTrue(conversations.messages("conversation").isEmpty())
            StorageMaintenance(db, sink, null).collect()
            assertTrue(sink.storedHashes().isEmpty())
            conversations.setArchived("conversation", false)
            java.io.RandomAccessFile(path.resolve("quota.idx").toFile(), "rw").use { it.setLength(StorageMaintenance.MIN_QUOTA_BYTES) }
            StorageMaintenance(db, sink, path.toFile()).configureQuota(StorageMaintenance.MIN_QUOTA_BYTES)
            assertThrows(IllegalStateException::class.java) { repo.appendUser("conversation", "", listOf(upload())) }
            assertTrue(conversations.messages("conversation").isEmpty())
            assertTrue(sink.storedHashes().isEmpty())
        }
    }

    @Test fun explicitConversationArchiveRestoresOriginalsAndDefaultExportExcludesThem() {
        JdbcSqlConnection().use { source ->
            fixture(source)
            val sourceSink = MemoryBlobSink()
            val message = ConversationImageRepository(source, ConversationRepository(source), sourceSink)
                .appendUser("conversation", "What is pictured?", listOf(upload()))
            val transfer = TransferRepository(source, blobSink = sourceSink)
            val defaultJson = transfer.exportAgent("agent")
            assertFalse(defaultJson.contains("chat-upload:"))
            val archive = ByteArrayOutputStream()
            transfer.exportArchive("agent", TransferOptions(includeConversations = true), archive)
            JdbcSqlConnection().use { target ->
                Migrations.apply(target)
                val targetSink = MemoryBlobSink()
                TransferRepository(target, blobSink = targetSink).importArchive(archive.toByteArray())
                val restored = ConversationRepository(target).messages("conversation").single()
                assertEquals(message.parts, restored.parts)
                val images = ConversationImageRepository(target, ConversationRepository(target), targetSink)
                val ref = images.references("conversation", restored.parts.filterIsInstance<ImagePart>()).single()
                assertArrayEquals(PNG, Base64.getDecoder().decode(images.load("conversation", ref).base64))
                StorageMaintenance(target, targetSink, null).collect()
                assertArrayEquals(PNG, targetSink.get(ref.sha256!!))
            }
        }
    }

    private fun fixture(db: SqlConnection) {
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1))
        profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", setOf("image"), contextLimit = 16384, outputLimit = 512, revision = 1))
        val agents = AgentRepository(db)
        agents.saveWithPrompt(AgentProfile("agent", "Fixture", "pending", "model", revision = 0), "Synthetic prompt")
        agents.createSnapshot("agent", "snapshot")
        ConversationRepository(db).create("snapshot", "Fixture", "conversation")
    }

    private fun upload() = ChatImageUpload(PNG, "image/png")
    companion object {
        val PNG: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jGd0AAAAASUVORK5CYII=")
    }
}
