// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Paths

/**
 * SELinux label observation for [AndroidOsPosixFdOperations].
 *
 * Extracted for the conditional-token ratchet. The label comes from the injected reader when one is
 * present, otherwise from `security.selinux` on `/proc/self/fd/<n>`; any failure stays `null`, which
 * makes the identity observation fail closed.
 */
internal class AndroidOsFdIdentityReader(private val ops: AndroidOsPosixFdOperations) {
    fun selinuxLabel(fdNumber: Int, resolvedPath: String): String? {
        ops.selinuxLabelReaderOrNull?.let { reader ->
            return runCatching { reader(Paths.get(resolvedPath)) }.getOrNull()
        }
        return runCatching {
            ops.kernel.getxattr("/proc/self/fd/$fdNumber", "security.selinux")
                .toString(Charsets.UTF_8)
                .trimEnd('\u0000')
        }.getOrNull()
    }
}
