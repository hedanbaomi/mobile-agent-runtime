// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

/**
 * Non-interactive view of the Root manager's persistent authorization for this app.
 *
 * A manager revocation does not kill an already-running UID0 child, and asking `su` again from the
 * app would be a *new interactive* request (forbidden by the foreground-only contract). The
 * already-connected UID0 service therefore answers this out-of-band by reading the manager's own
 * persistent policy. The semantics mirror Magisk 26.4 `uid_granted_root`
 * (`native/src/core/su/su_daemon.cpp`, commit 6c807d35b241c6a22b35534af8b6fe447a66f073):
 * global `root_access`, `multiuser_mode` mapping, and a policy row that is ALLOW and still valid.
 */
enum class RootManagerAuthorization(val permitsSession: Boolean = false) {
    /** Persistent ALLOW for this app uid (and the global settings still permit app requests). */
    GRANTED(true),

    /** Authenticated UID0 session; this provider has no supported live policy observer. */
    SESSION_GRANTED(true),

    /** The manager explicitly denies, is globally disabled, or only allows ADB. */
    REVOKED,

    /** No persistent grant / interactive-only / expired row / ambiguous data. */
    UNKNOWN,

    /** The RPC was not bound to the authenticated session, or a selected observer is unavailable. */
    UNSUPPORTED,
}

/** A settings/policy value that distinguishes "row absent" from "row unreadable". */
sealed interface ManagerValue<out T> {
    data class Present<T>(val value: T) : ManagerValue<T>

    /** The query succeeded and the row does not exist: the documented default applies. */
    data object Absent : ManagerValue<Nothing>

    /** Unreadable, duplicated, timed-out or unparseable: never defaulted. */
    data object Invalid : ManagerValue<Nothing>
}

object RootManagerAuthorizationPolicy {
    const val PROVIDER_MAGISK = "magisk"

    /** Magisk `SuPolicy`: 0 = INTERACTIVE/QUERY, 1 = DENY, 2 = ALLOW. */
    const val POLICY_INTERACTIVE = 0
    const val POLICY_DENY = 1
    const val POLICY_ALLOW = 2

    /** Magisk `settings.root_access`: 0 disabled, 1 apps only, 2 ADB only, 3 both (default). */
    const val ROOT_ACCESS_DISABLED = 0
    const val ROOT_ACCESS_APPS_ONLY = 1
    const val ROOT_ACCESS_ADB_ONLY = 2
    const val ROOT_ACCESS_BOTH = 3
    const val ROOT_ACCESS_DEFAULT = ROOT_ACCESS_BOTH

    /** Magisk `settings.multiuser_mode`: 0 owner only, 1 owner managed, 2 each user. */
    const val MULTIUSER_OWNER_ONLY = 0
    const val MULTIUSER_OWNER_MANAGED = 1
    const val MULTIUSER_EACH_USER = 2
    const val MULTIUSER_DEFAULT = MULTIUSER_OWNER_ONLY

    /**
     * Evaluates one observation.
     *
     * - defaults apply only to [ManagerValue.Absent]; [ManagerValue.Invalid] is always UNKNOWN;
     * - ALLOW needs a *valid* `until` window: a missing/unparseable `until` is never permanent;
     * - an expired row is UNKNOWN: "expired" is not the same fact as "the manager denies now";
     * - INTERACTIVE (0) and a missing row are UNKNOWN, not REVOKED.
     */
    fun evaluate(
        rootAccess: ManagerValue<Int>,
        multiuserMode: ManagerValue<Int>,
        appUid: Int,
        userId: Int,
        policy: ManagerValue<Int>,
        untilSeconds: ManagerValue<Long>,
        nowSeconds: Long,
    ): RootManagerAuthorization {
        val access = when (rootAccess) {
            is ManagerValue.Present -> rootAccess.value
            ManagerValue.Absent -> ROOT_ACCESS_DEFAULT
            ManagerValue.Invalid -> return RootManagerAuthorization.UNKNOWN
        }
        if (access !in ROOT_ACCESS_DISABLED..ROOT_ACCESS_BOTH) return RootManagerAuthorization.UNKNOWN
        if (access != ROOT_ACCESS_APPS_ONLY && access != ROOT_ACCESS_BOTH) return RootManagerAuthorization.REVOKED

        val mode = when (multiuserMode) {
            is ManagerValue.Present -> multiuserMode.value
            ManagerValue.Absent -> MULTIUSER_DEFAULT
            ManagerValue.Invalid -> return RootManagerAuthorization.UNKNOWN
        }
        when (mode) {
            MULTIUSER_OWNER_ONLY -> if (userId != 0) return RootManagerAuthorization.REVOKED
            MULTIUSER_OWNER_MANAGED -> if (appUid % 100_000 !in 10_000..19_999) {
                return RootManagerAuthorization.UNKNOWN
            }
            MULTIUSER_EACH_USER -> Unit
            else -> return RootManagerAuthorization.UNKNOWN
        }

        val policyValue = (policy as? ManagerValue.Present)?.value ?: return RootManagerAuthorization.UNKNOWN
        if (policyValue != POLICY_ALLOW && policyValue != POLICY_DENY &&
            policyValue != POLICY_INTERACTIVE
        ) {
            return RootManagerAuthorization.UNKNOWN
        }
        val until = when (untilSeconds) {
            is ManagerValue.Present -> untilSeconds.value
            ManagerValue.Absent, ManagerValue.Invalid -> return RootManagerAuthorization.UNKNOWN
        }
        if (until < 0L) return RootManagerAuthorization.UNKNOWN
        val stillValid = until == 0L || until > nowSeconds
        return when {
            policyValue == POLICY_INTERACTIVE -> RootManagerAuthorization.UNKNOWN
            !stillValid -> RootManagerAuthorization.UNKNOWN
            policyValue == POLICY_ALLOW -> RootManagerAuthorization.GRANTED
            else -> RootManagerAuthorization.REVOKED
        }
    }
}

/** The `su` executable that was actually used to launch the Root service. */
internal data class RootDirectProvider(
    val path: String,
    val version: String,
    val major: Int,
) {
    val observeMagiskPolicy: Boolean
        get() = version.contains(":MAGISKSU", ignoreCase = true) && major in MAGISK_POLICY_SCHEMA_MAJORS
}

/** Known su locations, followed by bounded absolute PATH entries. Listing is not execution. */
internal val ROOT_SU_FIXED_PATHS = listOf("/sbin/su", "/system/xbin/su", "/system/bin/su", "/debug_ramdisk/su")

/** Bounds an environment-controlled PATH scan; Android's real PATH is far shorter. */
internal const val ROOT_PATH_ENTRY_LIMIT = 64

/** The same su candidates the foreground probe will execute, resolved for reuse by readers. */
internal fun rootPathSuCandidates(pathValue: String? = System.getenv("PATH")): List<String> {
    val fromPath = pathValue.orEmpty().split(':').asSequence()
        .map { it.trim() }
        .filter { it.startsWith("/") }
        .take(ROOT_PATH_ENTRY_LIMIT)
        .map { it.trimEnd('/') + "/su" }
    return (ROOT_SU_FIXED_PATHS.asSequence() + fromPath).distinct().toList()
}

/**
 * Resolve and pin an executable su. Version output selects an optional policy observer;
 * it never grants Root or excludes another provider. The service UID0 handshake decides that.
 */
internal object RootDirectProviderProbe {
    private val MAGISKSU_VERSION = Regex("""^(\d+)\.\d+:(?i:MAGISKSU)\b""")

    fun resolve(
        timeoutMs: Long = 1_500L,
        isCancelled: () -> Boolean = { false },
    ): RootDirectProvider? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val paths = rootPathSuCandidates()
        for (path in paths) {
            if (isCancelled()) return null
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) return null
            if (!runCatching { java.io.File(path).canExecute() }.getOrDefault(false)) continue
            val version = BoundedProcess.run(
                command = listOf(path, "-v"),
                timeoutMs = remainingMs,
                maxBytes = 512,
                isCancelled = isCancelled,
            )?.text.orEmpty()
            val major = MAGISKSU_VERSION.find(version.trim())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            return RootDirectProvider(path = path, version = version.trim(), major = major)
        }
        return null
    }
}
