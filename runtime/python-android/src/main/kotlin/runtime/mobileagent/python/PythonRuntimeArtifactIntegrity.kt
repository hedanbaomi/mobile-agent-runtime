// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.python

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.security.MessageDigest
import runtime.mobileagent.skills.SkillArchive

/** Checks host-staged ZIP bytes before Binder START is acknowledged. Native code repeats this check. */
object PythonRuntimeArtifactIntegrity {
    private val sha256Hex = Regex("[0-9a-f]{64}")

    fun matches(descriptor: ParcelFileDescriptor, expectedHash: String): Boolean {
        if (!sha256Hex.matches(expectedHash)) return false
        return try {
            val stat = Os.fstat(descriptor.fileDescriptor)
            if ((stat.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG) return false
            if (stat.st_size !in 22L..SkillArchive.MAX_RUNTIME_ARTIFACT_BYTES) return false

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var offset = 0L
            while (offset < stat.st_size) {
                val remaining = stat.st_size - offset
                val requested = minOf(buffer.size.toLong(), remaining).toInt()
                val count = Os.pread(descriptor.fileDescriptor, buffer, 0, requested, offset)
                if (count <= 0 || count > requested) return false
                digest.update(buffer, 0, count)
                offset += count
            }
            MessageDigest.isEqual(decodeHex(expectedHash), digest.digest())
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeHex(value: String): ByteArray = ByteArray(32) { index ->
        val high = value[index * 2].digitToInt(16)
        val low = value[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}
