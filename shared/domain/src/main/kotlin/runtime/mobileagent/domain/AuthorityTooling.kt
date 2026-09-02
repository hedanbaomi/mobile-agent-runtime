// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable

private const val MAX_CWD_LENGTH = 4096

@Serializable
@JvmInline
value class CapabilityId(val value: String) {
    init { require(isValid(value)) { "Capability id is invalid" } }
    override fun toString(): String = value

    companion object {
        const val WORKSPACE_ENUMERATE = "workspace.enumerate"
        const val FILE_LIST = "file.list"
        const val FILE_STAT = "file.stat"
        const val FILE_READ_TEXT = "file.read_text"
        const val FILE_WRITE_TEXT = "file.write_text"
        const val FILE_CREATE_DIRECTORY = "file.create_directory"
        const val FILE_MOVE = "file.move"
        const val FILE_DELETE = "file.delete"
        const val MEMORY_READ = "memory.read"
        const val MEMORY_SEARCH = "memory.search"
        const val MEMORY_APPEND = "memory.append"
        const val MEMORY_REPLACE = "memory.replace"
        const val SHELL_EXECUTE = "shell.execute"

        fun parse(value: String): CapabilityId = CapabilityId(value)
        fun isValid(value: String): Boolean =
            value.length in 1..128 && value == value.trim() &&
                value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } &&
                value.first().isLetter() && value.last() !in charArrayOf('.', '_', '-') &&
                !value.contains("..")
    }
}

@Serializable
enum class Authority { NONE, SHIZUKU, WIRED_ADB }

@Serializable
enum class AuthorityUserIntent { NONE, SHIZUKU, WIRED_ADB }

@Serializable
enum class DangerousMode { DISABLED, ENABLED_CONFIRM_HIGH_RISK, ENABLED_AUTONOMOUS }

@Serializable
enum class GrantLifetime { ONCE, TASK, SESSION, PERSISTENT }

/**
 * Canonical lifetime-owner matching shared by persistence adapters and tool
 * resolvers.  A scoped grant never falls back to the Android process lifetime.
 */
fun GrantLifetime.matchesIdentity(
    taskId: String?,
    sessionId: String?,
    taskIdentity: String?,
    sessionIdentity: String?,
): Boolean = when (this) {
    GrantLifetime.ONCE ->
        (taskId == null || taskId == taskIdentity) &&
            (sessionId == null || sessionId == sessionIdentity)
    GrantLifetime.TASK -> taskId != null && taskId == taskIdentity
    GrantLifetime.SESSION -> sessionId != null && sessionId == sessionIdentity
    GrantLifetime.PERSISTENT -> taskId == null && sessionId == null
}

@Serializable
enum class WorkspaceBackendType { INTERNAL, SAF_TREE, PRIVILEGED }

/**
 * User-visible workspace scope is deliberately separate from the transport
 * or authority. A selected directory is bounded by the directory the user
 * attached; FULL_DEVICE_FILES is a separate, persistent high-risk grant. It
 * is not a root grant and does not imply shell execution.
 */
@Serializable
enum class WorkspaceScope { SELECTED_DIRECTORY, FULL_DEVICE_FILES }

typealias WorkspaceType = WorkspaceBackendType
typealias WorkspaceBackend = WorkspaceBackendType

@Serializable
data class Workspace(
    val id: String,
    val displayName: String,
    val backendType: WorkspaceBackendType,
    val rootReference: String,
    val readable: Boolean = true,
    val writable: Boolean = false,
    val quotaBytes: Long? = null,
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    val enabled: Boolean = true,
    val revision: Long = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val scope: WorkspaceScope = WorkspaceScope.SELECTED_DIRECTORY,
) {
    init {
        require(isSafeId(id)) { "Workspace id is invalid" }
        require(displayName.isNotBlank() && displayName.length <= 256) { "Workspace display name is invalid" }
        require(rootReference.isNotBlank() && rootReference.length <= 4096) { "Workspace root reference is invalid" }
        require(quotaBytes == null || quotaBytes > 0) { "Workspace quota must be positive" }
        require(maxFileBytes > 0 && (quotaBytes == null || maxFileBytes <= quotaBytes)) { "Workspace file limit is invalid" }
        require(revision >= 0) { "Workspace revision must not be negative" }
    }

    companion object { const val DEFAULT_MAX_FILE_BYTES = 1L * 1024 * 1024 }
}

@Serializable
data class AuthorityPolicy(
    val selectedAuthority: Authority = Authority.NONE,
    val dangerousMode: DangerousMode = DangerousMode.DISABLED,
    val policyVersion: Long = 0,
    val updatedAt: String = "",
) {
    init { require(policyVersion >= 0) { "Authority policy version must not be negative" } }
    val selected: Authority get() = selectedAuthority
    val dangerous: DangerousMode get() = dangerousMode
}

@Serializable
data class AuthorityPreferences(
    val authority: Authority = Authority.NONE,
    val userIntentEnabled: Boolean = false,
    val explicitlyConfigured: Boolean = false,
    val updatedAt: String = "",
) {
}

@Serializable
enum class SafGrantStatus { ACTIVE, GRANT_LOST, REVOKED }

@Serializable
data class SafWorkspaceGrant(
    val workspaceId: String,
    val uriReference: String,
    val readGranted: Boolean,
    val writeGranted: Boolean,
    val persistedFlags: Int = 0,
    val status: SafGrantStatus = SafGrantStatus.ACTIVE,
    val createdAt: String = "",
    val lostAt: String? = null,
    val updatedAt: String = "",
) {
    init {
        require(isSafeId(workspaceId)) { "SAF workspace id is invalid" }
        require(uriReference.isNotBlank() && uriReference.length <= 4096) { "SAF URI reference is invalid" }
        require(persistedFlags >= 0) { "SAF persisted flags must not be negative" }
    }
}

@Serializable
data class CapabilityGrant(
    val grantId: String,
    val agentId: String,
    val capability: CapabilityId,
    val skillInstallId: String? = null,
    val packageHash: String? = null,
    val workspaceId: String? = null,
    val pathScope: String? = null,
    val lifetime: GrantLifetime = GrantLifetime.PERSISTENT,
    val policyVersion: Long = 0,
    val createdAt: String = "",
    val expiresAt: String? = null,
    val revokedAt: String? = null,
    val revision: Long = 1,
    /** Durable owner for a TASK grant.  Runtime task ids are never inferred from process state. */
    val taskId: String? = null,
    /** Durable owner for a SESSION grant.  Session ids survive process restart when resumed. */
    val sessionId: String? = null,
    /** Durable one-shot consumption marker.  A consumed ONCE grant can never be replayed. */
    val consumedAt: String? = null,
) {
    init {
        require(isSafeId(grantId)) { "Capability grant id is invalid" }
        require(isSafeId(agentId)) { "Capability grant agent id is invalid" }
        require(skillInstallId == null || isSafeId(skillInstallId)) { "Capability grant install id is invalid" }
        require(packageHash == null || isSafeHash(packageHash)) { "Capability grant package hash is invalid" }
        require(workspaceId == null || isSafeId(workspaceId)) { "Capability grant workspace id is invalid" }
        require(pathScope == null || isRelativeScope(pathScope)) { "Capability grant path scope is invalid" }
        require(policyVersion >= 0 && revision > 0) { "Capability grant version is invalid" }
        require(taskId == null || isSafeId(taskId)) { "Capability grant task id is invalid" }
        require(sessionId == null || isSafeId(sessionId)) { "Capability grant session id is invalid" }
        require(consumedAt == null || (consumedAt.isNotBlank() && consumedAt.length <= 128)) {
            "Capability grant consumption timestamp is invalid"
        }
        require(consumedAt == null || lifetime == GrantLifetime.ONCE) {
            "Only ONCE capability grants may be consumed"
        }
        when (lifetime) {
            GrantLifetime.ONCE -> Unit
            GrantLifetime.TASK -> require(taskId != null) { "TASK capability grants require a task id" }
            GrantLifetime.SESSION -> require(sessionId != null) { "SESSION capability grants require a session id" }
            GrantLifetime.PERSISTENT -> require(taskId == null && sessionId == null) {
                "PERSISTENT capability grants must not carry task or session identity"
            }
        }
    }
    val revoked: Boolean get() = revokedAt != null
    val consumed: Boolean get() = consumedAt != null

    /**
     * Match the explicit lifetime owner supplied by the current runtime.
     * Missing task/session identity is intentionally a fail-closed mismatch for
     * scoped grants; process lifetime is never used as a substitute.
     */
    fun matchesLifetime(taskIdentity: String?, sessionIdentity: String?): Boolean =
        lifetime.matchesIdentity(taskId, sessionId, taskIdentity, sessionIdentity)

    /** Lifecycle gate without wall-clock expiry; useful for repository CAS checks. */
    fun isUsableFor(taskIdentity: String? = null, sessionIdentity: String? = null): Boolean =
        !revoked && !consumed && matchesLifetime(taskIdentity, sessionIdentity)

    /**
     * Lifecycle/revocation gate shared by all resolvers.  Expiry is parsed here
     * as well so malformed persisted timestamps fail closed consistently.
     */
    fun isActiveFor(
        now: Instant,
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): Boolean {
        if (!isUsableFor(taskIdentity, sessionIdentity)) return false
        val expiry = expiresAt?.takeIf { it.isNotBlank() } ?: return true
        return runCatching { Instant.parse(expiry).isAfter(now) }.getOrDefault(false)
    }

    fun isActiveFor(
        nowEpochMs: Long,
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): Boolean = isActiveFor(Instant.ofEpochMilli(nowEpochMs), taskIdentity, sessionIdentity)
}

@Serializable
data class SnapshotGrantBinding(
    val snapshotId: String,
    val grantId: String,
    val capability: CapabilityId,
    val workspaceId: String? = null,
    val pathScope: String? = null,
    val policyVersion: Long = 0,
    val boundAt: String = "",
) {
    init {
        require(isSafeId(snapshotId) && isSafeId(grantId)) { "Snapshot grant binding id is invalid" }
        require(pathScope == null || isRelativeScope(pathScope)) { "Snapshot path scope is invalid" }
        require(policyVersion >= 0) { "Snapshot policy version must not be negative" }
    }
}

/**
 * Immutable typed audit detail. Full command/stdout/stderr/URI/serial/secret fields are not
 * represented. A command can be accountable through a one-way hash only; no command preview is
 * retained.
 */
@Serializable
data class ToolAuditDetail(
    val auditId: String,
    val requestId: String,
    val agentId: String,
    val skillId: String? = null,
    val capability: CapabilityId,
    val workspaceId: String? = null,
    /** One-way digest of the relative path; raw paths are never persisted. */
    val relativePathSha256: String? = null,
    val authority: Authority = Authority.NONE,
    val approvalId: String? = null,
    val dangerousMode: DangerousMode? = null,
    val policyVersion: Long = 0,
    /** One-way digest of the working directory; raw cwd is never persisted. */
    val cwdSha256: String? = null,
    val commandSha256: String? = null,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val stdoutBytes: Long = 0,
    val stderrBytes: Long = 0,
    val durationMs: Long = 0,
    val result: String,
    val createdAt: String,
) {
    init {
        require(isSafeId(auditId) && isSafeId(requestId) && isSafeId(agentId)) { "Audit identity is invalid" }
        require(skillId == null || isSafeId(skillId)) { "Audit skill id is invalid" }
        require(workspaceId == null || isSafeId(workspaceId)) { "Audit workspace id is invalid" }
        require(relativePathSha256 == null || relativePathSha256.matches(HEX_64)) { "Audit relative path hash is invalid" }
        require(approvalId == null || isSafeId(approvalId)) { "Audit approval id is invalid" }
        require(policyVersion >= 0 && stdoutBytes >= 0 && stderrBytes >= 0 && durationMs >= 0) {
            "Audit counters are invalid"
        }
        require(commandSha256 == null || commandSha256.matches(HEX_64)) { "Audit command hash is invalid" }
        require(cwdSha256 == null || cwdSha256.matches(HEX_64)) { "Audit cwd hash is invalid" }
        require(result.isNotBlank() && result.length <= 128) { "Audit result is invalid" }
    }

    companion object {
        private val HEX_64 = Regex("[0-9a-fA-F]{64}")

        fun builder(
            auditId: String,
            requestId: String,
            agentId: String,
            capability: CapabilityId,
            result: String,
            createdAt: String,
        ): ToolAuditDetailBuilder = ToolAuditDetailBuilder(auditId, requestId, agentId, capability, result, createdAt)
    }
}

/** Builder is the intended construction seam for tool audit details. */
class ToolAuditDetailBuilder internal constructor(
    private val auditId: String,
    private val requestId: String,
    private val agentId: String,
    private val capability: CapabilityId,
    private val result: String,
    private val createdAt: String,
) {
    private var skillId: String? = null
    private var workspaceId: String? = null
    private var relativePathSha256: String? = null
    private var authority: Authority = Authority.NONE
    private var approvalId: String? = null
    private var dangerousMode: DangerousMode? = null
    private var policyVersion: Long = 0
    private var cwdSha256: String? = null
    private var commandSha256: String? = null
    private var exitCode: Int? = null
    private var timedOut = false
    private var cancelled = false
    private var stdoutTruncated = false
    private var stderrTruncated = false
    private var stdoutBytes = 0L
    private var stderrBytes = 0L
    private var durationMs = 0L

    fun skill(id: String?) = apply { skillId = id }
    fun workspace(id: String?, relativePath: String? = null) = apply {
        workspaceId = id
        this.relativePathSha256 = relativePath?.let {
            require(isRelativeScope(it)) { "Audit relative path is invalid" }
            sha256Hex(it)
        }
    }
    fun workspaceHash(id: String?, relativePathSha256: String? = null) = apply {
        workspaceId = id
        this.relativePathSha256 = relativePathSha256
    }
    fun authority(value: Authority) = apply { authority = value }
    fun approval(id: String?) = apply { approvalId = id }
    fun dangerousMode(value: DangerousMode?) = apply { dangerousMode = value }
    fun policyVersion(value: Long) = apply { policyVersion = value }
    /** Hash a raw cwd immediately; the builder never places the raw value in the detail. */
    fun cwd(value: String?) = apply {
        require(value == null || (value.isNotBlank() && value.length <= MAX_CWD_LENGTH && !value.contains('\u0000'))) {
            "Audit cwd is invalid"
        }
        cwdSha256 = value?.let(::sha256Hex)
    }
    fun cwdHash(value: String?) = apply { cwdSha256 = value }
    fun commandHash(value: String?) = apply { commandSha256 = value }
    fun exitCode(value: Int?) = apply { exitCode = value }
    fun timeout(value: Boolean) = apply { timedOut = value }
    fun cancel(value: Boolean) = apply { cancelled = value }
    fun truncated(stdout: Boolean, stderr: Boolean) = apply {
        stdoutTruncated = stdout
        stderrTruncated = stderr
    }
    fun outputBytes(stdout: Long, stderr: Long) = apply {
        stdoutBytes = stdout
        stderrBytes = stderr
    }
    fun duration(value: Long) = apply { durationMs = value }

    fun build(): ToolAuditDetail = ToolAuditDetail(
        auditId, requestId, agentId, skillId, capability, workspaceId, relativePathSha256, authority, approvalId,
        dangerousMode, policyVersion, cwdSha256, commandSha256, exitCode, timedOut, cancelled,
        stdoutTruncated, stderrTruncated, stdoutBytes, stderrBytes, durationMs, result, createdAt,
    )
}

typealias AuditDetailBuilder = ToolAuditDetailBuilder

private fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(byte) }

@Serializable
data class ApprovalRecord(
    val approvalId: String,
    /** Runtime-generated opaque request id; model callId is only an association field. */
    val requestId: String = "",
    val callId: String,
    val agentId: String,
    val skillId: String? = null,
    val commandHash: String? = null,
    /** One-way digest of cwd; raw cwd is never persisted in approval_records. */
    val cwdHash: String? = null,
    val selectedAuthority: Authority = Authority.NONE,
    val dangerousMode: DangerousMode = DangerousMode.DISABLED,
    val toolSchemaVersion: Int = 1,
    val policyVersion: Long = 0,
    val configSnapshotHash: String,
    val decision: ApprovalDecision = ApprovalDecision.PENDING,
    val createdAt: String,
    val expiresAt: String? = null,
    val consumedAt: String? = null,
) {
    init {
        require(isSafeId(approvalId) && (requestId.isBlank() || isSafeId(requestId)) && isSafeId(callId) && isSafeId(agentId)) { "Approval identity is invalid" }
        require(skillId == null || isSafeId(skillId)) { "Approval skill id is invalid" }
        require(commandHash == null || commandHash.matches(HEX_64)) { "Approval command hash is invalid" }
        require(cwdHash == null || cwdHash.matches(HEX_64)) { "Approval cwd hash is invalid" }
        require(configSnapshotHash.isNotBlank() && configSnapshotHash.length <= 128) { "Approval snapshot hash is invalid" }
        require(toolSchemaVersion > 0 && policyVersion >= 0) { "Approval version is invalid" }
    }

    companion object { private val HEX_64 = Regex("[0-9a-fA-F]{64}") }
}

@Serializable
enum class ApprovalDecision { PENDING, APPROVED, DENIED, EXPIRED, CONSUMED }

@Serializable
enum class DesktopTrustStatus { TRUSTED, REAUTH_REQUIRED, FORGOTTEN }

@Serializable
data class DesktopIdentity(
    val desktopId: String,
    val appInstanceId: String,
    val updatedAt: String = "",
) {
    init {
        require(isSafeId(desktopId) && isSafeId(appInstanceId)) { "Desktop identity is invalid" }
    }
}

@Serializable
data class DesktopTrust(
    val desktopId: String,
    val appInstanceId: String,
    /** A SecretRef only; secret/session/endpoint material is never stored in this record. */
    val secretRef: String,
    val status: DesktopTrustStatus = DesktopTrustStatus.TRUSTED,
    val createdAt: String = "",
    val lastSeenAt: String? = null,
    val forgottenAt: String? = null,
    val revision: Long = 1,
) {
    init {
        require(isSafeId(desktopId) && isSafeId(appInstanceId)) { "Desktop identity is invalid" }
        require(secretRef.matches(Regex("bridge:desktop:[A-Za-z0-9][A-Za-z0-9._-]{0,255}"))) {
            "Desktop secret reference is invalid"
        }
        require(revision > 0) { "Desktop trust revision is invalid" }
    }
}

@Serializable
data class SkillMemorySpace(
    val spaceId: String,
    val installId: String,
    val packageHash: String,
    val quotaBytes: Long = DEFAULT_QUOTA_BYTES,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val version: Long = 1,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    init {
        require(isSafeId(spaceId) && isSafeId(installId)) { "Skill memory identity is invalid" }
        require(isSafeHash(packageHash)) { "Skill package hash is invalid" }
        require(quotaBytes > 0 && maxEntries > 0 && version > 0) { "Skill memory limits are invalid" }
    }

    companion object {
        const val DEFAULT_QUOTA_BYTES = 4L * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 256
    }
}

@Serializable
data class SkillMemoryEntry(
    val entryId: String,
    val spaceId: String,
    val installId: String,
    val packageHash: String,
    val path: String,
    val content: String,
    val version: Long,
    val byteLength: Long,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(isSafeId(entryId) && isSafeId(spaceId) && isSafeId(installId)) { "Skill memory entry identity is invalid" }
        require(isSafeHash(packageHash) && isMemoryPath(path)) { "Skill memory path or package hash is invalid" }
        require(content.toByteArray(Charsets.UTF_8).size.toLong() == byteLength && byteLength >= 0) {
            "Skill memory byte length is invalid"
        }
        require(version > 0) { "Skill memory entry version is invalid" }
    }
}

private fun isSafeId(value: String): Boolean =
    value.length in 1..256 && value == value.trim() &&
        value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } &&
        value.first().isLetterOrDigit()

private fun isSafeHash(value: String): Boolean =
    value.length in 1..128 && value == value.trim() &&
        value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

private fun isRelativeScope(value: String): Boolean {
    if (value.isBlank() || value.length > 4096 || value.contains('\u0000') || value.contains('\\')) return false
    if (value.startsWith('/') || value.startsWith("//") || value.contains(':')) return false
    return value.split('/').all { it.isNotBlank() && it != "." && it != ".." }
}

private fun isMemoryPath(value: String): Boolean {
    if (value == "MEMORY.md") return true
    val prefix = "journal/"
    if (!value.startsWith(prefix) || !value.endsWith(".md")) return false
    val date = value.removePrefix(prefix).removeSuffix(".md")
    if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false
    return runCatching { LocalDate.parse(date) }.isSuccess
}
