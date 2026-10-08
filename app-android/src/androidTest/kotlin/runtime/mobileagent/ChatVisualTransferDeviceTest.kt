// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.storage.CasBlobSink

/** Captures real ChatViewModel -> RunTools -> adapter HTTP payloads on device loopback. */
class ChatVisualTransferDeviceTest {
    @Test fun endpointImageModalitySendsOriginalsInRetrievalAndToolFollowup() =
        exercise(ModelRole.CHAT, endpointImages = true, legacyImage = false, degrade = false, expectedImages = true)

    @Test fun legacyChatImageCapabilityStillSendsOriginals() =
        exercise(ModelRole.CHAT, endpointImages = false, legacyImage = true, degrade = false, expectedImages = true, legacyProfile = true)

    @Test fun textOnlyModelDisclosesWithheldOriginals() =
        exercise(ModelRole.CHAT, endpointImages = false, legacyImage = false, degrade = false, expectedImages = false)

    @Test fun explicitTextDegradationStillWithholdsOriginals() =
        exercise(ModelRole.CHAT, endpointImages = true, legacyImage = false, degrade = true, expectedImages = false)

    @Test fun sevenOriginalsAreSentTogetherAfterKnowledgeToolResult() =
        exercise(ModelRole.CHAT, endpointImages = true, legacyImage = true, degrade = false, expectedImages = true, imageCount = 7)

    @Test fun sevenLargeOriginalsRetainEveryByte() =
        exercise(ModelRole.CHAT, endpointImages = true, legacyImage = false, degrade = false, expectedImages = true, imageCount = 7, largeImages = true)

    @Test fun sixtyFourToolImagesReachWireInEightGroups() =
        exercise(ModelRole.CHAT, endpointImages = true, legacyImage = false, degrade = false, expectedImages = true, imageCount = 64)

    @Test fun textDegradationOnNextTurnNeverReplaysLegacyHistoryOriginals() =
        exercise(ModelRole.CHAT, endpointImages = true, legacyImage = false, degrade = false,
            expectedImages = true, followupTextOnly = true)

    @Test fun completedOversizedKnowledgeImageNotesRecoverWithoutReplayingTheTool() =
        exercise(ModelRole.CHAT, endpointImages = true, legacyImage = false, degrade = false,
            expectedImages = true, imageCount = 8, recoverOversized = true)

    private fun exercise(role: ModelRole, endpointImages: Boolean, legacyImage: Boolean, degrade: Boolean, expectedImages: Boolean, imageCount: Int = 1, legacyProfile: Boolean = false, largeImages: Boolean = false, followupTextOnly: Boolean = false, recoverOversized: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val kb = container.knowledge.createKnowledgeBase("visual transfer $suffix")
        val text = "visualtransferfixture$suffix original chart evidence"
        val imported = container.knowledge.importBytes("fixture.txt", "text/plain", text.toByteArray(), false, kb)
        assertEquals(ImportStage.READY, imported.stage)
        val version = container.db.query("SELECT active_version_id FROM documents WHERE id=?", listOf(imported.documentId))
            .single().string("active_version_id")
        val originals = (0 until imageCount).map { index ->
            val size = if (largeImages) 768 else 8
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            if (largeImages) {
                val rng = java.util.Random(731L + index)
                val pixels = IntArray(size * size) { 0xff000000.toInt() or rng.nextInt(0x1000000) }
                bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            } else bitmap.eraseColor(0xff2266aa.toInt() + index)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
        }
        if (largeImages) originals.forEach { assertTrue("Large fixture must exercise byte pressure", it.size in 1_500_000..2_097_152) }
        val assets = originals.indices.map { UUID.randomUUID().toString() }
        // Seed a published visual chunk locally; no Vision or external model is invoked.
        container.db.transaction {
            originals.forEachIndexed { index, png ->
                val stored = CasBlobSink(File(app.filesDir, "cas")).put(png, "image/png")
                container.db.execute("INSERT OR IGNORE INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,?)",
                    listOf(stored.sha256, stored.byteLength, stored.mediaType, stored.localRef, 1))
                container.db.execute("INSERT INTO assets(id,document_id,document_version_id,blob_hash,page,section,kind,surrounding_text_hash) VALUES(?,?,?,?,?,?,?,?)",
                    listOf(assets[index], imported.documentId, version, stored.sha256, null, null, "image", stored.sha256))
            }
            container.db.execute("UPDATE chunks SET asset_ids=? WHERE document_version_id=?", listOf(assets.joinToString(","), version))
        }
        val expectedBatches = if (recoverOversized) 3 else if (expectedImages) (imageCount + 7) / 8 else 0
        val initialRequests = 2 + expectedBatches
        val expectedRequests = initialRequests + if (followupTextOnly) 1 else 0
        val requests = CopyOnWriteArrayList<JsonObject>()
        val serverFailure = AtomicReference<Throwable?>()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 60_000
            val arguments = buildJsonObject { put("documentId", imported.documentId) }.toString()
            val serving = Thread {
                try {
                    var mainRounds = 0
                    var analysisRounds = 0
                    repeat(expectedRequests) {
                        server.accept().use { socket ->
                            socket.soTimeout = 30_000
                            val input = socket.getInputStream()
                            val header = ByteArrayOutputStream()
                            var tail = 0
                            while (header.size() < 16_384) {
                                val next = input.read(); check(next >= 0)
                                header.write(next); tail = (tail shl 8) or next
                                if (tail == 0x0d0a0d0a) break
                            }
                            val length = header.toString("US-ASCII").split("\r\n")
                                .first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                            check(length in 1..32 * 1024 * 1024)
                            val body = ByteArray(length)
                            var read = 0
                            while (read < length) { val n = input.read(body, read, length - read); check(n > 0); read += n }
                            requests += Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                            val analysis = (requests.last()["tools"] as? JsonArray).orEmpty().isEmpty()
                            val firstMain = !analysis && mainRounds++ == 0
                            val response = buildJsonObject {
                                put("choices", buildJsonArray { add(buildJsonObject {
                                    put("delta", buildJsonObject {
                                        if (firstMain) put("tool_calls", buildJsonArray { add(buildJsonObject {
                                            put("index", 0); put("id", "read-fixture"); put("type", "function")
                                            put("function", buildJsonObject { put("name", "read_document"); put("arguments", arguments) })
                                        }) }) else put("content", if (analysis && analysisRounds++ == 0 && recoverOversized) "REJECTED_PARENT_" + "x".repeat(16_001) else "fixture complete")
                                    })
                                    put("finish_reason", if (firstMain) "tool_calls" else "stop")
                                }) })
                            }
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                                write("data: $response\n\ndata: [DONE]\n\n".toByteArray()); flush()
                            }
                        }
                    }
                } catch (failure: Throwable) { serverFailure.set(failure) }
            }.apply { isDaemon = true; start() }
            val secret = "synthetic-visual-$suffix"
            container.secrets.put(secret, "synthetic-only".toCharArray())
            val provider = ProviderProfile("provider-visual-$suffix", "Synthetic loopback", ApiFormat.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.localPort}/v1", secretRef = secret, revision = 1)
            val model = ModelProfile("model-visual-$suffix", provider.id, role, "synthetic-visual",
                buildSet { add("stream"); add("tools"); if (legacyImage) add("image") },
                contextLimit = 1_048_576, outputLimit = 512, revision = 1,
                endpoint = if (legacyProfile) ModelEndpoint(emptySet()) else ModelEndpoint(setOf(ModelOperation.CHAT),
                    buildSet { add(InputModality.TEXT); if (endpointImages) add(InputModality.IMAGE) },
                    setOf(ModelFeature.STREAMING, ModelFeature.TOOL_CALLING), CapabilityVerification.USER_DECLARED))
            container.profiles.createProvider(provider)
            container.profiles.createModel(model)
            val agent = "agent-visual-$suffix"
            container.agents.saveWithPrompt(AgentProfile(agent, "Visual fixture", "pending", model.id,
                knowledgeBaseIds = listOf(kb), revision = 0), "Read the supplied evidence.")
            lateinit var vm: ChatViewModel
            instrumentation.runOnMainSync {
                vm = ChatViewModel(app, SavedStateHandle())
                vm.selectAgent(agent); vm.degrade(degrade)
                vm.input(if (imageCount > 1) "Read document " + imported.documentId else text); vm.send()
            }
            try {
                val deadline = System.currentTimeMillis() + 90_000
                while (vm.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertFalse("Run did not finish: ${vm.state.value.status}", vm.state.value.streaming)
                assertEquals("Expected initial request and tool followup; server error: ${serverFailure.get()}", initialRequests, requests.size)
                val session = requireNotNull(vm.state.value.selectedSessionId)
                assertEquals(RunStatus.COMPLETED, container.runs.list(session).single().state)
                fun imageUrls(request: JsonObject): List<String> = request.getValue("messages").jsonArray.flatMap { message ->
                    (message.jsonObject["content"] as? JsonArray)?.mapNotNull { part ->
                        part.jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                    }.orEmpty()
                }
                val transmitted = requests.flatMap(::imageUrls)
                assertEquals(if (recoverOversized) imageCount * 2 else if (expectedImages) imageCount else 0, transmitted.size)
                assertTrue(requests.all { imageUrls(it).size <= 8 })
                assertTrue(requests.filter { (it["tools"] as? JsonArray).orEmpty().isNotEmpty() }.all { imageUrls(it).isEmpty() })
                transmitted.forEach { url ->
                    assertTrue(url.startsWith("data:image/png;base64,"))
                    assertTrue("Wire image must equal an original byte for byte",
                        originals.any { it.contentEquals(Base64.getDecoder().decode(url.substringAfter(','))) })
                }
                if (expectedImages) assertEquals(originals.map { Base64.getEncoder().encodeToString(it) }.toSet(),
                    transmitted.map { it.substringAfter(',') }.toSet())
                if (recoverOversized) {
                    assertEquals(listOf(0, 8, 4, 4, 0), requests.map { imageUrls(it).size })
                    assertEquals(1, container.runs.invocations(container.runs.list(session).single().runId).count { it.name == "read_document" })
                    assertFalse(requests.last().toString().contains("REJECTED_PARENT_"))
                }
                val toolMessage = requests.last().getValue("messages").jsonArray.single {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
                }
                assertEquals(!expectedImages, toolMessage.toString().contains("textDegradation"))
                val receipts = container.conversations.messages(session).filter {
                    it.metadataJson.contains("\"visualBatchAnalysis\":true")
                }.flatMap { it.parts }.filterIsInstance<ImagePart>()
                assertEquals(if (expectedImages) imageCount else 0, receipts.size)
                if (expectedImages) assertEquals(assets.toSet(), receipts.map { it.assetId }.toSet())
                container.conversations.messages(session).filter {
                    it.metadataJson.contains("\"visualBatchAnalysis\":true")
                }.forEach { receipt ->
                    val metadata = Json.parseToJsonElement(receipt.metadataJson).jsonObject
                    val citedAssets = metadata.getValue("citations").jsonArray.map {
                        it.jsonObject.getValue("asset").jsonPrimitive.content
                    }.toSet()
                    assertTrue(citedAssets.containsAll(receipt.parts.filterIsInstance<ImagePart>().map { it.assetId }))
                    if (followupTextOnly) {
                        // Simulate a pre-batching durable transcript with authorized original references.
                        container.db.execute("UPDATE messages SET metadata_json=? WHERE id=?",
                            listOf(JsonObject(metadata.filterKeys { it != "visualBatchAnalysis" && it != "sourceHashes" }).toString(), receipt.id))
                    }
                }
                if (followupTextOnly) {
                    instrumentation.runOnMainSync {
                        vm = ChatViewModel(app, SavedStateHandle())
                        vm.selectSession(session); vm.degrade(true); vm.input("Continue with text only."); vm.send()
                    }
                    val nextDeadline = System.currentTimeMillis() + 30_000
                    while (vm.state.value.streaming && System.currentTimeMillis() < nextDeadline) Thread.sleep(50)
                    assertFalse(vm.state.value.streaming)
                    assertEquals(expectedRequests, requests.size)
                    assertTrue(imageUrls(requests.last()).isEmpty())
                    assertTrue(requests.last().toString().contains("历史原图本轮未发送"))
                    assertEquals(RunStatus.COMPLETED, container.runs.list(session).last().state)
                }
            } finally {
                instrumentation.runOnMainSync { vm.cancel() }
                server.close(); serving.join(2_000)
            }
        }
    }
}
