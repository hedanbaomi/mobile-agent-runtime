// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import runtime.mobileagent.domain.CapabilityId

/**
 * Backend-neutral model-facing tool description.  [ownerId] and provider
 * routing live in [ToolRegistration], never in this public schema.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: String,
    val capability: CapabilityId? = null,
    val sideEffect: Boolean = false,
    val schemaVersion: Int = 1,
) {
    init {
        require(name.matches(TOOL_NAME)) { "Invalid tool name" }
        require(description.length <= 512)
        require(inputSchema.length <= 64 * 1024)
        require(schemaVersion > 0)
        require(inputSchema.trimStart().startsWith("{")) { "Tool schema must be a JSON object" }
        val lowered = inputSchema.lowercase()
        require("\"backend\"" !in lowered)
        require("\"serial\"" !in lowered)
        require("\"endpoint\"" !in lowered)
    }

    /** Existing adapters often call this field parametersJson. */
    val parametersJson: String
        get() = inputSchema

    companion object {
        private val TOOL_NAME = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

data class ToolRegistration(
    val spec: ToolSpec,
    /** Runtime-local owner identifier; never copied into model input schema. */
    val ownerId: String,
)

data class ToolSnapshot(
    val snapshotId: String,
    val revision: Long,
    val specs: List<ToolSpec>,
    val schemaDigest: String,
) {
    init {
        require(snapshotId.isNotBlank())
        require(revision > 0)
        require(specs.map { it.name }.distinct().size == specs.size)
        require(schemaDigest.matches(HEX_SHA256))
    }

    /** Defensive copy keeps callers from mutating the registry's snapshot. */
    fun immutableSpecs(): List<ToolSpec> = Collections.unmodifiableList(specs.toList())

    companion object {
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** A run pins one immutable schema snapshot until the run completes. */
data class ToolRunSnapshot(val snapshot: ToolSnapshot) {
    val specs: List<ToolSpec>
        get() = snapshot.immutableSpecs()
}

class ToolInvocation internal constructor(
    /** Model-provided correlation id; never an execution or replay key. */
    val callId: String,
    val snapshotId: String,
    val agentId: String,
    val name: String,
    val argumentsJson: String,
    /** Runtime-generated id; model callId is correlation only. */
    val requestId: String,
    private val trustedSkill: TrustedSkillInvocation? = null,
) {
    init {
        require(callId.isNotBlank())
        require(snapshotId.isNotBlank())
        require(agentId.isNotBlank())
        require(name.isNotBlank())
        require(requestId.isNotBlank())
    }

    val skillId: String?
        get() = trustedSkill?.skillId

    val skillRevision: Long?
        get() = trustedSkill?.skillRevision

    val invocationId: String
        get() = requestId

    companion object {
        /** Runtime-only construction keeps the internal request id off model JSON. */
        fun fromRuntime(
            callId: String,
            snapshotId: String,
            agentId: String,
            name: String,
            argumentsJson: String,
            skill: TrustedSkillInvocation? = null,
        ): ToolInvocation = ToolInvocation(
            callId = callId,
            snapshotId = snapshotId,
            agentId = agentId,
            name = name,
            argumentsJson = argumentsJson,
            requestId = InternalRequestIds.new(),
            trustedSkill = skill,
        )
    }
}

data class ToolRoute(
    /** Model-provided correlation id retained for audit/response association. */
    val callId: String,
    /** Runtime-owned execution/replay key. */
    val requestId: String,
    val snapshotId: String,
    val agentId: String,
    val tool: ToolSpec,
    val ownerId: String,
)

sealed interface ToolRouteResult {
    data class Resolved(val route: ToolRoute) : ToolRouteResult
    data class Rejected(val error: ToolError) : ToolRouteResult
}

sealed interface ToolExecution {
    data class Value(val json: String) : ToolExecution
    data class Failed(val error: ToolError) : ToolExecution
    data class Unknown(val error: ToolError = ToolError.unknownOutcome()) : ToolExecution
}

fun interface ToolHandler {
    suspend fun invoke(invocation: ToolInvocation): ToolExecution
}

/**
 * Immutable-per-run tool snapshots with owner routing bound to the
 * Runtime-owned invocation id.  The model call id is correlation metadata
 * only.  A published change invalidates routes from the prior snapshot; no
 * old schema or invocation can silently execute against a new provider.
 */
class ToolRegistry(
    initial: Collection<ToolRegistration>,
    handlers: Map<String, ToolHandler> = emptyMap(),
) {
    private val revision = AtomicLong(0)
    private val handlersByOwner = handlers.toMap()
    /** Runtime invocation id is the only execution/replay key. */
    private val routesByInvocation = linkedMapOf<String, ToolRoute>()
    private val completedByInvocation = linkedMapOf<String, ToolExecution>()
    /**
     * Keep only a digest of the invocation payload so accidental or hostile
     * internal-id reuse fails closed without retaining commands or paths.
     */
    private val invocationFingerprints = linkedMapOf<String, String>()
    private var registrations: List<ToolRegistration> = immutableRegistrations(initial)
    private var current: ToolSnapshot = buildSnapshot(registrations, revision.incrementAndGet())

    constructor(specs: Iterable<ToolSpec>) : this(specs.map { ToolRegistration(it, DEFAULT_OWNER) })

    @Synchronized
    fun snapshot(): ToolSnapshot = current.copy(specs = immutableList(current.specs))

    fun beginRun(): ToolRunSnapshot = ToolRunSnapshot(snapshot())

    val specs: List<ToolSpec>
        get() = snapshot().specs

    /** Publish a complete set atomically. */
    @Synchronized
    fun publish(next: Iterable<ToolRegistration>): ToolSnapshot {
        registrations = immutableRegistrations(next)
        current = buildSnapshot(registrations, revision.incrementAndGet())
        return snapshot()
    }

    /**
     * Switch only the owner/provider.  The model-facing schema digest remains
     * unchanged; an invocation made against the old snapshot still fails
     * closed rather than being routed to the new owner.
     */
    @Synchronized
    fun switchOwner(toolName: String, ownerId: String): ToolSnapshot {
        require(ownerId.isNotBlank())
        val next = registrations.map { registration ->
            if (registration.spec.name == toolName) registration.copy(ownerId = ownerId) else registration
        }
        require(next.any { it.spec.name == toolName }) { "Unknown tool" }
        return publish(next)
    }

    @Synchronized
    fun bind(invocation: ToolInvocation): ToolRouteResult {
        if (invocation.snapshotId != current.snapshotId) {
            return ToolRouteResult.Rejected(ToolError(ToolErrorCode.SNAPSHOT_STALE))
        }
        val registration = registrations.firstOrNull { it.spec.name == invocation.name }
            ?: return ToolRouteResult.Rejected(ToolError(ToolErrorCode.INVALID_REQUEST, message = "Unknown tool"))
        val route = ToolRoute(
            callId = invocation.callId,
            requestId = invocation.invocationId,
            snapshotId = invocation.snapshotId,
            agentId = invocation.agentId,
            tool = registration.spec,
            ownerId = registration.ownerId,
        )
        val invocationId = invocation.invocationId
        val fingerprint = invocationFingerprint(invocation)
        val previous = routesByInvocation[invocationId]
        if (previous != null && (previous != route || invocationFingerprints[invocationId] != fingerprint)) {
            return ToolRouteResult.Rejected(ToolError(ToolErrorCode.CALL_ID_REPLAY))
        }
        routesByInvocation[invocationId] = route
        invocationFingerprints[invocationId] = fingerprint
        return ToolRouteResult.Resolved(route)
    }

    /** Alias used by adapters that call owner selection route(). */
    fun route(invocation: ToolInvocation): ToolRouteResult = bind(invocation)

    suspend fun dispatch(invocation: ToolInvocation): ToolExecution {
        val routeResult = bind(invocation)
        if (routeResult !is ToolRouteResult.Resolved) {
            return ToolExecution.Failed((routeResult as ToolRouteResult.Rejected).error)
        }
        // Re-bind before consulting the terminal cache.  This makes a reused
        // internal id with changed model metadata/arguments fail closed
        // instead of receiving the old result by cache hit.
        synchronized(this) {
            completedByInvocation[invocation.invocationId]?.let { return it }
        }
        val handler = synchronized(this) { handlersByOwner[routeResult.route.ownerId] }
            ?: return ToolExecution.Failed(ToolError(ToolErrorCode.INTERNAL_ERROR, message = "Tool owner is unavailable"))
        val result = try {
            handler.invoke(invocation)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            ToolExecution.Failed(ToolError.fromThrowable(failure))
        }
        synchronized(this) {
            val finalResult = if (current.snapshotId != routeResult.route.snapshotId) {
                ToolExecution.Unknown(ToolError(ToolErrorCode.SNAPSHOT_STALE))
            } else {
                result
            }
            // UNKNOWN is terminal for this invocation id: callers must not
            // retry an operation whose external outcome could not be
            // established.
            completedByInvocation[invocation.invocationId] = finalResult
            return finalResult
        }
    }

    /**
     * Fingerprint only non-secret invocation metadata and argument bytes; the
     * digest is used to reject reuse of an internal id with a changed payload
     * and is never emitted as a command/path log.
     */
    private fun invocationFingerprint(invocation: ToolInvocation): String = sha256Hex(
        listOf(
            invocation.callId,
            invocation.snapshotId,
            invocation.agentId,
            invocation.name,
            invocation.skillId.orEmpty(),
            invocation.skillRevision?.toString().orEmpty(),
            invocation.argumentsJson,
        ).joinToString("\u001f"),
    )

    private fun immutableRegistrations(values: Iterable<ToolRegistration>): List<ToolRegistration> {
        val result = values.toList()
        require(result.isNotEmpty()) { "Tool registry must contain at least one tool" }
        require(result.map { it.spec.name }.distinct().size == result.size) { "Duplicate tool name" }
        require(result.all { it.ownerId.isNotBlank() })
        return immutableList(result)
    }

    private fun buildSnapshot(values: List<ToolRegistration>, number: Long): ToolSnapshot {
        val canonical = values.sortedBy { it.spec.name }.joinToString("\n") { registration ->
            listOf(
                registration.spec.name,
                registration.spec.description,
                registration.spec.inputSchema,
                registration.spec.capability?.value.orEmpty(),
                registration.spec.sideEffect.toString(),
                registration.spec.schemaVersion.toString(),
            ).joinToString("\u001f")
        }
        return ToolSnapshot(
            snapshotId = "tool-snapshot-$number",
            revision = number,
            specs = immutableList(values.map { it.spec }),
            schemaDigest = sha256Hex(canonical),
        )
    }

    private fun <T> immutableList(values: Iterable<T>): List<T> =
        Collections.unmodifiableList(values.toList())

    companion object {
        const val DEFAULT_OWNER = "runtime"

        val SHELL_EXEC_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["command"],"properties":{
              "command":{"type":"string","minLength":1,"maxLength":262144},
              "cwd":{"type":["string","null"],"maxLength":4096},
              "timeout_ms":{"type":"integer","minimum":1,"maximum":300000},
              "max_output_bytes":{"type":"integer","minimum":1,"maximum":131072}
            }}
        """.trimIndent().replace(Regex("\\s+"), "")

        val SHELL_EXEC_SPEC = ToolSpec(
            name = "shell_exec",
            description = "Execute one shell command through the selected authority",
            inputSchema = SHELL_EXEC_SCHEMA,
            capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
            sideEffect = true,
        )

        fun fromSpecs(specs: Iterable<ToolSpec>, handlers: Map<String, ToolHandler> = emptyMap()): ToolRegistry =
            ToolRegistry(specs.map { ToolRegistration(it, DEFAULT_OWNER) }, handlers)
    }
}
