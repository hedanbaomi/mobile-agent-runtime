// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
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

    fun upsertModel(profile: ModelProfile) {
        db.execute(
            "INSERT OR REPLACE INTO model_profiles (id,provider_id,role,model_id,capabilities,parameter_schema_json,context_limit,output_limit,revision) VALUES (?,?,?,?,?,?,?,?,?)",
            listOf(
                profile.id,
                profile.providerId,
                profile.role.name,
                profile.modelId,
                json.encodeToString(profile.capabilities.toList()),
                profile.parameterSchemaJson,
                profile.contextLimit,
                profile.outputLimit,
                profile.revision,
            ),
        )
    }

    fun listModels(providerId: String? = null): List<ModelProfile> {
        val rows = if (providerId == null) {
            db.query("SELECT * FROM model_profiles ORDER BY model_id")
        } else {
            db.query("SELECT * FROM model_profiles WHERE provider_id = ? ORDER BY model_id", listOf(providerId))
        }
        return rows.map { row ->
            val caps: List<String> = json.decodeFromString(row.string("capabilities").ifBlank { "[]" })
            ModelProfile(
                id = row.string("id"),
                providerId = row.string("provider_id"),
                role = ModelRole.valueOf(row.string("role")),
                modelId = row.string("model_id"),
                capabilities = caps.toSet(),
                parameterSchemaJson = row.string("parameter_schema_json").ifBlank { "{}" },
                contextLimit = row.long("context_limit").toInt(),
                outputLimit = row.long("output_limit").toInt(),
                revision = row.long("revision").toInt(),
            )
        }
    }

    fun chatModel(): ModelProfile? = listModels().firstOrNull { it.role == ModelRole.CHAT }

    fun visionConfigured(): Boolean = listModels().any { "image" in it.capabilities }
}
