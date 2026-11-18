package ai.novaclaw.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64

/**
 * Opt-in entry point for Tasker / MacroDroid / ADB.
 * Adapted from PokeClaw's compact, security-conscious automation contract:
 * off by default, explicit package targeting, request id, status callbacks.
 */
class ExternalAutomationReceiver : BroadcastReceiver() {

    companion object {
        const val RUN_TASK = "ai.novaclaw.RUN_TASK"
        const val INTERNAL_RUN = "ai.novaclaw.INTERNAL_RUN_TASK"
        const val PREFS = "nova_settings"
        const val KEY_EXTERNAL = "external_automation_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("request_id") ?: "unknown"
        val enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXTERNAL, false)
        if (!enabled) {
            callback(context, intent, "rejected", requestId, "External automation is disabled")
            return
        }

        val task = intent.getStringExtra("task")
            ?: intent.getStringExtra("task_b64")?.let {
                runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull()
            }
        if (task.isNullOrBlank()) {
            callback(context, intent, "rejected", requestId, "Empty task")
            return
        }

        callback(context, intent, "accepted", requestId, null)
        val internal = Intent(INTERNAL_RUN).setPackage(context.packageName)
            .putExtra("task", task)
            .putExtra("request_id", requestId)
        context.sendBroadcast(internal)
    }

    private fun callback(context: Context, source: Intent, status: String, requestId: String, error: String?) {
        val action = source.getStringExtra("return_action") ?: return
        val pkg = source.getStringExtra("return_package") ?: return
        val out = Intent(action).setPackage(pkg)
            .putExtra("status", status)
            .putExtra("request_id", requestId)
        if (error != null) out.putExtra("error", error)
        context.sendBroadcast(out)
    }
}
