// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import runtime.mobileagent.domain.trpg.TrpgFact
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.domain.trpg.TrpgCampaign
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.ui.TrpgCampaignDialog

/**
 * Compose regression: a rejected command must keep the editor and every entered value.
 * Device execution is owned by the main Agent; this source only has to compile.
 */
class TrpgCampaignDialogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun factEditorKeepsOpeningRevisionAfterExternalWrite() {
        var live by mutableStateOf(TrpgSession("campaign", 3, TrpgState(campaign = TrpgCampaign(facts = listOf(TrpgFact("f", "Old"))))))
        var submitted = -1L
        compose.setContent { MaterialTheme {
            TrpgCampaignDialog(live, true, false, null, true, emptyList(),
                onSave = { _, _ -> error("unused") },
                onCommand = { revision, _ -> submitted = revision; error("revision conflict") },
                onRollCheck = { _, _ -> error("unused") }, onSource = {}, onInsertDraft = {}, onDismiss = {}, onOpenJournal = {})
        } }
        compose.onNodeWithTag("trpg.campaign.tab.2").performClick()
        compose.onNodeWithTag("trpg.campaign.fact.edit.f").performClick()
        compose.onNodeWithTag("trpg.campaign.fact.text").performTextReplacement("Local draft")
        compose.runOnIdle { live = live.copy(revision = 4, state = live.state.copy(campaign = live.state.campaign.copy(facts = listOf(TrpgFact("f", "Remote"))))) }
        compose.onNodeWithTag("trpg.campaign.fact.apply").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(3L, submitted)
        compose.onNodeWithTag("trpg.campaign.fact.text").assertTextContains("Local draft")
        org.junit.Assert.assertEquals("Remote", live.state.campaign.facts.single().text)
    }

    @Test fun failedFactCommandKeepsEditorAndInput() {
        val session = TrpgSession("campaign-1", 3, TrpgState(campaign = TrpgCampaign()))
        compose.setContent {
            MaterialTheme {
                TrpgCampaignDialog(
                    session = session,
                    writable = true,
                    saving = false,
                    error = null,
                    chinese = true,
                    recentMessages = emptyList(),
                    onSave = { _, _ -> throw IllegalStateException("save rejected") },
                    onCommand = { _, _ -> throw IllegalStateException("command rejected") },
                    onRollCheck = { _, _ -> throw IllegalStateException("roll rejected") },
                    onSource = {},
                    onInsertDraft = {},
                    onDismiss = {},
                    onOpenJournal = {},
                )
            }
        }
        compose.onNodeWithTag("trpg.campaign.tab.2").performClick()
        compose.onNodeWithTag("trpg.campaign.fact.create").performClick()
        compose.onNodeWithTag("trpg.campaign.fact.text").performTextInput("门后是空的")
        compose.onNodeWithTag("trpg.campaign.fact.apply").performClick()
        compose.waitForIdle()
        // Failure keeps the editor open and the typed text intact (nothing is closed early).
        compose.onNodeWithTag("trpg.campaign.fact.editor").assertExists()
        compose.onNodeWithTag("trpg.campaign.fact.text").assertTextContains("门后是空的", substring = true)
    }
}
