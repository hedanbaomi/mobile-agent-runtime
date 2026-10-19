// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The screen contract: a host may pass nothing but a state, and every callback must stay
 * a safe no-op. This guards the "presentation only" boundary: no callback may read a
 * file, a database or a network resource by itself.
 */
class WorldBooksActionsTest {

    @Test
    fun aDefaultActionSetIsFullyUsable() {
        val actions = WorldBooksActions()
        actions.onQuery("alpha")
        actions.onCreate()
        actions.onSelect("book")
        actions.onToggleBook("book", false)
        actions.onBookName("name")
        actions.onBookDescription("description")
        actions.onBookEnabled(true)
        actions.onAddEntry()
        actions.onSelectEntry("uid")
        actions.onEntryFieldChange("title", "value")
        actions.onSettingFieldChange("scan_depth", "4")
        actions.onDuplicateEntry()
        actions.onDeleteEntry()
        actions.onSave()
        actions.onCloseEditor()
        actions.onDuplicateBook()
        actions.onDeleteBook()
        actions.onImport()
        actions.onConfirmImport()
        actions.onCancelImport()
        WorldBookExportKind.entries.forEach(actions.onExport)
        actions.onPreviewInput("hello")
        actions.onPreview()
        actions.onDismissError()
    }

    @Test
    fun everyExplicitCallbackIsInvokedWithItsOwnPayload() {
        val events = mutableListOf<String>()
        val actions = WorldBooksActions(
            onQuery = { events += "query:$it" },
            onCreate = { events += "create" },
            onSelect = { events += "select:$it" },
            onToggleBook = { id, enabled -> events += "toggle:$id:$enabled" },
            onBookName = { events += "name:$it" },
            onBookDescription = { events += "description:$it" },
            onBookEnabled = { events += "bookEnabled:$it" },
            onAddEntry = { events += "addEntry" },
            onSelectEntry = { events += "selectEntry:$it" },
            onEntryFieldChange = { key, value -> events += "entryField:$key=$value" },
            onSettingFieldChange = { key, value -> events += "settingField:$key=$value" },
            onDuplicateEntry = { events += "duplicateEntry" },
            onDeleteEntry = { events += "deleteEntry" },
            onSave = { events += "save" },
            onCloseEditor = { events += "closeEditor" },
            onDuplicateBook = { events += "duplicateBook" },
            onDeleteBook = { events += "deleteBook" },
            onImport = { events += "import" },
            onConfirmImport = { events += "confirmImport" },
            onCancelImport = { events += "cancelImport" },
            onExport = { events += "export:${it.name}" },
            onPreviewInput = { events += "previewInput:$it" },
            onPreview = { events += "preview" },
            onDismissError = { events += "dismissError" },
        )
        actions.onQuery("q")
        actions.onCreate()
        actions.onSelect("b1")
        actions.onToggleBook("b1", true)
        actions.onBookName("n")
        actions.onBookDescription("d")
        actions.onBookEnabled(false)
        actions.onAddEntry()
        actions.onSelectEntry("u1")
        actions.onEntryFieldChange("title", "t")
        actions.onSettingFieldChange("depth", "4")
        actions.onDuplicateEntry()
        actions.onDeleteEntry()
        actions.onSave()
        actions.onCloseEditor()
        actions.onDuplicateBook()
        actions.onDeleteBook()
        actions.onImport()
        actions.onConfirmImport()
        actions.onCancelImport()
        actions.onExport(WorldBookExportKind.ORIGINAL)
        actions.onPreviewInput("in")
        actions.onPreview()
        actions.onDismissError()

        assertEquals(24, events.size)
        assertEquals("export:ORIGINAL", events[20])
        assertTrue(events.contains("entryField:title=t"))
        assertTrue(events.contains("settingField:depth=4"))
        assertEquals("toggle:b1:true", events[3])
        assertEquals("previewInput:in", events[21])
        assertEquals("dismissError", events[23])
    }

    @Test
    fun aDefaultStateDescribesAnEmptyIdleScreen() {
        val state = WorldBooksUiState()
        assertTrue(state.books.isEmpty())
        assertEquals("", state.query)
        assertNull(state.editor)
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals("", state.status)
        assertEquals("", state.previewInput)
        assertNull(state.preview)
        assertNull(state.pendingImport)
        assertEquals("zh-CN", state.language)
    }

    @Test
    fun theEditorDraftIsIndependentOfTheListedBook() {
        val editor = WorldBookEditorUi(id = "b1", revision = 7, name = "Draft", dirty = true)
        assertTrue(editor.dirty)
        assertEquals("b1", editor.copy(name = "Renamed").id)
        assertEquals(7, editor.copy(name = "Renamed").revision)
        assertNull(WorldBookEditorUi().id)
    }
}
