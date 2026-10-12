// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ChatImageUpload
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile

/** Composer image draft lifetime on the real ViewModel; no model request is made. */
@RunWith(AndroidJUnit4::class)
class ChatViewModelImageDraftTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
    private val gif = ChatImageUpload("GIF89a".toByteArray(), "image/gif")

    private fun fixture(capabilities: Set<String>): Pair<String, String> {
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "")
        container.profiles.createProvider(ProviderProfile("provider.draft.$suffix", "Draft fixture",
            ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", secretRef = "fixture-draft-$suffix", revision = 1))
        container.profiles.createModel(ModelProfile("model.draft.$suffix", "provider.draft.$suffix", ModelRole.CHAT,
            "fixture-draft", capabilities, contextLimit = 4096, outputLimit = 512, revision = 1))
        container.agents.saveWithPrompt(AgentProfile("agent.draft.$suffix", "Draft fixture", "pending",
            "model.draft.$suffix", revision = 0), "Draft fixture.")
        val snapshot = container.agents.createSnapshot("agent.draft.$suffix")
        val first = container.conversations.create(snapshot.id, "Draft A")
        val second = container.conversations.create(snapshot.id, "Draft B")
        return first.id to second.id
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun capLimitRemovalAndSessionSwitchKeepStripTruthful() {
        val (first, second) = fixture(setOf("stream", "image"))
        lateinit var vm: ChatViewModel
        onMain {
            vm = ChatViewModel(app, SavedStateHandle())
            vm.selectSession(first)
            vm.attachImages(List(9) { gif to null })
        }
        assertEquals(ChatImageUpload.MAX_IMAGES_PER_MESSAGE, vm.state.value.pendingImages.size)
        assertTrue(vm.state.value.status.contains("已忽略 1 张"))

        onMain { vm.removePendingImage(vm.state.value.pendingImages.first().id) }
        assertEquals(7, vm.state.value.pendingImages.size)

        // A read that finishes after the user switched pages must not land on the new page.
        onMain { vm.selectSession(second) }
        assertTrue(vm.state.value.pendingImages.isEmpty())
        onMain { vm.attachImages(listOf(gif to null), forSession = first) }
        assertTrue(vm.state.value.pendingImages.isEmpty())
        assertTrue(vm.state.value.status.contains("未添加"))

        onMain { vm.attachImages(listOf(gif to null)); vm.clearImageDraft() }
        assertTrue(vm.state.value.pendingImages.isEmpty())
    }

    @Test
    fun pickerStartedOnNewChatCannotAttachAfterSwitchingAgents() {
        val (first, _) = fixture(setOf("stream", "image"))
        val (second, _) = fixture(setOf("stream", "image"))
        val firstAgent = app.container.agents.getSnapshot(app.container.conversations.get(first)!!.snapshotId)!!.agentId
        val secondAgent = app.container.agents.getSnapshot(app.container.conversations.get(second)!!.snapshotId)!!.agentId
        lateinit var vm: ChatViewModel
        onMain {
            vm = ChatViewModel(app, SavedStateHandle())
            vm.selectAgent(firstAgent)
            vm.selectAgent(secondAgent)
            vm.attachImages(listOf(gif to null), forSession = null, forAgent = firstAgent)
        }
        assertTrue(vm.state.value.pendingImages.isEmpty())
        assertTrue(vm.state.value.status.contains("未添加"))
    }

    @Test
    fun latePickerResultWhileSendingIsRejectedWithVisibleFeedback() {
        val (first, _) = fixture(setOf("stream", "image"))
        onMain {
            val vm = ChatViewModel(app, SavedStateHandle())
            vm.selectSession(first)
            vm.input("synthetic input")
            vm.send()
            assertTrue(vm.state.value.streaming)
            vm.attachImages(listOf(gif to null))
            assertTrue(vm.state.value.pendingImages.isEmpty())
            assertTrue(vm.state.value.status.contains("所选图片未添加"))
        }
    }

    @Test
    fun textOnlyModelRejectsBeforePersistenceAndKeepsDraft() {
        val (first, _) = fixture(setOf("stream"))
        lateinit var vm: ChatViewModel
        onMain {
            vm = ChatViewModel(app, SavedStateHandle())
            vm.selectSession(first)
            vm.input("describe")
            vm.attachImages(listOf(gif to null))
            vm.send()
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(50)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse(vm.state.value.streaming)
        assertTrue(vm.state.value.error.orEmpty().contains("不支持图片"))
        assertEquals(1, vm.state.value.pendingImages.size)
        assertEquals("describe", vm.state.value.input)
        assertTrue(app.container.conversations.messages(first).none { message -> message.parts.any { it is ImagePart } })
    }
}
