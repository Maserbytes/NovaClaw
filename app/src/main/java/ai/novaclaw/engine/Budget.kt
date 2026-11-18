package ai.novaclaw.engine

/** Cheap token estimate — prompt chars / 4. Enough for a soft budget signal. */
object TokenMonitor {
    fun estimate(text: String): Int = text.length / 4
}

data class BudgetStatus(val used: Int, val limit: Int, val exhausted: Boolean, val near: Boolean)

/** Step/token budget: soft warning at 80%, hard stop at 100%. */
class TaskBudget(private val maxSteps: Int = 30, private val maxTokens: Int = 24_000) {
    private var steps = 0
    private var tokens = 0

    fun record(prompt: String): BudgetStatus {
        steps++
        tokens += TokenMonitor.estimate(prompt)
        return status()
    }

    fun status(): BudgetStatus {
        val stepRatio = steps.toFloat() / maxSteps
        val tokenRatio = tokens.toFloat() / maxTokens
        val used = (maxOf(stepRatio, tokenRatio) * 100).toInt().coerceAtMost(100)
        return BudgetStatus(used, 100, used >= 100, used >= 80)
    }

    fun reset() { steps = 0; tokens = 0 }
}

/** Detects repeated actions and repeated unchanged screens with real inputs. */
class StuckDetector {
    private val actions = ArrayDeque<String>()
    private val fingerprints = ArrayDeque<String>()

    data class Signal(val reason: String)

    fun record(action: String, fingerprint: String): Signal? {
        actions.addLast(action); if (actions.size > 8) actions.removeFirst()
        fingerprints.addLast(fingerprint); if (fingerprints.size > 4) fingerprints.removeFirst()

        if (fingerprints.size == 4 && fingerprints.all { it == fingerprint && it.isNotBlank() }) {
            return Signal("Screen unchanged for 4 consecutive steps")
        }
        if (actions.size >= 6) {
            val recent = actions.toList().takeLast(6).groupingBy { it }.eachCount()
            if (recent.values.any { it >= 4 }) return Signal("Repeated action '$action' 4+ times in 6 steps")
        }
        return null
    }
}
