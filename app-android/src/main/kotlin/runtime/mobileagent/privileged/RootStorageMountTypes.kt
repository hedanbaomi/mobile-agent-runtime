// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

/**
 * Kernel mount types whose entry semantics differ from POSIX filesystems.
 *
 * Android emulated storage (`fuse`, `sdcardfs`), the FAT/exFAT family and block-device FUSE
 * mounts synthesize ownership, mode and label, ignore `chmod`/`chown`/`chcon`, reject the
 * `security.selinux` extended attribute and cannot create hard links. The type is read from the
 * kernel `mountinfo` entry of the already-open FD — never from a path string and never inferred
 * from a failed syscall, because a permission denial says nothing about the filesystem.
 */
internal object RootStorageMountTypes {
    private val EMULATED_STORAGE = setOf(
        // Android emulated storage: the MediaProvider FUSE bridge and its sdcardfs predecessor.
        "fuse",
        "sdcardfs",
        // Block/removable filesystems that have no per-inode POSIX attributes or hard links.
        "vfat",
        "msdos",
        "exfat",
        "texfat",
        "fuseblk",
    )

    fun isEmulatedStorage(fileSystemType: String?): Boolean =
        fileSystemType != null && fileSystemType in EMULATED_STORAGE
}
