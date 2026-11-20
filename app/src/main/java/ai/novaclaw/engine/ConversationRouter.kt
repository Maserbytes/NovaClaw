package ai.novaclaw.engine

/**
 * General-purpose chat vs phone-action routing.
 *
 * The default must be NORMAL LLM CHAT: questions, explanations, reasoning,
 * writing, small talk. Phone tools are only exposed when the user explicitly
 * asks the app to interact with the device.
 */
object ConversationRouter {

    /** Verbs that genuinely require operating the phone. */
    private val phoneAction = Regex(
        "(?i)\\b(open|launch|start|send|text|call|dial|tap|click|swipe|scroll|" +
            "install|uninstall|navigate|go to|turn on|turn off|switch on|switch off|" +
            "enable|disable|search in|look up in|message|reply to|check my|read my|" +
            "take a screenshot|what'?s on (my|the) screen|play|pause|stop music)\\b"
    )

    /** A pure "open <app>" request is handled instantly without inference. */
    fun isPureOpen(goal: String): Boolean {
        val g = goal.trim().lowercase()
        val m = Regex("^(open|launch|start)\\s+(.+)$").find(g) ?: return false
        val rest = m.groupValues[2].trim()
        if (Regex("(?i)\\b(and|then|send|message|text|tell|reply|search|find|turn|set|for|with|to)\\b")
                .containsMatchIn(rest)) return false
        return rest.split(Regex("\\s+")).size <= 3
    }

    /**
     * True when this is a normal LLM request that needs no phone control.
     * Greetings, questions, explanations, writing, coding, reasoning — all chat.
     */
    fun isChatOnly(goal: String): Boolean = !phoneAction.containsMatchIn(goal)
}
