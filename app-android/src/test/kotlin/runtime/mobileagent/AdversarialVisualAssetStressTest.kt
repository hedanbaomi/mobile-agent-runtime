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

class AdversarialVisualAssetStressTest {

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
    fun chunkingBoundariesExactlyAtAndAroundChunkLimits() {
        TestJdbcConnection().use { db ->
            // Populate 205 assets
            for (i in 1..205) {
                insertAsset(db, "asset-$i", "hash-$i", "image/png", 1000L + i)
            }

            // Boundary 0
            assertTrue(queryAssetMetadataBatch(db, emptyList()).isEmpty())

            // Boundary 1
            val r1 = queryAssetMetadataBatch(db, listOf("asset-1"))
            assertEquals(1, r1.size)
            assertEquals("hash-1", r1["asset-1"]?.third)

            // Boundary 99
            val ids99 = (1..99).map { "asset-$it" }
            val r99 = queryAssetMetadataBatch(db, ids99)
            assertEquals(99, r99.size)

            // Boundary 100 (exactly 1 chunk)
            val ids100 = (1..100).map { "asset-$it" }
            val r100 = queryAssetMetadataBatch(db, ids100)
            assertEquals(100, r100.size)

            // Boundary 101 (crosses into 2 chunks: 100 + 1)
            val ids101 = (1..101).map { "asset-$it" }
            val r101 = queryAssetMetadataBatch(db, ids101)
            assertEquals(101, r101.size)
            assertEquals("hash-101", r101["asset-101"]?.third)

            // Boundary 200 (exactly 2 chunks)
            val ids200 = (1..200).map { "asset-$it" }
            val r200 = queryAssetMetadataBatch(db, ids200)
            assertEquals(200, r200.size)

            // Boundary 205 (3 chunks: 100 + 100 + 5)
            val ids205 = (1..205).map { "asset-$it" }
            val r205 = queryAssetMetadataBatch(db, ids205)
            assertEquals(205, r205.size)
            assertEquals("hash-205", r205["asset-205"]?.third)
        }
    }

    @Test
    fun largeScaleInterleavedDuplicatesAndMissingAssets() {
        TestJdbcConnection().use { db ->
            // Populate even-numbered assets up to 300
            for (i in 2..300 step 2) {
                insertAsset(db, "asset-$i", "hash-$i", "image/jpeg", 2000L + i)
            }

            // Request contains 1..300 (so 150 exist, 150 missing), with triple duplication
            val requested = (1..300).flatMap { listOf("asset-$it", "asset-$it", "asset-$it") }
            assertEquals(900, requested.size)

            val results = queryAssetMetadataBatch(db, requested)
            // Exactly 150 unique existing assets should be returned
            assertEquals(150, results.size)

            for (i in 1..300) {
                if (i % 2 == 0) {
                    val meta = results["asset-$i"]
                    assertEquals(Triple("image/jpeg", 2000L + i, "hash-$i"), meta)
                } else {
                    assertNull(results["asset-$i"])
                }
            }
        }
    }
}
