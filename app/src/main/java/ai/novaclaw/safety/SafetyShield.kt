package ai.novaclaw.safety

import ai.novaclaw.a11y.ScreenSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class SafetyVerdict(
    val allowed: Boolean,
    val requiresConfirmation: Boolean = false,
    val reason: String = "",
    val approvalKey: String? = null,
)

/**
 * Deterministic guard rails independent of the LLM. Screen/app content is
 * untrusted context; it can never raise its own privilege.
 */
class SafetyShield {

    private val approvalKeys = ConcurrentHashMap<String, Boolean>()
    private val sequence = AtomicLong(0)

    private val protectedPackages = listOf(
        "com.android.settings", "com.android.vending",
        "com.google.android.apps.walletnfcrel", "com.paypal",
        "com.binance", "com.coinbase", "org.telegram.messenger",
    )

    private val destructivePatterns = listOf(
        "(?i)\\b(delete|wipe|erase|format|reset|factory reset)\\b.*\\b(account|data|phone|device|everything)\\b",
        "(?i)\\b(pay|transfer|send money|purchase|buy|checkout)\\b",
        "(?i)\\b(remove|revoke)\\b.*\\b(admin|device owner|permission)\\b",
        "(?i)\\b(disable)\\b.*\\b(accessibility|safety|antivirus)\\b",
    )

    fun evaluate(
        tool: String,
        args: Map<String, Any?>,
        snapshot: ScreenSnapshot?,
    ): SafetyVerdict {
        val target = snapshot?.packageName.orEmpty()
        val label = snapshot?.appName.orEmpty()

        if (tool == "open_app") {
            val app = args["app_name"]?.toString().orEmpty()
            if (protectedPackages.any { app.contains(it, true) } ||
                app.contains("bank", true) || app.contains("wallet", true) ||
                app.contains("settings", true)
            ) {
                return requireConfirmation("Protected app launch: $app", "$tool -> $app")
            }
        }

        val nodeLabel = args["node_id"]?.toString()?.let { snapshot?.node(it)?.label }.orEmpty()
        if (tool == "send_message" || (tool == "tap_node" && Regex("(?i)^(send|post|publish)(\\b|_)").containsMatchIn(nodeLabel))) {
            return requireConfirmation("Confirm this action before it leaves your device", tool)
        }
        val text = (args.values.joinToString(" ") { it?.toString().orEmpty() } + " " + nodeLabel).trim()
        destructivePatterns.firstOrNull { Regex(it).containsMatchIn(text) }?.let {
            return requireConfirmation("Destructive or financial action detected", "$tool -> $text")
        }

        if (protectedPackages.any { target.contains(it, true) } && tool in setOf("tap_node", "tap", "input_text", "t")) {
            return SafetyVerdict(
                allowed = false,
                reason = "Action blocked inside protected app: $label ($target)",
            )
        }

        return SafetyVerdict(allowed = true)
    }

    private fun requireConfirmation(reason: String, target: String): SafetyVerdict {
        val key = "approval-${sequence.incrementAndGet()}"
        approvalKeys[key] = false
        return SafetyVerdict(false, true, reason, key)
    }

    fun approve(key: String) { approvalKeys[key] = true }
    fun deny(key: String) { approvalKeys.remove(key) }
    fun isApproved(key: String): Boolean = approvalKeys[key] == true
    fun reset() = approvalKeys.clear()
}
