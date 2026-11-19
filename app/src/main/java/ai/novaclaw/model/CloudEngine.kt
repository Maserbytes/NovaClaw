package ai.novaclaw.model

import ai.novaclaw.engine.ToolSpec
import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Gemini cloud reasoning through the same ReasoningSession interface as LiteRT. */
class CloudEngine(private val context: Context) {

    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun openSession(
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        tools: List<ToolSpec>,
    ): ReasoningSession = CloudSession(apiKey.trim(), modelId, systemPrompt.trim(), tools)

    private inner class CloudSession(
        private val apiKey: String,
        private val modelId: String,
        private val systemPrompt: String,
        private val tools: List<ToolSpec>,
    ) : ReasoningSession {

        private val contents = JsonArray()

        override suspend fun send(prompt: String, timeoutMs: Long): ModelReply =
            withContext(Dispatchers.IO) {
                val part = JsonObject().apply { addProperty("text", prompt) }
                val parts = JsonArray().apply { add(part) }
                contents.add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", parts)
                })
                call()
            }

        override suspend fun sendToolResponses(
            responses: List<Pair<String, String>>,
            timeoutMs: Long,
        ): ModelReply = withContext(Dispatchers.IO) {
            val parts = JsonArray()
            responses.forEach { (name, json) ->
                val responseObj = runCatching { JsonParser.parseString(json).asJsonObject }
                    .getOrElse { JsonObject().apply { addProperty("result", json) } }
                val functionResponse = JsonObject().apply {
                    addProperty("name", name)
                    add("response", responseObj)
                }
                parts.add(JsonObject().apply { add("functionResponse", functionResponse) })
            }
            contents.add(JsonObject().apply {
                addProperty("role", "user")
                add("parts", parts)
            })
            call()
        }

        private fun call(): ModelReply {
            val root = JsonObject().apply {
                add("systemInstruction", JsonObject().apply {
                    val p = JsonObject().apply { addProperty("text", systemPrompt) }
                    add("parts", JsonArray().apply { add(p) })
                })
                add("contents", contents)
                if (tools.isNotEmpty()) {
                    val declarations = JsonArray()
                    tools.forEach { t ->
                        declarations.add(JsonObject().apply {
                            addProperty("name", t.name)
                            addProperty("description", t.description.trim())
                            val schema = runCatching { JsonParser.parseString(gson.toJson(t.parameters)).asJsonObject }
                                .getOrNull() ?: JsonObject()
                            add("parameters", schema)
                        })
                    }
                    val toolObj = JsonObject().apply { add("functionDeclarations", declarations) }
                    add("tools", JsonArray().apply { add(toolObj) })
                }
                add("generationConfig", JsonObject().apply {
                    addProperty("temperature", 0.3)
                    addProperty("maxOutputTokens", 2048)
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                "$modelId:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(gson.toJson(root).toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("Gemini HTTP ${response.code}: ${body.take(200)}")
                }
                val rootJson = JsonParser.parseString(body).asJsonObject
                if (rootJson.has("error")) {
                    val msg = rootJson.getAsJsonObject("error").get("message")?.asString.orEmpty()
                    throw RuntimeException("Gemini error: ${msg.take(200)}")
                }
                val candidates = rootJson.getAsJsonArray("candidates")
                if (candidates == null || candidates.size() == 0) {
                    throw RuntimeException("Gemini returned no candidates")
                }
                val content = candidates[0].asJsonObject.getAsJsonObject("content")
                val parts = content?.getAsJsonArray("parts") ?: JsonArray()

                val texts = mutableListOf<String>()
                val calls = mutableListOf<ToolCall>()
                val modelParts = JsonArray()
                parts.forEach { element ->
                    val part = element.asJsonObject
                    if (part.has("text")) {
                        val value = part.get("text").asString
                        texts.add(value)
                        modelParts.add(JsonObject().apply { addProperty("text", value) })
                    }
                    if (part.has("functionCall")) {
                        val fc = part.getAsJsonObject("functionCall")
                        val name = fc.get("name")?.asString.orEmpty()
                        val argsObject = fc.getAsJsonObject("args") ?: JsonObject()
                        val args = argsObject.entrySet().associate { it.key to it.value }
                        if (name.isNotBlank()) calls.add(ToolCall(name, args))
                        modelParts.add(JsonObject().apply { add("functionCall", fc) })
                    }
                }
                contents.add(JsonObject().apply {
                    addProperty("role", "model")
                    add("parts", modelParts)
                })

                return ModelReply(
                    text = texts.joinToString("\n").trim().ifEmpty { null },
                    thought = null,
                    toolCalls = calls,
                    backend = "Gemini $modelId (cloud)",
                    raw = body.take(2000),
                )
            }
        }

        override fun close() { /* stateless; contents kept for the session lifetime */ }
    }
}
