// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

/**
 * UID0 drain probe (`N3`).
 *
 * `libmar_root_supervisor.so` refuses to run unless `getuid() == geteuid() == 0`, so the JUnit
 * instrumentation process (app UID) can never exercise the real drain loop. This entry point runs
 * the same production JNI from a UID0 `app_process` started by the emulator harness; it prints one
 * `PROBE ...` line per scenario and exits non-zero if any budget is violated.
 */
object NativeDrainProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val backend = JniRootNativeSupervisorBackend
        var failures = 0

        fun report(name: String, ok: Boolean, detail: String) {
            println("PROBE $name ${if (ok) "PASS" else "FAIL"} $detail")
            if (!ok) failures++
        }

        fun elapsed(sinceNanos: Long) = (System.nanoTime() - sinceNanos) / 1_000_000L

        fun run(
            command: String,
            timeoutMs: Long,
            maxBytes: Int = 64 * 1024,
            cwd: String = "/",
            environment: Map<String, String> = emptyMap(),
            graceMs: Long = 0L,
        ): Pair<RootSupervisedCommandReport?, Long> {
            val handle = backend.createCancelHandle() ?: return null to -1L
            return try {
                val started = System.nanoTime()
                backend.runSupervised(
                    RootSupervisedCommandSpec(command, cwd, environment, timeoutMs, graceMs, maxBytes),
                    handle,
                ) to elapsed(started)
            } finally {
                handle.close()
            }
        }

        println("PROBE uid=${android.os.Process.myUid()} kernelSupport=${backend.isKernelSubreaperSupported()}")

        val (control, controlMs) = run("echo mar-native-control", 5_000L)
        report(
            "control",
            control != null && control.startStatus == 0 && control.exitCode == 0 &&
                String(control.stdout).contains("mar-native-control"),
            "start=${control?.startStatus} exit=${control?.exitCode} elapsedMs=$controlMs",
        )

        val (stdout, stdoutMs) = run("yes", 300L)
        report(
            "continuous-stdout-deadline",
            stdout != null && stdout.timedOut && stdout.settled && stdoutMs < 10_000L,
            "timedOut=${stdout?.timedOut} settled=${stdout?.settled} elapsedMs=$stdoutMs",
        )

        val (stderr, stderrMs) = run("yes >&2", 300L)
        report(
            "continuous-stderr-deadline",
            stderr != null && stderr.timedOut && stderr.settled && stderrMs < 10_000L,
            "timedOut=${stderr?.timedOut} settled=${stderr?.settled} elapsedMs=$stderrMs",
        )

        val handle = backend.createCancelHandle()
        if (handle == null) {
            report("cancel-during-output", false, "no cancel token")
        } else {
            try {
                val started = System.nanoTime()
                val cancelled = java.util.concurrent.atomic.AtomicReference<RootSupervisedCommandReport?>(null)
                val worker = Thread {
                    cancelled.set(
                        backend.runSupervised(
                            RootSupervisedCommandSpec("yes", "/", emptyMap(), 60_000L, 0L, 64 * 1024),
                            handle,
                        ),
                    )
                }
                worker.isDaemon = true
                worker.start()
                Thread.sleep(400L)
                val signalled = handle.requestCancel()
                worker.join(15_000L)
                val ms = elapsed(started)
                val report0 = cancelled.get()
                // A cancel ACK alone is not a drained supervisor: require the report to be
                // settled and the worker to have actually stopped.
                report(
                    "cancel-during-output",
                    signalled && report0 != null && report0.cancelled && report0.settled &&
                        !worker.isAlive && ms < 12_000L,
                    "signalled=$signalled cancelled=${report0?.cancelled} settled=${report0?.settled} " +
                        "workerAlive=${worker.isAlive} elapsedMs=$ms",
                )
            } finally {
                handle.close()
            }
        }

        val (adopted, adoptedMs) = run("sh -c '(sleep 0.05; exit 0) & exit 0'; sleep 0.2", 5_000L)
        report("reaped-descendant-is-not-leak",
            adopted != null && adopted.exitCode == 0 && adopted.settled && !adopted.leakedDescendants,
            "settled=${adopted?.settled} leaked=${adopted?.leakedDescendants} elapsedMs=$adoptedMs")

        // `R4.1`: a descendant that finishes on its own inside the 300 ms natural drain window is
        // not a leak. The command stays SUCCEEDED (exit 0, output preserved) and no SIGTERM may
        // reach the descendant.
        val (naturalDrain, naturalDrainMs) = run(
            "(trap 'echo TERM_SEEN; exit 0' TERM; sleep 0.05; echo descendant_done) & exit 0",
            5_000L,
        )
        report("observed-descendant-drains-naturally",
            naturalDrain != null && naturalDrain.exitCode == 0 && naturalDrain.leaderExited &&
                naturalDrain.settled && !naturalDrain.leakedDescendants &&
                String(naturalDrain.stdout).contains("descendant_done") &&
                !String(naturalDrain.stdout).contains("TERM_SEEN"),
            "exit=${naturalDrain?.exitCode} settled=${naturalDrain?.settled} " +
                "leaked=${naturalDrain?.leakedDescendants} " +
                "stdout=${String(naturalDrain?.stdout ?: ByteArray(0))} elapsedMs=$naturalDrainMs")

        // `R4.1` exact reported counterexample: the backgrounded pipeline is still alive when the
        // leader exits and finishes by itself within a few milliseconds. It used to be latched as
        // leaked at T+0, which discarded `head`'s output.
        val (pipelineDrain, pipelineDrainMs) = run("seq 1 100000 | head -n 1 & exit 0", 5_000L)
        report("short-pipeline-drains-naturally",
            pipelineDrain != null && pipelineDrain.exitCode == 0 && pipelineDrain.leaderExited &&
                pipelineDrain.settled && !pipelineDrain.leakedDescendants &&
                String(pipelineDrain.stdout).trim() == "1",
            "exit=${pipelineDrain?.exitCode} settled=${pipelineDrain?.settled} " +
                "leaked=${pipelineDrain?.leakedDescendants} " +
                "stdout=${String(pipelineDrain?.stdout ?: ByteArray(0))} elapsedMs=$pipelineDrainMs")

        // Masking guard: a descendant that really outlives the natural window must still be
        // reported as leaked (fail closed) while the supervisor kills and reaps it.
        val (escaped, escapedMs) = run("(sleep 30 &); exit 0", 5_000L)
        report("live-descendant-after-grace-is-leaked",
            escaped != null && escaped.exitCode == 0 && escaped.leaderExited && escaped.settled &&
                escaped.leakedDescendants,
            "exit=${escaped?.exitCode} settled=${escaped?.settled} " +
                "leaked=${escaped?.leakedDescendants} elapsedMs=$escapedMs")

        val (newSession, newSessionMs) = run(
            "command -v setsid >/dev/null || exit 77; setsid sh -c 'sleep 30' & exit 0", 5_000L,
        )
        report("setsid-descendant-after-grace-is-leaked",
            newSession != null && newSession.exitCode == 0 && newSession.leaderExited &&
                newSession.settled && newSession.leakedDescendants,
            "exit=${newSession?.exitCode} settled=${newSession?.settled} " +
                "leaked=${newSession?.leakedDescendants} elapsedMs=$newSessionMs")

        // `M5`: a working directory that genuinely does not exist must fail inside the supervised
        // child, before the command runs, with exit 126 and a fixed, path-free diagnostic. The old
        // probe passed `/proc/self/fd/<n>` so that only the parent could stat it, which is exactly
        // the artificial invalidation that hid the unreachable 126 path.
        val missingCwd = "/data/local/tmp/mar-probe-missing-cwd-${java.util.UUID.randomUUID()}"
        if (java.io.File(missingCwd).exists()) {
            report("cwd-failure-diagnostic", false, "probe path unexpectedly exists: $missingCwd")
        } else {
            val (cwdFailure, cwdFailureMs) = run(
                "echo cwd-command-must-not-run", 5_000L, cwd = missingCwd,
            )
            val cwdStderr = String(cwdFailure?.stderr ?: ByteArray(0))
            report("cwd-failure-diagnostic",
                cwdFailure != null && cwdFailure.startStatus == 0 && cwdFailure.leaderExited &&
                    cwdFailure.exitCode == 126 && cwdFailure.settled &&
                    !cwdFailure.leakedDescendants && cwdFailure.stdout.isEmpty() &&
                    cwdStderr.contains("Root shell: working directory is unavailable") &&
                    !cwdStderr.contains(missingCwd) && !cwdStderr.contains("/data/local/tmp"),
                "start=${cwdFailure?.startStatus} exit=${cwdFailure?.exitCode} " +
                    "settled=${cwdFailure?.settled} stdoutBytes=${cwdFailure?.stdout?.size} " +
                    "stderr=$cwdStderr elapsedMs=$cwdFailureMs")
        }

        val cancelKill = backend.createCancelHandle()
        if (cancelKill == null) {
            report("cancel-with-missing-supervisor-report-preserves-cause", false, "no cancel token")
        } else {
            try {
                val started = System.nanoTime()
                val cancelledReport = java.util.concurrent.atomic.AtomicReference<RootSupervisedCommandReport?>(null)
                val command = "trap 'kill -9 \"\$PPID\"' TERM; while :; do sleep 1; done"
                val worker = Thread {
                    cancelledReport.set(
                        backend.runSupervised(
                            RootSupervisedCommandSpec(command, "/", emptyMap(), 60_000L, 100L, 64 * 1024),
                            cancelKill,
                        ),
                    )
                }
                worker.isDaemon = true
                worker.start()
                Thread.sleep(300L)
                val signalled = cancelKill.requestCancel()
                worker.join(15_000L)
                val elapsedMs = elapsed(started)
                val report0 = cancelledReport.get()
                report(
                    "cancel-with-missing-supervisor-report-preserves-cause",
                    signalled && !worker.isAlive && report0 != null && report0.startStatus == -3 &&
                        report0.cancelled && !report0.timedOut && !report0.settled,
                    "signalled=$signalled start=${report0?.startStatus} cancelled=${report0?.cancelled} " +
                        "timedOut=${report0?.timedOut} settled=${report0?.settled} " +
                        "workerAlive=${worker.isAlive} elapsedMs=$elapsedMs",
                )
            } finally {
                cancelKill.close()
            }
        }

        val (tail, tailMs) = run("dd if=/dev/zero bs=1000 count=200 2>/dev/null", 5_000L, 262_144)
        report("full-output-tail",
            tail != null && tail.exitCode == 0 && tail.stdout.size == 200000 && !tail.stdoutTruncated && tail.settled,
            "bytes=${tail?.stdout?.size} truncated=${tail?.stdoutTruncated} elapsedMs=$tailMs")

        val (androidCommands, androidMs) = run(
            "pm path android >/dev/null && cmd -l >/dev/null && am get-current-user >/dev/null && pwd",
            15_000L, cwd = "/data/local/tmp", environment = rootShellEnvironment(System.getenv()))
        report("android-environment-and-cwd",
            androidCommands != null && androidCommands.exitCode == 0 && androidCommands.settled &&
                String(androidCommands.stdout).trim() == "/data/local/tmp",
            "exit=${androidCommands?.exitCode} settled=${androidCommands?.settled} elapsedMs=$androidMs")

        println("PROBE summary failures=$failures")
        if (failures != 0) {
            kotlin.system.exitProcess(1)
        }
    }
}
