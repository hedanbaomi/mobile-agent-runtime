// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.worldbooks.WorldBookExportKind
import runtime.mobileagent.ui.WorldBooksRoute

/** Real production route and ViewModel, including content:// IO and persisted edits. */
class WorldBooksUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun createEditPreviewAndToggleAnUnselectedEntryWithoutChangingItsNeighbor() {
        val vm = screen()
        compose.onNodeWithTag("worldbooks.create").performClick()
        val name = "UI world ${UUID.randomUUID()}"
        compose.onNodeWithTag("worldbooks.editor.name").performTextReplacement(name)
        compose.onNodeWithTag("worldbooks.editor.entry.add").performClick()
        scrollTo("worldbooks.field.keys").performTextInput("白塔")
        scrollTo("worldbooks.field.content").performTextInput("UI_WORLD_CONTENT")
        scrollTo("worldbooks.editor.entry.add").performClick()
        scrollTo("worldbooks.editor.entry.toggle.0").performClick()
        compose.runOnIdle {
            assertEquals("0", vm.state.value.editor!!.selectedEntryUid)
            assertFalse(vm.state.value.editor!!.entries.first { it.uid == "0" }.enabled)
            assertTrue(vm.state.value.editor!!.entries.first { it.uid == "1" }.enabled)
        }
        scrollTo("worldbooks.editor.entry.toggle.0").performClick()
        scrollTo("worldbooks.editor.save").performClick()
        settled(vm)
        val app = compose.activity.application as MobileAgentApp
        val saved = app.container.worldBooks.list().single { it.name == name }
        assertEquals(2, saved.entryCount)
        assertEquals("UI_WORLD_CONTENT", app.container.worldBooks.get(saved.id)!!.document.entries.first().content)
        compose.runOnIdle { vm.previewInput("白塔"); vm.preview() }
        settled(vm)
        compose.runOnIdle {
            assertNotNull(vm.state.value.preview)
            assertTrue(vm.state.value.preview!!.matches.any { it.title.isNotBlank() })
        }
        scrollTo("worldbooks.editor.name").assertIsDisplayed()
        File(compose.activity.cacheDir, "worldbooks-editor.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun importRequiresConfirmationAndOriginalExportPreservesUnknownExtensions() {
        val vm = screen()
        val app = compose.activity.application as MobileAgentApp
        val before = app.container.worldBooks.list().size
        val id = UUID.randomUUID().toString()
        val directory = File(app.filesDir, "app-updates").apply { mkdirs() }
        val input = File(directory, "worldbook-$id.json")
        val output = File(directory, "worldbook-export-$id.json")
        input.writeText("""{"futureRoot":{"retained":true},"entries":{"7":{"uid":7,"key":["白塔"],"keysecondary":[],"content":"Literal {{setvar::x::1}}","comment":"Imported entry","constant":false,"position":1,"futureEntry":{"x":42}}}}""")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.app-updates", input)
        try {
            compose.runOnIdle { vm.importFrom(uri) }; settled(vm)
            compose.onNodeWithTag("worldbooks.import.dialog").assertIsDisplayed()
            assertEquals(before, app.container.worldBooks.list().size)
            compose.onNodeWithTag("worldbooks.import.cancel").performClick()
            assertEquals(before, app.container.worldBooks.list().size)
            compose.runOnIdle { vm.importFrom(uri) }; settled(vm)
            compose.onNodeWithTag("worldbooks.import.confirm").performClick(); settled(vm)
            assertEquals(before + 1, app.container.worldBooks.list().size)
            compose.runOnIdle { vm.prepareExport(WorldBookExportKind.ORIGINAL) {} }; settled(vm)
            val out = FileProvider.getUriForFile(app, "${app.packageName}.app-updates", output)
            compose.runOnIdle { vm.exportTo(out) }; settled(vm)
            val parsed = Json.parseToJsonElement(output.readText()).jsonObject
            assertEquals(true, parsed["futureRoot"]!!.jsonObject["retained"]!!.jsonPrimitive.boolean)
            val entry = parsed["entries"]!!.jsonObject["7"]!!.jsonObject
            assertEquals(42, entry["futureEntry"]!!.jsonObject["x"]!!.jsonPrimitive.int)
            assertEquals("Literal {{setvar::x::1}}", entry["content"]!!.jsonPrimitive.content)
        } finally { input.delete(); output.delete() }
    }

    private fun screen(): WorldBooksViewModel {
        val app = compose.activity.application as MobileAgentApp
        app.ensureHostInitialized()
        lateinit var vm: WorldBooksViewModel
        compose.runOnUiThread { vm = WorldBooksViewModel(app) }
        compose.setContent { MaterialTheme { WorldBooksRoute(vm, false, vm::closeEditor, { _, _, _ -> }, { _, _, _ -> }) } }
        settled(vm)
        return vm
    }
    private fun settled(vm: WorldBooksViewModel) {
        compose.waitUntil(15_000) { !vm.state.value.loading }
        compose.runOnIdle { assertNull(vm.state.value.error, vm.state.value.error) }
    }
    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("worldbooks.editor").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
}
