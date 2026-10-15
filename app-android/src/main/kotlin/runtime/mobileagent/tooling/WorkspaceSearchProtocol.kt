// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.tooling.ToolSpec
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.tooling.WorkspaceSearchMatching.WorkspaceSearchGlob

/**
 * Validated, bounded recursive-search arguments.  The compiled glob and the literal query are
 * produced once at parse time, so dispatch never re-parses model input.
 */
internal data class WorkspaceSearchRequest(
    val pattern: String?,
    val glob: WorkspaceSearchGlob?,
    val query: String?,
    val caseSensitive: Boolean,
    val limits: WorkspaceSearch.WorkspaceSearchLimits,
)

/**
 * The search-only wire surface: tool schemas, argument validation and result rendering for
 * `file_glob` / `file_grep`.
 *
 * Keeping this separate from `UnifiedWorkspaceToolExecutor` leaves that file with a single
 * authorization story — resolver gates, ONCE CAS receipts and audit — instead of mixing it with
 * schema text and JSON shaping.  Nothing here decides authority: a rejected request simply never
 * reaches the executor's gates.
 */
internal object WorkspaceSearchProtocol {

    /**
     * Shared bounded-search limit properties.  They are optional with documented defaults and hard
     * maxima, so the model can tighten a search but never ask for an unbounded walk.
     */
    private const val SEARCH_DEPTH_PROPERTY =
        """"max_depth":{"type":"integer","minimum":0,"maximum":16},"""
    private const val SEARCH_ENTRY_PROPERTY =
        """"max_scanned_entries":{"type":"integer","minimum":1,"maximum":20000},"""
    private const val SEARCH_FILE_PROPERTY =
        """"max_scanned_files":{"type":"integer","minimum":1,"maximum":5000},"""
    private const val SEARCH_RESULT_PROPERTY =
        """"max_results":{"type":"integer","minimum":1,"maximum":1000},"""
    private const val SEARCH_TIME_PROPERTY =
        """"max_millis":{"type":"integer","minimum":1,"maximum":30000}"""

    /** Glob patterns are `*`/`?`/`**` only; no regex is compiled from model input. */
    private val GLOB_SCHEMA =
        """{"type":"object","additionalProperties":false,"required":["workspace_id","pattern"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":512},"pattern":{"type":"string","minLength":1,"maxLength":256},""" +
            SEARCH_DEPTH_PROPERTY + SEARCH_ENTRY_PROPERTY + SEARCH_FILE_PROPERTY +
            SEARCH_RESULT_PROPERTY + SEARCH_TIME_PROPERTY + "}}"

    /** Literal text search; the schema deliberately has no regex-shaped option. */
    private val GREP_SCHEMA =
        """{"type":"object","additionalProperties":false,"required":["workspace_id","query"],"properties":{"workspace_id":{"type":"string","minLength":1,"maxLength":128},"relative_path":{"type":"string","maxLength":512},"query":{"type":"string","minLength":1,"maxLength":256},"case_sensitive":{"type":"boolean"},""" +
            SEARCH_DEPTH_PROPERTY + SEARCH_ENTRY_PROPERTY + SEARCH_FILE_PROPERTY +
            """"max_scanned_bytes":{"type":"integer","minimum":1,"maximum":4194304},""" +
            SEARCH_RESULT_PROPERTY + SEARCH_TIME_PROPERTY + "}}"

    private const val GLOB_DESCRIPTION =
        "Find workspace entries matching a glob pattern, recursively and with bounded budgets. " +
            "* and ? match inside one path segment; ** matches whole segments. A pattern without / " +
            "matches the entry name at any depth; with / it matches the full relative path. Files and " +
            "directories are both returned. truncation_reasons names the budget that stopped the scan, " +
            "so truncated=true with no matches is not the same as no match."

    private const val GREP_DESCRIPTION =
        "Search authorized workspace text for a literal string (never a regular expression), " +
            "recursively and with bounded budgets. Hits are reported one per line with line number and " +
            "column; case_sensitive defaults to false. Every limit is reported in truncation_reasons, and " +
            "unreadable non-UTF-8 files are counted in scanned.unreadable_entries instead of being " +
            "reported as no match."

    /** Keys accepted for `file_glob`; the camelCase spellings are compatibility aliases. */
    val globKeys: Set<String> = setOf(
        "workspaceId", "workspace_id", "relativePath", "relative_path", "pattern",
        "maxDepth", "max_depth", "maxScannedEntries", "max_scanned_entries",
        "maxScannedFiles", "max_scanned_files", "maxResults", "max_results",
        "maxMillis", "max_millis",
    )

    /** Keys accepted for `file_grep`; the byte budget and `case_sensitive` are grep-only. */
    val grepKeys: Set<String> = setOf(
        "workspaceId", "workspace_id", "relativePath", "relative_path", "query",
        "caseSensitive", "case_sensitive",
        "maxDepth", "max_depth", "maxScannedEntries", "max_scanned_entries",
        "maxScannedFiles", "max_scanned_files", "maxScannedBytes", "max_scanned_bytes",
        "maxResults", "max_results", "maxMillis", "max_millis",
    )

    val globSpec: ToolSpec = ToolSpec(
        UnifiedWorkspaceToolExecutor.FILE_GLOB,
        GLOB_DESCRIPTION,
        GLOB_SCHEMA,
        CapabilityId(CapabilityId.FILE_LIST),
        false,
        UnifiedWorkspaceToolExecutor.TOOL_SCHEMA_VERSION,
    )

    val grepSpec: ToolSpec = ToolSpec(
        UnifiedWorkspaceToolExecutor.FILE_GREP,
        GREP_DESCRIPTION,
        GREP_SCHEMA,
        CapabilityId(CapabilityId.FILE_READ_TEXT),
        false,
        UnifiedWorkspaceToolExecutor.TOOL_SCHEMA_VERSION,
    )

    /**
     * Validate and compile one search request.
     *
     * Every bound is range-checked *before* it is narrowed to an Int, a malformed pattern or query
     * is rejected rather than repaired, and a null answer means the caller must fail closed before
     * touching any grant or backend.
     */
    fun request(
        isGlob: Boolean,
        pattern: String?,
        query: String?,
        caseSensitive: Boolean?,
        maxDepth: Long?,
        maxScannedEntries: Long?,
        maxScannedFiles: Long?,
        maxScannedBytes: Long?,
        maxResults: Long?,
        maxMillis: Long?,
    ): WorkspaceSearchRequest? {
        val compiled: WorkspaceSearchGlob?
        val literal: String?
        if (isGlob) {
            val raw = pattern ?: return null
            // `case_sensitive` is meaningless for a path glob and is rejected, not ignored.
            if (caseSensitive != null) return null
            compiled = WorkspaceSearchGlob.parse(raw) ?: return null
            literal = null
        } else {
            val raw = query ?: return null
            compiled = null
            literal = WorkspaceSearchMatching.validateQuery(raw) ?: return null
        }
        val limits = WorkspaceSearch.WorkspaceSearchLimits(
            maxDepth = (maxDepth ?: WorkspaceSearch.DEFAULT_MAX_DEPTH.toLong())
                .bounded(0L, WorkspaceSearch.MAX_DEPTH_LIMIT.toLong())?.toInt() ?: return null,
            maxScannedEntries = (maxScannedEntries ?: WorkspaceSearch.DEFAULT_MAX_SCANNED_ENTRIES.toLong())
                .bounded(1L, WorkspaceSearch.MAX_SCANNED_ENTRIES_LIMIT.toLong())?.toInt() ?: return null,
            maxScannedFiles = (maxScannedFiles ?: WorkspaceSearch.DEFAULT_MAX_SCANNED_FILES.toLong())
                .bounded(1L, WorkspaceSearch.MAX_SCANNED_FILES_LIMIT.toLong())?.toInt() ?: return null,
            maxScannedBytes = (maxScannedBytes ?: WorkspaceSearch.DEFAULT_MAX_SCANNED_BYTES)
                .bounded(1L, WorkspaceSearch.MAX_SCANNED_BYTES_LIMIT) ?: return null,
            maxResults = (maxResults ?: WorkspaceSearch.DEFAULT_MAX_RESULTS.toLong())
                .bounded(1L, WorkspaceSearch.MAX_RESULTS_LIMIT.toLong())?.toInt() ?: return null,
            maxMillis = (maxMillis ?: WorkspaceSearch.DEFAULT_MAX_MILLIS)
                .bounded(1L, WorkspaceSearch.MAX_MILLIS_LIMIT) ?: return null,
        )
        return WorkspaceSearchRequest(
            pattern = if (isGlob) pattern else null,
            glob = compiled,
            query = literal,
            caseSensitive = !isGlob && caseSensitive == true,
            limits = limits,
        )
    }

    /** Range check on the raw wire value so narrowing to Int can never wrap into a valid range. */
    private fun Long.bounded(minimum: Long, maximum: Long): Long? =
        if (this in minimum..maximum) this else null

    /** Render a `file_glob` result under the shared bounded-search envelope. */
    fun putGlobResult(builder: JsonObjectBuilder, outcome: WorkspaceSearch.WorkspaceSearchGlobOutcome) {
        builder.put("pattern", outcome.pattern)
        val nodes = mutableListOf<JsonObject>()
        var used = 0
        var dropped = false
        for (entry in outcome.matches) {
            val node = buildJsonObject {
                put("relative_path", outputPath(entry.relativePath))
                put("type", if (entry.type == WorkspaceEntryType.DIRECTORY) "directory" else "file")
                put("bytes", entry.sizeBytes)
                put("depth", entry.depth)
            }
            val size = node.toString().toByteArray(Charsets.UTF_8).size + 1
            if (used + size > WorkspaceSearch.RESULT_TARGET_BYTES) {
                // Never render a payload the outer result budget would have to reject as
                // INTERNAL_ERROR; disclose the trim instead.
                dropped = true
                break
            }
            used += size
            nodes += node
        }
        builder.putJsonArray("matches") { nodes.forEach { add(it) } }
        builder.putEnvelope(outcome, nodes.size, dropped)
    }

    /** Render a `file_grep` result under the shared bounded-search envelope. */
    fun putGrepResult(builder: JsonObjectBuilder, outcome: WorkspaceSearch.WorkspaceSearchGrepOutcome) {
        builder.put("query", outcome.query)
        builder.put("case_sensitive", outcome.caseSensitive)
        val nodes = mutableListOf<JsonObject>()
        var used = 0
        var dropped = false
        for (hit in outcome.matches) {
            val node = buildJsonObject {
                put("relative_path", outputPath(hit.relativePath))
                put("line_number", hit.lineNumber)
                put("column", hit.column)
                put("line", hit.line)
                if (hit.lineTruncated) put("line_truncated", true)
            }
            val size = node.toString().toByteArray(Charsets.UTF_8).size + 1
            if (used + size > WorkspaceSearch.RESULT_TARGET_BYTES) {
                dropped = true
                break
            }
            used += size
            nodes += node
        }
        builder.putJsonArray("matches") { nodes.forEach { add(it) } }
        builder.putEnvelope(outcome, nodes.size, dropped)
    }

    /**
     * The envelope shared by both search tools.
     *
     * It always carries the scan counters and the reason a search stopped, so an exhausted budget
     * can never be read as "no match".  A trim performed for the byte budget is appended to
     * `truncation_reasons` rather than hidden.
     */
    private fun JsonObjectBuilder.putEnvelope(
        outcome: WorkspaceSearch.WorkspaceSearchOutcome,
        renderedMatches: Int,
        droppedByBytes: Boolean,
    ) {
        put("root", outputPath(outcome.root))
        put("match_count", renderedMatches)
        val reasons = linkedSetOf<String>()
        outcome.reasons.forEach { reasons += it.wireName }
        if (droppedByBytes) reasons += WorkspaceSearch.WorkspaceSearchReason.RESULT_BYTES.wireName
        put("truncated", outcome.truncated || droppedByBytes)
        putJsonArray("truncation_reasons") { reasons.forEach { add(it) } }
        putJsonObject("scanned") {
            put("directories", outcome.counters.directories)
            put("entries", outcome.counters.entries)
            put("files", outcome.counters.files)
            put("bytes", outcome.counters.bytes)
            put("skipped_entries", outcome.counters.skippedEntries)
            put("unreadable_entries", outcome.counters.unreadableEntries)
        }
        if (outcome.counters.warnings.isNotEmpty()) {
            putJsonArray("warnings") {
                outcome.counters.warnings.entries
                    .sortedBy { it.key.name }
                    .forEach { (code, count) ->
                        add(buildJsonObject {
                            put("code", code.name)
                            put("count", count)
                        })
                    }
            }
        }
    }

    /**
     * The search root is the workspace root when it is empty, which is not a normalizable relative
     * path; both it and the adapter's `"."` spelling are reported as the empty string.
     */
    private fun outputPath(path: String): String =
        if (path.isEmpty() || path == ".") "" else WorkspacePathPolicy.normalize(path, false)
}
