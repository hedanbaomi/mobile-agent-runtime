// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.announcements

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

object AnnouncementSignature {
    const val PREFIX = "MAR-ANNOUNCEMENTS-V1\n"

    fun verify(payloadBase64: String, signatureBase64: String, publicKey: ByteArray): Boolean {
        val message = (PREFIX + payloadBase64).toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(Base64.getDecoder().decode(signatureBase64))
    }

    fun decodePayload(payloadBase64: String): ByteArray = Base64.getDecoder().decode(payloadBase64)
}
