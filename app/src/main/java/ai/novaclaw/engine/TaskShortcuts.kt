package ai.novaclaw.engine

/** Tier-1 deterministic shortcuts: zero LLM calls for unambiguous intents. */
object TaskShortcuts {

    data class Shortcut(val tool: String, val args: Map<String, Any>, val summary: String)

    fun match(goal: String): Shortcut? {
        val g = goal.trim().lowercase()
        when (g) {
            "home", "go home", "home screen" ->
                return Shortcut("system_key", mapOf<String, Any>("key" to "home"), "Went home")
            "back", "go back", "press back" ->
                return Shortcut("system_key", mapOf<String, Any>("key" to "back"), "Pressed back")
        }
        val m = Regex("^(open|launch|start)\\s+(.+)$").find(g) ?: return null
        val rest = m.groupValues[2].trim()
        // Only a pure "open <app>" is a shortcut. Any second action means the
        // model must handle the whole task; otherwise we open the app and stop.
        val extraActions = Regex(
            "(?i)\\b(and|then|send|message|text|tell|reply|search|find|turn|set|" +
                "enable|disable|check|read|for|with|to)\\b"
        )
        if (extraActions.containsMatchIn(rest)) return null
        if (rest.split(Regex("\\s+")).size > 3) return null
        return Shortcut("open_app", mapOf<String, Any>("app_name" to rest), "Opened $rest")
    }
}
