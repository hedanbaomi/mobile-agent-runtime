// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.announcements.AnnouncementsScreen
import runtime.mobileagent.feature.announcements.AnnouncementsUiState

@RunWith(AndroidJUnit4::class)
class AnnouncementsLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun markAllReadFitsOnOneLineAndMatchesFilterWidthsOnPhone() {
        assertLayout(360.dp)
    }

    @Test
    fun markAllReadFitsOnOneLineAtNarrowWidth() {
        assertLayout(320.dp)
    }

    @Test
    fun markAllReadFitsAtNarrowWidthWithLargerSystemFont() {
        assertLayout(320.dp, fontScale = 1.3f)
    }

    private fun assertLayout(width: Dp, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = fontScale)) {
                Box(Modifier.width(width).height(640.dp)) {
                    MaterialTheme { AnnouncementsScreen(AnnouncementsUiState(language = "zh-CN")) }
                }
            }
        }

        val button = compose.onNodeWithTag("announcements.markAllRead").getUnclippedBoundsInRoot()
        val buttonWidth = button.right - button.left
        val buttonHeight = button.bottom - button.top
        listOf("未读", "全部", "历史").forEach { label ->
            val filter = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("$label filter width differs from mark-all-read", ((filter.right - filter.left) - buttonWidth).value in -1f..1f)
            assertTrue("$label filter height differs from mark-all-read", ((filter.bottom - filter.top) - buttonHeight).value in -1f..1f)
        }
        val text = compose.onNodeWithText("全部标为已读", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("mark-all-read label wraps", text.bottom - text.top <= 28.dp)
        assertTrue("mark-all-read label does not fit", text.right - text.left <= buttonWidth)
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText("全部标为已读", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
        val layout = results.single()
        assertTrue("mark-all-read label is clipped at $width: size=${layout.size}, lineRight=${layout.getLineRight(0)}, lineEnd=${layout.getLineEnd(0)}/${layout.layoutInput.text.length}, font=${layout.layoutInput.style.fontSize}, widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}", layout.hasVisualOverflow.not())
    }
}
