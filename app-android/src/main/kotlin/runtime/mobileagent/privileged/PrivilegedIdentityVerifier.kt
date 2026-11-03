// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.RootAccessDenial
import runtime.mobileagent.domain.RootConfinementRejection
import runtime.mobileagent.domain.RootFileOperationReason
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode

/**
 * Canonical Root error codes from `root-support-20261029.md` §7 mapped deterministically
 * to the model-facing [ToolErrorCode] boundary without collapsing into `INTERNAL_ERROR`.
 */
enum class RootBridgeErrorCode(val wireErrorCode: ToolErrorCode) {
    ROOT_AUTHORIZATION_REQUIRED(ToolErrorCode.AUTHORITY_NOT_GRANTED),
    ROOT_SHELL_CONSENT_REQUIRED(ToolErrorCode.SHELL_CAPABILITY_DENIED),
    ROOT_REQUEST_DENIED(ToolErrorCode.PERMISSION_DENIED),
    ROOT_SERVICE_UNAVAILABLE(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE),
    PRIVILEGE_MISMATCH(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH),
    IDENTITY_CHANGED(ToolErrorCode.SNAPSHOT_STALE),
    UNSUPPORTED_OPERATION(ToolErrorCode.OPERATION_UNAVAILABLE),
    ;

    fun toToolError(reason: String = name, retryable: Boolean = false): ToolError = ToolError(
        code = wireErrorCode,
        message = name,
        retryable = retryable,
        details = mapOf("root_error" to name.lowercase(Locale.ROOT), "reason" to reason.lowercase(Locale.ROOT)),
    )

    companion object {
        fun fromAccessDenial(denial: RootAccessDenial): ToolError = when (denial) {
            RootAccessDenial.AUTHORITY_CHANGED -> PRIVILEGE_MISMATCH.toToolError("authority_changed")
            RootAccessDenial.PLATFORM_NOT_GRANTED -> ROOT_AUTHORIZATION_REQUIRED.toToolError("platform_not_granted")
            RootAccessDenial.ROOT_CONSENT_REQUIRED -> ROOT_AUTHORIZATION_REQUIRED.toToolError("root_consent_required")
            RootAccessDenial.CONSENT_CHANGED -> ROOT_AUTHORIZATION_REQUIRED.toToolError("consent_changed")
            RootAccessDenial.IDENTITY_CHANGED -> IDENTITY_CHANGED.toToolError("identity_changed")
            RootAccessDenial.PROTOCOL_UNSUPPORTED -> PRIVILEGE_MISMATCH.toToolError("protocol_unsupported")
            RootAccessDenial.SESSION_DEGRADED -> ROOT_SERVICE_UNAVAILABLE.toToolError("root_session_degraded", retryable = false)
            RootAccessDenial.ROOT_SHELL_CONSENT_REQUIRED -> ROOT_SHELL_CONSENT_REQUIRED.toToolError("root_shell_consent_required")
            RootAccessDenial.DANGEROUS_MODE_DISABLED -> ToolError(ToolErrorCode.DANGEROUS_MODE_DISABLED)
        }

        fun fromConfinementRejection(rejection: RootConfinementRejection): ToolError = when (rejection) {
            RootConfinementRejection.SYMLINK_REJECTED -> ToolError(ToolErrorCode.SYMLINK_FORBIDDEN)
            RootConfinementRejection.HARDLINK_REJECTED -> ROOT_REQUEST_DENIED.toToolError("hardlink_rejected")
            RootConfinementRejection.SPECIAL_NODE_REJECTED -> ToolError(ToolErrorCode.UNSUPPORTED_ENTRY)
            RootConfinementRejection.NOT_A_DIRECTORY -> ToolError(ToolErrorCode.INVALID_REQUEST)
            RootConfinementRejection.MOUNT_ID_UNAVAILABLE -> UNSUPPORTED_OPERATION.toToolError("mount_id_unavailable")
            RootConfinementRejection.CROSS_MOUNT_REJECTED -> ToolError(ToolErrorCode.PATH_OUT_OF_SCOPE)
            RootConfinementRejection.CONTROL_PLANE_PROTECTED -> ToolError(ToolErrorCode.ROOT_OPERATION_FORBIDDEN)
            RootConfinementRejection.RENAME_RACE_DETECTED -> ToolError(ToolErrorCode.CONFLICT)
            RootConfinementRejection.LOCATOR_INVALID -> ROOT_REQUEST_DENIED.toToolError("locator_invalid")
            RootConfinementRejection.ORIGIN_MISMATCH -> IDENTITY_CHANGED.toToolError("origin_mismatch")
            RootConfinementRejection.IDENTITY_MISMATCH -> IDENTITY_CHANGED.toToolError("identity_mismatch")
            RootConfinementRejection.PROVISIONAL_HANDLE_INVALID -> ToolError(ToolErrorCode.INVALID_REQUEST)
            RootConfinementRejection.STAGED_HANDLE_INVALID -> ToolError(ToolErrorCode.INVALID_REQUEST)
            RootConfinementRejection.CAPACITY_EXCEEDED -> ToolError(ToolErrorCode.RESOURCE_LIMIT)
            RootConfinementRejection.WRITE_DOMAIN_VERSION_UNSUPPORTED ->
                ToolError(ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED)
        }

        fun fromExecuteFailure(response: RootServiceExecuteResponse): ToolError {
            response.fileUnsupportedAttr?.takeIf { it.isNotBlank() }?.let { attr ->
                return ToolError(
                    code = ToolErrorCode.OPERATION_UNAVAILABLE,
                    details = mapOf(
                        "reason" to "unsupported_attribute_preservation",
                        "attribute" to sanitizeDetailValue(attr),
                    ),
                )
            }
            response.fileErrno?.takeIf { it.isNotBlank() }?.let { errno ->
                return fromPosixErrno(errno, response.fileSelinuxDenied)
            }
            response.errorReason?.takeIf { it.isNotBlank() }?.let { reason ->
                fromFileErrorReason(reason, response.fileRejection)?.let { return it }
            }
            response.fileRejection?.let { return fromConfinementRejection(it) }
            return ToolError(ToolErrorCode.OPERATION_UNAVAILABLE)
        }

        fun fromPosixErrno(errnoName: String, selinuxConfirmed: Boolean = false): ToolError {
            val normalized = errnoName.trim().uppercase(Locale.ROOT)
            val code = when {
                selinuxConfirmed || normalized == "EACCES" || normalized == "EPERM" -> ToolErrorCode.PERMISSION_DENIED
                normalized == "ENOENT" -> ToolErrorCode.ENTRY_NOT_FOUND
                normalized == "ENOTDIR" || normalized == "EISDIR" || normalized == "EINVAL" ||
                    normalized == "ENAMETOOLONG" -> ToolErrorCode.INVALID_REQUEST
                normalized == "ELOOP" -> ToolErrorCode.SYMLINK_FORBIDDEN
                normalized == "EXDEV" -> ToolErrorCode.PATH_OUT_OF_SCOPE
                normalized == "ENOSPC" || normalized == "EDQUOT" -> ToolErrorCode.QUOTA_EXCEEDED
                normalized == "EROFS" -> ToolErrorCode.WORKSPACE_READ_ONLY
                normalized == "EOVERFLOW" || normalized == "EFBIG" -> ToolErrorCode.FILE_TOO_LARGE
                normalized == "EEXIST" || normalized == "ENOTEMPTY" || normalized == "EBUSY" ||
                    normalized == "EMLINK" -> ToolErrorCode.CONFLICT
                normalized == "ENODEV" || normalized == "ENXIO" -> ToolErrorCode.UNSUPPORTED_ENTRY
                normalized == "EOPNOTSUPP" || normalized == "ENOTSUP" || normalized == "ENOSYS" ->
                    ToolErrorCode.OPERATION_UNAVAILABLE
                normalized == "ETIMEDOUT" -> ToolErrorCode.TIMEOUT
                else -> ToolErrorCode.IO_ERROR
            }
            val details = buildMap {
                put("errno", sanitizeDetailValue(normalized.lowercase(Locale.ROOT)))
                put("selinux_denied", selinuxConfirmed.toString())
            }
            return ToolError(code = code, details = details)
        }

        private fun fromFileErrorReason(reason: String, rejection: RootConfinementRejection?): ToolError? {
            val normalized = reason.trim()
            val code = when {
                normalized == RootFileOperationReason.TARGET_NOT_FOUND -> ToolErrorCode.ENTRY_NOT_FOUND
                normalized == RootFileOperationReason.PATCH_CONTEXT_MISMATCH ||
                    normalized == RootFileOperationReason.TEMP_SOURCE_IDENTITY_MISMATCH ||
                    normalized == RootFileOperationReason.WRITE_DOMAIN_RECHECK_FAILED ||
                    normalized == RootFileOperationReason.FD_HANDLE_INVALID ||
                    normalized == "atomic_replace_race" ||
                    normalized == "expected_version_mismatch" ||
                    normalized == "target_already_exists" ||
                    normalized == "destination_already_exists" ||
                    normalized == "directory_not_empty" ||
                    normalized == "cross_owner_move_requires_metadata" -> ToolErrorCode.CONFLICT
                normalized == RootFileOperationReason.PATCH_INVALID ||
                    normalized == RootFileOperationReason.PATCH_AMBIGUOUS ||
                    normalized == RootFileOperationReason.READ_WINDOW_INVALID ||
                    normalized == RootFileOperationReason.LIST_CURSOR_INVALID ||
                    normalized == "invalid_glob_pattern" ||
                    normalized == "invalid_grep_query" ||
                    normalized == "utf8_window_too_small" -> ToolErrorCode.INVALID_REQUEST
                normalized == RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET ||
                    normalized == RootFileOperationReason.CONTENT_VERSION_UNOBSERVABLE ||
                    normalized == "result_exceeds_budget" -> ToolErrorCode.FILE_TOO_LARGE
                normalized == "non_utf8_text" ||
                    normalized.startsWith(RootFileOperationReason.UNSUPPORTED_WRITE_DOMAIN) ->
                    ToolErrorCode.UNSUPPORTED_ENTRY
                normalized == RootFileOperationReason.READ_FAILED -> ToolErrorCode.IO_ERROR
                else -> null
            } ?: return rejection?.let(::fromConfinementRejection)
            return ToolError(
                code = code,
                details = mapOf("reason" to sanitizeDetailValue(normalized)),
            )
        }

        private fun sanitizeDetailValue(raw: String): String =
            raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_.:+-]"), "_").take(128).ifBlank { "unknown" }
    }
}

/**
 * Pre-pinned application identity established in the controlled bootstrap chain before
 * any external Binder call is accepted. First-caller-pinning is strictly forbidden.
 */
data class PinnedAppBootstrapExpectation(
    val expectedAuthority: Authority,
    val expectedAppUid: Int,
    val expectedUserId: Int,
    val expectedPackageName: String,
    val expectedSigningSha256: String,
    val expectedApkSha256: String,
    val expectedAppInstanceId: String,
    val expectedIdentityEpoch: Long,
) {
    init {
        require(expectedAuthority == Authority.ROOT_DIRECT || expectedAuthority == Authority.SHIZUKU_ROOT)
        require(expectedAppUid % 100_000 >= 10_000 && expectedUserId >= 0 && expectedAppUid / 100_000 == expectedUserId)
        require(expectedPackageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(expectedSigningSha256.matches(Regex("[0-9a-f]{64}")))
        require(expectedApkSha256.matches(Regex("[0-9a-f]{64}")))
        require(expectedAppInstanceId.length in 16..128)
        require(expectedIdentityEpoch > 0L)
    }
}

data class ObservedOsAppIdentity(
    val observedServiceUid: Int,
    val callingUid: Int,
    val packagesForCallingUid: List<String>,
    val resolvedPackageUidForUser: Int?,
    val installedPackageUid: Int?,
    val installedSigningSha256: String?,
    val archivePackageName: String?,
    val archiveSigningSha256: String?,
    val canonicalLoadedCodePath: String?,
    val canonicalInstalledSourceDir: String?,
    val currentApkSha256: String?,
)

sealed interface PrivilegedIdentityVerificationResult {
    data object Verified : PrivilegedIdentityVerificationResult
    data class Denied(
        val errorCode: RootBridgeErrorCode,
        val reason: String,
    ) : PrivilegedIdentityVerificationResult
}

object PrivilegedIdentityVerifier {
    fun verify(
        expectation: PinnedAppBootstrapExpectation,
        observed: ObservedOsAppIdentity,
    ): PrivilegedIdentityVerificationResult {
        if (observed.observedServiceUid != PrivilegeTier.ROOT.uid) {
            return PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.PRIVILEGE_MISMATCH,
                "service_uid_not_root",
            )
        }
        if (observed.callingUid != expectation.expectedAppUid ||
            observed.callingUid / 100_000 != expectation.expectedUserId
        ) {
            return PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.ROOT_REQUEST_DENIED,
                "caller_uid_mismatch",
            )
        }
        if (observed.packagesForCallingUid != listOf(expectation.expectedPackageName) ||
            observed.resolvedPackageUidForUser != expectation.expectedAppUid ||
            observed.installedPackageUid != expectation.expectedAppUid
        ) {
            return PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.IDENTITY_CHANGED,
                "package_uid_or_shared_uid_mismatch",
            )
        }
        if (observed.installedSigningSha256 != expectation.expectedSigningSha256 ||
            observed.archivePackageName != expectation.expectedPackageName ||
            observed.archiveSigningSha256 != expectation.expectedSigningSha256
        ) {
            return PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.IDENTITY_CHANGED,
                "signing_certificate_mismatch",
            )
        }
        if (observed.canonicalLoadedCodePath.isNullOrBlank() ||
            observed.canonicalLoadedCodePath != observed.canonicalInstalledSourceDir ||
            observed.currentApkSha256 != expectation.expectedApkSha256
        ) {
            return PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.IDENTITY_CHANGED,
                "apk_source_or_digest_changed",
            )
        }
        return PrivilegedIdentityVerificationResult.Verified
    }

    private data class ApkDigestCacheKey(
        val canonicalPath: String,
        val lastModifiedMillis: Long,
        val lengthBytes: Long,
    )

    @Volatile
    private var cachedApkDigest: Pair<ApkDigestCacheKey, String>? = null

    fun observeAndVerifyFromOs(
        pinnedContext: Context,
        callingUid: Int,
        observedServiceUid: Int,
        expectation: PinnedAppBootstrapExpectation,
    ): PrivilegedIdentityVerificationResult {
        if (pinnedContext.packageName != expectation.expectedPackageName) {
            return PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.IDENTITY_CHANGED,
                "pinned_context_package_mismatch",
            )
        }
        val observed = runCatching {
            val pm = pinnedContext.packageManager
            val pkg = expectation.expectedPackageName
            val packages = pm.getPackagesForUid(callingUid)?.toList().orEmpty()
            val resolvedUid = pm.getPackageUid(pkg, 0)
            val installed = pm.getPackageInfo(pkg, signatureFlags())
            val codePath = pinnedContext.packageCodePath
            val archive = pm.getPackageArchiveInfo(codePath, signatureFlags())
            val sourceDir = installed.applicationInfo?.sourceDir
            val canonicalCodeFile = File(codePath).canonicalFile
            val canonicalInstalledPath = sourceDir?.let { File(it).canonicalPath }
            val installedSigner = singleSignerSha256(installed)
            ObservedOsAppIdentity(
                observedServiceUid = observedServiceUid,
                callingUid = callingUid,
                packagesForCallingUid = packages,
                resolvedPackageUidForUser = resolvedUid,
                installedPackageUid = installed.applicationInfo?.uid,
                installedSigningSha256 = installedSigner,
                archivePackageName = archive?.packageName ?: pkg.takeIf { canonicalCodeFile.path == canonicalInstalledPath },
                archiveSigningSha256 = archive?.let(::singleSignerSha256)
                    ?: installedSigner.takeIf { canonicalCodeFile.path == canonicalInstalledPath },
                canonicalLoadedCodePath = canonicalCodeFile.path,
                canonicalInstalledSourceDir = canonicalInstalledPath,
                currentApkSha256 = cachedFileSha256Hex(canonicalCodeFile),
            )
        }.getOrElse {
            return PrivilegedIdentityVerificationResult.Denied(
                RootBridgeErrorCode.IDENTITY_CHANGED,
                "os_package_inspection_failed",
            )
        }
        return verify(expectation, observed)
    }

    internal fun cachedFileSha256Hex(canonicalFile: File): String {
        val key = ApkDigestCacheKey(
            canonicalPath = canonicalFile.path,
            lastModifiedMillis = canonicalFile.lastModified(),
            lengthBytes = canonicalFile.length(),
        )
        cachedApkDigest?.let { (cachedKey, digest) ->
            if (cachedKey == key && key.lengthBytes > 0L) return digest
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8_192)
        canonicalFile.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        cachedApkDigest = key to hex
        return hex
    }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun singleSignerSha256(info: PackageInfo): String? {
        val certs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) return null
            signingInfo.apkContentsSigners
        } else {
            info.signatures
        }
        val single = certs?.singleOrNull() ?: return null
        return sha256Hex(single.toByteArray())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
