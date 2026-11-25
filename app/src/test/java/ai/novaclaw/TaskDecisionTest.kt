package ai.novaclaw

import ai.novaclaw.engine.ConversationRouter
import ai.novaclaw.engine.TaskDecision
import ai.novaclaw.engine.ToolResult
import ai.novaclaw.engine.ToolSpec
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TaskDecisionTest {
    private val tools = listOf(
        ToolSpec("tap_node", "Tap a visible control.", ToolSpec.obj("Id", mapOf("node_id" to "Id"), listOf("node_id"))),
        ToolSpec("finish", "Finish.", ToolSpec.obj("Summary", mapOf("summary" to "Summary"), listOf("summary"))),
        TaskDecision.blocked,
    )

    @Test fun validDecisionUsesDeclaredToolAndArguments() {
        val call = TaskDecision.parse("""{"tool":"tap_node","args":{"node_id":"n3"}}""", tools)
        assertEquals("tap_node", call?.name)
        assertEquals(mapOf("node_id" to "n3"), call?.arguments)
    }

    @Test fun arbitraryTextAndUnknownToolsCannotRun() {
        for (input in listOf(
            "Hey, how can I help you?",
            """{"tool":"open_app","args":{"app_name":"Clock"}}""",
            """{"tool":"tap_node","args":{"node_id":3}}""",
            """{"tool":"tap_node","args":{"node_id":"n3","unexpected":"argument"}}""",
            """{"tool":"tap_node","args":{}}""",
            """{"tool":"tap_node","args":{"node_id":"n3"}} {"tool":"finish","args":{}}""",
        )) {
            assertNull(input, TaskDecision.parse(input, tools))
        }
    }

    @Test fun completionRequiresEvidenceAndRejectsAGreeting() {
        val result = checkNotNull(TaskDecision.parse("""{"tool":"finish","args":{"summary":"Opened Clock"}}""", tools))
        assertFalse(TaskDecision.canFinish(result, readScreen = false, actionSucceeded = false))
        assertTrue(TaskDecision.canFinish(result, readScreen = false, actionSucceeded = true))
        assertTrue(TaskDecision.canFinish(result, readScreen = true, actionSucceeded = false))
        val greeting = result.copy(arguments = mapOf("summary" to "Hi! How can I help you?"))
        assertFalse(TaskDecision.canFinish(greeting, readScreen = false, actionSucceeded = true))
    }

    @Test fun observationsPreserveFailureAndEscapeNewlines() {
        val line = TaskDecision.observation("tap_node", "n3", ToolResult(false, "Moved\n\"Retry\""))
        assertFalse(line.contains('\n'))
        val decoded = JsonParser.parseString(line).asJsonObject
        assertFalse(decoded["ok"].asBoolean)
        assertEquals("Moved\n\"Retry\"", decoded["message"].asString)
    }

    @Test fun homeAndBackReachDeviceRouting() {
        assertFalse(ConversationRouter.isChatOnly("Go home"))
        assertFalse(ConversationRouter.isChatOnly("Press back"))
        assertFalse(ConversationRouter.isChatOnly("What is on my screen?"))
    }
    @Test fun openedHeadingOnlyCompletesASingleNavigationRequest() {
        assertTrue(TaskDecision.navigationTarget("Show me the calculation history", "History"))
        assertTrue(TaskDecision.navigationTarget("View alarms", "Alarms"))
        assertFalse(TaskDecision.navigationTarget("Open History and clear it", "History"))
        assertFalse(TaskDecision.navigationTarget("Show different history", "Settings"))
    }
}
