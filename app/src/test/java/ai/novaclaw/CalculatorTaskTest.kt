package ai.novaclaw

import ai.novaclaw.engine.CalculatorTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTaskTest {
    @Test fun usesRealKeypadSequenceForTheReportedPrompt() {
        val result = CalculatorTask.parse("Calculator", "calculate 24 times 17")
        assertEquals(listOf("clear", "2", "4", "×", "1", "7", "="), result?.keys)
        assertEquals("408", result?.expected)
    }

    @Test fun supportsBasicArithmeticWithoutInterceptingOtherAppTasks() {
        assertEquals("-3", CalculatorTask.parse("Calculator", "calculate 1 minus 4")?.expected)
        assertEquals("8", CalculatorTask.parse("Calculator", "calculate 3 + 5")?.expected)
        assertNull(CalculatorTask.parse("Calculator", "calculate 3 + 5 and paste the result"))
        assertNull(CalculatorTask.parse("Clock", "calculate 3 + 5"))
    }
}
