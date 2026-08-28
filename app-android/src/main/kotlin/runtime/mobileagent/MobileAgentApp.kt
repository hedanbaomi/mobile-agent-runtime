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
import runtime.mobileagent.security.AndroidSecretStore
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import java.io.File

class MobileAgentApp : Application() {
    lateinit var database: AndroidContextSqlite
        private set
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        database = AndroidContextSqlite(this)
        Migrations.apply(database)
        container = AppContainer(this)
    }
}

class AppContainer(app: MobileAgentApp) {
    val db = app.database
    val secrets = AndroidSecretStore(app, db)
    val profiles = ProfileRepository(db)
    val http: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 180_000
        }
    }
    val knowledge = KnowledgeRepository(
        db,
        CasBlobSink(File(app.filesDir, "cas")),
        vision = OpenAiCompatibleVision(http, profiles, secrets),
        visionModelFingerprint = profiles.visionBinding()?.second?.modelId ?: "vision-unconfigured",
    )
    val skills = SkillRepository(db)
    val announcements = AnnouncementRepository(db)
    val announcementHttp: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }
    val announcementFetcher = AnnouncementFetcher(announcementHttp)
}
