// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.DriverMutationStatus
import runtime.mobileagent.domain.RootFdNodeIdentity

/**
 * Refusal policy for `file_move` before and around its entry commit.
 *
 * The commit itself stays in [RootPosixMutations]; this object only decides which errors may be
 * reported as what. Two namespace primitives back the move, and neither one is a target-type CAS:
 *
 *  1. `renameat2(RENAME_NOREPLACE)` commits every no-replace move and every directory move (even
 *     with `replace_existing = true`). An entry that appears inside the syscall makes it fail
 *     instead of being destroyed, so the "nothing may be replaced" contract is kernel-enforced and
 *     not a check-then-rename. A missing primitive is an explicit unsupported result.
 *  2. `rename(2)` commits an explicitly requested **regular-file** replace. It atomically replaces
 *     the destination *directory entry* and never follows a symlink: a destination that is (or
 *     becomes) a directory makes the kernel refuse with `EISDIR`/`ENOTEMPTY`, while a symlink that
 *     appears inside the window is replaced as an entry. That is the same unconditional-overwrite
 *     contract the Internal/Wired backends implement (`ATOMIC_MOVE` + `REPLACE_EXISTING` after a
 *     no-follow pre-check). It is not, and is not claimed to be, a guarantee that the destination
 *     is a regular file at commit time.
 *
 * [destinationRejection] is therefore a *best-effort pre-check*, not the commit guarantee: it
 * refuses the states the filesystem can already prove non-replaceable (directory, symlink, special
 * file, multi-link file). After the commit nothing is ever deleted by name, so a swapped source or
 * a replaced entry can only surface as `UNKNOWN`, never as a silent unlink.
 */
internal object RootPosixMoveCommit {
    /** Attr channel reported when the kernel has no atomic no-replace rename. */
    const val ATOMIC_NO_REPLACE_RENAME = "atomic_no_replace_rename"

    /** Errnos that mean the primitive itself is missing, not that the move was denied. */
    private val PRIMITIVE_UNSUPPORTED_ERRNOS = setOf("EOPNOTSUPP", "ENOTSUP", "ENOSYS")

    /** Errnos that mean the destination entry appeared or is not replaceable. */
    private val DESTINATION_OCCUPIED_ERRNOS = setOf("EEXIST", "ENOTEMPTY", "EISDIR", "ENOTDIR")

    /**
     * Best-effort pre-check for an already-present destination entry.
     *
     * `replaceExisting = true` authorizes replacing one single-link regular file — the same
     * precondition the Internal/Wired backends apply before their `REPLACE_EXISTING` commit.
     * Directories, symlinks, special files and multi-link files keep the fail-closed race result,
     * and the commit still cannot re-check the type inside the rename window (see the object
     * documentation).
     */
    fun destinationRejection(
        existing: RootFdNodeIdentity?,
        replaceExisting: Boolean,
        sourceIsDirectory: Boolean,
    ): DriverMutationStatus? = when {
        existing == null -> null
        !replaceExisting || sourceIsDirectory || existing.isSymlink ||
            !existing.isRegularFile || existing.hardlinkCount != 1L ||
            !existing.hasValidKernelIdentity ->
            DriverMutationStatus.RaceOrDisappeared
        else -> null
    }

    /**
     * Maps a failed commit before any mutation. EINVAL is deliberately not a capability verdict:
     * it can mean an invalid directory relationship even on a filesystem supporting renameat2.
     */
    fun refusal(err: PosixOpException): DriverMutationStatus = when (err.errnoName) {
        in PRIMITIVE_UNSUPPORTED_ERRNOS -> DriverMutationStatus.Unsupported(ATOMIC_NO_REPLACE_RENAME)
        in DESTINATION_OCCUPIED_ERRNOS -> DriverMutationStatus.RaceOrDisappeared
        else -> DriverMutationStatus.Denied(err.errnoName, err.selinuxConfirmed)
    }
}
