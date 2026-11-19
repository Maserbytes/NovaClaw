package ai.novaclaw.ui

import ai.novaclaw.a11y.NovaAccessibilityService
import ai.novaclaw.engine.AgentEvent
import ai.novaclaw.engine.AgentLoop
import ai.novaclaw.model.BackendSelector
import ai.novaclaw.model.LiteRTEngine
import ai.novaclaw.model.ModelCatalog
import ai.novaclaw.safety.SafetyShield
import ai.novaclaw.service.AgentForegroundService
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    internal lateinit var webView: WebView
    internal lateinit var bridge: NovaWebBridge
    private val gson = Gson()

    internal val engine by lazy { LiteRTEngine(applicationContext) }
    internal val cloudEngine by lazy { ai.novaclaw.model.CloudEngine(applicationContext) }
    internal val safety = SafetyShield()
    internal val sessionStore = ai.novaclaw.state.TaskSessionStore()
    internal val history by lazy { ai.novaclaw.state.ConversationStore(applicationContext) }
    internal var agentLoop: AgentLoop? = null

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val internalRunReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, intent: Intent?) {
            val task = intent?.getStringExtra("task") ?: return
            try {
                val model = ModelCatalog.recommended(applicationContext)
                startForegroundTask(task)
                ensureAgentLoop().start(task, model)
            } catch (t: Throwable) {
                sendEvent(ai.novaclaw.engine.AgentEvent.Error(t.message ?: "Automation failed"))
            }
        }
    }

    private val killReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            agentLoop?.stop()
            stopForegroundTask()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ai.novaclaw.engine.PlaybookManager.loadAll(applicationContext)
        ai.novaclaw.util.NovaLog.i("MainActivity", "onCreate: playbooks=${ai.novaclaw.engine.PlaybookManager.all().size}")

        ai.novaclaw.service.AutoReplyManager.replyGenerator = { contact, message ->
            val model = ModelCatalog.recommended(applicationContext)
            if (!ModelCatalog.isInstalled(applicationContext, model)) {
                ai.novaclaw.util.NovaLog.w("AutoReply", "Model not installed; cannot generate reply")
                null
            } else {
                engine.open(ModelCatalog.path(applicationContext, model), preferGpu = false)
                val session = engine.openSession(
                    "You write brief, natural chat replies. Output only the reply text, no commentary.",
                    emptyList(),
                )
                try {
                    session.send("Contact: $contact\nTheir message: $message\nYour reply:").text
                } finally {
                    session.close()
                }
            }
        }

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = WebViewClient()
            setBackgroundColor(0x0D0D0D)
        }

        bridge = NovaWebBridge(this)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        // Android 15 edge-to-edge: keep the chat input above the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, nav.bottom)
            insets
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        if (!NovaAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "Enable NovaClaw in Accessibility to control your phone", Toast.LENGTH_LONG).show()
        }

        androidx.core.content.ContextCompat.registerReceiver(
            this, killReceiver,
            android.content.IntentFilter("ai.novaclaw.KILL_TASK"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this, internalRunReceiver,
            android.content.IntentFilter(ai.novaclaw.automation.ExternalAutomationReceiver.INTERNAL_RUN),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        val a11y = NovaAccessibilityService.isEnabled(this)
        val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        webView.evaluateJavascript(
            "window.updatePermissionStatus && window.updatePermissionStatus($a11y,$overlay);", null
        )
        if (a11y) NovaAccessibilityService.awaitReady(1_000L)
    }

    internal fun sendEvent(event: AgentEvent) {
        val json = gson.toJson(event)
        webView.post {
            webView.evaluateJavascript("window.novaOnEvent && window.novaOnEvent($json);", null)
        }
    }

    internal fun startForegroundTask(goal: String) {
        val intent = Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_START
            putExtra(AgentForegroundService.EXTRA_TITLE, "NovaClaw is working")
            putExtra(AgentForegroundService.EXTRA_TEXT, goal.take(120))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    internal fun updateForegroundTask(text: String) {
        val intent = Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_UPDATE
            putExtra(AgentForegroundService.EXTRA_TEXT, text)
        }
        startService(intent)
    }

    internal fun stopForegroundTask() {
        startService(Intent(this, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_STOP
        })
    }

    internal fun ensureAgentLoop(): AgentLoop {
        val service = NovaAccessibilityService.instance
            ?: error("Accessibility service not connected")
        return agentLoop ?: AgentLoop(
            context = applicationContext,
            engine = engine,
            cloudEngine = cloudEngine,
            accessibility = service,
            tools = ai.novaclaw.engine.ToolRegistry(applicationContext),
            safety = safety,
            sessionStore = sessionStore,
            history = history,
            emit = { event ->
                sendEvent(event)
                if (event is AgentEvent.State && event.name == "IDLE") stopForegroundTask()
                if (event is AgentEvent.Step) updateForegroundTask("Step ${event.index} · ${event.app}")
                if (event is AgentEvent.Complete || event is AgentEvent.Error) stopForegroundTask()
            }
        ).also { agentLoop = it }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(killReceiver) }
        runCatching { unregisterReceiver(internalRunReceiver) }
        agentLoop?.stop()
        scope.cancel()
        super.onDestroy()
    }
}
