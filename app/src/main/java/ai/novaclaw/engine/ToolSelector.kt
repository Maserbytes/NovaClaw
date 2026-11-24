package ai.novaclaw.engine

/**
 * Dynamic tool working set. Field data shows Gemma 4 E2B degrades as schemas
 * accumulate; we declare only the 3-6 tools relevant to the current request.
 */
object ToolSelector {

    fun select(goal: String, all: List<ToolSpec>): List<ToolSpec> {
        val g = goal.lowercase()
        fun pick(vararg names: String): List<ToolSpec> =
            names.mapNotNull { name -> all.firstOrNull { it.name == name } }

        // Higher-priority intent checks must run before the device-info route:
        // "Remember my wifi password" must not collapse into a battery/RAM query.
        val selected = when {
            g.contains("skill") || g.contains("mcp") ->
                pick("web_search", "skill_install", "skill_list", "skill_load",
                    "mcp_add", "mcp_list", "mcp_call", "finish")
            g.contains("remember") || g.contains("note ") || g.contains("todo") || g.contains("my notes") ->
                pick("kb_write", "kb_read", "kb_search", "kb_todo", "kb_list", "finish")
            g.contains("notification") || g.contains("messages I missed") ->
                pick("get_notifications", "finish")
            g.contains("clipboard") || g.contains("copy ") ->
                pick("get_clipboard", "set_clipboard", "finish")
            g.contains("installed") || g.contains("what apps") ->
                pick("get_installed_apps", "finish")
            PipelineRouter.route(goal) == PipelineRouter.Route.DeviceInfo ->
                pick("get_device_info", "finish")
            g.contains("send ") || g.contains("message ") || g.contains("reply ") || g.contains("tell ") ->
                pick("send_message", "open_app", "finish")
            else ->
                pick("open_app", "tap_node", "t", "scroll", "system_key", "web_search", "finish")
        }
        return selected.ifEmpty { pick("open_app", "tap_node", "t", "scroll", "system_key", "web_search", "finish") }
    }
}
