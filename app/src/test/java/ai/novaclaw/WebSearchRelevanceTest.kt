package ai.novaclaw

import ai.novaclaw.engine.WebSearchTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchRelevanceTest {
    private val generic = WebSearchTool.Hit("Latest News", "https://news.example/latest", "Headlines across India")
    private val docs = WebSearchTool.Hit("Gemini models", "https://ai.example/models", "Model IDs and release dates")

    @Test fun excludesTheUnrelatedNewsSeenInTheReportedFailure() {
        assertEquals(listOf(docs), WebSearchTool.relevant("latest gemini model", listOf(generic, docs), 5))
    }

    @Test fun aFeedWithOnlyUnrelatedHitsStaysEmpty() {
        assertTrue(WebSearchTool.relevant("latest gemini model", listOf(generic), 5).isEmpty())
    }

    @Test fun ignoresDuplicateLinksAndUnsafeSchemes() {
        val hits = listOf(docs, docs.copy(url = docs.url + "#details"), docs.copy(url = "javascript:alert(1)"))
        assertEquals(listOf(docs), WebSearchTool.relevant("gemini models", hits, 5))
    }
}
