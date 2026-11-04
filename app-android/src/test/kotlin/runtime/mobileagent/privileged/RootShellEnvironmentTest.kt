// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootShellEnvironmentTest {
    @Test
    fun androidRuntimeEnvironmentSurvivesWithoutDaemonOrLoaderOverrides() {
        val result = rootShellEnvironment(mapOf(
            "BOOTCLASSPATH" to "/apex/com.android.art/javalib/core-oj.jar",
            "ANDROID_ROOT" to "/system",
            "ANDROID_RUNTIME_ROOT" to "/apex/com.android.runtime",
            "CLASSPATH" to "/private/daemon.apk",
            "LD_PRELOAD" to "/private/override.so",
            "PATH" to "/private/bin",
        ))
        assertEquals("/apex/com.android.art/javalib/core-oj.jar", result["BOOTCLASSPATH"])
        assertEquals("/system", result["ANDROID_ROOT"])
        assertEquals("/apex/com.android.runtime", result["ANDROID_RUNTIME_ROOT"])
        assertFalse(result.containsKey("CLASSPATH"))
        assertFalse(result.containsKey("LD_PRELOAD"))
        assertTrue(result.getValue("PATH").contains("/apex/com.android.runtime/bin"))
        assertFalse(result.getValue("PATH").contains("/private/bin"))
    }
}
