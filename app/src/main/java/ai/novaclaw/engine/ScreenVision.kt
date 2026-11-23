package ai.novaclaw.engine

/** Bridge between the tool registry and the active model session's vision path. */
object ScreenVision {
    var ask: (suspend (question: String) -> String)? = null
}
