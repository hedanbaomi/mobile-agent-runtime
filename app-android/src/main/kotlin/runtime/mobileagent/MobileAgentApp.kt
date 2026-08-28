// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.storage.AndroidContextSqlite

class MobileAgentApp : Application() {
    lateinit var database: AndroidContextSqlite
        private set

    override fun onCreate() {
        super.onCreate()
        database = AndroidContextSqlite(this)
        Migrations.apply(database)
    }
}
