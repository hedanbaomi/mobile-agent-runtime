// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/**
 * An immutable encrypted locator for a fresh Root workspace. No ready backend or
 * platform authorization is implied. Selecting another directory requires a new workspace.
 */
class RootWorkspaceLocator(
    val origin: RootWorkspaceOrigin,
    encryptedEnvelope: ByteArray,
    val locatorVersion: Int = 1,
    val keyVersion: Int = 1,
    val aadVersion: Int = CURRENT_AAD_VERSION,
) {
    private val envelope = encryptedEnvelope.copyOf()

    init {
        require(envelope.size in 1..MAX_ENVELOPE_BYTES)
        require(locatorVersion > 0 && keyVersion > 0 && aadVersion == CURRENT_AAD_VERSION)
    }

    fun encryptedEnvelopeCopy(): ByteArray = envelope.copyOf()

    override fun equals(other: Any?): Boolean = other is RootWorkspaceLocator &&
        origin == other.origin && locatorVersion == other.locatorVersion &&
        keyVersion == other.keyVersion && aadVersion == other.aadVersion &&
        envelope.contentEquals(other.envelope)

    override fun hashCode(): Int = listOf(origin,locatorVersion,keyVersion,aadVersion,envelope.contentHashCode()).hashCode()

    override fun toString(): String = "RootWorkspaceLocator(authority=${origin.authority}, aadVersion=$aadVersion)"

    companion object {
        const val CURRENT_AAD_VERSION = 2
        const val MAX_ENVELOPE_BYTES = 16_384
    }
}
