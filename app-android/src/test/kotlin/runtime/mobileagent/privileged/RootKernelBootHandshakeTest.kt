// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.skills.tooling.ShellExecRequest

/** Synthetic caller/UID facts validate the response boundary, not an Android kernel observation. */
class RootKernelBootHandshakeTest {
    private val boot = "01234567-89ab-4def-8123-456789abcdef"
    private fun node(inode: Long) = RootFdNodeIdentity(
        1L, inode, 77L, true, false, false, 2L, 448, 10148, 10148, "u:object_r:app_data_file:s0",
    )
    private val boundary = RootControlPlaneBoundary(
        "/data/user/0/runtime.mobileagent", node(9001),
        "/data/user_de/0/runtime.mobileagent", node(9002),
    )
    private val request = RootServiceHandshakeRequest(
        Authority.ROOT_DIRECT, 1L, 10148, 0, "app-instance-boot-0001", "runtime.mobileagent",
        "a".repeat(64), "b".repeat(64), boundary.credentialEncryptedDataDir, boundary.deviceProtectedDataDir,
    )
    private val bootstrap = RootServiceHostBootstrap(
        request.appUid, request.androidUserId, request.packageName, request.signingSha256,
        request.apkSha256, request.ceDataDir, request.deDataDir,
    )

    private fun engine(provider: () -> String?, uid: Int = 0) = RootPrivilegedServiceEngine(
            selectionPathResolver = { java.nio.file.Paths.get(it) },
            mountPolicyProvider = { b -> runtime.mobileagent.domain.RootTrustedMountRoots.directoryTree(
                setOf(b.credentialEncryptedIdentity.mountId, b.deviceProtectedIdentity.mountId)) },

        serviceUidProvider = { uid }, controlPlaneOverride = boundary,
        managerAuthorizationOverride = { RootManagerAuthorization.GRANTED }, kernelBootIdProvider = provider,
        shellRunner = object : RootShellCommandRunner {
            override fun runCommand(authority: Authority, request: ShellExecRequest): RootShellRunOutcome =
                error("A handshake must never dispatch a command")
            override fun cancel(requestId: String) = false
        },
    ).also { it.pinHostBootstrap(bootstrap) }

    private fun handshake(service: RootPrivilegedServiceEngine, value: RootServiceHandshakeRequest = request,
        caller: Int = request.appUid): RootServiceHandshakeResponse = RootPrivilegedServiceEngine.json.decodeFromString(
        service.handshake(RootPrivilegedServiceEngine.json.encodeToString(value), caller),
    )

    @Test
    fun onlyAnAcceptedIdentityVerifiedUid0HandshakeIncludesKernelBootProof() {
        var observations = 0
        engine({ observations++; boot }).use { service ->
            assertNull(handshake(service, request.copy(signingSha256 = "c".repeat(64))).kernelBootId)
            assertNull(handshake(service, caller = 20148).kernelBootId)
            assertEquals(0, observations, "Rejected callers must not reach the boot observer")
            val accepted = handshake(service)
            assertTrue(accepted.accepted)
            assertEquals(0, accepted.identity?.serviceUid)
            assertEquals(boot, accepted.kernelBootId)
            assertEquals(1, observations)
        }
        engine({ error("Non-root service may not observe proof") }, uid = 2000).use { service ->
            val denied = handshake(service)
            assertFalse(denied.accepted)
            assertNull(denied.kernelBootId)
        }
    }

    @Test
    fun missingMalformedOrFailedKernelObservationNeverBecomesProof() {
        for (value in listOf(null, "4", "unknown", boot.uppercase(), "$boot\n", "00000000-0000-0000-0000-00000000000")) {
            engine({ value }).use { service ->
                val accepted = handshake(service)
                assertTrue(accepted.accepted)
                assertNull(accepted.kernelBootId)
            }
        }
        engine({ throw SecurityException("unreadable") }).use { service -> assertNull(handshake(service).kernelBootId) }
    }

    @Test
    fun fixedKernelReaderAcceptsOnlyCanonicalUuidAndAtMostOneLf() {
        for (value in listOf(boot, "$boot\n")) {
            assertEquals(boot, RootKernelBootReader.readBounded(ByteArrayInputStream(value.toByteArray())))
        }
        for (value in listOf("", "3", boot.uppercase(), "$boot\r\n", "$boot\n\n", " $boot", "$boot ")) {
            assertNull(RootKernelBootReader.readBounded(ByteArrayInputStream(value.toByteArray())), value)
        }
        var readBytes = 0
        val endless = object : InputStream() {
            override fun read(): Int { readBytes++; return 'a'.code }
        }
        assertNull(RootKernelBootReader.readBounded(endless))
        assertEquals(38, readBytes, "Hostile streams must be bounded before parsing")
    }

    @Test
    fun oldHandshakeDtoHasNoImplicitBootProof() {
        val response = RootPrivilegedServiceEngine.json.decodeFromString<RootServiceHandshakeResponse>("""{"accepted":false}""")
        assertNull(response.kernelBootId)
    }
}
