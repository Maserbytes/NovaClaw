package ai.novaclaw.engine

/**
 * Detects greetings and small talk that need an answer, not phone control.
 * Without this, a small model sees the word "Hi" in the chat transcript and
 * tries to tap it as a screen element instead of replying.
 */
object ConversationRouter {

    private val actionWords = Regex(
        "(?i)\\b(open|launch|start|send|message|text|call|tap|click|scroll|search|find|" +
            "turn|switch|set|enable|disable|install|navigate|go|check|read|write|delete|buy|pay)\\b"
    )

    private val conversational = Regex(
        "(?i)^\\s*(hi+|hello+|hey+|yo+|yo\\W|good\\s*(morning|afternoon|evening)|" +
            "how('s| is)\\s*(it\\s*)?going|how\\s*are\\s*you|what'?s\\s*up|sup|" +
            "thanks?|thank\\s*you|who\\s*are\\s*you|what\\s*can\\s*you\\s*do|" +
            "are\\s*you\\s*(there|ok|okay)|hi\\s*there|hello\\s*there)\\W*\\??\\s*$"
    )

    fun isConversational(goal: String): Boolean {
        val g = goal.trim()
        if (g.length > 140) return false
        if (actionWords.containsMatchIn(g)) return false
        if (conversational.containsMatchIn(g)) return true
        // Short questions without any device verbs are also plain conversation.
        return g.length <= 80 && g.endsWith("?") && !actionWords.containsMatchIn(g)
    }
}
