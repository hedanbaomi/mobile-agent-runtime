// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

/**
 * Real dispatch/driver seam over deterministic in-memory FDs: canonical model
 * fields, outer control-frame budgeting and list cursor continuity.
 */
class RootServiceFileDispatchProtocolTest {
    @Test
    fun tightMetadataBudgetNeverSilentlyDropsTheRequestedOwnershipAndMode() {
        Fixture().use { f ->
            f.ops.seedText("a.txt", "hello")
            val args = RootTypedArguments.FileGetMetadata("ws", "a.txt")
            val full = f.execute(args)
            val encoded = RootPrivilegedServiceEngine.json.encodeToString(RootServiceExecuteResponse.serializer(), full)
            val bounded = f.execute(args, encoded.toByteArray(Charsets.UTF_8).size - 1)
            assertEquals(RootServiceTerminalOutcome.FAILED, bounded.terminalOutcome)
            assertEquals("result_exceeds_budget", bounded.errorReason)
            assertEquals(0, f.body(full).getValue("owner_uid").jsonPrimitive.int)
        }
    }

    @Test
    fun metadataUsesTheOrdinaryCanonicalFields() {
        Fixture().use { f ->
            f.ops.seedText("a.txt", "hello")
            val response = f.execute(RootTypedArguments.FileStat("ws", "a.txt"))
            assertEquals(RootServiceTerminalOutcome.SUCCEEDED, response.terminalOutcome, response.fileResultJson)
            val body = f.body(response)
            assertTrue(body.getValue("success").jsonPrimitive.boolean)
            assertEquals("a.txt", body.getValue("relative_path").jsonPrimitive.content)
            assertEquals("a.txt", body.getValue("path").jsonPrimitive.content)
            assertEquals("file", body.getValue("type").jsonPrimitive.content)
            assertEquals("regular_file", body.getValue("kind").jsonPrimitive.content)
            assertEquals(5L, body.getValue("bytes").jsonPrimitive.long)
            assertEquals(5L, body.getValue("size_bytes").jsonPrimitive.long)
        }
    }

    @Test
    fun readUsesCanonicalPagingFieldsAndKeepsLegacyAliases() {
        Fixture().use { f ->
            f.ops.seedText("a.txt", "hello")
            val response = f.execute(RootTypedArguments.FileReadText("ws", "a.txt", 0, 64))
            assertEquals(RootServiceTerminalOutcome.SUCCEEDED, response.terminalOutcome, response.fileResultJson)
            val body = f.body(response)
            assertTrue(body.getValue("success").jsonPrimitive.boolean)
            assertEquals("hello", body.getValue("text").jsonPrimitive.content)
            assertEquals(5L, body.getValue("bytes").jsonPrimitive.long)
            assertEquals(5L, body.getValue("byte_length").jsonPrimitive.long)
            assertEquals(5L, body.getValue("total_bytes").jsonPrimitive.long)
            assertEquals(5L, body.getValue("total_size_bytes").jsonPrimitive.long)
            assertEquals(5L, body.getValue("next_offset").jsonPrimitive.long)
            assertEquals(5L, body.getValue("next_offset_bytes").jsonPrimitive.long)
            assertTrue(body.getValue("eof").jsonPrimitive.boolean)
            assertFalse(body.getValue("has_more").jsonPrimitive.boolean)
            assertFalse(body.getValue("truncated").jsonPrimitive.boolean)
        }
    }

    @Test
    fun listBudgetTrimAdvancesTheCursorWithoutSkippingOrRepeatingEntries() {
        Fixture().use { f ->
            repeat(30) { index -> f.ops.seedText("f%02d.txt".format(index), "x") }
            val allNames = (0 until 30).map { "f%02d.txt".format(it) }
            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            var hasMore = true
            do {
                val response = f.execute(RootTypedArguments.FileList("ws", "", maxEntries = 50, cursor = cursor), budget = 4096)
                assertEquals(RootServiceTerminalOutcome.SUCCEEDED, response.terminalOutcome, response.fileResultJson)
                val body = f.body(response)
                assertTrue(body.getValue("success").jsonPrimitive.boolean)
                val entries = body.getValue("entries").jsonArray.map { it.jsonObject }
                if (pages == 0 && entries.size == allNames.size) {
                    throw AssertionError("fixture budget did not force a bounded page")
                }
                val pageHasMore = body.getValue("has_more").jsonPrimitive.boolean
                assertEquals(pageHasMore, body.getValue("truncated").jsonPrimitive.boolean)
                if (pageHasMore) {
                    val next = body.getValue("next_cursor").jsonPrimitive.content
                    assertEquals(entries.last().getValue("name").jsonPrimitive.content, next,
                        "the cursor must name the last entry actually returned")
                    cursor = next
                } else {
                    assertFalse(body.containsKey("next_cursor"))
                }
                entries.forEach { entry ->
                    assertEquals("file", entry.getValue("type").jsonPrimitive.content)
                    assertEquals("regular_file", entry.getValue("kind").jsonPrimitive.content)
                    assertTrue(entry.getValue("bytes").jsonPrimitive.long >= 0L)
                    assertEquals(entry.getValue("bytes"), entry.getValue("size_bytes"))
                }
                seen += entries.map { it.getValue("relative_path").jsonPrimitive.content }
                hasMore = pageHasMore
                pages += 1
            } while (hasMore && pages < 40)
            assertEquals(allNames, seen.distinct(), "every entry is returned once across bounded pages")
            assertEquals(seen.size, seen.distinct().size, "no page may repeat an entry")
        }
    }

    @Test
    fun globBudgetTrimIsDisclosedInsteadOfFileTooLarge() {
        Fixture().use { f ->
            repeat(40) { index -> f.ops.seedText("match-%02d.txt".format(index), "x") }
            val response = f.execute(
                RootTypedArguments.FileGlob("ws", "", pattern = "*.txt", maxResults = 50),
                budget = 4096,
            )
            assertEquals(RootServiceTerminalOutcome.SUCCEEDED, response.terminalOutcome, response.fileResultJson)
            val body = f.body(response)
            assertTrue(body.getValue("success").jsonPrimitive.boolean)
            assertTrue(body.getValue("truncated").jsonPrimitive.boolean)
            val matches = body.getValue("matches").jsonArray.map { it.jsonObject }
            assertTrue(matches.isNotEmpty() && matches.size < 40, "the page is bounded, not the whole result or an error")
            assertEquals(matches.size, body.getValue("match_count").jsonPrimitive.int)
            assertTrue(body.getValue("truncation_reasons").jsonArray.any { it.jsonPrimitive.content == "result_bytes" })
            assertEquals(matches.size, body.getValue("entries").jsonArray.size, "legacy entries alias stays in sync")
            matches.forEach { match ->
                assertEquals("file", match.getValue("type").jsonPrimitive.content)
                assertTrue(match.getValue("relative_path").jsonPrimitive.content.isNotBlank())
                assertEquals(match.getValue("bytes"), match.getValue("size_bytes"))
            }
            val scanned = body.getValue("scanned").jsonObject
            assertTrue(scanned.getValue("entries").jsonPrimitive.int >= matches.size)
            assertTrue(body.getValue("scanned_entries").jsonPrimitive.int >= matches.size)
        }
    }

    @Test
    fun grepBudgetTrimIsDisclosedWithCountsAndCanonicalHits() {
        Fixture().use { f ->
            f.ops.seedText("notes.txt", (0 until 100).joinToString("\n") { "needle line $it" })
            val response = f.execute(
                RootTypedArguments.FileGrep("ws", "", query = "needle", maxResults = 100),
                budget = 4096,
            )
            assertEquals(RootServiceTerminalOutcome.SUCCEEDED, response.terminalOutcome, response.fileResultJson)
            val body = f.body(response)
            assertTrue(body.getValue("success").jsonPrimitive.boolean)
            assertTrue(body.getValue("truncated").jsonPrimitive.boolean)
            val matches = body.getValue("matches").jsonArray.map { it.jsonObject }
            assertTrue(matches.isNotEmpty() && matches.size < 100)
            assertEquals(matches.size, body.getValue("match_count").jsonPrimitive.int)
            assertTrue(body.getValue("truncation_reasons").jsonArray.any { it.jsonPrimitive.content == "result_bytes" })
            matches.forEach { hit ->
                assertEquals("notes.txt", hit.getValue("relative_path").jsonPrimitive.content)
                assertEquals("notes.txt", hit.getValue("path").jsonPrimitive.content)
                assertEquals(hit.getValue("line"), hit.getValue("line_text"))
                assertTrue(hit.getValue("line_number").jsonPrimitive.long >= 1L)
            }
            assertTrue(body.getValue("scanned").jsonObject.getValue("files").jsonPrimitive.int >= 1)
            assertTrue(body.getValue("scanned_bytes").jsonPrimitive.long > 0L)
        }
    }

    @Test
    fun everySuccessBodyFitsTheOuterControlFrameBudget() {
        Fixture().use { f ->
            repeat(20) { index -> f.ops.seedText("f%02d.txt".format(index), "x") }
            val budget = 4096
            val responses = listOf(
                f.execute(RootTypedArguments.FileList("ws", "", maxEntries = 20), budget),
                f.execute(RootTypedArguments.FileGlob("ws", "", pattern = "*.txt"), budget),
                f.execute(RootTypedArguments.FileGrep("ws", "", query = "x"), budget),
                f.execute(RootTypedArguments.FileReadText("ws", "f00.txt", 0, 8), budget),
                f.execute(RootTypedArguments.FileStat("ws", "f00.txt"), budget),
            )
            responses.forEach { response ->
                assertEquals(RootServiceTerminalOutcome.SUCCEEDED, response.terminalOutcome, response.fileResultJson)
                val encoded = RootPrivilegedServiceEngine.json.encodeToString(RootServiceExecuteResponse.serializer(), response)
                assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= budget, encoded)
                assertNotNull(response.fileResultJson)
            }
        }
    }

    private fun body(response: RootServiceExecuteResponse): JsonObject =
        Json.parseToJsonElement(requireNotNull(response.fileResultJson)).jsonObject

    private class Fixture : AutoCloseable {
        val ops = InMemoryServiceFdOps("/workspace")
        val driver = AndroidRootPosixFileDriver(Paths.get("/workspace"), ops)
        private val status = RootControlStatusSnapshot("request", "a".repeat(64), 1, RootServiceExecutionStage.ACCEPTED)
        private val executor: RootWorkspaceFileExecutor

        init {
            val root = requireNotNull(ops.openDirectoryAbsoluteNoFollow("/workspace"))
            val rootIdentity = root.use { ops.fstatIdentity(it) }!!
            val control = RootControlPlaneBoundary(
                "/data/user/0/app",
                RootFdNodeIdentity(1, 9001, 77, true, false, false, 2, 448, 10100, 10100),
                "/data/user_de/0/app",
                RootFdNodeIdentity(1, 9002, 77, true, false, false, 2, 448, 10100, 10100),
                listOf(RootFdNodeIdentity(1, 9003, 77, true, false, false, 2, 448, 10100, 10100)),
            )
            executor = RootWorkspaceFileExecutor(rootIdentity, control, driver)
        }

        fun execute(args: RootTypedArguments, budget: Int = RootExecutionBudget.MAX_OUTPUT_BYTES): RootServiceExecuteResponse =
            RootServiceFileDispatch.execute(executor, args, status, ByteArray(0), budget)

        fun body(response: RootServiceExecuteResponse): JsonObject =
            Json.parseToJsonElement(requireNotNull(response.fileResultJson)).jsonObject

        override fun close() = driver.close()
    }
}
