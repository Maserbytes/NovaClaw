package ai.novaclaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class AgentForegroundService : Service() {

    companion object {
        const val ACTION_START = "ai.novaclaw.START_TASK"
        const val ACTION_UPDATE = "ai.novaclaw.UPDATE_TASK"
        const val ACTION_STOP = "ai.novaclaw.STOP_TASK"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "nova_agent"
        private const val ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try {
                    startForeground(ID, notification(
                        intent.getStringExtra(EXTRA_TITLE) ?: "NovaClaw",
                        intent.getStringExtra(EXTRA_TEXT) ?: "Working…",
                    ))
                } catch (t: Throwable) {
                    // Never let a foreground-start failure kill the task silently.
                    stopSelf()
                }
            }
            ACTION_UPDATE -> {
                val n = notification("NovaClaw", intent.getStringExtra(EXTRA_TEXT) ?: "Working…")
                runCatching { getSystemService(NotificationManager::class.java).notify(ID, n) }
            }
            ACTION_STOP -> stopSelf()
        }
        // Sticky so aggressive ROMs restart the service during a long local task.
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "NovaClaw agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(title: String, text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
