// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.domain.trpg.TrpgCampaign
import runtime.mobileagent.domain.trpg.TrpgPreferences
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.domain.trpg.TrpgStory
import runtime.mobileagent.domain.trpg.TrpgStoryChapter
import runtime.mobileagent.domain.trpg.TrpgViewRole
import runtime.mobileagent.ui.TrpgStoryDialog

/**
 * Compose regression coverage for the story dialog. Device execution is owned by the main Agent;
 * these sources only have to compile in this task.
 */
class TrpgStoryDialogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private fun session(revision: Long = 4) = TrpgSession(
        conversationId = "story-1",
        revision = revision,
        state = TrpgState(campaign = TrpgCampaign(preferences = TrpgPreferences(viewRole = TrpgViewRole.GAME_MASTER))),
    )

    private val story = TrpgStory(campaignName = "Campaign", chapters = listOf(TrpgStoryChapter("chapter-1", "Chapter One")))

    @Test fun failedClockCommandKeepsEditorAndDraftValues() {
        compose.setContent {
            MaterialTheme {
                TrpgStoryDialog(
                    session = session(),
                    story = story,
                    writable = true,
                    saving = false,
                    error = null,
                    chinese = true,
                    recentMessages = emptyList(),
                    onCommand = { _, _ -> throw IllegalStateException("command rejected") },
                    onSource = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("trpg.story.tab.2").performClick()
        compose.onNodeWithTag("trpg.story.track.create.CLOCK").performClick()
        compose.onNodeWithTag("trpg.story.track.editor.title").performTextInput("Doom")
        compose.onNodeWithTag("trpg.story.track.editor.progress").performTextInput("3")
        compose.onNodeWithTag("trpg.story.track.editor.apply").performClick()
        compose.waitForIdle()
        // Failure keeps the editor open and the typed values intact (nothing is closed early).
        compose.onNodeWithTag("trpg.story.track.editor").assertExists()
        compose.onNodeWithTag("trpg.story.track.editor.title").assertTextContains("Doom", substring = true)
        compose.onNodeWithTag("trpg.story.track.editor.progress").assertTextContains("3", substring = true)
    }

    @Test fun externalRevisionKeepsTheEditorDraftAndShowsConflict() {
        val current = mutableStateOf(session(revision = 3))
        compose.setContent {
            MaterialTheme {
                TrpgStoryDialog(
                    session = current.value,
                    story = story,
                    writable = true,
                    saving = false,
                    error = null,
                    chinese = false,
                    recentMessages = emptyList(),
                    onCommand = { _, _ -> throw IllegalStateException("must not be called with a stale baseline") },
                    onSource = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("trpg.story.tab.2").performClick()
        compose.onNodeWithTag("trpg.story.track.create.CLOCK").performClick()
        compose.onNodeWithTag("trpg.story.track.editor.title").performTextInput("Doom draft")
        compose.runOnUiThread { current.value = current.value.copy(revision = 4) }
        compose.waitForIdle()
        compose.onNodeWithTag("trpg.story.editor.conflict").assertExists()
        compose.onNodeWithTag("trpg.story.track.editor.title").assertTextContains("Doom draft", substring = true)
    }

    @Test fun invalidProgressKeepsTheRawDraftInsteadOfClamping() {
        compose.setContent {
            MaterialTheme {
                TrpgStoryDialog(
                    session = session(),
                    story = story,
                    writable = true,
                    saving = false,
                    error = null,
                    chinese = true,
                    recentMessages = emptyList(),
                    onCommand = { _, _ -> throw IllegalStateException("invalid input must not reach the host") },
                    onSource = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("trpg.story.tab.2").performClick()
        compose.onNodeWithTag("trpg.story.track.create.CLOCK").performClick()
        compose.onNodeWithTag("trpg.story.track.editor.title").performTextInput("Doom")
        compose.onNodeWithTag("trpg.story.track.editor.progress").performTextClearance()
        compose.onNodeWithTag("trpg.story.track.editor.progress").performTextInput("99")
        compose.onNodeWithTag("trpg.story.track.editor.apply").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("trpg.story.track.editor").assertExists()
        compose.onNodeWithTag("trpg.story.track.editor.progress").assertTextContains("99", substring = true)
        compose.onNodeWithTag("trpg.story.editor.error").assertExists()
    }
}
