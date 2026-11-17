package ai.novaclaw.agent

import android.annotation.SuppressLint
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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * MainActivity: Android Shell hosting the smooth, whitish, glassy NovaClaw UI.
 * Connects the web client to the native Android Accessibility Service and Safety Guard.
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

        // Prompt user immediately on first launch if accessibility service is missing
        checkAndPromptPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Notify the web interface of updated permission status
        val isA11y = isAccessibilityServiceEnabled()
        val isOverlay = isOverlayEnabled()
        webView.evaluateJavascript("window.updatePermissionStatus && window.updatePermissionStatus($isA11y, $isOverlay);", null)
    }

    private fun checkAndPromptPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable NovaClaw in Accessibility Settings to control phone safely", Toast.LENGTH_LONG).show()
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
                    // Try generic intent for common apps
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
