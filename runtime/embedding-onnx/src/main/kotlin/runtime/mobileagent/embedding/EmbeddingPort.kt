// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass

data class ModelPackManifest(
    val id: String,
    val dimension: Int,
    val sha256: String,
    val license: String,
)

interface EmbeddingPort {
    val spaceId: String
    suspend fun embed(texts: List<String>): List<FloatArray>
}

class MissingModelPackEmbedding : EmbeddingPort {
    override val spaceId: String = "unconfigured"

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        throw AppError(
            ErrorCode.INDEX_NOT_READY,
            "Local embedding model pack is not installed",
            RetryClass.USER_ACTION,
            "embedding",
            "embed",
        ).asException()
    }
}
