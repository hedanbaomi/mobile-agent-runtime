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

class KnowledgeBatchUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
