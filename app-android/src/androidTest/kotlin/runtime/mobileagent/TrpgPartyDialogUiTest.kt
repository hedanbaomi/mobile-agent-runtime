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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.ui.TrpgPartyDialog

/**
 * Compose regressions for the party panel. Device execution is owned by the main Agent;
 * these sources only have to compile here.
 */
class TrpgPartyDialogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun skillAddRemoveAndResourceRenamePreserveDraftValues() {
        val actor = TrpgActor("hero", "Aria", resources = listOf(TrpgResource("HP", 5, 10), TrpgResource("MP", 3, 5)),
            skills = listOf(TrpgSkill("first", "First", expression = "1d4", dc = 2)),
            recovery = listOf(TrpgRecovery("HP", shortAmount = 2, longAmount = 5), TrpgRecovery("MP", shortAmount = 3, longAmount = 4)))
        var captured: TrpgActor? = null
        compose.setContent { MaterialTheme {
            runtime.mobileagent.ui.PartyActorEditor(actor, true, true, 4, 0, {}, {},
                onSubmit = { captured = it; true })
        } }
        compose.onNodeWithTag("trpg.party.actor.resource.name.0").performScrollTo().performTextReplacement("MP")
        compose.onNodeWithTag("trpg.party.actor.resource.name.0").performTextReplacement("Life")
        compose.onNodeWithTag("trpg.party.actor.skill.add").performScrollTo().performClick()
        compose.onNodeWithTag("trpg.party.actor.skill.name.1").performScrollTo().performTextReplacement("Listen")
        compose.onNodeWithTag("trpg.party.actor.skill.formula.1").performScrollTo().performTextReplacement("1d20+5")
        compose.onNodeWithTag("trpg.party.actor.skill.dc.1").performScrollTo().performTextReplacement("12")
        compose.onNodeWithTag("trpg.party.actor.skill.remove.0").performScrollTo().performClick()
        compose.onNodeWithTag("trpg.party.actor.apply").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals("Listen", captured?.skills?.single()?.name)
        org.junit.Assert.assertEquals("1d20+5", captured?.skills?.single()?.expression)
        org.junit.Assert.assertEquals(12, captured?.skills?.single()?.dc)
        org.junit.Assert.assertEquals(listOf(TrpgRecovery("Life", shortAmount = 2, longAmount = 5),
            TrpgRecovery("MP", shortAmount = 3, longAmount = 4)), captured?.recovery)
    }

    private fun session(party: TrpgParty = TrpgParty()) =
        TrpgSession("party-1", 4, TrpgState(campaign = TrpgCampaign(party = party)))

    private fun content(session: TrpgSession) {
        compose.setContent {
            MaterialTheme {
                TrpgPartyDialog(
                    session = session,
                    writable = true,
                    saving = false,
                    error = null,
                    chinese = true,
                    onCommand = { _, _ -> throw IllegalStateException("command rejected") },
                    onFocusActor = { _, _ -> throw IllegalStateException("focus rejected") },
                    onDismiss = {},
                )
            }
        }
    }

    @Test fun failedActorCommandKeepsEditorAndName() {
        content(session())
        compose.onNodeWithTag("trpg.party.actor.create").performClick()
        compose.onNodeWithTag("trpg.party.actor.name").performTextInput("Aria")
        compose.onNodeWithTag("trpg.party.actor.apply").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("trpg.party.actor.editor").assertExists()
        compose.onNodeWithTag("trpg.party.actor.name").assertTextContains("Aria", substring = true)
    }

    @Test fun actorEditorKeepsOpeningRevisionAfterExternalUpdate() {
        var live by mutableStateOf(session())
        var submittedRevision = -1L
        compose.setContent {
            MaterialTheme {
                TrpgPartyDialog(
                    session = live, writable = true, saving = false, error = null, chinese = true,
                    onCommand = { revision, _ ->
                        submittedRevision = revision
                        throw IllegalStateException("revision conflict")
                    },
                    onFocusActor = { _, _ -> live },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("trpg.party.actor.create").performClick()
        compose.onNodeWithTag("trpg.party.actor.name").performTextInput("Aria")
        // External write advances the session while the editor is open.
        compose.runOnIdle { live = live.copy(revision = 5) }
        compose.onNodeWithTag("trpg.party.actor.apply").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(4L, submittedRevision)
        compose.onNodeWithTag("trpg.party.actor.editor").assertExists()
        compose.onNodeWithTag("trpg.party.actor.name").assertTextContains("Aria", substring = true)
        compose.onNodeWithTag("trpg.party.actor.reload").assertExists()
    }

    @Test fun negativeItemEffectDeltaIsKeptAsTyped() {
        val party = TrpgParty(items = listOf(
            TrpgItem("potion", "Potion", quantity = 1, consumable = true,
                effects = listOf(TrpgResourceDelta("HP", 0))),
        ))
        var captured: TrpgItem? = null
        compose.setContent {
            MaterialTheme {
                TrpgPartyDialog(
                    session = session(party), writable = true, saving = false, error = null, chinese = true,
                    onCommand = { _, command -> captured = command.party?.item; throw IllegalStateException("reject") },
                    onFocusActor = { _, _ -> throw IllegalStateException("reject") },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("trpg.party.tab.1").performClick()
        compose.onNodeWithTag("trpg.party.item.edit.potion").performClick()
        compose.onNodeWithTag("trpg.party.item.effect.amount.HP").performTextReplacement("-3")
        compose.onNodeWithTag("trpg.party.item.apply").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(-3, captured?.effects?.single()?.amount)
        compose.onNodeWithTag("trpg.party.item.editor").assertExists()
        compose.onNodeWithTag("trpg.party.item.effect.amount.HP").assertTextContains("-3", substring = true)
    }

    @Test fun insufficientConsumableStockKeepsDialogAndInput() {
        val party = TrpgParty(
            actors = listOf(TrpgActor("hero", "Aria")),
            items = listOf(TrpgItem("potion", "Potion", ownerId = "hero", quantity = 2, consumable = true,
                effects = listOf(TrpgResourceDelta("HP", 3)))),
        )
        content(session(party))
        compose.onNodeWithTag("trpg.party.tab.1").performClick()
        compose.onNodeWithTag("trpg.party.item.consume.potion").performClick()
        compose.onNodeWithTag("trpg.party.consume.count").performTextInput("5")
        compose.onNodeWithTag("trpg.party.consume.apply").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("trpg.party.consume.dialog").assertExists()
        compose.onNodeWithTag("trpg.party.consume.error").assertExists()
        compose.onNodeWithTag("trpg.party.consume.count").assertTextContains("5", substring = true)
        compose.onNodeWithTag("trpg.party.consume.reload").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("trpg.party.consume.error").assertDoesNotExist()
        compose.onNodeWithTag("trpg.party.consume.count").assertTextContains("1", substring = true)
    }
}
