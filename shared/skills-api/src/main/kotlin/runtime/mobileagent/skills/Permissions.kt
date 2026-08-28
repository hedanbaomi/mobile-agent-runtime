// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

data class SkillManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val license: String,
    val runtimeKind: String,
    val entrypoint: String,
    val permissions: Set<String>,
)

enum class CompatibilityClass { A, B, C, D, E }

data class PermissionGrant(
    val grantId: String,
    val installId: String,
    val packageHash: String,
    val capabilities: Set<String>,
    val revoked: Boolean = false,
    val revision: Int = 1,
)

object CapabilityBroker {
    fun effective(
        declared: Set<String>,
        grant: PermissionGrant,
        agentBound: Set<String>,
        systemPolicy: Set<String>,
        budgetRemaining: Boolean,
    ): Set<String> {
        if (grant.revoked || !budgetRemaining) return emptySet()
        return declared intersect grant.capabilities intersect agentBound intersect systemPolicy
    }
}

object SkillCompatibility {
    fun classify(
        manifestPresent: Boolean,
        hasNativePayload: Boolean,
        zipSlip: Boolean,
        runtimeKind: String,
    ): CompatibilityClass {
        if (zipSlip || hasNativePayload) return CompatibilityClass.E
        if (!manifestPresent) return CompatibilityClass.A
        return when (runtimeKind) {
            "python" -> CompatibilityClass.B
            "unsupported-deps" -> CompatibilityClass.C
            "shell", "node", "docker" -> CompatibilityClass.D
            else -> CompatibilityClass.A
        }
    }
}
