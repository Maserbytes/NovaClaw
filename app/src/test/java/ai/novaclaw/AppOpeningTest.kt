package ai.novaclaw

import ai.novaclaw.engine.TaskShortcuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppOpeningTest {
    @Test fun calculatorRequestKeepsLaunchSeparateFromTheWork() {
        assertEquals(TaskShortcuts.AppOpening("Calculator", "calculate 24 times 17"),
            TaskShortcuts.openingFor("Open Calculator and calculate 24 times 17"))
    }

    @Test fun retainsTheWholeAppLabelAndRemainingTask() {
        assertEquals(TaskShortcuts.AppOpening("Google Maps", "search for a train station"),
            TaskShortcuts.openingFor("Please launch Google Maps, then search for a train station"))
    }

    @Test fun plainLaunchAndQuestionsUseTheirExistingRoutes() {
        assertNull(TaskShortcuts.openingFor("Open Calculator"))
        assertNull(TaskShortcuts.openingFor("How do I open Calculator and clear it?"))
    }
}
