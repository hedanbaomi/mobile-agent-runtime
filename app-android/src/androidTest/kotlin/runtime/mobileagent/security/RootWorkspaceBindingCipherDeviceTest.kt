// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.security

import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.RootWorkspaceOrigin

/** Production Keystore/AAD coverage only; these fixtures do not authenticate a Root platform session. */
class RootWorkspaceBindingCipherDeviceTest {
    private fun aad() = RootWorkspaceBindingAad(RootWorkspaceOrigin(
        "root-workspace",Authority.ROOT_DIRECT,"app-instance-000001","runtime.mobileagent",
        10123,0,"a".repeat(64),"workspace-generation-0001",
    ))
    private fun alias() = "runtime.mobileagent.test.rootpwb.${UUID.randomUUID()}"
    private fun deleteKey(alias: String) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    @Test fun rootRoundTripSurvivesCipherRecreationAndUsesFreshNonces() {
        val alias = alias()
        val plaintext = "public-root-locator-fixture".toByteArray()
        try {
            val first = PrivilegedWorkspaceBindingCipher.forRootTesting(alias).seal(plaintext,aad()) as PrivilegedWorkspaceBindingSealResult.Success
            val second = PrivilegedWorkspaceBindingCipher.forRootTesting(alias).seal(plaintext,aad()) as PrivilegedWorkspaceBindingSealResult.Success
            assertFalse(first.envelope.nonce.contentEquals(second.envelope.nonce))
            val opened = PrivilegedWorkspaceBindingCipher.forRootTesting(alias).open(first.envelope.toByteArray(),aad()) as PrivilegedWorkspaceBindingOpenResult.Success
            assertArrayEquals(plaintext,opened.locator)
            opened.locator.fill(0)
        } finally { plaintext.fill(0); deleteKey(alias) }
    }

    @Test fun rootAuthorityUserInstallationAndWorkspaceGenerationAreAuthenticated() {
        val alias = alias()
        val original = aad()
        val locator = "public-locator-fixture".toByteArray()
        try {
            val cipher = PrivilegedWorkspaceBindingCipher.forRootTesting(alias)
            val envelope = (cipher.seal(locator,original) as PrivilegedWorkspaceBindingSealResult.Success).envelope
            val changes = listOf(
                original.copy(origin=original.origin.copy(authority=Authority.SHIZUKU_ROOT)),
                original.copy(origin=original.origin.copy(androidUserId=1,appUid=110123)),
                original.copy(origin=original.origin.copy(appInstanceId="another-app-instance1")),
                original.copy(origin=original.origin.copy(packageName="another.application")),
                original.copy(origin=original.origin.copy(signingSha256="b".repeat(64))),
                original.copy(origin=original.origin.copy(generationId="another-generation-001")),
                original.copy(locatorVersion=2),original.copy(keyVersion=2),
            )
            changes.forEach {
                val result = cipher.open(envelope,it) as PrivilegedWorkspaceBindingOpenResult.Failure
                assertEquals(PrivilegedWorkspaceBindingErrorCode.AAD_MISMATCH,result.error.code)
            }
            val parts = cipher.open(envelope.encryptedLocator,envelope.locatorNonce,changes.first())
                as PrivilegedWorkspaceBindingOpenResult.Failure
            assertEquals(PrivilegedWorkspaceBindingErrorCode.AUTHENTICATION_FAILED,parts.error.code)
        } finally { locator.fill(0); deleteKey(alias) }
    }

    @Test fun keyPurposesRejectCrossDomainSealingAndOpening() {
        val rootAlias = alias()
        val shellAlias = rootAlias.replace(".rootpwb.",".shellpwb.")
        val root = aad()
        val locator = "public-shell-locator-fixture".toByteArray()
        try {
            val rootCipher = PrivilegedWorkspaceBindingCipher.forRootTesting(rootAlias)
            val shellCipher = PrivilegedWorkspaceBindingCipher(shellAlias)
            val shell = PrivilegedWorkspaceBindingAad(root.origin.appInstanceId,root.origin.workspaceId,Authority.SHIZUKU,1)
            val envelope = (shellCipher.seal(locator,shell) as PrivilegedWorkspaceBindingSealResult.Success).envelope
            val opened = shellCipher.open(envelope,shell) as PrivilegedWorkspaceBindingOpenResult.Success
            assertArrayEquals(locator,opened.locator)
            opened.locator.fill(0)
            assertEquals(PrivilegedWorkspaceBindingErrorCode.INVALID_INPUT,
                (shellCipher.seal(locator,root) as PrivilegedWorkspaceBindingSealResult.Failure).error.code)
            assertEquals(PrivilegedWorkspaceBindingErrorCode.INVALID_INPUT,
                (rootCipher.seal(locator,shell) as PrivilegedWorkspaceBindingSealResult.Failure).error.code)
            assertEquals(PrivilegedWorkspaceBindingErrorCode.INVALID_INPUT,
                (shellCipher.open(envelope,root) as PrivilegedWorkspaceBindingOpenResult.Failure).error.code)
            assertEquals(PrivilegedWorkspaceBindingErrorCode.INVALID_INPUT,
                (rootCipher.open(envelope,shell) as PrivilegedWorkspaceBindingOpenResult.Failure).error.code)
            // Matching Root purpose still cannot open a legacy shell envelope.
            assertEquals(PrivilegedWorkspaceBindingErrorCode.AAD_MISMATCH,
                (rootCipher.open(envelope,root) as PrivilegedWorkspaceBindingOpenResult.Failure).error.code)
        } finally { locator.fill(0); deleteKey(rootAlias); deleteKey(shellAlias) }
    }
}
