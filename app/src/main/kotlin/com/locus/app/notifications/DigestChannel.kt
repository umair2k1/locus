package com.locus.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object DigestChannel {
    const val CHANNEL_DIGEST = "digest"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return

            val digestChannel =
                NotificationChannel(
                    CHANNEL_DIGEST,
                    "Digest",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Notifications for computed note digests"
                }

            notificationManager.createNotificationChannel(digestChannel)
        }
    }
}
