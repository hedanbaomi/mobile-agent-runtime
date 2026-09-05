// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import runtime.mobileagent.announcements.ClientContext
import runtime.mobileagent.data.AnnouncementRepository
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.ProfileRepository
import runtime.mobileagent.data.SkillRepository
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.data.SettingsRepository
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.AuditRepository
import runtime.mobileagent.data.TransferRepository
import runtime.mobileagent.security.AndroidSecretStore
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import java.io.File
import runtime.mobileagent.background.ImportWorkerRegistry
import runtime.mobileagent.background.ImportJobHandler
import runtime.mobileagent.background.ImportCancellationHandler
import runtime.mobileagent.background.ImportBatchHandler
import runtime.mobileagent.background.ConsentTicketHandler
import runtime.mobileagent.background.ImportWorkScheduler
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.storage.AndroidPdfRendererAdapter
import runtime.mobileagent.vector.UsearchVectorIndexFactory
import runtime.mobileagent.diagnostics.AndroidDiagnosticLogger
import runtime.mobileagent.integration.createWiredAdbDiagnosticSink
import runtime.mobileagent.integration.RuntimeIntegration
import runtime.mobileagent.shizuku.ShizukuAuthorityBridge
import runtime.mobileagent.wired.WiredAdbAuthorityBridgeFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MobileAgentApp : Application() {
    lateinit var database: AndroidContextSqlite
        private set
    lateinit var container: AppContainer
        private set
    lateinit var diagnostics: AndroidDiagnosticLogger
        private set

    override fun onCreate() {
        super.onCreate()
        // Android creates Application in the isolated service too. Never initialize
        // the host database, Keystore, workers or network clients in that process.
        val isolated = if (android.os.Build.VERSION.SDK_INT >= 28) android.os.Process.isIsolated()
            else !android.os.Process.isApplicationUid(android.os.Process.myUid())
        if (isolated) return
        // Diagnostics are host-only too: constructing this adapter touches only files and the
        // opt-in preference, never the database, Keystore, workers, or network clients.
        if (!::diagnostics.isInitialized) {
            diagnostics = runCatching {
                AndroidDiagnosticLogger(this).also { it.installUncaughtExceptionHandler() }
            }.getOrElse {
                // Diagnostics are support tooling. Storage or handler failures must never make
                // the main runtime unavailable; the settings screen will remain safely disabled.
                AndroidDiagnosticLogger.disabledFallback()
            }
            runCatching { diagnostics.recordProcessStarted() }
        }
        if (!deferHostInitializationForInstrumentation) ensureHostInitialized()
    }

    /**
     * Initialize host-only state before the first Activity frame. Instrumentation that exercises
     * isolated services can defer this work, while a UI smoke still runs the production shell.
     */
    internal fun ensureHostInitialized() {
        if (::container.isInitialized) return
        synchronized(this) {
            if (::container.isInitialized) return
            if (!::database.isInitialized) database = AndroidContextSqlite(this)
            Migrations.apply(database)
            container = AppContainer(this)
            container.runs.markInFlightUnknown()
        }
    }

    internal val isHostInitialized: Boolean
        get() = ::container.isInitialized

    companion object {
        @Volatile
        internal var deferHostInitializationForInstrumentation: Boolean = false
    }
}

class AppContainer(app: MobileAgentApp) :
    AgentGrantPortProvider,
    SettingsAuthorityPortProvider,
    ThreadWorkspacePortProvider,
    ThreadWorkspaceRuntimePortProvider,
    runtime.mobileagent.workspace.CanonicalWorkspaceSinkProvider,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val runtimeIntegrationRef = AtomicReference<RuntimeIntegration?>()
    val db = app.database
    val uiPreferences = app.getSharedPreferences("ui-preferences", android.content.Context.MODE_PRIVATE)
    val secrets = AndroidSecretStore(app, db)
    val profiles = ProfileRepository(db)
    val agents = AgentRepository(db)
    val conversations = ConversationRepository(db)
    val settings = SettingsRepository(db)
    val shizuku = ShizukuAuthorityBridge(app).also { bridge ->
        // A persisted Shizuku grant represents the user's earlier explicit consent. Re-bind the
        // typed UserService after process restart, but never request permission or start Shizuku.
        bridge.addPermissionResultListener { result ->
            if (result.granted) bridge.bindUserService()
        }
        if (bridge.state.value.permissionGranted) bridge.bindUserService()
    }
    val runs = RunRepository(db)
    /**
     * Process-lifetime run ownership seam.  UI pages prepare/release runs
     * through this coordinator instead of owning durability themselves, so a
     * page switch never changes a run's owner or its frozen manifest.
     */
    val runCoordinator = RunCoordinator(runs)
    val audits = AuditRepository(db)
    val transfer = TransferRepository(db, blobSink = CasBlobSink(File(app.filesDir, "cas")))
    val http: HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
                addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.code == 503) {
                        response.close()
                        throw java.io.IOException("Provider unavailable; outcome unknown; automatic replay disabled")
                    }
                    response
                }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 180_000
        }
    }
    val apiEmbeddings = ApiEmbeddingRegistry(profiles, secrets, http)
    val knowledge = KnowledgeRepository(
        db,
        CasBlobSink(File(app.filesDir, "cas")),
        embedder = object : TextEmbedder {
            override val spaceId = AndroidModelPackLoader.DEFAULT_SPACE_ID
            override val dimension = AndroidModelPackLoader.DEFAULT_DIMENSION
            private val implementation by lazy { OnnxTextEmbedder(AndroidModelPackLoader(app).load()) }
            override fun embed(text: String) = implementation.embed(text)
        },
        pdfRasterizer = AndroidPdfRendererAdapter(app),
        vectorIndexFactory = UsearchVectorIndexFactory(),
        vision = OpenAiCompatibleVision(http, profiles, secrets),
        visionBinding = {
            profiles.visionBinding()?.let { (provider, model) ->
                runtime.mobileagent.knowledge.VisionBinding(
                    providerId = provider.id,
                    modelId = model.modelId,
                    endpoint = provider.baseUrl,
                    revision = maxOf(provider.revision, model.revision),
                    providerRevision = provider.revision,
                    modelRevision = model.revision,
                )
            }
        },
        apiEmbedderResolver = apiEmbeddings::resolve,
    )
    val skills = SkillRepository(db)
    /**
     * Production Wired ADB construction is the only app-scoped authority
     * factory. It creates canonical app-private metadata and the independent
     * Android Keystore secret store; no serial/desktop/token is inferred here.
     */
    val wiredAuthority = WiredAdbAuthorityBridgeFactory.create(
        context = app,
        diagnostics = createWiredAdbDiagnosticSink(app.diagnostics),
        shellPermission = {
            runCatching { runtimeIntegrationRef.get()?.wiredShellPermissionAvailable() == true }
                .getOrDefault(false)
        },
    )
    /**
     * The process-lifetime v2 runtime facade.  All UI/tool consumers resolve
     * adapters through this object; it owns the canonical repositories and
     * never exposes backend roots, URI grants, or bridge credentials.
     */
    val runtimeIntegration = RuntimeIntegration(
        context = app,
        db = db,
        agents = agents,
        skills = skills,
        auditRepository = audits,
        diagnostics = app.diagnostics,
        shizukuAuthority = shizuku,
        wiredAuthority = wiredAuthority,
    )
    init {
        runtimeIntegrationRef.set(runtimeIntegration)
    }

    override val agentGrantPort: AgentGrantPort
        get() = runtimeIntegration.grants

    override fun settingsAuthorityPort(): SettingsAuthorityPort = runtimeIntegration

    override val threadWorkspacePort: ThreadWorkspacePort
        get() = runtimeIntegration

    override val threadWorkspaceRuntimePort: ThreadWorkspaceRuntimePort
        get() = runtimeIntegration

    override val canonicalWorkspaceSink: runtime.mobileagent.workspace.CanonicalWorkspaceSink?
        get() = runtimeIntegration

    val announcements = AnnouncementRepository(db).apply {
        if (baseUrl().isBlank()) {
            setBaseUrl(BuildConfig.ANNOUNCEMENTS_BASE_URL)
            setKeyId(BuildConfig.ANNOUNCEMENTS_KEY_ID)
            setPublicKeyHex(BuildConfig.ANNOUNCEMENTS_PUBLIC_KEY_HEX)
        }
    }
    val announcementHttp: HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }
    val announcementFetcher = AnnouncementFetcher(announcementHttp)
    private val announcementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val announcementRefreshCoordinator = AnnouncementRefreshCoordinator(
        store = RepositoryAnnouncementRefreshStore(announcements) {
            ClientContext(
                platform = "android",
                channel = "stable",
                versionCode = BuildConfig.VERSION_CODE,
                locale = Locale.getDefault().toLanguageTag(),
                // This identity is for feed rollout only. The optional telemetry identity lives
                // in AnnouncementRepository and is never used for the signed-feed audience hash.
                installId = announcements.installId(),
            )
        },
        fetcher = announcementFetcher,
        scope = announcementScope,
    )

    /**
     * Release process-lifetime bridges, HTTP clients and coroutine scopes.
     * Android normally tears these down with the process, while tests and
     * host restarts use this explicit idempotent boundary.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        announcementScope.cancel()
        runCatching { runtimeIntegration.close() }
        runtimeIntegrationRef.set(null)
        runCatching { wiredAuthority.close() }
        runCatching { shizuku.close() }
        runCatching { announcementHttp.close() }
        runCatching { http.close() }
    }

    init {
        registerSettingsAuthorityPortProvider(app, this)
        ImportWorkerRegistry.handler = ImportJobHandler { id, configured -> knowledge.resumeImport(id, visionConfigured = configured) }
        ImportWorkerRegistry.cancellationHandler = ImportCancellationHandler { id -> knowledge.cancelImport(id); Unit }
        ImportWorkerRegistry.batchHandler = ImportBatchHandler { batchId, configured ->
            runCatching { app.diagnostics.recordBatchWorkerStart() }
            try {
                knowledge.processBatch(batchId, configured)
                runCatching { app.diagnostics.recordBatchWorkerComplete() }
            } catch (failure: Throwable) {
                runCatching { app.diagnostics.recordBatchWorkerFailed(failure) }
                throw failure
            }
        }
        ImportWorkerRegistry.consentHandler = ConsentTicketHandler { ticketId, configured ->
            knowledge.applyConsentTicket(ticketId, configured)
            Unit
        }
        // Resume one coordinator per durable batch. Consent and UNKNOWN states
        // are never enqueued here, and processBatch revalidates every external
        // operation before dispatch.
        val recoverableBatches = knowledge.recoverableBatchIds().toSet()
        recoverableBatches.forEach { batchId ->
            ImportWorkScheduler.enqueueBatch(app, batchId, profiles.visionConfigured())
        }
        // Legacy pre-batch copied jobs remain individually resumable.
        knowledge.listJobs().filter {
            it.first.stage == ImportStage.COPYING && knowledge.jobBatchId(it.first.id) == null
        }.forEach { (job, _, _) ->
            ImportWorkScheduler.enqueue(app, job.id, profiles.visionConfigured())
        }
    }
}
