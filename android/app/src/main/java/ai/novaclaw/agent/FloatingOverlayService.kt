package ai.novaclaw.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * FloatingOverlayService: Android System Alert Window Overlay.
 * Renders the glassy floating action pill and emergency kill-switch
 * that hovers above active apps during autonomous execution.
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        setupFloatingPill()
    }

    private fun setupFloatingPill() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        // Programmatic glassy pill view
        val pill = TextView(this).apply {
            text = "⚡ NovaClaw: Gemma 4 Active  [Tap to Stop]"
            setPadding(36, 16, 36, 16)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setBackgroundColor(0xDD111827.toInt()) // Frosted dark obsidian
            setOnClickListener {
                // Emergency Stop on tap
                NovaAccessibilityService.instance?.performHome()
                stopSelf()
            }
        }

        floatingView = pill
        try {
            windowManager.addView(floatingView, params)
        } catch (e: Exception) {
            // Overlay permission might not be granted yet
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
        }
    }
}
