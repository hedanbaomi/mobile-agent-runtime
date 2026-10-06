// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.shizuku.ShizukuUserService

@RunWith(AndroidJUnit4::class)
class ResidentAdbConsentTest {
    @Test fun providerRejectsAppCallerBeforePublication() {
        val provider = ResidentAdbProvider()
        try { provider.call("bootstrap", null, Bundle()); fail("ordinary app reached shell bootstrap") }
        catch (_: SecurityException) { }
    }

    @Test fun pinnedUidRejectsFirstCallerWithoutCapturingIt() {
        val service = ShizukuUserService(Process.myUid() + 1)
        val status = JSONObject(service.getStatus())
        assertFalse(status.getBoolean("ok"))
        assertEquals("SHELL_CALLER_UNTRUSTED", status.getString("code"))
        assertEquals("SHELL_SERVICE_UID_UNTRUSTED", JSONObject(service.statSession("forged", "proof.txt")).getString("code"))
    }
}
