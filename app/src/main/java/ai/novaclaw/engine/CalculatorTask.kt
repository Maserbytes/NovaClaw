package ai.novaclaw.engine

/** Plans basic integer arithmetic using labels from the installed calculator's keypad. */
internal data class CalculatorTask(val keys: List<String>, val expected: String) {
    companion object {
        private val request = Regex(
            """(?i)^calculate\s+(\d{1,8})\s*(times|multiplied by|[×*x+\-]|plus|minus)\s*(\d{1,8})[.!]?$""",
        )

        fun parse(app: String, goal: String): CalculatorTask? {
            if (!app.equals("Calculator", true)) return null
            val (left, op, right) = request.matchEntire(goal.trim())?.destructured ?: return null
            val key = when (op.lowercase()) {
                "+", "plus" -> "+"
                "-", "minus" -> "-"
                else -> "×"
            }
            val a = left.toLong()
            val b = right.toLong()
            val value = when (key) {
                "+" -> a + b
                "-" -> a - b
                else -> a * b
            }
            return CalculatorTask(
                listOf("clear") + left.map(Char::toString) + key + right.map(Char::toString) + "=",
                value.toString(),
            )
        }

        fun labels(key: String): Set<String> = when (key) {
            "clear" -> setOf("c", "ac", "clear", "all clear", "clear all", "clr")
            "×" -> setOf("×", "*", "x", "multiply", "times", "multiplication")
            "-" -> setOf("-", "−", "minus", "subtract")
            "+" -> setOf("+", "plus", "add")
            "=" -> setOf("=", "equals", "equal")
            else -> setOf(key, "digit $key", "digit_$key", "btn_$key")
        }
    }
}
