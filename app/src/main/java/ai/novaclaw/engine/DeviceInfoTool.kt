package ai.novaclaw.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import java.util.Locale

/** Deterministic, zero-LLM device answers. PokeClaw's #56 class of failures
 * ("is it processing or loading?") is avoided by answering instantly here. */
object DeviceInfoTool {

    fun read(context: Context): String {
        val parts = mutableListOf<String>()

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            parts += "battery=${pct}% plugged=$plugged"
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        parts += String.format(Locale.US, "ram_total=%.1fGB ram_available=%.1fGB low_memory=%s",
            mem.totalMem / 1e9, mem.availMem / 1e9, mem.lowMemory)

        val stat = StatFs(context.filesDir.path)
        parts += String.format(Locale.US, "storage_free=%.1fGB storage_total=%.1fGB",
            stat.availableBytes / 1e9, stat.totalBytes / 1e9)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetworkInfo
        parts += "network=${net?.typeName ?: "none"} connected=${net?.isConnected == true}"

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        parts += "wifi_enabled=${wifi?.isWifiEnabled == true}"

        parts += "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}"
        parts += "device=${Build.BRAND} ${Build.MODEL}"
        parts += "arch=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}"

        return parts.joinToString("\n")
    }
}
