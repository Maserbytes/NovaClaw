package ai.novaclaw.state

import android.content.Context
import java.io.File

/**
 * Markdown-backed chat history. Source of truth is a plain .md file per
 * conversation so history survives process death and is easy to inspect.
 * Adapted from PokeClaw's ConversationStore, without the SQLite index.
 */
class ConversationStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("nova_chat", Context.MODE_PRIVATE)
    private val dir: File
        get() = File(context.filesDir, "conversations").apply { if (!exists()) mkdirs() }

    fun currentId(): String {
        val stored = prefs.getString("current_id", "").orEmpty()
        if (stored.isNotBlank() && File(dir, "$stored.md").exists()) return stored
        val id = "chat_" + System.currentTimeMillis()
        prefs.edit().putString("current_id", id).apply()
        File(dir, "$id.md").writeText("# NovaClaw conversation\n\n")
        return id
    }

    fun newConversation(): String {
        val id = "chat_" + System.currentTimeMillis()
        File(dir, "$id.md").writeText("# NovaClaw conversation\n\n")
        prefs.edit().putString("current_id", id).apply()
        return id
    }

    fun append(role: String, text: String) {
        if (text.isBlank()) return
        val safe = text.replace("\n", " ").take(2000)
        File(dir, "${currentId()}.md").appendText("\n$role: $safe\n")
    }

    fun recent(limit: Int = 6): List<String> {
        val file = File(dir, "${currentId()}.md")
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.startsWith("user:") || it.startsWith("nova:") }
            .takeLast(limit)
    }

    fun allText(): String {
        val file = File(dir, "${currentId()}.md")
        return if (file.exists()) file.readText() else ""
    }

    fun list(): List<String> = dir.listFiles { f -> f.extension == "md" }
        ?.map { it.nameWithoutExtension }?.sorted().orEmpty()
}
