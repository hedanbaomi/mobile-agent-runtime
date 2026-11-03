// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.RootWorkspaceLocator
import runtime.mobileagent.domain.RootWorkspaceOrigin
import runtime.mobileagent.security.AndroidKeystoreRootWorkspaceBindingCipherFactory
import runtime.mobileagent.security.PrivilegedWorkspaceBindingCipher
import runtime.mobileagent.security.PrivilegedWorkspaceBindingEnvelope
import runtime.mobileagent.security.PrivilegedWorkspaceBindingEnvelopeDecodeResult
import runtime.mobileagent.security.PrivilegedWorkspaceBindingOpenResult
import runtime.mobileagent.security.PrivilegedWorkspaceBindingSealResult
import runtime.mobileagent.security.RootWorkspaceBindingAad

/** No plaintext fallback. Test implementations must be explicitly injected. */
interface RootLocatorCipher {
    fun seal(
        origin: RootWorkspaceOrigin,
        plaintext: ByteArray,
        locatorVersion: Int = 1,
        keyVersion: Int = 1,
        aadVersion: Int = RootWorkspaceLocator.CURRENT_AAD_VERSION,
    ): ByteArray?

    fun open(
        origin: RootWorkspaceOrigin,
        ciphertext: ByteArray,
        locatorVersion: Int = 1,
        keyVersion: Int = 1,
        aadVersion: Int = RootWorkspaceLocator.CURRENT_AAD_VERSION,
    ): ByteArray?
}

class AndroidRootLocatorCipher(
    private val cipherProvider: () -> PrivilegedWorkspaceBindingCipher = AndroidKeystoreRootWorkspaceBindingCipherFactory::create,
) : RootLocatorCipher {
    private val cipher by lazy(cipherProvider)

    override fun seal(
        origin: RootWorkspaceOrigin,
        plaintext: ByteArray,
        locatorVersion: Int,
        keyVersion: Int,
        aadVersion: Int,
    ): ByteArray? = runCatching {
        val aad = RootWorkspaceBindingAad(origin, locatorVersion, keyVersion, aadVersion)
        (cipher.seal(plaintext, aad) as? PrivilegedWorkspaceBindingSealResult.Success)?.envelope?.toByteArray()
    }.getOrNull()

    override fun open(
        origin: RootWorkspaceOrigin,
        ciphertext: ByteArray,
        locatorVersion: Int,
        keyVersion: Int,
        aadVersion: Int,
    ): ByteArray? = runCatching {
        val decoded = PrivilegedWorkspaceBindingEnvelope.decode(ciphertext)
            as? PrivilegedWorkspaceBindingEnvelopeDecodeResult.Success ?: return null
        val aad = RootWorkspaceBindingAad(origin, locatorVersion, keyVersion, aadVersion)
        (cipher.open(decoded.envelope, aad) as? PrivilegedWorkspaceBindingOpenResult.Success)?.locator
    }.getOrNull()
}
