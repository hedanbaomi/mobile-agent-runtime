// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.io.FileInputStream
import java.io.InputStream
import runtime.mobileagent.domain.RootKernelBootIdentity

/** UID0-service observation only. No caller path, settings value or random fallback is accepted. */
internal object RootKernelBootReader {
    fun read(): String? = runCatching {
        FileInputStream("/proc/sys/kernel/random/boot_id").use(::readBounded)
    }.getOrNull()

    internal fun readBounded(input: InputStream): String? {
        // Canonical UUID is 36 ASCII bytes, optionally followed by one kernel LF.
        val buffer = ByteArray(38)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            if (count == 0) return null
            size += count
        }
        if (size !in 36..37 || (size == 37 && buffer[36] != '\n'.code.toByte())) return null
        val value = runCatching { buffer.copyOf(36).decodeToString(throwOnInvalidSequence = true) }.getOrNull()
        return value?.takeIf(RootKernelBootIdentity::isValid)
    }
}
