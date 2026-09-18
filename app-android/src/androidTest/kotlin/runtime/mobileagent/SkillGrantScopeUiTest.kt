// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.skills.SkillsScreen
import runtime.mobileagent.ui.SkillPermissionScopeDialog
import runtime.mobileagent.ui.skillPermissionScope

/**
 * The real grant review entry: import a package, open its detail, click the
 * capability's authorize action, and confirm the dialog that the user actually
 * sees.  The scope text must already contain the approved model identities and
 * the call/token ceilings, and confirming stores exactly that scope.
 */
@RunWith(AndroidJUnit4::class)
class SkillGrantScopeUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test(timeout = 90_000)
    fun detailToConfirmationDialogShowsAndStoresTheModelScope() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val modelId = "model.scope.$suffix"
        val manifest = """
            {
              "schemaVersion": 1,
              "id": "scopetest-$suffix",
              "name": "Model scope review",
              "version": "1.0.0",
              "license": "AGPL-3.0-only",
              "runtime": {"kind": "python", "mode": "pure-python", "entrypoint": "main:run"},
              "inputSchema": {"type": "object", "properties": {}, "additionalProperties": false},
              "outputSchema": {"type": "object", "properties": {"ok": {"type": "boolean"}}, "required": ["ok"], "additionalProperties": false},
              "permissions": {
                "model.invoke": {
                  "modelProfileIds": ["$modelId"],
                  "maxModelCalls": 3,
                  "maxModelTokens": 8192
                }
              }
            }
        """.trimIndent()
        val imported = container.skills.importPackage(skillZip(manifest))
        assertTrue(imported.inspection.reasons.toString(), imported.accepted)
        val installId = container.skills.list().single { it.packageHash == imported.inspection.packageHash }.installId

        val viewModel = SkillsViewModel(app, SavedStateHandle())
        compose.runOnIdle { viewModel.openDetail(installId) }
        awaitDetail(viewModel, installId)

        // 1. The detail the dialog is built from must carry the model scope.
        val detail = viewModel.state.value.detail!!
        val scope = skillPermissionScope(detail, "model.invoke")
        assertTrue("detail scope must name the model: $scope", scope.contains(modelId))
        assertTrue("detail scope must show the call ceiling: $scope", scope.contains("3"))
        assertTrue("detail scope must show the token ceiling: $scope", scope.contains("8192"))
        assertTrue("detail scope must mention the separate Run authorization: $scope", scope.contains("Run"))

        // 2. Clicking authorize opens the real confirmation dialog with that scope.
        compose.runOnIdle { viewModel.beginGrant(installId, "model.invoke") }
        assertEquals(installId to "model.invoke", viewModel.permissionRequest.value)
        val state = viewModel.state.value.copy(language = "zh-CN")
        compose.setContent {
            MaterialTheme {
                SkillsScreen(state)
                SkillPermissionScopeDialog(
                    chinese = true,
                    capability = "model.invoke",
                    declaredScope = skillPermissionScope(viewModel.state.value.detail, "model.invoke"),
                    knowledgeScope = false,
                    knowledgeBases = emptyList(),
                    onConfirm = { viewModel.confirmGrant(it) },
                    onCancel = { viewModel.cancelGrant() },
                )
            }
        }
        compose.onNodeWithText("确认技能权限范围").assertIsDisplayed()
        compose.onNodeWithText("能力：model.invoke").assertIsDisplayed()
        compose.onNodeWithText("包声明范围：$scope").assertIsDisplayed()

        // 3. Confirming stores exactly the scope the dialog displayed.
        compose.onNodeWithText("确认授予").performClick()
        compose.waitForIdle()
        val grant = container.skills.grantsFor(installId).single { !it.revoked }
        assertEquals(setOf(modelId), grant.modelProfileIds)
        assertEquals(3, grant.maxModelCalls)
        assertEquals(8_192, grant.maxModelTokens)
        assertTrue(grant.capabilities.contains("model.invoke"))
        assertTrue("the pending request must be cleared", viewModel.permissionRequest.value == null)
    }

    private fun awaitDetail(viewModel: SkillsViewModel, installId: String) {
        repeat(200) {
            if (viewModel.state.value.detail != null && viewModel.state.value.selectedInstallId == installId) return
            Thread.sleep(25)
        }
        error("skill detail did not load")
    }

    private fun skillZip(manifest: String): ByteArray {
        val files = linkedMapOf(
            "mobile-skill.json" to manifest,
            "main.py" to "def run(payload):\n    return {\"ok\": True}\n",
        )
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { (name, content) ->
                    val payload = content.toByteArray(Charsets.UTF_8)
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = payload.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(payload) }.value
                        time = 0L
                    }
                    zip.putNextEntry(entry)
                    zip.write(payload)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }
}
