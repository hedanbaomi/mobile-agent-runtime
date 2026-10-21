// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.domain.trpg.TrpgAtlas
import runtime.mobileagent.domain.trpg.TrpgAtlasCommand
import runtime.mobileagent.domain.trpg.TrpgAtlasOperation
import runtime.mobileagent.domain.trpg.TrpgCampaign
import runtime.mobileagent.domain.trpg.TrpgEdge
import runtime.mobileagent.domain.trpg.TrpgLocation
import runtime.mobileagent.domain.trpg.TrpgPreferences
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.domain.trpg.TrpgViewRole
import runtime.mobileagent.domain.trpg.TrpgVisibility
import runtime.mobileagent.ui.TrpgAtlasDialog

/**
 * Compose regressions for the atlas panel: pin selection, player filtering, explicit map placement
 * and draft preservation after a rejected save. Device execution is owned by the main Agent;
 * these sources only have to compile here.
 */
class TrpgAtlasDialogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun largeBasemapIsDecodedWithinPixelAndEdgeBudget() {
        val source = android.graphics.Bitmap.createBitmap(4096, 4096, android.graphics.Bitmap.Config.RGB_565)
        val bytes = java.io.ByteArrayOutputStream().also { source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        source.recycle()
        val decoded = checkNotNull(TrpgAtlasBasemap.decodeSampled(bytes))
        try {
            org.junit.Assert.assertTrue(decoded.width <= 2048 && decoded.height <= 2048)
            org.junit.Assert.assertTrue(decoded.width.toLong() * decoded.height <= 2048L * 2048L)
        } finally { decoded.recycle() }
    }

    private fun session(role: TrpgViewRole = TrpgViewRole.PLAYER) = TrpgSession(
        "atlas-1", 4,
        TrpgState(campaign = TrpgCampaign(preferences = TrpgPreferences(viewRole = role))),
    )

    private val village = TrpgLocation("village", "Village", x = 120, y = 800)
    private val keep = TrpgLocation("keep", "Keep", x = 700, y = 240)
    private val secret = TrpgLocation("secret", "Secret", x = 500, y = 500, visibility = TrpgVisibility.GAME_MASTER)

    private fun content(
        atlas: TrpgAtlas,
        session: TrpgSession = session(),
        onCommand: suspend (Long, TrpgAtlasCommand) -> TrpgSession = { _, _ ->
            throw IllegalStateException("command rejected")
        },
    ) {
        compose.setContent {
            MaterialTheme {
                TrpgAtlasDialog(
                    session = session,
                    atlas = atlas,
                    writable = true,
                    saving = false,
                    error = null,
                    chinese = true,
                    onCommand = onCommand,
                    onDismiss = {},
                )
            }
        }
    }

    @Test fun selectingAPinShowsItsDetailsAndSendsSetCurrent() {
        var captured: TrpgAtlasCommand? = null
        content(
            atlas = TrpgAtlas(locations = listOf(village, keep), currentLocationId = "keep"),
            onCommand = { _, command -> captured = command; throw IllegalStateException("reject") },
        )
        compose.onNodeWithTag("trpg.atlas.pin.village").assertExists().performClick()
        compose.onNodeWithTag("trpg.atlas.selection").assertExists()
        compose.onNodeWithTag("trpg.atlas.selection.setcurrent").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(TrpgAtlasOperation.SET_CURRENT, captured?.operation)
        org.junit.Assert.assertEquals("village", captured?.locationId)
        compose.onNodeWithTag("trpg.atlas.selection").assertExists()
    }

    @Test fun hiddenLocationsAndTheirEdgesNeverAppearForPlayers() {
        content(
            atlas = TrpgAtlas(
                locations = listOf(village, secret),
                edges = listOf(TrpgEdge("peek", "village", "secret")),
            ),
        )
        compose.onNodeWithTag("trpg.atlas.pin.village").assertExists()
        compose.onNodeWithTag("trpg.atlas.pin.secret").assertDoesNotExist()
        compose.onNodeWithTag("trpg.atlas.tab.1").performClick()
        compose.onNodeWithTag("trpg.atlas.location.row.secret").assertDoesNotExist()
        compose.onNodeWithTag("trpg.atlas.edge.row.peek").assertDoesNotExist()
        compose.onNodeWithTag("trpg.atlas.location.row.village").assertExists()
    }

    @Test fun gameMasterViewStillShowsHiddenLocations() {
        content(
            atlas = TrpgAtlas(locations = listOf(village, secret)),
            session = session(TrpgViewRole.GAME_MASTER),
        )
        compose.onNodeWithTag("trpg.atlas.pin.secret").assertExists()
        compose.onNodeWithTag("trpg.atlas.pin.village").assertExists()
    }

    @Test fun tappingTheMapDuringPlacementPrefillsRealCoordinates() {
        content(atlas = TrpgAtlas())
        compose.onNodeWithTag("trpg.atlas.place").performClick()
        compose.onNodeWithTag("trpg.atlas.place.hint").assertExists()
        compose.onNodeWithTag("trpg.atlas.map").performTouchInput {
            click(Offset(width * 0.25f, height * 0.75f))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("trpg.atlas.location.editor").assertExists()
        compose.onNodeWithTag("trpg.atlas.location.editor.x").assertTextContains("250", substring = true)
        compose.onNodeWithTag("trpg.atlas.location.editor.y").assertTextContains("750", substring = true)
    }

    @Test fun rejectedSaveKeepsEditorAndInput() {
        content(atlas = TrpgAtlas(locations = listOf(village)))
        compose.onNodeWithTag("trpg.atlas.location.create").performClick()
        compose.onNodeWithTag("trpg.atlas.location.editor.title").performTextInput("Harbor")
        compose.onNodeWithTag("trpg.atlas.location.editor.apply").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("trpg.atlas.location.editor").assertExists()
        compose.onNodeWithTag("trpg.atlas.location.editor.title").assertTextContains("Harbor", substring = true)
        // The rejection is surfaced in the topmost editor itself, with the typed draft intact.
        compose.onNodeWithTag("trpg.atlas.location.editor.error").assertExists()
    }

    @Test fun externalUpdateBlocksSaveAndOffersExplicitReload() {
        var live by mutableStateOf(session())
        var submittedRevision = -1L
        compose.setContent {
            MaterialTheme {
                TrpgAtlasDialog(
                    session = live, atlas = TrpgAtlas(locations = listOf(village)), writable = true,
                    saving = false, error = null, chinese = true,
                    onCommand = { revision, _ ->
                        submittedRevision = revision
                        throw IllegalStateException("revision conflict")
                    },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("trpg.atlas.location.create").performClick()
        compose.onNodeWithTag("trpg.atlas.location.editor.title").performTextInput("Harbor")
        // An external write advances the session while the editor is open.
        compose.runOnIdle { live = live.copy(revision = 5) }
        compose.onNodeWithTag("trpg.atlas.location.editor.apply").performClick()
        compose.waitForIdle()
        // The captured revision never reaches the host: the stale draft is blocked locally.
        org.junit.Assert.assertEquals(-1L, submittedRevision)
        compose.onNodeWithTag("trpg.atlas.location.editor").assertExists()
        compose.onNodeWithTag("trpg.atlas.location.editor.title").assertTextContains("Harbor", substring = true)
        compose.onNodeWithTag("trpg.atlas.location.editor.conflict").assertExists()
        compose.onNodeWithTag("trpg.atlas.location.editor.reload").assertExists()
    }
}
