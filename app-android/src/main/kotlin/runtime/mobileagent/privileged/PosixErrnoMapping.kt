// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.system.OsConstants

internal object PosixErrnoMapping {
    private val UNSUPPORTED_XATTR_ERRNOS = setOf("EOPNOTSUPP", "ENOTSUP", "ENOSYS")

    fun isUnsupportedXattr(errnoName: String): Boolean = errnoName in UNSUPPORTED_XATTR_ERRNOS

    fun name(errno: Int, fallback: String): String {
        if (errno <= 0) return fallback
        return when (errno) {
            OsConstants.EPERM, 1 -> "EPERM"
            OsConstants.ENOENT, 2 -> "ENOENT"
            OsConstants.EINTR, 4 -> "EINTR"
            OsConstants.EIO, 5 -> "EIO"
            OsConstants.ENXIO, 6 -> "ENXIO"
            OsConstants.EBADF, 9 -> "EBADF"
            OsConstants.EAGAIN, 11 -> "EAGAIN"
            OsConstants.EACCES, 13 -> "EACCES"
            OsConstants.EBUSY, 16 -> "EBUSY"
            OsConstants.EEXIST, 17 -> "EEXIST"
            OsConstants.EXDEV, 18 -> "EXDEV"
            OsConstants.ENODEV, 19 -> "ENODEV"
            OsConstants.ENOTDIR, 20 -> "ENOTDIR"
            OsConstants.EISDIR, 21 -> "EISDIR"
            OsConstants.EINVAL, 22 -> "EINVAL"
            OsConstants.EFBIG, 27 -> "EFBIG"
            OsConstants.ENOSPC, 28 -> "ENOSPC"
            OsConstants.EROFS, 30 -> "EROFS"
            OsConstants.EMLINK, 31 -> "EMLINK"
            OsConstants.ENAMETOOLONG, 36 -> "ENAMETOOLONG"
            OsConstants.ENOSYS, 38 -> "ENOSYS"
            OsConstants.ENOTEMPTY, 39 -> "ENOTEMPTY"
            OsConstants.ELOOP, 40 -> "ELOOP"
            OsConstants.EOVERFLOW, 75 -> "EOVERFLOW"
            OsConstants.EOPNOTSUPP, 95 -> "EOPNOTSUPP"
            OsConstants.ETIMEDOUT, 110 -> "ETIMEDOUT"
            OsConstants.EDQUOT, 122 -> "EDQUOT"
            OsConstants.ECANCELED, 125 -> "ECANCELED"
            else -> fallback
        }
    }
}
