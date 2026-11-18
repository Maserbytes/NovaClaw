package ai.novaclaw.state

enum class TaskPhase { IDLE, RUNNING, STOPPING }

data class TaskSessionState(
    val phase: TaskPhase = TaskPhase.IDLE,
    val requestId: String = "",
    val taskText: String = "",
    val startedAt: Long = 0L,
    val stopRequested: Boolean = false,
) {
    val isRunning: Boolean get() = phase != TaskPhase.IDLE && requestId.isNotEmpty()
}

/**
 * Single authoritative source for task state.
 * Adapted from PokeClaw's TaskSessionStore; no channel coupling.
 */
class TaskSessionStore {
    private val lock = Any()
    @Volatile
    private var current = TaskSessionState()

    fun snapshot(): TaskSessionState = current
    fun isRunning(): Boolean = current.isRunning

    @Synchronized
    fun tryAcquire(requestId: String, taskText: String): Boolean {
        if (current.isRunning) return false
        current = TaskSessionState(
            phase = TaskPhase.RUNNING,
            requestId = requestId,
            taskText = taskText,
            startedAt = System.currentTimeMillis(),
        )
        return true
    }

    @Synchronized
    fun markStopping(): Boolean {
        val c = current
        if (!c.isRunning || c.stopRequested) return false
        current = c.copy(phase = TaskPhase.STOPPING, stopRequested = true)
        return true
    }

    @Synchronized
    fun release(): TaskSessionState {
        val previous = current
        current = TaskSessionState()
        return previous
    }
}
