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
        val app = m.groupValues[2].trim()
        return if (app.isNotBlank()) Shortcut("open_app", mapOf<String, Any>("app_name" to app), "Opened $app") else null
    }
}
