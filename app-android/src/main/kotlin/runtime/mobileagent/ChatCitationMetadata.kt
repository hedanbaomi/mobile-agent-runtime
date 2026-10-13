// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.*
import runtime.mobileagent.knowledge.*

internal fun chatCitationMetadata(bound: List<Citation>, warning: String?, coverage: RetrievalCoverage? = null, source: Map<String, Pair<Citation, String>>): String = buildJsonObject {
    warning?.let { put("visualWarning", it) }
    // Durable retrieval-scope fact: no query text, only ids and reason
    // codes, so reload and diagnostics can show partial coverage safely.
    coverage?.let {
        putJsonObject("retrievalCoverage") {
            putJsonArray("requested") { it.requested.forEach { id -> add(id) } }
            putJsonArray("searched") { it.searched.forEach { id -> add(id) } }
            putJsonArray("unavailable") { it.unavailable.forEach { source -> add(buildJsonObject {
                put("kb", source.knowledgeBaseId); put("reason", source.reason.name)
            }) } }
            put("partial", it.partial)
            it.notice()?.let { notice ->
                put("notice", notice)
                put("noticeUi", notice)
            }
        }
    }
    putJsonArray("citations") { bound.forEach { c -> add(buildJsonObject {
        put("id", c.citationId); put("runId", c.runId); put("kb", c.knowledgeBaseId); put("document", c.documentId)
        put("chunk", c.chunkId); put("version", c.documentVersionId); c.assetId?.let { put("asset", it) }
        c.page?.let { put("page", it) }; c.sourceSpan?.let { put("span", it) }; put("excerpt", source[c.citationId]?.second.orEmpty())
    }) } }
}.toString()
internal fun restoreChatCitations(raw: String, target: MutableMap<String, Pair<Citation, String>>) {
    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
    root["citations"]?.jsonArray?.forEach { element ->
        val c = element.jsonObject
        fun value(key: String) = c[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        val citation = Citation(value("id"), value("runId"), value("kb"), value("document"), value("chunk"),
            value("asset").ifBlank { null }, c["page"]?.jsonPrimitive?.intOrNull, value("version"), value("span").ifBlank { null })
        target[citation.citationId] = citation to value("excerpt")
    }
}
