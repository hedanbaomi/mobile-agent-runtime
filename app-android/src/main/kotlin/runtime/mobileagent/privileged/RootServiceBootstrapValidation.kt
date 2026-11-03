// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.RootControlPlaneBoundary

/** Compares a handshake with a pin supplied by trusted startup; performs no OS attestation itself. */
internal object RootServiceBootstrapValidation {
    fun matches(
        pin: RootServiceHostBootstrap?,
        req: RootServiceHandshakeRequest,
        observedCallingUid: Int,
        serviceUid: Int,
        boundary: RootControlPlaneBoundary,
    ): Boolean {
        val basicValid = serviceUid == PrivilegeTier.ROOT.uid &&
            observedCallingUid > 0 &&
            observedCallingUid == req.appUid &&
            req.androidUserId == observedCallingUid / 100_000 &&
            req.packageName.isNotBlank() &&
            req.appInstanceId.isNotBlank() &&
            isHexSha256(req.signingSha256) &&
            isHexSha256(req.apkSha256)
        val boundaryValid = boundary.credentialEncryptedDataDir == req.ceDataDir &&
            boundary.deviceProtectedDataDir == req.deDataDir &&
            boundary.credentialEncryptedIdentity.ownerUid == observedCallingUid &&
            boundary.deviceProtectedIdentity.ownerUid == observedCallingUid &&
            boundary.credentialEncryptedDataDir.substringAfterLast('/') == req.packageName &&
            boundary.deviceProtectedDataDir.substringAfterLast('/') == req.packageName
        if (!basicValid || !boundaryValid) return false
        val candidate = RootServiceHostBootstrap(
            expectedAppUid = observedCallingUid,
            expectedAndroidUserId = req.androidUserId,
            expectedPackageName = req.packageName,
            expectedSigningSha256 = req.signingSha256,
            expectedApkSha256 = req.apkSha256,
            expectedCeDataDir = boundary.credentialEncryptedDataDir,
            expectedDeDataDir = boundary.deviceProtectedDataDir,
        )
        return pin == candidate
    }

    private fun isHexSha256(value: String): Boolean =
        value.length == 64 && value.all { ch -> ch in "0123456789abcdefABCDEF" }
}
