// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShizukuBridgePolicyTest {
    @Test
    fun dispatchRequiresLiveBinderPermissionTrustedUidVersionAndUserService() {
        val allowed = status(
            binderAlive = true,
            permissionGranted = true,
            serverUid = ShizukuBridgePolicy.SHELL_UID,
            serverVersion = ShizukuBridgePolicy.MIN_SERVER_VERSION,
            userServiceAlive = true,
        )
        assertEquals(ShizukuGateDecision.Allowed, ShizukuBridgePolicy.evaluateDispatch(allowed))

        val missingBinder = ShizukuBridgePolicy.evaluateDispatch(allowed.copy(binderAlive = false))
        assertTrue(missingBinder is ShizukuGateDecision.Denied)

        val missingPermission = ShizukuBridgePolicy.evaluateDispatch(allowed.copy(permissionGranted = false))
        assertTrue(missingPermission is ShizukuGateDecision.Denied)

        val untrustedUid = ShizukuBridgePolicy.evaluateDispatch(allowed.copy(serverUid = 1000))
        assertTrue(untrustedUid is ShizukuGateDecision.Denied)

        val oldServer = ShizukuBridgePolicy.evaluateDispatch(
            allowed.copy(serverVersion = ShizukuBridgePolicy.MIN_SERVER_VERSION - 1),
        )
        assertTrue(oldServer is ShizukuGateDecision.Denied)

        val missingUserService = ShizukuBridgePolicy.evaluateDispatch(allowed.copy(userServiceAlive = false))
        assertTrue(missingUserService is ShizukuGateDecision.Denied)
    }

    @Test
    fun onlyShellIsTrustedServerUidInV2() {
        assertFalse(
            ShizukuBridgePolicy.evaluateServer(
                status(serverUid = ShizukuBridgePolicy.ROOT_UID),
            ) is ShizukuGateDecision.Allowed,
        )
        assertTrue(
            ShizukuBridgePolicy.evaluateServer(
                status(serverUid = ShizukuBridgePolicy.SHELL_UID),
            ) is ShizukuGateDecision.Allowed,
        )
        assertFalse(
            status(serverUid = null).trustedServerUid,
        )
        assertTrue(
            ShizukuBridgePolicy.evaluateServer(status(serverUid = null)) is ShizukuGateDecision.Denied,
        )
    }

    @Test
    fun dispatchRequiresShellUserServiceHandshakeAndRejectsRootOrUnknown() {
        val allowed = status(
            serverUid = ShizukuBridgePolicy.SHELL_UID,
            userServiceAlive = true,
            userServiceUid = ShizukuBridgePolicy.SHELL_UID,
            userServiceProtocolVersion = ShizukuBridgePolicy.USER_SERVICE_PROTOCOL_VERSION,
            userServiceSessionId = "session",
            userServiceCallerUid = android.os.Process.myUid(),
        )
        assertEquals(ShizukuGateDecision.Allowed, ShizukuBridgePolicy.evaluateDispatch(allowed))
        assertTrue(
            ShizukuBridgePolicy.evaluateDispatch(
                allowed.copy(userServiceUid = ShizukuBridgePolicy.ROOT_UID),
            ) is ShizukuGateDecision.Denied,
        )
        assertTrue(
            ShizukuBridgePolicy.evaluateDispatch(allowed.copy(userServiceUid = null)) is ShizukuGateDecision.Denied,
        )
        assertTrue(
            ShizukuBridgePolicy.evaluateDispatch(
                allowed.copy(userServiceProtocolVersion = ShizukuBridgePolicy.USER_SERVICE_PROTOCOL_VERSION - 1),
            ) is ShizukuGateDecision.Denied,
        )
        assertTrue(
            ShizukuBridgePolicy.evaluateDispatch(
                allowed.copy(userServiceCallerUid = android.os.Process.myUid() + 1),
            ) is ShizukuGateDecision.Denied,
        )
    }

    @Test
    fun relativePathPolicyRejectsEscapesAndAcceptsOnlyWorkspaceRelativePaths() {
        assertTrue(ShizukuWorkspacePathPolicy.isValid("notes/today.txt", allowRoot = false))
        assertTrue(ShizukuWorkspacePathPolicy.isValid("", allowRoot = true))
        listOf(
            "/absolute.txt",
            "../escape.txt",
            "a/../escape.txt",
            "C:/escape.txt",
            "a\\escape.txt",
            "a\u0000b",
            "a//b",
            "a/./b",
        ).forEach { path ->
            assertFalse("Expected rejection for $path", ShizukuWorkspacePathPolicy.isValid(path, allowRoot = false))
        }
        assertFalse(ShizukuWorkspacePathPolicy.isValid("", allowRoot = false))
    }

    @Test
    fun requestAndReadBoundsAreFinite() {
        assertTrue(ShizukuBridgePolicy.validPermissionRequestCode(1))
        assertFalse(ShizukuBridgePolicy.validPermissionRequestCode(0))
        assertFalse(ShizukuBridgePolicy.validPermissionRequestCode(-1))
        assertTrue(ShizukuBridgePolicy.validReadLimit(1))
        assertTrue(ShizukuBridgePolicy.validReadLimit(ShizukuWorkspaceFileStore.MAX_READ_BYTES))
        assertFalse(ShizukuBridgePolicy.validReadLimit(0))
        assertFalse(ShizukuBridgePolicy.validReadLimit(ShizukuWorkspaceFileStore.MAX_READ_BYTES + 1))
    }

    @Test
    fun shellSerializationBudgetStaysBelowOneBinderMiB() {
        assertTrue(ShizukuShellLimits.MAX_SERIALIZED_OUTPUT_BYTES < 1024 * 1024)
        assertEquals(960 * 1024, ShizukuShellLimits.MAX_SERIALIZED_OUTPUT_BYTES)
    }

    @Test
    fun cwdSyntaxIsAcceptedBeforeElevatedBoundaryChecksExistence() {
        // The app UID is not authoritative for an elevated cwd.  It only
        // validates the absolute path syntax; the UserService/shell runner
        // performs the existence and directory check after dispatch.
        assertTrue(isValidShizukuShellCwdSyntax("/data/local/tmp/shizuku-elevated-only"))
        assertFalse(isValidShizukuShellCwdSyntax("relative/path"))
        assertFalse(isValidShizukuShellCwdSyntax("/data/local/tmp/a\\b"))
        assertFalse(isValidShizukuShellCwdSyntax("/data/local/tmp/a\u0000b"))
    }

    private fun status(
        binderAlive: Boolean = true,
        permissionGranted: Boolean = true,
        serverUid: Int? = ShizukuBridgePolicy.SHELL_UID,
        serverVersion: Int? = ShizukuBridgePolicy.MIN_SERVER_VERSION,
        userServiceAlive: Boolean = true,
        userServiceUid: Int? = ShizukuBridgePolicy.SHELL_UID,
        userServiceProtocolVersion: Int? = ShizukuBridgePolicy.USER_SERVICE_PROTOCOL_VERSION,
        userServiceSessionId: String? = "test-session",
        userServiceCallerUid: Int? = android.os.Process.myUid(),
    ) = ShizukuBridgeStatus(
        binderAlive = binderAlive,
        permissionGranted = permissionGranted,
        serverUid = serverUid,
        serverVersion = serverVersion,
        userServiceAlive = userServiceAlive,
        preV11 = false,
        reason = "",
        userServiceUid = userServiceUid,
        userServiceProtocolVersion = userServiceProtocolVersion,
        userServiceSessionId = userServiceSessionId,
        userServiceCallerUid = userServiceCallerUid,
    )
}
