// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.security.NetworkSecurityPolicy
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderEndpointPolicy
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.knowledge.VisionInput
import runtime.mobileagent.knowledge.VisionOutcome
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.provider.*
import runtime.mobileagent.provider.openai.OpenAiAdapterFactory

/** Real loopback sockets and the production transport, including non-debuggable review builds. */
@RunWith(AndroidJUnit4::class)
class ProviderHttpEndpointDeviceTest {
    @Test fun knowledgeVisionParsesHttpContentAndKeepsAuxiliaryHeaderBindingInBothFormats() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val description = "HTTP evidence http://source.example.invalid/chart"
        val visionJson = """{"ocrText":"HTTP OCR","semanticDescription":"$description","tableMarkdown":"","type":"image"}"""
        val encoded = JsonPrimitive(visionJson).toString()
        for (format in ApiFormat.entries) for (host in listOf("LOCALHOST", "localhost.")) {
            Fixture(bindHost = host) { _ -> Reply(body = if (format == ApiFormat.OPENAI_COMPATIBLE)
                """{"choices":[{"message":{"content":$encoded},"finish_reason":"stop"}]}"""
                else """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":$encoded}]}]}""") }.use { server ->
                createProviderHttpClient(3_000).use { http ->
                    val provider = ProviderProfile("vision-http", "HTTP Vision", format, "${server.base}/proxy/v1",
                        headerSecretRefs = mapOf("X-Proxy-Token" to "aux-ref"), secretRef = "main-ref", revision = 1)
                    val model = ModelProfile("vision-model", provider.id, ModelRole.CHAT, "fixture-model", setOf("image"),
                        contextLimit = 32768, outputLimit = 256, revision = 1)
                    val vision = OpenAiCompatibleVision(http, { listOf(provider to model) }) { ref ->
                        (if (ref == "aux-ref") DUMMY_AUX else DUMMY_KEY).toCharArray()
                    }
                    val result = vision.process(VisionInput("fixture-asset", "fixture-context",
                        visionProfileBinding(provider, model).fingerprint, png, "image/png", "context", 1, "chart"))
                    assertTrue(result.toString(), result is VisionOutcome.Success)
                    result as VisionOutcome.Success
                    assertEquals("HTTP OCR", result.result.ocrText)
                    assertEquals(description, result.result.semanticDescription)
                    assertEquals(1, server.requests.size)
                    assertTrue(server.requests.single().credentialMatched)
                    assertTrue(server.requests.single().auxiliaryMatched)
                    assertTrue(server.requests.single().path.startsWith("/proxy/v1/"))
                    server.assertHealthy()
                }
            }
        }
    }

    @Test fun ipv6HttpPrefixAndAuxiliaryCredentialsReachOnlyTheSelectedHost() = runBlocking {
        for (format in ApiFormat.entries) {
            Fixture(bindHost = "::1") { request -> successReply(request, format) }.use { server ->
                createProviderHttpClient(3_000).use { http ->
                    val base = "${server.base}/ipv6/v1"
                    var resolvedHost = ""
                    val adapter = OpenAiAdapterFactory.create(format, http, base,
                        HeaderSecretResolver { host, ref ->
                            assertEquals("aux-ref", ref)
                            resolvedHost = host
                            DUMMY_AUX.toCharArray()
                        }, mapOf("X-Proxy-Token" to RequestHeaderValue.SecretRef("aux-ref")))
                    val model = ModelProfile("model", "provider", ModelRole.CHAT, "fixture-model", emptySet(),
                        contextLimit = 4096, outputLimit = 32, revision = 1)
                    assertTrue(adapter.testConnection(model, DUMMY_KEY.toCharArray()) is ProviderConnectionResult.Success)
                    assertEquals(URI(base).host, resolvedHost)
                    assertTrue(server.requests.single().credentialMatched && server.requests.single().auxiliaryMatched)
                    assertTrue(server.requests.single().path.startsWith("/ipv6/v1/"))
                    server.assertHealthy()
                }
            }
        }
    }

    @Test fun httpCapabilityMetadataAndStreamProbeRequireConsentAndPreserveProxyPrefix() = runBlocking {
        for (format in ApiFormat.entries) {
            Fixture { request -> if (request.path.endsWith("/models/fixture-model"))
                Reply(body = """{"id":"fixture-model","object":"model"}""")
                else successReply(request, format) }.use { server ->
                createProviderHttpClient(3_000).use { http ->
                    val adapter = OpenAiAdapterFactory.create(format, http, "${server.base}/probe/v1")
                    val model = ModelProfile("model", "provider", ModelRole.CHAT, "fixture-model", setOf("stream"),
                        contextLimit = 4096, outputLimit = 32, revision = 1)
                    assertEquals(CapabilityProbeStatus.PROFILE_ONLY,
                        adapter.probe(model, DUMMY_KEY.toCharArray(), ProbeConsent.NOT_GRANTED).status)
                    assertEquals("No consent must mean no network traffic", 0, server.requests.size)
                    val report = adapter.probe(model, DUMMY_KEY.toCharArray(), ProbeConsent.GRANTED)
                    assertEquals(report.toString(), CapabilityProbeStatus.SUCCEEDED, report.status)
                    assertTrue(report.supportsStream)
                    assertFalse(report.supportsTools || report.supportsImages)
                    val chatPath = if (format == ApiFormat.OPENAI_COMPATIBLE) "/chat/completions" else "/responses"
                    // Responses verifies endpoint/model support with a minimal non-streaming
                    // response; Chat Completions uses its optional per-model metadata route.
                    val metadataPath = if (format == ApiFormat.OPENAI_COMPATIBLE) "/models/fixture-model" else chatPath
                    assertEquals(listOf("/probe/v1$metadataPath", "/probe/v1$chatPath"), server.requests.map { it.path })
                    assertTrue(server.requests.last().body.contains("\"stream\":true"))
                    assertTrue(server.requests.all { it.credentialMatched })
                    server.assertHealthy()
                }
            }
        }
    }

    @Test fun configuredHttpWorksForConnectionVisionStreamAndEmbeddingInBothFormats() = runBlocking {
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("proxy.example.invalid"))
        for (format in ApiFormat.entries) {
            Fixture { request -> successReply(request, format) }.use { server ->
                createProviderHttpClient(3_000).use { http ->
                    val base = ProviderEndpointPolicy.parseBaseUrl("${server.base}/gateway/openai/v1/").toASCIIString()
                    val adapter = OpenAiAdapterFactory.create(format, http, base)
                    val model = ModelProfile("model", "provider", ModelRole.CHAT, "fixture-model", setOf("stream", "image"),
                        contextLimit = 4096, outputLimit = 32, revision = 1)
                    assertTrue(adapter.testConnection(model, DUMMY_KEY.toCharArray()) is ProviderConnectionResult.Success)
                    val events = adapter.stream(ModelRequest("fixture-model", listOf(ChatMessage("user", "fixture text",
                        images = listOf(InlineImage("image/png", "AA=="))))), DUMMY_KEY.toCharArray()).toList()
                    assertTrue(events.toString(), events.contains(ModelEvent.Completed))
                    assertTrue(events.any { it is ModelEvent.TextDelta && it.text == "ok" })
                    assertEquals(2, adapter.embed(EmbeddingRequest("fixture-model", listOf("fixture text")), DUMMY_KEY.toCharArray()).vectors.single().size)
                    assertEquals(3, server.requests.size)
                    val chatPath = if (format == ApiFormat.OPENAI_COMPATIBLE) "/chat/completions" else "/responses"
                    assertEquals(listOf("/gateway/openai/v1$chatPath", "/gateway/openai/v1$chatPath", "/gateway/openai/v1/embeddings"),
                        server.requests.map { it.path })
                    assertTrue(server.requests.all { it.credentialMatched })
                    assertTrue(server.requests[1].body.contains("data:image/png;base64,AA=="))
                    server.assertHealthy()
                }
            }
        }
    }

    @Test fun redirectsNeverForwardCredentialsOrContentToAnotherProxy() = runBlocking {
        for (format in ApiFormat.entries) for (status in listOf(301, 302, 303, 307, 308)) {
            Fixture { request -> successReply(request, format) }.use { destination ->
                Fixture { Reply(status, "application/json", "{}", "${destination.base}/unexpected") }.use { source ->
                    createProviderHttpClient(3_000).use { http ->
                        val adapter = OpenAiAdapterFactory.create(format, http, "${source.base}/v1")
                        val model = ModelProfile("model", "provider", ModelRole.CHAT, "fixture-model", emptySet(),
                            contextLimit = 4096, outputLimit = 32, revision = 1)
                        val result = adapter.testConnection(model, DUMMY_KEY.toCharArray())
                        assertTrue("redirect $status/$format must fail", result is ProviderConnectionResult.Failure)
                        assertEquals(1, source.requests.size)
                        assertTrue(source.requests.single().credentialMatched)
                        assertEquals("redirect destination must receive nothing", 0, destination.requests.size)
                        source.assertHealthy()
                        destination.assertHealthy()
                    }
                }
            }
        }
    }

    @Test fun serviceUnavailableIsSentOnceEvenWhenProxySuggestsImmediateRetry() = runBlocking {
        for (format in ApiFormat.entries) {
            Fixture { Reply(status = 503, body = "{}", retryAfter = "0") }.use { server ->
                createProviderHttpClient(3_000).use { http ->
                    val adapter = OpenAiAdapterFactory.create(format, http, "${server.base}/v1")
                    val model = ModelProfile("model", "provider", ModelRole.CHAT, "fixture-model", emptySet(),
                        contextLimit = 4096, outputLimit = 32, revision = 1)
                    val result = adapter.testConnection(model, DUMMY_KEY.toCharArray())
                    assertTrue(result is ProviderConnectionResult.Failure)
                    assertEquals("503 must not be automatically replayed", 1, server.requests.size)
                    server.assertHealthy()
                }
            }
        }
    }

    private fun successReply(request: Request, format: ApiFormat): Reply = when {
        request.path.endsWith("/embeddings") -> Reply(body = """{"data":[{"index":0,"embedding":[0.25,0.75]}]}""")
        request.body.contains("\"stream\":true") && format == ApiFormat.OPENAI_COMPATIBLE ->
            Reply(contentType = "text/event-stream", body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        request.body.contains("\"stream\":true") -> Reply(contentType = "text/event-stream", body =
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{}}\n\n")
        format == ApiFormat.OPENAI_COMPATIBLE -> Reply(body = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
        else -> Reply(body = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}""")
    }

    internal data class Request(val path: String, val body: String, val credentialMatched: Boolean, val auxiliaryMatched: Boolean)
    internal data class Reply(val status: Int = 200, val contentType: String = "application/json", val body: String,
        val location: String? = null, val retryAfter: String? = null)

    internal class Fixture(bindHost: String = "127.0.0.1", private val expectedKey: String = DUMMY_KEY,
        private val reply: (Request) -> Reply) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName(bindHost))
        val base = "http://${if (bindHost.contains(':')) "[$bindHost]" else bindHost}:${socket.localPort}"
        val requests = CopyOnWriteArrayList<Request>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(isDaemon = true, name = "http-provider-fixture") {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    client.soTimeout = 5_000
                    val input = client.getInputStream().buffered()
                    fun line(): String {
                        val bytes = ArrayList<Byte>()
                        while (bytes.size < 16_384) {
                            val value = input.read()
                            check(value >= 0) { "Incomplete fixture request" }
                            if (value == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
                            bytes += value.toByte()
                        }
                        error("Fixture header exceeds limit")
                    }
                    val path = line().split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = line()
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val size = headers["content-length"]?.toInt() ?: 0
                    check(size in 0..65_536)
                    val body = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        val count = input.read(body, read, size - read)
                        check(count > 0)
                        read += count
                    }
                    val request = Request(path, body.toString(Charsets.UTF_8), headers["authorization"] == "Bearer $expectedKey",
                        headers["x-proxy-token"] == DUMMY_AUX)
                    requests += request
                    val response = reply(request)
                    val bytes = response.body.toByteArray(Charsets.UTF_8)
                    val location = response.location?.let { "Location: $it\r\n" }.orEmpty()
                    val retryAfter = response.retryAfter?.let { "Retry-After: $it\r\n" }.orEmpty()
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 ${response.status} Fixture\r\nContent-Type: ${response.contentType}\r\n" +
                            "Content-Length: ${bytes.size}\r\n${location}${retryAfter}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            } catch (error: Throwable) { if (!socket.isClosed) failure.set(error) }
        }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("Fixture failed", it) } }
        override fun close() { socket.close(); worker.join(5_000); check(!worker.isAlive); assertHealthy() }
    }

    companion object {
        private const val DUMMY_KEY = "SYNTHETIC_HTTP_FIXTURE_NOT_A_REAL_KEY"
        private const val DUMMY_AUX = "SYNTHETIC_HTTP_AUXILIARY_NOT_A_REAL_KEY"
    }
}
