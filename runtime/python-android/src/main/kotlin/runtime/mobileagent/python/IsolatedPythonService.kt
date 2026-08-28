// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.python

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

class IsolatedPythonService : Service() {
    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : android.os.Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply?.writeString("PYTHON_RUNTIME_UNAVAILABLE")
            return true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

object PythonRuntimeGate {
    fun refuseMainProcessExecution(): Nothing {
        throw RemoteException("Python may only run in the isolated service")
    }
}
