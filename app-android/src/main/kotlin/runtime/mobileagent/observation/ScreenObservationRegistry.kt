// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

/** App-local observation channel, independent of the selected elevated ADB authority. */
class ScreenObservationRegistry private constructor(context: Context) : ScreenObservationPort {
    private val preferences = context.applicationContext.getSharedPreferences("screen-observation", Context.MODE_PRIVATE)
    private val lock = Any()
    private var service: ScreenObservationService? = null
    private val mutableAccess = MutableStateFlow(ScreenAccess(consent = preferences.getBoolean("consent", false)))
    private val mutableChanges = MutableStateFlow(0L)
    override val access = mutableAccess.asStateFlow()
    override val changes = mutableChanges.asStateFlow()

    fun setConsent(enabled: Boolean): Boolean = synchronized(lock) {
        if (!preferences.edit().putBoolean("consent", enabled).commit()) return false
        mutableAccess.value = mutableAccess.value.copy(consent = enabled, revision = mutableAccess.value.revision + 1)
        mutableChanges.value += 1
        true
    }

    internal fun attach(value: ScreenObservationService) = synchronized(lock) {
        service = value
        mutableAccess.value = mutableAccess.value.copy(connected = true, revision = mutableAccess.value.revision + 1)
        mutableChanges.value += 1
    }

    internal fun detach(value: ScreenObservationService) = synchronized(lock) {
        if (service === value) {
            service = null
            mutableAccess.value = mutableAccess.value.copy(connected = false, revision = mutableAccess.value.revision + 1)
            mutableChanges.value += 1
        }
    }

    internal fun changed() = synchronized(lock) { mutableChanges.value += 1 }

    override suspend fun capture(expectedPackage: String?): ScreenSnapshot = withContext(Dispatchers.IO) {
        val (active, permission) = synchronized(lock) {
            if (!mutableAccess.value.ready) throw ScreenObservationException("SCREEN_ACCESS_UNAVAILABLE")
            (service ?: throw ScreenObservationException("SCREEN_ACCESS_UNAVAILABLE")) to mutableAccess.value
        }
        val sequence = changes.value
        val started = SystemClock.elapsedRealtime()
        if (access.value != permission) throw ScreenObservationException("SCREEN_ACCESS_CHANGED")
        val root = active.rootInActiveWindow ?: throw ScreenObservationException("SCREEN_WINDOW_UNAVAILABLE")
        val queue = ArrayDeque<PendingNode>()
        queue.add(PendingNode(root, null, 0))
        val nodes = mutableListOf<ScreenNode>()
        var truncated = false
        var remainingChars = 12_000
        try {
            val packageName = root.packageName?.toString().orEmpty()
            val window = root.windowId
            if (packageName.isBlank() || (expectedPackage != null && packageName != expectedPackage)) {
                throw ScreenObservationException("SCREEN_PACKAGE_MISMATCH")
            }
            fun bounded(value: CharSequence?): String {
                val raw = value?.toString().orEmpty()
                val limit = minOf(240, remainingChars.coerceAtLeast(0))
                if (raw.length > limit) truncated = true
                return raw.take(limit).also { remainingChars -= it.length }
            }
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                if (access.value != permission) throw ScreenObservationException("SCREEN_ACCESS_CHANGED")
                if (nodes.size >= 160 || SystemClock.elapsedRealtime() - started >= 1500 || remainingChars <= 0) {
                    truncated = true
                    break
                }
                val entry = queue.removeFirst()
                val node = entry.node
                try {
                    // A password subtree is omitted, including descriptions and descendants.
                    if (!node.isVisibleToUser || node.isPassword) continue
                    val nodePackage = node.packageName?.toString()
                    if (nodePackage != null && nodePackage != packageName) continue
                    val bounds = Rect().also(node::getBoundsInScreen)
                    val index = nodes.size
                    nodes += ScreenNode(index, entry.parent, bounded(node.text), bounded(node.contentDescription),
                        bounded(node.className), listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        node.isClickable, node.isEditable, node.isScrollable, node.isEnabled)
                    if (entry.depth >= 24) {
                        if (node.childCount > 0) truncated = true
                    } else {
                        val count = minOf(node.childCount, (320 - queue.size).coerceAtLeast(0))
                        if (count < node.childCount) truncated = true
                        repeat(count) { child ->
                            currentCoroutineContext().ensureActive()
                            if (access.value != permission) throw ScreenObservationException("SCREEN_ACCESS_CHANGED")
                            node.getChild(child)?.let { queue.add(PendingNode(it, index, entry.depth + 1)) }
                        }
                    }
                } finally { recycle(node) }
            }
            if (access.value != permission) throw ScreenObservationException("SCREEN_ACCESS_CHANGED")
            val finalRoot = active.rootInActiveWindow ?: throw ScreenObservationException("SCREEN_WINDOW_UNAVAILABLE")
            try {
                if (finalRoot.packageName?.toString() != packageName || finalRoot.windowId != window) {
                    throw ScreenObservationException("SCREEN_WINDOW_CHANGED")
                }
            } finally { recycle(finalRoot) }
            ScreenSnapshot(packageName, window, sequence, started, nodes.toList(), truncated, changes.value == sequence)
        } finally { while (queue.isNotEmpty()) recycle(queue.removeFirst().node) }
    }

    private data class PendingNode(val node: AccessibilityNodeInfo, val parent: Int?, val depth: Int)
    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }

    companion object {
        @Volatile private var instance: ScreenObservationRegistry? = null
        fun get(context: Context): ScreenObservationRegistry = instance ?: synchronized(this) {
            instance ?: ScreenObservationRegistry(context).also { instance = it }
        }
    }
}

/** System-bound only; this service observes, never injects gestures or changes permissions. */
class ScreenObservationService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); ScreenObservationRegistry.get(this).attach(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { ScreenObservationRegistry.get(this).changed() }
    override fun onInterrupt() { ScreenObservationRegistry.get(this).changed() }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ScreenObservationRegistry.get(this).detach(this)
        return super.onUnbind(intent)
    }
    override fun onDestroy() { ScreenObservationRegistry.get(this).detach(this); super.onDestroy() }
}
