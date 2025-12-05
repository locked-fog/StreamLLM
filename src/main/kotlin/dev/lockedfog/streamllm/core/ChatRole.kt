package dev.lockedfog.streamllm.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 表示对话消息中的角色 (Role)。
 *
 * 用于区分消息是由谁发送的，通常符合 OpenAI 的 Chat Completion API 标准。
 *
 * @property value 角色的字符串表示形式 (如 "user", "assistant")。
 */
@Serializable
enum class ChatRole(val value: String) {
    /**
     * 用户角色。代表向 LLM 发送请求的人类用户。
     */
    @SerialName("user")
    USER("user"),

    /**
     * 助手角色。代表 LLM 模型生成的回复。
     */
    @SerialName("assistant")
    ASSISTANT("assistant"),

    /**
     * 系统角色。通常用于设定 LLM 的行为模式、人设或上下文约束。
     */
    @SerialName("system")
    SYSTEM("system"),

    /**
     * 工具角色，用于向模型提交工具执行的结果
     */
    @SerialName("tool")
    TOOL("tool");

    override fun toString(): String = value
}