// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Locale
import runtime.mobileagent.domain.RootFdNodeIdentity

/**
 * Non-secret host identity that the controlled launch chain may place in argv. It is never
 * trusted by itself: [RootServiceBootstrap.observe] resolves every field again from the OS
 * inside the `UID 0` process. Challenges, session ids, bootstrap secrets and authorization
 * proofs are deliberately absent and must never enter argv/env.
 */
data class RootServiceLaunchIdentity(
    val appUid: Int,
    val androidUserId: Int,
    val packageName: String,
    val identityEpoch: Long,
) {
    fun toArgs(): List<String> =
        listOf(appUid.toString(), androidUserId.toString(), packageName, identityEpoch.toString())

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

        fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value) && value.length <= 255

        /** Parses exactly the four non-secret fields; anything else is rejected (fail closed). */
        fun parse(args: List<String>): RootServiceLaunchIdentity? {
            if (args.size != 4) return null
            val uid = args[0].toIntOrNull() ?: return null
            val userId = args[1].toIntOrNull() ?: return null
            val packageName = args[2]
            val epoch = args[3].toLongOrNull() ?: return null
            if (uid <= 0 || userId < 0 || epoch <= 0 || !isPackageName(packageName)) return null
            return RootServiceLaunchIdentity(uid, userId, packageName, epoch)
        }
    }
}

/** OS facts for one installed package, resolved by the privileged process, never by a caller. */
data class RootPackageIdentity(
    val packageName: String,
    val uid: Int,
    val androidUserId: Int,
    val signingSha256: String,
    val codePath: String,
    val ceDataDir: String,
    val deDataDir: String,
)

/**
 * Narrow OS observation seam. The Android implementation lives in
 * [AndroidRootOsIdentityFacts]; deterministic tests supply a fake and exercise every branch
 * without claiming device attestation.
 */
interface RootOsIdentityFacts {
    fun packagesForUid(uid: Int): List<String>

    fun packageIdentity(packageName: String, androidUserId: Int): RootPackageIdentity?

    /** Path of the APK/dex this process actually loaded, resolved from the OS/class loader. */
    fun loadedCodePath(): String?

    fun dataDirIdentity(absolutePath: String): RootFdNodeIdentity?

    fun fileSha256(absolutePath: String): String?
}

sealed interface RootBootstrapObservation {
    data class Pinned(val bootstrap: RootServiceHostBootstrap) : RootBootstrapObservation

    data class Rejected(val reason: String) : RootBootstrapObservation
}

/**
 * Derives the immutable host [RootServiceHostBootstrap] from OS facts. This runs before the
 * first external RPC so the pin can never come from the first caller's self-reported JSON.
 */
object RootServiceBootstrap {
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun observe(
        launch: RootServiceLaunchIdentity,
        facts: RootOsIdentityFacts,
    ): RootBootstrapObservation {
        if (launch.appUid <= 0) return reject("app_uid_invalid")
        if (launch.androidUserId != launch.appUid / 100_000) return reject("android_user_mismatch")
        if (!RootServiceLaunchIdentity.isPackageName(launch.packageName)) return reject("package_invalid")
        if (launch.identityEpoch <= 0L) return reject("epoch_invalid")
        val pkg = facts.packageIdentity(launch.packageName, launch.androidUserId)
            ?: return reject("package_unresolved")
        if (pkg.uid != launch.appUid || pkg.androidUserId != launch.androidUserId) {
            return reject("package_uid_mismatch")
        }
        return verify(launch, pkg, facts)
    }

    /**
     * Binder path: the package name is fixed by the compiled-in application id and the UID is
     * resolved from the OS for the given Android user. The first caller's `Binder.getCallingUid`
     * never establishes identity; it is only compared with the pinned host UID afterwards.
     */
    fun observeFixedHost(
        packageName: String,
        androidUserId: Int,
        facts: RootOsIdentityFacts,
    ): RootBootstrapObservation {
        if (!RootServiceLaunchIdentity.isPackageName(packageName)) return reject("package_invalid")
        if (androidUserId < 0) return reject("android_user_invalid")
        val pkg = facts.packageIdentity(packageName, androidUserId) ?: return reject("package_unresolved")
        if (pkg.uid <= 0) return reject("app_uid_invalid")
        if (pkg.androidUserId != androidUserId) return reject("package_uid_mismatch")
        return verify(RootServiceLaunchIdentity(pkg.uid, pkg.androidUserId, packageName, 1L), pkg, facts)
    }

    private fun verify(
        launch: RootServiceLaunchIdentity,
        pkg: RootPackageIdentity,
        facts: RootOsIdentityFacts,
    ): RootBootstrapObservation {
        val packages = facts.packagesForUid(launch.appUid)
        if (packages != listOf(launch.packageName)) return reject("shared_uid_ambiguous")
        if (!SHA256.matches(pkg.signingSha256)) return reject("signing_invalid")
        val loaded = facts.loadedCodePath() ?: return reject("loaded_apk_unknown")
        if (!samePath(loaded, pkg.codePath)) return reject("loaded_apk_mismatch")
        val apkSha256 = facts.fileSha256(pkg.codePath) ?: return reject("apk_digest_unavailable")
        val ce = validatedDataDir(pkg.ceDataDir, "/data/user/", launch) ?: return reject("ce_data_dir_invalid")
        val de = validatedDataDir(pkg.deDataDir, "/data/user_de/", launch) ?: return reject("de_data_dir_invalid")
        return RootBootstrapObservation.Pinned(
            RootServiceHostBootstrap(
                expectedAppUid = launch.appUid,
                expectedAndroidUserId = launch.androidUserId,
                expectedPackageName = launch.packageName,
                expectedSigningSha256 = pkg.signingSha256,
                expectedApkSha256 = apkSha256,
                expectedCeDataDir = ce,
                expectedDeDataDir = de,
            ),
        )
    }

    private fun validatedDataDir(
        path: String,
        prefix: String,
        launch: RootServiceLaunchIdentity,
    ): String? {
        if (prefix == "/data/user/" && launch.androidUserId == 0 && path == "/data/data/${launch.packageName}") {
            return "/data/user/0/${launch.packageName}"
        }
        if (!path.startsWith(prefix)) return null
        val expectedSuffix = "/${launch.androidUserId}/${launch.packageName}"
        if (!path.endsWith(expectedSuffix)) return null
        return path
    }

    private fun samePath(left: String, right: String): Boolean =
        normalize(left) == normalize(right) && normalize(left).isNotBlank()

    private fun normalize(path: String): String =
        path.replace('\\', '/').replace(Regex("/{2,}"), "/").trimEnd('/')

    private fun reject(reason: String): RootBootstrapObservation =
        RootBootstrapObservation.Rejected(reason)
}

/**
 * Production OS observation used inside the privileged process. A missing package manager or
 * loaded code path rejects the bootstrap instead of falling back to caller-declared values.
 */
class AndroidRootOsIdentityFacts(
    private val packageManager: PackageManager?,
    private val fdOps: PosixFdOperations = AndroidOsPosixFdOperations(),
) : RootOsIdentityFacts {
    override fun packagesForUid(uid: Int): List<String> =
        runCatching {
            (packageManager ?: resolveSystemPackageManager())?.getPackagesForUid(uid)?.toList()
        }.getOrNull().orEmpty()

    override fun packageIdentity(packageName: String, androidUserId: Int): RootPackageIdentity? =
        runCatching {
            val pm = packageManager ?: resolveSystemPackageManager() ?: return@runCatching null
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val info = pm.getPackageInfo(packageName, flags)
            val app = info.applicationInfo ?: return@runCatching null
            val signer = signerBytes(info) ?: return@runCatching null
            val rawCe = app.dataDir ?: return@runCatching null
            val resolvedUserId = app.uid / 100_000
            val normalizedCe = if (resolvedUserId == 0 && rawCe == "/data/data/$packageName") {
                "/data/user/0/$packageName"
            } else {
                rawCe
            }
            val normalizedDe = app.deviceProtectedDataDir ?: "/data/user_de/$resolvedUserId/$packageName"
            RootPackageIdentity(
                packageName = info.packageName ?: packageName,
                uid = app.uid,
                androidUserId = resolvedUserId,
                signingSha256 = sha256Hex(signer),
                codePath = app.sourceDir ?: return@runCatching null,
                ceDataDir = normalizedCe,
                deDataDir = normalizedDe,
            )
        }.getOrNull()

    override fun loadedCodePath(): String? {
        val loaderCandidates = CLASSLOADER_APK_REGEX
            .findAll(AndroidRootOsIdentityFacts::class.java.classLoader?.toString().orEmpty())
            .map { it.groupValues[1] }
            .toList()
        val classpathCandidates = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.endsWith(".apk") }
        val mapsCandidates = runCatching {
            CLASSLOADER_APK_REGEX.findAll(File("/proc/self/maps").readText())
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
        val valid = (loaderCandidates + classpathCandidates + mapsCandidates).filter { File(it).isFile }
        return valid.firstOrNull { it.contains(runtime.mobileagent.BuildConfig.APPLICATION_ID) }
            ?: valid.firstOrNull()
    }

    override fun dataDirIdentity(absolutePath: String): RootFdNodeIdentity? =
        runCatching {
            fdOps.inspectDirectoryIdentityNoFollow(Paths.get(absolutePath))
                ?: (fdOps as? AndroidOsPosixFdOperations)?.inspectOwnAppDirectoryIdentityNoFollow(Paths.get(absolutePath))
        }.getOrNull()

    override fun fileSha256(absolutePath: String): String? = runCatching {
        val file = File(absolutePath)
        if (!file.isFile || file.length() > MAX_APK_BYTES) return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }.getOrNull()

    private fun signerBytes(info: android.content.pm.PackageInfo): ByteArray? {
        val signing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) return null
            signingInfo.apkContentsSigners?.singleOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.singleOrNull()
        }
        return signing?.toByteArray()
    }

    companion object {
        private const val MAX_APK_BYTES = 512L * 1024L * 1024L
        private val CLASSLOADER_APK_REGEX = Regex("""(/data/[^\s"\]]+\.apk)""")

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

        fun userServiceFacts(context: android.content.Context): RootOsIdentityFacts =
            AndroidRootOsIdentityFacts(runCatching { context.packageManager }.getOrNull() ?: resolveSystemPackageManager())

        internal fun resolveSystemPackageManager(): PackageManager? = runCatching {
            if (android.os.Looper.getMainLooper() == null) {
                runCatching { android.os.Looper.prepareMainLooper() }
            }
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = runCatching {
                activityThread.getMethod("currentActivityThread").invoke(null)
            }.getOrNull() ?: activityThread.getMethod("systemMain").invoke(null)
            val context = activityThread.getMethod("getSystemContext").invoke(thread)
                as? android.content.Context ?: return@runCatching null
            context.packageManager
        }.getOrNull()

        /**
         * `app_process` has no application Context. The system Context is obtained through the
         * framework's own entry point; any failure returns null so the service refuses to start
         * instead of trusting caller-declared identity.
         */
        fun systemProcessFacts(): RootOsIdentityFacts? =
            resolveSystemPackageManager()?.let { AndroidRootOsIdentityFacts(it) }
    }
}
