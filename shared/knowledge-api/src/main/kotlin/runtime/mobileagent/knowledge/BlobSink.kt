// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

data class StoredBlob(
    val sha256: String,
    val byteLength: Int,
    val mediaType: String,
    val localRef: String,
)

fun interface BlobSink {
    fun put(bytes: ByteArray, mediaType: String): StoredBlob
}

class MemoryBlobSink : BlobSink {
    val blobs = linkedMapOf<String, ByteArray>()

    override fun put(bytes: ByteArray, mediaType: String): StoredBlob {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val sha = digest.joinToString("") { b -> "%02x".format(b) }
        blobs[sha] = bytes.copyOf()
        return StoredBlob(sha, bytes.size, mediaType, "memory:$sha")
    }
}
