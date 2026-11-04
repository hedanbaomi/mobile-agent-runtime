// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity

/** Manager path resolution and observation fallbacks; no device or real Magisk dependency. */
class RootManagerAuthorizationReaderTest {
    @Test
    fun pathLookupFindsMagiskInARandomDevDirectoryAfterTheFixedLocations() {
        val candidates = RootManagerAuthorizationReader.managerBinaryCandidates(
            fixedPaths = listOf("/sbin/magisk"),
            pathValue = "/dev/8f3a:/system/bin:/dev/8f3a/",
            suCandidates = emptyList(),
        )
        assertEquals(
            listOf("/sbin/magisk", "/dev/8f3a/magisk", "/system/bin/magisk"),
            candidates,
            "The fixed locations stay first; absolute PATH entries are searched without duplicates",
        )
    }

    @Test
    fun pathLookupIgnoresRelativeAndEmptyEntries() {
        val candidates = RootManagerAuthorizationReader.managerBinaryCandidates(
            fixedPaths = listOf("/sbin/magisk"),
            pathValue = "relative/bin::/dev/ok",
            suCandidates = emptyList(),
        )
        assertEquals(listOf("/sbin/magisk", "/dev/ok/magisk"), candidates)
    }

    @Test
    fun resolverReturnsTheFirstExecutableCandidate() {
        val tried = mutableListOf<String>()
        val resolved = RootManagerAuthorizationReader.resolveManagerBinary(
            candidates = listOf("/sbin/magisk", "/dev/random/magisk", "/system/bin/magisk"),
        ) { path ->
            tried += path
            path == "/dev/random/magisk"
        }
        assertEquals("/dev/random/magisk", resolved)
        assertEquals(listOf("/sbin/magisk", "/dev/random/magisk"), tried)
    }

    @Test
    fun canonicalSuSymlinkRevealsMagiskRandomDirectoryWithoutScanningDev() {
        val candidates = RootManagerAuthorizationReader.managerBinaryCandidates(
            fixedPaths = listOf("/sbin/magisk"),
            pathValue = "/system/bin",
            suCandidates = listOf("/system/bin/su"),
        ) { path ->
            if (path == "/system/bin/su") "/dev/8f3a/su" else null
        }
        assertEquals(
            listOf("/sbin/magisk", "/system/bin/magisk", "/dev/8f3a/magisk"),
            candidates,
        )
    }

    @Test
    fun losingTheSelectedObserverCannotKeepTheSessionAuthorized() {
        val (authorization, reason) = RootManagerAuthorizationReader.observe(
            appUid = 10_123,
            androidUserId = 0,
            managerBinary = { null },
        )
        assertEquals(RootManagerAuthorization.UNSUPPORTED, authorization)
        assertFalse(authorization.permitsSession, "A lost selected observer must fail closed")
        assertEquals("manager_not_found", reason)
    }

    @Test
    fun unreadableOrUnsupportedSelectedObserverAlsoFailsClosed() {
        fun observe(version: Int?, headless: Boolean?) = RootManagerAuthorizationReader.observe(
            appUid = 10_123,
            androidUserId = 0,
            managerBinary = { "/fake/magisk" },
            versionOf = { _: String -> version },
            headless = { headless },
        )

        assertEquals(RootManagerAuthorization.UNSUPPORTED, observe(null, false).first)
        assertEquals("manager_version_unknown", observe(null, false).second)
        assertEquals(RootManagerAuthorization.UNSUPPORTED, observe(25, false).first)
        assertEquals("manager_version_unsupported:25", observe(25, false).second)
        assertEquals(RootManagerAuthorization.UNSUPPORTED, observe(26, true).first)
        assertEquals("headless_system_user_unsupported", observe(26, true).second)
        assertEquals(RootManagerAuthorization.UNKNOWN, observe(26, null).first)
    }

    @Test
    fun genericSessionWithoutAnObserverStillUsesSessionGranted() {
        val identity = identity()
        val raw = RootManagerAuthorizationReader.authorizationJson(
            sessionIdentity = identity,
            observedCallingUid = identity.appUid,
            liveServiceUid = 0,
            requestJson = RootPrivilegedServiceEngine.json.encodeToString(
                RootManagerAuthorizationRequest.serializer(),
                RootManagerAuthorizationRequest(
                    serviceInstanceId = identity.serviceInstanceId,
                    sessionId = identity.sessionId,
                    identityEpoch = identity.identityEpoch,
                ),
            ),
            override = null,
            observationEnabled = false,
        )
        val response = RootPrivilegedServiceEngine.json.decodeFromString<RootManagerAuthorizationResponse>(raw)
        assertEquals(RootManagerAuthorization.SESSION_GRANTED.name, response.result)
        assertEquals("manager_observation_unavailable", response.reason)
    }

    @Test
    fun supportedObserverExplicitDenialStillRejectsTheSession() {
        for (observationEnabled in listOf(true, false)) {
        val identity = identity()
        val request = RootManagerAuthorizationRequest(
            serviceInstanceId = identity.serviceInstanceId,
            sessionId = identity.sessionId,
            identityEpoch = identity.identityEpoch,
        )
        val raw = RootManagerAuthorizationReader.authorizationJson(
            sessionIdentity = identity,
            observedCallingUid = identity.appUid,
            liveServiceUid = 0,
            requestJson = RootPrivilegedServiceEngine.json.encodeToString(
                RootManagerAuthorizationRequest.serializer(),
                request,
            ),
            override = { RootManagerAuthorization.REVOKED },
            observationEnabled = observationEnabled,
        )
        val response = RootPrivilegedServiceEngine.json.decodeFromString<RootManagerAuthorizationResponse>(raw)
        assertEquals(RootManagerAuthorization.REVOKED.name, response.result)
        assertEquals("override", response.reason)
        assertEquals(identity.serviceInstanceId, response.serviceInstanceId)
        }
    }

    private fun identity() = PrivilegedSessionIdentity(
        authority = Authority.ROOT_DIRECT,
        privilegeTier = PrivilegeTier.ROOT,
        serviceUid = 0,
        appUid = 10_123,
        androidUserId = 0,
        appInstanceId = "app-instance-000001",
        packageName = "runtime.mobileagent",
        signingSha256 = "a".repeat(64),
        identityEpoch = 1L,
        serviceInstanceId = "root-service-000001",
        sessionId = "root-session-000001",
        protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
    )
}
