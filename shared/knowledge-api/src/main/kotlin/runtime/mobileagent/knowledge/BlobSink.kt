// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

data class StoredBlob(
    val sha256: String,
    val byteLength: Int,
    val mediaType: String,
    val localRef: String,
)

interface BlobSink {
    fun put(bytes: ByteArray, mediaType: String): StoredBlob
    fun get(sha256: String): ByteArray?
    /** Shared with every handle to the same CAS root; publication and sweep use this lock. */
    fun <T> withStorageLock(block: () -> T): T = synchronized(this, block)
    fun protect(sha256: String): AutoCloseable = AutoCloseable { }
    fun protectedHashes(): Set<String> = emptySet()
    fun storedHashes(): Set<String> = emptySet()
    fun allocatedBytes(): Long = 0
    fun storedByteLength(sha256: String): Long = get(sha256)?.size?.toLong() ?: 0
    fun remove(sha256: String): Boolean = false
    fun removeTemporaryFiles(): Long = 0
}

class MemoryBlobSink : BlobSink {
    val blobs = linkedMapOf<String, ByteArray>()
    private val protections = mutableMapOf<String, Int>()

    override fun put(bytes: ByteArray, mediaType: String): StoredBlob = withStorageLock {
        val sha = sha256Hex(bytes)
        blobs[sha] = bytes.copyOf()
        StoredBlob(sha, bytes.size, mediaType, "memory:$sha")
    }

    override fun get(sha256: String): ByteArray? = withStorageLock { blobs[sha256]?.copyOf() }
    override fun protect(sha256: String): AutoCloseable = protection(protections, sha256, ::withStorageLock)
    override fun protectedHashes(): Set<String> = withStorageLock { protections.keys.toSet() }
    override fun storedHashes(): Set<String> = withStorageLock { blobs.keys.toSet() }
    override fun allocatedBytes(): Long = withStorageLock { blobs.values.sumOf { it.size.toLong() } }
    override fun storedByteLength(sha256: String): Long = withStorageLock { blobs[sha256]?.size?.toLong() ?: 0 }
    override fun remove(sha256: String): Boolean = withStorageLock {
        sha256 !in protections && (blobs.remove(sha256) != null || sha256 !in blobs)
    }
}

class FileBlobSink(private val root: java.io.File) : BlobSink {
    private val state = states.computeIfAbsent(root.canonicalPath) { State() }
    override fun <T> withStorageLock(block: () -> T): T = synchronized(state, block)
    override fun protect(sha256: String): AutoCloseable = protection(state.protections, sha256, ::withStorageLock)
    override fun protectedHashes(): Set<String> = withStorageLock { state.protections.keys.toSet() }
    override fun storedHashes(): Set<String> = withStorageLock {
        inventory().first
    }
    override fun allocatedBytes(): Long = withStorageLock { inventory().second }
    override fun storedByteLength(sha256: String): Long = withStorageLock {
        require(validHash(sha256))
        java.io.File(java.io.File(root, sha256.take(2)), sha256).length()
    }

    private fun inventory(): Pair<Set<String>, Long> = state.inventory ?: run {
        val files = root.listFiles().orEmpty().flatMap { if (it.isDirectory) it.listFiles().orEmpty().toList() else listOf(it) }
            .filter { it.isFile }
        (files.filter { validHash(it.name) && it.parentFile.name == it.name.take(2) }.map { it.name }.toSet() to
            files.sumOf { it.length() }).also { state.inventory = it }
    }

    override fun removeTemporaryFiles(): Long = withStorageLock {
        var reclaimed = 0L
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{2}")) }.forEach { dir ->
            dir.listFiles().orEmpty().filter {
                it.isFile && it.name.matches(Regex("[0-9a-f]{64}-[0-9]+\\.tmp")) && it.name.startsWith(dir.name)
            }.forEach { val bytes = it.length(); if (it.delete()) reclaimed += bytes }
        }
        state.inventory = null
        reclaimed
    }
    override fun remove(sha256: String): Boolean = withStorageLock {
        require(validHash(sha256))
        if (sha256 in state.protections) return@withStorageLock false
        val file = java.io.File(java.io.File(root, sha256.take(2)), sha256)
        (!file.exists() || file.delete()).also { state.inventory = null }
    }

    override fun put(bytes: ByteArray, mediaType: String): StoredBlob = withStorageLock {
        state.inventory = null
        val sha = sha256Hex(bytes)
        val dir = java.io.File(root, sha.take(2))
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            error("Could not create CAS directory")
        }
        val file = java.io.File(dir, sha)
        if (file.isFile && file.length() == bytes.size.toLong() && sha256Hex(file.readBytes()) == sha) {
            return@withStorageLock StoredBlob(sha, bytes.size, mediaType, ref(sha))
        }
        if (file.exists()) {
            file.delete()
        }
        val tmp = java.io.File.createTempFile("$sha-", ".tmp", dir)
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            check(tmp.length() == bytes.size.toLong()) { "CAS write length mismatch" }
            check(sha256Hex(tmp.readBytes()) == sha) { "CAS write hash mismatch" }
            if (!tmp.renameTo(file)) {
                error("CAS atomic commit failed")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        check(file.isFile && file.length() == bytes.size.toLong() && sha256Hex(file.readBytes()) == sha) {
            "CAS commit failed"
        }
        StoredBlob(sha, bytes.size, mediaType, ref(sha))
    }

    override fun get(sha256: String): ByteArray? = withStorageLock {
        require(validHash(sha256)) { "Invalid CAS hash" }
        val file = java.io.File(java.io.File(root, sha256.take(2)), sha256)
        if (!file.isFile) return@withStorageLock null
        val bytes = file.readBytes()
        if (sha256Hex(bytes) != sha256) return@withStorageLock null
        bytes
    }

    private fun ref(sha: String): String = "cas/${sha.take(2)}/$sha"
    private class State {
        val protections = mutableMapOf<String, Int>()
        var inventory: Pair<Set<String>, Long>? = null
    }
    companion object { private val states = java.util.concurrent.ConcurrentHashMap<String, State>() }
}

private fun validHash(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))

private fun protection(counts: MutableMap<String, Int>, hash: String, lock: ((() -> Unit) -> Unit)): AutoCloseable {
    require(validHash(hash))
    lock { counts[hash] = (counts[hash] ?: 0) + 1 }
    val closed = java.util.concurrent.atomic.AtomicBoolean()
    return AutoCloseable {
        if (closed.compareAndSet(false, true)) lock {
            val count = checkNotNull(counts[hash]) - 1
            if (count == 0) counts.remove(hash) else counts[hash] = count
        }
    }
}

fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
