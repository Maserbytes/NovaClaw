package ai.novaclaw.engine

import ai.novaclaw.a11y.ActionOutcome
import ai.novaclaw.a11y.NovaAccessibilityService
import ai.novaclaw.a11y.ScreenSnapshot
import android.content.Context
import ai.novaclaw.knowledge.KBManager
import ai.novaclaw.skill.SkillManager
import ai.novaclaw.mcp.McpClient
import java.util.concurrent.TimeUnit

/**
 * Phone-control tools.
 *
 * Schemas are deliberately 1-argument wherever possible: field data
 * (LiteRT-LM issue #2202) shows Gemma 4 tool-call pass rates fall from ~90 %
 * at 0-1 args to 0 % at 4 args. The screen generation is bound internally to
 * the snapshot captured for the turn, never supplied by the model.
 */
class ToolRegistry(private val context: Context) {
    private val kb = KBManager(context)
    private val skills = SkillManager(context)
    private val mcp = McpClient(context)

    val specs: List<ToolSpec> = listOf(
        ToolSpec("open_app", "Launch an installed app by its name.", ToolSpec.obj(
            "App name, e.g. WhatsApp", mapOf("app_name" to "App name or package"), listOf("app_name"))),
        ToolSpec("tap_node", "Tap an element by the id shown in the current screen map.", ToolSpec.obj(
            "Element id", mapOf("node_id" to "Id like n3 from the screen map"), listOf("node_id"))),
        ToolSpec("tap", "Tap exact screen coordinates when no id is available.", ToolSpec.obj(
            "Coordinates", mapOf("at" to "Coordinates as x,y"), listOf("at"))),
        ToolSpec("t", "Type text into the focused field.", ToolSpec.obj(
            "Text", mapOf("text" to "Text to type"), listOf("text"))),
        ToolSpec("scroll", "Scroll the screen.", ToolSpec.obj(
            "Direction", mapOf("direction" to "up or down"), listOf("direction"))),
        ToolSpec("system_key", "Press a system navigation key.", ToolSpec.obj(
            "Key", mapOf("key" to "back, home or enter"), listOf("key"))),
        ToolSpec("get_device_info", "Read battery, RAM, storage, network and device facts instantly.", ToolSpec.obj(
            "No arguments")),
        ToolSpec("see_screen", "Capture the real screen pixels and answer a question about what is visible.", ToolSpec.obj(
            "Question", mapOf("question" to "What to look for on screen"), listOf("question"))),
        ToolSpec("web_search", "Search the live web for current information. Returns titles, links and snippets.", ToolSpec.obj(
            "Query", mapOf("query" to "Search query"), listOf("query"))),
        ToolSpec("fetch_url", "Read a web page as plain text.", ToolSpec.obj(
            "URL", mapOf("url" to "http/https URL"), listOf("url"))),
        ToolSpec("skill_install", "Download and install a markdown skill from a URL.", ToolSpec.obj(
            "URL", mapOf("url" to "Skill .md URL"), listOf("url"))),
        ToolSpec("skill_list", "List installed skills.", ToolSpec.obj("No arguments")),
        ToolSpec("skill_load", "Load an installed skill's instructions.", ToolSpec.obj(
            "Name", mapOf("name" to "Skill name"), listOf("name"))),
        ToolSpec("mcp_add", "Add a remote HTTP MCP server by name and URL.", ToolSpec.obj(
            "Server", mapOf("name" to "Name", "url" to "HTTP endpoint"), listOf("name", "url"))),
        ToolSpec("mcp_list", "List configured MCP servers.", ToolSpec.obj("No arguments")),
        ToolSpec("mcp_call", "Call a tool on a configured MCP server.", ToolSpec.obj(
            "Call", mapOf("server" to "Server name", "tool" to "Tool name", "args" to "JSON arguments"),
            listOf("server", "tool"))),
        ToolSpec("get_notifications", "Read current notifications.", ToolSpec.obj("No arguments")),
        ToolSpec("get_installed_apps", "List installed apps with their labels and packages.", ToolSpec.obj("No arguments")),
        ToolSpec("get_clipboard", "Read the clipboard.", ToolSpec.obj("No arguments")),
        ToolSpec("set_clipboard", "Copy text to the clipboard.", ToolSpec.obj(
            "Text", mapOf("text" to "Text to copy"), listOf("text"))),
        ToolSpec("send_message", "Send a message to a contact. to='Name on WhatsApp', text='message'.", ToolSpec.obj(
            "Recipient", mapOf("to" to "Contact, optionally 'on App'", "text" to "Message"), listOf("to", "text"))),
        ToolSpec("kb_write", "Save a note in local memory.", ToolSpec.obj(
            "Note", mapOf("name" to "File name", "content" to "Markdown content"), listOf("name", "content"))),
        ToolSpec("kb_read", "Read a note from local memory.", ToolSpec.obj(
            "Note", mapOf("name" to "File name"), listOf("name"))),
        ToolSpec("kb_search", "Search local memory.", ToolSpec.obj(
            "Query", mapOf("query" to "Search text"), listOf("query"))),
        ToolSpec("kb_todo", "Add a todo to local memory.", ToolSpec.obj(
            "Todo", mapOf("text" to "Todo text"), listOf("text"))),
        ToolSpec("kb_list", "List saved notes.", ToolSpec.obj("No arguments")),
        ToolSpec("finish", "Finish the task with a short summary.", ToolSpec.obj(
            "Summary", mapOf("summary" to "What happened, or the answer"), listOf("summary"))),
    )

    suspend fun execute(
        tool: String,
        args: Map<String, Any?>,
        snapshot: ScreenSnapshot?,
    ): ToolResult {
        val service = NovaAccessibilityService.instance
        if (service == null && tool in setOf("open_app", "tap_node", "tap", "t", "scroll", "system_key", "see_screen", "send_message")) {
            return ToolResult(false, "Accessibility service is not connected")
        }
        val generation = snapshot?.generation

        val outcome: ActionOutcome? = when (tool) {
            "open_app" -> service!!.openApp(service.resolvePackage(args["app_name"]?.toString().orEmpty())
                ?: args["app_name"]?.toString().orEmpty())

            "tap_node" -> {
                val id = args["node_id"]?.toString().orEmpty()
                val node = snapshot?.node(id)
                when {
                    node == null -> ActionOutcome(false, "Unknown node $id — re-observe the screen", "", "", 0L)
                    !node.clickable -> ActionOutcome(
                        false, "Node $id (${node.label.take(30)}) is not clickable — refusing ambiguous tap", "", "", 0L)
                    node.label.isBlank() -> ActionOutcome(
                        false, "Node $id has no label — refusing ambiguous tap", "", "", 0L)
                    else -> service!!.tapNode(id, generation)
                }
            }

            "tap" -> {
                val parts = args["at"]?.toString()?.split(",")
                val x = parts?.getOrNull(0)?.trim()?.toIntOrNull()
                val y = parts?.getOrNull(1)?.trim()?.toIntOrNull()
                if (x == null || y == null) ActionOutcome(false, "tap needs at=\"x,y\"", "", "", 0L)
                else service!!.tapAt(x, y)
            }

            "t" -> {
                val text = args["text"]?.toString().orEmpty()
                service!!.typeIntoFocused(text, generation)
            }

            "scroll" -> service!!.scroll(args["direction"]?.toString() ?: "down", generation)

            "system_key" -> when (args["key"]?.toString()?.lowercase()) {
                "back" -> service!!.back()
                "home" -> service!!.home()
                "enter" -> service!!.submitFocusedField(generation)
                else -> return ToolResult(false, "Unsupported key")
            }

            "get_device_info" -> ActionOutcome(true, DeviceInfoTool.read(context), "", "", 0L)
            "see_screen" -> {
                val question = args["question"]?.toString()?.ifBlank { "Describe this screen." }
                    ?: "Describe this screen."
                val answer = ScreenVision.ask?.invoke(question)
                    ?: "Vision is not available for this model."
                ActionOutcome(
                    !answer.startsWith("Vision is not available", true) &&
                        !answer.startsWith("Screenshot unavailable", true),
                    answer, "", "", 0L)
            }
            "web_search" -> ActionOutcome(true,
                WebSearchTool.search(args["query"]?.toString().orEmpty()), "", "", 0L)
            "fetch_url" -> ActionOutcome(true,
                WebSearchTool.fetch(args["url"]?.toString().orEmpty()), "", "", 0L)
            "skill_install" -> ActionOutcome(true,
                skills.installFromUrl(args["url"]?.toString().orEmpty()), "", "", 0L)
            "skill_list" -> ActionOutcome(true, skills.list(), "", "", 0L)
            "skill_load" -> ActionOutcome(true,
                skills.load(args["name"]?.toString().orEmpty()), "", "", 0L)
            "mcp_add" -> ActionOutcome(true,
                mcp.add(args["name"]?.toString().orEmpty(), args["url"]?.toString().orEmpty()), "", "", 0L)
            "mcp_list" -> ActionOutcome(true, mcp.list(), "", "", 0L)
            "mcp_call" -> ActionOutcome(true,
                mcp.call(
                    args["server"]?.toString().orEmpty(),
                    args["tool"]?.toString().orEmpty(),
                    args["args"]?.toString().takeIf { !it.isNullOrBlank() } ?: "{}",
                ), "", "", 0L)
            "get_notifications" -> ActionOutcome(true, DeviceTools.notifications(), "", "", 0L)
            "get_installed_apps" -> ActionOutcome(true, DeviceTools.installedApps(context), "", "", 0L)
            "get_clipboard" -> ActionOutcome(true, DeviceTools.clipboardGet(context), "", "", 0L)
            "set_clipboard" -> {
                val ok = DeviceTools.clipboardSet(context, args["text"]?.toString().orEmpty())
                ActionOutcome(ok, if (ok) "Clipboard set" else "Clipboard failed", "", "", 0L)
            }
            "send_message" -> DeviceTools.sendMessage(
                context,
                args["to"]?.toString().orEmpty(),
                args["text"]?.toString().orEmpty(),
            )
            "kb_write" -> boolOutcome(kb.write(
                args["name"]?.toString().orEmpty(), args["content"]?.toString().orEmpty()), "Note saved")
            "kb_read" -> ActionOutcome(true, kb.read(args["name"]?.toString().orEmpty()), "", "", 0L)
            "kb_search" -> ActionOutcome(true, kb.search(args["query"]?.toString().orEmpty()), "", "", 0L)
            "kb_todo" -> boolOutcome(kb.todo(args["text"]?.toString().orEmpty()), "Todo added")
            "kb_list" -> ActionOutcome(true, kb.list(), "", "", 0L)

            else -> return ToolResult(false, "Unknown tool: $tool")
        }

        return if (outcome == null) ToolResult(false, "Tool produced no outcome")
        else ToolResult(outcome.success, outcome.messageOrReason(), outcome)
    }

    private fun ActionOutcome.messageOrReason(): String = reason

    private fun boolOutcome(ok: Boolean, label: String) = ActionOutcome(
        ok, if (ok) label else "$label failed", "", "", 0L)

    private fun typeText(text: String): ActionOutcome {
        if (text.isBlank()) return ActionOutcome(false, "Empty text", "", "", 0L)
        val started = System.currentTimeMillis()
        var process: Process? = null
        val ok = try {
            val escaped = text.replace(" ", "%s").replace("'", "\\'")
            process = Runtime.getRuntime().exec(arrayOf("input", "text", escaped))
            process!!.waitFor(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
        }
        return ActionOutcome(ok, if (ok) "Text typed" else "Text entry failed", "", "",
            System.currentTimeMillis() - started)
    }
}
