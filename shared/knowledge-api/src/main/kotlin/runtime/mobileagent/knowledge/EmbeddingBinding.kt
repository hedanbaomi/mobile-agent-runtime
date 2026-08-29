// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

/**
 * Complete, user-approved binding for one remote text embedding destination.
 *
 * The value is intentionally stored as the knowledge base's `embedding_space_id`
 * so the current schema can pin every KB without a migration.  No field is
 * inferred from a provider default: changing any field produces a different
 * [spaceId] and therefore requires a new explicit binding/consent.
 */
data class ApiEmbeddingBinding(
    val providerId: String,
    val endpoint: String,
    val providerRevision: Int,
    val modelId: String,
    val modelRevision: Int,
    val dimension: Int,
    /** Explicit data range/purpose shown to the user before API consent. */
    val dataScope: String,
    /** Stable profile identity; defaults to the selected model id for compatibility. */
    val modelProfileId: String = modelId,
) {
    init {
        requireComponent("providerId", providerId)
        requireEndpoint(endpoint)
        require(providerRevision >= 0) { "providerRevision must be non-negative" }
        requireComponent("modelId", modelId)
        require(modelRevision >= 0) { "modelRevision must be non-negative" }
        require(dimension > 0) { "dimension must be positive" }
        requireComponent("dataScope", dataScope)
        requireComponent("modelProfileId", modelProfileId)
    }

    /**
     * Stable, auditable identity.  Endpoint path and all IDs retain their
     * caller-provided case; only trailing endpoint slashes are insignificant.
     */
    val spaceId: String = buildString {
        append("api-embedding-v1|")
        append("provider=").append(encode(providerId)).append('|')
        append("endpoint=").append(encode(endpoint.trimEnd('/'))).append('|')
        append("providerRevision=").append(providerRevision).append('|')
        append("model=").append(encode(modelId)).append('|')
        append("modelRevision=").append(modelRevision).append('|')
        append("dimension=").append(dimension).append('|')
        append("dataScope=").append(encode(dataScope)).append('|')
        append("modelProfileId=").append(encode(modelProfileId))
    }

    /** Alias for callers that name a space identity a fingerprint. */
    val fingerprint: String
        get() = spaceId

    companion object {
        private const val MAX_COMPONENT_LENGTH = 4096

        /**
         * Strictly decode a persisted API space identity.  Unknown fields,
         * reordered fields, malformed escapes, legacy identities, and any
         * non-canonical spelling are rejected rather than partially guessed.
         * A null result means the caller must surface the binding as
         * unavailable and ask for an explicit rebind.
         */
        fun parseSpaceId(spaceId: String): ApiEmbeddingBinding? =
            runCatching {
                val fields = spaceId.split('|')
                require(fields.size == 9) { "invalid API embedding space field count" }
                require(fields[0] == "api-embedding-v1") { "unsupported API embedding space version" }

                fun value(index: Int, key: String): String {
                    val field = fields[index]
                    val equals = field.indexOf('=')
                    require(equals > 0 && field.substring(0, equals) == key) {
                        "invalid API embedding space field $key"
                    }
                    return decode(field.substring(equals + 1))
                }

                val binding = ApiEmbeddingBinding(
                    providerId = value(1, "provider"),
                    endpoint = value(2, "endpoint"),
                    providerRevision = value(3, "providerRevision").toIntOrNull()
                        ?: error("invalid providerRevision"),
                    modelId = value(4, "model"),
                    modelRevision = value(5, "modelRevision").toIntOrNull()
                        ?: error("invalid modelRevision"),
                    dimension = value(6, "dimension").toIntOrNull()
                        ?: error("invalid dimension"),
                    dataScope = value(7, "dataScope"),
                    modelProfileId = value(8, "modelProfileId"),
                )
                require(binding.spaceId == spaceId) { "non-canonical API embedding space" }
                binding
            }.getOrNull()

        private fun requireComponent(name: String, value: String) {
            require(value.isNotEmpty()) { "$name must not be blank" }
            require(value == value.trim()) { "$name must not have surrounding whitespace" }
            require(value.length <= MAX_COMPONENT_LENGTH) { "$name is too long" }
            require(value.none { it == '\u0000' || it == '\r' || it == '\n' }) {
                "$name contains a control character"
            }
        }

        private fun requireEndpoint(value: String) {
            requireComponent("endpoint", value)
            require(value.startsWith("https://") || value.startsWith("http://")) {
                "endpoint must use http:// or https://"
            }
            require(value.substringAfter("://", "").isNotBlank()) { "endpoint must include a host" }
        }

        /** Avoid delimiter ambiguity while keeping the complete value visible. */
        private fun encode(value: String): String = value
            .replace("%", "%25")
            .replace("|", "%7C")

        private fun decode(value: String): String = buildString {
            var index = 0
            while (index < value.length) {
                if (value[index] != '%') {
                    append(value[index])
                    index += 1
                    continue
                }
                require(index + 2 < value.length) { "truncated API embedding escape" }
                when (value.substring(index, index + 3)) {
                    "%25" -> append('%')
                    "%7C" -> append('|')
                    else -> error("invalid API embedding escape")
                }
                index += 3
            }
        }
    }
}
