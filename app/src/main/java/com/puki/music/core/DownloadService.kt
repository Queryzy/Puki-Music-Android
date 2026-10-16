package com.puki.music.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.puki.music.R
import com.puki.music.ui.MainActivity

class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "puki_download_channel"
        const val NOTIFICATION_ID = 4040

        private const val ACTION_UPDATE_PROGRESS = "com.puki.music.action.UPDATE_DOWNLOAD_PROGRESS"
        private const val ACTION_NOTIFY_COMPLETE = "com.puki.music.action.NOTIFY_DOWNLOAD_COMPLETE"
        private const val ACTION_STOP = "com.puki.music.action.STOP_DOWNLOAD"

        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTEXT = "extra_subtext"
        private const val EXTRA_PROGRESS = "extra_progress"
        private const val EXTRA_TOTAL_COUNT = "extra_total_count"

        fun updateProgress(
            context: Context,
            title: String,
            subText: String,
            progress: Int
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTEXT, subText)
                putExtra(EXTRA_PROGRESS, progress)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {}
        }

        fun notifyComplete(context: Context, totalCount: Int) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_NOTIFY_COMPLETE
                putExtra(EXTRA_TOTAL_COUNT, totalCount)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_NOTIFY_COMPLETE -> {
                val count = intent.getIntExtra(EXTRA_TOTAL_COUNT, 0)
                val completeNotif = buildCompleteNotification(count)
                notificationManager.notify(NOTIFICATION_ID, completeNotif)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Mengunduh Musik"
                val subText = intent?.getStringExtra(EXTRA_SUBTEXT) ?: "Menyiapkan file audio..."
                val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0

                val notification = buildProgressNotification(title, subText, progress)
                startAsForeground(notification)
            }
        }

        return START_NOT_STICKY
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildProgressNotification(title: String, text: String, progress: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Puki Music")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pendingIntent)

        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(100, 0, true)
        }

        return builder.build()
    }

    private fun buildCompleteNotification(count: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle("Unduhan Selesai")
            .setContentText(if (count > 0) "$count lagu berhasil tersimpan offline" else "Lagu berhasil tersimpan offline")
            .setSubText("Puki Music")
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Unduhan Musik",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status dan progres unduhan lagu offline Puki Music"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
