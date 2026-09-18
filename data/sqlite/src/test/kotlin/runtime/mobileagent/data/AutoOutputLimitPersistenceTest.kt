// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.domain.ProviderProfile

/**
 * Vertical persistence/migration coverage for the automatic output cap.
 *
 * AUTO stores 0 in the legacy NOT NULL numeric column and is ignored; legacy
 * databases gain the mode column through the additive migration and keep both
 * their number and their manual behaviour.
 */
class AutoOutputLimitPersistenceTest {
    private fun provider() = ProviderProfile(
        id = "provider.auto",
        name = "Auto",
        apiFormat = ApiFormat.OPENAI_COMPATIBLE,
        baseUrl = "https://auto.example.invalid/v1",
        revision = 1,
    )

    private fun model(mode: OutputLimitMode, value: Int) = ModelProfile(
        id = "model.auto",
        providerId = "provider.auto",
        role = ModelRole.CHAT,
        modelId = "auto-chat",
        capabilities = setOf("stream"),
        contextLimit = 32_768,
        outputLimit = value,
        revision = 1,
        outputLimitMode = mode,
    )

    @Test
    fun autoAndManualRoundTripThroughTheRepository() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            profiles.createProvider(provider())

            profiles.createModel(model(OutputLimitMode.AUTO, 0))
            val auto = profiles.getModel("model.auto")!!
            assertEquals(OutputLimitMode.AUTO, auto.outputLimitMode)
            assertEquals(null, auto.effectiveOutputTokenLimit())
            assertTrue(auto.followsProviderOutputLimit)

            profiles.updateModel(model(OutputLimitMode.MANUAL, 8192))
            val manual = profiles.getModel("model.auto")!!
            assertEquals(OutputLimitMode.MANUAL, manual.outputLimitMode)
            assertEquals(8192, manual.effectiveOutputTokenLimit())
        }
    }

    @Test
    fun updatingAProbedModelKeepsEndpointVerificationAndMode() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            profiles.createProvider(provider())
            profiles.createModel(model(OutputLimitMode.MANUAL, 4096))

            profiles.recordProbe(
                modelId = "model.auto",
                providerRevision = 1,
                toolsSummary = "not-declared",
                imagesSummary = "not-declared",
                source = "metadata=verified;stream=not-declared;tools=not-declared;image=not-declared",
                probed = true,
            )

            // The column/argument order of the model UPDATE must stay in lockstep:
            // a shift once corrupted endpoint_json and silently degraded the
            // probe verdict back to the user-declared fallback.
            val probed = profiles.getModel("model.auto")!!
            assertEquals(runtime.mobileagent.domain.CapabilityVerification.UNKNOWN, probed.endpoint.verification)
            assertEquals(OutputLimitMode.MANUAL, probed.outputLimitMode)
            assertEquals(4096, probed.effectiveOutputTokenLimit())
        }
    }

    @Test
    fun legacyDatabaseWithoutTheColumnMigratesToManualAndKeepsItsNumber() {
        JdbcSqlConnection().use { db ->
            // Reproduce a pre-v22 model_profiles table, then let the migration
            // add the column exactly as an upgrade would.
            db.execute(
                "CREATE TABLE IF NOT EXISTS model_profiles (id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, role TEXT NOT NULL, " +
                    "model_id TEXT NOT NULL, capabilities TEXT NOT NULL, parameter_schema_json TEXT NOT NULL, " +
                    "parameters_json TEXT NOT NULL DEFAULT '{}', context_limit INTEGER NOT NULL, output_limit INTEGER NOT NULL, " +
                    "revision INTEGER NOT NULL, endpoint_json TEXT NOT NULL DEFAULT '{}')",
            )
            db.execute(
                "INSERT INTO model_profiles(id,provider_id,role,model_id,capabilities,parameter_schema_json,parameters_json," +
                    "context_limit,output_limit,revision,endpoint_json) VALUES('model.legacy','provider.legacy','CHAT'," +
                    "'legacy-chat','[\"stream\"]','{}','{}',32768,8192,1,'{}')",
            )

            Migrations.apply(db)

            val row = db.query("SELECT * FROM model_profiles WHERE id='model.legacy'").single()
            assertEquals("MANUAL", row.string("output_limit_mode"))
            assertEquals(8192, row.long("output_limit").toInt())
        }
    }
}