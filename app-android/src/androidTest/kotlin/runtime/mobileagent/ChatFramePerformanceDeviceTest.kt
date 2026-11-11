// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.chat.ChatActions
import runtime.mobileagent.feature.chat.ChatMessageUi
import runtime.mobileagent.feature.chat.ChatUiState
import runtime.mobileagent.feature.chat.ConversationScreen
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Instrumentation frame-time baseline for the production conversation surface.
 *
 * A ~300-row synthetic conversation is rendered by the real [ConversationScreen] and scrolled with
 * injected gestures; `Window.OnFrameMetricsAvailableListener` records `FrameMetrics.TOTAL_DURATION`
 * for frames the window actually produced. This is window frame timing collected by an
 * instrumentation test, not Macrobenchmark (no warmup/iteration/compilation control, no
 * `FrameTimingMetric` semantics) and not a real-device SLO. The fixture uses synthetic text only,
 * never user configuration, content, credentials or network clients.
 */
@SdkSuppress(minSdkVersion = 24)
class ChatFramePerformanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun scrollingLargeConversationWritesFrameMetricsBaseline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.filesDir, BASELINE_FILE)
        // A failed capture must not leave a previous run looking like this run's evidence.
        output.delete()

        val durationsNanos = ConcurrentLinkedQueue<Long>()
        val droppedMetricReports = AtomicLong()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, droppedSinceLastCallback ->
            if (droppedSinceLastCallback > 0) {
                droppedMetricReports.addAndGet(droppedSinceLastCallback.toLong())
            }
            // Zero/negative TOTAL_DURATION means no completed frame; it is not a fast frame.
            val totalDuration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (totalDuration > 0L) durationsNanos.add(totalDuration)
        }
        val window = compose.activity.window

        compose.setContent {
            MaterialTheme {
                ConversationScreen(
                    state = ChatUiState(
                        selectedSessionId = "frame-baseline-session",
                        messages = syntheticMessages(),
                        language = "en",
                    ),
                    actions = ChatActions(),
                    showGlobalMenu = false,
                )
            }
        }

        val timeline = compose.onNodeWithTag("conversation.list")
        timeline.assertIsDisplayed()
        compose.waitForIdle()

        compose.runOnUiThread {
            window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        }
        var listenerRegistered = true
        fun removeListener() = compose.runOnUiThread {
            if (listenerRegistered) {
                window.removeOnFrameMetricsAvailableListener(listener)
                listenerRegistered = false
            }
        }
        try {
            // The surface follows the newest message on first composition, so the first gesture
            // scrolls away from the bottom instead of silently doing nothing at the top.
            val startPosition = scrollPosition(timeline)
            timeline.performTouchInput { swipeDown(durationMillis = SWIPE_DURATION_MS) }
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            assertTrue(
                "The synthetic conversation did not move after a real swipe on conversation.list",
                abs(scrollPosition(timeline) - startPosition) > 0.01f,
            )

            var scrollGestures = 1
            repeat(MEASURED_SCROLL_GESTURES) { index ->
                timeline.performTouchInput {
                    if (index % 2 == 0) {
                        swipeUp(durationMillis = SWIPE_DURATION_MS)
                    } else {
                        swipeDown(durationMillis = SWIPE_DURATION_MS)
                    }
                }
                compose.waitForIdle()
                instrumentation.waitForIdleSync()
                scrollGestures++
            }

            // FrameMetrics callbacks are posted to the main looper; keep draining it until a real
            // sample arrives or the bounded wait expires.
            var waitedMs = 0L
            while (durationsNanos.isEmpty() && waitedMs < FRAME_COLLECTION_TIMEOUT_MS) {
                SystemClock.sleep(POLL_INTERVAL_MS)
                instrumentation.waitForIdleSync()
                waitedMs += POLL_INTERVAL_MS
            }

            removeListener()

            val durations = durationsNanos.toList().sorted()
            assertTrue(
                "No valid Window FrameMetrics.TOTAL_DURATION samples were collected after " +
                    "$scrollGestures scroll gestures; a frame baseline without frames is not evidence",
                durations.isNotEmpty(),
            )
            assertTrue(
                "Only ${durations.size} frame samples were collected after $scrollGestures scroll " +
                    "gestures; thin frame evidence must not be reported as a baseline",
                durations.size >= MIN_FRAME_SAMPLES,
            )

            val metric = frameBaselineJson(
                durationsNanos = durations,
                scrollGestureCount = scrollGestures,
                droppedMetricReportCount = droppedMetricReports.get(),
            )
            output.writeText(metric.toString(2), Charsets.UTF_8)
            // Also surface the compact metric through instrumentation status so a non-debuggable
            // Review run can be evidenced from the instrumentation log without pulling app files.
            instrumentation.sendStatus(STATUS_FRAME_METRIC, Bundle().apply {
                putString("frameMetric", metric.toString())
            })
        } finally {
            removeListener()
        }
    }

    private fun scrollPosition(timeline: SemanticsNodeInteraction): Float =
        timeline.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun syntheticMessages(): List<ChatMessageUi> = List(SYNTHETIC_MESSAGE_COUNT) { index ->
        val assistant = index % 2 == 1
        ChatMessageUi(
            id = "frame-message-$index",
            role = if (assistant) "assistant" else "user",
            text = if (assistant) {
                "Synthetic assistant reply ${index + 1}. Plain prose paragraph ${index % 7} belongs to " +
                    "a generated fixture used only to give each row a realistic scrolling height."
            } else {
                "Synthetic user line ${index + 1} with a short follow-up question for layout only."
            },
        )
    }

    private fun frameBaselineJson(
        durationsNanos: List<Long>,
        scrollGestureCount: Int,
        droppedMetricReportCount: Long,
    ): JSONObject {
        val sorted = durationsNanos.sorted()
        val budgetMs = frameBudgetMs()
        return JSONObject()
            .put("schemaVersion", 2)
            .put("metric", "window-frame-metrics-total-duration")
            .put("metricSource", "Window.addOnFrameMetricsAvailableListener(FrameMetrics.TOTAL_DURATION)")
            .put("measurementKind", "instrumentation-frame-timing")
            // The claim boundary is part of the artifact, not only of a chat message.
            .put("notMacrobenchmark", true)
            .put("limitations", LIMITATIONS)
            .put("sampleCount", sorted.size)
            .put("p50Ms", nanosToMillis(nearestRankPercentile(sorted, 0.50)))
            .put("p95Ms", nanosToMillis(nearestRankPercentile(sorted, 0.95)))
            .put("maxMs", nanosToMillis(sorted.last()))
            .put("frameBudgetMs", budgetMs)
            .put("framesOverBudgetCount", sorted.count { nanosToMillis(it) > budgetMs })
            // FrameMetrics' callback drop count is missed metric reports, not missed display frames.
            .put("droppedFrameMetricsCount", droppedMetricReportCount)
            .put("syntheticMessageCount", SYNTHETIC_MESSAGE_COUNT)
            .put("syntheticMessagePairing", "generated user/assistant text only; no user configuration or content")
            .put("scrollGestureCount", scrollGestureCount)
            .put("scrollGestureDurationMs", SWIPE_DURATION_MS)
            .put("surface", "ConversationScreen conversation.list (production component)")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            .put("abis", JSONArray().apply { Build.SUPPORTED_ABIS.forEach { abi -> put(abi) } })
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            // This app has no product flavors; the build type is the whole variant identity.
            .put("variant", BuildConfig.BUILD_TYPE)
            .put("buildType", BuildConfig.BUILD_TYPE)
            .put("debuggable", BuildConfig.DEBUG)
            .put("gitRevision", BuildConfig.GIT_REVISION)
    }

    private fun frameBudgetMs(): Double {
        @Suppress("DEPRECATION")
        val refreshRateHz = runCatching { compose.activity.windowManager.defaultDisplay.refreshRate }
            .getOrDefault(DEFAULT_REFRESH_RATE_HZ)
        val hz = if (refreshRateHz > 0f) refreshRateHz.toDouble() else DEFAULT_REFRESH_RATE_HZ.toDouble()
        return MILLIS_PER_SECOND / hz
    }

    private fun nearestRankPercentile(sortedNanos: List<Long>, percentile: Double): Long {
        val rank = ceil(percentile * sortedNanos.size).toInt().coerceIn(1, sortedNanos.size)
        return sortedNanos[rank - 1]
    }

    private fun nanosToMillis(nanos: Long): Double = nanos / NANOS_PER_MILLISECOND

    private companion object {
        const val BASELINE_FILE = "chat-frame-baseline.json"
        const val FRAME_COLLECTION_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 100L
        const val MEASURED_SCROLL_GESTURES = 6
        const val SWIPE_DURATION_MS = 400L
        // A gate against single-frame or stalled captures, not a performance requirement.
        const val MIN_FRAME_SAMPLES = 10
        const val SYNTHETIC_MESSAGE_COUNT = 300
        const val STATUS_FRAME_METRIC = 0
        const val DEFAULT_REFRESH_RATE_HZ = 60f
        const val MILLIS_PER_SECOND = 1_000.0
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        const val LIMITATIONS =
            "Instrumentation window frame timing on the production ConversationScreen, not " +
                "Macrobenchmark and not a real-device SLO. Samples cover scripted swipes plus settle " +
                "on one build/device with no warmup or iteration statistics; FrameMetrics " +
                "TOTAL_DURATION includes main-thread and render-stage time for the window, and " +
                "emulator or loaded-host values do not transfer to physical hardware."
    }
}