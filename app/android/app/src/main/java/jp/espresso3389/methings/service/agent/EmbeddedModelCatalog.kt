package jp.espresso3389.methings.service.agent

data class EmbeddedModelSpec(
    val id: String,
    val label: String,
    val supportsToolCalling: Boolean,
    val supportsImageInput: Boolean,
    val supportsAudioInput: Boolean,
    val preferredBackend: String,
)

object EmbeddedModelCatalog {
    private val models = listOf(
        EmbeddedModelSpec(
            id = "gemma4-e2b-it",
            label = "Gemma4-E2B-it",
            supportsToolCalling = true,
            supportsImageInput = false,
            supportsAudioInput = false,
            preferredBackend = "aicore_preview",
        ),
    )

    fun find(id: String): EmbeddedModelSpec? {
        val key = id.trim().lowercase()
        return models.firstOrNull { it.id.equals(key, ignoreCase = true) }
    }
}
