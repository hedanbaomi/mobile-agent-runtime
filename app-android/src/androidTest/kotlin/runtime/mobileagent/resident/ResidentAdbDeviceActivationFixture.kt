// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.DataOutputStream
import java.util.UUID

/** UiAutomation executes argument words directly, so stage only a fixed secret-free shell script. */
internal object ResidentAdbDeviceActivationFixture {
    fun launch(automation: UiAutomation, context: Context, token: ByteArray, secret: ByteArray) {
        val userId = context.applicationInfo.uid / 100_000
        val scriptPath = "/data/local/tmp/resident-adb-test-$userId-${UUID.randomUUID()}.sh"
        val script = "exec 3<&0; apk=\$(pm path --user $userId runtime.mobileagent </dev/null | sed -n 's/^package://p' | head -n 1); " +
            "[ -n \"\$apk\" ] || exit 127; CLASSPATH=\"\$apk\" app_process /system/bin " +
            "runtime.mobileagent.resident.ResidentAdbMain $userId <&3 3<&- >/dev/null 2>&1 & exec 3<&-; sleep 1\n"
        try {
            val staging = automation.executeShellCommandRw("tee $scriptPath")
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(staging[1]).use { output -> output.write(script.toByteArray(Charsets.UTF_8)); output.flush() }
                ParcelFileDescriptor.AutoCloseInputStream(staging[0]).use { it.readBytes() }
            } finally { staging.forEach { runCatching { it.close() } } }
            val descriptors = automation.executeShellCommandRw("sh $scriptPath")
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { output ->
                    DataOutputStream(output).apply { writeInt(ResidentAdbProtocol.MAGIC); write(token); write(secret); flush() }
                }
                ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { it.readBytes() }
            } finally { descriptors.forEach { runCatching { it.close() } } }
        } finally {
            // Exact test fixture path; this file has never contained credentials.
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("rm $scriptPath")).use { it.readBytes() }
        }
    }
}
