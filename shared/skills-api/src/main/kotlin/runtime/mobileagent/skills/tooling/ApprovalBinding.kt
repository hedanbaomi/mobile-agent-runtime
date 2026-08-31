// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode

/**
 * Canonical, replay-resistant identity for a single shell approval.  The
 * command itself is never stored in the digest input; its UTF-8 SHA-256 is.
 * Every field below is part of the binding, so changing any one makes the
 * previous approval stale.
 */
data class ApprovalBinding(
    /** Runtime invocation identity; callId remains the model correlation field. */
    val requestId: String? = null,
    val callId: String,
    val agentId: String,
    val snapshotId: String,
    val skillId: String? = null,
    val skillRevision: Long? = null,
    val command: String,
    val normalizedCwd: String? = null,
    val timeoutMs: Long = ShellLimits.DEFAULT_TIMEOUT_MS,
    val maxOutputBytes: Long = ShellLimits.DEFAULT_MAX_OUTPUT_BYTES,
    val selectedAuthority: Authority,
    val dangerousMode: DangerousMode,
    val toolSchemaVersion: Int = 1,
    val policyVersion: Long,
    val configSnapshotHash: String,
    val sessionIdentity: String,
) {
    /** Adapter compatibility for legacy stores that expose policyVersion as text. */
    constructor(
        callId: String,
        agentId: String,
        snapshotId: String,
        skillId: String? = null,
        skillRevision: Long? = null,
        command: String,
        normalizedCwd: String? = null,
        timeoutMs: Long = ShellLimits.DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Long = ShellLimits.DEFAULT_MAX_OUTPUT_BYTES,
        selectedAuthority: Authority,
        dangerousMode: DangerousMode,
        toolSchemaVersion: Int = 1,
        policyVersion: String,
        configSnapshotHash: String,
        sessionIdentity: String,
    ) : this(
        // Legacy adapters pass their Runtime-generated request id as callId;
        // carry it into the stronger internal identity slot as well.
        requestId = callId,
        callId = callId,
        agentId = agentId,
        snapshotId = snapshotId,
        skillId = skillId,
        skillRevision = skillRevision,
        command = command,
        normalizedCwd = normalizedCwd,
        timeoutMs = timeoutMs.coerceIn(1, ShellLimits.MAX_TIMEOUT_MS),
        maxOutputBytes = maxOutputBytes.coerceIn(1, ShellLimits.MAX_OUTPUT_BYTES),
        selectedAuthority = selectedAuthority,
        dangerousMode = dangerousMode,
        toolSchemaVersion = toolSchemaVersion,
        policyVersion = policyVersion.toLongOrNull() ?: throw IllegalArgumentException("policy version is invalid"),
        configSnapshotHash = configSnapshotHash,
        sessionIdentity = sessionIdentity,
    )

    init {
        require(callId.isNotBlank())
        require(requestId == null || requestId.isNotBlank())
        require(agentId.isNotBlank())
        require(snapshotId.isNotBlank())
        require(command.isNotEmpty())
        require(timeoutMs > 0)
        require(maxOutputBytes > 0)
        require(timeoutMs <= ShellLimits.MAX_TIMEOUT_MS)
        require(maxOutputBytes <= ShellLimits.MAX_OUTPUT_BYTES)
        require(toolSchemaVersion > 0)
        require(policyVersion >= 0)
        require(configSnapshotHash.isNotBlank())
        require(sessionIdentity.isNotBlank())
        require(skillRevision == null || skillRevision > 0)
    }

    /** Avoid accidentally logging the command or cwd when a binding is inspected. */
    override fun toString(): String =
        "ApprovalBinding(requestId=$requestId, callId=$callId, agentId=$agentId, snapshotId=$snapshotId, digest=$digest)"

    /** The only stable value that should be persisted with an approval record. */
    val digest: String
        get() = sha256Hex(canonicalBytes())

    val commandSha256: String
        get() = sha256Hex(command)

    /** Canonical bytes intentionally omit command/cwd plaintext. */
    fun canonicalBytes(): ByteArray {
        val normalizedCommandHash = commandSha256
        val normalizedPath = normalizedCwd?.let(::normalizeCwd)
        val fields: LinkedHashMap<String, String?> = linkedMapOf(
            "version" to "1",
            "request_id" to requestId,
            "call_id" to callId,
            "agent_id" to agentId,
            "snapshot_id" to snapshotId,
            "skill_id" to skillId,
            "skill_revision" to skillRevision?.toString(),
            "command_sha256" to normalizedCommandHash,
            // Persist only a one-way digest; the normalized path is never
            // included in the approval bytes or a log record as plaintext.
            "cwd_sha256" to normalizedPath?.let(::sha256Hex),
            "timeout_ms" to timeoutMs.toString(),
            "max_output_bytes" to maxOutputBytes.toString(),
            "authority" to selectedAuthority.name,
            "dangerous_mode" to dangerousMode.name,
            "tool_schema_version" to toolSchemaVersion.toString(),
            "policy_version" to policyVersion.toString(),
            "config_snapshot_hash" to configSnapshotHash,
            "session_identity" to sessionIdentity,
        )
        // Length-prefixing makes the representation unambiguous and preserves
        // null versus an empty string as distinct values.
        val canonical = buildString {
            fields.forEach { (key, value) ->
                append(key)
                append('=')
                if (value == null) {
                    append("-1:")
                } else {
                    val bytes = value.toByteArray(StandardCharsets.UTF_8)
                    append(bytes.size)
                    append(':')
                    append(value)
                }
                append('\n')
            }
        }
        return canonical.toByteArray(StandardCharsets.UTF_8)
    }

    /** A digest comparison is sufficient only after the caller verifies call ownership. */
    fun matches(other: ApprovalBinding): Boolean = digest == other.digest

    fun isStaleComparedWith(current: ApprovalBinding): Boolean = !matches(current)

    fun staleReasons(current: ApprovalBinding): Set<ApprovalStaleReason> = buildSet {
        if (requestId != current.requestId) add(ApprovalStaleReason.REQUEST_ID)
        if (callId != current.callId) add(ApprovalStaleReason.CALL_ID)
        if (agentId != current.agentId) add(ApprovalStaleReason.AGENT)
        if (snapshotId != current.snapshotId) add(ApprovalStaleReason.SNAPSHOT)
        if (skillId != current.skillId || skillRevision != current.skillRevision) add(ApprovalStaleReason.SKILL)
        if (commandSha256 != current.commandSha256) add(ApprovalStaleReason.COMMAND)
        if (normalizeCwd(normalizedCwd) != normalizeCwd(current.normalizedCwd)) add(ApprovalStaleReason.CWD)
        if (timeoutMs != current.timeoutMs || maxOutputBytes != current.maxOutputBytes) add(ApprovalStaleReason.LIMITS)
        if (selectedAuthority != current.selectedAuthority) add(ApprovalStaleReason.AUTHORITY)
        if (dangerousMode != current.dangerousMode) add(ApprovalStaleReason.DANGEROUS_MODE)
        if (toolSchemaVersion != current.toolSchemaVersion) add(ApprovalStaleReason.TOOL_SCHEMA)
        if (policyVersion != current.policyVersion) add(ApprovalStaleReason.POLICY)
        if (configSnapshotHash != current.configSnapshotHash) add(ApprovalStaleReason.CONFIG_SNAPSHOT)
        if (sessionIdentity != current.sessionIdentity) add(ApprovalStaleReason.SESSION)
    }

    companion object {
        /** Build a binding from a trusted Runtime request after clamping limits. */
        fun fromRequest(
            request: ShellExecRequest,
            normalizedCwd: String? = request.cwd?.let(::normalizeCwd),
            skillRevision: Long? = null,
        ): ApprovalBinding = ApprovalBinding(
            requestId = request.requestId,
            callId = request.callId,
            agentId = request.agentId,
            snapshotId = request.snapshotId,
            skillId = request.skillId,
            skillRevision = skillRevision ?: request.skillRevision,
            command = request.command,
            normalizedCwd = normalizedCwd,
            timeoutMs = request.limits.clamped().timeoutMs,
            maxOutputBytes = request.limits.clamped().maxOutputBytes,
            selectedAuthority = request.selectedAuthority ?: throw IllegalArgumentException("selected authority is required"),
            dangerousMode = request.dangerousMode,
            toolSchemaVersion = request.toolSchemaVersion,
            policyVersion = request.policyVersion,
            configSnapshotHash = request.configSnapshotHash,
            sessionIdentity = request.sessionIdentity,
        )

        fun canonicalSha256(binding: ApprovalBinding): String = binding.digest
    }
}

enum class ApprovalStaleReason {
    REQUEST_ID,
    CALL_ID,
    AGENT,
    SNAPSHOT,
    SKILL,
    COMMAND,
    CWD,
    LIMITS,
    AUTHORITY,
    DANGEROUS_MODE,
    TOOL_SCHEMA,
    POLICY,
    CONFIG_SNAPSHOT,
    SESSION,
}

fun normalizeCwd(cwd: String?): String? {
    if (cwd == null) return null
    require(cwd.isNotBlank()) { "cwd is blank" }
    require(!cwd.contains('\u0000')) { "cwd contains NUL" }
    // Cwd is an authority-side path and may be absolute.  Normalization is
    // lexical only; the selected backend must validate that it exists.
    val value = Normalizer.normalize(cwd.replace('\\', '/'), Normalizer.Form.NFC)
    val absolute = value.startsWith('/')
    val parts = value.split('/')
    val normalized = ArrayDeque<String>()
    parts.forEach { part ->
        when {
            part.isEmpty() || part == "." -> Unit
            part == ".." -> {
                if (normalized.isNotEmpty() && normalized.last() != "..") {
                    normalized.removeLast()
                } else if (!absolute) {
                    normalized.addLast(part)
                }
            }
            else -> normalized.addLast(part)
        }
    }
    val joined = normalized.joinToString("/")
    return when {
        absolute && joined.isEmpty() -> "/"
        absolute -> "/$joined"
        joined.isEmpty() -> "."
        else -> joined
    }
}

fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(StandardCharsets.UTF_8))

fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
