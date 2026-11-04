// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

}
