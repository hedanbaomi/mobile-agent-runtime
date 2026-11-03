// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.RootWorkspaceOrigin

class WorkspaceBindingAadTest {
    private fun origin() = RootWorkspaceOrigin(
        "workspace-root",Authority.ROOT_DIRECT,"app-instance-000001","runtime.mobileagent",
        10123,0,"a".repeat(64),"workspace-generation-0001",
    )
    private fun encoded(value: RootWorkspaceBindingAad) = WorkspaceBindingAadCodec.encode(value)

    @Test fun legacyShellAadRetainsItsExactHistoricalByteEncoding() {
        val bytes = WorkspaceBindingAadCodec.encode(PrivilegedWorkspaceBindingAad("app","workspace",Authority.SHIZUKU,1))
        assertEquals("4d41522d50524956494c454745442d574f524b53504143452d42494e44494e472d414144000000010000000361707000000009776f726b7370616365000000075348495a554b5500000001",bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    @Test fun everyRootIdentityDimensionAndVersionChangesAuthenticatedBytes() {
        val original = RootWorkspaceBindingAad(origin())
        val baseline = encoded(original)
        val changes = listOf(
            original.copy(origin=origin().copy(authority=Authority.SHIZUKU_ROOT)),
            original.copy(origin=origin().copy(appInstanceId="another-app-instance1")),
            original.copy(origin=origin().copy(workspaceId="another-workspace")),
            original.copy(origin=origin().copy(generationId="another-generation-001")),
            original.copy(origin=origin().copy(packageName="another.application")),
            original.copy(origin=origin().copy(signingSha256="b".repeat(64))),
            original.copy(origin=origin().copy(appUid=10124)),
            original.copy(origin=origin().copy(appUid=110123,androidUserId=1)),
            original.copy(locatorVersion=2),
            original.copy(keyVersion=2),
        )
        changes.forEach { assertFalse(baseline.contentEquals(encoded(it))) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(aadLocatorVersion=1) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(origin=origin().copy(authority=Authority.SHIZUKU)) }
    }

    @Test fun rootAndShellRemainSeparateAndDescriptionsOmitInstallationIdentity() {
        val root = RootWorkspaceBindingAad(origin())
        val shell = PrivilegedWorkspaceBindingAad(origin().appInstanceId,origin().workspaceId,Authority.SHIZUKU,1)
        assertFalse(encoded(root).contentEquals(WorkspaceBindingAadCodec.encode(shell)))
        assertFalse(root.toString().contains(origin().appInstanceId))
        assertFalse(root.toString().contains(origin().signingSha256))
        assertFalse(root.toString().contains(origin().workspaceId))
    }
}
