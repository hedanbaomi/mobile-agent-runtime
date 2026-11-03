// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegedSessionIdentity

/**
 * Read-only Magisk policy reader used by the already-authorized UID0 service.
 *
 * Extracted from [RootPrivilegedServiceEngine] purely for the conditional-token ratchet; the
 * behaviour, bounds and fail-closed semantics are unchanged:
 * - every command goes through [BoundedProcess] (deadline, byte cap, strict decoding, kill);
 * - `magisk --sqlite` exits 0 with empty stdout on SQL errors, so only a COUNT aggregate proves
 *   "the row is absent" (`n=0`); anything else is Invalid and never defaulted.
 */
internal object RootManagerAuthorizationReader {
    /**
     * Full answer for one observation request. The binding is always the *service's* session:
     * caller uid, service uid (live), authority and the complete session identity must match, and
     * the response echoes the service's real identity.
     */
    fun authorizationJson(
        sessionIdentity: PrivilegedSessionIdentity?,
        observedCallingUid: Int,
        liveServiceUid: Int,
        requestJson: String,
        override: (() -> RootManagerAuthorization)?,
    ): String {
        val request = runCatching {
            RootPrivilegedServiceEngine.json.decodeFromString<RootManagerAuthorizationRequest>(requestJson)
        }.getOrNull()
        if (sessionIdentity == null || request == null) {
            return jsonResponse(RootManagerAuthorization.UNSUPPORTED, null, "no_active_session")
        }
        val bound = sessionIdentity.authority == Authority.ROOT_DIRECT &&
            sessionIdentity.serviceUid == 0 &&
            liveServiceUid == 0 &&
            sessionIdentity.appUid == observedCallingUid &&
            request.identityEpoch == sessionIdentity.identityEpoch &&
            request.serviceInstanceId == sessionIdentity.serviceInstanceId &&
            request.sessionId == sessionIdentity.sessionId
        if (!bound) {
            return jsonResponse(RootManagerAuthorization.UNSUPPORTED, sessionIdentity, "caller_or_session_mismatch")
        }
        val overridden = override?.invoke()
        val (authorization, reason) = if (overridden != null) {
            overridden to "override"
        } else {
            observe(sessionIdentity.appUid, sessionIdentity.androidUserId)
        }
        return jsonResponse(authorization, sessionIdentity, reason)
    }

    private fun jsonResponse(
        authorization: RootManagerAuthorization,
        identity: PrivilegedSessionIdentity?,
        reason: String,
    ): String = RootPrivilegedServiceEngine.json.encodeToString(
        RootManagerAuthorizationResponse.serializer(),
        RootManagerAuthorizationResponse(
            provider = RootManagerAuthorizationPolicy.PROVIDER_MAGISK,
            result = authorization.name,
            serviceInstanceId = identity?.serviceInstanceId,
            sessionId = identity?.sessionId,
            identityEpoch = identity?.identityEpoch,
            reason = reason,
        ),
    )


    fun observe(appUid: Int, androidUserId: Int): Pair<RootManagerAuthorization, String> {
        val sqlite = MANAGER_SQLITE_PATHS.firstOrNull {
            runCatching { java.io.File(it).canExecute() }.getOrDefault(false)
        } ?: return RootManagerAuthorization.UNSUPPORTED to "manager_not_found"
        val major = majorVersion(sqlite)
            ?: return RootManagerAuthorization.UNSUPPORTED to "manager_version_unknown"
        if (major !in SUPPORTED_MANAGER_MAJORS) {
            return RootManagerAuthorization.UNSUPPORTED to "manager_version_unsupported:$major"
        }
        // Magisk 31 headless system-user mode forces multiuser = EACH_USER and ignores the DB
        // setting; this round refuses headless instead of reading the owner's ALLOW.
        when (headlessSystemUser()) {
            null -> return RootManagerAuthorization.UNKNOWN to "headless_unreadable"
            true -> return RootManagerAuthorization.UNSUPPORTED to "headless_system_user_unsupported"
            false -> Unit
        }
        // Magisk 31 removed the global `root_access` setting.
        val rootAccess = if (major >= 31) ManagerValue.Absent else intSetting(sqlite, "root_access")
        val multiuser = intSetting(sqlite, "multiuser_mode")
        if (rootAccess is ManagerValue.Invalid || multiuser is ManagerValue.Invalid) {
            return RootManagerAuthorization.UNKNOWN to "settings_invalid"
        }
        val mode = (multiuser as? ManagerValue.Present)?.value ?: RootManagerAuthorizationPolicy.MULTIUSER_DEFAULT
        val policyUid = if (mode == RootManagerAuthorizationPolicy.MULTIUSER_OWNER_MANAGED) {
            appUid % 100_000
        } else {
            appUid
        }
        val row = aggregate(
            sqlite,
            "select count(*) as n, COALESCE(group_concat(policy), '') as p, " +
                "COALESCE(group_concat(until), '') as u from policies where uid=$policyUid",
            required = listOf("p", "u"),
        )
        val policyValue = when (row) {
            is ManagerValue.Present -> ManagerValue.Present(
                row.value.second.getOrNull(0)?.toIntOrNull() ?: return RootManagerAuthorization.UNKNOWN to "policy_invalid",
            )
            ManagerValue.Absent -> ManagerValue.Absent
            ManagerValue.Invalid -> return RootManagerAuthorization.UNKNOWN to "policy_invalid"
        }
        val untilValue = when (row) {
            is ManagerValue.Present -> ManagerValue.Present(
                row.value.second.getOrNull(1)?.toLongOrNull() ?: return RootManagerAuthorization.UNKNOWN to "policy_invalid",
            )
            ManagerValue.Absent -> ManagerValue.Absent
            ManagerValue.Invalid -> ManagerValue.Invalid
        }
        val authorization = RootManagerAuthorizationPolicy.evaluate(
            rootAccess = rootAccess,
            multiuserMode = multiuser,
            appUid = appUid,
            userId = androidUserId,
            policy = policyValue,
            untilSeconds = untilValue,
            nowSeconds = System.currentTimeMillis() / 1_000L,
        )
        return authorization to "magisk_$major"
    }

    /** `magisk -v` prints e.g. `26.4:MAGISK:R`; only the major version is used. */
    fun majorVersion(sqlite: String): Int? {
        cachedMajorVersion?.let { return it }
        val output = runCommand(sqlite, "-v") ?: return null
        val major = Regex("""^(\d+)\.""").find(output.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        cachedMajorVersion = major
        return major
    }

    /** `ro.fw.mu.headless_system_user` decides Magisk's forced EACH_USER mode; bounded, read-only. */
    fun headlessSystemUser(): Boolean? {
        val output = BoundedProcess.run(
            command = listOf("/system/bin/getprop", "ro.fw.mu.headless_system_user"),
            timeoutMs = 1_000L,
            maxBytes = 64,
        )?.text?.trim() ?: return null
        return when (output) {
            "true" -> true
            "false", "" -> false
            else -> null
        }
    }

    fun runCommand(sqlite: String, vararg args: String): String? =
        BoundedProcess.run(
            command = listOf(sqlite) + args,
            timeoutMs = MANAGER_QUERY_TIMEOUT_MS,
            maxBytes = MANAGER_OUTPUT_LIMIT_BYTES,
        )?.text

    /**
     * One aggregate row. `group_concat` is COALESCE-guarded because Magisk 26.4's CLI/daemon
     * crashes on any aggregate whose row contains NULL (db.cpp `exec_sql` appends without a NULL
     * check; reproduced on the isolated API26 AVD). `n` must be present: 0 is a proven absent row,
     * 1 yields the required columns, empty output / missing `n` / duplicates / unparseable values
     * are Invalid — an SQL error and "no such row" are never conflated.
     */
    fun aggregate(
        sqlite: String,
        sql: String,
        required: List<String>,
    ): ManagerValue<Pair<Int, List<String?>>> {
        val output = runCommand(sqlite, "--sqlite", sql) ?: return ManagerValue.Invalid
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size != 1) return ManagerValue.Invalid
        val line = lines.single()
        val count = line.managerField("n")?.toIntOrNull() ?: return ManagerValue.Invalid
        return when (count) {
            0 -> ManagerValue.Absent
            1 -> {
                val values = required.map { line.managerField(it) ?: return ManagerValue.Invalid }
                ManagerValue.Present(1 to values)
            }
            else -> ManagerValue.Invalid
        }
    }

    private fun intSetting(sqlite: String, key: String): ManagerValue<Int> {
        return when (
            val row = aggregate(
                sqlite,
                "select count(*) as n, COALESCE(group_concat(value), '') as v " +
                    "from settings where key='$key'",
                required = listOf("v"),
            )
        ) {
            is ManagerValue.Present -> {
                val value = (row.value.second.single() ?: return ManagerValue.Invalid).toIntOrNull() ?: return ManagerValue.Invalid
                ManagerValue.Present(value)
            }
            ManagerValue.Absent -> ManagerValue.Absent
            ManagerValue.Invalid -> ManagerValue.Invalid
        }
    }

    @Volatile
    private var cachedMajorVersion: Int? = null
}
