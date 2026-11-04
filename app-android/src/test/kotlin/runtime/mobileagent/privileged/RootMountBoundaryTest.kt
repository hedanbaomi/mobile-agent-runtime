// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootMountBoundaryTest {
    private fun node(inode: Long, mount: Long = 20, uid: Int = 0, mode: Int = 493, directory: Boolean = true) =
        RootFdNodeIdentity(1, inode, mount, directory, !directory, false, if (directory) 2 else 1, mode, uid, uid, "")
    private val boundary = RootControlPlaneBoundary(
        "/data/user/0/runtime.mobileagent", node(100),
        "/data/user_de/0/runtime.mobileagent", node(101),
    )
    private val base = "20 1 253:0 / /data rw - ext4 /dev/data rw\n"

    @Test fun ordinaryMountsRemainAvailableButDeepAndFilePrivateAliasesDoNot() {
        val policy = requireNotNull(RootMountBoundary.parse(base + """
            21 20 253:0 /user/0/another.app /work/other rw - ext4 /dev/data rw
            22 20 253:0 /user/0/runtime.mobileagent/files/deep /work/alias rw - ext4 /dev/data rw
            23 20 253:0 /user/0/runtime.mobileagent/databases/main.db /work/file rw - ext4 /dev/data rw
            24 20 253:0 /user_de/0/runtime.mobileagent/files /work/de rw - ext4 /dev/data rw
            25 20 0:42 / /storage rw - fuse /dev/fuse rw
            26 20 253:0 / /work/data rw - ext4 /dev/data rw
            27 20 253:0 /user /work/user rw - ext4 /dev/data rw
            28 20 253:0 /user/0/another.app/files /work/files rw - ext4 /dev/data rw
        """.trimIndent(), boundary))
        assertTrue(policy.mountIds.containsAll(setOf(20L, 21L, 25L, 28L)))
        assertTrue(policy.mountIds.intersect(setOf(22L, 23L, 24L, 26L, 27L)).isEmpty())
    }

    @Test fun kernelEscapedMountRootIsComparedAfterUnescaping() {
        val b = RootControlPlaneBoundary("/data/host space", node(100), "/data/de", node(101))
        val policy = requireNotNull(RootMountBoundary.parse(base +
            "21 20 253:0 /host\\040space/file /work/file rw - ext4 /dev/data rw\n", b))
        assertFalse(21L in policy.mountIds)
    }

    @Test fun androidUserZeroAliasUsesTheRealFilesystemSource() {
        val policy = requireNotNull(RootMountBoundary.parse(base +
            "21 20 253:0 /data/runtime.mobileagent/files/private /work/alias rw - ext4 /dev/data rw\n",
            boundary,
        ) { it.replace("/data/user/0/", "/data/data/") })
        assertFalse(21L in policy.mountIds)
        assertTrue(20L in policy.mountIds)
    }

    @Test fun missingOrMalformedKernelBoundaryIsNotAnEmptyAllowlist() {
        assertNull(RootMountBoundary.parse("", boundary))
        assertNull(RootMountBoundary.parse("invalid", boundary))
        assertNull(RootMountBoundary.parse(base.replace("20 1", "30 1"), boundary))
    }

    @Test fun androidUserZeroBindKeepsDataMountWhileProtectingBothHostSpellings() {
        val b = RootControlPlaneBoundary("/data/user/0/runtime.mobileagent", node(100, mount = 214),
            "/data/user_de/0/runtime.mobileagent", node(101, mount = 20))
        val policy = requireNotNull(RootMountBoundary.parse(base + """
            214 20 253:0 /data /data/user/0 rw - ext4 /dev/data rw
            215 20 253:0 /data /data_mirror/data_ce/null/0 rw - ext4 /dev/data rw
            216 20 253:0 / /work/data rw - ext4 /dev/data rw
        """.trimIndent(), b))
        assertTrue(policy.mountIds.containsAll(setOf(20L, 214L)), "Normal /data and the protected user-0 spelling stay usable")
        assertFalse(215L in policy.mountIds)
        assertFalse(216L in policy.mountIds)
        for (path in listOf("/data/data/runtime.mobileagent", "/data/user/0/runtime.mobileagent")) {
            assertEquals(RootConfinementRejection.CONTROL_PLANE_PROTECTED,
                RootFdConfinementPolicy.verifyWorkspaceRoot("$path/files/deep", RootWorkspaceRootKind.DIRECTORY,
                    node(999, mount = 20), b))
        }
        assertNull(RootFdConfinementPolicy.verifyWorkspaceRoot("/data/adb/safe", RootWorkspaceRootKind.DIRECTORY,
            node(999, mount = 20), b))
    }

    @Test fun rootOwnedDirectoryDoesNotMakeOtherWritableFileExclusive() {
        val chain = listOf(node(1))
        val rootFile = node(2, directory = false, mode = 420)
        assertTrue(RootWriteDomainPolicy.assess(chain, target = rootFile).isUid0Exclusive)
        assertFalse(RootWriteDomainPolicy.assess(chain, target = rootFile.copy(ownerUid = 10123)).isUid0Exclusive)
        assertFalse(RootWriteDomainPolicy.assess(chain, target = rootFile.copy(modeOctal = 438)).isUid0Exclusive)
        assertTrue(RootWriteDomainPolicy.assess(chain, target = rootFile.copy(ownerUid = 10123)).isProvenForMutation)
    }
}
