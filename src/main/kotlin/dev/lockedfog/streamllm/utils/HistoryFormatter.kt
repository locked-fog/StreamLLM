package dev.lockedfog.streamllm.utils

import dev.lockedfog.streamllm.core.ChatMessage

interface HistoryFormatter {
    fun format(history: List<ChatMessage>): String

    companion object {
        // 预设默认格式
        val DEFAULT = SimpleFormatter(
            roleTemplates = mapOf(
                "user" to "User: {{content}}",
                "assistant" to "Assistant: {{content}}",
                "system" to "System: {{content}}"
            ),
            separator = "\n"
        )

        /**
         * 解析字符串格式: "user=Q: {{content}}; assistant=A: {{content}}; sep=\n"
         */
        fun fromString(format: String): HistoryFormatter {
            val parts = format.split(";")
            val map = mutableMapOf<String, String>()
            var sep = "\n"

            parts.forEach { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2) {
                    val key = kv[0].trim().lowercase()
                    val value = kv[1] // 保留 value 中的空格
                    if (key == "sep") {
                        // 处理常见转义符
                        sep = value.replace("\\n", "\n").replace("\\t", "\t")
                    } else {
                        map[key] = value
                    }
                }
            }
            return SimpleFormatter(map, sep)
        }
    }
}

class SimpleFormatter(
    private val roleTemplates: Map<String, String>,
    private val separator: String = "\n"
) : HistoryFormatter {

    override fun format(history: List<ChatMessage>): String {
        return history.mapNotNull { msg ->
            val template = roleTemplates[msg.role]
            // 如果没有定义该角色的模板，则忽略该消息（或者你可以选择 fallback 到默认格式）
                ?: return@mapNotNull null

            template.replace("{{content}}", msg.content)
        }.joinToString(separator)
    }
}