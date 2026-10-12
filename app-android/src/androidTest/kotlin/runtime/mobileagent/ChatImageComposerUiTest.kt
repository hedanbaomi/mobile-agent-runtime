// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.chat.CHAT_MAX_PENDING_IMAGES
import runtime.mobileagent.feature.chat.ChatActions
import runtime.mobileagent.feature.chat.ChatPendingImageUi
import runtime.mobileagent.feature.chat.ChatSessionUi
import runtime.mobileagent.feature.chat.ChatUiState
import runtime.mobileagent.feature.chat.ConversationScreen

@RunWith(AndroidJUnit4::class)
class ChatImageComposerUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private fun state(images: Int, streaming: Boolean = false) = ChatUiState(
        sessions = listOf(ChatSessionUi("session", "会话")),
        selectedSessionId = "session",
        streaming = streaming,
        pendingImages = (1..images).map { ChatPendingImageUi("image-$it", "image/png", 100) },
    )

    @Test
    fun imageOnlyDraftCanBeSentAndEachImageRemoved() {
        var picks = 0
        var sends = 0
        val removed = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ConversationScreen(
                    state = state(images = 2),
                    actions = ChatActions(onSend = { sends++ }, onPickImages = { picks++ }, onRemovePendingImage = { removed += it }),
                )
            }
        }
        compose.onNodeWithTag("conversation.composer.pendingImages").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(runtime.mobileagent.feature.chat.R.string.chat_pending_image, 1)).assertIsDisplayed()
        compose.onNodeWithTag("conversation.composer.send").assertIsEnabled().performClick()
        compose.onNodeWithTag("conversation.composer.attachImage").assertIsEnabled().performClick()
        compose.onNodeWithTag("conversation.composer.removeImage.image-2").performClick()
        assertEquals(1, sends)
        assertEquals(1, picks)
        assertEquals(listOf("image-2"), removed)
    }

    @Test
    fun attachIsDisabledAtTheLimitAndWhileStreaming() {
        var current by mutableStateHolder(state(images = CHAT_MAX_PENDING_IMAGES))
        compose.setContent {
            MaterialTheme { ConversationScreen(state = current, actions = ChatActions(onPickImages = {})) }
        }
        compose.onNodeWithTag("conversation.composer.attachImage").assertIsNotEnabled()
        current = state(images = 1, streaming = true)
        compose.waitForIdle()
        compose.onNodeWithTag("conversation.composer.attachImage").assertIsNotEnabled()
        compose.onNodeWithTag("conversation.composer.removeImage.image-1").assertDoesNotExist()
    }

    @Test
    fun hostWithoutUploadSupportHidesAttachButton() {
        compose.setContent { MaterialTheme { ConversationScreen(state = state(images = 0), actions = ChatActions()) } }
        compose.onNodeWithTag("conversation.composer.attachImage").assertDoesNotExist()
        compose.onNodeWithTag("conversation.composer.send").assertIsNotEnabled()
    }
}

private fun <T> mutableStateHolder(value: T) = androidx.compose.runtime.mutableStateOf(value)
