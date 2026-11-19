package ai.novaclaw.model

/** One interface for local Gemma and cloud Gemini inference. */
interface ReasoningSession : AutoCloseable {
    suspend fun send(prompt: String, timeoutMs: Long = 120_000L): ModelReply
    suspend fun sendToolResponses(
        responses: List<Pair<String, String>>,
        timeoutMs: Long = 120_000L,
    ): ModelReply
    fun markDirty() {}
}
