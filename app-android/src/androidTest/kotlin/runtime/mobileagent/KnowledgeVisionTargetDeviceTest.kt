// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.knowledge.KnowledgeActions
import runtime.mobileagent.feature.knowledge.KnowledgePendingImportUi
import runtime.mobileagent.feature.knowledge.KnowledgeScreen
import runtime.mobileagent.feature.knowledge.KnowledgeUiState
import runtime.mobileagent.feature.knowledge.KnowledgeVisionTargetUi

/**
 * Compose regressions for the create-and-import Vision step: the asynchronous destination list
 * must never be mistaken for "no model", a manually chosen non-default model must travel to the
 * import callback as its exact full-configuration fingerprint, and a staged file selection must
 * survive leaving and re-entering the Knowledge route.
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeVisionTargetDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val emptyModelNotice =
        "No image-capable Vision model is configured. Text-only material can continue; " +
            "a batch pauses with a configuration action if it reaches an image."

    @Test
    fun asynchronousTargetLoadingNeverLooksLikeAnEmptyTargetList() {
        var importedFiles: List<Uri>? = null
        val uri = Uri.parse("content://review/vision-target-async.png")
        val target = KnowledgeVisionTargetUi("fp-async-target", "Provider / image model")
        val state = mutableStateOf(
            KnowledgeUiState(
                language = "en",
                visionTargetsLoading = true,
                pendingImport = KnowledgePendingImportUi("pending-async", listOf(uri), "files"),
            ),
        )
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    KnowledgeScreen(
                        state.value,
                        KnowledgeActions(
                            onImport = { uris, _ -> importedFiles = uris },
                            onSelectPendingVisionTarget = { fingerprint ->
                                state.value = state.value.copy(
                                    pendingImport = state.value.pendingImport?.copy(
                                        selectedVisionTargetFingerprint = fingerprint,
                                        visionTargetSelectionInitialized = true,
                                    ),
                                )
                            },
                        ),
                    )
                }
            }
        }

        // While the profile refresh is in flight, only the loading row may be shown.
        compose.onNodeWithText("Refreshing available Vision models…").assertIsDisplayed()
        compose.onNodeWithText(emptyModelNotice).assertDoesNotExist()
        // ... and the confirmation must stay unavailable until a destination is resolved.
        compose.onAllNodesWithText("Create and start import")[1].assertIsNotEnabled()

        compose.runOnIdle {
            state.value = state.value.copy(visionTargetsLoading = false, visionTargets = listOf(target))
        }
        compose.onNodeWithText("Refreshing available Vision models…").assertDoesNotExist()
        compose.onNodeWithText(emptyModelNotice).assertDoesNotExist()
        compose.onNodeWithText(target.label).assertIsDisplayed()
        compose.onAllNodesWithText("Create and start import")[1].assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(uri), importedFiles) }
    }

    @Test
    fun manuallyChosenNonDefaultTargetFingerprintReachesTheImportCallback() {
        var selectedFingerprint: String? = null
        var destination: String? = null
        val uri = Uri.parse("content://review/vision-target-manual.png")
        val defaultTarget = KnowledgeVisionTargetUi("fp-default-target", "Default provider / model-a")
        val alternateTarget = KnowledgeVisionTargetUi("fp-alternate-target", "Alternate provider / model-b")
        val state = mutableStateOf(
            KnowledgeUiState(
                language = "en",
                visionTargets = listOf(defaultTarget, alternateTarget),
                visionTargetFingerprint = defaultTarget.fingerprint,
                pendingImport = KnowledgePendingImportUi(
                    id = "pending-manual",
                    uris = listOf(uri),
                    sourceKind = "files",
                    selectedVisionTargetFingerprint = defaultTarget.fingerprint,
                    visionTargetSelectionInitialized = true,
                ),
            ),
        )
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    KnowledgeScreen(
                        state.value,
                        KnowledgeActions(
                            onImport = { _, target -> destination = target },
                            onSelectPendingVisionTarget = { fingerprint ->
                                selectedFingerprint = fingerprint
                                state.value = state.value.copy(
                                    pendingImport = state.value.pendingImport?.copy(
                                        selectedVisionTargetFingerprint = fingerprint,
                                        visionTargetSelectionInitialized = true,
                                    ),
                                )
                            },
                        ),
                    )
                }
            }
        }

        // Every image-capable model is offered; picking a non-default one keeps its exact identity.
        compose.onNodeWithText(defaultTarget.label).performClick()
        compose.onNodeWithText(alternateTarget.label).performClick()
        compose.runOnIdle { assertEquals(alternateTarget.fingerprint, selectedFingerprint) }
        compose.onNodeWithText(alternateTarget.label).assertIsDisplayed()
        compose.onAllNodesWithText("Create and start import")[1].assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(alternateTarget.fingerprint, destination) }
    }

    @Test
    fun returningToTheKnowledgeRouteKeepsTheStagedFilesAndDestination() {
        val uri = Uri.parse("content://review/vision-target-kept.png")
        val target = KnowledgeVisionTargetUi("fp-kept-target", "Kept provider / image model")
        val stagedSummary = "1 selected item(s) will be imported into the default knowledge base."
        val state = mutableStateOf(
            KnowledgeUiState(
                language = "en",
                visionTargets = listOf(target),
                visionTargetFingerprint = target.fingerprint,
                pendingImport = KnowledgePendingImportUi(
                    id = "pending-kept",
                    uris = listOf(uri),
                    sourceKind = "files",
                    selectedVisionTargetFingerprint = target.fingerprint,
                    visionTargetSelectionInitialized = true,
                ),
            ),
        )
        val knowledgeVisible = mutableStateOf(true)
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    if (knowledgeVisible.value) {
                        KnowledgeScreen(state.value, KnowledgeActions())
                    } else {
                        Text("Providers")
                    }
                }
            }
        }

        compose.onNodeWithText(stagedSummary).assertIsDisplayed()
        compose.onNodeWithText(target.label).assertIsDisplayed()

        // "Configure and continue": the route composition is disposed while the user is away.
        compose.runOnIdle { knowledgeVisible.value = false }
        compose.onNodeWithText("Providers").assertIsDisplayed()
        compose.onNodeWithText(stagedSummary).assertDoesNotExist()

        // Returning must restore the same staged file and the same confirmed destination.
        compose.runOnIdle { knowledgeVisible.value = true }
        compose.onNodeWithText(stagedSummary).assertIsDisplayed()
        compose.onNodeWithText(target.label).assertIsDisplayed()
    }
}