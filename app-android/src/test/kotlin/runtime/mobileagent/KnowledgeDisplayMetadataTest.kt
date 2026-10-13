// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.*

class KnowledgeDisplayMetadataTest {
    @Test fun oneRefreshReadsProfilesOnceAndRetainsEmbeddingOrderAndMissingProviderRules() {
        var providerReads = 0
        var modelReads = 0
        val provider = ProviderProfile("provider", "供应商", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 3)
        val models = (0..499).map { model("embedding-$it", ModelRole.EMBEDDING, "provider", it + 1) } +
            listOf(model("missing", ModelRole.EMBEDDING, "absent", 1), model("chat", ModelRole.CHAT, "provider", 1))
        val result = readKnowledgeDisplayMetadata({ providerReads++; listOf(provider) }, { modelReads++; models })
        assertEquals(1, providerReads)
        assertEquals(1, modelReads)
        assertEquals((0..499).map { "embedding-$it" }, result.embeddingModels.map { it.id })
        assertEquals("供应商 · embedding-499 · r3/500", result.embeddingModels.last().label)
        assertTrue(result.visionTargets.isEmpty())
        // A later refresh reads current profiles again; removed providers do not
        // survive in a cross-refresh label/target cache.
        val removed = readKnowledgeDisplayMetadata({ providerReads++; emptyList() }, { modelReads++; models })
        assertEquals(2, providerReads)
        assertEquals(2, modelReads)
        assertTrue(removed.embeddingModels.isEmpty())
    }

    @Test fun waitingTargetsUseOneSqlReadForFiveHundredJobsAndRefreshChangedTargets() {
        DraftConnection().use { db ->
            db.execute("CREATE TABLE import_batches(id TEXT PRIMARY KEY,vision_target TEXT)")
            db.execute("CREATE TABLE import_jobs(id TEXT PRIMARY KEY,kb_id TEXT,batch_id TEXT)")
            db.execute("INSERT INTO import_batches VALUES('batch','target-a')")
            repeat(500) { db.execute("INSERT INTO import_jobs VALUES(?,?,?)", listOf("job-$it", "selected", "batch")) }
            db.execute("INSERT INTO import_jobs VALUES('other','other-kb','batch')")
            db.execute("INSERT INTO import_jobs VALUES('standalone','selected',NULL)")
            db.execute("INSERT INTO import_jobs VALUES('missing-batch','selected','absent')")
            db.transaction { repeat(10_000) { db.execute("INSERT INTO import_jobs VALUES(?,?,?)", listOf("history-$it", "selected", "batch")) } }
            var reads = 0
            var materializedRows = 0
            val counted = object : SqlConnection by db {
                override fun query(sql: String, args: List<Any?>): List<SqlRow> {
                    reads++
                    assertTrue(args.size <= 801)
                    return db.query(sql, args).also { materializedRows += it.size }
                }
            }
            val waiting = (0..499).map { "job-$it" } + listOf("standalone", "missing-batch")
            val targets = readKnowledgeWaitingBatchTargets(counted, "selected", waiting + "other")
            assertEquals(1, reads)
            assertEquals(502, materializedRows)
            assertEquals(502, targets.size)
            assertTrue((0..499).all { targets["job-$it"] == "target-a" })
            assertFalse(targets.containsKey("other"))
            assertNull(targets["standalone"])
            assertNull(targets["missing-batch"])
            assertTrue(readKnowledgeWaitingBatchTargets(counted, null, waiting).isEmpty())
            assertTrue(readKnowledgeWaitingBatchTargets(counted, "selected", emptyList()).isEmpty())
            assertEquals(1, reads)
            db.execute("UPDATE import_batches SET vision_target='target-b' WHERE id='batch'")
            assertEquals("target-b", readKnowledgeWaitingBatchTargets(counted, "selected", waiting)["job-0"])
            assertEquals(2, reads)
            // More IDs than the legacy SQLite variable limit still return every
            // requested row, without expanding scope to all stored history.
            reads = 0; materializedRows = 0
            val large = (0..1600).map { "history-$it" }
            val paged = readKnowledgeWaitingBatchTargets(counted, "selected", large + large.take(10))
            assertEquals(3, reads)
            assertEquals(1601, materializedRows)
            assertEquals(large.toSet(), paged.keys)
            assertTrue(paged.values.all { it == "target-b" })
        }
    }

    @Test fun refreshedProviderRevisionUpdatesLabelsAndVisionFingerprintWithoutAStoredCache() {
        val original = ProviderProfile("provider", "Original", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1)
        val models = listOf(model("vision", ModelRole.VISION, "provider", 1), model("embedding", ModelRole.EMBEDDING, "provider", 1))
        val before = readKnowledgeDisplayMetadata({ listOf(original) }, { models })
        val after = readKnowledgeDisplayMetadata({ listOf(original.copy(name = "Changed", revision = 2)) }, { models })
        assertEquals("Original · embedding · r1/1", before.embeddingModels.single().label)
        assertEquals("Changed · embedding · r2/1", after.embeddingModels.single().label)
        assertNotEquals(before.visionTargets.single().fingerprint, after.visionTargets.single().fingerprint)
        assertTrue(after.visionTargets.single().label.startsWith("Changed ·"))
    }

    private fun model(id: String, role: ModelRole, provider: String, revision: Int) =
        ModelProfile(id, provider, role, id, emptySet(), contextLimit = 4096, outputLimit = 256, revision = revision)
}
