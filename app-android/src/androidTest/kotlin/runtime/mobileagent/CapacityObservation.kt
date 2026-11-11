// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Sampling includes model initialization and staging; emulator sensors are observations, not heat SLOs. */
internal class CapacityObservation(private val context: Context, private val root: File) : AutoCloseable {
    private val stop = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    private var samples = 0
    private var peakPss = 0
    private var peakNative = 0L
    private var peakJava = 0L
    private var peakDisk = 0L
    private val thermal = mutableSetOf<Int>()
    private val batteryDeciC = mutableSetOf<Int>()
    private val thread = Thread({
        try {
            do {
                peakPss = maxOf(peakPss, Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss)
                peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                val runtime = Runtime.getRuntime()
                peakJava = maxOf(peakJava, runtime.totalMemory() - runtime.freeMemory())
                peakDisk = maxOf(peakDisk, root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                if (Build.VERSION.SDK_INT >= 29) {
                    thermal += (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
                }
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.let { if (it.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) batteryDeciC += it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) }
                samples++
            } while (!stop.await(500, TimeUnit.MILLISECONDS))
        } catch (error: Throwable) { failure.set(error) }
    }, "capacity-observation").apply { isDaemon = true; start() }

    override fun close() {
        stop.countDown()
        var interrupted = Thread.interrupted()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        try {
            while (thread.isAlive && System.nanoTime() < deadline) {
                try { thread.join(100) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
        check(!thread.isAlive) { "Capacity sampler did not terminate" }
        failure.get()?.let { throw AssertionError("Capacity observation failed", it) }
    }

    fun metric(): JSONObject {
        check(!thread.isAlive && samples > 0)
        return JSONObject().put("samples", samples).put("samplingIntervalMs", 500)
            .put("sampledPeakPssKb", peakPss).put("sampledPeakNativeBytes", peakNative)
            .put("sampledPeakJavaBytes", peakJava).put("sampledPeakDiskBytes", peakDisk)
            .put("thermalStatusObserved", JSONArray(thermal.sorted()))
            .put("batteryTemperatureDeciCObserved", JSONArray(batteryDeciC.sorted()))
            .put("thermalInterpretation", "Device-reported observations; emulator values do not establish physical thermal behavior")
    }
}
