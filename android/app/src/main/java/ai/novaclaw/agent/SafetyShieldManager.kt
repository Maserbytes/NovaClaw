package ai.novaclaw.agent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * SafetyShieldManager: Native Android Quad-Shield Safety Guardian.
 * Protects users from phone bricking, accidental purchases, or unauthorized data loss.
 */
class SafetyShieldManager {

    private val isEmergencyKilled = AtomicBoolean(false)
    private val sessionApprovals = mutableSetOf<String>()

    private val blacklistedPackages = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.android.vending",
        "com.paypal.android.p2pmobile",
        "com.google.android.apps.walletnfcrel",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.binance.dev",
        "com.coinbase.android",
        "com.x8bit.bitwarden",
        "com.onepassword.android"
    )

    private val destructivePatterns = listOf(
        Pattern.compile("\\b(delete|erase|format|wipe|uninstall|destroy)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(clear\\s+all\\s+data|clear\\s+storage|remove\\s+account)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(pay|transfer|send\\s+money|buy\\s+now|confirm\\s+payment)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(factory\\s+reset|reset\\s+phone)\\b", Pattern.CASE_INSENSITIVE)
    )

    data class SafetyEvaluation(
        val allowed: Boolean,
        val reason: String,
        val riskLevel: RiskLevel,
        val requiresConfirmation: Boolean,
        val approvalKey: String? = null
    )

    enum class RiskLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }

    fun evaluate(actionType: String, targetLabel: String?, packageName: String): SafetyEvaluation {
        if (isEmergencyKilled.get()) {
            return SafetyEvaluation(
                allowed = false,
                reason = "Emergency Kill-Switch is ENGAGED.",
                riskLevel = RiskLevel.CRITICAL,
                requiresConfirmation = false
            )
        }

        // 1. Blacklist check
        if (blacklistedPackages.contains(packageName.lowercase())) {
            val key = "pkg:$packageName"
            if (!sessionApprovals.contains(key)) {
                return SafetyEvaluation(
                    allowed = false,
                    reason = "Target package '$packageName' is a protected system/finance application.",
                    riskLevel = RiskLevel.HIGH,
                    requiresConfirmation = true,
                    approvalKey = key
                )
            }
        }

        // 2. Destructive semantic pattern check
        val combinedText = "$actionType ${targetLabel ?: ""}"
        for (pattern in destructivePatterns) {
            if (pattern.matcher(combinedText).find()) {
                val key = "action:$actionType:$targetLabel"
                if (!sessionApprovals.contains(key)) {
                    return SafetyEvaluation(
                        allowed = false,
                        reason = "Destructive operation detected: '$targetLabel'",
                        riskLevel = RiskLevel.CRITICAL,
                        requiresConfirmation = true,
                        approvalKey = key
                    )
                }
            }
        }

        return SafetyEvaluation(
            allowed = true,
            reason = "Action approved by safety rules.",
            riskLevel = RiskLevel.NONE,
            requiresConfirmation = false
        )
    }

    fun approveAction(key: String) {
        sessionApprovals.add(key)
    }

    fun triggerKillSwitch() {
        isEmergencyKilled.set(true)
    }

    fun reset() {
        isEmergencyKilled.set(false)
        sessionApprovals.clear()
    }
}
