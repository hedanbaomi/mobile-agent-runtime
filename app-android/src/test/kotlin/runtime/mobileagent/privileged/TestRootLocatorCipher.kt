// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import runtime.mobileagent.domain.RootWorkspaceOrigin
import runtime.mobileagent.security.RootWorkspaceBindingAad
import runtime.mobileagent.security.WorkspaceBindingAadCodec

/** JVM-only AEAD seam. This proves bridge use of AAD/ciphertext, not Android Keystore availability. */
internal class TestRootLocatorCipher : RootLocatorCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun seal(
        origin: RootWorkspaceOrigin,
        plaintext: ByteArray,
        locatorVersion: Int,
        keyVersion: Int,
        aadVersion: Int,
    ): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(
            WorkspaceBindingAadCodec.encode(
                RootWorkspaceBindingAad(
                    origin = origin,
                    locatorVersion = locatorVersion,
                    keyVersion = keyVersion,
                    aadLocatorVersion = aadVersion,
                ),
            ),
        )
        return nonce + cipher.doFinal(plaintext)
    }

    override fun open(
        origin: RootWorkspaceOrigin,
        ciphertext: ByteArray,
        locatorVersion: Int,
        keyVersion: Int,
        aadVersion: Int,
    ): ByteArray? = runCatching {
        require(ciphertext.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        cipher.updateAAD(
            WorkspaceBindingAadCodec.encode(
                RootWorkspaceBindingAad(
                    origin = origin,
                    locatorVersion = locatorVersion,
                    keyVersion = keyVersion,
                    aadLocatorVersion = aadVersion,
                ),
            ),
        )
        cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }.getOrNull()
}
