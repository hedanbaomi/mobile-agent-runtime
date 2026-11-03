// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.io.ByteArrayOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface RootPatchOutcome {
    data class Applied(val bytes: ByteArray) : RootPatchOutcome
    data class Rejected(val reason: String) : RootPatchOutcome
}

/** Strict, single-document `search_replace` patch: exact bytes, unique match unless replaceAll. */
object RootSearchReplacePatch {
    @Serializable
    internal data class SearchReplaceDocument(
        val search: String,
        val replace: String,
        val replaceAll: Boolean = false,
    )

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }

    internal fun parseDocument(patchBytes: ByteArray): SearchReplaceDocument? {
        val text = runCatching { patchBytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: return null
        val document = runCatching { json.decodeFromString<SearchReplaceDocument>(text) }.getOrNull()
            ?: return null
        if (document.search.isEmpty() || document.search.contains('\u0000') || document.replace.contains('\u0000')) {
            return null
        }
        return document
    }

    fun apply(current: ByteArray, patchBytes: ByteArray): RootPatchOutcome {
        val text = runCatching { patchBytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_INVALID)
        val document = runCatching {
            json.decodeFromString<SearchReplaceDocument>(text)
        }.getOrNull() ?: return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_INVALID)
        if (document.search.isEmpty() || document.search.contains('\u0000')) {
            return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_INVALID)
        }
        val search = document.search.toByteArray(Charsets.UTF_8)
        val replacement = document.replace.toByteArray(Charsets.UTF_8)
        var matches = 0
        var scanFrom = 0
        while (true) {
            val at = indexOf(current, search, scanFrom)
            if (at < 0) break
            matches += 1
            if (matches > 1 && !document.replaceAll) {
                return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_AMBIGUOUS)
            }
            scanFrom = at + search.size
        }
        if (matches == 0) return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_CONTEXT_MISMATCH)
        val output = ByteArrayOutputStream(current.size + replacement.size - search.size)
        var cursor = 0
        var replaced = 0
        while (cursor <= current.size) {
            val at = indexOf(current, search, cursor)
            if (at < 0) break
            output.write(current, cursor, at - cursor)
            output.write(replacement)
            cursor = at + search.size
            replaced += 1
            if (!document.replaceAll && replaced == 1) break
        }
        output.write(current, cursor, current.size - cursor)
        return RootPatchOutcome.Applied(output.toByteArray())
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from < 0 || from > haystack.size - needle.size) return -1
        for (start in from..haystack.size - needle.size) {
            var at = 0
            while (at < needle.size && haystack[start + at] == needle[at]) at += 1
            if (at == needle.size) return start
        }
        return -1
    }
}
