// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.storage

import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.StoredBlob
import java.io.File
import java.security.MessageDigest

class CasBlobSink(private val root: File) : BlobSink {
    override fun put(bytes: ByteArray, mediaType: String): StoredBlob {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val dir = File(root, sha.take(2))
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, sha)
        if (!file.exists()) {
            file.outputStream().use { it.write(bytes) }
        }
        return StoredBlob(sha, bytes.size, mediaType, "cas/${sha.take(2)}/$sha")
    }
}
