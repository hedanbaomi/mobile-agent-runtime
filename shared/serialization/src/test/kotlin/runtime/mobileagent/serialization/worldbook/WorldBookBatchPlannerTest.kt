// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction.AMBIGUOUS_TARGET
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction.CREATE
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction.CREATE_RENAMED
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction.REPLACE
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction.SKIP_DUPLICATE_IN_BATCH
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction.SKIP_EXISTING
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction.SKIP_IDENTICAL
import runtime.mobileagent.domain.worldbook.WorldBookConflictPolicy
import runtime.mobileagent.domain.worldbook.WorldBookConflictPolicy.KEEP_BOTH
import runtime.mobileagent.domain.worldbook.WorldBookConflictPolicy.SKIP
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.domain.worldbook.WorldBookHead
import runtime.mobileagent.domain.worldbook.WorldBookStorageUsage

class WorldBookBatchPlannerTest {
    private fun st(name: String?, content: String = "rule"): String =
        (if (name == null) "{" else """{"name":"$name",""") + """"entries":{"0":{"uid":0,"key":["火球术"],"content":"$content"}}}"""

    private val usage = WorldBookStorageUsage(books = 0, storedBytes = 0, maxBooks = 512, maxStoredBytes = 128L shl 20, maxRevisionBytes = 16L shl 20)

    private fun candidate(key: String, text: String, fallback: String = key, selected: Boolean = true) =
        WorldBookBatchCandidate(key, "$key.json", WorldBookBatchPlanner.probe(text, fallback), selected)

    private fun head(id: String, name: String, hash: String = "other") = WorldBookHead(id, name, 3, enabled = false, contentHash = hash)

    private fun plan(candidates: List<WorldBookBatchCandidate>, existing: List<WorldBookHead> = emptyList(),
        policy: WorldBookConflictPolicy = KEEP_BOTH, storage: WorldBookStorageUsage = usage) =
        WorldBookBatchPlanner.plan(candidates, existing, storage, policy)

    private fun WorldBookBatchPlan.actions(): List<WorldBookBatchAction> = rows.map { it.action }

    @Test
    fun `unchanged replacement under target spelling consumes no quota in preview`() {
        val text = st(null)
        val existingProbe = WorldBookBatchPlanner.probe(text, "PHB") as WorldBookProbe.Book
        val target = head("phb", "PHB", existingProbe.nativeHash)
        val probe = WorldBookBatchPlanner.probe(text, "phb", existing = listOf(target))
        val incoming = WorldBookBatchCandidate("phb", "phb.json", probe, true)
        val result = plan(listOf(incoming), listOf(target), WorldBookConflictPolicy.REPLACE,
            usage.copy(books = 1, storedBytes = usage.maxStoredBytes))
        assertEquals(listOf(SKIP_IDENTICAL), result.actions())
        assertEquals(0L, result.bytesToWrite)
        assertFalse(result.overStorageLimit)
    }

    @Test
    fun `renamed encoded size is checked before allowing writes`() {
        val incoming = candidate("a", st("PHB"))
        val bytes = (incoming.probe as WorldBookProbe.Book).nativeBytes
        val result = plan(listOf(incoming), listOf(head("phb", "PHB")), KEEP_BOTH,
            usage.copy(maxRevisionBytes = bytes))
        assertEquals(listOf(WorldBookBatchAction.TOO_LARGE), result.actions())
        assertFalse(result.canConfirm)
        assertFalse(result.rows.single().selectable)
    }

    @Test
    fun `probe separates world books from other json`() {
        val book = assertInstanceOf(WorldBookProbe.Book::class.java, WorldBookBatchPlanner.probe(st("PHB"), "file"))
        assertEquals("PHB", book.name)
        assertEquals(1, book.entryCount)
        assertFalse(book.normalizedName)
        assertInstanceOf(WorldBookProbe.Book::class.java, WorldBookBatchPlanner.probe("""{"entries":{}}""", "empty"))
        assertInstanceOf(WorldBookProbe.NotWorldBook::class.java, WorldBookBatchPlanner.probe("""{"report":true}""", "r"))
        assertInstanceOf(WorldBookProbe.NotWorldBook::class.java, WorldBookBatchPlanner.probe("""[1,2]""", "r"))
        assertInstanceOf(WorldBookProbe.NotWorldBook::class.java, WorldBookBatchPlanner.probe(
            """{"spec":"chara_card_v2","data":{"name":"Hero"}}""", "card"))
        assertInstanceOf(WorldBookProbe.Invalid::class.java, WorldBookBatchPlanner.probe("""{"entries": }""", "bad"))
        // A broken `entries` container in an object root is a damaged world book, not unrelated JSON.
        assertInstanceOf(WorldBookProbe.Invalid::class.java, WorldBookBatchPlanner.probe("""{"entries":"x"}""", "bad"))
        assertInstanceOf(WorldBookProbe.NotWorldBook::class.java, WorldBookBatchPlanner.probe("\uFEFF \"just text\"", "r"))
        val card = assertInstanceOf(WorldBookProbe.Book::class.java, WorldBookBatchPlanner.probe(
            """{"spec":"chara_card_v2","data":{"name":"Hero","character_book":{"entries":[{"keys":["a"],"content":"b"}]}}}""", "card"))
        assertTrue(card.fromCharacterCard)
    }

    @Test
    fun `the file name is the fallback and blank or long names are normalized`() {
        assertEquals("DMG", (WorldBookBatchPlanner.probe(st(null), "DMG") as WorldBookProbe.Book).name)
        val blank = WorldBookBatchPlanner.probe("""{"spec":"mobileagent.worldbook","version":1,"name":"  ","entries":[]}""", "x") as WorldBookProbe.Book
        assertEquals(WorldBookBatchPlanner.DEFAULT_NAME, blank.name)
        assertTrue(blank.normalizedName)
        val long = WorldBookBatchPlanner.probe(st("书".repeat(300)), "x") as WorldBookProbe.Book
        assertEquals(WorldBookDocument.MAX_NAME_CHARS, long.name.length)
        assertEquals(CREATE_RENAMED, plan(listOf(WorldBookBatchCandidate("k", "k.json", long, true))).rows.single().action)
    }

    @Test
    fun `the probe hash is the hash the repository stores for the same document`() {
        val text = st("PHB")
        val probe = WorldBookBatchPlanner.probe(text, "x") as WorldBookProbe.Book
        val native = WorldBookCodec.encodeNative(WorldBookCodec.decode(text, "x").document).toByteArray()
        assertEquals(WorldBookBatchPlanner.sha256(native), probe.nativeHash)
        assertEquals(native.size.toLong(), probe.nativeBytes)
    }

    @Test
    fun `keep both numbers every clash with existing books and earlier files`() {
        val result = plan(
            listOf(candidate("a", st("PHB", "1")), candidate("b", st("phb", "2")), candidate("c", st("DMG"))),
            listOf(head("x", "PHB"), head("y", "PHB (2)")),
        )
        assertEquals(listOf(CREATE_RENAMED, CREATE_RENAMED, CREATE), result.actions())
        assertEquals(listOf("PHB (3)", "phb (4)", "DMG"), result.rows.map { it.targetName })
        assertEquals(2, result.conflicts)
        assertEquals(3, result.newBooks)
        assertTrue(result.canConfirm)
    }

    @Test
    fun `identical content is skipped under every policy and is not a conflict`() {
        val probe = WorldBookBatchPlanner.probe(st("PHB"), "x") as WorldBookProbe.Book
        WorldBookConflictPolicy.entries.forEach { policy ->
            val result = plan(listOf(candidate("a", st("PHB"))), listOf(head("x", "PHB", probe.nativeHash)), policy)
            assertEquals(SKIP_IDENTICAL, result.rows.single().action, policy.name)
            assertFalse(result.rows.single().selectable)
            assertEquals(0, result.conflicts)
            assertFalse(result.canConfirm)
        }
    }

    @Test
    fun `replace targets the single same-name book and keeps its name, id and state`() {
        val result = plan(listOf(candidate("a", st("phb ", "new"))), listOf(head("x", "PHB")), WorldBookConflictPolicy.REPLACE)
        val row = result.rows.single()
        assertEquals(REPLACE, row.action)
        assertEquals("PHB", row.targetName)
        assertEquals("x", row.targetId)
        assertEquals(3, row.targetRevision)
        assertFalse(row.targetEnabled)
        assertEquals(0, result.newBooks)
        assertEquals(1, result.writeCount)
    }

    @Test
    fun `replace refuses ambiguous targets and a second file for the same book`() {
        assertEquals(AMBIGUOUS_TARGET, plan(listOf(candidate("a", st("PHB"))), listOf(head("x", "PHB"), head("y", "phb")),
            WorldBookConflictPolicy.REPLACE).rows.single().action)
        val twice = plan(listOf(candidate("a", st("PHB", "1")), candidate("b", st("PHB", "2"))), listOf(head("x", "PHB")),
            WorldBookConflictPolicy.REPLACE)
        assertEquals(listOf(REPLACE, SKIP_DUPLICATE_IN_BATCH), twice.actions())
        val fresh = plan(listOf(candidate("a", st("New", "1")), candidate("b", st("New", "2"))), policy = WorldBookConflictPolicy.REPLACE)
        assertEquals(listOf(CREATE, SKIP_DUPLICATE_IN_BATCH), fresh.actions())
    }

    @Test
    fun `skip keeps existing names and reports same-name files in the batch`() {
        val result = plan(listOf(candidate("a", st("PHB")), candidate("b", st("New", "1")), candidate("c", st("New", "2"))),
            listOf(head("x", "PHB")), SKIP)
        assertEquals(listOf(SKIP_EXISTING, CREATE, SKIP_DUPLICATE_IN_BATCH), result.actions())
        assertEquals(1, result.writeCount)
    }

    @Test
    fun `the same content twice in one batch is written once`() {
        val result = plan(listOf(candidate("a", st("PHB")), candidate("b", st("PHB"))))
        assertEquals(listOf(CREATE, SKIP_DUPLICATE_IN_BATCH), result.actions())
    }

    @Test
    fun `unselected rows neither claim names nor count toward limits`() {
        val result = plan(listOf(candidate("a", st("PHB", "1"), selected = false), candidate("b", st("PHB", "2"))))
        assertEquals(listOf("PHB", "PHB"), result.rows.map { it.targetName })
        assertEquals(CREATE, result.rows[1].action)
        assertFalse(result.rows[0].writes)
        assertEquals(1, result.writeCount)
        assertEquals(0, result.conflicts)
    }

    @Test
    fun `storage, book count and single-book limits block confirmation before anything is written`() {
        val one = candidate("a", st("PHB"))
        val bytes = (one.probe as WorldBookProbe.Book).nativeBytes
        assertTrue(plan(listOf(one), storage = usage.copy(storedBytes = usage.maxStoredBytes - bytes + 1)).overStorageLimit)
        assertFalse(plan(listOf(one), storage = usage.copy(storedBytes = usage.maxStoredBytes - bytes)).overStorageLimit)
        val full = usage.copy(books = 512)
        assertTrue(plan(listOf(one), storage = full).overBookLimit)
        assertFalse(plan(listOf(one), listOf(head("x", "PHB")), WorldBookConflictPolicy.REPLACE, full.copy(books = 512)).overBookLimit)
        val large = plan(listOf(one), storage = usage.copy(maxRevisionBytes = bytes - 1))
        assertEquals(WorldBookBatchAction.TOO_LARGE, large.rows.single().action)
        assertFalse(large.canConfirm)
    }

    @Test
    fun `invalid and non world book rows never write`() {
        val result = plan(listOf(candidate("a", """{"entries": }"""), candidate("b", """{"x":1}"""), candidate("c", st("ok"))))
        assertEquals(listOf(WorldBookBatchAction.INVALID, WorldBookBatchAction.NOT_WORLD_BOOK, CREATE), result.actions())
        assertEquals(1, result.writeCount)
        assertFalse(result.rows[0].selectable)
    }

    @Test
    fun `numbered names stay within the name limit`() {
        val long = "名".repeat(WorldBookDocument.MAX_NAME_CHARS)
        val result = plan(listOf(candidate("a", st(long))), listOf(head("x", long)))
        assertEquals(WorldBookDocument.MAX_NAME_CHARS, result.rows.single().targetName.length)
        assertTrue(result.rows.single().targetName.endsWith(" (2)"))
        // Truncation never leaves half of a surrogate pair (here: an emoji straddling the limit).
        val emoji = "a".repeat(WorldBookDocument.MAX_NAME_CHARS - 1) + "🎲tail"
        val truncated = (WorldBookBatchPlanner.probe(st(emoji), "x") as WorldBookProbe.Book).name
        assertFalse(truncated.last().isHighSurrogate())
    }

    @Test
    fun `size estimates count json escaping of the target name`() {
        // "PHB<TAB>" matches "PHB" after trimming; the tab is escaped as two bytes in JSON, not one.
        val text = st("PHB\\t")
        val row = plan(listOf(candidate("a", text)), listOf(WorldBookHead("x", "PHB", 1, true, "other")), WorldBookConflictPolicy.REPLACE).rows.single()
        assertEquals(WorldBookBatchAction.REPLACE, row.action)
        val exact = WorldBookCodec.encodeNative(WorldBookCodec.decode(text, "x").document.copy(name = "PHB")).toByteArray().size.toLong()
        assertEquals(exact, row.estimatedBytes)
        assertEquals("other", row.targetHash)
    }
}
