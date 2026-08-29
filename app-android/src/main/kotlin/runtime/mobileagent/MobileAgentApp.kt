// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
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
import runtime.mobileagent.background.ImportWorkScheduler
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.storage.AndroidPdfRendererAdapter
import runtime.mobileagent.vector.UsearchVectorIndexFactory

class MobileAgentApp : Application() {
    lateinit var database: AndroidContextSqlite
        private set
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Android creates Application in the isolated service too. Never initialize
        // the host database, Keystore, workers or network clients in that process.
        val isolated = if (android.os.Build.VERSION.SDK_INT >= 28) android.os.Process.isIsolated()
            else !android.os.Process.isApplicationUid(android.os.Process.myUid())
        if (isolated) return
        database = AndroidContextSqlite(this)
        Migrations.apply(database)
        container = AppContainer(this)
        container.runs.markInFlightUnknown()
    }
}

class AppContainer(app: MobileAgentApp) {
    val db = app.database
    val uiPreferences = app.getSharedPreferences("ui-preferences", android.content.Context.MODE_PRIVATE)
    val secrets = AndroidSecretStore(app, db)
    val profiles = ProfileRepository(db)
    val agents = AgentRepository(db)
    val conversations = ConversationRepository(db)
    val settings = SettingsRepository(db)
    val runs = RunRepository(db)
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

    init {
        ImportWorkerRegistry.handler = ImportJobHandler { id, configured -> knowledge.resumeImport(id, visionConfigured = configured) }
        ImportWorkerRegistry.cancellationHandler = ImportCancellationHandler { id -> knowledge.cancelImport(id); Unit }
        // Only copied local jobs are resumed automatically. Consent/unknown states never initiate a paid replay.
        knowledge.listJobs().filter { it.first.stage == ImportStage.COPYING }.forEach { (job, _, _) ->
            ImportWorkScheduler.enqueue(app, job.id, profiles.visionConfigured())
        }
    }
}
