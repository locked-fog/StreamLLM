package dev.lockedfog.streamllm.core

/**
 * LLM 生成选项配置类。
 *
 * 用于控制模型生成的行为，如随机性、长度限制和停止条件等。
 * 所有参数均为可空，若为 null 则使用 Provider 或模型的默认配置。
 *
 * @property temperature 采样温度 (0.0 - 2.0)。较高的值 (如 0.8) 使输出更随机，较低的值 (如 0.2) 使其更集中和确定。
 * @property topP 核采样 (Nucleus Sampling) 概率。建议不要同时修改 temperature 和 topP。
 * @property maxTokens 生成的最大 Token 数。防止模型生成过长的文本。
 * @property stopSequences 停止序列列表。当模型生成这些字符串之一时，将停止生成。
 * @property frequencyPenalty 频率惩罚 (-2.0 - 2.0)。正值会根据新 token 在文本中出现的频率对其进行惩罚，降低模型逐字重复的可能性。
 * @property presencePenalty 存在惩罚 (-2.0 - 2.0)。正值会根据新 token 是否出现在文本中对其进行惩罚，增加模型谈论新主题的可能性。
 * @property modelNameOverride 临时覆盖默认模型名称。用于在单次请求中切换特定模型。
 */
data class GenerationOptions(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stopSequences: List<String>? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val modelNameOverride: String? = null
)