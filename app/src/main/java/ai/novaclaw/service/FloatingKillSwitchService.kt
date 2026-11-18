package ai.novaclaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Always-on-top pill that immediately stops any running task. */
class FloatingKillSwitchService : Service() {

    private val manager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var pill: View? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        showPill()
        startForeground(2002, notification())
    }

    private fun showPill() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val label = TextView(this).apply {
            text = "✕ NovaClaw"
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.parseColor("#B2222222"))
            setPadding(24, 14, 24, 14)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 180
        }

        var lastX = 0f
        var lastY = 0f
        label.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x += (event.rawX - lastX).toInt()
                    params.y += (event.rawY - lastY).toInt()
                    lastX = event.rawX; lastY = event.rawY
                    runCatching { manager.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - lastX) + kotlin.math.abs(event.rawY - lastY)
                    if (moved < 8) {
                        sendBroadcast(Intent("ai.novaclaw.KILL_TASK"))
                        stopSelf()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { manager.addView(label, params) }
        pill = label
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("nova_kill", "NovaClaw kill switch", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "nova_kill")
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setSmallIcon(android.R.drawable.ic_delete)
            .setContentTitle("NovaClaw kill switch active")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        pill?.let { runCatching { manager.removeView(it) } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
