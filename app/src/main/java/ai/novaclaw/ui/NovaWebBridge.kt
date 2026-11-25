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
        val file = if (model != null) runCatching { java.io.File(ModelCatalog.path(context, model)) }.getOrNull() else null
        val installed = model != null && runCatching { ModelCatalog.isInstalled(context, model) }.getOrDefault(false)
        return JSONObject().apply {
            put("installed", installed)
            put("fileName", fileName)
            put("filePath", if (installed) file!!.absolutePath else "")
            put("fileSizeBytes", if (installed) file!!.length() else 0L)
        }.toString()
    }

    @JavascriptInterface
    fun downloadModel(url: String, fileName: String): Long {
        val model = ModelCatalog.LOCAL.firstOrNull { it.fileName == fileName || it.url == url }
            ?: return -1L
        val id = downloadIds.incrementAndGet()
        activity.scope.launch {
            activity.startForegroundTask("Downloading ${model.name}")
            emitProgress(model, 0L, model.sizeBytes, "PREPARING")
            val result = ModelDownloader.download(context, model) { p ->
                emitProgress(model, p.downloaded, p.total, p.status)
                activity.updateForegroundTask("Downloading ${model.name}: ${p.percent}%")
            }
            activity.stopForegroundTask()
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
        val model = (ModelCatalog.LOCAL + ModelCatalog.QWEN_35_4B).firstOrNull { it.fileName == fileName } ?: return false
        return ModelCatalog.delete(context, model)
    }

    @JavascriptInterface
    fun hasLegacyQwenDownload(): Boolean = runCatching { ModelCatalog.hasLegacyQwenDownload(context) }.getOrDefault(false)

    @JavascriptInterface
    fun cancelDownload(downloadId: Long) {
        activity.stopForegroundTask()
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
            if (loop.isRunning) return false
            activity.startForegroundTask(prompt)
            loop.start(prompt, model)
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
    fun runSelfTest(): String {
        val results = mutableListOf<Pair<String, Boolean>>()
        fun check(name: String, ok: Boolean) = results.add(name to ok)

        check("Accessibility service", ai.novaclaw.a11y.NovaAccessibilityService.isEnabled(context) &&
            ai.novaclaw.a11y.NovaAccessibilityService.instance != null)
        check("Notification access", ai.novaclaw.service.NovaNotificationListener.isEnabled())
        check("Overlay / kill switch", isOverlayEnabled())
        check("Network connected", runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            cm.activeNetworkInfo?.isConnected == true
        }.getOrDefault(false))
        val models = ai.novaclaw.model.ModelCatalog.LOCAL.filter {
            ai.novaclaw.model.ModelCatalog.isInstalled(context, it)
        }
        check("Gemma 4 E2B installed", models.any { it.id == "gemma4-e2b" })
        check("Gemma 4 E4B installed", models.any { it.id == "gemma4-e4b" })
        check("Qwen 3.5 2B catalog entry", models.any { it.id == "qwen3.5-2b" })
        check("Cloud key configured", ai.novaclaw.model.CloudConfig.apiKey(context).isNotBlank())
        check("Tool registry (${ai.novaclaw.engine.ToolRegistry(context).specs.size} tools)",
            ai.novaclaw.engine.ToolRegistry(context).specs.isNotEmpty())
        check("Skills installed", ai.novaclaw.skill.SkillManager(context).list() != "No skills installed.")
        check("MCP servers", ai.novaclaw.mcp.McpClient(context).list() != "No MCP servers configured.")
        check("Model storage writable", runCatching {
            ai.novaclaw.model.ModelCatalog.modelsDir(context).canWrite()
        }.getOrDefault(false))

        val passed = results.count { it.second }
        return org.json.JSONObject().apply {
            put("passed", passed)
            put("total", results.size)
            put("allOk", passed == results.size)
            val arr = org.json.JSONArray()
            results.forEach { (name, ok) ->
                arr.put(org.json.JSONObject().apply {
                    put("name", name); put("ok", ok)
                })
            }
            put("checks", arr)
        }.toString()
    }

    @JavascriptInterface
    fun installSkill(url: String): String =
        kotlinx.coroutines.runBlocking { ai.novaclaw.skill.SkillManager(context).installFromUrl(url) }

    @JavascriptInterface
    fun listSkills(): String = ai.novaclaw.skill.SkillManager(context).list()

    @JavascriptInterface
    fun addMcpServer(name: String, url: String): String =
        ai.novaclaw.mcp.McpClient(context).add(name, url)

    @JavascriptInterface
    fun listMcpServers(): String = ai.novaclaw.mcp.McpClient(context).list()

    @JavascriptInterface
    fun getShowThinking(): Boolean =
        context.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)
            .getBoolean("show_thinking", false)

    @JavascriptInterface
    fun setShowThinking(value: Boolean) {
        context.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("show_thinking", value).apply()
    }

    @JavascriptInterface
    fun setCloudApiKey(key: String) = ai.novaclaw.model.CloudConfig.setApiKey(context, key)

    @JavascriptInterface
    fun hasCloudApiKey(): Boolean = ai.novaclaw.model.CloudConfig.apiKey(context).isNotBlank()

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
        // WebView methods must run on the WebView's own thread. post() is safe
        // from coroutine/IO threads; evaluateJavascript directly is not.
        activity.webView.post {
            activity.webView.evaluateJavascript(
                "window.onModelDownloadProgress && window.onModelDownloadProgress($json);", null
            )
        }
    }

    private fun toast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
