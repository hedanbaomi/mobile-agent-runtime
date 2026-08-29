// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

/**
 * Persisted state for one API query embedding attempt. The query text is
 * intentionally absent; [queryHash] is SHA-256 over the exact UTF-8 query.
 */
data class ApiQueryAttempt(
    val knowledgeBaseId: String,
    val spaceId: String,
    val queryHash: String,
    val retryAuthorized: Boolean,
    val error: String,
    val updatedAt: String,
) {
    init {
        require(knowledgeBaseId.isNotBlank()) { "knowledgeBaseId must not be blank" }
        require(spaceId.isNotBlank()) { "spaceId must not be blank" }
        require(queryHash.matches(QUERY_HASH_PATTERN)) { "queryHash must be a lowercase SHA-256 hex digest" }
        require(error.isNotBlank()) { "error must not be blank" }
        require(updatedAt.isNotBlank()) { "updatedAt must not be blank" }
    }

    private companion object {
        private val QUERY_HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/**
 * A query embedding result may have been accepted by a provider even when
 * the client did not receive a response. Callers must explicitly authorize a
 * one-time retry; the message never contains the original query.
 */
class ApiQueryUnknownOutcomeException(
    val knowledgeBaseId: String,
    val spaceId: String,
    val queryHash: String,
    cause: Throwable? = null,
) : RuntimeException(
    "UNKNOWN_OUTCOME: API query embedding result is uncertain; explicit retry authorization is required",
    cause,
)
