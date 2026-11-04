// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import runtime.mobileagent.domain.RootDeletedEntry
import runtime.mobileagent.domain.RootDirectoryEntry
import runtime.mobileagent.domain.RootDirectoryListing
import runtime.mobileagent.domain.RootFileEntryKind
import runtime.mobileagent.domain.RootFileMetadataSnapshot
import runtime.mobileagent.domain.RootGlobMatchEntry
import runtime.mobileagent.domain.RootGlobSearchResult
import runtime.mobileagent.domain.RootGrepHitEntry
import runtime.mobileagent.domain.RootGrepSearchResult
import runtime.mobileagent.domain.RootMovedEntry
import runtime.mobileagent.domain.RootSearchCounters

/**
 * One model-facing representation of Root file results.
 *
 * The ordinary INTERNAL/SAF tools already teach the model `success`,
 * `relative_path`, `bytes`, `total_bytes`, `next_offset`, `matches`, `scanned`,
 * `truncated` and `has_more`.  Root keeps its older spellings as aliases for
 * existing internal consumers, but the canonical fields are emitted on every
 * result so one model contract covers both backends.
 */
internal object RootModelResultProjection {
    /** Truncation reason appended when the outer control frame, not the scan, was the limit. */
    const val RESULT_BYTES_REASON = "result_bytes"
    /** Truncation reason for a page that stopped at the caller's entry budget. */
    const val PAGE_LIMIT_REASON = "page_limit"

    fun metadata(value: RootFileMetadataSnapshot, includePrivileged: Boolean): JsonObject = buildJsonObject {
        put("success", true)
        put("workspace_id", value.workspaceId)
        put("relative_path", value.relativePath)
        put("path", value.relativePath)
        put("type", wireType(value.entryKind))
        put("kind", legacyKind(value.entryKind))
        put("bytes", value.sizeBytes)
        put("size_bytes", value.sizeBytes)
        put("version", value.version)
        if (includePrivileged) {
            put("mode_octal", value.modeOctal.toString(8))
            put("owner_uid", value.ownerUid)
            put("owner_gid", value.ownerGid)
            value.selinuxLabel?.let { put("selinux_label", it) }
            put("hardlink_count", value.hardlinkCount)
            put("device_id", value.deviceId)
            put("inode", value.inode)
            put("mount_id", value.mountId)
        }
    }

    fun listing(
        value: RootDirectoryListing,
        rendered: List<RootDirectoryEntry>,
        truncated: Boolean,
        nextCursor: String?,
        reasons: List<String>,
    ): JsonObject = buildJsonObject {
        put("success", true)
        put("workspace_id", value.workspaceId)
        put("relative_path", value.relativePath)
        put("path", value.relativePath)
        putJsonArray("entries") {
            rendered.forEach { entry ->
                add(buildJsonObject {
                    put("name", entry.name)
                    put("relative_path", entry.relativePath)
                    put("path", entry.relativePath)
                    put("type", wireType(entry.entryKind))
                    put("kind", legacyKind(entry.entryKind))
                    put("bytes", entry.sizeBytes)
                    put("size_bytes", entry.sizeBytes)
                    entry.version?.let { put("version", it) }
                })
            }
        }
        put("skipped_entries", value.skippedSpecialEntries)
        put("skipped_special_entries", value.skippedSpecialEntries)
        put("truncated", truncated)
        put("has_more", truncated)
        nextCursor?.let { put("next_cursor", it) }
        putReasons(reasons)
    }

    fun glob(
        value: RootGlobSearchResult,
        rendered: List<RootGlobMatchEntry>,
        truncated: Boolean,
        reasons: List<String>,
    ): JsonObject = buildJsonObject {
        put("success", true)
        put("workspace_id", value.workspaceId)
        put("root", value.root)
        put("path", value.root)
        put("pattern", value.pattern)
        val nodes = rendered.map { entry ->
            buildJsonObject {
                put("relative_path", entry.relativePath)
                put("path", entry.relativePath)
                put("type", wireType(entry.entryKind))
                put("kind", legacyKind(entry.entryKind))
                put("bytes", entry.sizeBytes)
                put("size_bytes", entry.sizeBytes)
                put("depth", entry.depth)
            }
        }
        put("matches", JsonArray(nodes))
        // Legacy alias for existing Root-only consumers; new code reads `matches`.
        put("entries", JsonArray(nodes))
        put("match_count", nodes.size)
        put("truncated", truncated)
        putReasons(reasons)
        putScan(value.counters)
    }

    fun grep(
        value: RootGrepSearchResult,
        rendered: List<RootGrepHitEntry>,
        truncated: Boolean,
        reasons: List<String>,
    ): JsonObject = buildJsonObject {
        put("success", true)
        put("workspace_id", value.workspaceId)
        put("root", value.root)
        put("path", value.root)
        put("query", value.query)
        put("case_sensitive", value.caseSensitive)
        val nodes = rendered.map { hit ->
            buildJsonObject {
                put("relative_path", hit.relativePath)
                put("path", hit.relativePath)
                put("line_number", hit.lineNumber)
                put("column", hit.column)
                put("line", hit.line)
                put("line_text", hit.line)
                if (hit.lineTruncated) put("line_truncated", true)
            }
        }
        put("matches", JsonArray(nodes))
        put("match_count", nodes.size)
        put("truncated", truncated)
        putReasons(reasons)
        putScan(value.counters)
    }

    fun read(
        value: runtime.mobileagent.domain.RootFileReadResult,
        text: String,
        consumedBytes: Int,
    ): JsonObject {
        val nextOffset = value.offsetBytes + consumedBytes
        val effectiveTruncated = value.truncated || nextOffset < value.totalSizeBytes
        return buildJsonObject {
            put("success", true)
            put("workspace_id", value.workspaceId)
            put("relative_path", value.relativePath)
            put("path", value.relativePath)
            put("text", text)
            put("bytes", consumedBytes)
            put("byte_length", consumedBytes)
            put("offset_bytes", value.offsetBytes)
            put("total_bytes", value.totalSizeBytes)
            put("total_size_bytes", value.totalSizeBytes)
            put("next_offset", nextOffset)
            put("next_offset_bytes", nextOffset)
            put("eof", !effectiveTruncated)
            put("has_more", effectiveTruncated)
            put("version", value.version)
            put("truncated", effectiveTruncated)
        }
    }

    fun deleted(value: RootDeletedEntry): JsonObject = buildJsonObject {
        val snapshot = value.deletedSnapshot
        put("success", true)
        put("workspace_id", value.workspaceId)
        put("relative_path", value.relativePath)
        put("path", value.relativePath)
        put("type", wireType(snapshot.entryKind))
        put("kind", legacyKind(snapshot.entryKind))
        put("bytes", snapshot.sizeBytes)
        put("size_bytes", snapshot.sizeBytes)
        put("version", snapshot.version)
        put("deleted", true)
    }

    fun moved(value: RootMovedEntry): JsonObject = buildJsonObject {
        val snapshot = value.snapshot
        put("success", true)
        put("workspace_id", value.workspaceId)
        put("source_relative_path", value.sourceRelativePath)
        put("source_path", value.sourceRelativePath)
        put("destination_relative_path", value.destinationRelativePath)
        put("destination_path", value.destinationRelativePath)
        put("relative_path", value.destinationRelativePath)
        put("path", value.destinationRelativePath)
        put("type", wireType(snapshot.entryKind))
        put("kind", legacyKind(snapshot.entryKind))
        put("bytes", snapshot.sizeBytes)
        put("size_bytes", snapshot.sizeBytes)
        put("version", snapshot.version)
        put("stat", metadata(snapshot, includePrivileged = false))
    }

    fun wireType(kind: RootFileEntryKind): String =
        if (kind == RootFileEntryKind.DIRECTORY) "directory" else "file"

    /** Pre-canonicalization spelling retained only for existing internal consumers. */
    fun legacyKind(kind: RootFileEntryKind): String = kind.name.lowercase()

    private fun kotlinx.serialization.json.JsonObjectBuilder.putReasons(reasons: List<String>) {
        putJsonArray("truncation_reasons") {
            reasons.distinct().forEach { add(JsonPrimitive(it)) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putScan(counters: RootSearchCounters) {
        putJsonObject("scanned") {
            put("directories", counters.directories)
            put("entries", counters.entries)
            put("files", counters.files)
            put("bytes", counters.bytes)
            put("skipped_entries", counters.skippedEntries)
            put("unreadable_entries", counters.unreadableEntries)
        }
        // Flat aliases from the original Root-only result shape.
        put("scanned_entries", counters.entries)
        put("scanned_files", counters.files)
        put("scanned_bytes", counters.bytes)
        put("skipped_entries", counters.skippedEntries)
        put("unreadable_entries", counters.unreadableEntries)
    }
}
