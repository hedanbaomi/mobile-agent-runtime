// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

/** Observed operating-system identity, never inferred from an installed root manager. */
@Serializable
enum class PrivilegeTier(val uid: Int) { SHELL(2000), ROOT(0) }

val Authority.requiredPrivilegeTier: PrivilegeTier?
    get() = when (this) {
        Authority.NONE -> null
        Authority.SHIZUKU, Authority.WIRED_ADB -> PrivilegeTier.SHELL
        Authority.ROOT_DIRECT, Authority.SHIZUKU_ROOT -> PrivilegeTier.ROOT
    }

val Authority.isRootAuthority: Boolean
    get() = this == Authority.ROOT_DIRECT || this == Authority.SHIZUKU_ROOT

/**
 * Application consent is distinct from a manager's OS permission and an Agent grant.
 * This record must be owned by the local policy store, never imported as effective trust.
 */
@Serializable
data class RootAuthorityConsent(
    val authority: Authority,
    val enabled: Boolean = false,
    val shellEnabled: Boolean = false,
    val revision: Long = 0,
    val shellRevision: Long = 0,
    val policyVersion: Long = 0,
) {
    init {
        require(authority.isRootAuthority) { "A Root authority is required" }
        require(revision >= 0 && shellRevision >= 0 && policyVersion >= 0) { "Invalid consent revision" }
        require(!enabled || revision > 0) { "Enabled Root consent needs a revision" }
        require(!shellEnabled || (enabled && shellRevision > 0)) { "Root Shell needs explicit consent" }
    }
}

/**
 * Non-secret binding facts supplied by the authenticated platform bootstrap.
 * Constructing this DTO does not establish trust: the adapter must first verify
 * caller/package/signature/user against the OS, outside model-controlled input.
 */
@Serializable
data class PrivilegedSessionIdentity(
    val authority: Authority,
    val privilegeTier: PrivilegeTier,
    val serviceUid: Int,
    val appUid: Int,
    val androidUserId: Int,
    val appInstanceId: String,
    val packageName: String,
    val signingSha256: String,
    val identityEpoch: Long,
    val serviceInstanceId: String,
    val sessionId: String,
    val protocolVersion: Int,
) {
    init {
        require(authority != Authority.NONE && authority.requiredPrivilegeTier == privilegeTier) {
            "Authority and privilege tier differ"
        }
        require(serviceUid == privilegeTier.uid) { "Service UID differs from its authority" }
        require(appUid % 100_000 >= 10_000 && androidUserId >= 0 && appUid / 100_000 == androidUserId) {
            "Invalid application identity"
        }
        require(identityEpoch > 0 && protocolVersion > 0) { "Invalid service generation" }
        require(listOf(appInstanceId, serviceInstanceId, sessionId).all(::isOpaqueIdentity)) {
            "Invalid session identity"
        }
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            "Invalid package identity"
        }
        require(signingSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid signing digest" }
    }

    companion object {
        const val ROOT_PROTOCOL_VERSION = 4
        private fun isOpaqueIdentity(value: String): Boolean =
            value.length in 16..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}

/** Immutable admission stamp; enabling a route later cannot widen a running call. */
@Serializable
data class RootExecutionStamp(
    val identity: PrivilegedSessionIdentity,
    val consentRevision: Long,
    val shellConsentRevision: Long,
    val policyVersion: Long,
) {
    init {
        require(identity.authority.isRootAuthority) { "Root stamp requires a Root authority" }
        require(consentRevision > 0 && shellConsentRevision >= 0 && policyVersion >= 0) {
            "Invalid Root execution stamp"
        }
    }
}

enum class RootAccessDenial {
    AUTHORITY_CHANGED,
    PLATFORM_NOT_GRANTED,
    ROOT_CONSENT_REQUIRED,
    CONSENT_CHANGED,
    IDENTITY_CHANGED,
    PROTOCOL_UNSUPPORTED,
    SESSION_DEGRADED,
    ROOT_SHELL_CONSENT_REQUIRED,
    DANGEROUS_MODE_DISABLED,
}

/** Pure revalidation; canonical workspace/Agent grants remain a separate required gate. */
object RootAccessPolicy {
    fun denial(
        frozen: RootExecutionStamp,
        selectedAuthority: Authority,
        liveIdentity: PrivilegedSessionIdentity?,
        liveConsent: RootAuthorityConsent?,
        platformGranted: Boolean,
        degraded: Boolean,
        shell: Boolean,
        dangerousMode: DangerousMode,
        livePolicyVersion: Long,
    ): RootAccessDenial? {
        if (selectedAuthority != frozen.identity.authority) return RootAccessDenial.AUTHORITY_CHANGED
        if (!platformGranted) return RootAccessDenial.PLATFORM_NOT_GRANTED
        if (degraded) return RootAccessDenial.SESSION_DEGRADED
        if (liveConsent == null || !liveConsent.enabled ||
            liveConsent.authority != frozen.identity.authority
        ) return RootAccessDenial.ROOT_CONSENT_REQUIRED
        if (livePolicyVersion != frozen.policyVersion ||
            liveConsent.revision != frozen.consentRevision ||
            liveConsent.policyVersion != frozen.policyVersion
        ) return RootAccessDenial.CONSENT_CHANGED
        if (liveIdentity == null || liveIdentity != frozen.identity) return RootAccessDenial.IDENTITY_CHANGED
        if (liveIdentity.protocolVersion != PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION) {
            return RootAccessDenial.PROTOCOL_UNSUPPORTED
        }
        if (shell && (!liveConsent.shellEnabled || frozen.shellConsentRevision <= 0 ||
                liveConsent.shellRevision != frozen.shellConsentRevision)
        ) return RootAccessDenial.ROOT_SHELL_CONSENT_REQUIRED
        return null
    }
}
