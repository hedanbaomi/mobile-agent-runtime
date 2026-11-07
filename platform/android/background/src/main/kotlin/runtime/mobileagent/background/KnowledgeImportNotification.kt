// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

private const val CHANNEL_ID = "runtime.mobileagent.knowledge.import"
private const val IMPORT_TITLE = "Knowledge import"

internal enum class KnowledgeImportNotification(private val notificationId: Int, private val title: String) {
    DOCUMENT(0x4D41, IMPORT_TITLE),
    BATCH(0x4D42, IMPORT_TITLE),
    CONSENT(0x4D43, "Knowledge consent");

    fun foregroundInfo(
        context: Context,
        message: String,
        progress: Int? = null,
    ): ForegroundInfo {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Knowledge imports", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (progress != null) {
            builder.setProgress(100, progress.coerceIn(0, 100), progress <= 0)
        }
        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
