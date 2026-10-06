// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.updates

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import runtime.mobileagent.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal class PreferenceUpdateStore(private val preferences: SharedPreferences) : UpdateCheckStore {
    override var checkedDay: String
        get() = preferences.getString("checked-day", "").orEmpty()
        set(value) { check(preferences.edit().putString("checked-day", value).commit()) }
    override var cachedRelease: AppRelease?
        get() = runCatching {
            Json.decodeFromString(AppRelease.serializer(), preferences.getString("release", "").orEmpty()).validate()
        }.getOrNull()
        set(value) {
            val encoded = value?.let { Json.encodeToString(AppRelease.serializer(), it.validate()) }
            check(preferences.edit().putString("release", encoded).commit())
        }
    override var dismissedPrompt: String
        get() = preferences.getString("dismissed-prompt", "").orEmpty()
        set(value) { preferences.edit().putString("dismissed-prompt", value).apply() }
}

internal data class ApkIdentity(val packageName: String, val versionName: String?, val versionCode: Long, val signers: Set<String>, val minSdk: Int)

internal fun verifyApkIdentity(installed: ApkIdentity, candidate: ApkIdentity, release: AppRelease, sdk: Int) {
    require(candidate.packageName == installed.packageName)
    require(candidate.versionName == release.version)
    require(candidate.versionCode > installed.versionCode)
    require(ReleaseVersion.parse(release.version) > ReleaseVersion.parse(requireNotNull(installed.versionName)))
    require(candidate.signers.isNotEmpty() && candidate.signers == installed.signers)
    require(candidate.minSdk <= sdk)
}

@Suppress("DEPRECATION")
internal class AndroidApkVerifier(private val context: Context) {
    private val flags: Int get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return ApkIdentity(
            info.packageName, info.versionName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet(), info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
        )
    }

    fun verify(file: File, release: AppRelease) {
        release.validate()
        require(file.canonicalFile.parentFile == File(context.filesDir, "app-updates").canonicalFile)
        require(file.isFile && file.length() == release.size && sha256(file) == release.sha256)
        val pm = context.packageManager
        val installed = identity(pm.getPackageInfo(context.packageName, flags))
        // GET_SIGNING_CERTIFICATES/GET_SIGNATURES requests Android's APK certificate collection;
        // malformed or tampered signatures produce null. The system installer independently verifies again.
        val candidate = identity(requireNotNull(pm.getPackageArchiveInfo(file.path, flags)))
        verifyApkIdentity(installed, candidate, release, Build.VERSION.SDK_INT)
        ZipFile(file).use { zip ->
            val native = zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }.toList()
            require(native.all { it.name.split('/').getOrNull(1) in Build.SUPPORTED_ABIS })
        }
    }
}

internal fun createAppUpdates(context: Context, scope: CoroutineScope): AppUpdateCoordinator = AppUpdateCoordinator(
    GitHubReleaseSource(), PreferenceUpdateStore(context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)),
    scope, File(context.filesDir, "app-updates"), BuildConfig.VERSION_NAME,
    compatible = BuildConfig.BUILD_TYPE == "release" && "arm64-v8a" in Build.SUPPORTED_ABIS,
    verify = AndroidApkVerifier(context)::verify,
)
