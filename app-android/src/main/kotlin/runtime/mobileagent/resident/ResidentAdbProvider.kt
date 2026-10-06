// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Fixed shell-only Binder publication. It exposes no file/shell/tool execution API. */
class ResidentAdbProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != ResidentAdbProtocol.SHELL_UID) throw SecurityException("shell identity required")
        val app = context ?: return Bundle.EMPTY
        val registry = ResidentAdbRegistry.get(app)
        val identity = Binder.clearCallingIdentity()
        return try {
            when (method) {
                "bootstrap" -> {
                    val token = extras?.getByteArray("token") ?: return Bundle.EMPTY
                    val secret = extras.getByteArray("secret") ?: return Bundle.EMPTY
                    try {
                        val generation = registry.bootstrap(token, secret) ?: return Bundle.EMPTY
                        Bundle().apply { putString("generation", generation); putInt("appUid", app.applicationInfo.uid) }
                    } finally { token.fill(0); secret.fill(0) }
                }
                "challenge" -> {
                    val generation = extras?.getString("generation") ?: return Bundle.EMPTY
                    val nonce = registry.issueChallenge(generation) ?: return Bundle.EMPTY
                    Bundle().apply { putByteArray("nonce", nonce); putInt("appUid", app.applicationInfo.uid) }
                }
                "publish" -> {
                    val generation = extras?.getString("generation") ?: return Bundle.EMPTY
                    val proof = extras.getByteArray("proof") ?: return Bundle.EMPTY
                    val service = extras.getBinder("service") ?: return Bundle.EMPTY
                    val control = extras.getBinder("control") ?: return Bundle.EMPTY
                    try { Bundle().apply { putBoolean("accepted", registry.publish(generation, proof, service, control)) } }
                    finally { proof.fill(0) }
                }
                "status" -> Bundle().apply {
                    putBoolean("configured", registry.configured())
                    putBoolean("ready", registry.binder() != null)
                }
                else -> Bundle.EMPTY
            }
        } catch (_: Exception) { Bundle.EMPTY }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
