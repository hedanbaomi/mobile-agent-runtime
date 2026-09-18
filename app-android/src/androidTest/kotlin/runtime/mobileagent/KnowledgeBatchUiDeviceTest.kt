// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.knowledge.*
import runtime.mobileagent.knowledge.PipelineProgress
import runtime.mobileagent.knowledge.PipelineUsage
import runtime.mobileagent.knowledge.PipelineReuseSummary

class KnowledgeBatchUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pipelineProgressAndResumeScopeRequireExplicitConfirmation() {
        var resumed: String? = null
        val batch = KnowledgeBatchUi("pipeline", "Synthetic pipeline", "FILES", "PAUSED", 1, 1, 0, 1, 0,
            paused = true, pipeline = PipelineProgress(files = 1, pages = 10, units = 10,
                pending = 5, succeeded = 4, unknown = 1,
                usage = PipelineUsage(inputTokens = 36, outputTokens = 12, reasoningTokens = 8,
                    attempts = 5, unknownUsageAttempts = 1, reservedTokens = 100)),
            reuse = PipelineReuseSummary(directReuse = 4, newRequests = 5, unknown = 1))
        compose.activity.runOnUiThread { compose.activity.setContent { MaterialTheme {
            KnowledgeScreen(KnowledgeUiState(language = "en", batches = listOf(batch)),
                KnowledgeActions(onResumeBatch = { resumed = it }))
        } } }
        compose.onNodeWithText("Pages 10 · Processing units 10").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Provider tokens: input 36 · output 12 · reasoning 8 (included in output)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resume import").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(null, resumed) }
        compose.onNodeWithText("Reuse 4 · Local rebuild 0 · New Provider requests 5 · UNKNOWN needs confirmation 1").assertIsDisplayed()
        compose.onNodeWithText("Confirm resume").performClick()
        compose.runOnIdle { assertEquals("pipeline", resumed) }
    }

    @Test fun changedVisionTargetShowsNewRequestScopeBeforeAuthorization() {
        var authorized: String? = null
        val batch = KnowledgeBatchUi("batch-diff", "Synthetic reconfiguration", "FILES", "BLOCKED", 1, 1, 0, 1, 0,
            reuse = PipelineReuseSummary(directReuse = 4, unknown = 1),
            reuseByTarget = mapOf("new-target" to PipelineReuseSummary(newRequests = 4, unknown = 1)))
        val screen = mutableStateOf(KnowledgeUiState(language = "en", batches = listOf(batch),
            visionTargets = listOf(KnowledgeVisionTargetUi("new-target", "Synthetic model")),
            pendingBatchVision = KnowledgeBatchVisionUi("batch-diff", "new-target", true)))
        compose.activity.runOnUiThread { compose.activity.setContent { MaterialTheme {
            KnowledgeScreen(screen.value,
                KnowledgeActions(onAuthorizeBatchVision = { _, target, acknowledged ->
                    check(acknowledged)
                    authorized = target
                }))
        } } }
        compose.onNodeWithText("Confirm and continue").assertIsNotEnabled()
        compose.runOnIdle { screen.value = screen.value.copy(pendingBatchVision =
            screen.value.pendingBatchVision!!.copy(reusePreview = PipelineReuseSummary(newRequests = 4, unknown = 1))) }
        compose.onNodeWithText("Reuse 0 · Local rebuild 0 · New Provider requests 4 · UNKNOWN needs confirmation 1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, authorized) }
        compose.onNodeWithText("Confirm and continue").assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Confirm and continue").performClick()
        compose.runOnIdle { assertEquals("new-target", authorized) }
    }

    @Test fun completedBatchOffersLocalChunkRebuildWithoutResumeOrVisionAuthorization() {
        var rebuilt: String? = null
        val batch = KnowledgeBatchUi("local", "Synthetic completed batch", "FILES", "COMPLETED", 1, 1, 0, 0, 0,
            published = 1, reuse = PipelineReuseSummary(localRebuild = 10))
        compose.activity.runOnUiThread { compose.activity.setContent { MaterialTheme {
            KnowledgeScreen(KnowledgeUiState(language = "en", batches = listOf(batch)),
                KnowledgeActions(onRebuildBatchLocalChunks = { rebuilt = it },
                    onResumeBatch = { error("Local rebuild must not resume provider work") },
                    onAuthorizeBatchVision = { _, _, _ -> error("Local rebuild must not request Vision") }))
        } } }
        compose.onNodeWithText("Show 0 item details").performScrollTo().performClick()
        compose.onNodeWithText("Rebuild retrieval chunks locally").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("local", rebuilt) }
    }

    @Test fun fileSelectionOpensImportConfirmationWithoutEmbeddingDialog() {
        var imported: List<Uri>? = null
        var destination: String? = null
        val state = mutableStateOf(KnowledgeUiState(language = "en", visionConfigured = true, visionTargetLabel = "Provider / readable label", visionTargetFingerprint = "exact-original-identity"))
        val uri = Uri.parse("content://review/visual.png")
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    dispatchResult(requestCode, listOf(uri))
                }
            }
        }
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme { KnowledgeScreen(state.value, KnowledgeActions(onImport = { uris, target -> imported = uris; destination = target })) }
                }
            }
        }
        compose.onNodeWithText("Add files").performClick()
        compose.onAllNodesWithText("Create and start import")[0].assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(visionTargetLabel = "New provider", visionTargetFingerprint = "changed-identity") }
        compose.onAllNodesWithText("Create and start import")[1].performClick()
        compose.runOnIdle { assertEquals(listOf(uri), imported); assertEquals("exact-original-identity", destination) }
    }
    @Test fun largeBatchStartsCollapsedAndUnknownRetryIsReachableInDetails() {
        var retry: String? = null
        val documents = (1..294).map { KnowledgeDocumentUi("doc-$it", "Document $it", status = "READY") }
        val job = KnowledgeImportJobUi("job-unknown", "Visual sample", "AWAITING_UPLOAD_CONSENT", "UNKNOWN_OUTCOME", unknownOutcome = true)
        val batch = KnowledgeBatchUi("batch", "Corpus", "FILES", "WAITING", 294, 294, 0, 1, 0,
            published = 293, unknown = 1, items = listOf(KnowledgeBatchItemUi(job.id, job.displayName, job.stage, job.error)))
        compose.activity.runOnUiThread { compose.activity.setContent { MaterialTheme {
            KnowledgeScreen(KnowledgeUiState(language = "en", documents = documents, jobs = listOf(job), batches = listOf(batch)),
                KnowledgeActions(onRetryVision = { retry = it }))
        } } }
        compose.onNodeWithText("Corpus").assertIsDisplayed()
        compose.onNodeWithText("Document 1").assertDoesNotExist()
        compose.onNodeWithText("Retry Vision (may charge twice)").assertDoesNotExist()
        compose.onNodeWithText("Show 1 item details").performScrollTo().performClick()
        compose.onNodeWithText("Retry Vision (may charge twice)").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(job.id, retry) }
    }
}
