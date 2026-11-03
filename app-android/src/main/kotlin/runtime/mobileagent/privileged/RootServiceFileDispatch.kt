// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.*

/** Closed file protocol dispatch. No typed file operation is translated into a shell command. */
internal object RootServiceFileDispatch {
    fun execute(
        executor: RootWorkspaceFileExecutor?,
        args: RootTypedArguments,
        acceptedStatus: RootControlStatusSnapshot,
        payloadBytes: ByteArray,
    ): RootServiceExecuteResponse {
        val result: RootFileOperationResult<*> = when {
            executor == null -> RootFileOperationResult.Rejected(RootConfinementRejection.STAGED_HANDLE_INVALID, "workspace_unbound")
            else -> when (args) {
                is RootTypedArguments.FileList -> executor.executeList(args)
                is RootTypedArguments.FileGlob -> executor.executeGlob(args)
                is RootTypedArguments.FileStat -> executor.executeStat(args)
                is RootTypedArguments.FileGetMetadata -> executor.executeGetMetadata(args)
                is RootTypedArguments.FileSetMetadata -> executor.executeSetMetadata(args)
                is RootTypedArguments.FileReadText -> executor.executeReadText(args)
                is RootTypedArguments.FileGrep -> executor.executeGrep(args)
                is RootTypedArguments.FileWriteText -> executor.executeAtomicWriteText(args, payloadBytes)
                is RootTypedArguments.FileApplyPatch -> executor.executeApplyPatch(args, payloadBytes)
                is RootTypedArguments.FileCreateDirectory -> executor.executeCreateDirectory(args)
                is RootTypedArguments.FileDelete -> executor.executeDelete(args)
                is RootTypedArguments.FileMove -> executor.executeMove(args)
                is RootTypedArguments.ShellExec -> RootFileOperationResult.Rejected(RootConfinementRejection.SPECIAL_NODE_REJECTED, "unsupported_tool")
            }
        }
        return when (result) {
            is RootFileOperationResult.Succeeded -> success(acceptedStatus, result.value, args.toolName)
            is RootFileOperationResult.PostMutationFailure -> RootServiceExecuteResponse(
                "ACCEPTED", acceptedStatus, terminalOutcome = result.terminalOutcome,
                requiresDegradedEpoch = result.requiresDegradedEpoch,
                fileFailureStage = result.reason, fileRejection = result.rejection,
            )
            is RootFileOperationResult.PermissionDenied -> failure(acceptedStatus, "permission_denied").copy(
                fileErrno = result.errnoName, fileSelinuxDenied = result.selinuxConfirmed,
            )
            is RootFileOperationResult.UnsupportedAttributePreservation -> failure(acceptedStatus, "unsupported_attribute_preservation").copy(
                fileUnsupportedAttr = result.attribute,
            )
            is RootFileOperationResult.Rejected -> failure(acceptedStatus, safeReason(result)).copy(fileRejection = result.rejection)
        }
    }

    private fun success(status: RootControlStatusSnapshot, value: Any?, tool: String): RootServiceExecuteResponse {
        val body = when (value) {
            is RootFileMetadataSnapshot -> metadata(value, includePrivileged = tool != "file_stat")
            is RootDirectoryListing -> listing(value)
            is RootGlobSearchResult -> globListing(value)
            is RootFileReadResult -> read(value).let { it.body ?: return failure(status, it.error) }
            is RootGrepSearchResult -> grepListing(value)
            is RootDeletedEntry -> buildJsonObject {
                put("workspace_id", value.workspaceId); put("path", value.relativePath); put("deleted", true)
            }
            is RootMovedEntry -> buildJsonObject {
                put("workspace_id", value.workspaceId); put("source_path", value.sourceRelativePath)
                put("destination_path", value.destinationRelativePath); put("stat", metadata(value.snapshot, false))
            }
            else -> return failure(status, "unsupported_result")
        }.toString()
        // JSON escaping may multiply the raw content size. Never emit an oversized control frame.
        if (body.toByteArray(Charsets.UTF_8).size > RootExecutionBudget.MAX_OUTPUT_BYTES) {
            return failure(status, "result_exceeds_budget")
        }
        return RootServiceExecuteResponse(
            "ACCEPTED", status, terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED,
            resultSha256 = RootPrivilegedServiceEngine.sha256Hex(body), fileResultJson = body,
            fileSnapshot = value as? RootFileMetadataSnapshot,
        )
    }

    private fun metadata(value: RootFileMetadataSnapshot, includePrivileged: Boolean) = buildJsonObject {
        put("workspace_id", value.workspaceId); put("path", value.relativePath)
        put("type", value.entryKind.name.lowercase()); put("size_bytes", value.sizeBytes); put("version", value.version)
        if (includePrivileged) {
            put("mode_octal", value.modeOctal.toString(8)); put("owner_uid", value.ownerUid); put("owner_gid", value.ownerGid)
            value.selinuxLabel?.let { put("selinux_label", it) }; put("hardlink_count", value.hardlinkCount)
            put("device_id", value.deviceId); put("inode", value.inode); put("mount_id", value.mountId)
        }
    }

    private fun listing(value: RootDirectoryListing) = buildJsonObject {
        put("workspace_id", value.workspaceId); put("path", value.relativePath)
        put("entries", buildJsonArray { value.entries.forEach { entry -> add(buildJsonObject {
            put("name", entry.name); put("path", entry.relativePath)
            put("type", entry.entryKind.name.lowercase()); put("size_bytes", entry.sizeBytes)
            entry.version?.let { put("version", it) }
        }) } })
        put("truncated", value.truncated); put("skipped_special_entries", value.skippedSpecialEntries)
        value.nextCursor?.let { put("next_cursor", it) }
    }

    private fun globListing(value: RootGlobSearchResult) = buildJsonObject {
        put("workspace_id", value.workspaceId); put("path", value.root); put("pattern", value.pattern)
        put("entries", buildJsonArray { value.matches.forEach { entry -> add(buildJsonObject {
            put("path", entry.relativePath); put("type", entry.entryKind.name.lowercase())
            put("size_bytes", entry.sizeBytes); put("depth", entry.depth)
        }) } })
        put("truncated", value.truncated)
        put("truncation_reasons", buildJsonArray { value.truncationReasons.forEach { add(JsonPrimitive(it)) } })
        put("scanned_entries", value.counters.entries); put("scanned_files", value.counters.files)
        put("skipped_entries", value.counters.skippedEntries)
    }

    private fun grepListing(value: RootGrepSearchResult) = buildJsonObject {
        put("workspace_id", value.workspaceId); put("path", value.root); put("query", value.query)
        put("case_sensitive", value.caseSensitive)
        put("matches", buildJsonArray { value.matches.forEach { m -> add(buildJsonObject {
            put("path", m.relativePath); put("line_number", m.lineNumber)
            put("column", m.column); put("line_text", m.line)
        }) } })
        put("truncated", value.truncated)
        put("truncation_reasons", buildJsonArray { value.truncationReasons.forEach { add(JsonPrimitive(it)) } })
        put("scanned_files", value.counters.files); put("scanned_bytes", value.counters.bytes)
        put("skipped_entries", value.counters.skippedEntries)
    }

    private data class ReadWindow(val body: JsonObject? = null, val error: String = "non_utf8_text")

    private fun read(value: RootFileReadResult): ReadWindow {
        val input = ByteBuffer.wrap(value.content)
        val output = CharBuffer.allocate(value.content.size)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // Only a non-EOF window may leave an incomplete code point unconsumed.
        val decoded = decoder.decode(input, output, !value.truncated)
        if (decoded.isError || decoded.isOverflow) return ReadWindow()
        // The JDK decoder defers surrogate rejection for ED A0..BF until byte three.
        // Those two bytes already cannot prefix a Unicode scalar, even at a window edge.
        if (input.remaining() >= 2 && (input.get(input.position()).toInt() and 0xff) == 0xed &&
            (input.get(input.position() + 1).toInt() and 0xff) in 0xa0..0xbf
        ) return ReadWindow()
        // A caller must enlarge the budget rather than repeat a successful zero-progress page.
        if (value.truncated && input.position() == 0) return ReadWindow(error = "utf8_window_too_small")
        val fullText = output.flip().toString()
        return ReadWindow(body = buildReadJson(value, fullText, input.position()))
    }

    private fun buildReadJson(value: RootFileReadResult, text: String, consumedBytes: Int): JsonObject {
        val nextOffset = value.offsetBytes + consumedBytes
        val effectiveTruncated = value.truncated || nextOffset < value.totalSizeBytes
        return buildJsonObject {
            put("workspace_id", value.workspaceId); put("path", value.relativePath); put("text", text)
            put("offset_bytes", value.offsetBytes); put("byte_length", consumedBytes)
            put("total_size_bytes", value.totalSizeBytes); put("next_offset_bytes", nextOffset)
            put("eof", !effectiveTruncated); put("version", value.version); put("truncated", effectiveTruncated)
        }
    }

    private fun safeReason(result: RootFileOperationResult.Rejected): String = when {
        result.reason.startsWith(RootFileOperationReason.UNSUPPORTED_WRITE_DOMAIN) -> result.reason
        result.reason.matches(SAFE_REASON_TOKEN) -> result.reason
        else -> result.rejection.name.lowercase()
    }

    private val SAFE_REASON_TOKEN = Regex("[a-z][a-z0-9_]{1,63}")

    private fun failure(status: RootControlStatusSnapshot, reason: String): RootServiceExecuteResponse {
        val body = buildJsonObject { put("error", reason) }.toString()
        return RootServiceExecuteResponse("ACCEPTED", status, terminalOutcome = RootServiceTerminalOutcome.FAILED,
            resultSha256 = RootPrivilegedServiceEngine.sha256Hex(body), fileResultJson = body, errorReason = reason)
    }
}
