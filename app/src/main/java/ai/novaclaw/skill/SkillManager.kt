package ai.novaclaw.skill

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Installs markdown skills from a URL into a local vault. */
class SkillManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private val dir: File
        get() = File(context.filesDir, "skills").apply { if (!exists()) mkdirs() }

    suspend fun installFromUrl(url: String): String = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return@withContext "Only http/https skill URLs are supported."
        }
        try {
            val req = Request.Builder().url(url).header("User-Agent", "NovaClaw-Skills/1.0").build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Install failed: HTTP ${response.code}"
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext "Install failed: empty skill file"
                val name = Regex("""(?m)^name:\s*(.+)$""").find(body)?.groupValues?.get(1)
                    ?.trim()?.replace(Regex("""[^A-Za-z0-9_-]"""), "_")
                    ?.ifBlank { "skill_${System.currentTimeMillis()}" }
                    ?: "skill_${System.currentTimeMillis()}"
                val file = File(dir, "$name.md")
                file.writeText(body.take(60_000))
                "Installed skill '$name'. Use skill_load(name='$name') to read it."
            }
        } catch (t: Throwable) {
            "Install failed: ${t.message?.take(140)}"
        }
    }

    fun list(): String {
        val files = dir.listFiles { f -> f.extension == "md" } ?: emptyArray()
        return if (files.isEmpty()) "No skills installed."
        else files.joinToString("\n") { it.nameWithoutExtension }
    }

    fun load(name: String): String {
        val clean = name.replace(Regex("""[^A-Za-z0-9_-]"""), "")
        val file = File(dir, "$clean.md")
        return if (file.exists()) file.readText().take(8000) else "Skill not found: $name"
    }

    fun remove(name: String): Boolean =
        File(dir, name.replace(Regex("""[^A-Za-z0-9_-]"""), "") + ".md").delete()
}
