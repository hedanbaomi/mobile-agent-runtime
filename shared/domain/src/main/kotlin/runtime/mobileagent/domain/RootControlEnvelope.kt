// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Root protocol v4 typed execution envelope.
 *
 * The controlled service decodes this envelope with strict no-unknown-keys and
 * no-duplicate-keys rules, re-normalizes [arguments] against its closed schema,
 * verifies [normalizedArgumentsSha256], and computes its own canonical envelope
 * digest bound to [execution] (including serviceInstanceId, sessionId, and identityEpoch).
 */
@Serializable
data class RootControlEnvelope(
    val requestId: String,
    val runId: String,
    val invocationId: String,
    val snapshotId: String,
    val grantId: String,
    val grantBeforeRevision: Long,
    val grantAfterRevision: Long,
    val once: Boolean,
    val grantConsumedAt: String? = null,
    val execution: RootExecutionStamp,
    val capability: CapabilityId,
    val toolName: String,
    val workspaceId: String,
    val workspaceGenerationId: String,
    val workspaceSessionHandle: String,
    val relativePathScope: String? = null,
    val arguments: RootTypedArguments,
    val rawArgumentsSha256: String,
    val normalizedArgumentsSha256: String,
    val budget: RootExecutionBudget = RootExecutionBudget(),
    val commandHash: String? = null,
    val cwdHash: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported Root control envelope schemaVersion" }
        require(execution.identity.protocolVersion == PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION) {
            "Root control envelope requires protocol version 4"
        }
        require(listOf(requestId, runId, invocationId, snapshotId, grantId, workspaceId).all(RootPathGuards::isSafeId)) {
            "Root control envelope identifier is invalid"
        }
        require(RootPathGuards.isOpaqueId(workspaceGenerationId) && RootPathGuards.isOpaqueId(workspaceSessionHandle)) {
            "Root workspace generation or session handle is invalid"
        }
        require(grantBeforeRevision > 0L) { "Root grant revision must be positive" }
        require(grantAfterRevision == Math.addExact(grantBeforeRevision, if (once) 1L else 0L)) {
            "Root ONCE grant revision transition is invalid"
        }
        require(once == (grantConsumedAt != null)) { "Root ONCE consumption timestamp mismatch" }
        grantConsumedAt?.let(Instant::parse)
        require(RootApprovalTools.capability(toolName) == capability && arguments.requiredCapability == capability) {
            "Root tool capability mapping mismatch"
        }
        val validToolName = toolName == arguments.toolName ||
            (toolName == "apply_patch" && arguments is RootTypedArguments.FileApplyPatch)
        require(validToolName) { "Root tool name does not match closed typed arguments" }
        require(relativePathScope == null || RootPathGuards.isValidRelativePath(relativePathScope, allowRoot = false)) {
            "Root relativePathScope is invalid"
        }
        arguments.validateScope(workspaceId, relativePathScope, execution)
        require(RootPathGuards.isSha256(rawArgumentsSha256) && RootPathGuards.isSha256(normalizedArgumentsSha256)) {
            "Root argument digests must be 64-character lowercase hex SHA-256"
        }
        require(arguments.canonicalSha256() == normalizedArgumentsSha256) {
            "Service re-normalized arguments digest does not match normalizedArgumentsSha256"
        }
        validateShellHashes()
    }

    private fun validateShellHashes() {
        if (arguments is RootTypedArguments.ShellExec) {
            require(commandHash == RootControlJson.sha256Hex(arguments.command)) {
                "Shell commandHash does not match typed command"
            }
            require(cwdHash == arguments.cwdRelativePath?.let(RootControlJson::sha256Hex)) {
                "Shell cwdHash does not match typed cwdRelativePath"
            }
        } else {
            require(commandHash == null && cwdHash == null) {
                "Non-shell Root tool must not carry commandHash or cwdHash"
            }
        }
    }

    fun canonicalJson(): String = RootControlJson.codec.encodeToString(serializer(), this).also { encoded ->
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENVELOPE_BYTES) {
            "Canonical Root control envelope exceeds 64KiB"
        }
    }

    fun canonicalSha256(): String = RootControlJson.sha256Hex(canonicalJson())

    override fun toString(): String =
        "RootControlEnvelope(authority=${execution.identity.authority}, capability=$capability, schemaVersion=$schemaVersion)"

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_ENVELOPE_BYTES = 65_536

        fun decodeAndCanonicalize(rawJson: String): RootControlEnvelope {
            RootControlJson.requireStrictJsonObject(rawJson, MAX_ENVELOPE_BYTES, maxDepth = 10, maxNodes = 512)
            return RootControlJson.codec.decodeFromString(serializer(), rawJson).also { it.canonicalJson() }
        }
    }
}
