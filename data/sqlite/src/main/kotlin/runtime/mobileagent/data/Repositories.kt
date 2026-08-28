// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ProviderProfile

class ProfileRepository(private val db: SqlConnection) {
    private val json = Json

    fun upsertProvider(profile: ProviderProfile) {
        db.execute(
            "INSERT OR REPLACE INTO provider_profiles (id,name,api_format,base_url,header_secret_refs,non_secret_headers,secret_ref,revision) VALUES (?,?,?,?,?,?,?,?)",
            listOf(
                profile.id,
                profile.name,
                profile.apiFormat.name,
                profile.baseUrl,
                json.encodeToString(profile.headerSecretRefs),
                json.encodeToString(profile.nonSecretHeaders),
                profile.secretRef,
                profile.revision,
            ),
        )
    }

    fun listProviders(): List<ProviderProfile> =
        db.query("SELECT * FROM provider_profiles ORDER BY name").map { row ->
            ProviderProfile(
                id = row.string("id"),
                name = row.string("name"),
                apiFormat = ApiFormat.valueOf(row.string("api_format")),
                baseUrl = row.string("base_url"),
                headerSecretRefs = json.decodeFromString(row.string("header_secret_refs").ifBlank { "{}" }),
                nonSecretHeaders = json.decodeFromString(row.string("non_secret_headers").ifBlank { "{}" }),
                secretRef = row.string("secret_ref"),
                revision = row.long("revision").toInt(),
            )
        }
}
