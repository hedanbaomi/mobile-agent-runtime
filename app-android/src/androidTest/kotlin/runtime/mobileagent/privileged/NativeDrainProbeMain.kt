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

        fun run(command: String, timeoutMs: Long): Pair<RootSupervisedCommandReport?, Long> {
            val handle = backend.createCancelHandle() ?: return null to -1L
            return try {
                val started = System.nanoTime()
                backend.runSupervised(
                    RootSupervisedCommandSpec(command, "/", emptyMap(), timeoutMs, 0L, 64 * 1024),
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

        println("PROBE summary failures=$failures")
        if (failures != 0) {
            kotlin.system.exitProcess(1)
        }
    }
}
