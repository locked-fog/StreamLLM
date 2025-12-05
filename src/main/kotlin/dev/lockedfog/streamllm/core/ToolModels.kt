package dev.lockedfog.streamllm.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- 发送端模型 (Request Models) ---

/**
 * 表示一个可供模型使用的工具定义。
 *
 * 对应 OpenAI API 中的 `tools` 数组项。
 *
 * @property type 工具类型，目前固定为 "function"。
 * @property function 具体的函数定义描述。
 */
@Serializable
data class Tool(
    val type: String = "function",
    val function: FunctionDefinition
)

/**
 * 函数定义的详细描述 (Schema)。
 *
 * 用于告诉模型该函数的功能、名称以及参数结构。
 *
 * @property name 函数名称。必须是字母、数字或下划线，且不能超过 64 个字符。
 * @property description 函数功能的自然语言描述。模型依据此描述决定何时调用该工具。
 * @property parameters 参数的 JSON Schema 结构。通常是一个 type 为 "object" 的 JSON 对象。
 */
@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement
)

// --- 接收端模型 (Response Models) ---

/**
 * 模型发起的工具调用请求对象。
 *
 * 对应 OpenAI API 响应中的 `tool_calls` 数组项。
 *
 * @property id 工具调用的唯一标识符 (Call ID)。
 * @property type 工具类型，目前通常为 "function"。
 * @property function 具体的函数调用执行信息。
 */
@Serializable
data class ToolCall(
    val id: String = "", // [Fix] 允许流式片段中为空
    val type: String = "function",
    val function: FunctionCall
)

/**
 * 函数调用执行详情。
 *
 * @property name 调用的函数名称。
 * @property arguments 函数参数的 JSON 字符串形式。
 * 注意：模型生成的 JSON 可能不完整或包含幻觉，使用前需校验。
 */
@Serializable
data class FunctionCall(
    val name: String = "",       // [Fix] 默认为空，适配流式分片
    val arguments: String = ""   // [Fix] 默认为空，适配流式分片
)
