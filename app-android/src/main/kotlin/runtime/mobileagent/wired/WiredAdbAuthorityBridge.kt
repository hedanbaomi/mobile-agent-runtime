// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import runtime.mobileagent.bridge.BridgeAuthenticationException
import runtime.mobileagent.bridge.BridgeFrameType
import runtime.mobileagent.bridge.BridgePairingClient
import runtime.mobileagent.bridge.BridgeProtocolException
import runtime.mobileagent.bridge.BridgeRequestState
import runtime.mobileagent.bridge.BridgeSession
import runtime.mobileagent.bridge.BridgeSequenceException
import runtime.mobileagent.bridge.BridgeProtocol

/**
 * Android client for the single wired ADB authority.
 *
 * The implementation has no host/port/path/serial request inputs and no
 * fallback authority. Pairing and session wire bytes are owned exclusively by
 * shared:bridge-protocol; this class only adapts Android lifecycle, storage,
 * and the fixed reverse channel.
 */
class WiredAdbAuthorityBridge internal constructor(
    private val appInstanceId: String,
    private val trustStore: WiredAdbTrustStore,
    private val secretStore: WiredAdbBoundSecretStore,
    private val intentStore: WiredAdbIntentStore = InMemoryWiredAdbIntentStore(),
    private val connector: WiredAdbLoopbackConnector = FixedLoopbackConnector(),
    private val sessionFactory: WiredAdbSessionFactory = DEFAULT_WIRED_SESSION_FACTORY,
    private val clock: WiredAdbClock = DEFAULT_WIRED_CLOCK,
    private val random: WiredAdbRandom = DEFAULT_WIRED_RANDOM,
    private val diagnostics: WiredAdbDiagnosticSink = NOOP_WIRED_DIAGNOSTICS,
    private val shellPermission: () -> Boolean = { false },
    /** Test-only scheduling seam; the production factory uses the no-op default. */
    private val beforeRequestPublish: () -> Unit = {},
) : WiredAdbAuthorityPort, WiredAdbWorkspacePort, WiredAdbShellPort {
    private val stateLock = Any()
    /** One response reader at a time; a long read does not block cancellation writes. */
    private val exchangeLock = Mutex()
    /** Every frame write and shared-session sequence allocation is atomic. */
    private val writeLock = Mutex()
    /** Pair/connect/forget are serialized; synchronous invalidations bump the epoch. */
    private val lifecycleLock = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    /** Request IDs are one-shot tombstones, including UNKNOWN_OUTCOME requests. */
    private val requestTombstones = ConcurrentHashMap.newKeySet<String>()
    private var trustLoadFailed = false
    private var intentPersistenceFailed = false
    private var trustRecord: WiredAdbTrustRecord? = try {
        trustStore.load()
    } catch (_: Throwable) {
        // A persistence failure is not equivalent to an unpaired device.
        // Keep the bridge fail-closed until the user explicitly forgets it.
        trustLoadFailed = true
        null
    }
    private var channel: WiredAdbChannel? = null
    /** The short-lived pairing channel is tracked so foreground cancellation can interrupt a read. */
    private var pairingChannel: WiredAdbChannel? = null
    private var session: BridgeSession? = null
    private var pendingPairing: PendingPairing? = null
    private var shellInFlight = false
    private var closed = false
    private var lifecycleEpoch = 0L

    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<WiredAdbStatus> = _status.asStateFlow()

    override val workspace: WiredAdbWorkspacePort
        get() = this

    override val shell: WiredAdbShellPort
        get() = this

    init {
        requireIdentityPart(appInstanceId, "appInstanceId")
        synchronized(stateLock) {
            val loaded = trustRecord
            if (trustLoadFailed) {
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.INTERNAL_ERROR)
            } else if (loaded != null && !bindingMatches(loaded)) {
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.BRIDGE_BINDING_MISMATCH)
            } else if (loaded != null) {
                publishLocked(WiredAdbLifecycleState.TRUSTED, null)
            }
        }
    }

    /** User intent is persistent and independent of current transport availability. */
    override fun setUserIntent(enabled: Boolean) {
        synchronized(stateLock) {
            if (closed) return
            val next = if (enabled) WiredAdbUserIntent.ENABLED else WiredAdbUserIntent.DISABLED
            // Invalidate every in-flight suspend operation before touching
            // persistence. A failed write must never be represented as a
            // successful policy transition.
            lifecycleEpoch++
            val saved = runCatching { intentStore.save(next) }.isSuccess
            if (!saved) {
                intentPersistenceFailed = true
                closeSessionLocked()
                clearPendingPairingLocked()
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.INTERNAL_ERROR)
                return
            }
            intentPersistenceFailed = false
            if (!enabled) {
                closeSessionLocked()
                clearPendingPairingLocked()
                publishLocked(
                    if (trustRecord == null) WiredAdbLifecycleState.UNPAIRED else WiredAdbLifecycleState.TRUSTED,
                    WiredAdbErrorCode.AUTHORITY_USER_DISABLED,
                )
            } else {
                publishLocked(
                    when {
                        trustLoadFailed -> WiredAdbLifecycleState.REAUTH_REQUIRED
                        trustRecord == null -> WiredAdbLifecycleState.UNPAIRED
                        bindingMatches(trustRecord!!) -> WiredAdbLifecycleState.TRUSTED
                        else -> WiredAdbLifecycleState.REAUTH_REQUIRED
                    },
                    null,
                )
            }
        }
    }

    /** Generates a CSPRNG 256-bit token only from a foreground UI action. */
    override fun requestPairingFromForeground(replaceExistingTrust: Boolean): WiredAdbResult<WiredAdbPairingPrompt> {
        synchronized(stateLock) {
            if (closed) return WiredAdbResult.Failure(WiredAdbErrorCode.BRIDGE_DISCONNECTED)
            if (trustLoadFailed || intentPersistenceFailed) return WiredAdbResult.Failure(WiredAdbErrorCode.INTERNAL_ERROR)
            if (trustRecord != null && !replaceExistingTrust) {
                return WiredAdbResult.Failure(WiredAdbErrorCode.PAIRING_REJECTED)
            }
            // A new foreground pairing request invalidates a previous
            // pairing/connect attempt and closes any old session. The token
            // itself remains memory-only until the caller completes pairing.
            lifecycleEpoch++
            closeSessionLocked()
            clearPendingPairingLocked()
            val token = runCatching { random.nextBytes(PAIR_TOKEN_BYTES) }.getOrNull()
                ?: return WiredAdbResult.Failure(WiredAdbErrorCode.INTERNAL_ERROR)
            if (token.size != PAIR_TOKEN_BYTES) {
                token.fill(0)
                return WiredAdbResult.Failure(WiredAdbErrorCode.INTERNAL_ERROR)
            }
            val expires = clock.nowEpochMs() + PAIR_TOKEN_TTL_MS
            pendingPairing = PendingPairing(token, expires, attempts = 0)
            if (runCatching { intentStore.save(WiredAdbUserIntent.ENABLED) }.isFailure) {
                intentPersistenceFailed = true
                clearPendingPairingLocked()
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.INTERNAL_ERROR)
                return WiredAdbResult.Failure(WiredAdbErrorCode.INTERNAL_ERROR)
            }
            intentPersistenceFailed = false
            publishLocked(WiredAdbLifecycleState.PAIRING, null)
            return WiredAdbResult.Success(WiredAdbPairingPrompt(token.toHex(), expires, PAIR_MAX_ATTEMPTS))
        }
    }

    /** Convenience overload retained for same-module protocol tests. */
    fun requestPairingFromForeground(): WiredAdbResult<WiredAdbPairingPrompt> =
        requestPairingFromForeground(false)

    /**
     * Cancels only the foreground pairing exchange.  Existing durable trust is
     * intentionally retained so replacing trust is an explicit successful
     * operation, never an effect of dismissing the prompt.
     */
    override fun cancelPairing() {
        synchronized(stateLock) {
            if (closed) return
            lifecycleEpoch++
            closeSessionLocked()
            clearPendingPairingLocked()
            publishLocked(
                if (trustRecord == null) WiredAdbLifecycleState.UNPAIRED else WiredAdbLifecycleState.TRUSTED,
                null,
            )
        }
    }

    /** Performs the shared canonical binary-transcript pairing and commit-ack. */
    override suspend fun pair(): WiredAdbResult<WiredAdbTrustRecord> = lifecycleLock.withLock {
        pairInternal(null)
    }

    /** Internal token override is retained for protocol tests only. */
    internal suspend fun pair(token: ByteArray?): WiredAdbResult<WiredAdbTrustRecord> = lifecycleLock.withLock {
        pairInternal(token)
    }

    private suspend fun pairInternal(token: ByteArray?): WiredAdbResult<WiredAdbTrustRecord> {
        val presentedToken = token?.copyOf()
        val reservation = try {
            synchronized(stateLock) { reservePairAttemptLocked(presentedToken) }
        } finally {
            presentedToken?.fill(0)
        }
        when (reservation) {
            is PairReservation.Rejected -> return WiredAdbResult.Failure(reservation.code)
            PairReservation.Required -> return WiredAdbResult.Failure(WiredAdbErrorCode.PAIRING_REQUIRED)
            is PairReservation.Accepted -> Unit
        }
        val accepted = reservation as PairReservation.Accepted
        val operationEpoch = accepted.epoch
        var localChannel: WiredAdbChannel? = null
        var client: BridgePairingClient? = null
        var commitMaterial: runtime.mobileagent.bridge.BridgePairingClientCommit? = null
        var newSecretRef: String? = null
        var persistedRecord: WiredAdbTrustRecord? = null
        var trustPersistenceAttempted = false
        return try {
            client = WiredAdbSharedAdapter.newPairingClient(
                appInstanceId,
                accepted.token,
                clock.nowEpochMs(),
                accepted.expiresAtEpochMs,
            )
            revalidateEpoch(operationEpoch)
            publishForEpoch(operationEpoch, WiredAdbLifecycleState.CONNECTING, null)
            localChannel = withContext(Dispatchers.IO) {
                connector.connect(WIRED_ADB_LOOPBACK_ADDRESS, WIRED_ADB_LOOPBACK_PORT)
            }
            synchronized(stateLock) {
                if (!isEpochCurrentLocked(operationEpoch)) throw LifecycleInvalidatedException()
                pairingChannel = localChannel
            }
            publishForEpoch(operationEpoch, WiredAdbLifecycleState.AUTHENTICATING, null)
            withContext(Dispatchers.IO) {
                localChannel!!.writeFrame(WiredAdbSharedAdapter.encodePairStart(client!!))
                localChannel!!.setReadDeadline(System.currentTimeMillis() + WIRED_ADB_HANDSHAKE_TIMEOUT_MS)
                val challenge = WiredAdbSharedAdapter.decodePairChallenge(localChannel!!.readFrame())
                val response = client!!.acceptChallenge(challenge)
                localChannel!!.writeFrame(WiredAdbSharedAdapter.encodePairResponse(response))
                localChannel!!.setReadDeadline(System.currentTimeMillis() + WIRED_ADB_HANDSHAKE_TIMEOUT_MS)
                val finished = WiredAdbSharedAdapter.decodePairFinished(localChannel!!.readFrame())
                commitMaterial = client!!.acceptFinished(finished)
            }
            revalidateEpoch(operationEpoch)

            val material = commitMaterial ?: throw BridgeProtocolException("pairing commit is missing")
            // The desktop identity is learned only after the shared challenge
            // and finished proof have been authenticated.  Never construct a
            // first-pair trust record from a preselected serial/desktop value.
            val identity = material.trustMaterial.identity
            val secret = material.trustMaterial.copyPersistentTrust()
            val transcriptHash = material.trustMaterial.transcriptHash.copyOf()
            newSecretRef = newSecretRef()
            val record = WiredAdbTrustRecord(
                desktopId = identity.desktopId,
                appInstanceId = identity.appInstanceId,
                serialFingerprint = runtime.mobileagent.bridge.BridgeEncoding.hex(identity.serialFingerprint),
                protocolVersion = WIRED_ADB_PROTOCOL_VERSION,
                secretRef = newSecretRef!!,
                transcriptHash = transcriptHash.toHex(),
            )
            try {
                revalidateEpoch(operationEpoch)
                trustPersistenceAttempted = true
                storeSecret(record, secret)
                revalidateEpoch(operationEpoch)
                trustStore.save(record)
                persistedRecord = record
                trustPersistenceAttempted = false
                // A synchronous store can race setUserIntent()/disconnect();
                // if it did, retain the persisted record but never activate it.
                revalidateEpoch(operationEpoch)
            } catch (error: Throwable) {
                val cleanupError = runCatching { secretStore.remove(record.secretRef) }.exceptionOrNull()
                if (cleanupError != null) throw cleanupError
                throw error
            } finally {
                secret.fill(0)
                transcriptHash.fill(0)
            }

            // Persisted trust must precede this shared commit acknowledgement.
            revalidateEpoch(operationEpoch)
            withContext(Dispatchers.IO) {
                localChannel!!.writeFrame(WiredAdbSharedAdapter.encodePairCommitAck(material.ackAfterPersisted()))
            }
            // A commit-ack write can be observed remotely even if this side
            // is concurrently disabled. Revalidate before publishing trust.
            revalidateEpoch(operationEpoch)
            val previous = synchronized(stateLock) {
                if (!isEpochCurrentLocked(operationEpoch)) throw LifecycleInvalidatedException()
                val old = trustRecord
                trustRecord = record
                clearPendingPairingLocked()
                publishLocked(WiredAdbLifecycleState.TRUSTED, null)
                old
            }
            if (previous != null && previous.secretRef != record.secretRef) {
                try {
                    secretStore.remove(previous.secretRef)
                } catch (_: Throwable) {
                    // New trust is already acknowledged by the peer. Keep it
                    // staged and require explicit re-authentication rather
                    // than reporting a successful replacement with unknown
                    // secret lifecycle.
                    synchronized(stateLock) {
                        publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.INTERNAL_ERROR)
                    }
                    return WiredAdbResult.Failure(WiredAdbErrorCode.INTERNAL_ERROR)
                }
            }
            WiredAdbResult.Success(record)
        } catch (_: LifecycleInvalidatedException) {
            val persisted = persistedRecord
            synchronized(stateLock) {
                if (persisted != null) trustRecord = persisted
                clearPendingPairingLocked()
                if (!closed) publishLocked(invalidationStateLocked(), WiredAdbErrorCode.UNKNOWN_OUTCOME)
            }
            WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME)
        } catch (error: BridgeAuthenticationException) {
            failPairing(WiredAdbErrorCode.BRIDGE_AUTH_FAILED, operationEpoch)
        } catch (error: BridgeProtocolException) {
            failPairing(if (isProtocolVersionError(error)) WiredAdbErrorCode.BRIDGE_PROTOCOL_MISMATCH else WiredAdbErrorCode.PAIRING_REJECTED, operationEpoch)
        } catch (error: IllegalArgumentException) {
            // Shared codec validators use require() for malformed control
            // values; classify them as a protocol rejection, never dispatch.
            failPairing(if (isProtocolVersionError(error)) WiredAdbErrorCode.BRIDGE_PROTOCOL_MISMATCH else WiredAdbErrorCode.PAIRING_REJECTED, operationEpoch)
        } catch (error: Throwable) {
            val persisted = persistedRecord
            if (trustPersistenceAttempted) {
                failTrustPersistence()
            } else if (persisted == null) {
                failPairing(WiredAdbErrorCode.UNKNOWN_OUTCOME, operationEpoch)
            } else {
                // The commit write may have reached the desktop. Preserve the
                // staged trust, but require an explicit re-authentication.
                synchronized(stateLock) {
                    trustRecord = persisted
                    clearPendingPairingLocked()
                    publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.UNKNOWN_OUTCOME)
                }
                WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME)
            }
        } finally {
            synchronized(stateLock) {
                if (pairingChannel === localChannel) pairingChannel = null
            }
            runCatching { localChannel?.close() }
            runCatching { commitMaterial?.close() }
            runCatching { client?.close() }
            accepted.token.fill(0)
            newSecretRef = null
        }
    }

    /** Authenticates a fresh session with persisted trust; no automatic fallback exists. */
    override suspend fun connect(): WiredAdbResult<Unit> = lifecycleLock.withLock {
        connectInternal()
    }

    private suspend fun connectInternal(): WiredAdbResult<Unit> {
        val selection = synchronized(stateLock) {
            if (closed) return@synchronized ConnectSelection.Rejected(WiredAdbErrorCode.BRIDGE_DISCONNECTED)
            if (trustLoadFailed || intentPersistenceFailed) {
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.INTERNAL_ERROR)
                return@synchronized ConnectSelection.Rejected(WiredAdbErrorCode.INTERNAL_ERROR)
            }
            val intent = try {
                intentStore.load()
            } catch (_: Throwable) {
                intentPersistenceFailed = true
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.INTERNAL_ERROR)
                return@synchronized ConnectSelection.Rejected(WiredAdbErrorCode.INTERNAL_ERROR)
            }
            if (intent != WiredAdbUserIntent.ENABLED) {
                publishLocked(
                    if (trustRecord == null) WiredAdbLifecycleState.UNPAIRED else WiredAdbLifecycleState.TRUSTED,
                    WiredAdbErrorCode.AUTHORITY_USER_DISABLED,
                )
                return@synchronized ConnectSelection.Rejected(WiredAdbErrorCode.AUTHORITY_USER_DISABLED)
            }
            // Reconnect is always based on the durable trust record, not a
            // stale in-memory identity from a previous process/lifecycle.
            val persisted = try {
                trustStore.load()
            } catch (_: Throwable) {
                trustLoadFailed = true
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.INTERNAL_ERROR)
                return@synchronized ConnectSelection.Rejected(WiredAdbErrorCode.INTERNAL_ERROR)
            }
            trustRecord = persisted
            val current = trustRecord
            if (current == null) {
                publishLocked(WiredAdbLifecycleState.UNPAIRED, WiredAdbErrorCode.BRIDGE_NOT_PAIRED)
                return@synchronized ConnectSelection.Rejected(WiredAdbErrorCode.BRIDGE_NOT_PAIRED)
            }
            if (!bindingMatches(current)) {
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.BRIDGE_BINDING_MISMATCH)
                return@synchronized ConnectSelection.Rejected(WiredAdbErrorCode.BRIDGE_BINDING_MISMATCH)
            }
            if (session != null) return@synchronized ConnectSelection.Rejected(WiredAdbErrorCode.BRIDGE_ALREADY_CONNECTED)
            ConnectSelection.Accepted(current, lifecycleEpoch)
        }
        val accepted = when (selection) {
            is ConnectSelection.Accepted -> selection
            is ConnectSelection.Rejected -> return WiredAdbResult.Failure(selection.code)
        }
        val record = accepted.record
        val operationEpoch = accepted.epoch

        val transcriptHash = try {
            revalidateEpoch(operationEpoch)
            decodeFingerprint(record.transcriptHash)
        } catch (_: LifecycleInvalidatedException) {
            synchronized(stateLock) {
                if (!closed) publishLocked(invalidationStateLocked(), WiredAdbErrorCode.UNKNOWN_OUTCOME)
            }
            return WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME)
        } catch (_: Throwable) {
            return authFailure(WiredAdbErrorCode.BRIDGE_SECRET_UNAVAILABLE)
        }
        val trustSecret = try {
            resolveSecret(record).also { revalidateEpoch(operationEpoch) }
        } catch (_: LifecycleInvalidatedException) {
            transcriptHash.fill(0)
            synchronized(stateLock) {
                if (!closed) publishLocked(invalidationStateLocked(), WiredAdbErrorCode.UNKNOWN_OUTCOME)
            }
            return WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME)
        } catch (_: Throwable) {
            transcriptHash.fill(0)
            return authFailure(WiredAdbErrorCode.BRIDGE_SECRET_UNAVAILABLE)
        } ?: run {
            transcriptHash.fill(0)
            return authFailure(WiredAdbErrorCode.BRIDGE_SECRET_UNAVAILABLE)
        }

        var localChannel: WiredAdbChannel? = null
        var localSession: BridgeSession? = null
        return try {
            publishForEpoch(operationEpoch, WiredAdbLifecycleState.CONNECTING, null)
            localChannel = withContext(Dispatchers.IO) {
                connector.connect(WIRED_ADB_LOOPBACK_ADDRESS, WIRED_ADB_LOOPBACK_PORT)
            }
            revalidateEpoch(operationEpoch)
            publishForEpoch(operationEpoch, WiredAdbLifecycleState.AUTHENTICATING, null)
            val hello = WiredAdbSharedAdapter.newSessionHello()
            val welcome = withContext(Dispatchers.IO) {
                localChannel!!.writeFrame(WiredAdbSharedAdapter.encodeSessionHello(hello))
                localChannel!!.setReadDeadline(System.currentTimeMillis() + WIRED_ADB_HANDSHAKE_TIMEOUT_MS)
                WiredAdbSharedAdapter.decodeSessionWelcome(localChannel!!.readFrame())
            }
            revalidateEpoch(operationEpoch)
            localSession = sessionFactory.complete(hello, welcome, trustSecret, transcriptHash)
            revalidateEpoch(operationEpoch)
            synchronized(stateLock) {
                if (!isEpochCurrentLocked(operationEpoch) || trustRecord?.secretRef != record.secretRef) {
                    throw LifecycleInvalidatedException()
                }
                channel = localChannel
                session = localSession
                localChannel = null
                localSession = null
                publishLocked(WiredAdbLifecycleState.READY, null)
            }
            WiredAdbResult.Success(Unit)
        } catch (_: LifecycleInvalidatedException) {
            synchronized(stateLock) {
                if (!closed) publishLocked(invalidationStateLocked(), WiredAdbErrorCode.UNKNOWN_OUTCOME)
            }
            WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME)
        } catch (_: BridgeAuthenticationException) {
            synchronized(stateLock) {
                closeSessionLocked()
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.BRIDGE_AUTH_FAILED)
            }
            WiredAdbResult.Failure(WiredAdbErrorCode.BRIDGE_AUTH_FAILED)
        } catch (error: BridgeProtocolException) {
            val code = if (isProtocolVersionError(error)) WiredAdbErrorCode.BRIDGE_PROTOCOL_MISMATCH else WiredAdbErrorCode.PROTOCOL_FRAME_INVALID
            synchronized(stateLock) {
                closeSessionLocked()
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, code)
            }
            WiredAdbResult.Failure(code)
        } catch (error: IllegalArgumentException) {
            val code = if (isProtocolVersionError(error)) WiredAdbErrorCode.BRIDGE_PROTOCOL_MISMATCH else WiredAdbErrorCode.PROTOCOL_FRAME_INVALID
            synchronized(stateLock) {
                closeSessionLocked()
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, code)
            }
            WiredAdbResult.Failure(code)
        } catch (_: IOException) {
            synchronized(stateLock) {
                closeSessionLocked()
                publishLocked(WiredAdbLifecycleState.DISCONNECTED, WiredAdbErrorCode.UNKNOWN_OUTCOME)
            }
            WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME)
        } catch (_: Throwable) {
            synchronized(stateLock) {
                closeSessionLocked()
                publishLocked(WiredAdbLifecycleState.DISCONNECTED, WiredAdbErrorCode.UNKNOWN_OUTCOME)
            }
            WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME)
        } finally {
            runCatching { localSession?.close() }
            runCatching { localChannel?.close() }
            trustSecret.fill(0)
            transcriptHash.fill(0)
        }
    }

    /** Drops only session material. Trust and user intent are intentionally retained. */
    override fun disconnect() {
        synchronized(stateLock) {
            if (closed) return
            lifecycleEpoch++
            closeSessionLocked()
            publishLocked(
                if (trustRecord == null) WiredAdbLifecycleState.UNPAIRED else WiredAdbLifecycleState.DISCONNECTED,
                null,
            )
        }
    }

    /** Explicit destructive user action: forget trust and remove its Keystore secret. */
    override suspend fun forget() = lifecycleLock.withLock {
        val (old, operationEpoch) = synchronized(stateLock) {
            if (closed) return@withLock
            lifecycleEpoch++
            closeSessionLocked()
            clearPendingPairingLocked()
            Pair(trustRecord, lifecycleEpoch)
        }
        if (old != null) {
            try {
                secretStore.remove(old.secretRef)
            } catch (_: Throwable) {
                synchronized(stateLock) {
                    trustLoadFailed = true
                    if (!closed) publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.INTERNAL_ERROR)
                }
                return@withLock
            }
        }
        try {
            trustStore.clear()
        } catch (_: Throwable) {
            synchronized(stateLock) {
                trustLoadFailed = true
                if (!closed) publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.INTERNAL_ERROR)
            }
            return@withLock
        }
        synchronized(stateLock) {
            // A synchronous policy action may have invalidated the forget
            // while Keystore/DB work was suspended. Do not erase an unknown
            // newer trust record or claim UNPAIRED in that case.
            if (!isEpochCurrentLocked(operationEpoch) || closed) {
                if (!closed) publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.UNKNOWN_OUTCOME)
                return@withLock
            }
            trustRecord = null
            trustLoadFailed = false
            intentPersistenceFailed = false
            publishLocked(WiredAdbLifecycleState.UNPAIRED, null)
        }
    }

    override fun newFileRequest(
        operation: WiredAdbFileOperation,
        relativePath: String?,
        destinationRelativePath: String?,
        contentUtf8: ByteArray?,
        replaceExisting: Boolean,
        maxBytes: Int,
    ): WiredAdbFileRequest = WiredAdbFileRequest(
        requestId = newWiredAdbRequestId(),
        operation = operation,
        relativePath = relativePath,
        destinationRelativePath = destinationRelativePath,
        contentUtf8 = contentUtf8?.copyOf(),
        replaceExisting = replaceExisting,
        maxBytes = maxBytes,
    )

    /** Convenience overload; cross-package callers use interface defaults. */
    fun newFileRequest(
        operation: WiredAdbFileOperation,
        relativePath: String?,
    ): WiredAdbFileRequest = newFileRequest(
        operation = operation,
        relativePath = relativePath,
        destinationRelativePath = null,
        contentUtf8 = null,
        replaceExisting = false,
        maxBytes = WIRED_DEFAULT_READ_BYTES,
    )

    override fun newShellRequest(
        command: String,
        cwd: String?,
        timeoutMs: Long,
        maxOutputBytes: Long,
    ): WiredAdbShellRequest = WiredAdbShellRequest(newWiredAdbRequestId(), command, cwd, timeoutMs, maxOutputBytes)

    /** Convenience overload; cross-package callers use interface defaults. */
    fun newShellRequest(command: String): WiredAdbShellRequest =
        newShellRequest(command, null, 30_000L, WIRED_ADB_MAX_SHELL_OUTPUT_BYTES)

    override suspend fun executeFile(request: WiredAdbFileRequest): WiredAdbResult<WiredAdbFileResult> {
        validateFileRequest(request)?.let { return WiredAdbResult.Failure(it) }
        if (!claimRequest(request.requestId)) return WiredAdbResult.Failure(WiredAdbErrorCode.REQUEST_INVALID)
        val started = clock.nowEpochMs()
        val outcome = try {
            val (operation, payload) = WiredAdbSharedAdapter.fileOperation(request)
            exchange(request.requestId, WIRED_ADB_FILE_READ_DEADLINE_MS) { active ->
                WiredAdbSharedAdapter.encodeRequest(active, request.requestId, operation, payload)
            }
        } catch (_: BridgeProtocolException) {
            protocolFailure(WiredAdbErrorCode.PROTOCOL_FRAME_INVALID)
        }
        val result = when (outcome) {
            is WiredAdbResult.Failure -> outcome
            is WiredAdbResult.Success -> try {
                WiredAdbResult.Success(WiredAdbSharedAdapter.decodeFileResult(outcome.value, request.operation))
            } catch (_: Throwable) {
                protocolFailure(WiredAdbErrorCode.PROTOCOL_FRAME_INVALID)
            }
        }
        emit(
            WiredAdbDiagnosticEvent(
                operation = "typed_file",
                outcome = if (result is WiredAdbResult.Success) "success" else "failure",
                error = (result as? WiredAdbResult.Failure)?.code,
                requestIdHash = request.requestId.safeHash(),
                durationMs = (clock.nowEpochMs() - started).coerceAtLeast(0),
                bytes = request.contentUtf8?.size?.toLong(),
            ),
        )
        return result
    }

    override suspend fun executeShell(request: WiredAdbShellRequest): WiredAdbResult<WiredAdbShellResult> {
        if (!shellPermission()) return WiredAdbResult.Failure(WiredAdbErrorCode.SHELL_CAPABILITY_DENIED)
        synchronized(stateLock) {
            if (shellInFlight) return WiredAdbResult.Failure(WiredAdbErrorCode.REQUEST_IN_FLIGHT)
            shellInFlight = true
        }
        if (!claimRequest(request.requestId)) {
            synchronized(stateLock) { shellInFlight = false }
            return WiredAdbResult.Failure(WiredAdbErrorCode.REQUEST_INVALID)
        }
        val started = clock.nowEpochMs()
        val outcome = try {
            val (operation, payload) = WiredAdbSharedAdapter.shellOperation(request)
            exchange(request.requestId, request.timeoutMs + WIRED_ADB_READ_DEADLINE_GRACE_MS) { active ->
                WiredAdbSharedAdapter.encodeRequest(active, request.requestId, operation, payload)
            }
        } catch (_: BridgeProtocolException) {
            protocolFailure(WiredAdbErrorCode.PROTOCOL_FRAME_INVALID)
        } finally {
            synchronized(stateLock) { shellInFlight = false }
        }
        val result = when (outcome) {
            is WiredAdbResult.Failure -> outcome
            is WiredAdbResult.Success -> try {
                WiredAdbResult.Success(WiredAdbSharedAdapter.decodeShellResult(outcome.value))
            } catch (_: Throwable) {
                protocolFailure(WiredAdbErrorCode.PROTOCOL_FRAME_INVALID)
            }
        }
        val shellResult = (result as? WiredAdbResult.Success)?.value
        emit(
            WiredAdbDiagnosticEvent(
                operation = "shell",
                outcome = when {
                    result is WiredAdbResult.Failure -> "failure"
                    shellResult?.timedOut == true -> "timeout"
                    shellResult?.cancelled == true -> "cancelled"
                    else -> "success"
                },
                error = (result as? WiredAdbResult.Failure)?.code,
                requestIdHash = request.requestId.safeHash(),
                durationMs = (clock.nowEpochMs() - started).coerceAtLeast(0),
                bytes = shellResult?.let { it.stdout.size.toLong() + it.stderr.size },
            ),
        )
        return result
    }

    /** Cancellation is a typed encrypted frame and uses the same serialized write path. */
    override suspend fun cancel(requestId: WiredAdbRequestId): WiredAdbResult<Unit> {
        val pending = pendingRequests[requestId.value]
            ?: return WiredAdbResult.Failure(WiredAdbErrorCode.REQUEST_INVALID)
        val disposition = try {
            writeLock.withLock cancelWrite@{
                val cancelRequestId: WiredAdbRequestId
                synchronized(pending) {
                    when (pending.state) {
                        RequestState.REQUEST_NOT_PUBLISHED -> {
                            // No request bytes have reached the channel. Keep
                            // the cancellation local and make exchange skip
                            // its later dispatch; sending CANCEL here would
                            // invert the wire order and poison the session.
                            pending.state = RequestState.CANCELLED_BEFORE_PUBLISH
                            return@cancelWrite CancelDisposition.LOCAL
                        }

                        RequestState.REQUEST_PUBLISHED -> {
                            cancelRequestId = newWiredAdbRequestId()
                            // Publish the association before writing. The
                            // response reader can then consume the peer's
                            // STATUS frames even when they race the write
                            // completion. A write failure closes the session
                            // below, so no unassociated frame can be admitted
                            // after this point.
                            pending.cancelRequestId = cancelRequestId.value
                            pending.cancelSent = true
                            pending.state = RequestState.CANCEL_SENT
                        }

                        else -> return@cancelWrite CancelDisposition.INACTIVE
                    }
                }
                val snapshot = synchronized(stateLock) { Pair(channel, session) }
                val active = snapshot.first ?: throw IOException("bridge is disconnected")
                val current = snapshot.second ?: throw IOException("bridge session is unavailable")
                val frame = WiredAdbSharedAdapter.encodeCancel(current, cancelRequestId, requestId)
                withContext(Dispatchers.IO) { active.writeFrame(frame) }
                CancelDisposition.SENT
            }
        } catch (_: Throwable) {
            synchronized(pending) {
                pending.cancelSent = false
                pending.state = RequestState.UNKNOWN
            }
            synchronized(stateLock) {
                closeSessionLocked()
                publishLocked(WiredAdbLifecycleState.DISCONNECTED, WiredAdbErrorCode.UNKNOWN_OUTCOME)
            }
            CancelDisposition.UNKNOWN
        }
        // Success means only that this authority wrote one authenticated
        // CANCEL frame for the still-active request. It does not claim that
        // the remote operation has stopped; exchange consumes the associated
        // ACK/terminal STATUS and target RESPONSE separately. A raced,
        // inactive, or failed write remains conservatively UNKNOWN_OUTCOME.
        return when (disposition) {
            CancelDisposition.SENT -> WiredAdbResult.Success(Unit)
            // This is a known local cancellation, with no remote side effect.
            CancelDisposition.LOCAL -> WiredAdbResult.Failure(WiredAdbErrorCode.REQUEST_CANCELLED)
            CancelDisposition.INACTIVE,
            CancelDisposition.UNKNOWN,
                -> WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME)
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            lifecycleEpoch++
            closed = true
            closeSessionLocked()
            clearPendingPairingLocked()
            publishLocked(
                if (trustRecord == null) WiredAdbLifecycleState.UNPAIRED else WiredAdbLifecycleState.DISCONNECTED,
                null,
            )
        }
    }

    private suspend fun exchange(
        requestId: WiredAdbRequestId,
        readDeadlineMs: Long,
        encode: (BridgeSession) -> ByteArray,
    ): WiredAdbResult<runtime.mobileagent.bridge.BridgeResponseEnvelope> = exchangeLock.withLock {
        val snapshot = synchronized(stateLock) {
            val active = channel
            val current = session
            if (active == null || current == null || _status.value.state != WiredAdbLifecycleState.READY) {
                null
            } else {
                pendingRequests[requestId.value] = PendingRequest(requestId.value)
                Pair(active, current)
            }
        } ?: return@withLock WiredAdbResult.Failure(WiredAdbErrorCode.BRIDGE_DISCONNECTED, retryable = true)

        val pending = pendingRequests[requestId.value]
        return@withLock try {
            val activePending = pending ?: throw LifecycleInvalidatedException()
            beforeRequestPublish()
            writeLock.withLock {
                val shouldPublish = synchronized(activePending) {
                    when (activePending.state) {
                        RequestState.REQUEST_NOT_PUBLISHED -> {
                            activePending.state = RequestState.REQUEST_WRITING
                            true
                        }

                        RequestState.CANCELLED_BEFORE_PUBLISH -> false
                        else -> throw LifecycleInvalidatedException()
                    }
                }
                if (!shouldPublish) throw RequestCancelledBeforePublishException()
                val frame = encode(snapshot.second)
                withContext(Dispatchers.IO) { snapshot.first.writeFrame(frame) }
                synchronized(activePending) {
                    if (activePending.state != RequestState.REQUEST_WRITING) {
                        // A lifecycle close while writeFrame was in progress
                        // makes whether the request reached the peer unknown.
                        throw LifecycleInvalidatedException()
                    }
                    activePending.state = RequestState.REQUEST_PUBLISHED
                }
            }
            // Deliberately release writeLock before a potentially long response read.
            val deadlineEpochMs = System.currentTimeMillis() + readDeadlineMs.coerceAtLeast(1L)
            snapshot.first.setReadDeadline(deadlineEpochMs)

            var response: runtime.mobileagent.bridge.BridgeResponseEnvelope? = null
            var targetUnknownStatusSeen = false
            var cancelAckSeen = false
            var cancelTerminalSeen = false
            var done = false

            fun cancellationId(): String? = synchronized(activePending) {
                activePending.cancelRequestId?.takeIf { activePending.cancelSent }
            }

            fun ensure(condition: Boolean, message: String) {
                if (!condition) throw BridgeSequenceException(message)
            }

            while (!done) {
                val decoded = withContext(Dispatchers.IO) { snapshot.first.readFrame() }
                    .let { frame -> WiredAdbSharedAdapter.decodeEncryptedFrame(snapshot.second, frame) }
                when (decoded.type) {
                    BridgeFrameType.RESPONSE -> {
                        val candidate = WiredAdbSharedAdapter.decodeResponse(decoded)
                        ensure(candidate.requestId == requestId.value, "bridge request identity mismatch")
                        ensure(response == null, "duplicate bridge response")
                        response = candidate

                        // A normal request is complete as soon as its target
                        // RESPONSE is authenticated. Once cancellation has
                        // been published, however, the peer must also deliver
                        // the cancel request's terminal STATUS. Consume that
                        // status before releasing the session so it cannot be
                        // mistaken for the next request's response.
                        val cancellationWasPublished = synchronized(activePending) {
                            if (activePending.cancelRequestId == null &&
                                activePending.state == RequestState.REQUEST_PUBLISHED
                            ) {
                                activePending.state = RequestState.COMPLETED
                                false
                            } else {
                                activePending.cancelRequestId != null && activePending.cancelSent
                            }
                        }
                        done = !cancellationWasPublished || cancelTerminalSeen
                    }

                    BridgeFrameType.STATUS -> {
                        val status = WiredAdbSharedAdapter.decodeStatus(decoded)
                        val cancelId = cancellationId()
                            ?: throw BridgeSequenceException("unassociated bridge status")
                        val state = BridgeRequestState.parse(status.state)
                        when {
                            status.requestId == requestId.value -> {
                                // Desktop force-unknown emits this target
                                // STATUS before the target RESPONSE. It is
                                // not a general progress channel: only the
                                // authenticated cancellation terminal state
                                // is accepted for this exchange.
                                ensure(
                                    state == BridgeRequestState.UNKNOWN_OUTCOME &&
                                        status.terminal && status.accepted &&
                                        !targetUnknownStatusSeen && response == null,
                                    "unexpected target bridge status",
                                )
                                targetUnknownStatusSeen = true
                            }

                            status.requestId == cancelId -> when (state) {
                                BridgeRequestState.CANCEL_ACK -> {
                                    ensure(
                                        !status.terminal && !cancelAckSeen && !cancelTerminalSeen,
                                        "invalid bridge cancel acknowledgement",
                                    )
                                    cancelAckSeen = true
                                }

                                BridgeRequestState.UNKNOWN_OUTCOME -> {
                                    ensure(
                                        status.terminal && status.accepted &&
                                            cancelAckSeen && response != null && !cancelTerminalSeen,
                                        "invalid bridge cancel terminal status",
                                    )
                                    cancelTerminalSeen = true
                                }

                                BridgeRequestState.CANCELLED -> {
                                    // If the target already completed before
                                    // CANCEL was received, desktop answers
                                    // CANCEL_ACK(accepted=false) followed by
                                    // terminal CANCELLED. The target RESPONSE
                                    // is still required and is consumed first.
                                    ensure(
                                        status.terminal && !status.accepted &&
                                            cancelAckSeen && response != null && !cancelTerminalSeen,
                                        "invalid bridge cancelled status",
                                    )
                                    cancelTerminalSeen = true
                                }

                                else -> throw BridgeSequenceException("unexpected bridge cancel status")
                            }

                            else -> throw BridgeSequenceException("bridge status request identity mismatch")
                        }
                        if (response != null && cancelTerminalSeen) done = true
                    }

                    else -> throw BridgeProtocolException("bridge response type is invalid")
                }
            }

            val completedResponse = response ?: throw BridgeProtocolException("bridge response is missing")
            if (!completedResponse.success) {
                WiredAdbResult.Failure(WiredAdbSharedAdapter.mapError(completedResponse))
            } else {
                WiredAdbResult.Success(completedResponse)
            }
        } catch (_: RequestCancelledBeforePublishException) {
            WiredAdbResult.Failure(WiredAdbErrorCode.REQUEST_CANCELLED)
        } catch (_: BridgeAuthenticationException) {
            sessionFailure(WiredAdbErrorCode.PROTOCOL_AUTH_FAILED, reauth = true)
        } catch (_: BridgeSequenceException) {
            sessionFailure(WiredAdbErrorCode.PROTOCOL_REPLAY, reauth = true)
        } catch (_: BridgeProtocolException) {
            sessionFailure(WiredAdbErrorCode.PROTOCOL_FRAME_INVALID, reauth = true)
        } catch (_: IllegalArgumentException) {
            sessionFailure(WiredAdbErrorCode.PROTOCOL_FRAME_INVALID, reauth = true)
        } catch (_: Throwable) {
            // A write/read failure has an unknown outcome; never auto-retry or dispatch again.
            sessionFailure(WiredAdbErrorCode.UNKNOWN_OUTCOME, reauth = false)
        } finally {
            pending?.let {
                synchronized(it) {
                    it.state = when (it.state) {
                        RequestState.REQUEST_PUBLISHED,
                        RequestState.CANCEL_SENT,
                            -> RequestState.COMPLETED
                        else -> it.state
                    }
                }
            }
            pendingRequests.remove(requestId.value)
        }
    }

    private fun validateFileRequest(request: WiredAdbFileRequest): WiredAdbErrorCode? = try {
        when (request.operation) {
            WiredAdbFileOperation.LIST -> WiredAdbPathPolicy.parse(request.relativePath, allowRoot = true)
            else -> WiredAdbPathPolicy.parse(request.relativePath, allowRoot = false)
        }
        if (request.operation == WiredAdbFileOperation.MOVE) {
            WiredAdbPathPolicy.parse(request.destinationRelativePath, allowRoot = false)
        }
        if (request.operation == WiredAdbFileOperation.WRITE_TEXT) {
            val content = request.contentUtf8 ?: return WiredAdbErrorCode.REQUEST_INVALID
            strictUtf8(content)
        }
        null
    } catch (_: Throwable) {
        WiredAdbErrorCode.REQUEST_INVALID
    }

    private fun claimRequest(requestId: WiredAdbRequestId): Boolean {
        // IDs are generated by this authority and are one-shot. Keeping the
        // claim after UNKNOWN_OUTCOME is the tombstone that prevents a caller
        // from replaying a possibly-mutating request after a broken read.
        if (requestTombstones.size >= WIRED_MAX_REQUEST_TOMBSTONES) return false
        return requestTombstones.add(requestId.value)
    }

    private suspend fun storeSecret(record: WiredAdbTrustRecord, secret: ByteArray) {
        val binding = WiredAdbSecretBinding(
            appInstanceId = record.appInstanceId,
            desktopId = record.desktopId,
            serialFingerprint = record.serialFingerprint,
            protocolVersion = record.protocolVersion,
            transcriptHash = record.transcriptHash,
        )
        secretStore.putBound(record.secretRef, secret, binding)
    }

    private suspend fun resolveSecret(record: WiredAdbTrustRecord): ByteArray? {
        val binding = WiredAdbSecretBinding(
            appInstanceId = record.appInstanceId,
            desktopId = record.desktopId,
            serialFingerprint = record.serialFingerprint,
            protocolVersion = record.protocolVersion,
            transcriptHash = record.transcriptHash,
        )
        return secretStore.resolveBound(record.secretRef, binding)?.also {
            require(it.size == BridgeProtocol.GCM_KEY_BYTES) { "persistent trust has invalid size" }
        }
    }

    private fun reservePairAttemptLocked(token: ByteArray?): PairReservation {
        val pending = pendingPairing ?: return PairReservation.Required
        if (clock.nowEpochMs() >= pending.expiresAtEpochMs) {
            expirePairingLocked(WiredAdbErrorCode.PAIRING_EXPIRED)
            return PairReservation.Rejected(WiredAdbErrorCode.PAIRING_EXPIRED)
        }
        if (pending.attempts >= PAIR_MAX_ATTEMPTS) {
            expirePairingLocked(WiredAdbErrorCode.PAIRING_ATTEMPTS_EXCEEDED)
            return PairReservation.Rejected(WiredAdbErrorCode.PAIRING_ATTEMPTS_EXCEEDED)
        }
        if (token != null && !constantTimeEquals(token, pending.token)) {
            pending.attempts++
            val exhausted = pending.attempts >= PAIR_MAX_ATTEMPTS
            if (exhausted) expirePairingLocked(WiredAdbErrorCode.PAIRING_ATTEMPTS_EXCEEDED)
            else publishLocked(WiredAdbLifecycleState.PAIRING, WiredAdbErrorCode.PAIRING_REJECTED)
            return PairReservation.Rejected(if (exhausted) WiredAdbErrorCode.PAIRING_ATTEMPTS_EXCEEDED else WiredAdbErrorCode.PAIRING_REJECTED)
        }
        pending.attempts++
        return PairReservation.Accepted(pending.token.copyOf(), pending.expiresAtEpochMs, lifecycleEpoch)
    }

    private fun failPairing(code: WiredAdbErrorCode, operationEpoch: Long? = null): WiredAdbResult<WiredAdbTrustRecord> {
        synchronized(stateLock) {
            if (operationEpoch != null && !isEpochCurrentLocked(operationEpoch)) {
                if (!closed) publishLocked(invalidationStateLocked(), WiredAdbErrorCode.UNKNOWN_OUTCOME)
                return WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME)
            }
            val exhausted = (pendingPairing?.attempts ?: PAIR_MAX_ATTEMPTS) >= PAIR_MAX_ATTEMPTS
            if (code == WiredAdbErrorCode.PAIRING_EXPIRED) expirePairingLocked(WiredAdbErrorCode.PAIRING_EXPIRED)
            else if (exhausted) expirePairingLocked(WiredAdbErrorCode.PAIRING_ATTEMPTS_EXCEEDED)
            else publishLocked(WiredAdbLifecycleState.PAIRING, code)
        }
        return WiredAdbResult.Failure(code, retryable = code == WiredAdbErrorCode.BRIDGE_DISCONNECTED)
    }

    /**
     * Trust metadata/secret persistence did not complete deterministically.
     * Keep the bridge fail-closed until the user explicitly forgets the
     * possibly-partially-written trust record; never report a fresh pairing
     * failure that could invite an unsafe retry.
     */
    private fun failTrustPersistence(): WiredAdbResult<WiredAdbTrustRecord> {
        synchronized(stateLock) {
            trustLoadFailed = true
            lifecycleEpoch++
            closeSessionLocked()
            clearPendingPairingLocked()
            if (!closed) {
                publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, WiredAdbErrorCode.INTERNAL_ERROR)
            }
        }
        return WiredAdbResult.Failure(WiredAdbErrorCode.INTERNAL_ERROR)
    }

    private fun authFailure(code: WiredAdbErrorCode): WiredAdbResult<Unit> {
        synchronized(stateLock) {
            closeSessionLocked()
            publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, code)
        }
        return WiredAdbResult.Failure(code)
    }

    private fun sessionFailure(code: WiredAdbErrorCode, reauth: Boolean): WiredAdbResult<Nothing> {
        synchronized(stateLock) {
            closeSessionLocked()
            publishLocked(if (reauth) WiredAdbLifecycleState.REAUTH_REQUIRED else WiredAdbLifecycleState.DISCONNECTED, code)
        }
        return WiredAdbResult.Failure(code)
    }

    private fun protocolFailure(code: WiredAdbErrorCode): WiredAdbResult.Failure {
        synchronized(stateLock) {
            closeSessionLocked()
            publishLocked(WiredAdbLifecycleState.REAUTH_REQUIRED, code)
        }
        return WiredAdbResult.Failure(code)
    }

    private fun expirePairingLocked(error: WiredAdbErrorCode) {
        clearPendingPairingLocked()
        publishLocked(WiredAdbLifecycleState.UNPAIRED, error)
    }

    /** Caller must hold [stateLock]. */
    private fun clearPendingPairingLocked() {
        pendingPairing?.token?.fill(0)
        pendingPairing = null
    }

    private fun initialStatus(): WiredAdbStatus {
        val loaded = trustRecord
        val intent = try {
            intentStore.load()
        } catch (_: Throwable) {
            intentPersistenceFailed = true
            WiredAdbUserIntent.DISABLED
        }
        return WiredAdbStatus(
            state = when {
                trustLoadFailed || intentPersistenceFailed -> WiredAdbLifecycleState.REAUTH_REQUIRED
                loaded == null -> WiredAdbLifecycleState.UNPAIRED
                !bindingMatches(loaded) -> WiredAdbLifecycleState.REAUTH_REQUIRED
                else -> WiredAdbLifecycleState.TRUSTED
            },
            userIntent = intent,
            platformGrant = if (loaded == null || !bindingMatches(loaded)) WiredAdbPlatformGrant.UNKNOWN else WiredAdbPlatformGrant.GRANTED,
            availability = if (loaded == null) WiredAdbAvailability.TEMPORARILY_UNAVAILABLE else WiredAdbAvailability.READY,
            connection = WiredAdbConnectionState.DISCONNECTED,
            trusted = loaded != null && bindingMatches(loaded),
            desktopId = loaded?.desktopId,
            appInstanceId = loaded?.appInstanceId,
            serialFingerprint = loaded?.serialFingerprint,
            protocolVersion = loaded?.protocolVersion,
        )
    }

    private fun bindingMatches(record: WiredAdbTrustRecord): Boolean = record.appInstanceId == appInstanceId &&
        record.protocolVersion == WIRED_ADB_PROTOCOL_VERSION &&
        runCatching {
            requireFingerprint(record.serialFingerprint)
            requireFingerprint(record.transcriptHash)
        }.isSuccess

    private fun publish(state: WiredAdbLifecycleState, error: WiredAdbErrorCode?) {
        synchronized(stateLock) {
            if (!closed) publishLocked(state, error)
        }
    }

    private fun publishForEpoch(epoch: Long, state: WiredAdbLifecycleState, error: WiredAdbErrorCode?) {
        synchronized(stateLock) {
            if (!isEpochCurrentLocked(epoch)) throw LifecycleInvalidatedException()
            publishLocked(state, error)
        }
    }

    private fun revalidateEpoch(epoch: Long) {
        synchronized(stateLock) {
            if (!isEpochCurrentLocked(epoch)) throw LifecycleInvalidatedException()
        }
    }

    private fun isEpochCurrentLocked(epoch: Long): Boolean =
        !closed && lifecycleEpoch == epoch && !intentPersistenceFailed

    private fun invalidationStateLocked(): WiredAdbLifecycleState {
        val intentEnabled = intentEnabledLocked()
        return when {
            intentPersistenceFailed || trustLoadFailed -> WiredAdbLifecycleState.REAUTH_REQUIRED
            intentEnabled != true -> if (trustRecord == null) WiredAdbLifecycleState.UNPAIRED else WiredAdbLifecycleState.TRUSTED
            trustRecord == null -> WiredAdbLifecycleState.UNPAIRED
            !bindingMatches(trustRecord!!) -> WiredAdbLifecycleState.REAUTH_REQUIRED
            else -> WiredAdbLifecycleState.DISCONNECTED
        }
    }

    private fun intentEnabledLocked(): Boolean? = try {
        intentStore.load() == WiredAdbUserIntent.ENABLED
    } catch (_: Throwable) {
        intentPersistenceFailed = true
        null
    }

    private fun publishLocked(state: WiredAdbLifecycleState, error: WiredAdbErrorCode?) {
        val previous = _status.value
        val trust = trustRecord
        val persistedIntent = try {
            intentStore.load()
        } catch (_: Throwable) {
            intentPersistenceFailed = true
            null
        }
        val effectiveState = if (intentPersistenceFailed && state != WiredAdbLifecycleState.UNPAIRED) {
            WiredAdbLifecycleState.REAUTH_REQUIRED
        } else {
            state
        }
        val next = previous.copy(
            state = effectiveState,
            userIntent = persistedIntent ?: previous.userIntent,
            platformGrant = if (trust == null || !bindingMatches(trust)) WiredAdbPlatformGrant.UNKNOWN else WiredAdbPlatformGrant.GRANTED,
            availability = if (state == WiredAdbLifecycleState.READY) WiredAdbAvailability.READY else WiredAdbAvailability.TEMPORARILY_UNAVAILABLE,
            connection = when (state) {
                WiredAdbLifecycleState.READY -> WiredAdbConnectionState.CONNECTED
                WiredAdbLifecycleState.CONNECTING,
                WiredAdbLifecycleState.AUTHENTICATING,
                WiredAdbLifecycleState.PAIRING -> WiredAdbConnectionState.CONNECTING
                else -> WiredAdbConnectionState.DISCONNECTED
            },
            trusted = trust != null && bindingMatches(trust),
            desktopId = trust?.desktopId,
            appInstanceId = trust?.appInstanceId,
            serialFingerprint = trust?.serialFingerprint,
            protocolVersion = trust?.protocolVersion,
            lastError = error,
        )
        _status.value = next
        if (previous.state != next.state || error != null) {
            emit(WiredAdbDiagnosticEvent(state = next.state, operation = "lifecycle", outcome = "state", error = error))
        }
    }

    private fun closeSessionLocked() {
        pendingRequests.values.forEach { pending ->
            synchronized(pending) {
                when (pending.state) {
                    RequestState.REQUEST_NOT_PUBLISHED,
                    RequestState.REQUEST_WRITING,
                    RequestState.REQUEST_PUBLISHED,
                    RequestState.CANCEL_SENT,
                        -> pending.state = RequestState.UNKNOWN
                    else -> Unit
                }
            }
        }
        runCatching { pairingChannel?.close() }
        pairingChannel = null
        runCatching { session?.close() }
        runCatching { channel?.close() }
        session = null
        channel = null
        pendingRequests.clear()
    }

    private fun newSecretRef(): String {
        val bytes = random.nextBytes(16)
        require(bytes.size == 16)
        return try {
            "wired-adb:" + bytes.toHex()
        } finally {
            bytes.fill(0)
        }
    }

    private fun decodeFingerprint(value: String): ByteArray =
        runtime.mobileagent.bridge.BridgeEncoding.unhex(value).also {
            require(it.size == BridgeProtocol.SERIAL_FINGERPRINT_BYTES)
        }

    private fun strictUtf8(bytes: ByteArray) {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
    }

    private fun mapProtocolVersion(error: Throwable): Boolean =
        error.message?.contains("protocol version", ignoreCase = true) == true

    private fun isProtocolVersionError(error: Throwable): Boolean = mapProtocolVersion(error)

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean = MessageDigest.isEqual(left, right)

    private fun emit(event: WiredAdbDiagnosticEvent) {
        runCatching { diagnostics.record(event) }
    }

    private sealed interface PairReservation {
        data object Required : PairReservation
        data class Rejected(val code: WiredAdbErrorCode) : PairReservation
        data class Accepted(val token: ByteArray, val expiresAtEpochMs: Long, val epoch: Long) : PairReservation
    }

    private sealed interface ConnectSelection {
        data class Accepted(val record: WiredAdbTrustRecord, val epoch: Long) : ConnectSelection
        data class Rejected(val code: WiredAdbErrorCode) : ConnectSelection
    }

    private class PendingPairing(val token: ByteArray, val expiresAtEpochMs: Long, var attempts: Int)

    private enum class RequestState {
        REQUEST_NOT_PUBLISHED,
        REQUEST_WRITING,
        REQUEST_PUBLISHED,
        CANCELLED_BEFORE_PUBLISH,
        CANCEL_SENT,
        COMPLETED,
        UNKNOWN,
    }

    private enum class CancelDisposition { SENT, LOCAL, INACTIVE, UNKNOWN }

    private class PendingRequest(
        val requestId: String,
        @Volatile var state: RequestState = RequestState.REQUEST_NOT_PUBLISHED,
        @Volatile var cancelRequestId: String? = null,
        @Volatile var cancelSent: Boolean = false,
    )

    private class LifecycleInvalidatedException : Exception()

    private class RequestCancelledBeforePublishException : Exception()

    companion object {
        /** Explicitly selected wired ADB authority; there is no fallback. */
        const val AUTHORITY_NAME = "WIRED_ADB"
    }
}
