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

class VisualAssetBatchQueryAdversarialTest {

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
    fun chunkingBoundaryExceeding100Items() {
        TestJdbcConnection().use { db ->
            // Insert 250 assets
            val totalAssets = 250
            for (i in 1..totalAssets) {
                insertAsset(db, "asset-$i", "hash-$i", "image/png", i * 100L)
            }

            // Query exactly 101 items (crosses chunk boundary: 100 + 1)
            val ids101 = (1..101).map { "asset-$it" }
            val map101 = queryAssetMetadataBatch(db, ids101)
            assertEquals(101, map101.size)
            assertEquals(Triple("image/png", 100L, "hash-1"), map101["asset-1"])
            assertEquals(Triple("image/png", 10100L, "hash-101"), map101["asset-101"])

            // Query all 250 items (3 chunks: 100 + 100 + 50)
            val ids250 = (1..totalAssets).map { "asset-$it" }
            val map250 = queryAssetMetadataBatch(db, ids250)
            assertEquals(250, map250.size)
            assertEquals(Triple("image/png", 25000L, "hash-250"), map250["asset-250"])
        }
    }

    @Test
    fun deduplicationAndNonExistentIdHandling() {
        TestJdbcConnection().use { db ->
            insertAsset(db, "existing-1", "hash-1", "image/jpeg", 500L)
            insertAsset(db, "existing-2", "hash-2", "image/png", 1500L)

            // 50 duplicate IDs of existing-1 + existing-2 + 10 missing IDs
            val idsWithDupes = List(25) { "existing-1" } + List(25) { "existing-2" } + (1..10).map { "missing-$it" }
            val map = queryAssetMetadataBatch(db, idsWithDupes)

            // Result must only contain the 2 existing ones
            assertEquals(2, map.size)
            assertEquals(Triple("image/jpeg", 500L, "hash-1"), map["existing-1"])
            assertEquals(Triple("image/png", 1500L, "hash-2"), map["existing-2"])
            assertNull(map["missing-1"])
        }
    }

    @Test
    fun missingBlobAssociationExcludesAsset() {
        TestJdbcConnection().use { db ->
            // Insert asset without corresponding blob
            db.execute(
                "INSERT INTO assets(id, document_id, blob_hash, kind, surrounding_text_hash) VALUES(?, ?, ?, ?, ?)",
                listOf("orphan-asset", "doc1", "missing-hash", "image", "sth"),
            )

            val map = queryAssetMetadataBatch(db, listOf("orphan-asset"))
            assertTrue(map.isEmpty(), "Inner join must omit asset when blob record is absent")
        }
    }

    @Test
    fun zeroByteAssetHandling() {
        TestJdbcConnection().use { db ->
            insertAsset(db, "zero-asset", "zero-hash", "image/png", 0L)
            val map = queryAssetMetadataBatch(db, listOf("zero-asset"))
            assertEquals(1, map.size)
            assertEquals(Triple("image/png", 0L, "zero-hash"), map["zero-asset"])
        }
    }
}
