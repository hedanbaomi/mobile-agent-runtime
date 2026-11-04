// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootServiceLifecycleCoordinator
import runtime.mobileagent.domain.RootServiceSession
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.skills.tooling.ShellExecutionStatus

class RootServiceBootstrapAndLifecycleTest {
    private val packageName = "runtime.mobileagent"
    private val signing = "a".repeat(64)
    private val apkDigest = "d".repeat(64)
    private val apkPath = "/data/app/~~abc/runtime.mobileagent-1/base.apk"
    private val cePath = "/data/user/0/$packageName"
    private val dePath = "/data/user_de/0/$packageName"

    private fun node(uid: Int = 10148) = RootFdNodeIdentity(
        1L, 9001L, 77L, true, false, false, 2L, 448, uid, uid, "u:object_r:app_data_file:s0",
    )

    private class FakeFacts(
        var packages: List<String> = listOf("runtime.mobileagent"),
        var pkg: RootPackageIdentity? = null,
        var loaded: String? = null,
        var apkSha: String? = null,
        var ceNode: RootFdNodeIdentity? = null,
        var deNode: RootFdNodeIdentity? = null,
    ) : RootOsIdentityFacts {
        override fun packagesForUid(uid: Int): List<String> = packages

        override fun packageIdentity(packageName: String, androidUserId: Int): RootPackageIdentity? = pkg

        override fun loadedCodePath(): String? = loaded

        override fun dataDirIdentity(absolutePath: String): RootFdNodeIdentity? =
            if (absolutePath.startsWith("/data/user_de/")) deNode else ceNode

        override fun fileSha256(absolutePath: String): String? = apkSha
    }

    private fun facts(): FakeFacts = FakeFacts(
        pkg = RootPackageIdentity(packageName, 10148, 0, signing, apkPath, cePath, dePath),
        loaded = apkPath,
        apkSha = apkDigest,
        ceNode = node(),
        deNode = node(),
    )

    private fun launch() = RootServiceLaunchIdentity(10148, 0, packageName, 3L)

    @Test
    fun osObservedBootstrapPinsHostIdentity() {
        val pinned = RootServiceBootstrap.observe(launch(), facts())
        assertTrue(pinned is RootBootstrapObservation.Pinned, pinned.toString())
        val bootstrap = (pinned as RootBootstrapObservation.Pinned).bootstrap
        assertEquals(10148, bootstrap.expectedAppUid)
        assertEquals(packageName, bootstrap.expectedPackageName)
        assertEquals(signing, bootstrap.expectedSigningSha256)
        assertEquals(apkDigest, bootstrap.expectedApkSha256)
        assertEquals(cePath, bootstrap.expectedCeDataDir)
    }

    @Test
    fun bootstrapRejectsCallerDeclaredOrInconsistentIdentity() {
        fun reason(mutate: FakeFacts.() -> Unit, value: RootServiceLaunchIdentity = launch()): String {
            val facts = facts().also(mutate)
            val observed = RootServiceBootstrap.observe(value, facts)
            assertTrue(observed is RootBootstrapObservation.Rejected, observed.toString())
            return (observed as RootBootstrapObservation.Rejected).reason
        }
        assertEquals("shared_uid_ambiguous", reason({ packages = listOf(packageName, "other.pkg") }))
        assertEquals("package_uid_mismatch", reason({ pkg = pkg?.copy(uid = 10149) }))
        assertEquals("signing_invalid", reason({ pkg = pkg?.copy(signingSha256 = "zz") }))
        assertEquals("loaded_apk_mismatch", reason({ loaded = "/data/app/~~evil/base.apk" }))
        assertEquals("loaded_apk_unknown", reason({ loaded = null }))
        assertEquals("apk_digest_unavailable", reason({ apkSha = null }))
        assertEquals("ce_data_dir_invalid", reason({ pkg = pkg?.copy(ceDataDir = "/data/user/0/other.pkg") }))
        assertEquals("de_data_dir_invalid", reason({ pkg = pkg?.copy(deDataDir = "/data/user/0/$packageName") }))
        assertEquals("android_user_mismatch", reason({}, launch().copy(androidUserId = 10)))
        assertEquals("app_uid_invalid", reason({}, launch().copy(appUid = 0)))
        assertTrue(RootServiceLaunchIdentity.parse(listOf("10148", "0", packageName)) == null)
        assertTrue(RootServiceLaunchIdentity.parse(listOf("10148", "0", "not a package", "1")) == null)
    }

    @Test
    fun fixedHostBootstrapComesFromOsNotFromFirstCaller() {
        val pinned = RootServiceBootstrap.observeFixedHost(packageName, 0, facts())
        assertTrue(pinned is RootBootstrapObservation.Pinned)
        assertEquals(
            10148,
            (pinned as RootBootstrapObservation.Pinned).bootstrap.expectedAppUid,
        )
        // A Binder caller in another user/profile never resolves back to the fixed host.
        val otherUser = facts().also { it.pkg = it.pkg?.copy(uid = 1010148, androidUserId = 10) }
        assertTrue(
            RootServiceBootstrap.observeFixedHost(packageName, 0, otherUser) is RootBootstrapObservation.Rejected,
        )
        assertTrue(RootServiceBootstrap.observeFixedHost("", 0, facts()) is RootBootstrapObservation.Rejected)
    }

    @Test
    fun procStatParsingKeepsKernelFieldsAtomic() {
        val raw = "1234 (sh -c echo hi) S 1 1234 1234 0 -1 4194560 100 0 0 0 5 5 0 0 20 0 1 0 987654 0 1"
        val stat = parseProcStat(1234, raw)
        assertNotNull(stat)
        assertEquals('S', stat!!.state)
        assertEquals(1, stat.ppid)
        assertEquals(1234, stat.pgrp)
        assertEquals(987654L, stat.startTime)
        assertFalse(stat.isDead)
    }

    @Test
    fun leakedOrUnverifiedDescendantsNeverReportSuccess() {
        val leaked = classifyRootShellOutcome(
            RootProcessGroupEvidence(
                leaderExited = true, exitCode = 0, timedOut = false, cancelled = false,
                leakedDescendants = true, settled = true,
            ),
        )
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, leaked.status)
        assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, leaked.terminalOutcome)
        assertFalse(leaked.requiresDegradedEpoch, "observed descendants were ultimately proven drained")

        val unconfirmed = classifyRootShellOutcome(
            RootProcessGroupEvidence(
                leaderExited = true, exitCode = 0, timedOut = false, cancelled = false,
                leakedDescendants = false, settled = false,
            ),
        )
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, unconfirmed.status)
        assertTrue(unconfirmed.requiresDegradedEpoch)

        val clean = classifyRootShellOutcome(
            RootProcessGroupEvidence(
                leaderExited = true, exitCode = 0, timedOut = false, cancelled = false,
                leakedDescendants = false, settled = true,
            ),
        )
        assertEquals(ShellExecutionStatus.SUCCEEDED, clean.status)
        assertFalse(clean.requiresDegradedEpoch)

        val timedOut = classifyRootShellOutcome(
            RootProcessGroupEvidence(
                leaderExited = false, exitCode = null, timedOut = true, cancelled = false,
                leakedDescendants = true, settled = false,
            ),
        )
        assertEquals(ShellExecutionStatus.TIMED_OUT, timedOut.status)
        assertTrue(timedOut.requiresDegradedEpoch)
    }

    @Test
    fun leaderExitWithSurvivingGroupMemberIsUnknownAndDegraded() {
        val survivor = RootProcStat(999_001, 'S', 1, 4242, 4242, 99L)
        val signals = object : RootProcessSignals {
            override fun stat(pid: Int): RootProcStat = RootProcStat(pid, 'S', 1, 4242, 4242, 5L)

            override fun oomScoreAdj(pid: Int): Int? = 900

            override fun groupMembers(pgrp: Int): List<RootProcStat> = listOf(survivor)

            override fun childrenOf(pid: Int): List<Int> = emptyList()

            override fun selfChildren(): Set<Int> = emptySet()
        }
        val runner = RootShellGroupRunner(
            signals = signals,
            killGroup = {},
            killPid = {},
            settleBudgetMs = 50L,
            childPidResolver = { 4242 },
        )
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd", "/c", "exit 0")
        } else {
            listOf("sh", "-c", "exit 0")
        }
        val outcome = runner.execute(ProcessBuilder(command), shellRequest())
        // Leader exited 0, but a group member survived and could not be confirmed dead:
        // the call must never be reported as success.
        assertEquals(ShellExecutionStatus.UNKNOWN_OUTCOME, outcome.status)
        assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, outcome.terminalOutcome)
        assertTrue(outcome.requiresDegradedEpoch)
    }

    private fun shellRequest() = runtime.mobileagent.skills.tooling.ShellExecRequest.fromRuntime(
        callId = "call-escape-window",
        command = "exit 0",
        cwd = null,
        limits = runtime.mobileagent.skills.tooling.ShellLimits(2_000L, 1024L),
        agentId = "agent",
        snapshotId = "snap",
        selectedAuthority = Authority.ROOT_DIRECT,
        dangerousMode = runtime.mobileagent.domain.DangerousMode.ENABLED_CONFIRM_HIGH_RISK,
        policyVersion = 1L,
        configSnapshotHash = "hash",
        sessionIdentity = "ses",
        toolSchemaVersion = 1,
    )

    private class FakeSignals : RootProcessSignals {
        val stats = mutableMapOf<Int, RootProcStat>()
        var oom = 900
        var pgrpMembers = mutableListOf<RootProcStat>()

        override fun stat(pid: Int): RootProcStat? = stats[pid]

        override fun oomScoreAdj(pid: Int): Int? = oom

        override fun groupMembers(pgrp: Int): List<RootProcStat> = pgrpMembers.filter { it.pgrp == pgrp }

        override fun childrenOf(pid: Int): List<Int> = emptyList()

        override fun selfChildren(): Set<Int> = emptySet()
    }

    private fun controller(pid: Int = 4242) = RootProcStat(pid, 'S', 1, 4242, 4242, 777L)

    @Test
    fun watchdogStopsServiceWithoutControllerAndReportsDeathOnce() {
        val clock = AtomicInteger(0)
        val signals = FakeSignals()
        val timeouts = AtomicInteger()
        val losses = AtomicInteger()
        val config = RootServiceLifecycleConfig(signals = signals, clock = { clock.get().toLong() })
        val preHandshake = RootServiceLifecycleWatchdog(
            config = config, startedAtMs = 0L, controllerPid = -1, controllerStartTime = -1L,
            lifecycle = null, onControllerLost = { losses.incrementAndGet() },
            onHandshakeTimeout = { timeouts.incrementAndGet() },
        )
        preHandshake.tick(29_999L)
        assertEquals(0, timeouts.get())
        preHandshake.tick(30_000L)
        assertEquals(1, timeouts.get())
        preHandshake.tick(60_000L)
        assertEquals(1, timeouts.get())

        signals.stats[4242] = controller()
        val armed = RootServiceLifecycleWatchdog(
            config = config, startedAtMs = 0L, controllerPid = 4242, controllerStartTime = 777L,
            lifecycle = null, onControllerLost = { losses.incrementAndGet() },
            onHandshakeTimeout = { timeouts.incrementAndGet() },
        )
        armed.tick(1_000L)
        assertEquals(0, losses.get())
        signals.stats.remove(4242)
        armed.tick(2_000L)
        armed.tick(3_000L)
        assertEquals(1, losses.get())
    }

    @Test
    fun cachedHostStopsAfterThirtySecondsButVisibleHostDoesNot() {
        val identity = PrivilegedSessionIdentity(
            authority = Authority.SHIZUKU_ROOT, privilegeTier = PrivilegeTier.ROOT, serviceUid = 0,
            appUid = 10148, androidUserId = 0, appInstanceId = "app-instance-0001",
            packageName = packageName, signingSha256 = signing, identityEpoch = 1L,
            serviceInstanceId = "root-svc-000000000001", sessionId = "root-ses-000000000001",
            protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
        )
        val stopReasons = mutableListOf<String>()
        val session = RootServiceSession(identity = identity)
        val lifecycle = RootServiceLifecycleCoordinator(
            session = session,
            onStopService = { reason -> stopReasons += reason },
        )
        val signals = FakeSignals().apply { stats[4242] = controller() }
        val config = RootServiceLifecycleConfig(signals = signals, clock = { 0L })
        val watchdog = RootServiceLifecycleWatchdog(
            config = config, startedAtMs = 0L, controllerPid = 4242, controllerStartTime = 777L,
            lifecycle = lifecycle, onControllerLost = {}, onHandshakeTimeout = {},
        )
        watchdog.tick(0L)
        watchdog.tick(29_999L)
        assertTrue(stopReasons.isEmpty())
        watchdog.tick(30_000L)
        assertEquals(listOf("background_idle_30s"), stopReasons)

        val visibleReasons = mutableListOf<String>()
        val visibleSession = RootServiceSession(identity = identity)
        val visibleLifecycle = RootServiceLifecycleCoordinator(
            session = visibleSession,
            onStopService = { reason -> visibleReasons += reason },
        )
        signals.oom = 0
        val visible = RootServiceLifecycleWatchdog(
            config = config, startedAtMs = 0L, controllerPid = 4242, controllerStartTime = 777L,
            lifecycle = visibleLifecycle, onControllerLost = {}, onHandshakeTimeout = {},
        )
        visible.tick(0L)
        visible.tick(120_000L)
        assertTrue(visibleReasons.isEmpty())
    }
}
