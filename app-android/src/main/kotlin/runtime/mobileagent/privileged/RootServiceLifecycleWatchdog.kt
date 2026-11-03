// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import runtime.mobileagent.domain.RootServiceLifecycleCoordinator

/**
 * One atomic `/proc/<pid>/stat` snapshot (state, ppid, pgrp, session, start-time).
 * Values are kernel facts; a recycled pid reports a different start-time and is rejected
 * by every caller that compares the pair.
 */
data class RootProcStat(
    val pid: Int,
    val state: Char,
    val ppid: Int,
    val pgrp: Int,
    val session: Int,
    val startTime: Long,
) {
    val isDead: Boolean
        get() = state == 'Z' || state == 'X' || state == 'x'
}

internal fun parseProcStat(pid: Int, raw: String): RootProcStat? = runCatching {
    val fields = raw.substringAfterLast(')').trim().split(' ').filter { it.isNotEmpty() }
    // fields[0]=state fields[1]=ppid fields[2]=pgrp fields[3]=session ... fields[19]=starttime
    RootProcStat(
        pid = pid,
        state = fields[0][0],
        ppid = fields[1].toInt(),
        pgrp = fields[2].toInt(),
        session = fields[3].toInt(),
        startTime = fields[19].toLong(),
    )
}.getOrNull()

/** Narrow `/proc` seam so lifecycle and process-group decisions are testable off-device. */
interface RootProcessSignals {
    fun stat(pid: Int): RootProcStat?

    fun oomScoreAdj(pid: Int): Int?

    fun groupMembers(pgrp: Int): List<RootProcStat>

    fun childrenOf(pid: Int): List<Int>

    fun selfChildren(): Set<Int>
}

object AndroidRootProcessSignals : RootProcessSignals {
    override fun stat(pid: Int): RootProcStat? {
        if (pid <= 0) return null
        val raw = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        return parseProcStat(pid, raw)
    }

    override fun oomScoreAdj(pid: Int): Int? =
        runCatching { File("/proc/$pid/oom_score_adj").readText().trim().toInt() }.getOrNull()

    override fun groupMembers(pgrp: Int): List<RootProcStat> {
        if (pgrp <= 0) return emptyList()
        val entries = runCatching { File("/proc").listFiles() }.getOrNull() ?: return emptyList()
        return entries.asSequence()
            .mapNotNull { it.name.toIntOrNull() }
            .mapNotNull(::stat)
            .filter { it.pgrp == pgrp && !it.isDead }
            .toList()
    }

    override fun childrenOf(pid: Int): List<Int> {
        if (pid <= 0) return emptyList()
        val tasks = runCatching { File("/proc/$pid/task").listFiles() }.getOrNull() ?: return emptyList()
        val pids = ArrayList<Int>()
        tasks.asSequence().filter { it.isDirectory }.forEach { task ->
            runCatching {
                File(task, "children").readText().split(' ').forEach { token ->
                    token.trim().toIntOrNull()?.let(pids::add)
                }
            }
        }
        return pids
    }

    override fun selfChildren(): Set<Int> {
        val tasks = runCatching { File("/proc/self/task").listFiles() }.getOrNull() ?: return emptySet()
        val pids = HashSet<Int>()
        tasks.asSequence().filter { it.isDirectory }.forEach { task ->
            runCatching {
                File(task, "children").readText().split(' ').forEach { token ->
                    token.trim().toIntOrNull()?.let(pids::add)
                }
            }
        }
        return pids
    }
}

data class RootServiceLifecycleConfig(
    val signals: RootProcessSignals = AndroidRootProcessSignals,
    val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    val handshakeTimeoutMs: Long = 30_000L,
    val pollIntervalMs: Long = 1_000L,
)

/**
 * Drives the non-daemon service lifecycle contract inside the `UID 0` process:
 *
 * - before handshake: no valid control Binder for [handshakeTimeoutMs] (30s) stops the service;
 * - after handshake: controller pid/start-time loss cancels work and initiates the bounded
 *   shutdown ([runtime.mobileagent.domain.RootForcedShutdownHandle], 5s upper bound);
 * - cached/background host process plus 30s without in-flight work closes FDs and stops.
 *
 * Cached/background is observed as `/proc/<pid>/oom_score_adj >= 900`; a visible or
 * perceptible host process never starts the idle timer.
 */
class RootServiceLifecycleWatchdog(
    private val config: RootServiceLifecycleConfig,
    private val startedAtMs: Long,
    private val controllerPid: Int,
    private val controllerStartTime: Long,
    private val lifecycle: RootServiceLifecycleCoordinator?,
    private val onControllerLost: () -> Unit,
    private val onHandshakeTimeout: () -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)
    @Volatile
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = Thread({ loop() }, "mar-root-service-lifecycle")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    fun tick(nowMs: Long) {
        if (controllerPid > 0 && !controllerAlive()) {
            reportOnce(onControllerLost)
            return
        }
        val coordinator = lifecycle
        if (coordinator == null) {
            if (nowMs - startedAtMs >= config.handshakeTimeoutMs) reportOnce(onHandshakeTimeout)
            return
        }
        coordinator.onAppBackgroundChanged(hostIsCached(), nowMs)
        coordinator.pollBackgroundIdleStop(nowMs)
    }

    private fun loop() {
        while (running.get()) {
            runCatching { tick(config.clock()) }
            if (!running.get()) break
            try {
                Thread.sleep(config.pollIntervalMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun controllerAlive(): Boolean {
        if (controllerPid <= 0) return false
        val stat = config.signals.stat(controllerPid) ?: return false
        if (stat.isDead) return false
        return controllerStartTime <= 0L || stat.startTime == controllerStartTime
    }

    private fun hostIsCached(): Boolean {
        val oomScoreAdj = config.signals.oomScoreAdj(controllerPid) ?: return false
        return oomScoreAdj >= CACHED_OOM_SCORE_ADJ
    }

    private fun reportOnce(action: () -> Unit) {
        if (reported.compareAndSet(false, true)) action()
    }

    companion object {
        /** Android `ProcessList.CACHED_APP_MIN_ADJ`: cached/background host process. */
        const val CACHED_OOM_SCORE_ADJ: Int = 900
    }
}
