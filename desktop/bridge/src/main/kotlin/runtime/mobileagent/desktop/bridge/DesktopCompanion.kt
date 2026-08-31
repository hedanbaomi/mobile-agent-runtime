// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import runtime.mobileagent.bridge.BridgeIdentity
import runtime.mobileagent.bridge.BridgePairingServer
import runtime.mobileagent.bridge.BridgePairingServerPending
import runtime.mobileagent.bridge.BridgePairCommitAck
import runtime.mobileagent.bridge.BridgePairResponse
import runtime.mobileagent.bridge.BridgePairStart
import runtime.mobileagent.bridge.BridgeTrustMaterial
import runtime.mobileagent.bridge.PairingTokenManager

fun interface CompanionConnectionHandler {
    fun handle(socket: Socket, companion: DesktopCompanion)
}

enum class CompanionState {
    STOPPED,
    STARTING,
    READY,
    DEGRADED,
}

data class CompanionStatus(
    val state: CompanionState,
    val serial: String,
    val serialFingerprintHex: String,
    val trusted: Boolean,
    val endpoint: LoopbackEndpoint?,
    val lastError: String? = null,
)

/**
 * Windows-first wired companion coordinator.  It owns one explicit serial,
 * one configured adb.exe and one loopback endpoint; no request can override
 * those values.
 */
class DesktopCompanion(
    private val adbPath: Path,
    private val serial: String,
    private val desktopId: String?,
    private val appInstanceId: String?,
    private val devicePort: Int,
    private val trustStore: DesktopTrustStore,
    private val runner: ProcessRunner,
    private val winTrustVerifier: WinTrustVerifier = JnaWinTrustVerifier(),
    private val connectionHandler: CompanionConnectionHandler,
    private val desktopIdentityStore: DesktopIdentityStore = InMemoryDesktopIdentityStore(desktopId),
) : AutoCloseable {
    private val state = AtomicReference(CompanionState.STOPPED)
    private val pairingTokens = PairingTokenManager()
    private val generation = AtomicLong(0)
    private val activeConnections = ConcurrentHashMap.newKeySet<DesktopAuthenticatedConnection>()
    private val pendingSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var failClosed = false
    @Volatile private var closed = false
    private var loopback: LoopbackBridgeServer? = null
    private var adb: AdbProcessManager? = null
    private var reverse: AdbReverseManager? = null
    private var identity: BridgeIdentity? = null
    private var stableDesktopId: String? = null
    private var doctorReport: AdbDoctorReport? = null
    private var lastError: String? = null

    @Synchronized
    fun start(): LoopbackEndpoint {
        check(!closed) { "companion is closed" }
        check(state.get() == CompanionState.STOPPED) { "companion is already running" }
        check(!failClosed) { "companion is fail-closed" }
        state.set(CompanionState.STARTING)
        var listener: LoopbackBridgeServer? = null
        var selectedReverse: AdbReverseManager? = null
        try {
            val selectedDesktopId = resolveDesktopId()
            listener = LoopbackBridgeServer(LoopbackConnectionHandler { socket ->
                connectionHandler.handle(socket, this)
            })
            val configuration = AdbConfiguration.create(
                adbPath = adbPath,
                serial = serial,
                devicePort = devicePort,
                hostPort = listener!!.endpoint.port,
            )
            val selectedIdentity = appInstanceId?.let {
                BridgeIdentity.forSerial(selectedDesktopId, it, serial)
            }
            // Validate/canonicalize the executable, publisher signature and
            // file identity before the first ADB child is spawned.
            val doctor = AdbDoctor(configuration, runner, winTrustVerifier)
            val report = doctor.inspect()
            val selectedAdb = AdbProcessManager.validated(configuration, runner, report, winTrustVerifier)
            val devices = selectedAdb.devices().process
            require(devices.outcome == ProcessOutcome.COMPLETE && devices.exitCode == 0) {
                "adb devices failed"
            }
            AdbDevicesParser.selectExplicit(AdbDevicesParser.parse(devices.stdout.toUtf8Strict()), serial)
            selectedIdentity?.let { configuredIdentity ->
                val existing = trustStore.load(configuredIdentity)
                existing?.use {
                    doctor.requireTrustHash(it, report)
                }
            }
            val localReverse = AdbReverseManager(selectedAdb)
            // Keep ownership before ensure(): a partially successful reverse
            // must still be removable on any subsequent validation failure.
            selectedReverse = localReverse
            localReverse.ensure()
            loopback = listener
            adb = selectedAdb
            reverse = selectedReverse
            identity = selectedIdentity
            doctorReport = report
            state.set(CompanionState.READY)
            return listener.endpoint
        } catch (error: Exception) {
            lastError = error.message ?: error::class.java.simpleName
            state.set(CompanionState.DEGRADED)
            runCatching { selectedReverse?.removeOwn() }
            loopback?.close()
            listener?.close()
            loopback = null
            state.set(CompanionState.STOPPED)
            throw error
        }
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        pairingTokens.revokeAll()
        closeActiveConnectionsLocked()
        reverse?.let { runCatching { it.removeOwn() } }
        loopback?.close()
        loopback = null
        adb = null
        reverse = null
        doctorReport = null
        state.set(CompanionState.STOPPED)
    }

    /** Disable transport and revoke in-flight pairing/session state, retaining trust. */
    @Synchronized
    fun disable() = stop()

    fun status(): CompanionStatus {
        val currentIdentity = identity ?: appInstanceId?.let {
            BridgeIdentity.forSerial(resolveDesktopId(), it, serial)
        }
        val serialFingerprint = if (currentIdentity != null) {
            currentIdentity.serialFingerprint
        } else {
            BridgeIdentity.forSerial(resolveDesktopId(), "status", serial).serialFingerprint
        }
        val trusted = if (failClosed) false else runCatching {
            currentIdentity?.let { trustStore.load(it)?.let { record -> record.close(); true } } ?: false
        }.getOrDefault(false)
        return CompanionStatus(
            state.get(),
            serial,
            runtime.mobileagent.bridge.BridgeEncoding.hex(serialFingerprint),
            trusted,
            loopback?.endpoint,
            lastError,
        )
    }

    /**
     * Registers the token displayed by the Android foreground pairing prompt.
     * Desktop never issues or replaces the App-owned one-time token.
     */
    fun registerPairingToken(token: ByteArray, expiresAtMillis: Long) {
        check(!closed && !failClosed) { "companion is fail-closed" }
        check(state.get() == CompanionState.READY) { "companion is not ready" }
        pairingTokens.register(token, expiresAtMillis)
    }

    fun beginPairing(start: BridgePairStart): BridgePairingServerPending {
        check(!closed && !failClosed) { "companion is fail-closed" }
        check(state.get() == CompanionState.READY) { "companion is not ready" }
        val serialFingerprint = BridgeIdentity.forSerial(resolveDesktopId(), "pairing", serial).serialFingerprint.copyOf()
        return try {
            BridgePairingServer(
                desktopId = resolveDesktopId(),
                serialFingerprint = serialFingerprint,
                tokenManager = pairingTokens,
                expectedAppInstanceId = appInstanceId,
            ).begin(start)
        } finally {
            java.util.Arrays.fill(serialFingerprint, 0)
        }
    }

    /** Persist only after the client has returned a verified commit acknowledgement. */
    fun finishPairing(
        pending: BridgePairingServerPending,
        response: BridgePairResponse,
    ): runtime.mobileagent.bridge.BridgePairFinished = pending.acceptResponse(response)

    @Synchronized
    fun commitPairing(
        pending: BridgePairingServerPending,
        ack: BridgePairCommitAck,
    ): BridgeTrustMaterial {
        // The client must have durably persisted its verified material before
        // this acknowledgement reaches the server.
        check(!closed && !failClosed && state.get() == CompanionState.READY) { "companion is not ready" }
        val report = doctorReport ?: throw IllegalStateException("companion is not running")
        val material = pending.prepareCommit(ack)
        val adbHash = runtime.mobileagent.bridge.BridgeEncoding.unhex(report.sha256Hex)
        val trustCopy = material.copyPersistentTrust()
        var record: DesktopTrustRecord? = null
        try {
            record = DesktopTrustRecord(
                material.identity,
                report.canonicalPath.toString(),
                adbHash,
                material.transcriptHash,
                runtime.mobileagent.bridge.SecretBytes.from(trustCopy),
            )
            trustStore.save(record!!)
            // Token consumption is the final operation, after trust is durable.
            pending.commitPrepared()
            identity = material.identity
            return material
        } catch (error: Exception) {
            if (record != null) {
                // A durable record exists but token finalization failed. Stop
                // accepting further sessions until the user explicitly
                // forgets/retries; never silently create a second trust.
                failClosed = true
                state.set(CompanionState.DEGRADED)
                lastError = "pairing finalization failed"
            }
            material.close()
            throw error
        } finally {
            record?.close()
            java.util.Arrays.fill(adbHash, 0)
            java.util.Arrays.fill(trustCopy, 0)
        }
    }

    @Synchronized
    fun forget() {
        check(!closed) { "companion is closed" }
        val currentIdentity = identity ?: appInstanceId?.let {
            BridgeIdentity.forSerial(resolveDesktopId(), it, serial)
        } ?: throw IllegalStateException("app instance identity is required to forget trust")
        generation.incrementAndGet()
        pairingTokens.revokeAll()
        closeActiveConnectionsLocked()
        try {
            trustStore.forget(currentIdentity)
            failClosed = false
            lastError = null
        } catch (error: Exception) {
            // The deletion result is unknown: do not allow any new session or
            // pairing until the user explicitly retries and deletion succeeds.
            failClosed = true
            lastError = error.message ?: "desktop trust deletion failed"
            state.set(CompanionState.DEGRADED)
            throw error
        }
    }

    fun shellExecutor(): WiredAdbShellExecutor {
        check(!closed && !failClosed && state.get() == CompanionState.READY) { "companion is not ready" }
        return WiredAdbShellExecutor(adb ?: error("companion is not running"))
    }

    /** Returns the typed helper executor bound to this companion's fixed ADB serial. */
    fun typedFileExecutor(): WiredAdbTypedFileExecutor {
        check(!closed && !failClosed && state.get() == CompanionState.READY) { "companion is not ready" }
        return WiredAdbTypedFileExecutorImpl(adb ?: error("companion is not running"))
    }

    /** Authenticate a loopback peer using this selected serial's persisted trust. */
    fun acceptAuthenticated(socket: Socket, readTimeoutMs: Int = 60_000): DesktopAuthenticatedConnection {
        val acceptedGeneration: Long
        val currentIdentity: BridgeIdentity
        synchronized(this) {
            check(!closed && !failClosed && state.get() == CompanionState.READY) { "companion is not ready" }
            acceptedGeneration = generation.get()
            currentIdentity = identity ?: appInstanceId?.let {
                BridgeIdentity.forSerial(resolveDesktopId(), it, serial)
            } ?: throw IllegalStateException("desktop trust is not established")
            // Authentication reads an untrusted socket and can block until
            // its deadline. Track it outside the monitor so forget/disable
            // can close it and advance generation immediately.
            pendingSockets += socket
        }
        var record: DesktopTrustRecord? = null
        val connection = try {
            val loaded = trustStore.load(currentIdentity)
                ?: throw IllegalStateException("desktop trust is not established")
            record = loaded
            DesktopAuthenticatedConnection.accept(socket, loaded, readTimeoutMs)
        } finally {
            record?.close()
            pendingSockets -= socket
        }
        synchronized(this) {
            if (acceptedGeneration != generation.get() || failClosed || state.get() != CompanionState.READY) {
                connection.close()
                throw IllegalStateException("bridge was disabled during authentication")
            }
            activeConnections += connection
            connection.onClosed = { activeConnections -= connection }
            return connection
        }
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        pairingTokens.close()
        (connectionHandler as? AutoCloseable)?.close()
    }

    @Synchronized
    private fun closeActiveConnectionsLocked() {
        loopback?.closeActiveConnections()
        pendingSockets.toList().forEach { runCatching { it.close() } }
        pendingSockets.clear()
        activeConnections.toList().forEach { runCatching { it.close() } }
        activeConnections.clear()
    }

    @Synchronized
    private fun resolveDesktopId(): String {
        stableDesktopId?.let { return it }
        val selected = desktopIdentityStore.loadOrCreate()
        require(selected.isNotBlank()) { "desktop identity store returned an empty id" }
        desktopId?.let { configured ->
            require(configured == selected) { "configured desktop identity does not match the stored identity" }
        }
        stableDesktopId = selected
        return selected
    }
}

private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R = try {
    block(this)
} finally {
    close()
}
