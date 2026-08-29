// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ModelOperation { CHAT, EMBEDDING, RERANK }

@Serializable
enum class InputModality { TEXT, IMAGE }

@Serializable
enum class ModelFeature { STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT }

@Serializable
enum class CapabilityVerification { UNKNOWN, USER_DECLARED, PROBED }

/**
 * Runtime model surface. [ModelRole] remains a derived compatibility field for
 * snapshots and transfer packages; callers that select models should use this.
 */
@Serializable
data class ModelEndpoint(
    val operations: Set<ModelOperation>,
    val inputModalities: Set<InputModality> = setOf(InputModality.TEXT),
    val features: Set<ModelFeature> = emptySet(),
    val verification: CapabilityVerification = CapabilityVerification.UNKNOWN,
) {
    fun derivedRole(): ModelRole = when {
        ModelOperation.EMBEDDING in operations -> ModelRole.EMBEDDING
        ModelOperation.RERANK in operations -> ModelRole.RERANKER
        InputModality.IMAGE in inputModalities && ModelOperation.CHAT in operations &&
            features.none { it == ModelFeature.TOOL_CALLING } &&
            operations == setOf(ModelOperation.CHAT) &&
            features.none { it == ModelFeature.STREAMING } -> ModelRole.VISION
        InputModality.IMAGE in inputModalities && operations == setOf(ModelOperation.CHAT) &&
            features.isEmpty() -> ModelRole.VISION
        else -> ModelRole.CHAT
    }

    companion object {
        fun fromLegacy(role: ModelRole, capabilities: Set<String>): ModelEndpoint {
            val features = buildSet {
                if ("stream" in capabilities) add(ModelFeature.STREAMING)
                if ("tools" in capabilities) add(ModelFeature.TOOL_CALLING)
            }
            return when (role) {
                ModelRole.EMBEDDING -> ModelEndpoint(
                    operations = setOf(ModelOperation.EMBEDDING),
                    inputModalities = setOf(InputModality.TEXT),
                    features = emptySet(),
                    verification = CapabilityVerification.USER_DECLARED,
                )
                ModelRole.RERANKER -> ModelEndpoint(
                    operations = setOf(ModelOperation.RERANK),
                    inputModalities = setOf(InputModality.TEXT),
                    features = emptySet(),
                    verification = CapabilityVerification.USER_DECLARED,
                )
                ModelRole.VISION -> ModelEndpoint(
                    operations = setOf(ModelOperation.CHAT),
                    inputModalities = setOf(InputModality.TEXT, InputModality.IMAGE),
                    features = features,
                    verification = CapabilityVerification.USER_DECLARED,
                )
                ModelRole.CHAT -> ModelEndpoint(
                    operations = setOf(ModelOperation.CHAT),
                    inputModalities = buildSet {
                        add(InputModality.TEXT)
                        if ("image" in capabilities) add(InputModality.IMAGE)
                    },
                    features = features,
                    verification = CapabilityVerification.USER_DECLARED,
                )
            }
        }
    }
}

fun ModelProfile.withEndpoint(): ModelProfile {
    val resolved = if (endpoint.operations.isEmpty()) {
        ModelEndpoint.fromLegacy(role, capabilities)
    } else {
        endpoint
    }
    return copy(endpoint = resolved, role = resolved.derivedRole().let { derived ->
        if (role == ModelRole.VISION && derived == ModelRole.CHAT && InputModality.IMAGE in resolved.inputModalities) {
            ModelRole.VISION
        } else {
            derived
        }
    })
}

fun ModelProfile.isChatEndpoint(): Boolean = ModelOperation.CHAT in endpoint.operations || role == ModelRole.CHAT || role == ModelRole.VISION

fun ModelProfile.isEmbeddingEndpoint(): Boolean = ModelOperation.EMBEDDING in endpoint.operations || role == ModelRole.EMBEDDING

fun ModelProfile.isRerankEndpoint(): Boolean = ModelOperation.RERANK in endpoint.operations || role == ModelRole.RERANKER

fun ModelProfile.acceptsImages(): Boolean =
    InputModality.IMAGE in endpoint.inputModalities || role == ModelRole.VISION || "image" in capabilities
