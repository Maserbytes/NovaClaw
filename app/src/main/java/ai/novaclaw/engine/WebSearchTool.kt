package ai.novaclaw.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Keyless web access: Bing RSS search + plain-text page fetch. */
object WebSearchTool {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    internal data class Hit(val title: String, val url: String, val summary: String)

    private val stopWords = setOf("latest", "newest", "current", "recent", "search", "find",
        "show", "about", "please", "what", "which", "when", "where", "with", "this",
        "that", "from", "today", "news", "the", "for", "and", "info", "information")

    internal fun relevant(query: String, hits: List<Hit>, limit: Int): List<Hit> {
        val words = Regex("[\\p{L}\\p{N}]+")
        fun tokens(value: String) = words.findAll(value.lowercase())
            .map { it.value }.filter { it !in stopWords }.toSet()
        val terms = tokens(query)
        if (terms.isEmpty()) return emptyList()
        return hits.asSequence().filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
            .filter { hit ->
                val title = tokens(hit.title)
                val body = title + tokens(hit.summary)
                fun covers(word: String, onPage: Set<String>) =
                    word in onPage || "${word}s" in onPage || word.removeSuffix("s") in onPage
                terms.all { covers(it, body) } && terms.any { covers(it, title) }
            }
            .distinctBy { it.url.substringBefore('#') }.take(limit).toList()
    }

    private fun decode(value: String): String = value.trim()
        .removePrefix("<![CDATA[").removeSuffix("]]>")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("<[^>]*>"), "")

    private fun parseRss(xml: String): List<Hit> =
        Regex("""<item>([\s\S]*?)</item>""").findAll(xml).take(40).map { item ->
            fun tag(name: String) = Regex("""<$name>([\s\S]*?)</$name>""")
                .find(item.groupValues[1])?.groupValues?.get(1)?.let(::decode).orEmpty()
            Hit(tag("title"), tag("link"), tag("description"))
        }.toList()

    suspend fun search(query: String, limit: Int = 5): ToolResult = withContext(Dispatchers.IO) {
        val topic = query.trim()
        if (topic.isEmpty()) return@withContext ToolResult(false, "Enter a topic to search for.")
        val anchor = topic.split(Regex("\\s+")).firstOrNull {
            it.length > 2 && it.lowercase() !in stopWords
        }
        val variants = listOf(topic, listOfNotNull(anchor?.let { "\"$it\"" }, topic).joinToString(" ")).distinct()
        for (variant in variants) {
            val url = "https://www.bing.com/search?q=" + URLEncoder.encode(variant, "UTF-8") + "&format=rss"
            val hits = try {
                val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) relevant(topic, parseRss(response.body?.string().orEmpty()), limit)
                    else emptyList()
                }
            } catch (_: java.io.IOException) {
                emptyList()
            }
            if (hits.isNotEmpty()) {
                return@withContext ToolResult(true, hits.joinToString("\n\n") {
                    val title = it.title.replace('[', '(').replace(']', ')')
                    val url = it.url.replace("(", "%28").replace(")", "%29")
                    "- [$title]($url) · ${it.summary.replace('\n', ' ').take(200)}"
                })
            }
        }
        ToolResult(false, "No matching sources for \"$topic\". Try a more specific query or share a link.")
    }

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return@withContext "Only http/https URLs are supported."
        }
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) NovaClaw").build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Fetch failed: HTTP ${response.code}"
                val html = response.body?.string().orEmpty()
                val text = html
                    .replace(Regex("""(?is)<(script|style|nav|footer|header)[^>]*>[\s\S]*?</\1>"""), " ")
                    .replace(Regex("""(?s)<[^>]+>"""), " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&")
                    .replace(Regex("""\s+"""), " ").trim()
                text.take(3000).ifBlank { "Page had no readable text." }
            }
        } catch (t: Throwable) {
            "Fetch failed: ${t.message?.take(120)}"
        }
    }
}
