// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.activity.compose.setContent
import android.content.Context
import runtime.mobileagent.domain.LocalePreference
import runtime.mobileagent.ui.ActivityLocaleConfiguration
import runtime.mobileagent.feature.knowledge.R as KnowledgeR
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.ui.AppLocalizedResources

@RunWith(AndroidJUnit4::class)
class AppLocalizedResourcesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun explicitAppLanguageOverridesDeviceResourcesAndRecomposes() {
        val language = mutableStateOf("en-US")
        val deviceLanguage = mutableStateOf("zh-CN")
        compose.setContent {
            val currentConfiguration = LocalConfiguration.current
            val deviceConfiguration = remember(currentConfiguration, deviceLanguage.value) {
                Configuration(currentConfiguration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(deviceLanguage.value)))
                }
            }
            CompositionLocalProvider(LocalConfiguration provides deviceConfiguration) {
                AppLocalizedResources(language.value) {
                    val context = LocalContext.current
                    var current = context
                    while (current is ContextWrapper && current !is Activity) current = current.baseContext
                    assertTrue("Localized Context preserves the Activity chain", current is Activity)
                    assertEquals(language.value, context.resources.configuration.locales[0].toLanguageTag())
                    Text(stringResource(R.string.nav_agents))
                }
            }
        }
        compose.onNodeWithText("Agents").assertIsDisplayed()
        compose.runOnUiThread {
            deviceLanguage.value = "en-US"
            language.value = "zh-CN"
        }
        compose.onNodeWithText("智能体").assertIsDisplayed()
        compose.runOnUiThread { language.value = "en-US" }
        compose.onNodeWithText("Agents").assertIsDisplayed()
    }

    @Test fun activityLocaleRecreationLocalizesRealAlertDialogInBothDirections() {
        val preferences = compose.activity.getSharedPreferences(ActivityLocaleConfiguration.PREFERENCES, Context.MODE_PRIVATE)
        val original = preferences.getString(ActivityLocaleConfiguration.KEY, null)
        try {
            showDialogWithActivityLocale(LocalePreference.EN_US, "en-US")
            listOf("Local storage budget", "Changing the limit does not delete content. Shared content and unfinished tasks are retained.", "Cancel", "Save").forEach {
                compose.onNodeWithText(it).assertIsDisplayed()
            }
            showDialogWithActivityLocale(LocalePreference.ZH_CN, "zh-CN")
            listOf("本地存储预算", "修改上限不会删除已有资料；共享内容和未结束任务不会被回收。", "取消", "保存").forEach {
                compose.onNodeWithText(it).assertIsDisplayed()
            }
            compose.onNodeWithText("Local storage budget").assertDoesNotExist()
            showDialogWithActivityLocale(LocalePreference.EN_US, "en-US")
            listOf("Local storage budget", "Changing the limit does not delete content. Shared content and unfinished tasks are retained.", "Cancel", "Save").forEach {
                compose.onNodeWithText(it).assertIsDisplayed()
            }
            compose.onNodeWithText("本地存储预算").assertDoesNotExist()
        } finally {
            preferences.edit().apply {
                if (original == null) remove(ActivityLocaleConfiguration.KEY)
                else putString(ActivityLocaleConfiguration.KEY, original)
            }.commit()
        }
    }

    private fun showDialogWithActivityLocale(preference: LocalePreference, language: String) {
        val previous = compose.activity
        val mirrored = previous.getSharedPreferences(ActivityLocaleConfiguration.PREFERENCES, Context.MODE_PRIVATE)
            .getString(ActivityLocaleConfiguration.KEY, "system")
        compose.activityRule.scenario.onActivity { it.syncLocalePreference(preference) }
        if (mirrored != language) {
            compose.waitUntil(5_000) { compose.activity !== previous }
        }
        compose.activityRule.scenario.onActivity { activity ->
            assertEquals(language, activity.resources.configuration.locales[0].toLanguageTag())
            activity.setContent {
                AppLocalizedResources(language) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(KnowledgeR.string.ui_local_storage_budget_dc31eacc)) },
                        text = { Text(stringResource(KnowledgeR.string.ui_changing_the_limit_does_not_delete_f595fe9f)) },
                        confirmButton = { TextButton(onClick = {}) { Text(stringResource(KnowledgeR.string.ui_save_ec8e6d58)) } },
                        dismissButton = { TextButton(onClick = {}) { Text(stringResource(KnowledgeR.string.ui_cancel_998b9c48)) } },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

}
