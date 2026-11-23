package ai.novaclaw.model

/** One interface for local Gemma, cloud Gemini, and image-capable sessions. */
interface ReasoningSession : AutoCloseable {
    suspend fun send(prompt: String, timeoutMs: Long = 120_000L): ModelReply
    suspend fun sendToolResponses(
        responses: List<Pair<String, String>>,
        timeoutMs: Long = 120_000L,
    ): ModelReply
    suspend fun sendImage(
        imagePath: String,
        prompt: String,
        timeoutMs: Long = 120_000L,
    ): ModelReply = throw UnsupportedOperationException("This session has no vision support")
    fun markDirty() {}
}
