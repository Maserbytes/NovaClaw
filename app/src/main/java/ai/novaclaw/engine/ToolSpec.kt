package ai.novaclaw.engine

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
) {
    companion object {
        fun obj(
            description: String,
            properties: Map<String, String> = emptyMap(),
            required: List<String> = emptyList(),
        ): Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to properties.mapValues { (_, desc) -> mapOf("type" to "string", "description" to desc) },
            "required" to required,
        )
    }
}

data class ToolResult(
    val success: Boolean,
    val message: String,
    val verification: ai.novaclaw.a11y.ActionOutcome? = null,
)
