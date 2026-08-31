// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.memory

/** Stable operation names for the repository-neutral Skill memory diagnostic seam. */
enum class SkillMemoryDiagnosticOperation(val wireName: String) {
    READ("read"),
    SEARCH("search"),
    APPEND("append"),
    REPLACE("replace"),
    /** An operation name could not be trusted or was not part of the memory protocol. */
    UNKNOWN("unknown");
}

/** Stable state names for one approval-gated Skill memory operation. */
enum class SkillMemoryDiagnosticState(val wireName: String) {
    STARTED("started"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    DENIED("denied"),
    EXPIRED("expired"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown");

    /** Only STARTED is non-terminal; every accepted call must eventually reach one of these. */
    val terminal: Boolean
        get() = this != STARTED
}

/**
 * References are supplied by the diagnostics adapter after app-local HMAC.  Raw identifiers,
 * install IDs, package hashes, paths and model arguments are deliberately not accepted here.
 */
data class SkillMemoryDiagnosticReferences(
    val skillRef: String? = null,
    val agentRef: String? = null,
    val requestRef: String? = null,
) {
    init {
        require(skillRef == null || HMAC_REFERENCE.matches(skillRef))
        require(agentRef == null || HMAC_REFERENCE.matches(agentRef))
        require(requestRef == null || HMAC_REFERENCE.matches(requestRef))
    }

    companion object {
        private val HMAC_REFERENCE = Regex("[0-9a-f]{32}")
        val EMPTY = SkillMemoryDiagnosticReferences()
    }
}

/**
 * Only opaque handle and call ID are offered to the ref provider.  The provider must return
 * already-HMACed references; no file path, query, content, install ID or package hash crosses
 * this boundary.
 */
data class SkillMemoryDiagnosticRefRequest(
    val operation: SkillMemoryDiagnosticOperation,
    val memoryHandle: String,
    val callId: String,
)

fun interface SkillMemoryDiagnosticRefProvider {
    fun references(request: SkillMemoryDiagnosticRefRequest): SkillMemoryDiagnosticReferences
}

fun interface SkillMemoryDiagnosticSink {
    fun record(event: SkillMemoryDiagnosticEvent)
}

/** A typed, path-free event that an Android logger adapter can translate to its own record type. */
data class SkillMemoryDiagnosticEvent(
    val operation: SkillMemoryDiagnosticOperation,
    val state: SkillMemoryDiagnosticState,
    val count: Int = 0,
    val errorCode: String = "unknown",
    val references: SkillMemoryDiagnosticReferences = SkillMemoryDiagnosticReferences.EMPTY,
) {
    init {
        require(count >= 0)
        require(errorCode.matches(ERROR_CODE))
    }

    companion object {
        private val ERROR_CODE = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    }
}

object NoopSkillMemoryDiagnosticSink : SkillMemoryDiagnosticSink {
    override fun record(event: SkillMemoryDiagnosticEvent) = Unit
}

object EmptySkillMemoryDiagnosticRefProvider : SkillMemoryDiagnosticRefProvider {
    override fun references(request: SkillMemoryDiagnosticRefRequest): SkillMemoryDiagnosticReferences =
        SkillMemoryDiagnosticReferences.EMPTY
}
