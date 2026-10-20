// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.feature.agents.*

/** Real content:// IO, production controller/VM, Compose preview/editor and SQLite persistence. */
class CharacterCardImportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private var selectedUri: Uri? = null
    private val suffix = UUID.randomUUID().toString()
    private val characterName = "角色卡青禾-$suffix"

    @Test fun jsonPreviewCancelDraftModelSelectionSaveAndGreetingAreReal() {
        val (vm, modelLabel) = screen(decoys = true)
        val app = compose.activity.application as MobileAgentApp
        val beforeAgents = app.container.agents.list().size
        val beforeBooks = app.container.worldBooks.list().size
        val input = inputFile(app, cardJson().toByteArray(), "json")
        try {
            selectedUri = FileProvider.getUriForFile(app, "${app.packageName}.app-updates", input)
            compose.onNodeWithTag("agents.character_import.open").performClick(); imported(vm)
            compose.onNodeWithTag("agents.character_import.preview").assertIsDisplayed()
            assertEquals(beforeAgents, app.container.agents.list().size)
            compose.onNodeWithTag("agents.character_import.cancel").performClick()
            assertEquals(beforeBooks, app.container.worldBooks.list().size)
            compose.onNodeWithTag("agents.character_import.open").performClick(); imported(vm)
            compose.onNodeWithTag("agents.character_import.user_name").performTextReplacement("旅人")
            compose.onNodeWithTag("agents.character_import.mode.trpg").performScrollTo().performClick()
            compose.onNodeWithTag("agents.character_import.confirm").performClick()
            compose.waitUntil(15_000) { vm.state.value.editor != null }
            assertEquals(beforeAgents, app.container.agents.list().size)
            assertEquals(beforeBooks, app.container.worldBooks.list().size)
            compose.runOnIdle { assertNull(vm.state.value.editor!!.chatModelId); vm.save() }
            compose.waitUntil(15_000) { !vm.saving.value }
            compose.runOnIdle { assertNotNull(vm.state.value.error) }
            assertEquals(beforeAgents, app.container.agents.list().size)
            scrollTo("agents.model.chat").performClick()
            // Full-suite fixtures leave a long menu. A composed offscreen label
            // is not a selected model; scroll to the real item before tapping.
            val modelOption = compose.onNodeWithText(modelLabel)
            val menu = compose.onNode(hasScrollAction() and hasAnyAncestor(isPopup()))
            var scrolls = 0
            val step = menu.fetchSemanticsNode().boundsInRoot.height / 2f
            while (!modelOption.isDisplayed() && scrolls++ < 80) {
                menu.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, step) }
            }
            modelOption.assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("cc-model-$suffix", vm.state.value.editor!!.chatModelId) }
            compose.onNodeWithTag(AgentTestTags.SAVE).assertIsEnabled().performClick()
            try {
                compose.waitUntil(30_000) { !vm.saving.value && vm.state.value.editor == null }
            } catch (failure: ComposeTimeoutException) {
                val detail = compose.runOnIdle {
                    "saving=${vm.saving.value}, loading=${vm.state.value.editor?.characterCardLoading}, error=${vm.state.value.error}"
                }
                throw AssertionError("Character save did not complete: $detail", failure)
            }
            compose.runOnIdle { assertNull(vm.state.value.error) }
            val saved = app.container.agents.list().single { it.name == characterName }
            assertNotNull(saved.characterCard)
            assertEquals(AgentMode.TRPG, saved.mode)
            assertEquals(1, saved.worldBookIds.size)
            assertFalse(AgentSearchPermission.enabled(saved.permissionSettingsJson))
            assertFalse(AgentToolConfirmation.skip(saved.permissionSettingsJson))
            assertEquals(beforeBooks + 1, app.container.worldBooks.list().size)
            var conversation: String? = null
            compose.runOnIdle { vm.createConversationAsync { conversation = it } }
            compose.waitUntil(30_000) { conversation != null && !vm.saving.value }
            val greeting = app.container.conversations.messages(conversation!!).single()
            assertEquals("你好旅人，我是$characterName。", greeting.text)
            assertEquals(MessageRole.ASSISTANT, greeting.role)
            assertEquals("旅人", app.container.worldBooks.conversationConfig(conversation!!).context.userName)
            val snapshot = app.container.conversations.get(conversation!!)!!.snapshotId
            assertEquals(AgentMode.TRPG, app.container.agents.resolveSnapshot(snapshot).snapshot.mode)
            compose.runOnIdle {
                vm.openEditor(saved.id)
                assertTrue(vm.state.value.editor!!.characterCardLoading)
                vm.save() // Loading metadata must not save a temporary null card.
            }
            compose.waitUntil(15_000) { vm.state.value.editor?.characterCardLoading == false }
            assertEquals(saved.characterCard, app.container.agents.get(saved.id)!!.characterCard)
            scrollTo("agents.editor.mode.trpg").assertIsSelected().assertIsNotEnabled()
            scrollTo("agents.editor.mode.normal").assertIsNotEnabled()
            compose.onNodeWithTag("agents.editor.character_greeting").performScrollTo().assertTextContains("{{user}}", substring = true)
            File(compose.activity.cacheDir, "character-card-editor.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.runOnIdle { vm.edit(vm.state.value.editor!!.copy(name = "$characterName edited")); vm.save() }
            compose.waitUntil(30_000) { !vm.saving.value && vm.state.value.editor == null }
            assertEquals(AgentMode.TRPG, app.container.agents.get(saved.id)!!.mode)
            assertEquals(saved.characterCard, app.container.agents.get(saved.id)!!.characterCard)
        } finally { input.delete() }
    }

    @Test fun pngImportCanOmitBookAndGreetingAndDiscardWithoutWriting() {
        val (vm, _) = screen()
        val app = compose.activity.application as MobileAgentApp
        val beforeAgents = app.container.agents.list().size
        val beforeBooks = app.container.worldBooks.list().size
        val input = inputFile(app, png(cardJson()), "png")
        try {
            selectedUri = FileProvider.getUriForFile(app, "${app.packageName}.app-updates", input)
            compose.onNodeWithTag("agents.character_import.open").performClick(); imported(vm)
            compose.runOnIdle {
                assertTrue(vm.characterImports.state.value.preview!!.format.contains("PNG"))
                val preview = vm.characterImports.state.value.preview!!
                vm.characterImports.change(preview.copy(importWorldBook = false, useGreeting = false))
            }
            compose.onNodeWithTag("agents.character_import.confirm").performClick()
            compose.waitUntil(15_000) { vm.state.value.editor != null }
            compose.runOnIdle {
                assertNull(vm.state.value.editor!!.pendingCharacterWorldBook)
                assertNull(vm.state.value.editor!!.characterCard!!.greeting)
                vm.closeEditor()
            }
            assertEquals(beforeAgents, app.container.agents.list().size)
            assertEquals(beforeBooks, app.container.worldBooks.list().size)
        } finally { input.delete() }
    }

    @Test fun stalePickerAndBadJsonCannotReplaceAnotherEditorOrPersistResources() {
        val (vm, _) = screen()
        val app = compose.activity.application as MobileAgentApp
        val before = app.container.agents.list().size
        val input = inputFile(app, "{\"entries\":{}}".toByteArray(), "json")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.app-updates", input)
        try {
            compose.runOnIdle {
                val token = vm.characterImports.beginPicker(true)
                vm.openEditor(null)
                vm.edit(vm.state.value.editor!!.copy(name = "Keep draft"))
                vm.characterImports.picked(uri, token)
                assertEquals("Keep draft", vm.state.value.editor!!.name)
                assertNull(vm.characterImports.state.value.preview)
                vm.closeEditor()
                vm.characterImports.picked(uri, vm.characterImports.beginPicker(true))
            }
            compose.waitUntil(15_000) { !vm.characterImports.state.value.busy }
            compose.runOnIdle { assertNotNull(vm.characterImports.state.value.error); assertNull(vm.state.value.editor) }
            assertEquals(before, app.container.agents.list().size)
        } finally { input.delete() }
    }

    private fun screen(decoys: Boolean = false): Pair<AgentsViewModel, String> {
        val app = compose.activity.application as MobileAgentApp
        app.ensureHostInitialized()
        val providerId = "cc-provider-$suffix"
        val modelId = "cc-model-$suffix"
        val label = "CC fixture $suffix / card-fixture"
        app.container.profiles.createProvider(ProviderProfile(providerId, "CC fixture $suffix", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = "fixture-absent", revision = 1))
        app.container.profiles.createModel(ModelProfile(modelId, providerId, ModelRole.CHAT, "card-fixture",
            capabilities = setOf("stream"), contextLimit = 32768, outputLimit = 1024, revision = 1))
        if (decoys) repeat(24) { index ->
            app.container.profiles.createModel(ModelProfile("000-cc-$suffix-$index", providerId, ModelRole.CHAT, "000-decoy-$index",
                capabilities = setOf("stream"), contextLimit = 32768, outputLimit = 1024, revision = 1))
        }
        lateinit var vm: AgentsViewModel
        compose.runOnUiThread { vm = AgentsViewModel(app, SavedStateHandle()) }
        compose.setContent {
            val imported by vm.characterImports.state
            val original by vm.state
            val state = original.copy(characterImport = imported.preview, characterImportBusy = imported.busy,
                error = imported.error ?: original.error)
            MaterialTheme {
                AgentsScreen(state, AgentsActions(onOpenEditor = vm::openEditor, onCloseEditor = vm::closeEditor,
                    onEditorChange = vm::edit, onSave = vm::save, onSelectAgent = vm::select,
                    onImportCharacter = { vm.characterImports.picked(selectedUri, vm.characterImports.beginPicker(true)) },
                    onCharacterImportChange = vm.characterImports::change,
                    onConfirmCharacterImport = vm.characterImports::confirm,
                    onCancelCharacterImport = vm.characterImports::cancel), renderEditorAsPage = true)
            }
        }
        return vm to label
    }

    private fun imported(vm: AgentsViewModel) {
        compose.waitUntil(15_000) { !vm.characterImports.state.value.busy }
        compose.runOnIdle { assertNull(vm.characterImports.state.value.error); assertNotNull(vm.characterImports.state.value.preview) }
    }

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(AgentTestTags.EDITOR).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun inputFile(app: MobileAgentApp, bytes: ByteArray, extension: String): File =
        File(File(app.filesDir, "app-updates").apply { mkdirs() }, "character-$suffix.$extension").apply { writeBytes(bytes) }

    private fun cardJson(): String = buildJsonObject {
        put("spec", "chara_card_v2"); put("spec_version", "2.0")
        putJsonObject("data") {
            put("name", characterName); put("description", "{{char}}管理白塔"); put("personality", "耐心的法师")
            put("scenario", "白塔"); put("first_mes", "你好{{user}}，我是{{char}}。"); put("mes_example", "")
            putJsonArray("alternate_greetings") { add("欢迎旅人") }
            putJsonObject("character_book") {
                put("name", "白塔-$suffix")
                putJsonArray("entries") { addJsonObject {
                    put("id", 1); putJsonArray("keys") { add("白塔") }; put("content", "白塔黄昏关闭")
                    put("enabled", true); put("insertion_order", 100)
                } }
            }
        }
    }.toString()

    private fun png(json: String): ByteArray {
        val raw = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
        }.toByteArray()
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val data = "chara\u0000".toByteArray(Charsets.US_ASCII) + Base64.getEncoder().encode(json.toByteArray(Charsets.UTF_8))
        return ByteArrayOutputStream().also { bytes ->
            val output = DataOutputStream(bytes)
            output.write(raw, 0, raw.size - 12)
            output.writeInt(data.size); output.write(type); output.write(data)
            output.writeInt(CRC32().apply { update(type); update(data) }.value.toInt())
            output.write(raw, raw.size - 12, 12)
        }.toByteArray()
    }
}
