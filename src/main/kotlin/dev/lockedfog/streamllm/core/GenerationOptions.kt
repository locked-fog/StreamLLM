package dev.lockedfog.streamllm.core

data class GenerationOptions(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stopSequences: List<String>? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val modelNameOverride: String? = null
)