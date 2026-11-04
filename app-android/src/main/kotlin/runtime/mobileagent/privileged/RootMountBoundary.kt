// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.io.File
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootTrustedMountRoots

/** Kernel mount roots distinguish ordinary mounted subtrees from aliases of private host files. */
internal object RootMountBoundary {
    private data class Mount(val id: Long, val device: String, val root: String, val point: String)

    fun read(boundary: RootControlPlaneBoundary): RootTrustedMountRoots? = runCatching {
        parse(readKernelMountInfo() ?: return null, boundary) { File(it).canonicalPath }
    }.getOrNull()

    internal fun fileSystemType(mountId: Long): String? = runCatching {
        readKernelMountInfo()?.lineSequence()?.firstOrNull {
            it.substringBefore(' ').toLongOrNull() == mountId
        }?.substringAfter(" - ", "")?.substringBefore(' ')?.takeIf(String::isNotEmpty)
    }.getOrNull()

    private fun readKernelMountInfo(): String? {
        return File("/proc/self/mountinfo").inputStream().use { input ->
            val bytes = ByteArray(1_048_577)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                if (count == 0) return null
                size += count
            }
            if (size == bytes.size) return null
            String(bytes, 0, size, Charsets.UTF_8)
        }
    }

    internal fun parse(
        text: String, boundary: RootControlPlaneBoundary,
        resolveProtectedPath: (String) -> String = { it },
    ): RootTrustedMountRoots? {
        val mounts = text.lineSequence().filter(String::isNotBlank).toList().map { line ->
            val fields = line.split(' ')
            if (fields.size < 10 || " - " !in line) return null
            Mount(fields[0].toLongOrNull() ?: return null, fields[2], unescape(fields[3]), unescape(fields[4]))
        }.toList()
        val protectedPaths = boundary.protectedDataDirectoryPaths()
            .flatMap { listOf(it, resolveProtectedPath(it)) }.toSet()
        val protected = listOf(
            boundary.credentialEncryptedDataDir to boundary.credentialEncryptedIdentity.mountId,
            boundary.deviceProtectedDataDir to boundary.deviceProtectedIdentity.mountId,
        ).map { (configuredPath, id) ->
            // Android user-0 CE may be /data/user/0 -> /data/data. Mountinfo roots use the
            // actual filesystem path, not the Context spelling of that symlink.
            val path = resolveProtectedPath(configuredPath)
            val mount = mounts.singleOrNull { it.id == id } ?: return null
            if (!under(path, mount.point)) return null
            val suffix = path.removePrefix(mount.point.trimEnd('/'))
            Triple(mount.device, (mount.root.trimEnd('/') + suffix).ifEmpty { "/" }, path)
        }
        val allowed = mounts.filter { mount ->
            mount.id > 0 && mount.root.startsWith('/') && mount.point.startsWith('/') &&
                protected.none { (device, root, _) ->
                    mount.device == device && (under(mount.root, root) ||
                        (under(root, mount.root) &&
                            mount.point.trimEnd('/') + root.removePrefix(mount.root.trimEnd('/')) !in protectedPaths))
                }
        }.mapTo(linkedSetOf()) { it.id }
        return RootTrustedMountRoots.directoryTree(allowed)
    }

    private fun under(path: String, root: String) =
        path == root || path.startsWith(root.trimEnd('/') + "/")

    private fun unescape(value: String): String = Regex("""\\([0-7]{3})""").replace(value) {
        it.groupValues[1].toInt(8).toChar().toString()
    }
}
