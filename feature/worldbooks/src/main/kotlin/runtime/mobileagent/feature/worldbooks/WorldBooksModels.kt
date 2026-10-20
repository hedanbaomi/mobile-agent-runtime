// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import runtime.mobileagent.domain.worldbook.WorldBookBatchAction
import runtime.mobileagent.domain.worldbook.WorldBookConflictPolicy
import runtime.mobileagent.domain.worldbook.WorldBookFileSkip
/**
 * Lightweight presentation model for the world book screen.
 *
 * The UI is state-and-callback driven: it never reads a database, the network or a
 * file, and it never renders a raw exception or implementation stack. Every label and
 * hint arrives from the host, so no string expression is evaluated here and no Chinese
 * copy is hardcoded in Kotlin; visible copy lives in the bilingual string resources.
 */

enum class WorldBookFieldKind { TEXT, MULTILINE, NUMBER, BOOLEAN, CHOICE }

data class WorldBookCardUi(
    val id: String,
    val name: String,
    val entryCount: Int,
    val enabled: Boolean,
    val revision: Int,
    val description: String = "",
)

data class WorldBookEntryUi(
    val uid: String,
    val title: String,
    val keysSummary: String,
    val enabled: Boolean,
    val constant: Boolean,
)

data class WorldBookFieldUi(
    val key: String,
    val label: String,
    val value: String,
    val kind: WorldBookFieldKind = WorldBookFieldKind.TEXT,
    val options: List<Pair<String, String>> = emptyList(),
    val hint: String = "",
)

data class WorldBookEditorUi(
    val id: String? = null,
    val revision: Int = 0,
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<WorldBookEntryUi> = emptyList(),
    val selectedEntryUid: String? = null,
    val entryFields: List<WorldBookFieldUi> = emptyList(),
    val settingsFields: List<WorldBookFieldUi> = emptyList(),
    val dirty: Boolean = false,
)

data class WorldBookMatchUi(
    val title: String,
    val status: String,
    val reason: String,
    val estimatedUnits: Int = 0,
)

data class WorldBookPreviewUi(
    val matches: List<WorldBookMatchUi> = emptyList(),
    val usedUnits: Int = 0,
    val budgetUnits: Int = 0,
    val warnings: List<String> = emptyList(),
)

data class WorldBookImportUi(
    val name: String,
    val entryCount: Int,
    val notices: List<String>,
    /** One row per candidate JSON; empty only for a legacy single-book preview. */
    val items: List<WorldBookImportItemUi> = emptyList(),
    /** Files that never became candidates (images, unsupported types, unreadable ZIP members). */
    val skipped: List<WorldBookImportSkipUi> = emptyList(),
    val policy: WorldBookConflictPolicy = WorldBookConflictPolicy.KEEP_BOTH,
    /** Selected candidates whose outcome depends on [policy]; zero hides the choice. */
    val conflicts: Int = 0,
    val writeCount: Int = 1,
    val bytesToWrite: Long = 0,
    val storedBytes: Long = 0,
    val maxStoredBytes: Long = 0,
    val overStorage: Boolean = false,
    val overBooks: Boolean = false,
    val maxBooks: Int = 0,
    val canConfirm: Boolean = true,
)

data class WorldBookImportItemUi(
    val key: String,
    val source: String,
    /** Name the book will be stored under, or the name in the file when nothing is written. */
    val name: String,
    val entryCount: Int,
    val action: WorldBookBatchAction,
    val selected: Boolean,
    val selectable: Boolean,
    /** Existing book or earlier file this row conflicts with. */
    val conflictWith: String? = null,
    /** Short technical reason for invalid or non world book files, rendered verbatim. */
    val detail: String = "",
    val notices: List<String> = emptyList(),
)

data class WorldBookImportSkipUi(val source: String, val reason: WorldBookFileSkip)

data class WorldBooksUiState(
    val books: List<WorldBookCardUi> = emptyList(),
    val query: String = "",
    val editor: WorldBookEditorUi? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val status: String = "",
    val previewInput: String = "",
    val preview: WorldBookPreviewUi? = null,
    val pendingImport: WorldBookImportUi? = null,
    val language: String = "zh-CN",
    /** The running work can be stopped from the status row (import scanning and writing). */
    val cancellable: Boolean = false,
)

enum class WorldBookExportKind { NATIVE, SILLY_TAVERN, ORIGINAL }

/** Every callback defaults to a no-op so a preview or test host can pass nothing. */
data class WorldBooksActions(
    val onQuery: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onToggleBook: (String, Boolean) -> Unit = { _, _ -> },
    val onBookName: (String) -> Unit = {},
    val onBookDescription: (String) -> Unit = {},
    val onBookEnabled: (Boolean) -> Unit = {},
    val onAddEntry: () -> Unit = {},
    val onSelectEntry: (String) -> Unit = {},
    val onEntryFieldChange: (String, String) -> Unit = { _, _ -> },
    val onSettingFieldChange: (String, String) -> Unit = { _, _ -> },
    val onDuplicateEntry: () -> Unit = {},
    val onDeleteEntry: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onCloseEditor: () -> Unit = {},
    val onDuplicateBook: () -> Unit = {},
    val onDeleteBook: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onImportFolder: () -> Unit = {},
    val onConfirmImport: () -> Unit = {},
    val onCancelImport: () -> Unit = {},
    val onImportPolicy: (WorldBookConflictPolicy) -> Unit = {},
    val onToggleImportItem: (String) -> Unit = {},
    val onCancelBusy: () -> Unit = {},
    val onExport: (WorldBookExportKind) -> Unit = {},
    val onPreviewInput: (String) -> Unit = {},
    val onPreview: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/** Stable semantics identifiers used by Compose and device tests. */
object WorldBookTestTags {
    const val SCREEN = "worldbooks.screen"
    const val LIST = "worldbooks.list"
    const val STATUS = "worldbooks.status"
    const val ERROR = "worldbooks.error"
    const val ERROR_DISMISS = "worldbooks.error.dismiss"
    const val LOADING = "worldbooks.loading"
    const val EMPTY = "worldbooks.empty"
    const val QUERY = "worldbooks.query"
    const val CREATE = "worldbooks.create"
    const val IMPORT = "worldbooks.import"
    const val EXPORT = "worldbooks.export"
    const val EXPORT_DIALOG = "worldbooks.export.dialog"
    const val EXPORT_CONFIRM = "worldbooks.export.confirm"
    const val EXPORT_CANCEL = "worldbooks.export.cancel"
    const val DELETE_DIALOG = "worldbooks.delete.dialog"
    const val DELETE_CONFIRM = "worldbooks.delete.confirm"
    const val DELETE_CANCEL = "worldbooks.delete.cancel"
    const val CARD_PREFIX = "worldbooks.card."
    const val CARD_TOGGLE_PREFIX = "worldbooks.card.toggle."
    const val CARD_SELECT_PREFIX = "worldbooks.card.select."
    const val CARD_OPEN_PREFIX = "worldbooks.card.open."
    const val EDITOR = "worldbooks.editor"
    const val EDITOR_NAME = "worldbooks.editor.name"
    const val EDITOR_DESCRIPTION = "worldbooks.editor.description"
    const val EDITOR_ENABLED = "worldbooks.editor.enabled"
    const val EDITOR_SAVE = "worldbooks.editor.save"
    const val EDITOR_CLOSE = "worldbooks.editor.close"
    const val EDITOR_DUPLICATE = "worldbooks.editor.duplicate"
    const val EDITOR_DELETE_BOOK = "worldbooks.editor.delete"
    const val EDITOR_ENTRIES = "worldbooks.editor.entries"
    const val EDITOR_ENTRIES_EMPTY = "worldbooks.editor.entries.empty"
    const val ENTRY_ADD = "worldbooks.editor.entry.add"
    const val ENTRY_PREFIX = "worldbooks.editor.entry."
    const val ENTRY_TOGGLE_PREFIX = "worldbooks.editor.entry.toggle."
    const val ENTRY_SELECT_PREFIX = "worldbooks.editor.entry.select."
    const val ENTRY_DUPLICATE_PREFIX = "worldbooks.editor.entry.duplicate."
    const val ENTRY_DELETE_PREFIX = "worldbooks.editor.entry.delete."
    const val ENTRY_FIELDS = "worldbooks.editor.entry.fields"
    const val ENTRY_FIELDS_TOGGLE = "worldbooks.editor.entry.fields.toggle"
    const val SETTINGS_FIELDS = "worldbooks.editor.settings"
    const val SETTINGS_FIELDS_TOGGLE = "worldbooks.editor.settings.toggle"
    const val UNSAVED_DIALOG = "worldbooks.editor.unsaved.dialog"
    const val UNSAVED_DISCARD = "worldbooks.editor.unsaved.discard"
    const val UNSAVED_KEEP = "worldbooks.editor.unsaved.keep"
    const val PREVIEW_INPUT = "worldbooks.preview.input"
    const val PREVIEW_RUN = "worldbooks.preview.run"
    const val RUN_PANEL = "worldbooks.preview.panel"
    const val RUN_BUDGET = "worldbooks.preview.budget"
    const val RUN_MATCH_PREFIX = "worldbooks.preview.match."
    const val RUN_WARNING_PREFIX = "worldbooks.preview.warning."
    const val IMPORT_DIALOG = "worldbooks.import.dialog"
    const val IMPORT_CONFIRM = "worldbooks.import.confirm"
    const val IMPORT_CANCEL = "worldbooks.import.cancel"
    const val IMPORT_NOTICE_PREFIX = "worldbooks.import.notice."
    const val IMPORT_FILES = "worldbooks.import.files"
    const val IMPORT_FOLDER = "worldbooks.import.folder"
    const val IMPORT_ITEM_PREFIX = "worldbooks.import.item."
    const val IMPORT_POLICY_PREFIX = "worldbooks.import.policy."
    const val IMPORT_SKIPPED = "worldbooks.import.skipped"
    const val BUSY_CANCEL = "worldbooks.busy.cancel"
    const val FIELD_PREFIX = "worldbooks.field."
    const val BINDING_DIALOG = "worldbooks.binding.dialog"
    const val BINDING_BOOK_PREFIX = "worldbooks.binding.book."
    const val BINDING_CONFIRM = "worldbooks.binding.confirm"
    const val BINDING_CANCEL = "worldbooks.binding.cancel"

    fun card(id: String): String = CARD_PREFIX + id
    fun cardToggle(id: String): String = CARD_TOGGLE_PREFIX + id
    fun cardSelect(id: String): String = CARD_SELECT_PREFIX + id
    fun cardOpen(id: String): String = CARD_OPEN_PREFIX + id
    fun entry(uid: String): String = ENTRY_PREFIX + uid
    fun entryToggle(uid: String): String = ENTRY_TOGGLE_PREFIX + uid
    fun entrySelect(uid: String): String = ENTRY_SELECT_PREFIX + uid
    fun entryDuplicate(uid: String): String = ENTRY_DUPLICATE_PREFIX + uid
    fun entryDelete(uid: String): String = ENTRY_DELETE_PREFIX + uid
    fun field(key: String): String = FIELD_PREFIX + key
    fun match(index: Int): String = RUN_MATCH_PREFIX + index
    fun warning(index: Int): String = RUN_WARNING_PREFIX + index
    fun importNotice(index: Int): String = IMPORT_NOTICE_PREFIX + index
    fun importItem(key: String): String = IMPORT_ITEM_PREFIX + key
    fun importPolicy(policy: WorldBookConflictPolicy): String = IMPORT_POLICY_PREFIX + policy.name.lowercase()
    fun bindingBook(id: String): String = BINDING_BOOK_PREFIX + id
    fun exportOption(kind: WorldBookExportKind): String = "worldbooks.export.option." + kind.name.lowercase()
}

/**
 * Canonical field keys for the per-entry switches. The actions contract carries no
 * dedicated entry-toggle callback, so an entry switch writes back through
 * [WorldBooksActions.onEntryFieldChange] with one of these keys; the host decides how
 * that value reaches the book itself.
 */
object WorldBookEntryKeys {
    const val ENABLED = "enabled"
    const val CONSTANT = "constant"

    fun enabledValue(enabled: Boolean): String = WorldBookFieldValues.of(enabled)
}

/** How a host-supplied status code should be presented, independent of color. */
enum class WorldBookStatusTone { NEUTRAL, SUCCESS, EXCLUDED, WARNING, ERROR }

private val HIT_STATUS = setOf("hit", "match", "matched", "included", "active", "triggered")
private val EXCLUDED_STATUS = setOf("excluded", "exclude", "skip", "skipped", "filtered", "disabled", "constant")
private val WARNING_STATUS = setOf("warning", "limited", "budget", "truncated", "cooldown", "deferred")
private val ERROR_STATUS = setOf("error", "failed", "failure", "invalid", "rejected")

fun worldBookStatusTone(status: String): WorldBookStatusTone {
    val normalized = status.trim().lowercase().replace('-', '_')
    if (normalized.isEmpty()) return WorldBookStatusTone.NEUTRAL
    val head = normalized.substringBefore('_')
    return when {
        normalized in ERROR_STATUS || head in ERROR_STATUS -> WorldBookStatusTone.ERROR
        normalized in HIT_STATUS || head in HIT_STATUS -> WorldBookStatusTone.SUCCESS
        normalized in EXCLUDED_STATUS || head in EXCLUDED_STATUS -> WorldBookStatusTone.EXCLUDED
        normalized in WARNING_STATUS || head in WARNING_STATUS -> WorldBookStatusTone.WARNING
        else -> WorldBookStatusTone.NEUTRAL
    }
}

fun WorldBookCardUi.matchesQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return name.contains(needle, ignoreCase = true) || description.contains(needle, ignoreCase = true)
}

/** Search narrows the host-provided order; it never reorders or drops entries by itself. */
fun WorldBooksUiState.visibleBooks(): List<WorldBookCardUi> = books.filter { it.matchesQuery(query) }

fun WorldBookEditorUi.selectedEntry(): WorldBookEntryUi? {
    val uid = selectedEntryUid ?: return null
    return entries.firstOrNull { it.uid == uid }
}

fun WorldBookEditorUi.entryField(key: String): WorldBookFieldUi? = entryFields.firstOrNull { it.key == key }

fun WorldBookEditorUi.settingField(key: String): WorldBookFieldUi? = settingsFields.firstOrNull { it.key == key }

/** Boolean fields carry their value as text; anything unrecognized reads as false. */
fun WorldBookFieldUi.booleanValue(): Boolean {
    val normalized = value.trim().lowercase()
    return normalized == "true" || normalized == "1" || normalized == "yes" || normalized == "on"
}

/** Frozen write-back text for a boolean field, so hosts never parse UI language. */
object WorldBookFieldValues {
    const val TRUE = "true"
    const val FALSE = "false"

    fun of(enabled: Boolean): String = if (enabled) TRUE else FALSE
}

val WorldBookMatchUi.safeUnits: Int get() = estimatedUnits.coerceAtLeast(0)

val WorldBookPreviewUi.totalEstimatedUnits: Int get() = matches.sumOf { it.safeUnits }

fun WorldBookPreviewUi.hasResults(): Boolean = matches.isNotEmpty() || warnings.isNotEmpty()

fun WorldBookPreviewUi.isOverBudget(): Boolean = budgetUnits > 0 && usedUnits > budgetUnits

/** Clamped share of the configured budget; an unset budget reports zero. */
fun WorldBookPreviewUi.budgetFraction(): Float {
    if (budgetUnits <= 0) return 0f
    return (usedUnits.toFloat() / budgetUnits.toFloat()).coerceIn(0f, 1f)
}

fun WorldBookImportUi.hasNotices(): Boolean = notices.isNotEmpty()

/** True when the import preview would change nothing; the confirm button stays optional. */
fun WorldBookImportUi.isEmptyImport(): Boolean = if (items.isEmpty()) entryCount <= 0 else writeCount <= 0

/** A preview of more than one candidate, or one that also reports skipped files. */
fun WorldBookImportUi.isBatch(): Boolean = items.size > 1 || skipped.isNotEmpty()

/** Binary MiB with one decimal, independent of the device locale; any non-empty size shows at least 0.1. */
fun worldBookMiB(bytes: Long): String {
    val rounded = (bytes.coerceAtLeast(0) * 10 + 524_288) / 1_048_576
    val tenths = if (bytes > 0) rounded.coerceAtLeast(1) else 0
    return "${tenths / 10}.${tenths % 10}"
}
