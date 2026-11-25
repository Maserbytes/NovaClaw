package ai.novaclaw.model

import ai.novaclaw.engine.ToolSpec
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class ToolCall(val name: String, val arguments: Map<String, Any?>)
data class ModelReply(
    val text: String?,
    val thought: String?,
    val toolCalls: List<ToolCall>,
    val backend: String,
)

/**
 * LiteRT-LM wrapper: one gated engine, typed tool-call protocol, token budgets,
 * real thinking channel, CPU-first correctness with opt-in GPU.
 */
class LiteRTEngine(private val context: Context) {

    private val gson = Gson()
    private var engine: Engine? = null
    private var modelPath: String? = null
    private var backendLabel: String = "none"

    val currentBackend: String get() = backendLabel

    private val cacheDir: String
        get() {
            val dir = File(context.cacheDir, "litert")
            if (!dir.exists()) dir.mkdirs()
            return dir.path
        }

    fun open(desiredPath: String, preferGpu: Boolean = false): String = LocalRuntimeGate.write {
        val decision = BackendSelector.decide(context, preferGpu)
        if (engine != null && modelPath == desiredPath &&
            backendLabel.startsWith(if (decision.useGpu) "GPU" else "CPU")
        ) {
            return@write backendLabel
        }

        check(LocalRuntimeGate.leaseCount() == 0) { "A local model is still in use" }
        closeLocked()

        fun create(useGpu: Boolean): Pair<Engine, String> {
            val backend: Backend = if (useGpu) Backend.GPU() else Backend.CPU()
            val label = if (useGpu) "GPU" else "CPU"
            if (useGpu) {
                // MTP is recommended by Google for GPU; the E2B bundle ships a drafter.
                @OptIn(ExperimentalApi::class)
                fun enableMtp() { ExperimentalFlags.enableSpeculativeDecoding = true }
                runCatching { enableMtp() }
                BackendSelector.markGpuAttempt(context, desiredPath)
            }
            return try {
                val cfg = EngineConfig(
                    modelPath = desiredPath,
                    backend = backend,
                    maxNumTokens = decision.maxTokens,
                    cacheDir = cacheDir,
                    visionBackend = Backend.CPU(),
                )
                val e = Engine(cfg).also { it.initialize() }
                if (useGpu) BackendSelector.markGpuSuccess(context, desiredPath)
                e to "$label (kv=${decision.maxTokens}, ram=${decision.totalRamGb}GB)"
            } catch (t: Throwable) {
                BackendSelector.noteFailure(context, t)
                throw t
            }
        }

        val created = if (decision.useGpu) {
            try { create(true) } catch (t: Throwable) { create(false) }
        } else {
            create(false)
        }

        engine = created.first
        modelPath = desiredPath
        backendLabel = created.second
        backendLabel
    }

    fun openSession(
        systemPrompt: String,
        tools: List<ToolSpec>,
        temperature: Double = 1.0,
    ): Session {
        val nativeTools = tools.map { spec ->
            com.google.ai.edge.litertlm.tool(object : OpenApiTool {
                // Trim whitespace to save tokens — small models are sensitive to prompt size.
                override fun getToolDescriptionJsonString(): String = gson.toJson(
                    mapOf(
                        "name" to spec.name,
                        "description" to spec.description.trim(),
                        "parameters" to spec.parameters,
                    )
                )

                override fun execute(params: String): String = "{}"
            })
        }
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt.trimIndent()),
            tools = nativeTools,
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = temperature),
            automaticToolCalling = false,
        )
        val conversation = LocalRuntimeGate.write {
            val currentEngine = engine ?: error("Engine not opened")
            currentEngine.createConversation(config).also { LocalRuntimeGate.acquireLease() }
        }
        return Session(conversation)
    }

    inner class Session(private val conversation: Conversation) : ReasoningSession {
        private var closed = false

        override suspend fun send(prompt: String, timeoutMs: Long): ModelReply =
            invoke(timeoutMs, Message.user(prompt))

        /** Send tool execution results in the format Gemma 4 expects. */
        override suspend fun sendToolResponses(
            responses: List<Pair<String, String>>,
            timeoutMs: Long,
        ): ModelReply = invoke(timeoutMs, Message.tool(Contents.of(responses.map { (name, json) ->
            Content.ToolResponse(name, gson.fromJson(json, Map::class.java))
        })))

        override suspend fun sendImage(
            imagePath: String,
            prompt: String,
            timeoutMs: Long,
        ): ModelReply = invoke(timeoutMs, Message.user(Contents.of(
            Content.ImageFile(imagePath), Content.Text(prompt),
        )))

        private suspend fun invoke(timeoutMs: Long, message: Message): ModelReply =
            withContext(Dispatchers.Default) {
                val text = StringBuilder()
                val thought = StringBuilder()
                val calls = mutableListOf<ToolCall>()
                val finished = try {
                    withTimeoutOrNull(timeoutMs) {
                        conversation.sendMessageAsync(message).collect { chunk ->
                            chunk.contents.contents.filterIsInstance<Content.Text>()
                                .forEach { text.append(it.text) }
                            thought.append(chunk.channels["thought"] ?: chunk.channels["thinking"].orEmpty())
                            chunk.toolCalls.forEach { calls.add(ToolCall(it.name, it.arguments)) }
                        }
                        true
                    }
                } catch (cancelled: CancellationException) {
                    conversation.cancelProcess()
                    throw cancelled
                }
                if (finished == null) {
                    conversation.cancelProcess()
                    error("Local inference exceeded ${timeoutMs / 1000}s.")
                }
                ModelReply(text.toString().trim().ifEmpty { null },
                    thought.toString().trim().ifEmpty { null }, calls, backendLabel)
            }

        override fun close() {
            if (closed) return
            closed = true
            LocalRuntimeGate.write {
                try { conversation.close() } catch (_: Throwable) {}
            }
            LocalRuntimeGate.releaseLease()
        }
    }

    fun close() = LocalRuntimeGate.write {
        if (LocalRuntimeGate.leaseCount() > 0) return@write
        closeLocked()
    }

    private fun closeLocked() {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        modelPath = null
        backendLabel = "none"
    }
}
