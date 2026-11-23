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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class ToolCall(val name: String, val arguments: Map<String, Any?>)
data class ModelReply(
    val text: String?,
    val thought: String?,
    val toolCalls: List<ToolCall>,
    val backend: String,
    val raw: String,
) {
    /** Detect the silent-parse-drop failure described in LiteRT-LM issue #2202. */
    val hasDroppedToolCall: Boolean
        get() = toolCalls.isEmpty() && (raw.contains("<|tool_call>", true) || raw.contains("<tool_call>", true))
}

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
        val currentEngine = engine ?: error("Engine not opened")
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
        val conversation = LocalRuntimeGate.write { currentEngine.createConversation(config) }
        LocalRuntimeGate.acquireLease()
        return Session(conversation)
    }

    inner class Session(private val conversation: Conversation) : ReasoningSession {
        private var closed = false
        var dirty = false
            private set

        override suspend fun send(prompt: String, timeoutMs: Long): ModelReply =
            invoke(timeoutMs) { conversation.sendMessage(prompt) }

        /** Send tool execution results in the format Gemma 4 expects. */
        override suspend fun sendToolResponses(
            responses: List<Pair<String, String>>,
            timeoutMs: Long,
        ): ModelReply = invoke(timeoutMs) {
            val contents = Contents.of(responses.map { Content.ToolResponse(it.first, it.second) })
            conversation.sendMessage(Message.tool(contents))
        }

        override suspend fun sendImage(
            imagePath: String,
            prompt: String,
            timeoutMs: Long,
        ): ModelReply = invoke(timeoutMs) {
            conversation.sendMessage(Contents.of(
                Content.ImageFile(imagePath),
                Content.Text(prompt),
            ))
        }

        override fun markDirty() { dirty = true }

        private suspend fun invoke(timeoutMs: Long, call: () -> Any): ModelReply =
            withContext(Dispatchers.Default) {
                val result = try {
                    withTimeoutOrNull(timeoutMs) { call() }
                } catch (t: Throwable) {
                    val m = t.message.orEmpty()
                    val parseFailure = m.contains("parse FC", true) ||
                        m.contains("ParseCancelled", true) ||
                        m.contains("Failed to parse tool calls", true) ||
                        m.contains("tool_call", true)
                    if (parseFailure) {
                        dirty = true
                        return@withContext ModelReply(null, null, emptyList(), backendLabel, m.take(1000))
                    }
                    throw t
                }
                if (result == null) {
                    dirty = true
                    BackendSelector.noteFailure(context, RuntimeException("local inference timeout"))
                    throw RuntimeException("Local inference exceeded ${timeoutMs / 1000}s — backend quarantined")
                }
                parseReply(result)
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

    private fun parseReply(message: Any): ModelReply {
        val msg = message as? Message
        val calls = mutableListOf<ToolCall>()
        var thought: String? = null
        var text: String? = null
        var raw = ""

        if (msg != null) {
            runCatching { msg.toolCalls?.forEach { calls.add(ToolCall(it.name, normalize(it.arguments))) } }
            // Message.channels is a public property in LiteRT-LM 0.19.
            runCatching {
                val channels = msg.channels
                thought = channels["thought"]?.trim()?.ifEmpty { null }
                    ?: channels["thinking"]?.trim()?.ifEmpty { null }
            }
            // Message.toString() delegates to Contents.toString(), which joins
            // every Content.Text — this is the reliable answer source.
            runCatching { raw = msg.toString() }
            if (raw.isBlank()) runCatching { raw = msg.contents?.toString().orEmpty() }
            if (raw.isBlank()) {
                runCatching {
                    val contents = msg.javaClass.getMethod("getContents").invoke(msg)
                    val list = contents?.javaClass?.getMethod("getContents")?.invoke(contents) as? List<*>
                    raw = list?.joinToString("") { it?.toString().orEmpty() }.orEmpty()
                }
            }
        } else {
            raw = message.toString()
        }
        if (text == null && raw.isNotBlank()) text = raw.trim()
        // Some builds put the final answer in a non-"thought" channel.
        if (text == null && msg != null) {
            runCatching {
                val other = msg.channels.filterKeys { it != "thought" && it != "thinking" }
                    .values.joinToString("\n").trim()
                if (other.isNotBlank()) text = other
            }
        }

        // Extract thinking tags if not provided by native channels
        if (thought.isNullOrBlank()) {
            val m = Regex("""<thought>(.*?)</thought>""", RegexOption.DOT_MATCHES_ALL).find(raw)
            if (m != null) thought = m.groupValues[1].trim().ifEmpty { null }
        }

        // Fallback for models/runtimes that emit tool calls as tagged text.
        if (calls.isEmpty()) {
            // Match <|tool_call|>call:name{args}<|tool_call|> or <tool_call>{"name":...}</tool_call>
            val taggedRegex = Regex("""<\|?tool_call\|?>\s*(?:call:)?(\w+)?\s*(\{.*?\})\s*</?\|?tool_call\|?>""", RegexOption.DOT_MATCHES_ALL)
            taggedRegex.findAll(raw).forEach { m ->
                runCatching {
                    val inlineName = m.groupValues[1].takeIf { it.isNotBlank() }
                    val jsonStr = m.groupValues[2]
                    val map = gson.fromJson(jsonStr, Map::class.java) as Map<*, *>
                    val name = inlineName ?: map["name"]?.toString() ?: map["tool"]?.toString() ?: return@forEach
                    val args = if (inlineName != null) normalize(map) else normalize(map["arguments"] ?: map["parameters"] ?: map)
                    calls.add(ToolCall(name, args))
                }
            }
        }

        // Secondary fallback: plain call:tool_name{...} without XML tags
        if (calls.isEmpty()) {
            Regex("""(?:call:)?(\w+)\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL).findAll(raw).forEach { m ->
                runCatching {
                    val name = m.groupValues[1]
                    if (name in listOf("open_app", "tap_node", "tap", "t", "scroll", "system_key", "get_device_info", "finish")) {
                        val map = gson.fromJson(m.groupValues[2], Map::class.java) as Map<*, *>
                        calls.add(ToolCall(name, normalize(map)))
                    }
                }
            }
        }

        return ModelReply(text, thought, calls, backendLabel, raw)
    }

    private fun normalize(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.mapKeys { it.key.toString() }
        null -> emptyMap()
        else -> runCatching {
            (gson.fromJson(value.toString(), Map::class.java) as? Map<*, *>)
                ?.mapKeys { it.key.toString() } ?: emptyMap()
        }.getOrDefault(emptyMap())
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
