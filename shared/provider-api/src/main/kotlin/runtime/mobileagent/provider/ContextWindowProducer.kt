// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import java.time.Instant
import runtime.mobileagent.domain.ContextLimitSource
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.contextWindowTargetMatches

/** An adapter may return this only from documented, metadata-only capability data.
 * No inference/probe request, model-name table, or generic /models assumption is permitted.
 * [evidence] identifies the metadata field/contract, never credentials or response content.
 */
data class ContextWindowMetadata(
    val value: Int,
    val target: String,
    val checkedAt: String,
    val evidence: String,
    val expiresAt: String? = null,
)

fun interface ContextWindowMetadataSource {
    suspend fun read(target: String): ContextWindowMetadata?
}

data class ProducedContextWindow(
    val value: Int?,
    val source: ContextLimitSource,
    val target: String,
    val checkedAt: String,
    val stale: Boolean = false,
    val evidence: String? = null,
    val expiresAt: String? = null,
) {
    /** The current profile schema cannot retain a metadata expiry. Fail closed on persistence
     * rather than turning a time-limited fact into an indefinitely reusable window.
     */
    fun applyTo(model: ModelProfile): ModelProfile = model.copy(
        contextWindowValue = value.takeIf { expiresAt == null && !stale },
        contextWindowSource = if (expiresAt != null || stale) ContextLimitSource.UNKNOWN else source,
        contextWindowTarget = target,
        contextWindowCheckedAt = checkedAt,
    )
}

/** Produces facts; it never guesses a context window from a model name or local guard. */
class ContextWindowProducer(
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun metadata(target: String, model: ModelProfile, adapter: ModelAdapter): ProducedContextWindow =
        metadata(target) { adapter.contextWindowMetadata(model) }

    fun userDeclared(target: String, value: Int?): ProducedContextWindow {
        require(target.isNotBlank())
        require(value == null || value > 0)
        return ProducedContextWindow(value,
            if (value == null) ContextLimitSource.UNKNOWN else ContextLimitSource.USER_DECLARED,
            target, clock().toString())
    }

    suspend fun metadata(target: String, source: ContextWindowMetadataSource): ProducedContextWindow {
        require(target.isNotBlank())
        val candidate = try { source.read(target) } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) { null }
        // Validate against the time the source finished reading. A fresh metadata
        // source stamps the verified result after its HTTP response arrives.
        val now = clock()
        val unknown = ProducedContextWindow(null, ContextLimitSource.UNKNOWN, target, now.toString())
        candidate ?: return unknown
        val checked = runCatching { Instant.parse(candidate.checkedAt) }.getOrNull() ?: return unknown
        val expiry = candidate.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() ?: return unknown }
        val stale = !contextWindowTargetMatches(candidate.target, target) ||
            checked.isAfter(now) || (expiry != null && !expiry.isAfter(now))
        if (stale) return unknown.copy(stale = true)
        if (candidate.value <= 0 || candidate.evidence.isBlank()) return unknown
        return ProducedContextWindow(candidate.value, ContextLimitSource.PROVIDER_METADATA,
            target, candidate.checkedAt, evidence = candidate.evidence, expiresAt = candidate.expiresAt)
    }
}
