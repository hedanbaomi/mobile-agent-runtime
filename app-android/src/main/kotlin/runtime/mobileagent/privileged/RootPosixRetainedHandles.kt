// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

internal data class RetainedTargetEntry(
    val parentFd: PosixRetainedFd,
    val targetFd: PosixRetainedFd,
    val leafName: String,
) : AutoCloseable {
    override fun close() {
        runCatching { targetFd.close() }
        runCatching { parentFd.close() }
    }
}

internal data class RetainedParentEntry(
    val parentFd: PosixRetainedFd,
    val leafName: String,
) : AutoCloseable {
    override fun close() {
        runCatching { parentFd.close() }
    }
}

internal class RootPosixRetainedRegistry {
    private val targets = java.util.concurrent.ConcurrentHashMap<String, RetainedTargetEntry>()
    private val parents = java.util.concurrent.ConcurrentHashMap<String, RetainedParentEntry>()

    fun storeTarget(entry: RetainedTargetEntry, handle: String) {
        evictOldest(targets)?.close()
        targets[handle] = entry
    }

    fun storeParent(entry: RetainedParentEntry, handle: String) {
        evictOldest(parents)?.close()
        parents[handle] = entry
    }

    fun consumeTarget(handle: String): RetainedTargetEntry? = targets.remove(handle)

    fun consumeParent(handle: String): RetainedParentEntry? = parents.remove(handle)

    fun release(handle: String) {
        targets.remove(handle)?.close()
        parents.remove(handle)?.close()
    }

    fun closeAll() {
        targets.keys.toList().forEach { targets.remove(it)?.close() }
        parents.keys.toList().forEach { parents.remove(it)?.close() }
    }

    fun targetCount(): Int = targets.size

    private fun <T : AutoCloseable> evictOldest(map: java.util.concurrent.ConcurrentHashMap<String, T>): T? =
        if (map.size >= MAX_RETAINED_HANDLES) map.keys.firstOrNull()?.let { map.remove(it) } else null

    companion object {
        const val MAX_RETAINED_HANDLES = 64
    }
}
