// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.*

/** Closed file protocol dispatch. No typed file operation is translated into a shell command. */
internal object RootServiceFileDispatch {
    fun execute(
        executor: RootWorkspaceFileExecutor?,
        args: RootTypedArguments,
        acceptedStatus: RootControlStatusSnapshot,
        payloadBytes: ByteArray,
        maxOutputBytes: Int = RootExecutionBudget.MAX_OUTPUT_BYTES,
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
            is RootFileOperationResult.Succeeded -> success(acceptedStatus, result.value, args.toolName, maxOutputBytes)
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

    private fun success(status: RootControlStatusSnapshot, value: Any?, tool: String, maxOutputBytes: Int): RootServiceExecuteResponse {
        val budget = maxOutputBytes.coerceIn(1, RootExecutionBudget.MAX_OUTPUT_BYTES)
        return when (value) {
            is RootFileMetadataSnapshot -> boundedMetadata(status, value, includePrivileged = tool != "file_stat", budget)
            is RootDirectoryListing -> boundedListing(status, value, budget)
            is RootGlobSearchResult -> boundedGlob(status, value, budget)
            is RootFileReadResult -> read(value, status, budget).let { window ->
                window.body?.let { response(status, it) } ?: failure(status, window.error)
            }
            is RootGrepSearchResult -> boundedGrep(status, value, budget)
            is RootDeletedEntry -> single(status, RootModelResultProjection.deleted(value), null, budget)
            is RootMovedEntry -> single(status, RootModelResultProjection.moved(value), null, budget)
            else -> failure(status, "unsupported_result")
        }
    }

    private fun boundedMetadata(
        status: RootControlStatusSnapshot,
        value: RootFileMetadataSnapshot,
        includePrivileged: Boolean,
        maxOutputBytes: Int,
    ): RootServiceExecuteResponse {
        val full = response(status, RootModelResultProjection.metadata(value, includePrivileged), value)
        // file_get_metadata requests these attributes; dropping them cannot be a successful read.
        return if (fits(full, maxOutputBytes)) full else failure(status, "result_exceeds_budget")
    }

    private fun boundedListing(
        status: RootControlStatusSnapshot,
        value: RootDirectoryListing,
        maxOutputBytes: Int,
    ): RootServiceExecuteResponse {
        val total = value.entries.size
        if (total == 0) {
            return accept(status, RootModelResultProjection.listing(value, emptyList(), value.truncated, value.nextCursor, listReasons(value, 0)), maxOutputBytes)
                ?: failure(status, "result_exceeds_budget")
        }
        var low = 1
        var high = total
        var best: JsonObject? = null
        while (low <= high) {
            val count = low + (high - low) / 2
            val body = listingBody(value, count)
            if (fits(response(status, body), maxOutputBytes)) {
                best = body
                low = count + 1
            } else {
                high = count - 1
            }
        }
        return best?.let { response(status, it) } ?: failure(status, "result_exceeds_budget")
    }

    private fun listingBody(value: RootDirectoryListing, count: Int): JsonObject {
        val rendered = value.entries.take(count)
        val dropped = rendered.size < value.entries.size
        // A cursor must name the last entry actually returned; using the
        // original cursor would skip every entry between the two.
        val nextCursor = if (dropped) rendered.last().name else value.nextCursor
        return RootModelResultProjection.listing(
            value,
            rendered,
            value.truncated || dropped,
            nextCursor,
            listReasons(value, count),
        )
    }

    private fun listReasons(value: RootDirectoryListing, count: Int): List<String> = buildList {
        if (value.truncated) add(RootModelResultProjection.PAGE_LIMIT_REASON)
        if (count < value.entries.size) add(RootModelResultProjection.RESULT_BYTES_REASON)
    }

    private fun boundedGlob(
        status: RootControlStatusSnapshot,
        value: RootGlobSearchResult,
        maxOutputBytes: Int,
    ): RootServiceExecuteResponse {
        val total = value.matches.size
        var low = 0
        var high = total
        var best: JsonObject? = null
        while (low <= high) {
            val count = low + (high - low) / 2
            val rendered = value.matches.take(count)
            val dropped = rendered.size < total
            val reasons = if (dropped) value.truncationReasons + RootModelResultProjection.RESULT_BYTES_REASON else value.truncationReasons
            val body = RootModelResultProjection.glob(value, rendered, value.truncated || dropped, reasons)
            if (fits(response(status, body), maxOutputBytes)) {
                best = body
                low = count + 1
            } else {
                high = count - 1
            }
        }
        return best?.let { response(status, it) } ?: failure(status, "result_exceeds_budget")
    }

    private fun boundedGrep(
        status: RootControlStatusSnapshot,
        value: RootGrepSearchResult,
        maxOutputBytes: Int,
    ): RootServiceExecuteResponse {
        val total = value.matches.size
        var low = 0
        var high = total
        var best: JsonObject? = null
        while (low <= high) {
            val count = low + (high - low) / 2
            val rendered = value.matches.take(count)
            val dropped = rendered.size < total
            val reasons = if (dropped) value.truncationReasons + RootModelResultProjection.RESULT_BYTES_REASON else value.truncationReasons
            val body = RootModelResultProjection.grep(value, rendered, value.truncated || dropped, reasons)
            if (fits(response(status, body), maxOutputBytes)) {
                best = body
                low = count + 1
            } else {
                high = count - 1
            }
        }
        return best?.let { response(status, it) } ?: failure(status, "result_exceeds_budget")
    }

    private fun accept(
        status: RootControlStatusSnapshot,
        body: JsonObject,
        maxOutputBytes: Int,
    ): RootServiceExecuteResponse? {
        val response = response(status, body)
        return response.takeIf { fits(it, maxOutputBytes) }
    }

    private fun single(
        status: RootControlStatusSnapshot,
        body: JsonObject,
        snapshot: RootFileMetadataSnapshot?,
        maxOutputBytes: Int,
    ): RootServiceExecuteResponse {
        val response = response(status, body, snapshot)
        return if (fits(response, maxOutputBytes)) response else failure(status, "result_exceeds_budget")
    }

    private fun response(
        status: RootControlStatusSnapshot,
        body: JsonObject,
        snapshot: RootFileMetadataSnapshot? = null,
    ) = RootServiceExecuteResponse(
        "ACCEPTED", status, terminalOutcome = RootServiceTerminalOutcome.SUCCEEDED,
        resultSha256 = RootPrivilegedServiceEngine.sha256Hex(body.toString()), fileResultJson = body.toString(),
        fileSnapshot = snapshot,
    )

    private fun fits(response: RootServiceExecuteResponse, maxOutputBytes: Int): Boolean =
        RootPrivilegedServiceEngine.json.encodeToString(RootServiceExecuteResponse.serializer(), response)
            .toByteArray(Charsets.UTF_8).size <= maxOutputBytes.coerceAtMost(RootExecutionBudget.MAX_OUTPUT_BYTES)

    private data class ReadWindow(val body: JsonObject? = null, val error: String = "non_utf8_text")

    private fun read(value: RootFileReadResult, status: RootControlStatusSnapshot, maxOutputBytes: Int): ReadWindow {
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
        val fullBody = RootModelResultProjection.read(value, fullText, input.position())
        if (fits(response(status, fullBody), maxOutputBytes)) return ReadWindow(body = fullBody)
        // Budget the actual outer response, including both layers of JSON escaping.
        // Binary search Unicode scalar prefixes, never UTF-16 halves or raw byte offsets.
        var low = 1
        var high = fullText.codePointCount(0, fullText.length)
        var best: JsonObject? = null
        while (low <= high) {
            val count = low + (high - low) / 2
            val prefix = fullText.substring(0, fullText.offsetByCodePoints(0, count))
            val body = RootModelResultProjection.read(value, prefix, prefix.toByteArray(Charsets.UTF_8).size)
            if (fits(response(status, body), maxOutputBytes)) {
                best = body
                low = count + 1
            } else high = count - 1
        }
        return best?.let { ReadWindow(body = it) } ?: ReadWindow(error = "result_exceeds_budget")
    }

    private fun safeReason(result: RootFileOperationResult.Rejected): String = when {
        result.reason.startsWith(RootFileOperationReason.UNSUPPORTED_WRITE_DOMAIN) -> result.reason
        result.reason.matches(SAFE_REASON_TOKEN) -> result.reason
        else -> result.rejection.name.lowercase()
    }

    private val SAFE_REASON_TOKEN = Regex("[a-z][a-z0-9_]{1,63}")

    private fun failure(status: RootControlStatusSnapshot, reason: String): RootServiceExecuteResponse {
        val body = buildJsonObject { put("success", false); put("error", reason) }.toString()
        return RootServiceExecuteResponse("ACCEPTED", status, terminalOutcome = RootServiceTerminalOutcome.FAILED,
            resultSha256 = RootPrivilegedServiceEngine.sha256Hex(body), fileResultJson = body, errorReason = reason)
    }
}
