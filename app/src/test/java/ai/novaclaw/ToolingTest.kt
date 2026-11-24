package ai.novaclaw

import ai.novaclaw.engine.BudgetStatus
import ai.novaclaw.engine.StuckDetector
import ai.novaclaw.engine.TaskBudget
import ai.novaclaw.engine.TokenMonitor
import ai.novaclaw.engine.ToolSelector
import ai.novaclaw.engine.ToolSpec
import org.junit.Assert.*
import org.junit.Test

/** Tool schema / selector / budget tests. */
class ToolingTest {

    private val allTools = listOf(
        "finish", "send_message", "open_app", "tap_node", "t", "scroll", "system_key",
        "get_device_info", "web_search", "fetch_url", "get_notifications", "get_clipboard",
        "set_clipboard", "get_installed_apps", "see_screen", "kb_write", "kb_read",
        "kb_search", "kb_todo", "kb_list", "skill_install", "skill_list", "skill_load",
        "mcp_add", "mcp_list", "mcp_call",
    ).map { ToolSpec(it, "$it tool", ToolSpec.obj("arg")) }

    // ---------- ToolSpec.obj — 8 tests ----------
    @Test fun spec_typeIsObject() = assertEquals("object", ToolSpec.obj("d")["type"])
    @Test fun spec_emptyProperties() = assertTrue((ToolSpec.obj("d")["properties"] as Map<*, *>).isEmpty())
    @Test fun spec_propertiesPassed() {
        val m = ToolSpec.obj("d", mapOf("q" to "query"))
        val props = m["properties"] as Map<*, *>
        assertEquals("query", (props["q"] as Map<*, *>)["description"])
    }
    @Test fun spec_requiredPassed() {
        val m = ToolSpec.obj("d", mapOf("q" to "query"), listOf("q"))
        assertEquals(listOf("q"), m["required"])
    }
    @Test fun spec_propertyTypeIsString() {
        val props = ToolSpec.obj("d", mapOf("q" to "query"))["properties"] as Map<*, *>
        assertEquals("string", (props["q"] as Map<*, *>)["type"])
    }
    @Test fun spec_defaultRequiredEmpty() = assertTrue((ToolSpec.obj("d")["required"] as List<*>).isEmpty())
    @Test fun spec_dataClassFields() {
        val s = ToolSpec("tap", "Tap screen", emptyMap())
        assertEquals("tap", s.name)
        assertEquals("Tap screen", s.description)
    }
    @Test fun spec_distinctNames() = assertEquals(allTools.size, allTools.map { it.name }.distinct().size)

    // ---------- ToolSelector — 22 tests ----------
    @Test fun select_battery() {
        val r = ToolSelector.select("What's my battery?", allTools)
        assertEquals(listOf("get_device_info", "finish"), r.map { it.name })
    }
    @Test fun select_whoIs() {
        val r = ToolSelector.select("Who is Albert Einstein", allTools)
        assertTrue(r.map { it.name }.contains("web_search"))
        assertTrue(r.map { it.name }.contains("finish"))
    }
    @Test fun select_webSearch() {
        val r = ToolSelector.select("Search the web for latest Gemini model", allTools)
        assertTrue("web_search" in r.map { it.name })
    }
    @Test fun select_notifications() {
        val r = ToolSelector.select("Read my notifications", allTools)
        assertTrue("get_notifications" in r.map { it.name })
    }
    @Test fun select_clipboard() {
        val r = ToolSelector.select("Copy this to clipboard", allTools)
        assertTrue("set_clipboard" in r.map { it.name })
    }
    @Test fun select_installedApps() {
        val r = ToolSelector.select("What apps are installed?", allTools)
        assertTrue("get_installed_apps" in r.map { it.name })
    }
    @Test fun select_skills() {
        val r = ToolSelector.select("Install a skill from URL", allTools)
        assertTrue("skill_install" in r.map { it.name })
        assertTrue("mcp_add" in r.map { it.name })
    }
    @Test fun select_mcp() {
        val r = ToolSelector.select("Add an MCP server", allTools)
        assertTrue("mcp_add" in r.map { it.name })
    }
    @Test fun select_memory() {
        val r = ToolSelector.select("Remember that my wifi password is X", allTools)
        assertTrue("kb_write" in r.map { it.name })
    }
    @Test fun select_sendMessage() {
        val r = ToolSelector.select("Send hi to Sarah on WhatsApp", allTools)
        assertTrue("send_message" in r.map { it.name })
    }
    @Test fun select_openApp() {
        val r = ToolSelector.select("Open WhatsApp and search for Sarah", allTools)
        assertTrue("open_app" in r.map { it.name })
    }
    @Test fun select_smallWorkingSet() {
        val r = ToolSelector.select("What's my battery?", allTools)
        assertTrue("Working set too large: ${r.size}", r.size <= 4)
    }
    @Test fun select_alwaysHasFinish() {
        listOf("Hi", "Open WhatsApp", "Search web", "Remember this").forEach { goal ->
            assertTrue("$goal missing finish", "finish" in ToolSelector.select(goal, allTools).map { it.name })
        }
    }
    @Test fun select_emptyListFallsBack() = assertTrue(ToolSelector.select("Hi", emptyList()).isEmpty())
    @Test fun select_caseInsensitive() {
        val r = ToolSelector.select("SEARCH THE WEB FOR GEMINI", allTools)
        assertTrue("web_search" in r.map { it.name })
    }
    @Test fun select_noDuplicates() {
        val names = ToolSelector.select("Hi", allTools).map { it.name }
        assertEquals(names.size, names.distinct().size)
    }
    @Test fun select_chatHasWebSearch() {
        val r = ToolSelector.select("Who is Einstein", allTools)
        assertTrue("web_search" in r.map { it.name })
    }
    @Test fun select_temperatureRoutesDeviceInfo() {
        val r = ToolSelector.select("What's the phone temperature", allTools)
        assertEquals(listOf("get_device_info", "finish"), r.map { it.name })
    }
    @Test fun select_playMusicIsAgent() {
        val r = ToolSelector.select("play music", allTools)
        assertTrue(r.size > 2)
    }
    @Test fun select_fallbackNotTooBig() {
        val r = ToolSelector.select("Hi", allTools)
        assertTrue("Fallback too large", r.size <= 8)
    }
    @Test fun select_allNamesExistInCatalog() {
        val r = ToolSelector.select("Install a skill and add MCP", allTools)
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { it.name in allTools.map { t -> t.name } })
    }
    @Test fun select_batteryNotWhy() {
        val r = ToolSelector.select("Why is my battery low?", allTools)
        assertFalse("get_device_info" in r.map { it.name })
    }

    // ---------- TokenMonitor / TaskBudget — 8 tests ----------
    @Test fun token_empty() = assertEquals(0, TokenMonitor.estimate(""))
    @Test fun token_fourChars() = assertEquals(1, TokenMonitor.estimate("abcd"))
    @Test fun token_fourHundredChars() = assertEquals(100, TokenMonitor.estimate("a".repeat(400)))
    @Test fun budget_initial() {
        val b = TaskBudget()
        assertEquals(0, b.status().used)
        assertFalse(b.status().exhausted)
    }
    @Test fun budget_softWarn() {
        val b = TaskBudget(maxSteps = 10, maxTokens = 10_000)
        repeat(8) { b.record("a".repeat(1000)) }
        assertTrue(b.status().near)
        assertFalse(b.status().exhausted)
    }
    @Test fun budget_hardStop() {
        val b = TaskBudget(maxSteps = 10, maxTokens = 10_000)
        repeat(10) { b.record("a".repeat(1000)) }
        assertTrue(b.status().exhausted)
    }
    @Test fun budget_reset() {
        val b = TaskBudget(maxSteps = 10, maxTokens = 10_000)
        repeat(10) { b.record("a".repeat(1000)) }
        b.reset()
        assertEquals(0, b.status().used)
    }
    @Test fun budget_statusShape() {
        val s: BudgetStatus = TaskBudget().status()
        assertEquals(100, s.limit)
    }

    // ---------- StuckDetector — 7 tests ----------
    @Test fun stuck_noSignalInitially() = assertNull(StuckDetector().record("tap", "a"))
    @Test fun stuck_threeSameNoSignal() {
        val d = StuckDetector()
        repeat(3) { assertNull(d.record("tap", "same")) }
    }
    @Test fun stuck_fourSameFires() {
        val d = StuckDetector()
        repeat(3) { d.record("tap", "same") }
        assertNotNull(d.record("tap", "same"))
    }
    @Test fun stuck_changedScreenNoSignal() {
        val d = StuckDetector()
        repeat(6) { assertNull(d.record("step$it", "fp$it")) }
    }
    @Test fun stuck_repeatedActionFires() {
        val d = StuckDetector()
        repeat(6) { d.record("tap", "fp$it") }
        assertNotNull(d.record("tap", "fp6"))
    }
    @Test fun stuck_blankFingerprintIgnored() {
        val d = StuckDetector()
        repeat(6) { assertNull(d.record("step$it", "")) }
    }
    @Test fun stuck_variedActionsNoSignal() {
        val d = StuckDetector()
        repeat(6) { assertNull(d.record("step$it", "fp$it")) }
    }
}
