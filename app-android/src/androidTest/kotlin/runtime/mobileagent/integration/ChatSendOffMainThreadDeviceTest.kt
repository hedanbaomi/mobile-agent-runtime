// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.ChatViewModel
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.tooling.WorkspaceRegistry

/**
 * Regression for the v1.1.0 IME freeze after Send: run preparation probed every workspace
 * backend (SAF ContentResolver queries on a real device) on the Main thread.  This backend
 * records the thread of every capability read; preparation must never read it on Main.
 */
@RunWith(AndroidJUnit4::class)
class ChatSendOffMainThreadDeviceTest {
    @Test
    fun runPreparationNeverProbesWorkspaceBackendsOnMain() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val runtime = container.runtimeIntegration
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val recording = AtomicBoolean(false)
        val mainReads = AtomicInteger()
        val workerReads = AtomicInteger()
        val requestReceived = CountDownLatch(1)
        val released = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serving = Thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 15_000
                        val input = socket.getInputStream()
                        val header = ByteArrayOutputStream()
                        var tail = 0
                        while (header.size() < 16_384) {
                            val next = input.read()
                            check(next >= 0)
                            header.write(next)
                            tail = (tail shl 8) or next
                            if (tail == 0x0d0a0d0a) break
                        }
                        // Preparation is complete once the model request reaches the wire.
                        recording.set(false)
                        requestReceived.countDown()
                        val length = header.toString("US-ASCII").split("\r\n")
                            .first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        repeat(length) { check(input.read() >= 0) }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            write("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n".toByteArray())
                            write("data: [DONE]\n\n".toByteArray())
                            flush()
                        }
                        released.await(10, TimeUnit.SECONDS)
                    }
                }
            }.apply { isDaemon = true; start() }

            val secretRef = "synthetic-offmain-$suffix"
            container.secrets.put(secretRef, "synthetic-only".toCharArray())
            val provider = ProviderProfile("provider-offmain-$suffix", "Synthetic", ApiFormat.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.localPort}/v1", secretRef = secretRef, revision = 1)
            val model = ModelProfile("model-offmain-$suffix", provider.id, ModelRole.CHAT, "synthetic",
                setOf("stream"), contextLimit = 32_768, outputLimit = 512, revision = 1)
            container.profiles.createProvider(provider)
            container.profiles.createModel(model)
            val agentId = "agent-offmain-$suffix"
            container.agents.saveWithPrompt(AgentProfile(agentId, "Synthetic", "pending", model.id, revision = 0), "Reply briefly.")

            val workspace = WorkspaceRepository(container.db).save(
                Workspace(
                    id = "workspace-offmain-$suffix",
                    displayName = "Probe-recording workspace",
                    backendType = WorkspaceBackendType.INTERNAL,
                    rootReference = "fixture-offmain-root-$suffix",
                    readable = true,
                    writable = false,
                    quotaBytes = 4L * 1024L * 1024L,
                    maxFileBytes = 256L * 1024L,
                    enabled = true,
                    scope = WorkspaceScope.SELECTED_DIRECTORY,
                ),
            )
            val readCapabilities = setOf(
                CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
                CapabilityId(CapabilityId.FILE_LIST),
                CapabilityId(CapabilityId.FILE_STAT),
                CapabilityId(CapabilityId.FILE_READ_TEXT),
            )
            val backend = object : WorkspaceBackend {
                override val descriptor = WorkspaceDescriptor(
                    id = workspace.id,
                    displayName = workspace.displayName,
                    backendType = workspace.backendType,
                    rootReference = workspace.rootReference,
                    readable = true,
                    writable = false,
                    quotaBytes = workspace.quotaBytes,
                    maxFileBytes = workspace.maxFileBytes,
                    enabled = true,
                    scope = workspace.scope,
                )
                // Stands in for SafWorkspaceBackend, whose capability read is a ContentResolver query.
                override val capabilities: Set<CapabilityId>
                    get() {
                        if (recording.get()) {
                            if (Looper.myLooper() == Looper.getMainLooper()) mainReads.incrementAndGet() else workerReads.incrementAndGet()
                        }
                        return readCapabilities
                    }
            }
            val registryField = RuntimeIntegration::class.java.getDeclaredField("workspaceRegistry")
            registryField.isAccessible = true
            (registryField.get(runtime) as WorkspaceRegistry).registerOrReplace(workspace, backend)
            runtime.useRecentWorkspace(workspaceId = workspace.id, target = WorkspacePickerTarget(agentId = agentId))
                as? WorkspaceAccessResult.Success ?: error("agent default workspace did not commit")

            try {
                lateinit var chat: ChatViewModel
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    chat = ChatViewModel(app, SavedStateHandle())
                    chat.selectAgent(agentId)
                    requireNotNull(chat.newSession())
                    chat.input("hello")
                    recording.set(true)
                    chat.send()
                }
                assertTrue("model request never dispatched", requestReceived.await(30, TimeUnit.SECONDS))
                released.countDown()
                val deadline = System.currentTimeMillis() + 15_000
                while (chat.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(30)

                assertEquals("workspace backend probed on Main during run preparation", 0, mainReads.get())
                assertTrue("the fixture backend was never probed during preparation", workerReads.get() > 0)
            } finally {
                released.countDown()
                serving.join(2_000)
                container.db.execute("DELETE FROM secrets WHERE ref=?", listOf(secretRef))
            }
        }
    }
}
