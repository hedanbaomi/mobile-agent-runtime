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
        val sha = sha256Hex(bytes)
        blobs[sha] = bytes.copyOf()
        return StoredBlob(sha, bytes.size, mediaType, "memory:$sha")
    }
}

class FileBlobSink(private val root: java.io.File) : BlobSink {
    override fun put(bytes: ByteArray, mediaType: String): StoredBlob {
        val sha = sha256Hex(bytes)
        val dir = java.io.File(root, sha.take(2))
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            error("Could not create CAS directory")
        }
        val file = java.io.File(dir, sha)
        if (file.isFile && file.length() == bytes.size.toLong() && sha256Hex(file.readBytes()) == sha) {
            return StoredBlob(sha, bytes.size, mediaType, ref(sha))
        }
        if (file.exists()) {
            file.delete()
        }
        val tmp = java.io.File.createTempFile("$sha-", ".tmp", dir)
        try {
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.flush()
            }
            check(tmp.length() == bytes.size.toLong()) { "CAS write length mismatch" }
            check(sha256Hex(tmp.readBytes()) == sha) { "CAS write hash mismatch" }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        check(file.isFile && file.length() == bytes.size.toLong() && sha256Hex(file.readBytes()) == sha) {
            "CAS commit failed"
        }
        return StoredBlob(sha, bytes.size, mediaType, ref(sha))
    }

    private fun ref(sha: String): String = "cas/${sha.take(2)}/$sha"
}

internal fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
