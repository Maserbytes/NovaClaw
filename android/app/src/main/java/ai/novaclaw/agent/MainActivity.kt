package ai.novaclaw.agent

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MainActivity: Android Shell hosting the smooth, whitish, glassy NovaClaw UI.
 * Connects the web client to the native Android Accessibility Service, Model Downloader, and Safety Guard.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = WebViewClient()
            addJavascriptInterface(NovaWebBridge(this@MainActivity), "AndroidBridge")
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        checkAndPromptPermissions()
    }

    override fun onResume() {
        super.onResume()
        val isA11y = isAccessibilityServiceEnabled()
        val isOverlay = isOverlayEnabled()
        webView.evaluateJavascript("window.updatePermissionStatus && window.updatePermissionStatus($isA11y, $isOverlay);", null)
    }

    private fun checkAndPromptPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Enable NovaClaw in Accessibility Settings to control phone safely", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedServiceName = "$packageName/${NovaAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabledServices.contains(expectedServiceName) || enabledServices.contains(NovaAccessibilityService::class.java.simpleName)
    }

    private fun isOverlayEnabled(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun startDownloadWatcher(downloadId: Long, fileName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var active = true
            while (active) {
                delay(800)
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesSoFar = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))

                    val statusStr = when (status) {
                        DownloadManager.STATUS_RUNNING -> "RUNNING"
                        DownloadManager.STATUS_SUCCESSFUL -> "SUCCESSFUL"
                        DownloadManager.STATUS_FAILED -> "FAILED"
                        DownloadManager.STATUS_PAUSED -> "PAUSED"
                        else -> "PENDING"
                    }

                    val json = JSONObject().apply {
                        put("downloadId", downloadId)
                        put("fileName", fileName)
                        put("bytesDownloaded", bytesSoFar)
                        put("bytesTotal", totalBytes)
                        put("status", statusStr)
                    }

                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("window.onModelDownloadProgress && window.onModelDownloadProgress($json);", null)
                    }

                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        active = false
                    }
                } else {
                    active = false
                }
                cursor?.close()
            }
        }
    }

    inner class NovaWebBridge(private val context: Context) {

        @JavascriptInterface
        fun isAccessibilityEnabled(): Boolean {
            return isAccessibilityServiceEnabled()
        }

        @JavascriptInterface
        fun isOverlayEnabled(): Boolean {
            return this@MainActivity.isOverlayEnabled()
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        @JavascriptInterface
        fun openAppInfoSettings() {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Open App Info in Settings > Apps > NovaClaw AI", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun openOverlaySettings() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }

        @JavascriptInterface
        fun downloadModel(url: String, fileName: String): Long {
            return try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = Uri.parse(url)
                val modelsDir = context.getExternalFilesDir("models")
                if (modelsDir != null && !modelsDir.exists()) {
                    modelsDir.mkdirs()
                }

                val request = DownloadManager.Request(uri).apply {
                    setTitle("NovaClaw: Downloading $fileName")
                    setDescription("Gemma 4 / On-Device AI Model Weights")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalFilesDir(context, "models", fileName)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }
                val downloadId = dm.enqueue(request)
                startDownloadWatcher(downloadId, fileName)
                downloadId
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(context, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                -1L
            }
        }

        @JavascriptInterface
        fun checkModelStatus(fileName: String): String {
            val file = File(context.getExternalFilesDir("models"), fileName)
            val json = JSONObject()
            val exists = file.exists() && file.length() > 10L * 1024 * 1024 // Greater than 10MB
            json.put("installed", exists)
            json.put("fileSizeBytes", if (file.exists()) file.length() else 0L)
            json.put("filePath", if (file.exists()) file.absolutePath else "")
            json.put("fileName", fileName)
            return json.toString()
        }

        @JavascriptInterface
        fun deleteModel(fileName: String): Boolean {
            return try {
                val file = File(context.getExternalFilesDir("models"), fileName)
                if (file.exists()) {
                    file.delete()
                } else true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun resolveAppPackage(appName: String): String {
            val pm = packageManager
            val clean = appName.trim().lowercase()
            try {
                val apps = pm.getInstalledApplications(0)
                for (app in apps) {
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    if (label == clean || label.contains(clean) || clean.contains(label)) {
                        if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                            return app.packageName
                        }
                    }
                }
            } catch (e: Exception) {}
            return ""
        }

        @JavascriptInterface
        fun cancelDownload(downloadId: Long) {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(downloadId)
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun startFloatingOverlay() {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.startService(intent)
        }

        @JavascriptInterface
        fun launchApp(targetPackage: String): Boolean {
            return try {
                val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    true
                } else {
                    if (targetPackage.contains("settings", ignoreCase = true)) {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        true
                    } else {
                        false
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun captureScreen(): String {
            val service = NovaAccessibilityService.instance ?: return "{}"
            val data = service.capturePrunedScreen()

            val json = JSONObject()
            json.put("packageName", data.packageName)
            json.put("stateHash", data.stateHash)

            val array = JSONArray()
            for (el in data.elements) {
                val elJson = JSONObject()
                elJson.put("id", el.id)
                elJson.put("type", el.type)
                elJson.put("label", el.label)
                elJson.put("clickable", el.clickable)
                elJson.put("editable", el.editable)
                elJson.put("scrollable", el.scrollable)

                val center = JSONObject()
                center.put("x", el.centerX)
                center.put("y", el.centerY)
                elJson.put("center", center)

                array.put(elJson)
            }
            json.put("elements", array)
            return json.toString()
        }

        @JavascriptInterface
        fun performTap(x: Float, y: Float): Boolean {
            val service = NovaAccessibilityService.instance ?: return false
            return runBlocking {
                service.performTap(x, y)
            }
        }

        @JavascriptInterface
        fun performScroll(direction: String): Boolean {
            val service = NovaAccessibilityService.instance ?: return false
            val isForward = !direction.equals("up", ignoreCase = true)

            // 1. First attempt native scroll action on scrollable container (100% reliable)
            val nativeSuccess = service.scrollScrollable(isForward)
            if (nativeSuccess) return true

            // 2. Fallback to physical touch drag
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()
            val screenHeight = displayMetrics.heightPixels.toFloat()
            val midX = screenWidth / 2f

            return runBlocking {
                if (!isForward) {
                    service.performScroll(midX, screenHeight * 0.25f, midX, screenHeight * 0.75f)
                } else {
                    service.performScroll(midX, screenHeight * 0.75f, midX, screenHeight * 0.25f)
                }
            }
        }

        @JavascriptInterface
        fun performText(nodeId: Int, text: String): Boolean {
            val service = NovaAccessibilityService.instance ?: return false
            return service.performTextEntry(nodeId, text)
        }

        @JavascriptInterface
        fun performBack(): Boolean {
            val service = NovaAccessibilityService.instance ?: return false
            return service.performBack()
        }

        @JavascriptInterface
        fun performHome(): Boolean {
            val service = NovaAccessibilityService.instance ?: return false
            return service.performHome()
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
