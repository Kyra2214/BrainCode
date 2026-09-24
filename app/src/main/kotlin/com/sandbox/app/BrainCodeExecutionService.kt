package com.sandbox.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground execution anchor for long-running BrainCode work.
 *
 * The SandboxViewModel remains the owner of downloads, extraction, setup,
 * builds and jobs. This service keeps the application process in the Android
 * foreground while those operations are running. It also holds a partial
 * wake lock so long operations are not suspended simply because the screen
 * turns off.
 */
class BrainCodeExecutionService : Service() {
    companion object {
        const val ACTION_UPDATE = "com.sandbox.app.action.UPDATE_EXECUTION_NOTIFICATION"
        const val ACTION_STOP = "com.sandbox.app.action.STOP_EXECUTION_NOTIFICATION"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MAX = "max"
        const val EXTRA_INDETERMINATE = "indeterminate"

        private const val CHANNEL_ID = "braincode_execution"
        private const val NOTIFICATION_ID = 4202
        private const val WAKE_LOCK_TAG = "BrainCode::Execution"

        fun start(context: android.content.Context) {
            val intent = Intent(context, BrainCodeExecutionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: android.content.Context, text: String, progress: Int? = null, max: Int = 100) {
            val intent = Intent(context, BrainCodeExecutionService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
                progress?.let { putExtra(EXTRA_PROGRESS, it) }
                putExtra(EXTRA_MAX, max)
                putExtra(EXTRA_INDETERMINATE, progress == null)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, BrainCodeExecutionService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("BrainCode ativo — processos continuam em segundo plano", null)
        )
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                    ?: "BrainCode ativo — processos continuam em segundo plano"
                val progress = if (intent.hasExtra(EXTRA_PROGRESS)) {
                    intent.getIntExtra(EXTRA_PROGRESS, 0)
                } else null
                val max = intent.getIntExtra(EXTRA_MAX, 100)
                getSystemService(NotificationManager::class.java)?.notify(
                    NOTIFICATION_ID,
                    buildNotification(text, progress, max)
                )
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing BrainCode from recents must not cancel the execution anchor.
        start(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(text: String, progress: Int?, max: Int = 100): Notification {
        val openIntent = Intent(this, BrainCodeActivity::class.java)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("BrainCode")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress != null) {
            val safeMax = max.coerceAtLeast(1)
            builder.setProgress(safeMax, progress.coerceIn(0, safeMax), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Execução do BrainCode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém tarefas longas do Sandbox executando em segundo plano."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
