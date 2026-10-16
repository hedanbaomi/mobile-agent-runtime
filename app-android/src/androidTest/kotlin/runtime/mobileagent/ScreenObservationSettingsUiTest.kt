// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.settings.SettingsActions
import runtime.mobileagent.feature.settings.SettingsScreen
import runtime.mobileagent.feature.settings.SettingsUiState

class ScreenObservationSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun actionsHaveSeparateOptInAndCannotEnableBeforeObservationConsent() {
        var actionsAllowed = false
        val observation = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { SettingsScreen(SettingsUiState(language = "en-US", screenObservationEnabled = observation.value),
            SettingsActions(onScreenActionsEnabled = { actionsAllowed = it })) }
        compose.onNodeWithTag("settings.screen_observation.actions").performScrollTo().assertIsNotEnabled()
        compose.runOnUiThread { observation.value = true }
        compose.onNodeWithTag("settings.screen_observation.actions").assertIsEnabled().performClick()
        compose.onNodeWithText("Allow assisted control?").assertIsDisplayed()
        assertFalse(actionsAllowed)
        compose.onNodeWithText("Allow actions", useUnmergedTree = true).performClick()
        assertTrue(actionsAllowed)
    }

    @Test fun enablingRequiresDisclosureAndSystemSettingsIsAnExplicitAction() {
        var consent = false
        var settingsOpened = false
        compose.setContent { SettingsScreen(SettingsUiState(language = "en-US"), SettingsActions(
            onScreenObservationEnabled = { consent = it }, onOpenAccessibilitySettings = { settingsOpened = true },
        )) }
        compose.onNodeWithTag("settings.screen_observation.enabled").performScrollTo().performClick()
        compose.onNodeWithText("Allow screen content requests?").assertIsDisplayed()
        assertFalse(consent)
        compose.onNodeWithText("Allow requests", useUnmergedTree = true).performClick()
        assertTrue(consent)
        assertFalse(settingsOpened)
        compose.onNodeWithTag("settings.screen_observation.settings").performScrollTo().performClick()
        assertTrue(settingsOpened)
    }
}
