package ai.novaclaw.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Notification access for deterministic reads and later auto-reply. */
class NovaNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NovaNotificationListener? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val title = sbn.notification.extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = (sbn.notification.extras.getCharSequence("android.bigText")
            ?: sbn.notification.extras.getCharSequence("android.text"))?.toString().orEmpty()
        AutoReplyManager.onNotificationPosted(applicationContext, sbn.packageName, title, text)
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    fun recent(limit: Int = 15): List<String> {
        val active = activeNotifications ?: return emptyList()
        return active
            .filter { it.packageName != packageName }
            .sortedByDescending { it.notification.`when` }
            .take(limit)
            .map { sbn: StatusBarNotification ->
                val title = sbn.notification.extras.getCharSequence("android.title")?.toString().orEmpty()
                val text = sbn.notification.extras.getCharSequence("android.bigText")
                    ?: sbn.notification.extras.getCharSequence("android.text")
                "${sbn.packageName}: $title — ${text?.toString().orEmpty()}".take(200)
            }
    }

    fun dismiss(pkg: String) {
        activeNotifications?.filter { it.packageName == pkg }?.forEach { cancelNotification(it.key) }
    }
}
