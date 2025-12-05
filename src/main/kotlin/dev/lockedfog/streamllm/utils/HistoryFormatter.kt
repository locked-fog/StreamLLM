package dev.lockedfog.streamllm.utils

import dev.lockedfog.streamllm.core.ChatContent
import dev.lockedfog.streamllm.core.ChatMessage
import dev.lockedfog.streamllm.core.ContentPart

/**
 * 历史记录格式化器接口。
 *
 * 用于将结构化的 [ChatMessage] 列表转换为单一的字符串，通常用于将历史记录注入到 Prompt 模版中。
 */
interface HistoryFormatter {
    /**
     * 将消息历史列表格式化为字符串。
     *
     * @param history 消息列表。
     * @return 格式化后的字符串。
     */
    fun format(history: List<ChatMessage>): String

    companion object {
        /**
         * 预设的默认格式化器。
         *
         * 格式为：
         * User: content
         * Assistant: content
         * System: content
         */
        val DEFAULT = SimpleFormatter(
            roleTemplates = mapOf(
                "user" to "User: {{content}}",
                "assistant" to "Assistant: {{content}}",
                "system" to "System: {{content}}"
            ),
            separator = "\n"
        )

        /**
         * 从字符串描述解析并创建格式化器。
         *
         * 字符串格式示例：`"user=Q: {{content}}; assistant=A: {{content}}; sep=\n"`
         *
         * 规则：
         * - 使用分号 `;` 分隔不同角色的配置。
         * - 使用等号 `=` 分隔键和值。
         * - 键为角色名称 (user, assistant, system) 或 `sep` (分隔符)。
         * - 值为模版内容，必须包含 `{{content}}` 占位符。
         * - `sep` 支持 `\n` 和 `\t` 转义。
         *
         * @param format 格式描述字符串。
         * @return [HistoryFormatter] 实例。
         */
        fun fromString(format: String): HistoryFormatter {
            val parts = format.split(";")
            val map = mutableMapOf<String, String>()
            var sep = "\n"

            parts.forEach { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) {
                    val key = kv[0].trim().lowercase()
                    val value = kv[1] // 保留值中的空格
                    if (key == "sep") {
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

/**
 * 简单的基于模版的格式化器实现。
 *
 * @property roleTemplates 角色到模版字符串的映射。
 * @property separator 消息之间的分隔符。
 */
class SimpleFormatter(
    private val roleTemplates: Map<String, String>,
    private val separator: String = "\n"
) : HistoryFormatter {

    override fun format(history: List<ChatMessage>): String {
        return history.mapNotNull { msg ->
            // 使用枚举的 value ("user", "assistant") 来查找对应的模板
            val template = roleTemplates[msg.role.value]
                ?: return@mapNotNull null

            val textContent = when (val c = msg.content) {
                is ChatContent.Text -> c.text
                is ChatContent.Parts -> c.parts
                    .filterIsInstance<ContentPart.TextPart>()
                    .joinToString("\n") { it.text }
            }

            template.replace("{{content}}",textContent)
        }.joinToString(separator)
    }
}