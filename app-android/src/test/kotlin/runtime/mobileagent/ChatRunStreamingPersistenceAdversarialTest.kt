// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Optional
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.MessageErrorCode
import runtime.mobileagent.domain.ReasoningPart
import runtime.mobileagent.provider.SecretRedactor

class ChatRunStreamingPersistenceAdversarialTest {
    private data class RunMetrics(
        val chunks: Int,
        val elapsedNs: Long,
        val allocatedBytes: Long,
        val flushes: List<Pair<String, String>>,
        val maxPendingTail: Int,
        val persistedText: String,
        val persistedReasoning: String,
    )

    @Test
    fun bytecodeDefersSnapshotEvaluationUntilAfter50msThrottleCheck() {
        val javap = findJavapTool()
        val responseDisassembly = disassembleClass(javap, ChatRunResponse::class.java)
        for (methodName in listOf("appendText", "appendRefusal", "appendReasoning")) {
            val body = extractMethodBody(responseDisassembly, "public final java.lang.Object $methodName(")
            assertFalse(body.contains("getAnswer:()"), "$methodName must not evaluate answer before throttle:\n$body")
            assertFalse(body.contains("getReasoning:()"), "$methodName must not evaluate reasoning before throttle:\n$body")
            assertFalse(body.contains("StringBuilder.toString:()"), "$methodName must not copy StringBuilder:\n$body")
            assertTrue(body.contains("flushStreamingAnswer:(Ljava/lang/String;Z)V"), "$methodName must call fast flush:\n$body")
        }
        val flushBody = extractMethodBody(
            responseDisassembly,
            "public final void flushStreamingAnswer(java.lang.String, boolean)",
        )
        val cmpIndex = flushBody.indexOf("50l")
        val toStringIndex = flushBody.indexOf("StringBuilder.toString:()")
        assertTrue(cmpIndex in 1 until toStringIndex, "toString() must occur strictly after 50ms check:\n$flushBody")

        val toolHistoryClass = Class.forName("runtime.mobileagent.ToolEvidenceHistoryKt")
        val toolDisassembly = disassembleClass(javap, toolHistoryClass)
        val appendReasoningBody = extractMethodBody(toolDisassembly, "appendDeclaredReasoning(")
        assertFalse(
            appendReasoningBody.contains("StringBuilder.toString:()"),
            "appendDeclaredReasoning must not copy full buffer per delta:\n$appendReasoningBody",
        )
    }

    @Test
    fun hostStreaming256And512And1024ChunksAvoidsQuadraticCopiesAndPreservesVisibleSnapshots() = runBlocking {
        // Warm up JIT and SQLite paths before measuring scaling across 256 / 512 / 1024 chunks.
        measureHostStream(chunks = 128)

        val m256 = measureHostStream(chunks = 256)
        val m512 = measureHostStream(chunks = 512)
        val m1024 = measureHostStream(chunks = 1_024)

        for (m in listOf(m256, m512, m1024)) {
            assertEquals(m.chunks * 16, m.persistedText.length + 14) // one 17-char secret -> 3-char "***"
            assertFalse(m.persistedText.contains(STREAM_SECRET))
            assertFalse(m.persistedReasoning.contains(STREAM_SECRET))
            assertTrue(m.persistedText.contains("***"))
            assertTrue(m.persistedReasoning.contains("***"))
            assertTrue(m.maxPendingTail <= 64, "Unexpected pending tail growth: ${m.maxPendingTail}")
            assertMonotonicCleanSnapshots(m.flushes)
        }

        // 1024 chunks (16,384 chars text + 16,384 chars reasoning + refusal) with 2ms/delta => ~42 UI flushes.
        assertTrue(m1024.flushes.size in 35..50, "Expected ~42 throttled flushes, got ${m1024.flushes.size}")
        assertTrue(m1024.elapsedNs < 1_500_000_000L, "1024-chunk stream took ${m1024.elapsedNs / 1_000_000} ms")
        if (m256.allocatedBytes > 0L && m1024.allocatedBytes > 0L) {
            val ratio = m1024.allocatedBytes.toDouble() / m256.allocatedBytes.toDouble()
            // Pre-fix O(N^2) full-string copies scaled ~16x for 4x chunks (>35 MB for 1024 chunks);
            // post-fix scales ~4x linearly and stays well below 10x even with SQLite checkpoints.
            assertTrue(
                ratio < 9.5 && m1024.allocatedBytes < 14_000_000L,
                "Expected linear allocation scaling: 256=${m256.allocatedBytes}B, 512=${m512.allocatedBytes}B, 1024=${m1024.allocatedBytes}B (ratio=$ratio)",
            )
        }
    }

    @Test
    fun unclosedProseQuotesLongCookieLineAndCancelledCredentialCandidatesPreserveSafetyAndBoundedTail() = runBlocking {
        // 1. Unclosed prose quotes across 1024 chunks (16,384 chars): zero stall, bounded tail, exact persistence.
        withConversation { conversations ->
            var clock = 100L
            val flushes = mutableListOf<String>()
            val response = ChatRunResponse(
                conversations = conversations,
                conversationId = CONVERSATION_ID,
                runId = "run-prose-quotes",
                citations = emptyMap(),
                onFlush = { _, text, _, _ -> flushes += text },
                onAppend = {},
                nowMillis = { clock },
            )
            response.beginModelResponse("", "msg-prose-quotes")
            val prose = ("He wrote \"Hello world with unclosed quote and 'nested prose " + "segment_01234567".repeat(1_024))
                .substring(0, 16_384)
            for (chunk in slice16(prose)) {
                clock += 2L
                response.appendText(chunk)
                assertTrue(response.pendingRedactorLength() <= 16)
            }
            response.finish()
            assertEquals(prose, response.answer)
            assertEquals(prose, conversations.messages(CONVERSATION_ID).single().text)
            assertTrue(flushes.size >= 35 && flushes[10].length > 3_000, "Unclosed prose quote must not stall UI")
        }

        // 2. Single-line Cookie header across 1024 chunks (16,384 chars): O(1) bounded tail + clean cancel discard.
        withConversation { conversations ->
            var clock = 100L
            val flushes = mutableListOf<String>()
            val response = ChatRunResponse(
                conversations = conversations,
                conversationId = CONVERSATION_ID,
                runId = "run-long-cookie",
                citations = emptyMap(),
                onFlush = { _, text, _, _ -> flushes += text },
                onAppend = {},
                nowMillis = { clock },
            )
            response.beginModelResponse("", "msg-long-cookie")
            val cookieLine = "Safe preamble.\nCookie: sid=" + "x".repeat(16_357)
            for (chunk in slice16(cookieLine)) {
                clock += 2L
                response.appendText(chunk)
                assertTrue(
                    response.pendingRedactorLength() <= 160,
                    "Long Cookie line must keep O(1) pending tail, got ${response.pendingRedactorLength()}",
                )
            }
            response.flushStreamingAnswer(force = true)
            assertTrue(flushes.all { !it.contains("sid=") }, "Forced flush must not leak open Cookie header")

            response.finishRun(completed = false)
            response.checkpoint("CANCELLED")
            val saved = conversations.messages(CONVERSATION_ID).single()
            assertEquals("CANCELLED", saved.status)
            assertEquals("Safe preamble.\n", saved.text)
            assertEquals(0, response.pendingRedactorLength())
        }

        // 3. Unclosed quoted api-key candidate aborted by failModelResponse vs completed normally.
        withConversation { conversations ->
            val flushes = mutableListOf<Pair<String, String>>()
            val response = ChatRunResponse(
                conversations = conversations,
                conversationId = CONVERSATION_ID,
                runId = "run-unclosed-apikey-fail",
                citations = emptyMap(),
                onFlush = { _, text, reasoningText, _ -> flushes += text to reasoningText },
                onAppend = {},
            )
            response.beginModelResponse("", "msg-apikey-fail")
            val candidate = "Visible prefix. x-api-key: \"" + "secret_fragment_".repeat(512)
            for (chunk in slice16(candidate)) {
                response.appendText(chunk)
                response.appendReasoning(chunk)
            }
            response.failModelResponse(ErrorPart(MessageErrorCode.INTERNAL, "Stream interrupted"))
            val saved = conversations.messages(CONVERSATION_ID).single()
            assertEquals("ERROR", saved.status)
            assertFalse(saved.text.contains("secret_fragment_"))
            assertFalse(saved.parts.filterIsInstance<ReasoningPart>().single().text.contains("secret_fragment_"))
            assertTrue(flushes.none { it.first.contains("secret_fragment_") || it.second.contains("secret_fragment_") })
        }
    }

    private suspend fun measureHostStream(chunks: Int): RunMetrics {
        val textBody = buildStreamPayload(chunks * 16, "txt")
        val reasoningBody = buildStreamPayload(chunks * 16, "rsn")
        val textChunks = slice16(textBody)
        val reasoningChunks = slice16(reasoningBody)
        val secrets = listOf(STREAM_SECRET)

        var result: RunMetrics? = null
        withConversation { conversations ->
            var clock = 1_000L
            val flushes = mutableListOf<Pair<String, String>>()
            val response = ChatRunResponse(
                conversations = conversations,
                conversationId = CONVERSATION_ID,
                runId = "run-$chunks",
                citations = emptyMap(),
                onFlush = { _, text, reasoningText, _ -> flushes += text to reasoningText },
                onAppend = {},
                nowMillis = { clock },
            )
            response.beginModelResponse("", "msg-$chunks")

            val allocBefore = currentThreadAllocatedBytes()
            val startNs = System.nanoTime()
            var maxPending = 0
            for (i in 0 until chunks) {
                clock += 2L
                response.appendText(textChunks[i], secrets)
                response.appendReasoning(reasoningChunks[i], secrets)
                maxPending = maxOf(maxPending, response.pendingRedactorLength())
            }
            response.finish()
            val elapsedNs = System.nanoTime() - startNs
            val allocDelta = (currentThreadAllocatedBytes() - allocBefore).coerceAtLeast(0L)
            val saved = conversations.messages(CONVERSATION_ID).single()
            assertEquals(SecretRedactor.redact(textBody, secrets), saved.text)
            val savedReasoning = saved.parts.filterIsInstance<ReasoningPart>().single().text
            assertEquals(SecretRedactor.redact(reasoningBody, secrets), savedReasoning)
            result = RunMetrics(chunks, elapsedNs, allocDelta, flushes, maxPending, saved.text, savedReasoning)
        }
        return checkNotNull(result)
    }

    private fun buildStreamPayload(totalChars: Int, tag: String): String = buildString(totalChars + 32) {
        append("$tag-head-012345 ")
        append(STREAM_SECRET)
        append(' ')
        while (length < totalChars) {
            append("$tag-prose-block ")
        }
    }.substring(0, totalChars)

    private fun assertMonotonicCleanSnapshots(flushes: List<Pair<String, String>>) {
        var prevTextLen = 0
        var prevReasoningLen = 0
        for ((text, reasoning) in flushes) {
            assertTrue(text.length >= prevTextLen && reasoning.length >= prevReasoningLen)
            assertFalse(text.contains("sk-host-secret") || reasoning.contains("sk-host-secret"))
            prevTextLen = text.length
            prevReasoningLen = reasoning.length
        }
    }

    private fun slice16(text: String): List<String> = buildList((text.length + 15) / 16) {
        var idx = 0
        while (idx < text.length) {
            val end = (idx + 16).coerceAtMost(text.length)
            add(text.substring(idx, end))
            idx = end
        }
    }

    private fun currentThreadAllocatedBytes(): Long = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean")
            .invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        method.invoke(bean, Thread.currentThread().id) as Long
    }.getOrDefault(-1L)

    private fun findJavapTool(): Any {
        val optional = Class.forName("java.util.spi.ToolProvider")
            .getMethod("findFirst", String::class.java)
            .invoke(null, "javap") as Optional<*>
        return checkNotNull(optional.orElse(null)) { "javap ToolProvider must be available on JDK test runtime" }
    }

    private fun disassembleClass(javap: Any, clazz: Class<*>): String {
        val simpleName = clazz.simpleName + ".class"
        val bytes = checkNotNull(clazz.getResourceAsStream(simpleName)) { "Missing class resource $simpleName" }
            .use { it.readBytes() }
        val tempFile = Files.createTempFile("bytecode-check-", ".class")
        try {
            Files.write(tempFile, bytes)
            val out = StringWriter()
            val err = StringWriter()
            val runMethod = Class.forName("java.util.spi.ToolProvider")
                .getMethod("run", PrintWriter::class.java, PrintWriter::class.java, Array<String>::class.java)
            val code = runMethod.invoke(
                javap,
                PrintWriter(out),
                PrintWriter(err),
                arrayOf("-c", "-p", tempFile.toAbsolutePath().toString()),
            ) as Int
            assertEquals(0, code, "javap failed: $err")
            return out.toString()
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun extractMethodBody(disassembly: String, signatureMarker: String): String {
        val start = disassembly.indexOf(signatureMarker)
        assertTrue(start >= 0, "Method signature '$signatureMarker' not found in disassembly")
        val nextBlank = disassembly.indexOf("\n\n", start)
        val nextCrLfBlank = disassembly.indexOf("\r\n\r\n", start)
        val endCandidates = listOf(nextBlank, nextCrLfBlank).filter { it > start }
        val end = endCandidates.minOrNull() ?: disassembly.length
        return disassembly.substring(start, end)
    }

    private suspend fun withConversation(block: suspend (ConversationRepository) -> Unit) {
        JdbcConnection().use { db ->
            Migrations.apply(db)
            val at = "2026-10-10T00:00:00Z"
            db.execute(
                "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("snapshot", 1, "agent", "prompt", "model", 1, "[]", "[]", at),
            )
            val conversations = ConversationRepository(db) { at }
            conversations.create("snapshot", "Adversarial streaming fixture", CONVERSATION_ID)
            block(conversations)
        }
    }

    private class JdbcConnection : SqlConnection, AutoCloseable {
        private val connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        }

        override fun execute(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.execute()
            }
        }

        override fun query(sql: String, args: List<Any?>): List<SqlRow> = connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(SqlRow((1..result.metaData.columnCount).associate {
                            result.metaData.getColumnLabel(it) to result.getObject(it)
                        }))
                    }
                }
            }
        }

        override fun <T> transaction(block: () -> T): T {
            if (!connection.autoCommit) return block()
            connection.autoCommit = false
            return try {
                block().also { connection.commit() }
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

        override fun close() = connection.close()
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val STREAM_SECRET = "sk-host-secret-99"
    }
}
