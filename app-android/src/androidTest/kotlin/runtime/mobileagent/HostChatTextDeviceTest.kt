// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Contract for the migrated Chat host copy.
 *
 * The two locales are resolved for real and compared as text, not only as matching key sets: the
 * English and Chinese strings must differ, every positional placeholder must be filled with the
 * caller's values, and the stable ASCII budget code must survive unchanged in both languages.
 * Nothing here asserts translated wording as a behavioral requirement; only structure, locale
 * wiring and the machine-readable marker are fixed.
 */
@RunWith(AndroidJUnit4::class)
class HostChatTextDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun inLanguage(language: String): Context =
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
            },
        )

    @Test
    fun hostCopyResolvesDifferentlyPerAppLanguage() {
        val english = inLanguage("en-US")
        val chinese = inLanguage("zh-CN")
        listOf(
            R.string.chat_preparing_conversation,
            R.string.chat_operation_failed,
            R.string.chat_knowledge_query_unknown,
            R.string.chat_context_compaction_in_progress,
            R.string.chat_budget_exhausted,
            R.string.chat_cancelled_partial,
            R.string.chat_workspace_not_bound,
            R.string.chat_empty_completed_answer,
            R.string.chat_visual_degraded_without_reason,
            R.string.chat_retrieval_partial_coverage_fallback,
            R.string.chat_user_message_saved_unconfirmed,
            R.string.chat_message_saved_conversation_archived,
        ).forEach { key ->
            val en = english.getString(key)
            val zh = chinese.getString(key)
            assertTrue("empty copy for ${context.resources.getResourceEntryName(key)}", en.isNotBlank() && zh.isNotBlank())
            assertNotEquals("untranslated copy for ${context.resources.getResourceEntryName(key)}", en, zh)
            // The English default must not fall back to Chinese text (a common half-migration bug).
            assertTrue(
                "English resource still contains CJK for ${context.resources.getResourceEntryName(key)}",
                en.none { it.code in 0x3400..0x9FFF },
            )
            assertTrue(
                "Chinese resource lost CJK for ${context.resources.getResourceEntryName(key)}",
                zh.any { it.code in 0x3400..0x9FFF },
            )
        }
    }

    @Test
    fun positionalPlaceholdersAreFilledFromArguments() {
        val english = inLanguage("en-US")
        val chinese = inLanguage("zh-CN")
        listOf(english, chinese).forEach { localized ->
            val budget = localized.getString(
                R.string.chat_budget_exceeded,
                11L, 22L, 33, 44L, 55L, 66L, 77L, 88L, 99L, 3, 4,
            )
            listOf("11", "22", "33", "44", "55", "66", "77", "88", "99").forEach { value ->
                assertTrue("budget copy lost argument $value: $budget", budget.contains(value))
            }
            assertEquals(
                "budget copy must carry exactly one stable ASCII code",
                1,
                Regex("CONTEXT_OVERFLOW").findAll(budget).count(),
            )
            assertTrue(
                "no placeholder may survive formatting: $budget",
                !budget.contains("%1\$s") && !budget.contains("%s"),
            )

            val sending = localized.getString(R.string.chat_sending_to_target, "api.example.invalid", "synthetic-model")
            assertTrue("sending copy lost host argument: $sending", sending.contains("api.example.invalid"))
            assertTrue("sending copy lost model argument: $sending", sending.contains("synthetic-model"))

            val page = localized.getString(R.string.chat_citation_page_label, "7", "citation-abc")
            assertTrue("citation label lost page: $page", page.contains("7"))
            assertTrue("citation label lost locator: $page", page.contains("citation-abc"))

            val images = localized.getString(R.string.chat_image_attachment_limit, "8", "2 MiB")
            assertTrue("image limit lost count: $images", images.contains("8"))
            assertTrue("image limit lost size: $images", images.contains("2 MiB"))

            val usage = localized.getString(R.string.chat_completed_usage, 120L, 340L)
            assertTrue("usage line lost input tokens: $usage", usage.contains("120"))
            assertTrue("usage line lost output tokens: $usage", usage.contains("340"))

            val recordSave = localized.getString(R.string.chat_record_save_failed, "synthetic-detail")
            assertTrue("record-save copy lost detail: $recordSave", recordSave.contains("synthetic-detail"))

            val degraded = fillSinglePlaceholder(
                localized.getString(R.string.chat_visual_degraded_prefix),
                "RUN_IMAGE_BUDGET_EXCEEDED",
            )
            assertTrue("degradation copy lost warning: $degraded", degraded.contains("RUN_IMAGE_BUDGET_EXCEEDED"))
            assertTrue("degradation template left a placeholder: $degraded", !degraded.contains("%1\$s"))

            val batchAnalyzed = localized.getString(R.string.chat_visual_batch_analyzed, "3")
            assertTrue("visual batch copy lost image count: $batchAnalyzed", batchAnalyzed.contains("3"))
            val toolImages = localized.getString(R.string.chat_tool_attached_images, "2")
            assertTrue("tool attached copy lost image count: $toolImages", toolImages.contains("2"))
            val incomplete = localized.getString(R.string.chat_turn_incomplete_retained, "STREAMING")
            assertTrue("incomplete turn copy lost status: $incomplete", incomplete.contains("STREAMING"))
            val snapshotLabel = localized.getString(R.string.chat_configuration_snapshot_label, "snap1234")
            assertTrue("snapshot label lost prefix: $snapshotLabel", snapshotLabel.contains("snap1234"))
        }
    }

    @Test
    fun stableBudgetCodeIsLocaleIndependent() {
        val english = inLanguage("en-US").getString(R.string.chat_budget_exceeded, 1L, 2L, 3, 4L, 5L, 6L, 7L, 8L, 9L, 1, 2)
        val chinese = inLanguage("zh-CN").getString(R.string.chat_budget_exceeded, 1L, 2L, 3, 4L, 5L, 6L, 7L, 8L, 9L, 1, 2)
        assertTrue(english.contains(CHAT_CONTEXT_OVERFLOW_CODE))
        assertTrue(chinese.contains(CHAT_CONTEXT_OVERFLOW_CODE))
        // Recovery UI matches this marker; it must never be translated.
        assertNotEquals(english, chinese)
    }

    @Test
    fun runNoticesFollowTheRequestedLanguageAndKeepTheirFields() {
        val english = inLanguage("en-US")
        val chinese = inLanguage("zh-CN")
        val enNotices = noticeTexts(english)
        val zhNotices = noticeTexts(chinese)
        assertNotEquals("interruption copy did not follow the language", enNotices, zhNotices)
        enNotices.forEach { assertTrue("English notice contains CJK: $it", it.none { c -> c.code in 0x3400..0x9FFF }) }
        zhNotices.forEach { assertTrue("Chinese notice missing CJK: $it", it.any { c -> c.code in 0x3400..0x9FFF }) }
        // Run-specific numbers stay in the copy regardless of language.
        listOf(enNotices, zhNotices).forEach { notices ->
            assertTrue("usage line lost the run tokens", notices.any { it.contains("1234") && it.contains("56") })
        }
    }

    private fun noticeTexts(localized: Context): List<String> = listOf(
        localized.getString(R.string.chat_completed_usage, 1234, 56),
        localized.getString(R.string.chat_cancelled_partial_unknown_outcome_message),
        localized.getString(R.string.chat_cancelled_partial_message),
        localized.getString(R.string.chat_runtime_limit_reached),
        localized.getString(R.string.chat_budget_exhausted),
        localized.getString(R.string.chat_cancelled_unknown_outcome),
        localized.getString(R.string.chat_cancelled_partial),
    )

    @Test
    fun hostChatTextUsesTheAttachedAppLanguageSource() {
        val preferences = context.getSharedPreferences(
            runtime.mobileagent.ui.ActivityLocaleConfiguration.PREFERENCES, Context.MODE_PRIVATE)
        val key = runtime.mobileagent.ui.ActivityLocaleConfiguration.KEY
        val hadPreference = preferences.contains(key)
        val previous = preferences.getString(key, null)
        try {
            for (language in listOf("en-US", "zh-CN")) {
                assertTrue(preferences.edit().putString(key, language).commit())
                assertEquals(inLanguage(language).getString(R.string.chat_preparing_conversation),
                    context.hostChatText(R.string.chat_preparing_conversation))
                assertEquals(inLanguage(language).getString(R.string.chat_completed_usage, 1234, 56),
                    context.hostChatText(R.string.chat_completed_usage, 1234, 56))
            }
            assertTrue(preferences.edit().putString(key, "system").commit())
            val system = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(android.content.res.Resources.getSystem().configuration.locales)
            })
            assertEquals(system.getString(R.string.chat_preparing_conversation),
                context.hostChatText(R.string.chat_preparing_conversation))
        } finally {
            val editor = preferences.edit()
            if (hadPreference) editor.putString(key, previous) else editor.remove(key)
            assertTrue("Test locale preference could not be restored", editor.commit())
        }
    }

    @Test
    fun unformattedTemplatesRemainAvailableToToolResultProjection() {
        val preferences = context.getSharedPreferences(
            runtime.mobileagent.ui.ActivityLocaleConfiguration.PREFERENCES, Context.MODE_PRIVATE)
        val key = runtime.mobileagent.ui.ActivityLocaleConfiguration.KEY
        val hadPreference = preferences.contains(key)
        val previous = preferences.getString(key, null)
        val withoutApplication = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context? = null
        }
        try {
            for (language in listOf("en-US", "zh-CN")) {
                assertTrue(preferences.edit().putString(key, language).commit())
                val localized = inLanguage(language)
                val template = localized.getString(R.string.chat_visual_degraded_prefix)
                assertTrue("The projection requires an unformatted placeholder", template.contains('%'))
                for (source in listOf(context, withoutApplication)) {
                    assertEquals(template, source.hostChatText(R.string.chat_visual_degraded_prefix))
                    assertEquals(
                        localized.getString(R.string.chat_visual_degraded_prefix, "VISION_NOT_CONFIGURED"),
                        source.hostChatText(R.string.chat_visual_degraded_prefix, "VISION_NOT_CONFIGURED"),
                    )
                }
            }
        } finally {
            val editor = preferences.edit()
            if (hadPreference) editor.putString(key, previous) else editor.remove(key)
            assertTrue("Test locale preference could not be restored", editor.commit())
        }
    }
}
