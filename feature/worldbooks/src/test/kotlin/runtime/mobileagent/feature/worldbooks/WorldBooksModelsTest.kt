// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Presentation model behaviour that must hold without any Android runtime. */
class WorldBooksModelsTest {

    private fun card(id: String, name: String, description: String = "", enabled: Boolean = true) =
        WorldBookCardUi(id = id, name = name, entryCount = 2, enabled = enabled, revision = 3, description = description)

    @Test
    fun searchNarrowsTheHostOrderWithoutReorderingOrDroppingUnrelatedBooks() {
        val state = WorldBooksUiState(
            books = listOf(card("a", "Alpha"), card("b", "Beta", description = "alpha lore"), card("c", "Gamma")),
            query = "  ALPHA ",
        )
        assertEquals(listOf("a", "b"), state.visibleBooks().map { it.id })
    }

    @Test
    fun blankSearchKeepsEveryBookInTheGivenOrder() {
        val books = listOf(card("a", "Alpha"), card("b", "Beta"))
        assertEquals(books, WorldBooksUiState(books = books, query = "   ").visibleBooks())
        assertEquals(books, WorldBooksUiState(books = books).visibleBooks())
    }

    @Test
    fun booleanFieldsReadTheTextTheHostSupplies() {
        assertTrue(field(value = "true").booleanValue())
        assertTrue(field(value = " TRUE ").booleanValue())
        assertTrue(field(value = "1").booleanValue())
        assertTrue(field(value = "yes").booleanValue())
        assertFalse(field(value = "false").booleanValue())
        assertFalse(field(value = "0").booleanValue())
        assertFalse(field(value = "").booleanValue())
        assertFalse(field(value = "maybe").booleanValue())
    }

    @Test
    fun booleanWriteBackUsesStableTextRatherThanALanguageSpecificWord() {
        assertEquals("true", WorldBookFieldValues.of(true))
        assertEquals("false", WorldBookFieldValues.of(false))
        assertEquals("true", WorldBookEntryKeys.enabledValue(true))
        assertEquals("false", WorldBookEntryKeys.enabledValue(false))
    }

    @Test
    fun editorHelpersResolveOnlyWhatTheHostActuallyProvided() {
        val editor = WorldBookEditorUi(
            entries = listOf(entry("u1"), entry("u2")),
            selectedEntryUid = "u2",
            entryFields = listOf(field(key = "title", value = "T"), field(key = "body", value = "B")),
            settingsFields = listOf(field(key = "scan_depth", value = "4")),
        )
        assertEquals("u2", editor.selectedEntry()?.uid)
        assertEquals("B", editor.entryField("body")?.value)
        assertNull(editor.entryField("missing"))
        assertEquals("scan_depth", editor.settingField("scan_depth")?.key)
        assertNull(WorldBookEditorUi().selectedEntry())
        assertNull(editor.copy(selectedEntryUid = "gone").selectedEntry())
    }

    @Test
    fun statusCodesMapToPresentableTonesThatNeverDependOnColorAlone() {
        assertEquals(WorldBookStatusTone.SUCCESS, worldBookStatusTone("HIT"))
        assertEquals(WorldBookStatusTone.SUCCESS, worldBookStatusTone("matched"))
        assertEquals(WorldBookStatusTone.EXCLUDED, worldBookStatusTone("excluded"))
        assertEquals(WorldBookStatusTone.EXCLUDED, worldBookStatusTone("skip-disabled"))
        assertEquals(WorldBookStatusTone.WARNING, worldBookStatusTone("budget_capped"))
        assertEquals(WorldBookStatusTone.ERROR, worldBookStatusTone("ERROR"))
        assertEquals(WorldBookStatusTone.ERROR, worldBookStatusTone("failed"))
        assertEquals(WorldBookStatusTone.NEUTRAL, worldBookStatusTone(""))
        assertEquals(WorldBookStatusTone.NEUTRAL, worldBookStatusTone("something-else"))
    }

    @Test
    fun negativeUnitEstimatesNeverReduceTheReportedBudgetUse() {
        val preview = WorldBookPreviewUi(
            matches = listOf(
                WorldBookMatchUi(title = "a", status = "HIT", reason = "key", estimatedUnits = -5),
                WorldBookMatchUi(title = "b", status = "HIT", reason = "key", estimatedUnits = 7),
            ),
            usedUnits = 7,
            budgetUnits = 10,
        )
        assertEquals(0, preview.matches[0].safeUnits)
        assertEquals(7, preview.totalEstimatedUnits)
        assertFalse(preview.isOverBudget())
        assertEquals(0.7f, preview.budgetFraction(), 0.0001f)
    }

    @Test
    fun budgetFractionIsClampedAndAnUnsetBudgetReportsZero() {
        assertEquals(0f, WorldBookPreviewUi(budgetUnits = 0).budgetFraction(), 0.0001f)
        assertEquals(1f, WorldBookPreviewUi(usedUnits = 99, budgetUnits = 10).budgetFraction(), 0.0001f)
        assertTrue(WorldBookPreviewUi(usedUnits = 11, budgetUnits = 10).isOverBudget())
        assertFalse(WorldBookPreviewUi(usedUnits = 10, budgetUnits = 10).isOverBudget())
    }

    @Test
    fun resultsAndNoticesAreReportedEvenWhenNothingMatched() {
        assertFalse(WorldBookPreviewUi().hasResults())
        assertTrue(WorldBookPreviewUi(warnings = listOf("w")).hasResults())
        assertTrue(WorldBookPreviewUi(matches = listOf(WorldBookMatchUi("a", "hit", "r"))).hasResults())

        assertFalse(WorldBookImportUi(name = "n", entryCount = 0, notices = emptyList()).hasNotices())
        assertTrue(WorldBookImportUi(name = "n", entryCount = 0, notices = listOf("x")).hasNotices())
        assertTrue(WorldBookImportUi(name = "n", entryCount = 0, notices = emptyList()).isEmptyImport())
        assertFalse(WorldBookImportUi(name = "n", entryCount = 4, notices = emptyList()).isEmptyImport())
    }

    @Test
    fun numericDraftFilterAcceptsPrefixesAndRejectsLetters() {
        assertTrue("".isNumericDraft())
        assertTrue("12".isNumericDraft())
        assertTrue("-3.5".isNumericDraft())
        assertTrue("1,024".isNumericDraft())
        assertFalse("12a".isNumericDraft())
        assertFalse("1e5".isNumericDraft())
    }

    @Test
    fun testTagsAreStableAndScopedPerIdentifier() {
        assertEquals("worldbooks.card.b1", WorldBookTestTags.card("b1"))
        assertEquals("worldbooks.card.toggle.b1", WorldBookTestTags.cardToggle("b1"))
        assertEquals("worldbooks.card.select.b1", WorldBookTestTags.cardSelect("b1"))
        assertEquals("worldbooks.card.open.b1", WorldBookTestTags.cardOpen("b1"))
        assertEquals("worldbooks.editor.entry.u1", WorldBookTestTags.entry("u1"))
        assertEquals("worldbooks.editor.entry.toggle.u1", WorldBookTestTags.entryToggle("u1"))
        assertEquals("worldbooks.editor.entry.duplicate.u1", WorldBookTestTags.entryDuplicate("u1"))
        assertEquals("worldbooks.editor.entry.delete.u1", WorldBookTestTags.entryDelete("u1"))
        assertEquals("worldbooks.field.body", WorldBookTestTags.field("body"))
        assertEquals("worldbooks.preview.match.0", WorldBookTestTags.match(0))
        assertEquals("worldbooks.preview.warning.2", WorldBookTestTags.warning(2))
        assertEquals("worldbooks.import.notice.1", WorldBookTestTags.importNotice(1))
        assertEquals("worldbooks.binding.book.b1", WorldBookTestTags.bindingBook("b1"))
        assertEquals("worldbooks.export.option.silly_tavern", WorldBookTestTags.exportOption(WorldBookExportKind.SILLY_TAVERN))
    }

    private fun field(key: String = "k", value: String = "", kind: WorldBookFieldKind = WorldBookFieldKind.TEXT) =
        WorldBookFieldUi(key = key, label = "label", value = value, kind = kind)

    private fun entry(uid: String) = WorldBookEntryUi(uid = uid, title = uid, keysSummary = "k", enabled = true, constant = false)
}
