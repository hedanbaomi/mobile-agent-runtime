// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.net.Uri
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode

/**
 * Real-thread regressions for the extracted Settings authority actions. Two named single-thread
 * executors stand in for IO and Main; no coroutines-test dependency is used.
 */
class SettingsAuthorityActionsTest {
    @Test
    fun blockingHandshakeRunsOnIoWhileSnapshotAndFactsCallbacksRunOnMain() {
        val port = FakePort().apply { blockSelect = true }
        runActions(port) { actions, recorder, onMain ->
            onMain { actions.selectAuthority(Authority.ROOT_DIRECT) }

            assertTrue(port.selectEntered.await(5, TimeUnit.SECONDS), "handshake did not start")
            assertEquals(IO_THREAD, port.selectThread.get())
            assertNull(recorder.snapshotThread.get(), "snapshot must not be published before the handshake returns")

            port.selectRelease.countDown()
            awaitUntil("snapshot and facts callbacks") {
                recorder.snapshots.get() == 1 && recorder.facts.get() == 1
            }
            assertEquals(MAIN_THREAD, recorder.snapshotThread.get())
            assertEquals(MAIN_THREAD, recorder.factsThread.get())
        }
    }

    @Test
    fun firstRootEnableStepRequestsPermissionOnMainAndDoesNotSelectOnIo() {
        val port = FakePort().apply { rootReady = false }
        runActions(port) { actions, recorder, onMain ->
            onMain { actions.enableShizukuRoot() }

            awaitUntil("permission request and snapshot") {
                port.requestRootCalls.get() == 1 && recorder.snapshots.get() == 1
            }
            assertEquals(IO_THREAD, port.platformReadyThread.get())
            assertEquals(MAIN_THREAD, port.requestRootThread.get())
            assertEquals(0, port.selectCalls.get())
            assertEquals(0, port.intentCalls.get())
        }
    }

    @Test
    fun readyRootEnableRunsSelectAndIntentOnIoAndNeverRequestsPermission() {
        val port = FakePort().apply { rootReady = true }
        runActions(port) { actions, recorder, onMain ->
            onMain { actions.enableShizukuRoot() }

            awaitUntil("select, intent and final snapshot") {
                port.selectCalls.get() == 1 && port.intentCalls.get() == 1 && recorder.snapshots.get() == 2
            }
            assertEquals(IO_THREAD, port.selectThread.get())
            assertEquals(IO_THREAD, port.intentThread.get())
            assertEquals(0, port.requestRootCalls.get())
            assertEquals(MAIN_THREAD, recorder.snapshotThread.get())
        }
    }

    @Test
    fun reentrantClickIsIgnoredAndFailureStillReleasesTheGate() {
        val port = FakePort().apply { blockSelect = true }
        runActions(port) { actions, recorder, onMain ->
            onMain { actions.selectAuthority(Authority.ROOT_DIRECT) }
            assertTrue(port.selectEntered.await(5, TimeUnit.SECONDS))
            onMain { actions.selectAuthority(Authority.ROOT_DIRECT) }
            assertEquals(1, port.selectCalls.get(), "a second click must not start a second handshake")

            port.selectRelease.countDown()
            awaitUntil("first action completes") { recorder.snapshots.get() == 1 }

            port.blockSelect = false
            port.failSelect = true
            onMain { actions.selectAuthority(Authority.ROOT_DIRECT) }
            awaitUntil("failure callback") { recorder.errors.get() == 1 }
            assertEquals(MAIN_THREAD, recorder.errorThread.get())
            assertEquals("mapped:boom", recorder.lastError.get())
            assertEquals(1, recorder.facts.get(), "a failed action must not refresh facts")

            port.failSelect = false
            onMain { actions.selectAuthority(Authority.ROOT_DIRECT) }
            awaitUntil("gate released after failure") {
                port.selectCalls.get() == 3 && recorder.snapshots.get() == 2
            }
        }
    }

    @Test
    fun inFlightRefreshAndMutationAreReplayedInsteadOfDropped() {
        val port = FakePort().apply { blockSelect = true }
        runActions(port) { actions, recorder, onMain ->
            onMain { actions.selectAuthority(Authority.ROOT_DIRECT) }
            assertTrue(port.selectEntered.await(5, TimeUnit.SECONDS))

            var mutationThread: String? = null
            onMain { actions.refreshAuthorities() }
            onMain { actions.mutate {
                mutationThread = Thread.currentThread().name.substringBefore(" @coroutine#")
                recorder.mutations.incrementAndGet()
                SettingsAuthoritySnapshot()
            } }
            assertEquals(0, port.refreshCalls.get(), "refresh must wait for the in-flight handshake")
            assertEquals(0, recorder.mutations.get(), "SAF callback must wait for the in-flight handshake")

            port.selectRelease.countDown()
            awaitUntil("queued mutation and refresh") {
                port.refreshCalls.get() == 1 && recorder.mutations.get() == 1
            }
            assertEquals(IO_THREAD, port.refreshThread.get())
            assertEquals(IO_THREAD, mutationThread)
            assertEquals(MAIN_THREAD, recorder.snapshotThread.get())
        }
    }

    @Test
    fun directRootButtonSelectionThenEnableAndLaterModeActionsAreNotDropped() {
        val port = FakePort().apply { blockSelect = true }
        runActions(port) { actions, recorder, onMain ->
            onMain {
                // The actual Direct Root settings button emits these two callbacks in one click.
                actions.selectAuthority(Authority.ROOT_DIRECT)
                actions.setAuthorityIntent(Authority.ROOT_DIRECT, true)
                actions.setDangerousMode(DangerousMode.DISABLED)
                actions.setRootShellConsent(Authority.ROOT_DIRECT, false, false)
            }
            assertTrue(port.selectEntered.await(5, TimeUnit.SECONDS))
            assertEquals(0, port.intentCalls.get())
            assertEquals(0, port.modeCalls.get())
            assertEquals(0, port.shellCalls.get())
            port.selectRelease.countDown()
            awaitUntil("select, enable, mode and shell actions complete") { recorder.facts.get() == 4 }
            assertEquals(listOf(true), port.intentValues.toList())
            assertEquals(1, port.modeCalls.get())
            assertEquals(1, port.shellCalls.get())
            assertEquals(IO_THREAD, port.modeThread.get())
            assertEquals(IO_THREAD, port.shellThread.get())
        }
    }

    @Test
    fun queuedLatestIntentKeepsRevocationInsteadOfEnablingAfterTheUserTurnedItOff() {
        val port = FakePort().apply { blockSelect = true }
        runActions(port) { actions, recorder, onMain ->
            onMain {
                actions.selectAuthority(Authority.ROOT_DIRECT)
                actions.setAuthorityIntent(Authority.ROOT_DIRECT, true)
                actions.setAuthorityIntent(Authority.ROOT_DIRECT, false)
            }
            assertTrue(port.selectEntered.await(5, TimeUnit.SECONDS))
            port.selectRelease.countDown()
            awaitUntil("latest queued intent completes") { recorder.facts.get() == 2 }
            assertEquals(listOf(false), port.intentValues.toList())
            assertEquals(1, port.intentCalls.get())
        }
    }

    @Test
    fun permissionRequestEntryPointRunsOnMainAndPublishesOnMain() {
        val port = FakePort()
        runActions(port) { actions, recorder, onMain ->
            onMain { actions.requestShizukuPermission() }
            awaitUntil("permission snapshot") { recorder.snapshots.get() == 1 }
            assertEquals(MAIN_THREAD, port.requestShizukuThread.get())
            assertEquals(MAIN_THREAD, recorder.snapshotThread.get())
        }
    }

    private fun runActions(
        port: FakePort,
        block: (SettingsAuthorityActions, Recorder, (() -> Unit) -> Unit) -> Unit,
    ) {
        val ioExecutor = newExecutor(IO_THREAD)
        val mainExecutor = newExecutor(MAIN_THREAD)
        val io = ioExecutor.asCoroutineDispatcher()
        val main = mainExecutor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val recorder = Recorder()
        val actions = SettingsAuthorityActions(
            scope = scope,
            port = port,
            onSnapshot = {
                recorder.snapshotThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
                recorder.snapshots.incrementAndGet()
            },
            onError = {
                recorder.errorThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
                recorder.lastError.set(it)
                if (it != null) recorder.errors.incrementAndGet()
            },
            onFactsChanged = {
                recorder.factsThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
                recorder.facts.incrementAndGet()
            },
            errorMapper = { "mapped:${it.message}" },
            reasonMapper = { "reason:$it" },
            ioDispatcher = io,
            mainDispatcher = main,
        )
        try {
            block(actions, recorder) { action ->
                mainExecutor.submit { action() }.get(5, TimeUnit.SECONDS)
            }
        } finally {
            port.releaseAll()
            scope.cancel()
            io.close()
            main.close()
            ioExecutor.shutdownNow()
            mainExecutor.shutdownNow()
        }
    }

    private fun newExecutor(name: String): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("Timed out waiting for $what")
            }
            Thread.sleep(5)
        }
    }

    private class Recorder {
        val snapshotThread = AtomicReference<String>()
        val errorThread = AtomicReference<String>()
        val factsThread = AtomicReference<String>()
        val lastError = AtomicReference<String?>()
        val snapshots = AtomicInteger()
        val errors = AtomicInteger()
        val facts = AtomicInteger()
        val mutations = AtomicInteger()
    }

    private class FakePort : SettingsAuthorityPort {
        @Volatile var blockSelect = false
        @Volatile var failSelect = false
        @Volatile var rootReady = false
        val selectEntered = CountDownLatch(1)
        val selectRelease = CountDownLatch(1)
        val selectCalls = AtomicInteger()
        val intentCalls = AtomicInteger()
        val intentValues = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val modeCalls = AtomicInteger()
        val shellCalls = AtomicInteger()
        val modeThread = AtomicReference<String>()
        val shellThread = AtomicReference<String>()
        val requestRootCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()
        val selectThread = AtomicReference<String>()
        val intentThread = AtomicReference<String>()
        val requestRootThread = AtomicReference<String>()
        val requestShizukuThread = AtomicReference<String>()
        val platformReadyThread = AtomicReference<String>()
        val refreshThread = AtomicReference<String>()

        override fun snapshot(): SettingsAuthoritySnapshot = SettingsAuthoritySnapshot()

        override fun refresh(): SettingsAuthoritySnapshot {
            refreshThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
            refreshCalls.incrementAndGet()
            return snapshot()
        }

        override fun selectAuthority(authority: Authority): SettingsAuthoritySnapshot {
            selectThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
            selectCalls.incrementAndGet()
            if (failSelect) error("boom")
            if (blockSelect) {
                selectEntered.countDown()
                check(selectRelease.await(5, TimeUnit.SECONDS)) { "select release timed out" }
            }
            return snapshot()
        }

        override fun setUserIntent(authority: Authority, enabled: Boolean): SettingsAuthoritySnapshot {
            intentThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
            intentCalls.incrementAndGet()
            intentValues += enabled
            return snapshot()
        }

        override fun requestShizukuPermission(): SettingsAuthoritySnapshot {
            requestShizukuThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
            return snapshot()
        }

        override fun requestRootShizukuPermission(): SettingsAuthoritySnapshot {
            requestRootThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
            requestRootCalls.incrementAndGet()
            return snapshot()
        }

        override fun rootShizukuPlatformReady(): Boolean {
            platformReadyThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
            return rootReady
        }

        override fun openShizuku(): Boolean = false

        override fun authorizeSaf(uri: Uri): SettingsAuthoritySnapshot = snapshot()

        override fun revokeSaf(): SettingsAuthoritySnapshot = snapshot()

        override fun setDangerousMode(mode: DangerousMode, confirmed: Boolean): SettingsAuthorityMutation {
            modeThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
            modeCalls.incrementAndGet()
            return SettingsAuthorityMutation(true, snapshot())
        }

        override fun setRootShellConsent(authority: Authority, enabled: Boolean, confirmed: Boolean): SettingsAuthorityMutation {
            shellThread.set(Thread.currentThread().name.substringBefore(" @coroutine#"))
            shellCalls.incrementAndGet()
            return SettingsAuthorityMutation(true, snapshot())
        }

        fun releaseAll() {
            selectRelease.countDown()
        }
    }

    private companion object {
        const val IO_THREAD = "settings-test-io"
        const val MAIN_THREAD = "settings-test-main"
    }
}
