package ai.novaclaw.agent

import android.annotation.SuppressLint
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
            }
            webViewClient = WebViewClient()
            addJavascriptInterface(NovaWebBridge(), "AndroidBridge")
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        checkPermissions()
    }

    private fun checkPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Enable NovaClaw in Accessibility Settings to control phone safely", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedServiceName = "$packageName/${NovaAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabledServices.contains(expectedServiceName)
    }

    inner class NovaWebBridge {
        @JavascriptInterface
        fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        @JavascriptInterface
        fun openOverlaySettings() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }

        @JavascriptInterface
        fun startFloatingOverlay() {
            val intent = Intent(this@MainActivity, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}
