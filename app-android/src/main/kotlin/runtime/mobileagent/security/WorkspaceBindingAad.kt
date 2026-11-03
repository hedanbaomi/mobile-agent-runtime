// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.security

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.RootWorkspaceOrigin

/** Closed set: callers cannot supply arbitrary bytes as the locator's authenticated identity. */
sealed interface WorkspaceBindingAad

/** Root adds an explicit AAD version and OS user/install/workspace provenance. */
data class RootWorkspaceBindingAad(
    val origin: RootWorkspaceOrigin,
    val locatorVersion: Int = 1,
    val keyVersion: Int = 1,
    val aadLocatorVersion: Int = CURRENT_AAD_VERSION,
) : WorkspaceBindingAad {
    init {
        require(locatorVersion > 0 && keyVersion > 0 && aadLocatorVersion == CURRENT_AAD_VERSION)
        require(listOf(origin.workspaceId,origin.appInstanceId,origin.generationId).all {
            it.length in 1..256 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))
        })
        require(origin.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
    }

    override fun toString(): String =
        "RootWorkspaceBindingAad(authority=${origin.authority}, aadLocatorVersion=$aadLocatorVersion)"

    companion object { const val CURRENT_AAD_VERSION = runtime.mobileagent.domain.RootWorkspaceLocator.CURRENT_AAD_VERSION }
}

internal object WorkspaceBindingAadCodec {
    fun encode(binding: WorkspaceBindingAad): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        when (binding) {
            is PrivilegedWorkspaceBindingAad -> {
                // Byte-for-byte v1 compatibility: do not add Root fields or reinterpret old locators.
                data.write("MAR-PRIVILEGED-WORKSPACE-BINDING-AAD".toByteArray(StandardCharsets.US_ASCII))
                data.writeInt(1)
                writeUtf8(data,binding.appInstanceId)
                writeUtf8(data,binding.workspaceId)
                writeUtf8(data,binding.authority.name)
                data.writeInt(binding.locatorVersion)
            }
            is RootWorkspaceBindingAad -> {
                val origin = binding.origin
                data.write("MAR-ROOT-WORKSPACE-BINDING-AAD".toByteArray(StandardCharsets.US_ASCII))
                data.writeInt(binding.aadLocatorVersion)
                writeUtf8(data,PrivilegeTier.ROOT.name)
                writeUtf8(data,origin.authority.name)
                data.writeInt(origin.androidUserId)
                data.writeInt(origin.appUid)
                writeUtf8(data,origin.appInstanceId)
                writeUtf8(data,origin.packageName)
                writeUtf8(data,origin.signingSha256)
                writeUtf8(data,origin.workspaceId)
                writeUtf8(data,origin.generationId)
                data.writeInt(binding.locatorVersion)
                data.writeInt(binding.keyVersion)
            }
        }
        return output.toByteArray()
    }

    private fun writeUtf8(data: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try { data.writeInt(bytes.size); data.write(bytes) } finally { bytes.fill(0) }
    }
}

/** Root uses its own production Keystore purpose; there is no plaintext or software fallback. */
object AndroidKeystoreRootWorkspaceBindingCipherFactory {
    fun create(): PrivilegedWorkspaceBindingCipher =
        PrivilegedWorkspaceBindingCipher.forRoot()
}
