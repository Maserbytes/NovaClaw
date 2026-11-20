package ai.novaclaw.engine

/**
 * Tier-1 deterministic routing. PokeClaw's own QA found that small local models
 * waste 2+ minutes of inference on questions Android can answer directly, and
 * frequently deny device facts they actually have access to. Answer those
 * locally in milliseconds and reserve the LLM for real UI work.
 */
object PipelineRouter {

    sealed class Route {
        data object Agent : Route()
        data object DeviceInfo : Route()
    }

    private val deviceInfoPatterns = listOf(
        "(?i)\\b(battery|charge|charging|power level)\\b",
        "(?i)\\b(storage|disk space|free space|how much space)\\b",
        "(?i)\\b(ram|memory|how much memory)\\b",
        "(?i)\\b(android version|which android|os version)\\b",
        "(?i)\\b(device model|what phone|which phone|model number)\\b",
        "(?i)\\b(wifi|wi-fi|network connection|connected to)\\b",
        "(?i)\\b(temperature|phone temp)\\b",
    )

    fun route(goal: String): Route {
        // Only pure information requests go deterministic. If the user also asks
        // to change something, fall through to the agent loop.
        val actionWords = Regex("(?i)\\b(open|turn|set|send|tap|click|install|enable|disable|change|navigate|search)\\b")
        if (actionWords.containsMatchIn(goal)) return Route.Agent
        // "Why is my battery low?" needs reasoning, not a raw stat dump.
        if (Regex("(?i)^\\s*(why|how|what if|should|can you explain)\\b").containsMatchIn(goal)) return Route.Agent
        return if (deviceInfoPatterns.any { Regex(it).containsMatchIn(goal) }) Route.DeviceInfo
        else Route.Agent
    }
}
