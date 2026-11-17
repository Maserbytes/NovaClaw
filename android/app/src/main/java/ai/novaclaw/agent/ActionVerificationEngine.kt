package ai.novaclaw.agent

import kotlinx.coroutines.delay

/**
 * ActionVerificationEngine: Android Native State Diff & Self-Healing Verifier.
 * Differs from PokeClaw by validating each gesture before advancing.
 */
class ActionVerificationEngine(private val settleDelayMs: Long = 350) {

    data class VerificationResult(
        val success: Boolean,
        val status: String,
        val message: String,
        val reflection: String
    )

    suspend fun verify(
        actionType: String,
        preState: PrunedScreenData,
        capturePostState: () -> PrunedScreenData
    ): VerificationResult {
        // Wait for UI settle / animation debounce
        delay(settleDelayMs)

        val postState = capturePostState()
        val isStateChanged = preState.stateHash != postState.stateHash
        val isPackageChanged = preState.packageName != postState.packageName

        // ANR / Modal check
        for (el in postState.elements) {
            val label = el.label.lowercase()
            if (label.contains("isn't responding") || label.contains("close app")) {
                return VerificationResult(
                    success = false,
                    status = "ANR_BLOCKED",
                    message = "System ANR dialog popped up.",
                    reflection = "An ANR dialog is blocking the foreground. Must click 'Wait' or dismiss."
                )
            }
        }

        if (isPackageChanged) {
            return VerificationResult(
                success = true,
                status = "APP_TRANSITION",
                message = "Navigated from ${preState.packageName} to ${postState.packageName}.",
                reflection = "Screen transition complete."
            )
        }

        if (!isStateChanged && actionType == "click") {
            return VerificationResult(
                success = false,
                status = "NO_STATE_CHANGE",
                message = "Tapped element, but no screen change detected.",
                reflection = "Target may be disabled or off-screen. Try scrolling or text-based locator fallback."
            )
        }

        return VerificationResult(
            success = true,
            status = "VERIFIED",
            message = "Action verified. UI state transitioned (hash: ${postState.stateHash}).",
            reflection = "Step verified. Ready for next sub-goal."
        )
    }
}
