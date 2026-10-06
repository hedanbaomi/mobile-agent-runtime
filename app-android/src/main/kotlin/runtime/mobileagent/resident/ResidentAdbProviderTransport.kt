// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.annotation.RequiresApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Shell app_process is not an AMS application: acquire the provider as an external shell caller. */
internal class ResidentAdbProviderTransport(private val userId: Int) {
    init { require(userId in 0..21474); check(Process.myUid() == ResidentAdbProtocol.SHELL_UID) }

    /** 0 is observed absence; null is unavailable/ambiguous and never revokes consent. */
    fun runningAppPid(appUid: Int): Int? {
        check(Process.myUid() == ResidentAdbProtocol.SHELL_UID)
        return try {
            val manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService")
                .apply { isAccessible = true }.invoke(null) ?: return null
            val method = Class.forName("android.app.IActivityManager").getMethod("getRunningAppProcesses")
            val processes = invoke(method, manager, emptyArray()) as? List<*> ?: return null
            appPid(processes, appUid)
        } catch (_: Exception) { null }
    }

    fun call(method: String, extras: Bundle): Bundle? {
        require(method in setOf("bootstrap", "challenge", "publish"))
        check(Process.myUid() == ResidentAdbProtocol.SHELL_UID)
        val manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService")
            .apply { isAccessible = true }.invoke(null) ?: error("Activity manager unavailable")
        val managerApi = Class.forName("android.app.IActivityManager")
        val acquire = managerApi.methods.singleOrNull { validAcquire(it) } ?: error("External provider API unavailable")
        val release = managerApi.methods.filter { validRelease(it) }
            .maxByOrNull { it.parameterTypes.size } ?: error("External release API unavailable")
        // The legacy release API has no user selector. Avoid acquiring an unreleaseable reference.
        check(release.parameterTypes.size == 3 || userId == 0) { "Explicit-user external release unavailable" }
        val token = Binder()
        var acquired = false
        try {
            val holder = invoke(acquire, manager, if (acquire.parameterTypes.size == 4)
                arrayOf(ResidentAdbProtocol.AUTHORITY, userId, token, "resident-adb")
                else arrayOf(ResidentAdbProtocol.AUTHORITY, userId, token)) ?: return null
            acquired = true
            val provider = Class.forName("android.app.ContentProviderHolder").getField("provider").get(holder) ?: return null
            val providerApi = Class.forName("android.content.IContentProvider")
            val providerCall = providerApi.methods.singleOrNull { validCall(it, Build.VERSION.SDK_INT) }
                ?: error("Provider call API unavailable")
            val args: Array<Any?> = when {
                Build.VERSION.SDK_INT >= 31 -> arrayOf(Api31.shellAttribution(), ResidentAdbProtocol.AUTHORITY, method, null, extras)
                Build.VERSION.SDK_INT == 30 -> arrayOf("com.android.shell", null, ResidentAdbProtocol.AUTHORITY, method, null, extras)
                providerCall.parameterTypes.size == 5 -> arrayOf("com.android.shell", ResidentAdbProtocol.AUTHORITY, method, null, extras)
                else -> arrayOf("com.android.shell", method, null, extras)
            }
            return (invoke(providerCall, provider, args) as? Bundle)?.also { it.size() }
        } finally {
            if (acquired) invoke(release, manager, if (release.parameterTypes.size == 3)
                arrayOf(ResidentAdbProtocol.AUTHORITY, token, userId) else arrayOf(ResidentAdbProtocol.AUTHORITY, token))
        }
    }

    private fun invoke(method: Method, receiver: Any, args: Array<out Any?>): Any? = try {
        method.isAccessible = true
        method.invoke(receiver, *args)
    } catch (failure: InvocationTargetException) {
        // Preserve only the exception object for the caller's closed diagnostic classification.
        throw (failure.targetException as? Exception ?: failure)
    }

    @RequiresApi(31)
    private object Api31 {
        fun shellAttribution(): Any = AttributionSource.Builder(ResidentAdbProtocol.SHELL_UID)
            .setPackageName("com.android.shell").build()
    }

    companion object {
        internal fun appPid(processes: List<*>?, appUid: Int): Int? {
            if (processes == null || processes.any { it !is android.app.ActivityManager.RunningAppProcessInfo }) return null
            val matches = processes.filterIsInstance<android.app.ActivityManager.RunningAppProcessInfo>()
                .filter { it.uid == appUid && it.processName == "runtime.mobileagent" }
            return when { matches.isEmpty() -> 0; matches.size == 1 && matches.single().pid > 0 -> matches.single().pid; else -> null }
        }
        internal fun validAcquire(method: Method): Boolean = method.name == "getContentProviderExternal" &&
            method.returnType.name == "android.app.ContentProviderHolder" &&
            method.parameterTypes.map { it.name } in listOf(
                listOf("java.lang.String", "int", "android.os.IBinder"),
                listOf("java.lang.String", "int", "android.os.IBinder", "java.lang.String"))

        internal fun validRelease(method: Method): Boolean = method.returnType == Void.TYPE &&
            ((method.name == "removeContentProviderExternalAsUser" && method.parameterTypes.map { it.name } ==
                listOf("java.lang.String", "android.os.IBinder", "int")) ||
                (method.name == "removeContentProviderExternal" && method.parameterTypes.map { it.name } ==
                    listOf("java.lang.String", "android.os.IBinder")))

        internal fun validCall(method: Method, sdk: Int): Boolean {
            if (method.name != "call" || method.returnType != Bundle::class.java) return false
            val parameters = method.parameterTypes.map { it.name }
            return parameters == when {
                sdk >= 31 -> listOf("android.content.AttributionSource", "java.lang.String", "java.lang.String", "java.lang.String", "android.os.Bundle")
                sdk == 30 -> listOf("java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String", "android.os.Bundle")
                sdk >= 29 -> listOf("java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String", "android.os.Bundle")
                else -> listOf("java.lang.String", "java.lang.String", "java.lang.String", "android.os.Bundle")
            }
        }
    }
}
