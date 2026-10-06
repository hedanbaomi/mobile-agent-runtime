// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResidentAdbActivationGateTest {
    private fun fixture(block: (ResidentAdbRegistry, (Long) -> Unit) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences("$name-test-$suffix", mode)
        }
        var elapsed = 1000L
        val registry = ResidentAdbRegistry.forTest(context) { elapsed }
        try { block(registry) { elapsed += it } }
        finally { app.deleteSharedPreferences("resident-adb-v1-test-$suffix") }
    }

    @Test fun expiredAndExhaustedTokensCannotStartAService() = fixture { registry, advance ->
        val token = registry.activationToken(false)!!.first
        advance(ResidentAdbProtocol.ACTIVATION_TTL_MS)
        assertNull(registry.bootstrap(token, ByteArray(32) { 1 }))
        assertFalse(registry.configured())
        val next = registry.activationToken(false)!!.first
        repeat(ResidentAdbProtocol.MAX_ATTEMPTS) { assertNull(registry.bootstrap(ByteArray(32), ByteArray(32) { 1 })) }
        assertNull(registry.bootstrap(next, ByteArray(32) { 1 }))
        assertFalse(registry.configured())
        token.fill(0); next.fill(0)
    }

    @Test fun bootstrapIsOneShotAndUnpublishedCredentialCannotBecomeDurable() = fixture { registry, advance ->
        val token = registry.activationToken(false)!!.first
        val generation = registry.bootstrap(token, ByteArray(32) { 1 })!!
        assertNull(registry.bootstrap(token, ByteArray(32) { 1 }))
        assertFalse("Bootstrap alone must not commit grant", registry.configured())
        assertFalse(registry.granted())
        assertNotNull(registry.issueChallenge(generation))
        assertNull(registry.issueChallenge("00000000-0000-0000-0000-000000000000"))
        advance(ResidentAdbProtocol.CHALLENGE_TTL_MS)
        assertNull(registry.issueChallenge(generation))
        assertFalse(registry.configured())
        token.fill(0)
    }

    @Test fun cancelDisableAndNewActivationInvalidateAlreadyIssuedPublicationProof() = fixture { registry, _ ->
        val appUid = ApplicationProvider.getApplicationContext<Context>().applicationInfo.uid
        for (action in 0..2) {
            val token = registry.activationToken(false)!!.first
            val secret = ByteArray(32) { 7 }
            val generation = registry.bootstrap(token, secret)!!
            val nonce = registry.issueChallenge(generation)!!
            val proof = ResidentAdbProtocol.proof(secret, nonce, generation, appUid)
            when (action) {
                0 -> registry.cancelActivation()
                1 -> registry.setEnabled(false)
                2 -> registry.activationToken(false)
            }
            assertFalse("Canceled staged proof cannot commit a service", registry.publish(generation, proof, Binder(), Binder()))
            assertNull(registry.issueChallenge(generation))
            assertFalse(registry.configured())
            token.fill(0); secret.fill(0); nonce.fill(0); proof.fill(0)
        }
    }
}
