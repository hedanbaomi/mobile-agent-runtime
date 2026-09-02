// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/**
 * The lifecycle of a durable binding is separate from both the grant and the
 * ephemeral provider handle.  A temporary disconnect therefore becomes
 * [UNAVAILABLE], while the encrypted locator and the capability grant remain
 * durable and recoverable.
 */
enum class PrivilegedWorkspaceBindingStatus {
    ACTIVE,
    UNAVAILABLE,
    /** A more specific presentation state for an authority which is offline. */
    UNAVAILABLE_AUTHORITY,
    REATTACHING,
    WORKSPACE_NOT_FOUND,
    PERMISSION_DENIED,
    GRANT_LOST,
    BINDING_UNRECOVERABLE,
    REVOKED,
}

typealias WorkspaceBindingStatus = PrivilegedWorkspaceBindingStatus

/**
 * Durable, encrypted locator metadata for a privileged directory.
 *
 * The locator bytes are ciphertext produced by the platform adapter.  This
 * model intentionally contains no plaintext path, URI, serial, token or
 * runtime handle.  The AAD facts are persisted so a decrypting adapter can
 * verify that a row has not been rebound to a different application,
 * workspace, authority or locator format.
 */
data class PrivilegedWorkspaceBinding(
    val workspaceId: String,
    val authority: Authority,
    val encryptedLocator: ByteArray,
    val locatorNonce: ByteArray,
    val locatorVersion: Int = 1,
    val keyVersion: Int = 1,
    val aadAppInstanceId: String,
    val aadWorkspaceId: String = workspaceId,
    val aadAuthority: Authority = authority,
    val aadLocatorVersion: Int = locatorVersion,
    val scope: WorkspaceScope = WorkspaceScope.SELECTED_DIRECTORY,
    val status: PrivilegedWorkspaceBindingStatus = PrivilegedWorkspaceBindingStatus.ACTIVE,
    val revision: Long = 1,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    init {
        require(isBindingId(workspaceId)) { "Privileged workspace id is invalid" }
        require(authority != Authority.NONE) { "Privileged workspace authority is invalid" }
        require(encryptedLocator.isNotEmpty() && encryptedLocator.size <= MAX_LOCATOR_BYTES) {
            "Encrypted workspace locator is invalid"
        }
        require(locatorNonce.size in MIN_NONCE_BYTES..MAX_NONCE_BYTES) {
            "Workspace locator nonce is invalid"
        }
        require(locatorVersion > 0 && keyVersion > 0) { "Workspace binding version is invalid" }
        require(isBindingId(aadAppInstanceId)) { "Workspace binding application identity is invalid" }
        require(aadWorkspaceId == workspaceId) { "Workspace binding AAD workspace does not match" }
        require(aadAuthority == authority) { "Workspace binding AAD authority does not match" }
        require(aadLocatorVersion == locatorVersion) { "Workspace binding AAD version does not match" }
        require(revision > 0) { "Workspace binding revision must be positive" }
        require(createdAt.length <= MAX_TIMESTAMP_LENGTH && updatedAt.length <= MAX_TIMESTAMP_LENGTH) {
            "Workspace binding timestamp is invalid"
        }
    }

    /** Return a copy without exposing the mutable backing array held by this object. */
    fun encryptedLocatorCopy(): ByteArray = encryptedLocator.copyOf()

    /** Return a copy without exposing the mutable backing array held by this object. */
    fun locatorNonceCopy(): ByteArray = locatorNonce.copyOf()

    /** Keep ciphertext and nonce out of diagnostics, exceptions and UI previews. */
    override fun toString(): String =
        "PrivilegedWorkspaceBinding(workspaceId=$workspaceId, authority=$authority, " +
            "locator=<encrypted>, locatorVersion=$locatorVersion, keyVersion=$keyVersion, " +
            "scope=$scope, status=$status, revision=$revision)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrivilegedWorkspaceBinding) return false
        return workspaceId == other.workspaceId && authority == other.authority &&
            encryptedLocator.contentEquals(other.encryptedLocator) &&
            locatorNonce.contentEquals(other.locatorNonce) && locatorVersion == other.locatorVersion &&
            keyVersion == other.keyVersion && aadAppInstanceId == other.aadAppInstanceId &&
            aadWorkspaceId == other.aadWorkspaceId && aadAuthority == other.aadAuthority &&
            aadLocatorVersion == other.aadLocatorVersion && scope == other.scope &&
            status == other.status && revision == other.revision && createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = workspaceId.hashCode()
        result = 31 * result + authority.hashCode()
        result = 31 * result + encryptedLocator.contentHashCode()
        result = 31 * result + locatorNonce.contentHashCode()
        result = 31 * result + locatorVersion
        result = 31 * result + keyVersion
        result = 31 * result + aadAppInstanceId.hashCode()
        result = 31 * result + aadWorkspaceId.hashCode()
        result = 31 * result + aadAuthority.hashCode()
        result = 31 * result + aadLocatorVersion
        result = 31 * result + scope.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }

    companion object {
        const val MAX_LOCATOR_BYTES = 1 * 1024 * 1024
        const val MIN_NONCE_BYTES = 12
        const val MAX_NONCE_BYTES = 32
        const val MAX_TIMESTAMP_LENGTH = 128
    }
}

/**
 * A Thread/Conversation chooses exactly one workspace.  [sessionId] is the
 * historical name retained by the wire/data layer; [conversationId] is a
 * presentation alias and does not create a second identity.
 */
data class ConversationWorkspaceBinding(
    val sessionId: String,
    val workspaceId: String,
    val boundAt: String = "",
    val revision: Long = 1,
) {
    init {
        require(isBindingId(sessionId)) { "Conversation workspace session id is invalid" }
        require(isBindingId(workspaceId)) { "Conversation workspace id is invalid" }
        require(boundAt.length <= PrivilegedWorkspaceBinding.MAX_TIMESTAMP_LENGTH) {
            "Conversation workspace binding timestamp is invalid"
        }
        require(revision > 0) { "Conversation workspace binding revision must be positive" }
    }

    val conversationId: String get() = sessionId
}

/**
 * Agent-level default used only while creating a new Thread.  It is not an
 * authorization record and never changes an existing Conversation binding.
 * A null workspace explicitly clears the default.
 */
data class AgentWorkspaceDefault(
    val agentId: String,
    val workspaceId: String? = null,
    val revision: Long = 1,
    val updatedAt: String = "",
) {
    init {
        require(isBindingId(agentId)) { "Agent workspace default agent id is invalid" }
        require(workspaceId == null || isBindingId(workspaceId)) {
            "Agent workspace default workspace id is invalid"
        }
        require(revision > 0) { "Agent workspace default revision must be positive" }
        require(updatedAt.length <= PrivilegedWorkspaceBinding.MAX_TIMESTAMP_LENGTH) {
            "Agent workspace default timestamp is invalid"
        }
    }

    val defaultWorkspaceId: String? get() = workspaceId
}

/**
 * The three canonical workspace selection semantics.  They are mutually
 * exclusive at the product level: a single user selection must resolve to
 * exactly one intent, and the resulting transaction derives every side effect
 * from that intent instead of letting each screen compose attach/grant/default
 * on its own.
 *
 * - [ADD_TO_LIBRARY]: attach (or reuse) the workspace and, when an Agent is
 *   already known, grant it to that Agent.  Never changes the Agent default
 *   and never binds a Thread.
 * - [SET_AGENT_DEFAULT]: attach, grant, and make the workspace the Agent
 *   default used by future Threads.  This is the normal Agent editor flow.
 * - [BIND_THREAD]: attach, grant, and bind the workspace to one Thread on its
 *   **first** bind. A later request for a different workspace does not rewrite
 *   the existing Thread; the UI creates a new Thread instead.  It
 *   must never mutate the Agent default.
 */
enum class WorkspaceIntent {
    ADD_TO_LIBRARY,
    SET_AGENT_DEFAULT,
    BIND_THREAD,
}

/**
 * The subject a workspace selection applies to.  [agentId] is nullable so a
 * not-yet-saved Agent draft can still choose a workspace; the resulting
 * selection is then staged and committed only after the Agent exists.
 */
data class WorkspaceTarget(
    val agentId: String? = null,
    val threadId: String? = null,
) {
    init {
        require(agentId == null || isBindingId(agentId)) { "Workspace target agent id is invalid" }
        require(threadId == null || isBindingId(threadId)) { "Workspace target thread id is invalid" }
        require(threadId == null || agentId != null) {
            "A thread workspace target requires an agent target"
        }
    }
}

/**
 * A workspace selection made before its Agent exists.  Nothing about it is
 * authorized yet: no capability grant and no Agent default is written until
 * [WorkspaceDraft] is committed with a concrete Agent id, so abandoning the
 * editor cannot leave an orphan grant behind.
 */
data class WorkspaceDraft(
    val workspaceId: String,
    val displayName: String,
    val setAsAgentDefault: Boolean = true,
) {
    init {
        require(isBindingId(workspaceId)) { "Workspace draft id is invalid" }
        require(displayName.length <= 256) { "Workspace draft display name is invalid" }
    }
}

/**
 * The resolved side effects of one selection.  It is produced by
 * [WorkspaceIntent.plan] and is the only thing a downstream transaction is
 * allowed to read, so no screen can reintroduce a second attach/grant/default
 * composition.
 */
data class WorkspaceIntentPlan(
    val intent: WorkspaceIntent,
    val grantRequired: Boolean,
    val setAgentDefault: Boolean,
    val bindThread: Boolean,
    /** True when the Agent does not exist yet and the selection must be staged. */
    val deferred: Boolean,
) {
    init {
        require(!bindThread || !deferred) { "A deferred selection cannot bind a thread" }
        require(!setAgentDefault || !bindThread) {
            "One selection cannot both set the Agent default and bind a thread"
        }
        require(!deferred || !grantRequired) { "A deferred selection cannot grant immediately" }
    }
}

/**
 * Resolve one intent against its target.  The rules are intentionally
 * conservative: a missing Agent never silently downgrades to "attach only",
 * it becomes a deferred draft the caller must commit later.
 */
fun WorkspaceIntent.plan(target: WorkspaceTarget): WorkspaceIntentPlan {
    val hasAgent = target.agentId != null
    return when (this) {
        WorkspaceIntent.ADD_TO_LIBRARY -> WorkspaceIntentPlan(
            intent = this,
            grantRequired = hasAgent,
            setAgentDefault = false,
            bindThread = false,
            deferred = false,
        )
        WorkspaceIntent.SET_AGENT_DEFAULT -> WorkspaceIntentPlan(
            intent = this,
            grantRequired = hasAgent,
            setAgentDefault = true,
            bindThread = false,
            deferred = !hasAgent,
        )
        WorkspaceIntent.BIND_THREAD -> {
            require(target.threadId != null) { "Binding a thread workspace requires a thread target" }
            WorkspaceIntentPlan(
                intent = this,
                grantRequired = hasAgent,
                setAgentDefault = false,
                bindThread = true,
                deferred = false,
            )
        }
    }
}

private fun isBindingId(value: String): Boolean =
    value.length in 1..256 && value == value.trim() &&
        value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } &&
        value.first().isLetterOrDigit()
