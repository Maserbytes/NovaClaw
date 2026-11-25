package ai.novaclaw.engine

import ai.novaclaw.model.ToolCall
import com.google.gson.Gson
import com.google.gson.JsonParser

/** Compact model output for a single observed turn. It carries no authority to execute a tool. */
internal object TaskDecision {
    private val gson = Gson()
    val blocked = ToolSpec("blocked", "Report what prevented the task.", ToolSpec.obj(
        "Reason", mapOf("summary" to "Specific reason the task is blocked"), listOf("summary")))

    fun instructions(tools: List<ToolSpec>): String = buildString {
        appendLine("""Return exactly one JSON object of the form {"tool":"tool_name","args":{"argument":"value"}}.""")
        appendLine("Choose one of these tools and use only its named string arguments:")
        tools.forEach { tool -> appendLine("${tool.name}(${required(tool).joinToString()}) - ${tool.description}") }
        append("Do not greet, plan in prose, or claim success without evidence. Use blocked when you cannot proceed.")
    }

    fun parse(text: String?, tools: List<ToolSpec>): ToolCall? {
        val output = text?.trim()?.takeIf { it.length in 2..4096 } ?: return null
        val json = if (output.startsWith("```json\n") && output.endsWith("```")) {
            output.removePrefix("```json\n").removeSuffix("```").trim()
        } else output
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            if (root.keySet() != setOf("tool", "args")) return null
            val tool = root.getAsJsonPrimitive("tool")?.takeIf { it.isString }?.asString ?: return null
            val args = root.getAsJsonObject("args").entrySet().associate { (key, value) ->
                key to value.asJsonPrimitive.takeIf { it.isString }?.asString.orEmpty()
            }
            ToolCall(tool, args).takeIf { accepts(it, tools) }
        }.getOrNull()
    }

    fun accepts(call: ToolCall, tools: List<ToolSpec>): Boolean {
        val spec = tools.firstOrNull { it.name == call.name } ?: return false
        val properties = spec.parameters["properties"] as? Map<*, *> ?: return false
        return call.arguments.keys.all { it in properties } &&
            call.arguments.values.all { it is String && it.isNotBlank() } &&
            call.arguments.keys.containsAll(required(spec))
    }

    fun canFinish(call: ToolCall, readScreen: Boolean, actionSucceeded: Boolean): Boolean {
        val summary = call.arguments["summary"] as? String ?: return false
        return (readScreen || actionSucceeded) &&
            !Regex("(?i)\\b(how (can|may) i (help|assist)|what can i do for you)\\b").containsMatchIn(summary)
    }

    fun observation(tool: String, target: String, result: ToolResult): String = gson.toJson(
        mapOf("tool" to tool, "target" to target.take(80), "ok" to result.success,
            "message" to result.message.take(600)),
    )

    private fun required(tool: ToolSpec): List<String> =
        (tool.parameters["required"] as? List<*>)?.filterIsInstance<String>().orEmpty()
}
