package ai.novaclaw.engine

import android.content.Context
import ai.novaclaw.util.NovaLog

/** Loads markdown playbooks from assets/playbooks and matches them by trigger specificity. */
object PlaybookManager {

    data class Playbook(val id: String, val name: String, val triggers: List<String>, val body: String)

    private const val TAG = "PlaybookManager"
    private var playbooks: List<Playbook> = emptyList()

    fun loadAll(context: Context) {
        val loaded = mutableListOf<Playbook>()
        runCatching {
            (context.assets.list("playbooks") ?: emptyArray()).filter { it.endsWith(".md") }.forEach { file ->
                runCatching {
                    context.assets.open("playbooks/$file").bufferedReader().use { it.readText() }
                        .let { parse(it) }
                }.onFailure { NovaLog.w(TAG, "Failed to load $file", it) }
                    .getOrNull()?.let { loaded.add(it) }
            }
        }.onFailure { NovaLog.e(TAG, "Failed to list playbooks", it) }
        playbooks = loaded
        NovaLog.i(TAG, "Loaded ${loaded.size} playbooks")
    }

    fun match(task: String): Playbook? {
        val lower = task.lowercase()
        var best: Playbook? = null
        var bestScore = 0
        for (p in playbooks) for (trigger in p.triggers) {
            val t = trigger.lowercase().trim()
            if (t.isNotBlank() && lower.contains(t) && t.length > bestScore) {
                bestScore = t.length
                best = p
            }
        }
        if (best != null) NovaLog.decision(TAG, "playbook", best.id, "trigger score=$bestScore")
        return best
    }

    fun all(): List<Playbook> = playbooks

    private fun parse(content: String): Playbook? {
        if (!content.startsWith("---")) return null
        val end = content.indexOf("---", 3)
        if (end < 0) return null
        val front = content.substring(3, end).trim()
        val body = content.substring(end + 3).trim()
        var id = ""; var name = ""; val triggers = mutableListOf<String>(); var inTriggers = false
        for (raw in front.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("id:") -> { id = line.substringAfter("id:").trim(); inTriggers = false }
                line.startsWith("name:") -> { name = line.substringAfter("name:").trim(); inTriggers = false }
                line.startsWith("triggers:") -> inTriggers = true
                inTriggers && line.startsWith("- ") -> triggers.add(
                    line.substringAfter("- ").trim().removeSurrounding("\"").removeSurrounding("'"))
                else -> inTriggers = false
            }
        }
        return if (id.isBlank() || body.isBlank()) null else Playbook(id, name, triggers, body)
    }
}
