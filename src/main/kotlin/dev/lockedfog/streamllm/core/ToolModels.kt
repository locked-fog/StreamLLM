package dev.lockedfog.streamllm.core

import kotlinx.serialization.Serializable

/**
 * 模型发起的工具调用请求对象。
 *
 * 对应 OpenAI API 中的 `tool_calls` 数组项。
 *
 * @property id 工具调用的唯一标识符 (Call ID)。
 * @property type 工具类型，目前通常为 "function"。
 * @property function 具体的函数调用信息。
 */
@Serializable
data class ToolCall(
    val id: String = "", // [Fix] 允许流式片段中为空
    val type: String = "function",
    val function: FunctionCall
)

/**
 * 函数调用详情。
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
