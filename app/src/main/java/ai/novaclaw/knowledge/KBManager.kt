package ai.novaclaw.knowledge

import android.content.Context
import java.io.File

/** Small local markdown vault giving the agent durable memory. Paths are sandboxed. */
class KBManager(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "knowledge").apply { if (!exists()) mkdirs() }

    private fun safe(name: String): File? {
        val clean = name.trim().replace("\\", "/").removePrefix("/")
            .split("/").filter { it.isNotBlank() && it != ".." && it != "." }
            .joinToString("/")
        if (clean.isBlank()) return null
        return File(root, clean + if (clean.endsWith(".md")) "" else ".md")
    }

    fun write(name: String, content: String): Boolean = safe(name)?.let { file ->
        runCatching { file.parentFile?.mkdirs(); file.writeText(content.take(50_000)); true }
            .getOrDefault(false)
    } ?: false

    fun append(name: String, content: String): Boolean = safe(name)?.let { file ->
        runCatching { file.parentFile?.mkdirs(); file.appendText("\n" + content.take(10_000)); true }
            .getOrDefault(false)
    } ?: false

    fun read(name: String): String = safe(name)?.let { file ->
        if (file.exists()) file.readText().take(4000) else "Not found: $name"
    } ?: "Invalid path"

    fun search(query: String): String {
        val q = query.lowercase()
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "md" }
            .mapNotNull { file ->
                val lines = file.readLines()
                val match = lines.firstOrNull { it.contains(q, ignoreCase = true) }
                if (match != null) "${file.name}: ${match.take(140)}" else null
            }.take(10)
        return if (hits.isEmpty()) "No matches." else hits.joinToString("\n")
    }

    fun todo(text: String): Boolean =
        append("todos", "- [ ] ${text.take(500)}")

    fun list(): String = root.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(root).path }.sorted().joinToString("\n").ifBlank { "Empty vault." }
}
