package ai.novaclaw.engine

import ai.novaclaw.a11y.ActionOutcome
import ai.novaclaw.a11y.NovaAccessibilityService
import ai.novaclaw.service.NovaNotificationListener
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.delay

/** High-level device tools that replace long blind UI navigation chains. */
object DeviceTools {

    fun notifications(): String {
        val notifier = NovaNotificationListener.instance
            ?: return "Notification access is not enabled. Open NovaClaw settings to grant it."
        val list = notifier.recent()
        return if (list.isEmpty()) "No active notifications."
        else list.joinToString("\n")
    }

    fun installedApps(context: Context): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { pm.getApplicationLabel(it).toString() to it.packageName }
            .sortedBy { it.first.lowercase() }
        return apps.take(80).joinToString("\n") { "${it.first} (${it.second})" }
    }

    fun clipboardGet(context: Context): String {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = manager?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        return if (text.isBlank()) "Clipboard is empty." else text.take(2000)
    }

    fun clipboardSet(context: Context, text: String): Boolean = try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("novaclaw", text))
        true
    } catch (_: Exception) {
        false
    }

    /**
     * App-agnostic message send: open app → find contact → type → press a real
     * send control → verify the composer cleared. Refuses call/video/profile
     * controls, which was PokeClaw issue #2's worst failure.
     */
    suspend fun sendMessage(context: Context, to: String, message: String): ActionOutcome {
        val service = NovaAccessibilityService.instance
            ?: return ActionOutcome(false, "Accessibility service not connected", "", "", 0L)

        val hasApp = to.contains(" on ", ignoreCase = true)
        val appName = if (hasApp) to.substringAfter(" on ").trim() else "WhatsApp"
        val contact = if (hasApp) to.substringBefore(" on ").trim() else to.trim()
        if (contact.isBlank() || message.isBlank()) {
            return ActionOutcome(false, "send_message needs a contact and a message", "", "", 0L)
        }

        val pkg = service.resolvePackage(appName) ?: appName
        val opened = service.openApp(pkg)
        if (!opened.success) return opened
        delay(500)

        var snap = service.captureScreen("find $contact")
            ?: return ActionOutcome(false, "Could not read $appName", "", "", 0L)
        var target = snap.nodes.firstOrNull { it.clickable && it.label.contains(contact, true) }
        repeat(5) {
            if (target != null) return@repeat
            service.scroll("down", snap.generation)
            delay(400)
            snap = service.captureScreen("find $contact")
                ?: return@repeat
            target = snap.nodes.firstOrNull { it.clickable && it.label.contains(contact, true) }
        }
        if (target == null) {
            return ActionOutcome(false, "Contact '$contact' not found in $appName after 5 scrolls", "", "", 0L)
        }

        val selected = service.tapNode(target.id, snap.generation)
        if (!selected.success) return selected
        delay(500)

        snap = service.captureScreen("compose message")
            ?: return ActionOutcome(false, "Could not read the chat", "", "", 0L)
        val field = snap.nodes.firstOrNull { it.editable }
            ?: return ActionOutcome(false, "Message field not found", "", "", 0L)
        val typed = service.setText(field.id, snap.generation, message)
        if (!typed.success) return typed
        delay(300)

        snap = service.captureScreen("send message")
            ?: return ActionOutcome(false, "Could not read the composer", "", "", 0L)
        val send = snap.nodes.firstOrNull { n ->
            n.clickable && (
                n.label.contains("send", true) || n.label.contains("➤") ||
                    n.label.contains("arrow", true) || n.label.equals("➡")
                ) && !n.label.contains("call", true) && !n.label.contains("video", true)
        } ?: return ActionOutcome(false, "No safe send control found — refusing ambiguous tap", "", "", 0L)

        val sent = service.tapNode(send.id, snap.generation)
        delay(500)
        val after = service.captureScreen("verify send")
        val composerCleared = after?.nodes?.firstOrNull { it.editable }?.label.isNullOrBlank()
        return ActionOutcome(
            sent.success && composerCleared == true,
            if (sent.success && composerCleared == true) "Message sent and verified"
            else "Send tap dispatched but composer was not cleared — verify manually",
            sent.preFingerprint, sent.postFingerprint, sent.elapsedMs,
        )
    }
}
