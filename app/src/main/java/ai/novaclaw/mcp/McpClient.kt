package ai.novaclaw.mcp

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Minimal HTTP MCP client (JSON-RPC 2.0) for remote MCP servers. */
class McpClient(private val context: Context) {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("nova_mcp", Context.MODE_PRIVATE)
    private val jsonType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun add(name: String, url: String): String {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true))
            return "Only http/https MCP endpoints are supported."
        prefs.edit().putString(name.replace(" ", "_"), url).apply()
        return "MCP server '$name' saved."
    }

    fun list(): String {
        val all = prefs.all.filter { it.value is String }
        return if (all.isEmpty()) "No MCP servers configured."
        else all.map { "${it.key} = ${it.value}" }.joinToString("\n")
    }

    fun remove(name: String) = prefs.edit().remove(name).apply()

    suspend fun call(server: String, tool: String, argsJson: String): String = withContext(Dispatchers.IO) {
        val url = prefs.getString(server, null)
            ?: return@withContext "MCP server '$server' not found."
        val id = System.currentTimeMillis()
        val rpc = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", "tools/call")
            add("params", JsonObject().apply {
                addProperty("name", tool)
                val args = runCatching { JsonParser.parseString(argsJson).asJsonObject }
                    .getOrDefault(JsonObject())
                add("arguments", args)
            })
        }
        try {
            val req = Request.Builder().url(url)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .post(gson.toJson(rpc).toRequestBody(jsonType)).build()
            client.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext "MCP HTTP ${response.code}: ${body.take(160)}"
                val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    ?: return@withContext body.take(800)
                val result = obj.getAsJsonObject("result")
                val error = obj.getAsJsonObject("error")
                when {
                    error != null -> "MCP error: ${error.get("message")?.asString?.take(200)}"
                    result != null -> {
                        val content = result.getAsJsonArray("content")
                        val texts = content?.mapNotNull { el ->
                            el.asJsonObject.get("text")?.asString
                        }.orEmpty()
                        texts.joinToString("\n").ifBlank { result.toString().take(800) }
                    }
                    else -> obj.toString().take(800)
                }
            }
        } catch (t: Throwable) {
            "MCP call failed: ${t.message?.take(140)}"
        }
    }
}
