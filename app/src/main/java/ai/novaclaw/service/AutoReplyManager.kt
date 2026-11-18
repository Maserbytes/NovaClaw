package ai.novaclaw.service

import ai.novaclaw.engine.DeviceTools
import ai.novaclaw.util.NovaLog
import android.content.Context
import android.os.PowerManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class MonitorTarget(val contact: String, val app: String, val packageName: String)

/**
 * Background monitor: reacts to notifications for watched contacts and replies
 * with on-device reasoning. Includes PokeClaw #30's locked-device guard and
 * self-reply loop protection.
 */
object AutoReplyManager {

    private const val TAG = "AutoReply"
    private const val PREFS = "nova_monitors"
    private const val KEY = "targets"
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastSent = mutableMapOf<String, Long>()

    @Volatile
    var replyGenerator: (suspend (contact: String, message: String) -> String?)? = null

    private fun store(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun targets(context: Context): List<MonitorTarget> {
        val raw = store(context).getString(KEY, "[]").orEmpty()
        return runCatching {
            gson.fromJson<List<MonitorTarget>>(raw, object : TypeToken<List<MonitorTarget>>() {}.type)
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, contact: String, app: String, packageName: String) {
        val list = targets(context).toMutableList()
        if (list.none { it.contact.equals(contact, true) && it.app.equals(app, true) }) {
            list.add(MonitorTarget(contact, app, packageName))
            NovaLog.i(TAG, "Monitor added: $contact on $app")
        }
        store(context).edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun remove(context: Context, contact: String) {
        val list = targets(context).filterNot { it.contact.equals(contact, true) }
        store(context).edit().putString(KEY, gson.toJson(list)).apply()
        NovaLog.i(TAG, "Monitor removed: $contact")
    }

    fun onNotificationPosted(context: Context, pkg: String, title: String, text: String) {
        if (pkg == context.packageName) return
        val matches = targets(context).filter { t ->
            t.packageName == pkg || t.app.equals(pkg, true) ||
                title.contains(t.contact, true) || text.contains(t.contact, true)
        }
        if (matches.isEmpty()) return

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            NovaLog.w(TAG, "Device locked — reply deferred (platform limit)")
            return
        }
        val now = System.currentTimeMillis()
        val target = matches.first()
        if (now - (lastSent[target.contact] ?: 0L) < 15_000L) return
        if (text.isBlank() || text.contains("Group", true)) return

        scope.launch {
            delay(5_000) // debounce: wait for the full message to arrive
            val generate = replyGenerator
            if (generate == null) {
                NovaLog.w(TAG, "No reply generator configured")
                return@launch
            }
            val reply = runCatching { generate(target.contact, text) }.getOrNull()
            if (reply.isNullOrBlank()) {
                NovaLog.w(TAG, "Reply generation failed for ${target.contact}")
                return@launch
            }
            val result = DeviceTools.sendMessage(context, "${target.contact} on ${target.app}", reply)
            if (result.success) {
                lastSent[target.contact] = System.currentTimeMillis()
                NovaLog.i(TAG, "Auto-reply sent to ${target.contact}: ${reply.take(80)}")
            } else {
                NovaLog.w(TAG, "Auto-reply failed: ${result.reason}")
            }
        }
    }
}
