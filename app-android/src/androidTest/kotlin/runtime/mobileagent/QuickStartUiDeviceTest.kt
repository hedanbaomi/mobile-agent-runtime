// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.ui.AppRoutes
import runtime.mobileagent.ui.QuickStartContent

@RunWith(AndroidJUnit4::class)
class QuickStartUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun everyStepActsAndCollapsedSetupCanResume() {
        val step = mutableStateOf(QuickStartStep.MODEL)
        val collapsed = mutableStateOf(false)
        val routes = mutableListOf<String>()
        var choices = 0
        compose.setContent { MaterialTheme {
            QuickStartContent(step.value, collapsed.value, { collapsed.value = it }, { choices++ }, { routes += it })
        } }
        val context = compose.activity
        val next = context.getString(R.string.quick_start_continue)
        compose.onNodeWithText(next).performClick()
        compose.runOnIdle { step.value = QuickStartStep.AGENT }
        compose.onNodeWithText(next).performClick()
        compose.runOnIdle { step.value = QuickStartStep.MESSAGE }
        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(context.getString(R.string.quick_start_later)).performClick()
        compose.onNodeWithText(next).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.quick_start_resume)).performClick()
        compose.onNodeWithText(next).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(AppRoutes.PROVIDERS, AppRoutes.AGENTS), routes)
            assertEquals(1, choices)
            step.value = QuickStartStep.COMPLETE
        }
        compose.onNodeWithText(next).assertDoesNotExist()
    }
}
