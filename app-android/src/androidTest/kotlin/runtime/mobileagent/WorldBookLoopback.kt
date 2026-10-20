// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.assertNull
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.skills.ToolCall

internal class WorldBookLoopback(
    private val format: ApiFormat,
    private val tools: Boolean,
    private val toolCall: ToolCall = ToolCall("worldbook-calculation", "calculator", """{"expression":"1+1"}"""),
) : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
    val port = server.localPort
    val requests = CopyOnWriteArrayList<JsonObject>()
    @Volatile var beforeReply: () -> Unit = {}
    private val closed = AtomicBoolean()
    private val active = AtomicReference<Socket?>()
    private val failure = AtomicReference<Throwable?>()
    private val worker = Thread({ serve() }, "worldbook-loopback").apply { isDaemon = true; start() }

    private fun serve() {
        while (!closed.get()) {
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { return }
            active.set(socket)
            try { socket.use { connection ->
                connection.soTimeout = 5000
                val input = connection.getInputStream()
                val raw = ByteArrayOutputStream()
                var suffix = 0
                while (raw.size() < 16_384) {
                    val next = input.read(); check(next >= 0); raw.write(next); suffix = (suffix shl 8) or next
                    if (suffix == 0x0d0a0d0a) break
                }
                val headers = raw.toString("US-ASCII").split("\r\n")
                check(headers.first().startsWith("POST /v1/"))
                val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                check(length in 1..2_000_000)
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) { val read = input.read(bytes, offset, length - offset); check(read > 0); offset += read }
                val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                requests.add(request); check(requests.size <= 10)
                beforeReply()
                val response = response(request).toByteArray(Charsets.UTF_8)
                connection.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    write(response); flush()
                }
            } } catch (error: Throwable) { if (!closed.get()) failure.compareAndSet(null, error) }
            finally { active.set(null) }
        }
    }

    private fun response(request: JsonObject): String {
        val callTool = tools && requests.size == 1
        if (format != ApiFormat.OPENAI_COMPATIBLE) {
            val event = if (callTool) buildJsonObject {
                put("type", "response.function_call_arguments.done")
                put("item_id", "fc_fixture"); put("call_id", toolCall.callId)
                put("name", toolCall.name); put("arguments", toolCall.argumentsJson)
            } else buildJsonObject { put("type", "response.output_text.delta"); put("delta", "fixture reply") }
            return "data: $event\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n"
        }
        val summary = request["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
            .startsWith("Summarize the supplied conversation data")
        val text = if (summary) """{"goals":["continue"],"constraints":[],"decisions":[],"pending":[],"results":["earlier replies retained"]}""" else "fixture reply"
        val chunk = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject {
                if (callTool) put("tool_calls", buildJsonArray { add(buildJsonObject {
                    put("index", 0); put("id", toolCall.callId); put("type", "function")
                    put("function", buildJsonObject { put("name", toolCall.name); put("arguments", toolCall.argumentsJson) })
                }) }) else put("content", text)
            }); put("finish_reason", if (callTool) "tool_calls" else "stop")
        }) }) }
        return "data: $chunk\n\ndata: [DONE]\n\n"
    }
    fun assertHealthy() { assertNull(failure.get()?.toString(), failure.get()) }
    override fun close() { closed.set(true); server.close(); active.getAndSet(null)?.close(); worker.join(2000); check(!worker.isAlive) }
}
