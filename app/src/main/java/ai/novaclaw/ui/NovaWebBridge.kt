package ai.novaclaw.ui

import ai.novaclaw.a11y.NovaAccessibilityService
import ai.novaclaw.engine.AgentEvent
import ai.novaclaw.model.BackendSelector
import ai.novaclaw.model.ModelCatalog
import ai.novaclaw.model.ModelDownloader
import ai.novaclaw.service.FloatingKillSwitchService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class NovaWebBridge(private val activity: MainActivity) {

    private val context: Context = activity.applicationContext
    private val downloadIds = AtomicLong(1000)

    @JavascriptInterface
    fun isAccessibilityEnabled(): Boolean = NovaAccessibilityService.isEnabled(context)

    @JavascriptInterface
    fun isOverlayEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    @JavascriptInterface
    fun openAccessibilitySettings() {
        activity.scope.launch {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    @JavascriptInterface
    fun openAppInfoSettings() {
        activity.scope.launch {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
                toast("Open Settings → Apps → NovaClaw")
            }
        }
    }

    @JavascriptInterface
    fun openOverlaySettings() {
        activity.scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    @JavascriptInterface
    fun startFloatingOverlay() {
        activity.scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, FloatingKillSwitchService::class.java))
            } else {
                context.startService(Intent(context, FloatingKillSwitchService::class.java))
            }
        }
    }

    @JavascriptInterface
    fun checkModelStatus(fileName: String): String {
        val model = ModelCatalog.LOCAL.firstOrNull { it.fileName == fileName }
        val installed = model != null && ModelCatalog.isInstalled(context, model)
        return JSONObject().apply {
            put("installed", installed)
            put("fileName", fileName)
            put("filePath", if (model != null && installed) ModelCatalog.path(context, model) else "")
            put("fileSizeBytes", if (model != null && installed)
                runCatching { java.io.File(ModelCatalog.path(context, model)).length() }.getOrDefault(0L)
            else 0L)
        }.toString()
    }

    @JavascriptInterface
    fun downloadModel(url: String, fileName: String): Long {
        val model = ModelCatalog.LOCAL.firstOrNull { it.fileName == fileName || it.url == url }
            ?: return -1L
        val id = downloadIds.incrementAndGet()
        activity.scope.launch {
            emitProgress(model, 0L, model.sizeBytes, "PREPARING")
            val result = ModelDownloader.download(context, model) { p ->
                val json = JSONObject().apply {
                    put("fileName", model.fileName)
                    put("bytesDownloaded", p.downloaded)
                    put("bytesTotal", p.total)
                    put("status", p.status)
                }
                activity.webView.evaluateJavascript(
                    "window.onModelDownloadProgress && window.onModelDownloadProgress($json);", null
                )
            }
            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: "Unknown error"
                emitProgress(model, 0L, model.sizeBytes, "FAILED: ${message.take(140)}")
                toast("Download failed: $message")
            }
        }
        return id
    }

    @JavascriptInterface
    fun deleteModel(fileName: String): Boolean {
        val model = ModelCatalog.LOCAL.firstOrNull { it.fileName == fileName } ?: return false
        return ModelCatalog.delete(context, model)
    }

    @JavascriptInterface
    fun cancelDownload(downloadId: Long) {
        ModelCatalog.LOCAL.forEach { ModelDownloader.cancel(context, it) }
    }

    @JavascriptInterface
    fun resolveAppPackage(appName: String): String =
        NovaAccessibilityService.instance?.resolvePackage(appName).orEmpty()

    @JavascriptInterface
    fun captureScreen(): String = runBlocking(Dispatchers.Default) {
        val service = NovaAccessibilityService.instance ?: return@runBlocking "{}"
        val snap = service.captureScreen() ?: return@runBlocking "{}"
        org.json.JSONObject().apply {
            put("packageName", snap.packageName)
            put("appName", snap.appName)
            put("stateHash", snap.fingerprint)
            put("generation", snap.generation)
            val arr = org.json.JSONArray()
            snap.nodes.forEach { n ->
                arr.put(org.json.JSONObject().apply {
                    put("id", n.id)
                    put("label", n.label)
                    put("type", n.type)
                    put("clickable", n.clickable)
                    put("editable", n.editable)
                    put("scrollable", n.scrollable)
                    put("center", org.json.JSONObject().apply {
                        put("x", n.centerX); put("y", n.centerY)
                    })
                })
            }
            put("elements", arr)
        }.toString()
    }

    @JavascriptInterface
    fun performTap(x: Float, y: Float): Boolean = runBlocking(Dispatchers.Default) {
        NovaAccessibilityService.instance?.tapAt(x.toInt(), y.toInt())?.success ?: false
    }

    @JavascriptInterface
    fun performScroll(direction: String): Boolean = runBlocking(Dispatchers.Default) {
        NovaAccessibilityService.instance?.scroll(direction, null)?.success ?: false
    }

    @JavascriptInterface
    fun performText(nodeId: Int, text: String): Boolean = runBlocking(Dispatchers.Default) {
        NovaAccessibilityService.instance?.setText("n$nodeId", null, text)?.success ?: false
    }

    @JavascriptInterface
    fun performBack(): Boolean = runBlocking(Dispatchers.Default) {
        NovaAccessibilityService.instance?.back()?.success ?: false
    }

    @JavascriptInterface
    fun performHome(): Boolean = runBlocking(Dispatchers.Default) {
        NovaAccessibilityService.instance?.home()?.success ?: false
    }

    /** New: send a task to the real Kotlin agent loop. */
    @JavascriptInterface
    fun submitTask(prompt: String, modelId: String): Boolean {
        val model = ModelCatalog.byId(modelId) ?: ModelCatalog.recommended(context)
        return try {
            val loop = activity.ensureAgentLoop()
            activity.startForegroundTask(prompt)
            loop.start(prompt, model)
            true
        } catch (t: Throwable) {
            activity.sendEvent(AgentEvent.Error(t.message ?: "Could not start agent"))
            false
        }
    }

    @JavascriptInterface
    fun stopTask() {
        activity.agentLoop?.stop()
        activity.stopForegroundTask()
    }

    @JavascriptInterface
    fun resolveSafety(key: String, approved: Boolean) {
        activity.agentLoop?.resolveApproval(key, approved)
    }

    @JavascriptInterface
    fun getBackendState(): String = BackendSelector.debugState(context)

    @JavascriptInterface
    fun requestGpuProbe() = BackendSelector.requestGpuProbe(context)

    @JavascriptInterface
    fun isExternalAutomationEnabled(): Boolean =
        context.getSharedPreferences(ai.novaclaw.automation.ExternalAutomationReceiver.PREFS, Context.MODE_PRIVATE)
            .getBoolean(ai.novaclaw.automation.ExternalAutomationReceiver.KEY_EXTERNAL, false)

    @JavascriptInterface
    fun setExternalAutomation(enabled: Boolean) {
        context.getSharedPreferences(ai.novaclaw.automation.ExternalAutomationReceiver.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ai.novaclaw.automation.ExternalAutomationReceiver.KEY_EXTERNAL, enabled).apply()
    }

    @JavascriptInterface
    fun newConversation() { activity.history.newConversation() }

    @JavascriptInterface
    fun getChatHistory(): String = activity.history.recent(20).joinToString("\n")

    @JavascriptInterface
    fun getGlobalInstructions(): String = ai.novaclaw.engine.PromptComposer.globalInstructions(context)

    @JavascriptInterface
    fun setGlobalInstructions(text: String) =
        ai.novaclaw.engine.PromptComposer.setGlobalInstructions(context, text)

    @JavascriptInterface
    fun openNotificationSettings() {
        activity.scope.launch {
            runCatching {
                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    @JavascriptInterface
    fun startMonitor(contact: String, app: String): Boolean {
        if (contact.isBlank()) return false
        val pkg = NovaAccessibilityService.instance?.resolvePackage(app) ?: app
        ai.novaclaw.service.AutoReplyManager.add(context, contact.trim(), app.trim(), pkg)
        return true
    }

    @JavascriptInterface
    fun stopMonitor(contact: String) = ai.novaclaw.service.AutoReplyManager.remove(context, contact)

    @JavascriptInterface
    fun listMonitors(): String = ai.novaclaw.service.AutoReplyManager.targets(context)
        .joinToString("\n") { "${it.contact} on ${it.app}" }

    @JavascriptInterface
    fun getAppVersion(): String = "0.1.0"

    @JavascriptInterface
    fun showToast(message: String) = toast(message)

    private fun emitProgress(
        model: ai.novaclaw.model.ModelInfo,
        downloaded: Long,
        total: Long,
        status: String,
    ) {
        val json = JSONObject().apply {
            put("fileName", model.fileName)
            put("bytesDownloaded", downloaded)
            put("bytesTotal", total)
            put("status", status)
        }
        activity.webView.evaluateJavascript(
            "window.onModelDownloadProgress && window.onModelDownloadProgress($json);", null
        )
    }

    private fun toast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
