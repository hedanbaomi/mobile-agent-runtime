// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.knowledge.VisualAttachmentPolicy
import runtime.mobileagent.knowledge.VisualReferencePlan

class VisualAssetBatchQueryTest {

    private class TestJdbcConnection : SqlConnection, AutoCloseable {
        private val conn = DriverManager.getConnection("jdbc:sqlite::memory:")

        init {
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE blobs (hash TEXT PRIMARY KEY, byte_length INTEGER NOT NULL, media_type TEXT NOT NULL, local_ref TEXT NOT NULL, ref_count INTEGER NOT NULL)",
                )
                stmt.execute(
                    "CREATE TABLE assets (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, document_version_id TEXT, blob_hash TEXT NOT NULL, page INTEGER, section TEXT, kind TEXT NOT NULL, surrounding_text_hash TEXT NOT NULL)",
                )
            }
        }

        override fun execute(sql: String, args: List<Any?>) {
            conn.prepareStatement(sql).use { stmt ->
                args.forEachIndexed { idx, arg -> stmt.setObject(idx + 1, arg) }
                stmt.execute()
            }
        }

        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            return conn.prepareStatement(sql).use { stmt ->
                args.forEachIndexed { idx, arg -> stmt.setObject(idx + 1, arg) }
                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<SqlRow>()
                    val meta = rs.metaData
                    while (rs.next()) {
                        val map = linkedMapOf<String, Any?>()
                        for (i in 1..meta.columnCount) {
                            map[meta.getColumnLabel(i)] = rs.getObject(i)
                        }
                        rows += SqlRow(map)
                    }
                    rows
                }
            }
        }

        override fun <T> transaction(block: () -> T): T = block()

        override fun close() {
            conn.close()
        }
    }

    private fun insertAsset(db: SqlConnection, assetId: String, hash: String, mediaType: String, byteLength: Long) {
        db.execute(
            "INSERT OR REPLACE INTO blobs(hash, byte_length, media_type, local_ref, ref_count) VALUES(?, ?, ?, ?, ?)",
            listOf(hash, byteLength, mediaType, "ref_$hash", 1),
        )
        db.execute(
            "INSERT OR REPLACE INTO assets(id, document_id, blob_hash, kind, surrounding_text_hash) VALUES(?, ?, ?, ?, ?)",
            listOf(assetId, "doc1", hash, "image", "sth"),
        )
    }

    @Test
    fun queryAssetMetadataBatchEmptyReturnsEmptyMap() {
        TestJdbcConnection().use { db ->
            val result = queryAssetMetadataBatch(db, emptyList())
            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun queryAssetMetadataBatchSingleAndMultipleAssets() {
        TestJdbcConnection().use { db ->
            insertAsset(db, "asset-1", "hash-1", "image/png", 1024L)
            insertAsset(db, "asset-2", "hash-2", "image/jpeg", 2048L)
            insertAsset(db, "asset-3", "hash-3", "image/webp", 4096L)

            val map = queryAssetMetadataBatch(db, listOf("asset-1", "asset-2", "asset-3"))
            assertEquals(3, map.size)

            val a1 = map["asset-1"]
            assertEquals(Triple("image/png", 1024L, "hash-1"), a1)

            val a2 = map["asset-2"]
            assertEquals(Triple("image/jpeg", 2048L, "hash-2"), a2)

            val a3 = map["asset-3"]
            assertEquals(Triple("image/webp", 4096L, "hash-3"), a3)
        }
    }

    @Test
    fun queryAssetMetadataBatchMissingAssetOmitted() {
        TestJdbcConnection().use { db ->
            insertAsset(db, "asset-1", "hash-1", "image/png", 1024L)
            insertAsset(db, "asset-3", "hash-3", "image/webp", 4096L)

            val map = queryAssetMetadataBatch(db, listOf("asset-1", "nonexistent-asset", "asset-3"))
            assertEquals(2, map.size)
            assertTrue("asset-1" in map)
            assertTrue("asset-3" in map)
            assertNull(map["nonexistent-asset"])
        }
    }

    @Test
    fun queryAssetMetadataBatchDeduplication() {
        TestJdbcConnection().use { db ->
            insertAsset(db, "asset-1", "hash-1", "image/png", 1024L)
            insertAsset(db, "asset-2", "hash-2", "image/jpeg", 2048L)

            val map = queryAssetMetadataBatch(db, listOf("asset-1", "asset-1", "asset-2", "asset-1"))
            assertEquals(2, map.size)
            assertTrue("asset-1" in map)
            assertTrue("asset-2" in map)
        }
    }

    @Test
    fun queryAssetMetadataBatchChunkingBoundaryHandlesOverOneHundredItems() {
        TestJdbcConnection().use { db ->
            val assetIds = (1..150).map { i ->
                val id = "asset-$i"
                val hash = "hash-$i"
                insertAsset(db, id, hash, "image/png", 1000L + i)
                id
            }

            val map = queryAssetMetadataBatch(db, assetIds)
            assertEquals(150, map.size)
            for (i in 1..150) {
                val metadata = map["asset-$i"]
                assertEquals(Triple("image/png", 1000L + i, "hash-$i"), metadata)
            }
        }
    }

    @Test
    fun visualReferencePlanIntegrationWithPolicy() {
        TestJdbcConnection().use { db ->
            insertAsset(db, "asset-1", "hash-1", "image/png", 1024L)
            insertAsset(db, "asset-2", "hash-2", "image/jpeg", 2048L)

            // 1. All valid assets
            val validMetadata = queryAssetMetadataBatch(db, listOf("asset-1", "asset-2"))
            val planComplete = VisualAttachmentPolicy.references(listOf("asset-1", "asset-2")) { id -> validMetadata[id] }
            assertTrue(planComplete is VisualReferencePlan.Complete)
            val images = (planComplete as VisualReferencePlan.Complete).images
            assertEquals(2, images.size)
            assertEquals("asset-1", images[0].assetId)
            assertEquals("asset-2", images[1].assetId)

            // 2. Missing asset returns Incomplete
            val partialMetadata = queryAssetMetadataBatch(db, listOf("asset-1", "missing-asset"))
            val planMissing = VisualAttachmentPolicy.references(listOf("asset-1", "missing-asset")) { id -> partialMetadata[id] }
            assertTrue(planMissing is VisualReferencePlan.Incomplete)
            assertEquals("Visual source missing", (planMissing as VisualReferencePlan.Incomplete).reason)

            // 3. Exceeding max images (64)
            val tooMany = (1..65).map { "asset-$it" }
            val planBudget = VisualAttachmentPolicy.references(tooMany) { null }
            assertTrue(planBudget is VisualReferencePlan.Incomplete)
            assertTrue((planBudget as VisualReferencePlan.Incomplete).reason.contains("RUN_IMAGE_BUDGET_EXCEEDED"))
        }
    }
}
