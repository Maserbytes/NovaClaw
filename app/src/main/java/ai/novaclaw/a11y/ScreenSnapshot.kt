package ai.novaclaw.a11y

import android.graphics.Rect

/** A single interactive element in the compact Set-of-Marks map. */
data class UiNode(
    val id: String,
    val label: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val bounds: Rect,
    val centerX: Int,
    val centerY: Int,
) {
    val type: String
        get() = when {
            editable -> "input"
            scrollable -> "scroll"
            clickable -> "button"
            className.contains("Text", true) -> "text"
            else -> "view"
        }
}

/**
 * Immutable observation. `generation` binds an action to the exact screen the
 * model reasoned over, so a stale node id can never silently tap the wrong thing.
 */
data class ScreenSnapshot(
    val generation: Long,
    val packageName: String,
    val appName: String,
    val nodes: List<UiNode>,
    val fingerprint: String,
    val capturedAt: Long = System.currentTimeMillis(),
) {
    /** Compact prompt, ~250 tokens: Set-of-Marks instead of a raw 15k-token tree. */
    fun compactPrompt(goal: String): String = buildString {
        append("GOAL: ").append(goal).append('\n')
        append("APP: ").append(appName).append(" (").append(packageName).append(")\n")
        append("SCREEN [").append(generation).append("]:\n")
        nodes.take(MAX_PROMPT_NODES).forEach { n ->
            val flags = buildString {
                if (n.clickable) append('C')
                if (n.editable) append('E')
                if (n.scrollable) append('S')
                if (isEmpty()) append('-')
            }
            append('[').append(n.id).append("] \"")
                .append(n.label.take(64).replace('\n', ' '))
                .append("\" ").append(n.type).append(' ').append(flags)
                .append(" @").append(n.centerX).append(',').append(n.centerY).append('\n')
        }
        append("RULES: one tool per turn; use tap_node with the id from this screen; ")
        append("re-observe after each action; call finish(summary) when done.")
    }

    fun node(id: String): UiNode? = nodes.firstOrNull { it.id.equals(id, true) }

    companion object {
        const val MAX_PROMPT_NODES = 40
    }
}

/** Outcome of an executed gesture — never a bare Boolean. */
data class ActionOutcome(
    val success: Boolean,
    val reason: String,
    val preFingerprint: String,
    val postFingerprint: String,
    val elapsedMs: Long,
) {
    val changed: Boolean get() = preFingerprint != postFingerprint
}
