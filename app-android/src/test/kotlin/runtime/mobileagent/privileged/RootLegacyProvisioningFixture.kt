// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Path
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*

/** Synthetic legacy fixture only. Never compiled into an APK or used for production admission. */
internal fun provisionFixtureWorkspaceAndSnapshot(
    db: SqlConnection,
    coordinator: RootRuntimeCoordinator,
    fdOps: PosixFdOperations,
    locatorCipher: RootLocatorCipher,
    authority: Authority,
    workspaceId: String,
    workspaceRootPath: Path,
    agentId: String,
    snapshotId: String,
    capabilities: Set<CapabilityId>,
    lifetime: GrantLifetime = GrantLifetime.PERSISTENT,
): List<String> {
    val execution = requireNotNull(coordinator.currentExecutionStamp(authority))

        val wsRepo = RootWorkspaceRepository(db) { workspaceId }
        val locRepo = RootWorkspaceLocatorRepository(db)
        if (wsRepo.getOrigin(workspaceId) == null) {
            wsRepo.createNew("Root Workspace $workspaceId", true, execution)
        }
        val origin = requireNotNull(wsRepo.getOrigin(workspaceId))
        if (locRepo.getCurrent(workspaceId, execution) == null) {
            val node = requireNotNull(fdOps.inspectDirectoryIdentityNoFollow(workspaceRootPath))
            val posixPath = AndroidRootPosixFileDriver.toPosixAbsolutePath(workspaceRootPath)
            val payload = RootRecoveryLocatorPayload(
                originSha256 = origin.canonicalSha256(),
                workspaceId = origin.workspaceId,
                workspaceGenerationId = origin.generationId,
                authority = origin.authority,
                rootKind = if (posixPath == "/") RootWorkspaceRootKind.DEVICE_ROOT else RootWorkspaceRootKind.DIRECTORY,
                absoluteRootPath = posixPath,
                rootDeviceId = node.deviceId,
                rootInode = node.inode,
                rootMountId = node.mountId,
            )
            val plaintext = RootRecoveryLocatorCodec.encode(payload)
            val ciphertext = try { requireNotNull(locatorCipher.seal(origin, plaintext)) {
                "Root locator encryption unavailable"
            } } finally { plaintext.fill(0) }
            locRepo.attachNew(RootWorkspaceLocator(origin = origin, encryptedEnvelope = ciphertext), execution)
        }
        val grantRepo = RootGrantRepository(db)
        val grantIds = capabilities.map { cap ->
            val gId = "grant-$snapshotId-${cap.value.replace('.', '-')}"
            if (grantRepo.get(gId) == null) {
                grantRepo.create(
                    CapabilityGrant(
                        grantId = gId,
                        agentId = agentId,
                        capability = cap,
                        workspaceId = workspaceId,
                        policyVersion = execution.policyVersion,
                        lifetime = lifetime,
                    ),
                    execution,
                )
            }
            gId
        }
        val now = Utc.nowIso()
        if (db.query("SELECT id FROM agent_snapshots WHERE id = ?", listOf(snapshotId)).isEmpty()) {
            RootSnapshotGrantRepository(db).createNewSnapshot(
                snapshotId = snapshotId,
                agentId = agentId,
                grantIds = grantIds.toSet(),
                execution = execution,
            ) {
                db.execute(
                    "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at,provider_id,chat_model_revision,parameter_overrides_json,context_policy_json,permission_settings_json,binding_manifest_json,expanded_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    listOf(snapshotId, 1, agentId, "p-1", "m-1", 1, "[]", "[]", now, "prov-1", 1, "{}", "{}", "{}", "{}", "{}"),
                )
            }
        }
        return grantIds
    }
