// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.BatchTextEmbedder
import runtime.mobileagent.knowledge.CancellableBatchTextEmbedder
import runtime.mobileagent.knowledge.EmbeddingUnknownOutcomeException
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.ModelAdapter

/**
 * Repository adapter for a user selected remote embedding model.
 *
 * [embedBatchCancellable] is the lock-free boundary used by the repository's
 * staged API operation.  [embedBatch] remains a synchronous compatibility
 * wrapper for existing callers.  Both perform exactly one adapter call for
 * each batch and never retry. The adapter and secret provider are supplied by
 * the app; this class never discovers a provider or opens a network connection
 * itself.
 */
class ApiEmbeddingTextEmbedder(
    val binding: ApiEmbeddingBinding,
    private val adapter: ModelAdapter,
    private val secretProvider: suspend () -> CharArray,
) : TextEmbedder, BatchTextEmbedder, CancellableBatchTextEmbedder {
    override val spaceId: String = binding.spaceId
    override val dimension: Int = binding.dimension

    override fun embed(text: String): FloatArray = embedBatch(listOf(text)).single()

    override fun embedBatch(texts: List<String>): List<FloatArray> {
        return runBlocking { embedBatchCancellable(texts) }
    }

    /**
     * Suspending counterpart used by the repository's lock free API path.  It
     * intentionally performs no dispatcher hop: the caller resolves the
     * selected profile/secret on its serialized DB thread, while Ktor's
     * suspend transport yields without holding the repository lock.
     */
    override suspend fun embedBatchCancellable(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) { "Embedding batch must not be empty" }
        require(texts.all { it.isNotEmpty() }) { "Embedding input must not be empty" }
        // Secret resolution is outside the uncertain-call catch.  A missing
        // local secret therefore cannot be mistaken for a billable provider
        // response.
        val secret = secretProvider()
        return try {
            val result = try {
                adapter.embed(
                    EmbeddingRequest(binding.modelId, texts.toList()),
                    secret,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AppException) {
                if (failure.error.code == ErrorCode.UNKNOWN_OUTCOME) {
                    throw EmbeddingUnknownOutcomeException(failure)
                }
                throw failure
            } catch (failure: Throwable) {
                // A transport exception can occur after the request was
                // accepted.  Fail closed and require explicit duplicate
                // charge acknowledgement before a caller retries.
                throw EmbeddingUnknownOutcomeException(failure)
            }
            try {
                validateBatch(result, texts.size)
            } catch (failure: Throwable) {
                // The provider may already have charged before returning a
                // malformed response, so malformed batch metadata is also
                // uncertain and never worker-retried.
                throw EmbeddingUnknownOutcomeException(failure)
            }
        } finally {
            secret.fill('\u0000')
        }
    }

    private fun validateBatch(result: EmbeddingBatch, expectedCount: Int): List<FloatArray> {
        require(result.dimension == dimension) {
            "Embedding provider dimension ${result.dimension} does not match bound dimension $dimension"
        }
        require(result.vectors.size == expectedCount) {
            "Embedding provider returned ${result.vectors.size} vectors for $expectedCount inputs"
        }
        return result.vectors.mapIndexed { index, vector ->
            require(vector.size == dimension) {
                "Embedding vector $index has dimension ${vector.size}, expected $dimension"
            }
            require(vector.all { it.isFinite() }) { "Embedding vector $index contains a non-finite value" }
            // Do not let an adapter mutate a vector after the SQLite write.
            vector.copyOf()
        }
    }
}

/**
 * Precise AppContainer factory.  The caller must first capture the selected
 * provider/model binding and pass a secret resolver for that same destination.
 */
object ApiEmbeddingTextEmbedderFactory {
    fun create(
        binding: ApiEmbeddingBinding,
        adapter: ModelAdapter,
        secretProvider: suspend () -> CharArray,
    ): ApiEmbeddingTextEmbedder = ApiEmbeddingTextEmbedder(binding, adapter, secretProvider)
}
