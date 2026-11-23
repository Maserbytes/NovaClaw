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

    suspend fun search(query: String, limit: Int = 5): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext "Empty query."
        val url = "https://www.bing.com/search?q=" +
            URLEncoder.encode(query, "UTF-8") + "&format=rss"
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) NovaClaw")
                .build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Web search failed: HTTP ${response.code}"
                val xml = response.body?.string().orEmpty()
                val items = Regex("""<item>([\s\S]*?)</item>""").findAll(xml).take(limit)
                    .mapNotNull { m ->
                        val block = m.groupValues[1]
                        fun tag(name: String) = Regex("""<$name>([\s\S]*?)</$name>""")
                            .find(block)?.groupValues?.get(1)
                            ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim().orEmpty()
                        val title = tag("title")
                        val link = tag("link")
                        val desc = tag("description")
                        if (title.isBlank()) null else
                            listOf(title, link, desc).filter { it.isNotBlank() }.joinToString(" — ")
                    }.toList()
                if (items.isEmpty()) "No results found for: $query"
                else items.joinToString("\n\n")
            }
        } catch (t: Throwable) {
            "Web search failed: ${t.message?.take(120)}"
        }
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
