// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.AttributionSource
import android.os.Bundle
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Validate narrowly allowed framework call signatures without invoking hidden APIs as an app. */
@RunWith(AndroidJUnit4::class)
class ResidentAdbProviderTransportTest {
    interface Api26 { fun call(pkg: String, method: String, arg: String?, extras: Bundle?): Bundle? }
    interface Api29 { fun call(pkg: String, authority: String, method: String, arg: String?, extras: Bundle?): Bundle? }
    interface Api30 { fun call(pkg: String, tag: String?, authority: String, method: String, arg: String?, extras: Bundle?): Bundle? }
    interface Api31 { fun call(source: AttributionSource, authority: String, method: String, arg: String?, extras: Bundle?): Bundle? }
    interface Invalid { fun call(source: AttributionSource, authority: String, method: String, arg: String?, extras: Bundle?, extra: Int): Bundle? }
    interface Release {
        fun removeContentProviderExternal(authority: String, token: IBinder)
        fun removeContentProviderExternalAsUser(authority: String, token: IBinder, user: Int)
        fun removeContentProviderExternalAsUser(authority: String, token: IBinder, user: Long)
    }

    @Test fun eachSdkAcceptsOnlyItsCanonicalProviderSignature() {
        val shapes = listOf(Api26::class.java to 26..28, Api29::class.java to 29..29,
            Api30::class.java to 30..30, Api31::class.java to 31..35)
        for (sdk in 26..35) {
            shapes.forEach { (type, range) ->
                assertEquals("SDK $sdk signature ${type.simpleName}", sdk in range,
                    ResidentAdbProviderTransport.validCall(type.methods.single(), sdk))
            }
            assertFalse(ResidentAdbProviderTransport.validCall(Invalid::class.java.methods.single(), sdk))
        }
    }

    @Test fun releaseRejectsSimilarButWrongUserType() {
        Release::class.java.methods.forEach { method ->
            assertEquals(method.parameterTypes.last() != Long::class.javaPrimitiveType,
                ResidentAdbProviderTransport.validRelease(method))
            assertFalse("An arbitrary return type cannot impersonate framework provider holder",
                ResidentAdbProviderTransport.validAcquire(method))
        }
    }

    @Test fun presenceRequiresPinnedUidAndExactMainProcessAndRetainsUnknown() {
        fun process(uid: Int, name: String, pid: Int) = android.app.ActivityManager.RunningAppProcessInfo(name, pid, emptyArray()).apply { this.uid = uid }
        val appUid = 10123
        assertNull(ResidentAdbProviderTransport.appPid(null, appUid))
        assertNull(ResidentAdbProviderTransport.appPid(listOf("unknown-shape"), appUid))
        assertEquals(0, ResidentAdbProviderTransport.appPid(emptyList<Any>(), appUid))
        assertEquals(0, ResidentAdbProviderTransport.appPid(listOf(process(10124, "runtime.mobileagent", 12),
            process(appUid, "runtime.mobileagent:other", 13)), appUid))
        assertEquals(14, ResidentAdbProviderTransport.appPid(listOf(process(appUid, "runtime.mobileagent", 14)), appUid))
        assertNull(ResidentAdbProviderTransport.appPid(listOf(process(appUid, "runtime.mobileagent", -1)), appUid))
        assertNull(ResidentAdbProviderTransport.appPid(listOf(process(appUid, "runtime.mobileagent", 14),
            process(appUid, "runtime.mobileagent", 15)), appUid))
    }
}
