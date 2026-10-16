// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.UUID

/** App-local observation channel, independent of the selected elevated ADB authority. */
class ScreenObservationRegistry private constructor(context: Context) : ScreenObservationPort, ScreenActionPort {
    private val ownPackage = context.applicationContext.packageName
    private val preferences = context.applicationContext.getSharedPreferences("screen-observation", Context.MODE_PRIVATE)
    private val lock = Any()
    private var service: ScreenObservationService? = null
    private val mutableAccess = MutableStateFlow(ScreenAccess(consent = preferences.getBoolean("consent", false),
        actionsAllowed = preferences.getBoolean("consent", false) && preferences.getBoolean("actions", false)))
    private val mutableChanges = MutableStateFlow(0L)
    private var actionSnapshot: ActionSnapshot? = null
    override val access = mutableAccess.asStateFlow()
    override val changes = mutableChanges.asStateFlow()

    fun setConsent(enabled: Boolean): Boolean = synchronized(lock) {
        val actionsAllowed = enabled && mutableAccess.value.actionsAllowed
        if (!preferences.edit().putBoolean("consent", enabled).putBoolean("actions", actionsAllowed).commit()) return false
        mutableAccess.value = mutableAccess.value.copy(consent = enabled, actionsAllowed = actionsAllowed,
            revision = mutableAccess.value.revision + 1)
        actionSnapshot = null
        mutableChanges.value += 1
        true
    }

    fun setActionsAllowed(enabled: Boolean): Boolean = synchronized(lock) {
        if (enabled && !mutableAccess.value.consent) return false
        if (!preferences.edit().putBoolean("actions", enabled).commit()) return false
        mutableAccess.value = mutableAccess.value.copy(actionsAllowed = enabled, revision = mutableAccess.value.revision + 1)
        actionSnapshot = null
        mutableChanges.value += 1
        true
    }

    internal fun attach(value: ScreenObservationService) = synchronized(lock) {
        service = value
        actionSnapshot = null
        mutableAccess.value = mutableAccess.value.copy(connected = true, revision = mutableAccess.value.revision + 1)
        mutableChanges.value += 1
    }

    internal fun detach(value: ScreenObservationService) = synchronized(lock) {
        if (service === value) {
            service = null
            actionSnapshot = null
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
        val targets = mutableMapOf<Int, NodeTarget>()
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
                    val observed = ScreenNode(index, entry.parent, bounded(node.text), bounded(node.contentDescription),
                        bounded(node.className), listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        node.isClickable, node.isEditable, node.isScrollable, node.isEnabled,
                        if (permission.actionsReady && packageName != ownPackage) nodeActions(node) else emptyList())
                    nodes += observed
                    if (observed.actions.isNotEmpty()) targets[index] = NodeTarget(entry.path, observed)
                    if (entry.depth >= 24) {
                        if (node.childCount > 0) truncated = true
                    } else {
                        val count = minOf(node.childCount, (320 - queue.size).coerceAtLeast(0))
                        if (count < node.childCount) truncated = true
                        repeat(count) { child ->
                            currentCoroutineContext().ensureActive()
                            if (access.value != permission) throw ScreenObservationException("SCREEN_ACCESS_CHANGED")
                            node.getChild(child)?.let { queue.add(PendingNode(it, index, entry.depth + 1, entry.path + child)) }
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
            val stable = changes.value == sequence
            val id = synchronized(lock) {
                if (mutableAccess.value != permission || service !== active) throw ScreenObservationException("SCREEN_ACCESS_CHANGED")
                actionSnapshot = if (permission.actionsReady && stable) ActionSnapshot(
                    UUID.randomUUID().toString(), packageName, window, permission, active, targets.toMap()) else null
                actionSnapshot?.id
            }
            ScreenSnapshot(packageName, window, sequence, started, nodes.toList(), truncated, stable, id)
        } finally { while (queue.isNotEmpty()) recycle(queue.removeFirst().node) }
    }

    override suspend fun perform(request: ScreenActionRequest, authorized: suspend () -> Boolean,
        onDispatch: () -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!authorized()) throw ScreenObservationException("SCREEN_ACTION_ACCESS_CHANGED")
        val snapshot = synchronized(lock) {
            actionSnapshot?.takeIf { it.id == request.observationId && it.permission == mutableAccess.value &&
                it.permission.actionsReady && it.service === service }
                ?: throw ScreenObservationException("SCREEN_ACTION_OBSERVE_AGAIN")
        }
        var node = snapshot.service.rootInActiveWindow ?: throw ScreenObservationException("SCREEN_WINDOW_UNAVAILABLE")
        try {
            if (node.packageName?.toString() != snapshot.packageName || node.windowId != snapshot.windowId) {
                throw ScreenObservationException("SCREEN_WINDOW_CHANGED")
            }
            if (request.action.needsNode) {
                // This application's own settings UI is not an actionable target.
                if (snapshot.packageName == ownPackage) throw ScreenObservationException("SCREEN_ACTION_OWN_APP")
                val target = snapshot.targets[request.nodeIndex] ?: throw ScreenObservationException("SCREEN_ACTION_TARGET_UNAVAILABLE")
                for (child in target.path) {
                    checkVisible(node, snapshot.packageName)
                    val next = node.getChild(child) ?: throw ScreenObservationException("SCREEN_ACTION_OBSERVE_AGAIN")
                    recycle(node)
                    node = next
                }
                checkVisible(node, snapshot.packageName)
                val bounds = Rect().also(node::getBoundsInScreen)
                val before = target.observed
                if (node.windowId != snapshot.windowId || !node.isEnabled ||
                    node.text?.toString().orEmpty().take(240) != before.text ||
                    node.contentDescription?.toString().orEmpty().take(240) != before.description ||
                    node.className?.toString().orEmpty().take(240) != before.role ||
                    listOf(bounds.left, bounds.top, bounds.right, bounds.bottom) != before.bounds) {
                    throw ScreenObservationException("SCREEN_ACTION_OBSERVE_AGAIN")
                }
                if (request.action.wireName !in before.actions || request.action.wireName !in nodeActions(node)) {
                    throw ScreenObservationException("SCREEN_ACTION_UNSUPPORTED")
                }
            }
            if (!authorized()) throw ScreenObservationException("SCREEN_ACTION_ACCESS_CHANGED")
            currentCoroutineContext().ensureActive()
            val currentRoot = snapshot.service.rootInActiveWindow ?: throw ScreenObservationException("SCREEN_WINDOW_UNAVAILABLE")
            try {
                if (currentRoot.windowId != snapshot.windowId || currentRoot.packageName?.toString() != snapshot.packageName) {
                    throw ScreenObservationException("SCREEN_WINDOW_CHANGED")
                }
            } finally { recycle(currentRoot) }
            synchronized(lock) {
                if (actionSnapshot !== snapshot || mutableAccess.value != snapshot.permission || service !== snapshot.service) {
                    throw ScreenObservationException("SCREEN_ACTION_OBSERVE_AGAIN")
                }
                actionSnapshot = null // Consume before dispatch; a second call cannot repeat this target.
            }
            onDispatch()
            when (request.action) {
                ScreenAction.BACK -> snapshot.service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                ScreenAction.HOME -> snapshot.service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                ScreenAction.RECENTS -> snapshot.service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                ScreenAction.SET_TEXT -> node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.text) })
                else -> node.performAction(NODE_ACTIONS.getValue(request.action))
            }
        } finally { recycle(node) }
    }

    private fun checkVisible(node: AccessibilityNodeInfo, expectedPackage: String) {
        if (!node.isVisibleToUser || node.isPassword || node.packageName?.toString() != expectedPackage) {
            throw ScreenObservationException("SCREEN_ACTION_TARGET_UNAVAILABLE")
        }
    }

    private fun nodeActions(node: AccessibilityNodeInfo): List<String> = if (!node.isEnabled) emptyList() else
        NODE_ACTIONS.filterValues { id -> node.actionList.any { it.id == id } }.keys.map { it.wireName }

    private data class NodeTarget(val path: List<Int>, val observed: ScreenNode)
    private data class ActionSnapshot(val id: String, val packageName: String, val windowId: Int,
        val permission: ScreenAccess, val service: ScreenObservationService, val targets: Map<Int, NodeTarget>)
    private data class PendingNode(val node: AccessibilityNodeInfo, val parent: Int?, val depth: Int, val path: List<Int> = emptyList())
    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }

    companion object {
        private val NODE_ACTIONS = linkedMapOf(ScreenAction.CLICK to AccessibilityNodeInfo.ACTION_CLICK,
            ScreenAction.LONG_CLICK to AccessibilityNodeInfo.ACTION_LONG_CLICK,
            ScreenAction.SET_TEXT to AccessibilityNodeInfo.ACTION_SET_TEXT,
            ScreenAction.SCROLL_FORWARD to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            ScreenAction.SCROLL_BACKWARD to AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        @Volatile private var instance: ScreenObservationRegistry? = null
        fun get(context: Context): ScreenObservationRegistry = instance ?: synchronized(this) {
            instance ?: ScreenObservationRegistry(context).also { instance = it }
        }
    }
}

/** System-bound observation and explicitly enabled control assistance; never changes permissions. */
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
